package pl.victor.app.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Kieruje CAŁĄ rozmowę - i słuchanie, i mówienie - przez zestaw słuchawkowy
 * Bluetooth (czyli przez okulary), z automatycznym powrotem na telefon, gdy
 * żadnego nie ma.
 *
 * ## To NIE jest protokół BLE okularów
 * Zdjęcia, przycisk i sterowanie idą przez BLE (`VictorManager`) - osobny,
 * niskoprzepustowy kanał, nienadający się do dźwięku. Tu jest standardowy
 * mechanizm Androida (Bluetooth Classic, profil HFP/SCO), który działa tak
 * samo dla każdego zestawu słuchawkowego. Okularom mówimy tylko `openBT()`
 * (patrz `VictorManager.onGlassesReady`), żeby włączyły swoją część klasyczną -
 * resztę robi system.
 *
 * ## Dlaczego licznik zamiast zwykłego start/stop
 * Zestawienie łącza SCO trwa ułamek do kilku sekund. Poprzednia wersja
 * podnosiła je i rozbierała przy KAŻDEJ wypowiedzi, więc rozmowa rwała się na
 * każdej turze, a między pytaniem a odpowiedzią okulary potrafiły zamilknąć.
 * Teraz orkiestrator trzyma łącze na całą rozmowę ([acquire]/[release] są
 * zliczane), a rozpoznawanie mowy tylko dokłada swoje odwołanie.
 *
 * ## Dwie epoki API
 * - Android 12+ (API 31): [AudioManager.setCommunicationDevice] - synchroniczne,
 *   zwraca czy się udało. To jedyna droga, która na nowszych telefonach
 *   (m.in. Samsung) faktycznie działa.
 * - Starsze: `startBluetoothSco()` + czekanie na rozgłoszenie stanu. Metoda jest
 *   od API 31 wycofana i bywa cicho ignorowana - stąd rozdział.
 */
class BluetoothAudioRouter private constructor(private val context: Context) {

    private val tag = "BluetoothAudioRouter"
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val scope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() +
            pl.victor.app.utils.loggingExceptionHandler(tag)
    )
    private val lock = Mutex()
    private var holdCount = 0
    private var scoReceiver: BroadcastReceiver? = null
    private var previousMode: Int? = null
    private var teardownJob: Job? = null

    private val _isRoutedToBluetooth = MutableStateFlow(false)

    /** Czy dźwięk faktycznie idzie teraz przez zestaw Bluetooth (okulary). */
    val isRoutedToBluetooth: StateFlow<Boolean> = _isRoutedToBluetooth.asStateFlow()

    /**
     * Atrybuty dźwięku dla syntezy mowy.
     *
     * Kluczowe dla "okulary nie mówią": gdy łącze SCO jest podniesione, dźwięk
     * z `USAGE_ASSISTANT`/`STREAM_MUSIC` **nie idzie** przez zestaw
     * słuchawkowy - trafia w głośnik telefonu albo w nic. Przez SCO idzie
     * wyłącznie ścieżka rozmowy, czyli `USAGE_VOICE_COMMUNICATION`.
     */
    fun ttsAudioAttributes(): AudioAttributes =
        if (_isRoutedToBluetooth.value) {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        } else {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        }

    /**
     * Czy zestaw Bluetooth jest podłączony jako urządzenie MULTIMEDIALNE (A2DP).
     *
     * Od tego zależy, czy da się do niego mówić BEZ zajmowania profilu rozmowy.
     * Gdy jest - wypowiedź idzie przez A2DP i okulary zostają w trybie
     * multimediów. Gdy go nie ma (sparowane tylko jako zestaw głośnomówiący),
     * jedyną drogą do ich głośnika jest SCO - a wtedy trzeba je wziąć, bo
     * inaczej odpowiedź poleciałaby z głośnika telefonu w kieszeni.
     */
    fun hasA2dpOutput(): Boolean {
        val am = audioManager ?: return false
        if (!hasBluetoothPermission()) return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } catch (e: Exception) {
            Log.w(tag, "Nie udało się sprawdzić wyjść A2DP", e)
            false
        }
    }

    /** Czy telefon widzi PODŁĄCZONY (nie tylko sparowany) zestaw audio Bluetooth. */
    fun hasConnectedBluetoothAudioDevice(): Boolean {
        val am = audioManager ?: return false
        if (!hasBluetoothPermission()) return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } || am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } catch (e: Exception) {
            Log.w(tag, "Nie udało się sprawdzić urządzeń audio", e)
            false
        }
    }

    /**
     * Opis tego, JAK telefon widzi okulary od strony dźwięku.
     *
     * Rozróżnienie jest kluczowe i nieoczywiste: **A2DP** to tylko odtwarzanie
     * (okulary mówią), **SCO/HFP** to dwukierunkowa rozmowa (okulary mówią i
     * słyszą). Zestaw, który wystawia wyłącznie A2DP, będzie dobrze odtwarzał
     * odpowiedzi, ale mikrofon poleci z telefonu - i bez tej informacji wygląda
     * to na losową usterkę ("słyszę w okularach, ale mnie nie słyszy").
     */
    fun audioProfileSummary(): String {
        val am = audioManager ?: return "Brak dostępu do systemu audio."
        if (!hasBluetoothPermission()) return "Brak uprawnienia Bluetooth."
        return try {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val a2dp = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            val scoOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            val scoIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

            buildString {
                append("Odtwarzanie (A2DP): ")
                append(a2dp?.productName?.toString() ?: "brak")
                append('\n')
                append("Rozmowa (SCO/HFP): ")
                append(scoOut?.productName?.toString() ?: scoIn?.productName?.toString() ?: "brak")
                append('\n')
                if (scoOut == null && scoIn == null && a2dp != null) {
                    append("Okulary odtwarzają dźwięk, ale NIE wystawiają mikrofonu ")
                    append("jako zestawu rozmownego - pytania pójdą z mikrofonu telefonu.")
                } else if (scoIn != null) {
                    append("Mikrofon okularów jest dostępny dla rozmowy.")
                } else if (a2dp == null) {
                    append("Telefon nie widzi okularów jako urządzenia audio. ")
                    append("Sparuj je w ustawieniach Bluetooth telefonu.")
                }
            }
        } catch (e: Exception) {
            "Nie udało się odczytać urządzeń audio: ${e.message}"
        }
    }

    /**
     * Czy telefon widzi JAKIKOLWIEK bluetoothowy mikrofon (SCO/HFP).
     *
     * Krótka wersja [audioProfileSummary] - do wplecenia w komunikat błędu, gdzie
     * kilka linijek diagnostyki byłoby nie do przeczytania. Odróżnia jedyne dwie
     * rzeczy, które w tym momencie mają znaczenie: czy pytanie w ogóle miało
     * szansę pójść z mikrofonu okularów, czy z telefonu.
     */
    fun hasConversationMic(): Boolean {
        val am = audioManager ?: return false
        if (!hasBluetoothPermission()) return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } catch (e: Exception) {
            Log.w(tag, "Odczyt urządzeń wejściowych nie powiódł się", e)
            false
        }
    }

    /**
     * Nazwa podłączonego zestawu - do pokazania w diagnostyce, żeby użytkownik
     * widział, czy telefon widzi okulary jako zestaw słuchawkowy.
     */
    fun connectedDeviceName(): String? {
        val am = audioManager ?: return null
        if (!hasBluetoothPermission()) return null
        return try {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.productName?.toString()
                ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.productName?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Czy w ogóle wolno zestawiać profil rozmowy (SCO/HFP).
     *
     * Wyłączone znaczy: nigdy nie ruszaj SCO. Odtwarzanie przez A2DP zostaje
     * nietknięte, a mikrofonem jest telefon. Ustawiane z
     * [pl.victor.app.data.SettingsRepository.isGlassesMicEnabled].
     */
    @Volatile
    var scoEnabled: Boolean = true

    /**
     * Podnosi (albo dokłada odwołanie do już podniesionego) łącza rozmowy przez
     * zestaw Bluetooth. Bezpieczne do wołania zawsze - bez zestawu po prostu
     * zwraca `false` i nic się nie dzieje.
     *
     * Każde udane [acquire] MUSI mieć swoje [release], najlepiej w `finally`.
     */
    suspend fun acquire(timeoutMs: Long = SCO_TIMEOUT_MS): Boolean = lock.withLock {
        if (!scoEnabled) {
            // Świadomy wybór użytkownika (albo automatyczne wyłączenie po serii
            // nieudanych tur): odpowiedź pójdzie przez A2DP, pytania zbierze
            // mikrofon telefonu. To działa zawsze - tylko gorzej słychać.
            return@withLock false
        }
        // Anuluj odroczone rozebranie - jeśli łącze wciąż stoi, przejmujemy je
        // bez ponownej negocjacji (to jest cała istota karencji, patrz release).
        teardownJob?.cancel()
        teardownJob = null

        if (holdCount > 0 || _isRoutedToBluetooth.value) {
            holdCount++
            return@withLock _isRoutedToBluetooth.value
        }
        val started = startRouting(timeoutMs)
        if (started) {
            holdCount = 1
            _isRoutedToBluetooth.value = true
        }
        started
    }

    /**
     * Zwalnia jedno odwołanie. Ostatnie NIE rozbiera łącza od razu - odczekuje
     * [LINGER_MS].
     *
     * Bez tej karencji rozmowa rwałaby się na każdej turze: orkiestrator zwalnia
     * łącze po odpowiedzi, tryb konwersacyjny bierze je z powrotem ułamek
     * sekundy później pod kolejne pytanie - a każde takie zestawienie SCO to
     * nawet kilka sekund ciszy w okularach. Karencja przykrywa te przerwy;
     * po dłuższej bezczynności telefon i tak wraca do trybu normalnego.
     */
    suspend fun release() = lock.withLock {
        if (holdCount == 0) return@withLock
        holdCount--
        if (holdCount > 0) return@withLock

        teardownJob?.cancel()
        teardownJob = scope.launch {
            delay(LINGER_MS)
            lock.withLock {
                if (holdCount == 0) {
                    stopRouting()
                    _isRoutedToBluetooth.value = false
                }
            }
        }
    }

    /**
     * Rozbiera łącze niezależnie od licznika - do użycia przy awariach i przy
     * zamykaniu aplikacji, żeby nie zostawić telefonu w trybie rozmowy.
     */
    suspend fun releaseAll() = lock.withLock {
        teardownJob?.cancel()
        teardownJob = null
        holdCount = 0
        stopRouting()
        _isRoutedToBluetooth.value = false
    }

    // === Zgodność ze starszym wywołaniem w SpeechToText ===

    @Deprecated("Użyj acquire()", ReplaceWith("acquire(timeoutMs)"))
    suspend fun startScoAndAwait(timeoutMs: Long = SCO_TIMEOUT_MS): Boolean = acquire(timeoutMs)

    private suspend fun startRouting(timeoutMs: Long): Boolean {
        val am = audioManager ?: return false
        if (!hasConnectedBluetoothAudioDevice()) {
            Log.d(tag, "Brak podłączonego zestawu Bluetooth - zostaję na telefonie")
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startModern(am)
        } else {
            startLegacy(am, timeoutMs)
        }
    }

    private fun startModern(am: AudioManager): Boolean {
        val device = try {
            am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } catch (e: Exception) {
            Log.w(tag, "Nie udało się odczytać listy urządzeń rozmowy", e)
            return false
        }
        if (device == null) {
            Log.d(tag, "Brak urządzenia SCO na liście do rozmowy")
            return false
        }

        // TRYB ROZMOWY PODNOSIMY DOPIERO TUTAJ I ODDAJEMY GO NA KAŻDEJ DRODZE
        // WYJŚCIA.
        //
        // Wcześniej `mode` był ustawiany PRZED setCommunicationDevice, a wyjątek
        // z tego wywołania wychodził przez catch BEZ restoreMode. Skoro acquire()
        // zwracało wtedy false, nikt nie wołał release() - i telefon zostawał w
        // MODE_IN_COMMUNICATION na stałe, czyli grał "tylko połączenia" zamiast
        // multimediów, aż do ubicia procesu. To jest trzecia przyczyna tego
        // zgłoszenia; dwie poprzednie (SCO brane przy każdym nasłuchu i na całą
        // turę) usunęły commity d219557 i 17b9555.
        previousMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        return try {
            val ok = am.setCommunicationDevice(device)
            if (ok) {
                Log.i(tag, "Rozmowa przez ${device.productName}")
            } else {
                Log.w(tag, "setCommunicationDevice odmówiło - wracam na telefon")
                restoreMode(am)
            }
            ok
        } catch (e: Exception) {
            Log.w(tag, "Nie udało się ustawić urządzenia rozmowy", e)
            restoreMode(am)
            false
        }
    }

    private suspend fun startLegacy(am: AudioManager, timeoutMs: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED ->
                        if (!deferred.isCompleted) deferred.complete(true)
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                    AudioManager.SCO_AUDIO_STATE_ERROR ->
                        if (!deferred.isCompleted) deferred.complete(false)
                }
            }
        }
        scoReceiver = receiver

        return try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            previousMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.startBluetoothSco()

            val connected = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
            if (connected) {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                Log.i(tag, "SCO połączone - rozmowa przez zestaw Bluetooth")
            } else {
                Log.d(tag, "SCO nie połączyło się w ${timeoutMs}ms - zostaję na telefonie")
                stopRouting()
            }
            connected
        } catch (e: Exception) {
            Log.w(tag, "Nie udało się uruchomić SCO", e)
            stopRouting()
            false
        }
    }

    private fun stopRouting() {
        scoReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        scoReceiver = null
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
        } catch (e: Exception) {
            Log.w(tag, "Nie udało się zatrzymać routingu Bluetooth", e)
        }
        restoreMode(am)
    }

    private fun restoreMode(am: AudioManager) {
        val mode = previousMode ?: AudioManager.MODE_NORMAL
        previousMode = null
        runCatching { am.mode = mode }
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val SCO_TIMEOUT_MS = 4_000L

        /**
         * Ile trzymać łącze po zwolnieniu ostatniego odwołania. Dobrane tak, by
         * przykryć przerwę między odpowiedzią a kolejnym pytaniem w trybie
         * konwersacyjnym, ale nie trzymać telefonu w trybie rozmowy bez potrzeby.
         */
        private const val LINGER_MS = 8_000L

        @Volatile
        private var instance: BluetoothAudioRouter? = null

        /**
         * Jedna instancja na proces. Routing audio to stan GLOBALNY telefonu -
         * dwa niezależne routery deptałyby sobie po trybie i po urządzeniu
         * rozmowy (jeden by je zwalniał, gdy drugi jeszcze go potrzebuje).
         */
        fun getInstance(context: Context): BluetoothAudioRouter =
            instance ?: synchronized(this) {
                instance ?: BluetoothAudioRouter(context.applicationContext).also { instance = it }
            }
    }
}
