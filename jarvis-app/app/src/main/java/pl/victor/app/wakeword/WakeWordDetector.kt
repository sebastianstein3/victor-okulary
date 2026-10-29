package pl.victor.app.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.victor.app.audio.AudioManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Detektor wake word używający Porcupine (Picovoice).
 *
 * On-device, offline, ultra-low-latency. Obsługuje wiele języków i custom keywords.
 *
 * Jak uzyskać AccessKey:
 * 1. Zarejestruj się na https://console.picovoice.ai/ (darmowy tier)
 * 2. Utwórz projekt
 * 3. Skopiuj AccessKey
 * 4. Wpisz w Ustawieniach aplikacji
 *
 * Domyślne wbudowane keywords (angielskie):
 * - "computer" (domyślna komenda aplikacji, patrz [pl.victor.app.data.WakeWordRegistry])
 * - "jarvis" (Iron Man)
 * - "alexa"
 * - "hey siri" (uwaga: może kolidować z iPhone)
 * - "ok google"
 * - "porcupine"
 *
 * Docelowa fraza aplikacji, "Hey Victor", nie jest wśród nich - jak każda fraza
 * spoza tej listy (w tym polska), wymaga wytrenowania custom keyword w konsoli
 * Picovoice.
 */
class WakeWordDetector(
    private val context: Context
) {
    private val tag = "WakeWordDetector"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var porcupine: Porcupine? = null
    private var audioManager: VoiceProcessor? = null
    private var isListening = false
    private var selectedKeyword: String = "computer"
    private val bluetoothRouter = pl.victor.app.audio.BluetoothAudioRouter.getInstance(context)
    private val settings = pl.victor.app.data.SettingsRepository.getInstance(context)

    /** Czy trzymamy odwołanie do routera - żeby release() nie zszedł poniżej zera. */
    @Volatile
    private var holdsBluetooth = false

    // Event po wykryciu
    private val _detectionEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val detectionEvent: SharedFlow<String> = _detectionEvent.asSharedFlow()

    // Stan
    private val _state = MutableStateFlow(WakeWordState.IDLE)
    val state: StateFlow<WakeWordState> = _state.asStateFlow()

    /**
     * Mapuje nazwę komendy na wbudowany keyword Porcupine.
     *
     * Porcupine przyjmuje wyłącznie te wartości; własna fraza wymaga modelu
     * `.ppn` wytrenowanego w konsoli Picovoice. Nieznana nazwa schodzi
     * na "jarvis" zamiast wywalać inicjalizację.
     */
    /**
     * Zamienia nazwę na wbudowaną komendę Porcupine.
     *
     * Zwraca `null`, a **nie** JARVIS, gdy nazwy nie ma na liście. Wcześniejsza
     * wersja po cichu podmieniała frazę, przez co użytkownik wybierał
     * „Cześć” albo „Jarvis Start”, a urządzenie reagowało na „jarvis” - i nie
     * było jak tego zauważyć poza logiem.
     */
    private fun toBuiltInKeyword(keyword: String): Porcupine.BuiltInKeyword? =
        when (keyword.trim().lowercase().replace(' ', '_')) {
            "alexa" -> Porcupine.BuiltInKeyword.ALEXA
            "americano" -> Porcupine.BuiltInKeyword.AMERICANO
            "blueberry" -> Porcupine.BuiltInKeyword.BLUEBERRY
            "bumblebee" -> Porcupine.BuiltInKeyword.BUMBLEBEE
            "computer" -> Porcupine.BuiltInKeyword.COMPUTER
            "grapefruit" -> Porcupine.BuiltInKeyword.GRAPEFRUIT
            "grasshopper" -> Porcupine.BuiltInKeyword.GRASSHOPPER
            "hey_google" -> Porcupine.BuiltInKeyword.HEY_GOOGLE
            "hey_siri" -> Porcupine.BuiltInKeyword.HEY_SIRI
            "jarvis" -> Porcupine.BuiltInKeyword.JARVIS
            "ok_google" -> Porcupine.BuiltInKeyword.OK_GOOGLE
            "picovoice" -> Porcupine.BuiltInKeyword.PICOVOICE
            "porcupine" -> Porcupine.BuiltInKeyword.PORCUPINE
            "terminator" -> Porcupine.BuiltInKeyword.TERMINATOR
            else -> null
        }

    /**
     * Inicjalizuje Porcupine z danym AccessKey.
     * @return true jeśli OK
     */
    suspend fun initialize(
        accessKey: String,
        keyword: String = "computer",
        keywordPath: String = "",
        modelPath: String = ""
    ): InitResult {
        if (!hasRecordPermission()) {
            Log.w(tag, "Brak RECORD_AUDIO permission")
            _state.value = WakeWordState.NO_PERMISSION
            return InitResult.NoPermission
        }
        if (accessKey.isBlank()) {
            _state.value = WakeWordState.ERROR
            return InitResult.NoAccessKey
        }

        val builtIn = toBuiltInKeyword(keyword)
        val hasCustomModel = keywordPath.isNotBlank() && File(keywordPath).isFile
        if (builtIn == null && !hasCustomModel) {
            // Świadomie nie schodzimy na JARVIS - lepiej powiedzieć wprost,
            // że tej frazy nie da się rozpoznać, niż nasłuchiwać innej.
            Log.w(tag, "Fraza \"$keyword\" wymaga własnego modelu .ppn")
            _state.value = WakeWordState.ERROR
            return InitResult.NeedsCustomModel(keyword)
        }

        return withContext(Dispatchers.IO) {
            try {
                // Cleanup stary
                release()

                val builder = Porcupine.Builder().setAccessKey(accessKey)
                if (hasCustomModel) {
                    builder.setKeywordPath(keywordPath)
                    // Model językowy jest potrzebny tylko dla fraz nieangielskich.
                    if (modelPath.isNotBlank() && File(modelPath).isFile) {
                        builder.setModelPath(modelPath)
                    }
                } else {
                    builder.setKeyword(builtIn)
                }
                porcupine = builder.build(context)

                selectedKeyword = keyword
                _state.value = WakeWordState.READY
                Log.i(tag, "Porcupine gotowy, fraza: $keyword" +
                    if (hasCustomModel) " (własny model)" else " (wbudowana)")
                InitResult.Success
            } catch (e: PorcupineException) {
                Log.e(tag, "Porcupine init failed", e)
                _state.value = WakeWordState.ERROR
                InitResult.Failed(e.message ?: "błąd Porcupine")
            } catch (e: Exception) {
                Log.e(tag, "Porcupine init failed", e)
                _state.value = WakeWordState.ERROR
                InitResult.Failed(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /**
     * Rozpoczyna nasłuchiwanie w tle.
     *
     * Próbuje najpierw urządzenia audio Bluetooth (patrz [BluetoothAudioRouter]) -
     * jeśli telefon nie ma żadnego podłączonego, albo połączenie się nie uda,
     * bez żadnego dodatkowego kroku wraca na mikrofon telefonu (dzisiejsze
     * zachowanie).
     */
    fun startListening() {
        if (isListening) return
        val p = porcupine ?: run {
            Log.w(tag, "Porcupine not initialized")
            return
        }

        if (!hasRecordPermission()) {
            _state.value = WakeWordState.NO_PERMISSION
            return
        }

        // Ustawiane od razu - inaczej szybkie podwójne wywołanie (zanim korutyna
        // niżej ruszy) wystartowałoby nasłuch dwa razy.
        isListening = true
        scope.launch {
            // NASŁUCH FRAZY NIE ZAJMUJE JUŻ PROFILU ROZMOWY.
            //
            // Mikrofon zestawu Bluetooth działa tylko przez SCO/HFP, a nasłuch
            // frazy trwa BEZ PRZERWY - więc zajmowanie go tutaj trzymało okulary
            // w trybie rozmowy przez cały czas. Android pokazywał je wtedy jako
            // urządzenie "do połączeń", nie "do multimediów", a przełącznik
            // multimediów w ustawieniach systemu wracał sam do wyłączenia, bo
            // aplikacja natychmiast zajmowała profil z powrotem. Zgłoszone
            // dokładnie w tych słowach.
            //
            // Wybudzanie okularami działa i bez tego: okulary wysyłają je własną
            // drogą po BLE. Fraza wypowiedziana do TELEFONU idzie teraz przez
            // mikrofon telefonu, a kto woli inaczej, włącza to w ustawieniach i
            // przyjmuje opisany tam koszt.
            val useBluetooth =
                settings.isWakeWordOverGlassesMic() && bluetoothRouter.acquire()
            // stopListening() wywołane w trakcie negocjowania łącza (do kilku
            // sekund) nie miało czego zatrzymać ani zwolnić - nagrywanie startowało
            // zaraz po nim, a licznik routera zostawał podbity na stałe (SCO i tryb
            // rozmowy bez końca, A2DP zablokowane). Sprawdzamy, czy ktoś nie
            // zmienił zdania.
            if (!isListening) {
                if (useBluetooth) bluetoothRouter.release()
                return@launch
            }
            holdsBluetooth = useBluetooth
            startListeningWithSource(p, useBluetooth)
        }
    }

    private fun startListeningWithSource(p: Porcupine, useBluetooth: Boolean) {
        try {
            val source = if (useBluetooth) {
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                android.media.MediaRecorder.AudioSource.MIC
            }
            audioManager = VoiceProcessor.Builder()
                .sampleRate(p.sampleRate)
                .frameLength(p.frameLength)
                .audioSource(source)
                .build()

            audioManager?.addListener(object : VoiceProcessor.VoiceProcessorListener {
                override fun onFrame(pcm: ShortArray) {
                    try {
                        val result = porcupine?.process(pcm) ?: -1
                        if (result >= 0) {
                            // Wykryto wake word!
                            Log.i(tag, "Wake word detected: $selectedKeyword")
                            _detectionEvent.tryEmit(selectedKeyword)
                        }
                    } catch (e: PorcupineException) {
                        Log.e(tag, "Process error", e)
                    }
                }
            })

            audioManager?.start()
            _state.value = WakeWordState.LISTENING
            Log.i(tag, "Started listening for '$selectedKeyword'" + if (useBluetooth) " (Bluetooth)" else "")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start listening", e)
            isListening = false
            releaseBluetooth()
            _state.value = WakeWordState.ERROR
        }
    }

    /**
     * Zwalnia odwołanie do routera Bluetooth, jeśli je trzymamy.
     *
     * `release()` jest zawieszalne (router serializuje zmiany routingu), a
     * `stopListening()` woła kod z UI - stąd `scope.launch`. Flaga pilnuje, żeby
     * podwójne zatrzymanie nie zwolniło cudzego odwołania: router jest wspólny
     * dla wykrywania słowa kluczowego, rozpoznawania mowy i syntezy.
     */
    private fun releaseBluetooth() {
        if (!holdsBluetooth) return
        holdsBluetooth = false
        scope.launch { bluetoothRouter.release() }
    }

    /**
     * Zatrzymuje nasłuchiwanie.
     */
    fun stopListening() {
        if (!isListening) return
        try {
            audioManager?.stop()
            isListening = false
            releaseBluetooth()
            _state.value = WakeWordState.READY
            Log.i(tag, "Stopped listening")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping", e)
        }
    }

    /**
     * Cleanup zasobów.
     */
    fun release() {
        stopListening()
        try {
            porcupine?.delete()
        } catch (_: Exception) {}
        porcupine = null
        _state.value = WakeWordState.IDLE
    }

    private fun hasRecordPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Lista wbudowanych keywords (angielskie).
     */
    companion object {
        /** Nazwy odpowiadające Porcupine.BuiltInKeyword. */
        val BUILT_IN_KEYWORDS = listOf(
            "jarvis",
            "computer",
            "alexa",
            "hey siri",
            "hey google",
            "ok google",
            "picovoice",
            "porcupine",
            "bumblebee",
            "blueberry",
            "grapefruit",
            "grasshopper",
            "americano",
            "terminator"
        )

        // Wymagane uprawnienia
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO
        )
    }
}

/**
 * Stan detektora.
 */
/**
 * Wynik inicjalizacji wykrywania komendy.
 *
 * Osobne przypadki zamiast `Boolean`, bo każdy wymaga od użytkownika czegoś
 * innego, a wcześniej wszystkie ginęły pod jednym `false`.
 */
sealed class InitResult {
    object Success : InitResult()

    /** Brak uprawnienia RECORD_AUDIO. */
    object NoPermission : InitResult()

    /** Nie wpisano klucza Picovoice. */
    object NoAccessKey : InitResult()

    /** Fraza nie jest wbudowana i nie wskazano pliku `.ppn`. */
    data class NeedsCustomModel(val phrase: String) : InitResult()

    /** Porcupine odmówił - zwykle zły klucz albo niezgodny model. */
    data class Failed(val reason: String) : InitResult()

    val isSuccess: Boolean get() = this is Success

    /** Komunikat dla użytkownika - po polsku, z konkretem co zrobić. */
    fun message(): String = when (this) {
        is Success -> "Wykrywanie komendy włączone."
        is NoPermission -> "Brak zgody na dostęp do mikrofonu."
        is NoAccessKey -> "Brak klucza Picovoice - wpisz go w ustawieniach."
        is NeedsCustomModel ->
            "Fraza „$phrase” nie jest wbudowana w Porcupine. Wytrenuj model .ppn " +
                "na console.picovoice.ai i wskaż plik w ustawieniach."
        is Failed -> "Nie udało się uruchomić wykrywania komendy: $reason"
    }
}

enum class WakeWordState {
    IDLE,           // nie zainicjalizowany
    READY,          // zainicjalizowany, nie nasłuchuje
    LISTENING,      // nasłuchuje
    NO_PERMISSION,  // brak RECORD_AUDIO
    ERROR           // błąd
}

/**
 * Wrapper na VoiceProcessor (Picovoice) - opakowuje dostęp do mikrofonu.
 *
 * UWAGA: VoiceProcessor w wersji 3.x ma inne API niż Porcupine 2.x.
 * Ten wrapper izoluje różnice.
 */
class VoiceProcessor private constructor(
    val sampleRate: Int,
    val frameLength: Int,
    audioSource: Int = android.media.MediaRecorder.AudioSource.MIC
) {
    private val listeners = mutableListOf<VoiceProcessorListener>()
    private val recorder = android.media.AudioRecord(
        audioSource,
        sampleRate,
        android.media.AudioFormat.CHANNEL_IN_MONO,
        android.media.AudioFormat.ENCODING_PCM_16BIT,
        sampleRate * 2  // buffer size
    )
    @Volatile
    private var running = false
    private var thread: Thread? = null

    interface VoiceProcessorListener {
        fun onFrame(pcm: ShortArray)
    }

    fun addListener(l: VoiceProcessorListener) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun start() {
        if (running) return
        running = true
        recorder.startRecording()
        thread = thread(name = "VoiceProcessor") {
            val frame = ShortArray(frameLength)
            while (running) {
                val read = recorder.read(frame, 0, frame.size)
                if (read > 0) {
                    synchronized(listeners) {
                        listeners.toList().forEach { it.onFrame(frame) }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (_: Exception) {}
        thread = null
        try {
            recorder.stop()
        } catch (_: Exception) {}
        // Każdy cykl stop/start (tryb konwersacyjny robi to co turę) tworzy NOWY
        // procesor z nowym natywnym AudioRecord - stary musi być zwolniony.
        try {
            recorder.release()
        } catch (_: Exception) {}
    }

    data class Builder(
        private var sampleRate: Int = 16000,
        private var frameLength: Int = 512,
        private var audioSource: Int = android.media.MediaRecorder.AudioSource.MIC
    ) {
        fun sampleRate(rate: Int) = apply { sampleRate = rate }
        fun frameLength(length: Int) = apply { frameLength = length }
        fun audioSource(source: Int) = apply { audioSource = source }
        fun build() = VoiceProcessor(sampleRate, frameLength, audioSource)
    }
}
