package pl.victor.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
import pl.victor.app.ble.ConnectionAnnouncer
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
import pl.victor.app.orchestrator.languageTagFor

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
    private val history: HistoryRepository
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

    /** Klawisz słuchawek w trakcie mówienia = "cicho" - patrz [SpeechMediaButtons]. */
    private val mediaButtons by lazy {
        pl.victor.app.audio.SpeechMediaButtons(context) { keyCode ->
            scope.launch {
                bezpiecznie("klawisz multimediów") {
                    val mówimy = audio.speaking.value
                    runCatching {
                        diag.event(
                            DiagFormat.Phase.WAKE, "klawisz multimediów z okularów",
                            mapOf(
                                "klawisz" to android.view.KeyEvent.keyCodeToString(keyCode),
                                "mówię" to mówimy,
                                "tłumaczenie" to earTranslation.value
                            )
                        )
                    }
                    // Te same zasady co ramka "ucisz": dotyczy mówienia, a w
                    // tłumaczeniu wychodzi się przyciskiem.
                    if (liveConversationOn()) {
                        stopLiveConversation("klawisz słuchawek")
                        return@bezpiecznie
                    }
                    if (!mówimy || earTranslation.value) return@bezpiecznie
                    cancelCurrentTurn("klawisz słuchawek (dotknięcie zausznika)")
                }
            }
        }
    }

    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val _lastResponse = MutableStateFlow<AIResponse?>(null)
    val lastResponse: StateFlow<AIResponse?> = _lastResponse.asStateFlow()

    /** Dostawcy modelu - patrz [pl.victor.app.orchestrator.ProviderManager]. */
    private val providers = pl.victor.app.orchestrator.ProviderManager(context, settings)

    /** Ostrzeżenia o modelu (deprecated, migration, itd.). */
    val modelWarning: StateFlow<String?> get() = providers.modelWarning

    /** Aktualnie używany model. */
    val currentModelId: StateFlow<String?> get() = providers.currentModelId

    /** Publikuje model z bieżących ustawień - patrz [pl.victor.app.orchestrator.ProviderManager.publishConfiguredModel]. */
    fun publishConfiguredModel() = providers.publishConfiguredModel()

    /** Czyści aktywne ostrzeżenie (po wyświetleniu użytkownikowi). */
    fun clearModelWarning() = providers.clearModelWarning()

    private suspend fun getOrCreateProvider(): AIProvider = providers.getOrCreateProvider()

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

    /** Adresat, temat i wątek maila ustalane PRZED pytaniem „Wysłać?” - patrz [pl.victor.app.actions.EmailPreparer]. */
    private val emailPreparer = pl.victor.app.actions.EmailPreparer(context)
    private val urlAnalyzer = URLAnalyzer()
    private val translator = pl.victor.app.translation.SimultaneousTranslator()
    private val longTermMemory = pl.victor.app.memory.LongTermMemory(context, history)

    /**
     * Pamięć po znaczeniu (embeddingi) - patrz [pl.victor.app.memory.SemanticMemory].
     * Wyłączona albo przy "Tylko darmowe funkcje" zostaje samo [longTermMemory].
     */
    private val semanticMemory = pl.victor.app.memory.SemanticMemoryService(
        context = context,
        settings = settings,
        history = history,
        diag = { diag },
        scope = scope
    )
    private val conversationContext = ConversationContext()
    private val captureModeSelector = pl.victor.app.camera.CaptureModeSelector()

    // Tryb konwersacyjny (continuous listening)
    /** Rozpoznawanie mowy - wspólne dla trybu konwersacyjnego i wybudzenia z okularów. */
    private val speechToText = pl.victor.app.conversation.SpeechToText(context)

    private val conversationalMode = ConversationalMode(
        audio = audio,
        // Nasłuch trybu konwersacyjnego oddaje mikrofon frazy pod własnym
        // imieniem - koniec tego nasłuchu nie może wznowić frazy w trakcie tury.
        pauseWakeMic = {
            victorApp?.pauseWakeForTurn(pl.victor.app.VictorApplication.WAKE_HOLDER_CONVERSATION)
        },
        resumeWakeMic = {
            victorApp?.resumeWakeAfterTurn(pl.victor.app.VictorApplication.WAKE_HOLDER_CONVERSATION)
        },
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
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Anulowanie (np. koniec spotkania, przerwana tura) musi lecieć dalej -
        // połknięte, zamieniało się w "model nic nie odpowiedział".
        throw e
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

    /** Wiadomości z powiadomień - patrz [pl.victor.app.messages.MessageAnnouncer]. */
    private val messages by lazy {
        pl.victor.app.messages.MessageAnnouncer(
            scope = scope,
            settings = settings,
            tematy = openContextTopics,
            okulary = { glassesManager.isConnected() },
            cisza = { ciszaWTle() },
            mów = { tekst -> audio.speakAndAwait(tekst, language = settings.getResponseLanguage()) },
            dziennik = { co, pola -> runCatching { diag.event(DiagFormat.Phase.MOWA, co, pola) } },
            bezpiecznie = { co, blok -> bezpiecznie(co, blok) }
        )
    }

    /** Home Assistant użytkownika - adres i token czytane przy każdym wywołaniu. */
    private val homeAssistant by lazy {
        pl.victor.app.home.HomeAssistantClient(
            adres = { settings.getHomeAssistantUrl() },
            token = { settings.getHomeAssistantToken() }
        )
    }

    /**
     * Polecenie dla domu ("włącz światło w salonie", "dom, …").
     *
     * ## Czemu przez conversation API HA, a nie przez model
     * HA sam rozumie polskie polecenia i zna swoje urządzenia; model musiałby
     * dostać ich listę w każdej turze i zgadywać identyfikatory. Za darmo i
     * szybciej - jedno zapytanie w sieci domowej.
     *
     * @return `true`, gdy zdanie zostało obsłużone (także komunikatem, że dom
     *   jest wyłączony albo nieskonfigurowany)
     */
    private fun handleHomeCommand(trigger: TriggerSource, question: String): Boolean {
        val włączone = settings.isHomeControlEnabled()
        val p = pl.victor.app.home.DomPolecenia.rozpoznaj(
            question, if (włączone) settings.getHomeAssistantAreas() else emptyList()
        ) ?: return false
        if (!włączone || !homeAssistant.skonfigurowany()) {
            // Bez domu "włącz światło" idzie dalej jak dotąd. Tylko jawne
            // "dom, …" dostaje wyjaśnienie, czemu nic się nie stało.
            if (!włączone && !p.jawne) return false
            val mowa = if (!włączone) pl.victor.app.home.HaMowa.WYŁĄCZONE else pl.victor.app.home.HaMowa.BRAK_KONFIGURACJI
            runCatching {
                diag.event(
                    DiagFormat.Phase.AKCJA, "dom: polecenie bez połączenia",
                    mapOf("włączone" to włączone, "tekst" to p.tekst.take(120))
                )
            }
            audio.speak(mowa, language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Completed(mowa)
            return true
        }
        runCatching {
            diag.event(
                DiagFormat.Phase.AKCJA, "dom: polecenie rozpoznane",
                mapOf("tekst" to p.tekst.take(120), "jawne" to p.jawne, "potwierdzenie" to p.wymagaPotwierdzenia)
            )
        }
        if (p.wymagaPotwierdzenia) {
            // Otwarcie zamka czy rozbrojenie alarmu z przesłyszenia byłoby
            // gorsze niż każda inna pomyłka asystenta - najpierw "na pewno?".
            actionFlow.requestConfirmation(
                PendingActionConfirmation(
                    actions = emptyList(),
                    title = "Wykonać w domu?",
                    message = p.tekst,
                    confirmText = "🏠 Wykonaj",
                    cancelText = "Anuluj",
                    homeCommand = p.tekst
                ),
                pl.victor.app.home.HaMowa.pytanieOPotwierdzenie(p.tekst)
            )
            return true
        }
        runHomeCommand(trigger, question, p.tekst)
        return true
    }

    /**
     * Wysyła zdanie do HA i mówi odpowiedź. Gdy HA nie zrozumiał, pytanie
     * idzie do zwykłego modelu z tą informacją (bez tokenu i adresu).
     */
    private fun runHomeCommand(trigger: TriggerSource, question: String, tekst: String) {
        scope.launch {
            val (w, odp) = homeAssistant.polecenie(tekst)
            val decyzja = pl.victor.app.home.HaDecyzja.z(w.kodHttp, w.błąd, odp, w.host)
            runCatching {
                diag.event(
                    DiagFormat.Phase.AKCJA, "dom: odpowiedź Home Assistant",
                    mapOf(
                        "tekst" to tekst.take(120),
                        "ms" to w.ms,
                        "http" to w.kodHttp,
                        "błąd" to w.błąd?.name,
                        "typ" to odp?.typ,
                        "kod" to odp?.kodBłędu,
                        "zrozumiał" to (decyzja is pl.victor.app.home.HaDecyzja.Decyzja.Powiedz && decyzja.zrozumiał),
                        "doModelu" to (decyzja is pl.victor.app.home.HaDecyzja.Decyzja.DoModelu)
                    )
                )
            }
            when (decyzja) {
                is pl.victor.app.home.HaDecyzja.Decyzja.DoModelu -> handleUserTrigger(
                    trigger,
                    pl.victor.app.home.HaMowa.dlaModelu(question, decyzja.odpowiedźHa),
                    internalPrompt = true
                )
                is pl.victor.app.home.HaDecyzja.Decyzja.Powiedz -> {
                    conversationalMode.onAiStartedSpeaking()
                    // Po polsku - tak pytamy HA i tak odpowiada.
                    audio.speakAndAwait(decyzja.mowa, language = "pl")
                    _state.value = OrchestratorState.Completed(decyzja.mowa)
                    conversationalMode.onAiFinishedSpeaking()
                }
            }
        }
    }

    /** Hermes Agent użytkownika - adres i klucz czytane przy każdym wywołaniu. */
    private val hermes by lazy {
        pl.victor.app.hermes.HermesClient(
            adres = { settings.getHermesUrl() },
            klucz = { settings.getHermesKey() },
            kluczSesji = { settings.getHermesSessionKey() }
        )
    }

    /**
     * Zadanie dla Hermesa ("Hermes, znajdź w Dokumentach fakturę…").
     *
     * ## Czemu osobna ścieżka, a nie kolejny dostawca modelu
     * Hermes to agent z własnym modelem, narzędziami i pamięcią - nie dostaje
     * promptu Victora, zdjęć ani znaczników akcji. Dostaje tekst, a odpowiedź
     * wraca strumieniem i jest czytana na głos zdanie po zdaniu.
     *
     * @return `true`, gdy zdanie zostało obsłużone (także wyjaśnieniem, że
     *   Hermes jest wyłączony - ale tylko przy jawnym wołaniu po imieniu)
     */
    private fun handleHermes(trigger: TriggerSource, question: String): Boolean {
        val p = pl.victor.app.hermes.HermesRouting.rozpoznaj(question, settings.isHermesAutoRoute()) ?: return false
        val dostępność = settings.hermesAvailability()
        if (dostępność != pl.victor.app.hermes.HermesDostępność.MOŻNA) {
            // Bez Hermesa zdanie idzie dalej jak dotąd. Tylko jawne wołanie po
            // imieniu dostaje wyjaśnienie, czemu nic się nie stało.
            if (!p.jawne) return false
            val mowa = dostępność.komunikat.orEmpty()
            runCatching {
                diag.event(
                    DiagFormat.Phase.AKCJA, "hermes: niedostępny",
                    mapOf("powód" to dostępność.name, "tekst" to p.tekst.take(120))
                )
            }
            audio.speak(mowa, language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Completed(mowa)
            return true
        }
        runHermes(p)
        return true
    }

    /**
     * Wysyła zadanie do Hermesa i czyta odpowiedź na głos w miarę jej
     * przychodzenia.
     *
     * To prawdziwa tura ([launchTurn]): "przerwij", dotknięcie zausznika i
     * nowe pytanie przerywają zapytanie (zamykają połączenie), a mikrofon
     * frazy i blokada uśpienia są brane i zwalniane jak przy zwykłej turze.
     */
    private fun runHermes(p: pl.victor.app.hermes.HermesPolecenie) {
        val micHold = java.util.concurrent.atomic.AtomicBoolean(true)
        holdMicForTurn()
        val releaseMic = { if (micHold.compareAndSet(true, false)) releaseMicAfterTurn() }
        val lockTurn = "$LOCK_TURN#${turnSeq.incrementAndGet()}"
        val turnJob = launchTurn {
            var audioHeld = false
            try {
                // Zadanie agenta bywa długie - blokada na kwadranse, nie na 4 minuty.
                wakeLock.acquire(lockTurn, HERMES_WAKE_LOCK_MS)
                _state.value = OrchestratorState.Thinking
                val canUseMedia = audio.canSpeakOverMedia()
                if (!canUseMedia) glassesManager.requestClassicAudio("brak A2DP przed odpowiedzią Hermesa")
                audioHeld = if (canUseMedia) false else audio.beginConversationRouting(waitForLink = false)
                conversationalMode.onAiStartedSpeaking()
                audio.beginStream("pl")
                runCatching {
                    diag.event(
                        DiagFormat.Phase.MODEL, "hermes: zapytanie",
                        mapOf(
                            "tekst" to p.tekst.take(120),
                            "jawne" to p.jawne,
                            "wiadomościHistorii" to hermes.historia.wiadomości(System.currentTimeMillis()).size
                        )
                    )
                }
                // Jedno miejsce, które pisze do strumienia mowy: ramki z sieci
                // (wątek IO) i zdania o cierpliwości (osobna korutyna) nie mogą
                // dopisywać do bufora jednocześnie.
                val mówienie = kotlinx.coroutines.sync.Mutex()
                val cierpliwość = pl.victor.app.hermes.HermesCierpliwość()
                cierpliwość.start(System.currentTimeMillis())
                val czujka = launch {
                    while (isActive) {
                        kotlinx.coroutines.delay(1_000)
                        cierpliwość.zapytaj(System.currentTimeMillis())?.let { zdanie ->
                            mówienie.withLock { audio.addStreamFragment(zdanie) }
                        }
                    }
                }
                val widoczny = StringBuilder()
                var zgodaOgłoszona = false
                var logowanychNarzędzi = 0
                var logowanychNieznanych = 0
                val wynik = try {
                    hermes.zapytaj(p.tekst) { r ->
                        when (r) {
                            is pl.victor.app.hermes.HermesRamka.Tekst -> if (r.kawałek.isNotEmpty()) {
                                cierpliwość.usłyszano(System.currentTimeMillis())
                                widoczny.append(r.kawałek)
                                mówienie.withLock { audio.addStreamFragment(r.kawałek) }
                                _state.value = OrchestratorState.Streaming(widoczny.toString())
                            }
                            is pl.victor.app.hermes.HermesRamka.Narzędzie -> if (logowanychNarzędzi++ < 20) {
                                runCatching {
                                    diag.event(DiagFormat.Phase.MODEL, "hermes: narzędzie", mapOf("opis" to r.opis))
                                }
                            }
                            is pl.victor.app.hermes.HermesRamka.Zgoda -> {
                                // FORMAT PROŚBY O ZGODĘ JESZCZE NIEZNANY - zapisujemy ramkę w całości
                                // (początek), żeby dało się dopisać odpowiedź. Hermes czeka na
                                // zgodę do 300 s, więc użytkownik musi o tym wiedzieć.
                                runCatching {
                                    diag.event(
                                        DiagFormat.Phase.MODEL, "hermes: prośba o zgodę (ramka)",
                                        mapOf("początek" to r.początek)
                                    )
                                }
                                if (!zgodaOgłoszona) {
                                    zgodaOgłoszona = true
                                    cierpliwość.usłyszano(System.currentTimeMillis())
                                    mówienie.withLock {
                                        audio.addStreamFragment(
                                            "Hermes prosi o zgodę na operację. Nie umiem jeszcze jej przekazać, " +
                                                "zatwierdź ją na komputerze. "
                                        )
                                    }
                                }
                            }
                            is pl.victor.app.hermes.HermesRamka.Nieznana -> if (logowanychNieznanych++ < 10) {
                                runCatching {
                                    diag.event(
                                        DiagFormat.Phase.MODEL, "hermes: nieznana ramka",
                                        mapOf("zdarzenie" to r.zdarzenie, "początek" to r.początek)
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
                } finally {
                    czujka.cancel()
                }
                mówienie.withLock { audio.flushStream() }
                audio.awaitStreamSpoken()
                runCatching {
                    diag.event(
                        DiagFormat.Phase.MODEL, "hermes: odpowiedź",
                        mapOf(
                            "ms" to wynik.ms,
                            "pierwszyTekstMs" to wynik.pierwszyTekstMs,
                            "znaków" to wynik.tekst.length,
                            "narzędzi" to wynik.narzędzi,
                            "zgód" to wynik.zgód,
                            "nieznanychRamek" to wynik.nieznanychRamek,
                            "http" to wynik.kodHttp,
                            "błąd" to wynik.błąd?.name,
                            "szczegół" to wynik.szczegół
                        )
                    )
                }
                val błąd = wynik.błąd
                if (błąd == null) {
                    _state.value = OrchestratorState.Completed(wynik.tekst)
                } else {
                    // Błąd po części odpowiedzi: człowiek już coś usłyszał - dopowiadamy,
                    // co poszło nie tak. Bez żadnej odpowiedzi mówimy tylko to.
                    val powód = błąd.mowa + (wynik.szczegół?.takeIf { błąd == pl.victor.app.hermes.HermesBłąd.SERWER }
                        ?.let { " $it." } ?: "")
                    audio.speakAndAwait(powód, language = "pl")
                    _state.value = if (wynik.tekst.isBlank()) OrchestratorState.Error(powód)
                    else OrchestratorState.Completed(wynik.tekst)
                }
                conversationalMode.onAiFinishedSpeaking()
            } finally {
                if (audioHeld) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        audio.endConversationRouting()
                    }
                }
                if (glassesManager.isConnected() && !audio.canSpeakOverMedia()) {
                    glassesManager.requestClassicAudioWhenIdle("po odpowiedzi Hermesa bez trybu multimediów")
                }
                wakeLock.release(lockTurn)
                releaseMic()
            }
        }
        // Tura anulowana, ZANIM jej korutyna ruszyła, nie ma `finally`.
        turnJob.invokeOnCompletion { releaseMic() }
    }

    /**
     * "Kiedy najbliższy tramwaj?", "jakie autobusy stąd jeżdżą?" - za darmo.
     *
     * Najbliższe przystanki i linie z OpenStreetMap, potem propozycja
     * otwarcia Map Google, które mają rozkład. Odjazdów na żywo darmowo nie
     * mamy i mówimy to wprost. Z włączonymi Mapami Google (płatne) i Gemini
     * pytanie idzie do modelu - [pl.victor.app.orchestrator.ModelContextBuilder.buildLocationContext] doda mu przystanki.
     *
     * @return `true`, gdy pytanie zostało obsłużone tutaj
     */
    private fun handleTransitDepartures(question: String): Boolean {
        val pytanie = pl.victor.app.navigation.OdjazdyPytanie.rozpoznaj(question) ?: return false
        val mapyGoogle = settings.getActiveProvider() == "gemini" && settings.isMapsGroundingEnabled() &&
            settings.hasApiKey("gemini")
        runCatching {
            diag.event(
                DiagFormat.Phase.KONTEKST, "komunikacja: pytanie o odjazdy",
                mapOf(
                    "pojazd" to pytanie.pojazd?.name,
                    "przystanek" to pytanie.przystanek,
                    "droga" to if (mapyGoogle) "model z Mapami Google" else "OpenStreetMap"
                )
            )
        }
        if (mapyGoogle) return false
        scope.launch {
            val od = System.currentTimeMillis()
            val tu = withTimeoutOrNull(TRANSIT_LOCATION_MS) {
                pl.victor.app.proactive.LocationContext.tutaj(context, naŚwieżąMs = TRANSIT_LOCATION_MS)
            }
            val osm = settings.isOsmPlacesEnabled()
            val lista = if (tu != null && osm) {
                withTimeoutOrNull(TRANSIT_OSM_MS) {
                    pl.victor.app.navigation.PrzystankiClient.przystanki(tu.lat, tu.lon, pytanie.przystanek)
                }
            } else {
                null
            }
            runCatching {
                diag.event(
                    DiagFormat.Phase.KONTEKST, "komunikacja: przystanki z OpenStreetMap",
                    mapOf(
                        "ms" to (System.currentTimeMillis() - od),
                        "położenie" to (tu != null),
                        "osm" to osm,
                        "przystanków" to lista?.size,
                        "najbliższy" to lista?.firstOrNull()?.nazwa
                    )
                )
            }
            val wybrany = lista?.let { pl.victor.app.navigation.Przystanki.wybierz(it, pytanie).firstOrNull() }
            val mowa = when {
                tu == null -> "Nie udało się ustalić położenia, więc nie wiem, który przystanek jest najbliżej."
                !osm -> "Przystanki z OpenStreetMap są wyłączone w ustawieniach (Miejsca z OpenStreetMap)."
                lista == null -> "Serwer map OpenStreetMap nie odpowiedział."
                else -> pl.victor.app.navigation.Przystanki.odpowiedź(lista, pytanie)
            }
            // Co otworzyć w Mapach: wybrany przystanek albo ten z pytania.
            val doMap = pl.victor.app.navigation.KomunikacjaLinki.przystanekDoMap(
                wybrany?.nazwa ?: pytanie.przystanek ?: "komunikacji miejskiej",
                tu?.miasto
            )
            // Wiszące potwierdzenie czegoś innego (np. SMS) ma pierwszeństwo -
            // nie nadpisujemy go propozycją Map (przegląd 22.11).
            if (pendingActionConfirmation.value != null) {
                audio.speak(mowa, language = settings.getResponseLanguage())
                return@launch
            }
            actionFlow.requestConfirmation(
                PendingActionConfirmation(
                    actions = listOf(Action.ShowOnMap(doMap)),
                    title = "Otworzyć rozkład w Mapach Google?",
                    message = mowa,
                    confirmText = "🗺 Otwórz Mapy",
                    cancelText = "Nie"
                ),
                "$mowa Odjazdów na żywo nie mam. Otworzyć rozkład w Mapach Google?"
            )
        }
        return true
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
        val byłWłączony = conversationalMode.isActive()
        conversationalMode.enable()
        // Ten sam sygnał, co przy pojedynczej turze - użytkownik ma jeden znak
        // "mów teraz", niezależnie od tego, którym trybem trafił do nasłuchu.
        // Tylko gdy tryb naprawdę wstaje: obrót telefonu odtwarza ekran, który
        // woła to ponownie, i grał wtedy "bip" w okularach (przegląd 22.11).
        if (!byłWłączony || afterUserAction) audio.playListeningCue()
    }

    fun disableConversationalMode() = conversationalMode.disable()

    /** Akcje telefonu i bramka potwierdzeń - patrz [pl.victor.app.orchestrator.ActionFlow]. */
    private val actionFlow = pl.victor.app.orchestrator.ActionFlow(
        settings = settings,
        scope = scope,
        audio = audio,
        conversationalMode = conversationalMode,
        accessibility = accessibility,
        actionExecutor = actionExecutor,
        directActionExecutor = directActionExecutor,
        contactResolver = contactResolver,
        emailPreparer = emailPreparer,
        state = _state,
        dziennik = { diag },
        askModelPlain = { prompt -> askModelPlain(prompt) },
        runHomeCommand = { trigger, question, tekst -> runHomeCommand(trigger, question, tekst) }
    )

    /** Akcja oczekująca na potwierdzenie (null = nic nie czeka). */
    val pendingActionConfirmation: StateFlow<PendingActionConfirmation?>
        get() = actionFlow.pendingActionConfirmation

    /** Potwierdzenie akcji przez usera (z dialogu albo głosem). */
    fun confirmAction() = actionFlow.confirmAction()

    /** Anulowanie akcji przez usera. */
    fun cancelAction() = actionFlow.cancelAction()

    private val buttonDetector = ButtonActionDetector()


    /** Korutyna bieżącej tury - do przerwania przez [cancelCurrentTurn]. */
    private var activeTurnJob: kotlinx.coroutines.Job? = null

    /**
     * Znacznik bieżącej tury - para do [activeTurnJob], ustawiana w [launchTurn].
     *
     * ## Po co
     * Stan `Idle` NIE znaczy, że nic nie trwa. Tura głosowa stoi w `Idle` dwa
     * razy: zanim zestawi łącze audio (kilka sekund) i między końcem nasłuchu a
     * przekazaniem pytania dalej (transkrypcja). W obu oknach drugi trigger -
     * powtórzona fraza, ramka "zdjęcie gotowe" - przechodził przez [claimIdle]
     * i ruszał RÓWNOLEGŁĄ turę: dwa nasłuchy na jednym mikrofonie, dwie
     * odpowiedzi naraz (audyt 28.10).
     *
     * Samo "korutyna żyje" nie wystarcza do odmowy, bo tura przekazuje pytanie
     * sama sobie (nasłuch -> model, powtórka ze zdjęciem) - i robi to właśnie w
     * stanie `Idle`. [turnMarker] odróżnia jedno od drugiego: kod wykonywany W
     * korutynie tury widzi jej znacznik, obcy trigger - nie.
     */
    @Volatile
    private var activeTurnToken: Any? = null

    /** Znacznik tury widoczny z wnętrza jej korutyny - patrz [activeTurnToken]. */
    private val turnMarker = ThreadLocal<Any?>()

    /** Kiedy ruszyła bieżąca tura - karencja przejęcia w stanie `Idle`. */
    @Volatile
    private var turnLaunchedAtMs = 0L

    /** Numer tury - do nazw blokad uśpienia, patrz [launchTurn]. */
    private val turnSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** Numer ostatniego nasłuchu zaczętego z okularów - patrz [startVoiceTurn]. */
    private val glassesListenSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Ile tur trzyma teraz mikrofon frazy - patrz [holdMicForTurn].
     */
    private val turnMicHolds = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Uruchamia korutynę tury i zapisuje ją jako bieżącą.
     *
     * JEDYNE miejsce, które przypisuje [activeTurnJob] nową turą: znacznik i
     * korutyna muszą powstawać razem, inaczej [claimIdle] nie odróżni
     * przekazania pytania wewnątrz tury od obcego triggera.
     */
    private fun launchTurn(
        block: suspend CoroutineScope.() -> Unit
    ): kotlinx.coroutines.Job {
        val token = Any()
        activeTurnToken = token
        turnLaunchedAtMs = System.currentTimeMillis()
        val job = scope.launch(turnMarker.asContextElement(token), block = block)
        activeTurnJob = job
        // Tura, która skończyła się jako bieżąca, zwalnia znacznik - inaczej
        // sprzątanie następnej brałoby ją za "przejętą".
        job.invokeOnCompletion { if (activeTurnToken === token) activeTurnToken = null }
        return job
    }

    /** Czy to wywołanie pochodzi z wnętrza bieżącej tury (przekazanie pytania). */
    private fun insideActiveTurn(): Boolean {
        val token = activeTurnToken
        return token != null && turnMarker.get() === token
    }

    /**
     * Tura bierze mikrofon frazy - z licznikiem właścicieli.
     *
     * Bez licznika stara tura (przerwana albo przekazująca pytanie dalej)
     * wznawiała w swoim `finally` nasłuch frazy W TRAKCIE nowej tury: nasłuch
     * zabierał mikrofon rozpoznawaniu mowy albo słuchał własnej odpowiedzi
     * asystenta. Każde wywołanie musi mieć dokładnie jedno [releaseMicAfterTurn].
     */
    private fun holdMicForTurn() {
        turnMicHolds.incrementAndGet()
        pauseWakeWordMic()
    }

    private fun releaseMicAfterTurn() {
        val left = turnMicHolds.updateAndGet { if (it > 0) it - 1 else 0 }
        if (left > 0) return
        resumeWakeWordMic()
    }

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
     * Raz otwarty temat zostaje więc otwarty - ale na kilka minut od ostatniej
     * wzmianki, nie do końca życia procesu. Powód i skutek poprzedniej wersji:
     * [pl.victor.app.conversation.ContextTopics]. Czyści go też "nowy temat".
     */
    /**
     * Zbiór współbieżny, nie zwykły [mutableSetOf].
     *
     * Odkąd konteksty (kalendarz, poczta, pogoda, notatki) zbierane są
     * RÓWNOLEGLE, dopisują się do niego z kilku korutyn naraz - a zwykły
     * HashSet potrafi się przy tym trwale uszkodzić.
     */
    private val openContextTopics = pl.victor.app.conversation.ContextTopics()

    /** Konteksty dla modelu - patrz [pl.victor.app.orchestrator.ModelContextBuilder]. */
    private val modelContext = pl.victor.app.orchestrator.ModelContextBuilder(
        context = context,
        settings = settings,
        history = history,
        dziennik = { diag },
        openContextTopics = openContextTopics,
        contactResolver = contactResolver,
        longTermMemory = longTermMemory,
        semanticMemory = semanticMemory,
        translator = translator
    )


    init {
        // Jedno miejsce, w którym mierzymy wiek stanu - patrz [stateChangedAtMs].
        scope.launch { _state.collect { stateChangedAtMs = System.currentTimeMillis() } }

        // Preferencja mikrofonu okularów musi trafić do routera PRZED pierwszą
        // turą - inaczej wyłączenie działałoby dopiero po restarcie aplikacji.
        audio.setGlassesMicEnabled(settings.isGlassesMicEnabled())

        // Pamięć po znaczeniu: dociągnięcie brakujących wektorów (w tle, z
        // dziennym limitem) i nasłuch zmian notatek i faktów.
        semanticMemory.start()

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
            // Bez `?.let` i bez konsumowania: SharedFlow oddaje każde wciśnięcie
            // osobno. Wcześniej stał tu StateFlow, w którym kolejne TAKIE SAMO
            // kliknięcie przepadało, jeśli kolektor nie zdążył wyzerować
            // poprzedniego - i to zabijało szybkie serie, czyli podwójne i
            // potrójne kliknięcie.
            glassesManager.buttonEvent.collect { event ->
                bezpiecznie("wciśnięcie przycisku") { buttonDetector.processEvent(event) }
            }
        }

        // Nasłuchuj zdetektowane akcje
        scope.launch {
            buttonDetector.action.collect { action ->
                bezpiecznie("akcja przycisku $action") { handleButtonAction(action) }
            }
        }

        // === Wybudzenie po stronie okularów ===
        // Okulary same wykrywają swoje słowo kluczowe (włączane przez
        // VictorManager.setGlassesWakeWord). Wcześniej detekcja była włączona,
        // ale zdarzenie nie miało odbiorcy - czyli "wake word nie działał".
        scope.launch {
            glassesManager.aiSessionRequest.collect { realtimeText ->
                bezpiecznie("rozmowa z okularów") { startGlassesConversation(realtimeText) }
            }
        }

        // OKULARY MAJĄ WIEDZIEĆ, ŻE MÓWIMY - tak jak u producenta.
        //
        // Aplikacja producenta przy każdej wypowiedzi wysyła "zaczynam" (1),
        // puls co 2 s (2) i "koniec" (3). U nas nie szło nic, więc okulary nie
        // miały skąd wiedzieć, że gra asystent - a to od nich zależy, czy
        // dotknięcie zausznika przyjdzie jako "ucisz" (0x0C). Chwilowy spadek
        // między zdaniami strumienia nie może dawać "koniec, zaczynam" co
        // zdanie, stąd odczekanie przed kodem 3.
        scope.launch {
            var okularyWiedzą = false
            audio.speaking.collectLatest { mówi ->
                bezpiecznie("sygnał mówienia dla okularów") {
                    if (mówi) {
                        if (!glassesManager.isConnected()) return@bezpiecznie
                        if (!okularyWiedzą) {
                            glassesManager.playGlassesTone(GlassesProtocol.TONE_PLAYBACK_STARTED)
                            okularyWiedzą = true
                        }
                        while (true) {
                            delay(PLAYBACK_PULSE_MS)
                            if (!glassesManager.isConnected()) break
                            glassesManager.playGlassesTone(GlassesProtocol.TONE_PLAYBACK_ALIVE)
                        }
                    } else if (okularyWiedzą) {
                        delay(PLAYBACK_END_GRACE_MS)
                        okularyWiedzą = false
                        if (glassesManager.isConnected()) {
                            glassesManager.playGlassesTone(GlassesProtocol.TONE_STOP_PLAYBACK)
                        }
                    }
                }
            }
        }

        // Okulary zgłosiły, że kończy im się pamięć. Dotąd szło to tylko do
        // dziennika - a człowiek dowiadywał się dopiero z nieudanego zdjęcia.
        scope.launch {
            glassesManager.lowMemory.collect {
                bezpiecznie("mało pamięci w okularach") {
                    val teraz = System.currentTimeMillis()
                    if (teraz - lowMemoryAnnouncedAtMs < LOW_MEMORY_ANNOUNCE_EVERY_MS) return@bezpiecznie
                    // Nie wchodzimy w słowo; okulary zgłoszą to ponownie przy
                    // następnym zdjęciu, więc pominięty komunikat nie ginie.
                    if (!ciszaWTle()) return@bezpiecznie
                    lowMemoryAnnouncedAtMs = teraz
                    audio.speak(
                        "Pamięć okularów jest prawie pełna. Zapisz zdjęcia w telefonie " +
                            "i wyczyść pamięć okularów w galerii aplikacji.",
                        language = settings.getResponseLanguage()
                    )
                }
            }
        }

        // Słaba bateria okularów - raz przy 20% i raz przy 10%. Dotąd okulary
        // po prostu gasły, a z zewnątrz wyglądało to jak kolejne "rozłączyły się".
        scope.launch {
            glassesManager.batteryLevel.collect { poziom ->
                bezpiecznie("słaba bateria okularów") {
                    val próg = lowBattery.check(poziom, glassesManager.isCharging.value)
                        ?: return@bezpiecznie
                    // Nie wchodzimy w słowo - komunikat wróci przy następnym odczycie.
                    if (!ciszaWTle()) return@bezpiecznie
                    lowBattery.announced(próg)
                    diag.event(DiagFormat.Phase.MOWA, "słaba bateria okularów", mapOf("procent" to poziom))
                    audio.speak(
                        "Okulary mają $poziom procent baterii." +
                            if (próg <= 10) " Naładuj je wkrótce." else "",
                        language = settings.getResponseLanguage()
                    )
                }
            }
        }

        // Dotknięcie zauszników w trakcie mówienia = "cicho".
        scope.launch {
            glassesManager.speechInterrupted.collect {
                bezpiecznie("przerwanie wypowiedzi") {
                    // "Ucisz" dotyczy MÓWIENIA - jak u producenta, który na
                    // tę ramkę zatrzymuje wyłącznie syntezator. Kiedy nic nie
                    // mówimy, nie ma czego uciszać, a przerwanie całej tury
                    // zabijało nasłuch, zdjęcie w trakcie robienia i tryb
                    // tłumaczenia. W tłumaczeniu producent tę ramkę pomija
                    // całkowicie - wychodzi się z niego przyciskiem.
                    // W ROZMOWIE W DWIE STRONY dotknięcie zausznika = "moja kolej".
                    // Bieg 185: w całej rozmowie nie przyszła ANI JEDNA ramka
                    // przycisku, za to przychodziły ramki dotyku - okulary
                    // podłączone tylko w trybie rozmowy zgłaszają naciśnięcie
                    // właśnie tak, a my je tu pomijaliśmy. Odsiewamy tylko echo
                    // naszej własnej komendy "zaczynam/kończę mówić".
                    // ROZMOWA NA ŻYWO: dotknięcie skroni ją kończy. Odsiewamy
                    // echo naszej komendy "zaczynam/kończę mówić" (zapowiedź).
                    if (liveConversationOn()) {
                        val odKomendy = glassesManager.msSincePlaybackEdge()
                        val echo = odKomendy < PLAYBACK_ECHO_WINDOW_MS
                        runCatching {
                            diag.event(
                                DiagFormat.Phase.WAKE, "rozmowa na żywo: dotknięcie skroni",
                                mapOf("echo" to echo, "msOdKomendy" to odKomendy)
                            )
                        }
                        if (!echo) stopLiveConversation("dotknięcie skroni")
                        return@bezpiecznie
                    }
                    if (earTranslation.value && earTwoWay.value) {
                        val odKomendy = glassesManager.msSincePlaybackEdge()
                        val echo = odKomendy < PLAYBACK_ECHO_WINDOW_MS
                        runCatching {
                            diag.event(
                                DiagFormat.Phase.WAKE, "rozmowa: dotknięcie zausznika",
                                mapOf("mojaKolej" to (!echo && !ear.mojaKolej), "echo" to echo, "msOdKomendy" to odKomendy)
                            )
                        }
                        if (!echo && !ear.mojaKolej) myTurn(zOkularów = false)
                        return@bezpiecznie
                    }
                    val mówimy = audio.speaking.value
                    if (earTranslation.value || !mówimy) {
                        Log.i(TAG, "Okulary: 'ucisz' pominięte (tłumaczenie=${earTranslation.value}, mówię=$mówimy)")
                        runCatching {
                            diag.event(
                                DiagFormat.Phase.WAKE, "ucisz z okularów pominięte",
                                mapOf("tłumaczenie" to earTranslation.value, "mówię" to mówimy)
                            )
                        }
                        return@bezpiecznie
                    }
                    // ECHO NASZEJ WŁASNEJ KOMENDY, NIE DOTKNIĘCIE.
                    //
                    // Dziennik z biegu 152: tę samą ramkę okulary odsyłają
                    // circa pół sekundy po "zaczynam/kończę mówić", które sami
                    // im wysyłamy. Po "kończę" nikt nie mówi, więc warunek
                    // wyżej ją odsiewa - ale echo "zaczynam" trafia akurat w
                    // mówienie. Tak zginęła tura "ile to jest 7 + 7" o
                    // 17:44:31: PRZERWANO 0,36 s po zestawieniu dźwięku, zanim
                    // padło jedno słowo odpowiedzi. Dotknąć w pierwszej
                    // półtorej sekundy nikt nie zdąży, więc to okno nic nie
                    // odbiera człowiekowi.
                    val odKomendy = glassesManager.msSincePlaybackEdge()
                    if (odKomendy < PLAYBACK_ECHO_WINDOW_MS) {
                        runCatching {
                            diag.event(
                                DiagFormat.Phase.WAKE, "ucisz z okularów pominięte - echo komendy odtwarzania",
                                mapOf("msOdKomendy" to odKomendy)
                            )
                        }
                        return@bezpiecznie
                    }
                    // MÓWI POPRZEDNIA TURA, A NOWA JUŻ RUSZYŁA.
                    //
                    // Bieg 174, 16:44:19: asystent czytał jeszcze poprzednią
                    // odpowiedź, użytkownik nacisnął aparat (nowa tura: zdjęcie)
                    // i dotknął zausznika, żeby uciszyć tamto. Dotknięcie
                    // ubiło NOWĄ turę 0,24 s po starcie - zdjęcie przepadło.
                    // Gdy bieżąca tura jeszcze nic nie mówi (nasłuch, zdjęcie,
                    // czekanie na model), mowa należy do poprzedniej: uciszamy
                    // tylko ją.
                    val stan = _state.value
                    if (stan is OrchestratorState.Listening ||
                        stan is OrchestratorState.Capturing ||
                        stan is OrchestratorState.Thinking
                    ) {
                        runCatching {
                            diag.event(
                                DiagFormat.Phase.WAKE, "ucisz z okularów - uciszam mowę, nowa tura trwa dalej",
                                mapOf("stan" to stan::class.simpleName)
                            )
                        }
                        audio.stopSpeaking()
                        return@bezpiecznie
                    }
                    Log.i(TAG, "Okulary: użytkownik przerwał wypowiedź")
                    cancelCurrentTurn("dotknięcie zauszników")
                }
            }
        }

        // To samo "cicho" KLAWISZEM MULTIMEDIÓW - tędy przychodzi dotknięcie
        // zausznika, gdy mowa idzie profilem rozmowy. Patrz [SpeechMediaButtons].
        scope.launch {
            audio.speaking.collect { mówi -> mediaButtons.setSpeaking(mówi) }
        }

        // Wiadomości z powiadomień - patrz pl.victor.app.messages.
        messages.start()
        // Skręty z nawigacji Google Maps - patrz pl.victor.app.navigation.
        startNavPrompts()
        // Po każdej wypowiedzi asystenta chwila na dopytanie, zanim tło
        // (przewodnik, wiadomości) zabierze głos - patrz [ciszaWTle].
        scope.launch {
            audio.speaking.collect { mówi ->
                if (!mówi) {
                    val karencja = System.currentTimeMillis() - CISZA_PO_AKTYWNOŚCI_MS + CISZA_PO_MOWIE_MS
                    if (karencja > ostatniaAktywnośćMs) ostatniaAktywnośćMs = karencja
                }
            }
        }

        // Przypomnienia w miejscach przeżywają restart aplikacji - pilnowanie
        // wraca samo, jeśli jest czego pilnować.
        scope.launch { placeReminders.pilnuj() }
        // Przypomnienia w miejscach potrzebują usługi w tle (lokalizacja), dopóki
        // jakieś czekają.
        scope.launch {
            placeReminders.lista.collect {
                pl.victor.app.features.BackgroundFeatures.ustaw(
                    pl.victor.app.features.BackgroundFeatures.PRZYPOMNIENIA, it.isNotEmpty()
                )
            }
        }
        audio.noiseAdaptiveVolume = settings.isNoiseAdaptiveVolume()

        // === ROZŁĄCZENIE MA BYĆ SŁYSZALNE ===
        //
        // VictorManager radzi sobie z zerwanym łączem sam: wznawia je w
        // nieskończoność, gęsto przez pierwszą minutę, potem coraz rzadziej.
        // Czego nie robił NIKT, to powiedzenie o tym człowiekowi - `connectionState`
        // był tu tylko ODCZYTYWANY w kilku miejscach, nigdy zbierany.
        //
        // Zgłoszone trzy razy, zawsze tak samo: "okulary przestały reagować na
        // hej lens i na kliknięcie (...) okazało się, że w ustawieniach Bluetooth
        // się rozłączyły". Objaw brzmi "aplikacja jest zepsuta", a dowiedzieć się
        // prawdy można wyłącznie wchodząc w ustawienia systemowe. Człowiek w
        // okularach na głowie naciska martwy przycisk i nie ma żadnego powodu,
        // żeby podejrzewać Bluetooth.
        //
        // Komunikat idzie GŁOŚNIKIEM TELEFONU i nie da się tego zmienić: okularów
        // właśnie nie ma, więc A2DP nie istnieje. To jedyna droga, która w tej
        // chwili działa.
        scope.launch {
            glassesManager.connectionState.collect { stan ->
                bezpiecznie("zmiana stanu połączenia") {
                    if (stan == ConnectionState.READY) {
                        utrataJob?.cancel()
                        utrataJob = null
                        if (announcer.czyOgłosićPowrót()) {
                            diag.event(DiagFormat.Phase.BLE, "okulary wróciły - mówię o tym")
                            audio.speak(
                                "Okulary znów połączone.",
                                language = settings.getResponseLanguage()
                            )
                        }
                        speakMissedAlertsSoon()
                    } else if (utrataJob == null) {
                        // KARENCJA, a nie natychmiast. Łącze potrafi mrugnąć na
                        // sekundę i wrócić samo - a komunikat o czymś, co już się
                        // naprawiło, jest gorszy niż cisza, bo uczy ignorowania
                        // komunikatów.
                        utrataJob = scope.launch {
                            delay(UTRATA_KARENCJA_MS)
                            if (glassesManager.connectionState.value != ConnectionState.READY &&
                                announcer.czyOgłosićUtratę()
                            ) {
                                diag.event(
                                    DiagFormat.Phase.BLE,
                                    "okulary rozłączone dłużej niż karencja - mówię o tym",
                                    mapOf("karencjaMs" to UTRATA_KARENCJA_MS)
                                )
                                audio.speak(
                                    "Okulary się rozłączyły. Próbuję połączyć ponownie.",
                                    language = settings.getResponseLanguage()
                                )
                            }
                            utrataJob = null
                        }
                    }
                }
            }
        }

        // Drugi przycisk okularów: zdjęcie zrobione ręcznie, bez udziału
        // aplikacji. Producent w tym miejscu pobiera miniaturę i wrzuca ją do
        // rozmowy - u nas dotąd nie działo się nic, bo czekaliśmy wyłącznie na
        // zdjęcia, o które sami poprosiliśmy.
        scope.launch {
            glassesManager.glassesPhotoTaken.collect { aiVision ->
                bezpiecznie("zdjęcie z przycisku okularów") { handleGlassesPhoto(aiVision) }
            }
        }
    }

    /**
     * Wykonuje obsługę zdarzenia tak, żeby jej błąd nie zabił STRUMIENIA.
     *
     * ## Czemu to jest konieczne, a nie ostrożnościowe
     * Kolektory zdarzeń żyją w `scope.launch { ... collect { ... } }`. Scope ma
     * [kotlinx.coroutines.SupervisorJob] i własny CoroutineExceptionHandler, co
     * znaczy, że wyjątek w obsłudze JEDNEGO zdarzenia nie wywraca aplikacji -
     * ale kończy tę korutynę na dobre. Strumień przestaje być zbierany i żadne
     * kolejne zdarzenie już nie dochodzi, aż do restartu aplikacji.
     *
     * Przy przycisku wygląda to dokładnie tak, jak zgłoszono: okulary grają
     * swój dźwięk (robią to same, lokalnie), a aplikacja nie robi NIC - przy
     * jednym kliknięciu, przy dwóch i przy trzech tak samo. Jedna usterka
     * sprzed kilkunastu minut kasuje wszystkie gesty naraz, po cichu.
     *
     * Teraz błąd kosztuje JEDNO zdarzenie i zostaje zapisany w dzienniku -
     * wcześniej szedł tylko do logcata, którego przy okularach na głowie nikt
     * nie czyta.
     *
     * CancellationException przepuszczamy dalej: anulowanie nie jest awarią i
     * połknięcie go łamie zamykanie korutyn.
     */
    private suspend fun bezpiecznie(co: String, blok: suspend () -> Unit) {
        try {
            blok()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Błąd w obsłudze: $co", e)
            runCatching {
                diag.event(
                    DiagFormat.Phase.BŁĄD,
                    "błąd w obsłudze zdarzenia - strumień zostaje czynny",
                    mapOf("co" to co, "błąd" to (e.message ?: e::class.simpleName))
                )
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
        // Tryb bez kamery: okulary zdjęcie i tak zrobiły (tego telefon nie
        // wyłączy), ale aplikacja go nie pobiera ani nie opisuje.
        if (settings.isCameraFree()) {
            runCatching {
                diag.event(
                    DiagFormat.Phase.ZDJĘCIE, "zdjęcie z przycisku pominięte - tryb bez kamery",
                    mapOf("opisz" to aiVision)
                )
            }
            return
        }
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
            // BLOKADA UŚPIENIA NA CZAS POBIERANIA. Bieg 174: "pobieram" o
            // 16:42:28, miniatura o 16:44:18 - sto dziesięć sekund, bo przy
            // zgaszonym ekranie procesor zasnął w środku czekania i obudził się
            // dopiero, gdy użytkownik sięgnął po telefon. Usługa pierwszoplanowa
            // trzyma proces, ale nie procesor.
            val lockPhoto = "$LOCK_HW_PHOTO#${turnSeq.incrementAndGet()}"
            wakeLock.acquire(lockPhoto, HW_PHOTO_WAKE_LOCK_MS)
            try {
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
                    // Prośba o opis (tryb 2) to palec na przycisku aparatu,
                    // a nie echo naszej migawki - te odsiewa obsługa ramki,
                    // zanim tu dotrą. Bieg 174, 16:45:07: zdjęcie z prośbą o
                    // opis odrzucone ("tura już trwa"), bo asystent mówił
                    // akurat "nie dosłyszałem" z poprzedniej tury. Zwykłe
                    // zdjęcie (bez prośby) dalej nie przerywa mowy.
                    allowInterruptSpeech = aiVision
                )
            }
            } finally {
                wakeLock.release(lockPhoto)
            }
        }
    }

    /**
     * Która droga przepisała ostatnie pytanie na tekst.
     *
     * ## Po co to w ogóle jest
     * Bo dróg jest teraz cztery - chmura, rozpoznawanie systemowe na urządzeniu,
     * nasłuch telefonu i nagranie wprost do modelu - a z zewnątrz wszystkie
     * wyglądają tak samo: asystent po prostu odpowiada. Gdy odpowiada źle, nie da
     * się zgadnąć, którą poszedł, a od tego zależy CAŁA diagnoza. Do tej pory i ja,
     * i użytkownik zgadywaliśmy.
     */
    private val _lastTranscriptionSource = MutableStateFlow<String?>(null)
    val lastTranscriptionSource: StateFlow<String?> = _lastTranscriptionSource.asStateFlow()

    /** Przepisywanie nagrań - patrz [pl.victor.app.orchestrator.RecordingTranscriber]. */
    private val transcriber = pl.victor.app.orchestrator.RecordingTranscriber(
        context = context,
        settings = settings,
        speechToText = speechToText,
        dziennik = { diag },
        getOrCreateProvider = { getOrCreateProvider() },
        lastTranscriptionSource = _lastTranscriptionSource
    )

    /**
     * Czy da się przepisać nagranie JAKĄKOLWIEK drogą - patrz
     * [pl.victor.app.orchestrator.RecordingTranscriber.canTranscribeRecordings].
     */
    fun canTranscribeRecordings(): Boolean = transcriber.canTranscribeRecordings()

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
     * Aplikacja jako właściciel nasłuchu frazy - patrz [pauseWakeWordMic].
     * Nullable, bo w testach i podglądach Compose kontekst bywa inny.
     */
    private val victorApp: pl.victor.app.VictorApplication?
        get() = context.applicationContext as? pl.victor.app.VictorApplication

    /**
     * Oddaje mikrofon zajęty przez wykrywanie frazy.
     *
     * Nasłuch "Hej Wiktor" trzyma własny AudioRecord i nie wie o niczym, więc
     * trzeba mu powiedzieć wprost. Bez tego rozpoznawanie mowy dostaje zajęty
     * mikrofon i tura kończy się komunikatem "nie mogę rozpoznać nagrania".
     */
    private fun pauseWakeWordMic() {
        runCatching { victorApp?.pauseWakeForTurn() }
        // Spotkanie oddaje mikrofon na czas tury - inaczej pytanie (w tym
        // "zakończ spotkanie") dostaje ciszę. Bieg 160: po "nagrywaj
        // spotkanie" każda tura kończyła się "MIKROFON NIE RUSZYŁ".
        if (meetingHoldsMic()) meeting.wstrzymaj()
    }

    private fun resumeWakeWordMic() {
        // Tryb, który SAM trzyma mikrofon (spotkanie, tłumaczenie ze słuchu),
        // nie może go stracić na rzecz nasłuchu frazy tylko dlatego, że
        // skończyła się tura, która ten tryb włączyła. Tryb wznawia nasłuch
        // sam, gdy się kończy.
        if (meetingCreated && !earTranslation.value) meeting.wznów()
        if (meetingHoldsMic() || earTranslation.value) return
        runCatching { victorApp?.resumeWakeAfterTurn() }
    }

    private fun meetingHoldsMic(): Boolean = meetingCreated && meeting.nagrywa

    /** Czy rejestrator spotkań już powstał - żeby samo pytanie go nie tworzyło. */
    @Volatile
    private var meetingCreated = false

    private fun claimIdle(
        takeOver: Boolean = false,
        mayInterruptSpeech: Boolean = false
    ): Boolean {
        return when (_state.value) {
            is OrchestratorState.Idle -> {
                // `Idle` z żywą korutyną tury to tura w przygotowaniu albo w
                // przekazaniu pytania - patrz [activeTurnToken].
                val job = activeTurnJob
                val ageMs = System.currentTimeMillis() -
                    maxOf(stateChangedAtMs, turnLaunchedAtMs)
                when (
                    pl.victor.app.conversation.TurnClaim.whenIdle(
                        turnAlive = job?.isActive == true && activeTurnToken != null,
                        insideTurn = insideActiveTurn(),
                        takeOver = takeOver,
                        ageMs = ageMs,
                        graceMs = TAKEOVER_GRACE_MS,
                        stuckMs = STUCK_TURN_MS
                    )
                ) {
                    pl.victor.app.conversation.TurnClaim.Verdict.ENTER -> return true
                    pl.victor.app.conversation.TurnClaim.Verdict.REFUSE -> return false
                    pl.victor.app.conversation.TurnClaim.Verdict.TAKE_OVER -> Unit
                }
                Log.i(TAG, "Nowe wywołanie po $ageMs ms - przerywam turę w przygotowaniu")
                runCatching {
                    diag.event(
                        DiagFormat.Phase.SESJA, "PRZERWANO turę - weszło nowe wywołanie",
                        mapOf("stan" to "Idle (tura w przygotowaniu)", "poMs" to ageMs)
                    )
                }
                lastCancelReason = "nowe wywołanie użytkownika"
                job?.cancel()
                activeTurnJob = null
                activeTurnToken = null
                // Przejęta tura nie zamknie już dziennika sama (jej `finally`
                // widzi cudzy znacznik) - a nowa ma liczyć czas od zera.
                runCatching { diag.endTurn("PRZERWANA: nowe wywołanie użytkownika") }
                true
            }
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
                    activeTurnToken = null
                    if (!jobFinished) {
                        // Patrz gałąź Idle: przerwana tura nie zamknie dziennika sama.
                        runCatching {
                            diag.endTurn(
                                if (supersede) "PRZERWANA: nowe wywołanie użytkownika"
                                else "PRZERWANA: tura utknęła"
                            )
                        }
                    }
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
        // "Przerwij" w aplikacji ma przerywać WSZYSTKO, co mówi i słucha - także
        // tryb tłumaczenia, który nie jest turą i do tej pory przez to przycisk
        // go omijał.
        stopEarTranslation(reason)
        stopLiveConversation(reason)
        activeTurnJob?.cancel()
        activeTurnJob = null
        activeTurnToken = null
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
            // Okulary proszą o TRYB TEKSTU NA ŻYWO, nie o pytanie do AI.
            //
            // Stało tu "obsługuję jak zwykłe pytanie" - zaślepka z czasów, gdy
            // tego trybu u nas nie było. U producenta ta sama ramka otwiera
            // tłumaczenie ze słuchu, więc od teraz otwiera je i u nas.
            Log.i(TAG, "Okulary proszą o tekst na żywo - włączam tłumaczenie ze słuchu")
            toggleEarTranslation()
            return
        }
        startVoiceTurn(fromGlasses = true)
    }

    /** Czy można teraz mówić bez wchodzenia w słowo - dla trybów działających w tle. */
    private fun ciszaWTle(): Boolean {
        val st = _state.value
        val bezTury = st is OrchestratorState.Idle || st is OrchestratorState.Completed ||
            st is OrchestratorState.Error
        // Sam stan nie wystarcza: między nasłuchem a pytaniem do modelu tura
        // bywa w stanie Idle. Bieg 160, 21:38:10 - przewodnik wszedł w słowo
        // w trakcie przepisywania pytania. Stąd karencja od ostatniego gestu.
        val odGestu = System.currentTimeMillis() - ostatniaAktywnośćMs
        return bezTury && !audio.speaking.value && !earTranslation.value &&
            !liveConversationOn() && odGestu > CISZA_PO_AKTYWNOŚCI_MS
    }

    /**
     * Mówi w okularach o skrętach, gdy prowadzą Mapy Google.
     *
     * Skręt nie może czekać jak wiadomość: dajemy chwilę ([NAV_WAIT_MS]) na
     * koniec bieżącej wypowiedzi, a gdy trwa rozmowa - pomijamy, z wpisem w
     * dzienniku (Mapy i tak mówią same, jeśli ich głos jest włączony).
     * Bez okularów milczymy: głośnik telefonu dublowałby głos Map.
     */
    private fun startNavPrompts() {
        val zapowiedzi = pl.victor.app.navigation.NavZapowiedzi()
        scope.launch {
            pl.victor.app.navigation.MapsNavigation.stan.collect { stan ->
                if (stan == null) {
                    zapowiedzi.wyzeruj()
                    return@collect
                }
                val zdanie = zapowiedzi.decyzja(stan) ?: return@collect
                if (!settings.isNavPromptsEnabled() || !glassesManager.isConnected()) return@collect
                val doKiedy = System.currentTimeMillis() + NAV_WAIT_MS
                while (!ciszaWTle() && System.currentTimeMillis() < doKiedy) delay(EAR_TURN_POLL_MS)
                val mówię = ciszaWTle()
                runCatching {
                    diag.event(
                        DiagFormat.Phase.KONTEKST, "nawigacja: zapowiedź",
                        mapOf("zdanie" to zdanie, "m" to stan.dystansM, "powiedziane" to mówię)
                    )
                }
                if (mówię) audio.speak(zdanie, language = settings.getResponseLanguage())
            }
        }
    }

    /** Ostatni przycisk albo nasłuch - patrz [ciszaWTle]. */
    @Volatile
    private var ostatniaAktywnośćMs = 0L

    /** Kiedy ostatnio powiedzieliśmy o pełnej pamięci okularów. */
    @Volatile
    private var lowMemoryAnnouncedAtMs = 0L

    /** Notatki ze spotkania - patrz [pl.victor.app.features.meeting.MeetingRecorder]. */
    val meeting by lazy {
        meetingCreated = true
        pl.victor.app.features.meeting.MeetingRecorder(
            context = context,
            scope = scope,
            // Ten sam łańcuszek co pytania z okularów: chmura -> urządzenie.
            // Samo rozpoznawanie na urządzeniu wymaga Androida 13+ i pakietu języka,
            // a bez niego spotkanie "nagrywało" bez jednego słowa tekstu.
            przepisz = { pcm ->
                transcriber.transcribeSpeechPcm(pcm, languageTagFor(settings.getResponseLanguage()))
            },
            podsumuj = { polecenie -> askModelPlain(polecenie) },
            dziennik = { co, pola -> runCatching { diag.event(DiagFormat.Phase.NASŁUCH, co, pola) } },
            // "Zakończ spotkanie" powiedziane do stołu też kończy - nie trzeba
            // na to tury asystenta.
            polecenie = { tekst ->
                if (pl.victor.app.conversation.MetaCommands.meetingCommand(tekst) == false) stopMeeting()
            },
            wolnoWznowić = { !earTranslation.value },
            // Mówcy: płatne (przełącznik w Ustawieniach, "tylko darmowe") i tylko
            // przez Gemini - diaryzację ma `gemini-3.5-transcribe`, nie aktywny dostawca.
            bezMówców = {
                pl.victor.app.features.meeting.SpeakerTranscript.powódBezMówców(
                    tylkoDarmowe = settings.isFreeOnly(),
                    włączone = settings.isMeetingSpeakersEnabled(),
                    kluczGemini = settings.getApiKey("gemini")
                )
            },
            rozpoznajMówców = { plik ->
                settings.getApiKey("gemini")?.takeIf { it.isNotBlank() }?.let { klucz ->
                    pl.victor.app.features.meeting.MeetingDiarizer(klucz)
                        .rozpoznaj(plik, languageTagFor(settings.getResponseLanguage()))
                }
            }
        )
    }

    /**
     * Zaczyna nagrywanie spotkania - z przycisku w aplikacji albo głosem.
     *
     * @return `false`, gdy mikrofon jest niedostępny
     */
    fun startMeeting(): Boolean {
        if (meeting.nagrywa) return true
        // Rozmowa na żywo i tłumaczenie trzymają mikrofon - drugi nagrywający
        // dostawał ciszę, a "Nagrywam spotkanie" szło do Gemini (przegląd 22.11).
        // Rozmowa na żywo odmawia startu przy spotkaniu; tu odwrotnie.
        if (liveMode.aktywna.value || earTranslation.value) {
            runCatching {
                diag.event(
                    DiagFormat.Phase.AUDIO, "spotkanie: odmowa - mikrofon zajęty",
                    mapOf("rozmowaNaŻywo" to liveMode.aktywna.value, "tłumaczenie" to earTranslation.value)
                )
            }
            audio.speak(
                "Mikrofon jest zajęty przez " +
                    (if (earTranslation.value) "tłumaczenie" else "rozmowę na żywo") +
                    ". Zakończ je, zanim zacznę nagrywać spotkanie.",
                language = settings.getResponseLanguage()
            )
            return false
        }
        if (!canTranscribeRecordings()) {
            audio.speak(
                "Nie mogę nagrywać spotkania: ten telefon nie ma czym przepisać mowy. Włącz " +
                    "transkrypcję w chmurze w ustawieniach albo pobierz pakiet języka rozpoznawania mowy.",
                language = settings.getResponseLanguage()
            )
            return false
        }
        // Nasłuch frazy na telefonie trzyma mikrofon - na czas spotkania oddaje go.
        pauseWakeWordMic()
        val ok = meeting.start()
        // Usługa w tle z mikrofonem - inaczej po zgaszeniu ekranu nagrywa ciszę.
        pl.victor.app.features.BackgroundFeatures.ustaw(pl.victor.app.features.BackgroundFeatures.SPOTKANIE, ok)
        if (!ok) {
            resumeWakeWordMic()
            audio.speak(
                "Nie mogę nagrywać - mikrofon telefonu jest zajęty albo brakuje zgody na mikrofon.",
                language = settings.getResponseLanguage()
            )
            return false
        }
        audio.speak(
            "Nagrywam spotkanie. Gdy skończycie, powiedz: zakończ spotkanie, albo kliknij cztery razy.",
            language = settings.getResponseLanguage()
        )
        return true
    }

    /** Kończy nagrywanie, robi podsumowanie, zapisuje je w notatkach i mówi, co wyszło. */
    fun stopMeeting() {
        // Atomowo: "zakończ spotkanie" ze stołu i z tury potrafiło ruszyć
        // dwa razy i dwa razy ogłosić koniec.
        if (!meeting.nagrywa || !meetingStopping.compareAndSet(false, true)) return
        scope.launch {
            try {
                stopMeetingNow()
            } finally {
                meetingStopping.set(false)
            }
        }
    }

    private val meetingStopping = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun stopMeetingNow() {
        run {
            audio.speak("Kończę nagrywanie i robię podsumowanie.", language = settings.getResponseLanguage())
            val m = meeting.stop()
            pl.victor.app.features.BackgroundFeatures.ustaw(pl.victor.app.features.BackgroundFeatures.SPOTKANIE, false)
            resumeWakeWordMic()
            if (m == null) return
            m.podsumowanie?.let { p ->
                val data = java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale("pl", "PL"))
                    .format(java.util.Date(m.startMs))
                settings.addNote("Spotkanie $data\n${p.trim()}")
            }
            audio.speakAndAwait(
                pl.victor.app.features.meeting.MeetingNotes.naGłos(m.podsumowanie, m.linie.size),
                language = settings.getResponseLanguage()
            )
        }
    }

    /** Przypomnienia związane z miejscem - patrz [pl.victor.app.features.reminders.PlaceReminders]. */
    val placeReminders by lazy {
        pl.victor.app.features.reminders.PlaceReminders(
            context = context,
            scope = scope,
            mów = { tekst -> audio.speakAndAwait(tekst, language = settings.getResponseLanguage()) },
            wolno = { ciszaWTle() },
            okulary = { glassesManager.isConnected() },
            miejsce = { nazwa -> settings.getPlace(nazwa) },
            dziennik = { co, pola -> runCatching { diag.event(DiagFormat.Phase.KONTEKST, co, pola) } }
        )
    }

    /** Komendy przypomnień w miejscach - patrz [pl.victor.app.features.reminders.PlaceReminderCommands]. */
    private val placeReminderCommands by lazy {
        pl.victor.app.features.reminders.PlaceReminderCommands(
            context = context,
            scope = scope,
            settings = settings,
            reminders = placeReminders,
            odpowiedz = { odp ->
                audio.speak(odp, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(odp)
            }
        )
    }

    /**
     * Listy (zakupy, zadania, nazwane) i ich synchronizacja z Google Tasks -
     * patrz [pl.victor.app.lists.ListsAssistant]. Publiczne, bo ekran Notatek
     * i przełącznik w Ustawieniach uruchamiają tę samą synchronizację.
     */
    val lists by lazy {
        pl.victor.app.lists.ListsAssistant(
            context = context,
            scope = scope,
            settings = settings,
            dziennik = { co, pola -> runCatching { diag.event(DiagFormat.Phase.AKCJA, co, pola) } }
        )
    }

    /** Tryb krok po kroku - patrz [pl.victor.app.features.StepController]. */
    val steps by lazy {
        pl.victor.app.features.StepController(
            scope = scope,
            mów = { tekst -> audio.speak(tekst, language = settings.getResponseLanguage()) },
            mówIczekaj = { tekst -> audio.speakAndAwait(tekst, language = settings.getResponseLanguage()) },
            model = { polecenie -> askModelPlain(polecenie) },
            dziennik = { co, pola -> runCatching { diag.event(DiagFormat.Phase.AKCJA, co, pola) } }
        )
    }

    /** Tryb przewodnika - patrz [pl.victor.app.features.GuideMode]. */
    val guide by lazy {
        pl.victor.app.features.GuideMode(
            context = context,
            scope = scope,
            mów = { tekst -> audio.speakAndAwait(tekst, language = settings.getResponseLanguage()) },
            wolno = { ciszaWTle() },
            dziennik = { co, pola -> runCatching { diag.event(DiagFormat.Phase.KONTEKST, co, pola) } }
        ).also { g ->
            // Przewodnik potrzebuje usługi w tle (lokalizacja przy zgaszonym ekranie).
            scope.launch {
                g.aktywny.collect {
                    pl.victor.app.features.BackgroundFeatures.ustaw(
                        pl.victor.app.features.BackgroundFeatures.PRZEWODNIK, it
                    )
                }
            }
        }
    }

    /**
     * Tłumaczenie ze słuchu i rozmowa w dwie strony - patrz
     * [pl.victor.app.orchestrator.EarTranslationController]. Jego stan (flagi
     * trybu, pętle, panel) należy do modułu; tu jest tylko odczyt i wejścia.
     */
    private val ear = pl.victor.app.orchestrator.EarTranslationController(
        context = context,
        settings = settings,
        scope = scope,
        audio = audio,
        glassesManager = glassesManager,
        speechToText = speechToText,
        conversationalMode = conversationalMode,
        translator = translator,
        transcriber = transcriber,
        state = _state,
        dziennik = { diag },
        host = object : pl.victor.app.orchestrator.EarTranslationHost {
            override val activeTurnJob: kotlinx.coroutines.Job?
                get() = this@AIOrchestrator.activeTurnJob
            override var setAsidePhoneTranscript: String?
                get() = this@AIOrchestrator.setAsidePhoneTranscript
                set(value) {
                    this@AIOrchestrator.setAsidePhoneTranscript = value
                }
            override fun hasMicrophonePermission() = this@AIOrchestrator.hasMicrophonePermission()
            override fun liveConversationOn() = this@AIOrchestrator.liveConversationOn()
            override fun stopLiveConversation(powód: String) = this@AIOrchestrator.stopLiveConversation(powód)
            override fun pauseWakeWordMic() = this@AIOrchestrator.pauseWakeWordMic()
            override fun resumeWakeWordMic() = this@AIOrchestrator.resumeWakeWordMic()
            override suspend fun askModelPlain(prompt: String) = this@AIOrchestrator.askModelPlain(prompt)
            override suspend fun listenUntilSpeechEnds(
                languageTag: String,
                capture: GlassesVoiceCapture?,
                trustPhoneMicrophone: Boolean,
                useBluetoothMic: Boolean,
                onPartial: ((String) -> Unit)?
            ) = this@AIOrchestrator.listenUntilSpeechEnds(
                languageTag = languageTag,
                capture = capture,
                trustPhoneMicrophone = trustPhoneMicrophone,
                useBluetoothMic = useBluetoothMic,
                onPartial = onPartial
            )
        }
    )

    /** Czy tłumaczenie ze słuchu działa jako rozmowa w dwie strony. */
    val earTwoWay: StateFlow<Boolean> get() = ear.earTwoWay

    /** Czy trwa tryb tłumaczenia ze słuchu - do pokazania w interfejsie. */
    val earTranslation: StateFlow<Boolean> get() = ear.earTranslation

    /** Co słychać i co z tego wyszło - dla panelu tłumaczenia. */
    val earTranscript: StateFlow<pl.victor.app.translation.EarTranscript> get() = ear.earTranscript

    /** Rozmowa w dwie strony z tłumaczem - patrz [EarTranslationController.startTwoWayTranslation]. */
    fun startTwoWayTranslation(obcy: String? = null) = ear.startTwoWayTranslation(obcy)

    /** Moja kolej w rozmowie - patrz [EarTranslationController.myTurn]. */
    fun myTurn(zOkularów: Boolean) = ear.myTurn(zOkularów)

    /** Czyści tekst w panelu. Trwającego trybu nie zatrzymuje. */
    fun clearEarTranscript() = ear.clearEarTranscript()

    /** Zmienia języki trybu - patrz [EarTranslationController.setEarTranslationLanguages]. */
    fun setEarTranslationLanguages(from: String, to: String) = ear.setEarTranslationLanguages(from, to)

    /** Jedno wejście dla przycisku, gestu i komendy głosowej. */
    fun toggleEarTranslation() = ear.toggleEarTranslation()

    /** Włącza tryb - patrz [EarTranslationController.startEarTranslation]. */
    fun startEarTranslation() = ear.startEarTranslation()

    /** Wyłącza tryb; [powód] trafia do dziennika. */
    fun stopEarTranslation(powód: String) = ear.stopEarTranslation(powód)

    /**
     * Rozmowa na żywo z asystentem (Gemini Live) - patrz
     * [pl.victor.app.live.LiveConversationMode]. Tryb, nie tura: dopóki trwa,
     * trzyma mikrofon, a orkiestrator nie wpuszcza żadnej tury.
     */
    private val liveMode by lazy {
        pl.victor.app.live.LiveConversationMode(
            context = context,
            scope = scope,
            settings = settings,
            audio = audio,
            dziennik = { co, pola -> runCatching { diag.event(DiagFormat.Phase.AUDIO, co, pola) } },
            przejmijMikrofon = {
                // Pod własnym imieniem: koniec tury, która włączyła tryb, nie
                // może wznowić "Hej Wiktor" w trakcie rozmowy.
                runCatching {
                    victorApp?.pauseWakeForTurn(pl.victor.app.VictorApplication.WAKE_HOLDER_LIVE)
                }
                glassesManager.suspendMicWake(true)
                // Nasłuch trybu konwersacyjnego po odpowiedzi tury włączającej
                // też chce mikrofonu - zwalniamy go.
                conversationalMode.onAiStartedSpeaking()
            },
            oddajMikrofon = {
                // Okulary nadają jeszcze chwilę - bez karencji ten ogon
                // odpalałby turę z przypadkowym pytaniem (jak po tłumaczeniu).
                glassesManager.suspendMicWake(false, graceMs = EAR_MIC_WAKE_GRACE_MS)
                runCatching {
                    victorApp?.resumeWakeAfterTurn(pl.victor.app.VictorApplication.WAKE_HOLDER_LIVE)
                }
            },
            instrukcja = { buildLiveConversationInstruction() },
            zapiszWymianę = { ty, asystent -> conversationContext.addTurn(ty, asystent) },
            zgodaNaMikrofon = { hasMicrophonePermission() }
        )
    }

    /** Czy trwa rozmowa na żywo - do pokazania w interfejsie. */
    val liveConversation: StateFlow<Boolean> get() = liveMode.aktywna

    private fun liveConversationOn(): Boolean = liveMode.aktywna.value

    /**
     * Zaczyna rozmowę na żywo - z kafelka, przycisku albo polecenia głosowego.
     *
     * Gdy nie wolno (wyłączona, "tylko darmowe", brak klucza Gemini), mówi
     * dlaczego i zostaje zwykły tryb pytań. Tłumaczenie ze słuchu trzyma ten
     * sam mikrofon, więc je kończymy; nagrywanie spotkania - nie, bo to
     * byłaby utrata notatek bez pytania.
     */
    fun startLiveConversation(źródło: String = "aplikacja") {
        if (liveConversationOn()) return
        if (meetingHoldsMic()) {
            val komunikat = "Trwa nagrywanie spotkania. Zakończ je, zanim zaczniemy rozmowę na żywo."
            runCatching {
                diag.event(DiagFormat.Phase.AUDIO, "rozmowa na żywo: odmowa - spotkanie", mapOf("źródło" to źródło))
            }
            audio.speak(komunikat, language = settings.getResponseLanguage())
            return
        }
        // Najpierw sprawdzić, czy rozmowa w ogóle ruszy - kafelek przy "tylko
        // darmowych" zabijał trwające tłumaczenie i nic nie startował
        // (przegląd 22.11). start() przy odmowie sam mówi powód.
        if (liveMode.dostępność() != pl.victor.app.live.LiveConversationLogic.Dostępność.MOŻNA) {
            liveMode.start(null, źródło)
            return
        }
        if (earTranslation.value) stopEarTranslation("start rozmowy na żywo")
        val turaWToku = activeTurnJob?.takeIf { it.isActive }
        liveMode.start(turaWToku, źródło)
    }

    /** Kończy rozmowę na żywo (przycisk, gest, ekran). */
    fun stopLiveConversation(powód: String) {
        liveMode.stop(powód)
    }

    /** Jedno wejście dla kafelka na ekranie głównym. */
    fun toggleLiveConversation(źródło: String = "aplikacja") {
        if (liveConversationOn()) stopLiveConversation("ponowne wywołanie ($źródło)")
        else startLiveConversation(źródło)
    }

    /**
     * Instrukcja systemowa rozmowy na żywo: ta sama persona co w zwykłej
     * turze, czas i - jeśli jest od ręki - miejsce. Bez katalogu akcji: w
     * tym trybie nikt nie wykonuje znaczników.
     */
    private suspend fun buildLiveConversationInstruction(): String {
        val tu = runCatching {
            withTimeoutOrNull(LIVE_LOCATION_MS) {
                pl.victor.app.proactive.LocationContext.tutaj(context)
            }
        }.getOrNull()
        return pl.victor.app.live.LiveConversationProtocol.instrukcja(
            persona = getActivePersona().systemPrompt,
            czas = modelContext.buildTimeContext(),
            miejsce = pl.victor.app.live.LiveConversationProtocol.miejsce(tu?.opis, tu?.miasto)
        )
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
    private fun handleSpokenText(text: String): Boolean {
        val configured = pl.victor.app.conversation.WakePhrase.PHONE_PHRASES
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
            return false
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
            return true
        }
        val question = WakePhrase.stripLeadingWakePhrase(text, configured)
        if (question != text) {
            diag.event(
                DiagFormat.Phase.NASŁUCH, "odcięta fraza wybudzenia",
                mapOf("usłyszane" to text.take(60), "pytanie" to question.take(60))
            )
        }
        handleUserTrigger(TriggerSource.VOICE, question)
        return true
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
        ostatniaAktywnośćMs = System.currentTimeMillis()
        // "Hej Wiktor" i ikona mikrofonu w trakcie rozmowy na żywo: nasłuch
        // zabrałby sesji mikrofon. Wybudzenie jest wtedy wstrzymane, więc tu
        // trafia głównie ikona w aplikacji.
        if (liveConversationOn()) {
            runCatching {
                diag.event(
                    DiagFormat.Phase.AUDIO, "rozmowa na żywo: nasłuch pominięty",
                    mapOf("zOkularów" to fromGlasses)
                )
            }
            return
        }
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
        // Zasoby wzięte PRZED głównym try tej tury. Anulowanie w trakcie
        // negocjacji SCO kończyło korutynę bez finally: mikrofon frazy i
        // nagrywanie spotkania zostawały wstrzymane, wake lock trzymał do
        // limitu, a subskrypcja mikrofonu okularów wisiała (audyt 28.10).
        class Prelude {
            @Volatile var capture: GlassesVoiceCapture? = null
            @Volatile var held = false
            @Volatile var done = false
            @Volatile var heartbeat = false
            @Volatile var micHeld = false
            @Volatile var lockHeld = false
        }
        val prelude = Prelude()
        // Własna nazwa blokady na turę: przerwana tura zwalniała w `finally`
        // blokadę o TEJ SAMEJ nazwie, którą nowa tura właśnie wzięła - i nowy
        // nasłuch szedł przy zgaszonym ekranie bez blokady uśpienia.
        val lockListening = "$LOCK_LISTENING#${turnSeq.incrementAndGet()}"
        // Zwolnienia jednorazowe: `finally` tury i obsługa jej zakończenia
        // wołają je oba, a liczy się pierwsze. Gdyby coś w `finally` rzuciło
        // przed zwolnieniem, licznik mikrofonu frazy zostałby podbity do
        // restartu aplikacji - nasłuch frazy na telefonie już by nie wrócił.
        val micReleased = java.util.concurrent.atomic.AtomicBoolean(false)
        val lockReleased = java.util.concurrent.atomic.AtomicBoolean(false)
        val releaseVoiceMic = {
            if (prelude.micHeld && micReleased.compareAndSet(false, true)) releaseMicAfterTurn()
        }
        val releaseVoiceLock = {
            if (prelude.lockHeld && lockReleased.compareAndSet(false, true)) {
                runCatching { wakeLock.release(lockListening) }
            }
        }
        // Który nasłuch okularów jest NAJNOWSZY. Stara tura, której sprzątanie
        // spóźni się za start nowej, nie może wysłać okularom "koniec nasłuchu" -
        // zatrzymałaby strumień i puls tury, która właśnie słucha.
        val myGlassesListen = if (fromGlasses) glassesListenSeq.incrementAndGet() else -1
        val voiceJob = launchTurn {
            val myToken = turnMarker.get()
            // Strumień z mikrofonu okularów podpinamy JAKO PIERWSZY, przed
            // zestawianiem łącza audio. Producent (Prism Pro) subskrybuje go
            // dokładnie w chwili wybudzenia, a negocjacja SCO potrafi trwać
            // kilka sekund - gdyby szła przodem, początek pytania przepadłby,
            // zanim zdążylibyśmy zacząć słuchać.
            var micStreamLive = false
            val glassesCapture =
                if (fromGlasses && glassesManager.isConnected()) {
                    GlassesVoiceCapture(glassesManager).also {
                        prelude.capture = it
                        micStreamLive = it.start()
                    }
                } else {
                    null
                }
            if (fromGlasses) {
                prelude.heartbeat = true
                // Uciszenie okularów to komenda BLE - idzie natychmiast, więc
                // musi pójść PRZED zestawianiem łącza audio. Za nim czekałoby
                // na negocjację SCO i okulary grałyby dalej przez ten czas.
                // Producent nie gra tu żadnego dźwięku powitalnego, tylko
                // ucisza to, co leci - i my robimy tak samo.
                glassesManager.playGlassesTone(GlassesProtocol.TONE_STOP_PLAYBACK)
                // Puls "wciąż słucham" - jak u producenta, patrz
                // VictorManager.startVoiceHeartbeat. Gaśnie w stopGlassesListening.
                glassesManager.startVoiceHeartbeat()
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
            holdMicForTurn()
            prelude.micHeld = true
            wakeLock.acquire(lockListening, LISTEN_WAKE_LOCK_MS)
            prelude.lockHeld = true
            // MIKROFON OKULARÓW IDZIE STRUMIENIEM BLE, NIE PROFILEM ROZMOWY.
            //
            // ## Prostuję to, co stało tu wcześniej
            // Napisałem, że "strumień BLE NIE DAJE NAM dźwięku pytania", bo
            // transkrypcja z niego nie oddała tekstu ani razu na około
            // czterdzieści prób. Wniosek był taki, że jedyną drogą do mikrofonu
            // okularów jest SCO - i pod to poszła cała reszta.
            //
            // Wniosek był zły, bo brał skutek obejścia za własność drogi: SCO
            // każe okularom oddać mikrofon do HFP, przez co PRZESTAJĄ nadawać
            // ten strumień (w dzienniku pakiety spadały z 456 do 48). Droga,
            // którą uznałem za ślepą, była psuta przez obejście wprowadzone
            // dlatego, że uznałem ją za ślepą.
            //
            // ## Co robi producent
            // `GlassesAzureSpeechRecognizer` w Prismie nie zestawia SCO ANI RAZU
            // i nie tyka mikrofonu telefonu. Bierze dźwięk z okularów po BLE i
            // pcha go do rozpoznawania w chmurze. To jest cała ich droga - i to
            // jest droga, na którą przechodzimy.
            //
            // ## Co z tego wynika tutaj
            // Gdy strumień BLE żyje, profil rozmowy jest NIEPOTRZEBNY - a jest
            // przy tym szkodliwy: zestawienie SCO zawiesza A2DP (okulary
            // milkną), kosztuje do czterech sekund negocjacji przed nasłuchem i
            // każe okularom oddać mikrofon do HFP, przez co PRZESTAJĄ nadawać
            // ten strumień. Braliśmy więc coś, co samo psuło drogę, którą
            // właśnie chcemy iść.
            //
            // Profil rozmowy zostaje wyłącznie na wypadek, gdy strumienia nie ma
            // (stary egzemplarz, nieudany start) - wtedy to nadal jedyna droga
            // do mikrofonu innego niż telefon.
            val wantsGlassesMic = settings.isGlassesMicEnabled()
            val routingStartedAtMs = System.currentTimeMillis()
            var held = if (micStreamLive) {
                // Strumień BLE niesie mikrofon okularów. SCO by go ZABIŁO.
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
                            // Ile trwało faktyczne wstanie łącza; -1 = nie
                            // wstało w limicie i poszliśmy dalej.
                            "łączeMs" to audio.lastConversationLinkWaitMs(),
                            "mikrofonOkularów" to wantsGlassesMic
                        )
                    )
                }
            }
            prelude.held = held
            val overSco = held && audio.isRoutedToBluetooth()
            // KOMENDA "KONIEC NASŁUCHU AI" SZŁA DWA RAZY NA TURĘ.
            //
            // Widać to wprost w dzienniku ramek z 19 września: odpowiedzi na nią
            // przychodzą parami, 16:07:03,287 i 16:07:04,678, potem 16:07:38,477
            // i 16:07:39,783. Odstęp circa 1,4 s to dokładnie czas między
            // końcem nasłuchu a wyjściem z bloku `finally` - czyli dwa różne
            // miejsca w tej samej turze, oba wykonane.
            //
            // Kosztuje to więcej niż dwa wiersze w logu. Vendor SDK ma JEDEN
            // slot na odpowiedź (patrz [pl.victor.app.ble.VictorManager.send]),
            // więc druga wysyłka eksmituje callback pierwszej - i to na kanale,
            // którym idą też zdjęcia.
            //
            // Obu wywołań nie da się po prostu skasować: to pierwsze ma wyłączyć
            // mikrofon NATYCHMIAST po nasłuchu (inaczej okulary nadają przez
            // całą transkrypcję), a to w `finally` jest jedynym, które zadziała
            // po ANULOWANEJ turze. Pilnuje ich więc ten znacznik.
            var nasluchZatrzymany = false
            // Nagranie z okularów oddane do dekodowania w tle - wtedy to tamta
            // korutyna je zamyka, a `finally` tury nie może go odpinać.
            var captureHandedOff = false
            // Liczone RAZ i tutaj, bo czyta je zarówno wpis o końcu nasłuchu,
            // jak i wpis o transkrypcji - a te dwa dzieli pół ekranu kodu.
            val phoneMicNotTrusted = pl.victor.app.audio.MicChoice.phoneMicNotTrusted(
                fromGlasses = fromGlasses,
                wantsGlassesMic = wantsGlassesMic,
                overSco = overSco,
                bleStreamLive = micStreamLive
            )
            // PROŚBA O MIKROFON OKULARÓW, KTÓREJ NIE DAŁO SIĘ SPEŁNIĆ, BYŁA
            // DOTĄD NIEWIDOCZNA.
            //
            // Zejście na mikrofon telefonu jest sensownym zachowaniem awaryjnym -
            // gorzej słychać, ale w ogóle słychać. Złe było to, że nie zostawiało
            // ŻADNEGO śladu: ani w dzienniku, ani dla człowieka. Z zewnątrz
            // wyglądało to jak zignorowane ustawienie i dokładnie tak zostało
            // zgłoszone.
            // Tylko wtedy, gdy NIE MA strumienia BLE: przy żywym strumieniu
            // profil rozmowy jest celowo niezestawiany i ten wpis był fałszywym
            // alarmem przy każdym naciśnięciu przycisku.
            if (wantsGlassesMic && !overSco && !micStreamLive) {
                runCatching {
                    diag.event(
                        DiagFormat.Phase.AUDIO,
                        "proszono o mikrofon okularów, ale profil rozmowy nie wstał",
                        mapOf(
                            // Te dwa pola rozdzielają dwie różne naprawy: brak
                            // mikrofonu w zestawie to sprzęt, a obecny mikrofon
                            // przy nieudanym zestawieniu to profil zajęty albo
                            // okulary stojące w tej chwili jako multimedia.
                            "mikrofonWZestawie" to audio.hasConversationMic(),
                            "profil" to audio.audioProfileSummary(),
                            "strumieńBLE" to micStreamLive
                        )
                    )
                }
            }
            prelude.done = true
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
                        "mikrofonBT" to audio.isRoutedToBluetooth(),
                        // Stan USTAWIENIA w tej turze. Nagłówek dziennika podaje
                        // tylko wartość z chwili startu procesu, a tę wartość
                        // przestawia w trakcie pracy i człowiek, i sama
                        // aplikacja - czytanie nagłówka jako stanu bieżącego
                        // raz już doprowadziło do błędnej diagnozy.
                        "ustawienieMikOkularów" to wantsGlassesMic,
                        // Ile ciszy po zdaniu kończy ten nasłuch - wybór z
                        // Ustawień. Bez tego nie da się porównać `ms` z
                        // wiersza "koniec" między biegami.
                        "czekanieNaKoniec" to settings.getSpeechEndWait().describe()
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
                    // USTAWIENIE CZŁOWIEKA MUSI BYĆ W TYM WYRAŻENIU - I NIE BYŁO.
                    //
                    // Stało tu `overSco || !micStreamLive`. Ani jednego odwołania
                    // do `wantsGlassesMic`. Przy turze z okularów strumień BLE
                    // żyje prawie zawsze, więc `micStreamLive` jest prawdą; jeśli
                    // do tego łącze rozmowy nie wstało, całość dawała FAŁSZ -
                    // czyli jawny zakaz sięgania po mikrofon okularów, wydany
                    // komuś, kto właśnie o ten mikrofon poprosił.
                    //
                    // Zgłoszone wprost: "wybrałem Pytania mikrofonem okularów, a
                    // wciąż włącza się mikrofon w telefonie". Ustawienie było
                    // czytane wyłącznie po to, żeby zdecydować, czy PRÓBOWAĆ
                    // zestawić SCO (wyżej). Co zrobić, gdy próba się nie uda,
                    // nie zależało od niego w ogóle.
                    //
                    // A nie udaje się realnie: `startModern` szuka urządzenia
                    // typu TYPE_BLUETOOTH_SCO na liście do rozmowy. Gdy okulary
                    // stoją w tej chwili jako urządzenie MULTIMEDIALNE (A2DP),
                    // takiego wpisu nie ma i acquire() oddaje false.
                    //
                    // Prośby człowieka NIE MA w tym wyrażeniu i tak ma zostać -
                    // decyduje ona piętro wyżej, o tym, czy w ogóle podjąć próbę
                    // (patrz `held`), a `overSco` to już WYNIK tamtej próby.
                    // Dopisanie jej tutaj kazało brać profil rozmowy drugi raz,
                    // przy każdym rozpoznaniu - patrz [MicChoice.useBluetoothMic].
                    useBluetoothMic = pl.victor.app.audio.MicChoice.useBluetoothMic(
                        overSco = overSco,
                        bleStreamLive = micStreamLive
                    )
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
                        // Którym mikrofonem NAPRAWDĘ zebrano pytanie. To jedyne
                        // miejsce, w którym widać różnicę między "wybrał telefon"
                        // a "wybrał okulary, ale dostał telefon".
                        "zebrałTelefonZamiastOkularów" to phoneMicNotTrusted,
                        "mikrofon" to mic?.verdict(),
                        "szczytDb" to mic?.peakDb,
                        "próbek" to mic?.samples,
                        "gotowyPoMs" to mic?.readyMs,
                        // true: koniec wyznaczyła cisza po zdaniu mierzona
                        // przez aplikację (RecognizerEndpoint), nie silnik.
                        "koniecPoCiszyAplikacji" to mic?.endedByApp
                    )
                )
                // PYTANIE O KOD: strumień rusza JUŻ TERAZ, nie po przepisaniu
                // nagrania i zebraniu kontekstów - podniesienie trwa 8-25 s
                // i to jest największa pozycja w czasie takiej tury.
                heard?.let { codeStream.warmUpStreamForCode(it) }
                // Mikrofon jest teraz wolny, a model dopiero myśli - pół
                // sekundy pomiaru gwaru nie kosztuje czasu odpowiedzi.
                if (settings.isNoiseAdaptiveVolume()) {
                    scope.launch {
                        val db = audio.ambientNoise.zmierz()
                        runCatching { diag.event(DiagFormat.Phase.AUDIO, "hałas wokół", mapOf("tłoDb" to db?.toInt())) }
                    }
                }
                Log.i(TAG, "Nasłuch trwał ${System.currentTimeMillis() - listenStartedAtMs} ms")
                _state.value = OrchestratorState.Idle
                // Okulary nadają, dopóki im się tego nie zabroni - i to była
                // przyczyna zgłoszenia "przestaję mówić, a one nasłuchują
                // jeszcze długo". Komenda idzie DOKŁADNIE tutaj, tak jak u
                // producenta: w chwili, gdy nasłuch po naszej stronie się
                // skończył, przed transkrypcją i przed pytaniem modelu.
                if (fromGlasses) {
                    glassesManager.stopGlassesListening()
                    nasluchZatrzymany = true
                }

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
                // NIE CZEKAMY NA NAGRANIE, GDY NIE BĘDZIE UŻYTE.
                //
                // Rozkodowanie nagrania z okularów trwa 2-3,5 s (bieg 174:
                // 258 pakietów - 2,1 s, 424 - 3,4 s). Gdy telefon dał już tekst,
                // a przepisanie nagrania jest wyłączone albo milczy od
                // [GLASSES_TRANSCRIPT_MAX_MISSES] prób, nagranie trafia
                // wyłącznie do dziennika - a tura czekała na nie i tak, w
                // środku drogi od pytania do odpowiedzi. Teraz rozkodowanie
                // idzie obok, a pytanie od razu dalej.
                fun zapiszNagranie(nagranie: GlassesVoiceCapture.Result?, wTle: Boolean) {
                    runCatching {
                        diag.event(
                            DiagFormat.Phase.NASŁUCH, "nagranie z okularów",
                            mapOf(
                                "jest" to (nagranie?.hasAudio == true),
                                "sekund" to nagranie?.audioSeconds,
                                // Bez tych czterech liczb „nagranie jest niewyraźne"
                                // nie ma jak się rozstrzygnąć: nie wiadomo, czy to
                                // użytkownik mówił cicho, czy dekoder składa szum z
                                // pakietów, których kształtu nie odgadł.
                                "pakietów" to nagranie?.packets,
                                "rozkodowanych" to nagranie?.decodedPackets,
                                "odrzuconych" to nagranie?.failedPackets,
                                "przesunięcie" to nagranie?.payloadOffset,
                                "ramka" to nagranie?.packetSize,
                                // Czy koniec wypowiedzi wykrył miernik głośności i co
                                // zmierzył - patrz EnergyEndpoint.
                                "miernik" to glassesCapture?.meterSummary(),
                                // Głośność co pół sekundy - rozstrzyga, czy "w
                                // nagraniu nie słychać pytania" znaczy "mowa jest,
                                // tylko nierozpoznana", czy "okulary po pierwszej
                                // chwili nadają same zera". Patrz [AudioEnvelope].
                                "obwiednia" to nagranie?.pcm?.let {
                                    pl.victor.app.audio.AudioEnvelope.doDziennika(
                                        pl.victor.app.audio.AudioEnvelope.rms(
                                            it, pl.victor.app.audio.OpusDecoder.SAMPLE_RATE
                                        )
                                    )
                                },
                                "ciszaNaKońcuOkien" to nagranie?.pcm?.let {
                                    pl.victor.app.audio.AudioEnvelope.ciszaNaKońcu(
                                        pl.victor.app.audio.AudioEnvelope.rms(
                                            it, pl.victor.app.audio.OpusDecoder.SAMPLE_RATE
                                        )
                                    )
                                }
                            )
                        )
                    }
                    if (wTle) {
                        runCatching {
                            diag.event(DiagFormat.Phase.NASŁUCH, "nagranie z okularów rozkodowane w tle - tura nie czekała")
                        }
                    }
                }
                val tekstZTelefonu = setAsidePhoneTranscript
                val nagranieNiepotrzebne = tekstZTelefonu != null && glassesCapture != null &&
                    (!wantsGlassesMic || transcriber.glassesTranscriptMissedTooOften())
                val captured = if (nagranieNiepotrzebne) {
                    captureHandedOff = true
                    val doDziennika = glassesCapture
                    scope.launch(kotlinx.coroutines.NonCancellable) {
                        zapiszNagranie(runCatching { doDziennika?.stop() }.getOrNull(), wTle = true)
                    }
                    diag.event(
                        DiagFormat.Phase.TRANSKRYPCJA,
                        "mam tekst z telefonu - nie czekam na nagranie z okularów",
                        mapOf("mikrofonOkularów" to wantsGlassesMic, "pustychPrzepisań" to transcriber.glassesTranscriptMisses)
                    )
                    null
                } else {
                    glassesCapture?.stop().also { zapiszNagranie(it, wTle = false) }
                }
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
                // NAGRANIE Z OKULARÓW JEST TERAZ ŹRÓDŁEM PIERWSZYM, NIE ZAPASEM.
                //
                // Dotąd tekst z telefonu blokował przepisanie nagrania: skoro
                // coś już mamy, po co płacić sekundy za drugą drogę. Rachunek
                // był słuszny, dopóki obie drogi słyszały to samo - ale nie
                // słyszą. Telefon leży w kieszeni, mikrofon okularów wisi przy
                // ustach, a po odejściu na dwa metry telefon nie słyszy nic.
                //
                // Od przejścia na drogę producenta (dźwięk strumieniem BLE,
                // bez SCO) nagranie z okularów jest tym, o co człowiek prosił
                // w ustawieniach. Tekst z telefonu zostaje jako zapas na wypadek,
                // gdyby z okularów nic nie wyszło - `bestHeard` niżej bierze
                // `glassesHeard ?: heard`, więc cisza z okularów sama oddaje mu
                // pole.
                //
                // Płacimy czasem transkrypcji w każdej turze z okularów. To
                // jest cena funkcji, nie skutek uboczny - i była to świadoma
                // decyzja, nie moja samowola.
                // ...ALE NIE W NIESKOŃCZONOŚĆ. Bieg 160: 12 tur z rzędu
                // przepisanie nagrania okularów trwało 1-4,3 s i nie oddało
                // tekstu ANI RAZU, a pytanie i tak szło z tekstu telefonu.
                // Po [GLASSES_TRANSCRIPT_MAX_MISSES] pustych próbach z rzędu,
                // gdy tekst z telefonu leży, nie przepisujemy - do pierwszej
                // tury bez tekstu z telefonu albo pierwszego sukcesu.
                val skipGlassesTranscript = phoneFallback != null && transcriber.glassesTranscriptMissedTooOften()
                val glassesHeard = when {
                    captured?.hasAudio != true -> null
                    // Kto WYŁĄCZYŁ mikrofon okularów, ten ma dostać stare
                    // zachowanie: gotowy tekst z telefonu bez dopłaty czasowej.
                    !wantsGlassesMic && phoneFallback != null -> null
                    skipGlassesTranscript -> null
                    else -> captured.pcm?.let { transcriber.transcribeGlassesAudio(it, languageTagFor(language)) }
                        .also { wynik ->
                            transcriber.glassesTranscriptMisses = if (wynik.isNullOrBlank()) transcriber.glassesTranscriptMisses + 1 else 0
                        }
                }
                if (captured?.hasAudio == true) {
                    diag.event(
                        DiagFormat.Phase.TRANSKRYPCJA,
                        if (skipGlassesTranscript) {
                            "nagrania z okularów NIE przepisuję - ta droga milczy, biorę tekst z telefonu"
                        } else if (!wantsGlassesMic && phoneFallback != null) {
                            "nagrania z okularów NIE przepisuję - mam tekst z telefonu"
                        } else {
                            "z nagrania okularów"
                        },
                        if (!wantsGlassesMic && phoneFallback != null) {
                            // Dwa pytania o stan, zero rozpoznawania. Bez nich
                            // „ta droga milczy" i „tej drogi nie ma" wyglądają
                            // z dziennika identycznie, a to dwie różne naprawy:
                            // pierwsza to usterka, druga to jeden pakiet języka
                            // do pobrania w ustawieniach Androida.
                            mapOf(
                                "rozpoznawanieNaUrządzeniu" to speechToText.isOnDeviceAvailable(),
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
                        // NAGRANIE BEZ MOWY NIE IDZIE DO MODELU.
                        //
                        // Bieg 174, tura 3c01: przycisk wciśnięty, nikt nic nie
                        // powiedział (szczyt głośności 359, mowa w dziennikach
                        // zaczyna się od ok. 1500), a model dostał ciszę, myślał
                        // dwie sekundy, odpowiedział "słyszę tylko szum" - i
                        // przez ten czas zablokował zdjęcie, o które użytkownik
                        // poprosił przyciskiem aparatu.
                        val szczyt = raw?.let {
                            pl.victor.app.audio.VoiceTrim.measure(it, pl.victor.app.audio.OpusDecoder.SAMPLE_RATE)?.peak
                        }
                        if (szczyt != null && szczyt < RECORDING_SPEECH_PEAK) {
                            runCatching {
                                diag.event(
                                    DiagFormat.Phase.NASŁUCH, "w nagraniu z okularów nie ma mowy - nie wysyłam go modelowi",
                                    mapOf("szczyt" to szczyt.toInt(), "próg" to RECORDING_SPEECH_PEAK.toInt())
                                )
                            }
                            null
                        } else if (raw == null) {
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
                        return@launchTurn
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
                        return@launchTurn
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
                    return@launchTurn
                }
                silentScoTurns = 0
                Log.i(TAG, "Usłyszałem: \"$bestHeard\"")
                handleUserTrigger(
                    if (fromGlasses) TriggerSource.WAKE_WORD else TriggerSource.VOICE,
                    bestHeard
                )
            } finally {
                // Sprzątanie to już nie tura - patrz ten sam zabieg w turze
                // z modelem. Po przekazaniu pytania znacznik jest już cudzy.
                if (activeTurnToken === myToken) activeTurnToken = null
                // detach(), nie stop(): ten blok wykonuje się także po
                // ANULOWANIU tury, a wtedy każde wywołanie zawieszalne
                // natychmiast rzuca - subskrypcja BLE zostałaby zarejestrowana
                // na zawsze. Przerwana tura nie ma zresztą czego dekodować;
                // gałąź ciszy wyżej zdążyła już wziąć wynik przez stop().
                if (!captureHandedOff) glassesCapture?.detach()
                // Również po anulowanej turze - inaczej "cicho" uciszało
                // syntezator, a okulary nasłuchiwały dalej. Ale TYLKO wtedy, gdy
                // ścieżka normalna tego nie zrobiła: patrz znacznik wyżej.
                if (fromGlasses && !nasluchZatrzymany && glassesListenSeq.get() == myGlassesListen) {
                    runCatching { glassesManager.stopGlassesListening() }
                }
                // NonCancellable: ten blok wykonuje się też po ANULOWANIU tury, a
                // wtedy zawieszenie na blokadzie routera rzuca od razu - licznik
                // łącza zostawał podbity, SCO stało, a wiersze niżej (blokada
                // ekranu, mikrofon frazy) nie wykonywały się wcale.
                if (held) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        audio.endConversationRouting()
                    }
                }
                // Nazwa jest tu istotna: bez niej ten blok zwalniał blokadę TURY,
                // która startuje z tego samego miejsca i żyje dłużej niż nasłuch.
                releaseVoiceLock()
                releaseVoiceMic()
            }
        }
        // Anulowanie PRZED głównym try (negocjacja SCO, start strumienia):
        // sprzątamy to, co prelude zdążył wziąć. Po wejściu w try robi to finally.
        voiceJob.invokeOnCompletion { cause ->
            if (cause != null && !prelude.done) {
                prelude.capture?.detach()
                if (prelude.heartbeat && glassesListenSeq.get() == myGlassesListen) {
                    runCatching { glassesManager.stopGlassesListening() }
                }
                if (prelude.held) {
                    scope.launch(kotlinx.coroutines.NonCancellable) { audio.endConversationRouting() }
                }
            }
            // Zawsze, nie tylko przy anulowaniu w przygotowaniu: po zwykłym
            // końcu to nic nie robi (zwolnienia są jednorazowe), a ratuje
            // mikrofon i blokadę, gdy `finally` tury wyleci wyjątkiem.
            releaseVoiceLock()
            releaseVoiceMic()
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
        // Bez tego wpisu przełączenie było NIEWIDOCZNE w dzienniku: ustawienie
        // zmieniało się trwale, komunikat padał raz głosem i znikał, a kolejne
        // sesje pokazywały już tylko skutek. Człowiek pamiętał, że włączał
        // mikrofon okularów, dziennik twierdził, że jest wyłączony - i nic nie
        // łączyło jednego z drugim.
        runCatching {
            diag.event(
                DiagFormat.Phase.AUDIO,
                "sam wyłączam mikrofon okularów po $SILENT_SCO_LIMIT cichych turach",
                mapOf("odwracalne" to "Ustawienia > Pytania mikrofonem okularów")
            )
        }
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
        useBluetoothMic: Boolean = true,
        onPartial: ((String) -> Unit)? = null
    ): String? {
        if (capture == null) {
            return conversationalMode.listenOnce(
                languageTag = languageTag,
                useBluetoothMic = useBluetoothMic,
                onPartial = onPartial
            )
        }

        return coroutineScope {
            // Czy rozpoznawanie w telefonie ROZUMIE, a nie tylko słyszy.
            //
            // Dotąd ogon [GlassesVoiceCapture.PHONE_TAIL_MS] dostawał każdy
            // nasłuch, w którym telefon wykrył "mowę". Bieg 174: w dwóch turach
            // telefon wykrył mowę, nie oddał ani słowa, a nasłuch stał przez to
            // 2,5 s dłużej. Wynik częściowy znaczy, że telefon naprawdę coś
            // rozpoznaje i warto na niego poczekać.
            val phonePartial = java.util.concurrent.atomic.AtomicBoolean(false)
            val partialWatch: (String) -> Unit = { text ->
                if (text.isNotBlank()) phonePartial.set(true)
                onPartial?.invoke(text)
            }
            val listening = async {
                conversationalMode.listenOnce(
                    languageTag = languageTag,
                    useBluetoothMic = useBluetoothMic,
                    onPartial = partialWatch
                )
            }
            // Cisza kończąca wypowiedź z Ustawień ("Czekanie na koniec
            // zdania") - ta sama, którą rozpoznawanie w telefonie dostaje w
            // SpeechToText.listen. Czekanie na POCZĄTEK mowy (karencja,
            // limit) zostaje jak było.
            val endWait = settings.getSpeechEndWait()
            val glassesQuiet = async {
                capture.awaitSpeechEnd(
                    silenceMs = endWait.glassesPacketSilenceMs,
                    energySilenceMs = endWait.glassesEnergySilenceMs,
                    phoneHearing = { phonePartial.get() }
                )
            }

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

    /** Pamięć o tym, co już powiedziano o łączu - patrz [ConnectionAnnouncer]. */
    private val announcer = ConnectionAnnouncer()

    /** Odliczanie karencji przed ogłoszeniem utraty łącza. */
    private var utrataJob: kotlinx.coroutines.Job? = null

    private var missedAlertsJob: kotlinx.coroutines.Job? = null

    /**
     * Po powrocie okularów mówi alerty, które przepadły, gdy były rozłączone.
     *
     * Z opóźnieniem: zaraz po połączeniu idzie powitanie okularów i wstaje
     * dźwięk - komunikat wypowiedziany w tej samej sekundzie poszedłby
     * głośnikiem telefonu albo w próżnię.
     */
    private fun speakMissedAlertsSoon() {
        if (!settings.isAlertsSpokenEnabled()) return
        missedAlertsJob?.cancel()
        missedAlertsJob = scope.launch {
            // Kilka podejść: gdy po powrocie okularów akurat trwa rozmowa,
            // alert ma wejść po niej, a nie przepaść do następnego rozłączenia.
            repeat(MISSED_ALERTS_ATTEMPTS) {
                delay(MISSED_ALERTS_DELAY_MS)
                if (glassesManager.connectionState.value != ConnectionState.READY) return@launch
                // Nie wchodzimy w słowo. Alerty zostają w pamięci tylko do
                // pobrania, więc sprawdzamy ciszę PRZED ich zabraniem.
                if (!ciszaWTle()) return@repeat
                bezpiecznie("zaległe alerty") {
                    val zaległe = runCatching { victorApp?.missedAlerts?.takeFresh() }.getOrNull()
                    if (!zaległe.isNullOrEmpty()) {
                        diag.event(
                            DiagFormat.Phase.MOWA, "zaległe alerty po powrocie okularów",
                            mapOf("ile" to zaległe.size)
                        )
                        audio.speak(zaległe.joinToString(" "), language = settings.getResponseLanguage())
                    }
                }
                return@launch
            }
        }
    }

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
        val bezMowy = capture?.takeIf { it.hasAudio }?.pcm?.let {
            pl.victor.app.audio.VoiceTrim.measure(it, pl.victor.app.audio.OpusDecoder.SAMPLE_RATE)?.peak
        }?.let { it < RECORDING_SPEECH_PEAK } == true
        if (bezMowy) return "Nie usłyszałem pytania. Powiedz je zaraz po sygnale."
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
        ostatniaAktywnośćMs = System.currentTimeMillis()
        // DO DZIENNIKA, NIE TYLKO DO LOGCATA.
        //
        // Dziennik zapisywał wciśnięcie ramki ("PRZYCISK wciśnięto"), ale NIE
        // to, czy aplikacja cokolwiek z nim zrobiła. Przy zgłoszeniu "okulary
        // grają dźwięk, a nasłuch się nie zaczyna" te dwie rzeczy trzeba
        // rozróżnić: ramka doszła i akcja nie ruszyła, czy ramka nie doszła
        // wcale. Bez tego wpisu wyglądają identycznie.
        runCatching {
            diag.event(
                DiagFormat.Phase.PRZYCISK,
                "akcja z przycisku",
                mapOf(
                    "akcja" to action::class.simpleName,
                    // Liczba kliknięć i odstępy MIĘDZY nimi - patrz
                    // [ButtonActionDetector.lastGapsMs]. Zgłoszono, że jedno i
                    // dwa kliknięcia działają, a trzy i cztery nie; te dwa pola
                    // rozdzielają dwie możliwe przyczyny, których z kodu nie da
                    // się rozróżnić. Przy serii "3 kliknięć" dwa odstępy znaczą,
                    // że gubi je nasze okno zliczania (do naprawienia liczbą),
                    // a jeden - że firmware nie zgłosił trzeciego wciśnięcia
                    // (nie do naprawienia, funkcja musi iść na głos).
                    "kliknięć" to buttonDetector.lastClickCount,
                    "odstępyMs" to buttonDetector.lastGapsMs
                        .takeIf { it.isNotEmpty() }?.joinToString("/")
                )
            )
        }
        // W TRAKCIE TŁUMACZENIA ZE SŁUCHU PRZYCISK JE KOŃCZY - I TO JEST
        // JEDYNE WYJŚCIE, NA KTÓRE MOŻNA LICZYĆ.
        //
        // Wyjście głosem ("koniec tłumaczenia") ma dziurę, którą sam zrobiłem:
        // pętla nasłuchuje w języku ŹRÓDŁOWYM. Przy domyślnym angielskim polskie
        // "koniec tłumaczenia" rozpoznawanie angielskie zamienia w przypadkowe
        // angielskie słowa i fraza nie pasuje. Człowiek zostawał w trybie,
        // z którego nie umie wyjść - a ten trzyma mikrofon i baterię.
        //
        // Dodatkowo wciśnięcie przycisku w trakcie tego trybu uruchamiało
        // drugą, równoległą turę pytania, która biła się z pętlą o mikrofon.
        // Każdy gest - jedno, dwa, trzy kliknięcia, także "Hey Lens", który
        // przychodzi tą samą ramką - kończy więc tryb i nic więcej nie robi.
        // W ROZMOWIE W DWIE STRONY pojedyncze kliknięcie oddaje głos Tobie;
        // kończy ją każdy inny gest (dwa, trzy kliknięcia, przytrzymanie).
        // ROZMOWA NA ŻYWO: każdy gest ją kończy - jak tłumaczenie ze słuchu.
        // Gest włącza okulary w nasłuch, więc od razu go kończymy.
        if (liveConversationOn()) {
            glassesManager.stopGlassesListening()
            stopLiveConversation("przycisk na okularach (${action::class.simpleName})")
            return
        }
        if (earTranslation.value && earTwoWay.value && action == ButtonAction.QUICK_QUESTION) {
            myTurn(zOkularów = true)
            return
        }
        if (earTranslation.value) {
            stopEarTranslation("przycisk na okularach")
            // Wciśnięcie samo włączyło okulary w nasłuch (tak działa ich
            // przycisk AI) - bez końca sesji nadawały dalej, choć nikt już
            // nie słuchał.
            glassesManager.stopGlassesListening()
            audio.speak("Kończę tłumaczenie.", language = settings.getResponseLanguage())
            return
        }
        // SPOTKANIE: 4 kliknięcia kończą nagrywanie - działa zawsze, także gdy
        // głos nie przechodzi.
        if (meetingHoldsMic() && action == ButtonAction.NEW_CONVERSATION) {
            glassesManager.stopGlassesListening()
            stopMeeting()
            return
        }

        // KROK PO KROKU: 1 klik dalej, 2 pytanie (zwykła tura z krokami w
        // kontekście), 3 powtórz, 4 koniec. Klik włącza okulary w nasłuch -
        // przy "dalej" i "powtórz" od razu go kończymy, inaczej wybudzenie z
        // dźwięku odpaliłoby turę z przypadkowym pytaniem. Po tłumaczeniu,
        // bo przycisk ma je kończyć przed czymkolwiek innym.
        if (steps.aktywny) {
            val k = if (action == ButtonAction.QUICK_QUESTION) {
                pl.victor.app.features.StepLogic.Komenda.DALEJ
            } else if (action == ButtonAction.READ_AND_TRANSLATE) {
                pl.victor.app.features.StepLogic.Komenda.POWTÓRZ
            } else if (action == ButtonAction.NEW_CONVERSATION) {
                pl.victor.app.features.StepLogic.Komenda.KONIEC
            } else {
                null
            }
            if (k != null) {
                glassesManager.stopGlassesListening()
                steps.komenda(k)
                return
            }
            if (action == ButtonAction.LOOK_AND_DESCRIBE) {
                startVoiceTurn(fromGlasses = true)
                return
            }
        }
        // TRYB BEZ KAMERY: gesty aparatu dostają nowe znaczenie. Dwa kliknięcia -
        // nowe wiadomości, trzy - "mój dzień" (kalendarz, pogoda, poczta). Klik
        // włącza okulary w nasłuch, więc od razu go kończymy - inaczej wybudzenie
        // z dźwięku odpaliłoby turę z przypadkowym pytaniem.
        if (settings.isCameraFree()) {
            when (action) {
                ButtonAction.LOOK_AND_DESCRIBE -> {
                    glassesManager.stopGlassesListening()
                    readNewMessages()
                    return
                }
                ButtonAction.READ_AND_TRANSLATE -> {
                    glassesManager.stopGlassesListening()
                    runBriefing()
                    return
                }
                ButtonAction.SCAN_QR -> {
                    glassesManager.stopGlassesListening()
                    audio.speak(CAMERA_OFF_SPEECH, language = settings.getResponseLanguage())
                    return
                }
                else -> Unit
            }
        }
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
            ButtonAction.READ_AND_TRANSLATE -> handleUserTrigger(
                TriggerSource.BUTTON,
                pl.victor.app.vision.ReadTextPrompt.forLanguage(settings.getResponseLanguage()),
                forceVision = true
            )
            // POLECENIE MÓWIŁO WYŁĄCZNIE O KODZIE QR - I TO BYŁ MÓJ BŁĄD.
            //
            // Wczoraj podpiąłem tu komendy głosowe "zeskanuj kod", "co to za
            // produkt" i "sprawdź ten produkt", nie ruszając treści polecenia.
            // Człowiek pytał o KOD KRESKOWY produktu, a model dostawał zadanie
            // o kodzie QR - i odpowiadał o czymś innym, niż pytano.
            //
            // Kod kreskowy ma tu zresztą własną, lepszą drogę: EAN trafia do
            // Open Food Facts i wraca z nazwą, gramaturą i alergenami, czyli z
            // czymś, czego model ze zdjęcia nie wyczyta. Polecenie musi
            // obejmować oba rodzaje kodów, żeby ta droga w ogóle ruszyła.
            ButtonAction.SCAN_QR -> handleUserTrigger(
                TriggerSource.BUTTON,
                "Odczytaj kod ze zdjęcia - kreskowy albo QR - i powiedz krótko, " +
                    "co to za produkt albo co jest w kodzie zapisane.",
                // Bez tego szło przez warstwę 0, a tam wykrywanie komend mogło
                // przechwycić zdanie, zanim w ogóle doszło do aparatu.
                forceVision = true
            )
            ButtonAction.NEW_CONVERSATION -> startNewConversation()
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
     * - **Warstwa 2 - awaria.** Gdy AI jest niedostępne (brak klucza albo -
     *   po nieudanej warstwie 1 - brak sieci u wszystkich dostawców), wracamy
     *   do pełnej detekcji wzorcami [SmartActionDetector.detect]. Lepsza
     *   niedoskonała komenda niż komunikat o błędzie. Patrz
     *   [pl.victor.app.ai.OfflineFallback].
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
        saveAsNote: Boolean = false,
        /**
         * Czy [textQuestion] to polecenie złożone przez aplikację (briefing,
         * notatka z odpowiedzi), a nie słowa użytkownika.
         *
         * Takie polecenie nie może przechodzić przez warstwę 0 ani komendy
         * sterujące: niesie w sobie cudzy materiał (kalendarz, pocztę), a
         * wzorce szukają słów. Bieg 174: briefing zawierał "masz" i "styl",
         * więc zamiast porannego podsumowania asystent wyrecytował listę
         * person - dwa razy pod rząd.
         */
        internalPrompt: Boolean = false
    ) {
        // ROZMOWA NA ŻYWO TRZYMA MIKROFON - żadna tura nie wchodzi. Tura
        // zaczęłaby nasłuch albo mówienie obok sesji Gemini Live, a ta słyszy
        // wszystko, co gra. Z trybu wychodzi się głosem ("koniec rozmowy"),
        // przyciskiem, dotykiem skroni albo na ekranie.
        if (liveConversationOn()) {
            runCatching {
                diag.event(
                    DiagFormat.Phase.AUDIO, "rozmowa na żywo: tura pominięta",
                    mapOf("źródło" to trigger.name, "tekst" to textQuestion.take(60))
                )
            }
            return
        }
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
        val textIsQuestion = audioQuestion == null && !internalPrompt

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
        //
        // DWA OGRANICZENIA (audyt 28.10): potwierdzenie wygasa po
        // [CONFIRMATION_VOICE_TTL_MS] - wiszące godzinę wysyłało zaległy SMS na
        // "zrób zdjęcie" - a wypowiedź musi składać się WYŁĄCZNIE ze słów
        // zgody/odmowy ("dodaj mleko" i "zrób zdjęcie" to polecenia, nie "tak").
        val pending = pendingActionConfirmation.value
        if (pending != null && pending.createdAtMs != 0L &&
            System.currentTimeMillis() - pending.createdAtMs > CONFIRMATION_VOICE_TTL_MS
        ) {
            diag.event(DiagFormat.Phase.AKCJA, "potwierdzenie wygasło - głosem już nie przyjmuję")
        }
        if (textIsQuestion && pending != null &&
            System.currentTimeMillis() - pending.createdAtMs <= CONFIRMATION_VOICE_TTL_MS
        ) {
            // POPRAWKA MAILA - "popraw, napisz że będę o piątej" przy wiszącym
            // "Wysłać?". Przed tak/nie, bo to zdanie zawiera "nie"/"napisz" i
            // poszłoby jako odmowa albo nowe polecenie. Polecenia list ("usuń
            // chleb z listy") mają pierwszeństwo - to nie są uwagi do maila.
            val emailDraft = pending.actions.singleOrNull() as? Action.SendEmail
            val remark = emailDraft?.takeIf {
                pl.victor.app.lists.ListCommands.parse(textQuestion) == null
            }?.let { pl.victor.app.actions.EmailVoice.correctionInNewTurn(textQuestion) }
            if (emailDraft != null && remark != null) {
                actionFlow.reviseEmailDraft(emailDraft, remark)
                return
            }
            val reply = pl.victor.app.actions.ConfirmationReply.parseStrict(textQuestion)
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
        actionFlow.stopListeningForConfirmation()
        // Ta tura NIE była odpowiedzią na pytanie - od teraz "tak" tylko w
        // okienku. Inaczej "dobra, dzięki" po innej rozmowie wykonywało akcję.
        if (textIsQuestion && pending != null) actionFlow.closeVoiceConfirmation()

        // === KOMENDY STERUJĄCE ROZMOWĄ (persona, reset) - zanim cokolwiek innego ===
        // Muszą być sprawdzone przed detekcją akcji: "bądź Sterna" nie pasuje do
        // żadnego wzorca akcji, więc poleciałoby jako zwykłe pytanie do AI.
        if (textIsQuestion && textQuestion.isNotBlank() && handleMetaCommand(textQuestion)) {
            // Komenda meta (przewodnik, spotkanie, krok po kroku, tłumaczenie)
            // kończyła turę bez stanu końcowego: nikt nie uzbrajał ponownie
            // frazy "Hey Lens" w okularach ani nie wznawiał nasłuchu rozmowy.
            // Stan zostaje, jeśli komenda sama go ustawiła (tryb, który trzyma
            // mikrofon - tłumaczenie, spotkanie - pilnuje tego sam).
            if (_state.value is OrchestratorState.Idle && !earTranslation.value && !meetingHoldsMic()) {
                _state.value = OrchestratorState.Completed("")
            }
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
                    actionFlow.handleActions(listOf(custom), textQuestion)
                }
                return
            }

            // ODPOWIEDŹ NA WIADOMOŚĆ PODYKTOWANA DOSŁOWNIE - bez modelu.
            //
            // "Odpowiedz jej, że będę za dziesięć minut" ma iść pewnie i od
            // razu, z treścią dokładnie taką, jak padła. Odpowiedzi do ułożenia
            // ("grzecznie", "po angielsku") parser oddaje modelowi, który ma
            // listę wiadomości i znacznik reply_message.
            pl.victor.app.messages.MessageReplyParser.prośbaBezTreści(textQuestion)?.let { kto ->
                val m = pl.victor.app.messages.MessageInbox.adresat(kto.ifBlank { null })
                val speech = when {
                    !settings.isMessageReadingEnabled() ->
                        "Czytanie wiadomości jest wyłączone. Włącz je w ustawieniach, w sekcji " +
                            "Wiadomości, i daj aplikacji dostęp do powiadomień."
                    m == null && kto.isNotBlank() ->
                        "Nie mam wiadomości od: $kto, na którą da się odpowiedzieć. Odpowiadać mogę " +
                            "tylko na SMS-y i komunikatory, nie na powiadomienia z innych aplikacji."
                    m == null -> "Nie mam teraz żadnej wiadomości, na którą da się odpowiedzieć."
                    else -> "Co mam odpisać? Powiedz na przykład: odpisz, że będę za dziesięć minut."
                }
                runCatching { diag.event(DiagFormat.Phase.AKCJA, "odpowiedź na wiadomość bez treści", mapOf("do" to kto, "jest" to (m != null))) }
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                return
            }
            // Skrzynka musi mieć ŚWIEŻĄ wiadomość - leżąca od rana nie jest
            // powodem, by "odpowiedz mi szczerze..." poszło do kogoś w SMS-ie.
            if (pl.victor.app.messages.MessageInbox.ostatnie(1)
                    .any { System.currentTimeMillis() - it.czasMs < MESSAGE_REPLY_FRESH_MS }
            ) {
                pl.victor.app.messages.MessageReplyParser.parse(textQuestion)?.let { odp ->
                    Log.i(TAG, "Warstwa 0: odpowiedź na wiadomość")
                    diag.event(
                        DiagFormat.Phase.AKCJA, "odpowiedź na wiadomość - dosłownie, bez modelu",
                        mapOf("do" to (odp.adresat ?: "ostatnia"), "znaków" to odp.treść.length)
                    )
                    actionFlow.handleActions(listOf(Action.ReplyMessage(odp.adresat, odp.treść)), textQuestion)
                    return
                }
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

            // PRZYPOMNIENIA W MIEJSCACH ("przypomnij mi kupić mleko, gdy będę w
            // Biedronce") i zapis domu - PRZED notatkami. Bieg 160: notatki
            // łapią każde "przypomnij mi..." i "zapisz...", więc wszystkie trzy
            // prośby o przypomnienie w miejscu skończyły jako zwykłe notatki.
            if (textIsQuestion && placeReminderCommands.obsłuż(textQuestion)) return

            // LISTY ("dodaj mleko do listy zakupów", "co mam na liście zakupów",
            // "odhacz chleb") - PRZED notatkami, bo notatki łapią "dodaj ..." i
            // "lista zakupów ..." i zapisywały zakupy jako zdania w notatniku,
            // z którego nie dało się nic odhaczyć. Bez modelu i za darmo.
            val listCommand = if (textIsQuestion) {
                pl.victor.app.lists.ListCommands.parse(textQuestion)?.takeIf { lists.accepts(it) }
            } else {
                null
            }
            if (listCommand != null) {
                Log.i(TAG, "Warstwa 0: lista (${pl.victor.app.lists.ListCommands.describe(listCommand)})")
                runCatching {
                    diag.event(
                        DiagFormat.Phase.AKCJA, "polecenie listy",
                        mapOf("polecenie" to pl.victor.app.lists.ListCommands.describe(listCommand), "tekst" to textQuestion.take(80))
                    )
                }
                scope.launch {
                    val speech = runCatching { lists.handle(listCommand) }.getOrElse { e ->
                        Log.w(TAG, "Polecenie listy nie powiodło się", e)
                        "Nie udało się zmienić listy."
                    }
                    conversationalMode.onAiStartedSpeaking()
                    audio.speakAndAwait(speech, language = settings.getResponseLanguage())
                    _state.value = OrchestratorState.Completed(speech)
                    conversationalMode.onAiFinishedSpeaking()
                }
                return
            }

            // (Przeniesione PRZED fakty i notatki - audyt 28.10: prefiks
            // "zapamiętaj" zamieniał "zapamiętaj, gdzie zaparkowałem" w fakt
            // "Gdzie zaparkowałem", a "zapisz gdzie..." w notatkę, więc pamięć
            // miejsca w ogóle nie była osiągalna.)
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
                            saveAsNote = true,
                            internalPrompt = true
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
                            saveAsNote = true,
                            internalPrompt = true
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
                    actionFlow.handleActions(listOf(route), textQuestion)
                    return
                }
            }

            // GESTY PRZYCISKU WYPOWIEDZIANE GŁOSEM.
            //
            // Trzy i cztery kliknięcia zgłoszono jako martwe, a dwa działają -
            // więc gest przestał być drogą, na której można cokolwiek opierać.
            // Najdotkliwsze było to, że ReadTextPrompt miał w całej aplikacji
            // DOKŁADNIE JEDNEGO wywołującego: akcję trzech kliknięć. Tłumaczenie
            // napisów, o które poproszono wprost, nie dawało się uruchomić
            // NICZYM.
            //
            // Idzie to przez handleButtonAction, czyli tę samą gałąź co przycisk.
            // Osobna kopia logiki zaczęłaby się rozjeżdżać - a "dwie kopie jednej
            // rzeczy" to w tym projekcie najczęstsza przyczyna usterek.
            if (textIsQuestion) {
                actionDetector.detectGesture(textQuestion)?.let { gest ->
                    Log.i(TAG, "Warstwa 0: gest głosem -> $gest")
                    runCatching {
                        diag.event(
                            DiagFormat.Phase.AKCJA, "gest wywołany głosem",
                            mapOf("gest" to gest::class.simpleName)
                        )
                    }
                    // Tryb bez kamery przestawia znaczenie KLIKNIĘĆ (2 = wiadomości,
                    // 3 = mój dzień), nie słów: "przetłumacz to" powiedziane głosem
                    // to nadal prośba o obraz, nie o briefing.
                    if (settings.isCameraFree() && gest != ButtonAction.QUICK_QUESTION &&
                        gest != ButtonAction.NEW_CONVERSATION
                    ) {
                        audio.speak(CAMERA_OFF_SPEECH, language = settings.getResponseLanguage())
                        _state.value = OrchestratorState.Completed(CAMERA_OFF_SPEECH)
                        return
                    }
                    handleButtonAction(gest)
                    return
                }
            }

            // Tryb bez kamery i motyw - wykonujemy sami, model nie ma czym.
            pl.victor.app.conversation.SettingsVoiceCommands.rozpoznaj(textQuestion)?.let { p ->
                when (p) {
                    is pl.victor.app.conversation.SettingsVoiceCommands.Polecenie.BezKamery -> settings.setCameraFree(p.włącz)
                    is pl.victor.app.conversation.SettingsVoiceCommands.Polecenie.Motyw -> settings.setThemeMode(p.tryb)
                }
                val odp = pl.victor.app.conversation.SettingsVoiceCommands.potwierdzenie(p)
                runCatching { diag.event(DiagFormat.Phase.AKCJA, "ustawienie głosem", mapOf("co" to p.toString())) }
                audio.speak(odp, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(odp)
                return
            }

            // "Ile jeszcze?", "gdzie skręcam?" w trakcie prowadzenia Map -
            // odpowiedź jest w powiadomieniu, model niczego tu nie doda.
            pl.victor.app.navigation.NavPytania.odpowiedź(
                textQuestion, pl.victor.app.navigation.MapsNavigation.aktywna()
            )?.let { odp ->
                runCatching { diag.event(DiagFormat.Phase.AKCJA, "nawigacja: pytanie o trasę", mapOf("odpowiedź" to odp)) }
                audio.speak(odp, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(odp)
                return
            }

            // "Hermes, znajdź…" - zadanie dla agenta na komputerze użytkownika.
            // Przed resztą warstwy 0: jawne wywołanie po imieniu jest mocniejszą
            // deklaracją intencji niż nasze wzorce ("Hermes, zgaś światło" ma
            // pójść do Hermesa, nie do domu).
            if (handleHermes(trigger, textQuestion)) return

            // "Kiedy najbliższy tramwaj?" - przystanki i linie z OpenStreetMap
            // za darmo; z płatnymi Mapami Google pytanie idzie do modelu.
            if (handleTransitDepartures(textQuestion)) return

            // DOM - "włącz światło w salonie" do Home Assistant użytkownika.
            // Przed detectCritical: z włączonym domem "zgaś światło" to lampa w
            // pokoju, a nie latarka (tę i tak łapie tylko słowo "latarkę").
            if (handleHomeCommand(trigger, textQuestion)) return

            val critical = actionDetector.detectCritical(textQuestion)
            if (critical.isNotEmpty()) {
                // "Zrób zdjęcie" nie jest akcją do wykonania przez Intent - to
                // wejście w ścieżkę obrazu, tyle że bez pytania od usera.
                if (critical.any { it.type == pl.victor.app.actions.ActionType.TAKE_PHOTO }) {
                    Log.i(TAG, "Warstwa 0: zdjęcie na komendę")
                    handleUserTrigger(trigger, PHOTO_ON_DEMAND_QUESTION, forceVision = true)
                } else {
                    Log.i(TAG, "Warstwa 0: ${critical.joinToString { it.type.name }}")
                    actionFlow.handleActions(critical, textQuestion)
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
                actionFlow.handleActions(fallback, textQuestion)
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

        // "ZESKANUJ TEN KOD" TO TEŻ PROŚBA O OBRAZ.
        //
        // Dziennik z biegu 152: "zeskanuj ten kod produktu" nie pasował do
        // wzorców widzenia, więc tura szła "bez zdjęcia" i dopiero gałąź kodu
        // robiła migawkę - z pominięciem strumienia, który daje kod w pełnej
        // rozdzielczości. Wyjątek: cyfry podyktowane w samym pytaniu wystarczą,
        // aparat nic do nich nie doda.
        val wantsCode = glassesReady && audioQuestion == null &&
            pl.victor.app.ai.VisionDetail.isAboutCode(textQuestion) &&
            pl.victor.app.vision.EanFromText.find(textQuestion) == null &&
            pl.victor.app.vision.EanFromText.zMowy(textQuestion) == null

        // TRYB BEZ KAMERY: wyraźna prośba o obraz (przycisk, "zrób zdjęcie",
        // notatka z tego, co widać) dostaje krótką odpowiedź zamiast migawki,
        // a wzorce widzenia i kodów nie uruchamiają aparatu w ogóle.
        val kameraWyłączona = settings.isCameraFree()
        if (kameraWyłączona && forceVision) {
            diag.event(DiagFormat.Phase.ZDJĘCIE, "prośba o obraz w trybie bez kamery - nie robię zdjęcia")
            audio.speak(CAMERA_OFF_SPEECH, language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Completed(CAMERA_OFF_SPEECH)
            return
        }
        val useVision = !kameraWyłączona &&
            (forceVision || trigger == TriggerSource.BUTTON || wantsToLook || wantsCode)
        diag.event(
            DiagFormat.Phase.ZDJĘCIE, if (useVision) "robię zdjęcie" else "bez zdjęcia",
            mapOf(
                "okularyGotowe" to glassesReady,
                "wzorzecWidzenia" to wantsToLook,
                "kod" to wantsCode,
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

        // Mikrofon frazy bierzemy JUŻ TUTAJ, synchronicznie, a nie na początku
        // korutyny: tura głosowa, która przekazuje pytanie, kończy się zaraz po
        // tym wywołaniu i w swoim `finally` oddaje mikrofon. Gdyby nowa tura
        // jeszcze go nie trzymała, nasłuch frazy wstawał na ułamek sekundy
        // między jedną a drugą - przy każdym pytaniu głosem.
        val micHold = java.util.concurrent.atomic.AtomicBoolean(true)
        holdMicForTurn()
        val releaseMic = { if (micHold.compareAndSet(true, false)) releaseMicAfterTurn() }
        // Nazwa blokady na turę - patrz ten sam zabieg w turze głosowej.
        // Powtórka ze zdjęciem brała blokadę "Tura", a `finally` tury, która ją
        // zleciła, zwalniało ją chwilę później.
        val lockTurn = "$LOCK_TURN#${turnSeq.incrementAndGet()}"
        val turnJob = launchTurn {
            val myToken = turnMarker.get()
            // Powtórka ze zdjęciem to DALSZY CIĄG tego samego pytania użytkownika,
            // tylko z drugim obiegiem modelu. Bez tej flagi `finally` zamykało
            // turę zanim powtórka zdążyła ruszyć, a że zamknięcie zeruje licznik,
            // najdłuższa i najciekawsza tura zapisywała się w dzienniku BEZ
            // czasów - czyli ginął dokładnie ten pomiar, dla którego dziennik
            // powstał. Turę zamyka wtedy `finally` powtórki.
            var handedOffToRetry = false
            // Zasoby brane PRZED głównym try (blokada, mikrofon frazy, łącze
            // audio) muszą być zwalniane także wtedy, gdy tura zostanie
            // anulowana w trakcie negocjowania SCO (audyt 28.10) - dlatego try
            // zaczyna się tutaj, a nie dopiero pod "1. CAPTURE".
            var audioHeld = false
            try {
            // Łącze audio do okularów bierzemy na CAŁĄ turę - i na słuchanie, i
            // na mówienie. Zestawienie SCO trwa nawet kilka sekund, więc
            // podnoszenie go osobno pod każdy fragment rwałoby rozmowę.
            // Zwraca false, gdy okulary nie są sparowane jako zestaw audio -
            // wtedy wszystko idzie przez telefon, tak jak dotąd.
            // Tura z modelem bywa dłuższa niż nasłuch: zdjęcie, kontekst,
            // odpowiedź i jej odczytanie. Bez blokady przy zgaszonym ekranie
            // potrafi utknąć w połowie.
            wakeLock.acquire(lockTurn, TURN_WAKE_LOCK_MS)
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
            // Tryb multimediów jest - liczy się jako sukces także bez prośby
            // (zeruje serię porażek i chowa podpowiedź na ekranie głównym).
            if (canUseMedia) glassesManager.noteMediaModeAvailable()
            if (!canUseMedia) {
                // Bierzemy SCO, bo trzeba - ale przy okazji prosimy okulary,
                // żeby wróciły do trybu multimediów. Inaczej raz utracony A2DP
                // nie wraca nigdy: każda tura bierze profil rozmowy, a profil
                // rozmowy nie pozwala A2DP wstać.
                glassesManager.requestClassicAudio("brak A2DP przed odpowiedzią")
            }
            // Bez czekania na faktyczne wstanie łącza: mówić zaczniemy dopiero
            // po odpowiedzi modelu, a do tego czasu łącze zdąży wstać samo.
            audioHeld = if (canUseMedia) false else audio.beginConversationRouting(waitForLink = false)

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
                mapOf(
                    "a2dp" to canUseMedia,
                    "scoStoi" to audio.isRoutedToBluetooth(),
                    // Czy telefon w ogóle ma połączony profil multimediów -
                    // rozstrzyga, skąd bierze się lepki stan "tylko rozmowa".
                    "profile" to audio.bluetoothProfileStates()
                )
            )
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
                val streamFrame = if (useVision && glassesManager.isLiveVisionRunning && codeStream.codeStreamWarmup == null) {
                    val wantsText = pl.victor.app.ai.VisionDetail.needsDetail(textQuestion)
                    glassesManager.liveFrame(detail = wantsText)?.also { frame ->
                        diag.event(
                            DiagFormat.Phase.ZDJĘCIE, "klatka ze strumienia zamiast migawki",
                            mapOf("bajtów" to frame.size, "szczegół" to wantsText)
                        )
                    }?.let { frame ->
                        if (pl.victor.app.ai.VisionDetail.isAboutCode(textQuestion)) codeStream.scanStreamForCode(frame) else frame
                    }
                } else {
                    null
                } ?: codeStream.streamFrameForText(useVision, textQuestion)

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
                    return@launchTurn
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
                var sharpForCode: ByteArray? = null
                // Klatka ze strumienia JEST pełną rozdzielczością - a flaga z
                // VictorManager mówi o ostatnim ZDJĘCIU, czyli o czymś sprzed
                // tej tury. Dziennik z biegu 152: kod nie wyszedł z klatki
                // 1600x1200, flaga pamiętała miniaturę, więc aplikacja szła po
                // ostre zdjęcie przez Wi-Fi okularów - 30 s i znów miniatura.
                val photosSharp = streamFrame != null || glassesManager.lastPhotoWasFullResolution
                if (asksAboutCode && scannedCodes.isEmpty() && !photosSharp) {
                    Log.i(TAG, "Kod nieodczytany z miniatury - próbuję na pełnym zdjęciu")
                    _state.value = OrchestratorState.Capturing(
                        progress = 1,
                        total = 1,
                        label = "Nie widzę kodu na podglądzie - pobieram ostrzejsze zdjęcie."
                    )
                    // forCode = true: TA PRÓBA DOTĄD ZWRACAŁA GORSZĄ MINIATURĘ.
                    //
                    // Z dziennika z 23 września, każda tura o kod: pierwsze
                    // zdjęcie 36 kB, "ostrzejsze" 19 kB. `captureSharpPhoto`
                    // słuchało ustawienia "źródło zdjęcia: miniatura" i oddawało
                    // miniaturę, zanim w ogóle sięgnęło po Wi-Fi - do tego z
                    // domyślną, NIŻSZĄ jakością (2 zamiast 5). Czyli circa 10 s
                    // na zdjęcie gorsze od tego, które już było.
                    //
                    // Użytkownik zgadł dokładnie: "chyba dostaje miniaturę, choć
                    // Wi-Fi jest włączone". Wi-Fi nie miało nic do rzeczy -
                    // aplikacja w ogóle go nie próbowała.
                    //
                    // Ustawienie jest o SZYBKOŚCI zwykłych pytań i dla nich
                    // zostaje. Kodu kreskowego z miniatury nie odczyta żadna
                    // biblioteka, więc tu ustawienie nie ma prawa decydować.
                    sharpForCode = glassesManager.captureSharpPhoto(
                        quality = CODE_PHOTO_QUALITY,
                        forCode = true
                    )
                    sharpForCode?.let { scanInto(it) }
                }

                if (scannedCodes.isNotEmpty()) {
                    Log.i(TAG, "Wykryto ${scannedCodes.size} kod(ów): ${scannedCodes.map { it.format }}")
                } else if (asksAboutCode) {
                    Log.w(TAG, "Pytanie o kod, ale żadnego nie odczytano")
                    // DO DZIENNIKA, NIE TYLKO DO LOGCATA.
                    //
                    // Zgłoszone: "poprosiłem o zweryfikowanie kodu kreskowego,
                    // zrobił chyba 5 zdjęć i nie sprawdził jej w openfood".
                    // Są trzy różne powody, dla których baza nie zostaje
                    // odpytana, i z zewnątrz wyglądają identycznie: kodu nie
                    // odczytano wcale, odczytano kod INNEGO typu niż EAN, albo
                    // odczytano i produktu nie ma w bazie. Ostatnie dwa miały
                    // już swój wpis, pierwszy szedł wyłącznie do logcata -
                    // czyli donikąd, bo nikt go z telefonu nie odczyta.
                    runCatching {
                        diag.event(
                            DiagFormat.Phase.ZDJĘCIE,
                            "pytanie o kod, ale ŻADNEGO nie odczytano",
                            mapOf(
                                "zdjęć" to photos.size,
                                "pełnaRozdzielczość" to photosSharp,
                                "źródło" to if (streamFrame != null) "strumień" else "migawka"
                            )
                        )
                    }
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
                var dietContext: String? = null
                // UPC TEŻ JEST KODEM PRODUKTU - I BYŁ TU POMIJANY.
                //
                // Filtr przepuszczał wyłącznie EAN_13 i EAN_8. UPC-A to ten sam
                // kod co EAN-13 z wiodącym zerem (tak trzyma go Open Food
                // Facts), a UPC-E to jego skrócony zapis - towary spoza Europy
                // mają na opakowaniu właśnie je. Kod dawał się odczytać i
                // kończył jako trzynaście cyfr przeczytanych na głos, czyli
                // informacja zerowa. Patrz [pl.victor.app.vision.ProductCode].
                val fromBars = scannedCodes.firstOrNull {
                    pl.victor.app.vision.ProductCode.toKodProduktu(it.format) != null
                }
                // CYFRY POD KRESKAMI, GDY KRESEK NIE DAŁO SIĘ ODCZYTAĆ.
                //
                // Z dziennika z 23 września: ML Kit nie odczytał kresek ANI
                // RAZU na kilkanaście prób, a model na tych samych zdjęciach
                // widział "puszkę kukurydzy Bonduelle, kod w pełni widoczny".
                // Cyfry pod kodem są kilkukrotnie większe od kresek, więc
                // rozpoznawanie tekstu czyta je z tej samej miniatury. Cyfra
                // kontrolna EAN pilnuje, żeby to nie była data ani cena - patrz
                // [pl.victor.app.vision.EanFromText].
                //
                // Pierwsze źródło to samo PYTANIE: człowiek może przeczytać
                // cyfry na głos ("sprawdź produkt 5 901234 123457") i to jest
                // droga, która działa zawsze, niezależnie od aparatu.
                var eanZródło: String? = null
                // Pytanie sprawdzamy ZAWSZE, nie tylko przy "zeskanuj kod":
                // "sprawdź produkt 5 901234 123457" nie zawiera słowa "kod", a
                // jest najpewniejszą drogą ze wszystkich. Fałszywych trafień z
                // numerów telefonów i dat pilnuje układ druku w EanFromText.
                val fromDigits: String? = if (fromBars == null) {
                    (pl.victor.app.vision.EanFromText.find(textQuestion)
                        ?: pl.victor.app.vision.EanFromText.zMowy(textQuestion))
                        ?.also { eanZródło = "pytanie" }
                        ?: if (asksAboutCode) {
                            (photos + listOfNotNull(sharpForCode)).firstNotNullOfOrNull { zdjęcie ->
                                runCatching { ocrReader.readBytes(zdjęcie) }.getOrNull()
                                    ?.takeIf { it.isSuccess }
                                    ?.let { pl.victor.app.vision.EanFromText.find(it.fullText) }
                            }?.also { eanZródło = "cyfry pod kodem (OCR)" }
                        } else {
                            null
                        }
                } else {
                    null
                }
                if (fromDigits != null) {
                    diag.event(
                        DiagFormat.Phase.ZDJĘCIE, "kod produktu odczytany z cyfr",
                        mapOf("kod" to fromDigits, "skąd" to eanZródło)
                    )
                }
                val productCode = fromBars ?: fromDigits?.let {
                    ScannedCode(rawValue = it, format = if (it.length == 8) "EAN_8" else "EAN_13")
                }
                if (productCode != null &&
                    pl.victor.app.vision.ProductCode.jestWewnętrzny(productCode.rawValue)
                ) {
                    // Kodu sklepu nie ma w żadnej bazie - pytanie Open Food
                    // Facts to tylko strata czasu. Co z nim zrobić, mówi
                    // modelowi CodeScanReport.
                    diag.event(
                        DiagFormat.Phase.ZDJĘCIE, "kod wewnętrzny sklepu - nie szukam w bazie",
                        mapOf("kod" to productCode.rawValue)
                    )
                } else if (productCode != null) {
                    val doWyszukania =
                        pl.victor.app.vision.ProductCode.znormalizuj(productCode.rawValue)
                    productLookup.info(doWyszukania)?.let { info ->
                        val described = info.opis
                        productContext = described
                        // Dieta - lokalnie, na danych, które już są: zero
                        // dodatkowego czasu odpowiedzi. Patrz DietCheck.
                        val ostrzeżenia = pl.victor.app.vision.DietCheck.ostrzeżenia(
                            info, settings.getFacts().map { it.text }
                        )
                        dietContext = pl.victor.app.vision.DietCheck.dlaModelu(ostrzeżenia)
                        if (ostrzeżenia.isNotEmpty()) {
                            diag.event(
                                DiagFormat.Phase.ZDJĘCIE, "produkt kłóci się z dietą",
                                mapOf("ostrzeżenia" to ostrzeżenia.joinToString(" | ").take(200))
                            )
                        }
                        diag.event(
                            DiagFormat.Phase.ZDJĘCIE, "produkt rozpoznany z kodu",
                            mapOf("kod" to productCode.rawValue, "opis" to described)
                        )
                    } ?: diag.event(
                        DiagFormat.Phase.ZDJĘCIE, "kodu nie ma w bazie produktów",
                        mapOf("kod" to doWyszukania, "format" to productCode.format)
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
                    settings.isCameraFree() ->
                        "\n\nOBRAZ: użytkownik wyłączył kamerę w ustawieniach (tryb bez " +
                            "kamery). Nie używaj take_photo i nie proponuj zdjęć. Gdy pyta o to, " +
                            "co widać, powiedz krótko, że kamera jest wyłączona w ustawieniach."
                    photos.isNotEmpty() || (video != null && video.isNotEmpty()) ->
                        // "Zrobione przed chwilą" jest tu kluczowe. Dziennik
                        // z 18:29 (tura 2011): "zrób zdjęcie tej wódki i..."
                        // wróciło do modelu ze zdjęciem, a model odpowiedział
                        // "nie mogę zrobić zdjęcia, nie mam dostępu do kamery".
                        // Czytał prośbę w pytaniu jako zadanie dla siebie.
                        "\n\nOBRAZ: masz zdjęcie z kamery okularów w tej wiadomości - " +
                            "aplikacja zrobiła je przed chwilą właśnie na potrzeby " +
                            "tego pytania. Jeśli user prosi \"zrób zdjęcie\", to " +
                            "zdjęcie JUŻ jest zrobione i to jest właśnie ono - nie " +
                            "mów, że nie masz dostępu do kamery. Odpowiadaj na jego " +
                            "podstawie i NIE proś o kolejne."
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
                    pl.victor.app.ai.WebSearchPrompt.dlaModelu(
                        maWyszukiwarkę = provider.supportsWebSearch && settings.isWebSearchEnabled()
                    ) +
                    (
                        if (provider.supportsWebSearch && settings.isWebSearchEnabled() &&
                            pl.victor.app.ai.WebSearchPrompt.wymagaSzukania(textQuestion)
                        ) {
                            pl.victor.app.ai.WebSearchPrompt.WYMUSZENIE
                        } else {
                            ""
                        }
                    ) +
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
                val timeContext = modelContext.buildTimeContext()

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
                val notesContext = modelContext.buildNotesContext(textQuestion, force = audioTurn)
                val messagesContext = messages.kontekst(textQuestion, wymuszone = audioTurn)
                val contactsContext = runCatching { modelContext.buildContactsContext(textQuestion) }.getOrNull()

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
                val memoryDeferred = async { runCatching { modelContext.buildMemoryContext(textQuestion, notesInPrompt = notesContext != null, audioTurn = audioTurn) }.getOrNull() }
                val calendarDeferred =
                    async { runCatching { modelContext.buildCalendarContext(textQuestion, audioTurn) }.getOrNull() }
                val gmailDeferred =
                    async { runCatching { modelContext.buildGmailContext(textQuestion, audioTurn) }.getOrNull() }
                val weatherDeferred =
                    async { runCatching { modelContext.buildWeatherContext(textQuestion, audioTurn) }.getOrNull() }
                // Gdzie jesteśmy - przy pytaniach ZE ZDJĘCIEM (model widzi
                // "kościół", a z położeniem wie, KTÓRY) i przy pytaniach O
                // OKOLICĘ ("gdzie tu zjeść", "co ciekawego w pobliżu"). Patrz
                // buildLocationContext.
                val locationDeferred = async {
                    runCatching { modelContext.buildLocationContext(textQuestion, photos.isNotEmpty()) }.getOrNull()
                }
                val translationDeferred =
                    async { runCatching { modelContext.translateOcrIfRequested(textQuestion, ocrContext) }.getOrNull() }

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
                    if (messagesContext != null) {
                        append(messagesContext)
                        append("\n\n")
                    }
                    pl.victor.app.navigation.MapsNavigation.kontekstDlaModelu()?.let { append(it).append("\n\n") }
                    contactsContext?.let { append(it).append("\n\n") }
                    steps.kontekst()?.let { append(it).append("\n") }
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
                        append("odpowiedz na ich podstawie. Jeśli pyta o cenę albo gdzie ")
                        append("kupić taniej - wyszukaj aktualne ceny tego produktu (nazwa, ")
                        append("marka, gramatura) w polskich sklepach i podaj konkretne kwoty.")
                        append("\n\n")
                    }
                    (
                        dietContext
                            ?: pl.victor.app.vision.DietCheck.regułaDlaModelu(settings.getFacts().map { it.text })
                        )?.let { append(it).append("\n\n") }
                    // CO APLIKACJA ZROBIŁA Z KODEM - ŻEBY MODEL NIE ZGADYWAŁ.
                    //
                    // Z dziennika z 23 września: "kod zasłonięty", "kod
                    // niewyraźny", "nie mam jak sprawdzić informacji o
                    // produkcie" - o wyraźnym kodzie na odsłoniętej puszce, przy
                    // aplikacji, która ma bazę produktów. Model nie wiedział,
                    // że dostał miniaturę, ani że baza istnieje.
                    pl.victor.app.vision.CodeScanReport.dlaModelu(
                        pytanieOKod = asksAboutCode || productCode != null,
                        kodProduktu = productCode?.rawValue,
                        produktZnaleziony = productContext != null,
                        pełnaRozdzielczość = streamFrame != null || glassesManager.lastPhotoWasFullResolution,
                        powódMiniatury = if (streamFrame != null) null else glassesManager.lastSharpFallbackReason
                    )?.let { raport ->
                        append(raport)
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
                audio.beginStream(language = language)
                // Czy cokolwiek poszło na głos w trakcie generowania - decyduje,
                // czy na końcu CZEKAMY na syntezator, czy dopiero go prosimy.
                var spokenWhileStreaming = false
                val useVideoStream = video != null && video.isNotEmpty() && capabilities.supportsVideo

                // Buduje strumień dla danego providera - wywoływane raz na próbę,
                // bo Flow jest leniwy (błąd połączenia wyskakuje dopiero na collect()).
                fun buildStreamWith(p: AIProvider, effectiveSystemPrompt: String) = if (useVideoStream) {
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
                fun buildStream(p: AIProvider): kotlinx.coroutines.flow.Flow<pl.victor.app.ai.AIResponseChunk> {
                    (p as? pl.victor.app.ai.GeminiProvider)?.mapsLocation = modelContext.mapsLocationForTurn
                    // Prompt mówi "masz wyszukiwarkę Google" wg GŁÓWNEGO dostawcy. Gdy
                    // odpowiada zapasowy bez wyszukiwarki, model dalej to słyszał i
                    // zmyślał "aktualne" ceny - dopisujemy prawdę dla tej próby.
                    val prompt = if (p.supportsWebSearch && settings.isWebSearchEnabled()) {
                        effectiveSystemPrompt
                    } else {
                        effectiveSystemPrompt + pl.victor.app.ai.WebSearchPrompt.dlaModelu(maWyszukiwarkę = false)
                    }
                    return buildStreamWith(p, prompt)
                }

                // Cache jest zawsze kluczowany providerem wybranym w Ustawieniach, nie
                // tym, który faktycznie odpowiedział - inaczej trafienie w cache po
                // fallbacku nigdy by się nie powtórzyło (kolejne zapytanie sprawdza
                // cache pod aktywnym providerem, nie pod tym z fallbacku).
                val cacheProviderId = settings.getActiveProvider()
                // Język odpowiedzi w kluczu: to samo pytanie po zmianie języka
                // miało dostać zapamiętaną odpowiedź w poprzednim.
                val cacheModelId = (settings.getSelectedModel(cacheProviderId) ?: "default") +
                    "|" + settings.getResponseLanguage()
                // Nagranie nie jest kluczem cache'a: dwa różne pytania mają tę
                // samą instrukcję tekstową, więc trafienie byłoby czystym
                // przypadkiem - i odpowiedzią na cudze pytanie.
                // W trwającej rozmowie odpowiedź zależy od tego, co padło
                // wcześniej: "dlaczego?", "a po angielsku?", "ile to kosztuje?"
                // z pamięci dostawałyby odpowiedź z INNEJ rozmowy.
                val cacheEligible = aiCache.shouldCache(textQuestion) &&
                    !pl.victor.app.proactive.NearbyQuestion.dotyczyOkolicy(textQuestion) &&
                    !pl.victor.app.ai.WebSearchPrompt.wymagaSzukania(textQuestion) &&
                    photos.isEmpty() && video == null && audioQuestion == null &&
                    conversationContext.size() == 0

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
                    providers.fallbackProviderOrder(needsAudio = audioQuestion != null, needsImages = photos.isNotEmpty())
                } else {
                    listOf(settings.getActiveProvider())
                }

                var attemptIndex = 0
                val modelStartedAt = System.currentTimeMillis()
                // Treści błędów kolejnych prób - po to, żeby po ostatniej
                // rozstrzygnąć, czy WSZYSCY padli z braku sieci (patrz
                // [pl.victor.app.ai.OfflineFallback]).
                val attemptFailures = mutableListOf<String?>()
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
                    // Po identyfikatorze, nie po numerze próby: kolejka stawia
                    // dostawcę z martwym modelem NA KOŃCU, więc pierwsza próba
                    // bywa innym dostawcą niż główny. Numer próby wysyłał wtedy
                    // pytanie do martwego modelu głównego, podpisując to w
                    // dzienniku (i w odstawieniu) nazwą następnego w kolejce.
                    val attemptProvider = if (attemptProviderId == providers.currentProviderId) {
                        provider
                    } else {
                        providers.buildProviderForFallback(attemptProviderId)
                    }
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
                                    modelStartedAt,
                                    // KTO to powiedział i CO powiedział.
                                    //
                                    // Zgłoszone: "dawał odpowiedzi np. do połowy,
                                    // a potem mówił jakieś alfa signal and the
                                    // title". Z dotychczasowego wpisu dało się
                                    // odczytać wyłącznie DŁUGOŚĆ odpowiedzi, więc
                                    // bełkot i sensowne zdanie wyglądały
                                    // identycznie - a to są dwie zupełnie różne
                                    // awarie i dwie różne naprawy.
                                    //
                                    // Bez nazwy dostawcy przy TEJ odpowiedzi
                                    // wskazanie winnego to zgadywanie.
                                    // Początek tekstu rozstrzyga jedno i drugie.
                                    mapOf(
                                        "dostawca" to attemptProviderId,
                                        "początek" to accumulatedText.toString()
                                            .trim().take(80)
                                    )
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
                        // PRZERWANA TURA TO NIE AWARIA DOSTAWCY.
                        //
                        // Dziennik z 18:37 (tura 28d1): użytkownik przerwał,
                        // a pętla uznała to za trzy awarie z rzędu - Gemini
                        // "odmówił", potem DeepSeek, potem ówczesny model
                        // lokalny, z zapowiedzią przejścia na niego już po
                        // przerwaniu. Sprawdzenie typu wyjątku nie wystarcza:
                        // Gemini opakowuje anulowanie we własny wyjątek
                        // ("Streaming error: StandaloneCoroutine was
                        // cancelled"). Rozstrzyga stan korutyny - gdy jest
                        // anulowana, to rzuca i kończy turę bez kolejnych prób.
                        currentCoroutineContext().ensureActive()
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
                        // NIEISTNIEJĄCY MODEL ZAPAMIĘTUJEMY NA CAŁĄ SESJĘ.
                        //
                        // Nazwa modelu, której dostawca nie zna, nie zacznie
                        // istnieć za minutę. Bez tego każda tura zaczynała się
                        // od tej samej odmowy - w dzienniku użytkownika
                        // dwadzieścia kilka razy pod rząd, za każdym razem z
                        // opóźnieniem doliczonym do odpowiedzi.
                        if (pl.victor.app.ai.ProviderFailure.isMissingModel(e.message)) {
                            val martwyModel = settings.getSelectedModel(attemptProviderId)
                            val pierwszyRaz = providers.deadModels.zapamiętaj(attemptProviderId, martwyModel)
                            diag.event(
                                DiagFormat.Phase.MODEL, "model nie istnieje - odstawiam go na tę sesję",
                                mapOf(
                                    "dostawca" to attemptProviderId,
                                    "model" to martwyModel,
                                    "pierwszyRaz" to pierwszyRaz
                                )
                            )
                            // POWIEDZIEĆ RAZ, A NIE MILCZEĆ I NIE POWTARZAĆ.
                            //
                            // Podmiana dostawcy udaje się po cichu, więc bez
                            // tego zdania człowiek nie ma skąd wiedzieć, że w
                            // ustawieniach siedzi martwa nazwa - a to naprawa
                            // na dziesięć sekund. Powtarzanie co turę byłoby
                            // za to nie do zniesienia, stąd `pierwszyRaz`.
                            if (pierwszyRaz && firstChunk) {
                                audio.speak(
                                    "Model $martwyModel nie istnieje u tego dostawcy. " +
                                        "Zmień go w Ustawieniach - na razie pytam kolejnego.",
                                    language = settings.getResponseLanguage()
                                )
                            }
                        }
                        attemptFailures += e.message
                        // Bezpieczne do ponowienia tylko, gdy nic jeszcze nie zostało
                        // powiedziane - inaczej user usłyszałby dwa zaczątki odpowiedzi.
                        val canRetryWithNext = firstChunk && attemptIndex < candidates.lastIndex
                        if (canRetryWithNext) {
                            Log.w(TAG, "Provider $attemptProviderId zawiódł przed pierwszym fragmentem " +
                                "(próba ${attemptIndex + 1}/${candidates.size}), próbuję kolejnego", e)
                            attemptIndex++
                        } else if (firstChunk &&
                            pl.victor.app.ai.OfflineFallback.allFailedOffline(attemptFailures)
                        ) {
                            // === BEZ SIECI: WARSTWA 2 ZAMIAST MODELU LOKALNEGO ===
                            //
                            // Wcześniej kolejka kończyła się modelem lokalnym. Na
                            // Galaxy A23 wczytywał się 13,8 s, liczył 19 s i nie
                            // oddał ani tokena - pół minuty ciszy. Teraz: ten sam
                            // awaryjny detektor poleceń co przy braku klucza (timer,
                            // budzik, telefon do kontaktu rozpoznaje tylko on), a gdy
                            // nic nie pasuje - krótko, że nie ma internetu i co
                            // działa bez niego. Te same strażniki co warstwa 0:
                            // instrukcja dla modelu (nagranie, prompt wewnętrzny,
                            // powtórka ze zdjęciem) to nie słowa użytkownika.
                            val offlineActions = if (!forceVision && textIsQuestion) {
                                actionDetector.detect(textQuestion)
                            } else emptyList()
                            diag.event(
                                DiagFormat.Phase.MODEL, "brak sieci u wszystkich dostawców",
                                mapOf(
                                    "prób" to attemptFailures.size,
                                    "poMs" to (System.currentTimeMillis() - modelStartedAt),
                                    "poleceniaBezSieci" to offlineActions
                                        .joinToString(",") { it.type.name }.ifBlank { null },
                                    "pytanieTekstem" to textIsQuestion
                                )
                            )
                            if (offlineActions.isNotEmpty()) {
                                Log.i(TAG, "Warstwa 2 (brak sieci): ${offlineActions.joinToString { it.type.name }}")
                                _state.value = OrchestratorState.Completed("")
                                actionFlow.handleActions(offlineActions, textQuestion)
                            } else {
                                _state.value = OrchestratorState.Error(pl.victor.app.ai.OfflineFallback.SPEECH)
                                announceTurnFailure(trigger, pl.victor.app.ai.OfflineFallback.SPEECH)
                                // Jak w bloku catch niżej: błąd nie może zostawić
                                // trybu konwersacyjnego głuchym na stałe.
                                conversationalMode.onAiFinishedSpeaking()
                            }
                            return@launchTurn
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
                // W trybie bez kamery prośbę modelu o obraz po prostu pomijamy -
                // polecenie mówi mu, że kamery nie ma, ale model bywa uparty.
                val wantsPhoto = !settings.isCameraFree() && aiDetectedActions.any {
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
                            audioQuestion = audioQuestion,
                            // "...i zrób z tego notatkę" przy pytaniu, które
                            // wymaga zdjęcia - bez tego notatka ginęła właśnie
                            // na powtórce z obrazem.
                            saveAsNote = saveAsNote
                        )
                        return@launchTurn
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
                    actionFlow.handleActions(executableActions, textQuestion)
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
                    // Nowa rozmowa dostaje wektor w tle (a przycięte znikają z indeksu).
                    semanticMemory.requestSync("nowa rozmowa")
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
                // Sprzątanie to już nie tura: znacznik zwalniamy od razu, żeby
                // nowe wywołanie nie czekało na zwinięcie łącza audio. Gdy
                // znacznik jest już cudzy, tę turę przejęło nowe wywołanie.
                val superseded = activeTurnToken.let { it != null && it !== myToken }
                if (activeTurnToken === myToken) activeTurnToken = null
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
                    // NonCancellable - patrz ten sam blok w turze głosowej.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        audio.endConversationRouting()
                    }
                    runCatching {
                        diag.event(
                            DiagFormat.Phase.AUDIO, "zwinięte łącze audio tury",
                            mapOf("ms" to (System.currentTimeMillis() - teardownStartedAt))
                        )
                    }
                }
                // Tura szła bez A2DP. Gdy SCO zejdzie (jeśli w ogóle stało),
                // poprosimy o A2DP w spokoju - inaczej każda następna tura
                // znowu idzie gorszą drogą (patrz VictorManager). Także bez
                // SCO: w biegu 154 telefon nie widział okularów jako
                // urządzenia audio WCALE i prośba po turze nie szła.
                if (glassesManager.isConnected() && !audio.canSpeakOverMedia()) {
                    glassesManager.requestClassicAudioWhenIdle("po turze bez trybu multimediów")
                }
                wakeLock.release(lockTurn)
                releaseMic()
                // Tura PRZEJĘTA przez nowe wywołanie nie zamyka dziennika: ten
                // należy już do nowej tury, a zamknięcie zerowało jej licznik
                // czasu - nowe pytanie zapisywało się bez pomiarów.
                if (!handedOffToRetry && !superseded) {
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
        // Tura anulowana, ZANIM jej korutyna ruszyła, nie ma `finally` -
        // mikrofon frazy zostałby wzięty na zawsze. Po zwykłym końcu to nic
        // nie robi (zwolnienie jest jednorazowe).
        turnJob.invokeOnCompletion { releaseMic() }
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
                    runCatching { modelContext.buildCalendarContext("", force = true) }.getOrNull()
                } else null
            }
            val weatherDeferred = async {
                if (preferences.includeWeather) {
                    runCatching { modelContext.buildWeatherContext("", force = true) }.getOrNull()
                } else null
            }
            val mailDeferred = async {
                if (preferences.includeMail) {
                    runCatching { modelContext.buildGmailContext("", force = true) }.getOrNull()
                } else null
            }
            val material = pl.victor.app.proactive.DailyBriefing.Material(
                calendar = calendarDeferred.await(),
                weather = weatherDeferred.await(),
                mail = mailDeferred.await()
            )

            val prompt = pl.victor.app.proactive.DailyBriefing.buildPrompt(material, preferences)
            diag.event(
                DiagFormat.Phase.SESJA,
                "mój dzień",
                mapOf(
                    "kalendarz" to (material.calendar != null),
                    "pogoda" to (material.weather != null),
                    "poczta" to (material.mail != null),
                    "jestOCzymMówić" to (prompt != null)
                )
            )
            if (prompt == null) {
                // Kafelek "Mój dzień" bez żadnej reakcji wyglądał na zepsuty
                // (dziennik 22.11: bez sieci nic nie przyszło, a aplikacja
                // milczała). Zawsze odpowiadamy - krótko i z powodem.
                Log.i(TAG, "Briefing pominięty - nie ma o czym mówić")
                val maSieć = runCatching {
                    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager
                    cm.getNetworkCapabilities(cm.activeNetwork)
                        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                }.getOrDefault(true)
                val powód = if (!maSieć) {
                    "Nie mam teraz internetu, więc nie pobiorę kalendarza, pogody ani poczty."
                } else {
                    "Nie mam dziś nic do podsumowania: kalendarz, pogoda i poczta nic nie zwróciły."
                }
                audio.speak(powód, language = settings.getResponseLanguage())
                return@launch
            }
            handleUserTrigger(TriggerSource.TEXT_INPUT, prompt, internalPrompt = true)
        }
    }

    private val lowBattery = pl.victor.app.conversation.LowBatteryWatch()

    /** Film, bateria, pliki i głośność na głos - patrz [pl.victor.app.orchestrator.GlassesCommandHandler]. */
    private val glassesCommands = pl.victor.app.orchestrator.GlassesCommandHandler(
        settings = settings,
        scope = scope,
        audio = audio,
        glassesManager = glassesManager,
        conversationalMode = conversationalMode,
        state = _state,
        dziennik = { diag },
        ciszaWTle = { ciszaWTle() }
    )

    /** Kafelek "Zakończ" przy filmie na ekranie głównym - ta sama droga co głos. */
    fun stopVideoFromUi() = glassesCommands.stopVideoFromUi()

    /**
     * Kafelek "Wiadomości": nowe wiadomości na głos - przez zwykłą turę, bo
     * model ma już kontekst skrzynki i umie streścić kilka naraz.
     */
    fun readNewMessages() {
        handleUserTrigger(TriggerSource.TEXT_INPUT, READ_MESSAGES_PROMPT)
    }

    private fun handleMetaCommand(text: String): Boolean {
        // Briefing jest komendą META, nie akcją: nie uruchamia niczego przez
        // Intent, tylko układa wypowiedź z danych, które i tak już zbieramy.
        if (pl.victor.app.proactive.DailyBriefing.isBriefingRequest(text)) {
            Log.i(TAG, "Komenda briefingu: \"$text\"")
            runBriefing()
            return true
        }

        // TŁUMACZENIE ZE SŁUCHU - WŁĄCZANE LOKALNIE, PRZED MODELEM.
        //
        // Ten tryb ma wejść bez obiegu przez sieć z tego samego powodu, dla
        // którego wchodzi tak Shazam: model zapytany o czynność ODPOWIADA NA
        // NIĄ SŁOWAMI. "Włącz tłumaczenie na żywo" wróciłoby zapowiedzią, że
        // może włączyć - i to jest dokładnie ta klasa błędu, którą użytkownik
        // zgłaszał już przy trasie i przy Shazamie.
        //
        // Sprawdzane PRZED hamulcem trybów ciągłych, bo zdania "wyłącz
        // tłumaczenie" nie ma w żadnym z nich - o wyjściu z trybu rozstrzyga
        // [pl.victor.app.translation.EarTranslation.toKoniec] wewnątrz pętli,
        // na tekście usłyszanym już po włączeniu.
        // Tryb przewodnika - lokalnie, bez modelu, z tego samego powodu co
        // tłumaczenie: zapytany o czynność model odpowiada na nią słowami.
        // GŁOŚNOŚĆ - lokalnie, bo model zapytany o czynność odpowiada na nią
        // słowami, a głośności nie ruszy.
        pl.victor.app.conversation.VolumeCommand.parse(text)?.let { komenda ->
            glassesCommands.applyVolumeCommand(komenda)
            runCatching { diag.endTurn("głośność") }
            return true
        }

        // KOMENDY SAMYCH OKULARÓW - film, bateria, liczba plików. Przed
        // spotkaniem, bo "zakończ nagrywanie" przy trwającym filmie dotyczy filmu.
        pl.victor.app.conversation.GlassesCommand.parse(text)?.let { komenda ->
            if (glassesCommands.handleGlassesCommand(komenda)) {
                runCatching { diag.endTurn("komenda okularów") }
                return true
            }
        }

        // KROK PO KROKU - start i sterowanie głosem.
        if (steps.głosem(text)) {
            runCatching { diag.endTurn("krok po kroku") }
            return true
        }

        pl.victor.app.conversation.MetaCommands.meetingCommand(text)?.let { zacznij ->
            if (zacznij) startMeeting() else if (meeting.nagrywa) {
                stopMeeting()
            } else {
                audio.speak("Nie nagrywam teraz żadnego spotkania.", language = settings.getResponseLanguage())
            }
            runCatching { diag.endTurn("notatki ze spotkania") }
            return true
        }

        pl.victor.app.conversation.MetaCommands.guideCommand(text)?.let { włącz ->
            if (włącz) {
                if (guide.aktywny.value) {
                    audio.speak("Przewodnik już działa.", language = settings.getResponseLanguage())
                } else {
                    audio.speak(
                        "Włączam przewodnika. Będę mówił o miejscach, które mijasz.",
                        language = settings.getResponseLanguage()
                    )
                    guide.start()
                }
            } else {
                guide.stop("komenda głosowa")
                audio.speak("Wyłączam przewodnika.", language = settings.getResponseLanguage())
            }
            runCatching { diag.endTurn("przewodnik") }
            return true
        }

        // ROZMOWA NA ŻYWO (Gemini Live) - lokalnie, bez modelu: zapytany o
        // czynność model odpowiada na nią słowami. Gdy nie wolno (wyłączona,
        // "tylko darmowe", brak klucza Gemini), tryb sam mówi dlaczego, a
        // zostaje zwykły tryb pytań.
        if (pl.victor.app.conversation.MetaCommands.startsLiveConversation(text)) {
            Log.i(TAG, "Komenda rozmowy na żywo: \"$text\"")
            startLiveConversation("polecenie głosowe")
            runCatching { diag.endTurn("rozmowa na żywo") }
            return true
        }

        pl.victor.app.conversation.MetaCommands.twoWayCommand(text)?.let { język ->
            startTwoWayTranslation(język.ifBlank { null })
            runCatching { diag.endTurn("tłumaczenie rozmowy") }
            return true
        }

        if (pl.victor.app.conversation.MetaCommands.startsEarTranslation(text)) {
            Log.i(TAG, "Komenda tłumaczenia ze słuchu: \"$text\"")
            val języki = pl.victor.app.conversation.MetaCommands.earTranslationLanguages(text)
            if (języki != null) {
                // Języki z komendy wygrywają z Ustawieniami - i zostają w
                // nich, tak jak po zmianie w panelu.
                setEarTranslationLanguages(języki.first, języki.second)
                if (!earTranslation.value) startEarTranslation()
            } else if (earTranslation.value) {
                stopEarTranslation("powtórzona komenda głosowa")
            } else {
                startEarTranslation()
            }
            // Ta droga nie przechodzi przez stan Completed, więc tura zostawała
            // w dzienniku otwarta - kolejne wiersze liczyły czas od niej.
            runCatching { diag.endTurn("tłumaczenie ze słuchu") }
            return true
        }

        // HAMULEC TRYBU CIĄGŁEGO - LOKALNY, BO INACZEJ ZALEŻY OD SIECI.
        //
        // Tryby dostępności chodzą w pętli i pytają model kilkadziesiąt razy na
        // minutę po circa 1600 tokenów. Zatrzymanie ich szło dotąd JEDYNIE przez
        // model: warstwa 0 nie miała żadnego wzorca na "przestań", a wzorce
        // zapasowe ([SmartActionDetector.detect]) uruchamiają się tylko wtedy,
        // gdy AI jest niedostępne. Czyli dokładnie wtedy, gdy pętla kosztuje
        // najwięcej, jej wyłączenie wymagało obiegu przez sieć - a gdy sieć
        // padła, nie dało się jej wyłączyć głosem WCALE.
        //
        // Drugi błąd był w tym, co obiecuje katalog komend: uczy mówić
        // "Dziękuję, wystarczy" i "Przestań czytać", a lista wzorców znała
        // "stop czytanie" i "wyłącz tryb". Człowiek mówił więc zdanie z
        // instrukcji i nic się nie działo.
        //
        // Bramkujemy to stanem trybu, nie samym tekstem: gdy nic nie chodzi,
        // "przestań czytać" leci dalej do modelu jak zwykłe zdanie. Dzięki temu
        // szeroka lista zwrotów nie przechwytuje rozmowy.
        if (accessibility.mode.value != pl.victor.app.accessibility.AccessibilityMode.OFF &&
            pl.victor.app.conversation.MetaCommands.stopsAccessibility(text)
        ) {
            Log.i(TAG, "Zatrzymuję tryb dostępności lokalnie: \"$text\"")
            diag.event(
                DiagFormat.Phase.AKCJA, "tryb dostępności zatrzymany bez modelu",
                mapOf("tryb" to accessibility.mode.value.name)
            )
            audio.stopSpeaking()
            accessibility.disable()
            _state.value = OrchestratorState.Idle
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

    /**
     * Gest "nowa rozmowa" (cztery kliknięcia) - to samo, co komenda "nowy temat".
     *
     * Wcześniej gest wołał [reset], czyli tylko przestawiał stan na bezczynny:
     * historia rozmowy i doklejane tematy zostawały, a trwająca tura żyła
     * dalej, tyle że już bez możliwości przerwania (nowa tura nadpisywała jej
     * uchwyt).
     */
    private fun startNewConversation() {
        cancelCurrentTurn("nowa rozmowa")
        conversationContext.clear()
        openContextTopics.clear()
        audio.speak("Zaczynamy od nowa.", language = settings.getResponseLanguage())
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

    /** Obraz ze strumienia pod litery i kody - patrz [pl.victor.app.orchestrator.CodeStreamScanner]. */
    private val codeStream = pl.victor.app.orchestrator.CodeStreamScanner(
        settings = settings,
        scope = scope,
        glassesManager = glassesManager,
        qrScanner = qrScanner,
        ocrReader = ocrReader,
        state = _state,
        dziennik = { diag }
    )

    /**
     * Sprawdza czy user chce OCR - czytanie tekstu z otoczenia.
     * Słowa kluczowe: "przeczytaj", "co pisze", "co jest napisane",
     * "przetłumacz", "menu", "etykieta", "tablica"
     */
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

    /** Dociągnięcie wektorów pamięci po znaczeniu (np. po włączeniu w Ustawieniach). */
    fun requestSemanticIndexing(reason: String) = semanticMemory.requestSync(reason)

    companion object {
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
        const val SOURCE_AUDIO_TO_MODEL = "Bez transkrypcji - nagranie do modelu"

        /**
         * Bezpiecznik blokady uśpienia na czas NASŁUCHU.
         *
         * Nasłuch ma własny limit i nie może trwać dłużej - dziewięćdziesiąt sekund
         * to zapas nad nim, nie planowany czas pracy.
         */
        private const val LISTEN_WAKE_LOCK_MS = 90_000L

        /** Na świeże położenie przy pytaniu o odjazdy - przystanek obok to kilkadziesiąt metrów. */
        private const val TRANSIT_LOCATION_MS = 4_500L

        /** Overpass z trasami linii bywa wolniejszy niż zwykłe miejsca. */
        private const val TRANSIT_OSM_MS = 9_000L

        /** Tyle zapowiedź skrętu czeka na koniec bieżącej wypowiedzi. */
        private const val NAV_WAIT_MS = 4_000L

        /** Ile najwyżej czekamy na położenie do instrukcji rozmowy na żywo. */
        private const val LIVE_LOCATION_MS = 1_500L

        /** Jak często pętla tłumaczenia sprawdza, czy skończyła się Twoja kolej. */
        internal const val EAR_TURN_POLL_MS = 200L

        /** Ile po geście lub nasłuchu tło (przewodnik, wiadomości) milczy - patrz [ciszaWTle]. */
        private const val CISZA_PO_AKTYWNOŚCI_MS = 20_000L

        /** Ile po wypowiedzi asystenta tło milczy - patrz [ciszaWTle]. */
        private const val CISZA_PO_MOWIE_MS = 8_000L

        /**
         * Najgłośniejsze okno (20 ms, RMS) nagrania z okularów, poniżej którego
         * nie ma w nim mowy. Z dzienników: nagrania bez mowy mają szczyt 100-360,
         * z mową - od ok. 1500 wzwyż.
         */
        internal const val RECORDING_SPEECH_PEAK = 800.0

        /** Okno, w którym ramka "ucisz" jest echem naszej komendy odtwarzania. */
        private const val PLAYBACK_ECHO_WINDOW_MS = 1_500L

        /** Karencja wybudzenia z dźwięku po końcu tłumaczenia. */
        internal const val EAR_MIC_WAKE_GRACE_MS = 8_000L

        /** Puls "wciąż mówię" dla okularów - odstęp jak u producenta. */
        private const val PLAYBACK_PULSE_MS = 2_000L

        /** Ile czekać po ucichnięciu, zanim okulary usłyszą "koniec" - przerwy między zdaniami. */
        private const val PLAYBACK_END_GRACE_MS = 600L

        /**
         * Jakość miniatury przy zdjęciu do odczytu kodu.
         *
         * Z dziennika z 23 września: przy jakości 5 miniatura miała 36 kB, przy
         * domyślnej 2 - 19 kB. Próba "ostrzejszego" zdjęcia pod kod szła na tej
         * drugiej, czyli dawała obraz GORSZY niż pierwszy.
         */
        private const val CODE_PHOTO_QUALITY = 5

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

        /** Zadanie Hermesa może trwać kwadranse - dłuższa blokada niż zwykła tura. */
        private const val HERMES_WAKE_LOCK_MS = 15 * 60_000L

        /** Nazwy właścicieli blokady - patrz [pl.victor.app.power.WakelockHelper]. */
        /** Nie częściej mówimy o pełnej pamięci okularów. */
        private const val LOW_MEMORY_ANNOUNCE_EVERY_MS = 6 * 60 * 60 * 1000L

        /** Odpowiedź na prośbę o obraz w trybie bez kamery. */
        const val CAMERA_OFF_SPEECH =
            "Kamera jest wyłączona. Włączysz ją w ustawieniach, wyłączając tryb bez kamery."

        /** Polecenie kafelka "Wiadomości" i podwójnego kliknięcia w trybie bez kamery. */
        const val READ_MESSAGES_PROMPT = "Przeczytaj moje nowe wiadomości"

        /** Ile po powrocie okularów czekamy z zaległymi alertami. */
        private const val MISSED_ALERTS_DELAY_MS = 8_000L
        private const val MISSED_ALERTS_ATTEMPTS = 6

        /** Blokada uśpienia na pobranie zdjęcia z przycisku - patrz [handleGlassesPhoto]. */
        private const val LOCK_HW_PHOTO = "ZdjecieZPrzycisku"
        private const val HW_PHOTO_WAKE_LOCK_MS = 45_000L

        private const val LOCK_LISTENING = "Nasluch"
        private const val LOCK_TURN = "Tura"

        internal const val PLAIN_TASK_SYSTEM_PROMPT =
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

        private const val TAG = "AIOrchestrator"

        /** Najkrótszy odstęp między wysyłkami dziennika - patrz uploadDiagnosticsInBackground. */
        private const val DIAG_UPLOAD_INTERVAL_MS = 60_000L

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
         * Ile czekamy, zanim ogłosimy utratę okularów.
         *
         * Osiem sekund to nie jest liczba z sufitu: pierwsze próby wznowienia
         * idą co sześć sekund ([pl.victor.app.ble.ReconnectBackoff.FAST_DELAY_MS]),
         * więc karencja mieści JEDNĄ pełną próbę z zapasem. Zerwanie, które
         * naprawia się samo za pierwszym podejściem, nie wygeneruje żadnego
         * komunikatu - a dokładnie takie zdarza się najczęściej.
         */
        private const val UTRATA_KARENCJA_MS = 8_000L

        /**
         * Do tylu słów wypowiedź jest ODPOWIEDZIĄ na pytanie o potwierdzenie.
         *
         * Dłuższa jest nowym poleceniem, nawet jeśli zaczyna się od "tak":
         * "tak, a przy okazji jaka jest pogoda" ma pójść do modelu, a nie
         * wysłać wiadomość, o której mowa była zdanie wcześniej.
         */
        private const val CONFIRMATION_MAX_WORDS = 3

        /** Jak stara może być wiadomość, na którą odpowiadamy z warstwy 0. */
        private const val MESSAGE_REPLY_FRESH_MS = 30 * 60_000L

        /** Jak długo wiszące potwierdzenie przyjmuje "tak"/"nie" z nowej tury głosowej. */
        private const val CONFIRMATION_VOICE_TTL_MS = 60_000L

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
    val cancelText: String,
    /** Od kiedy wisi - głosowe "tak" z nowej tury ma ważność tylko chwilę. */
    val createdAtMs: Long = System.currentTimeMillis(),
    /**
     * Polecenie dla Home Assistant czekające na "tak" (zamek, brama, alarm) -
     * wtedy [actions] jest puste, a [AIOrchestrator.confirmAction] wysyła to
     * zdanie do domu.
     */
    val homeCommand: String? = null
)
