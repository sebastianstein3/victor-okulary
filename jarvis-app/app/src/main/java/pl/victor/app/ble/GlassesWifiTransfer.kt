package pl.victor.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wi-Fi Direct do pobierania plików z okularów.
 *
 * Zdjęcia da się dostać po BLE jako miniatury, ale wideo i pliki w pełnej
 * rozdzielczości idą wyłącznie po HTTP z serwera na okularach. Żeby telefon
 * miał do niego trasę, musi najpierw dołączyć do grupy Wi-Fi Direct okularów.
 *
 * Kolejność operacji:
 * 1. BLE: `glassesControl(0x02 0x01 0x04)` włącza tryb transferu
 * 2. Wi-Fi Direct: odkrycie urządzeń i połączenie (WPS PBC)
 * 3. Przypięcie procesu do sieci P2P - bez tego na części telefonów
 *    (zwłaszcza Samsung) ruch idzie domyślną siecią i HTTP nie dochodzi
 * 4. BLE notify 0x08 podaje IP okularów
 * 5. HTTP: `/files/media.config`, potem `/files/<nazwa>`
 *
 * Pułapka: `WifiP2pInfo.groupOwnerAddress` to zwykle **telefon**
 * (`192.168.49.1`), a nie okulary. Adresu okularów szukamy w ramce BLE 0x08.
 */
class GlassesWifiTransfer(context: Context) {

    private val appContext: Context = context.applicationContext
    private val tag = TAG

    private val wifiP2pManager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val locationManager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * Dlaczego ostatnia próba się nie udała - zdaniem dla użytkownika.
     *
     * Sam [TransferState.FAILED] mówi tylko tyle, że się nie udało. Powód
     * bywa banalny i po stronie telefonu (zgaszone Wi-Fi, zgaszona
     * Lokalizacja), a poprzednie komunikaty go zgadywały - patrz
     * [WifiDirectDiagnosis].
     */
    @Volatile
    var lastFailure: String? = null
        private set

    private val _state = MutableStateFlow(TransferState.IDLE)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private var peersDeferred: CompletableDeferred<List<WifiP2pDevice>>? = null
    private var connectionDeferred: CompletableDeferred<WifiP2pInfo>? = null
    private var receiverRegistered = false
    private var boundNetwork: Network? = null
    private var apCallback: ConnectivityManager.NetworkCallback? = null

    /** Czy urządzenie ma uprawnienie wymagane do Wi-Fi Direct. */
    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Czy Wi-Fi Direct jest w ogóle dostępny na tym urządzeniu. */
    fun isAvailable(): Boolean = wifiP2pManager != null

    // === Cykl życia ===

    /** Inicjalizuje kanał P2P i rejestruje nasłuch zdarzeń. */
    @Synchronized
    fun start() {
        val manager = wifiP2pManager ?: run {
            Log.w(tag, "Wi-Fi Direct niedostępny na tym urządzeniu")
            return
        }
        if (channel == null) {
            channel = manager.initialize(appContext, Looper.getMainLooper()) {
                Log.w(tag, "Kanał P2P rozłączony")
                channel = null
                _state.value = TransferState.IDLE
            }
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                p2pReceiver,
                intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    /** Rozłącza grupę P2P i zwalnia zasoby. */
    @Synchronized
    fun stop() {
        leaveAccessPoint()
        removeGroup()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(p2pReceiver) }
                .onFailure { Log.w(tag, "unregisterReceiver nie powiodło się", it) }
            receiverRegistered = false
        }
        _state.value = TransferState.IDLE
    }

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val manager = wifiP2pManager ?: return
            val ch = channel ?: return

            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (!hasPermission()) return
                    manager.requestPeers(ch) { peers ->
                        val list = peers.deviceList.toList()
                        Log.d(tag, "Znaleziono ${list.size} urządzeń P2P")
                        peersDeferred?.takeIf { !it.isCompleted && list.isNotEmpty() }
                            ?.complete(list)
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (!hasPermission()) return
                    manager.requestConnectionInfo(ch) { info ->
                        if (info != null && info.groupFormed) {
                            Log.i(tag, "Grupa P2P utworzona (właściciel=${info.isGroupOwner})")
                            connectionDeferred?.takeIf { !it.isCompleted }?.complete(info)
                        } else {
                            Log.d(tag, "Grupa P2P rozwiązana")
                            _state.value = TransferState.IDLE
                        }
                    }
                }
            }
        }
    }

    // === Połączenie ===

    /** Nazwy urządzeń widzianych przy ostatnim szukaniu - do komunikatu o błędzie. */
    @Volatile
    var lastSeenPeers: List<String> = emptyList()
        private set

    /**
     * Łączy telefon z grupą Wi-Fi Direct okularów.
     *
     * @param deviceNameHints fragmenty nazw, po których poznajemy okulary.
     *                        Pusta lista znaczy „nie wiem, jak się nazywają" -
     *                        i wtedy NIE łączymy się z niczym, patrz niżej.
     * @return `true` gdy grupa została utworzona i proces przypięty do sieci P2P
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceNameHints: List<String> = emptyList()): Boolean {
        lastFailure = null
        val manager = wifiP2pManager ?: run {
            lastFailure = WifiDirectDiagnosis.preflight(
                p2pAvailable = false,
                wifiEnabled = true,
                locationEnabled = true,
                sdkInt = Build.VERSION.SDK_INT
            )
            _state.value = TransferState.FAILED
            return false
        }
        if (!hasPermission()) {
            Log.w(tag, "Brak uprawnienia do Wi-Fi Direct")
            lastFailure = "Brak zgody na urządzenia w pobliżu. Bez niej telefon nie " +
                "dołączy do sieci okularów - przyznaj ją i spróbuj ponownie."
            _state.value = TransferState.NO_PERMISSION
            return false
        }

        // WARUNKI, KTÓRE DA SIĘ SPRAWDZIĆ ZANIM ZACZNIEMY SZUKAĆ.
        //
        // Dwa najczęstsze powody, dla których "galeria nie łączy się z
        // okularami", leżą w ustawieniach telefonu, a nie w okularach: zgaszone
        // Wi-Fi i - do Androida 12 włącznie - zgaszona systemowa Lokalizacja.
        // Ta druga jest wredna, bo `discoverPeers` nie zgłasza wtedy ŻADNEGO
        // błędu, tylko zwraca pustą listę, którą dotąd tłumaczyliśmy jako
        // "okulary nie postawiły sieci, podejdź bliżej".
        WifiDirectDiagnosis.preflight(
            p2pAvailable = true,
            wifiEnabled = isWifiEnabled(),
            locationEnabled = isLocationEnabled(),
            sdkInt = Build.VERSION.SDK_INT
        )?.let { reason ->
            Log.w(tag, "Wi-Fi Direct nieprzygotowany: $reason")
            lastFailure = reason
            _state.value = TransferState.FAILED
            return false
        }

        start()
        val ch = channel ?: return false

        // Sprzątamy po poprzedniej próbie. Framework P2P trzyma jedną grupę na
        // urządzenie: niedokończone połączenie zostaje w nim jako "zajęte" i
        // każde następne szukanie wraca z kodem BUSY - czyli raz nieudana
        // próba psuła wszystkie kolejne, aż do przełączenia Wi-Fi.
        //
        // Z CZEKANIEM, nie na ślepo: removeGroup() tylko WYSYŁA żądanie, a
        // odpowiedź przychodzi wywołaniem zwrotnym. Bez tego szukanie ruszałoby
        // zanim framework zdąży zwolnić grupę - czyli dokładnie w tę samą
        // ścianę, którą sprzątanie miało usunąć.
        awaitGroupRemoved()

        _state.value = TransferState.DISCOVERING
        val peers = discoverPeers(manager, ch)
        // Nazwy widzianych urządzeń idą do komunikatu dla użytkownika: "nie
        // znalazłem sieci okularów" znaczy co innego, gdy nie widać NICZEGO
        // (okulary nie postawiły grupy), a co innego, gdy widać kilka obcych
        // urządzeń (jesteśmy za daleko albo trafiliśmy w cudzą).
        lastSeenPeers = peers.map { it.deviceName.orEmpty() }
        Log.i(tag, "Widoczne urządzenia Wi-Fi Direct: ${lastSeenPeers.joinToString()}")
        if (peers.isEmpty()) {
            Log.w(tag, "Nie znaleziono urządzeń Wi-Fi Direct")
            // Odmowa frameworka to co innego niż puste szukanie - patrz
            // discoverPeers, gdzie zapisujemy kod odmowy.
            lastFailure = lastFailure ?: WifiDirectDiagnosis.nothingFound(lastSeenPeers)
            _state.value = TransferState.FAILED
            return false
        }

        // NIE ŁĄCZYMY SIĘ Z URZĄDZENIEM, KTÓRE NIE WYGLĄDA NA OKULARY.
        //
        // Dotąd, gdy żadna nazwa nie pasowała do podpowiedzi, braliśmy
        // `peers.first()` - czyli PIERWSZE LEPSZE urządzenie w zasięgu. W
        // dzienniku z 12 września wyszło to dokładnie tak, jak musiało:
        // "Znalazłem okulary ([TV] Samsung 6 Series Gumis)" i trzy próby
        // dołączenia do CUDZEGO TELEWIZORA przez 90 sekund. Przez te 90 sekund
        // telefon był przypięty do grupy P2P zamiast do internetu, a na
        // telewizorze obcej osoby wyskakiwało pytanie o parowanie.
        //
        // Prośba o połączenie jest widoczna na drugim urządzeniu, więc
        // „spróbujmy, a nuż to okulary" nie jest tu niewinne. Gdy nic nie
        // pasuje, mówimy wprost, co widać - i to nazwy z dziennika powiedzą,
        // jak naprawdę przedstawiają się okulary w Wi-Fi Direct.
        val target = peers.firstOrNull { peer ->
            // `deviceName` przychodzi z Javy i bywa puste, gdy rekord
            // rozgłoszenia nie doszedł w całości - wtedy po prostu nie pasuje.
            val name = peer.deviceName.orEmpty()
            deviceNameHints.any { hint -> name.contains(hint, ignoreCase = true) }
        }
        if (target == null) {
            Log.w(tag, "Żadne widoczne urządzenie nie wygląda na okulary")
            lastFailure = "W pobliżu nie ma sieci okularów. Widzę tylko: " +
                lastSeenPeers.joinToString() + ". Upewnij się, że okulary są " +
                "włączone i blisko telefonu."
            _state.value = TransferState.FAILED
            return false
        }
        Log.i(tag, "Łączę z ${target.deviceName} (${target.deviceAddress})")

        _state.value = TransferState.CONNECTING
        val info = connectToDevice(manager, ch, target)
        if (info == null) {
            Log.w(tag, "Nie udało się utworzyć grupy P2P")
            lastFailure = lastFailure ?: ("Znalazłem okulary (${target.deviceName}), ale " +
                "telefon nie zdążył się z nimi połączyć. Spróbuj jeszcze raz - " +
                "przy pierwszym łączeniu bywa potrzebne potwierdzenie na okularach.")
            _state.value = TransferState.FAILED
            return false
        }

        // Adres właściciela grupy to adres TELEFONU (zwykle 192.168.49.1).
        // Okulary muszą go poznać - patrz [groupOwnerAddress].
        lastGroupOwnerAddress = runCatching { info.groupOwnerAddress?.hostAddress }.getOrNull()

        // Bez tego na części telefonów ruch HTTP pójdzie zwykłym Wi-Fi.
        bindProcessToP2pNetwork()
        _state.value = TransferState.CONNECTED
        return true
    }

    /**
     * Adres telefonu w grupie Wi-Fi Direct - ten, który trzeba PODAĆ OKULAROM.
     *
     * ## Skąd wiadomo, że trzeba
     * Z AAR producenta. `LargeDataHandler.writeIpToSoc(ip, callback)` wysyła
     * pod komendą `0xFC` strukturę `WifiInfoReq`, czyli `[0x02, długość, ip
     * jako UTF-8]`. Aplikacja nigdy tego nie wołała: czekaliśmy wyłącznie, aż
     * okulary SAME podadzą swój adres ramką notify 0x08 - a on nie przychodził
     * i cała galeria oraz pełna rozdzielczość stały na tym w miejscu.
     *
     * `null`, dopóki grupa nie stoi.
     */
    @Volatile
    var lastGroupOwnerAddress: String? = null
        private set

    @SuppressLint("MissingPermission")
    private suspend fun discoverPeers(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ): List<WifiP2pDevice> {
        val deferred = CompletableDeferred<List<WifiP2pDevice>>()
        peersDeferred = deferred

        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(tag, "Skanowanie P2P wystartowało")
            }

            override fun onFailure(reason: Int) {
                Log.w(tag, "Skanowanie P2P nie wystartowało (kod=$reason)")
                lastFailure = WifiDirectDiagnosis.discoveryRefused(reason)
                if (!deferred.isCompleted) deferred.complete(emptyList())
            }
        })

        val result = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { deferred.await() } ?: emptyList()
        peersDeferred = null
        return result
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToDevice(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        device: WifiP2pDevice
    ): WifiP2pInfo? {
        val deferred = CompletableDeferred<WifiP2pInfo>()
        connectionDeferred = deferred

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // WPS Push Button - tak łączy się oficjalna aplikacja producenta.
            wps.setup = android.net.wifi.WpsInfo.PBC
        }

        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(tag, "Żądanie połączenia P2P wysłane")
            }

            override fun onFailure(reason: Int) {
                Log.w(tag, "Żądanie połączenia P2P odrzucone (kod=$reason)")
                lastFailure = WifiDirectDiagnosis.discoveryRefused(reason)
                if (!deferred.isCompleted) deferred.cancel()
            }
        })

        val info = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { deferred.await() }.getOrNull()
        }
        connectionDeferred = null
        return info
    }

    /**
     * Prosi o usunięcie grupy P2P i czeka na odpowiedź frameworka.
     *
     * Gdy żadnej grupy nie ma - a tak jest najczęściej - odpowiedź wraca
     * natychmiast, więc to nie jest stały koszt doliczany do każdego łączenia.
     * Limit czasu jest krótki: nieodebrana odpowiedź nie może blokować
     * szukania, bo bez sprzątania i tak jest szansa na sukces.
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitGroupRemoved() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return
        if (!hasPermission()) return
        val done = CompletableDeferred<Unit>()
        runCatching {
            manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(tag, "Grupa P2P usunięta przed szukaniem")
                    done.complete(Unit)
                }

                override fun onFailure(reason: Int) {
                    // Najczęściej znaczy "nie było czego usuwać" - to nie błąd.
                    Log.d(tag, "Nie usunięto grupy P2P przed szukaniem (kod=$reason)")
                    done.complete(Unit)
                }
            })
        }.onFailure {
            Log.w(tag, "removeGroup rzuciło wyjątkiem", it)
            done.complete(Unit)
        }
        withTimeoutOrNull(GROUP_CLEANUP_TIMEOUT_MS) { done.await() }
    }

    @SuppressLint("MissingPermission")
    private fun removeGroup() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return
        if (!hasPermission()) return
        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(tag, "Grupa P2P usunięta")
            }

            override fun onFailure(reason: Int) {
                Log.d(tag, "Nie usunięto grupy P2P (kod=$reason)")
            }
        })
    }

    // === Routing ===

    /**
     * Przypina proces do sieci Wi-Fi Direct, żeby żądania HTTP trafiały
     * do okularów, a nie domyślną trasą (np. przez sieć komórkową).
     */
    private fun bindProcessToP2pNetwork() {
        val cm = connectivityManager ?: return
        val network = findP2pNetwork(cm)
        if (network == null) {
            Log.i(tag, "Nie znalazłem interfejsu p2p - zostawiam domyślny routing")
            return
        }
        val bound = runCatching { cm.bindProcessToNetwork(network) }
            .onFailure { Log.w(tag, "bindProcessToNetwork nie powiodło się", it) }
            .getOrDefault(false)
        if (bound) {
            boundNetwork = network
            Log.i(tag, "Proces przypięty do sieci P2P")
        }
    }

    /** Zdejmuje przypięcie procesu do sieci P2P. */
    private fun unbindProcessFromNetwork() {
        if (boundNetwork == null) return
        runCatching { connectivityManager?.bindProcessToNetwork(null) }
            .onFailure { Log.w(tag, "Odpięcie od sieci nie powiodło się", it) }
        boundNetwork = null
        Log.d(tag, "Proces odpięty od sieci P2P")
    }

    /** Szuka sieci, której interfejs nazywa się `p2p...` - to grupa Wi-Fi Direct. */
    private fun findP2pNetwork(cm: ConnectivityManager): Network? = try {
        cm.allNetworks.firstOrNull { network ->
            cm.getLinkProperties(network)?.interfaceName?.startsWith("p2p") == true
        }
    } catch (e: Exception) {
        Log.w(tag, "Nie udało się wyszukać sieci P2P", e)
        null
    }

    /**
     * Czy radio Wi-Fi jest włączone.
     *
     * Wi-Fi Direct NIE włącza go samo, a od Androida 10 aplikacja nie może go
     * włączyć za użytkownika - zostaje poproszenie go wprost.
     */
    private fun isWifiEnabled(): Boolean =
        runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

    /**
     * Czy systemowa Lokalizacja jest włączona.
     *
     * Liczy się przełącznik, nie uprawnienie - patrz [WifiDirectDiagnosis].
     * Gdy nie da się tego sprawdzić, mówimy "włączona": lepiej pozwolić
     * spróbować niż zablokować działającą drogę fałszywym alarmem.
     */
    private fun isLocationEnabled(): Boolean = runCatching {
        val lm = locationManager ?: return@runCatching true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(true)

    // === Hotspot okularów (tryb AP) ===

    /**
     * Dołącza do hotspotu okularów po nazwie i haśle - BEZ Wi-Fi Direct.
     *
     * ## Czemu ta droga istnieje obok grupy P2P
     * Bo grupa P2P nigdy nie wstała. W sześciu rundach wykrywania telefon
     * widział wyłącznie cudze telewizory, a okularów ani razu. Aplikacja
     * producenta ma DWIE ścieżki importu galerii i ta druga - hotspot - nie
     * wymaga wykrywania niczego: nazwa sieci jest policzalna z nazwy i adresu
     * BLE (patrz [GlassesProtocol.glassesApSsid]), więc telefon łączy się
     * wprost, z pominięciem całego frameworku P2P.
     *
     * Sieć jest zgłaszana jako [WifiNetworkSpecifier], czyli połączenie
     * "tylko dla tej aplikacji": nie zmienia domyślnej sieci telefonu, nie
     * zapisuje się w ustawieniach i znika po [leaveAccessPoint]. Proces jest
     * do niej przypinany, bo inaczej ruch HTTP idzie dalej siecią domyślną i
     * do okularów nie dociera.
     *
     * @return `true` gdy telefon jest w sieci okularów i ruch idzie przez nią
     */
    suspend fun joinAccessPoint(ssid: String, password: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastFailure = "Łączenie z hotspotem okularów wymaga Androida 10 lub nowszego."
            Log.w(tag, "joinAccessPoint: API ${Build.VERSION.SDK_INT} za niskie")
            return false
        }
        val cm = connectivityManager ?: run {
            lastFailure = "Telefon nie udostępnia usługi sieciowej."
            return false
        }
        if (!isWifiEnabled()) {
            lastFailure = "Wi-Fi jest wyłączone - włącz je, żeby telefon mógł " +
                "połączyć się z okularami."
            return false
        }

        leaveAccessPoint()
        _state.value = TransferState.CONNECTING
        return joinAccessPointQ(cm, ssid, password)
    }

    /**
     * Właściwe dołączenie do hotspotu - osobno, bo [WifiNetworkSpecifier]
     * istnieje dopiero od Androida 10, a aplikacja wspiera 8.0.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun joinAccessPointQ(
        cm: ConnectivityManager,
        ssid: String,
        password: String
    ): Boolean {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        // Jeden transport, jeden specyfikator i nic więcej - dokładnie tak,
        // jak robi to aplikacja producenta. Domyślny `NetworkRequest.Builder`
        // NIE wymaga zdolności INTERNET, więc sieć bez internetu (a taka jest
        // każda sieć okularów) przechodzi.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val available = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.complete(network)
            }

            override fun onUnavailable() {
                available.complete(null)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Sieć okularów zniknęła")
            }
        }

        return try {
            cm.requestNetwork(request, callback)
            apCallback = callback
            Log.i(tag, "Czekam na hotspot okularów: $ssid")
            val network = withTimeoutOrNull(AP_JOIN_TIMEOUT_MS) { available.await() }
            if (network == null) {
                lastFailure = "Okulary nie wystawiły sieci \"$ssid\" albo telefon jej " +
                    "nie przyjął. Zdejmij okulary i załóż ponownie."
                releaseApCallback()
                _state.value = TransferState.FAILED
                false
            } else {
                val bound = runCatching { cm.bindProcessToNetwork(network) }
                    .onFailure { Log.w(tag, "bindProcessToNetwork nie powiodło się", it) }
                    .getOrDefault(false)
                if (bound) boundNetwork = network
                Log.i(tag, "Telefon w sieci okularów (przypięty=$bound)")
                _state.value = TransferState.CONNECTED
                true
            }
        } catch (e: Exception) {
            Log.e(tag, "joinAccessPoint nie powiodło się", e)
            lastFailure = "Nie udało się połączyć z siecią okularów: ${e.message}"
            releaseApCallback()
            _state.value = TransferState.FAILED
            false
        }
    }

    /** Opuszcza hotspot okularów i przywraca telefonowi domyślną sieć. */
    fun leaveAccessPoint() {
        unbindProcessFromNetwork()
        releaseApCallback()
    }

    private fun releaseApCallback() {
        val callback = apCallback ?: return
        apCallback = null
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
            .onFailure { Log.w(tag, "unregisterNetworkCallback nie powiodło się", it) }
    }

    /** Krótka pauza po zestawieniu grupy - serwer HTTP na okularach wstaje z opóźnieniem. */
    suspend fun awaitServerReady() {
        delay(SERVER_WARMUP_MS)
    }

    companion object {
        private const val TAG = "GlassesWifiTransfer"

        private const val DISCOVERY_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val SERVER_WARMUP_MS = 1_500L

        /**
         * Ile czekać, aż telefon wejdzie do hotspotu okularów.
         *
         * Dłużej niż przy zwykłej sieci: okulary podnoszą radio
         * dopiero po komendzie BLE, a użytkownik musi jeszcze
         * potwierdzić systemowe okienko "połączyć z siecią?".
         */
        private const val AP_JOIN_TIMEOUT_MS = 40_000L

        /**
         * Ile czekać na potwierdzenie usunięcia starej grupy P2P. Krótko:
         * to sprzątanie, nie warunek powodzenia - patrz [awaitGroupRemoved].
         */
        private const val GROUP_CLEANUP_TIMEOUT_MS = 2_000L
    }
}

/** Stan połączenia Wi-Fi Direct z okularami. */
enum class TransferState {
    IDLE,
    NO_PERMISSION,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    FAILED
}
