package pl.victor.app.audio

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manager audio - nagrywanie mikrofonu + synteza mowy (TTS).
 *
 * Nagrywanie: MediaRecorder do pliku .m4a w cache
 * TTS: Android TextToSpeech (offline, darmowy, polski)
 * - Obsługuje wybór głosu (męski/żeński)
 * - Regulacja prędkości (0.5x - 2.0x)
 * - Regulacja wysokości (0.5x - 2.0x)
 *
 * W przyszłości v1.1: integracja z HeyCyan przez BLE audio stream
 */
class AudioManager(
    private val context: Context
) {
    private val tag = "AudioManager"
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
            pl.victor.app.utils.loggingExceptionHandler(tag)
    )

    // === TTS ===
    private var tts: TextToSpeech? = null
    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    // Dostępne głosy
    private val _availableVoices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val availableVoices: StateFlow<List<VoiceInfo>> = _availableVoices.asStateFlow()

    // Aktualny głos
    private val _currentVoice = MutableStateFlow<VoiceInfo?>(null)
    val currentVoice: StateFlow<VoiceInfo?> = _currentVoice.asStateFlow()

    // Prędkość i wysokość
    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _pitch = MutableStateFlow(1.0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    // === Recording ===
    private var recorder: MediaRecorder? = null
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _lastRecordingPath = MutableStateFlow<String?>(null)
    val lastRecordingPath: StateFlow<String?> = _lastRecordingPath.asStateFlow()

    /** Wspólny router audio - patrz [BluetoothAudioRouter]. */
    private val bluetoothRouter = BluetoothAudioRouter.getInstance(context)

    /** Czy dźwięk idzie teraz przez okulary (zestaw Bluetooth). */
    val routedThroughGlasses: StateFlow<Boolean> = bluetoothRouter.isRoutedToBluetooth

    private val settingsRepo by lazy { pl.victor.app.data.SettingsRepository.getInstance(context) }

    /**
     * Głos z chmury (Gemini / OpenAI) - patrz [CloudVoiceEngine].
     *
     * Podpięty pod te same zdarzenia co systemowy syntezator: początek i koniec
     * mówienia idą do [_speaking] (sygnał dla okularów, "ucisz", wyciszanie
     * nasłuchu frazy), koniec wypowiedzi do [complete] (na nim czekają
     * [speakAndAwait] i [awaitStreamSpoken]). Dzięki temu orkiestrator nie
     * wie i nie musi wiedzieć, KTO mówi.
     */
    private val cloudVoice: CloudVoiceEngine = CloudVoiceEngine(
        cacheDir = context.cacheDir,
        audioAttributes = { bluetoothRouter.ttsAudioAttributes() },
        listener = object : CloudVoiceEngine.Listener {
            override fun onSpeakingChanged(speaking: Boolean) {
                if (speaking) {
                    _speaking.value = true
                } else if (runCatching { tts?.isSpeaking }.getOrNull() != true) {
                    _speaking.value = false
                }
            }

            override fun onUtteranceDone(utteranceId: String, success: Boolean) {
                complete(utteranceId, success)
            }

            override fun onFallback(pieces: List<Pair<String, String?>>, reason: CloudVoiceFallback) {
                // Reszta po polsku przez syntezator systemowy, DOPISANA do
                // kolejki - identyfikator wypowiedzi zostaje ten sam, więc
                // czekający na jej koniec niczego nie zauważą.
                pieces.forEach { (text, id) ->
                    val pieceId = id ?: "victor-cloudrest-${utteranceCounter.incrementAndGet()}"
                    val ok = speakSystem(text, "pl", pieceId, TextToSpeech.QUEUE_ADD) != null
                    if (!ok && id != null) complete(id, false)
                }
            }
        },
        diagEvent = { message, fields -> diagMowa(message, fields) }
    )

    /** Czy ostatnia wypowiedź poszła chmurą - dłuższy limit w [speakAndAwait]. */
    @Volatile
    private var lastRouteCloud = false

    /** Ostatnio zapisany powód "wybrano chmurę, mówi systemowy" - żeby nie pisać go co zdanie. */
    @Volatile
    private var lastDecisionNote: String? = null

    private fun diagMowa(message: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching {
            pl.victor.app.VictorApplication.get().diag.event(
                pl.victor.app.diagnostics.DiagFormat.Phase.MOWA, message, fields
            )
        }
    }

    private val utteranceCounter = AtomicLong(0)
    /**
     * Wypowiedzi, na których koniec ktoś czeka.
     *
     * Tylko te z [speakAndAwait] - zwykłe [speak] nic tu nie zostawia, więc
     * mapa nie puchnie od wypowiedzi "wystrzel i zapomnij".
     */
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _speaking = MutableStateFlow(false)

    /**
     * Czy syntezator właśnie mówi - od pierwszej głoski do końca kolejki.
     *
     * Między zdaniami strumienia potrafi na chwilę spaść do fałszu; kto
     * potrzebuje ciągłości (sygnał dla okularów), niech odczeka chwilę.
     */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    init {
        initializeTts()
        setupAudioFocus()
        // Podbitą na gwar głośność oddajemy dopiero po chwili ciszy - między
        // zdaniami jednej odpowiedzi silnik na moment przestaje mówić i bez
        // zwłoki głośność skakałaby w górę i w dół co zdanie.
        scope.launch {
            _speaking.collect { mówi ->
                if (mówi) {
                    restoreJob?.cancel()
                } else if (podbicie != null) {
                    restoreJob?.cancel()
                    restoreJob = scope.launch {
                        kotlinx.coroutines.delay(RESTORE_AFTER_MS)
                        przywróćGłośność()
                    }
                }
            }
        }
    }

    // === Głośność wg hałasu ===

    /** Pomiar hałasu - patrz [AmbientNoise]. */
    val ambientNoise = AmbientNoise(context)

    /** Ustawiane przez orkiestrator z ustawień. */
    @Volatile
    var noiseAdaptiveVolume: Boolean = true

    private data class Podbicie(val stream: Int, val było: Int, val ustawione: Int)

    @Volatile
    private var podbicie: Podbicie? = null
    private var restoreJob: kotlinx.coroutines.Job? = null

    /**
     * Przed wypowiedzią: w gwarze podnosi głośność strumienia, którym pójdzie
     * mowa. Nigdy nie ścisza - w zwykłym otoczeniu zostaje to, co ustawił
     * użytkownik (patrz [NoiseLogic.dodatek]).
     */
    private fun podbijGłośnośćWgHałasu() {
        restoreJob?.cancel()
        if (!noiseAdaptiveVolume || podbicie != null) return
        val dodatek = NoiseLogic.dodatek(ambientNoise.aktualnyDb())
        if (dodatek <= 0f) return
        val stream = if (bluetoothRouter.isRoutedToBluetooth.value) {
            android.media.AudioManager.STREAM_VOICE_CALL
        } else {
            android.media.AudioManager.STREAM_MUSIC
        }
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val obecna = am.getStreamVolume(stream)
            val cel = NoiseLogic.cel(obecna, am.getStreamMaxVolume(stream), dodatek)
            if (cel > obecna) {
                am.setStreamVolume(stream, cel, 0)
                podbicie = Podbicie(stream, obecna, cel)
                Log.d(tag, "Gwar (${ambientNoise.aktualnyDb()?.toInt()} dB) - głośność $obecna -> $cel")
            }
        }.onFailure { Log.w(tag, "Nie udało się podnieść głośności", it) }
    }

    /**
     * Zmienia głośność mowy na telefonie na życzenie użytkownika.
     *
     * Dokładnie jedno z [steps] i [percent] ma znaczenie: kroki to "głośniej"
     * i "ciszej" (krok = jedna piąta zakresu), procent to "głośność na 70".
     * W dół nigdy do zera - wyciszonego asystenta nie da się już poprosić
     * głosem o powrót.
     *
     * @return nowa głośność w procentach albo `null`, gdy system odmówił
     *   (np. tryb "nie przeszkadzać")
     */
    fun changeSpeechVolume(steps: Int, percent: Int?): Int? = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        // Wybór użytkownika wygrywa z podbiciem "wg hałasu" - nie przywracamy
        // już poziomu sprzed podbicia.
        restoreJob?.cancel()
        podbicie = null
        val streams = buildList {
            add(android.media.AudioManager.STREAM_MUSIC)
            if (bluetoothRouter.isRoutedToBluetooth.value) {
                add(android.media.AudioManager.STREAM_VOICE_CALL)
            }
        }
        var result: Int? = null
        for (stream in streams) {
            val max = am.getStreamMaxVolume(stream)
            if (max <= 0) continue
            val current = am.getStreamVolume(stream)
            val target = if (percent != null) {
                Math.round(max * percent.coerceIn(0, 100) / 100f).coerceIn(1, max)
            } else {
                val step = maxOf(1, Math.round(max * 0.2f))
                (current + steps * step).coerceIn(1, max)
            }
            if (target != current) am.setStreamVolume(stream, target, 0)
            if (result == null) result = target * 100 / max
        }
        result
    }.onFailure { Log.w(tag, "Nie udało się zmienić głośności", it) }.getOrNull()

    /** Zlecenie mowy nie wyszło - nikt nie zgłosi końca, więc przywrócenie planujemy sami. */
    private fun planujPrzywróceniePoPorażce() {
        if (podbicie == null) return
        restoreJob?.cancel()
        restoreJob = scope.launch {
            kotlinx.coroutines.delay(RESTORE_AFTER_MS)
            przywróćGłośność()
        }
    }

    private fun przywróćGłośność() {
        val p = podbicie ?: return
        podbicie = null
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            // Użytkownik ruszył głośność w trakcie - jego wybór wygrywa.
            if (am.getStreamVolume(p.stream) == p.ustawione) am.setStreamVolume(p.stream, p.było, 0)
        }
    }

    /**
     * Bierze na czas rozmowy łącze audio do okularów - i mikrofon, i głośnik.
     *
     * Trzymane na CAŁĄ turę (pytanie + odpowiedź), bo zestawienie łącza SCO
     * trwa nawet kilka sekund; podnoszenie go osobno pod nasłuch i osobno pod
     * mowę rwałoby rozmowę na każdym kroku.
     *
     * @return `true` gdy rozmowa faktycznie idzie przez okulary
     */
    suspend fun beginConversationRouting(waitForLink: Boolean = true): Boolean =
        bluetoothRouter.acquire(waitForLink = waitForLink)

    /** Stan profili Bluetooth w telefonie - patrz [BluetoothAudioRouter.profileStates]. */
    fun bluetoothProfileStates(): String = bluetoothRouter.profileStates()

    /** Ile trwało ostatnie czekanie na łącze rozmowy - do dziennika. */
    fun lastConversationLinkWaitMs(): Long = bluetoothRouter.lastLinkWaitMs

    /** Czy łącze SCO faktycznie stoi - patrz [BluetoothAudioRouter.isScoLinkUp]. */
    fun isConversationLinkUp(): Boolean = bluetoothRouter.isScoLinkUp()

    /** Zwalnia łącze wzięte przez [beginConversationRouting]. */
    suspend fun endConversationRouting() = bluetoothRouter.release()

    /** Nazwa zestawu audio, przez który idzie rozmowa - do diagnostyki. */
    fun conversationDeviceName(): String? = bluetoothRouter.connectedDeviceName()

    /** Jak telefon widzi okulary od strony dźwięku - patrz [BluetoothAudioRouter.audioProfileSummary]. */
    fun audioProfileSummary(): String = bluetoothRouter.audioProfileSummary()

    /** Czy jest bluetoothowy mikrofon - patrz [BluetoothAudioRouter.hasConversationMic]. */
    fun hasConversationMic(): Boolean = bluetoothRouter.hasConversationMic()

    /** Czy trwająca rozmowa idzie przez profil SCO/HFP zestawu Bluetooth. */
    fun isRoutedToBluetooth(): Boolean = bluetoothRouter.isRoutedToBluetooth.value

    /** Czy da się mówić przez zestaw BEZ zajmowania profilu rozmowy - patrz router. */
    fun canSpeakOverMedia(): Boolean = bluetoothRouter.hasA2dpOutput()

    /** Włącza albo wyłącza korzystanie z mikrofonu zestawu - patrz [BluetoothAudioRouter.scoEnabled]. */
    fun setGlassesMicEnabled(enabled: Boolean) {
        bluetoothRouter.scoEnabled = enabled
    }

    /**
     * Rozbiera łącze rozmowy NATYCHMIAST, bez karencji - do użycia, gdy trzeba
     * powiedzieć coś inną drogą (A2DP) niż ta, która właśnie zawiodła.
     */
    suspend fun resetConversationRouting() = bluetoothRouter.releaseAll()

    /**
     * Krótki sygnał "teraz mów".
     *
     * ## Po co
     * Między dźwiękiem wybudzenia (gra go firmware okularów) a chwilą, w której
     * naprawdę zaczynamy nagrywać, mija zauważalny czas: okno rozpoznawania
     * kliknięć plus zestawienie łącza SCO, na starszym Androidzie nawet kilka
     * sekund. Użytkownik słyszy sygnał okularów, od razu zaczyna mówić - i
     * pierwsze słowa idą w nic. Z zewnątrz wygląda to dokładnie jak zgłoszone
     * "wybudzam, mówię, a on nie reaguje", mimo że wszystko inne działa.
     *
     * Sygnał gra przez ten sam kanał, którym za chwilę pójdzie odpowiedź, więc
     * jest też darmowym sprawdzianem trasy: gdy go nie słychać w okularach,
     * odpowiedzi też nie będzie słychać.
     *
     * [ToneGenerator] zamiast wypowiedzi, bo "Słucham" z syntezatora kosztuje
     * prawie sekundę - a to jest dokładnie ten czas, który staramy się odzyskać.
     */
    fun playListeningCue() {
        val stream = if (bluetoothRouter.isRoutedToBluetooth.value) {
            android.media.AudioManager.STREAM_VOICE_CALL
        } else {
            android.media.AudioManager.STREAM_MUSIC
        }
        runCatching {
            val tone = ToneGenerator(stream, CUE_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_DURATION_MS)
            // Zwolnienie przed końcem dźwięku ucina go w pół, więc czekamy -
            // ale w tle, żeby nasłuch ruszył natychmiast.
            scope.launch {
                kotlinx.coroutines.delay(CUE_DURATION_MS + CUE_RELEASE_MARGIN_MS)
                runCatching { tone.release() }
            }
        }.onFailure { Log.w(tag, "Nie udało się zagrać sygnału nasłuchu", it) }
    }

    /** Czy telefon widzi okulary jako podłączony zestaw audio. */
    fun hasBluetoothAudioDevice(): Boolean = bluetoothRouter.hasConnectedBluetoothAudioDevice()

    // === Audio focus (adaptive volume) ===

    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private val _isInFocus = MutableStateFlow(false)
    val isInFocus: StateFlow<Boolean> = _isInFocus.asStateFlow()

    /**
     * Konfiguruje audio focus - dostosowuje głośność TTS do otoczenia.
     * Jeśli user słucha muzyki / podcastu - nie przerywamy, tylko ściszamy.
     */
    private fun setupAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

        audioFocusRequest = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                        // Mamy focus - przywróć normalną głośność
                        _isInFocus.value = true
                        tts?.setSpeechRate(_speechRate.value)
                    }
                    android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                        // Straciliśmy focus - przerywamy
                        _isInFocus.value = false
                        tts?.stop()
                        cloudVoice.stop()
                    }
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // Tymczasowa utrata - pauza
                        _isInFocus.value = false
                        tts?.stop()
                        cloudVoice.stop()
                    }
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // Ducking - mów ciszej ale nie przerywaj
                        _isInFocus.value = true
                        tts?.setSpeechRate(_speechRate.value * 0.85f)  // trochę wolniej = cicho
                    }
                }
            }
            .build()
    }

    /**
     * Prosi o audio focus przed mówieniem.
     */
    private fun requestAudioFocus(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        val granted = result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        _isInFocus.value = granted
        return granted
    }

    /**
     * Zwalnia audio focus.
     */
    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        _isInFocus.value = false
    }

    /**
     * Mówi z automatycznym zarządzaniem audio focus.
     * Dostosowuje głośność do tego co user robi (muzyka, podcast itd.)
     */
    fun speakAdaptive(text: String, language: String = "pl") {
        if (text.isBlank()) return
        requestAudioFocus()
        speak(text, language)
        // Po zakończeniu - zwolnij focus
        // (nie czekamy, bo to synchroniczne)
    }

    /**
     * Lista zainstalowanych silników mowy - do wyboru w ustawieniach.
     *
     * Bez tego aplikacja brała systemowy domyślny, a na telefonach Samsunga
     * jest nim silnik Samsunga: jeden polski głos i BRAK angielskiego.
     * Zgłoszone jako "nie da się wgrać głosów z Google TTS i mam tylko jeden
     * głos kobiecy".
     */
    fun availableEngines(): List<Pair<String, String>> =
        runCatching { tts?.engines?.map { it.name to it.label }.orEmpty() }
            .getOrDefault(emptyList())

    private fun initializeTts() {
        // Silnik wybrany przez użytkownika, a przy jego braku - systemowy.
        val engine = runCatching {
            pl.victor.app.data.SettingsRepository.getInstance(context).getTtsEngine()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pl", "PL")
                tts?.setSpeechRate(_speechRate.value)
                tts?.setPitch(_pitch.value)
                loadAvailableVoices()
                installUtteranceListener()
                _ttsReady.value = true
                // Przywróć zapisany głos, tempo i wysokość - inaczej ustawienia
                // działałyby tylko jako podgląd i znikały po restarcie aplikacji.
                applyPersistedSettings()
                Log.d(
                    tag,
                    "TTS gotowe (silnik=${engine ?: "systemowy"}) - " +
                        "${_availableVoices.value.size} głosów"
                )
            } else {
                Log.e(tag, "TTS init failed: $status")
                _ttsReady.value = false
            }
        }
        tts = if (engine != null) {
            TextToSpeech(context, listener, engine)
        } else {
            TextToSpeech(context, listener)
        }
    }

    /**
     * Ponownie tworzy syntezator - po zmianie silnika w ustawieniach.
     *
     * Silnika nie da się podmienić w locie: TextToSpeech wiąże się z nim przy
     * tworzeniu. Bez tego wybór w ustawieniach działałby dopiero po restarcie
     * aplikacji, czyli z punktu widzenia użytkownika wcale.
     */
    fun restartTts() {
        _ttsReady.value = false
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        initializeTts()
    }

    /**
     * Ładuje listę dostępnych głosów (dla bieżącego locale).
     * Filtruje polskie głosy, dodaje angielskie i inne jako alternatywę.
     */
    private fun loadAvailableVoices() {
        val voices = tts?.voices ?: emptySet()

        // Polskie (priorytet)
        val polishVoices = voices.filter { languageCodeOf(it.locale) == "pl" }
        // Angielskie offline (backup)
        val englishOffline = voices.filter {
            languageCodeOf(it.locale) == "en" && !it.isNetworkConnectionRequired
        }
        // Inne języki offline
        val otherOffline = voices.filter {
            languageCodeOf(it.locale) !in listOf("pl", "en") && !it.isNetworkConnectionRequired
        }

        val finalVoices = (polishVoices + englishOffline + otherOffline).map { voice ->
            VoiceInfo(
                name = voice.name,
                displayName = buildDisplayName(voice),
                language = displayLanguageOf(voice.locale),
                locale = voice.locale.toLanguageTag(),
                gender = when {
                    voice.name.contains("female", ignoreCase = true) ||
                    voice.name.contains("żensk", ignoreCase = true) -> "F"
                    voice.name.contains("male", ignoreCase = true) ||
                    voice.name.contains("męsk", ignoreCase = true) -> "M"
                    else -> "?"
                },
                quality = when (voice.quality) {
                    Voice.QUALITY_VERY_HIGH -> "Bardzo wysoka"
                    Voice.QUALITY_HIGH -> "Wysoka"
                    Voice.QUALITY_NORMAL -> "Normalna"
                    else -> "Niska"
                },
                isNetwork = voice.isNetworkConnectionRequired,
                requiresNetwork = voice.isNetworkConnectionRequired,
                isInstalledOffline = !voice.isNetworkConnectionRequired,
                languageCode = languageCodeOf(voice.locale)
            )
        }.sortedWith(
            compareByDescending<VoiceInfo> { it.isPolish }
                .thenByDescending { it.isInstalledOffline }
                .thenBy { it.languageCode }
        )

        _availableVoices.value = finalVoices

        // Statystyki
        val polishCount = finalVoices.count { it.isPolish }
        val polishOffline = finalVoices.count { it.isPolish && it.isInstalledOffline }
        Log.i(tag, "Voices: ${finalVoices.size} total, " +
                "$polishCount Polish, $polishOffline offline Polish")

        // Ustaw domyślny
        if (_currentVoice.value == null && finalVoices.isNotEmpty()) {
            val defaultVoice = finalVoices.firstOrNull { it.isPolish && it.isInstalledOffline }
                ?: finalVoices.firstOrNull { it.isPolish }
                ?: finalVoices.first()
            _currentVoice.value = defaultVoice
            tts?.voice = voices.find { it.name == defaultVoice.name }
            Log.d(tag, "Default voice: ${defaultVoice.displayName}")
        }
    }

    // === Informacje o silniku TTS ===

    /**
     * Ile jest zainstalowanych polskich głosów offline.
     */
    fun getPolishOfflineVoicesCount(): Int =
        _availableVoices.value.count { it.isPolish && it.isInstalledOffline }

    /**
     * Ile jest zainstalowanych polskich głosów (łącznie).
     */
    fun getPolishVoicesCount(): Int =
        _availableVoices.value.count { it.isPolish }

    /**
     * Czy użytkownik MA polski głos offline (gotowy do użycia).
     */
    fun hasPolishOfflineVoice(): Boolean = getPolishOfflineVoicesCount() > 0

    /**
     * Zwraca intent do ustawień TTS w systemie (gdzie user może pobrać głosy).
     */
    fun getTtsSettingsIntent(): Intent {
        return Intent().apply {
            action = "com.android.settings.TTS_SETTINGS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Zwraca intent do Google TTS w Play Store (jeśli nie zainstalowany).
     */
    fun getGoogleTtsInstallIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(
                "https://play.google.com/store/apps/details?id=com.google.android.tts"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Otwiera ustawienia TTS (gdzie można wybrać silnik i pobrać głosy).
     */
    fun openTtsSettings() {
        try {
            val intent = getTtsSettingsIntent()
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(tag, "Couldn't open TTS settings: ${e.message}")
        }
    }

    /**
     * Otwiera Google TTS w Play Store.
     */
    fun openGoogleTtsPlayStore() {
        try {
            val intent = getGoogleTtsInstallIntent()
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(tag, "Couldn't open Play Store: ${e.message}")
        }
    }

    /**
     * Dwuliterowy kod języka głosu.
     *
     * Nie wystarczy `locale.language`: część silników (m.in. Samsung) zwraca w
     * `Voice.getLocale()` kod TRZYLITEROWY - "pol" zamiast "pl", "deu" zamiast
     * "de". Filtr `language == "pl"` nie łapał wtedy ANI JEDNEGO polskiego
     * głosu; lista wyglądała, jakby polskich w ogóle nie było, a użytkownikowi
     * zostawał do wyboru np. niemiecki.
     */
    private fun languageCodeOf(locale: Locale): String {
        val raw = locale.language.lowercase(Locale.ROOT)
        return if (raw.length == 3) ISO3_TO_ISO2[raw] ?: raw else raw
    }

    /** Nazwa języka po polsku, odporna na kody trzyliterowe (patrz [languageCodeOf]). */
    private fun displayLanguageOf(locale: Locale): String {
        val code = languageCodeOf(locale)
        val name = Locale(code).getDisplayLanguage(Locale("pl"))
        return name.ifBlank { code }
    }

    private fun buildDisplayName(voice: Voice): String {
        val parts = mutableListOf<String>()
        parts.add(displayLanguageOf(voice.locale))

        val gender = when {
            voice.name.contains("female", ignoreCase = true) -> "żeński"
            voice.name.contains("male", ignoreCase = true) -> "męski"
            else -> null
        }
        gender?.let { parts.add(it) }

        // Network vs local
        if (voice.isNetworkConnectionRequired) {
            parts.add("online")
        } else {
            parts.add("offline")
        }

        return parts.joinToString(" · ")
    }

    // === TTS - mówienie ===

    /**
     * Mówi podany tekst przez głośniki telefonu (przez HeyCyan jeśli BT audio aktywne).
     */
    fun speak(text: String, language: String = "pl") {
        speakInternal(text, language)
    }

    /**
     * Mówi, DOPISUJĄC do kolejki zamiast ucinać to, co właśnie leci.
     *
     * ## Dlaczego to musi być osobne wejście
     * Zwykłe [speak] używa QUEUE_FLUSH, bo prawie zawsze jest nową, samodzielną
     * wypowiedzią - i ma wyprzeć poprzednią. Przy czytaniu odpowiedzi zdanie po
     * zdaniu jest dokładnie odwrotnie: kolejne zdanie ma DOKOŃCZYĆ poprzednie,
     * a nie je uciąć. Z QUEUE_FLUSH użytkownik słyszał początek pierwszego
     * zdania, potem początek drugiego, potem trzeciego - czyli poszarpany
     * bełkot zamiast odpowiedzi.
     */
    private fun speakQueued(text: String, language: String, utteranceId: String): String? =
        speakInternal(text, language, utteranceId, queueMode = TextToSpeech.QUEUE_ADD)

    /**
     * Mówi i **czeka**, aż faktycznie skończy mówić.
     *
     * [speak] wraca natychmiast - to tylko zlecenie do silnika TTS. Tryb
     * konwersacyjny wołał `onAiFinishedSpeaking()` zaraz po nim, czyli
     * właściwie w tej samej milisekundzie, w której V.I.C.T.O.R. zaczynał
     * mówić. Mikrofon startował więc w trakcie jego własnej wypowiedzi i
     * nagrywał ją jako "pytanie użytkownika". Stąd ta wersja.
     *
     * Zwraca `false`, gdy silnik nie był gotowy albo zgłosił błąd.
     */
    suspend fun speakAndAwait(text: String, language: String = "pl"): Boolean {
        val id = "victor-${utteranceCounter.incrementAndGet()}"
        // Rejestracja MUSI poprzedzać wypowiedź. Gdyby szła po niej, silnik
        // mógłby zgłosić zakończenie, zanim zdążylibyśmy się podpiąć - i
        // czekalibyśmy do wyczerpania limitu czasu, mimo że mowa dawno ucichła.
        val done = CompletableDeferred<Boolean>()
        pendingUtterances[id] = done

        if (speakInternal(text, language, id) == null) {
            pendingUtterances.remove(id)
            return false
        }
        // Chmura: do zwykłego limitu dochodzi czekanie na pierwszy dźwięk
        // (i ewentualne przejście na syntezator systemowy po nim).
        val extra = if (lastRouteCloud) CLOUD_EXTRA_TIMEOUT_MS else 0L
        return try {
            withTimeoutOrNull(speechTimeoutFor(text) + extra) { done.await() } ?: run {
                // Silnik potrafi nie zgłosić zakończenia (np. gdy dźwięk przejmie
                // rozmowa telefoniczna). Lepiej ruszyć dalej niż zawiesić rozmowę.
                Log.w(tag, "TTS nie zgłosiło zakończenia w limicie czasu")
                false
            }
        } finally {
            pendingUtterances.remove(id)
        }
    }

    /**
     * Wspólna ścieżka dla [speak] i [speakAndAwait].
     *
     * Najpierw głos z chmury (gdy wybrany, dozwolony i język jest polski - patrz
     * [speakCloud]), inaczej syntezator systemowy. Semantyka kolejki jest ta sama
     * dla obu: QUEUE_FLUSH wypiera to, co mówi KTÓRYKOLWIEK z nich.
     *
     * @param allowCloud `false` = zawsze systemowy (podgląd głosu systemowego)
     * @return identyfikator wypowiedzi albo `null`, gdy nic nie zostało zlecone
     */
    private fun speakInternal(
        text: String,
        language: String,
        utteranceId: String? = null,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        allowCloud: Boolean = true
    ): String? {
        if (text.isBlank()) return null
        if (allowCloud && CloudVoiceLogic.isCloudLanguage(language)) {
            val cloudId = runCatching { speakCloud(text, utteranceId, queueMode) }
                .onFailure { Log.w(tag, "Głos z chmury nie ruszył", it) }
                .getOrNull()
            if (cloudId != null) {
                lastRouteCloud = true
                return cloudId
            }
        }
        lastRouteCloud = false
        // Systemowy wypiera to, co ewentualnie gra z chmury - jak QUEUE_FLUSH.
        if (queueMode == TextToSpeech.QUEUE_FLUSH) cloudVoice.stop()
        return speakSystem(text, language, utteranceId, queueMode)
    }

    /**
     * Zleca wypowiedź głosem z chmury.
     *
     * @return identyfikator albo `null` = mów systemowym (chmura nie wybrana,
     *   "Tylko darmowe", brak klucza, chwilowa przerwa po porażce)
     */
    private fun speakCloud(text: String, utteranceId: String?, queueMode: Int): String? {
        val decision = settingsRepo.cloudVoiceDecision()
        noteDecision(decision)
        if (!decision.isCloud) return null
        // Dopisanie za tym, co właśnie czyta systemowy (np. reszta po
        // przejściu z chmury) - też systemowym, inaczej dwa głosy naraz.
        if (queueMode != TextToSpeech.QUEUE_FLUSH && runCatching { tts?.isSpeaking }.getOrNull() == true) {
            return null
        }
        val spoken = sanitizeForSpeech(text)
        if (spoken.isBlank()) return null
        val sentences = CloudVoiceLogic.splitSentences(spoken)
        if (sentences.isEmpty()) return null
        val provider = decision.provider
        val key = settingsRepo.getApiKey(provider.id)?.takeIf { it.isNotBlank() } ?: return null
        val config = CloudVoiceEngine.Config(
            provider = provider,
            voice = CloudVoiceLogic.voiceFor(provider, settingsRepo.getCloudVoiceName(provider.id)),
            apiKey = key
        )
        val id = utteranceId ?: "victor-${utteranceCounter.incrementAndGet()}"
        val flush = queueMode == TextToSpeech.QUEUE_FLUSH
        if (cloudVoice.isCoolingDown()) {
            noteCooldown()
            return null
        }
        if (flush) runCatching { tts?.stop() }
        podbijGłośnośćWgHałasu()
        if (!cloudVoice.enqueue(sentences, id, flush, config)) {
            noteCooldown()
            return null
        }
        cooldownNoted = false
        Log.d(tag, "Mówię z chmury (${provider.nazwa}, ${sentences.size} zdań): ${spoken.take(80)}")
        return id
    }

    /** Czy przerwę chmury już zapisaliśmy w dzienniku - raz na przerwę, nie co zdanie. */
    @Volatile
    private var cooldownNoted = false

    private fun noteCooldown() {
        if (cooldownNoted) return
        cooldownNoted = true
        diagMowa(
            "głos z chmury chwilowo wyłączony - mówi systemowy",
            mapOf("powód" to cloudVoice.lastFallback?.opis)
        )
    }

    /** Zapisuje w dzienniku, że wybrano chmurę, a mówi systemowy - tylko przy zmianie powodu. */
    private fun noteDecision(decision: CloudVoiceLogic.Decision) {
        val note = decision.reason
        if (note == lastDecisionNote) return
        lastDecisionNote = note
        if (note != null) {
            diagMowa(
                "głos z chmury niedostępny - mówi systemowy",
                mapOf("wybrany" to decision.chosen.nazwa, "powód" to note)
            )
        }
    }

    /**
     * Odsłuchanie próbki głosu z chmury w Ustawieniach.
     *
     * @return opis dla użytkownika - co (i dlaczego) mówi
     */
    fun speakCloudVoiceSample(): String {
        val decision = settingsRepo.cloudVoiceDecision()
        if (!decision.isCloud) {
            speakInternal(CLOUD_SAMPLE_TEXT, "pl", allowCloud = false)
            return "Mówi głos systemowy" + (decision.reason?.let { " ($it)" } ?: "") + "."
        }
        val provider = decision.provider
        val config = CloudVoiceEngine.Config(
            provider = provider,
            voice = CloudVoiceLogic.voiceFor(provider, settingsRepo.getCloudVoiceName(provider.id)),
            apiKey = settingsRepo.getApiKey(provider.id).orEmpty()
        )
        runCatching { tts?.stop() }
        podbijGłośnośćWgHałasu()
        val id = "victor-sample-${utteranceCounter.incrementAndGet()}"
        diagMowa(
            "próbka głosu z chmury",
            mapOf("dostawca" to provider.nazwa, "model" to config.model, "głos" to config.voice)
        )
        lastRouteCloud = true
        return if (cloudVoice.sample(CLOUD_SAMPLE_TEXT, config, id)) {
            "${provider.nazwa}, głos ${config.voice}. Jeśli usłyszysz głos systemowy, usługa nie odpowiedziała - powód poniżej i w dzienniku."
        } else {
            "Nie udało się zlecić próbki."
        }
    }

    /** Stan głosu z chmury do Ustawień: ostatnia porażka i czy trwa przerwa. */
    fun cloudVoiceStatus(): String? {
        val f = cloudVoice.lastFallback ?: return null
        return "Ostatnio: przejście na systemowy - ${f.opis}" +
            if (cloudVoice.isCoolingDown()) " (chmura chwilowo wyłączona)." else "."
    }

    /** Syntezator systemowy Androida - dawna treść [speakInternal]. */
    private fun speakSystem(
        text: String,
        language: String,
        utteranceId: String?,
        queueMode: Int
    ): String? {
        if (!_ttsReady.value) {
            Log.w(tag, "TTS not ready, skipping")
            return null
        }
        if (text.isBlank()) return null

        applyLanguage(language)

        val spoken = sanitizeForSpeech(text)
        if (spoken.isBlank()) return null

        // Atrybuty MUSZĄ być ustawione przed każdą wypowiedzią: gdy rozmowa
        // idzie przez okulary, dźwięk musi mieć USAGE_VOICE_COMMUNICATION,
        // inaczej nie wejdzie w łącze SCO i okulary po prostu milczą.
        runCatching { tts?.setAudioAttributes(bluetoothRouter.ttsAudioAttributes()) }
        podbijGłośnośćWgHałasu()

        val id = utteranceId ?: "victor-${utteranceCounter.incrementAndGet()}"

        // Angielskie wtręty czytane polskim głosem brzmią tak, jak się je
        // pisze - "the best" jako "te best". Żaden polski głos nie zna
        // angielskiej wymowy, więc jedynym wyjściem jest przełączenie głosu na
        // czas takiego fragmentu. Gdy silnik nie ma angielskiego głosu (np.
        // Samsung), zostaje stara droga: lepiej przeczytać z polskim akcentem
        // niż nie przeczytać wcale.
        val segments = SpokenLanguage.split(spoken)
        val englishVoice = if (segments.any { it.english }) findEnglishVoice() else null
        if (englishVoice != null && segments.size > 1) {
            val baseVoice = runCatching { tts?.voice }.getOrNull()
            segments.forEachIndexed { index, segment ->
                val mode = if (index == 0) queueMode else TextToSpeech.QUEUE_ADD
                runCatching {
                    tts?.voice = if (segment.english) englishVoice else baseVoice
                }
                // Prawdziwy identyfikator niesie OSTATNI fragment - na nim
                // wisi powiadomienie o końcu wypowiedzi, z którego korzysta
                // tryb konwersacyjny.
                val segmentId = if (index == segments.lastIndex) id else "$id-$index"
                tts?.speak(segment.text, mode, null, segmentId)
            }
            runCatching { if (baseVoice != null) tts?.voice = baseVoice }
            Log.d(tag, "Mówię w ${segments.size} fragmentach (w tym angielskie)")
            return id
        }

        val result = tts?.speak(spoken, queueMode, null, id)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(tag, "TTS odmówiło wypowiedzi (kod $result)")
            planujPrzywróceniePoPorażce()
            return null
        }
        Log.d(tag, "Speaking: ${spoken.take(80)}...")
        return id
    }

    /**
     * Najlepszy dostępny głos angielski w bieżącym silniku.
     *
     * Woli głos działający offline: przełączanie w środku zdania na głos
     * wymagający sieci dawałoby dziurę w wypowiedzi przy słabym zasięgu.
     */
    private fun findEnglishVoice(): android.speech.tts.Voice? = runCatching {
        tts?.voices
            ?.filter { it.locale.language.equals("en", ignoreCase = true) }
            ?.minByOrNull { voice ->
                var score = 0
                if (voice.isNetworkConnectionRequired) score += 10
                if (voice.quality < android.speech.tts.Voice.QUALITY_NORMAL) score += 5
                score
            }
    }.getOrNull()

    /**
     * Usuwa z tekstu to, czego syntezator nie umie przeczytać sensownie.
     *
     * Modele mimo instrukcji zwracają markdown - a TTS czyta `**` jako
     * "gwiazdka gwiazdka", `#` jako "hash", a każdy myślnik na początku linii
     * jako osobne słowo. Adresy URL są jeszcze gorsze: czytane znak po znaku
     * potrafią trwać dłużej niż cała reszta odpowiedzi.
     *
     * Czyszczenie jest tutaj, a nie w orkiestratorze, żeby objęło KAŻDĄ ścieżkę:
     * odpowiedzi AI, potwierdzenia akcji, komunikaty trybu dostępności.
     */
    private fun sanitizeForSpeech(text: String): String = text
        .replace(MARKDOWN_LINK_REGEX, "$1")
        .replace(URL_REGEX, "link")
        .replace(CODE_FENCE_REGEX, " ")
        .replace(INLINE_CODE_REGEX, "")
        .replace(EMPHASIS_REGEX, "")
        .replace(HEADING_REGEX, "")
        .replace(BULLET_REGEX, "")
        .replace(ARROW_REGEX, ", ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    /**
     * Ustawia język TYLKO wtedy, gdy trzeba.
     *
     * `TextToSpeech.setLanguage()` kasuje wybrany głos i wraca do domyślnego
     * dla danego locale. Poprzednia wersja wołała je przy KAŻDEJ wypowiedzi,
     * więc głos wybrany w ustawieniach działał wyłącznie w podglądzie -
     * w rozmowie natychmiast wracał domyślny silnik systemowy.
     */
    private fun applyLanguage(language: String) {
        val requested = localeFor(language)
        val current = _currentVoice.value
        if (current != null && current.languageCode.equals(requested.language, ignoreCase = true)) {
            // Wybrany głos już jest w tym języku - nie ruszamy.
            return
        }
        // Głos jest w innym języku niż odpowiedź. Ustawiamy język i godzimy się
        // na utratę wybranego głosu - lepiej brzmieć poprawnie niż wybranym
        // głosem w złym języku. Ważne, żeby UI o tym wiedziało: wcześniej
        // ustawienia dalej pokazywały stary głos (np. niemiecki), choć mówił
        // już całkiem inny.
        tts?.language = requested
        syncCurrentVoiceFromEngine()
    }

    /** Dociąga do UI głos, który silnik faktycznie ma teraz ustawiony. */
    private fun syncCurrentVoiceFromEngine() {
        val active = runCatching { tts?.voice }.getOrNull() ?: return
        val known = _availableVoices.value.find { it.name == active.name }
        if (known != null) {
            _currentVoice.value = known
        }
    }

    private fun localeFor(language: String): Locale =
        if (language.contains('-') || language.contains('_')) {
            Locale.forLanguageTag(language.replace('_', '-'))
        } else {
            Locale(language)
        }

    /**
     * Ile najwyżej czekać na koniec wypowiedzi. Mowa idzie ~12 znaków/sekundę;
     * bierzemy z dużym zapasem plus stała na rozruch silnika.
     */
    private fun speechTimeoutFor(text: String): Long =
        (SPEECH_BASE_TIMEOUT_MS + text.length * MS_PER_CHARACTER).coerceAtMost(MAX_SPEECH_TIMEOUT_MS)

    /**
     * Ustawia konkretny głos.
     */
    /**
     * Przywraca zapisane ustawienia głosu z [pl.victor.app.data.SettingsRepository].
     * Wywoływane po zainicjalizowaniu silnika TTS.
     */
    private fun applyPersistedSettings() {
        try {
            val settings = pl.victor.app.data.SettingsRepository.getInstance(context)
            setSpeechRate(settings.getTtsSpeechRate())
            setPitch(settings.getTtsPitch())
            val voiceName = settings.getTtsVoiceName()
            if (!voiceName.isNullOrBlank()) {
                if (setVoice(voiceName)) {
                    Log.i(tag, "Przywrócono zapisany głos: $voiceName")
                } else {
                    Log.w(tag, "Zapisany głos niedostępny: $voiceName")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Nie udało się przywrócić ustawień TTS", e)
        }
    }

    fun setVoice(voiceName: String): Boolean {
        val voice = tts?.voices?.find { it.name == voiceName } ?: return false
        tts?.voice = voice
        val info = _availableVoices.value.find { it.name == voiceName }
        _currentVoice.value = info
        Log.i(tag, "Voice changed to: ${info?.displayName}")
        return true
    }

    /**
     * Ustawia prędkość mówienia (0.5 - 2.0).
     */
    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(clamped)
        _speechRate.value = clamped
        Log.d(tag, "Speech rate: $clamped")
    }

    /**
     * Ustawia wysokość głosu (0.5 - 2.0).
     */
    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        tts?.setPitch(clamped)
        _pitch.value = clamped
        Log.d(tag, "Pitch: $clamped")
    }

    /**
     * Testuje aktualny głos - mówi krótki tekst.
     */
    fun testCurrentVoice() {
        // Zawsze systemowy: to podgląd głosu wybranego na liście głosów
        // Androida - próbka chmury ma osobny przycisk.
        speakInternal("Cześć, jestem Twoim asystentem AI. Tak brzmi mój głos.", "pl", allowCloud = false)
    }

    // === Streaming TTS - mówienie po zdaniach ===

    private val streamBuffer = StringBuilder()

    /** Czy w tym strumieniu padło już pierwsze zdanie - patrz [queueSentence]. */
    @Volatile
    private var streamStarted = false

    /** Ostatnie zakolejkowane zdanie strumienia - na nim czeka [awaitStreamSpoken]. */
    @Volatile
    private var lastStreamUtteranceId: String? = null

    /**
     * Dodaje fragment tekstu ze streamingu. Jeśli w buforze jest kompletne zdanie
     * (kończy się na . ! ? lub nowej linii), mówi je i usuwa z bufora.
     *
     * @return lista zdań które zostały wypowiedziane (do logów/UI)
     */
    fun addStreamFragment(fragment: String): List<String> {
        if (fragment.isBlank()) return emptyList()

        streamBuffer.append(fragment)
        val spoken = mutableListOf<String>()

        // ZNACZNIK AKCJI NIE MOŻE ZOSTAĆ PRZECZYTANY NA GŁOS.
        //
        // Model dokleja na końcu wypowiedzi `[[ACTION: type=...]]`. Jest on
        // wycinany dopiero PO zakończeniu strumienia - a my mówimy w trakcie.
        // Wstrzymujemy więc to, co może być znacznikiem - ale TYLKO to.
        val bufferText = streamBuffer.toString()
        val speakable = bufferText.substring(0, StreamSpeech.speakableEnd(bufferText))

        val sentenceEndRegex = Regex("""([^.!?\n]*[.!?\n])""")
        var lastEnd = 0
        for (match in sentenceEndRegex.findAll(speakable)) {
            val sentence = match.value.trim()
            if (sentence.isNotBlank() && sentence.length > 3) {
                queueSentence(sentence)
                spoken.add(sentence)
            }
            lastEnd = match.range.last + 1
        }

        if (lastEnd > 0) {
            streamBuffer.delete(0, lastEnd)
        }

        return spoken
    }

    /**
     * Dopisuje zdanie do kolejki syntezatora i zapamiętuje je jako ostatnie.
     *
     * Identyfikator ostatniego zdania jest tym, na czym czeka
     * [awaitStreamSpoken] - inaczej nie dałoby się poznać, kiedy odpowiedź
     * naprawdę została wypowiedziana do końca.
     */
    private fun queueSentence(sentence: String) {
        val id = "victor-stream-${utteranceCounter.incrementAndGet()}"
        val done = CompletableDeferred<Boolean>()
        pendingUtterances[id] = done
        // Pierwsze zdanie strumienia wypiera to, co ewentualnie jeszcze leci
        // (np. "chwila, spojrzę"); każde następne DOPISUJE się do kolejki.
        // Język z [beginStream], nie "pl" na sztywno - inaczej odpowiedź po
        // angielsku (język odpowiedzi w Ustawieniach) czytał polski głos.
        val queued = if (streamStarted) {
            speakQueued(sentence, streamLanguage, id)
        } else {
            streamStarted = true
            speakInternal(sentence, streamLanguage, id, queueMode = TextToSpeech.QUEUE_FLUSH)
        }
        if (queued == null) {
            pendingUtterances.remove(id)
            done.complete(false)
            return
        }
        lastStreamUtteranceId = id
    }

    /**
     * Kończy streaming - mówi to, co zostało w buforze.
     *
     * @param strip funkcja czyszcząca resztę przed wypowiedzeniem; tu wycinany
     *   jest znacznik akcji, trzymany wcześniej w buforze
     */
    /**
     * @return `true`, gdy resztę bufora ODDANO syntezatorowi.
     *
     * Wołający musi to wiedzieć, inaczej uzna, że strumień nic nie powiedział,
     * i przeczyta całą odpowiedź jeszcze raz - z `QUEUE_FLUSH`, czyli ucinając
     * w pół słowa to, co właśnie leci. Tak wracał objaw "odpowiada jakby na
     * inne pytanie" przy odpowiedziach bez kropki przed znacznikiem akcji
     * ("Jasne [[ACTION: ...]]"), gdzie `addStreamFragment` nie wypowiada nic,
     * a cała treść wychodzi dopiero tędy.
     */
    fun flushStream(strip: (String) -> String = { it }): Boolean {
        val remaining = strip(streamBuffer.toString()).trim()
        streamBuffer.clear()
        if (remaining.isNotBlank() && remaining.length > 2) {
            queueSentence(remaining)
            return true
        }
        return false
    }

    /**
     * Czeka, aż syntezator dojdzie do końca ostatniego zdania ze strumienia.
     *
     * ## Dlaczego to zastąpiło ponowne czytanie całości
     * Bo wcześniej odpowiedź szła na głos DWA RAZY: raz zdanie po zdaniu w
     * trakcie generowania, a potem jeszcze raz w całości, przez
     * `speakAndAwait(cała odpowiedź)` - bo tylko tak dało się poczekać na
     * koniec mówienia. Przy QUEUE_FLUSH drugie czytanie ucinało pierwsze w pół
     * słowa. Z perspektywy użytkownika: poszarpany początek, potem odpowiedź od
     * nowa, a tura kończyła się dopiero po tym wszystkim.
     *
     * Teraz czekamy na to, co i tak zostało wypowiedziane.
     *
     * @return `false`, gdy nic nie było w kolejce albo syntezator nie zgłosił końca
     */
    suspend fun awaitStreamSpoken(): Boolean {
        val id = lastStreamUtteranceId ?: return false
        val done = pendingUtterances[id] ?: return false
        return try {
            withTimeoutOrNull(STREAM_DRAIN_TIMEOUT_MS) { done.await() } ?: run {
                Log.w(tag, "Syntezator nie zgłosił końca strumienia - idę dalej")
                pendingUtterances.remove(id)
                false
            }
        } finally {
            lastStreamUtteranceId = null
        }
    }

    /** Język bieżącego strumienia - patrz [beginStream]. */
    @Volatile
    private var streamLanguage: String = "pl"

    /** Zaczyna nowy strumień odpowiedzi - patrz [queueSentence]. */
    fun beginStream(language: String = "pl") {
        streamLanguage = language
        streamBuffer.clear()
        streamStarted = false
        lastStreamUtteranceId = null
    }

    /**
     * Czyści bufor streamingu (np. przy cancel).
     */
    fun clearStream() {
        streamBuffer.clear()
        streamStarted = false
        lastStreamUtteranceId = null
        tts?.stop()
        cloudVoice.stop()
    }

    /**
     * Zatrzymuje mówienie.
     *
     * Budzi też wszystkich czekających na [speakAndAwait] - inaczej komenda
     * "cicho" uciszyłaby syntezator, ale rozmowa zostałaby zawieszona do
     * upływu limitu czasu.
     */
    fun stopSpeaking() {
        tts?.stop()
        // Chmura też - "cicho" i dotknięcie zausznika mają uciszać każdy głos.
        cloudVoice.stop()
        _speaking.value = false
        completeAllPending(false)
        // Przerwane PRZED pierwszym dźwiękiem: stan "mówię" nie zmienił się z
        // true na false, więc przywrócenie podbitej głośności nigdy nie ruszało
        // (przegląd 22.11). No-op, gdy nic nie było podbite.
        planujPrzywróceniePoPorażce()
    }

    /**
     * Mówi przez GŁOŚNIK TELEFONU, nawet gdy okulary są połączone.
     *
     * Do tłumaczenia rozmowy w dwie strony: przekład tego, co powiedział
     * użytkownik, ma usłyszeć ROZMÓWCA, a nie on sam w okularach. Syntezator
     * nie pozwala wybrać urządzenia wyjściowego, więc zapisujemy mowę do
     * pliku i odtwarzamy ją odtwarzaczem z wybranym głośnikiem telefonu.
     *
     * @return `false`, gdy synteza albo odtwarzanie się nie udały
     */
    suspend fun speakOnPhoneSpeaker(text: String, language: String): Boolean {
        val engine = tts ?: return false
        if (!_ttsReady.value || text.isBlank()) return false
        val plik = java.io.File(context.cacheDir, "glosnik_${System.currentTimeMillis()}.wav")
        val id = "victor-file-${utteranceCounter.incrementAndGet()}"
        val done = CompletableDeferred<Boolean>()
        pendingUtterances[id] = done
        applyLanguage(language)
        val zlecone = runCatching {
            engine.synthesizeToFile(sanitizeForSpeech(text), android.os.Bundle(), plik, id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (zlecone != TextToSpeech.SUCCESS) {
            pendingUtterances.remove(id)
            return false
        }
        val ok = withTimeoutOrNull(speechTimeoutFor(text)) { done.await() } ?: false
        pendingUtterances.remove(id)
        if (!ok || !plik.exists()) return false
        return try {
            odtwórzNaGłośniku(plik)
        } finally {
            plik.delete()
        }
    }

    /**
     * Którędy naprawdę poszedł ostatni dźwięk "na głośnik telefonu" - do
     * dziennika (np. "głośnik telefonu (tryb rozmowy)" albo "W610T_A6BA").
     */
    @Volatile
    var ostatniaTrasaGłośnika: String? = null
        private set

    /**
     * Odtwarza plik Z GŁOŚNIKA TELEFONU, także przy podłączonych okularach.
     *
     * ## Czemu tryb rozmowy, a nie sam "preferowany głośnik"
     * Bieg 183: przekład dla rozmówcy grał w OKULARACH, choć odtwarzacz prosił
     * o głośnik telefonu. Multimedia (USAGE_MEDIA) przy aktywnym Bluetoothu
     * Samsung kieruje do słuchawek mimo setPreferredDevice. Jedyna droga, którą
     * Android gwarantuje (od 12), to dźwięk ROZMOWY z urządzeniem komunikacji
     * ustawionym na wbudowany głośnik - tak działa "głośnik" w połączeniu.
     * Po odtworzeniu przywracamy poprzednie urządzenie i tryb, bo z trybu
     * rozmowy korzysta też nasłuch przez mikrofon okularów.
     */
    private suspend fun odtwórzNaGłośniku(plik: java.io.File): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val głośnik = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val nowyAndroid = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        val poprzedniTryb = am.mode
        val poprzednieUrządzenie = if (nowyAndroid) runCatching { am.communicationDevice }.getOrNull() else null
        var wymuszone = false
        if (nowyAndroid && głośnik != null) {
            wymuszone = runCatching {
                am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                am.setCommunicationDevice(głośnik)
            }.getOrDefault(false)
            if (!wymuszone) runCatching { am.mode = poprzedniTryb }
        }
        val koniec = CompletableDeferred<Boolean>()
        val player = android.media.MediaPlayer()
        return try {
            player.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(
                        if (wymuszone) android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else android.media.AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(plik.absolutePath)
            player.prepare()
            if (głośnik != null) player.setPreferredDevice(głośnik)
            player.setOnCompletionListener { koniec.complete(true) }
            player.setOnErrorListener { _, _, _ -> koniec.complete(false); true }
            _speaking.value = true
            player.start()
            ostatniaTrasaGłośnika = runCatching {
                val d = player.routedDevice
                when {
                    d == null -> "nieznana"
                    d.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ->
                        "głośnik telefonu" + if (wymuszone) " (tryb rozmowy)" else ""
                    else -> d.productName?.toString() ?: "urządzenie typu ${d.type}"
                }
            }.getOrNull()
            withTimeoutOrNull(player.duration.toLong() + 3_000L) { koniec.await() } ?: false
        } catch (e: Exception) {
            Log.w(tag, "Odtwarzanie na głośniku telefonu nie wyszło", e)
            false
        } finally {
            runCatching { player.release() }
            if (wymuszone) {
                runCatching {
                    val wróć = poprzednieUrządzenie?.takeIf {
                        it.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (wróć != null) am.setCommunicationDevice(wróć) else am.clearCommunicationDevice()
                    am.mode = poprzedniTryb
                }
            }
            if (runCatching { tts?.isSpeaking }.getOrNull() != true && !cloudVoice.isPlaying) {
                _speaking.value = false
            }
        }
    }

    /**
     * Podpina nasłuch zakończenia wypowiedzi. Bez niego [speakAndAwait] nie ma
     * skąd wiedzieć, kiedy silnik faktycznie przestał mówić.
     */
    private fun installUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                complete(utteranceId, true)
            }

            @Deprecated("Wymagane przez klasę bazową", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                complete(utteranceId, false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(tag, "Błąd TTS ($errorCode) dla $utteranceId")
                complete(utteranceId, false)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                complete(utteranceId, false)
            }
        })
    }

    private fun complete(utteranceId: String?, success: Boolean) {
        // Przed wczesnym powrotem: koniec wypowiedzi bez identyfikatora też
        // może być końcem mówienia. Chyba że gra jeszcze głos z chmury.
        if (runCatching { tts?.isSpeaking }.getOrNull() != true && !cloudVoice.isPlaying) {
            _speaking.value = false
        }
        val id = utteranceId ?: return
        pendingUtterances.remove(id)?.complete(success)
    }

    private fun completeAllPending(success: Boolean) {
        val ids = pendingUtterances.keys.toList()
        ids.forEach { complete(it, success) }
    }

    // === Recording ===

    /**
     * Rozpoczyna nagrywanie z mikrofonu do pliku .m4a.
     */
    fun startRecording(): Boolean {
        if (_isRecording.value) {
            Log.w(tag, "Already recording")
            return false
        }

        return try {
            val outputFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.m4a")

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            _isRecording.value = true
            _lastRecordingPath.value = outputFile.absolutePath
            Log.d(tag, "Recording started: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start recording", e)
            cleanupRecorder()
            false
        }
    }

    /**
     * Zatrzymuje nagrywanie. Zwraca ścieżkę do pliku lub null.
     */
    fun stopRecording(): String? {
        if (!_isRecording.value) {
            return null
        }

        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            _isRecording.value = false

            val path = _lastRecordingPath.value
            Log.d(tag, "Recording stopped: $path")
            path
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop recording", e)
            cleanupRecorder()
            null
        }
    }

    /**
     * Zwraca audio jako ByteArray (do wysłania do AI).
     */
    fun readLastRecording(): ByteArray? {
        val path = _lastRecordingPath.value ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return file.readBytes()
    }

    private fun cleanupRecorder() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        _isRecording.value = false
    }

    // === Odtwarzanie audio (np. nagranej odpowiedzi AI) ===

    private var mediaPlayer: MediaPlayer? = null

    /**
     * Odtwarza audio z tablicy bajtów (np. odpowiedź AI w formacie audio).
     */
    /**
     * Zapisuje bufor audio do pliku tymczasowego w cache.
     * MediaPlayer potrafi odtwarzać tylko z pliku lub URI, nie z tablicy bajtów.
     */
    private fun writeToTempFile(bytes: ByteArray): java.io.File {
        val file = java.io.File.createTempFile("victor_audio_", ".tmp", context.cacheDir)
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    fun playAudio(bytes: ByteArray, onComplete: (() -> Unit)? = null) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                // MediaPlayer nie przyjmuje strumienia z pamięci - trzeba pliku.
                setDataSource(writeToTempFile(bytes).absolutePath)
                prepare()
                setOnCompletionListener {
                    onComplete?.invoke()
                    release()
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to play audio", e)
        }
    }

    // === Cleanup ===

    fun shutdown() {
        stopSpeaking()
        cloudVoice.shutdown()
        stopRecording()
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        _ttsReady.value = false
        Log.d(tag, "AudioManager shut down")
    }

    companion object {
        /** Po tylu ms ciszy głośność podbita na gwar wraca do ustawionej. */
        private const val RESTORE_AFTER_MS = 2_500L

        /** Głośność sygnału "teraz mów" w skali ToneGenerator (0-100). */
        private const val CUE_VOLUME = 70

        /** Krótko - to ma być znak, nie melodia. */
        private const val CUE_DURATION_MS = 140

        /** Zapas, żeby zwolnienie generatora nie ucięło dźwięku. */
        private const val CUE_RELEASE_MARGIN_MS = 150L

        /** Stały narzut na rozruch silnika TTS, zanim w ogóle padnie pierwsze słowo. */
        private const val SPEECH_BASE_TIMEOUT_MS = 4_000L

        /** Mowa idzie ~12 znaków/s; 120 ms/znak to spory zapas nawet dla wolnego tempa. */
        private const val MS_PER_CHARACTER = 120L

        /** Twardy sufit - żaden pojedynczy fragment odpowiedzi nie trwa dłużej. */
        private const val MAX_SPEECH_TIMEOUT_MS = 120_000L

        /**
         * Ile najdłużej czekamy, aż syntezator dojdzie do końca strumienia.
         *
         * Hojnie, bo w kolejce stoi CAŁA odpowiedź, nie jedno zdanie - a limit
         * jest tu bezpiecznikiem na wypadek silnika, który nie zgłosi końca
         * (zdarza się, gdy dźwięk przejmie rozmowa telefoniczna), nie planem.
         */
        private const val STREAM_DRAIN_TIMEOUT_MS = 150_000L

        /** Zapas w [speakAndAwait] na pierwszy dźwięk z chmury i ewentualne przejście na systemowy. */
        private val CLOUD_EXTRA_TIMEOUT_MS = CloudVoiceConfig.FIRST_SOUND_TIMEOUT_MS + 1_500L

        /** Próbka głosu z chmury - dwa zdania, żeby było słychać przejście między nimi. */
        private const val CLOUD_SAMPLE_TEXT =
            "Cześć, tak brzmi mój głos z chmury. Odpowiedzi słyszysz zdanie po zdaniu, " +
                "a gdy sieć zawiedzie, wracam do głosu telefonu."

        // Wzorce czyszczenia tekstu przed syntezą - patrz `sanitizeForSpeech`.

        /** `[tekst](adres)` -> `tekst`. */
        private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)\]\([^)]*\)""")

        /** Goły adres czytany znak po znaku trwa dłużej niż cała odpowiedź. */
        private val URL_REGEX = Regex("""https?://\S+|www\.\S+""")

        /** Blok kodu wypada w całości - czytanie go na głos nie ma sensu. */
        private val CODE_FENCE_REGEX = Regex("""```[\s\S]*?```""")

        /** Pojedyncze backticki znikają, ale TREŚĆ zostaje - to zwykle nazwa albo komenda. */
        private val INLINE_CODE_REGEX = Regex("""`""")

        /**
         * Same gwiazdki. Podkreśleń celowo NIE ruszamy - w polskim tekście
         * gwiazdka praktycznie nie występuje, a podkreślenie owszem
         * (nazwy plików, identyfikatory), więc usuwanie go psułoby słowa.
         */
        private val EMPHASIS_REGEX = Regex("""\*+""")

        private val HEADING_REGEX = Regex("""(?m)^\s{0,3}#{1,6}\s*""")

        /** Myślnik albo numer na początku linii - syntezator czyta je jako słowo. */
        private val BULLET_REGEX = Regex("""(?m)^\s{0,3}(?:[-*•]|\d{1,2}[.)])\s+""")

        /**
         * Strzałka nawigacyjna z komunikatów typu "Ustawienia → Klucz API".
         *
         * Na ekranie czyta się świetnie, ale syntezator albo ją pomija, albo
         * mówi "strzałka w prawo" w środku zdania. Przecinek daje to samo
         * znaczenie i brzmi jak zdanie, a nie jak opis interfejsu.
         */
        private val ARROW_REGEX = Regex("""\s*(?:→|⇒|»|->)\s*""")

        private val WHITESPACE_REGEX = Regex("""\s+""")

        /**
         * Mapa kodów trzyliterowych na dwuliterowe ("pol" -> "pl").
         * JDK ma tylko konwersję w drugą stronę, więc budujemy ją z listy
         * znanych języków. Patrz `languageCodeOf`.
         */
        private val ISO3_TO_ISO2: Map<String, String> by lazy {
            buildMap {
                Locale.getISOLanguages().forEach { iso2 ->
                    val iso3 = runCatching { Locale(iso2).isO3Language }.getOrNull()
                    if (!iso3.isNullOrBlank()) put(iso3.lowercase(Locale.ROOT), iso2)
                }
            }
        }

        @Volatile
        private var instance: AudioManager? = null

        fun getInstance(context: Context): AudioManager {
            return instance ?: synchronized(this) {
                instance ?: AudioManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

/**
 * Informacja o głosie TTS dostępnym w systemie.
 */
data class VoiceInfo(
    /** Unikalna nazwa głosu (np. "pl-pl-x-oda-local") */
    val name: String,
    /** Nazwa wyświetlana w UI */
    val displayName: String,
    /** Język (np. "polski") */
    val language: String,
    /** Locale (np. "pl-PL") */
    val locale: String,
    /** Płeć: "M" / "F" / "?" */
    val gender: String,
    /** Jakość głosu */
    val quality: String,
    /** Czy wymaga internetu (Google Cloud TTS) */
    val isNetwork: Boolean,
    /** Czy faktycznie wymaga sieci do działania */
    val requiresNetwork: Boolean,
    /** Czy głos jest zainstalowany offline (nie trzeba pobierać) */
    val isInstalledOffline: Boolean = !requiresNetwork,
    /** Kod języka (pl, en, de, ...) */
    val languageCode: String = locale.split("-").firstOrNull() ?: "",
    /** Czy to polski głos */
    val isPolish: Boolean = languageCode == "pl"
) {
    /** Etykieta statusu dla UI */
    val statusLabel: String = when {
        isPolish && isInstalledOffline -> "✓ Polski offline"
        isPolish && !isInstalledOffline -> "⚠ Polski (wymaga sieci)"
        isInstalledOffline -> "✓ Offline"
        else -> "☁ Online"
    }
}
