package pl.victor.app.orchestrator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.victor.app.AIOrchestrator.Companion.EAR_MIC_WAKE_GRACE_MS
import pl.victor.app.AIOrchestrator.Companion.EAR_TURN_POLL_MS
import pl.victor.app.OrchestratorState
import pl.victor.app.audio.AudioManager
import pl.victor.app.audio.GlassesVoiceCapture
import pl.victor.app.ble.VictorManager
import pl.victor.app.conversation.ConversationalMode
import pl.victor.app.data.SettingsRepository
import pl.victor.app.diagnostics.DiagFormat

/**
 * To, czego tłumaczenie ze słuchu potrzebuje od orkiestratora - rzeczy, które
 * należą do TURY (mikrofon frazy, nasłuch z wyścigiem okularów i telefonu,
 * rozmowa na żywo), więc nie mogą przejść do [EarTranslationController].
 */
internal interface EarTranslationHost {
    /** Korutyna bieżącej tury - patrz `AIOrchestrator.activeTurnJob`. */
    val activeTurnJob: kotlinx.coroutines.Job?

    /** Tekst z telefonu odłożony przez nasłuch - patrz `AIOrchestrator.setAsidePhoneTranscript`. */
    var setAsidePhoneTranscript: String?

    fun hasMicrophonePermission(): Boolean
    fun liveConversationOn(): Boolean
    fun stopLiveConversation(powód: String)
    fun pauseWakeWordMic()
    fun resumeWakeWordMic()
    suspend fun askModelPlain(prompt: String): String?
    suspend fun listenUntilSpeechEnds(
        languageTag: String,
        capture: GlassesVoiceCapture?,
        trustPhoneMicrophone: Boolean = true,
        useBluetoothMic: Boolean = true,
        onPartial: ((String) -> Unit)? = null
    ): String?
}

/**
 * Tłumaczenie ze słuchu (w telefonie i na żywo przez chmurę) oraz rozmowa w
 * dwie strony z tłumaczem.
 *
 * ## Po co osobna klasa
 * Wydzielone z [pl.victor.app.AIOrchestrator] BEZ ZMIANY ZACHOWANIA: te same
 * pętle, komunikaty i wpisy w dzienniku. To TRYB, nie tura - ma własny stan
 * (flagi trybu, korutyny pętli, panel z przekładem), którego jedynym
 * właścicielem jest teraz ta klasa. Orkiestrator czyta go przez
 * [earTranslation], [earTwoWay] i [mojaKolej], a resztę - nasłuch, mikrofon
 * frazy, rozmowę na żywo - daje przez [EarTranslationHost].
 */
internal class EarTranslationController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val audio: AudioManager,
    private val glassesManager: VictorManager,
    private val speechToText: pl.victor.app.conversation.SpeechToText,
    private val conversationalMode: ConversationalMode,
    private val translator: pl.victor.app.translation.SimultaneousTranslator,
    private val transcriber: RecordingTranscriber,
    /** Stan orkiestratora - tryb pokazuje w nim nasłuch i swoje błędy. */
    private val state: MutableStateFlow<OrchestratorState>,
    /** Dziennik leniwie - z tego samego powodu co `AIOrchestrator.diag`. */
    private val dziennik: () -> pl.victor.app.diagnostics.DiagnosticLog,
    private val host: EarTranslationHost
) {
    private val diag: pl.victor.app.diagnostics.DiagnosticLog
        get() = dziennik()

    private val earSession = pl.victor.app.translation.EarTranslationSession()

    /** Błąd ŻYWEGO nasłuchu z ostatniego obiegu tłumaczenia - patrz [earListenOnce]. */
    @Volatile
    private var earListenError: String? = null

    @Volatile
    private var earListenErrorCode: Int? = null
    private var earJob: kotlinx.coroutines.Job? = null

    private val _earTwoWay = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Czy tłumaczenie ze słuchu działa jako rozmowa w dwie strony. */
    val earTwoWay: kotlinx.coroutines.flow.StateFlow<Boolean> = _earTwoWay

    @Volatile
    var mojaKolej = false
        private set
    private var myTurnJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var earListenJob: kotlinx.coroutines.Job? = null

    /**
     * Rozmowa w dwie strony z tłumaczem.
     *
     * Rozmówcę słucha telefon, a przekład słyszysz w okularach - jak w
     * zwykłym tłumaczeniu ze słuchu. Gdy chcesz coś powiedzieć, klikasz
     * przycisk ([myTurn]): mówisz po polsku do okularów, a przekład czyta
     * GŁOŚNIK TELEFONU, żeby usłyszał go rozmówca.
     *
     * @param obcy język rozmówcy albo `null` = z ustawień tłumaczenia
     */
    fun startTwoWayTranslation(obcy: String? = null) {
        val mój = settings.getResponseLanguage()
        val jegoJęzyk = obcy?.takeIf { it != mój }
            ?: settings.getEarTranslationFrom().takeIf { it != mój }
            ?: "en"
        if (_earTranslation.value) stopEarTranslation("przełączenie na rozmowę")
        settings.setEarTranslationFrom(jegoJęzyk)
        settings.setEarTranslationTo(mój)
        _earTranscript.value = _earTranscript.value.copy(z = jegoJęzyk, na = mój)
        _earTwoWay.value = true
        startEarTranslation()
        if (!_earTranslation.value) _earTwoWay.value = false
    }

    /**
     * Moja kolej w rozmowie: słuchamy Ciebie (mikrofon okularów, a bez
     * okularów - telefonu), tłumaczymy na język rozmówcy i czytamy przez
     * głośnik telefonu.
     *
     * @param zOkularów kliknięcie na oprawce - okulary już nadają dźwięk
     */
    fun myTurn(zOkularów: Boolean) {
        if (!_earTwoWay.value || !_earTranslation.value) return
        if (myTurnJob?.isActive == true) return
        myTurnJob = scope.launch {
            mojaKolej = true
            earListenJob?.cancel()
            val mój = settings.getEarTranslationTo()
            // Język, którym rozmówca NAPRAWDĘ mówi (wykryty na żywo), a dopiero
            // bez niego - ten z ustawień.
            val wykryty = językRozmówcy?.pewny
            val jegoJęzyk = wykryty ?: settings.getEarTranslationFrom()
            try {
                diag.event(
                    DiagFormat.Phase.NASŁUCH, "rozmowa: moja kolej",
                    mapOf("zOkularów" to zOkularów, "naJęzyk" to jegoJęzyk, "wykryty" to (wykryty != null))
                )
                // TELEFON SŁUCHA TEŻ, PO POLSKU - NIE TYLKO OKULARY.
                //
                // Dotąd kliknięcie na oprawce brało WYŁĄCZNIE nagranie z
                // okularów i przepisywało je rozpoznawaniem na urządzeniu. Na
                // telefonie użytkownika ta droga nie oddała tekstu ani razu w
                // ponad 250 próbach - więc w rozmowie w dwie strony Twoja kolej
                // kończyłaby się zawsze "Nie usłyszałem". Teraz jest tak jak przy
                // zwykłym pytaniu: rozpoznawanie telefonu w Twoim języku, a
                // okulary pilnują końca wypowiedzi. Nagranie z okularów idzie w
                // ruch dopiero, gdy telefon nic nie zrozumiał.
                var źródło = "telefon"
                val tekst = if (zOkularów && glassesManager.isConnected()) {
                    val capture = GlassesVoiceCapture(glassesManager)
                    try {
                        val nadaje = capture.start()
                        audio.playListeningCue()
                        host.setAsidePhoneTranscript = null
                        val zTelefonu = try {
                            host.listenUntilSpeechEnds(
                                languageTag = languageTagFor(mój),
                                capture = if (nadaje) capture else null,
                                trustPhoneMicrophone = true,
                                useBluetoothMic = pl.victor.app.audio.MicChoice.useBluetoothMic(
                                    overSco = false,
                                    bleStreamLive = nadaje
                                )
                            )?.takeIf { it.isNotBlank() } ?: host.setAsidePhoneTranscript
                        } finally {
                            glassesManager.stopGlassesListening()
                        }
                        zTelefonu ?: run {
                            val pcm = if (nadaje) capture.stop().takeIf { it.hasAudio }?.pcm else null
                            pcm?.let {
                                źródło = "okulary"
                                transcriber.transcribeGlassesAudio(it, languageTagFor(mój))
                                    ?: transcriber.transcribeWithModel(it, mój)?.also { źródło = "model" }
                            }
                        }
                    } finally {
                        // Anulowanie albo nieudany start zostawiały odbiorcę
                        // mikrofonu na zawsze (bufor sprzed nasłuchu i
                        // wybudzenie dźwiękiem przestawały działać).
                        capture.detach()
                    }
                } else {
                    audio.playListeningCue()
                    conversationalMode.listenOnce(languageTagFor(mój))
                }?.trim()
                if (tekst.isNullOrBlank()) {
                    audio.speakAndAwait("Nie usłyszałem. Kliknij jeszcze raz i mów.", language = mój)
                    return@launch
                }
                if (pl.victor.app.translation.EarTranslation.toKoniec(tekst)) {
                    stopEarTranslation("koniec rozmowy - moja kolej")
                    audio.speak("Kończę tłumaczenie rozmowy.", language = mój)
                    return@launch
                }
                val (przekład, tłumacz) = przetłumaczMojeZdanie(tekst, mój, jegoJęzyk)
                if (przekład.isNullOrBlank() || przekład == tekst) {
                    audio.speakAndAwait("Nie udało się przetłumaczyć.", language = mój)
                    return@launch
                }
                diag.event(
                    DiagFormat.Phase.NASŁUCH, "rozmowa: moje zdanie",
                    mapOf(
                        "powiedziane" to tekst.take(60), "przekład" to przekład.take(60),
                        "źródło" to źródło, "tłumacz" to tłumacz, "na" to jegoJęzyk
                    )
                )
                _earTranscript.value = _earTranscript.value.zSegmentem(
                    pl.victor.app.translation.TwoWay.mojeZdanie(tekst), przekład, System.currentTimeMillis()
                )
                // Telefon zaraz usłyszy własny głośnik - to echo, nie rozmówca.
                earSession.zapamiętajWłasnąWypowiedź(przekład)
                // Przekład jest DLA ROZMÓWCY - w okularach nikomu się nie przyda,
                // więc gdy głośnik telefonu zawiedzie, nie gramy go "zastępczo" do
                // ucha (bieg 183: wszystko szło do okularów), tylko mówimy, co się stało.
                val zGłośnika = audio.speakOnPhoneSpeaker(przekład, language = jegoJęzyk)
                runCatching {
                    diag.event(
                        DiagFormat.Phase.MOWA, "rozmowa: przekład z głośnika telefonu",
                        mapOf("ok" to zGłośnika, "trasa" to audio.ostatniaTrasaGłośnika)
                    )
                }
                if (!zGłośnika) {
                    audio.speakAndAwait("Nie udało się puścić przekładu z głośnika telefonu.", language = mój)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Moja kolej w rozmowie się nie udała", e)
            } finally {
                mojaKolej = false
            }
        }
    }

    /**
     * Twoje zdanie w rozmowie na język rozmówcy.
     *
     * Przy włączonym tłumaczeniu na żywo i kluczu wybranego dostawcy tłumaczy
     * MODEL (Gemini przy Gemini, GPT przy OpenAI) - rozmówca słyszy to z
     * głośnika, więc zdanie ma brzmieć naturalnie, a nie słowo w słowo. Bez
     * sieci, bez klucza albo po [MY_TURN_MODEL_MS] - tłumacz w telefonie.
     *
     * @return przekład (albo `null`) i kto tłumaczył - do dziennika
     */
    private suspend fun przetłumaczMojeZdanie(tekst: String, z: String, na: String): Pair<String?, String> {
        if (settings.isLiveEarTranslationEnabled() && settings.hasApiKey(settings.getActiveProvider())) {
            val zModelu = withTimeoutOrNull(MY_TURN_MODEL_MS) {
                host.askModelPlain(pl.victor.app.translation.TwoWay.polecenieTłumaczenia(tekst, z, na))
            }
            pl.victor.app.translation.TwoWay.oczyśćPrzekład(zModelu)
                ?.takeIf { it != tekst }
                ?.let { return it to "model" }
        }
        return runCatching { translator.translate(tekst, z, na) }.getOrNull() to "telefon"
    }

    /** Język rozmówcy wykrywany w trakcie tłumaczenia na żywo - patrz [earLiveLoop]. */
    @Volatile
    private var językRozmówcy: pl.victor.app.translation.JęzykRozmówcy? = null

    private val _earTranslation = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Czy trwa tryb tłumaczenia ze słuchu - do pokazania w interfejsie. */
    val earTranslation: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _earTranslation.asStateFlow()

    private val _earTranscript = MutableStateFlow(pl.victor.app.translation.EarTranscript())

    /**
     * Co słychać i co z tego wyszło - dla panelu tłumaczenia.
     *
     * Panel jest tylko widokiem: tryb działa tak samo z panelem i bez niego
     * (z przycisku na okularach, z komendy głosowej).
     */
    val earTranscript: StateFlow<pl.victor.app.translation.EarTranscript> =
        _earTranscript.asStateFlow()

    /** Ostatni tekst częściowy - źródło przekładu na żywo, patrz [startEarPartialTranslation]. */
    private val earPartial = MutableStateFlow("")
    private var earPartialJob: kotlinx.coroutines.Job? = null

    /** Czyści tekst w panelu. Trwającego trybu nie zatrzymuje. */
    fun clearEarTranscript() {
        _earTranscript.value = _earTranscript.value.wyczyść()
    }

    /**
     * Zmienia języki trybu.
     *
     * W trakcie trybu uruchamia go od nowa - rozpoznawanie słucha w języku
     * źródłowym i tłumacz jest zbudowany dla pary, więc zmiana w locie nie ma
     * jak zadziałać. Tak samo robi aplikacja producenta.
     */
    fun setEarTranslationLanguages(from: String, to: String) {
        settings.setEarTranslationFrom(from)
        settings.setEarTranslationTo(to)
        _earTranscript.value = _earTranscript.value.copy(z = from, na = to)
        if (_earTranslation.value) {
            stopEarTranslation("zmiana języków")
            startEarTranslation()
        }
    }

    /** Jedno wejście dla przycisku, gestu i komendy głosowej. */
    fun toggleEarTranslation() {
        if (_earTranslation.value) stopEarTranslation("ponowne wywołanie")
        else startEarTranslation()
    }

    /**
     * Tłumaczenie ze słuchu - pętla, nie tura.
     *
     * ## Czym to się różni od "przetłumacz to zdanie"
     * Tamto jest turą: pytanie, odpowiedź, koniec. To jest TRYB: dopóki go nie
     * wyłączysz, wszystko, co słychać, wraca do ucha po polsku. Tak działa
     * `TranslateListenerActivity` w aplikacji producenta i o to prosił
     * użytkownik ("tłumaczenie ze słuchu zrób takie samo jak w Prismie").
     *
     * ## Droga dźwięku
     * Ta sama, którą chodzi pytanie z okularów: strumień BLE, dekoder Opusa,
     * przepróbkowanie do 16 kHz, rozpoznawanie mowy. BEZ profilu rozmowy - SCO
     * kazałoby okularom oddać mikrofon do HFP i strumień by ucichł, a przy
     * tłumaczeniu to nie jest jedna zmarnowana tura, tylko cały tryb.
     *
     * ## Czego tu NIE robimy
     * Nie pytamy modelu. Tłumaczy [pl.victor.app.translation.SimultaneousTranslator]
     * (ML Kit, na urządzeniu) - chyba że działa tryb NA ŻYWO ([earLiveLoop]) - więc tryb działa bez sieci i nie zużywa tokenów -
     * a przy mowie ciągłej zapytanie do modelu za każdym zdaniem byłoby i
     * wolniejsze, i kosztowne.
     *
     * Wszystkie decyzje ("co jest nowe", "czy to własne echo", "czy już czas")
     * są w [pl.victor.app.translation.EarTranslationSession] - tutaj jest sama
     * pętla. Inaczej nie dałoby się sprawdzić niczego bez okularów.
     */
    fun startEarTranslation() {
        if (_earTranslation.value) return
        // Ten sam mikrofon - rozmowa na żywo ustępuje tłumaczeniu.
        if (host.liveConversationOn()) host.stopLiveConversation("start tłumaczenia ze słuchu")
        if (!speechToText.isAvailable()) {
            state.value = OrchestratorState.Error(
                "To urządzenie nie ma rozpoznawania mowy - tłumaczenie ze słuchu nie zadziała."
            )
            return
        }
        if (!host.hasMicrophonePermission()) {
            val message = "Brak zgody na mikrofon. Otwórz aplikację i naciśnij " +
                "przycisk Powiedz - system zapyta o uprawnienie."
            state.value = OrchestratorState.Error(message)
            audio.speak(message, language = settings.getResponseLanguage())
            return
        }
        val from = settings.getEarTranslationFrom()
        val to = settings.getEarTranslationTo()
        if (from == to) {
            val message = "Tłumaczenie ze słuchu ma ten sam język na wejściu i wyjściu. " +
                "Zmień go w Ustawieniach."
            state.value = OrchestratorState.Error(message)
            _earTranscript.value = _earTranscript.value.copy(z = from, na = to, komunikat = message)
            audio.speak(message, language = settings.getResponseLanguage())
            return
        }
        earSession.wyzeruj()
        // Wykryty język rozmówcy należy do JEDNEJ rozmowy - nowa zaczyna od ustawień.
        językRozmówcy = null
        _earTranscript.value = _earTranscript.value.start(from, to)
        _earTranslation.value = true
        startEarPartialTranslation(from, to)
        // Włączone GŁOSEM rusza z wnętrza tury, która jeszcze sprząta po sobie
        // (łącze audio, mikrofon frazy) - patrz początek [earTranslationLoop].
        val turaWToku = host.activeTurnJob?.takeIf { it.isActive }
        // LAZY i start() po przypisaniu: pętla porównuje się z [earJob], więc
        // uchwyt musi już stać, zanim wykona pierwszą instrukcję.
        earJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            earTranslationLoop(from, to, turaWToku)
        }
        earJob?.start()
    }

    /**
     * Przekład tekstu częściowego - najwyżej raz na sekundę.
     *
     * Ten sam rytm co u producenta: częściej to miganie tekstu i marnowanie
     * tłumacza na słowa, które za chwilę się zmienią. Tłumaczy zawsze
     * NAJNOWSZY tekst - odczytany po odczekaniu, nie ten, który zbudził pętlę.
     */
    private fun startEarPartialTranslation(from: String, to: String) {
        earPartialJob?.cancel()
        earPartial.value = ""
        earPartialJob = scope.launch {
            var ostatnio = 0L
            earPartial.collect { pierwszy ->
                if (pierwszy.isBlank()) return@collect
                val czekaj = ostatnio + pl.victor.app.translation.EarTranslation.MIN_ODSTĘP_MS -
                    System.currentTimeMillis()
                if (czekaj > 0) delay(czekaj)
                val tekst = earPartial.value.takeIf { it.isNotBlank() } ?: return@collect
                ostatnio = System.currentTimeMillis()
                val przekład = try {
                    translator.translate(tekst, from, to)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (!przekład.isNullOrBlank() && przekład != tekst) {
                    _earTranscript.value = _earTranscript.value.zCzęściowymPrzekładem(tekst, przekład)
                }
            }
        }
    }

    /**
     * Wyłącza tryb.
     *
     * @param powód trafia do dziennika - inaczej "tryb sam się wyłączył" jest
     *   nie do odróżnienia od "tryb się wysypał"
     */
    fun stopEarTranslation(powód: String) {
        if (!_earTranslation.value && earJob == null) return
        Log.i(TAG, "Tłumaczenie ze słuchu - koniec ($powód)")
        runCatching {
            diag.event(
                DiagFormat.Phase.NASŁUCH, "tłumaczenie ze słuchu: koniec",
                mapOf("powód" to powód)
            )
        }
        _earTranslation.value = false
        _earTwoWay.value = false
        myTurnJob?.cancel()
        _earTranscript.value = _earTranscript.value.stop()
        earPartialJob?.cancel()
        earPartialJob = null
        earJob?.cancel()
        earJob = null
    }

    /** Komunikat dla człowieka w panelu - to samo, co tryb mówi na głos. */
    private fun noteEarMessage(tekst: String) {
        _earTranscript.value = _earTranscript.value.copy(komunikat = tekst)
    }

    private suspend fun earTranslationLoop(
        from: String,
        to: String,
        turaWToku: kotlinx.coroutines.Job? = null
    ) {
        val fromName = pl.victor.app.translation.SimultaneousTranslator.languageName(from)
        val toName = pl.victor.app.translation.SimultaneousTranslator.languageName(to)
        runCatching {
            diag.event(
                DiagFormat.Phase.NASŁUCH, "tłumaczenie ze słuchu: start",
                mapOf("z" to from, "na" to to, "zTury" to (turaWToku != null))
            )
        }
        val mójJob = currentCoroutineContext()[kotlinx.coroutines.Job]
        // WŁĄCZONE GŁOSEM CZEKA, AŻ TURA, KTÓRA JE WŁĄCZYŁA, SKOŃCZY SPRZĄTAĆ.
        //
        // Dziennik z biegu 152: z panelu tryb działał, a włączony głosem
        // ("tłumaczenie na żywo") milczał 23 i 37 sekund - bez zapowiedzi w
        // dzienniku, bez jednego pustego nasłuchu, do wciśnięcia przycisku.
        // Różnica między tymi drogami jest jedna: głosem pętla rusza W ŚRODKU
        // tury z okularów, a ta w swoim `finally` zwija łącze audio rozmowy i
        // wznawia nasłuch frazy na mikrofonie telefonu - równolegle z
        // zapowiedzią i pierwszym nasłuchem tłumaczenia. Panel nie ma tury,
        // więc niczego takiego nie ma. Czekanie jest krótkie (sprzątanie trwa
        // milisekundy) i ma limit, żeby zawieszona tura nie zatrzymała trybu.
        if (turaWToku != null && turaWToku !== mójJob) {
            val czekamOd = System.currentTimeMillis()
            val zdążyła = kotlinx.coroutines.withTimeoutOrNull(EAR_TURN_WAIT_MS) { turaWToku.join() } != null
            runCatching {
                diag.event(
                    DiagFormat.Phase.NASŁUCH, "tłumaczenie ze słuchu: tura włączająca skończona",
                    mapOf("ms" to (System.currentTimeMillis() - czekamOd), "zdążyła" to zdążyła)
                )
            }
        }
        // Tryb SAM trzyma mikrofon: nasłuch frazy na telefonie i wybudzenie z
        // dźwięku okularów tylko by mu go podbierały - i odpalały tury w trakcie.
        host.pauseWakeWordMic()
        glassesManager.suspendMicWake(true)
        // Zapowiedź idzie w JĘZYKU DOCELOWYM, bo w nim człowiek będzie słyszał
        // wszystko, co dalej - i od razu słychać, czy syntezator ten język ma.
        // Przy wyciszonym trybie (przekład tylko w panelu) - bez zapowiedzi.
        val mówić = settings.isEarTranslationSpoken()
        if (mówić) {
            val zapowiedźOd = System.currentTimeMillis()
            val zapowiedź = if (_earTwoWay.value) {
                // W rozmowie zapowiedź mówi, JAK oddać głos - bez tego tryb
                // dwustronny wygląda jak zwykłe tłumaczenie ze słuchu.
                pl.victor.app.translation.TwoWay.zapowiedź(fromName)
            } else {
                "Tłumaczę z $fromName na $toName."
            }
            val powiedziane = audio.speakAndAwait(zapowiedź, language = if (_earTwoWay.value) settings.getResponseLanguage() else to)
            runCatching {
                diag.event(
                    DiagFormat.Phase.NASŁUCH, "tłumaczenie ze słuchu: zapowiedź",
                    mapOf("ms" to (System.currentTimeMillis() - zapowiedźOd), "powiedziane" to powiedziane)
                )
            }
        }
        // POTKNIĘCIE JEDNEGO ZDANIA NIE MOŻE KOŃCZYĆ TRYBU.
        //
        // Przy mowie ciągłej wyjątek z rozpoznawania albo z tłumacza jest
        // normalnym zdarzeniem (urwana sieć przy pobieraniu modelu, mikrofon
        // zajęty na chwilę przez system). Tryb, który po pierwszym takim
        // potknięciu milknie bez słowa, wygląda jak zepsuty - a to dokładnie ta
        // klasa błędu, którą już raz tu zgłoszono ("cicha porażka, widoczna
        // tylko w logu"). Liczymy je więc i poddajemy się dopiero po serii.
        var zRzędu = 0
        try {
            // NA ŻYWO PRZEZ CHMURĘ, gdy się da - patrz [earLiveLoop]. Gdy nie
            // (brak klucza, sieci, zerwana sesja), dalej idzie dotychczasowa
            // pętla w telefonie, bez wyłączania trybu.
            if (earLiveLoop(to, mójJob)) return
            // `earJob === mójJob`: po szybkim "wyłącz i włącz" flaga jest znowu
            // prawdą, ale należy już do NOWEJ sesji - stara pętla ma wtedy
            // skończyć, a nie słuchać równolegle z nową.
            while (_earTranslation.value && earJob === mójJob) {
                // Twoja kolej w rozmowie dwustronnej - pętla czeka, aż przekład
                // Twoich słów wybrzmi z głośnika telefonu.
                while (mojaKolej && _earTranslation.value) delay(EAR_TURN_POLL_MS)
                if (!_earTranslation.value || earJob !== mójJob) break
                val nasłuchOd = System.currentTimeMillis()
                val usłyszane = try {
                    // STRAŻNIK: jeden nasłuch ma swoje limity (rozpoznawanie,
                    // cisza w strumieniu okularów - najwyżej kilkanaście
                    // sekund). Gdy mimo to wisi, tryb nie może milczeć do
                    // przycisku - liczymy to jak pusty nasłuch, Z WPISEM.
                    // Pusty tekst zamiast null, żeby odróżnić "nic" od "zawisł".
                    // Osobne zadanie, żeby "moja kolej" mogła przerwać nasłuch
                    // rozmówcy, nie kończąc całego trybu.
                    val nasłuch = scope.async {
                        kotlinx.coroutines.withTimeoutOrNull(EAR_LISTEN_MAX_MS) {
                            earListenOnce(from) ?: ""
                        }
                    }
                    earListenJob = nasłuch
                    val wynik = try {
                        nasłuch.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Przerwany przez "moją kolej" - to nie koniec trybu.
                        currentCoroutineContext().ensureActive()
                        nasłuch.cancel()
                        continue
                    } finally {
                        if (!nasłuch.isCompleted) nasłuch.cancel()
                        earListenJob = null
                    }
                    if (wynik == null) {
                        earListenError = "nasłuch zawisł na ${EAR_LISTEN_MAX_MS / 1000} s"
                        earListenErrorCode = null
                    }
                    wynik
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // runCatching połykał i to - anulowana pętla liczyła wtedy
                    // "puste nasłuchy" i mówiła "nic nie słyszę, kończę".
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Tłumaczenie ze słuchu: nasłuch nie wyszedł", e)
                    null
                }
                if (usłyszane.isNullOrBlank()) {
                    // "NIC NIE SŁYSZĘ" TO BYŁA ODPOWIEDŹ NA NIEZADANE PYTANIE.
                    //
                    // Zgłoszone: "tłumaczenie na żywo nie działa - mówi, że nic
                    // nie słyszy". I mówiło prawdę, tylko bezużyteczną: pusty
                    // wynik rozpoznawania ma kilka bardzo różnych powodów, a
                    // tylko JEDEN z nich to naprawdę cisza w pokoju.
                    //
                    // Najważniejszy jest ten, który sam wprowadziłem: tryb
                    // nasłuchuje w JĘZYKU ŹRÓDŁOWYM (domyślnie angielskim), a
                    // polski telefon nie ma pobranego pakietu angielskiego.
                    // Rozpoznawanie oddaje wtedy ERROR_LANGUAGE_UNAVAILABLE i
                    // pustkę - co do znaku tak samo jak przy ciszy. Powód leżał
                    // w `speechToText.lastFailureReason()` przez cały czas;
                    // pętla go nie czytała.
                    val powód = earListenError
                    runCatching {
                        diag.event(
                            DiagFormat.Phase.NASŁUCH, "tłumaczenie ze słuchu: pusty nasłuch",
                            mapOf(
                                "powód" to (powód ?: "cisza"),
                                "zRzędu" to (zRzędu + 1),
                                "ms" to (System.currentTimeMillis() - nasłuchOd)
                            )
                        )
                    }
                    // OD RAZU KOŃCZYMY TYLKO PRZY BRAKU JĘZYKA.
                    //
                    // Pierwsza wersja kończyła przy KAŻDYM błędzie - także przy
                    // zajętym na chwilę mikrofonie albo zerwanej na sekundę
                    // sieci, czyli przy rzeczach, które same mijają. Brak języka
                    // w obu silnikach nie minie, i tylko on uzasadnia koniec po
                    // jednym podejściu. Reszta liczy się jak pusty nasłuch.
                    if (powód != null &&
                        pl.victor.app.conversation.LanguagePackFallback.isLanguageError(earListenErrorCode)
                    ) {
                        // Awaria, nie cisza - liczenie do pięciu nic tu nie da,
                        // bo szósty raz skończy się tak samo.
                        // Do tego miejsca dochodzi się teraz dopiero wtedy, gdy
                        // zawiodły OBA silniki: na urządzeniu (brak pakietu w jego
                        // magazynie) i przez sieć. Pobieranie pakietu gdzie
                        // indziej nic tu nie da - stąd zdanie o internecie, a nie
                        // o ustawieniach Androida, które już raz wysłało
                        // człowieka szukać czegoś, co miał.
                        val komunikat = "Nie mogę słuchać po $fromName: $powód. Rozpoznawanie " +
                            "bez sieci nie ma tego języka, a przez internet też " +
                            "się nie udało. Sprawdź połączenie z internetem."
                        noteEarMessage(komunikat)
                        audio.speakAndAwait(komunikat, language = to)
                        stopEarTranslation("rozpoznawanie odmówiło: $powód")
                        return
                    }
                    zRzędu++
                    // W rozmowie cisza jest normalna - ktoś myśli, czyta menu.
                    // Kończy ją przycisk albo "koniec tłumaczenia".
                    if (zRzędu >= EAR_MAX_PUSTYCH && !_earTwoWay.value) {
                        val komunikat = "Nic nie słyszę po $fromName. Kończę tłumaczenie."
                        noteEarMessage(komunikat)
                        audio.speakAndAwait(komunikat, language = to)
                        stopEarTranslation("$EAR_MAX_PUSTYCH nasłuchów bez dźwięku")
                        return
                    }
                    continue
                }
                zRzędu = 0
                when (val decyzja = earSession.rozstrzygnij(usłyszane)) {
                    is pl.victor.app.translation.EarTranslationSession.Decyzja.Koniec -> {
                        audio.speakAndAwait("Kończę tłumaczenie.", language = to)
                        stopEarTranslation("polecenie głosowe")
                        return
                    }
                    is pl.victor.app.translation.EarTranslationSession.Decyzja.Pomiń -> {
                        // Do dziennika, nie tylko do logcata: pominięty tekst
                        // wygląda z zewnątrz dokładnie jak "nie tłumaczy".
                        runCatching {
                            diag.event(
                                DiagFormat.Phase.NASŁUCH, "tłumaczenie ze słuchu: pomijam",
                                mapOf("powód" to decyzja.powód, "usłyszane" to usłyszane.take(60))
                            )
                        }
                    }
                    is pl.victor.app.translation.EarTranslationSession.Decyzja.Tłumacz -> {
                        val przekład = try {
                            translator.translate(decyzja.fragment, from, to)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Tłumaczenie ze słuchu: tłumacz odmówił", e)
                            null
                        }
                        // Tłumacz oddaje ORYGINAŁ, gdy nie dał rady (patrz
                        // SimultaneousTranslator.translate). Mówienie wtedy tego
                        // samego, co przed chwilą padło, jest gorsze niż cisza:
                        // brzmi jak tłumaczenie, a nim nie jest.
                        if (przekład.isNullOrBlank() || przekład == decyzja.fragment) {
                            runCatching {
                                diag.event(
                                    DiagFormat.Phase.NASŁUCH,
                                    "tłumaczenie ze słuchu: brak przekładu",
                                    mapOf("fragment" to decyzja.fragment.take(60))
                                )
                            }
                            continue
                        }
                        runCatching {
                            diag.event(
                                DiagFormat.Phase.NASŁUCH, "tłumaczenie ze słuchu",
                                mapOf(
                                    "usłyszane" to decyzja.fragment.take(60),
                                    "przekład" to przekład.take(60)
                                )
                            )
                        }
                        _earTranscript.value = _earTranscript.value.zSegmentem(
                            decyzja.fragment, przekład, System.currentTimeMillis()
                        )
                        earPartial.value = ""
                        // Czytamy na głos tylko przy włączonym głosie - w panelu
                        // można go wyciszyć, jak u producenta. Wtedy tryb nie
                        // czeka na syntezator i słucha niemal bez przerw.
                        if (settings.isEarTranslationSpoken()) {
                            earSession.zapamiętajWłasnąWypowiedź(przekład)
                            _earTranscript.value = _earTranscript.value
                                .czyta(_earTranscript.value.segmenty.lastOrNull()?.id)
                            // speakAndAwait, nie speak: dopóki mówimy, NIE słuchamy.
                            // Mikrofon okularów wisi centymetry od ich głośnika,
                            // więc nakładanie tych dwóch rzeczy znaczyłoby
                            // tłumaczyć własny głos w kółko. [EarTranslation.jestEchem]
                            // jest drugim zabezpieczeniem, nie pierwszym.
                            try {
                                audio.speakAndAwait(przekład, language = to)
                            } finally {
                                _earTranscript.value = _earTranscript.value.czyta(null)
                            }
                        }
                    }
                }
            }
        } finally {
            // Tylko gdy to wciąż NASZA sesja. Stara pętla, która kończy się
            // po szybkim ponownym włączeniu, wyłączała tu nową i zostawiała ją
            // bez uchwytu - stopEarTranslation nie miał już czego zatrzymać.
            if (earJob === mójJob || earJob == null) {
                // Okulary nadają jeszcze chwilę po ostatnim nasłuchu - bez
                // karencji ten ogon odpalał turę z przypadkowym pytaniem.
                glassesManager.suspendMicWake(false, graceMs = EAR_MIC_WAKE_GRACE_MS)
                // Najpierw flaga, potem nasłuch: resumeWakeWordMic nie oddaje
                // mikrofonu, dopóki tłumaczenie trwa.
                _earTranslation.value = false
                host.resumeWakeWordMic()
                earJob = null
                earPartialJob?.cancel()
                earPartialJob = null
                _earTranscript.value = _earTranscript.value.stop()
                state.value = OrchestratorState.Idle
            }
        }
    }

    /**
     * Tłumaczenie ze słuchu NA ŻYWO - model mowa-na-mowę w chmurze.
     *
     * Telefon słucha rozmówcy, dźwięk płynie do usługi kawałkami po 100 ms, a
     * przekład wraca głosem w trakcie mówienia - do okularów, tą samą drogą co
     * mowa asystenta. Usługa: Gemini przy wybranym Gemini, OpenAI przy OpenAI
     * (patrz [pl.victor.app.translation.LiveTranslationProtocol]).
     *
     * W rozmowie dwustronnej "moja kolej" działa jak dotąd: na jej czas
     * oddajemy mikrofon rozpoznawaniu telefonu i wysyłamy ciszę, żeby sesja
     * nie wygasła.
     *
     * @return `true` - tryb się skończył (wyłączony albo "koniec tłumaczenia");
     *   `false` - na żywo się nie da i trzeba tłumaczyć w telefonie
     */
    private suspend fun earLiveLoop(to: String, mójJob: kotlinx.coroutines.Job?): Boolean {
        if (!settings.isLiveEarTranslationEnabled()) return false
        val (usługa, klucz) = pl.victor.app.translation.LiveTranslationProtocol.wybierz(
            aktywny = settings.getActiveProvider(),
            kluczGemini = settings.getApiKey("gemini"),
            kluczOpenAi = settings.getApiKey("openai")
        ) ?: return false
        val sesja = pl.victor.app.translation.LiveTranslationSession(context, usługa, klucz, to)
        val wykrywanie = pl.victor.app.translation.JęzykRozmówcy(to)
        językRozmówcy = wykrywanie
        val od = System.currentTimeMillis()
        var wysłane = 0
        var przekłady = 0
        try {
            val połączona = sesja.połącz(EAR_LIVE_CONNECT_MS)
            runCatching {
                diag.event(
                    DiagFormat.Phase.NASŁUCH, "tłumaczenie na żywo: połączenie",
                    mapOf(
                        "usługa" to usługa.nazwa, "model" to usługa.model, "na" to to,
                        "ok" to połączona, "ms" to (System.currentTimeMillis() - od),
                        "powód" to sesja.powódZamknięcia
                    )
                )
            }
            if (!połączona) {
                noteEarMessage("Tłumaczenie na żywo (${usługa.nazwa}) niedostępne - tłumaczę w telefonie.")
                return false
            }
            if (!kotlinx.coroutines.withContext(Dispatchers.IO) { sesja.otwórzMikrofon() }) {
                runCatching { diag.event(DiagFormat.Phase.NASŁUCH, "tłumaczenie na żywo: mikrofon zajęty") }
                return false
            }
            state.value = OrchestratorState.Listening
            noteEarMessage("Tłumaczę na żywo przez ${usługa.nazwa}.")
            val mówić = settings.isEarTranslationSpoken()
            // Do dziennika: czy przekład zagra z głośnika telefonu (brak
            // słuchawek) - to tłumaczy ewentualne echo i głośność.
            val naGłośniku = sesja.graNaGłośnikuTelefonu()
            runCatching {
                diag.event(DiagFormat.Phase.NASŁUCH, "tłumaczenie na żywo: wyjście", mapOf("głośnikTelefonu" to naGłośniku))
            }
            val bufor = pl.victor.app.translation.LiveTranscriptBuffer()
            val doZagrania = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)

            fun pokażCzęściowe() {
                _earTranscript.value = _earTranscript.value.copy(
                    częściowyOryginał = bufor.oryginałTeraz,
                    częściowyPrzekład = bufor.przekładTeraz
                )
            }

            fun zamknijZdanie() {
                val (oryginał, przekład) = bufor.zamknij() ?: run { pokażCzęściowe(); return }
                przekłady++
                runCatching {
                    diag.event(
                        DiagFormat.Phase.NASŁUCH, "tłumaczenie na żywo",
                        mapOf("usłyszane" to oryginał.take(60), "przekład" to przekład.take(60))
                    )
                }
                _earTranscript.value = _earTranscript.value.zSegmentem(oryginał, przekład, System.currentTimeMillis())
            }

            return coroutineScope {
                val odtwarzanie = launch(Dispatchers.IO) {
                    for (pcm in doZagrania) sesja.graj(pcm)
                }
                val nadawanie = launch(Dispatchers.IO) {
                    val cisza = ByteArray(sesja.bajtówKawałka)
                    val kawałek = ByteArray(sesja.bajtówKawałka)
                    // Podsumowanie co 10 s - bieg 184: minuty bez jednego zdania
                    // i nic w dzienniku, co by mówiło dlaczego.
                    var odPodsumowania = System.currentTimeMillis()
                    var wysłaneOkno = 0
                    var wyciszoneOkno = 0
                    var maksPoziom = 0
                    var wyciszaOd = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (mojaKolej) {
                            sesja.zamknijMikrofon()
                            sesja.wyślij(cisza)
                            delay(pl.victor.app.translation.LiveTranslationProtocol.KAWAŁEK_MS.toLong())
                            continue
                        }
                        if (!sesja.otwórzMikrofon()) {
                            delay(EAR_TURN_POLL_MS)
                            continue
                        }
                        val pcm = sesja.czytaj(kawałek) ?: break
                        val teraz = System.currentTimeMillis()
                        maksPoziom = maxOf(maksPoziom, sesja.poziom(pcm))
                        if (teraz - odPodsumowania >= EAR_LIVE_SUMMARY_MS) {
                            val źródło = sesja.źródłoMikrofonu()
                            runCatching {
                                diag.event(
                                    DiagFormat.Phase.NASŁUCH, "tłumaczenie na żywo: mikrofon",
                                    mapOf(
                                        "wysłane" to wysłaneOkno, "wyciszone" to wyciszoneOkno,
                                        "maksPoziom" to maksPoziom, "źródło" to źródło,
                                        "przekładKawałków" to sesja.wyjścieKawałków
                                    )
                                )
                            }
                            odPodsumowania = teraz
                            wysłaneOkno = 0
                            wyciszoneOkno = 0
                            maksPoziom = 0
                        }
                        // ZAWSZE półdupleks: bieg 183 - okulary bywały podłączone
                        // tylko w trybie rozmowy (bez A2DP), przekład grał wtedy z
                        // głośnika telefonu i mikrofon łapał go w kółko ("wszystko
                        // słychać po kilka razy"). Także z A2DP głośniki okularów
                        // są otwarte, a telefon jest blisko. Na czas przekładu i
                        // chwilę po nim usługa dostaje ciszę.
                        // Bezpiecznik: wyciszenie dłużej niż EAR_LIVE_MAX_MUTE_MS z
                        // rzędu to już nie echo jednego zdania, tylko błąd - wtedy
                        // lepiej słuchać z echem niż wcale.
                        var echo = sesja.graTeraz(teraz - EAR_LIVE_ECHO_TAIL_MS)
                        if (echo) {
                            if (wyciszaOd == 0L) wyciszaOd = teraz
                            if (teraz - wyciszaOd > EAR_LIVE_MAX_MUTE_MS) echo = false
                        } else {
                            wyciszaOd = 0L
                        }
                        if (!sesja.wyślij(if (echo) cisza else pcm)) break
                        if (echo) wyciszoneOkno++ else wysłaneOkno++
                        wysłane++
                    }
                }
                var wynik: Boolean? = null
                while (wynik == null) {
                    if (!_earTranslation.value || earJob !== mójJob) {
                        wynik = true
                        break
                    }
                    val odebrane = withTimeoutOrNull(EAR_LIVE_POLL_MS) { sesja.zdarzenia.receiveCatching() }
                    val teraz = System.currentTimeMillis()
                    val z = odebrane?.let { it.getOrNull() ?: pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie.Zamknięte }
                    when (z) {
                        is pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie.Dźwięk ->
                            if (mówić) doZagrania.trySend(z.pcm)
                        is pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie.Oryginał -> {
                            bufor.dodajOryginał(z.tekst, teraz)
                            val przed = wykrywanie.pewny
                            wykrywanie.usłyszany(z.język)
                            if (wykrywanie.pewny != przed) {
                                wykrywanie.pewny?.let { _earTranscript.value = _earTranscript.value.copy(z = it) }
                                runCatching {
                                    diag.event(
                                        DiagFormat.Phase.NASŁUCH, "tłumaczenie na żywo: język rozmówcy",
                                        mapOf("język" to wykrywanie.pewny)
                                    )
                                }
                            }
                            if (pl.victor.app.translation.EarTranslation.toKoniec(bufor.oryginałTeraz)) {
                                stopEarTranslation("polecenie głosowe (na żywo)")
                                audio.speak("Kończę tłumaczenie.", language = to)
                                wynik = true
                            } else {
                                pokażCzęściowe()
                            }
                        }
                        is pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie.Przekład -> {
                            bufor.dodajPrzekład(z.tekst, teraz)
                            pokażCzęściowe()
                        }
                        pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie.KoniecTury -> zamknijZdanie()
                        pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie.Zamknięte -> wynik = false
                        else -> Unit
                    }
                    if (wynik == null && bufor.czasZamknąć(teraz)) zamknijZdanie()
                    if (wynik == null && !nadawanie.isActive) wynik = false
                }
                nadawanie.cancel()
                doZagrania.close()
                odtwarzanie.cancel()
                zamknijZdanie()
                if (wynik == false && _earTranslation.value && earJob === mójJob) {
                    // Sesja padła w trakcie - tryb trwa dalej, tylko w telefonie.
                    val komunikat = "Tłumaczenie w chmurze przerwane. Tłumaczę dalej w telefonie."
                    noteEarMessage(komunikat)
                    runCatching {
                        diag.event(
                            DiagFormat.Phase.NASŁUCH, "tłumaczenie na żywo: przerwane",
                            mapOf("powód" to (sesja.powódZamknięcia ?: "mikrofon"))
                        )
                    }
                    audio.speakAndAwait(komunikat, language = settings.getResponseLanguage())
                }
                wynik == true
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { sesja.zamknij() }
            runCatching {
                diag.event(
                    DiagFormat.Phase.NASŁUCH, "tłumaczenie na żywo: koniec sesji",
                    mapOf(
                        "usługa" to usługa.nazwa,
                        "sekund" to ((System.currentTimeMillis() - od) / 1000),
                        "kawałków" to wysłane,
                        "zdań" to przekłady
                    )
                )
            }
        }
    }

    /**
     * Jeden nasłuch w trybie tłumaczenia - tą samą drogą, co pytanie z okularów.
     *
     * @param from język, którego słuchamy; idzie do rozpoznawania jako język
     *   nasłuchu i do transkrypcji nagrania z okularów
     */
    private suspend fun earListenOnce(from: String): String? {
        var micStreamLive = false
        var zatrzymane = false
        val capture =
            if (glassesManager.isConnected()) {
                GlassesVoiceCapture(glassesManager).also { micStreamLive = it.start() }
            } else {
                null
            }
        earListenError = null
        earListenErrorCode = null
        return try {
            state.value = OrchestratorState.Listening
            host.setAsidePhoneTranscript = null
            val heard = host.listenUntilSpeechEnds(
                languageTag = languageTagFor(from),
                capture = capture,
                // MIKROFON TELEFONU JEST TU PRAWOWITYM ŹRÓDŁEM - I TO JEST
                // POPRAWKA DO MOJEJ WŁASNEJ WERSJI.
                //
                // Stało tu `!micStreamLive`, przepisane z tury pytania. Tam ma
                // sens: człowiek mówi DO okularów, a telefon leży w kieszeni.
                // Tu jest inaczej - tłumaczy się cudzą rozmowę, telefon równie
                // dobrze leży na stole, a okulary nadają mikrofon dopiero po
                // wciśnięciu przycisku (`start()` tylko dopisuje się do
                // strumienia, nie każe im nadawać). Przy samej subskrypcji
                // `micStreamLive` jest prawdą, więc wynik z telefonu był
                // ODKŁADANY, choć bywał jedynym, jaki w ogóle był.
                //
                // Nagranie z okularów nic na tym nie traci: niżej i tak idzie
                // `zOkularów ?: heard`, więc gdy okulary coś przyniosą, one
                // wygrywają.
                trustPhoneMicrophone = true,
                useBluetoothMic = pl.victor.app.audio.MicChoice.useBluetoothMic(
                    overSco = false,
                    bleStreamLive = micStreamLive
                ),
                // Tekst na żywo do panelu - i jego przekład, raz na sekundę.
                onPartial = { tekst ->
                    earPartial.value = tekst
                    _earTranscript.value = _earTranscript.value.zCzęściowym(tekst)
                }
            )
            // BŁĄD ŻYWEGO NASŁUCHU - ZAPISANY, ZANIM COŚ GO NADPISZE.
            //
            // `lastFailureReason()` pamięta tylko OSTATNI błąd rozpoznawania. Tuż
            // niżej idzie transkrypcja nagrania z okularów - silnikiem na
            // urządzeniu - i jej błąd przykrywał wynik nasłuchu. Tak było w
            // dzienniku z 23 września: "model języka niepobrany" pochodziło z
            // drogi zapasowej, a pętla uznała je za werdykt o całym trybie i
            // wyłączyła tłumaczenie po jednym podejściu.
            earListenError = speechToText.lastFailureReason()
            earListenErrorCode = speechToText.lastFailureCode
            // Telefon dał tekst, a przepisanie nagrania z okularów milczy od
            // [GLASSES_TRANSCRIPT_MAX_MISSES] prób: każde zdanie czekało 2-4 s
            // na rozkodowanie i przepisanie, które nic nie wnosiło - przekład
            // przychodził przez to wyraźnie po rozmówcy. Nagranie zamykamy obok.
            if (!heard.isNullOrBlank() && transcriber.glassesTranscriptMissedTooOften()) {
                zatrzymane = true
                capture?.let { c ->
                    scope.launch(kotlinx.coroutines.NonCancellable) { runCatching { c.stop() } }
                }
                return heard
            }
            val captured = capture?.stop()
            zatrzymane = true
            val zOkularów = captured?.pcm
                ?.takeIf { captured.hasAudio }
                ?.let { transcriber.transcribeGlassesAudio(it, languageTagFor(from)) }
                ?.also { transcriber.glassesTranscriptMisses = 0 }
            if (zOkularów == null && captured?.hasAudio == true && !heard.isNullOrBlank()) {
                transcriber.glassesTranscriptMisses = transcriber.glassesTranscriptMisses + 1
            }
            zOkularów ?: heard ?: host.setAsidePhoneTranscript
        } finally {
            state.value = OrchestratorState.Idle
            // Przy anulowaniu trybu (albo wyjątku) nasłuch kończy się przed
            // `stop()`, a wtedy strumień BLE zostałby podpięty na zawsze -
            // okulary nadawałyby mimo wyłączonego tłumaczenia.
            if (!zatrzymane) {
                runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        capture?.stop()
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Po tylu nasłuchach bez dźwięku tłumaczenie ze słuchu samo się kończy.
         *
         * Tryb trzyma mikrofon i procesor - zostawiony włączony w kieszeni
         * zjadłby baterię do rana. Pięć pustych nasłuchów to około minuta ciszy;
         * krócej znaczyłoby wyłączać się w przerwie w rozmowie.
         */
        private const val EAR_MAX_PUSTYCH = 5

        /** Tyle najdłużej czeka przekład Twojego zdania z modelu, potem tłumacz w telefonie. */
        private const val MY_TURN_MODEL_MS = 6_000L

        /** Ile czekamy na gotowość sesji na żywo, zanim przejdziemy na telefon. */
        private const val EAR_LIVE_CONNECT_MS = 8_000L

        /** Co ile pętla na żywo zagląda, czy tryb wciąż trwa. */
        private const val EAR_LIVE_POLL_MS = 250L

        /** Po przekładzie z głośnika telefonu mikrofon milczy jeszcze tyle (pogłos). */
        private const val EAR_LIVE_ECHO_TAIL_MS = 700L

        /** Najdłuższe wyciszenie mikrofonu tłumacza z rzędu - patrz bezpiecznik w [earLiveLoop]. */
        private const val EAR_LIVE_MAX_MUTE_MS = 10_000L

        /** Co ile podsumowanie mikrofonu tłumacza w dzienniku. */
        private const val EAR_LIVE_SUMMARY_MS = 10_000L

        /** Ile czekać, aż tura, która włączyła tłumaczenie, posprząta po sobie. */
        private const val EAR_TURN_WAIT_MS = 5_000L

        /** Górna granica jednego nasłuchu tłumaczenia - patrz strażnik w pętli. */
        private const val EAR_LISTEN_MAX_MS = 30_000L

        /** Ten sam znacznik logcata co orkiestrator - filtry logów działają jak dotąd. */
        private const val TAG = "AIOrchestrator"
    }
}
