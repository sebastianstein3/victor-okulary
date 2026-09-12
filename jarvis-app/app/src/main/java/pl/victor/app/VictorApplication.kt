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
import pl.victor.app.wakeword.WakeWordDetector

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

    lateinit var wakeWordDetector: WakeWordDetector
        private set

    /**
     * Alternatywa dla Picovoice - bez konta i bez klucza. Tworzona zawsze,
     * bo sama w sobie nic nie robi, dopóki jej się nie wystartuje.
     */
    val voskWakeWord: pl.victor.app.wakeword.VoskWakeWord by lazy {
        pl.victor.app.wakeword.VoskWakeWord(this)
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
    val diag: pl.victor.app.diagnostics.DiagnosticLog by lazy {
        pl.victor.app.diagnostics.DiagnosticLog(this)
    }

    /**
     * Wersja aplikacji do nagłówka dziennika.
     *
     * Z PackageManagera, nie z `BuildConfig`: log z telefonu, którego nie mam,
     * jest bezwartościowy bez informacji, KTÓRA to wersja - a odczyt z pakietu
     * działa niezależnie od tego, jak zbudowano APK.
     */
    private fun appVersionLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("nieznana")

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Zapisuj nieobsłużone wyjątki do pliku - inaczej crash znika z procesem.
        pl.victor.app.utils.CrashReporter.install(this)

        settings = SettingsRepository.getInstance(this)
        if (settings.isDiagnosticLogEnabled()) {
            diag.startSession(
                listOf(
                    "V.I.C.T.O.R. - dziennik diagnostyczny",
                    "telefon: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
                    "aplikacja: ${appVersionLabel()}",
                    "dostawca AI: ${settings.getActiveProvider()}",
                    "transkrypcja w chmurze: ${settings.isCloudTranscriptionEnabled()}",
                    "mikrofon okularów: ${settings.isGlassesMicEnabled()}",
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
        wakeWordDetector = WakeWordDetector(this)
        photoStorage = PhotoStorage(this)
        audio = AudioManager.getInstance(this)
        orchestrator = AIOrchestrator(
            context = this,
            settings = settings,
            history = pl.victor.app.data.HistoryRepository(database.conversationDao()),
            wakeWord = wakeWordDetector
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

        // MODEL LOKALNY WCZYTUJEMY ZAWCZASU, NIE PRZY PIERWSZYM PYTANIU.
        //
        // Zgłoszone dwa razy: "model lokalny w ogóle nie działa". W dzienniku z
        // 13 września między "wysyłam pytanie dostawca=local" a "dostawca nie
        // odpowiedział w czasie" mija sto dziesięć sekund przy limicie
        // czterdziestu pięciu - czyli limit nie zdołał niczego przerwać, bo
        // wczytywanie modelu to blokujące wywołanie natywne.
        //
        // Pierwsze pytanie płaciło więc za wczytanie całego pliku modelu i z
        // tego powodu padało ZAWSZE, choć z odpowiadaniem nie miało to nic
        // wspólnego. Do drugiej próby rzadko kto dochodzi.
        //
        // Tylko gdy to model lokalny jest dostawcą: trzymanie go w pamięci
        // kosztuje kilkaset megabajtów, których nie ma po co zajmować komuś,
        // kto korzysta z chmury.
        if (settings.getActiveProvider() == LOCAL_PROVIDER_ID) {
            appScope.launch {
                val warmed = pl.victor.app.ai.LocalAIProvider(this@VictorApplication).warmUp()
                Log.i(
                    TAG,
                    if (warmed.isSuccess) "Model lokalny wczytany zawczasu"
                    else "Nie udało się wczytać modelu lokalnego zawczasu: " +
                        "${warmed.exceptionOrNull()?.message}"
                )
            }
        }

        // Jeśli wake word jest włączony - uruchom
        if (settings.isWakeWordEnabled()) {
            val accessKey = settings.getPicovoiceAccessKey()
            if (accessKey.isNotBlank()) {
                // initialize() jest suspend (ładuje model Porcupine), a onCreate nie jest
                // korutyną - uruchamiamy w tle, żeby nie blokować startu aplikacji.
                appScope.launch {
                    val entry = settings.getSelectedWakeWordEntry()
                    val result = wakeWordDetector.initialize(
                        accessKey = accessKey,
                        keyword = entry.porcupineKeyword ?: settings.getSelectedWakeWord(),
                        keywordPath = settings.getCustomKeywordPath(),
                        modelPath = settings.getCustomModelPath()
                    )
                    if (result.isSuccess) {
                        wakeWordDetector.startListening()
                        Log.i(TAG, "Wake word detector started (fraza: ${entry.phrase.ifBlank { entry.id }})")
                    } else {
                        // Świadomie NIE podmieniamy na inną frazę po cichu - jeśli
                        // "Hey Victor" wymaga jeszcze własnego modelu, aplikacja ma
                        // milczeć, a nie nasłuchiwać czegoś, czego użytkownik nie wybrał.
                        Log.w(TAG, "Wake word start przy uruchomieniu nieudany: ${result.message()}")
                    }
                }
            }
        }

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
        registerUnlockReceiver()

        Log.d(TAG, "VictorApplication initialized (HeyCyan SDK + DB + Discovery ready)")
    }

    /**
     * Czy nasłuch Voska jest w tej chwili uruchomiony.
     *
     * ## Dlaczego to musi być tutaj, a nie w MainActivity
     * Bo Vosk trzyma MIKROFON, a mikrofon jest wyłączny. Uruchomiony z ekranu
     * przeżywał jego zamknięcie i nadal zajmował mikrofon, więc systemowe
     * rozpoznawanie mowy nie miało skąd go wziąć - zgłoszone jako "AI mówi, że
     * nie może rozpoznać wiadomości z nagrania". Skoro więc coś ma nim
     * zarządzać przez całe życie procesu, to aplikacja, nie ekran.
     */
    @Volatile
    private var voskRunning = false

    /** Czy oddaliśmy mikrofon na czas tury i mamy go potem odzyskać. */
    @Volatile
    private var voskPausedForTurn = false

    fun startVoskListening() {
        if (voskRunning) return
        if (!voskWakeWord.isModelReady()) {
            Log.w(TAG, "Vosk: model nie jest pobrany - nasłuch nie startuje")
            return
        }
        val error = voskWakeWord.start(settings.getVoskPhrase()) {
            orchestrator.startVoiceQuestion()
        }
        voskRunning = error == null
        if (error != null) Log.w(TAG, "Vosk nie wystartował: $error") else Log.i(TAG, "Vosk słucha")
    }

    fun stopVoskListening() {
        voskWakeWord.stop()
        voskRunning = false
        voskPausedForTurn = false
    }

    /**
     * Oddaje mikrofon na czas tury.
     *
     * Bez tego Vosk i rozpoznawanie mowy biją się o to samo urządzenie, a
     * przegrywa zawsze to drugie - czyli właśnie pytanie użytkownika.
     */
    fun pauseVoskForTurn() {
        if (!voskRunning) return
        voskWakeWord.stop()
        voskRunning = false
        voskPausedForTurn = true
    }

    fun resumeVoskAfterTurn() {
        if (!voskPausedForTurn) return
        voskPausedForTurn = false
        startVoskListening()
    }

    /**
     * Przepisuje nagranie na tekst Voskiem - druga droga, gdy systemowa zawiedzie.
     *
     * Tu, a nie wprost w orkiestratorze, bo instancja Voska i jej model żyją w
     * aplikacji: wczytanie modelu kosztuje i nie ma sensu robić tego na turę.
     *
     * Nie rusza mikrofonu (pracuje na gotowym buforze), więc nie trzeba wokół tego
     * zawieszać nasłuchu frazy.
     *
     * @return tekst albo `null`, gdy modelu nie ma albo nic nie rozpoznał
     */
    suspend fun transcribeWithVosk(pcm: ByteArray, sampleRate: Int): String? =
        runCatching { voskWakeWord.transcribe(pcm, sampleRate) }
            .onFailure { Log.w(TAG, "Transkrypcja Voskiem nie powiodła się", it) }
            .getOrNull()

    /**
     * Uruchamia albo zatrzymuje Voska zgodnie z ustawieniami.
     *
     * Publiczne, bo wybór silnika i pobranie modelu dzieją się w ustawieniach,
     * a nie są strumieniem - bez wywołania stamtąd zmiana zaczynałaby działać
     * dopiero przy najbliższym połączeniu okularów.
     */
    fun refreshVosk() {
        val wanted = settings.wakeWordEnabledFlow.value &&
            settings.getWakeEngine() == pl.victor.app.data.SettingsRepository.WAKE_ENGINE_VOSK
        if (wanted) startVoskListening() else stopVoskListening()
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
        refreshVosk()
        val connectionState = glassesManager.connectionState.value
        val wakeWordOn = settings.wakeWordEnabledFlow.value
        val glassesActive = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.READY ||
            connectionState == ConnectionState.CONNECTING
        if (!glassesActive && !wakeWordOn) {
            VictorForegroundService.stop(this)
            return
        }
        // Prawdziwa aktywna fraza, nie nazwa apki - dopóki "Hey Victor" wymaga
        // własnego modelu, nasłuch faktycznie idzie na inne słowo (domyślnie
        // "Computer"), i powiadomienie ma o tym mówić prawdę.
        val phrase = settings.getSelectedWakeWordEntry().phrase.ifBlank { "wybraną frazę" }
        val reason = when {
            glassesActive && wakeWordOn -> "Połączony z okularami · nasłuchuję „$phrase”"
            glassesActive -> "Połączony z okularami"
            else -> "Nasłuchuję słowa „$phrase”"
        }
        VictorForegroundService.start(this, reason)
    }

    companion object {
        private const val TAG = "VictorApp"

        /** Identyfikator dostawcy „model lokalny" - patrz [pl.victor.app.ai.LocalAIProvider.id]. */
        private const val LOCAL_PROVIDER_ID = "local"

        private var instance: VictorApplication? = null

        fun get(): VictorApplication = instance
            ?: throw IllegalStateException("Application not initialized")
    }
}
