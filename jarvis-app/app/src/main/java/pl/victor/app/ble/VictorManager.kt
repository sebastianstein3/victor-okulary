package pl.victor.app.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.Constants
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * VictorManager - warstwa dostępu do okularów HeyCyan (vendor SDK `glasses_sdk_20250723_v01.aar`).
 *
 * Implementacja oparta o zweryfikowane API AAR (javap na classes.jar) oraz o protokół
 * potwierdzony w działającej aplikacji referencyjnej CyanBridge
 * (github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK).
 *
 * ## Architektura
 *
 * Sterowanie okularami idzie przez BLE (`LargeDataHandler.glassesControl`), a zdarzenia
 * z okularów wracają jako ramki notify (`addOutDeviceListener(100, ...)`).
 *
 * ### Mapa ramek notify (`loadData[6]`)
 * | Kod   | Znaczenie                          | Dane                                    |
 * |-------|------------------------------------|-----------------------------------------|
 * | 0x02  | Zdjęcie gotowe (aplikacja lub przycisk) | `[9] == 2` → prośba o opis obrazu   |
 * | 0x03  | Przycisk okularów                   | `[7]` = numer; `1` = przycisk AI        |
 * | 0x04  | Postęp OTA                          | download/soc/nor                        |
 * | 0x05  | Bateria                             | `[7]` = %, `[8]` = 1 gdy ładowanie      |
 * | 0x08  | IP okularów (Wi-Fi Direct)          | `[7..10]` = IPv4                        |
 * | 0x09  | Błąd P2P                            | `[7] == 0xFF` częsty, nie zawsze fatalny|
 * | 0x0c  | Pauza / komunikat głosowy           | `loadData[7] == 1`                      |
 * | 0x0e  | Mało pamięci na okularach           | -                                       |
 *
 * ### Dwie ścieżki pobierania obrazu
 * 1. **Miniatura przez BLE** (`capturePhoto()`) - szybka, bez Wi-Fi, ~4 s + transfer.
 *    To jest domyślna ścieżka dla V.I.C.T.O.R. (pytanie → zdjęcie → AI → TTS).
 * 2. **Pełne pliki przez Wi-Fi Direct** (`downloadLatestPhoto()` / `downloadLatestVideo()`) -
 *    pełna rozdzielczość, wymaga trybu transferu i IP z ramki 0x08.
 */
class VictorManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val tag = TAG

    /**
     * Zapamiętany adres okularów przeżywa restart aplikacji - patrz [scheduleReconnect].
     * Leniwie, żeby konstruktor VictorManagera (wołany z VictorApplication.onCreate)
     * nie mógł wywrócić startu aplikacji, gdyby inicjalizacja preferencji zawiodła.
     */
    private val settings by lazy { pl.victor.app.data.SettingsRepository.getInstance(appContext) }

    /**
     * Dziennik diagnostyczny. Rozłączenia i przyciski muszą trafiać do TEGO
     * SAMEGO pliku co tura rozmowy - zgłoszenie "okulary się rozłączają"
     * rozstrzyga się właśnie tym, co działo się w sekundach wokół rozłączenia.
     */
    private val diag by lazy { pl.victor.app.VictorApplication.get().diag }

    private val largeDataHandler: LargeDataHandler = LargeDataHandler.getInstance()

    /** Wi-Fi Direct - potrzebny do pobierania wideo i plików w pełnej rozdzielczości. */
    private val wifiTransfer = GlassesWifiTransfer(context)

    /** Nagrania głosowe po BLE - osobny kanał vendor SDK, działa bez Wi-Fi. */
    private val recordings = GlassesRecordings()

    /** Własny scope - symulator odgrywa zdarzenia asynchronicznie. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            pl.victor.app.utils.loggingExceptionHandler(TAG)
    )

    /**
     * Symulator okularów. Niepusty tylko w trybie symulacji - wtedy przejmuje
     * cały transport, a reszta klasy działa na niezmienionym kodzie.
     */
    @Volatile
    private var simulator: GlassesSimulator? = null


    // === Stan ===

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _glassesIp = MutableStateFlow<String?>(null)
    val glassesIp: StateFlow<String?> = _glassesIp.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _buttonEvent = MutableStateFlow<ButtonEvent?>(null)
    val buttonEvent: StateFlow<ButtonEvent?> = _buttonEvent.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    /** Stan połączenia Wi-Fi Direct. */
    val wifiTransferState: StateFlow<TransferState> get() = wifiTransfer.state

    /** Ostatnia ramka notify w postaci szesnastkowej - dla ekranu diagnostycznego. */
    private val _lastNotifyFrame = MutableStateFlow<String?>(null)
    val lastNotifyFrame: StateFlow<String?> = _lastNotifyFrame.asStateFlow()

    /**
     * Ostatnie ramki notify wraz z odczytanym znaczeniem - najnowsze na górze.
     * Bufor jest ograniczony do [NOTIFY_LOG_SIZE], żeby nie puchł w nieskończoność.
     */
    private val _notifyLog = MutableStateFlow<List<NotifyLogEntry>>(emptyList())
    val notifyLog: StateFlow<List<NotifyLogEntry>> = _notifyLog.asStateFlow()

    private val _mediaCount = MutableStateFlow<MediaCount?>(null)
    val mediaCount: StateFlow<MediaCount?> = _mediaCount.asStateFlow()

    /** Czy działamy na symulatorze zamiast na sprzęcie. */
    private val _simulationEnabled = MutableStateFlow(false)
    val simulationEnabled: StateFlow<Boolean> = _simulationEnabled.asStateFlow()

    /** Ostatnia komenda wysłana do okularów - dla ekranu diagnostycznego. */
    private val _lastCommand = MutableStateFlow<String?>(null)
    val lastCommand: StateFlow<String?> = _lastCommand.asStateFlow()

    /** Ustawiane na `true` gdy okulary zgłoszą gotowe zdjęcie AI (ramka 0x02). */
    private val _photoReady = MutableStateFlow(false)
    val photoReady: StateFlow<Boolean> = _photoReady.asStateFlow()

    /**
     * Użytkownik zrobił zdjęcie DRUGIM przyciskiem okularów.
     *
     * Okulary mają dwa przyciski, ale tylko jeden z nich (AI) zgłaszał się
     * ramką 0x03. Drugi robi zdjęcie sam, w firmware, i melduje o tym ramką
     * 0x02 - dokładnie tą samą, na którą czeka [capturePhoto]. Dopóki nie
     * odróżnialiśmy zdjęcia zamówionego od zrobionego ręcznie, wciśnięcie tego
     * przycisku nie robiło w aplikacji NIC.
     *
     * W strumieniu leci `true`, gdy okulary proszą o opisanie zdjęcia
     * (bajt trybu = 2, tak jak u producenta), i `false` dla zwykłego zdjęcia.
     */
    private val _glassesPhotoTaken = MutableSharedFlow<Boolean>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val glassesPhotoTaken: SharedFlow<Boolean> = _glassesPhotoTaken.asSharedFlow()

    /**
     * Czy właśnie trwa zdjęcie zamówione przez aplikację.
     *
     * Bez tego znacznika ramka 0x02 wywołana przez [capturePhoto] wyglądałaby
     * identycznie jak wciśnięcie przycisku na okularach - i aplikacja pytałaby
     * model o to samo zdjęcie dwa razy.
     */
    @Volatile
    private var captureInProgress = false

    /**
     * Kiedy okulary ostatnio odpowiedziały NA JAKĄKOLWIEK komendę sterującą.
     *
     * ## Po co osobny licznik
     * Bo "aparat nie zadziałał" i "okulary nie przyjmują żadnych komend" to dwie
     * różne awarie, a z zewnątrz wyglądają identycznie: cisza. Wcześniej
     * próbowałem to rozstrzygnąć licznikiem plików, ale ten ustawia się tylko
     * przy odpowiedzi o konkretnym `dataType` - jego brak nie dowodził niczego
     * i komunikat "okulary nie odpowiadają" pojawiał się także wtedy, gdy
     * odpowiadały. To pole ustawia KAŻDA odpowiedź z [send], niezależnie od
     * treści, więc mierzy dokładnie to, co ma mierzyć: czy kanał odpowiedzi
     * w ogóle żyje.
     */
    @Volatile
    private var lastCommandAckAtMs = 0L

    /** Czy okulary kiedykolwiek odpowiedziały na komendę w tej sesji. */
    val glassesAnswerCommands: Boolean get() = lastCommandAckAtMs > 0L

    /**
     * Czy powitanie po połączeniu zostało już wykonane dla TEGO połączenia.
     *
     * Vendor SDK sam rozgłasza `service_discovered` drugi raz, 2,5 s po
     * [armWriteChannel] (patrz `BleOperateManager$3`). Bez tego znacznika całe
     * powitanie - czas, informacje, głośność, bateria, openBT - leciałoby
     * dwa razy przy każdym połączeniu.
     */
    @Volatile
    private var greetingDone = false

    /** Czy vendor SDK przyjmuje w tej chwili zapisy (komendy) do okularów. */
    val writeChannelArmed: Boolean
        get() = simulator?.let { true }
            ?: runCatching { BleOperateManager.getInstance().isReady }.getOrDefault(false)

    /**
     * Odblokowuje wysyłanie CZEGOKOLWIEK do okularów.
     *
     * ## To była przyczyna "okulary nie reagują na komendy"
     * `BleOperateManager.execute()` w vendor SDK zaczyna się tak:
     *
     * ```java
     * if (request.writeRequest && !this.ready) return false;
     * ```
     *
     * Pole `ready` startuje jako `false`, a SDK ustawia je wyłącznie na `false`
     * (w `disconnect()` i w `isConnected()`, gdy połączenia nie ma). Na `true`
     * nie ustawia go NIGDY - to zadanie aplikacji. W dekompilacji Prism Pro
     * jedyne wywołanie `setReady(true)` siedzi w `BleCommonDataParse
     * .parseDeviceInfoData`, po odczycie charakterystyki wersji sprzętu
     * (`00002A27`), którą SDK czyta samo tuż po wykryciu usług.
     *
     * My tego nie robiliśmy, więc `ready` zostawało `false` na zawsze i KAŻDY
     * zapis - komenda zdjęcia, prośba o miniaturę, włączenie Wi-Fi, zakończenie
     * nasłuchu - był po cichu wyrzucany. `execute()` zwraca w tym miejscu
     * `false`, ale nikt tej wartości nie sprawdza, więc nie było ani błędu, ani
     * wpisu w dzienniku.
     *
     * Nasłuch (`EnableNotifyRequest`) i odczyty (`ReadRequest`) mają
     * `writeRequest == false`, więc przechodziły. Stąd dokładnie ten obraz
     * awarii, który zgłoszono: ramki notify przychodzą normalnie (bateria,
     * przycisk, "zdjęcie gotowe" - `BC 73 05 00 0C AA 02 00 21 02 00`), a nic,
     * o co aplikacja prosi, się nie dzieje. I stąd "okulary nie zgłosiły adresu
     * Wi-Fi": prośba o jego włączenie też jest zapisem.
     *
     * Przypisanie `ready = true` dzieje się w SDK synchronicznie, więc zaraz po
     * powrocie z tej metody komendy już idą.
     */
    private fun armWriteChannel(reason: String) {
        if (simulator != null) return
        val already = runCatching { BleOperateManager.getInstance().isReady }.getOrDefault(false)
        if (already) return
        runCatching { BleOperateManager.getInstance().setReady(true) }
            .onSuccess {
                Log.i(tag, "Kanał zapisu do okularów uzbrojony ($reason)")
                _notifyLog.update { log ->
                    (
                        listOf(
                            NotifyLogEntry(
                                System.currentTimeMillis(),
                                "(kanał zapisu)",
                                "Komendy do okularów odblokowane ($reason)"
                            )
                        ) + log
                        ).take(NOTIFY_LOG_SIZE)
                }
            }
            .onFailure { Log.w(tag, "setReady nie powiodło się", it) }
    }

    /** Czy okulary zgłaszają włączone własne wykrywanie komendy głosowej. */
    private val _glassesWakeWordEnabled = MutableStateFlow(false)
    val glassesWakeWordEnabled: StateFlow<Boolean> = _glassesWakeWordEnabled.asStateFlow()

    /**
     * Okulary proszą o rozmowę: użytkownik powiedział słowo wybudzenia albo
     * przytrzymał zausznik. `true` w strumieniu oznacza tryb tekstu na żywo.
     *
     * To brakujące ogniwo wake worda: `setGlassesWakeWord(true)` włącza detekcję
     * PO STRONIE OKULARÓW, ale bez nasłuchu tego zdarzenia aplikacja nigdy się
     * nie dowiadywała, że coś wykryły.
     */
    private val _aiSessionRequest = MutableSharedFlow<Boolean>(replay = 0, extraBufferCapacity = 4)
    val aiSessionRequest: SharedFlow<Boolean> = _aiSessionRequest.asSharedFlow()

    /** Użytkownik uciszył V.I.C.T.O.R.-a dotknięciem zauszników. */
    private val _speechInterrupted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    val speechInterrupted: SharedFlow<Unit> = _speechInterrupted.asSharedFlow()

    /** Ostatnia głośność zgłoszona przez okulary (-1 = nieznana). */
    private val _glassesVolume = MutableStateFlow(-1)
    val glassesVolume: StateFlow<Int> = _glassesVolume.asStateFlow()

    private var initialized = false
    private var scanning = false
    private var notifyListenerRegistered = false

    /**
     * Zegar bezpieczeństwa dla [connect] - WYŁĄCZNIE na wypadek scenariusza, którego nie
     * pokrywa BLE_NO_CALLBACK (patrz [bleStateReceiver]). Sam SDK ma własny, 40-sekundowy
     * mechanizm wykrywania braku odpowiedzi systemu na GATT (zweryfikowane w smali SDK:
     * BleBaseControl - Handler.postDelayed(mTimeoutRunnable, 40000) -> BleOperateManager
     * .bleNoCallback() -> broadcast BLE_NO_CALLBACK) - to jest normalna, oczekiwana droga
     * zgłoszenia porażki połączenia, którą [bleStateReceiver] teraz obsługuje wprost. Ten
     * job istniał, zanim to odkryliśmy (patrz historia commitów) i zostaje jako druga
     * linia obrony, gdyby jednak coś ominęło nawet ten mechanizm producenta.
     */
    private var connectTimeoutJob: Job? = null

    /** Ostatni adres, z którym łączyliśmy się świadomie - baza do auto-reconnectu. */
    private var lastConnectedAddress: String? = null

    /** Rozłączenie zlecone przez użytkownika NIE ma być odwracane przez auto-reconnect. */
    @Volatile
    private var userInitiatedDisconnect = false

    private var reconnectJob: Job? = null

    // === Inicjalizacja ===

    /**
     * Inicjalizuje vendor SDK i rejestruje nasłuch zdarzeń.
     * Bezpieczne do wielokrotnego wywołania - kolejne wywołania są ignorowane.
     */
    @Synchronized
    fun initialize() {
        if (initialized) {
            Log.d(tag, "initialize() pominięte - już zainicjalizowane")
            return
        }

        simulator?.let {
            Log.i(tag, "Inicjalizacja w trybie symulacji - vendor SDK nie jest ruszane")
            _connectionState.value = ConnectionState.DISCONNECTED
            initialized = true
            return
        }

        Log.i(tag, "Inicjalizacja vendor SDK")

        val application = appContext as? Application
        if (application == null) {
            Log.e(tag, "appContext nie jest Application - SDK nie zostanie zainicjalizowane")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        try {
            // KOLEJNOŚĆ MA ZNACZENIE. Bezparametrowe getInstance() w tym SDK NIE tworzy
            // singletona - to dosłownie `getstatic; areturn` (zweryfikowane javap na
            // naszym AAR), a konstruktor BleOperateManager(Context) jest prywatny.
            // Jedyną fabryką jest getInstance(Application). Bez tej linijki wszystkie
            // późniejsze BleOperateManager.getInstance() zwracały null, a że w Kotlinie
            // to typ platformowy, kompilator tego nie wyłapywał - dopiero runtime rzucał
            // NPE. Skutek: skan działał (BleScannerHelper to osobny, poprawnie leniwy
            // singleton), ale connectDirectly() nigdy nie ruszało, a stan i tak był już
            // ERROR po nieudanej inicjalizacji, więc UI nie reagowało na "Połącz" w ogóle.
            // Tak samo robi to aplikacja producenta (Prism Pro, GlassApplication).
            BleOperateManager.getInstance(application)
            BleOperateManager.getInstance().setApplication(application)
            BleOperateManager.getInstance().init()
            // UWAGA: largeDataHandler.initEnable() celowo NIE tutaj - patrz onGlassesReady().
            registerBleBroadcastReceiver()
            registerDeviceNotifyListener()
            // Odtwórz realny stan - SDK mogło być już połączone (np. po obrocie ekranu).
            val alreadyConnected = BleOperateManager.getInstance().isConnected
            _connectionState.value =
                if (alreadyConnected) ConnectionState.READY else ConnectionState.DISCONNECTED
            // Gdy okulary są już połączone, BLE_SERVICE_DISCOVERED już nie przyjdzie -
            // kanał danych trzeba włączyć tutaj, inaczej zostanie wyłączony na zawsze.
            if (alreadyConnected) onGlassesReady()
            initialized = true
            Log.i(tag, "SDK zainicjalizowane, stan=${_connectionState.value}")
        } catch (t: Throwable) {
            // Throwable, nie Exception. Brakująca zależność vendor SDK objawia się jako
            // NoClassDefFoundError, czyli Error - wcześniejszy catch (e: Exception) go nie
            // łapał, wyjątek szedł z VictorApplication.onCreate() i zabijał CAŁĄ aplikację
            // przy starcie. Okulary to funkcja opcjonalna: gdy ich warstwa nie wstanie,
            // reszta appki (ustawienia, AI, historia) ma działać dalej.
            Log.e(tag, "Inicjalizacja SDK nie powiodła się - okulary będą niedostępne", t)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    /**
     * Rejestruje odbiornik broadcastów BLE z vendor SDK - to jest realne źródło stanu połączenia.
     *
     * MUSI iść przez LocalBroadcastManager, nie przez Context.registerReceiver. SDK wysyła
     * wszystkie swoje ramki prywatną metodą mySendBroadcast(), która woła
     * LocalBroadcastManager.getInstance(ctx).sendBroadcast() (zweryfikowane javap na naszym
     * AAR). To osobna, wewnątrzprocesowa szyna - globalnie zarejestrowany odbiornik NIE
     * dostaje z niej nic. Wcześniej rejestrowaliśmy się globalnie, więc żadna ramka stanu
     * (GATT_CONNECTED, SERVICE_DISCOVERED, NO_CALLBACK...) do nas nie docierała i ekran
     * parowania nie miał prawa wyjść poza "Łączenie...".
     */
    private fun registerBleBroadcastReceiver() {
        LocalBroadcastManager.getInstance(appContext)
            .registerReceiver(bleStateReceiver, BleAction.getIntentFilter())
    }

    private val bleStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BleAction.BLE_START_CONNECT -> {
                    Log.d(tag, "BLE: rozpoczęto łączenie")
                    _connectionState.value = ConnectionState.CONNECTING
                }
                BleAction.BLE_GATT_CONNECTED -> {
                    Log.i(tag, "BLE: GATT połączony")
                    _connectionState.value = ConnectionState.CONNECTED
                }
                BleAction.BLE_SERVICE_DISCOVERED -> {
                    Log.i(tag, "BLE: usługi wykryte - okulary gotowe")
                    runCatching {
                        diag.event(
                            pl.victor.app.diagnostics.DiagFormat.Phase.BLE, "POŁĄCZONO"
                        )
                    }
                    connectTimeoutJob?.cancel()
                    _connectionState.value = ConnectionState.READY
                    onGlassesReady()
                }
                BleAction.BLE_GATT_DISCONNECTED -> {
                    Log.i(tag, "BLE: rozłączono")
                    runCatching {
                        diag.event(
                            pl.victor.app.diagnostics.DiagFormat.Phase.BLE,
                            "ROZŁĄCZONO",
                            mapOf("świadome" to userInitiatedDisconnect)
                        )
                    }
                    greetingDone = false
                    connectTimeoutJob?.cancel()
                    // Subskrypcja mikrofonu padła razem z GATT - patrz [onGattDropped].
                    onGattDropped()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _glassesIp.value = null
                    // Wykrywanie frazy żyje w okularach, więc bez połączenia nie
                    // działa. Przełącznik w ustawieniach ma pokazywać prawdę, a nie
                    // ostatni znany stan sprzed rozłączenia.
                    _glassesWakeWordEnabled.value = false
                    // Okulary potrafią się rozłączyć same (zasięg, uśpienie, chwilowa
                    // utrata łączności). Bez tego użytkownik musiał za każdym razem
                    // wchodzić w parowanie ręcznie.
                    if (!userInitiatedDisconnect) scheduleReconnect()
                }
                BleAction.BLE_CHARACTERISTIC_READ -> {
                    // Tak samo jak Prism Pro: odczyt wersji sprzętu jest sygnałem,
                    // że GATT jest w pełni gotowy - i dopiero on odblokowuje zapisy.
                    val uuid = intent.getStringExtra(BleAction.EXTRA_CHARACTER_UUID)
                    if (uuid != null &&
                        uuid.equals(Constants.CHAR_HW_REVISION.toString(), ignoreCase = true)
                    ) {
                        armWriteChannel("odczyt wersji sprzętu")
                    }
                }
                BleAction.BLE_NOT_SUPPORTED,
                BleAction.BLE_NO_BT_ADAPTER,
                BleAction.BLE_STATUS_ABNORMAL -> {
                    Log.e(tag, "BLE: błąd adaptera (${intent.action})")
                    _connectionState.value = ConnectionState.ERROR
                }
                BleAction.BLE_NO_CALLBACK -> {
                    // SDK wysyła to samodzielnie ~40s po starcie connect(), gdy Android
                    // w ogóle nie oddał callbacku GATT (np. okulary poza zasięgiem, już
                    // połączone z innym telefonem, albo wymagają restartu). Bez tej gałęzi
                    // ta ramka była po cichu ignorowana - stan zostawał w CONNECTING na
                    // zawsze, a jedynym ratunkiem był nasz własny [connectTimeoutJob].
                    Log.w(tag, "BLE: SDK zgłosił brak odpowiedzi systemu na GATT (BLE_NO_CALLBACK)")
                    connectTimeoutJob?.cancel()
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        }
    }

    /**
     * Rejestruje nasłuch ramek notify z okularów (bateria, przycisk, IP, gotowe zdjęcie).
     */
    private fun registerDeviceNotifyListener() {
        if (notifyListenerRegistered) return
        largeDataHandler.addOutDeviceListener(GlassesProtocol.DEVICE_NOTIFY_KEY, deviceNotifyListener)
        notifyListenerRegistered = true
        Log.d(tag, "Zarejestrowano nasłuch notify (klucz=${GlassesProtocol.DEVICE_NOTIFY_KEY})")
    }

    private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            handleNotify(response.loadData)
        }
    }

    /**
     * Jedyne miejsce, w którym ramka notify zamienia się w stan aplikacji.
     *
     * Wchodzą tędy zarówno ramki ze sprzętu, jak i te z [GlassesSimulator] -
     * dzięki temu symulacja przechodzi przez ten sam kod, co prawdziwe okulary.
     */
    private fun handleNotify(load: ByteArray?) {
        val hex = GlassesProtocol.formatFrame(load)
        _lastNotifyFrame.value = hex

        val decoded = GlassesProtocol.decodeNotify(load)
        _notifyLog.update { log ->
            (listOf(NotifyLogEntry(System.currentTimeMillis(), hex, describe(decoded))) + log)
                .take(NOTIFY_LOG_SIZE)
        }

        when (val event = decoded) {
            is NotifyEvent.PhotoReady -> {
                Log.i(tag, "Notify: zdjęcie gotowe (tryb=${event.mode}, opisz=${event.aiVision})")
                runCatching {
                    diag.event(
                        pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE,
                        "okulary zgłosiły gotowe zdjęcie"
                    )
                }
                _photoReady.value = true
                // Zdjęcia, o które sami nie prosiliśmy, robi użytkownik
                // przyciskiem na okularach. To jedyna droga, którą drugi
                // przycisk może cokolwiek uruchomić w aplikacji.
                if (!captureInProgress) _glassesPhotoTaken.tryEmit(event.aiVision)
            }
            is NotifyEvent.ButtonPressed -> {
                runCatching {
                    diag.event(
                        pl.victor.app.diagnostics.DiagFormat.Phase.PRZYCISK,
                        "wciśnięto",
                        mapOf("numer" to event.button)
                    )
                }
                if (event.button == GlassesProtocol.AI_BUTTON) {
                    Log.i(tag, "Notify: wciśnięto przycisk AI")
                    _buttonEvent.value = ButtonEvent.ShortClick
                } else {
                    // Świadomie NIE zgadujemy, co robi. Numer ląduje w
                    // dzienniku diagnostycznym - to jedyny sposób, żeby
                    // odczytać go z prawdziwych okularów zamiast wróżyć.
                    Log.i(tag, "Notify: wciśnięto przycisk nr ${event.button} (nieobsługiwany)")
                }
            }
            is NotifyEvent.Battery -> {
                Log.i(tag, "Notify: bateria ${event.level}%, ładowanie=${event.charging}")
                _batteryLevel.value = event.level
                _isCharging.value = event.charging
            }
            is NotifyEvent.GlassesIp -> {
                Log.i(tag, "Notify: IP okularów = ${event.ip}")
                _glassesIp.value = event.ip
            }
            is NotifyEvent.P2pError -> {
                // Kod 255 okulary zgłaszają rutynowo - nie panikujemy.
                Log.w(tag, "Notify: błąd P2P (kod=${event.code})")
            }
            is NotifyEvent.OtaProgress -> {
                Log.d(tag, "Notify: OTA ${event.download}/${event.soc}/${event.nor}")
            }
            is NotifyEvent.LowMemory -> Log.w(tag, "Notify: mało pamięci na okularach")
            is NotifyEvent.SpeechInterrupted -> {
                Log.i(tag, "Notify: użytkownik uciszył V.I.C.T.O.R.-a")
                _speechInterrupted.tryEmit(Unit)
            }
            is NotifyEvent.Unbound -> Log.w(tag, "Notify: okulary odpięły aplikację")
            is NotifyEvent.IdentificationStopped ->
                Log.d(tag, "Notify: okulary przerwały rozpoznawanie obrazu")
            is NotifyEvent.VolumeChanged -> {
                Log.i(tag, "Notify: głośność na zausznikach = ${event.level}")
                _glassesVolume.value = event.level
            }
            is NotifyEvent.CameraAngle ->
                Log.d(tag, "Notify: kąt kamery ${event.angle}")
            is NotifyEvent.AiSessionRequested -> {
                Log.i(tag, "Notify: okulary proszą o rozmowę (tekst na żywo=${event.realtimeText})")
                _aiSessionRequest.tryEmit(event.realtimeText)
            }
            is NotifyEvent.Unknown ->
                Log.d(tag, "Notify: nieobsługiwany typ 0x${event.type.toString(16)}")
            is NotifyEvent.Malformed ->
                Log.w(tag, "Notify: ramka za krótka (${event.size} B)")
        }
    }

    /**
     * Wywoływane, gdy okulary osiągną stan READY (usługi GATT wykryte).
     *
     * `initEnable()` MUSI iść tutaj, a nie przy starcie aplikacji. To ono włącza kanał
     * dużych danych (miniatury zdjęć, odpowiedzi na komendy) na KONKRETNYM, świeżo
     * połączonym urządzeniu - wołane bez połączenia nie ma czego włączyć i cicho nic nie
     * robi. Wcześniej wołaliśmy je raz, w initialize(), zanim jakiekolwiek okulary były
     * połączone: BLE łączyło się poprawnie, przycisk na okularach działał (to inny kanał,
     * addOutDeviceListener), ale transfer miniatur i odpowiedzi na komendy leciały w
     * timeouty - stąd "przechwytywanie 1/5... 5/5" i potem "nie udało się pobrać zdjęcia".
     * Aplikacja producenta (Prism Pro) robi dokładnie to samo w onServiceDiscovered.
     *
     * Idzie przez [scope], bo producent też trzyma to poza wątkiem głównym, a onReceive()
     * broadcastu wykonuje się na main thread.
     */
    private fun onGlassesReady() {
        reconnectJob?.cancel()
        scope.launch {
            // NAJPIERW to, bo bez tego wszystko poniżej jest zapisem, a zapisy
            // przy `ready == false` vendor SDK wyrzuca po cichu - patrz
            // [armWriteChannel]. Kolejność nie jest kosmetyczna: przez nią całe
            // powitanie producenta, które wysyłaliśmy, nie docierało nigdzie.
            armWriteChannel("usługi GATT wykryte")

            runCatching { largeDataHandler.initEnable() }
                .onFailure { Log.w(tag, "initEnable nie powiodło się", it) }

            // Gdy rozłączenie zastało turę w trakcie, odbiorca strumienia wciąż
            // czeka - subskrypcję trzeba mu odtworzyć, bo sam się o nią nie
            // upomni. Przy pustej liście to nic nie robi.
            rearmMicStreamAfterReconnect()

            // SDK sam rozgłasza `service_discovered` jeszcze raz, 2,5 s po
            // uzbrojeniu kanału zapisu. Powitanie ma iść raz na połączenie.
            if (greetingDone) return@launch
            greetingDone = true

            // Uzbrój mechanizm auto-reconnectu producenta na TEN adres. connectWithScan()
            // w SDK sprawdza pole reConnectMac i bez niego od razu wychodzi.
            lastConnectedAddress?.let { address ->
                runCatching { BleOperateManager.getInstance().setReConnectMac(address) }
                    .onFailure { Log.w(tag, "setReConnectMac nie powiodło się", it) }
            }

            // === Powitanie, którego nie robiliśmy ===
            //
            // Aplikacja producenta (Prism Pro, DeviceCmdInit) po połączeniu wysyła
            // CAŁĄ serię zapytań, zanim czegokolwiek zażąda: czas, informacje o
            // urządzeniu, obsługiwane funkcje, głośność, bateria i liczba plików.
            // My robiliśmy z tego jedno - baterię.
            //
            // Objaw, który to tłumaczy: okulary reagowały na wszystko BIERNE
            // (ramki notify, przycisk, wybudzenie), ale komenda zdjęcia ginęła
            // bez śladu - ani ramki 0x02, ani błędu. Zgłoszone jako "przechwytuję
            // obraz 1/1", a potem "okulary nie potwierdziły zrobienia zdjęcia".
            //
            // Kolejność jest ta sama co u producenta. Każde wywołanie jest
            // ODCZYTEM albo synchronizacją - żadne nic nie psuje, nawet gdy dany
            // egzemplarz go nie potrzebuje; brak choćby jednego może za to
            // zostawiać okulary w stanie, w którym nie przyjmują sterowania.
            runCatching { largeDataHandler.syncTime(null) }
                .onFailure { Log.w(tag, "syncTime nie powiodło się", it) }
            runCatching { largeDataHandler.syncDeviceInfo(null) }
                .onFailure { Log.w(tag, "syncDeviceInfo nie powiodło się", it) }
            runCatching { largeDataHandler.wearFunctionSupport(null) }
                .onFailure { Log.w(tag, "wearFunctionSupport nie powiodło się", it) }
            runCatching { largeDataHandler.getVolumeControl(null) }
                .onFailure { Log.w(tag, "getVolumeControl nie powiodło się", it) }

            runCatching { largeDataHandler.syncBattery() }
                .onFailure { Log.w(tag, "syncBattery nie powiodło się", it) }

            // Liczba plików na okularach - u producenta ostatni krok powitania.
            // Przy okazji jest to pierwszy DOWÓD, że okulary przyjmują komendy:
            // odpowiedź wraca jako dataType 4 i ustawia licznik w diagnostyce.
            requestMediaCount { _, _, _ -> }

            // Głośnik i mikrofon okularów działają po KLASYCZNYM Bluetoothie (układ
            // audio JieLi), osobno od kanału sterowania BLE. openBT() każe okularom
            // włączyć tę część - dopiero wtedy telefon może je zobaczyć i sparować jako
            // zestaw słuchawkowy, a wtedy TTS i mikrofon idą przez okulary bez żadnych
            // dodatkowych sztuczek w kodzie. Tak samo robi to aplikacja producenta.
            runCatching { largeDataHandler.openBT() }
                .onFailure { Log.w(tag, "openBT nie powiodło się", it) }
            runCatching { largeDataHandler.speakSoundSwitch(true) }
                .onFailure { Log.w(tag, "speakSoundSwitch nie powiodło się", it) }

            // Wykrywanie komendy głosowej po stronie okularów - nie wymaga Picovoice.
            // Respektujemy wybór użytkownika, a nie włączamy na sztywno.
            setGlassesWakeWord(settings.isGlassesWakeWordEnabled())
        }
    }

    /**
     * Próbuje wznowić połączenie po nieoczekiwanym rozłączeniu.
     *
     * Używa pary setReConnectMac + connectWithScan z vendor SDK (a nie connectDirectly):
     * connectWithScan włącza wewnętrzny tryb ponawiania SDK i szuka urządzenia skanem,
     * co działa też wtedy, gdy okulary chwilowo zniknęły z zasięgu. Tak robi to
     * aplikacja producenta w swojej klasie DeviceReconnect.
     */
    private fun scheduleReconnect() {
        val address = lastConnectedAddress ?: settings.getLastGlassesAddress() ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // PRÓBY NIE MAJĄ KOŃCA - i to jest cała zmiana.
            //
            // Wcześniej było ich dziesięć co sześć sekund, czyli minuta. Minuta
            // wystarcza na wyjście za róg, ale nie na odłożenie okularów na
            // biurko: po niej aplikacja poddawała się na dobre i trzeba było
            // łączyć się ręcznie. Zgłoszone dwa razy jako "rozłączanie okularów
            // nie naprawiło się".
            //
            // Zamiast liczyć próby, rozrzedzamy je - patrz [ReconnectBackoff].
            // Kończy je wyłącznie powrót okularów albo świadome rozłączenie.
            var attempt = 0
            while (true) {
                delay(ReconnectBackoff.delayForAttempt(attempt))
                if (userInitiatedDisconnect || isConnected()) return@launch
                // Wyłączony Bluetooth: nie ma czego szukać, a skan i tak by nie
                // ruszył. Pętla ZOSTAJE - okulary mają wrócić same w chwili, gdy
                // użytkownik włączy Bluetooth z powrotem, bez wchodzenia w
                // aplikację.
                //
                // Licznik wraca do zera, więc czekanie na powrót radia jest
                // krótkie i stałe (sam odczyt stanu adaptera, bez skanowania), a
                // po jego włączeniu pierwsze podejścia znów są gęste - tak jakby
                // rozłączenie właśnie nastąpiło.
                if (!isBluetoothOn()) {
                    if (attempt != 0) Log.i(tag, "Auto-reconnect: Bluetooth wyłączony - czekam")
                    attempt = 0
                    continue
                }
                if (ReconnectBackoff.shouldLog(attempt)) {
                    Log.i(tag, "Auto-reconnect: próba ${attempt + 1} ($address)")
                }
                // Stan CONNECTING tylko przez pierwsze, gęste próby. Później
                // rozłączone okulary mają wyglądać na rozłączone - kręcący się
                // w nieskończoność wskaźnik "łączę" to nieprawda o tym, co się
                // dzieje, i zasłania przycisk ręcznego połączenia.
                //
                // Nigdy nie nadpisujemy stanu READY. Okulary mogą się połączyć
                // dokładnie między sprawdzeniem wyżej a tym przypisaniem -
                // onGlassesReady() kasuje wtedy tę korutynę, ale kasowanie działa
                // dopiero na najbliższym zawieszeniu, a tutaj żadnego nie ma.
                // Bez tego warunku pętla cofnęłaby świeżo połączone okulary do
                // "rozłączone" i użytkownik zobaczyłby to na ekranie.
                if (_connectionState.value != ConnectionState.READY) {
                    _connectionState.value = if (attempt < ReconnectBackoff.FAST_ATTEMPTS) {
                        ConnectionState.CONNECTING
                    } else {
                        ConnectionState.DISCONNECTED
                    }
                }
                runCatching {
                    BleOperateManager.getInstance().setReConnectMac(address)
                    BleOperateManager.getInstance().connectWithScan(address)
                }.onFailure { Log.w(tag, "Auto-reconnect nie wystartował", it) }
                attempt++
            }
        }
    }

    // === Strumień audio z mikrofonu okularów (BLE, nie Bluetooth klasyczny) ===

    private val _micStreamStats = MutableStateFlow(GlassesMicStats())

    /** Statystyki strumienia z mikrofonu okularów - patrz [addMicStreamListener]. */
    val micStreamStats: StateFlow<GlassesMicStats> = _micStreamStats.asStateFlow()

    @Volatile
    private var micStreamActive = false

    /** Odbiorcy pakietów - patrz [addMicStreamListener]. */
    private val micStreamListeners = mutableListOf<(ByteArray) -> Unit>()

    /**
     * Dopisuje odbiorcę pakietów audio z mikrofonu okularów.
     * Pierwszy odbiorca uruchamia subskrypcję w SDK.
     *
     * ## Co to właściwie jest
     * Aplikacja producenta (Prism Pro) NIE bierze dźwięku z mikrofonu okularów
     * przez profil zestawu słuchawkowego. Bierze go po BLE: `initPackageNotify`
     * rejestruje odbiór pakietów `AiChatResponse`, których `getSubData()` to
     * strumień **Opus**, dekodowany u producenta biblioteką JieLi
     * (`com.jieli.jl_audio_decode.opus.OpusManager`) i podawany prosto do
     * rozpoznawania mowy. U nas dekoduje go
     * [pl.victor.app.audio.GlassesVoiceCapture].
     *
     * Ścieżka przez klasyczny Bluetooth (SCO/HFP, patrz
     * [pl.victor.app.audio.BluetoothAudioRouter]) działa niezależnie i pozostaje
     * podstawowa - jeśli okulary wystawiają się jako zestaw słuchawkowy,
     * mikrofon i głośnik działają bez żadnego dekodowania.
     *
     * ## Dlaczego odbiorców może być kilku
     * Bo naprawdę bywają dwaj naraz. Pomiar w diagnostyce każe użytkownikowi
     * WYBUDZIĆ okulary w trakcie - a wybudzenie uruchamia turę rozmowy, która
     * też chce ten strumień. Przy jednym odbiorcy drugi zgłaszający się
     * dostawał ciszę (subskrypcja już była), a jego zakończenie zdejmowało
     * subskrypcję pierwszemu. Czyli instrukcja z ekranu psuła własny pomiar.
     */
    fun addMicStreamListener(listener: (ByteArray) -> Unit) {
        if (simulator != null) return
        synchronized(micStreamListeners) {
            micStreamListeners.add(listener)
            if (micStreamActive) return
            if (!armMicNotify()) micStreamListeners.remove(listener)
        }
    }

    /**
     * Zamawia w SDK odbiór pakietów mikrofonu.
     *
     * Wołane wyłącznie pod blokadą [micStreamListeners].
     *
     * @return czy subskrypcja doszła do skutku
     */
    private fun armMicNotify(): Boolean = runCatching {
        largeDataHandler.initPackageNotify { _, rsp ->
            val payload = runCatching { rsp?.subData }.getOrNull()
            if (payload != null && payload.isNotEmpty()) onMicPacket(payload)
        }
        micStreamActive = true
        Log.i(tag, "Nasłuch strumienia audio z okularów włączony")
    }.onFailure { Log.w(tag, "initPackageNotify nie powiodło się", it) }.isSuccess

    /**
     * Subskrypcja pakietów mikrofonu ginie razem z połączeniem GATT.
     *
     * Bez wyzerowania flagi [micStreamActive] rozłączenie zostawiało ją na
     * `true`, więc po samoczynnym powrocie okularów [addMicStreamListener]
     * wychodził od razu i NIGDY nie zamawiał subskrypcji ponownie: połączenie
     * wyglądało na sprawne, a mikrofon milczał do końca życia procesu. Zdarza
     * się to przy każdym wyjściu z zasięgu, czyli w normalnym używaniu.
     *
     * Gdy rozłączenie zastało turę w trakcie, odbiorca jest nadal zapisany -
     * wtedy odtwarzamy mu subskrypcję od razu po powrocie, bo sam się o nią
     * nie upomni.
     */
    private fun onGattDropped() {
        synchronized(micStreamListeners) { micStreamActive = false }
    }

    private fun rearmMicStreamAfterReconnect() {
        synchronized(micStreamListeners) {
            if (micStreamListeners.isNotEmpty() && !micStreamActive) armMicNotify()
        }
    }

    /**
     * Rozdaje pakiet licznikom i wszystkim odbiorcom.
     *
     * Wyjątek jednego odbiorcy nie może uciszyć pozostałych - stąd runCatching
     * wokół każdego wywołania z osobna.
     */
    private fun onMicPacket(payload: ByteArray) {
        _micStreamStats.update { stats ->
            stats.copy(
                packets = stats.packets + 1,
                bytes = stats.bytes + payload.size,
                lastPacketAtMs = System.currentTimeMillis(),
                lastPacketSize = payload.size
            )
        }
        val listeners = synchronized(micStreamListeners) { micStreamListeners.toList() }
        listeners.forEach { listener ->
            runCatching { listener(payload) }
                .onFailure { Log.w(tag, "Odbiorca strumienia rzucił wyjątkiem", it) }
        }
    }

    /**
     * Usuwa odbiorcę. Subskrypcja w SDK znika dopiero z ostatnim - inaczej
     * koniec jednej tury uciszałby trwający pomiar diagnostyczny.
     */
    fun removeMicStreamListener(listener: (ByteArray) -> Unit) {
        synchronized(micStreamListeners) {
            micStreamListeners.remove(listener)
            if (micStreamListeners.isNotEmpty()) return
            unsubscribeMicStream()
        }
    }

    /** Zdejmuje WSZYSTKICH odbiorców - do sprzątania przy rozłączeniu. */
    fun stopGlassesMicStream() {
        synchronized(micStreamListeners) {
            micStreamListeners.clear()
            unsubscribeMicStream()
        }
    }

    /** Wołane wyłącznie pod blokadą [micStreamListeners]. */
    private fun unsubscribeMicStream() {
        if (!micStreamActive) return
        micStreamActive = false
        runCatching { largeDataHandler.removeGptNotify() }
            .onFailure { Log.w(tag, "removeGptNotify nie powiodło się", it) }
        Log.i(tag, "Nasłuch strumienia audio z okularów wyłączony")
    }

    /** Zeruje liczniki strumienia - do powtórzenia pomiaru w diagnostyce. */
    fun resetMicStreamStats() {
        _micStreamStats.value = GlassesMicStats()
    }

    /**
     * Steruje dźwiękiem odtwarzanym przez okulary (`aiVoicePlay` w SDK).
     *
     * Kody w [GlassesProtocol] - odczytane z aplikacji producenta. Używamy tego
     * przy rozpoczynaniu rozmowy (zatrzymaj to, co leci) i przy niepowodzeniu
     * (komunikat błędu), dokładnie tak jak Prism Pro.
     */
    fun playGlassesTone(code: Int) {
        if (!isConnected()) return
        runCatching { largeDataHandler.aiVoicePlay(code, null) }
            .onFailure { Log.w(tag, "aiVoicePlay($code) nie powiodło się", it) }
    }

    /**
     * Każe okularom zakończyć nasłuch.
     *
     * ## Dlaczego to musi być osobna komenda
     * Zgłoszono to jako "okulary same nie kończą nasłuchu". Odsubskrybowanie
     * strumienia audio (`removeGptNotify`) tylko wyrzuca callback z mapy w SDK -
     * do okularów NIE IDZIE ŻADEN BAJT, więc one nadają dalej, aż same się
     * znudzą. Aplikacja producenta wysyła w tym miejscu `0x02 0x01 0x0B`:
     * w chwili rozpoznania wypowiedzi, po swoim limicie czasu i przy wyjściu
     * z trybu AI. My robimy dokładnie to samo.
     *
     * Bez [isConnected] po cichu nic nie robi - wołamy to również ze sprzątania
     * po anulowanej turze, gdzie okulary mogą już być odłączone.
     */
    fun stopGlassesListening() {
        if (!isConnected()) return
        Log.i(tag, "Kończę nasłuch po stronie okularów")
        send(GlassesProtocol.stopAiSession())
    }

    /**
     * Włącza albo wyłącza wykrywanie komendy głosowej PO STRONIE OKULARÓW.
     *
     * To alternatywa dla Picovoice na telefonie: okulary mają własny układ wykrywania
     * wybudzenia, a SDK wystawia go przez aiVoiceWake. Odpowiedź (stan włączenia) wraca
     * w callbacku i trafia do [glassesWakeWordEnabled].
     */
    fun setGlassesWakeWord(enabled: Boolean) {
        // Wybór musi przeżyć rozłączenie: po każdym połączeniu wysyłamy go do
        // okularów od nowa (patrz onGlassesReady), więc bez zapamiętania
        // wyłączenie wracałoby przy pierwszym auto-reconnect.
        settings.setGlassesWakeWordEnabled(enabled)

        if (simulator != null) {
            // W symulacji nie ma czego pytać - odzwierciedlamy stan wprost,
            // inaczej przełącznik w ustawieniach wyglądałby na zablokowany.
            _glassesWakeWordEnabled.value = enabled
            return
        }
        runCatching {
            // Pierwszy parametr to ZAPIS vs ODCZYT, nie "włącz". Wysyłanie tu
            // `enabled` sprawiało, że wyłączenie wake worda było w istocie
            // pytaniem o stan - okulary nasłuchiwały dalej, a przełącznik w
            // ustawieniach wracał do włączonego.
            largeDataHandler.aiVoiceWake(true, enabled) { _, rsp ->
                val open = runCatching { rsp?.isOpen == true }.getOrDefault(false)
                Log.i(tag, "Wake word okularów: żądano=$enabled, urządzenie zgłasza=$open")
                _glassesWakeWordEnabled.value = open
            }
        }.onFailure { Log.w(tag, "aiVoiceWake nie powiodło się", it) }
    }

    /** Opis zdarzenia po polsku - na ekran diagnostyczny. */
    private fun describe(event: NotifyEvent): String = when (event) {
        is NotifyEvent.PhotoReady -> when {
            event.aiVision -> "Zdjęcie gotowe - prośba o opis (tryb ${event.mode})"
            event.mode == GlassesProtocol.PHOTO_MODE_ABSENT -> "Zdjęcie gotowe (bez bajtu trybu)"
            else -> "Zdjęcie gotowe (tryb ${event.mode})"
        }
        is NotifyEvent.ButtonPressed ->
            if (event.button == GlassesProtocol.AI_BUTTON) {
                "Wciśnięto przycisk AI"
            } else {
                "Wciśnięto przycisk nr ${event.button}"
            }
        is NotifyEvent.Battery ->
            "Bateria ${event.level}%" + if (event.charging) " (ładowanie)" else ""
        is NotifyEvent.GlassesIp -> "IP okularów: ${event.ip}"
        is NotifyEvent.P2pError -> "Błąd P2P, kod ${event.code}"
        is NotifyEvent.OtaProgress ->
            "OTA: pobrano ${event.download}%, SoC ${event.soc}%, NOR ${event.nor}%"
        is NotifyEvent.LowMemory -> "Mało pamięci na okularach"
        is NotifyEvent.SpeechInterrupted -> "Użytkownik przerwał wypowiedź"
        is NotifyEvent.Unbound -> "Okulary odpięły aplikację"
        is NotifyEvent.IdentificationStopped -> "Przerwano rozpoznawanie obrazu"
        is NotifyEvent.VolumeChanged -> "Głośność: ${event.level}"
        is NotifyEvent.CameraAngle -> "Kąt kamery: ${event.angle}"
        is NotifyEvent.AiSessionRequested ->
            if (event.realtimeText) "Okulary: tekst na żywo" else "Okulary: rozmowa z AI"
        is NotifyEvent.Unknown -> "Nieobsługiwany typ 0x%02X".format(event.type)
        is NotifyEvent.Malformed -> "Ramka uszkodzona (${event.size} B)"
    }

    /** Czyści dziennik ramek. */
    fun clearNotifyLog() {
        _notifyLog.value = emptyList()
    }

    /** Kasuje ostatnie zdarzenie przycisku po jego obsłużeniu. */
    fun consumeButtonEvent() {
        _buttonEvent.value = null
    }

    // === Tryb symulacji ===

    /**
     * Włącza albo wyłącza symulowane okulary.
     *
     * W trybie symulacji podmieniany jest **wyłącznie transport**: komendy nie idą
     * przez BLE, a ramki notify składa [GlassesSimulator]. Wszystko powyżej -
     * dekodowanie, stan, UI, warstwa AI - działa na tym samym kodzie co ze sprzętem.
     *
     * Przełączenie rozłącza to, co jest aktualnie połączone, i wymaga ponownego
     * [initialize] - dlatego wywołuj to zanim aplikacja zacznie łączyć się z okularami.
     *
     * @param photoSource źródło zdjęć; na Androidzie [CanvasPhotoSource] rysuje
     *        czytelne sceny testowe, w testach wystarczy [EmbeddedPhotoSource]
     */
    @Synchronized
    fun setSimulationEnabled(
        enabled: Boolean,
        photoSource: SimulatedPhotoSource = CanvasPhotoSource(),
        timings: GlassesSimulator.Timings = GlassesSimulator.Timings(),
        faults: GlassesSimulator.Faults = GlassesSimulator.Faults()
    ) {
        if (enabled == _simulationEnabled.value) return

        // Posprzątaj po poprzednim trybie - inaczej zostaje wiszące połączenie.
        if (initialized) release()
        resetState()

        simulator = if (enabled) {
            GlassesSimulator(
                scope = scope,
                timings = timings,
                photos = photoSource,
                faults = faults,
                onNotify = ::handleNotify
            )
        } else {
            null
        }
        _simulationEnabled.value = enabled
        Log.i(tag, if (enabled) "Włączono symulowane okulary" else "Wyłączono symulację")
    }

    /**
     * Symulator, gdy tryb symulacji jest włączony.
     * Ekran diagnostyczny sięga po niego, żeby wstrzykiwać zdarzenia.
     */
    fun simulatorOrNull(): GlassesSimulator? = simulator

    private fun resetState() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _glassesIp.value = null
        _discoveredDevices.value = emptyList()
        _buttonEvent.value = null
        _batteryLevel.value = null
        _isCharging.value = false
        _lastNotifyFrame.value = null
        _lastCommand.value = null
        _notifyLog.value = emptyList()
        _mediaCount.value = null
        _photoReady.value = false
    }

    // === Skanowanie i parowanie ===

    /**
     * Rozpoczyna skanowanie BLE w poszukiwaniu okularów.
     * Wymaga uprawnień BLUETOOTH_SCAN (API 31+) oraz lokalizacji na starszych wersjach.
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        // WCZEŚNIEJ BYŁO TU CICHE `return` I TO BYŁ BŁĄD.
        //
        // `scanning` żyje w singletonie, który przeżywa ekran parowania. Kto wszedł
        // na parowanie, zeskanował i wyszedł BEZ łączenia (a stop był wołany tylko
        // przy łączeniu), zostawiał tę flagę na zawsze podniesioną. Od tego momentu
        // każde następne wejście na parowanie kończyło się natychmiastowym
        // "skan już trwa" - ekran stał na "Skanuję" i nie pokazywał niczego, aż do
        // ubicia procesu. Zgłoszone jako "aplikacja przestała wyszukiwać urządzenia".
        //
        // Restart zamiast pominięcia: użytkownik naciskający "Skanuj" ma prawo
        // oczekiwać skanu, a nie odziedziczonego stanu sprzed pięciu minut. Skan
        // po stronie systemu i tak mógł już dawno zostać wygaszony - flaga o tym
        // nie wie.
        if (scanning) {
            Log.i(tag, "startScan() przy trwającym skanie - restartuję")
            stopScan()
        }
        Log.i(tag, "Start skanowania BLE")
        _discoveredDevices.value = emptyList()
        _connectionState.value = ConnectionState.SCANNING
        scanning = true

        simulator?.let { sim ->
            scope.launch {
                delay(SIMULATED_SCAN_DELAY_MS)
                if (scanning) _discoveredDevices.value = listOf(sim.advertisedDevice())
            }
            return
        }

        try {
            BleScannerHelper.getInstance().reSetCallback()
            BleScannerHelper.getInstance().scanDevice(appContext, null, scanCallback)
        } catch (e: Exception) {
            scanning = false
            Log.e(tag, "Skanowanie nie wystartowało", e)
            _connectionState.value = ConnectionState.ERROR
            throw e
        }
    }

    /** Zatrzymuje skanowanie BLE. */
    fun stopScan() {
        if (!scanning) return
        Log.i(tag, "Stop skanowania BLE")
        scanning = false
        if (simulator == null) {
            runCatching { BleScannerHelper.getInstance().stopScan(appContext) }
                .onFailure { Log.w(tag, "stopScan nie powiodło się", it) }
        }
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private val scanCallback = object : ScanWrapperCallback {
        override fun onStart() {
            scanning = true
        }

        override fun onStop() {
            scanning = false
        }

        @SuppressLint("MissingPermission")
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            val bluetoothDevice = device ?: return
            val name = runCatching { bluetoothDevice.name }.getOrNull()
            upsertDevice(bluetoothDevice.address, name, rssi)
        }

        @SuppressLint("MissingPermission")
        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
            val bluetoothDevice = device ?: return
            val name = runCatching { scanRecord?.deviceName ?: bluetoothDevice.name }.getOrNull()
            upsertDevice(bluetoothDevice.address, name, null)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.e(tag, "Skanowanie nie powiodło się (kod=$errorCode)")
            _connectionState.value = ConnectionState.ERROR
        }

        override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>?) {
            // Nieużywane - wyniki przychodzą przez onLeScan/onParsedData.
        }
    }

    /**
     * Dodaje lub aktualizuje urządzenie na liście wyników skanu.
     * `rssi == null` zachowuje poprzednio znaną siłę sygnału.
     */
    private fun upsertDevice(address: String, name: String?, rssi: Int?) {
        _discoveredDevices.update { current ->
            val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
            val updated = DiscoveredDevice(
                address = address,
                name = name ?: existing?.name,
                rssi = rssi ?: existing?.rssi ?: 0
            )
            if (existing == null) {
                current + updated
            } else {
                current.map { if (it.address.equals(address, ignoreCase = true)) updated else it }
            }
        }
    }

    /**
     * Łączy się z okularami o podanym adresie MAC.
     * Stan połączenia śledzony jest przez broadcasty BLE (patrz [bleStateReceiver]).
     */
    fun connect(address: String) {
        Log.i(tag, "Łączenie z $address")
        stopScan()
        connectTimeoutJob?.cancel()
        reconnectJob?.cancel()
        userInitiatedDisconnect = false
        lastConnectedAddress = address
        settings.setLastGlassesAddress(address)
        _connectionState.value = ConnectionState.CONNECTING

        simulator?.let { sim ->
            sim.connect { state -> _connectionState.value = state }
            return
        }

        connectTimeoutJob = scope.launch {
            delay(BLE_CONNECT_TIMEOUT_MS)
            Log.w(tag, "Połączenie nie osiągnęło stanu READY w ${BLE_CONNECT_TIMEOUT_MS}ms - przerywam")
            runCatching { BleOperateManager.getInstance().disconnect() }
            _connectionState.value = ConnectionState.ERROR
        }

        try {
            BleOperateManager.getInstance().connectDirectly(address)
        } catch (e: Exception) {
            connectTimeoutJob?.cancel()
            Log.e(tag, "Łączenie nie powiodło się", e)
            _connectionState.value = ConnectionState.ERROR
            throw e
        }
    }

    /** Rozłącza okulary i czyści stan. */
    fun disconnect() {
        Log.i(tag, "Rozłączanie")
        // Świadome rozłączenie przez użytkownika - auto-reconnect ma tego NIE cofać.
        userInitiatedDisconnect = true
        greetingDone = false
        connectTimeoutJob?.cancel()
        reconnectJob?.cancel()
        val sim = simulator
        if (sim != null) {
            sim.disconnect()
        } else {
            runCatching { BleOperateManager.getInstance().disconnect() }
                .onFailure { Log.w(tag, "disconnect nie powiodło się", it) }
        }
        // Subskrypcja strumienia audio przeżyłaby rozłączenie i wisiała w SDK
        // do końca życia procesu - a po ponownym połączeniu doszłaby druga.
        stopGlassesMicStream()
        _connectionState.value = ConnectionState.DISCONNECTED
        _glassesIp.value = null
    }

    /** Czy okulary są realnie połączone (odpytuje vendor SDK). */
    /**
     * Czy radio Bluetooth jest włączone.
     *
     * Przy wyłączonym nie ma sensu ani skanować, ani się łączyć - a pętla
     * ponawiania biłaby w nie co minutę bez końca. Brak adaptera albo brak
     * uprawnienia liczymy jako "włączony": lepiej spróbować i dostać błąd niż
     * po cichu nie próbować w ogóle.
     */
    private fun isBluetoothOn(): Boolean = runCatching {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager
        manager?.adapter?.isEnabled ?: true
    }.getOrDefault(true)

    fun isConnected(): Boolean =
        simulator?.connected
            ?: runCatching { BleOperateManager.getInstance().isConnected }.getOrDefault(false)

    // === Komendy sterujące ===

    /**
     * Wysyła komendę sterującą do okularów.
     * `glassesControl` wymaga callbacku - vendor SDK rejestruje go pod kluczem
     * ACTION_GLASSES_CONTROL (65) i ma tylko jeden slot na odpowiedź.
     */
    private fun send(bytes: ByteArray, onResponse: ((Int) -> Unit)? = null) {
        simulator?.let { sim ->
            _lastCommand.value = sim.handleCommand(bytes)
            onResponse?.invoke(0)
            return
        }
        _lastCommand.value = GlassesProtocol.describeCommand(bytes)
        // Siatka bezpieczeństwa: gdyby okulary były połączone jeszcze przed startem
        // aplikacji, ramka `service_discovered` już nie przyjdzie i kanał zapisu
        // zostałby nieuzbrojony. Przy uzbrojonym to zwykły no-op.
        armWriteChannel("wysyłka komendy")
        try {
            largeDataHandler.glassesControl(bytes) { _, response ->
                lastCommandAckAtMs = System.currentTimeMillis()
                // Odpowiedzi na komendy idą inną akcją (0x41) niż zdarzenia
                // (0x73), więc w dzienniku ramek ich nie widać. Bez tego wpisu
                // nie da się odróżnić "okulary milczą" od "odpowiadają, ale
                // odmawiają" - a to dwie różne awarie.
                _notifyLog.update { log ->
                    (
                        listOf(
                            NotifyLogEntry(
                                System.currentTimeMillis(),
                                "(odpowiedź na komendę)",
                                "Okulary odpowiedziały na: " + GlassesProtocol.describeCommand(bytes)
                            )
                        ) + log
                        ).take(NOTIFY_LOG_SIZE)
                }
                val error = runCatching { response?.errorCode ?: 0 }.getOrDefault(0)
                if (error != 0) {
                    Log.w(tag, "glassesControl: błąd urządzenia (kod=$error)")
                }
                onResponse?.invoke(error)
            }
        } catch (e: Exception) {
            Log.e(tag, "glassesControl nie powiodło się", e)
        }
    }

    /**
     * Włącza tryb transferu plików (Wi-Fi Direct).
     * IP okularów przyjdzie asynchronicznie jako ramka notify 0x08.
     */
    fun enableTransferMode() {
        Log.d(tag, "Włączanie trybu transferu")
        send(GlassesProtocol.enableTransferMode())
    }

    /**
     * Włącza tryb transferu i CZEKA, aż okulary zgłoszą swój adres w grupie
     * Wi-Fi Direct (ramka notify 0x08).
     *
     * Bez tego każda operacja na plikach wymagała od użytkownika osobnego
     * kliknięcia "Tryb transferu", odczekania nieokreślonego czasu i dopiero
     * potem właściwego przycisku - a gdy trafił za wcześnie, dostawał surowy
     * komunikat "Brak IP okularów - najpierw enableTransferMode()", który
     * niczego nie tłumaczył.
     *
     * @return adres IP albo `null`, gdy okulary go nie zgłosiły w limicie czasu
     */
    suspend fun ensureTransferMode(timeoutMs: Long = TRANSFER_MODE_TIMEOUT_MS): String? {
        _glassesIp.value?.let { return it }
        if (simulator != null) return _glassesIp.value

        enableTransferMode()
        return withTimeoutOrNull(timeoutMs) {
            _glassesIp.first { !it.isNullOrBlank() }
        }
    }

    /** Resetuje połączenie P2P na okularach (gdy transfer się zawiesi). */
    fun resetP2p() {
        Log.d(tag, "Reset P2P")
        _glassesIp.value = null
        send(GlassesProtocol.resetP2p())
    }

    // === Komendy eksperymentalne (wyłącznie z gated panelu developerskiego) ===
    //
    // W przeciwieństwie do reszty tego pliku, te trzy funkcje NIE mają domyślnej
    // wartości dla `onResponse` - wołający musi jawnie obsłużyć odpowiedź (albo
    // jej brak), bo cały sens tego panelu to obserwacja skutków. Nigdy nie wołaj
    // tego z pętli/automatu - patrz pl.victor.app.livestream.LiveStreamLab.

    /** Wysyła nieznaną komendę 0x07 - patrz [GlassesProtocol.WORK_EXPERIMENTAL_07]. */
    fun sendExperimentalCommand07(onResponse: (errorCode: Int) -> Unit) {
        Log.w(tag, "EKSPERYMENT: wysyłam niepotwierdzoną komendę 0x07")
        send(GlassesProtocol.experimental07(), onResponse)
    }

    /** Wysyła nieznaną komendę 0x0D - patrz [GlassesProtocol.WORK_EXPERIMENTAL_0D]. */
    fun sendExperimentalCommand0D(onResponse: (errorCode: Int) -> Unit) {
        Log.w(tag, "EKSPERYMENT: wysyłam niepotwierdzoną komendę 0x0D")
        send(GlassesProtocol.experimental0D(), onResponse)
    }

    /** Restartuje okulary (potwierdzona komenda) - odzyskiwanie, gdy coś utknie. */
    fun restartDeviceExperimental(onResponse: (errorCode: Int) -> Unit) {
        Log.w(tag, "Restart okularów (komenda 0x0E)")
        send(GlassesProtocol.restartDevice(), onResponse)
    }

    /**
     * Łączy z grupą Wi-Fi Direct okularów BEZ wysyłania żadnej komendy sterującej
     * najpierw - w przeciwieństwie do [awaitGlassesIp], który zaczyna od
     * `enableTransferMode()`.
     *
     * To mirror pasywnego flow z CyanBridge (`LivePreviewManager.kt`): jeśli tryb 8
     * (live streaming) zostanie aktywowany zewnętrznie, okulary same rozgłoszą grupę
     * P2P (firmware ładuje moduł WLAN przed startem binarki streamującej) - nie trzeba
     * (i nie powinno się) najpierw włączać trybu transferu plików.
     *
     * @return `true` gdy telefon dołączył do grupy i dostał IP okularów (ramka 0x08)
     */
    suspend fun awaitGlassesIpPassive(): Boolean {
        if (simulator != null) {
            return withTimeoutOrNull(IP_TIMEOUT_MS) {
                while (_glassesIp.value == null) delay(IP_POLL_INTERVAL_MS)
                true
            } ?: false
        }
        if (!joinWifiDirectGroup()) return false
        val ip = withTimeoutOrNull(IP_TIMEOUT_MS) {
            while (_glassesIp.value == null) {
                delay(IP_POLL_INTERVAL_MS)
            }
            _glassesIp.value
        }
        if (ip == null) {
            Log.w(tag, "[Live Stream Lab] Nie doczekano się IP okularów (ramka notify 0x08)")
            return false
        }
        Log.i(tag, "[Live Stream Lab] Okulary osiągalne pod $ip (bez wysłanej komendy)")
        return true
    }

    /**
     * Robi zdjęcie okularami.
     * Uwaga: to tylko wyzwala migawkę - plik zostaje w pamięci okularów.
     * Po bajty zdjęcia sięgnij przez [capturePhoto] (miniatura po BLE)
     * albo [downloadLatestPhoto] (pełny plik po Wi-Fi Direct).
     */
    fun takePhoto() {
        Log.d(tag, "Zdjęcie")
        send(GlassesProtocol.takePhoto())
    }

    fun startVideoRecording() {
        Log.d(tag, "Start nagrywania wideo")
        send(GlassesProtocol.startVideo())
    }

    fun stopVideoRecording() {
        Log.d(tag, "Stop nagrywania wideo")
        send(GlassesProtocol.stopVideo())
    }

    fun startAudioRecording() {
        Log.d(tag, "Start nagrywania audio")
        send(GlassesProtocol.startAudio())
    }

    fun stopAudioRecording() {
        Log.d(tag, "Stop nagrywania audio")
        send(GlassesProtocol.stopAudio())
    }

    /**
     * Pyta okulary ile niezsynchronizowanych plików mają w pamięci.
     * Odpowiedź ma `dataType == 4` i niesie liczniki zdjęć, wideo i nagrań.
     */
    fun requestMediaCount(onResult: (images: Int, videos: Int, records: Int) -> Unit) {
        val bytes = GlassesProtocol.requestMediaCount()

        simulator?.let { sim ->
            _lastCommand.value = sim.handleCommand(bytes)
            val count = sim.mediaCount()
            _mediaCount.value = count
            onResult(count.images, count.videos, count.records)
            return
        }

        try {
            largeDataHandler.glassesControl(bytes) { _, response ->
                if (response != null && response.dataType == GlassesProtocol.DATA_TYPE_MEDIA_COUNT) {
                    val i = response.imageCount
                    val v = response.videoCount
                    val r = response.recordCount
                    Log.i(tag, "Na okularach: $i zdjęć, $v wideo, $r nagrań")
                    _mediaCount.value = MediaCount(i, v, r)
                    onResult(i, v, r)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "requestMediaCount nie powiodło się", e)
        }
    }

    /** Prosi okulary o aktualny poziom baterii - odpowiedź wraca jako notify 0x05. */
    fun requestBatteryLevel() {
        Log.d(tag, "Zapytanie o baterię")
        val sim = simulator
        if (sim != null) {
            sim.requestBattery()
            return
        }
        runCatching { largeDataHandler.syncBattery() }
            .onFailure { Log.w(tag, "syncBattery nie powiodło się", it) }
    }

    // === Szybka ścieżka: zdjęcie jako miniatura po BLE ===

    /**
     * Robi zdjęcie i pobiera je jako miniaturę przez BLE - bez Wi-Fi Direct.
     * To jest domyślna ścieżka dla V.I.C.T.O.R.: najszybsza droga od migawki do bajtów JPEG.
     *
     * @param quality wartość jakości miniatury przekazywana do okularów (0-2; wyżej = lepiej)
     * @return bajty JPEG albo `null` gdy okulary nie odpowiedziały w czasie
     */
    suspend fun capturePhoto(quality: Int = DEFAULT_THUMBNAIL_QUALITY): ByteArray? {
        if (!isConnected()) {
            Log.w(tag, "capturePhoto: okulary nie są połączone")
            lastPhotoFailure = "Okulary nie są połączone."
            return null
        }
        lastPhotoFailure = null
        // Znacznik dotyczy OSTATNIEGO zdjęcia, więc musi się kasować przy
        // każdym - inaczej "true" sprzed kilku minut kazałoby pominąć
        // ponowną próbę odczytania kodu na ostrzejszym zdjęciu.
        lastPhotoWasFullResolution = false
        pendingHardwarePhoto?.let { ready ->
            pendingHardwarePhoto = null
            Log.i(tag, "Używam zdjęcia zrobionego przyciskiem - bez nowej migawki")
            return ready
        }
        _photoReady.value = false
        captureInProgress = true
        try {
            return captureAiPhotoInternal(quality)
        } finally {
            captureInProgress = false
        }
    }

    private suspend fun captureAiPhotoInternal(quality: Int): ByteArray? {
        // PRÓBA 1 - dokładnie ta sekwencja, którą robi aplikacja producenta.
        //
        // ## Co było nie tak
        // Wysyłaliśmy `0x02 0x01 0x06 <jakość> <jakość>` - komendę "zdjęcia AI"
        // wziętą z aplikacji CyanBridge. W dzienniku ramek ze sprzętu NIE MA po
        // niej ani jednej odpowiedzi 0x02: okulary tej komendy po prostu nie
        // wykonują. Zgłoszono to jako "jakby okulary nie dostawały sygnału, że
        // mają zrobić zdjęcie" - i dokładnie tak było.
        //
        // Producent robi to w DWÓCH krokach, inną rodziną komend:
        //   1. `0x02 0x0B <jakość> <jakość>` - ustaw jakość miniatury dla AI
        //      (AIHelperActivity.showImageClarity - lista "jakość obrazu");
        //   2. `0x02 0x01 0x01` - zwykłe zdjęcie (AiChatViewModel.takePicture).
        // Po notify 0x02 prosi o miniaturę NATYCHMIAST, bez odczekania.
        diag.event(pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE, "próba 1: droga producenta", mapOf("jakość" to quality))
        send(GlassesProtocol.setAiPhotoQuality(quality))
        _photoReady.value = false
        send(GlassesProtocol.takePhoto())
        val signalled = awaitPhotoReady()
        diag.event(pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE, "próba 1: notify o gotowym zdjęciu", mapOf("przyszło" to signalled))
        if (signalled) {
            val first = receiveThumbnail(THUMBNAIL_TIMEOUT_MS)
            diag.event(
                pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE, "próba 1: miniatura",
                thumbnailFields(first)
            )
            first?.let { if (acceptPhoto(it)) return it }
        }

        // PRÓBA 1b - JESZCZE RAZ PO TĘ SAMĄ MINIATURĘ, BEZ NOWEJ MIGAWKI.
        //
        // Zgłoszone wprost: "słychać, że robią się dwa zdjęcia". Robiły się -
        // bo gdy pierwszy transfer miniatury nie doszedł, od razu leciała
        // PRÓBA 2, czyli druga KOMENDA MIGAWKI. Tymczasem zdjęcie już leży w
        // pamięci okularów: nie doszedł transfer, a nie zdjęcie. Ponowna prośba
        // o ten sam plik jest darmowa, nie zapełnia pamięci okularów i nie
        // każe użytkownikowi drugi raz trzymać kadru.
        if (signalled) {
            Log.w(tag, "Miniatura nie doszła, ale zdjęcie JEST - proszę o nie ponownie")
            diag.event(pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE, "próba 1b: proszę o ten sam plik jeszcze raz")
            val retry = receiveThumbnail(THUMBNAIL_TIMEOUT_MS)
            diag.event(
                pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE, "próba 1b: miniatura",
                thumbnailFields(retry)
            )
            retry?.let { if (acceptPhoto(it)) return it }
        }

        // PRÓBA 2 - komenda zdjęcia AI i stałe odczekanie, czyli droga CyanBridge.
        //
        // Zostaje jako zapas dla egzemplarzy, na których to ONA działa - inna
        // komenda i inne momenty, a więc naprawdę inna próba, a nie powtórzenie
        // pierwszej.
        Log.w(tag, "Droga producenta nie dała miniatury - próbuję komendą zdjęcia AI")
        _photoReady.value = false
        diag.event(pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE, "próba 2: komenda zdjęcia AI")
        val fallbackSignalled = shootAndWait(GlassesProtocol.captureAiPhoto(quality))
        val second = receiveThumbnail(THUMBNAIL_TIMEOUT_MS)
        diag.event(
            pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE, "próba 2: miniatura",
            thumbnailFields(second, mapOf("notify" to fallbackSignalled))
        )
        second?.let { if (acceptPhoto(it)) return it }

        // PRÓBA 3 - sama miniatura, BEZ ŻADNEJ KOMENDY.
        //
        // Pobieranie miniatur idzie innym kanałem SDK niż komendy sterujące, a
        // dziennik ze sprzętu pokazuje, że te dwa kanały potrafią żyć osobno:
        // zdarzenia przychodzą, a na komendy nie ma ani jednej odpowiedzi.
        // W takim stanie okulary i tak mają w pamięci ostatnie zdjęcie -
        // choćby to zrobione przyciskiem. Zapytanie o nie jest darmowe i jest
        // jedyną drogą, która nie zależy od kanału komend.
        if (!glassesAnswerCommands) {
            Log.w(tag, "Okulary nie odpowiadają na komendy - proszę o ostatnią miniaturę")
            diag.event(
                pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE,
                "próba 3: ostatnia miniatura bez komendy"
            )
            val third = receiveThumbnail(THUMBNAIL_TIMEOUT_MS)
            diag.event(
                pl.victor.app.diagnostics.DiagFormat.Phase.ZDJĘCIE, "próba 3: miniatura",
                thumbnailFields(third)
            )
            third?.let { if (acceptPhoto(it)) return it }
        }

        // Bez tego zdania użytkownik dostawał samo "nie udało się pobrać
        // zdjęcia" po kilkunastu sekundach ciszy - a to są DWIE różne awarie
        // wymagające dwóch różnych rzeczy.
        lastPhotoFailure = lastPhotoFailure ?: if (!signalled && !fallbackSignalled) {
            // Rozróżnienie, którego brakowało: "aparat nie zadziałał" to co
            // innego niż "okulary nie przyjmują ŻADNYCH komend". Licznik plików
            // pytamy zaraz po połączeniu (patrz onGlassesReady) - jeśli i on nie
            // wrócił, problem jest przed aparatem i mówienie o pełnej pamięci
            // wysyła użytkownika w złą stronę.
            if (!writeChannelArmed) {
                "Kanał komend do okularów nie jest odblokowany - żaden zapis do nich " +
                    "nie wychodzi. Rozłącz okulary i połącz je ponownie."
            } else if (!glassesAnswerCommands) {
                "Okulary nie odpowiadają na ŻADNĄ komendę sterującą, choć przysyłają " +
                    "zdarzenia. Rozłącz je i połącz ponownie; jeśli to nie pomoże, " +
                    "zrestartuj okulary."
            } else {
                "Okulary nie potwierdziły zrobienia zdjęcia. Sprawdź, czy nie mają " +
                    "pełnej pamięci i czy nie nagrywają w tej chwili wideo."
            }
        } else {
            "Okulary zrobiły zdjęcie, ale nie przysłały go po BLE. Podejdź " +
                "bliżej telefonu i spróbuj ponownie."
        }
        return null
    }

    /**
     * Wysyła komendę migawki i czeka, aż zdjęcie na pewno leży w pamięci okularów.
     *
     * ## Odczekanie jest BEZWARUNKOWE - i to jest tu sedno
     * Wcześniej pytaliśmy o miniaturę od razu po notify 0x02, traktując je jako
     * "zdjęcie gotowe". Okulary wysyłają je jednak wcześniej, niż plik wyląduje
     * w pamięci: SDK pyta wtedy o miniaturę, dostaje "łącznie 0 kawałków" i -
     * co gorsza - NIE woła w tym przypadku w ogóle naszego nasłuchu (patrz
     * [receiveThumbnail]). Z zewnątrz wygląda to dokładnie tak, jak zgłoszono:
     * okulary robią zdjęcie, a do AI nic nie dociera.
     *
     * Aplikacja referencyjna na tym samym SDK nie czeka na żadne notify -
     * odlicza stałe cztery sekundy i dopiero wtedy prosi o dane. Robimy tak
     * samo, a notify zostaje wyłącznie jako informacja do komunikatu błędu.
     *
     * @return czy okulary potwierdziły zdjęcie ramką notify
     */
    private suspend fun shootAndWait(command: ByteArray): Boolean {
        send(command)
        val signalled = awaitPhotoReady()
        delay(CAPTURE_SETTLE_MS)
        return signalled
    }

    /**
     * Przyjmuje bajty tylko wtedy, gdy to naprawdę zdjęcie.
     *
     * Transfer po BLE idzie kawałkami i nic w SDK nie sprawdza, czy poskładał
     * się z nich JPEG. Urwany transfer dawał wcześniej "obraz", na który model
     * odpowiadał o niczym - a to jest nie do odróżnienia od złej odpowiedzi.
     */
    /**
     * Pola miniatury do dziennika - JEDNO miejsce, żeby wszystkie próby
     * zapisywały to samo.
     *
     * Wcześniej `jpeg` logowała tylko próba 1, próba 2 sam rozmiar, a próba 3
     * nic. Przez to hipotezy "nie ma zdjęcia" i "przychodzi połowa" wyglądały w
     * dzienniku identycznie i nie dało się ich rozróżnić - czyli dziennik nie
     * odpowiadał na pytanie, dla którego powstał.
     */
    private fun thumbnailFields(
        bytes: ByteArray?,
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = extra + mapOf(
        "bajtów" to bytes?.size,
        "jpeg" to bytes?.let { GlassesProtocol.looksLikeJpeg(it) },
        "kompletny" to bytes?.let { GlassesProtocol.isCompleteJpeg(it) }
    )

    private fun acceptPhoto(bytes: ByteArray): Boolean {
        if (GlassesProtocol.looksLikeJpeg(bytes)) return true
        Log.w(tag, "Odebrane ${bytes.size} B nie jest zdjęciem JPEG")
        lastPhotoFailure = "Okulary przysłały ${bytes.size} B, ale to nie jest " +
            "zdjęcie - transfer się urwał. Podejdź bliżej telefonu."
        return false
    }

    /**
     * Czemu ostatnie zdjęcie się nie udało - albo `null`, gdy się udało.
     *
     * Trzymane obok [capturePhoto], a nie zwracane z niej, żeby nie przerabiać
     * całej drogi od okularów do orkiestratora tylko po to, by przenieść jedno
     * zdanie. Czyta to [pl.victor.app.AIOrchestrator], gdy seria zdjęć wyszła
     * pusta.
     */
    @Volatile
    var lastPhotoFailure: String? = null
        private set

    /**
     * Czy ostatnie zdjęcie z [captureSharpPhoto] jest ORYGINAŁEM z pamięci
     * okularów, czy tylko miniaturą.
     *
     * Ma znaczenie dla tego, co powiemy użytkownikowi i co powiemy modelowi:
     * na miniaturze liter z bliska po prostu nie ma, więc model, który o tym
     * nie wie, zaczyna zgadywać zamiast przyznać, że nie widzi.
     */
    @Volatile
    var lastPhotoWasFullResolution: Boolean = false
        private set

    /**
     * Robi zdjęcie i stara się oddać ORYGINAŁ, a nie miniaturę.
     *
     * ## Dlaczego to musi być osobna droga
     * Miniatura po BLE przychodzi w sekundę i do "co przede mną jest"
     * wystarcza. Do czytania nie wystarcza w ogóle - zgłoszone jako "AI nie
     * potrafi rozczytać większości tekstu ze zdjęć". Oryginał leży w pamięci
     * okularów i idzie przez Wi-Fi Direct, czyli kilkanaście sekund.
     *
     * Kolejność nie jest dowolna: miniatura MUSI iść pierwsza, bo to jej
     * komenda uruchamia migawkę - bez świeżego pliku nie ma czego pobierać.
     * Zostaje też jako zapas, gdy Wi-Fi nie wstanie: gorsze zdjęcie jest lepsze
     * niż żadne, byle wołający wiedział, które dostał ([lastPhotoWasFullResolution]).
     */
    suspend fun captureSharpPhoto(quality: Int = DEFAULT_THUMBNAIL_QUALITY): ByteArray? {
        lastPhotoWasFullResolution = false
        val thumbnail = capturePhoto(quality) ?: return null

        // Użytkownik może zostać przy samej miniaturze - jest natychmiast, a do
        // pytania "co przede mną" zwykle wystarcza. Pobieranie oryginału przez
        // Wi-Fi to najdłuższy element tury, więc nie ma go narzucać każdemu.
        val settings = pl.victor.app.data.SettingsRepository.getInstance(appContext)
        if (settings.getPhotoSource() == pl.victor.app.data.SettingsRepository.PHOTO_THUMBNAIL) {
            Log.i(tag, "Ustawienia: model dostaje miniaturę (${thumbnail.size} B)")
            return thumbnail
        }

        val full = runCatching { downloadLatestPhoto() }
            .onFailure { Log.w(tag, "Pobranie oryginału nie powiodło się", it) }
            .getOrNull()
        if (full != null && full.size > thumbnail.size) {
            // Zmniejszamy PRZED wysłaniem: litery zostają czytelne, a to rozmiar
            // pliku decyduje, jak długo trwa droga do modelu.
            val divisor = settings.getPhotoDivisor()
            val prepared = pl.victor.app.vision.PhotoScaler.shrink(full, divisor)
            Log.i(
                tag,
                "Oryginał ${full.size} B -> ${prepared.size} B (dzielnik $divisor, " +
                    "miniatura miała ${thumbnail.size} B)"
            )
            lastPhotoWasFullResolution = true
            return prepared
        }
        Log.w(tag, "Zostaję przy miniaturze - oryginał nie doszedł albo nie jest lepszy")
        return thumbnail
    }

    /**
     * Czeka aż okulary zgłoszą gotowe zdjęcie ramką notify 0x02.
     * Gdy notify nie dotrze (starszy firmware), wraca do sztywnego odczekania -
     * dzięki temu przechwytywanie działa tak szybko, jak pozwala sprzęt.
     */
    private suspend fun awaitPhotoReady(): Boolean {
        val signalled = withTimeoutOrNull(PHOTO_READY_TIMEOUT_MS) {
            while (!_photoReady.value) {
                delay(PHOTO_READY_POLL_MS)
            }
            true
        }
        if (signalled == null) Log.d(tag, "Brak notify o gotowym zdjęciu")
        return signalled == true
    }

    /**
     * Pobiera miniaturę zdjęcia zrobionego fizycznym przyciskiem na okularach
     * i odkłada ją dla najbliższego [capturePhoto].
     *
     * ## Po co ten schowek
     * Zdjęcie już istnieje - użytkownik właśnie wcisnął przycisk. Bez schowka
     * cała droga "opisz, co widzisz" zaczynałaby się od zrobienia DRUGIEGO
     * zdjęcia: migawka, kolejny plik w pamięci okularów i kilka sekund
     * czekania na to samo, co już mamy.
     *
     * @return `true` gdy udało się pobrać zdjęcie
     */
    suspend fun fetchPhotoFromHardwareButton(): Boolean {
        if (!isConnected()) {
            Log.w(tag, "fetchPhotoFromHardwareButton: okulary nie są połączone")
            return false
        }
        // Notify przychodzi, ZANIM plik wyląduje w pamięci - patrz [shootAndWait].
        delay(CAPTURE_SETTLE_MS)
        _photoReady.value = false
        val photo = receiveThumbnail()
        if (photo == null || !acceptPhoto(photo)) return false
        pendingHardwarePhoto = photo
        return true
    }

    /**
     * Zdjęcie zrobione przyciskiem, czekające na odbiorcę.
     *
     * Konsumuje je pierwsze [capturePhoto] - i tylko jedno, bo po odczytaniu
     * schowek jest pusty. Stare zdjęcie oddane drugi raz opisywałoby scenę,
     * której użytkownik dawno nie ma przed sobą.
     */
    @Volatile
    private var pendingHardwarePhoto: ByteArray? = null

    /**
     * Odbiera miniaturę po BLE. Vendor SDK dostarcza ją w kawałkach -
     * `isComplete == true` oznacza koniec transferu.
     *
     * ## Czego SDK producenta NIE zgłasza
     * Odczyt kodu `LargeDataHandler` pokazuje, że `getPictureThumbnails`
     * rejestruje nasłuch i sam prosi o kolejne kawałki (numer kawałka rośnie w
     * jego własnym callbacku). Ale gdy okulary odpowiedzą "łącznie 0 kawałków",
     * SDK po prostu WYCHODZI - nie woła naszego nasłuchu ani razu, nawet z
     * błędem. Z naszej strony jest to nie do odróżnienia od zerwanego
     * transferu: jedno i drugie kończy się limitem czasu. Dlatego limit jest
     * jedynym wyjściem z tej metody i dlatego [capturePhoto] tłumaczy go na
     * zdanie dla użytkownika, zamiast milczeć.
     *
     * @param timeoutMs ile czekać na koniec transferu
     */
    private suspend fun receiveThumbnail(
        timeoutMs: Long = THUMBNAIL_TIMEOUT_MS
    ): ByteArray? {
        simulator?.let { return it.thumbnail() }

        // Prośba o miniaturę to też zapis - bez uzbrojonego kanału SDK wyrzuca ją
        // po cichu, a z zewnątrz wygląda to jak zerwany transfer (limit czasu).
        armWriteChannel("pobieranie miniatury")

        val output = ByteArrayOutputStream()
        val complete = CompletableDeferred<Boolean>()
        try {
            var chunks = 0
            largeDataHandler.getPictureThumbnails { _, isComplete, data ->
                if (data != null && data.isNotEmpty()) {
                    output.write(data)
                    chunks++
                }
                if (isComplete && !complete.isCompleted) {
                    // Bez tego wpisu "miniatura nie doszła" i "doszła pusta" są
                    // z zewnątrz nie do odróżnienia - a to dwie różne awarie.
                    Log.i(tag, "Miniatura: $chunks kawałków, ${output.size()} B")
                    complete.complete(output.size() > 0)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "getPictureThumbnails nie powiodło się", e)
            return null
        }

        val ok = withTimeoutOrNull(timeoutMs) { complete.await() }
        if (ok != true) {
            Log.w(tag, "Transfer miniatury przekroczył limit czasu")
            return null
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    // === Pełne pliki przez Wi-Fi Direct (HTTP) ===

    /**
     * Pobiera listę plików multimedialnych z okularów.
     * Endpoint `/files/media.config` zwraca zwykły tekst - jedna nazwa pliku na linię.
     */
    suspend fun getMediaFileList(): List<String> = withContext(Dispatchers.IO) {
        simulator?.let { return@withContext it.mediaFileList() }

        val ip = _glassesIp.value
            ?: throw VictorException("Brak IP okularów - najpierw enableTransferMode()")

        val conn = URL("http://$ip/files/media.config").openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = LIST_READ_TIMEOUT_MS
        try {
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8)
                .readText()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    /** Pobiera pojedynczy plik z okularów przez HTTP. */
    suspend fun downloadFile(filename: String): ByteArray = withContext(Dispatchers.IO) {
        simulator?.let { return@withContext it.fileBytes(filename) }

        val ip = _glassesIp.value
            ?: throw VictorException("Brak IP okularów - najpierw enableTransferMode()")

        Log.d(tag, "Pobieranie $filename z $ip")
        val conn = URL("http://$ip/files/$filename").openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = FILE_READ_TIMEOUT_MS
        try {
            conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Włącza tryb transferu i czeka aż okulary zgłoszą swoje IP ramką notify 0x08.
     * @return `true` gdy IP jest dostępne
     */
    /**
     * Otwiera sesję transferu plików i czeka na adres okularów.
     *
     * Publiczny odpowiednik [awaitGlassesIp] dla ekranów, które przeglądają
     * pliki dłużej niż jedno pobranie (galeria). Sesję zamyka
     * [endTransferSession] - i trzeba to zrobić, bo dopóki trwa, cały ruch
     * telefonu idzie przez grupę Wi-Fi okularów.
     */
    suspend fun openMediaSession(): Boolean = awaitGlassesIp()

    /**
     * Czemu ostatnie podnoszenie łącza Wi-Fi Direct się nie udało.
     *
     * ## Po co osobne zdanie zamiast `false`
     * Bo tych awarii są CZTERY i wymagają czterech różnych rzeczy: telefon bez
     * Wi-Fi Direct, brak zgody na urządzenia w pobliżu, nieodnaleziona grupa
     * okularów i brak ramki z adresem. Galeria pokazywała na wszystkie jeden
     * komunikat - "okulary nie zgłosiły adresu Wi-Fi" - który w trzech
     * przypadkach na cztery wysyłał użytkownika w złą stronę.
     */
    @Volatile
    var lastTransferFailure: String? = null
        private set

    private suspend fun awaitGlassesIp(): Boolean {
        lastTransferFailure = null

        // 1. Poproś okulary o wejście w tryb transferu - zaczną rozgłaszać grupę Wi-Fi Direct.
        enableTransferMode()

        // 2. Dołącz do tej grupy - ale NIE natychmiast.
        //
        // Okulary stawiają grupę Wi-Fi Direct dopiero PO otrzymaniu komendy, a
        // to trwa kilka sekund. Szukanie od razu po jej wysłaniu nie widziało
        // niczego i kończyło się komunikatem "nie znalazłem sieci okularów,
        // podejdź bliżej" - który wysyłał użytkownika w złą stronę, bo
        // odległość nie miała z tym nic wspólnego.
        //
        // Stąd kilka podejść z przerwą. Każde kolejne jest darmowe: grupa albo
        // już stoi, albo właśnie wstaje.
        if (simulator == null) {
            var joined = false
            repeat(WIFI_JOIN_ATTEMPTS) { attempt ->
                if (!joined) {
                    delay(WIFI_GROUP_SETTLE_MS)
                    joined = joinWifiDirectGroup()
                    if (!joined) {
                        Log.i(tag, "Sieć okularów jeszcze nie stoi (próba ${attempt + 1})")
                    }
                }
            }
            if (!joined) return false
        }

        // 3. IP okularów przychodzi ramką notify 0x08 - groupOwnerAddress to zwykle telefon.
        val ip = withTimeoutOrNull(IP_TIMEOUT_MS) {
            while (_glassesIp.value == null) {
                delay(IP_POLL_INTERVAL_MS)
            }
            _glassesIp.value
        }
        if (ip == null) {
            Log.w(tag, "Nie doczekano się IP okularów (ramka notify 0x08)")
            lastTransferFailure = "Telefon dołączył do sieci okularów, ale one nie " +
                "podały swojego adresu. Zdejmij je i załóż ponownie albo zrestartuj."
            return false
        }
        Log.i(tag, "Okulary osiągalne pod $ip")
        return true
    }

    /** Dołącza do grupy Wi-Fi Direct okularów. @return `true` gdy się udało */
    private suspend fun joinWifiDirectGroup(): Boolean {
        // Powód bierzemy Z PRÓBY, nie zgadujemy przed nią.
        //
        // Wcześniej były tu trzy własne komunikaty, budowane z tego, co dało się
        // sprawdzić stąd - a najczęstsze przyczyny (zgaszone Wi-Fi, zgaszona
        // systemowa Lokalizacja na Androidzie 12 i starszym, framework P2P
        // zajęty poprzednią próbą) nie były wśród nich. Użytkownik dostawał
        // "podejdź bliżej" i podchodził, co oczywiście nic nie dawało.
        // Rozpoznaje je teraz GlassesWifiTransfer - patrz WifiDirectDiagnosis.
        if (!wifiTransfer.connect(deviceNameHint = WIFI_DEVICE_NAME_HINT)) {
            Log.w(tag, "Nie udało się dołączyć do grupy Wi-Fi Direct okularów")
            lastTransferFailure = wifiTransfer.lastFailure
                ?: "Nie udało się połączyć z siecią okularów. Spróbuj ponownie."
            return false
        }
        wifiTransfer.awaitServerReady()
        return true
    }

    // === Nagrania głosowe przez BLE (bez Wi-Fi Direct) ===

    /** Postęp pobierania nagrania: 0.0 - 1.0, albo `null` gdy nic nie trwa. */
    val recordingProgress: StateFlow<Float?> get() = recordings.progress

    /**
     * Lista nagrań głosowych w pamięci okularów, pobierana kanałem BLE.
     * Nie wymaga trybu transferu ani Wi-Fi Direct.
     *
     * @param fileType numer typu pliku; producent go nie udokumentował, więc
     *        właściwą wartość ustala się doświadczalnie - patrz [GlassesRecordings]
     */
    suspend fun listRecordings(
        fileType: Int = GlassesRecordings.DEFAULT_FILE_TYPE
    ): List<Recording> {
        simulator?.let { return it.recordings() }
        if (!isConnected()) {
            Log.w(tag, "listRecordings: okulary nie są połączone")
            return emptyList()
        }
        return recordings.list(fileType)
    }

    /** Pobiera nagranie głosowe kanałem BLE. */
    suspend fun downloadRecording(
        fileName: String,
        fileType: Int = GlassesRecordings.DEFAULT_FILE_TYPE
    ): ByteArray? {
        simulator?.let { return it.recordingBytes(fileName) }
        if (!isConnected()) {
            Log.w(tag, "downloadRecording: okulary nie są połączone")
            return null
        }
        return recordings.download(fileName, fileType)
    }

    /** Kończy sesję transferu: rozłącza Wi-Fi Direct i przywraca domyślny routing. */
    fun endTransferSession() {
        if (simulator == null) wifiTransfer.stop()
        _glassesIp.value = null
    }

    /** Pobiera najnowsze zdjęcie w pełnej rozdzielczości przez Wi-Fi Direct. */
    suspend fun downloadLatestPhoto(): ByteArray? = downloadLatest(PHOTO_EXTENSIONS, "zdjęcia")

    /** Pobiera najnowsze wideo przez Wi-Fi Direct. */
    suspend fun downloadLatestVideo(): ByteArray? = downloadLatest(VIDEO_EXTENSIONS, "wideo")

    /** Pobiera najnowsze nagranie audio przez Wi-Fi Direct. */
    suspend fun downloadLatestAudio(): ByteArray? = downloadLatest(AUDIO_EXTENSIONS, "audio")

    private suspend fun downloadLatest(extensions: List<String>, label: String): ByteArray? {
        if (!awaitGlassesIp()) return null

        return try {
            val matching = getMediaFileList().filter { file ->
                extensions.any { file.endsWith(it, ignoreCase = true) }
            }
            if (matching.isEmpty()) {
                Log.w(tag, "Brak plików typu $label na okularach")
                return null
            }
            // Nazwy plików z okularów są sekwencyjne/oparte na czasie - największa = najnowsza.
            val latest = matching.max()
            Log.i(tag, "Pobieranie najnowszego pliku $label: $latest")
            downloadFile(latest)
        } catch (e: Exception) {
            Log.e(tag, "Pobieranie pliku $label nie powiodło się", e)
            null
        } finally {
            // Zwolnij sieć - inaczej cały ruch aplikacji zostaje na grupie okularów.
            endTransferSession()
        }
    }

    // === Sprzątanie ===

    /** Zwalnia zasoby SDK - wywołaj gdy aplikacja kończy pracę. */
    @Synchronized
    fun release() {
        Log.i(tag, "Zwalnianie zasobów")
        stopScan()

        val sim = simulator
        if (sim != null) {
            sim.disconnect()
            _connectionState.value = ConnectionState.DISCONNECTED
            initialized = false
            return
        }

        wifiTransfer.stop()
        // Wyrejestrowanie musi iść tą samą szyną co rejestracja - patrz
        // registerBleBroadcastReceiver().
        runCatching {
            LocalBroadcastManager.getInstance(appContext).unregisterReceiver(bleStateReceiver)
        }.onFailure { Log.w(tag, "unregisterReceiver nie powiodło się", it) }
        if (notifyListenerRegistered) {
            runCatching { largeDataHandler.removeOutDeviceListener(GlassesProtocol.DEVICE_NOTIFY_KEY) }
                .onFailure { Log.w(tag, "removeOutDeviceListener nie powiodło się", it) }
            notifyListenerRegistered = false
        }
        runCatching { largeDataHandler.removeGlassesControlCallback() }
            .onFailure { Log.w(tag, "removeGlassesControlCallback nie powiodło się", it) }
        runCatching { largeDataHandler.disEnable() }
            .onFailure { Log.w(tag, "disEnable nie powiodło się", it) }
        initialized = false
    }

    companion object {
        private const val TAG = "VictorManager"






        /** Jakość miniatury: zakres 0..6 wg dokumentacji producenta. */
        /** Fragment nazwy urządzenia Wi-Fi Direct okularów. */
        private const val WIFI_DEVICE_NAME_HINT = "cyan"

        /** Ile razy próbujemy dołączyć do grupy okularów. */
        private const val WIFI_JOIN_ATTEMPTS = 3

        /** Ile czekamy, zanim okulary postawią grupę Wi-Fi Direct. */
        private const val WIFI_GROUP_SETTLE_MS = 3_000L

        private const val DEFAULT_THUMBNAIL_QUALITY = 2

        /** Symulowane okulary "znajdują się" po chwili, jak prawdziwy skan BLE. */
        private const val SIMULATED_SCAN_DELAY_MS = 700L

        /**
         * Ile czekamy na BLE_SERVICE_DISCOVERED zanim uznamy próbę połączenia za
         * nieudaną. Sam SDK ma wewnętrzny zegar 40s (patrz BLE_NO_CALLBACK) - ten jest
         * celowo dłuższy (45s), żeby nie ubiegać własnym, luźno dobranym limitem
         * mechanizmu producenta strojonego pod ich sprzęt. To wyłącznie siatka
         * bezpieczeństwa na wypadek scenariusza, którego BLE_NO_CALLBACK nie pokrywa.
         */
        private const val BLE_CONNECT_TIMEOUT_MS = 45_000L

        /** Ile ramek notify trzymamy na potrzeby diagnostyki. */
        private const val NOTIFY_LOG_SIZE = 50

        /**
         * Ile czekać na adres IP po włączeniu trybu transferu. Okulary muszą
         * podnieść grupę Wi-Fi Direct, co bywa wolniejsze niż samo BLE.
         */
        private const val TRANSFER_MODE_TIMEOUT_MS = 15_000L

        /**
         * Czas potrzebny okularom na ZAPISANIE zdjęcia, zanim poprosimy o
         * miniaturę. Tyle samo odlicza aplikacja referencyjna na tym SDK.
         */
        private const val CAPTURE_SETTLE_MS = 4_000L

        /**
         * Ile czekamy na notify 0x02. Krótko, bo to już tylko informacja do
         * komunikatu błędu - właściwe odliczanie robi [CAPTURE_SETTLE_MS].
         */
        private const val PHOTO_READY_TIMEOUT_MS = 3_000L
        private const val PHOTO_READY_POLL_MS = 50L
        private const val THUMBNAIL_TIMEOUT_MS = 10_000L

        private const val IP_TIMEOUT_MS = 15_000L
        private const val IP_POLL_INTERVAL_MS = 100L

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val LIST_READ_TIMEOUT_MS = 10_000
        private const val FILE_READ_TIMEOUT_MS = 60_000

        private val PHOTO_EXTENSIONS = listOf(".jpg", ".jpeg", ".png")
        private val VIDEO_EXTENSIONS = listOf(".mp4", ".mov", ".avi")
        private val AUDIO_EXTENSIONS = listOf(".opus", ".ogg", ".wav", ".mp3")

        @Volatile
        private var instance: VictorManager? = null

        fun getInstance(context: Context): VictorManager =
            instance ?: synchronized(this) {
                instance ?: VictorManager(context).also { instance = it }
            }
    }
}

/**
 * Ile danych przyszło z mikrofonu okularów - patrz [VictorManager.addMicStreamListener].
 *
 * Służy do jednej, bardzo konkretnej rzeczy: odróżnienia "okulary nie nadają
 * dźwięku" od "nadają, ale aplikacja nie umie go rozkodować". Bez tego pomiaru
 * obie sytuacje wyglądają identycznie - jako cisza.
 */
data class GlassesMicStats(
    val packets: Int = 0,
    val bytes: Int = 0,
    val lastPacketAtMs: Long = 0L,
    val lastPacketSize: Int = 0
) {
    val isReceiving: Boolean
        get() = packets > 0 && System.currentTimeMillis() - lastPacketAtMs < 5_000L
}
