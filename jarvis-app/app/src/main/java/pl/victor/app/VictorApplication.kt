package pl.victor.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.launch
import pl.victor.app.audio.AudioManager
import pl.victor.app.ble.ConnectionState
import pl.victor.app.ble.VictorForegroundService
import pl.victor.app.ble.VictorManager
import pl.victor.app.data.AppDatabase
import pl.victor.app.data.ModelDiscoveryService
import pl.victor.app.data.SettingsRepository
import pl.victor.app.proactive.ProactiveAlertsScheduler
import pl.victor.app.storage.PhotoStorage

/**
 * Application class - inicjalizuje globalne zależności.
 */
class VictorApplication : Application() {

    /** Zakres dla zadań startowych, które muszą być korutynami. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    lateinit var settings: SettingsRepository
        private set

    lateinit var glassesManager: VictorManager
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var modelDiscovery: ModelDiscoveryService
        private set

    /**
     * Nasłuch "Hej Wiktor" na telefonie (openWakeWord). Tworzony leniwie, bo
     * sam w sobie nic nie robi, dopóki go nie wystartuje [refreshWake]; kto
     * wybrał frazę okularów, nie płaci nawet za wczytanie sieci.
     */
    val phoneWake: pl.victor.app.wakeword.OpenWakeWord by lazy {
        pl.victor.app.wakeword.OpenWakeWord(this).also { detector ->
            detector.onDiag = { message, fields ->
                runCatching { diag.event(pl.victor.app.diagnostics.DiagFormat.Phase.WAKE, message, fields) }
            }
            detector.onZakonczonySam = { błąd -> onWakeThreadDied(błąd) }
        }
    }

    /** Kolejne samoczynne zgony wątku nasłuchu - do odstępu ponowień. */
    private var wakeDeaths = 0

    /**
     * Wątek nasłuchu "Hej Wiktor" skończył się sam. Oznaczamy nasłuch jako
     * stojący i próbujemy go podnieść z rosnącym odstępem (5 s, 10 s, ... do
     * 2 min) - mikrofon bywa chwilowo zajęty albo serwer audio się restartuje.
     */
    private fun onWakeThreadDied(błąd: String?) {
        val opóźnienie = (5_000L shl minOf(wakeDeaths, 5)).coerceAtMost(120_000L)
        wakeDeaths++
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            synchronized(this) { wakeRunning = false }
            wakeLogStart = true
            wakeDiag(
                "nasłuch „Hej Wiktor” przerwał się sam - ponowię",
                mapOf("błąd" to błąd, "zaMs" to opóźnienie, "próba" to wakeDeaths)
            )
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            synchronized(this) { if (wakePauses.isEmpty()) refreshPhoneWake() }
        }, opóźnienie)
    }

    lateinit var photoStorage: PhotoStorage

    lateinit var audio: AudioManager
        private set

    lateinit var orchestrator: AIOrchestrator
        private set

    /**
     * Dziennik diagnostyczny - jeden na proces, żeby wiersze ze wszystkich
     * warstw (BLE, nasłuch, model, mowa) trafiały do JEDNEGO pliku w kolejności
     * zdarzeń. Rozbicie na osobne dzienniki per warstwa odebrałoby mu całą
     * wartość: szuka się w nim właśnie tego, co działo się MIĘDZY warstwami.
     */
    /** Alerty niewypowiedziane przy rozłączonych okularach - patrz [pl.victor.app.proactive.MissedAlerts]. */
    val missedAlerts = pl.victor.app.proactive.MissedAlerts()

    val diag: pl.victor.app.diagnostics.DiagnosticLog by lazy {
        pl.victor.app.diagnostics.DiagnosticLog(this)
    }

    /**
     * Licznik zużycia tokenów - patrz [pl.victor.app.ai.UsageMeter].
     *
     * Tutaj, obok dziennika, bo dotyczy tak samo każdej warstwy: zapytanie z
     * rozmowy, z trybu opisu otoczenia i z nawigacji płacone jest z tego
     * samego salda i ma się liczyć do jednej sumy.
     */
    val usage: pl.victor.app.ai.UsageMeter by lazy {
        pl.victor.app.ai.UsageMeter(this)
    }

    /**
     * Wersja aplikacji do nagłówka dziennika.
     *
     * Z PackageManagera, nie z `BuildConfig`: log z telefonu, którego nie mam,
     * jest bezwartościowy bez informacji, KTÓRA to wersja - a odczyt z pakietu
     * działa niezależnie od tego, jak zbudowano APK.
     *
     * Do tego numer biegu CI i skrót commita ([BuildConfig.BUILD_ID]), bo nazwa i
     * kod wersji są w każdym buildzie takie same - a pytanie "czy ta poprawka
     * jest już zainstalowana?" padało przy prawie każdym dzienniku.
     */
    private fun appVersionLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code), ${BuildConfig.BUILD_ID}"
    }.getOrDefault("nieznana")

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Zapisuj nieobsłużone wyjątki do pliku - inaczej crash znika z procesem.
        pl.victor.app.utils.CrashReporter.install(this)

        settings = SettingsRepository.getInstance(this)
        // PRZED nagłówkiem dziennika: ten wypisuje "dostawca AI", a u osoby,
        // która miała wybrany usunięty model lokalny, stałoby tam jeszcze
        // "local" - czyli dostawca, którego aplikacja już nie zna.
        val localModelCleanup = settings.removeLocalModelSettings(
            pl.victor.app.ai.AIProviderFactory.supportedProviders().map { it.id }
        )
        if (settings.isDiagnosticLogEnabled()) {
            diag.startSession(
                listOf(
                    "V.I.C.T.O.R. - dziennik diagnostyczny",
                    "telefon: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
                    "aplikacja: ${appVersionLabel()}",
                    "dostawca AI: ${settings.getActiveProvider()}",
                    "transkrypcja w chmurze: ${settings.isCloudTranscriptionEnabled()}",
                    // "przy starcie" nie jest ozdobnikiem. Nagłówek powstaje RAZ,
                    // w onCreate procesu, a te ustawienia zmieniają się potem -
                    // część z nich przestawia sama aplikacja w trakcie tury.
                    // Czytany jak stan bieżący, wyprowadził na manowce: dziennik
                    // mówił "mikrofon okularów: false", gdy w Ustawieniach stało
                    // "włączony". Stan BIEŻĄCY każdej tury jest w zdarzeniu
                    // NASŁUCH/start, a każde samoczynne przełączenie - w AUDIO.
                    "mikrofon okularów przy starcie: ${settings.isGlassesMicEnabled()}",
                    // Ten przełącznik decyduje, czy okulary w ogóle słuchają
                    // "Hey Lens". Zgłoszenie "przestały reagować na frazę"
                    // rozstrzygał dotąd dopiero wiersz WAKE "żądano=false" -
                    // czyli aplikacja SAMA kazała okularom nie słuchać.
                    "fraza wybudzenia w okularach: ${settings.isGlassesWakeWordEnabled()}",
                    // "Hej Wiktor" (nasłuch w telefonie) - włączony, wybrany silnik
                    // i czy wyłączyło go oszczędzanie baterii. Dziennik 22.11 nie
                    // miał o tym ani słowa, a nasłuch nie działał cały bieg.
                    "Hej Wiktor przy starcie: włączony=${settings.isWakeWordEnabled()} " +
                        "silnik=${settings.getWakeEngine()} " +
                        "wyłączyłaOszczędnośćBaterii=${settings.isWakeDisabledByPower()} " +
                        "mikrofonOkularów=${settings.isWakeWordOverGlassesMic()}",
                    // Prośba o ograniczenie myślenia zmienia KSZTAŁT zapytania do
                    // Gemini - a dziennik z biegu 152 to sześćdziesiąt odmów 400
                    // bez słowa o tym, co w zapytaniu było.
                    "ograniczenie myślenia: ${settings.isThinkingLimitEnabled()}",
                    "źródło zdjęcia: ${settings.getPhotoSource()} (dzielnik ${settings.getPhotoDivisor()})"
                )
            )
        }
        glassesManager = VictorManager.getInstance(this).also { manager ->
            // Tryb symulacji trzeba ustawić PRZED initialize() - decyduje o tym,
            // czy w ogóle ruszamy vendor SDK.
            if (settings.isGlassesSimulationEnabled()) {
                manager.setSimulationEnabled(true)
            }
            manager.initialize()
        }
        database = AppDatabase.getInstance(this)
        modelDiscovery = ModelDiscoveryService(settings)
        photoStorage = PhotoStorage(this)
        audio = AudioManager.getInstance(this)
        orchestrator = AIOrchestrator(
            context = this,
            settings = settings,
            history = pl.victor.app.data.HistoryRepository(database.conversationDao())
        )

        // Cleanup starych zdjęć (>30 dni)
        photoStorage.cleanupOldPhotos(30)

        // Włącz proaktywne alerty (pogoda + kalendarz) co 15 min
        if (settings.isProactiveAlertsEnabled()) {
            val interval = settings.getProactiveIntervalMinutes()
            ProactiveAlertsScheduler.enable(this, interval)
            Log.i(TAG, "Proaktywne alerty włączone (co $interval min)")
        }

        // Przy starcie - sprawdź nowe modele u aktywnego providera
        // (async, nie blokuje UI)
        modelDiscovery.checkActive()

        removeLocalModelLeftovers(localModelCleanup)

        // Nasłuch "Hej Wiktor" startuje przez refreshBackgroundService() niżej:
        // pierwszy odczyt każdego z obserwowanych StateFlow woła refreshPhoneWake().

        // Usługa pierwszoplanowa musi żyć dokładnie wtedy, gdy okulary są połączone
        // i/lub wake word nasłuchuje - inaczej Doze zabija BLE i AudioRecord kilka
        // minut po zgaszeniu ekranu. Nasłuch obu StateFlow zamiast wywołań rozsianych
        // po ViewModelach - każde miejsce, które zmienia jeden z tych dwóch stanów
        // (ustawienia, onboarding, automatyczny PowerManager, sam BLE), trafia tu
        // automatycznie, więc nie da się o tym zapomnieć w nowym miejscu w kodzie.
        appScope.launch {
            glassesManager.connectionState.collect { refreshBackgroundService() }
        }
        appScope.launch {
            settings.wakeWordEnabledFlow.collect { refreshBackgroundService() }
        }
        // Spotkanie, przewodnik i przypomnienia w miejscach też potrzebują usługi.
        appScope.launch {
            pl.victor.app.features.BackgroundFeatures.potrzebne.collect { refreshBackgroundService() }
        }
        registerUnlockReceiver()

        Log.d(TAG, "VictorApplication initialized (HeyCyan SDK + DB + Discovery ready)")
    }

    /**
     * Usuwa z pamięci aplikacji plik usuniętego modelu lokalnego (circa 560 MB)
     * i kanał powiadomień jego pobierania - patrz [pl.victor.app.data.LocalModelLeftovers].
     *
     * Przy każdym starcie, ale w praktyce raz: po pierwszym przebiegu katalogu
     * już nie ma, a sprawdzenie, czy istnieje, nic nie kosztuje. Na wątku IO,
     * bo kasowanie pół gigabajta na wolnej pamięci flash potrafi chwilę trwać,
     * a start aplikacji nie ma na to czekać.
     *
     * @param settingsCleanup wynik sprzątania ustawień - trafia do tego samego
     *   wpisu w dzienniku, żeby całe sprzątanie było w jednym wierszu
     */
    private fun removeLocalModelLeftovers(
        settingsCleanup: pl.victor.app.data.LocalModelLeftovers.Cleanup?
    ) {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(filesDir, pl.victor.app.data.LocalModelLeftovers.MODELS_DIR)
            val bytes = if (dir.exists()) {
                dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            } else 0L
            val fileRemoved = dir.exists() && dir.deleteRecursively()
            val channelRemoved = runCatching {
                val nm = getSystemService(android.app.NotificationManager::class.java)
                // minSdk 26, więc kanały powiadomień są zawsze dostępne.
                if (nm?.getNotificationChannel(pl.victor.app.data.LocalModelLeftovers.DOWNLOAD_CHANNEL_ID) != null) {
                    nm.deleteNotificationChannel(pl.victor.app.data.LocalModelLeftovers.DOWNLOAD_CHANNEL_ID)
                    true
                } else false
            }.getOrDefault(false)
            if (settingsCleanup == null && !fileRemoved && !channelRemoved && !dir.exists()) return@launch
            Log.i(TAG, "Sprzątanie po modelu lokalnym: plik=$fileRemoved ($bytes B), kanał=$channelRemoved")
            runCatching {
                diag.event(
                    pl.victor.app.diagnostics.DiagFormat.Phase.SESJA,
                    "usunięte pozostałości modelu lokalnego",
                    mapOf(
                        "nowyDostawca" to settingsCleanup?.newProvider,
                        "kluczeUstawień" to settingsCleanup?.removedKeys?.joinToString(",")?.ifBlank { null },
                        "plikUsunięty" to fileRemoved,
                        "zwolnioneMB" to (bytes / 1_000_000).takeIf { fileRemoved },
                        // Prawda tylko, gdy kasowanie się nie udało - wtedy
                        // spróbujemy znowu przy następnym starcie.
                        "plikZostał" to dir.exists().takeIf { it },
                        "kanałPowiadomień" to channelRemoved.takeIf { it }
                    )
                )
            }
        }
    }

    /**
     * Czy nasłuch "Hej Wiktor" jest w tej chwili uruchomiony.
     *
     * ## Dlaczego to musi być tutaj, a nie w MainActivity
     * Bo nasłuch trzyma MIKROFON, a mikrofon jest wyłączny. Uruchomiony z ekranu
     * przeżywał jego zamknięcie i nadal zajmował mikrofon, więc systemowe
     * rozpoznawanie mowy nie miało skąd go wziąć - zgłoszone jako "AI mówi, że
     * nie może rozpoznać wiadomości z nagrania". Skoro więc coś ma nim
     * zarządzać przez całe życie procesu, to aplikacja, nie ekran.
     */
    @Volatile
    private var wakeRunning = false

    /**
     * Kto w tej chwili potrzebuje mikrofonu frazy dla siebie - patrz
     * [pauseWakeForTurn]. Zbiór, nie flaga: tura (orkiestrator) i tryb
     * konwersacyjny oddają mikrofon niezależnie, i koniec jednego nie może
     * wznowić nasłuchu w trakcie drugiego.
     */
    private val wakePauses = mutableSetOf<String>()

    /** Ostatni zapisany do dziennika powód, dla którego nasłuch nie ruszył. */
    private var lastWakeStartError: String? = null

    @Synchronized
    fun startPhoneWake() {
        if (wakeRunning) return
        // Mikrofon jest teraz czyjś (tura, spotkanie, tłumaczenie ze słuchu) -
        // nasłuch wróci sam przy [resumeWakeAfterTurn]. Bez tego zmiana
        // połączenia okularów w trakcie spotkania podbierała mu mikrofon.
        if (wakePauses.isNotEmpty()) return
        val error = phoneWake.start { orchestrator.startVoiceQuestion() }
        wakeRunning = error == null
        if (error == null) {
            lastWakeStartError = null
            Log.i(TAG, "Nasłuch „Hej Wiktor” uruchomiony")
            // Raz po starcie aplikacji i po każdym wyłączeniu - nie po każdej
            // turze (tura tylko pożycza mikrofon, to nie zdarzenie).
            if (wakeLogStart) {
                wakeLogStart = false
                wakeDiag("nasłuch „Hej Wiktor” uruchomiony")
            }
        } else if (error != lastWakeStartError) {
            // Raz na powód, nie przy każdym odświeżeniu - brak modelu to stan,
            // a nie zdarzenie, i zalałby dziennik.
            lastWakeStartError = error
            Log.w(TAG, "Nasłuch „Hej Wiktor” nie ruszył: $error")
            runCatching {
                diag.event(
                    pl.victor.app.diagnostics.DiagFormat.Phase.WAKE,
                    "nasłuch „Hej Wiktor” nie ruszył",
                    mapOf("powód" to error)
                )
            }
        }
    }

    @Synchronized
    fun stopPhoneWake() {
        if (!wakeRunning) return
        phoneWake.stop()
        wakeRunning = false
        wakeLogStart = true
        wakeDiag("nasłuch „Hej Wiktor” zatrzymany")
    }

    /** Czy następny udany start nasłuchu zapisać w dzienniku. */
    private var wakeLogStart = true

    /** Start/stop nasłuchu w dzienniku - bez tego "Hej Wiktor nie działa" nie miało śladu. */
    private fun wakeDiag(opis: String, pola: Map<String, Any?> = emptyMap()) {
        runCatching {
            diag.event(
                pl.victor.app.diagnostics.DiagFormat.Phase.WAKE,
                opis,
                pola + mapOf("czekająNaMikrofon" to wakePauses.joinToString(",").ifEmpty { null })
            )
        }
    }

    /**
     * Oddaje mikrofon na czas tury (albo spotkania, tłumaczenia ze słuchu,
     * nasłuchu trybu konwersacyjnego).
     *
     * Bez tego nasłuch frazy i rozpoznawanie mowy biją się o to samo
     * urządzenie, a przegrywa zawsze to drugie - czyli właśnie pytanie
     * użytkownika. Wołanie kilka razy z rzędu przez tego samego [kto] jest
     * bezpieczne; mikrofon wraca dopiero, gdy odda go KAŻDY, kto go wziął.
     */
    @Synchronized
    fun pauseWakeForTurn(kto: String = WAKE_HOLDER_TURN) {
        wakePauses.add(kto)
        if (!wakeRunning) return
        phoneWake.stop()
        wakeRunning = false
    }

    /** Odwrotność [pauseWakeForTurn]; nasłuch wraca, jeśli ustawienia go chcą. */
    @Synchronized
    fun resumeWakeAfterTurn(kto: String = WAKE_HOLDER_TURN) {
        if (!wakePauses.remove(kto)) return
        if (wakePauses.isEmpty()) refreshPhoneWake()
    }

    /** Startuje albo zatrzymuje nasłuch zgodnie z ustawieniami. */
    @Synchronized
    private fun refreshPhoneWake() {
        if (settings.isPhoneWakeWanted()) startPhoneWake() else stopPhoneWake()
    }

    /**
     * Uruchamia od nowa, gdy działa - po zmianie ustawienia, które nasłuch
     * czyta przy starcie (np. mikrofon okularów).
     */
    @Synchronized
    fun restartPhoneWake() {
        if (!wakeRunning) return
        stopPhoneWake()
        startPhoneWake()
    }

    /**
     * Uruchamia albo zatrzymuje nasłuch "Hej Wiktor" i odświeża usługę w tle.
     *
     * Publiczne, bo wybór frazy dzieje się w ustawieniach i nie jest
     * strumieniem - bez wywołania stamtąd zmiana zaczynałaby działać dopiero
     * przy najbliższym połączeniu okularów.
     */
    fun refreshWake() {
        refreshBackgroundService()
    }

    /**
     * Odświeża usługę w tle w chwili ODBLOKOWANIA ekranu.
     *
     * ## Po co osobny odbiornik
     * Od Androida 14 typ usługi `microphone` wolno wziąć tylko wtedy, gdy aplikacja
     * ma w danej chwili prawo nagrywać - czyli przy odblokowanym ekranie. Usługa,
     * która wystartowała przy zablokowanym telefonie (po restarcie, po ponownym
     * połączeniu okularów w kieszeni), dostaje wtedy sam `connectedDevice`:
     * BLE działa, ale nasłuch frazy nie ma prawa do mikrofonu.
     *
     * Bez tego odbiornika taki stan trwałby aż do następnej zmiany połączenia albo
     * przełączenia frazy - czyli w praktyce godzinami. `ACTION_USER_PRESENT` to
     * dokładnie ta chwila, w której warunek systemu jest spełniony, więc usługa
     * podnosi wtedy swój typ i mikrofon wraca.
     */
    private fun registerUnlockReceiver() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                Log.i(TAG, "Ekran odblokowany - odświeżam usługę w tle")
                refreshBackgroundService()
            }
        }
        runCatching {
            registerReceiver(receiver, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT))
        }.onFailure { Log.w(TAG, "Nie udało się nasłuchiwać odblokowania ekranu", it) }
    }

    private fun refreshBackgroundService() {
        refreshPhoneWake()
        val connectionState = glassesManager.connectionState.value
        // Usługa z mikrofonem tylko wtedy, gdy telefon naprawdę ma słuchać -
        // przy frazie okularów nasłuch telefonu stoi, więc nie ma czego chronić.
        val wakeWordOn = settings.isPhoneWakeWanted()
        val glassesActive = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.READY ||
            connectionState == ConnectionState.CONNECTING
        val featuresOn = pl.victor.app.features.BackgroundFeatures.potrzebne.value
        val reconnecting = glassesManager.isReconnecting
        if (!glassesActive && !wakeWordOn && !featuresOn && !reconnecting) {
            VictorForegroundService.stop(this)
            return
        }
        val phrase = "Hej Wiktor"
        val reason = when {
            glassesActive && wakeWordOn -> "Połączony z okularami · nasłuchuję „$phrase”"
            glassesActive -> "Połączony z okularami"
            reconnecting && !wakeWordOn && !featuresOn -> "Łączę z okularami..."
            wakeWordOn -> "Nasłuchuję słowa „$phrase”"
            else -> "Działa: " + pl.victor.app.features.BackgroundFeatures.nazwy().joinToString(", ")
        }
        VictorForegroundService.start(this, reason)
    }

    companion object {
        private const val TAG = "VictorApp"

        /** Właściciel mikrofonu frazy: tura orkiestratora - patrz [pauseWakeForTurn]. */
        const val WAKE_HOLDER_TURN = "tura"

        /** Właściciel mikrofonu frazy: nasłuch trybu konwersacyjnego. */
        const val WAKE_HOLDER_CONVERSATION = "rozmowa"

        /** Właściciel mikrofonu frazy: rozmowa na żywo z asystentem (Gemini Live). */
        const val WAKE_HOLDER_LIVE = "rozmowa na żywo"

        /** Właściciel mikrofonu frazy: nauka głosu "Hej Wiktor" w Ustawieniach. */
        const val WAKE_HOLDER_NAUKA_GLOSU = "nauka głosu"

        private var instance: VictorApplication? = null

        fun get(): VictorApplication = instance
            ?: throw IllegalStateException("Application not initialized")
    }
}
