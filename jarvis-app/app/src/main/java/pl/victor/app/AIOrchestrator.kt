package pl.victor.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.victor.app.ai.AIProvider
import pl.victor.app.ai.AIProviderException
import pl.victor.app.ai.AIProviderFactory
import pl.victor.app.ai.AIResponse
import pl.victor.app.actions.Action
import pl.victor.app.diagnostics.DiagFormat
import pl.victor.app.actions.ActionConfirmation
import pl.victor.app.actions.ActionExecutor
import pl.victor.app.actions.ActionMode
import pl.victor.app.actions.ActionResult
import pl.victor.app.actions.ContactResolver
import pl.victor.app.actions.RouteAssist
import pl.victor.app.actions.DirectActionExecutor
import pl.victor.app.actions.SmartActionDetector
import pl.victor.app.audio.AudioManager
import pl.victor.app.audio.GlassesVoiceCapture
import pl.victor.app.conversation.OverheardSpeech
import pl.victor.app.conversation.WakePhrase
import pl.victor.app.ble.ButtonAction
import pl.victor.app.ble.ButtonActionDetector
import pl.victor.app.ble.ConnectionState
import pl.victor.app.ble.GlassesProtocol
import pl.victor.app.ble.VictorManager
import pl.victor.app.camera.BurstCaptureManager
import pl.victor.app.conversation.ConversationContext
import pl.victor.app.conversation.ConversationalMode
import pl.victor.app.data.HistoryRepository
import pl.victor.app.data.RemoteModelValidator
import pl.victor.app.data.SettingsRepository
import pl.victor.app.persona.Persona
import pl.victor.app.persona.PersonaRegistry
import pl.victor.app.storage.PhotoStorage
import pl.victor.app.vision.OCRReader
import pl.victor.app.vision.OCRResult
import pl.victor.app.vision.QRScanner
import pl.victor.app.vision.ScannedCode
import pl.victor.app.web.URLAnalyzer
import pl.victor.app.web.WebContent

/**
 * Orkiestrator - koordynuje cały flow:
 * 1. Trigger (przycisk, text input, wake word)
 * 2. Capture (5 zdjęć co 1s przez VictorManager)
 * 3. AI analysis (multimodal)
 * 4. TTS playback
 * 5. Zapis do historii
 */
class AIOrchestrator(
    private val context: Context,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val wakeWord: pl.victor.app.wakeword.WakeWordDetector? = null
) {
    /**
     * Ostatnia siatka bezpieczeństwa dla korutyn orkiestratora.
     *
     * Bez niej wyjątek, którego nie złapała żadna gałąź, leci do systemowego
     * handlera - czyli **wywraca aplikację**. Dla asystenta noszonego na głowie
     * to najgorszy możliwy wynik: telefon jest w kieszeni, użytkownik nie widzi
     * ekranu i nie ma jak się dowiedzieć, że coś się stało. Lepiej pokazać błąd
     * i wrócić do gotowości.
     *
     * Anulowanie przepuszczamy bez śladu - to normalne przerwanie tury, nie awaria.
     */
    private val coroutineErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
        if (error is kotlinx.coroutines.CancellationException) return@CoroutineExceptionHandler
        Log.e(TAG, "Nieobsłużony błąd w korutynie orkiestratora", error)
        _state.value = OrchestratorState.Error(
            "Coś poszło nie tak: " + (error.message ?: error::class.simpleName ?: "nieznany błąd")
        )
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + coroutineErrors)

    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val _lastResponse = MutableStateFlow<AIResponse?>(null)
    val lastResponse: StateFlow<AIResponse?> = _lastResponse.asStateFlow()

    // Ostrzeżenia o modelu (deprecated, migration, itd.)
    private val _modelWarning = MutableStateFlow<String?>(null)
    val modelWarning: StateFlow<String?> = _modelWarning.asStateFlow()

    // Aktualnie używany model
    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId.asStateFlow()

    // HeyCyan BLE manager (singleton z vendor SDK)
    private val glassesManager: VictorManager = VictorManager.getInstance(context)
    private val photoStorage: PhotoStorage = PhotoStorage(context)
    private val capture: BurstCaptureManager = BurstCaptureManager(
        context = context,
        photoStorage = photoStorage,
        glassesManager = glassesManager
    )

    // Power management - kontroluje co może działać
    val powerManager = pl.victor.app.power.PowerManager(context, settings)
    private val aiCache = pl.victor.app.ai.AIResponseCache()
    private val wakeLock = pl.victor.app.power.WakelockHelper(context)
    private val audio: AudioManager = AudioManager.getInstance(context)
    private val qrScanner: QRScanner = QRScanner()
    private val ocrReader: OCRReader = OCRReader()
    private val actionDetector = SmartActionDetector()
    private val productLookup = pl.victor.app.vision.ProductLookupClient()

    /**
     * Dziennik diagnostyczny. Leniwie, bo orkiestrator powstaje w
     * [pl.victor.app.VictorApplication.onCreate] - sięganie po `get()` w
     * konstruktorze trafiłoby w pole, którego jeszcze nie ma.
     */
    private val diag: pl.victor.app.diagnostics.DiagnosticLog
        get() = VictorApplication.get().diag

    /** Licznik zużycia tokenów - leniwie z tego samego powodu co [diag]. */
    private val usage: pl.victor.app.ai.UsageMeter
        get() = VictorApplication.get().usage
    private val actionExecutor = ActionExecutor(context)
    private val directActionExecutor = DirectActionExecutor(context)
    private val contactResolver = ContactResolver(context)
    private val urlAnalyzer = URLAnalyzer()
    private val translator = pl.victor.app.translation.SimultaneousTranslator()
    private val longTermMemory = pl.victor.app.memory.LongTermMemory(context, history)
    private val conversationContext = ConversationContext()
    private val captureModeSelector = pl.victor.app.camera.CaptureModeSelector()

    // Tryb konwersacyjny (continuous listening)
    /** Rozpoznawanie mowy - wspólne dla trybu konwersacyjnego i wybudzenia z okularów. */
    private val speechToText = pl.victor.app.conversation.SpeechToText(context)

    private val conversationalMode = ConversationalMode(
        audio = audio,
        wakeWord = wakeWord,
        speechToText = speechToText,
        onUserSpoke = { text -> handleSpokenText(text) },
        onActivated = { Log.i(TAG, "Tryb konwersacyjny ON") },
        onDeactivated = { Log.i(TAG, "Tryb konwersacyjny OFF") }
    ).apply {
        // Rozpoznawanie ma słuchać w tym języku, w którym użytkownik mówi.
        recognitionLanguageTag = languageTagFor(settings.getResponseLanguage())
    }

    // Accessibility - dla niewidomych/słabowidzących
    val accessibility = pl.victor.app.accessibility.AccessibilityService(
        audio = audio,
        ocrReader = ocrReader,
        glassesManager = glassesManager,
        onDescribeScene = { photoBytes ->
            // Opis sceny ze zdjęcia. Bez zmyślonych odległości - model ich nie zmierzy.
            val text = buildString {
                append("Opisz krótko co widać na tym zdjęciu. ")
                append("Używaj kierunków słownych (na wprost/po lewej/po prawej). ")
                append("Bliskość opisuj względnie (blisko, kilka kroków dalej) - ")
                append("NIE podawaj odległości w metrach. ")
                append("Jeśli obraz jest niewyraźny, powiedz to. ")
                append("Bez ozdobników, tylko fakty. 1-2 zdania po polsku.")
            }
            val provider = getOrCreateProvider()
            val response = provider.analyze(
                textQuestion = text,
                images = listOf(photoBytes),
                audioBytes = null,
                scannedCodes = emptyList(),
                enableWebSearch = false,
                systemPrompt = ACCESSIBILITY_SYSTEM_PROMPT
            )
            // Tryby ciągłe omijają główną drogę tury, więc zużycie doliczamy
            // tutaj - inaczej najdroższa część aplikacji byłaby jedyną, której
            // w liczniku nie widać.
            usage.record(response.tokensUsed)
            response.text
        },
        onNavigate = { photoBytes ->
            // Opis drogi ze zdjęcia - pomoc uzupełniająca, nie system bezpieczeństwa.
            val text = buildString {
                append("Co widać na drodze przed osobą idącą? ")
                append("Schody, krawężnik, słupek, drzwi, przeszkoda? ")
                append("Odpowiedz krótko, np. \"Na wprost schody w dół\" albo ")
                append("\"Nie widzę wyraźnie\". ")
                append("NIE podawaj odległości w metrach i NIE mów, że droga jest wolna ")
                append("ani że można bezpiecznie iść - nie masz do tego podstaw.")
            }
            val provider = getOrCreateProvider()
            val response = provider.analyze(
                textQuestion = text,
                images = listOf(photoBytes),
                enableWebSearch = false,
                systemPrompt = ACCESSIBILITY_SYSTEM_PROMPT
            )
            usage.record(response.tokensUsed)
            response.text
        }
    )

    /**
     * Publiczny dostęp do trybu konwersacyjnego (UI/Settings).
     */
    val conversationalModeFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = conversationalMode.enabled
    val isListeningFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = conversationalMode.isListening

    /**
     * Zamienia kod języka odpowiedzi na tag BCP-47 dla rozpoznawania mowy.
     * `SpeechRecognizer` oczekuje pełnego tagu z regionem - samo "pl" bywa
     * ignorowane i schodzi na język systemu.
     */
    /**
     * Rozwiązuje nazwę kontaktu na numer telefonu dla SendSms/MakeCall.
     * Inne typy akcji przechodzą bez zmian.
     *
     * @return akcja z numerem zamiast nazwy, ta sama akcja gdy `to` już jest
     *         numerem, albo `null` gdy kontaktu nie udało się znaleźć
     */
    private suspend fun resolveContactIfNeeded(action: Action): Action? = when (action) {
        is Action.SendSms -> {
            if (contactResolver.isPhoneNumber(action.to)) {
                action
            } else {
                // "smsto:" nie ma żadnego mechanizmu wyszukiwania po nazwie -
                // bez rozwiązania SMS nigdy by nie doszedł do adresata, więc
                // lepiej zgłosić to wprost niż cicho otworzyć aplikację SMS
                // z odbiorcą, którego nikt nie rozpozna.
                contactResolver.findContact(action.to)?.let { found ->
                    noteResolvedContact(action.to, found)
                    action.copy(to = found.phoneNumber, resolvedName = found.displayName)
                }
            }
        }
        is Action.MakeCall -> {
            if (contactResolver.isPhoneNumber(action.to)) {
                action
            } else {
                // ACTION_DIAL z nazwą czasem trafia w wyszukiwanie T9 dialera
                // (wpisanie liter na klawiaturze telefonu też sugeruje kontakty).
                // Gdy nie mamy dostępu do książki albo kontaktu nie ma, zostawiamy
                // oryginalną nazwę zamiast twardo failować - to jedyna ścieżka,
                // która wcześniej działała bez READ_CONTACTS.
                contactResolver.findContact(action.to)?.let { found ->
                    noteResolvedContact(action.to, found)
                    action.copy(to = found.phoneNumber, resolvedName = found.displayName)
                } ?: action
            }
        }
        else -> action
    }

    /**
     * Zapisuje w dzienniku, KOGO wybraliśmy z książki adresowej.
     *
     * ## Czemu to musi zostawiać ślad
     * Bo pomyłka w tym miejscu kończy się rozmową albo SMS-em do obcej osoby, a
     * z zewnątrz wygląda dokładnie tak samo jak trafienie: asystent mówi „dzwonię
     * do Janusza" i dzwoni. Numer jest w dzienniku ukrywany - wystarczy sama
     * nazwa, żeby dało się rozstrzygnąć, czy wybór był dobry.
     */
    private fun noteResolvedContact(asked: String, found: ContactResolver.Resolved) {
        runCatching {
            diag.event(
                DiagFormat.Phase.AKCJA, "kontakt z książki adresowej",
                mapOf("proszono" to asked, "wybrano" to found.displayName)
            )
        }
    }

    /** Ile milisekund dźwięku to tyle bajtów próbek 16-bit mono. */
    private fun msOf(pcmBytes: Int): Long =
        pcmBytes.toLong() * 1000L / (pl.victor.app.audio.OpusDecoder.SAMPLE_RATE.toLong() * 2L)

    /**
     * Zapisuje bieżącą pozycję pod nazwą.
     *
     * Bez uprawnienia albo bez ustalonej pozycji mówimy to WPROST. Cicha
     * porażka byłaby tu najgorsza z możliwych: człowiek odchodzi przekonany, że
     * miejsce jest zapamiętane, i dowiaduje się, że nie, dopiero gdy wraca.
     */
    private fun handlePlaceSave(name: String) {
        scope.launch {
            val position = pl.victor.app.proactive.LocationContext.currentPosition(context)
            if (position == null) {
                val message = "Nie znam swojego położenia, więc nie zapamiętam tego miejsca. " +
                    "Sprawdź, czy aplikacja ma dostęp do lokalizacji."
                audio.speak(message, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(message)
                return@launch
            }
            // ZDJĘCIE MIEJSCA - pomysł z terenu i lepszy, niż wygląda.
            //
            // W garażu podziemnym GPS nie widzi nieba, więc współrzędne są tam
            // warte tyle co nic - a to jest DOKŁADNIE to miejsce, w którym
            // ludzie gubią samochód. Napis "POZIOM -2, SEKTOR B" rozwiązuje
            // sprawę natychmiast.
            //
            // Litery rozpoznaje telefon (ML Kit), więc nie kosztuje to ani
            // jednego tokenu. Zdjęcie idzie przy okazji do galerii telefonu,
            // żeby dało się na nie po prostu spojrzeć.
            //
            // NIE PYTAMY O ZGODĘ przed zrobieniem zdjęcia z rozmysłu: cała ta
            // funkcja ma kosztować jedno zdanie w chwili odchodzenia, a
            // dopytywanie zamieniłoby ją w rozmowę. Gdy okularów nie ma,
            // zapisujemy same współrzędne i nic się nie psuje.
            val sign = capturePlaceSign(name)

            settings.savePlace(
                name, position.first, position.second, System.currentTimeMillis(), sign
            )
            diag.event(
                DiagFormat.Phase.SESJA, "miejsce zapamiętane",
                mapOf("nazwa" to name, "napis" to (sign ?: "-"))
            )
            val message = if (sign != null) {
                "Zapamiętane, ze zdjęciem. Widzę napis: $sign."
            } else {
                "Zapamiętane."
            }
            audio.speak(message, language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Completed(message)
        }
    }

    /**
     * Robi zdjęcie miejsca i wyciąga z niego oznaczenie - albo `null`.
     *
     * Wszystko tu jest "najlepiej jak się da": brak okularów, nieudane zdjęcie
     * i brak tekstu na zdjęciu dają ten sam wynik co brak funkcji, czyli sam
     * zapis współrzędnych. Zapamiętanie miejsca nie może się nie udać z powodu
     * dodatku do niego.
     */
    private suspend fun capturePlaceSign(name: String): String? {
        if (glassesManager.connectionState.value != ConnectionState.READY) return null
        val photo = runCatching {
            glassesManager.liveFrame(detail = true) ?: glassesManager.capturePhoto()
        }.getOrNull() ?: return null

        runCatching {
            pl.victor.app.memory.PlacePhoto.saveToGallery(context, photo, name)
        }.onFailure { Log.w(TAG, "Nie udało się zapisać zdjęcia miejsca", it) }

        val ocr = runCatching { ocrReader.readBytes(photo) }.getOrNull() ?: return null
        if (!ocr.isSuccess) return null
        return pl.victor.app.memory.PlaceMemory.tidySign(ocr.fullText)
    }

    /** Mówi, gdzie stoi zapamiętane miejsce względem bieżącej pozycji. */
    private fun handlePlaceRecall(name: String) {
        scope.launch {
            val place = settings.getPlace(name)
            if (place == null) {
                val message = "Nie mam zapamiętanego takiego miejsca. Powiedz " +
                    "\"zapamiętaj, gdzie zaparkowałem\", gdy będziesz wychodzić."
                audio.speak(message, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(message)
                return@launch
            }
            val position = pl.victor.app.proactive.LocationContext.currentPosition(context)
            if (position == null) {
                val message = "Znam zapamiętane miejsce, ale nie wiem, gdzie jestem teraz, " +
                    "więc nie powiem, w którą stronę iść."
                audio.speak(message, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(message)
                return@launch
            }
            val message = pl.victor.app.memory.PlaceMemory.describe(
                place = place,
                here = pl.victor.app.memory.PlaceMemory.Here(position.first, position.second),
                nowMs = System.currentTimeMillis()
            )
            audio.speak(message, language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Completed(message)
        }
    }

    private fun languageTagFor(languageCode: String): String = when (languageCode) {
        "pl" -> "pl-PL"
        "en" -> "en-US"
        "de" -> "de-DE"
        "fr" -> "fr-FR"
        "es" -> "es-ES"
        "it" -> "it-IT"
        "uk" -> "uk-UA"
        else -> languageCode
    }

    /**
     * Wydarzenia z kalendarza urządzenia jako kontekst dla modelu.
     *
     * Czyta kalendarz systemowy (dowolny zsynchronizowany, w tym Google), więc
     * nie wymaga logowania OAuth. Wcześniej kalendarz był odczytywany wyłącznie
     * przez alerty pogodowe - V.I.C.T.O.R. nie potrafił odpowiedzieć na „co mam dziś
     * w planach", mimo że dane były na wyciągnięcie ręki.
     *
     * @return fragment promptu albo `null`, gdy pytanie nie dotyczy planów,
     *         brakuje uprawnienia albo nie ma nadchodzących wydarzeń
     */
    /**
     * Bieżąca data i godzina jako kontekst dla modelu.
     *
     * Model nie ma zegara, a jego wiedza kończy się na dacie treningu - bez tego
     * "jaki dziś dzień", "ile zostało do piątku" albo "umów spotkanie na jutro"
     * są zgadywaniem, podanym tym samym pewnym tonem co prawdziwa odpowiedź.
     *
     * Format ISO obok zapisu słownego, bo ten sam blok obsługuje dwie różne
     * potrzeby: człowiek pyta "jaki dziś dzień", a znacznik
     * `[[ACTION: type=create_calendar_event start=...]]` potrzebuje daty, którą
     * da się sparsować bez zgadywania (patrz [SmartActionDetector.parseStartTime]).
     *
     * Doklejany ZAWSZE - w odróżnieniu od pogody czy kalendarza nie da się
     * wykryć słowami kluczowymi, kiedy jest potrzebny ("ile mam czasu?",
     * "zdążę?", "jutro" w środku zdania), a kosztuje dwie linijki promptu.
     */
    private fun buildTimeContext(): String {
        val now = java.time.ZonedDateTime.now()
        val polish = java.util.Locale("pl", "PL")
        val spoken = now.format(
            java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", polish)
        )
        return buildString {
            append("=== TERAZ ===\n")
            append(spoken).append('\n')
            append("ISO: ").append(
                now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            ).append(" (strefa ").append(now.zone.id).append(")\n")
            // Gotowe kotwice zamiast liczenia. Modele mylą się w arytmetyce
            // kalendarzowej (przełomy miesięcy, lata przestępne) znacznie
            // częściej niż w czytaniu podanej daty - a "co zapisałem wczoraj"
            // rozstrzyga się właśnie na tej jednej dacie.
            val day = java.time.format.DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd", polish)
            append("wczoraj = ").append(now.minusDays(1).format(day)).append('\n')
            append("jutro = ").append(now.plusDays(1).format(day)).append('\n')
        }
    }

    /**
     * Sekcja kontekstu mówiąca, DLACZEGO danych nie ma i co z tym zrobić.
     *
     * ## Dlaczego to nie może być ciche `null`
     * Gdy użytkownik pyta o kalendarz, a my po cichu nie doklejamy danych,
     * model dostaje samo pytanie i odpowiada po swojemu - "nie mam dostępu do
     * Twojego kalendarza". Brzmi jak awaria aplikacji, a jest brakiem jednej
     * zgody, którą da się kliknąć w trzydzieści sekund. Zgłoszono to dwa razy:
     * raz o pogodę, raz o kalendarz.
     *
     * Wersja z powodem zamienia bezradną odpowiedź w instrukcję.
     *
     * @param force gdy prawda, sekcja jest pomijana - briefing zbiera dane
     *   automatycznie i nie ma komu tłumaczyć, czego brakuje
     */
    private fun missingContext(section: String, reason: String, force: Boolean): String? {
        if (force) return null
        return "=== $section ===\nNIE MAM TYCH DANYCH: $reason\n" +
            "Powiedz to użytkownikowi wprost i krótko, jednym zdaniem. " +
            "Nie zmyślaj danych i nie twierdź, że nie masz takiej funkcji."
    }

    /**
     * Notatki jako kontekst dla modelu.
     *
     * Osobno od odczytania na żądanie ([pl.victor.app.notes.Notes.isListRequest]):
     * tam użytkownik chce USŁYSZEĆ listę, tu chce ODPOWIEDZI. "Czy mam coś do
     * kupienia?" albo "co miałem zrobić w piątek?" ma dostać zdanie, a nie
     * wyliczankę wszystkiego po kolei.
     */
    private fun buildNotesContext(question: String, force: Boolean = false): String? {
        if (!force && !pl.victor.app.notes.Notes.mentionsNotes(question) &&
            !openContextTopics.contains(TOPIC_NOTES)
        ) {
            return null
        }
        openContextTopics.add(TOPIC_NOTES)
        return pl.victor.app.notes.Notes.buildPromptContext(settings.getNotes())
            ?.also { Log.i(TAG, "Doklejam notatki użytkownika") }
    }

    /**
     * Jednorazowe pytanie do modelu: bez zdjęć, bez historii rozmowy, bez
     * mówienia na głos i bez ruszania stanu tury.
     *
     * Służy zadaniom pomocniczym - uporządkowaniu notatki, jej streszczeniu -
     * czyli rzeczom, które mają się wydarzyć OBOK rozmowy, a nie zamiast niej.
     * Dlatego nie przechodzi przez [handleUserTrigger] i nie zajmuje
     * asystenta: użytkownik może w tym czasie zadać zwykłe pytanie.
     *
     * @return odpowiedź modelu albo `null`, gdy się nie udała - wołający MUSI
     *   umieć bez niej żyć
     */
    suspend fun askModelPlain(prompt: String): String? = try {
        getOrCreateProvider().analyze(
            textQuestion = prompt,
            images = emptyList(),
            enableWebSearch = false,
            systemPrompt = PLAIN_TASK_SYSTEM_PROMPT
        ).text.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "Pomocnicze pytanie do modelu nie powiodło się", e)
        null
    }

    /**
     * Porządkuje świeżo zapisaną notatkę, gdy użytkownik tak wybrał w
     * ustawieniach.
     *
     * Notatka jest już zapisana dosłownie - to jest podmiana, a nie zapis, i
     * każde niepowodzenie po prostu zostawia oryginał. Szukamy jej po TREŚCI,
     * nie po indeksie: w międzyczasie mogła dojść kolejna i przesunąć listę.
     */
    private suspend fun tidyNote(original: String) {
        val tidied = askModelPlain(pl.victor.app.notes.Notes.tidyPrompt(original))
        val accepted = pl.victor.app.notes.Notes.acceptTidied(original, tidied)
        if (accepted == original) {
            Log.i(TAG, "Notatka zostaje w oryginale")
            return
        }
        val index = settings.getNotes().indexOfFirst { it.text == original }
        if (index < 0) return
        settings.updateNote(index, accepted)
        Log.i(TAG, "Notatka uporządkowana przez model")
    }

    private suspend fun buildCalendarContext(question: String, force: Boolean = false): String? {
        // `force` obchodzi bramkę słów kluczowych - używa go briefing, który
        // ma zebrać wszystko, o co użytkownik poprosił w ustawieniach, a nie
        // to, co akurat wynika z brzmienia pytania.
        if (!force && !pl.victor.app.proactive.CalendarContext.isAboutSchedule(question) &&
            !openContextTopics.contains(TOPIC_CALENDAR)
        ) {
            return null
        }
        openContextTopics.add(TOPIC_CALENDAR)

        // DWIE DROGI DO KALENDARZA, BO SĄ DWA RÓŻNE KALENDARZE.
        //
        // Kalendarz URZĄDZENIA czyta się przez dostawcę treści Androida i wymaga
        // uprawnienia READ_CALENDAR. Kalendarz GOOGLE czyta się przez API i wymaga
        // połączonego konta. To są niezależne warunki, a do tej pory odczyt
        // korzystał wyłącznie z pierwszej drogi - podczas gdy TWORZENIE wydarzeń
        // szło drugą.
        //
        // Stąd zgłoszenie: "AI mówi, że nie ma dostępu do kalendarza Google, ale
        // potrafi utworzyć wydarzenie". Obie rzeczy były prawdą naraz.
        val calendar = pl.victor.app.proactive.CalendarService(context)
        val deviceEvents = if (calendar.hasPermission()) {
            runCatching { calendar.getUpcomingEvents(limit = 8, hoursAhead = 48) }
                .onFailure { Log.w(TAG, "Odczyt kalendarza urządzenia nie powiódł się", it) }
                .getOrDefault(emptyList())
        } else {
            Log.d(TAG, "Brak uprawnienia READ_CALENDAR - próbuję kalendarza Google")
            emptyList()
        }

        // Google dopytujemy, gdy urządzenie nic nie dało. Nie zawsze, bo to sieć:
        // przy działającym kalendarzu urządzenia byłby to koszt bez zysku.
        val events = deviceEvents.ifEmpty {
            val google = pl.victor.app.calendar.GoogleCalendarService(context)
            if (!google.isSignedIn()) {
                emptyList()
            } else {
                runCatching {
                    pl.victor.app.calendar.GoogleEventBridge
                        .toDeviceEvents(google.getUpcomingEvents(maxResults = 8))
                }
                    .onFailure { Log.w(TAG, "Odczyt Kalendarza Google nie powiódł się", it) }
                    .getOrDefault(emptyList())
                    .also { if (it.isNotEmpty()) Log.i(TAG, "Kalendarz z konta Google: ${it.size}") }
            }
        }

        if (events.isEmpty()) {
            // Rozróżniamy BRAK DOSTĘPU od PUSTEGO KALENDARZA - to zupełnie inne
            // rady dla użytkownika, a do tej pory obie brzmiały tak samo.
            val googleConnected =
                runCatching { pl.victor.app.calendar.GoogleCalendarService(context).isSignedIn() }
                    .getOrDefault(false)
            if (!calendar.hasPermission() && !googleConnected) {
                return missingContext(
                    section = "KALENDARZ",
                    reason = "nie mam dostępu do żadnego kalendarza. Trzeba albo włączyć " +
                        "zgodę na kalendarz w Ustawieniach aplikacji (kalendarz telefonu), " +
                        "albo podłączyć konto Google w Ustawieniach V.I.C.T.O.R.",
                    force = force
                )
            }
            Log.i(TAG, "Kalendarz dostępny, ale pusty w najbliższych 48 h")
            return missingContext(
                section = "KALENDARZ",
                reason = "kalendarz jest dostępny, ale nie ma w nim nic w najbliższych " +
                    "dwóch dobach. To NIE jest usterka - powiedz po prostu, że nic nie ma.",
                force = force
            )
        }

        return pl.victor.app.proactive.CalendarContext.buildPromptContext(events)
            ?.also { Log.i(TAG, "Doklejam ${events.size} wydarzeń z kalendarza") }
    }

    /**
     * Prognoza pogody jako kontekst dla modelu.
     *
     * Do tej pory pogoda żyła wyłącznie w alertach w tle: aplikacja sprawdzała
     * ją co jakiś czas i wysyłała powiadomienie, gdy coś było nie tak. Zapytana
     * wprost - "jaka jest pogoda?" - odpowiadała z pamięci modelu, czyli
     * ZMYŚLAŁA. Teraz pytania o pogodę dostają prawdziwe dane.
     *
     * Wymaga klucza OpenWeatherMap i lokalizacji z ustawień; bez nich po prostu
     * nic nie dokleja, zamiast wywracać odpowiedź.
     */
    private suspend fun buildWeatherContext(question: String, force: Boolean = false): String? {
        // `force` obchodzi bramkę słów kluczowych - używa go briefing, który
        // ma zebrać wszystko, o co użytkownik poprosił w ustawieniach, a nie
        // to, co akurat wynika z brzmienia pytania.
        if (!force && !pl.victor.app.proactive.WeatherContext.isAboutWeather(question) &&
            !openContextTopics.contains(TOPIC_WEATHER)
        ) {
            return null
        }
        openContextTopics.add(TOPIC_WEATHER)

        val apiKey = settings.getOpenWeatherApiKey()
        if (apiKey.isBlank()) {
            Log.d(TAG, "Pytanie o pogodę, ale brak klucza OpenWeatherMap")
            return missingContext(
                section = "POGODA",
                reason = "brakuje klucza OpenWeatherMap. Trzeba go wpisać w Ustawieniach.",
                force = force
            )
        }
        val place = settings.getWeatherLocation()
        if (place.isBlank()) {
            Log.d(TAG, "Pytanie o pogodę, ale brak ustawionej lokalizacji")
            return missingContext(
                section = "POGODA",
                reason = "nie ustawiono miejscowości. Trzeba ją wpisać w Ustawieniach.",
                force = force
            )
        }
        return try {
            val service = pl.victor.app.proactive.WeatherService(apiKey)
            val geo = service.geocode(place) ?: return null
            val forecast = service.getForecast(geo.lat, geo.lon)
            val air = runCatching { service.getAirQuality(geo.lat, geo.lon) }.getOrNull()
            pl.victor.app.proactive.WeatherContext.buildPromptContext(forecast, air)
                ?.also { Log.i(TAG, "Doklejam prognozę pogody dla $place") }
        } catch (e: Exception) {
            // Padnięte API pogodowe nie może wywrócić odpowiedzi na pytanie.
            Log.w(TAG, "Pobranie pogody nie powiodło się", e)
            null
        }
    }

    /**
     * Ostatnie maile jako kontekst dla modelu.
     *
     * Tak jak kalendarz - doklejane tylko gdy pytanie faktycznie dotyczy
     * poczty. Wymaga połączonego konta Google (patrz [pl.victor.app.google.GoogleAccountManager]);
     * bez tego po prostu nic nie dokleja, zamiast pokazywać błąd.
     *
     * @return fragment promptu albo `null`, gdy pytanie nie dotyczy maili,
     *         konto nie jest połączone albo nie ma żadnych wiadomości
     */
    private suspend fun buildGmailContext(question: String, force: Boolean = false): String? {
        // `force` obchodzi bramkę słów kluczowych - używa go briefing, który
        // ma zebrać wszystko, o co użytkownik poprosił w ustawieniach, a nie
        // to, co akurat wynika z brzmienia pytania.
        if (!force && !pl.victor.app.proactive.GmailContext.isAboutEmail(question) &&
            !openContextTopics.contains(TOPIC_MAIL)
        ) {
            return null
        }
        openContextTopics.add(TOPIC_MAIL)

        val gmail = pl.victor.app.google.GmailService(context)
        // WYGASŁE logowanie przed "niepołączonym": od chwili wykrycia wygaśnięcia
        // konto liczy się jako niezalogowane, więc bez tej gałęzi użytkownik
        // usłyszałby "podłącz konto" - a on je podłączył i nic nie zrobił źle.
        // Rada jest zresztą inna: nie ma czego konfigurować, trzeba kliknąć jeszcze raz.
        if (pl.victor.app.google.GoogleAccountManager.isLoginExpired()) {
            Log.d(TAG, "Pytanie o maile, ale logowanie Google wygasło")
            return missingContext(
                section = "POCZTA",
                reason = "logowanie Google wygasło i trzeba je odnowić w Ustawieniach. " +
                    "Google unieważnia je co siedem dni, dopóki aplikacja jest w trybie " +
                    "testowym - to normalne, nie usterka.",
                force = force
            )
        }
        if (!gmail.isSignedIn()) {
            Log.d(TAG, "Pytanie o maile, ale brak połączonego konta Google")
            return missingContext(
                section = "POCZTA",
                reason = "konto Google nie jest połączone. Trzeba je podłączyć w Ustawieniach.",
                force = force
            )
        }
        if (!gmail.hasAccess()) {
            // Konto jest połączone, ale poczta wymaga OSOBNEJ zgody. Bez tej gałęzi
            // model dostawał pustą listę i odpowiadał, że nie ma nowych maili albo
            // że nie umie ich czytać - obie odpowiedzi nieprawdziwe.
            Log.d(TAG, "Pytanie o maile, ale konto nie ma zgody na pocztę")
            return missingContext(
                section = "POCZTA",
                reason = "poczta wymaga osobnej zgody Google. Użytkownik włącza ją " +
                    "w Ustawieniach, w karcie konta Google, przyciskiem \"Włącz pocztę\".",
                force = force
            )
        }
        return try {
            val messages = gmail.getRecentMessages(maxResults = 8)
            pl.victor.app.proactive.GmailContext.buildPromptContext(messages)
                ?.also { Log.i(TAG, "Doklejam ${messages.size} maili") }
        } catch (e: Exception) {
            // Brak dostępu do Gmaila nie może wywrócić odpowiedzi na pytanie.
            Log.w(TAG, "Odczyt Gmaila nie powiódł się", e)
            null
        }
    }

    /**
     * Czy trwa rozmowa, w której wolno dopowiadać BEZ frazy wybudzenia.
     *
     * Okno otwiera świadome działanie człowieka (fraza wybudzenia, włączenie
     * nasłuchu) i każda skończona tura - bo „a ile to kosztuje?" tuż po
     * odpowiedzi jest normalną kontynuacją, a wymaganie tam frazy byłoby
     * uciążliwe. Poza oknem cisza jest bezpieczniejsza niż odpowiadanie na
     * cudzą rozmowę.
     */
    private fun conversationOpen(): Boolean =
        System.currentTimeMillis() - conversationOpenedAtMs < CONVERSATION_WINDOW_MS

    @Volatile
    private var conversationOpenedAtMs = 0L

    /** Otwiera okno dopowiedzi - patrz [conversationOpen]. */
    private fun openConversationWindow() {
        conversationOpenedAtMs = System.currentTimeMillis()
    }

    /**
     * @param afterUserAction czy nasłuch zaczyna się od ŚWIADOMEGO działania
     *   człowieka (fraza wybudzenia, przełącznik w ustawieniach). Uruchomienie
     *   aplikacji nim NIE JEST - i właśnie dlatego ten parametr istnieje:
     *   inaczej asystent odpowiada na wszystko, co usłyszy po starcie.
     */
    fun enableConversationalMode(afterUserAction: Boolean = true) {
        if (afterUserAction) openConversationWindow()
        // Język mógł się zmienić w ustawieniach od czasu utworzenia orkiestratora.
        conversationalMode.recognitionLanguageTag =
            languageTagFor(settings.getResponseLanguage())
        conversationalMode.enable()
        // Ten sam sygnał, co przy pojedynczej turze - użytkownik ma jeden znak
        // "mów teraz", niezależnie od tego, którym trybem trafił do nasłuchu.
        audio.playListeningCue()
    }

    fun disableConversationalMode() = conversationalMode.disable()

    // Akcja oczekująca na potwierdzenie (null = nic nie czeka)
    private val _pendingActionConfirmation = MutableStateFlow<PendingActionConfirmation?>(null)
    val pendingActionConfirmation: StateFlow<PendingActionConfirmation?> =
        _pendingActionConfirmation.asStateFlow()
    private val buttonDetector = ButtonActionDetector()


    /** Korutyna bieżącej tury - do przerwania przez [cancelCurrentTurn]. */
    private var activeTurnJob: kotlinx.coroutines.Job? = null

    /**
     * Kiedy stan ostatnio się zmienił - do wykrywania tury, która utknęła.
     *
     * Zgłoszone jako "po jakimś czasie AI przestaje odpowiadać, jakby się
     * zatykało". Tak właśnie było: [claimIdle] przepuszcza tylko stan końcowy,
     * więc tura, która zginęła bez ustawienia takiego stanu - anulowana
     * korutyna, wyjątek na nieoczekiwanej drodze, urwane połączenie w środku
     * strumienia - zostawiała `Thinking` albo `Listening` NA ZAWSZE. Od tego
     * momentu każde kolejne pytanie było odrzucane wpisem "Already processing"
     * i jedynym ratunkiem był restart aplikacji.
     */
    @Volatile
    private var stateChangedAtMs = System.currentTimeMillis()

    /**
     * Tematy, do których model dostał już dane w tej rozmowie.
     *
     * ## Po co
     * Kontekst doklejamy tylko wtedy, gdy PYTANIE pasuje do wzorca - inaczej
     * każde zapytanie ciągnęłoby pogodę, kalendarz i pocztę. Ale rozmowa idzie
     * dalej: po "jaka jest pogoda" pada "a jutro?" albo "to brać kurtkę?", a te
     * do wzorca nie pasują. Model dostawał wtedy pytanie BEZ danych i odpowiadał,
     * że nie ma dostępu do aktualnej pogody - minutę po tym, jak ją podał.
     * Zgłoszono to dokładnie tak.
     *
     * Raz otwarty temat zostaje więc otwarty do końca rozmowy. Czyści go "nowy
     * temat" - tak samo jak historię.
     */
    /**
     * Zbiór współbieżny, nie zwykły [mutableSetOf].
     *
     * Odkąd konteksty (kalendarz, poczta, pogoda, notatki) zbierane są
     * RÓWNOLEGLE, dopisują się do niego z kilku korutyn naraz - a zwykły
     * HashSet potrafi się przy tym trwale uszkodzić.
     */
    private val openContextTopics: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())


    private var currentProvider: AIProvider? = null
    private var currentProviderId: String? = null
    private var activeModelId: String? = null

    init {
        // Jedno miejsce, w którym mierzymy wiek stanu - patrz [stateChangedAtMs].
        scope.launch { _state.collect { stateChangedAtMs = System.currentTimeMillis() } }

        // Preferencja mikrofonu okularów musi trafić do routera PRZED pierwszą
        // turą - inaczej wyłączenie działałoby dopiero po restarcie aplikacji.
        audio.setGlassesMicEnabled(settings.isGlassesMicEnabled())

        // Nasłuch trybu konwersacyjnego wraca ZAWSZE, gdy tura się kończy.
        // Wcześniej zależało to od tego, czy dana ścieżka wyjścia pamiętała o
        // onAiFinishedSpeaking() - a wyjść jest kilkanaście: brak klucza API,
        // odłączone okulary, nieudane zdjęcie, akcja z warstwy 0. Każde
        // zapomnienie zostawiało tryb konwersacyjny głuchym na stałe, bo nasłuch
        // wstrzymywaliśmy PRZED turą, a wznawiali dopiero po odpowiedzi.
        // Sterowanie stanem końcowym zamyka tę dziurę raz, dla wszystkich ścieżek.
        //
        // Celowo bez stanu Idle: startVoiceTurn ustawia go W TRAKCIE tury, zaraz
        // po nasłuchu, więc wznowienie w tym miejscu wysyłałoby mikrofon po
        // kolejne pytanie, gdy model dopiero zaczyna odpowiadać.
        scope.launch {
            _state.collect { current ->
                if (current is OrchestratorState.Completed ||
                    current is OrchestratorState.Error
                ) {
                    conversationalMode.onAiFinishedSpeaking()
                    // Zamknięcie wpisu w dzienniku tą samą drogą i z tego
                    // samego powodu co wznowienie nasłuchu: wyjść jest
                    // kilkanaście i każde zapomniane zostawiało turę otwartą.
                    // W dzienniku z 21:55 widać to na turze 0066 („zapamiętaj,
                    // że mam na imię...") - jest „pytanie", nie ma „KONIEC
                    // TURY", a kolejne wiersze liczą czas od tury, która dawno
                    // się skończyła. Powtórne wywołanie jest nieszkodliwe.
                    runCatching {
                        diag.endTurn(
                            if (current is OrchestratorState.Error) "błąd" else "odpowiedziano"
                        )
                    }
                    // Okulary dostają po nasłuchu "koniec sesji AI" - to samo
                    // polecenie gasi im wykrywanie frazy, a włączenie szło RAZ,
                    // w powitaniu po połączeniu. Bez przypomnienia kolejne
                    // "hej lens" nie ma do czego trafić, choć przycisk w
                    // aplikacji działa dalej - i dokładnie tak to zgłoszono.
                    //
                    // Tutaj, a nie w finally tury: finally startVoiceTurn
                    // wykonuje się, ZANIM odpowiedź zostanie wypowiedziana
                    // (właściwa tura leci własną korutyną), więc okulary
                    // zaczęłyby nasłuchiwać w trakcie mówienia i usłyszały
                    // własny głos asystenta.
                    runCatching { glassesManager.rearmGlassesWakeWord("koniec tury") }
                    // Skończona tura otwiera okno dopowiedzi: przez najbliższą
                    // chwilę „a ile to kosztuje?" liczy się bez frazy
                    // wybudzenia - patrz `conversationOpen`.
                    openConversationWindow()
                }
            }
        }

        // Nasłuchuj akcji przycisku fizycznego
        scope.launch {
            glassesManager.buttonEvent.collect { event ->
                event?.let {
                    buttonDetector.processEvent(it)
                    glassesManager.consumeButtonEvent()
                }
            }
        }

        // Nasłuchuj zdetektowane akcje
        scope.launch {
            buttonDetector.action.collect { action ->
                handleButtonAction(action)
            }
        }

        // === Wybudzenie po stronie okularów ===
        // Okulary same wykrywają swoje słowo kluczowe (włączane przez
        // VictorManager.setGlassesWakeWord). Wcześniej detekcja była włączona,
        // ale zdarzenie nie miało odbiorcy - czyli "wake word nie działał".
        scope.launch {
            glassesManager.aiSessionRequest.collect { realtimeText ->
                startGlassesConversation(realtimeText)
            }
        }

        // Dotknięcie zauszników w trakcie mówienia = "cicho".
        scope.launch {
            glassesManager.speechInterrupted.collect {
                Log.i(TAG, "Okulary: użytkownik przerwał wypowiedź")
                cancelCurrentTurn("dotknięcie zauszników")
            }
        }

        // Drugi przycisk okularów: zdjęcie zrobione ręcznie, bez udziału
        // aplikacji. Producent w tym miejscu pobiera miniaturę i wrzuca ją do
        // rozmowy - u nas dotąd nie działo się nic, bo czekaliśmy wyłącznie na
        // zdjęcia, o które sami poprosiliśmy.
        scope.launch {
            glassesManager.glassesPhotoTaken.collect { aiVision ->
                handleGlassesPhoto(aiVision)
            }
        }
    }

    /**
     * Zdjęcie zrobione przyciskiem na okularach.
     *
     * ## Dlaczego bajt trybu decyduje
     * Okulary same mówią, po co zrobiły zdjęcie: tryb 2 oznacza "opisz, co
     * widzisz", cokolwiek innego - zwykłą fotkę do pamięci. Gdybyśmy komentowali
     * każde zdjęcie, aparat zamieniłby się w gadatliwego asystenta, którego nikt
     * o nic nie prosił. Zwykłe zdjęcie zostaje więc bez słowa - poza wpisem w
     * dzienniku, żeby dało się to potwierdzić na sprzęcie.
     */
    private fun handleGlassesPhoto(aiVision: Boolean) {
        // W trybie czytania przycisk znaczy "czytaj to, na co patrzę", a nie
        // "zacznij rozmowę o zdjęciu". To jest ten moment, w którym użytkownik
        // przewrócił stronę - i jedyny, w którym ma sens nowe zdjęcie.
        if (accessibility.requestRead()) {
            Log.i(TAG, "Przycisk okularów w trybie czytania - czytam dalej")
            return
        }
        // Pobieramy ZAWSZE, także bez prośby o opis.
        //
        // ## Dlaczego
        // Zdjęcie, o które nie prosiliśmy, zrobił użytkownik przyciskiem na
        // okularach - i ono JUŻ ISTNIEJE. Pobranie go nic nie kosztuje (żadnej
        // migawki, żadnej komendy sterującej), a bez tego wciśnięcie przycisku
        // kończyło się tym, że aplikacja zaczynała WŁASNE przechwytywanie:
        // wysyłała komendy, czekała i przegrywała - mając gotowe zdjęcie tuż
        // obok. Zgłoszone jako "klikam zrobienie zdjęcia na okularach, a potem
        // nie udało się zrobić zdjęcia".
        //
        // Bajt trybu decyduje już tylko o tym, czy o zdjęciu MÓWIMY.
        Log.i(TAG, "Okulary: zdjęcie z przycisku (opisz=$aiVision)")
        scope.launch {
            // Zdjęcie już jest w okularach - pobieramy JE, zamiast robić drugie.
            // Gdy pobranie się nie uda, tura i tak rusza: zrobi wtedy własne
            // zdjęcie, co jest gorsze niż nic nie zrobić, ale lepsze niż cisza
            // po wciśnięciu przycisku.
            val fetched = glassesManager.fetchPhotoFromHardwareButton()
            if (!fetched) {
                Log.w(TAG, "Nie udało się pobrać zdjęcia zrobionego przyciskiem")
            }
            // Gdy okulary nie proszą o opis, a zdjęcia nie udało się pobrać,
            // wciśnięcie przycisku kończy się CISZĄ - i z zewnątrz wygląda to
            // jak „aplikacja nie zareagowała". Niech w dzienniku zostanie ślad,
            // że reakcja była, tylko nie miała na czym stanąć.
            if (!aiVision && !fetched) {
                diag.event(
                    pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE,
                    "zdjęcie z przycisku: nie startuję tury - brak zdjęcia i brak prośby o opis"
                )
            }
            // Turę uruchamiamy, gdy okulary o opis poprosiły ALBO gdy zdjęcie
            // faktycznie mamy. Bez tego drugiego warunku wciśnięcie przycisku
            // przy nieodpowiadających okularach kończyło się serią komend i
            // komunikatem o błędzie zamiast po prostu niczym.
            if (aiVision || fetched) {
                handleUserTrigger(
                    TriggerSource.BUTTON,
                    PHOTO_ON_DEMAND_QUESTION,
                    forceVision = true,
                    // NIE przerywa mówienia: to nie jest palec na oprawce,
                    // tylko tura zbudowana z ramki notify. Patrz uzasadnienie
                    // przy [handleUserTrigger].
                    allowInterruptSpeech = false
                )
            }
        }
    }

    /**
     * Przepisuje nagranie Z OKULARÓW wszystkimi dostępnymi drogami, po kolei.
     *
     * ## Dlaczego to jest jedna funkcja, a nie rozsypane próby
     * Bo kolejność jest tu całą treścią. Mikrofon okularów wisi przy ustach,
     * telefon leży w kieszeni - więc DOWOLNA droga licząca na nagraniu z okularów
     * jest lepsza od najlepszego nasłuchu telefonu. Do tej pory tak nie było:
     * chmura wyprzedzała telefon, ale rozpoznawanie systemowe i Vosk czekały w
     * gałęzi ciszy, czyli wchodziły dopiero wtedy, gdy telefon nie usłyszał NIC.
     *
     * Kto nie wpisał klucza OpenAI, nie dostawał więc żadnej poprawy: telefon
     * słyszał "coś" i to "coś" wygrywało. Teraz bez klucza wchodzi rozpoznawanie
     * systemowe na tym samym nagraniu, a gdy i jego nie ma - Vosk.
     *
     * @return tekst albo `null`, gdy żadna droga nie dała rady
     */
    private suspend fun transcribeGlassesAudio(pcm: ByteArray, languageTag: String): String? {
        // Opus rozkodowuje się na 48 kHz, a rozpoznawanie mowy pracuje na 16 kHz.
        // Przeliczamy sami - podanie 48 kHz i liczenie na to, że usługa sobie
        // poradzi, byłoby zakładem o całą transkrypcję.
        val speechPcm = pl.victor.app.audio.PcmResampler.resample(
            pcm = pcm,
            sourceRate = pl.victor.app.audio.OpusDecoder.SAMPLE_RATE
        )
        val rate = pl.victor.app.audio.PcmResampler.SPEECH_SAMPLE_RATE

        transcribeInCloud(speechPcm, languageTag)?.let { return it }

        // Rozpoznawanie systemowe NA URZĄDZENIU - ten sam silnik co dyktowanie na
        // klawiaturze bez sieci. Darmowe i dobre, ale wymaga pobranego pakietu
        // języka; patrz SpeechToText.isOnDeviceAvailable i ekran ustawień.
        runCatching {
            speechToText.transcribe(pcm = speechPcm, sampleRate = rate, languageTag = languageTag)
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            Log.i(TAG, "Transkrypcja systemowa z nagrania okularów: \"$it\"")
            _lastTranscriptionSource.value = SOURCE_ON_DEVICE
            return it
        }

        // Vosk liczy offline i nie stawia żadnych warunków - za to myli słowa.
        // Ostatnia droga do TEKSTU, zanim zostanie już tylko samo nagranie.
        runCatching {
            pl.victor.app.VictorApplication.get().transcribeWithVosk(speechPcm, rate)
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            Log.i(TAG, "Transkrypcja Voskiem z nagrania okularów: \"$it\"")
            _lastTranscriptionSource.value = SOURCE_VOSK
            return it
        }
        return null
    }

    /**
     * Przepisuje nagranie przez usługę w chmurze - albo od razu oddaje `null`.
     *
     * Trzy warunki i wszystkie muszą być spełnione: ustawienie włączone, klucz
     * OpenAI wpisany, nagranie niepuste. Bez któregokolwiek nic nie wychodzi z
     * telefonu, a wołający idzie dalej swoją drogą.
     */
    private suspend fun transcribeInCloud(pcm: ByteArray, languageTag: String): String? {
        if (!settings.isCloudTranscriptionEnabled()) return null
        val key = settings.getApiKey("openai")?.takeIf { it.isNotBlank() } ?: return null
        val heard = runCatching {
            pl.victor.app.conversation.CloudSpeechToText(key).transcribe(
                pcm = pcm,
                sampleRate = pl.victor.app.audio.PcmResampler.SPEECH_SAMPLE_RATE,
                languageTag = languageTag
            )
        }.onFailure { Log.w(TAG, "Transkrypcja w chmurze nie powiodła się", it) }.getOrNull()
        if (heard != null) {
            Log.i(TAG, "Transkrypcja z chmury: \"$heard\"")
            _lastTranscriptionSource.value = SOURCE_CLOUD
        }
        return heard
    }

    /**
     * Która droga przepisała ostatnie pytanie na tekst.
     *
     * ## Po co to w ogóle jest
     * Bo dróg jest teraz pięć - chmura, rozpoznawanie systemowe na urządzeniu,
     * Vosk, nasłuch telefonu i nagranie wprost do modelu - a z zewnątrz wszystkie
     * wyglądają tak samo: asystent po prostu odpowiada. Gdy odpowiada źle, nie da
     * się zgadnąć, którą poszedł, a od tego zależy CAŁA diagnoza. Do tej pory i ja,
     * i użytkownik zgadywaliśmy.
     */
    private val _lastTranscriptionSource = MutableStateFlow<String?>(null)
    val lastTranscriptionSource: StateFlow<String?> = _lastTranscriptionSource.asStateFlow()

    /** Czy mamy zgodę na mikrofon - patrz [startVoiceTurn]. */
    private fun hasMicrophonePermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Czy można zacząć nową turę - a jeśli poprzednia już się skończyła, sprząta
     * po niej stan.
     *
     * TO BYŁA PRZYCZYNA "aplikacja nie reaguje na komendy". Stany `Completed` i
     * `Error` NIE oznaczają, że coś trwa - to ślad po turze, która się
     * zakończyła. Zostawały jednak na ekranie do czasu, aż użytkownik kliknął
     * "OK" albo "Spróbuj ponownie", a warunek `stan != Idle` odrzucał w tym
     * czasie KAŻDY trigger: przycisk na okularach, wybudzenie, komendę. Z
     * perspektywy użytkownika okulary przestawały działać po pierwszym błędzie,
     * a jedynym ratunkiem było sięgnięcie po telefon.
     *
     * Zajęte są wyłącznie stany, w których coś faktycznie leci.
     */
    /**
     * Ile tur z rzędu poszło przez profil rozmowy zestawu Bluetooth i skończyło
     * się kompletną ciszą - patrz [noteSilentTurn].
     */
    private var silentScoTurns = 0

    /**
     * Aplikacja jako właściciel nasłuchu Voska - patrz [pauseWakeWordMic].
     * Nullable, bo w testach i podglądach Compose kontekst bywa inny.
     */
    private val victorApp: pl.victor.app.VictorApplication?
        get() = context.applicationContext as? pl.victor.app.VictorApplication

    /**
     * Oddaje mikrofon zajęty przez wykrywanie frazy.
     *
     * Porcupine zwalnia go sam przez tryb konwersacyjny; Vosk trzyma własny
     * AudioRecord i nie wie o niczym, więc trzeba mu powiedzieć wprost.
     * Bez tego rozpoznawanie mowy dostaje zajęty mikrofon i tura kończy się
     * komunikatem "nie mogę rozpoznać nagrania".
     */
    private fun pauseWakeWordMic() {
        runCatching { victorApp?.pauseVoskForTurn() }
    }

    private fun resumeWakeWordMic() {
        runCatching { victorApp?.resumeVoskAfterTurn() }
    }

    private fun claimIdle(
        takeOver: Boolean = false,
        mayInterruptSpeech: Boolean = false
    ): Boolean {
        return when (_state.value) {
            is OrchestratorState.Idle -> true
            is OrchestratorState.Completed, is OrchestratorState.Error -> {
                // Poprzednia tura się skończyła - sprzątamy i wchodzimy.
                _state.value = OrchestratorState.Idle
                true
            }
            else -> {
                // Stan roboczy: albo tura naprawdę trwa, albo utknęła.
                // Rozstrzygamy tym, czy jej korutyna jeszcze żyje - a gdy i to
                // zawiedzie, wiekiem stanu. Bez tego jedna zgubiona tura
                // wyłączała asystenta do restartu aplikacji.
                val stuckMs = System.currentTimeMillis() - stateChangedAtMs
                val jobFinished = activeTurnJob?.isActive != true
                // NOWA WYPOWIEDŹ UŻYTKOWNIKA MA PIERWSZEŃSTWO PRZED STARĄ TURĄ.
                //
                // Do tej pory trigger w trakcie tury był po prostu porzucany. Kto
                // więc zapytał, nie doczekał się i zapytał ponownie, dostawał w
                // odpowiedzi... turę pierwszą, minutę później. Zgłoszone: "czasem
                // odpowiada na pytanie zadane dużo wcześniej".
                //
                // Ktoś, kto mówi do asystenta jeszcze raz, jednoznacznie porzucił
                // poprzednie pytanie. Trzymanie się starej tury nie służy nikomu.
                //
                // Karencja jest po to, żeby podwójne wykrycie TEGO SAMEGO słowa
                // wybudzenia nie ubijało tury, którą samo przed chwilą zaczęło.
                val supersede = takeOver && stuckMs > TAKEOVER_GRACE_MS &&
                    canBeSuperseded(_state.value, mayInterruptSpeech)
                if (jobFinished || stuckMs > STUCK_TURN_MS || supersede) {
                    if (supersede && !jobFinished) {
                        Log.i(TAG, "Nowe pytanie po $stuckMs ms - przerywam poprzednią turę")
                        runCatching {
                            diag.event(
                                DiagFormat.Phase.SESJA, "PRZERWANO turę - weszło nowe wywołanie",
                                mapOf("stan" to _state.value::class.simpleName, "poMs" to stuckMs)
                            )
                        }
                        lastCancelReason = "nowe wywołanie użytkownika"
                        // Bez tego stara odpowiedź dogadałaby się do końca w tle,
                        // nakładając się na nową.
                        runCatching { audio.stopSpeaking() }
                    }
                    Log.w(
                        TAG,
                        "Tura utknęła w ${_state.value} od $stuckMs ms " +
                            "(korutyna żyje: ${!jobFinished}) - odblokowuję"
                    )
                    activeTurnJob?.cancel()
                    activeTurnJob = null
                    _state.value = OrchestratorState.Idle
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Lista person, powiedziana tak, jak się mówi.
     *
     * Czytana z [pl.victor.app.persona.PersonaRegistry] przy każdym pytaniu, a
     * nie przepisana tutaj - inaczej dopisanie persony w rejestrze zostawiałoby
     * tę odpowiedź nieaktualną i nikt by tego nie zauważył.
     */
    private fun describePersonas(): String {
        val personas = pl.victor.app.persona.PersonaRegistry.all()
        val currentId = settings.getSelectedPersonaId()
        val current = personas.firstOrNull { it.id == currentId }
        val names = personas.joinToString(", ") { it.name }
        return buildString {
            append("Mam ").append(personas.size).append(" styli rozmowy: ").append(names).append(". ")
            current?.let { append("Teraz używam: ").append(it.name).append(". ") }
            append("Zmienisz je w Ustawieniach, w grupie Model AI i klucze.")
        }
    }

    /**
     * Co asystent potrafi - z tego samego katalogu, który zasila ekran
     * „Komendy". Grupy zamiast wyliczanki wszystkiego: pełna lista ma
     * kilkadziesiąt pozycji i po głosie nikt jej nie wysłucha.
     */
    private fun describeCapabilities(): String {
        val groups = pl.victor.app.actions.CommandCatalog.ALL
            .groupBy { it.group }
            .map { (group, commands) -> "${group.title} - ${commands.size}" }
        return buildString {
            append("Potrafię ").append(pl.victor.app.actions.CommandCatalog.ALL.size)
            append(" rzeczy w ").append(groups.size).append(" grupach: ")
            append(groups.joinToString("; "))
            append(". Pełna lista z przykładami jest w aplikacji, na ekranie Komendy.")
        }
    }

    /**
     * Czy turę w tym stanie wolno porzucić na rzecz nowego pytania.
     *
     * ## Dlaczego nie w każdym
     * Bo w [OrchestratorState.Streaming] asystent MÓWI - a jego własny głos
     * leci przez głośnik okularów, w których siedzi wykrywanie słowa
     * kluczowego. Przejmowanie tury w tym stanie groziłoby ucinaniem odpowiedzi
     * przez echo własnej wypowiedzi. Kto chce przerwać mówienie, ma do tego
     * komendę "stop" (patrz handleMetaCommand) i dotyk zausznika.
     *
     * We wszystkich pozostałych stanach roboczych - nasłuch, zdjęcie,
     * czekanie na model - użytkownik nie słyszy niczego. Powtórzenie pytania
     * jest wtedy jedynym sensownym odruchem i musi działać.
     *
     * ## Wyjątek: przycisk na okularach
     * Echo dotyczy MOWY, nie palca. Wciśnięcie przycisku jest jednoznaczną
     * decyzją człowieka podjętą TERAZ i nie ma jak wziąć się z głośnika.
     * Odrzucanie go dawało dokładnie to, co zgłoszono: „po odpowiedzi blokuje
     * się wszystko na dłuższą chwilę... nie wywołuje AI przyciskiem".
     * W dzienniku z 11 września widać to dwa razy jako
     * `trigger ODRZUCONY - tura już trwa  źródło=BUTTON stan=Streaming`,
     * a mówienie trwało wtedy od 3 do 16 sekund.
     */
    private fun canBeSuperseded(
        state: OrchestratorState,
        mayInterruptSpeech: Boolean = false
    ): Boolean = mayInterruptSpeech || state !is OrchestratorState.Streaming

    /**
     * Przerywa bieżącą turę na żądanie użytkownika - dotykiem zauszników,
     * przyciskiem "Przerwij" w aplikacji albo komendą.
     *
     * Samo uciszenie syntezatora nie wystarcza: gdy przerwanie przyjdzie, zanim
     * model skończy generować, odpowiedź dojdzie chwilę później i i tak zostanie
     * wypowiedziana - czyli "cicho" wyglądałoby na zignorowane. Dlatego kasujemy
     * całą korutynę tury.
     */
    fun cancelCurrentTurn(reason: String = "żądanie użytkownika") {
        // Bez tego wpisu przerwana tura wyglądała w dzienniku jak tura, która
        // po prostu ucichła: w dzienniku z 23:07 dwie tury kończą się
        // „KONIEC TURY ...: Idle" bez ani jednego wiersza o tym, kto je uciął.
        // Zgłoszone jako „AI przestaje odpowiadać, nawet nie widać, żeby
        // reagowało" - i dokładnie tego nie dało się rozstrzygnąć.
        runCatching {
            diag.event(DiagFormat.Phase.SESJA, "PRZERWANO turę", mapOf("powód" to reason))
        }
        lastCancelReason = reason
        audio.stopSpeaking()
        activeTurnJob?.cancel()
        activeTurnJob = null
        _state.value = OrchestratorState.Idle
        conversationalMode.onAiFinishedSpeaking()
    }

    /** Czemu ostatnia tura została przerwana - do wpisu zamykającego. */
    @Volatile
    private var lastCancelReason: String? = null

    /**
     * Rozmowa zainicjowana przez same okulary - słowem kluczowym albo
     * przytrzymaniem zausznika. Właściwa tura jest w [startVoiceTurn].
     */
    private fun startGlassesConversation(realtimeText: Boolean) {
        if (realtimeText) {
            // Tryb tekstu na żywo (tłumaczenie) nie ma jeszcze osobnej ścieżki -
            // traktujemy go jak zwykłe pytanie, żeby wybudzenie w ogóle coś
            // robiło, zamiast milczeć.
            Log.i(TAG, "Tryb tekstu na żywo - obsługuję jak zwykłe pytanie")
        }
        startVoiceTurn(fromGlasses = true)
    }

    /**
     * Rozmowa zainicjowana z aplikacji - przycisk "zapytaj głosem" na ekranie
     * głównym.
     *
     * Bez tego jedyną drogą do rozmowy było wybudzenie okularami albo pisanie
     * z klawiatury. Asystent głosowy, do którego trzeba pisać, mija się z celem -
     * a okularów nie zawsze ma się na sobie.
     */
    fun startVoiceQuestion() = startVoiceTurn(fromGlasses = false)

    /**
     * Wypowiedź usłyszana w trybie ciągłego nasłuchu.
     *
     * ## Po co osobne wejście
     * Bo fraza wybudzenia nie jest pytaniem. W dzienniku z 11 września tura
     * `e117` poszła do modelu z pytaniem `tekst=okej lens` - czyli samym
     * wywołaniem. Model dostał kontekst, odpowiedział na nic, tura zajęła
     * kilkanaście sekund, a prawdziwe pytanie, zadane zaraz po frazie, trafiało
     * już w turę trwającą. Zgłoszone jako „wywołuję AI głosowo, okulary
     * reagują, ale aplikacja nic nie robi, jakby nie słyszała".
     *
     * Teraz sama fraza OTWIERA NASŁUCH, a fraza z pytaniem („okej lens, jaka
     * jest pogoda") oddaje modelowi samo pytanie.
     */
    private fun handleSpokenText(text: String) {
        val configured = listOf(settings.getSelectedWakeWord())
        // BEZ FRAZY I POZA ROZMOWĄ - NIE ODPOWIADAMY.
        //
        // Tryb konwersacyjny wstaje razem z aplikacją i od tej chwili słucha bez
        // przerwy. Brak frazy wybudzenia niczego dotąd nie zatrzymywał: fraza
        // była OBCINANA, gdy ją znaleziono, ale jej BRAK przepuszczał zdanie
        // dalej. Skutek widać w dzienniku czternaście sekund po starcie, przy
        // jeszcze niepodłączonych okularach - do modelu poszła tura z pytaniem
        // „bardzo by chciał", czyli urywkiem czyjejś rozmowy w pokoju.
        // Zgłoszone jako „pytam, co widzi, a on odpowiada na pytanie z wczoraj".
        if (OverheardSpeech.classify(text, configured, conversationOpen()) ==
            OverheardSpeech.Verdict.IGNORE
        ) {
            runCatching {
                diag.event(
                    DiagFormat.Phase.NASŁUCH,
                    "usłyszane, ale nie do mnie - brak frazy poza rozmową",
                    mapOf("usłyszane" to text.take(60))
                )
            }
            return
        }
        if (WakePhrase.isOnlyWakePhrase(text, configured)) {
            diag.event(
                DiagFormat.Phase.NASŁUCH,
                "sama fraza wybudzenia - otwieram nasłuch zamiast pytać model",
                mapOf("usłyszane" to text.take(60))
            )
            // fromGlasses = false świadomie: to jest ta sama droga, którą
            // przeszły wszystkie udane tury z dziennika (mikrofon telefonu po
            // SCO). Strumień BLE z okularów nie jest jeszcze potwierdzony na
            // sprzęcie i nie chcę go stawiać na ścieżce, która właśnie zaczęła
            // działać.
            startVoiceTurn(fromGlasses = false)
            return
        }
        val question = WakePhrase.stripLeadingWakePhrase(text, configured)
        if (question != text) {
            diag.event(
                DiagFormat.Phase.NASŁUCH, "odcięta fraza wybudzenia",
                mapOf("usłyszane" to text.take(60), "pytanie" to question.take(60))
            )
        }
        handleUserTrigger(TriggerSource.VOICE, question)
    }

    /**
     * „Pokaż" z ekranu głównego: zrób zdjęcie i powiedz, co na nim jest.
     *
     * Wcześniej szło to jako trigger BUTTON z PUSTYM pytaniem - model dostawał
     * sam obraz i musiał się domyślić, po co. Zwykle się domyślał, ale wynik
     * zależał od modelu i od tego, co akurat było w kontekście. Jawne polecenie
     * jest przewidywalne, a `forceVision` pomija warstwę 0, więc słowo „opisz"
     * w treści polecenia nie odpali przypadkiem trybu dostępności.
     */
    fun askAboutView() =
        handleUserTrigger(TriggerSource.BUTTON, PHOTO_ON_DEMAND_QUESTION, forceVision = true)

    /**
     * Jedna tura rozmowy mówionej: słuchaj -> zrozum -> odpowiedz głosem.
     *
     * Kolejność jest istotna i wzorowana na aplikacji producenta: najpierw
     * bierzemy łącze audio (bez niego mikrofon okularów jest niedostępny, a
     * odpowiedź poszłaby w głośnik telefonu), potem uciszamy to, co okulary
     * akurat odtwarzają, i dopiero wtedy nagrywamy.
     *
     * @param fromGlasses czy turę zaczęły okulary (wtedy dodatkowo sterujemy ich
     *   odtwarzaniem i sygnalizujemy im niepowodzenie)
     */
    private fun startVoiceTurn(fromGlasses: Boolean) {
        // takeOver: użytkownik właśnie mówi do asystenta, więc jego nowe pytanie
        // jest ważniejsze niż tura, na którą przestał czekać.
        //
        // mayInterruptSpeech: I WAŻNIEJSZE NIŻ ODPOWIEDŹ, KTÓREJ PRZESTAŁ SŁUCHAĆ.
        //
        // Tu leży zgłoszenie "po każdej odpowiedzi AI bardzo długo trzeba
        // czekać, żeby dało się zadać następną". W dzienniku nie ma żadnej
        // blokady po turze - między "KONIEC TURY" a gotowością mija od 0,1 do
        // 0,8 s. Ale czytanie odpowiedzi trwa 12-16 sekund (245-306 znaków), a
        // przez CAŁY ten czas stan to `Streaming` - i `canBeSuperseded`
        // odrzucało wtedy każde wywołanie, które nie deklarowało prawa do
        // przerwania mowy. Czyli: wciśnięcie przycisku na oprawce w trakcie
        // odpowiedzi nie robiło nic, a z zewnątrz wygląda to jak zawieszenie na
        // kilkanaście sekund po każdym pytaniu.
        //
        // To moja regresja z rundy, w której "przycisk przerywa mówienie" stało
        // się cechą wywołania zamiast cechą źródła - żeby tury zbudowane z ramki
        // "zdjęcie gotowe" przestały wywłaszczać trwające tury. Tamto było
        // słuszne i zostaje, ale TA droga to co innego: tędy wchodzi się
        // WYŁĄCZNIE przez świadome działanie człowieka - palec na zauszniku,
        // fraza wybudzenia albo ikona mikrofonu w aplikacji. Żadna ramka od
        // okularów tu nie trafia.
        //
        // Echa własnego głosu to nie grozi: tryb konwersacyjny nie nasłuchuje
        // między `onAiStartedSpeaking` a `onAiFinishedSpeaking`, a fraza
        // wybudzenia w okularach jest w trakcie tury zgaszona i wraca dopiero
        // po jej końcu.
        if (!claimIdle(takeOver = true, mayInterruptSpeech = true)) {
            Log.w(TAG, "Nasłuch zignorowany - poprzednia tura ruszyła przed chwilą")
            return
        }
        if (!speechToText.isAvailable()) {
            _state.value = OrchestratorState.Error(
                "To urządzenie nie ma rozpoznawania mowy. Wpisz pytanie z klawiatury."
            )
            return
        }
        // Bez uprawnienia do mikrofonu rozpoznawanie zwraca po prostu ciszę, a
        // wybudzenie okularami wyglądało wtedy dokładnie tak, jak zgłoszono:
        // dźwięk w okularach jest, po czym NIC. Ścieżka z okularów nie ma jak
        // pokazać systemowego okienka o zgodę, więc trzeba powiedzieć wprost,
        // czego brakuje - i to na głos, bo użytkownik patrzy przed siebie, a nie
        // w telefon.
        if (!hasMicrophonePermission()) {
            val message = "Brak zgody na mikrofon. Otwórz aplikację i naciśnij " +
                "przycisk Powiedz - system zapyta o uprawnienie."
            Log.w(TAG, "Nasłuch niemożliwy - brak RECORD_AUDIO")
            _state.value = OrchestratorState.Error(message)
            audio.speak(message, language = settings.getResponseLanguage())
            return
        }
        // Przypisujemy do activeTurnJob, bo to ONO rozstrzyga, czy tura żyje
        // (patrz claimIdle) - a przy okazji nasłuch staje się przerywalny.
        activeTurnJob = scope.launch {
            // Strumień z mikrofonu okularów podpinamy JAKO PIERWSZY, przed
            // zestawianiem łącza audio. Producent (Prism Pro) subskrybuje go
            // dokładnie w chwili wybudzenia, a negocjacja SCO potrafi trwać
            // kilka sekund - gdyby szła przodem, początek pytania przepadłby,
            // zanim zdążylibyśmy zacząć słuchać.
            var micStreamLive = false
            val glassesCapture =
                if (fromGlasses && glassesManager.isConnected()) {
                    GlassesVoiceCapture(glassesManager).also { micStreamLive = it.start() }
                } else {
                    null
                }
            if (fromGlasses) {
                // Uciszenie okularów to komenda BLE - idzie natychmiast, więc
                // musi pójść PRZED zestawianiem łącza audio. Za nim czekałoby
                // na negocjację SCO i okulary grałyby dalej przez ten czas.
                // Producent nie gra tu żadnego dźwięku powitalnego, tylko
                // ucisza to, co leci - i my robimy tak samo.
                glassesManager.playGlassesTone(GlassesProtocol.TONE_STOP_PLAYBACK)
            }
            // Sygnał "teraz mów" idzie PRZED zestawianiem łącza audio, gdy
            // strumień mikrofonu z okularów już nagrywa.
            //
            // ## Dlaczego to skraca oczekiwanie
            // beginConversationRouting() negocjuje SCO i na niektórych zestawach
            // trwa to kilka sekund. Przez ten czas użytkownik nie wiedział, czy
            // wolno mówić - a strumień BLE, który NIE potrzebuje SCO, już
            // wszystko nagrywał. Zgłoszone jako "długi czas między
            // powiedzeniem czegoś a reakcją". Gdy strumienia nie ma, kolejność
            // zostaje stara: sygnał dopiero po zestawieniu łącza, bo wtedy to
            // ono jest jedyną drogą dźwięku.
            if (micStreamLive) {
                _state.value = OrchestratorState.Listening
                audio.playListeningCue()
            }
            // Blokada uśpienia na czas tury.
            //
            // Pole `wakeLock` istniało od dawna i NIE BYŁO UŻYWANE ani razu.
            // Przy zgaszonym ekranie procesor potrafi przysnąć między pakietami
            // BLE a odpowiedzią z sieci - usługa pierwszoplanowa trzyma proces
            // przy życiu, ale nie trzyma procesora. Zgłoszone jako "gdy telefon
            // jest zablokowany, AI często nie odpowiada".
            pauseWakeWordMic()
            wakeLock.acquire(LOCK_LISTENING, LISTEN_WAKE_LOCK_MS)
            // STRUMIEŃ BLE NIE ZASTĘPUJE MIKROFONU OKULARÓW - I TO BYŁ BŁĄD.
            //
            // Stało tu: „gdy strumień BLE żyje, dźwięk pytania mamy niezależnie
            // od SCO", więc profil rozmowy pomijaliśmy, żeby nie płacić do
            // czterech sekund negocjacji przed nasłuchem. Rozumowanie było
            // spójne i całkowicie fałszywe w jednym punkcie: strumień BLE NIE
            // DAJE NAM dźwięku pytania.
            //
            // Zgłoszone przez użytkownika wprost: „gdy oddalam się od telefonu,
            // AI mnie nie słyszy - dźwięk z okularów w ogóle nie przechodzi do
            // telefonu". Dzienniki mówią to samo od ośmiu sesji, tylko nie
            // umiałem tego przeczytać: transkrypcja nagrania z okularów nie
            // oddała tekstu ANI RAZU na około czterdzieści prób, a model dostając
            // to nagranie słyszy „kroki" albo „niewyraźne". Pakiety przychodzą i
            // dekodują się co do sztuki (456 na 456, zero odrzuconych), ale to,
            // co z nich wychodzi, nie jest mową.
            //
            // Skutek był taki, że pytania zbierał WYŁĄCZNIE mikrofon telefonu -
            // choć w ustawieniach stoi „Pytania mikrofonem okularów". Dopóki
            // telefon leżał obok, nikt tego nie zauważył. Po odejściu na dwa
            // metry asystent głuchnie.
            //
            // Honorujemy więc ustawienie: jeśli człowiek poprosił o mikrofon
            // okularów, zestawiamy profil rozmowy - to jedyna droga, która
            // naprawdę do tego mikrofonu prowadzi. Strumień BLE zostaje jako
            // dodatkowe nagranie i materiał diagnostyczny, ale niczego już nie
            // zastępuje.
            //
            // Płacimy za to negocjacją przed nasłuchem. To jest świadomy wybór:
            // kilka sekund rozruchu jest tańsze niż asystent, który nie słyszy
            // pytania. Czas negocjacji trafia do dziennika - jeśli okaże się
            // dotkliwy, będzie na czym oprzeć następną decyzję.
            val wantsGlassesMic = settings.isGlassesMicEnabled()
            val routingStartedAtMs = System.currentTimeMillis()
            var held = if (micStreamLive && !wantsGlassesMic) {
                false
            } else {
                audio.beginConversationRouting()
            }
            if (held) {
                runCatching {
                    diag.event(
                        DiagFormat.Phase.AUDIO, "zestawiony profil rozmowy do nasłuchu",
                        mapOf(
                            "ms" to (System.currentTimeMillis() - routingStartedAtMs),
                            "mikrofonOkularów" to wantsGlassesMic
                        )
                    )
                }
            }
            val overSco = held && audio.isRoutedToBluetooth()
            try {
                conversationalMode.onAiStartedSpeaking()
                _state.value = OrchestratorState.Listening
                // Sygnał "teraz mów" DOKŁADNIE w chwili startu nasłuchu, nie
                // wcześniej. Dźwięk wybudzenia gra firmware okularów, a między
                // nim a tym momentem mija okno rozpoznawania kliknięć plus
                // zestawienie łącza SCO - na starszym Androidzie nawet kilka
                // sekund. Bez tego znaku pierwsze słowa idą w nic, co wygląda
                // dokładnie jak "wybudzam, mówię, a on nie reaguje".
                if (!micStreamLive) audio.playListeningCue()
                val language = settings.getResponseLanguage()
                // Przez conversationalMode, NIE bezpośrednio przez speechToText:
                // mikrofon jest wyłączny, a wykrywanie słowa kluczowego trzyma
                // AudioRecord. Bez zwolnienia go rozpoznawanie dostaje
                // ERROR_RECOGNIZER_BUSY - czyli "mikrofon nie działa".
                setAsidePhoneTranscript = null
                val listenStartedAtMs = System.currentTimeMillis()
                diag.startTurn(if (fromGlasses) "OKULARY" else "TELEFON")
                diag.event(
                    DiagFormat.Phase.NASŁUCH, "start",
                    mapOf(
                        "strumieńBLE" to micStreamLive,
                        "sco" to overSco,
                        // Stan RZECZYWISTY, nie wyprowadzony ze strumienia BLE.
                        // Dotąd stało tu `!micStreamLive`, co nie jest żadnym
                        // stanem sprzętu: przy żywym strumieniu BLE pole
                        // pokazywało `mikrofonBT=false` NAWET wtedy, gdy łącze
                        // SCO stało i mikrofon zestawu był używany. Dwa razy w
                        // ciągu jednego wieczoru wyprowadziło mnie to na manowce.
                        "mikrofonBT" to audio.isRoutedToBluetooth()
                    )
                )
                // Gdy łącze SCO nie stoi, "mikrofon telefonu" to naprawdę
                // mikrofon telefonu - w kieszeni, pod kurtką. Jego wynik nie ma
                // wtedy prawa przebić strumienia z okularów.
                val heard = listenUntilSpeechEnds(
                    languageTag = languageTagFor(language),
                    // STRUMIEŃ BLE NIE MOŻE ROZSTRZYGAĆ O KOŃCU NASŁUCHU, GDY
                    // MIKROFON IDZIE PRZEZ SCO.
                    //
                    // To druga połowa zgłoszenia "AI czasem mnie nie słyszy" i
                    // znowu mój skutek uboczny. Odkąd nasłuch zestawia profil
                    // rozmowy, okulary oddają mikrofon do HFP i PRZESTAJĄ nadawać
                    // strumień BLE - w dzienniku z 13 września nagranie ma 0,95 s
                    // przy 8,7 s nasłuchu, a liczba pakietów spadła z 456 do 48.
                    //
                    // Wyścig czytał tę ciszę jako "użytkownik skończył mówić" i
                    // kończył nasłuch po dwóch sekundach z pustym wynikiem. To
                    // nie była cisza użytkownika, tylko cisza cudzego mikrofonu.
                    capture = if (overSco) null else glassesCapture,
                    trustPhoneMicrophone = overSco || !fromGlasses,
                    // Skoro łącze SCO i tak stoi, rozpoznawanie ma słuchać
                    // MIKROFONU OKULARÓW, a nie telefonu w kieszeni. Dotąd było
                    // tu samo `!micStreamLive`: strumień BLE żył, więc
                    // rozpoznawanie brało mikrofon telefonu - płaciliśmy za SCO
                    // i nie korzystaliśmy z niego. Najgorsze z obu stron.
                    useBluetoothMic = overSco || !micStreamLive
                )
                // MIKROFON MA POWIEDZIEĆ, CZY COKOLWIEK PRZYNIÓSŁ.
                //
                // W dzienniku z 14 września tura d9c6 wygląda tak: nasłuch
                // 10203 ms, żadnego tekstu, koniec błędem. I nie da się z tego
                // wyczytać, czy człowiek milczał, czy mikrofon podawał ciszę -
                // a to dwie różne sprawy, z których tylko jedna jest usterką.
                // Te cztery liczby rozstrzygają ją jednym wierszem.
                val mic = speechToText.lastMicSignal
                diag.event(
                    DiagFormat.Phase.NASŁUCH, "koniec",
                    mapOf(
                        "ms" to (System.currentTimeMillis() - listenStartedAtMs),
                        "telefonUsłyszał" to heard?.take(80),
                        "odłożone" to setAsidePhoneTranscript?.take(80),
                        "mikrofon" to mic?.verdict(),
                        "szczytDb" to mic?.peakDb,
                        "próbek" to mic?.samples,
                        "gotowyPoMs" to mic?.readyMs
                    )
                )
                Log.i(TAG, "Nasłuch trwał ${System.currentTimeMillis() - listenStartedAtMs} ms")
                _state.value = OrchestratorState.Idle
                // Okulary nadają, dopóki im się tego nie zabroni - i to była
                // przyczyna zgłoszenia "przestaję mówić, a one nasłuchują
                // jeszcze długo". Komenda idzie DOKŁADNIE tutaj, tak jak u
                // producenta: w chwili, gdy nasłuch po naszej stronie się
                // skończył, przed transkrypcją i przed pytaniem modelu.
                if (fromGlasses) glassesManager.stopGlassesListening()

                // NAGRANIE Z OKULARÓW MA PIERWSZEŃSTWO PRZED NASŁUCHEM TELEFONU.
                //
                // To jest poprawka do poprzedniej poprawki i trzeba to powiedzieć
                // wprost: transkrypcja w chmurze siedziała WEWNĄTRZ gałęzi ciszy,
                // czyli odpalała się wyłącznie wtedy, gdy telefon nie usłyszał
                // NIC. A zgłoszony błąd był inny - telefon słyszał ŹLE ("jaka jest
                // pogoda" jako coś o rozwodzie), więc wynik był niepusty, gałąź
                // się nie wykonywała i lepsze rozpoznanie nigdy nie wchodziło.
                //
                // Przyczyna złego słyszenia jest zresztą oczywista: telefon leży w
                // kieszeni, a mówi się do okularów. Mikrofon okularów jest przy
                // ustach i to ON jest źródłem prawdy, gdy nagrywał. Nasłuch
                // telefonu zostaje jako zapas - dla pytań zadawanych do telefonu i
                // na wypadek, gdyby strumień BLE nic nie przyniósł.
                val captured = glassesCapture?.stop()
                diag.event(
                    DiagFormat.Phase.NASŁUCH, "nagranie z okularów",
                    mapOf(
                        "jest" to (captured?.hasAudio == true),
                        "sekund" to captured?.audioSeconds,
                        // Bez tych czterech liczb „nagranie jest niewyraźne"
                        // nie ma jak się rozstrzygnąć: nie wiadomo, czy to
                        // użytkownik mówił cicho, czy dekoder składa szum z
                        // pakietów, których kształtu nie odgadł.
                        "pakietów" to captured?.packets,
                        "rozkodowanych" to captured?.decodedPackets,
                        "odrzuconych" to captured?.failedPackets,
                        "przesunięcie" to captured?.payloadOffset,
                        "ramka" to captured?.packetSize
                    )
                )
                val transcribeStartedAt = System.currentTimeMillis()
                // NIE PRZEPISUJEMY NAGRANIA, GDY TEKST Z TELEFONU JUŻ LEŻY.
                //
                // Zamysł był dobry: mikrofon okularów wisi przy ustach, telefon
                // leży w kieszeni, więc transkrypcja z okularów powinna bić
                // nasłuch telefonu. Tylko że przez pięć dzienników i około
                // czterdzieści tur ta droga NIE ODDAŁA TEKSTU ANI RAZU - po
                // każdym wierszu „z nagrania okularów" idzie „biorę odłożony
                // tekst z telefonu" albo „nagranie do modelu".
                //
                // Kosztowała przy tym 1134, 1795, 1938, 2672 i 3905 ms
                // (12 września) oraz 5456-5950 ms (11 września wieczorem) - i to
                // w ciszy między „skończyłem mówić" a „model dostał pytanie",
                // czyli w miejscu, w którym użytkownik po prostu czeka.
                //
                // Czasy rosną z długością nagrania (0,3-0,6 x czas trwania),
                // więc to nie jest szybka odmowa - jakiś silnik naprawdę mieli
                // te próbki i oddaje pustkę. Którego brakuje, powie wiersz
                // niżej: sprawdzenie obu dróg jest darmowe, bo to dwa pytania o
                // stan, a nie rozpoznawanie.
                //
                // Droga zostaje na miejscu dla przypadku, w którym jest JEDYNA:
                // gdy telefon nic nie usłyszał (kieszeń, kurtka, zablokowany
                // ekran), odłożonego tekstu nie ma i wtedy próbujemy jak dotąd.
                val phoneFallback = setAsidePhoneTranscript
                val glassesHeard = when {
                    captured?.hasAudio != true -> null
                    phoneFallback != null -> null
                    else -> captured.pcm?.let { transcribeGlassesAudio(it, languageTagFor(language)) }
                }
                if (captured?.hasAudio == true) {
                    diag.event(
                        DiagFormat.Phase.TRANSKRYPCJA,
                        if (phoneFallback != null) {
                            "nagrania z okularów NIE przepisuję - mam tekst z telefonu"
                        } else {
                            "z nagrania okularów"
                        },
                        if (phoneFallback != null) {
                            // Dwa pytania o stan, zero rozpoznawania. Bez nich
                            // „ta droga milczy" i „tej drogi nie ma" wyglądają
                            // z dziennika identycznie, a to dwie różne naprawy:
                            // pierwsza to usterka, druga to jeden pakiet języka
                            // do pobrania w ustawieniach Androida.
                            mapOf(
                                "rozpoznawanieNaUrządzeniu" to speechToText.isOnDeviceAvailable(),
                                "voskGotowy" to runCatching {
                                    VictorApplication.get().voskWakeWord.isModelReady()
                                }.getOrDefault(false),
                                "chmura" to settings.isCloudTranscriptionEnabled()
                            )
                        } else {
                            mapOf(
                                "ms" to (System.currentTimeMillis() - transcribeStartedAt),
                                "droga" to _lastTranscriptionSource.value,
                                "wynik" to glassesHeard?.take(80)
                            )
                        }
                    )
                }
                if (glassesHeard != null && !heard.isNullOrBlank() && glassesHeard != heard) {
                    // Rozbieżność w dzienniku, bo to jedyny sposób, żeby potem
                    // sprawdzić, która droga miała rację.
                    Log.i(TAG, "Telefon: \"$heard\" | okulary: \"$glassesHeard\" - biorę okulary")
                }
                val bestHeard = glassesHeard ?: heard
                if (glassesHeard == null && !heard.isNullOrBlank()) {
                    _lastTranscriptionSource.value = SOURCE_PHONE
                }

                if (bestHeard.isNullOrBlank()) {
                    // Zanim ogłosimy porażkę: może okulary jednak przysłały
                    // dźwięk po BLE. Jeśli tak i model umie słuchać, pytanie
                    // idzie do niego jako nagranie - bez rozpoznawania mowy.
                    // PRZYTNIJ NAGRANIE DO TEGO, W CZYM KTOŚ MÓWI.
                    //
                    // Tu kończą tury, w których rozpoznawanie mowy nie wykryło
                    // końca wypowiedzi - a wtedy nasłuch dobija do twardego
                    // sufitu i nagranie ma dziewięć sekund przy pytaniu
                    // trwającym dwie. Model dostaje materiał, w którym mowa jest
                    // MNIEJSZOŚCIĄ, i opisuje to, co słychać najwyraźniej.
                    // Zgłoszone dosłownie: „AI mówi, że słyszy tylko kroki, a
                    // nikt nawet nie chodzi".
                    //
                    // Przycinanie nie rozpoznaje mowy - odcina tylko końce, w
                    // których nie dzieje się nic, i w razie wątpliwości oddaje
                    // nagranie bez zmian (patrz [VoiceTrim]). Przy okazji zbija
                    // wysyłkę z ~875 kB do ułamka tego, co skraca też czekanie.
                    val recording = captured?.takeIf { it.hasAudio }?.let { capture ->
                        val raw = capture.pcm
                        if (raw == null) {
                            capture.wav
                        } else {
                            val trimmed = pl.victor.app.audio.VoiceTrim.trim(
                                raw,
                                pl.victor.app.audio.OpusDecoder.SAMPLE_RATE
                            )
                            runCatching {
                                // Tło i szczyt idą do dziennika, bo pierwsza
                                // wersja przycinania nie ucięła ANI RAZU i bez
                                // tych dwóch liczb nie da się dobrać progu
                                // inaczej niż zgadywaniem. Mówią też, czy w
                                // strumieniu z okularów w ogóle jest mowa:
                                // nagranie o tle i szczycie prawie równych to
                                // szum, nie wypowiedź.
                                val levels = pl.victor.app.audio.VoiceTrim.measure(
                                    raw,
                                    pl.victor.app.audio.OpusDecoder.SAMPLE_RATE
                                )
                                diag.event(
                                    DiagFormat.Phase.NASŁUCH, "przycinam nagranie dla modelu",
                                    mapOf(
                                        "byłoMs" to msOf(raw.size),
                                        "jestMs" to msOf(trimmed.size),
                                        "tło" to levels?.floor?.toInt(),
                                        "szczyt" to levels?.peak?.toInt(),
                                        "ile razy" to levels?.ratio?.let {
                                            (it * 10).toInt() / 10.0
                                        }
                                    )
                                )
                            }
                            pl.victor.app.audio.WavWriter.wrap(
                                trimmed,
                                pl.victor.app.audio.OpusDecoder.SAMPLE_RATE
                            )
                        }
                    }
                    val seconds = captured?.audioSeconds ?: 0.0

                    // Wszystkie drogi do TEKSTU zostały już przejechane wyżej
                    // (transcribeGlassesAudio) - powtarzanie ich tutaj nic by nie
                    // dało, a chmurę kosztowałoby drugi raz. Zostaje samo nagranie.
                    //
                    // Capabilities z tabeli, NIE z getOrCreateProvider(): to
                    // drugie rzuca wyjątkiem przy braku klucza API i potrafi
                    // pójść do sieci po listę modeli. Tutaj potrzebujemy tylko
                    // odpowiedzi "czy ten model przyjmuje nagrania", a wyjątek
                    // z korutyny bez catcha wywróciłby aplikację.
                    // TEKST Z TELEFONU IDZIE PRZED NAGRANIEM. To jest poprawka
                    // do poprzedniej poprawki i trzeba ją nazwać wprost.
                    //
                    // Dziennik z 11 września, dziesięć tur z rzędu, zawsze ten
                    // sam układ:
                    //
                    //   NASŁUCH koniec  odłożone=czy słyszysz co mówię
                    //   TRANSKRYPCJA z nagrania okularów  droga=Bez transkrypcji
                    //   SESJA pytanie  tekst=W załączonym nagraniu... nagranie=876860 B
                    //
                    // Telefon rozumiał pytanie DOKŁADNIE ("ile to 2 + 2",
                    // "co widzisz"), a my odkładaliśmy ten tekst i wysyłaliśmy
                    // modelowi 876 kB dźwięku, na co model odpowiadał, że
                    // nagranie jest niewyraźne i nie słyszy w nim pytania.
                    // Zgłoszone jako „na większość pytań AI odpowiada: to
                    // nagranie jest niewyraźne".
                    //
                    // Nagranie do modelu zostaje - ale jako OSTATNIA deska, a
                    // nie pierwsza. Gotowy tekst bije surowy dźwięk także na
                    // czasie: odpada wysyłka prawie megabajta, która w dzienniku
                    // kosztowała od 6 do 40 sekund.
                    setAsidePhoneTranscript?.let { phoneText ->
                        Log.i(TAG, "Biorę odłożony tekst z nasłuchu telefonu")
                        diag.event(
                            DiagFormat.Phase.TRANSKRYPCJA,
                            "biorę odłożony tekst z telefonu",
                            mapOf("tekst" to phoneText.take(60))
                        )
                        // Nagłówek dziennika pokazywał tu drogę z POPRZEDNIEJ
                        // tury: transcribeGlassesAudio ustawia to pole tylko
                        // wtedy, gdy sam coś rozpozna, a tutaj właśnie nie
                        // rozpoznał (albo w ogóle nie był pytany).
                        _lastTranscriptionSource.value = SOURCE_PHONE
                        silentScoTurns = 0
                        conversationalMode.onAiFinishedSpeaking()
                        handleUserTrigger(TriggerSource.WAKE_WORD, phoneText)
                        return@launch
                    }

                    val providerId = settings.getActiveProvider()
                    val modelHearsAudio = settings.hasApiKey(providerId) &&
                        AIProviderFactory.getCapabilitiesFor(providerId).supportsAudio
                    if (recording != null && modelHearsAudio) {
                        Log.i(
                            TAG,
                            "Rozpoznawanie nic nie usłyszało, ale mam " +
                                "%.1f s".format(seconds) +
                                " dźwięku z okularów - pytam modelu nagraniem"
                        )
                        silentScoTurns = 0
                        _lastTranscriptionSource.value = SOURCE_AUDIO_TO_MODEL
                        conversationalMode.onAiFinishedSpeaking()
                        handleUserTrigger(
                            TriggerSource.WAKE_WORD,
                            AUDIO_QUESTION_PROMPT,
                            audioQuestion = recording
                        )
                        return@launch
                    }

                    val switched = noteSilentTurn(overSco)
                    val message = switched ?: silenceMessage(captured)
                    Log.i(TAG, "Nasłuch bez wypowiedzi: $message")
                    if (fromGlasses) glassesManager.playGlassesTone(GlassesProtocol.TONE_ERROR)
                    // Przy turze z okularów użytkownik patrzy przed siebie, nie
                    // w telefon. Sam sygnał błędu znaczy dla niego tyle co nic -
                    // "AI nie odpowiada" i tyle. Powód musi pójść głosem.
                    if (switched == null && fromGlasses) {
                        audio.speak(message, language = settings.getResponseLanguage())
                    }
                    if (switched != null) {
                        // Łącze SCO trzeba rozebrać OD RAZU, zanim cokolwiek
                        // powiemy. Zwykłe zwolnienie ma karencję (patrz
                        // BluetoothAudioRouter.release), więc komunikat o
                        // przełączeniu poszedłby dokładnie tą martwą drogą,
                        // którą właśnie wyłączamy - i nikt by go nie usłyszał.
                        audio.resetConversationRouting()
                        held = false
                        audio.speak(message, language = settings.getResponseLanguage())
                    }
                    // Stan błędu ustawiamy RÓWNIEŻ dla tury z okularów. Sam sygnał
                    // dźwiękowy nie mówi, co poszło nie tak - a to była dokładnie
                    // zgłoszona sytuacja: "słychać dźwięk wybudzenia, ale nic więcej".
                    // Po sięgnięciu po telefon ma tam czekać odpowiedź, nie pusty ekran.
                    // Nie blokuje to kolejnych tur - claimIdle() sprząta stan błędu.
                    _state.value = OrchestratorState.Error(message)
                    // Bez tego tryb konwersacyjny zostawał uciszony na stałe:
                    // onAiStartedSpeaking() wyżej anulował nasłuch, a nikt by go
                    // już nie wznowił - jedna cisza kończyłaby całą rozmowę.
                    conversationalMode.onAiFinishedSpeaking()
                    return@launch
                }
                silentScoTurns = 0
                Log.i(TAG, "Usłyszałem: \"$bestHeard\"")
                handleUserTrigger(
                    if (fromGlasses) TriggerSource.WAKE_WORD else TriggerSource.VOICE,
                    bestHeard
                )
            } finally {
                // detach(), nie stop(): ten blok wykonuje się także po
                // ANULOWANIU tury, a wtedy każde wywołanie zawieszalne
                // natychmiast rzuca - subskrypcja BLE zostałaby zarejestrowana
                // na zawsze. Przerwana tura nie ma zresztą czego dekodować;
                // gałąź ciszy wyżej zdążyła już wziąć wynik przez stop().
                glassesCapture?.detach()
                // Również po anulowanej turze - inaczej "cicho" uciszało
                // syntezator, a okulary nasłuchiwały dalej.
                if (fromGlasses) glassesManager.stopGlassesListening()
                if (held) audio.endConversationRouting()
                // Nazwa jest tu istotna: bez niej ten blok zwalniał blokadę TURY,
                // która startuje z tego samego miejsca i żyje dłużej niż nasłuch.
                wakeLock.release(LOCK_LISTENING)
                resumeWakeWordMic()
            }
        }
    }

    /**
     * Zlicza tury przez profil rozmowy (SCO/HFP), które skończyły się ciszą - i
     * po serii takich sam przełącza się na mikrofon telefonu.
     *
     * ## Dlaczego to jest potrzebne
     * Zestawienie SCO **zawiesza odtwarzanie A2DP**. Zestaw, który zgłasza
     * profil rozmowy, ale go porządnie nie obsługuje, daje więc najgorszy
     * możliwy wynik naraz: okulary milkną (A2DP stoi) i nic nie słyszą (SCO nie
     * niesie dźwięku). Z zewnątrz to dokładnie zgłoszony objaw - "dźwięk
     * wybudzenia jest, po czym cisza i brak reakcji" - i nie ma z tego wyjścia,
     * bo każda kolejna tura powtarza ten sam błąd.
     *
     * Trzy tury z rzędu to nie przypadek: raz można się rozmyślić, dwa razy
     * można nie zdążyć, trzy razy pod rząd znaczy, że tą drogą dźwięk nie idzie.
     * Wyłączenie jest zapamiętane i odwracalne w Ustawieniach.
     *
     * @return komunikat do pokazania, gdy właśnie doszło do przełączenia
     */
    private fun noteSilentTurn(overSco: Boolean): String? {
        if (!overSco) return null
        silentScoTurns++
        if (silentScoTurns < SILENT_SCO_LIMIT) return null
        silentScoTurns = 0
        settings.setGlassesMicEnabled(false)
        audio.setGlassesMicEnabled(false)
        Log.w(TAG, "Trzy ciche tury przez SCO - przechodzę na mikrofon telefonu")
        // Samo mówienie należy do wołającego: komunikat musi pójść DOPIERO po
        // rozebraniu łącza SCO, inaczej nie da się go usłyszeć.
        return "Mikrofon okularów nie zbiera dźwięku, więc przełączam się na " +
            "mikrofon telefonu. Odpowiedzi dalej będą słyszalne w okularach. " +
            "Możesz to cofnąć w Ustawieniach."
    }

    /**
     * Nasłuchuje, ale nie dłużej, niż użytkownik faktycznie mówi.
     *
     * ## Dlaczego to wyścig, a nie zwykłe wywołanie
     * Rozpoznawanie mowy ma własny limit - piętnaście sekund - i czeka do końca,
     * gdy nic nie słyszy. Przy telefonie zablokowanym w kieszeni to reguła, nie
     * wyjątek: użytkownik mówi trzy sekundy, a tura stoi jeszcze dwanaście.
     * Zgłoszono to wprost: "przestaje mówić, a nagranie dalej długo trwa".
     *
     * Okulary nadają pakiety tylko wtedy, gdy w mikrofonie coś jest, więc cisza
     * w ICH strumieniu jest lepszym sygnałem końca wypowiedzi niż zegar
     * rozpoznawania. Wygrywa to, co przyjdzie pierwsze: rozpoznany tekst albo
     * cisza z okularów. Gdy okulary nie nadają, drugi tor nigdy nie kończy i
     * decyduje samo rozpoznawanie - czyli zachowanie sprzed tej zmiany.
     */
    private suspend fun listenUntilSpeechEnds(
        languageTag: String,
        capture: GlassesVoiceCapture?,
        trustPhoneMicrophone: Boolean = true,
        useBluetoothMic: Boolean = true
    ): String? {
        if (capture == null) {
            return conversationalMode.listenOnce(
                languageTag = languageTag,
                useBluetoothMic = useBluetoothMic
            )
        }

        return coroutineScope {
            val listening = async {
                conversationalMode.listenOnce(
                    languageTag = languageTag,
                    useBluetoothMic = useBluetoothMic
                )
            }
            val glassesQuiet = async { capture.awaitSpeechEnd() }

            // Rozpoznawanie wygrywa wyścig TYLKO z niepustym wynikiem - i to
            // jest tu sedno.
            //
            // ## Dlaczego
            // Gdy telefon jest zablokowany albo mikrofon zajmuje profil rozmowy,
            // rozpoznawanie wraca z pustką po dwóch sekundach. Traktowaliśmy to
            // jak koniec wyścigu i natychmiast zatrzymywali nagranie z okularów -
            // razem z pytaniem, którego użytkownik jeszcze nie skończył mówić.
            // Zostawał z niego ułamek sekundy i komunikat "wyłapało tylko urywek
            // 0,2 s". Pustka nie znaczy "koniec wypowiedzi", tylko "ja nic nie
            // usłyszałem" - a wtedy jedynym sędzią zostaje strumień z okularów.
            val heard = select<String?> {
                listening.onAwait { text ->
                    val usable = text?.takeIf { it.isNotBlank() }
                    when {
                        usable == null -> RECOGNIZER_GAVE_UP
                        trustPhoneMicrophone -> usable
                        // Okulary mają już prawdziwą wypowiedź, a telefon leży
                        // gdzieś w kieszeni - jego wersja to zgadywanie ze
                        // stłumionego dźwięku. Zgłoszone jako "często nie
                        // rozumie, co się mówi": trafiały tak przypadkowe słowa
                        // zamiast pytania.
                        capture.voicedMsSoFar >= pl.victor.app.audio.SpeechEnd.MIN_VOICED_MS -> {
                            Log.i(
                                TAG,
                                "Odkładam wynik mikrofonu telefonu (\"$usable\") - " +
                                    "okulary mają ${capture.voicedMsSoFar} ms mowy"
                            )
                            // ODKŁADAMY, nie wyrzucamy. Gdy droga przez okulary
                            // nic nie da (telefon bez rozpoznawania na
                            // urządzeniu, model bez obsługi dźwięku), to jest
                            // jedyne, co usłyszeliśmy - a cisza w odpowiedzi
                            // jest gorsza niż niedoskonała transkrypcja.
                            setAsidePhoneTranscript = usable
                            // ...ale nasłuch KOŃCZYMY. Niepusty wynik znaczy, że
                            // rozpoznawanie wykryło koniec wypowiedzi - a to
                            // jedyny sygnał końca, jaki tu mamy.
                            //
                            // Dotąd szło stąd RECOGNIZER_GAVE_UP, czyli
                            // „czekaj, aż okulary ucichną". One nie cichną: w
                            // dzienniku z 11 września wiersz
                            // `WAKE okulary nadają dźwięk, choć żadna tura nie
                            // trwa` pojawia się także MIĘDZY turami, więc
                            // strumień leci bez przerwy i cisza w nim nie
                            // nastąpi nigdy. Skutek: KAŻDY nasłuch dobijał do
                            // twardego limitu 9 s - dziesięć tur z rzędu po
                            // 8,7-9,0 s. Zgłoszone jako „AI nadal za długo
                            // nasłuchuje po zadaniu pytania".
                            PHONE_ENDPOINTED
                        }
                        else -> usable
                    }
                }
                glassesQuiet.onAwait {
                    Log.i(TAG, "Okulary ucichły przed rozpoznawaniem - kończę nasłuch")
                    null
                }
            }

            val result = when {
                heard === PHONE_ENDPOINTED -> {
                    // KARENCJA NA OGON WYPOWIEDZI ZNIKA, BO NIE MA JUŻ CZEGO
                    // CHRONIĆ.
                    //
                    // Dokładała pół sekundy nagrywania po tym, jak rozpoznawanie
                    // zamknęło wynik - żeby nagranie z okularów, zostawione jako
                    // zapas, nie straciło ostatniej sylaby. Ale ten zapas
                    // przestał istnieć: gdy rozpoznawanie telefonu oddało tekst
                    // (a PHONE_ENDPOINTED znaczy dokładnie to - jest zwracane w
                    // tej samej gałęzi, która odkłada tekst), nagrania z okularów
                    // już nie przepisujemy ani nie wysyłamy do modelu.
                    //
                    // Zostawała więc czysta zwłoka: pół sekundy ciszy po każdym
                    // pytaniu, za nagranie, którego nikt nie przeczyta. Do
                    // dziennika nagranie i tak trafia, tyle że o pół sekundy
                    // krótsze.
                    Log.i(TAG, "Rozpoznawanie wykryło koniec wypowiedzi - kończę nasłuch")
                    null
                }
                heard === RECOGNIZER_GAVE_UP -> {
                    Log.i(TAG, "Rozpoznawanie nic nie usłyszało - czekam, aż okulary ucichną")
                    glassesQuiet.await()
                    null
                }
                else -> heard
            }

            // Przegrany tor nie ma już nic do zrobienia. Anulowanie zwycięzcy
            // jest bezpieczne - zakończona korutyna ignoruje cancel().
            listening.cancel()
            glassesQuiet.cancel()
            result
        }
    }

    /**
     * Znacznik "rozpoznawanie się poddało" - patrz [listenUntilSpeechEnds].
     *
     * Osobna instancja porównywana przez tożsamość, a nie zwykły `null`: null
     * znaczy tam "koniec nasłuchu bez tekstu" i musi się dać odróżnić od
     * "jeden tor odpadł, drugi jeszcze pracuje".
     */
    private val RECOGNIZER_GAVE_UP: String = String("brak-rozpoznania".toCharArray())

    /**
     * Rozpoznawanie oddało wynik, ale nie ufamy jego TREŚCI - ufamy za to jego
     * decyzji, że użytkownik skończył mówić. Patrz [listenUntilSpeechEnds].
     */
    private val PHONE_ENDPOINTED: String = String("koniec-wypowiedzi".toCharArray())

    /**
     * Tekst z mikrofonu telefonu, którego NIE puściliśmy dalej, bo lepszym
     * źródłem były okulary - do użycia, gdy tamta droga nic nie dała.
     *
     * Kasowane na starcie każdej tury: wynik sprzed dwóch pytań jest gorszy
     * niż cisza.
     */
    @Volatile
    private var setAsidePhoneTranscript: String? = null

    /**
     * Co powiedzieć po nasłuchu, który nic nie usłyszał.
     *
     * "Nic nie usłyszałem" jest prawdziwe, ale bezużyteczne - nie odróżnia trzech
     * zupełnie różnych awarii: użytkownik się nie odezwał, okulary nie przesłały
     * dźwięku, albo przesłały, a my nie umiemy go rozkodować. Licznik pakietów
     * BLE rozstrzyga to jednoznacznie - i to bez wchodzenia w diagnostykę.
     */
    /**
     * Czy ekran jest zablokowany.
     *
     * Ma znaczenie dla tego, co powiemy: systemowe rozpoznawanie mowy przy
     * zablokowanym ekranie na wielu telefonach po prostu nie startuje, a
     * "nic nie usłyszałem" wysyłało wtedy użytkownika w złą stronę.
     */
    private fun isDeviceLocked(): Boolean = runCatching {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager)
            .isDeviceLocked
    }.getOrDefault(false)

    private fun silenceMessage(capture: GlassesVoiceCapture.Result?): String {
        // Najpierw prawdziwa awaria, jeśli była. Zajęty mikrofon, brak sieci
        // czy odmowa uprawnienia to NIE jest "nic nie usłyszałem" - a właśnie
        // tak wyglądały do tej pory, bo rozpoznawanie zwraca przy każdym błędzie
        // to samo puste `null`.
        speechToText.lastFailureReason()?.let { reason ->
            return "Rozpoznawanie mowy nie zadziałało: $reason."
        }
        if (capture == null) {
            return if (isDeviceLocked()) {
                "Nic nie usłyszałem. Telefon jest zablokowany, a systemowe " +
                    "rozpoznawanie mowy przy zablokowanym ekranie na wielu " +
                    "telefonach nie działa - odblokuj ekran i spróbuj ponownie."
            } else {
                "Nic nie usłyszałem."
            }
        }
        return when {
            capture.packets == 0 -> {
                val route = if (audio.hasConversationMic()) {
                    "Telefon widzi bluetoothowy mikrofon, więc pytanie miało którędy " +
                        "pójść - mów wyraźnie zaraz po sygnale."
                } else {
                    "Telefon NIE widzi mikrofonu okularów (brak profilu rozmowy) - " +
                        "sparuj je dodatkowo jako zestaw słuchawkowy w ustawieniach Bluetooth."
                }
                "Nic nie usłyszałem, a okulary nie przysłały dźwięku po BLE. $route"
            }
            capture.decodedPackets == 0 ->
                "Nic nie usłyszałem. Okulary przysłały ${capture.packets} pakietów " +
                    "dźwięku po BLE, ale nie dały się rozkodować - szczegóły w " +
                    "Diagnostyce, pomiar strumienia z mikrofonu."
            // Rozkodowane, ale za krótkie, żeby cokolwiek z tego wynikało - to
            // NIE jest wina modelu, więc nie odsyłamy do zmiany providera.
            !capture.hasAudio ->
                "Nic nie usłyszałem. Z okularów przyszedł tylko urywek dźwięku (" +
                    "%.1f s".format(capture.audioSeconds) + ") - za mało na pytanie. " +
                    "Zacznij mówić zaraz po sygnale wybudzenia."
            else ->
                "Nic nie usłyszałem. Z okularów przyszło " +
                    "%.1f s".format(capture.audioSeconds) + " dźwięku, ale wybrany " +
                    "model nie przyjmuje nagrań - przełącz się na Gemini albo mów " +
                    "wyraźniej do mikrofonu telefonu."
        }
    }

    private fun handleButtonAction(action: ButtonAction) {
        Log.i(TAG, "Button action: $action")
        when (action) {
            // Pojedyncze kliknięcie = "chcę o coś zapytać", więc SŁUCHAMY, a nie
            // od razu robimy zdjęcie. Ma to dodatkowe, bardzo praktyczne
            // znaczenie: na tym egzemplarzu okularów WŁASNE słowo wybudzenia
            // przychodzi tą samą ramką co przycisk (notify 0x03, w dzienniku
            // "wciśnięto przycisk AI") - a nie 0x17/0x18, jak w aplikacji
            // producenta. Gdyby to od razu wywoływało aparat, wybudzenie głosem
            // kończyłoby się cichym zdjęciem zamiast rozmowy.
            // Zdjęcie i tak poleci, jeśli model uzna, że bez obrazu nie odpowie.
            ButtonAction.QUICK_QUESTION -> startVoiceTurn(fromGlasses = true)
            // Podwójne kliknięcie = JEDYNY pewny, fizyczny gest, który ZAWSZE
            // robi zdjęcie. Odkąd pojedyncze kliknięcie zaczęło słuchać, aparat
            // ruszał wyłącznie wtedy, gdy model sam o obraz poprosił - czyli z
            // punktu widzenia użytkownika "zdjęć w ogóle nie robi". Tu nie ma
            // żadnego wnioskowania: dwa kliknięcia to obraz i opis tego, co
            // widać, niezależnie od tego, co akurat myśli model.
            ButtonAction.LOOK_AND_DESCRIBE -> askAboutView()
            // Przytrzymanie - najłatwiejszy gest do trafienia bez patrzenia,
            // więc dostaje funkcję, dla której nosi się te okulary, gdy nie
            // widzi się dobrze: odczytanie tego, co jest napisane.
            //
            // ## Czemu od razu po polsku
            // Bo czytanie obcego tekstu polskim głosem TTS i tak nie dawało nic
            // użytecznego - angielskie słowa wychodziły przekręcone, a osoba
            // słuchająca i tak nie wiedziała, co znaczą. Model widzi zdjęcie i
            // czyta z niego tekst, więc przetłumaczenie go to zmiana POLECENIA,
            // nie druga runda: bez dodatkowego zapytania, bez OCR-a na telefonie
            // i bez pobierania modeli tłumaczących.
            //
            // Tekst w języku docelowym jest po prostu czytany - „tłumaczenie"
            // polskiej tabliczki na polski nie ma sensu, a model sam to widzi.
            ButtonAction.READ_TEXT -> handleUserTrigger(
                TriggerSource.BUTTON,
                pl.victor.app.vision.ReadTextPrompt.forLanguage(settings.getResponseLanguage()),
                forceVision = true
            )
            ButtonAction.SCAN_QR -> handleUserTrigger(
                TriggerSource.BUTTON,
                "Zeskanuj kod QR ze zdjęcia i powiedz krótko, co w nim jest.",
                // Bez tego szło przez warstwę 0, a tam wykrywanie komend mogło
                // przechwycić zdanie, zanim w ogóle doszło do aparatu.
                forceVision = true
            )
            ButtonAction.NEW_CONVERSATION -> reset()
        }
    }

    /**
     * Główny flow - wywoływany po naciśnięciu przycisku lub wpisaniu tekstu.
     *
     * Routing decyduje się w trzech warstwach (od najtańszej do najbogatszej):
     *
     * - **Warstwa 0 - odruch.** Najpierw własne komendy użytkownika
     *   ([pl.victor.app.actions.CustomCommands]), potem
     *   [SmartActionDetector.detectCritical]: garść komend, które muszą
     *   zadziałać natychmiast i offline ("stop", "zrób zdjęcie", "włącz
     *   latarkę"). Dopasowanie jest ścisłe - całe zdanie, nie fragment.
     * - **Warstwa 1 - rozumienie.** Wszystko inne idzie do AI, które jest routerem:
     *   samo odpowiada, samo prosi o narzędzia znacznikiem `[[ACTION: ...]]`
     *   (patrz [SmartActionDetector.AI_ACTION_CAPABILITIES_PROMPT]) i samo prosi
     *   o zdjęcie (`take_photo`), gdy bez obrazu nie odpowie.
     * - **Warstwa 2 - awaria.** Gdy AI jest niedostępne (brak klucza, brak
     *   pobranego modelu lokalnego), wracamy do pełnej detekcji wzorcami
     *   [SmartActionDetector.detect]. Lepsza niedoskonała komenda niż komunikat
     *   o błędzie.
     *
     * @param forceVision wymusza zrobienie zdjęcia niezależnie od źródła triggera.
     *   Używane, gdy warstwa 1 poprosiła o obraz - patrz obsługa [Action.TakePhoto].
     */
    fun handleUserTrigger(
        trigger: TriggerSource,
        textQuestion: String = "",
        forceVision: Boolean = false,
        audioQuestion: ByteArray? = null,
        /**
         * Czy to wywołanie wolno wpuścić, gdy asystent właśnie MÓWI.
         *
         * ## Dlaczego to parametr, a nie cecha [TriggerSource]
         * Bo `BUTTON` niesie dwie zupełnie różne rzeczy. Jedna to palec na
         * oprawce - świadoma decyzja człowieka, która ma przerwać mówienie.
         * Druga to tura zbudowana z RAMKI „zdjęcie gotowe", której nikt
         * świadomie nie wywołał.
         *
         * Rozróżnienia nie było i w dzienniku z 21:55 widać, co z tego wyszło:
         * o 22:14:50 i 22:15:17 tura z ramki wywłaszczyła turę, która właśnie
         * trwała (`KONIEC TURY ...: Idle`), zrobiła własne zdjęcie, okulary
         * zameldowały je kolejną ramką - i to samo od nowa. Zgłoszone jako
         * „okulary zaczęły robić zdjęcia przez cały czas".
         */
        allowInterruptSpeech: Boolean = trigger.mayInterruptSpeech(),
        /**
         * Czy odpowiedź modelu ma trafić do notatnika zamiast być tylko
         * wypowiedzianą odpowiedzią - patrz [pl.victor.app.notes.Notes.describeRequest].
         */
        saveAsNote: Boolean = false
    ) {
        // Przycisk na okularach to też świadome działanie użytkownika TERAZ -
        // ma pierwszeństwo tak samo jak wypowiedź. Tury wewnętrzne (powtórka ze
        // zdjęciem) wchodzą na stanie Idle, więc ich to nie dotyczy.
        if (!claimIdle(
                takeOver = trigger.mayTakeOverTurn(),
                mayInterruptSpeech = allowInterruptSpeech
            )
        ) {
            Log.w(TAG, "Already processing, ignoring trigger")
            diag.event(
                DiagFormat.Phase.BŁĄD, "trigger ODRZUCONY - tura już trwa",
                mapOf("źródło" to trigger.name, "stan" to _state.value::class.simpleName)
            )
            return
        }
        diag.continueOrStartTurn(trigger.name)
        diag.event(
            DiagFormat.Phase.SESJA, "pytanie",
            mapOf(
                "tekst" to textQuestion.take(120),
                "nagranie" to (audioQuestion?.size?.let { "$it B" }),
                "wymuszonyObraz" to forceVision
            )
        )

        // Z nagraniem zamiast tekstu warstwy 0 i 2 nie mają czego dopasowywać:
        // `textQuestion` jest wtedy instrukcją dla modelu, a nie tym, co
        // powiedział użytkownik. Sprawdzanie ich na takim tekście mogłoby
        // odpalić przypadkową komendę - dlatego wszystko idzie prosto do modelu.
        val textIsQuestion = audioQuestion == null

        // === POTWIERDZENIE CZEKAJĄCEJ AKCJI - PRZED WSZYSTKIM INNYM ===
        //
        // Zgłoszone: "mówię, że potwierdzam, a akceptacja nie działa - trzeba
        // fizycznie kliknąć". W dzienniku z 14 września widać dokładnie, co się
        // dzieje: o 06:40:54 pada wciśnięcie przycisku, rusza NOWA TURA, a
        // "potwierdzam" leci do modelu jako zwykłe pytanie.
        //
        // Głosowe potwierdzanie istniało - tyle że nasłuchiwało wyłącznie w
        // oknie tuż po zapytaniu. Człowiek robi to inaczej i słusznie: naciska
        // przycisk, bo tak działa w tej aplikacji WSZYSTKO inne. Każde takie
        // naciśnięcie ubijało własne pytanie asystenta.
        //
        // Dlatego dopóki potwierdzenie wisi, krótka odpowiedź "tak"/"nie"
        // należy do NIEGO, a nie do modelu. Tylko krótka: całe zdanie to nowe
        // polecenie, a nie odpowiedź na pytanie sprzed chwili.
        if (textIsQuestion && _pendingActionConfirmation.value != null) {
            val reply = pl.victor.app.actions.ConfirmationReply.parse(textQuestion)
            val shortEnough = textQuestion.trim().split(Regex("\\s+")).size <= CONFIRMATION_MAX_WORDS
            if (shortEnough &&
                reply != pl.victor.app.actions.ConfirmationReply.Reply.UNCLEAR
            ) {
                diag.event(
                    DiagFormat.Phase.AKCJA, "potwierdzenie głosem z nowej tury",
                    mapOf("odpowiedź" to reply.name, "tekst" to textQuestion.take(40))
                )
                if (reply == pl.victor.app.actions.ConfirmationReply.Reply.YES) {
                    confirmAction()
                } else {
                    cancelAction()
                }
                return
            }
        }

        // Nowa tura to koniec czekania na odpowiedź głosem: dwa nasłuchy na
        // jednym mikrofonie blokują się nawzajem. Samo okno zostaje - decyzja
        // ma dokąd wrócić.
        confirmationJob?.cancel()

        // === KOMENDY STERUJĄCE ROZMOWĄ (persona, reset) - zanim cokolwiek innego ===
        // Muszą być sprawdzone przed detekcją akcji: "bądź Sterna" nie pasuje do
        // żadnego wzorca akcji, więc poleciałoby jako zwykłe pytanie do AI.
        if (textIsQuestion && textQuestion.isNotBlank() && handleMetaCommand(textQuestion)) {
            return
        }

        // === WARSTWA 0: ODRUCH ===
        // Tylko komendy krytyczne czasowo. Reszta ma iść do AI, bo wzorce nie
        // rozumieją intencji ("daj znać Ani, że się spóźnię" to też SMS).
        if (!forceVision && textIsQuestion) {
            // NAJPIERW komendy zdefiniowane przez użytkownika. Fraza wpisana
            // ręcznie jest jednoznaczną deklaracją intencji - mocniejszą niż
            // nasze wzorce i niż domysł modelu. Kto ustawił "dobranoc" na
            // zgaszenie latarki, ma dostać zgaszoną latarkę, a nie rozmowę o
            // spaniu.
            val custom = pl.victor.app.actions.CustomCommands.match(
                textQuestion,
                settings.getCustomCommands()
            )?.let { pl.victor.app.actions.CustomCommands.toAction(it) }
            if (custom != null) {
                Log.i(TAG, "Warstwa 0: własna komenda -> ${custom.type.name}")
                if (custom.type == pl.victor.app.actions.ActionType.TAKE_PHOTO) {
                    handleUserTrigger(trigger, PHOTO_ON_DEMAND_QUESTION, forceVision = true)
                } else {
                    handleActions(listOf(custom), textQuestion)
                }
                return
            }

            // WIEDZA O SOBIE - z katalogu, bez pytania modelu.
            //
            // Model nie wie, jakie persony ma aplikacja, bo lista nigdy do
            // niego nie docierała: w dzienniku z 21:55 na „jakie persony mamy
            // dostępne" odpowiedział tym, co mu się wydawało. Doklejenie
            // katalogu do promptu naprawiłoby wiedzę kosztem KAŻDEJ tury -
            // także tej o pogodzie - a prompt i tak sięga już 11 tysięcy
            // znaków. Odpowiadamy więc na miejscu, z danych, które aplikacja
            // ma; tura kończy się w ułamku sekundy zamiast po kilkunastu.
            pl.victor.app.actions.SelfKnowledge.topicOf(textQuestion)?.let { topic ->
                val speech = when (topic) {
                    pl.victor.app.actions.SelfKnowledge.Topic.PERSONAS -> describePersonas()
                    pl.victor.app.actions.SelfKnowledge.Topic.CAPABILITIES -> describeCapabilities()
                }
                Log.i(TAG, "Warstwa 0: pytanie o samego siebie ($topic)")
                diag.event(
                    DiagFormat.Phase.AKCJA, "odpowiadam z katalogu, bez modelu",
                    mapOf("temat" to topic.name)
                )
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                return
            }

            // FAKTY O UŻYTKOWNIKU - przed notatkami, bo granica jest tu prosta i
            // ma taka zostać: "zapamiętaj" mówi o CZŁOWIEKU, "zapisz" o zadaniu.
            // Model potrafiłby na jedno i drugie odpowiedzieć "dobrze, zapamiętam"
            // i nie zapisać niczego - a to gorsze niż odmowa.
            pl.victor.app.memory.UserFacts.extract(textQuestion)?.let { fact ->
                val facts = settings.addFact(fact)
                val speech = "Zapamiętane. Wiem o Tobie ${facts.size} rzeczy."
                Log.i(TAG, "Warstwa 0: nowy fakt o użytkowniku")
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                return
            }
            pl.victor.app.memory.UserFacts.extractForget(textQuestion)?.let { what ->
                val before = settings.getFacts().size
                val facts = settings.forgetFacts(what)
                val removed = before - facts.size
                val speech = if (removed > 0) {
                    "Zapomniane. Zostało ${facts.size}."
                } else {
                    "Nie mam nic takiego zapisanego."
                }
                Log.i(TAG, "Warstwa 0: zapominanie faktów (usunięto $removed)")
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                return
            }
            if (pl.victor.app.memory.UserFacts.isListRequest(textQuestion)) {
                val speech = pl.victor.app.memory.UserFacts.speak(settings.getFacts())
                Log.i(TAG, "Warstwa 0: wyliczenie faktów")
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                return
            }

            // NOTATKA, KTÓREJ TREŚĆ PISZE MODEL - sprawdzana PRZED zwykłą.
            //
            // "Zrób notatkę o tym zamku" nie jest notatką o treści "o tym
            // zamku". Taka wypowiedź nie NIESIE treści, tylko ODSYŁA - do tego,
            // na co użytkownik patrzy, albo do tego, co przed chwilą usłyszał.
            // Zwykła ścieżka zapisałaby sam odsyłacz i wyglądałoby to na
            // działającą funkcję, dopóki ktoś nie zajrzy do notatnika.
            //
            // Kolejność jest tu warunkiem poprawności, a nie preferencją: obie
            // funkcje łapią te same zwroty otwierające, więc ta bardziej
            // szczegółowa musi być pierwsza.
            // PYTANIE Z PROŚBĄ O NOTATKĘ NA KOŃCU - sprawdzane jeszcze wcześniej.
            //
            // "Opowiedz mi o zamku w Bodrum I ZRÓB Z TEGO NOTATKĘ" nie ma jeszcze
            // materiału: on dopiero powstanie z odpowiedzi na to samo pytanie.
            // Dotąd całość szła do modelu, a model - zgodnie z tym, co ma
            // napisane w poleceniu - tłumaczył, że tej notatki nie zapisał, i
            // podawał formułę "Notatka: ...". Wyglądało to na upór aplikacji, a
            // było brakiem jednego wzorca.
            pl.victor.app.notes.Notes.trailingNoteRequest(textQuestion)?.let { question ->
                Log.i(TAG, "Warstwa 0: pytanie z prośbą o notatkę na końcu")
                handleUserTrigger(trigger, question, saveAsNote = true)
                return
            }

            pl.victor.app.notes.Notes.describeRequest(textQuestion)?.let { request ->
                // WSKAZANIE ROZSTRZYGAMY TUTAJ, bo tylko tu wiadomo, czy jest o
                // czym pisać. "Zrób notatkę o tym zamku" znaczy co innego zaraz
                // po opowieści o zamku, a co innego, gdy użytkownik stoi przed
                // zamkiem i milczał do tej pory. Wcześniej wszystkie takie
                // zwroty szły na sztywno po zdjęcie - stąd zgłoszenie, że
                // asystent opowiada o Bodrum, a notatka wychodzi z pokoju.
                val source = if (request.source == pl.victor.app.notes.Notes.Source.RECENT) {
                    if (!_lastResponse.value?.text.isNullOrBlank()) {
                        pl.victor.app.notes.Notes.Source.LAST_ANSWER
                    } else {
                        pl.victor.app.notes.Notes.Source.SIGHT
                    }
                } else {
                    request.source
                }
                when (source) {
                    pl.victor.app.notes.Notes.Source.LAST_ANSWER -> {
                        val material = _lastResponse.value?.text
                        if (material.isNullOrBlank()) {
                            // Cicha porażka byłaby tu najgorsza: użytkownik
                            // odchodzi przekonany, że ma notatkę.
                            val speech = "Nie mam jeszcze z czego zrobić notatki - " +
                                "najpierw o coś zapytaj."
                            audio.speak(speech, language = settings.getResponseLanguage())
                            _state.value = OrchestratorState.Completed(speech)
                            return
                        }
                        Log.i(TAG, "Warstwa 0: notatka z ostatniej odpowiedzi")
                        handleUserTrigger(
                            trigger,
                            pl.victor.app.notes.Notes.noteFromTextPrompt(
                                material, request.topic
                            ),
                            saveAsNote = true
                        )
                    }
                    else -> {
                        // SIGHT - i tylko on, bo RECENT jest wyżej zamieniany na
                        // jedno z dwóch konkretnych źródeł.
                        Log.i(TAG, "Warstwa 0: notatka z tego, co widzą okulary")
                        handleUserTrigger(
                            trigger,
                            pl.victor.app.notes.Notes.noteFromSightPrompt(request.topic),
                            forceVision = true,
                            saveAsNote = true
                        )
                    }
                }
                return
            }

            // NOTATKI - przed modelem, bo "zapisz, że mam kupić mleko" ma się
            // zapisać, a nie stać się tematem rozmowy. Model potrafiłby na to
            // odpowiedzieć "dobrze, zapamiętam" i nie zapisać niczego - a to
            // gorsze niż odmowa, bo użytkownik jest przekonany, że ma notatkę.
            pl.victor.app.notes.Notes.extract(textQuestion)?.let { body ->
                // Zapis idzie NAJPIERW i zawsze dosłownie. Porządkowanie przez
                // model jest opcjonalne i może się nie udać - a notatka, która
                // czeka na odpowiedź z sieci, to notatka, którą można stracić.
                val notes = settings.addNote(body)
                val speech = "Zapisane. Masz teraz ${notes.size} notatek."
                Log.i(TAG, "Warstwa 0: nowa notatka")
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                if (settings.getNoteStyle() == pl.victor.app.notes.Notes.Style.AI) {
                    scope.launch { tidyNote(body) }
                }
                return
            }
            if (pl.victor.app.notes.Notes.isListRequest(textQuestion)) {
                val speech = pl.victor.app.notes.Notes.speak(settings.getNotes())
                Log.i(TAG, "Warstwa 0: odczytanie notatek")
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                return
            }

            // PAMIĘĆ MIEJSCA - "zapamiętaj, gdzie zaparkowałem" i "gdzie
            // zaparkowałem".
            //
            // W warstwie 0 z tego samego powodu co trasa: model zapytany o to,
            // gdzie stoi samochód, ODPOWIE - i odpowiedź będzie zmyślona, bo
            // nie ma skąd znać współrzędnych sprzed godziny. Zapamiętanie musi
            // przy tym kosztować jedno zdanie w chwili odchodzenia, więc nie
            // może czekać na obieg przez sieć.
            if (textIsQuestion) {
                pl.victor.app.memory.PlaceMemory.saveRequest(textQuestion)?.let { name ->
                    handlePlaceSave(name)
                    return
                }
                pl.victor.app.memory.PlaceMemory.recallRequest(textQuestion)?.let { name ->
                    handlePlaceRecall(name)
                    return
                }
            }

            // TRASA - w warstwie 0, nie w zapasowej.
            //
            // Model, który dostaje "nawiguj do najbliższej biedronki",
            // ODPOWIADA na to słowami. W dzienniku z 15 września, 20:52:01,
            // zapowiedział włączenie nawigacji i nie uruchomił niczego: żadnej
            // akcji w logu, żadnego Intentu, a użytkownik poszedł dalej
            // przekonany, że trasa leci. Zapowiedź bez wykonania jest gorsza
            // niż odmowa.
            //
            // Wzorzec jest ścisły (czasownik ruchu + "do" + cel), więc nie
            // przechwytuje rozmowy o drodze - patrz
            // [SmartActionDetector.detectNavigation].
            if (textIsQuestion) {
                actionDetector.detectNavigation(textQuestion)?.let { route ->
                    Log.i(TAG, "Warstwa 0: trasa do ${route.destination}")
                    handleActions(listOf(route), textQuestion)
                    return
                }
            }

            val critical = actionDetector.detectCritical(textQuestion)
            if (critical.isNotEmpty()) {
                // "Zrób zdjęcie" nie jest akcją do wykonania przez Intent - to
                // wejście w ścieżkę obrazu, tyle że bez pytania od usera.
                if (critical.any { it.type == pl.victor.app.actions.ActionType.TAKE_PHOTO }) {
                    Log.i(TAG, "Warstwa 0: zdjęcie na komendę")
                    handleUserTrigger(trigger, PHOTO_ON_DEMAND_QUESTION, forceVision = true)
                } else {
                    Log.i(TAG, "Warstwa 0: ${critical.joinToString { it.type.name }}")
                    handleActions(critical, textQuestion)
                }
                return
            }
        }

        // === WARSTWA 2: AWARIA (gdy nie ma czym uruchomić warstwy 1) ===
        // Sprawdzane tu, a nie po teście okularów, bo "wyślij SMS do Ani" ma
        // zadziałać także wtedy, gdy okularów nie ma w pobliżu.
        val providerId = settings.getActiveProvider()
        if (!settings.hasApiKey(providerId)) {
            val fallback = if (textIsQuestion) actionDetector.detect(textQuestion) else emptyList()
            if (fallback.isNotEmpty()) {
                Log.i(TAG, "Warstwa 2 (brak AI): ${fallback.joinToString { it.type.name }}")
                handleActions(fallback, textQuestion)
                return
            }
            _state.value = OrchestratorState.Error("Brak klucza API. Ustawienia → Klucz API.")
            return
        }

        // Zdjęcie z góry robimy tylko wtedy, gdy user sam o nie poprosił - fizycznym
        // przyciskiem na okularach - albo gdy zleciła to warstwa 1 znacznikiem
        // take_photo (forceVision). Głos, wake word i tekst idą najpierw jako samo
        // pytanie; jeśli model potrzebuje zobaczyć, sam się o to upomni. Wcześniej
        // aparat startował przy każdym pytaniu i nawet "ile to 20 euro w złotych"
        // czekało na transfer pięciu zdjęć, zanim poszło do modelu.
        val glassesReady = glassesManager.connectionState.value == ConnectionState.READY

        // "Co właśnie widzę", "przeczytaj to", "co to za budynek" - pytania,
        // których BEZ obrazu nie da się sensownie odpowiedzieć. Projekt zakładał,
        // że model sam o zdjęcie poprosi znacznikiem take_photo; w praktyce robi
        // to niesystematycznie i zgłoszono, że takie pytania w ogóle nie
        // uruchamiają aparatu. Rozpoznajemy je więc sami - pewnie i bez
        // dodatkowej tury. Prośba modelu zostaje jako uzupełnienie dla zdań,
        // których wzorce nie łapią.
        //
        // Tylko przy połączonych okularach: bez nich wymuszenie obrazu zamieniło
        // by zwykłe pytanie w błąd "okulary nie są połączone".
        val wantsToLook = glassesReady && audioQuestion == null &&
            actionDetector.needsVision(textQuestion)
        if (wantsToLook) Log.i(TAG, "Pytanie o to, co widać - robię zdjęcie bez pytania modelu")

        val useVision = forceVision || trigger == TriggerSource.BUTTON || wantsToLook
        diag.event(
            DiagFormat.Phase.ZDJĘCIE, if (useVision) "robię zdjęcie" else "bez zdjęcia",
            mapOf(
                "okularyGotowe" to glassesReady,
                "wzorzecWidzenia" to wantsToLook,
                "wymuszone" to forceVision,
                "zNagrania" to (audioQuestion != null)
            )
        )

        if (useVision && !glassesReady) {
            // Przycisk na okularach to z założenia pytanie o otoczenie -
            // bez okularów nie ma o czym rozmawiać.
            _state.value = OrchestratorState.Error("Okulary nie są połączone. Ustawienia → Połącz.")
            return
        }
        if (!useVision) {
            Log.i(TAG, "Pytam AI bez zdjęcia - obraz dojdzie tylko jeśli model o niego poprosi")
        }

        activeTurnJob = scope.launch {
            // Powtórka ze zdjęciem to DALSZY CIĄG tego samego pytania użytkownika,
            // tylko z drugim obiegiem modelu. Bez tej flagi `finally` zamykało
            // turę zanim powtórka zdążyła ruszyć, a że zamknięcie zeruje licznik,
            // najdłuższa i najciekawsza tura zapisywała się w dzienniku BEZ
            // czasów - czyli ginął dokładnie ten pomiar, dla którego dziennik
            // powstał. Turę zamyka wtedy `finally` powtórki.
            var handedOffToRetry = false
            // Łącze audio do okularów bierzemy na CAŁĄ turę - i na słuchanie, i
            // na mówienie. Zestawienie SCO trwa nawet kilka sekund, więc
            // podnoszenie go osobno pod każdy fragment rwałoby rozmowę.
            // Zwraca false, gdy okulary nie są sparowane jako zestaw audio -
            // wtedy wszystko idzie przez telefon, tak jak dotąd.
            // Tura z modelem bywa dłuższa niż nasłuch: zdjęcie, kontekst,
            // odpowiedź i jej odczytanie. Bez blokady przy zgaszonym ekranie
            // potrafi utknąć w połowie.
            pauseWakeWordMic()
            wakeLock.acquire(LOCK_TURN, TURN_WAKE_LOCK_MS)
            // PROFIL ROZMOWY (SCO) NIE JEST TU DO NICZEGO POTRZEBNY.
            //
            // Ta część tury już nie słucha - nasłuch skończył się piętro wyżej.
            // Zostaje mówienie, a do mówienia wystarczy A2DP, czyli ten sam
            // profil, którym idzie muzyka. SCO służy WYŁĄCZNIE mikrofonowi
            // zestawu.
            //
            // A brane było na całą turę - i to jest druga, po SpeechToText,
            // przyczyna zgłoszenia "okulary łączą się jako używane do połączeń,
            // a nie do odtwarzania". Android trzyma urządzenie w trybie rozmowy
            // tak długo, jak ktoś trzyma SCO; przy profilu branym co turę
            // przełącznik multimediów w ustawieniach systemu wracał sam do
            // wyłączenia.
            //
            // Router sam dobiera atrybuty: bez SCO wypowiedź idzie jako
            // USAGE_ASSISTANT, czyli przez A2DP (patrz ttsAudioAttributes).
            //
            // Wyjątek: gdy okulary są sparowane WYŁĄCZNIE jako zestaw
            // głośnomówiący, A2DP nie istnieje i jedyną drogą do ich głośnika
            // jest SCO. Wtedy je bierzemy - bo odpowiedź z głośnika telefonu w
            // kieszeni jest gorsza niż tryb rozmowy.
            val canUseMedia = audio.canSpeakOverMedia()
            if (!canUseMedia) {
                // Bierzemy SCO, bo trzeba - ale przy okazji prosimy okulary,
                // żeby wróciły do trybu multimediów. Inaczej raz utracony A2DP
                // nie wraca nigdy: każda tura bierze profil rozmowy, a profil
                // rozmowy nie pozwala A2DP wstać.
                glassesManager.requestClassicAudio("brak A2DP przed odpowiedzią")
            }
            val audioHeld = if (canUseMedia) false else audio.beginConversationRouting()

            // ZWIŃ PROFIL ROZMOWY, ZANIM ZACZNIESZ MÓWIĆ PRZEZ A2DP.
            //
            // To jest przyczyna zgłoszenia "odpowiedzi są ucinane po kilku
            // słowach", a zarazem moja regresja z poprawki mikrofonu okularów.
            // W dzienniku z 13 września DZIEWIĘĆ tur na dziesięć ma:
            //
            //     mowa przez A2DP - bez profilu rozmowy  a2dp=true scoStoi=true
            //
            // Te dwie rzeczy wykluczają się na sprzęcie. SCO to profil rozmowy,
            // wąskopasmowy - gdy stoi, zestaw Bluetooth jest w trybie rozmowy i
            // strumień multimediów przez niego nie przechodzi. Wypowiedź szła
            // więc drogą, która w tym momencie była martwa.
            //
            // Samo "nie bierzemy SCO do mowy" (wiersz wyżej) nie wystarcza, bo
            // SCO nie wzięła mowa - wziął je NASŁUCH, a zwalnia się je z
            // karencją (patrz BluetoothAudioRouter.release). Karencja jest tam
            // sensowna: chroni przed rozbieraniem i stawianiem łącza między
            // pytaniem a pytaniem. Tyle że tu trafia dokładnie w czas mowy.
            //
            // Gałąź ciszy kilkaset wierszy wyżej robiła to od dawna i z tego
            // samego powodu - brakowało tego samego kroku na ścieżce udanej
            // odpowiedzi.
            if (canUseMedia && audio.isRoutedToBluetooth()) {
                audio.resetConversationRouting()
            }

            diag.event(
                DiagFormat.Phase.AUDIO,
                if (canUseMedia) "mowa przez A2DP - bez profilu rozmowy"
                else "brak A2DP - biorę profil rozmowy",
                mapOf("a2dp" to canUseMedia, "scoStoi" to audio.isRoutedToBluetooth())
            )
            try {
                // 1. CAPTURE - adaptacyjny tryb
                val provider = getOrCreateProvider()
                val capabilities = provider.capabilities

                // KLATKA ZE STRUMIENIA MA PIERWSZEŃSTWO PRZED MIGAWKĄ.
                //
                // Tryby ciągłe robiły tak od dawna (AccessibilityService), a ta
                // ścieżka - przycisk, pytanie o obraz - nie. Dziennik z 15
                // września mówi, ile to kosztowało: W KAŻDEJ próbie oryginał
                // przez Wi-Fi Direct nie doszedł, model dostał miniaturę
                // 13-27 kB, a samo czekanie na nieudany hotspot zjadało circa
                // 10 sekund:
                //
                //     próba 0: miniatura  bajtów=17761
                //     Hotspot okularów: gotowe  ms=10315
                //     Wi-Fi Direct nie oddał oryginału  ms=10395
                //     przechwycone  bajtów=17761 pełnaRozdzielczość=false
                //
                // Ten sam dziennik pokazuje, że strumień działa bez zarzutu i
                // oddaje 1600x1200 (Klatki: PIERWSZA KLATKA WYJĘTA). Gdy więc
                // strumień JUŻ STOI, migawka jest w tym momencie gorsza na obu
                // osiach naraz: wolniejsza i w gorszej jakości.
                //
                // Warunek jest celowo wąski - tylko gdy strumień już chodzi.
                // Podnoszenie go pod jedno pytanie kosztuje circa 12 s i o tym
                // ma decydować tryb, a nie ta gałąź.
                val streamFrame = if (useVision && glassesManager.isLiveVisionRunning) {
                    val wantsText = pl.victor.app.ai.VisionDetail.needsDetail(textQuestion)
                    glassesManager.liveFrame(detail = wantsText)?.also { frame ->
                        diag.event(
                            DiagFormat.Phase.ZDJĘCIE, "klatka ze strumienia zamiast migawki",
                            mapOf("bajtów" to frame.size, "szczegół" to wantsText)
                        )
                    }
                } else {
                    null
                } ?: streamFrameForText(useVision, textQuestion)

                val captureResult = if (useVision && streamFrame == null) {
                    val preferredMode = pl.victor.app.ai.CaptureMode.valueOf(
                        settings.getPreferredCaptureMode()
                    )
                    val decision = captureModeSelector.select(
                        preferred = preferredMode,
                        capabilities = capabilities,
                        autoDegrade = settings.isAutoDegradeCaptureEnabled()
                    )
                    Log.i(TAG, "Capture decision: ${decision.mode} (${decision.reason})")

                    // Czytanie tekstu to inna potrzeba niż "co przede mną jest".
                    // Miniatura po BLE nie niesie liter z bliska - trzeba
                    // oryginału z pamięci okularów, przez Wi-Fi Direct.
                    val wantsDetail = settings.isFullResolutionVisionEnabled() &&
                        !decision.mode.requiresVideo &&
                        pl.victor.app.ai.VisionDetail.needsDetail(textQuestion)
                    if (wantsDetail) {
                        Log.i(TAG, "Pytanie o szczegół - biorę zdjęcie w pełnej rozdzielczości")
                    }

                    // Przy pełnej rozdzielczości zdjęcie jest JEDNO: pobranie
                    // oryginału idzie przez Wi-Fi i trwa kilkanaście sekund.
                    val total = if (wantsDetail) 1 else decision.mode.expectedImageCount.coerceAtLeast(1)
                    _state.value = OrchestratorState.Capturing(progress = 0, total = total)

                    // Dla trybów seryjnych respektuj liczbę zdjęć i odstęp z ustawień.
                    val isBurstMode = !decision.mode.requiresVideo &&
                        decision.mode.expectedImageCount > 1
                    if (wantsDetail) {
                        _state.value = OrchestratorState.Capturing(
                            progress = 0,
                            total = 1,
                            label = "Czytam tekst - biorę zdjęcie w pełnej jakości. " +
                                "Idzie przez Wi-Fi okularów, więc potrwa kilkanaście sekund."
                        )
                    }
                    capture.capture(
                        mode = decision.mode,
                        // Skalowanie w dół tuż po pobraniu oryginału zjadałoby
                        // dokładnie to, po co po niego poszliśmy.
                        resolution =
                            if (wantsDetail) pl.victor.app.ai.ImageResolution.ULTRA
                            else decision.resolution,
                        countOverride =
                            if (isBurstMode && !wantsDetail) settings.getCaptureCount() else null,
                        intervalMsOverride =
                            if (isBurstMode && !wantsDetail) settings.getCaptureIntervalMs()
                            else null,
                        preferFullResolution = wantsDetail
                    ) { progress ->
                        _state.value = OrchestratorState.Capturing(
                            progress = progress,
                            total = total,
                            label = if (wantsDetail) {
                                "Zdjęcie zrobione - pobieram oryginał z okularów."
                            } else {
                                null
                            }
                        )
                    }
                } else {
                    null
                }

                val photos = streamFrame?.let { listOf(it) } ?: captureResult?.images.orEmpty()
                if (useVision) {
                    diag.event(
                        DiagFormat.Phase.ZDJĘCIE, "przechwycone",
                        mapOf(
                            "sztuk" to photos.size,
                            "bajtów" to photos.sumOf { it.size },
                            "źródło" to if (streamFrame != null) "strumień" else "migawka",
                            "pełnaRozdzielczość" to
                                (streamFrame != null || glassesManager.lastPhotoWasFullResolution),
                            "powódBłędu" to glassesManager.lastPhotoFailure
                        )
                    )
                }
                val video = captureResult?.video
                val videoDurationMs = captureResult?.videoDurationMs ?: 0L

                // Puste zdjęcia są błędem tylko wtedy, gdy mieliśmy je zrobić.
                if (useVision && photos.isEmpty()) {
                    // Powód bierzemy od okularów. Samo "nie udało się pobrać
                    // zdjęcia" nie mówiło NIC - a przyczyny są różne i wymagają
                    // różnych rzeczy: pełna pamięć okularów, zbyt duża
                    // odległość, trwające nagranie wideo.
                    val why = glassesManager.lastPhotoFailure
                    val message =
                        if (why != null) "Nie udało się zrobić zdjęcia. $why"
                        else "Nie udało się pobrać żadnego zdjęcia"
                    _state.value = OrchestratorState.Error(message)
                    // I POWIEDZ TO NA GŁOS.
                    //
                    // Zgłoszone dosłownie: "zrobiło 3 zdjęcia, nie dostałem
                    // odpowiedzi - przy zablokowanym telefonie (...) odblokowałem
                    // i mam komunikat, że nie udało się zrobić zdjęcia". Komunikat
                    // BYŁ, tylko lądował wyłącznie na ekranie, a ekran był zgaszony
                    // w kieszeni. Z perspektywy człowieka w okularach wygląda to
                    // identycznie jak zawieszenie: pytanie poszło, zapadła cisza.
                    //
                    // Pozostałe wyjścia błędem z tury już mówią (patrz
                    // [announceTurnFailure]); to jedno się nie załapało, bo wychodzi
                    // wcześniej, własnym `return@launch`, a nie przez blok catch.
                    announceTurnFailure(trigger, message)
                    return@launch
                }

                // 1b. Skan QR (offline, ML Kit)
                //
                // Kod na MINIATURZE jest nie do odczytania - to nie jest kwestia
                // biblioteki, tylko liczby pikseli: kwadraty QR zlewają się w
                // szarą plamę. Zgłoszone jako "AI nie czyta kodów QR".
                val scannedCodes = mutableListOf<ScannedCode>()
                fun scanInto(imageBytes: ByteArray) {
                    qrScanner.scanImageBytesSync(imageBytes).forEach { code ->
                        if (scannedCodes.none { it.rawValue == code.rawValue }) {
                            scannedCodes.add(code)
                        }
                    }
                }
                val asksAboutCode = pl.victor.app.ai.VisionDetail.isAboutCode(textQuestion)
                // Przy zwykłym pytaniu skanujemy JEDNO zdjęcie, nie całą serię.
                // Każdy skan ma własny limit czasu, więc pięć zdjęć to pięć razy
                // tyle czekania - a kod widoczny na jednej klatce serii jest
                // widoczny i na pierwszej. Całą serię przeglądamy tylko wtedy,
                // gdy pytanie faktycznie dotyczy kodu.
                if (asksAboutCode) photos.forEach { scanInto(it) } else photos.firstOrNull()?.let { scanInto(it) }
                // Druga próba, na ORYGINALE z pamięci okularów. Wchodzi tylko
                // wtedy, gdy pytanie faktycznie dotyczy kodu, a pierwsza próba
                // nic nie dała - bo kosztuje kilkanaście sekund (Wi-Fi Direct).
                if (asksAboutCode && scannedCodes.isEmpty() &&
                    !glassesManager.lastPhotoWasFullResolution
                ) {
                    Log.i(TAG, "Kod nieodczytany z miniatury - próbuję na pełnym zdjęciu")
                    _state.value = OrchestratorState.Capturing(
                        progress = 1,
                        total = 1,
                        label = "Nie widzę kodu na podglądzie - pobieram ostrzejsze zdjęcie."
                    )
                    glassesManager.captureSharpPhoto()?.let { scanInto(it) }
                }

                if (scannedCodes.isNotEmpty()) {
                    Log.i(TAG, "Wykryto ${scannedCodes.size} kod(ów): ${scannedCodes.map { it.format }}")
                } else if (asksAboutCode) {
                    Log.w(TAG, "Pytanie o kod, ale żadnego nie odczytano")
                }

                // 1b-bis. KOD KRESKOWY PRODUKTU -> CO TO JEST.
                //
                // EAN-13 i EAN-8 skanowaliśmy od dawna, ale kod produktu
                // kończył jako trzynaście cyfr przeczytanych na głos - czyli
                // informacja zerowa. Baza Open Food Facts oddaje nazwę, markę,
                // gramaturę i ALERGENY, czyli dokładnie to, czego na froncie
                // opakowania nie ma, a model patrzący na zdjęcie nie ma skąd
                // wziąć.
                //
                // Dla osoby niewidomej w sklepie to jest różnica między "chyba
                // płatki" a "płatki owsiane, 500 g, zawiera gluten". Nie
                // kosztuje przy tym ani jednego tokenu modelu.
                var productContext: String? = null
                val productCode = scannedCodes.firstOrNull {
                    it.format == "EAN_13" || it.format == "EAN_8"
                }
                if (productCode != null) {
                    productLookup.describe(productCode.rawValue)?.let { described ->
                        productContext = described
                        diag.event(
                            DiagFormat.Phase.ZDJĘCIE, "produkt rozpoznany z kodu",
                            mapOf("kod" to productCode.rawValue, "opis" to described)
                        )
                    } ?: diag.event(
                        DiagFormat.Phase.ZDJĘCIE, "kodu nie ma w bazie produktów",
                        mapOf("kod" to productCode.rawValue)
                    )
                }

                // 1c. URL z QR - fetch content jeśli user chce info
                var webContext: WebContent? = null
                if (scannedCodes.isNotEmpty() && shouldFetchUrl(textQuestion, scannedCodes)) {
                    _state.value = OrchestratorState.Thinking
                    webContext = urlAnalyzer.fetchFirstUrl(scannedCodes)
                    if (webContext != null) {
                        Log.i(TAG, "Pobrany URL: ${webContext.url}, ${webContext.totalChars()} znaków")
                    }
                }

                // 1d. OCR - czytaj tekst z otoczenia
                var ocrContext: OCRResult? = null
                if (photos.isNotEmpty() && shouldRunOcr(textQuestion)) {
                    _state.value = OrchestratorState.Thinking
                    // Pierwsze zdjęcie - zazwyczaj ostre, dobre do OCR
                    val firstPhotoBytes = photos.first()
                    ocrContext = ocrReader.readBytes(firstPhotoBytes)
                    if (ocrContext.isSuccess) {
                        Log.i(TAG, "OCR odczytał ${ocrContext.fullText.length} znaków z ${ocrContext.blocks.size} bloków")
                    }
                }

                // 2. AI - streaming
                _state.value = OrchestratorState.Thinking

                // provider już zadeklarowany wyżej dla capabilities

                // Pobierz aktywną personę (system prompt) i dołóż instrukcję o
                // znaczniku [[ACTION: ...]] - patrz SmartActionDetector.detectAiMarkedActions
                // i executeAiDetectedActions niżej, gdzie odpowiedź jest tym skanowana.
                val persona = getActivePersona()
                // Model musi wiedzieć, czy w TEJ wiadomości dostał obraz i czy w
                // ogóle ma jak go dostać - inaczej albo prosi o zdjęcie, które już
                // ma, albo prosi o nie przy odłączonych okularach.
                val visionStatus = when {
                    photos.isNotEmpty() || (video != null && video.isNotEmpty()) ->
                        "\n\nOBRAZ: masz zdjęcie z kamery okularów w tej wiadomości - " +
                            "odpowiadaj na jego podstawie i NIE proś o kolejne."
                    glassesManager.connectionState.value == ConnectionState.READY ->
                        "\n\nOBRAZ: nie masz zdjęcia, ale okulary są połączone - " +
                            "jeśli musisz zobaczyć, o co pyta user, użyj [[ACTION: type=take_photo]]."
                    else ->
                        "\n\nOBRAZ: nie masz zdjęcia i okulary nie są połączone - " +
                            "nie proś o take_photo, powiedz wprost, że nie możesz tego zobaczyć."
                }
                val effectiveSystemPrompt = persona.systemPrompt +
                    "\n\n" + pl.victor.app.actions.SmartActionDetector.AI_ACTION_CAPABILITIES_PROMPT +
                    visionStatus +
                    NOTES_CAPABILITY_PROMPT +
                    PRIVATE_DATA_HONESTY_PROMPT +
                    ENGLISH_QUOTING_PROMPT
                Log.d(TAG, "Using persona: ${persona.name}")

                // 1d2. Wizytówka vCard z kodu QR
                val contactCard = scannedCodes
                    .asSequence()
                    .map { it.rawValue }
                    .filter { pl.victor.app.vision.VCardParser.looksLikeContact(it) }
                    .mapNotNull { pl.victor.app.vision.VCardParser.parse(it) }
                    .firstOrNull()
                if (contactCard != null) {
                    Log.i(TAG, "Odczytano wizytówkę: ${contactCard.name}")
                }

                // 1e0. Data i godzina. Model ich NIE ZNA - nie ma zegara, a jego
                // wiedza kończy się na dacie treningu. Bez tego "jaki dziś
                // dzień", "ile zostało do piątku" czy "umów na jutro na 15"
                // były zgadywaniem podanym pewnym głosem.
                val timeContext = buildTimeContext()

                // 1e5. Notatki - gdy pytanie ich dotyczy. Odczytanie na żądanie
                // ("przeczytaj notatki") poszło już warstwą 0; tu chodzi o
                // pytania W OPARCIU o notatki, na które model ma odpowiedzieć.
                // Lokalne, więc bez korutyny - i tak wraca natychmiast.
                // PYTANIE PRZYSZŁO NAGRANIEM = NIE MAMY CZEGO DOPASOWYWAĆ.
                //
                // Wszystkie bramki niżej patrzą na słowa w `textQuestion`. Gdy
                // pytanie idzie do modelu jako DŹWIĘK, ten tekst jest tylko
                // instrukcją ("odpowiedz na pytanie z nagrania") i nie zawiera ani
                // jednego słowa użytkownika. Żadna bramka więc nie trafiała i model
                // dostawał nagranie bez kalendarza, pogody, poczty i notatek -
                // a prompt dodatkowo kazał mu powiedzieć, że ich nie sprawdzi.
                //
                // Na telefonie bez lokalnego rozpoznawania mowy TĄ drogą idzie
                // KAŻDE pytanie głosowe, więc kalendarz i pogoda były przez głos
                // nieosiągalne. Zgłoszone: "model nie dostaje transkrypcji, tylko
                // plik z głosem".
                //
                // Świadomy koszt: przy nagraniu dociągamy wszystko, także wtedy,
                // gdy pytanie brzmiało "ile to jest dwa plus dwa". Inaczej się nie
                // da - nie wiemy, o co pytano. Konteksty i tak lecą równolegle,
                // więc kosztuje to najdłuższy z nich, a nie ich sumę. Koszt
                // znika sam, gdy tura ma transkrypcję.
                val audioTurn = audioQuestion != null
                val notesContext = buildNotesContext(textQuestion, force = audioTurn)

                // Fakty o użytkowniku idą do modelu ZAWSZE, bez bramki słów
                // kluczowych - inaczej asystent, który wie, jak masz na imię,
                // pamiętałby o tym tylko wtedy, gdy zapytasz o imię. Jest ich
                // kilkanaście, są krótkie i lokalne, więc nic nie kosztują.
                val factsContext =
                    pl.victor.app.memory.UserFacts.buildPromptContext(settings.getFacts())

                // === KONTEKSTY RÓWNOLEGLE ===
                //
                // Szły dotąd JEDEN PO DRUGIM: pamięć (baza), kalendarz (sieć),
                // poczta (sieć), pogoda (sieć), lokalizacja (GPS plus
                // geokodowanie) i tłumaczenie. Każde z osobna to ułamek sekundy
                // do półtorej - razem kilka sekund CISZY, zanim model w ogóle
                // dostanie pytanie. A one o sobie nie wiedzą i niczego od siebie
                // nie potrzebują, więc jedyne, co je łączyło, to kolejność linii
                // w tym pliku.
                //
                // runCatching w każdej gałęzi jest tu konieczne: wyjątek z
                // async przewraca całą korutynę tury, a brak pogody nie może
                // kosztować odpowiedzi.
                val contextStartedAtMs = System.currentTimeMillis()
                val memoryDeferred = async { runCatching { buildMemoryContext(textQuestion) }.getOrNull() }
                val calendarDeferred =
                    async { runCatching { buildCalendarContext(textQuestion, audioTurn) }.getOrNull() }
                val gmailDeferred =
                    async { runCatching { buildGmailContext(textQuestion, audioTurn) }.getOrNull() }
                val weatherDeferred =
                    async { runCatching { buildWeatherContext(textQuestion, audioTurn) }.getOrNull() }
                // Gdzie jesteśmy - tylko przy pytaniach ZE ZDJĘCIEM. Model
                // patrzący na sam obraz widzi "kościół"; ten sam obraz plus
                // "Rzym, okolice Piazza Navona" pozwala powiedzieć, KTÓRY.
                val locationDeferred = async {
                    if (photos.isEmpty()) {
                        null
                    } else {
                        runCatching {
                            pl.victor.app.proactive.LocationContext.buildPromptContext(context)
                        }.getOrNull()?.also { Log.i(TAG, "Doklejam kontekst lokalizacji") }
                    }
                }
                val translationDeferred =
                    async { runCatching { translateOcrIfRequested(textQuestion, ocrContext) }.getOrNull() }

                // ...ALE NIE CZEKAMY NA NIE BEZ KOŃCA.
                //
                // Równoległość zdejmuje sumowanie czasów, nie zdejmuje
                // zawieszenia: czekaliśmy na WSZYSTKIE sześć źródeł bez żadnego
                // sufitu, a `GoogleCalendarService` i `GmailService` nie mają
                // ustawionego ANI JEDNEGO limitu czasu (lokalizacja to w dodatku
                // GPS). Jedno źródło, które nie wraca, zatrzymywało całą
                // odpowiedź na dowolnie długo - i nie zostawiało po sobie śladu,
                // bo wiersz „zebrany" powstaje dopiero po fakcie.
                //
                // W dzienniku zwykle 368-492 ms, ale raz 4952 ms. Dziesięciokrotny
                // rozrzut przy stałym zestawie źródeł to nie szum, tylko ten sam
                // mechanizm w łagodnej postaci. Dobry kandydat na zgłoszenie „po
                // jakimś czasie AI przestaje odpowiadać, nawet nie widać, żeby
                // reagowało".
                //
                // Sufit jest bezpieczny, bo BRAK KONTEKSTU JEST JUŻ DZIŚ NORMALNĄ
                // SYTUACJĄ: każde źródło może oddać null (wyłączone w
                // ustawieniach, brak zgody, wygasłe logowanie) i prompt składa
                // się wtedy z tego, co przyszło. Wygaśnięcie limitu prowadzi
                // dokładnie w ten sam stan, a nie w jakiś nowy.
                //
                // WSPÓLNY TERMIN, nie limit na każde źródło z osobna: sześć
                // osobnych limitów pozwoliłoby powolnym źródłom zsumować się do
                // sześciokrotności, a użytkownik czeka na ostatnie z nich. Każde
                // kolejne czekanie dostaje więc tyle, ile ZOSTAŁO do terminu.
                val pending = listOf(
                    memoryDeferred, calendarDeferred, gmailDeferred,
                    weatherDeferred, locationDeferred, translationDeferred
                )
                val contextDeadlineMs = contextStartedAtMs + CONTEXT_BUDGET_MS
                val gathered = pending.map { deferred ->
                    // coerceAtLeast(1): przy zerze withTimeoutOrNull wraca od
                    // razu, NIE WCHODZĄC w blok - a wtedy wyrzucilibyśmy wynik
                    // źródła, które dawno jest gotowe. Przy jednej milisekundzie
                    // `await` na gotowym wyniku nie zawiesza się w ogóle.
                    val leftMs = (contextDeadlineMs - System.currentTimeMillis()).coerceAtLeast(1L)
                    withTimeoutOrNull(leftMs) { deferred.await() }
                }
                // Przerwane czekanie nie kończy samego źródła. Spóźnialec bez
                // odbiorcy trzymałby przy życiu połączenie sieciowe albo nasłuch
                // GPS przez cały czas odpowiadania - a jego wynik i tak nie ma
                // już dokąd trafić.
                val contextTimedOut = pending.any { it.isActive }
                pending.forEach { it.cancel() }
                val memoryContext = gathered[0]
                val calendarContext = gathered[1]
                val gmailContext = gathered[2]
                val weatherContext = gathered[3]
                val locationContext = gathered[4]
                val translatedOcr = gathered[5]
                Log.i(TAG, "Kontekst zebrany w ${System.currentTimeMillis() - contextStartedAtMs} ms")
                // Pytanie użytkownika: "czy to przez przeszukiwanie informacji o
                // użytkowniku?". Ten wiersz odpowiada na nie liczbą - i mówi
                // WHICH źródło doszło, a które nie.
                diag.event(
                    DiagFormat.Phase.KONTEKST, "zebrany",
                    mapOf(
                        "ms" to (System.currentTimeMillis() - contextStartedAtMs),
                        "pamięć" to (memoryContext != null),
                        "kalendarz" to (calendarContext != null),
                        "poczta" to (gmailContext != null),
                        "pogoda" to (weatherContext != null),
                        "lokalizacja" to (locationContext != null),
                        // Bez tego pola „kalendarz=false" znaczy naraz „wyłączony",
                        // „pusty" i „nie zdążył" - a to trzy różne rzeczy i trzy
                        // różne naprawy.
                        "limitCzasu" to contextTimedOut
                    )
                )

                // Buduj prompt z kontekstem: pamięć + URL + OCR + kontekst rozmowy
                val enhancedPrompt = buildString {
                    append(timeContext).append("\n\n")
                    if (factsContext != null) {
                        append(factsContext)
                        append("\n\n")
                    }
                    if (memoryContext != null) {
                        append(memoryContext)
                        append("\n\n")
                    }
                    if (calendarContext != null) {
                        append(calendarContext)
                        append("\n\n")
                    }
                    if (gmailContext != null) {
                        append(gmailContext)
                        append("\n\n")
                    }
                    if (notesContext != null) {
                        append(notesContext)
                        append("\n\n")
                    }
                    if (weatherContext != null) {
                        append(weatherContext)
                        append("\n\n")
                    }
                    if (locationContext != null) {
                        append(locationContext)
                        append("\n\n")
                    }
                    if (webContext != null) {
                        append(urlAnalyzer.buildPromptContext(webContext))
                        append("\n\n")
                    }
                    productContext?.let { described ->
                        append("Kod kreskowy na zdjęciu należy do tego produktu: ")
                        append(described)
                        append(" To są dane z bazy produktów, pewniejsze niż odczyt z ")
                        append("opakowania - jeśli pytanie dotyczy tego produktu, ")
                        append("odpowiedz na ich podstawie.")
                        append("\n\n")
                    }
                    if (ocrContext != null && ocrContext.isSuccess) {
                        append(ocrContext.toPromptContext())
                        append("\n\n")
                    }
                    if (translatedOcr != null) {
                        append(translatedOcr)
                        append("\n\n")
                    }
                    if (contactCard != null) {
                        append(contactCard.toPromptContext())
                        append("Jeśli użytkownik chce, zaproponuj zapisanie kontaktu.\n\n")
                    }
                    append(conversationContext.asSystemContext())
                    append(textQuestion)
                }

                val accumulatedText = StringBuilder()

                // ZUŻYCIE TURY - DOTĄD GUBIONE.
                //
                // Prawdziwa liczba przychodzi TYLKO w ostatnim fragmencie
                // strumienia, a `AIResponse` niżej powstawał bez niej, z
                // domyślnym zerem. Skutek widać było na ekranie historii: przy
                // każdym wpisie stało "0 tokenów", niezależnie od tego, jak
                // długa była odpowiedź.
                //
                // Sumujemy, a nie nadpisujemy: przy przejściu na kolejnego
                // dostawcę płaci się także za próbę, która się nie udała.
                var turnTokens = 0
                val language = settings.getResponseLanguage()
                var firstChunk = true
                // Nowa odpowiedź = nowy strumień mowy. Bez tego pierwsze zdanie
                // dopisałoby się do kolejki po poprzedniej turze.
                audio.beginStream()
                // Czy cokolwiek poszło na głos w trakcie generowania - decyduje,
                // czy na końcu CZEKAMY na syntezator, czy dopiero go prosimy.
                var spokenWhileStreaming = false
                val useVideoStream = video != null && video.isNotEmpty() && capabilities.supportsVideo

                // Buduje strumień dla danego providera - wywoływane raz na próbę,
                // bo Flow jest leniwy (błąd połączenia wyskakuje dopiero na collect()).
                fun buildStream(p: AIProvider) = if (useVideoStream) {
                    Log.i(TAG, "Używam analyzeVideo (${video!!.size} bytes, ${videoDurationMs}ms)")
                    p.analyzeVideoStream(
                        textQuestion = enhancedPrompt,
                        videoBytes = video,
                        videoDurationMs = videoDurationMs,
                        audioBytes = audioQuestion,
                        scannedCodes = scannedCodes,
                        enableWebSearch = settings.isWebSearchEnabled(),
                        systemPrompt = effectiveSystemPrompt
                    )
                } else {
                    Log.i(TAG, "Używam analyzeStream (${photos.size} zdjęć)")
                    p.analyzeStream(
                        textQuestion = enhancedPrompt,
                        images = photos,
                        audioBytes = audioQuestion,
                        scannedCodes = scannedCodes,
                        enableWebSearch = settings.isWebSearchEnabled(),
                        systemPrompt = effectiveSystemPrompt
                    )
                }

                // Cache jest zawsze kluczowany providerem wybranym w Ustawieniach, nie
                // tym, który faktycznie odpowiedział - inaczej trafienie w cache po
                // fallbacku nigdy by się nie powtórzyło (kolejne zapytanie sprawdza
                // cache pod aktywnym providerem, nie pod tym z fallbacku).
                val cacheProviderId = settings.getActiveProvider()
                val cacheModelId = settings.getSelectedModel(cacheProviderId) ?: "default"
                // Nagranie nie jest kluczem cache'a: dwa różne pytania mają tę
                // samą instrukcję tekstową, więc trafienie byłoby czystym
                // przypadkiem - i odpowiedzią na cudze pytanie.
                val cacheEligible = aiCache.shouldCache(textQuestion) &&
                    photos.isEmpty() && video == null && audioQuestion == null

                // CACHE CHECK - może już mamy odpowiedź?
                val cachedAnswer = if (cacheEligible) {
                    aiCache.get(textQuestion, cacheProviderId, cacheModelId)
                } else null

                var successfulProvider = provider

                if (cachedAnswer != null) {
                    Log.i(TAG, "✅ Odpowiedź z cache (zaoszczędzony request!)")
                    accumulatedText.append(cachedAnswer)
                    // TU CELOWO NIE MÓWIMY. Wspólna ścieżka niżej i tak wypowiada
                    // odpowiedź - ale dopiero PO wycięciu znacznika [[ACTION: ...]].
                    // Wcześniej odpowiedź z cache szła do syntezatora surowa, więc
                    // użytkownik słyszał znacznik przeczytany na głos, a zaraz potem
                    // drugi raz tę samą odpowiedź (QUEUE_FLUSH ucinał pierwszą).
                    _state.value = OrchestratorState.Streaming(cachedAnswer)
                } else {

                // Kolejni kandydaci, gdy aktywny provider zawiedzie zanim wypowiedział
                // choć jeden fragment - tylko providerzy, dla których user już ma klucz.
                // Wyłączalne w Ustawieniach (isAutoProviderFallbackEnabled), bo to
                // zmiana zachowania, nie tylko naprawa - user może wolieć jasny błąd
                // od cichej podmiany providera.
                val candidates = if (settings.isAutoProviderFallbackEnabled()) {
                    fallbackProviderOrder()
                } else {
                    listOf(settings.getActiveProvider())
                }

                var attemptIndex = 0
                val modelStartedAt = System.currentTimeMillis()
                while (true) {
                    val attemptProviderId = candidates[attemptIndex]
                    diag.event(
                        DiagFormat.Phase.MODEL, "wysyłam pytanie",
                        mapOf(
                            "dostawca" to attemptProviderId,
                            "model" to settings.getSelectedModel(attemptProviderId),
                            "próba" to (attemptIndex + 1),
                            "zdjęć" to photos.size,
                            "znakówPromptu" to enhancedPrompt.length
                        )
                    )
                    if (attemptIndex > 0 && attemptProviderId == AIProviderFactory.LOCAL_PROVIDER_ID) {
                        // Cichy fallback na model lokalny byłby mylący - to realny spadek
                        // jakości (mały model offline), user powinien wiedzieć, że o to chodzi.
                        audio.speak("Przechodzę na model lokalny, offline.", language = settings.getResponseLanguage())
                    }
                    val attemptProvider = if (attemptIndex == 0) provider else buildProviderForFallback(attemptProviderId)
                    try {
                        // LIMIT CZASU NA JEDNEGO DOSTAWCĘ.
                        //
                        // W dzienniku z 23:07 (tura b58b) Gemini dostał pytanie
                        // z nagraniem 875 kB i przez 60 SEKUND nie przysłał ani
                        // jednego fragmentu. Dopiero potem poszła próba na model
                        // lokalny, a tura skończyła się bez słowa. Z zewnątrz
                        // dokładnie to, co zgłoszono: „po jakimś czasie AI
                        // przestaje odpowiadać, nawet nie widać w apce, żeby
                        // reagowało na pytanie".
                        //
                        // Najdłuższa UDANA odpowiedź w tym samym dzienniku
                        // generowała się 20 s, więc limit zostawia ponad dwa
                        // razy tyle zapasu. Przekroczenie nie kończy tury: gdy
                        // coś już przyszło, bierzemy to, co jest; gdy nic - idzie
                        // wyjątek, czyli ta sama droga co każda inna awaria
                        // dostawcy, z przejściem na kolejnego włącznie.
                        val finished = withTimeoutOrNull(MODEL_ATTEMPT_TIMEOUT_MS) {
                        // Streaming - każdy fragment natychmiast mówimy
                        buildStream(attemptProvider).collect { chunk ->
                            accumulatedText.append(chunk.text)

                            if (chunk.isFinal) {
                                turnTokens += chunk.tokensUsed
                                Log.i(TAG, "Stream complete, ${chunk.tokensUsed} tokens, text len=${accumulatedText.length}")
                                diag.took(
                                    DiagFormat.Phase.MODEL,
                                    "koniec odpowiedzi (${accumulatedText.length} znaków, " +
                                        "${chunk.tokensUsed} tokenów)",
                                    modelStartedAt
                                )
                                // Wymuś wypowiedzenie ostatniego fragmentu
                                // Reszta bufora idzie na głos BEZ znacznika akcji -
                                // trzymaliśmy go właśnie po to, żeby nie został
                                // przeczytany na głos.
                                // Reszta bufora TEŻ liczy się jako wypowiedziane -
                                // przy krótkiej odpowiedzi bez kropki to jedyne,
                                // co w ogóle poszło na głos.
                                if (audio.flushStream { tail ->
                                        actionDetector.detectAiMarkedActions(tail).first
                                    }
                                ) {
                                    spokenWhileStreaming = true
                                }

                                // Zapisz do cache pod providerem z Ustawień - patrz komentarz wyżej
                                if (cacheEligible) {
                                    aiCache.put(textQuestion, accumulatedText.toString(), cacheProviderId, cacheModelId)
                                }
                            } else if (chunk.text.isNotBlank()) {
                                // Pierwszy fragment - zacznij mówić natychmiast
                                if (firstChunk) {
                                    Log.d(TAG, "First chunk received, starting TTS streaming")
                                    firstChunk = false
                                    // TO JEST TA LICZBA, o którą chodzi przy
                                    // "długo trwa od pytania do odpowiedzi":
                                    // ile minęło, zanim model powiedział
                                    // PIERWSZE słowo. Reszta to już mówienie.
                                    diag.took(
                                        DiagFormat.Phase.MODEL, "PIERWSZY FRAGMENT odpowiedzi", modelStartedAt
                                    )
                                }

                                // Wykryj kompletne zdania i mów je od razu (TTS streaming)
                                val spokenSentences = audio.addStreamFragment(chunk.text)
                                if (spokenSentences.isNotEmpty()) {
                                    spokenWhileStreaming = true
                                    Log.d(TAG, "Spoke ${spokenSentences.size} sentence(s): ${spokenSentences.last().take(50)}...")
                                }

                                // Aktualizuj UI na bieżąco
                                _state.value = OrchestratorState.Streaming(accumulatedText.toString())
                            }
                        }  // streamFlow.collect
                        true
                        }
                        if (finished == null) {
                            diag.event(
                                DiagFormat.Phase.MODEL, "dostawca nie odpowiedział w czasie",
                                mapOf(
                                    "dostawca" to attemptProviderId,
                                    "ms" to MODEL_ATTEMPT_TIMEOUT_MS,
                                    "znakówOdpowiedzi" to accumulatedText.length
                                )
                            )
                            // Limit przerywa KORUTYNĘ, nie pracę silnika. Model
                            // lokalny liczył dalej jeszcze 52 sekundy po
                            // wygaśnięciu limitu (dziennik z 15 września, 20:45:51)
                            // - procesor i bateria wydane na odpowiedź, której
                            // nikt już nie odbierze, w dodatku spowalniające
                            // turę, która właśnie ruszyła w to miejsce.
                            if (attemptProviderId == AIProviderFactory.LOCAL_PROVIDER_ID) {
                                pl.victor.app.ai.LocalAIProvider.cancelOngoing()
                            }
                            if (accumulatedText.isBlank()) {
                                throw IllegalStateException(
                                    "Dostawca $attemptProviderId nie odpowiedział w " +
                                        "${MODEL_ATTEMPT_TIMEOUT_MS / 1000} s"
                                )
                            }
                            Log.w(TAG, "Limit czasu, ale mam ${accumulatedText.length} znaków - biorę je")
                        }
                        successfulProvider = attemptProvider
                        break  // sukces - koniec prób
                    } catch (e: Exception) {
                        // POWÓD DO DZIENNIKA, nie tylko do logcata.
                        //
                        // W dzienniku z 15 września stoją trzy "wysyłam pytanie"
                        // pod rząd i ani słowa o tym, czemu dwie pierwsze
                        // odpadły po 70 ms. Powód był w wyjątku przez cały czas
                        // - szedł wyłącznie do Log.w, którego nie ma jak
                        // odczytać z telefonu osoby testującej. Bez tego wiersza
                        // każda awaria dostawcy wygląda w dzienniku identycznie.
                        diag.event(
                            DiagFormat.Phase.MODEL, "dostawca zawiódł",
                            mapOf(
                                "dostawca" to attemptProviderId,
                                "model" to settings.getSelectedModel(attemptProviderId),
                                "poMs" to (System.currentTimeMillis() - modelStartedAt),
                                "powód" to pl.victor.app.ai.ProviderFailure.describe(e.message),
                                "komunikat" to (e.message ?: e.javaClass.simpleName).take(160)
                            )
                        )
                        // Bezpieczne do ponowienia tylko, gdy nic jeszcze nie zostało
                        // powiedziane - inaczej user usłyszałby dwa zaczątki odpowiedzi.
                        val canRetryWithNext = firstChunk && attemptIndex < candidates.lastIndex
                        if (canRetryWithNext) {
                            Log.w(TAG, "Provider $attemptProviderId zawiódł przed pierwszym fragmentem " +
                                "(próba ${attemptIndex + 1}/${candidates.size}), próbuję kolejnego", e)
                            attemptIndex++
                        } else {
                            throw e
                        }
                    }
                }  // while (próby providerów)
                }  // else dla cache check

                // AI mogło oznaczyć akcję znacznikiem [[ACTION: ...]] (patrz
                // AI_ACTION_CAPABILITIES_PROMPT wyżej) - wytnij go z tego, co user
                // zobaczy/usłyszy, i zapamiętaj wykrytą akcję na potem.
                val (responseText, aiDetectedActions) =
                    actionDetector.detectAiMarkedActions(accumulatedText.toString().trim())

                // CO MODEL ZLECIŁ - ZAWSZE, TAKŻE GDY NIC.
                //
                // Bez tego wpisu z dziennika NIE DA SIĘ odróżnić trzech różnych
                // rzeczy: model nie wysłał znacznika, wysłał i nie sparsował się,
                // albo wysłał i akcja padła. Wyglądają tak samo - jako brak.
                //
                // Kosztowało to całą sesję w terenie: pięć aplikacji "nie
                // działało", a z dziennika nie dało się orzec, czy w ogóle
                // cokolwiek zostało zlecone. Okazało się, że model nie MIAŁ jak
                // ich zlecić, bo app_task nie był w jego słowniku - ale żeby to
                // stwierdzić, trzeba było czytać kod, a nie dziennik.
                diag.event(
                    DiagFormat.Phase.AKCJA,
                    if (aiDetectedActions.isEmpty()) {
                        "model nie zlecił żadnej akcji"
                    } else {
                        "model zlecił akcje"
                    },
                    mapOf(
                        "akcje" to aiDetectedActions.joinToString(",") { it.type.name }
                            .ifBlank { null },
                        "znakówOdpowiedzi" to responseText.length
                    )
                )

                // === WARSTWA 1 ZLECA WARSTWIE 0: "muszę to zobaczyć" ===
                // Model odpowiedział znacznikiem take_photo, bo bez obrazu nie
                // odpowie na pytanie. Robimy zdjęcie i zadajemy TO SAMO pytanie
                // jeszcze raz, już z obrazem. Ta odpowiedź ("Chwila, spojrzę.")
                // celowo nie trafia do historii ani do kontekstu rozmowy - to nie
                // odpowiedź, tylko prośba o obraz.
                val wantsPhoto = aiDetectedActions.any {
                    it.type == pl.victor.app.actions.ActionType.TAKE_PHOTO
                }
                val executableActions = aiDetectedActions.filterNot {
                    it.type == pl.victor.app.actions.ActionType.TAKE_PHOTO
                }

                if (wantsPhoto && !useVision) {
                    // useVision == false gwarantuje, że ta gałąź nie zapętli się:
                    // powtórka leci z forceVision = true, więc drugi raz tu nie wejdzie.
                    if (glassesManager.connectionState.value == ConnectionState.READY) {
                        Log.i(TAG, "Warstwa 1 poprosiła o zdjęcie - powtarzam pytanie z obrazem")
                        conversationalMode.onAiStartedSpeaking()
                        // Ta sama zasada co przy zwykłym końcu tury: jeśli
                        // strumień już to wypowiedział, CZEKAMY na wybrzmienie.
                        // Wcześniej szło tu speakAndAwait(responseText), czyli
                        // QUEUE_FLUSH na tekście, który właśnie leciał - ucięcie
                        // w pół słowa i przeczytanie całości od nowa. To jest ta
                        // sama usterka, którą af89056 naprawił na głównej
                        // ścieżce, a tę gałąź pominął.
                        if (spokenWhileStreaming) {
                            audio.awaitStreamSpoken()
                        } else {
                            audio.speakAndAwait(
                                responseText.ifBlank { "Chwila, spojrzę." },
                                language = language
                            )
                        }
                        _state.value = OrchestratorState.Idle
                        // Nagranie MUSI polecieć razem z powtórką. Gdy pytanie
                        // przyszło głosem z okularów, `textQuestion` jest tylko
                        // instrukcją ("odpowiedz na pytanie z nagrania") - bez
                        // dźwięku model dostałby zdjęcie i polecenie odnoszące
                        // się do czegoś, czego nie ma.
                        handedOffToRetry = true
                        handleUserTrigger(
                            trigger,
                            textQuestion,
                            forceVision = true,
                            audioQuestion = audioQuestion
                        )
                        return@launch
                    }
                    Log.i(TAG, "Warstwa 1 poprosiła o zdjęcie, ale okulary nie są połączone")
                }

                // Gdy model chciał zobaczyć, a okularów nie ma - powiedz to wprost,
                // zamiast wypuścić samo "Chwila, spojrzę." i zamilknąć.
                val answerText = if (wantsPhoto && !useVision) {
                    "Musiałbym to zobaczyć, ale okulary nie są połączone."
                } else if (wantsPhoto) {
                    // MODEL PROSI O ZDJĘCIE, CHOĆ JEDNO JUŻ DOSTAŁ.
                    //
                    // Gałąź wyżej celowo nie powtarza pytania drugi raz i to
                    // jest słuszne - inaczej model, który nie widzi dość,
                    // kazałby robić zdjęcia w kółko. Ale drugi przypadek nie
                    // był obsłużony WCALE: `wantsPhoto` przestawało być
                    // potrzebne, znacznik znikał, a na głos szła sama
                    // zapowiedź, którą prompt każe modelowi powiedzieć PRZED
                    // znacznikiem - "Chwila, spojrzę.".
                    //
                    // Z zewnątrz wyglądało to dokładnie tak, jak zgłoszono:
                    // zdjęcie się robi, POTEM pada "zrobię zdjęcie i sprawdzę"
                    // i nic się już nie dzieje. Zapowiedź czynności, która nie
                    // nastąpi, jest gorsza od przyznania się, bo człowiek czeka.
                    //
                    // Prawdziwy powód jest jeden: obraz, który poszedł, nie
                    // wystarczył. Mówimy więc to, a przy miniaturze dodajemy,
                    // co z tym zrobić - "podejdź bliżej" jest wskazówką, którą
                    // da się wykonać bez patrzenia na ekran.
                    if (glassesManager.lastPhotoWasFullResolution) {
                        "Mam zdjęcie, ale nie widać na nim dość, żeby to " +
                            "rozpoznać. Podejdź bliżej albo ustaw lepsze światło."
                    } else {
                        "Mam tylko zdjęcie w małej rozdzielczości i nie widać na " +
                            "nim dość. Włącz Wi-Fi w telefonie - bez niego nie " +
                            "pobiorę z okularów ostrego zdjęcia."
                    }
                } else {
                    // PUSTA ODPOWIEDŹ NIE MOŻE ZNACZYĆ CISZY.
                    //
                    // `responseText` bywa pusty z dwóch zupełnie różnych powodów:
                    // model odpowiedział SAMYM znacznikiem [[ACTION: ...]], który
                    // stąd wycinamy, albo strumień urwał się przed pierwszym
                    // słowem. Do tej pory pusty tekst szedł wprost do
                    // syntezatora i do stanu Completed - aplikacja nic nie mówiła
                    // i pokazywała puste pole. Zgłoszone: "nie dostaję żadnej
                    // odpowiedzi, kompletnie nic, puste pole".
                    //
                    // Cisza jest najgorszą z możliwych odpowiedzi, bo nie da się
                    // po niej poznać, czy aplikacja w ogóle cokolwiek zrobiła.
                    responseText.ifBlank {
                        if (executableActions.isNotEmpty()) {
                            // Model chciał TYLKO wykonać akcję. Potwierdzenie i tak
                            // przyjdzie z handleActions niżej, ale coś trzeba
                            // powiedzieć teraz, żeby tura nie była niema.
                            "Już się tym zajmuję."
                        } else {
                            "Model nie odesłał odpowiedzi. Spróbuj zapytać jeszcze raz " +
                                "albo przełącz dostawcę AI w Ustawieniach."
                        }
                    }
                }

                val response = AIResponse(
                    text = answerText,
                    providerId = successfulProvider.id,
                    tokensUsed = turnTokens
                )
                _lastResponse.value = response
                // Jedno miejsce dla całej rozmowy - tryby dla niewidomych
                // dopisują się osobno, bo omijają tę drogę.
                usage.record(turnTokens)

                // Pamięć rozmowy trzyma WYŁĄCZNIE conversationContext. Były tu
                // obok dwa pola z ostatnim pytaniem i ostatnią odpowiedzią, ale
                // nikt ich nie czytał - wyglądały na pamięć, a nią nie były.
                conversationContext.addTurn(
                    question = textQuestion,
                    answer = response.text,
                    photos = photos.size,
                    tokens = response.tokensUsed
                )

                // 3. TTS
                //
                // ODPOWIEDŹ SZŁA NA GŁOS DWA RAZY - I TO BYŁA NAJWIĘKSZA USTERKA
                // W CAŁEJ TURZE.
                //
                // Fragmenty są wypowiadane na bieżąco, w miarę generowania
                // (addStreamFragment wyżej). Tutaj stało `speakAndAwait(CAŁA
                // odpowiedź)` - bo tylko tak dało się poczekać na koniec
                // mówienia przed wznowieniem nasłuchu. Skutek: użytkownik
                // słyszał poszarpane początki zdań (każde kolejne ucinało
                // poprzednie przez QUEUE_FLUSH), a potem całą odpowiedź od nowa.
                // Stąd zgłoszenia "odpowiada jakby na inne pytanie" i "jedno
                // pytanie jest okej, potem się zawiesza".
                //
                // Teraz czekamy na to, co JUŻ zostało wypowiedziane. Mówimy
                // wprost tylko wtedy, gdy strumień nic nie powiedział - czyli
                // przy odpowiedzi z cache i przy odpowiedzi bez zdań (sam
                // znacznik akcji).
                conversationalMode.onAiStartedSpeaking()
                val listenStartedAt = System.currentTimeMillis()
                if (spokenWhileStreaming) {
                    audio.awaitStreamSpoken()
                } else {
                    // speakAndAwait, NIE speak: to drugie wraca natychmiast, więc
                    // nasłuch startowałby w trakcie mówienia i nagrywał własny
                    // głos asystenta jako kolejne pytanie.
                    audio.speakAndAwait(response.text, language = language)
                }
                diag.took(DiagFormat.Phase.MOWA, "koniec wypowiedzi", listenStartedAt)
                conversationalMode.onAiFinishedSpeaking()

                // Akcja, którą AI oznaczyło znacznikiem [[ACTION: ...]] - ten sam
                // handleActions co dla głosu/przycisku, więc DIRECT nadal pyta o
                // potwierdzenie, a SAFE nadal tylko otwiera zewnętrzną apkę.
                if (executableActions.isNotEmpty()) {
                    handleActions(executableActions, textQuestion)
                }

                // 4. Historia - zapisz pierwsze zdjęcie do pliku (miniatura)
                try {
                    val firstPhotoPath = photos.firstOrNull()?.let { bytes ->
                        photoStorage.savePhoto(bytes, prefix = "conv_${System.currentTimeMillis()}")
                    }
                    history.save(
                        // Puste pytanie zdarza się już tylko przy fizycznym przycisku
                        // na okularach ("popatrz i powiedz, co widzisz"). Głos ma
                        // od tej wersji transkrypcję, więc dawny komunikat o jej
                        // braku byłby dziś nieprawdą.
                        question = textQuestion.ifBlank { "(przycisk na okularach - opis obrazu)" },
                        response = response.text,
                        providerId = response.providerId,
                        firstPhotoPath = firstPhotoPath,
                        photoCount = photos.size,
                        tokensUsed = response.tokensUsed,
                        sourcesJson = null
                    )
                    Log.d(TAG, "Saved to history (photo: $firstPhotoPath)")
                    // Egzekwuj limit historii z ustawień
                    history.trimTo(settings.getHistoryLimit())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save history", e)
                }

                // ODPOWIEDŹ MODELU JAKO NOTATKA.
                //
                // Zapis idzie PO wypowiedzeniu, a nie zamiast: użytkownik i tak
                // usłyszy, co zostało zapisane, bo prompt każe modelowi oddać
                // samą treść notatki. Krótkie potwierdzenie na końcu jest po to,
                // żeby dało się odróżnić notatkę zapisaną od zwykłej odpowiedzi.
                if (saveAsNote) {
                    val written = pl.victor.app.notes.Notes.acceptWritten(response.text)
                    val confirmation = if (written == null) {
                        // Model nie dał się użyć - i trzeba to powiedzieć wprost.
                        // Milczenie znaczyłoby dla użytkownika "zapisane".
                        Log.w(TAG, "Model nie napisał treści nadającej się na notatkę")
                        "Nie udało mi się z tego zrobić notatki."
                    } else {
                        val notes = settings.addNote(written)
                        Log.i(TAG, "Notatka napisana przez model zapisana")
                        "Zapisane. Masz teraz ${notes.size} notatek."
                    }
                    audio.speak(confirmation, language = settings.getResponseLanguage())
                }

                _state.value = OrchestratorState.Completed(response.text)

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Przerwanie na żądanie użytkownika to nie awaria. Bez tej gałęzi
                // ogólny catch niżej złapałby je (CancellationException JEST
                // wyjątkiem) i pokazał "Nieoczekiwany błąd" po każdym "cicho".
                Log.i(TAG, "Tura przerwana")
                throw e
            } catch (e: AIProviderException) {
                Log.e(TAG, "AI error", e)
                val shown = "Błąd AI: ${e.message}" +
                    if (e.isRetryable) " (spróbuj ponownie)" else ""
                _state.value = OrchestratorState.Error(shown)
                announceTurnFailure(
                    trigger,
                    pl.victor.app.ai.ProviderFailure.describe(e.message, e.isRetryable)
                )
                // Wznów nasłuch: nasłuch został wstrzymany przed mówieniem, a
                // błąd nie może zostawić trybu konwersacyjnego głuchym na stałe.
                conversationalMode.onAiFinishedSpeaking()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                _state.value = OrchestratorState.Error("Nieoczekiwany błąd: ${e.message}")
                announceTurnFailure(
                    trigger,
                    "Coś poszło nie tak po mojej stronie. Powtórz, proszę, pytanie."
                )
                conversationalMode.onAiFinishedSpeaking()
            } finally {
                // ILE TRWA ZWIJANIE ŁĄCZA AUDIO - I CO SIĘ DZIEJE PO NIM.
                //
                // Zgłoszone: "przez 10 sekund po odpowiedzi przycisk wywoływania
                // i «hej lens» nie działają". W dzienniku z 19:39 te przerwy
                // wynoszą 10,9 / 11,6 / 10,1 s i - co istotne - NIE MA w nich
                // ani jednej odrzuconej próby. Gdyby to aplikacja odrzucała
                // wywołanie, byłby wiersz "trigger ODRZUCONY". Nie ma go, więc
                // ramka przycisku w ogóle do nas nie dociera.
                //
                // Podejrzenie pada na to miejsce: odpowiedź idzie przez profil
                // rozmowy (w każdej turze "brak A2DP - biorę profil rozmowy"),
                // a zwijanie SCO potrafi na współdzielonym radiu zagłodzić BLE.
                // Ale to jest HIPOTEZA, nie ustalenie - nie mam w dzienniku ani
                // jednego wiersza o tym, kiedy łącze faktycznie schodzi.
                //
                // Nie zgaduję więc naprawy, tylko dokładam pomiar: znacznik
                // przed i po, z czasem. Następny dziennik pokaże, czy martwe
                // dziesięć sekund zaczyna się dokładnie tutaj - a jeśli nie,
                // przynajmniej wykluczy to miejsce.
                if (audioHeld) {
                    val teardownStartedAt = System.currentTimeMillis()
                    audio.endConversationRouting()
                    runCatching {
                        diag.event(
                            DiagFormat.Phase.AUDIO, "zwinięte łącze audio tury",
                            mapOf("ms" to (System.currentTimeMillis() - teardownStartedAt))
                        )
                    }
                }
                wakeLock.release(LOCK_TURN)
                resumeWakeWordMic()
                if (!handedOffToRetry) {
                    diag.endTurn(
                        when (val st = _state.value) {
                            is OrchestratorState.Error -> "BŁĄD: ${st.message}"
                            is OrchestratorState.Completed -> "odpowiedziano"
                            // „Idle" nic nie mówiło. Tura, która skończyła się w
                            // stanie roboczym, NIE DAŁA ODPOWIEDZI - i tak ma
                            // brzmieć, razem z powodem, jeśli go znamy.
                            else -> "BEZ ODPOWIEDZI (stan ${st::class.simpleName}" +
                                (lastCancelReason?.let { ", powód: $it" } ?: "") + ")"
                        }
                    )
                    lastCancelReason = null
                    uploadDiagnosticsInBackground()
                }
            }
        }
    }

    /**
     * Obsługuje komendy sterujące samą rozmową - zmianę persony i reset kontekstu.
     * Sprawdzane przed detekcją akcji i przed AI - patrz wywołanie w [handleUserTrigger].
     *
     * @return `true` gdy komenda została obsłużona (nic więcej nie powinno się zdarzyć
     *         dla tego triggera)
     */
    /**
     * Składa i wygłasza codzienny briefing.
     *
     * ## Dlaczego przez zwykłą turę, a nie osobną ścieżką
     * Bo tura ma już wszystko, czego briefing potrzebuje: strumieniowe mówienie
     * zdanie po zdaniu, trasę dźwięku na okulary, zapis do historii i przerwanie
     * słowem "stop". Osobna ścieżka znaczyłaby drugą implementację tego samego -
     * i drugie miejsce, w którym te rzeczy mogą się zepsuć.
     *
     * Zbieranie danych idzie z pominięciem bramek słów kluczowych (`force`):
     * briefing ma zebrać to, o co użytkownik poprosił w ustawieniach, a nie to,
     * co wynikałoby z brzmienia zdania.
     */
    fun runBriefing() {
        scope.launch(coroutineErrors) {
            val preferences = settings.getBriefingPreferences()
            // Trzy niezależne zapytania do sieci - równolegle, nie po kolei.
            // Briefing bywa wywoływany głosem ("co dziś?"), a szeregowanie
            // kalendarza, pogody i poczty dokładało kilka sekund ciszy do
            // czegoś, co ma być krótkie.
            val calendarDeferred = async {
                if (preferences.includeCalendar) {
                    runCatching { buildCalendarContext("", force = true) }.getOrNull()
                } else null
            }
            val weatherDeferred = async {
                if (preferences.includeWeather) {
                    runCatching { buildWeatherContext("", force = true) }.getOrNull()
                } else null
            }
            val mailDeferred = async {
                if (preferences.includeMail) {
                    runCatching { buildGmailContext("", force = true) }.getOrNull()
                } else null
            }
            val material = pl.victor.app.proactive.DailyBriefing.Material(
                calendar = calendarDeferred.await(),
                weather = weatherDeferred.await(),
                mail = mailDeferred.await()
            )

            val prompt = pl.victor.app.proactive.DailyBriefing.buildPrompt(material, preferences)
            if (prompt == null) {
                // Milczenie jest tu lepsze niż "nie mam żadnych informacji" -
                // to drugie budzi i nic nie wnosi.
                Log.i(TAG, "Briefing pominięty - nie ma o czym mówić")
                return@launch
            }
            handleUserTrigger(TriggerSource.TEXT_INPUT, prompt)
        }
    }

    private fun handleMetaCommand(text: String): Boolean {
        // Briefing jest komendą META, nie akcją: nie uruchamia niczego przez
        // Intent, tylko układa wypowiedź z danych, które i tak już zbieramy.
        if (pl.victor.app.proactive.DailyBriefing.isBriefingRequest(text)) {
            Log.i(TAG, "Komenda briefingu: \"$text\"")
            runBriefing()
            return true
        }

        // "Stop"/"cicho" ucisza V.I.C.T.O.R.-a, a nie steruje odtwarzaczem muzyki.
        // Osobno od warstwy 0, bo tam wszystko kończy się akcją przez Intent,
        // a tu chodzi tylko o zamknięcie ust syntezatorowi.
        if (SILENCE_COMMAND_REGEX.matches(text.lowercase().trim().trimEnd('.', '!', '?'))) {
            Log.i(TAG, "Komenda ciszy: \"$text\"")
            audio.stopSpeaking()
            conversationalMode.onAiFinishedSpeaking()
            _state.value = OrchestratorState.Idle
            return true
        }

        if (pl.victor.app.conversation.MetaCommands.detectContextReset(text)) {
            conversationContext.clear()
            // Razem z historią - inaczej "nowy temat" zostawiałby doklejaną
            // pogodę sprzed resetu.
            openContextTopics.clear()
            val speech = "Zaczynamy od nowa."
            audio.speak(speech, language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Completed(speech)
            return true
        }

        when (val attempt = pl.victor.app.conversation.MetaCommands.detectPersonaSwitchAttempt(text)) {
            is pl.victor.app.conversation.PersonaSwitchAttempt.Recognized -> {
                settings.setSelectedPersonaId(attempt.personaId)
                val persona = PersonaRegistry.findById(attempt.personaId) ?: PersonaRegistry.default()
                val speech = "OK, jestem teraz ${persona.name}."
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                return true
            }
            is pl.victor.app.conversation.PersonaSwitchAttempt.Unrecognized -> {
                val speech = "Nie znam persony „${attempt.requestedName}”. Dostępne: " +
                    PersonaRegistry.all().joinToString(", ") { it.name }
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                return true
            }
            null -> return false
        }
    }

    /**
     * Reset do stanu Idle (po wyświetleniu odpowiedzi)
     */
    fun reset() {
        _state.value = OrchestratorState.Idle
    }

    /** Kiedy ostatnio wysłano dziennik - patrz [uploadDiagnosticsInBackground]. */
    @Volatile
    private var lastDiagUploadAtMs = 0L

    /**
     * Wysyła dziennik na GitHuba po zakończonej turze.
     *
     * ## Dlaczego po turze, a nie na żądanie
     * Bo najciekawsze zgłoszenia brzmią "zawiesiło się" i "przestało działać" -
     * a wtedy nikt nie wchodzi w ustawienia, żeby kliknąć "wyślij". Dziennik ma
     * być na GitHubie ZANIM ktokolwiek zauważy, że jest potrzebny.
     *
     * Odstęp jest po to, żeby seria krótkich pytań nie zrobiła serii wysyłek:
     * plik i tak zawiera całą sesję, więc jedna wysyłka na minutę niesie
     * dokładnie tyle samo informacji.
     */
    private fun uploadDiagnosticsInBackground() {
        if (!settings.isDiagnosticLogEnabled()) return
        val token = settings.getGithubToken().takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        if (now - lastDiagUploadAtMs < DIAG_UPLOAD_INTERVAL_MS) return
        lastDiagUploadAtMs = now

        val file = diag.currentFile() ?: return
        scope.launch {
            val content = diag.readSession()
            if (content.isBlank()) return@launch
            pl.victor.app.diagnostics.DiagnosticUploader(token)
                .upload(file.name, content)
                .onSuccess { Log.i(TAG, "Dziennik wysłany: $it") }
                .onFailure { Log.w(TAG, "Dziennik nie poszedł: ${it.message}") }
        }
    }

    /**
     * Mówi na głos, że tura się nie udała.
     *
     * ## Dlaczego to musi iść głosem
     * Błąd lądował dotąd WYŁĄCZNIE w [OrchestratorState.Error], czyli na ekranie
     * telefonu. Ktoś w okularach, z telefonem w kieszeni, nie zobaczy go nigdy -
     * dla niego każdy limit zapytań, każde 402 i każde zerwane połączenie
     * wygląda dokładnie tak samo: zadał pytanie i zapadła cisza. Zgłoszone jako
     * "często jest brak odpowiedzi" - a odpowiedź była, tylko na ekranie.
     *
     * Wpisane z klawiatury pytanie tego nie potrzebuje: kto pisze, ten patrzy.
     */
    private fun announceTurnFailure(trigger: TriggerSource, message: String) {
        if (trigger == TriggerSource.TEXT_INPUT) return
        scope.launch {
            // Ta sama klamra co przy zwykłej odpowiedzi. Bez niej tryb
            // konwersacyjny wznowiłby nasłuch w trakcie komunikatu i nagrał
            // własny głos asystenta jako kolejne pytanie.
            conversationalMode.onAiStartedSpeaking()
            runCatching {
                audio.speakAndAwait(message, language = settings.getResponseLanguage())
            }.onFailure { Log.w(TAG, "Nie udało się powiedzieć o błędzie", it) }
            conversationalMode.onAiFinishedSpeaking()
        }
    }

    /**
     * Wykonuje wykryte akcje bez udziału AI (szybko, offline).
     * Wspiera SAFE i DIRECT mode.
     */
    private fun handleActions(actions: List<Action>, originalText: String) {
        Log.i(TAG, "Executing ${actions.size} action(s) from: \"$originalText\"")

        // NAJPIERW ROZWIĄŻ KONTAKT, POTEM PYTAJ - nie odwrotnie.
        //
        // Kolejność była tu odwrócona i to jest usterka w jedynym miejscu
        // aplikacji, które powstrzymuje rzeczy nieodwracalne: pytanie o zgodę
        // budowało się z NAZWY, KTÓRĄ PADŁA („zadzwonić do Janusza?"), a zamiana
        // nazwy na numer szła dopiero w executeActionsList, PO potwierdzeniu.
        // Człowiek zatwierdzał więc imię, które sam wypowiedział, a dzwoniło do
        // kogoś, kogo wybrał dopasowywacz - i nie miał jak tego zauważyć.
        //
        // Teraz pytanie niesie nazwę Z KSIĄŻKI ADRESOWEJ. Przy dwóch podobnych
        // kontaktach to jedyny moment, w którym da się powiedzieć „nie ten".
        //
        // Rozwiązywanie jest zawieszalne, więc całość idzie w korutynę. Wołający
        // i tak nie czekali na wynik - executeActionsList sam startuje własną -
        // ale kolejność WEWNĄTRZ tej funkcji nie zmienia się ani o krok: nic nie
        // wykonuje się, dopóki potwierdzenie nie zapadnie.
        scope.launch {
            // `?: it` jest tu istotne: nierozwiązany kontakt zostaje surowy i
            // trafia do executeActionsList, które oddaje dotychczasowe
            // „Nie znalazłem kontaktu". Rozwiązany ma już numer w `to`, więc
            // tamtejsze powtórne rozwiązywanie rozpozna go jako numer i nie
            // pójdzie drugi raz do książki adresowej.
            val prepared = actions.map { resolveContactIfNeeded(it) ?: it }
            handlePreparedActions(prepared)
        }
    }

    /**
     * Druga połowa [handleActions], na akcjach z rozwiązanymi już kontaktami.
     *
     * Wydzielona wyłącznie po to, żeby bramka potwierdzeń została dokładnie tym,
     * czym była - jednym ciągiem warunków bez korutyn w środku.
     */
    private fun handlePreparedActions(actions: List<Action>) {
        val mode = ActionMode.fromName(settings.getActionMode())
        Log.d(TAG, "Action mode: $mode")

        // W trybie DIRECT - sprawdź czy akcja wymaga potwierdzenia.
        //
        // Kalendarz pyta ZAWSZE, także w trybie SAFE, bo w SAFE też zapisujemy
        // go teraz naprawdę (patrz executeActionsList). Wcześniej SAFE otwierał
        // formularz w aplikacji kalendarza i to kliknięcie "Zapisz" było całym
        // potwierdzeniem - skoro formularza już nie ma, pytanie musi paść tutaj.
        val calendarViaApi = actions.any { it is Action.CreateCalendarEvent } &&
            directActionExecutor.canWriteCalendarDirectly()
        if (mode == ActionMode.DIRECT || calendarViaApi) {
            // POTWIERDZENIE MUSI OBJĄĆ KAŻDĄ AKCJĘ Z LISTY, NIE TYLKO PIERWSZĄ.
            //
            // Niżej `executeActionsList(actions)` wykonuje CAŁĄ listę. Pytanie o
            // samą pierwszą znaczyło, że przy dwóch znacznikach od modelu
            // użytkownik potwierdzał "Wyślij SMS do Ani?", a przy okazji
            // wykonywało się połączenie, o które nikt nie zapytał. To jedyne
            // miejsce w całym przeglądzie, które mogło zrobić coś
            // nieodwracalnego bez zgody.
            val required = actions.mapNotNull {
                directActionExecutor.canExecuteDirect(it) as? ActionConfirmation.Required
            }
            if (required.isNotEmpty()) {
                val single = required.singleOrNull()
                val title = single?.title ?: "Potwierdź wszystko, co zaraz zrobię"
                val message = single?.message
                    ?: required.joinToString("\n") { "• ${it.message}" }
                // Zapisz akcje do późniejszego wykonania.
                //
                // TE SAME akcje, które opisało pytanie - z rozwiązanymi już
                // kontaktami. Gdyby tu wróciła lista sprzed rozwiązania, człowiek
                // potwierdzałby jedno, a wykonywałoby się drugie.
                _pendingActionConfirmation.value = PendingActionConfirmation(
                    actions = actions,
                    title = title,
                    message = message,
                    confirmText = required.first().confirmText,
                    cancelText = required.first().cancelText
                )
                // Zapytaj GŁOSEM i wysłuchaj odpowiedzi.
                //
                // Dotąd jedyną drogą było kliknięcie w oknie na telefonie -
                // czyli asystent, którego cała reszta działa bez rąk, na
                // ostatnim kroku kazał sięgnąć po telefon. Zgłoszone: "żeby
                // dało się zatwierdzić głosowo, a nie klikając".
                //
                // Okno zostaje: gdy odpowiedź jest niejednoznaczna albo nie
                // padnie wcale, decyzja ma dokąd wrócić.
                listenForConfirmation("$title. $message")
                return
            }
        }

        // Tryb SAFE lub brak potwierdzenia - wykonaj
        executeActionsList(actions)
    }

    /**
     * Wykonuje listę akcji (używane zarówno po wykryciu jak i po potwierdzeniu).
     */
    private fun executeActionsList(rawActions: List<Action>) {
        // PROWADZENIE Z ASYSTENTEM CZY BEZ - rozstrzygane TUTAJ, raz.
        //
        // Powiedziane wprost ("prowadź do apteki z asystentem") wygrywa; gdy nie
        // padło, decyduje ustawienie. Rozstrzygamy przed wykonaniem, żeby
        // wykonawca i wypowiadany komunikat mówiły o tym samym - inaczej
        // asystent meldowałby jedno, a robił drugie.
        val actions = rawActions.map { action ->
            if (action is Action.Navigate && action.assist == RouteAssist.FROM_SETTINGS) {
                action.copy(
                    assist = if (settings.isRouteAssistEnabled()) RouteAssist.ON
                    else RouteAssist.OFF
                )
            } else {
                action
            }
        }

        // Obsługa accessibility (nie wymaga trybu DIRECT/SAFE)
        val accessibilityActions = actions.filter {
            it is Action.ReadText || it is Action.DescribeScene ||
            it is Action.StartNavigation || it is Action.StopAccessibility
        }

        if (accessibilityActions.isNotEmpty()) {
            scope.launch {
                accessibilityActions.forEach { action ->
                    when (action) {
                        // W trybie czytania powtórzone "czytaj" znaczy KOLEJNĄ
                        // stronę, nie ponowne włączenie trybu. Zdjęcie powstaje
                        // dokładnie wtedy - patrz AccessibilityService.requestRead.
                        is Action.ReadText ->
                            if (!accessibility.requestRead()) accessibility.enableReadText()
                        is Action.DescribeScene -> {
                            // Jednorazowy opis
                            val desc = accessibility.describeOnce()
                            if (desc != null) {
                                audio.speak(desc, language = "pl")
                            }
                        }
                        is Action.StartNavigation -> accessibility.enableNavigate()
                        is Action.StopAccessibility -> accessibility.disable()
                        // Lista jest wcześniej przefiltrowana do akcji dostępności,
                        // ale Kotlin wymaga wyczerpania when po typie Action.
                        else -> Log.w(TAG, "Nieoczekiwana akcja w trybie dostępności: ${action.type}")
                    }
                }
            }
            // Jeśli wszystko to accessibility, zwracamy bezpośrednio.
            // Idle nie przechodzi przez wznowienie nasłuchu (patrz init), więc
            // tryb konwersacyjny wznawiamy tu wprost.
            if (actions.size == accessibilityActions.size) {
                _state.value = OrchestratorState.Idle
                conversationalMode.onAiFinishedSpeaking()
                return
            }
        }

        // Trasa z asystentem: mapy mówią, GDZIE SKRĘCIĆ, a my - W CO SIĘ NIE
        // WYWRÓCIĆ. To są dwie różne rzeczy i dopiero razem dają to, o co w tej
        // aplikacji chodzi; osobno każda z nich ma sens i każda działa sama.
        //
        // Włączamy przed odpaleniem map, bo za chwilę na wierzchu będzie cudza
        // aplikacja i nasz kod nie dostanie już okazji.
        if (actions.any { it is Action.Navigate && it.assist == RouteAssist.ON }) {
            scope.launch { accessibility.enableNavigate() }
        }

        val mode = ActionMode.fromName(settings.getActionMode())

        scope.launch {
            val results = actions.map { rawAction ->
                // SmartActionDetector wyciąga z mowy samo słowo po "do"/"pod" - to
                // zwykle nazwa kontaktu, nie numer. ActionExecutor (tryb SAFE) wsadza
                // ten tekst wprost do intencji "tel:"/"smsto:" - Android nie rozwiązuje
                // tam nazw, więc "zadzwoń do mamy" bez tego kroku nigdy nie działało
                // w domyślnym trybie. Rozwiązanie robimy raz, przed obiema ścieżkami.
                val action = resolveContactIfNeeded(rawAction)
                if (action == null) {
                    val name = (rawAction as? Action.SendSms)?.to
                        ?: (rawAction as? Action.MakeCall)?.to
                    val failure = ActionResult.Failed("Nie znalazłem kontaktu „$name” w książce adresowej.")
                    Log.w(TAG, "Kontakt nierozwiązany: $name")
                    return@map rawAction to failure
                }

                // Spróbuj DIRECT jeśli tryb DIRECT i akcja to obsługuje
                // Kalendarz: pisz przez konto Google, jeśli jest podłączone -
                // NIEZALEŻNIE od trybu akcji. To była przyczyna zgłoszenia
                // "mówi, że dodaje coś do kalendarza, a finalnie nie dodaje":
                // domyślny tryb SAFE odpalał Intent, czyli otwierał formularz na
                // telefonie i meldował sukces, choć nikt niczego nie zapisał.
                val calendarViaApi = action is Action.CreateCalendarEvent &&
                    directActionExecutor.canWriteCalendarDirectly()
                val result = if (calendarViaApi || (mode == ActionMode.DIRECT &&
                    (action is Action.SendSms || action is Action.MakeCall ||
                        action is Action.CreateCalendarEvent))) {
                    val direct = directActionExecutor.executeDirect(action)
                    // Fallback do SAFE jeśli direct się nie udało
                    if (direct is ActionResult.Failed) {
                        Log.w(TAG, "DIRECT failed, falling back to SAFE: ${direct.reason}")
                        actionExecutor.execute(action)
                    } else direct
                } else {
                    actionExecutor.execute(action)
                }
                Log.d(TAG, "Action ${action.type}: $result")
                action to result
            }

            // Zbuduj odpowiedź głosową
            val speech = when {
                results.all { it.second is ActionResult.Success } -> {
                    // Z WYNIKU, nie z opisu akcji. Opis akcji to zamiar ("dodaj
                    // do kalendarza") i brzmi jak wykonany, cokolwiek się
                    // wydarzyło - a część dróg tylko otwiera okno i czeka na
                    // użytkownika. Wynik wie, co naprawdę zaszło.
                    // Akcje bez własnego komunikatu zbieramy w JEDNO zdanie, a
                    // nie w łańcuszek "OK, ... OK, ...". Te z komunikatem mówią
                    // same za siebie i idą po nim.
                    val plain = results
                        .filter { (_, r) -> (r as ActionResult.Success).message == GENERIC_ACTION_SUCCESS }
                        .map { (action, _) -> action.description.lowercase() }
                    val spoken = results
                        .map { (_, r) -> (r as ActionResult.Success).message }
                        .filter { it != GENERIC_ACTION_SUCCESS }
                    val head = if (plain.isEmpty()) null else "OK, ${plain.joinToString(", ")}."
                    (listOfNotNull(head) + spoken).joinToString(" ")
                }
                results.any { it.second is ActionResult.Failed } -> {
                    val failed = results.filter { it.second is ActionResult.Failed }
                    "Nie udało się: ${failed.joinToString { (it.second as ActionResult.Failed).reason }}"
                }
                else -> "Wykonano"
            }

            // Mów i pokaż. Czekamy na koniec wypowiedzi, bo w trybie
            // konwersacyjnym zaraz potem wraca nasłuch - inaczej mikrofon
            // łapałby potwierdzenie akcji jako kolejne pytanie.
            conversationalMode.onAiStartedSpeaking()
            audio.speakAndAwait(speech, language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Completed(speech)
            conversationalMode.onAiFinishedSpeaking()
        }
    }

    /**
     * Zadaje pytanie o potwierdzenie na głos i słucha odpowiedzi.
     *
     * ## Dlaczego niejednoznaczna odpowiedź NIE wykonuje akcji
     * Bo te akcje wysyłają maile, dzwonią i dodają wydarzenia - żadnej z nich nie
     * da się cofnąć słowem. Przy głosie nie ma drugiego ekranu, na którym dałoby
     * się złapać pomyłkę, więc wszystko poza wyraźnym "tak" zostawia decyzję
     * użytkownikowi. Okno na telefonie czeka dalej.
     *
     * @param question pytanie do wypowiedzenia
     */
    /**
     * Nasłuch potwierdzenia - do przerwania, gdy rusza nowa tura.
     *
     * Bez tego naciśnięcie przycisku w trakcie pytania "Dodać do kalendarza?"
     * zostawiało DWA nasłuchy walczące o jeden mikrofon: ten i ten z nowej
     * tury. Zgłoszone jako "gdy ekran potwierdzenia się wyświetla, zadawanie
     * pytań się blokuje".
     */
    private var confirmationJob: kotlinx.coroutines.Job? = null

    private fun listenForConfirmation(question: String) {
        confirmationJob?.cancel()
        confirmationJob = scope.launch {
            val language = settings.getResponseLanguage()
            diag.event(
                DiagFormat.Phase.AKCJA, "pytam o potwierdzenie",
                mapOf("pytanie" to question.take(80))
            )
            // ŁĄCZE DO MIKROFONU ZESTAWU ZESTAWIAMY W TLE, W TRAKCIE PYTANIA.
            //
            // Do mikrofonu okularów prowadzi wyłącznie profil rozmowy (SCO), a
            // jego negocjacja trwa do czterech sekund. Gdyby szła po pytaniu,
            // użytkownik usłyszałby "Dodać do kalendarza?" i musiał odczekać w
            // ciszy, zanim ktokolwiek zacznie go słuchać - a "tak" pada od razu
            // po pytaniu i przepadłoby. Czytanie pytania trwa mniej więcej tyle
            // samo, więc jedno chowa się za drugim.
            val routing = async { runCatching { audio.beginConversationRouting() }
                .getOrDefault(false) }
            // speakAndAwait, nie speak: nasłuch nie może ruszyć w trakcie
            // czytania pytania, bo nagrałby własny głos asystenta.
            audio.speakAndAwait(question, language = language)
            val routed = routing.await()

            // PROFIL ROZMOWY ZWALNIAMY W `finally`, A NIE PO NASŁUCHU.
            //
            // Odkąd ten nasłuch da się PRZERWAĆ (robi to każda nowa tura -
            // patrz `confirmationJob`), zwolnienie ustawione po nim wykonałoby
            // się tylko wtedy, gdy nikt nie przerwał. Naciśnięcie przycisku w
            // trakcie pytania zostawiałoby podniesione SCO na zawsze - czyli
            // dokładnie tę usterkę, przez którą wczoraj urywały się odpowiedzi.
            val heard = try {
                if (_pendingActionConfirmation.value == null) return@launch
                runCatching {
                    conversationalMode.listenOnce(
                        languageTag = languageTagFor(language),
                        timeoutMs = CONFIRMATION_TIMEOUT_MS
                    )
                }.getOrNull()
            } finally {
                // withContext(NonCancellable): zwykłe wywołanie zawieszalne w
                // `finally` anulowanej korutyny rzuca natychmiast i nie zdąży
                // niczego zwolnić.
                if (routed) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        audio.endConversationRouting()
                    }
                }
            }

            // Użytkownik mógł w tym czasie kliknąć w oknie - wtedy nie ma już
            // czego potwierdzać i nie wolno wykonać akcji drugi raz.
            if (_pendingActionConfirmation.value == null) return@launch

            when (pl.victor.app.actions.ConfirmationReply.parse(heard)) {
                pl.victor.app.actions.ConfirmationReply.Reply.YES -> {
                    Log.i(TAG, "Potwierdzenie głosem: tak")
                    diag.event(
                        DiagFormat.Phase.AKCJA, "potwierdzenie głosem",
                        mapOf("odpowiedź" to "TAK", "usłyszano" to heard?.take(40))
                    )
                    confirmAction()
                }
                pl.victor.app.actions.ConfirmationReply.Reply.NO -> {
                    Log.i(TAG, "Potwierdzenie głosem: nie")
                    diag.event(
                        DiagFormat.Phase.AKCJA, "potwierdzenie głosem",
                        mapOf("odpowiedź" to "NIE", "usłyszano" to heard?.take(40))
                    )
                    cancelAction()
                }
                pl.victor.app.actions.ConfirmationReply.Reply.UNCLEAR -> {
                    diag.event(
                        DiagFormat.Phase.AKCJA, "potwierdzenie NIEROZSTRZYGNIĘTE",
                        mapOf("usłyszano" to heard?.take(40))
                    )
                    // Milczenie i wahanie traktujemy tak samo: nie wykonujemy.
                    Log.i(TAG, "Potwierdzenie głosem nierozstrzygnięte: \"$heard\"")
                    audio.speak(
                        "Nie odczytałem odpowiedzi, więc nic nie robię. " +
                            "Powiedz \"tak\" albo potwierdź w aplikacji.",
                        language = language
                    )
                }
            }
        }
    }

    /**
     * Potwierdzenie akcji przez usera (z dialogu albo głosem).
     */
    fun confirmAction() {
        val pending = _pendingActionConfirmation.value
        if (pending != null) {
            Log.i(TAG, "User confirmed action: ${pending.actions}")
            runCatching {
                diag.event(
                    DiagFormat.Phase.AKCJA, "akcja POTWIERDZONA",
                    mapOf("co" to pending.actions.joinToString { it.type.name })
                )
            }
            confirmationJob?.cancel()
            _pendingActionConfirmation.value = null
            executeActionsList(pending.actions)
        }
    }

    /**
     * Anulowanie akcji przez usera.
     */
    fun cancelAction() {
        val pending = _pendingActionConfirmation.value
        if (pending != null) {
            Log.i(TAG, "User cancelled action: ${pending.actions}")
            runCatching {
                diag.event(
                    DiagFormat.Phase.AKCJA, "akcja ANULOWANA",
                    mapOf("co" to pending.actions.joinToString { it.type.name })
                )
            }
            confirmationJob?.cancel()
            _pendingActionConfirmation.value = null
            audio.speak("Anulowano", language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Idle
            // Idle nie wznawia nasłuchu samo (patrz init) - a anulowanie kończy
            // turę tak samo jak wykonanie akcji.
            conversationalMode.onAiFinishedSpeaking()
        }
    }

    /**
     * Sprawdza czy user chce informacji o URL z QR.
     * Słowa kluczowe: "co to", "co tam jest", "co na stronie", "co jest na stronie",
     * "co na tej", "co to za", "powiedz mi o", "czytaj", "streść"
     */
    private fun shouldFetchUrl(text: String, codes: List<ScannedCode>): Boolean {
        if (codes.isEmpty()) return false
        if (urlAnalyzer.extractUrls(codes).isEmpty()) return false

        val triggers = listOf(
            "co to", "co tam", "co na", "co jest", "czytaj", "streść",
            "streszcz", "powiedz o", "informacje", "info", "opowiedz"
        )
        val lower = text.lowercase()
        return triggers.any { lower.contains(it) } || text.isBlank()
    }

    /**
     * Sprawdza czy user chce OCR - czytanie tekstu z otoczenia.
     * Słowa kluczowe: "przeczytaj", "co pisze", "co jest napisane",
     * "przetłumacz", "menu", "etykieta", "tablica"
     */
    /**
     * Ostatnia droga do LITER, gdy Wi-Fi Direct tu nie wstaje.
     *
     * ## Czemu to nie przeczy wąskiemu warunkowi wyżej
     * Tamten mówi: nie podnoś strumienia pod jedno pytanie, bo kosztuje circa
     * 12 s, a migawka odda ostre zdjęcie szybciej. To prawda, DOPÓKI migawka
     * faktycznie je oddaje. Gdy bezpiecznik Wi-Fi Direct jest zatrzaśnięty,
     * wiemy z POMIARU, że nie odda - wróci miniatura, na której liter nie ma.
     * Wtedy wybór nie brzmi "szybciej czy wolniej", tylko "czekanie czy
     * odpowiedź zgadywana z szarej plamy".
     *
     * ## Ile to naprawdę kosztuje, łącznie z gorszym przypadkiem
     * Circa 12 s, gdy strumień wstaje - i to jest przypadek typowy. Gdy NIE
     * wstaje, [pl.victor.app.ble.VictorManager.startLiveVision] czeka na
     * pierwszą klatkę do dwudziestu sekund, zanim się podda. Tyle wynosi
     * sufit, i dopiero po nim schodzimy na zwykłą drogę ze zdjęciem.
     *
     * Ten sufit jest świadomie przyjęty: dotyczy wyłącznie pytań O LITERY przy
     * zatrzaśniętym bezpieczniku, czyli sytuacji, w której druga droga jest
     * już zmierzona jako nieskuteczna. Gdyby pomiar z terenu pokazał, że
     * strumień też tam nie wstaje, ten warunek trzeba będzie odwołać - i
     * dlatego wynik idzie do dziennika z czasem.
     *
     * Dziennik z 21:55 pokazuje obie strony naraz: pobranie oryginału zawiodło
     * po 10,4 s i model dostał 17 761 bajtów, a strumień w tym samym czasie
     * oddawał 1600x1200 bez zarzutu.
     *
     * ## Warunki są wąskie z rozmysłu
     * Wchodzi wyłącznie wtedy, gdy pytanie DOTYCZY liter, strumień jeszcze nie
     * stoi, a bezpiecznik jest zatrzaśnięty. Przy "co przede mną jest"
     * miniatura wystarcza i nikt nie ma czekać 12 sekund za nic.
     *
     * Strumień jest po wszystkim gaszony: podniesiony pod jedno pytanie i
     * zostawiony trzymałby łącze oraz baterię okularów bez powodu.
     */
    private suspend fun streamFrameForText(useVision: Boolean, textQuestion: String): ByteArray? {
        if (!useVision) return null
        if (glassesManager.isLiveVisionRunning) return null
        if (!glassesManager.wifiDirectKnownBroken) return null
        if (!pl.victor.app.ai.VisionDetail.needsDetail(textQuestion)) return null

        _state.value = OrchestratorState.Capturing(
            progress = 1,
            total = 1,
            label = "Ostre zdjęcie tu nie przechodzi - biorę obraz ze strumienia. " +
                "Chwilę to potrwa."
        )
        val startedAt = System.currentTimeMillis()
        if (!glassesManager.startLiveVision()) {
            // POWÓD, NIE TYLKO CZAS. Pierwszy pomiar z terenu oddał samo
            // "nie wstał ms=1242" i trzeba było szukać przyczyny trzy wiersze
            // wyżej, w zdarzeniu z innej fazy. A przyczyna była jedna i
            // banalna: wyłączone Wi-Fi w telefonie, które blokuje OBIE drogi
            // do ostrego obrazu naraz.
            diag.event(
                DiagFormat.Phase.ZDJĘCIE, "strumień dla liter nie wstał",
                mapOf(
                    "ms" to (System.currentTimeMillis() - startedAt),
                    "powód" to glassesManager.lastTransferFailure
                )
            )
            return null
        }
        return try {
            glassesManager.liveFrame(detail = true)?.also { frame ->
                diag.event(
                    DiagFormat.Phase.ZDJĘCIE, "litery ze strumienia zamiast miniatury",
                    mapOf(
                        "bajtów" to frame.size,
                        "ms" to (System.currentTimeMillis() - startedAt)
                    )
                )
            }
        } finally {
            glassesManager.stopLiveVision()
        }
    }

    private fun shouldRunOcr(text: String): Boolean {
        val triggers = listOf(
            "przeczytaj", "co pisze", "co napisane", "co tu pisze",
            "przetłumacz", "tłumacz", "menu", "etykieta", "tablica",
            "napis", "tekst", "czytaj", "wytłumacz"
        )
        val lower = text.lowercase()
        return triggers.any { lower.contains(it) } || text.isBlank()
    }

    /**
     * Wyczyść kontekst rozmowy (nowa sesja).
     */
    fun clearConversation() {
        conversationContext.clear()
    }

    /**
     * Ile jest wymian w kontekście.
     */
    fun getConversationSize(): Int = conversationContext.size()

    /**
     * Publikuje model wynikający z BIEŻĄCYCH ustawień, bez tworzenia providera i bez sieci.
     *
     * [_currentModelId] było dotąd aktualizowane wyłącznie w [getOrCreateProvider], czyli
     * dopiero przy pierwszym realnym zapytaniu do AI. Po zmianie modelu w ustawieniach
     * badge na ekranie głównym pokazywał więc poprzedni model aż do następnego pytania.
     * Ekran główny woła to przy wejściu i po powrocie z ustawień.
     */
    fun publishConfiguredModel() {
        val providerId = settings.getActiveProvider()
        _currentModelId.value = if (providerId == AIProviderFactory.LOCAL_PROVIDER_ID) {
            pl.victor.app.localmodel.LocalModelCatalog.QWEN_0_8B.id
        } else {
            settings.getSelectedModel(providerId)
                ?: pl.victor.app.data.ModelRegistry.defaultFor(providerId)?.id
        }
    }

    // === Private ===

    private suspend fun getOrCreateProvider(): AIProvider {
        val providerId = settings.getActiveProvider()

        if (providerId == AIProviderFactory.LOCAL_PROVIDER_ID) {
            // Bez klucza, bez walidacji modeli u zdalnego providera - po co
            // próbować sieci dla trybu, którego cały sens to działanie offline.
            if (currentProviderId != providerId) {
                currentProvider = AIProviderFactory.create(providerId, "", context).provider
                currentProviderId = providerId
                activeModelId = null
            }
            // Publikuj realny model lokalny, nie null. Przy null badge na ekranie głównym
            // wpadał w swój fallback i pokazywał Gemini, mimo że aktywny był model offline.
            _currentModelId.value = pl.victor.app.localmodel.LocalModelCatalog.QWEN_0_8B.id
            return currentProvider!!
        }

        val apiKey = settings.getApiKey(providerId)
            ?: throw AIProviderException(
                "Brak klucza API dla $providerId. Ustaw go w ustawieniach.",
                providerId = providerId,
                isRetryable = false
            )

        val preferredModel = settings.getSelectedModel(providerId)

        // Sprawdź czy trzeba odświeżyć (provider lub model się zmienił)
        if (currentProviderId != providerId || activeModelId != preferredModel) {

            // Walidacja modeli u providera (async, nie blokuje UI)
            val available = try {
                val validator = RemoteModelValidator(apiKey, providerId)
                validator.fetchAvailableModels()
            } catch (e: Exception) {
                Log.w(TAG, "Could not validate models", e)
                emptyList()
            }

            // Stwórz provider z resolverem
            val withMetadata = AIProviderFactory.create(
                providerId = providerId,
                apiKey = apiKey,
                context = context,
                preferredModelId = preferredModel,
                availableFromProvider = available
            )

            currentProvider = withMetadata.provider
            currentProviderId = providerId
            activeModelId = withMetadata.modelId
            _currentModelId.value = withMetadata.modelId  // publikuj dla UI

            // Pokaż ostrzeżenie jeśli model jest deprecated lub zmigrowany
            withMetadata.resolution.warning?.let { warning ->
                _modelWarning.value = warning.toUserMessage()
                Log.w(TAG, "Model warning: ${warning.toUserMessage()}")
            }

            // Jeśli automatyczna migracja - zaktualizuj ustawienia
            if (withMetadata.resolution.source ==
                pl.victor.app.data.ModelSource.AUTO_MIGRATED) {
                withMetadata.resolution.warning?.let { warning ->
                    if (warning is pl.victor.app.data.ModelWarning.AutoMigrated) {
                        settings.setSelectedModel(providerId, warning.newModelId)
                    }
                }
            }
        }
        return currentProvider!!
    }

    /**
     * Kolejność providerów do próby: aktywny z Ustawień pierwszy, potem reszta
     * providerów, dla których user w ogóle ma wpisany klucz API - w kolejności
     * z [AIProviderFactory.supportedProviders]. Nie próbujemy providera bez klucza,
     * bo to i tak od razu by zawiodło.
     */
    private fun fallbackProviderOrder(): List<String> {
        val primary = settings.getActiveProvider()
        val others = AIProviderFactory.supportedProviders()
            .map { it.id }
            .filter { it != primary && settings.hasApiKey(it) }
        return listOf(primary) + others
    }

    /**
     * Buduje provider dla próby fallbacku - celowo NIE dotyka `currentProvider`/
     * `currentProviderId` ani ustawień. Fallback jest jednorazowy, dla tego
     * konkretnego zapytania - nie zmienia trwale wybranego providera usera.
     */
    private suspend fun buildProviderForFallback(providerId: String): AIProvider {
        if (providerId == AIProviderFactory.LOCAL_PROVIDER_ID) {
            return AIProviderFactory.create(providerId, "", context).provider
        }

        val apiKey = settings.getApiKey(providerId)
            ?: throw AIProviderException(
                "Brak klucza API dla $providerId",
                providerId = providerId,
                isRetryable = false
            )
        val preferredModel = settings.getSelectedModel(providerId)
        val available = try {
            RemoteModelValidator(apiKey, providerId).fetchAvailableModels()
        } catch (e: Exception) {
            Log.w(TAG, "Could not validate models for fallback provider $providerId", e)
            emptyList()
        }
        return AIProviderFactory.create(
            providerId = providerId,
            apiKey = apiKey,
            context = context,
            preferredModelId = preferredModel,
            availableFromProvider = available
        ).provider
    }

    /**
     * Czyści aktywne ostrzeżenie (po wyświetleniu użytkownikowi).
     */
    fun clearModelWarning() {
        _modelWarning.value = null
    }

    /**
     * Pobiera aktywną personę na podstawie ustawień.
     * Jeśli wybrano "custom" - używa wpisanego promptu.
     */
    fun getActivePersona(): Persona {
        val personaId = settings.getSelectedPersonaId()
        return when (personaId) {
            "custom" -> {
                val customPrompt = settings.getCustomPersonaPrompt()
                if (customPrompt.isBlank()) {
                    PersonaRegistry.default()
                } else {
                    PersonaRegistry.customFromPrompt(customPrompt)
                }
            }
            else -> PersonaRegistry.findById(personaId) ?: PersonaRegistry.default()
        }
    }

    /**
     * Szuka w historii rozmów podobnych do bieżącego pytania i buduje z nich kontekst.
     * Respektuje przełącznik "pamięć długoterminowa" w ustawieniach.
     *
     * @return fragment promptu albo `null` gdy wyłączone lub brak trafień
     */
    private suspend fun buildMemoryContext(question: String): String? {
        if (!settings.isLongTermMemoryEnabled()) return null
        return try {
            val entries = history.getRecent(MEMORY_SEARCH_POOL)
            if (entries.isEmpty()) return null

            val matches = longTermMemory
                .findSimilar(question, entries, limit = MEMORY_MAX_MATCHES)
                .filter { it.score >= MEMORY_MIN_SCORE }
            if (matches.isEmpty()) return null

            Log.i(TAG, "Pamięć: ${matches.size} podobnych rozmów (najlepsza ${matches.first().score})")
            buildString {
                append("Wcześniejsze rozmowy z tym użytkownikiem na podobny temat:\n")
                matches.forEach { match ->
                    append("- Pytanie: ").append(match.entry.userQuestion.take(200)).append('\n')
                    append("  Odpowiedź: ").append(match.entry.aiResponse.take(300)).append('\n')
                }
                append("Wykorzystaj to jeśli pomaga, ale nie powtarzaj bez potrzeby.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pamięć długoterminowa niedostępna", e)
            null
        }
    }

    /**
     * Tłumaczy tekst odczytany przez OCR na język docelowy z ustawień,
     * ale tylko gdy użytkownik faktycznie prosi o tłumaczenie.
     */
    private suspend fun translateOcrIfRequested(question: String, ocr: OCRResult?): String? {
        if (ocr == null || !ocr.isSuccess || ocr.fullText.isBlank()) return null
        if (!wantsTranslation(question)) return null

        val target = settings.getTranslationTarget()
        return try {
            val source = settings.getResponseLanguage().take(2).lowercase()
            val translated = translator.translate(ocr.fullText.take(1000), source, target)
            if (translated.isBlank() || translated == ocr.fullText) return null
            val targetName = pl.victor.app.translation.SimultaneousTranslator.languageName(target)
            Log.i(TAG, "Przetłumaczono OCR na $target")
            "Tłumaczenie odczytanego tekstu ($targetName):\n$translated"
        } catch (e: Exception) {
            Log.w(TAG, "Tłumaczenie OCR nie powiodło się", e)
            null
        }
    }

    /** Czy pytanie użytkownika dotyczy tłumaczenia. */
    private fun wantsTranslation(question: String): Boolean {
        val q = question.lowercase()
        return TRANSLATION_KEYWORDS.any { q.contains(it) }
    }

    companion object {
        /** Klucze tematów kontekstu - patrz [openContextTopics]. */
        private const val TOPIC_CALENDAR = "kalendarz"
        private const val TOPIC_WEATHER = "pogoda"
        private const val TOPIC_MAIL = "poczta"
        private const val TOPIC_NOTES = "notatki"

        /**
         * Wspólny prompt systemowy dla trybów dostępności.
         * Model widzi pojedyncze zdjęcie z okularów - nie ma czujnika odległości
         * ani podglądu na żywo, więc nie wolno mu udawać systemu bezpieczeństwa.
         */
        /**
         * Zasada, bez której model kłamie o notatkach.
         *
         * Zgłoszone wprost: "powiedziałem «Notatka: kupić XYZ», AI odpowiedziało
         * «zapisuję w Twoich notatkach», ale nic nie zapisało". Model nie ma
         * czym zapisać notatki - robi to warstwa 0, ZANIM cokolwiek do niego
         * pójdzie. Jeśli więc prośba o notatkę do niego dotarła, znaczy to, że
         * nie została rozpoznana; jedyną uczciwą odpowiedzią jest przyznanie
         * się i podanie formuły, która zadziała. Cicha obietnica jest gorsza
         * niż odmowa, bo użytkownik odchodzi przekonany, że notatkę ma.
         */
        /**
         * System prompt dla zadań pomocniczych ([askModelPlain]).
         *
         * Persona jest tu przeszkodą, a nie zaletą: asystent z charakterem
         * dopisze do streszczenia zdanie od siebie, a wynik ma trafić do pola
         * tekstowego, nie do rozmowy.
         */
        /**
         * Górny limit blokady uśpienia na jedną turę.
         *
         * Nie dziesięć sekund, jak przy krótkich zadaniach w tle: tura potrafi
         * trwać od nasłuchu przez zdjęcie po odpowiedź modelu. Limit jest
         * bezpiecznikiem na wypadek zgubionego release(), a nie planem.
         */
        /**
         * Po tylu milisekundach w stanie roboczym uznajemy turę za zgubioną.
         *
         * Hojnie: najdłuższa uczciwa tura to nasłuch, zdjęcie w pełnej
         * rozdzielczości przez Wi-Fi i odpowiedź modelu. Lepiej odblokować za
         * późno niż przerwać turę, która naprawdę trwa.
         */
        private const val STUCK_TURN_MS = 180_000L

        /**
         * Ile tura jest chroniona przed przejęciem przez następny trigger.
         *
         * Półtorej sekundy, i to nie jest zapas na wyrost: słowo wybudzenia bywa
         * wykryte dwa razy pod rząd (wynik częściowy i końcowy silnika), a bez tej
         * karencji drugie wykrycie ubijałoby turę, którą samo przed chwilą
         * zaczęło - i asystent nie odpowiedziałby nigdy.
         */
        private const val TAKEOVER_GRACE_MS = 1_500L

        /**
         * Ile łącznie wolno zbierać kontekst, zanim pytanie pójdzie do modelu.
         *
         * Sześć sekund, bo pomiar mówi 368-492 ms w normalnej turze i 4952 ms w
         * najgorszej zaobserwowanej - sufit ma odcinać zawieszenie, a nie
         * zdrowe, choć powolne źródło. Termin jest WSPÓLNY dla wszystkich
         * sześciu, patrz miejsce użycia.
         */
        private const val CONTEXT_BUDGET_MS = 6_000L

        /**
         * Jak długo po odpowiedzi (albo po świadomym włączeniu nasłuchu) wolno
         * dopowiadać bez frazy wybudzenia - patrz `conversationOpen`.
         *
         * Czterdzieści pięć sekund: tyle, żeby zdążyć wysłuchać odpowiedzi i
         * dopytać, i za mało, żeby rozmowa przy stole godzinę później trafiła do
         * modelu jako pytanie.
         */
        private const val CONVERSATION_WINDOW_MS = 45_000L

        /** Nazwy dróg transkrypcji - patrz [lastTranscriptionSource]. */
        const val SOURCE_CLOUD = "Chmura (Whisper)"
        const val SOURCE_PHONE = "Nasłuch telefonu"
        const val SOURCE_ON_DEVICE = "Rozpoznawanie systemowe (offline)"
        const val SOURCE_VOSK = "Vosk (offline, słabszy)"
        const val SOURCE_AUDIO_TO_MODEL = "Bez transkrypcji - nagranie do modelu"

        /**
         * Ile czekamy na "tak" albo "nie" po pytaniu o potwierdzenie.
         *
         * Krócej niż zwykły nasłuch: to jest odpowiedź na pytanie zamknięte, więc
         * albo pada od razu, albo użytkownik sięga po telefon.
         */
        private const val CONFIRMATION_TIMEOUT_MS = 7_000L

        /**
         * Bezpiecznik blokady uśpienia na czas NASŁUCHU.
         *
         * Nasłuch ma własny limit i nie może trwać dłużej - dziewięćdziesiąt sekund
         * to zapas nad nim, nie planowany czas pracy.
         */
        private const val LISTEN_WAKE_LOCK_MS = 90_000L

        /**
         * Bezpiecznik blokady uśpienia na czas CAŁEJ TURY.
         *
         * Dłuższy niż nasłuch, bo tura to zupełnie inna praca: zdjęcie przez Wi-Fi
         * (z ponowieniami), kilka kontekstów, odpowiedź modelu i odczytanie jej na
         * głos. Przy dziewięćdziesięciu sekundach bezpiecznik potrafił zejść W TRAKCIE
         * odpowiedzi - przy zgaszonym ekranie procesor przysypiał i głos się urywał.
         * Wartość leży POWYŻEJ [STUCK_TURN_MS], żeby to watchdog kończył zawieszoną
         * turę, a nie wygaśnięcie blokady.
         */
        private const val TURN_WAKE_LOCK_MS = 240_000L

        /** Nazwy właścicieli blokady - patrz [pl.victor.app.power.WakelockHelper]. */
        private const val LOCK_LISTENING = "Nasluch"
        private const val LOCK_TURN = "Tura"

        private const val PLAIN_TASK_SYSTEM_PROMPT =
            "Jesteś narzędziem tekstowym. Wykonujesz dokładnie to, o co prosi " +
                "polecenie, i odpowiadasz samą treścią wyniku - bez powitania, " +
                "bez komentarza, bez pytań zwrotnych i bez cudzysłowów."

        /**
         * Prośba o cudzysłów wokół angielskich wtrętów.
         *
         * Nie kosmetyka: syntezator mowy czyta wtedy taki fragment poprawnie, a
         * bez cudzysłowu wymawia angielskie słowa tak, jak się je pisze po
         * polsku. Zauważone w użyciu - "jak jest cudzysłów, to czyta dobrze" -
         * i wykorzystane po obu stronach: model ma tak pisać, a dzielenie tekstu
         * na głosy traktuje cudzysłów jako mocny sygnał.
         */
        private const val ENGLISH_QUOTING_PROMPT =
            "\n\nJĘZYK: gdy w polskiej odpowiedzi wstawiasz angielskie zdanie, " +
                "nazwę własną, cytat albo tytuł, ZAWSZE bierz je w cudzysłów. " +
                "Dzięki temu syntezator mowy przeczyta je po angielsku, a nie " +
                "literami po polsku. Pojedynczych słów powszechnie używanych po " +
                "polsku (weekend, marketing, komputer) nie cytuj."

        private const val NOTES_CAPABILITY_PROMPT =
            "\n\nNOTATKI: aplikacja UMIE zapisywać notatki i user ma je w " +
                "swojej zakładce Notatki - nigdy nie mów, że takiej funkcji nie " +
                "ma. Zapisuje je jednak sama aplikacja, zanim wiadomość dotrze " +
                "do Ciebie, więc TY osobiście niczego nie zapisujesz: nie mów, " +
                "że zapisałeś, zapamiętałeś ani dodałeś. Gdy prośba o zapisanie " +
                "dotarła do Ciebie, znaczy to, że aplikacja jej nie rozpoznała - " +
                "powiedz wtedy, że TEJ jednej notatki nie zapisałeś, i podaj " +
                "formułę, która działa: \"Notatka: ...\" albo \"Zapisz, że ...\". " +
                "Notatki, które user ma zapisane, dostajesz w kontekście i " +
                "możesz o nich swobodnie mówić."

        /**
         * Zakaz wymyślania cudzych danych, gdy ich w poleceniu nie ma.
         *
         * ## Skąd to się wzięło
         * Ze zgłoszenia: "zmyślał coś o tym, że mam coś w kalendarzu, czego nie
         * mam". Kalendarz, poczta i pogoda doklejane są WARUNKOWO - tylko gdy
         * pytanie zostanie rozpoznane jako ich dotyczące. Rozpoznanie poprawiłem
         * i z dziesięciu prawdziwych pytań przechodzi teraz dziesięć zamiast
         * jednego, ale ŻADNA lista słów nie będzie pełna. Zawsze znajdzie się
         * zdanie, po którym model dostanie pytanie o plany i ani jednej danej.
         *
         * Wtedy potrzebna jest reguła, nie słownik: brak sekcji ZNACZY brak
         * danych. Bez niej model wypełnia lukę tym, co brzmi prawdopodobnie - a
         * wymyślone spotkanie jest gorsze niż przyznanie się do niewiedzy,
         * bo na nim buduje się potem cały dzień.
         */
        private const val PRIVATE_DATA_HONESTY_PROMPT =
            "\n\nDANE OSOBISTE UŻYTKOWNIKA: jego kalendarz, pocztę, pogodę i " +
                "notatki dostajesz WYŁĄCZNIE jako osobne sekcje w tym poleceniu " +
                "(np. \"=== KALENDARZ UŻYTKOWNIKA ===\"). Nie masz do nich " +
                "żadnego innego dostępu. Jeśli takiej sekcji tutaj NIE MA, to " +
                "znaczy, że tych danych nie dostałeś - i wtedy NIE WOLNO Ci " +
                "podawać ani zgadywać wydarzeń, godzin, wiadomości czy " +
                "temperatur. Powiedz krótko, że nie masz tych danych pod ręką i " +
                "poproś o powtórzenie pytania ze słowem \"kalendarz\", " +
                "\"poczta\" albo \"pogoda\". Zmyślone spotkanie jest gorsze " +
                "niż przyznanie, że czegoś nie wiesz."

        private const val ACCESSIBILITY_SYSTEM_PROMPT =
            "Jesteś asystentem osoby niewidomej. Widzisz pojedyncze zdjęcie z kamery " +
                "w okularach, zrobione kilka sekund temu. Nie masz czujnika odległości " +
                "i nie widzisz ruchu. Nigdy nie podawaj odległości w metrach ani " +
                "centymetrach i nigdy nie zapewniaj, że droga jest wolna lub bezpieczna. " +
                "Gdy czegoś nie widzisz wyraźnie - powiedz to. Mów krótko, rzeczowo, po polsku."

        /** Ile ostatnich rozmów przeszukiwać w pamięci długoterminowej. */
        private const val MEMORY_SEARCH_POOL = 50
        private const val MEMORY_MAX_MATCHES = 3
        private const val MEMORY_MIN_SCORE = 0.15f

        private val TRANSLATION_KEYWORDS = listOf(
            "przetłumacz", "tłumacz", "translate", "po angielsku", "po niemiecku",
            "po polsku", "co to znaczy", "what does it mean"
        )

        private const val TAG = "AIOrchestrator"

        /** Najkrótszy odstęp między wysyłkami dziennika - patrz uploadDiagnosticsInBackground. */
        private const val DIAG_UPLOAD_INTERVAL_MS = 60_000L

        /**
         * Komunikat, który [ActionExecutor] zwraca, gdy nie ma nic ciekawego do
         * powiedzenia. Wtedy - i tylko wtedy - opowiadamy o akcji jej własnym
         * opisem. Bierzemy go STAMTĄD, żeby zmiana po tamtej stronie nie
         * rozjechała tego porównania po cichu.
         */
        private const val GENERIC_ACTION_SUCCESS = ActionExecutor.GENERIC_SUCCESS

        /**
         * Po tylu cichych turach z rzędu przez SCO aplikacja sama wraca na
         * mikrofon telefonu - patrz [noteSilentTurn].
         */
        private const val SILENT_SCO_LIMIT = 3

        /**
         * Co powiedzieć modelowi, gdy pytanie idzie NAGRANIEM, a nie tekstem.
         *
         * Model dostaje wtedy dźwięk z mikrofonu okularów zamiast transkrypcji -
         * musi więc wiedzieć, że pytania ma szukać w nagraniu, a nie w tym
         * zdaniu, i że odpowiedź będzie odczytana na głos.
         *
         * ## Dlaczego jest tu ostrzeżenie o danych na żywo
         * Kalendarz, poczta i prognoza są doklejane do promptu tylko wtedy, gdy
         * PYTANIE ich dotyczy - a przy nagraniu nie wiemy, o co użytkownik pyta,
         * dopóki model go nie wysłucha. Bez tego zdania model dostałby pytanie
         * "jaka jutro pogoda", nie dostałby prognozy i odpowiedziałby z własnej
         * pamięci, czyli zmyślił. Lepiej, żeby powiedział wprost, że tej drogi
         * trzeba użyć inaczej.
         */
        private const val AUDIO_QUESTION_PROMPT =
            "W załączonym nagraniu użytkownik zadaje pytanie. Wysłuchaj go i " +
                "odpowiedz na nie. Nie transkrybuj nagrania i nie opisuj, co " +
                "słyszysz - po prostu odpowiedz, krótko i tak, jak się mówi na " +
                "głos. Jeśli nagranie jest niewyraźne albo nie ma w nim pytania, " +
                "powiedz to jednym zdaniem.\n" +
                "Kalendarz, pogodę, pocztę i notatki masz dołączone niżej, tak " +
                "samo jak przy pytaniu wpisanym z klawiatury - korzystaj z nich " +
                "normalnie. Gdy któregoś z nich w tekście poniżej NIE MA, powiedz " +
                "krótko, że akurat tego nie sprawdzisz, i NIE zgaduj temperatury, " +
                "godzin spotkań ani treści maili."

        /** Komendy uciszające syntezator - patrz [handleMetaCommand]. */
        private val SILENCE_COMMAND_REGEX =
            Regex("""^(stop|przesta[nń]|cicho|zamilcz|anuluj|dosy[cć])$""")

        /**
         * Pytanie podstawiane, gdy user powiedział samo "zrób zdjęcie" (warstwa 0).
         * Bez niego model dostałby jako pytanie polecenie "zrób zdjęcie" razem ze
         * zdjęciem i odpowiadałby na nie dosłownie.
         */
        /**
         * Pytanie dla ścieżki „popatrz i powiedz": przycisk „Pokaż" i komenda
         * „zrób zdjęcie".
         *
         * Zdanie o tekście nie jest ozdobnikiem. Zdjęcia z okularów idą domyślnie
         * jako miniatury, a na miniaturze model potrafi źle odczytać napis, który
         * lokalny OCR (ML Kit, na urządzeniu, ułamek sekundy) odczyta pewnie.
         * To zdanie włącza tę ścieżkę - [shouldRunOcr] szuka w pytaniu słowa
         * „przeczytaj" - i jednocześnie mówi modelowi, czego od niego chcemy.
         */
        /**
         * Ile czekamy na JEDNEGO dostawcę modelu, zanim uznamy go za martwego.
         *
         * Patrz uzasadnienie przy wywołaniu: 60 s ciszy w dzienniku z 23:07,
         * przy najdłuższej udanej odpowiedzi trwającej 20 s.
         */
        private const val MODEL_ATTEMPT_TIMEOUT_MS = 45_000L

        /**
         * Do tylu słów wypowiedź jest ODPOWIEDZIĄ na pytanie o potwierdzenie.
         *
         * Dłuższa jest nowym poleceniem, nawet jeśli zaczyna się od "tak":
         * "tak, a przy okazji jaka jest pogoda" ma pójść do modelu, a nie
         * wysłać wiadomość, o której mowa była zdanie wcześniej.
         */
        private const val CONFIRMATION_MAX_WORDS = 3

        private const val PHOTO_ON_DEMAND_QUESTION =
            "Opisz krótko, co widać na tym zdjęciu. Jeśli jest na nim tekst, " +
                "przeczytaj to, co istotne."
    }
}

/**
 * Stan orkiestratora - obserwowany przez UI
 */
sealed class OrchestratorState {
    object Idle : OrchestratorState()

    /** Mikrofon otwarty - czekamy, aż użytkownik powie, o co mu chodzi. */
    object Listening : OrchestratorState()
    /**
     * @param label co dokładnie się dzieje, gdy samo "Przechwytuję obraz" to
     *   za mało. Pobranie zdjęcia w pełnej rozdzielczości idzie przez Wi-Fi
     *   Direct i trwa kilkanaście sekund - bez tego zdania wygląda to jak
     *   zawieszenie, a nie jak praca.
     */
    data class Capturing(
        val progress: Int,
        val total: Int,
        val label: String? = null
    ) : OrchestratorState()
    object Thinking : OrchestratorState()
    data class Streaming(val text: String) : OrchestratorState()  // nowy - streaming partial
    data class Completed(val text: String) : OrchestratorState()
    data class Error(val message: String) : OrchestratorState()
}

enum class TriggerSource { BUTTON, TEXT_INPUT, WAKE_WORD, VOICE }

/**
 * Czy ten sposób wywołania ma prawo PORZUCIĆ trwającą turę.
 *
 * Przejmowanie miał dotąd wyłącznie przycisk na okularach - i to była luka,
 * przez którą przechodziło zgłoszenie "jest jakiś problem z komendami,
 * nasłuchiwaniem". Ktoś w okularach nie ma pod ręką przycisku "Przerwij" w
 * aplikacji: gdy tura utknie, jedyne, co może zrobić, to powiedzieć słowo
 * kluczowe jeszcze raz. A właśnie to było ignorowane - przez [STUCK_TURN_MS],
 * czyli trzy minuty.
 *
 * Wpisane pytanie ([TEXT_INPUT]) nie przejmuje: kto pisze, ten widzi na ekranie,
 * że tura trwa, i ma przycisk przerwania.
 */
private fun TriggerSource.mayTakeOverTurn(): Boolean =
    this == TriggerSource.BUTTON ||
        this == TriggerSource.WAKE_WORD ||
        this == TriggerSource.VOICE

/**
 * Czy to wywołanie wolno wpuścić także wtedy, gdy asystent właśnie MÓWI.
 *
 * Tylko przycisk. Wypowiedź w tym momencie może być echem własnego głosu
 * asystenta z głośnika okularów - patrz [AIOrchestrator.canBeSuperseded];
 * wciśnięty przycisk echem nie jest.
 */
private fun TriggerSource.mayInterruptSpeech(): Boolean = this == TriggerSource.BUTTON

/**
 * Akcja oczekująca na potwierdzenie użytkownika.
 */
data class PendingActionConfirmation(
    val actions: List<Action>,
    val title: String,
    val message: String,
    val confirmText: String,
    val cancelText: String
)
