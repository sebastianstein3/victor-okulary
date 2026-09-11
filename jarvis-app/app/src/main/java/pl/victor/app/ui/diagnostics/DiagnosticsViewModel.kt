package pl.victor.app.ui.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import pl.victor.app.VictorApplication
import pl.victor.app.ble.ConnectionState
import pl.victor.app.ble.GlassesRecordings
import pl.victor.app.ble.GlassesSimulator
import pl.victor.app.ble.VictorManager
import pl.victor.app.ble.MediaCount
import pl.victor.app.ble.NotifyLogEntry
import pl.victor.app.power.BatteryOptimizationHelper

/**
 * ViewModel ekranu diagnostycznego.
 *
 * Ekran ma dwa zastosowania:
 * 1. **Przed przyjściem okularów** - włącz symulację i przejdź całą ścieżkę
 *    aplikacji, żeby wiedzieć, że wszystko poza sprzętem działa.
 * 2. **Po podłączeniu okularów** - podglądaj surowe ramki notify i sprawdzaj
 *    kolejne funkcje pojedynczo, zamiast zgadywać, co poszło nie tak.
 */
class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VictorApplication
    private val manager: VictorManager = app.glassesManager

    val connectionState: StateFlow<ConnectionState> = manager.connectionState
    val batteryLevel: StateFlow<Int?> = manager.batteryLevel
    val isCharging: StateFlow<Boolean> = manager.isCharging
    val glassesIp: StateFlow<String?> = manager.glassesIp
    val mediaCount: StateFlow<MediaCount?> = manager.mediaCount
    val notifyLog: StateFlow<List<NotifyLogEntry>> = manager.notifyLog
    val lastCommand: StateFlow<String?> = manager.lastCommand
    val simulationEnabled: StateFlow<Boolean> = manager.simulationEnabled

    /** Wynik ostatniego testu - jedna linia do pokazania pod przyciskami. */
    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Raport ze sprawdzenia całej ścieżki - osobno od [result].
     *
     * Wspólne pole znaczyłoby, że ten sam tekst pokazuje się jednocześnie w
     * trzech kartach ekranu, bo każda renderuje [result].
     */
    private val _fullCheck = MutableStateFlow<String?>(null)
    val fullCheck: StateFlow<String?> = _fullCheck.asStateFlow()

    /** Postęp pobierania nagrania po BLE. */
    val recordingProgress: StateFlow<Float?> = manager.recordingProgress

    /**
     * Czy Android ma wyłączoną optymalizację baterii dla V.I.C.T.O.R. To jest zwykle
     * prawdziwa przyczyna, gdy wake word albo połączenie z okularami "działa przez
     * chwilę, a potem samo się rozłącza" - Doze usypia proces, zanim cokolwiek
     * w kodzie zdąży zgłosić błąd. Odświeżane w onResume, bo to ekran Ustawień
     * systemowych, nie runtime permission z callbackiem.
     */
    private val _batteryExemptionGranted = MutableStateFlow(
        BatteryOptimizationHelper.isIgnoringOptimizations(application)
    )
    val batteryExemptionGranted: StateFlow<Boolean> = _batteryExemptionGranted.asStateFlow()

    fun refreshBatteryExemption() {
        _batteryExemptionGranted.value = BatteryOptimizationHelper.isIgnoringOptimizations(app)
    }

    /**
     * Numer typu pliku dla kanału nagrań. Producent go nie udokumentował,
     * więc użytkownik musi go znaleźć metodą prób - stąd suwak zamiast stałej.
     */
    private val _recordingFileType = MutableStateFlow(GlassesRecordings.DEFAULT_FILE_TYPE)
    val recordingFileType: StateFlow<Int> = _recordingFileType.asStateFlow()

    fun setRecordingFileType(type: Int) {
        _recordingFileType.value = type.coerceIn(RECORDING_FILE_TYPE_RANGE)
    }

    // === Tryb symulacji ===

    fun setSimulation(enabled: Boolean) {
        app.settings.setGlassesSimulationEnabled(enabled)
        manager.setSimulationEnabled(enabled)
        manager.initialize()
        _result.value = if (enabled) {
            "Symulacja włączona. Kliknij \"Połącz\", żeby zacząć."
        } else {
            "Symulacja wyłączona - aplikacja wróciła na prawdziwe BLE."
        }
    }

    /** W symulacji łączy od razu; na sprzęcie trzeba przejść przez ekran parowania. */
    fun connectSimulated() {
        val sim = manager.simulatorOrNull()
        if (sim == null) {
            _result.value = "To działa tylko w trybie symulacji - na sprzęcie użyj ekranu parowania."
            return
        }
        manager.connect(GlassesSimulator.SIMULATED_MAC)
    }

    fun disconnect() {
        manager.disconnect()
        _result.value = "Rozłączono."
    }

    // === Wstrzykiwanie zdarzeń (tylko symulacja) ===

    fun injectButtonPress() = withSimulator("Nie ma czego wstrzykiwać - symulacja wyłączona.") {
        it.pressButton()
        _result.value = "Wstrzyknięto wciśnięcie przycisku AI."
    }

    /**
     * Udaje DRUGI przycisk okularów: użytkownik sam robi zdjęcie i prosi o opis.
     *
     * Bez tego całą ścieżkę "drugi przycisk -> zdjęcie -> opis" dało się
     * sprawdzić wyłącznie z okularami na głowie.
     */
    fun injectPhotoButton() = withSimulator("Symulacja wyłączona.") {
        it.pressPhotoButton()
        _result.value = "Wstrzyknięto zdjęcie z przycisku (z prośbą o opis)."
    }

    fun injectLowBattery() = withSimulator("Symulacja wyłączona.") {
        it.setBattery(9, isCharging = false)
        _result.value = "Ustawiono baterię na 9%."
    }

    fun injectLowMemory() = withSimulator("Symulacja wyłączona.") {
        it.signalLowMemory()
        _result.value = "Zgłoszono brak pamięci na okularach."
    }

    /**
     * Udaje wybudzenie po stronie okularów - to samo zdarzenie, które przychodzi,
     * gdy okulary usłyszą swoją frazę. Cała ścieżka rozmowy (audio -> nasłuch ->
     * AI -> odpowiedź głosem) da się dzięki temu przejść bez sprzętu na głowie.
     */
    fun injectWakeWord() = withSimulator("Symulacja wyłączona.") {
        it.requestAiSession(realtimeText = false)
        _result.value = "Zgłoszono wybudzenie - powinien ruszyć nasłuch."
    }

    /** Udaje dotknięcie zauszników w trakcie mówienia ("cicho"). */
    fun injectInterrupt() = withSimulator("Symulacja wyłączona.") {
        it.interruptSpeech()
        _result.value = "Zgłoszono przerwanie wypowiedzi."
    }

    /** Udaje zmianę głośności na zausznikach. */
    fun injectVolume() = withSimulator("Symulacja wyłączona.") {
        it.setVolume(7)
        _result.value = "Zgłoszono zmianę głośności."
    }

    /**
     * Sprawdza, czy telefon widzi okulary jako zestaw audio Bluetooth, i mówi
     * przez nie zdanie testowe.
     *
     * To najczęstsza przyczyna "okulary nie mówią": BLE jest połączone (bo to
     * inny kanał), ale część klasyczna nie została sparowana w ustawieniach
     * Bluetooth telefonu - i wtedy dźwięk idzie w głośnik telefonu.
     */
    fun testGlassesAudio() {
        viewModelScope.launch {
            val audio = app.audio
            if (!audio.hasBluetoothAudioDevice()) {
                _result.value = "Telefon NIE widzi żadnego zestawu audio Bluetooth.\n" +
                    "Okulary trzeba sparować osobno, w ustawieniach Bluetooth telefonu " +
                    "(BLE to inny kanał niż dźwięk). Jeśli ich tam nie ma - połącz się " +
                    "najpierw z aplikacji, bo dopiero wtedy okulary włączają część audio."
                return@launch
            }
            val held = audio.beginConversationRouting()
            try {
                // Rozdzielamy odtwarzanie od rozmowy. Zestaw, który wystawia samo
                // A2DP, będzie dobrze MÓWIŁ, ale mikrofon poleci z telefonu -
                // bez tego rozróżnienia wygląda to na losową usterkę.
                _result.value = audio.audioProfileSummary() + "\n\n" + if (held) {
                    "Rozmowa idzie przez zestaw Bluetooth. Mów teraz - powinno " +
                        "być słychać odpowiedź w okularach."
                } else {
                    "Nie udało się przełączyć ROZMOWY na zestaw Bluetooth. " +
                        "Odpowiedź i tak może być słyszalna w okularach (A2DP), " +
                        "ale pytania będzie zbierał mikrofon telefonu."
                }
                audio.speakAndAwait(
                    "Test dźwięku. Jeśli mnie słyszysz w okularach, wszystko gra.",
                    language = app.settings.getResponseLanguage()
                )
            } finally {
                if (held) audio.endConversationRouting()
            }
        }
    }

    /** Statystyki strumienia audio z mikrofonu okularów. */
    val micStats = manager.micStreamStats

    /**
     * Włącza nasłuch strumienia audio z okularów na 10 sekund i mówi, ile
     * przyszło.
     *
     * To jedyny sposób, żeby odróżnić "okulary nie nadają dźwięku" od "nadają,
     * ale nie umiemy tego rozkodować" - bez pomiaru obie sytuacje wyglądają
     * identycznie, czyli jako cisza. Producent bierze ten strumień po BLE i
     * dekoduje go jako Opus; my na razie tylko liczymy pakiety.
     */
    fun testGlassesMicStream() {
        if (manager.connectionState.value != ConnectionState.READY) {
            _result.value = "Najpierw połącz okulary."
            return
        }
        viewModelScope.launch {
            val capture = pl.victor.app.audio.GlassesVoiceCapture(manager)
            // Liczniki zeruje POMIAR, nie przechwytywanie - patrz
            // GlassesVoiceCapture.start(). Dzięki temu wybudzenie okularów w
            // trakcie (a o to wprost prosimy niżej) nie kasuje odliczonych już
            // pakietów.
            manager.resetMicStreamStats()
            val decoderOk = capture.start()
            // Odliczanie na żywo, bo zgłoszono, że "nic się nie dzieje": pomiar
            // trwał kilkanaście sekund w całkowitej ciszy, więc wyglądał jak
            // martwy przycisk. Teraz na ekranie widać, że coś leci, ILE jeszcze
            // i czy pakiety już przychodzą - a nie tylko wynik na końcu.
            for (remaining in MIC_TEST_SECONDS downTo 1) {
                val stats = manager.micStreamStats.value
                _result.value = buildString {
                    append("Nasłuchuję strumienia BLE z okularów: ")
                    append(remaining).append(" s\n")
                    append("TERAZ wybudź okulary - naciśnij przycisk AI albo powiedz ")
                    append("słowo kluczowe - i mów.\n")
                    append("Same okulary nie nadają dźwięku bez wybudzenia, więc ")
                    append("czekanie w ciszy zawsze da zero.\n")
                    append("Pakiety: ").append(stats.packets)
                    append(" (").append(stats.bytes).append(" B)")
                }
                kotlinx.coroutines.delay(1_000)
            }
            val result = capture.stop()
            _result.value = buildString {
                append(result.describe()).append("\n\n")
                when {
                    result.packets == 0 -> append(
                        "Jeśli w trakcie pomiaru okulary były wybudzone, to znaczy, że " +
                            "ten egzemplarz nie nadaje mikrofonu tą drogą. Zostaje ścieżka " +
                            "przez klasyczny Bluetooth - sparuj okulary jako zestaw " +
                            "słuchawkowy w ustawieniach Bluetooth telefonu."
                    )
                    !decoderOk -> append(
                        "To urządzenie nie ma systemowego dekodera Opusa, więc strumienia " +
                            "nie da się tu wykorzystać - ale okulary NADAJĄ, co jest " +
                            "najważniejszą informacją z tego pomiaru."
                    )
                    result.hasAudio -> append(
                        "Ścieżka po BLE DZIAŁA: dźwięk z mikrofonu okularów da się " +
                            "rozkodować. Aplikacja użyje jej automatycznie, gdy zwykłe " +
                            "rozpoznawanie mowy nic nie usłyszy."
                    )
                    else -> append(
                        "Pakiety przychodzą, ale to nie jest goły strumień Opusa - " +
                            "prawdopodobnie ma własną ramkę producenta. Podgląd " +
                            "pierwszego pakietu wyżej wystarczy, żeby to rozstrzygnąć."
                    )
                }
            }

            // Drugi etap pomiaru: czy z tego dźwięku powstaje TEKST. To jest
            // jedyny sposób, żeby sprawdzić na sprzęcie, czy rozpoznawanie na
            // urządzeniu przyjmuje własne źródło dźwięku (EXTRA_AUDIO_SOURCE).
            // Bez tego sprawdzenia niepowodzenie tej drogi byłoby niewidzialne:
            // aplikacja po cichu wysyłałaby nagranie do modelu i wyglądałoby to
            // tak samo jak wcześniej.
            if (result.hasAudio) {
                appendPlayback(result)
                appendTranscription(result)
            }
        }
    }

    /**
     * Odtwarza rozkodowane nagranie i ocenia jego głośność.
     *
     * ## Dlaczego to jest najważniejszy krok tego pomiaru
     * Liczby nie rozstrzygają, czy dźwięk jest DOBRY. "Rozkodowano 300
     * pakietów" wygląda tak samo, gdy ramkowanie jest poprawne i gdy dekoder
     * dostał źle poskładane dane i oddał szum. Ucho rozstrzyga to w sekundę -
     * i to jest jedyny sposób, żeby na sprzęcie odróżnić "model nie rozumie
     * pytania, bo dźwięk jest zły" od "model nie rozumie, bo model".
     */
    private suspend fun appendPlayback(result: pl.victor.app.audio.GlassesVoiceCapture.Result) {
        val pcm = result.pcm ?: return
        val base = _result.value
        val loudness = pl.victor.app.audio.PcmPlayer.loudness(pcm)
        _result.value = "$base\n\nOdtwarzam to, co rozkodowałem - posłuchaj…"
        withContext(Dispatchers.IO) {
            pl.victor.app.audio.PcmPlayer.play(pcm, pl.victor.app.audio.OpusDecoder.SAMPLE_RATE)
        }
        _result.value = buildString {
            append(base).append("\n\n")
            append("Odsłuchane. Głośność nagrania: ")
            append("%.0f".format(loudness * 100)).append("%.\n")
            append(
                when {
                    // Same zera - dekoder "zadziałał", ale nic nie oddał.
                    loudness < QUIET_LEVEL ->
                        "To jest praktycznie cisza. Mikrofon okularów nie zbiera " +
                            "dźwięku albo nie mówiłeś w trakcie pomiaru."
                    // Wartości przypadkowe brzmią głośno i szumią.
                    loudness > NOISE_LEVEL ->
                        "Tak głośne nagranie to zwykle szum, a nie mowa - pakiety " +
                            "składają się w zły sposób. Jeśli słyszałeś trzask " +
                            "zamiast swojego głosu, to jest właśnie to."
                    else ->
                        "Poziom typowy dla mowy. Jeśli słyszałeś swój głos, ścieżka " +
                            "dźwięku działa i model dostaje to samo, co Ty."
                }
            )
        }
    }

    /** Dopisuje do wyniku pomiaru próbę przepisania nagrania na tekst. */
    private suspend fun appendTranscription(result: pl.victor.app.audio.GlassesVoiceCapture.Result) {
        val base = _result.value
        _result.value = "$base\n\nPrzepisuję nagranie na tekst…"
        val pcm = result.pcm
        if (pcm == null) {
            _result.value = "$base\n\nBrak próbek do przepisania."
            return
        }
        val speechPcm = pl.victor.app.audio.PcmResampler.resample(
            pcm = pcm,
            sourceRate = pl.victor.app.audio.OpusDecoder.SAMPLE_RATE
        )
        val text = runCatching {
            pl.victor.app.conversation.SpeechToText(app).transcribe(
                pcm = speechPcm,
                sampleRate = pl.victor.app.audio.PcmResampler.SPEECH_SAMPLE_RATE,
                languageTag = java.util.Locale.getDefault().toLanguageTag()
            )
        }.getOrNull()

        _result.value = buildString {
            append(base).append("\n\n")
            if (!text.isNullOrBlank()) {
                append("TRANSKRYPCJA NA URZĄDZENIU DZIAŁA. Usłyszałem:\n\"")
                append(text).append("\"\n")
                append("To znaczy, że model dostaje TEKST, a nie nagranie - działa więc ")
                append("wykrywanie komend i rozpoznawanie pytań o to, co widać.")
            } else {
                append("Nie udało się przepisać nagrania na tekst na tym urządzeniu. ")
                append("Nagranie pójdzie do modelu jako dźwięk (działa tylko z Gemini). ")
                append("Sprawdź krok 3 w \"Sprawdź całą ścieżkę\" - najczęściej brakuje ")
                append("pobranego polskiego modelu rozpoznawania offline.")
            }
        }
    }


    // === Sprawdzenie całej ścieżki ===

    /**
     * Przechodzi po kolei przez wszystko, co musi zadziałać, żeby okulary były
     * użyteczne, i mówi, KTÓRY etap nie działa.
     *
     * ## Po co osobny przycisk, skoro są testy pojedynczych funkcji
     * Bo "AI nie reaguje" nie wskazuje etapu. Ścieżka od wybudzenia do
     * odpowiedzi ma siedem ogniw - BLE, zgoda na mikrofon, rozpoznawanie mowy,
     * profil audio, syntezator, klucz do modelu, aparat - i awaria KAŻDEGO
     * wygląda tak samo: cisza. Testy pojedyncze wymagały wiedzy, którego z nich
     * użyć; ten przycisk sprawdza wszystkie po kolei i pokazuje raport.
     *
     * Wynik dopisuje się na bieżąco, żeby było widać postęp, a nie martwy ekran.
     */
    fun runFullCheck() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val report = StringBuilder("SPRAWDZENIE CAŁEJ ŚCIEŻKI\n")
            fun step(line: String) {
                report.append(line).append('\n')
                _fullCheck.value = report.toString()
            }
            try {
                step(checkGlasses())
                step(checkMicPermission())
                step(checkSpeechRecognition())
                step(checkAudioRoute())
                step(checkTts())
                step(checkAiProvider())
                step(checkCamera())
                step(checkGoogleSignIn())
                step(checkDiagnosticLog())
                step(checkCommandChannel())
                step(checkGlassesStorage())
                step(checkMediaDownload())
                report.append('\n').append(verdict(report.toString()))
                // Część rzeczy nie da się zmierzyć przyciskiem - patrz stała.
                report.append("\n\n").append(MANUAL_CHECKLIST)
                _fullCheck.value = report.toString()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun checkGlasses(): String {
        val state = manager.connectionState.value
        return if (state == ConnectionState.READY) {
            val battery = manager.batteryLevel.value
            "✅ 1. Okulary połączone (BLE)" + (battery?.let { ", bateria $it%" } ?: "")
        } else {
            "❌ 1. Okulary NIE są połączone (stan: $state).\n" +
                "   → Ekran Parowanie. Bez tego nic dalej nie zadziała."
        }
    }

    private fun checkMicPermission(): String {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (granted) {
            "✅ 2. Zgoda na mikrofon jest"
        } else {
            "❌ 2. BRAK zgody na mikrofon.\n" +
                "   → Ekran główny, przycisk Powiedz - system zapyta. To jest\n" +
                "     najczęstsza przyczyna \"wybudzenie działa, ale potem cisza\"."
        }
    }

    /**
     * Rozpoznawanie mowy - osobno ogólne i osobno TO NA URZĄDZENIU.
     *
     * Rozdzielenie ich jest tu najważniejsze. Rozpoznawanie na urządzeniu
     * decyduje o dwóch rzeczach, o które zgłoszono pretensje: czy V.I.C.T.O.R.
     * rozumie mowę przy ZABLOKOWANYM telefonie, i czy mowa z okularów staje się
     * tekstem, zamiast lecieć do modelu jako nagranie. Bez niego jedno i drugie
     * działa gorzej, ale aplikacja nie ma jak tego powiedzieć - a to nie jest
     * usterka do naprawienia w kodzie, tylko jeden brakujący pakiet językowy.
     */
    private fun checkSpeechRecognition(): String {
        val available = runCatching {
            android.speech.SpeechRecognizer.isRecognitionAvailable(app)
        }.getOrDefault(false)
        if (!available) {
            return "❌ 3. To urządzenie NIE ma rozpoznawania mowy.\n" +
                "   → Zainstaluj Google (aplikację) albo używaj klawiatury."
        }
        val onDevice =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                runCatching {
                    android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
                }.getOrDefault(false)
        return if (onDevice) {
            "✅ 3. Rozpoznawanie mowy dostępne, również NA URZĄDZENIU\n" +
                "   (działa przy zgaszonym ekranie i przepisuje mowę z okularów)"
        } else {
            "⚠️ 3. Rozpoznawanie mowy działa, ale BRAK wersji na urządzeniu.\n" +
                "   → Bez niej przy zablokowanym telefonie mowa bywa gubiona, a\n" +
                "     dźwięk z okularów idzie do modelu jako nagranie zamiast\n" +
                "     tekstu (drożej i bez wykrywania komend).\n" +
                "   → Ustawienia telefonu → System → Języki i metody wprowadzania\n" +
                "     → Wprowadzanie głosowe → Google → Rozpoznawanie mowy offline\n" +
                "     → pobierz polski. Wymaga Androida 13 lub nowszego."
        }
    }

    private fun checkAudioRoute(): String {
        val audio = app.audio
        if (!audio.hasBluetoothAudioDevice()) {
            return "❌ 4. Telefon nie widzi okularów jako urządzenia audio.\n" +
                "   → Sparuj je DODATKOWO w ustawieniach Bluetooth telefonu.\n" +
                "     BLE (to połączenie) i dźwięk to dwa różne kanały."
        }
        val mic = audio.hasConversationMic()
        val micOn = app.settings.isGlassesMicEnabled()
        return buildString {
            append("✅ 4. Dźwięk przez Bluetooth działa\n")
            append("   ").append(audio.conversationDeviceName() ?: "urządzenie bez nazwy").append('\n')
            if (mic && micOn) {
                append("   Mikrofon okularów dostępny (profil rozmowy SCO/HFP)")
            } else if (mic) {
                append("   Mikrofon okularów jest, ale wyłączony w Ustawieniach -\n")
                append("   pytania zbiera telefon")
            } else {
                append("   ⚠️ Tylko odtwarzanie (A2DP), bez profilu rozmowy -\n")
                append("   pytania zbierze mikrofon telefonu.\n")
                append("   Zostaje druga droga: dźwięk po BLE prosto do modelu.\n")
                append("   Sprawdź ją przyciskiem \"Zmierz strumień z mikrofonu\".")
            }
        }
    }

    private suspend fun checkTts(): String {
        val audio = app.audio
        if (!audio.ttsReady.value) {
            return "❌ 5. Syntezator mowy nie wystartował.\n" +
                "   → Ustawienia Androida > Zamiana tekstu na mowę."
        }
        val voice = audio.currentVoice.value
        val spoke = audio.speakAndAwait("Sprawdzam dźwięk.", app.settings.getResponseLanguage())
        return if (spoke) {
            "✅ 5. Syntezator mówi" + (voice?.let { ", głos: ${it.displayName}" } ?: "") +
                "\n   Czy to było słychać W OKULARACH? Jeśli w telefonie -\n" +
                "   dźwięk nie idzie przez zestaw."
        } else {
            "⚠️ 5. Syntezator jest, ale wypowiedź się nie zakończyła.\n" +
                "   → Sprawdź głośność i czy nic innego nie gra."
        }
    }

    private suspend fun checkAiProvider(): String {
        val settings = app.settings
        val providerId = settings.getActiveProvider()

        // Model lokalny nie ma klucza ani listy modeli u providera - dla niego
        // "gotowy" znaczy "plik pobrany". Bez tej gałęzi sprawdzenie mówiło
        // użytkownikowi trybu offline, że brakuje mu klucza API, którego z
        // definicji nie potrzebuje.
        if (providerId == pl.victor.app.ai.AIProviderFactory.LOCAL_PROVIDER_ID) {
            val entry = pl.victor.app.localmodel.LocalModelCatalog.QWEN_0_8B
            val ready = pl.victor.app.localmodel.LocalModelStorage.isDownloaded(app, entry)
            return if (ready) {
                "✅ 6. Model lokalny (${entry.displayName}) pobrany i gotowy"
            } else {
                "❌ 6. Model lokalny nie jest pobrany.\n" +
                    "   → Ustawienia > Model AI > pobierz model offline."
            }
        }

        val key = settings.getApiKey(providerId)
        if (key.isNullOrBlank()) {
            return "❌ 6. Brak klucza API dla providera \"$providerId\".\n" +
                "   → Ustawienia > Model AI."
        }
        val models = pl.victor.app.data.RemoteModelValidator(key, providerId)
            .fetchAvailableModels()
        val selected = settings.getSelectedModel(providerId)
        return when {
            models.isEmpty() ->
                "⚠️ 6. Nie udało się pobrać listy modeli dla \"$providerId\".\n" +
                    "   Klucz może być zły albo nie ma internetu - albo ten\n" +
                    "   provider po prostu nie udostępnia takiej listy."
            selected != null && !models.contains(selected) ->
                "⚠️ 6. Klucz działa, ale wybrany model \"$selected\" nie jest\n" +
                    "   na liście dostępnych (${models.size} modeli).\n" +
                    "   → Ustawienia > Model AI, wybierz z listy."
            else ->
                "✅ 6. Klucz do \"$providerId\" działa (${models.size} modeli)" +
                    (selected?.let { ", wybrany: $it" } ?: "")
        }
    }

    /**
     * Aparat - z rozróżnieniem URWANEGO transferu.
     *
     * Sam nagłówek JPEG nie znaczy, że przyszło całe zdjęcie: transfer urwany w
     * jednej trzeciej ma poprawne trzy pierwsze bajty i do niedawna przechodził
     * jako zdrowy obraz. Model dostawał wtedy pół kadru i odpowiadał o niczym,
     * a z zewnątrz wyglądało to jak "AI zmyśla". To są DWIE różne awarie i
     * wymagają dwóch różnych rzeczy, więc muszą być rozróżnione tutaj.
     */
    private suspend fun checkCamera(): String {
        if (manager.connectionState.value != ConnectionState.READY) {
            return "⏭️ 7. Aparat pominięty - okulary nie są połączone."
        }
        val bytes = manager.capturePhoto()
            ?: return "❌ 7. Zdjęcie NIE przyszło.\n" +
                "   → Pytania o to, co widzisz, nie zadziałają.\n" +
                "     Spróbuj rozłączyć i połączyć okulary."
        val kb = bytes.size / 1024
        return when {
            pl.victor.app.ble.GlassesProtocol.isCompleteJpeg(bytes) ->
                "✅ 7. Zdjęcie z okularów przyszło W CAŁOŚCI ($kb kB)"
            pl.victor.app.ble.GlassesProtocol.looksLikeJpeg(bytes) ->
                "⚠️ 7. Zdjęcie przyszło URWANE ($kb kB).\n" +
                    "   Jest nagłówek JPEG, ale brakuje znacznika końca - transfer\n" +
                    "   nie doszedł do końca. Model dostanie pół kadru i odpowie\n" +
                    "   nie na temat.\n" +
                    "   → Podejdź bliżej telefonu i powtórz."
            else ->
                "❌ 7. Przyszło ${bytes.size} B, ale to NIE jest zdjęcie.\n" +
                    "   → Transfer urwał się na samym początku."
        }
    }

    /**
     * Dziennik diagnostyczny - i to jest teraz pozycja, która blokuje resztę.
     *
     * Bez wysyłki nikt poza telefonem nie zobaczy, co się stało, a wszystkie
     * pytania typu "dlaczego nie zrobiło zdjęcia" wracają do zgadywania.
     * Sprawdzenie jest ŚWIADOMIE na żywo - próbuje naprawdę wysłać, bo sam
     * fakt wklejonego tokenu niczego nie dowodzi: token bywa wygasły, bez
     * uprawnienia do zapisu albo wystawiony na inne repozytorium.
     */
    private suspend fun checkDiagnosticLog(): String {
        val settings = app.settings
        if (!settings.isDiagnosticLogEnabled()) {
            return "❌ 9. Dziennik diagnostyczny jest WYŁĄCZONY.\n" +
                "   → Ustawienia → 🩺 Dziennik diagnostyczny."
        }
        val token = settings.getGithubToken()
        if (token.isBlank()) {
            return "❌ 9. Brak tokenu - dziennik zostaje w telefonie.\n" +
                "   → Ustawienia → 🩺 Dziennik diagnostyczny, wklej token\n" +
                "     z uprawnieniem \"Contents: Read and write\"."
        }
        val file = app.diag.currentFile()
        val content = app.diag.readSession()
        if (file == null || content.isBlank()) {
            return "⚠️ 9. Dziennik jest pusty - nie ma jeszcze czego wysłać."
        }
        return pl.victor.app.diagnostics.DiagnosticUploader(token)
            .upload(file.name, content)
            .fold(
                onSuccess = { "✅ 9. Dziennik wysłany (${content.length / 1024} kB)" },
                onFailure = {
                    "❌ 9. Dziennik NIE poszedł: ${it.message}\n" +
                        "   → Najczęściej: token wygasł, nie ma uprawnienia\n" +
                        "     \"Contents: Read and write\" albo dotyczy innego repo."
                }
            )
    }

    /**
     * Kanał komend osobno od kanału danych.
     *
     * Te dwa potrafią żyć niezależnie: zdarzenia przychodzą, a na komendy nie
     * ma ani jednej odpowiedzi. Bez rozdzielenia obie awarie wyglądają
     * identycznie - "okulary nie reagują".
     */
    private fun checkCommandChannel(): String {
        if (manager.connectionState.value != ConnectionState.READY) {
            return "⏭️ 10. Kanał komend pominięty - okulary nie są połączone."
        }
        return if (manager.glassesAnswerCommands) {
            "✅ 10. Okulary odpowiadają na komendy"
        } else {
            "⚠️ 10. Okulary nie odpowiedziały jeszcze na ŻADNĄ komendę.\n" +
                "   Pobieranie plików idzie innym kanałem niż komendy sterujące,\n" +
                "   więc zdjęcia mogą działać mimo tego. Jeśli działają - padł\n" +
                "   kanał komend, a nie aparat."
        }
    }

    /**
     * Ile plików leży w pamięci okularów.
     *
     * Liczba sama w sobie nic nie znaczy - znaczenie ma jej ZMIANA między
     * uruchomieniami. Rosnąca oznacza, że pliki zostają w okularach po
     * pobraniu, więc pamięć kiedyś się zapełni, a wtedy nie da się zrobić
     * kolejnego zdjęcia. Dlatego wynik trzeba porównać z poprzednim.
     */
    private suspend fun checkGlassesStorage(): String {
        if (manager.connectionState.value != ConnectionState.READY) {
            return "⏭️ 11. Pamięć okularów pominięta - okulary nie są połączone."
        }
        val done = kotlinx.coroutines.CompletableDeferred<Triple<Int, Int, Int>>()
        manager.requestMediaCount { images, videos, records ->
            done.complete(Triple(images, videos, records))
        }
        val counts = kotlinx.coroutines.withTimeoutOrNull(MEDIA_COUNT_TIMEOUT_MS) { done.await() }
            ?: return "⚠️ 11. Okulary nie podały liczby plików w " +
                "${MEDIA_COUNT_TIMEOUT_MS / 1000} s.\n" +
                "   Patrz pozycja 10 - to może być ten sam kanał komend."
        val (images, videos, records) = counts
        return "✅ 11. W pamięci okularów: $images zdjęć, $videos wideo, " +
            "$records nagrań\n" +
            "   Zapisz tę liczbę. Jeśli przy następnym sprawdzeniu urośnie,\n" +
            "   to pliki NIE są kasowane po pobraniu i pamięć się zapełni."
    }

    /**
     * Pobieranie PEŁNYCH plików - czyli to, czym żyje galeria w aplikacji.
     *
     * To jest inna droga niż miniatura, którą dostaje model: miniatura idzie po
     * BLE, pełny plik po Wi-Fi Direct. Dlatego AI potrafi opisać zdjęcie,
     * którego w galerii nie ma - i odwrotnie. Bez rozdzielenia tych dwóch
     * ścieżek zgłoszenie "nie pobiera multimediów" nie ma jak zostać
     * rozstrzygnięte.
     */
    private suspend fun checkMediaDownload(): String {
        if (manager.connectionState.value != ConnectionState.READY) {
            return "⏭️ 12. Pobieranie do galerii pominięte - okulary nie są połączone."
        }
        if (manager.ensureTransferMode() == null) {
            return "❌ 12. Wi-Fi Direct nie wystartował.\n" +
                "   " + (manager.lastTransferFailure
                    ?: "Okulary nie zgłosiły adresu w 15 s.") + "\n" +
                "   → TĄ drogą idą pełne pliki do galerii. Miniatury dla modelu\n" +
                "     idą po BLE i działają niezależnie, więc AI może widzieć\n" +
                "     zdjęcia, których w galerii nie ma."
        }
        val files = manager.getMediaFileList()
        return if (files.isEmpty()) {
            "⚠️ 12. Wi-Fi Direct działa, ale okulary nie zgłosiły żadnych plików."
        } else {
            "✅ 12. Wi-Fi Direct działa, okulary widzą ${files.size} plików"
        }
    }

    /** Jedno zdanie na koniec - żeby nie trzeba było czytać całego raportu. */
    /**
     * Konfiguracja logowania Google - z danymi do wpisania w Google Cloud.
     *
     * ## Dlaczego to jest krok sprawdzenia, a nie ukryta ciekawostka
     * Odmowa logowania z kodem DEVELOPER_ERROR nie da się naprawić w aplikacji:
     * klient OAuth jest wiązany z PARĄ (nazwa pakietu, odcisk SHA-1 podpisu), a
     * te dane trzeba wpisać po stronie Google. Bez pokazania ich tutaj
     * użytkownik musiałby wyciągać je z APK narzędziem `keytool` na komputerze -
     * czyli w praktyce nie miałby jak.
     */
    private fun checkGoogleSignIn(): String {
        val pkg = pl.victor.app.utils.AppSignature.packageName(app)
        val sha1 = pl.victor.app.utils.AppSignature.sha1(app)
        val connected = runCatching {
            pl.victor.app.google.GoogleAccountManager(app).isSignedIn()
        }.getOrDefault(false)

        return buildString {
            if (connected) {
                append("✅ 8. Konto Google połączone (Kalendarz i poczta działają)")
                return@buildString
            }
            append("⚠️ 8. Konto Google NIE jest połączone.\n")
            append("   Jeśli przy logowaniu widzisz \"klient OAuth nie jest\n")
            append("   skonfigurowany\", wpisz w Google Cloud Console te DWIE\n")
            append("   wartości dla klienta OAuth typu Android:\n")
            append("   • nazwa pakietu: ").append(pkg).append('\n')
            append("   • odcisk SHA-1:  ").append(sha1 ?: "(nie udało się odczytać)")
        }
    }

    private fun verdict(report: String): String = when {
        report.contains("❌") -> "WNIOSEK: coś nie działa - napraw pozycje z ❌ od góry."
        report.contains("⚠️") -> "WNIOSEK: podstawy działają, ale zobacz ostrzeżenia ⚠️."
        else -> "WNIOSEK: cała ścieżka działa. Wybudź okulary i zadaj pytanie."
    }

    private inline fun withSimulator(onMissing: String, block: (GlassesSimulator) -> Unit) {
        val sim = manager.simulatorOrNull()
        if (sim == null) _result.value = onMissing else block(sim)
    }

    // === Testy funkcji - działają tak samo na symulatorze i na sprzęcie ===

    fun testBattery() {
        manager.requestBatteryLevel()
        _result.value = "Wysłano zapytanie o baterię - odpowiedź pojawi się w dzienniku."
    }

    fun testMediaCount() {
        manager.requestMediaCount { images, videos, records ->
            _result.value = "Na okularach: $images zdjęć, $videos wideo, $records nagrań."
        }
    }

    fun testPhoto() = runTest("Zdjęcie") {
        val bytes = manager.capturePhoto()
        if (bytes == null) {
            "Zdjęcie NIE dotarło (brak odpowiedzi w limicie czasu)."
        } else {
            "Zdjęcie OK: ${bytes.size} B, nagłówek ${bytes.take(2).joinToString(" ") { b ->
                "%02X".format(b)
            }}."
        }
    }

    fun testVideo() {
        manager.startVideoRecording()
        _result.value = "Rozpoczęto nagrywanie wideo - zatrzymaj drugim przyciskiem."
    }

    fun stopVideo() {
        manager.stopVideoRecording()
        _result.value = "Zatrzymano nagrywanie wideo."
    }

    fun testAudio() {
        manager.startAudioRecording()
        _result.value = "Rozpoczęto nagrywanie audio."
    }

    fun stopAudio() {
        manager.stopAudioRecording()
        _result.value = "Zatrzymano nagrywanie audio."
    }

    fun testTransferMode() {
        manager.enableTransferMode()
        _result.value = "Włączono tryb transferu - czekam na ramkę 0x08 z adresem IP."
    }

    fun testFileList() = runTest("Lista plików") {
        // Sam włącz tryb transferu i poczekaj na adres - wcześniej trzeba było
        // zrobić to ręcznie osobnym przyciskiem i trafić w moment, w którym
        // okulary już zgłosiły IP.
        if (manager.ensureTransferMode() == null) {
            // Prawdziwy powód, nie sam fakt niepowodzenia. Najczęstsze przyczyny
            // to wyłączone Wi-Fi albo wyłączona systemowa Lokalizacja - patrz
            // WifiDirectDiagnosis. Bez tego zdania diagnostyka odsyłała do
            // "Reset P2P", który przy zgaszonym radiu nic nie da.
            return@runTest (manager.lastTransferFailure
                ?: "Okulary nie zgłosiły adresu Wi-Fi Direct w 15 s.") +
                "\n\nNagrania da się wylistować także bez Wi-Fi Direct - patrz karta niżej."
        }
        val files = manager.getMediaFileList()
        if (files.isEmpty()) "Okulary nie zgłosiły żadnych plików."
        else "Plików: ${files.size}. Pierwsze: ${files.take(3).joinToString(", ")}"
    }

    fun testDownloadPhoto() = runTest("Pobranie zdjęcia") {
        if (manager.ensureTransferMode() == null) {
            return@runTest manager.lastTransferFailure
                ?: "Okulary nie zgłosiły adresu Wi-Fi Direct w 15 s."
        }
        val bytes = manager.downloadLatestPhoto()
        if (bytes == null) "Nie udało się pobrać zdjęcia przez Wi-Fi Direct."
        else "Pobrano zdjęcie: ${bytes.size} B."
    }

    /**
     * Lista nagrań kanałem BLE - działa bez Wi-Fi Direct, więc jest to droga
     * awaryjna, gdy grupa P2P nie chce się podnieść.
     */
    fun testListRecordings() = runTest("Nagrania (BLE)") {
        val type = _recordingFileType.value
        val list = manager.listRecordings(type)
        if (list.isEmpty()) {
            "Typ $type: brak nagrań. Nagraj coś przyciskiem albo spróbuj innego typu pliku."
        } else {
            "Typ $type: ${list.size} nagrań. Pierwsze: " +
                list.take(3).joinToString(", ") { "${it.fileName} (${it.lengthBytes} B)" }
        }
    }

    fun testDownloadRecording() = runTest("Pobranie nagrania (BLE)") {
        val type = _recordingFileType.value
        val first = manager.listRecordings(type).firstOrNull()
            ?: return@runTest "Typ $type: nie ma czego pobrać."
        val bytes = manager.downloadRecording(first.fileName, type)
        if (bytes == null) "Nie udało się pobrać ${first.fileName}."
        else "Pobrano ${first.fileName}: ${bytes.size} B."
    }

    fun resetP2p() {
        manager.resetP2p()
        _result.value = "Wysłano reset P2P."
    }

    /**
     * Czy okulary odpowiedziały już na jakąkolwiek komendę sterującą.
     *
     * Zdarzenia (przycisk, stan) idą INNYM kanałem niż odpowiedzi na komendy,
     * więc jedno potrafi działać bez drugiego - a wtedy obie awarie wyglądają
     * z zewnątrz tak samo: cisza po naciśnięciu.
     */
    fun commandsAnswered(): Boolean = manager.glassesAnswerCommands

    fun clearLog() {
        manager.clearNotifyLog()
        _result.value = null
    }

    /**
     * Cały dziennik ramek jako tekst do wklejenia.
     *
     * Najstarsze na górze, odwrotnie niż na ekranie: czytając zgłoszenie chce
     * się widzieć kolejność zdarzeń, a nie ostatnie na początku.
     *
     * Bez tego surowe ramki zostawały w telefonie: przepisywanie
     * szesnastkowych bajtów z ekranu jest na tyle żmudne, że w praktyce nikt
     * tego nie robi - a bez nich nie da się potwierdzić, co okulary naprawdę
     * wysyłają.
     */
    fun notifyLogAsText(): String {
        val entries = manager.notifyLog.value
        if (entries.isEmpty()) return "Dziennik pusty."
        return entries.reversed().joinToString("\n") { entry ->
            val time = LOG_TIME_FORMAT.format(Date(entry.timestampMs))
            "$time  ${entry.hex}  # ${entry.meaning}"
        }
    }

    /**
     * Uruchamia test w tle i zapisuje wynik. Wyjątki są łapane celowo -
     * ekran diagnostyczny ma pokazać błąd, a nie wywalić aplikację.
     */
    private companion object {
        /** Ile czekamy, aż okulary podadzą liczbę plików. */
        const val MEDIA_COUNT_TIMEOUT_MS = 8_000L

        /**
         * Czego żaden przycisk nie zmierzy.
         *
         * ## Po co to tutaj, a nie w dokumentacji
         * Bo czyta się to Z OKULARAMI NA GŁOWIE, w chwili testowania, a nie przy
         * komputerze. Każda pozycja odpowiada usterce, która była naprawiana
         * "na ślepo" i której skutek słychać, ale nie widać w żadnym pomiarze.
         * Bez takiej listy zgłoszenie wraca jako "dalej nie działa", co nie
         * wskazuje, KTÓRA z kilku poprawek nie zadziałała.
         */
        val MANUAL_CHECKLIST = """
            DO SPRAWDZENIA UCHEM - tego nie zmierzy żaden przycisk.
            Rób po kolei, po każdej turze dziennik wysyła się sam.

            A. Zadaj JEDNO pytanie i wysłuchaj całej odpowiedzi.
               • Czy zdania są całe, czy któreś urywa się w pół słowa?
               • Czy odpowiedź NIE została przeczytana drugi raz od nowa?

            B. Zadaj drugie pytanie zaraz po pierwszym.
               • Czy odpowiedź dotyczy TEGO pytania, czy czegoś innego?
               • Czy tura się kończy, czy zawiesza?

            C. Zapytaj "co widzisz".
               • Czy odpowiedź dotyczy tego, na co patrzysz?
               • Jeśli opis jest ogólnikowy - patrz pozycja 7 wyżej,
                 zdjęcie mogło przyjść urwane.

            D. Odejdź, aż okulary się rozłączą, wróć i zadaj pytanie.
               • Czy mikrofon nadal działa po samoczynnym powrocie?

            E. Po zakończonej turze włącz muzykę z telefonu.
               • Czy gra przez okulary jak multimedia, czy jak przez telefon?

            F. Powiedz coś, co wywoła akcję ("wyślij SMS do...").
               • Czy pytanie o potwierdzenie wymienia WSZYSTKO,
                 co ma się wykonać - a nie tylko pierwszą rzecz?

            G. Zajrzyj do Galerii okularów.
               • Pliki są w PAMIĘCI OKULARÓW. Aplikacja nic stamtąd nie
                 kasuje i nic nie pobiera automatycznie - patrz pozycja 11.
        """.trimIndent()

        /** Poniżej tego poziomu nagranie jest praktycznie ciszą. */
        const val QUIET_LEVEL = 0.01

        /** Powyżej tego poziomu to zwykle szum, a nie mowa. */
        const val NOISE_LEVEL = 0.45

        /** Rozsądny zakres do przeszukania; typów jest niewiele. */
        val RECORDING_FILE_TYPE_RANGE = 0..7

        /** Okno pomiaru strumienia z mikrofonu - tyle, żeby zdążyć wybudzić i coś powiedzieć. */
        const val MIC_TEST_SECONDS = 15
    }

    private fun runTest(label: String, block: suspend () -> String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _result.value = "$label: w toku..."
            _result.value = try {
                block()
            } catch (e: Exception) {
                "$label - błąd: ${e.message ?: e::class.java.simpleName}"
            } finally {
                _busy.value = false
            }
        }
    }
}

private val LOG_TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
