package pl.victor.app.accessibility

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.ai.ProviderFailure
import pl.victor.app.audio.AudioManager
import pl.victor.app.data.HistoryRepository
import pl.victor.app.stream.YuvFrame
import pl.victor.app.vision.OCRReader
import pl.victor.app.vision.OCRResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility Service - funkcje dla niewidomych i słabowidzących.
 *
 * 3 tryby:
 * 1. READ_TEXT (czytaj) - czyta tekst z otoczenia (OCR + TTS)
 * 2. DESCRIBE_SCENE (opisuj) - opisuje co przed userem (capture + AI co X sekund)
 * 3. NAVIGATE (nawiguj) - ostrzega o przeszkodach + prowadzi
 *
 * Sterowanie głosowe:
 * - "V.I.C.T.O.R., czytaj" → READ_TEXT
 * - "V.I.C.T.O.R., opisz" → DESCRIBE_SCENE
 * - "V.I.C.T.O.R., prowadź" → NAVIGATE
 * - "V.I.C.T.O.R., stop" → kończy tryb
 * - "V.I.C.T.O.R., co przede mną" → jednorazowy opis
 *
 * Dźwiękowe sygnały:
 * - Nowa scena wykryta (inny obraz) → krótki "bip"
 * - Wykryto tekst → 2 krótkie "bip"
 * - Wykryto twarz → długi "bip"
 * - Niebezpieczeństwo (w trybie NAVIGATE) → ciągły sygnał
 */
class AccessibilityService(
    private val audio: AudioManager,
    private val ocrReader: OCRReader,
    private val glassesManager: pl.victor.app.ble.VictorManager,
    private val onDescribeScene: suspend (ByteArray) -> String,
    private val onNavigate: suspend (ByteArray) -> String
) {
    private val tag = "AccessibilityService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _mode = MutableStateFlow<AccessibilityMode>(AccessibilityMode.OFF)
    val mode: StateFlow<AccessibilityMode> = _mode.asStateFlow()

    private val _lastDescription = MutableStateFlow<String?>(null)
    val lastDescription: StateFlow<String?> = _lastDescription.asStateFlow()

    private val active = AtomicBoolean(false)

    /**
     * Prośby o kolejne czytanie - patrz [readTextLoop].
     *
     * CONFLATED, bo nadmiarowe naciśnięcia mają się scalić: trzy kliknięcia pod
     * rząd znaczą "czytaj", a nie "czytaj trzy razy".
     */
    private val readRequests =
        kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var workerJob: Job? = null

    // Konfiguracja
    /** Przerwa MIĘDZY opisami, liczona od końca mówienia - patrz [describeSceneLoop]. */
    var describeIntervalMs: Long = 10_000L
    var navigateIntervalMs: Long = 1_500L      // co 1.5s sprawdzenie
    var readPageTimeoutMs: Long = 10_000L      // ile czekamy aż user przewróci stronę

    /**
     * Włącza tryb czytania tekstu.
     * User klika "czytaj" - apka czeka aż wykryje tekst i czyta go.
     */
    fun enableReadText() {
        if (_mode.value != AccessibilityMode.OFF) return
        Log.i(tag, "Tryb READ_TEXT włączony")
        _mode.value = AccessibilityMode.READ_TEXT
        active.set(true)
        playBeep(BeepType.MODE_CHANGED)
        audio.speak("Tryb czytania włączony. Skieruj okulary na tekst.", language = "pl")
        resetHealth()
        workerJob = scope.launch {
            startLiveVisionOrExplain()
            readTextLoop()
        }
    }

    /**
     * Włącza tryb opisu otoczenia.
     * Apka co X sekund robi zdjęcie i opisuje co widzi.
     */
    fun enableDescribeScene() {
        if (_mode.value != AccessibilityMode.OFF) return
        Log.i(tag, "Tryb DESCRIBE_SCENE włączony")
        _mode.value = AccessibilityMode.DESCRIBE_SCENE
        active.set(true)
        playBeep(BeepType.MODE_CHANGED)
        audio.speak("Tryb opisu włączony. Będę Ci mówił co widzisz.", language = "pl")
        resetHealth()
        workerJob = scope.launch {
            startLiveVisionOrExplain()
            describeSceneLoop()
        }
    }

    /**
     * Włącza tryb nawigacji.
     * Ciągłe sprawdzanie otoczenia, ostrzeganie o przeszkodach.
     */
    fun enableNavigate() {
        if (_mode.value != AccessibilityMode.OFF) return
        Log.i(tag, "Tryb NAVIGATE włączony")
        _mode.value = AccessibilityMode.NAVIGATE
        active.set(true)
        playBeep(BeepType.MODE_CHANGED)
        audio.speak("Tryb nawigacji włączony. Uważaj - będę Cię prowadził.", language = "pl")
        resetHealth()
        workerJob = scope.launch {
            startLiveVisionOrExplain()
            navigateLoop()
        }
    }

    /**
     * Podnosi strumień klatek na czas trwania trybu - i mówi, gdy się nie uda.
     *
     * ## Czemu przy WŁĄCZANIU trybu, a nie przy pierwszym obrocie pętli
     * Bo podniesienie kosztuje jednorazowo circa 8,4 s i lepiej, żeby zeszło
     * razem z zapowiedzią głosową, niż żeby pierwszy opis przyszedł
     * osiem sekund po tym, jak użytkownik już czeka.
     *
     * ## Czemu niepowodzenie NIE kończy trybu
     * Bo droga przez zdjęcie dalej działa - jest wolniejsza i daje gorszy
     * obraz, ale działa. Zamknięcie trybu dlatego, że nie udało się go
     * PRZYSPIESZYĆ, byłoby gorsze niż jego zwolnienie.
     */
    private suspend fun startLiveVisionOrExplain() {
        if (glassesManager.isLiveVisionRunning) return
        val ok = runCatching { glassesManager.startLiveVision() }.getOrDefault(false)
        if (!ok) {
            Log.w(tag, "Strumień klatek nie ruszył - zostaję przy zdjęciach")
        }
    }

    /**
     * Wyłącza aktywny tryb.
     */
    fun disable(reason: String = "user") {
        if (_mode.value == AccessibilityMode.OFF) return
        Log.i(tag, "Tryb ${_mode.value} wyłączony: $reason")
        _mode.value = AccessibilityMode.OFF
        active.set(false)
        workerJob?.cancel()
        workerJob = null
        // Strumień ZAWSZE gaśnie z trybem. Zostawiony trzymałby okulary w
        // trybie podglądu - a te odmawiają wejścia w niego ponownie - i jadłby
        // ich baterię w tle, czego użytkownik nie ma jak zauważyć.
        glassesManager.stopLiveVision()
        playBeep(BeepType.MODE_CHANGED)
        audio.speak("Tryb wyłączony", language = "pl")
    }

    /**
     * Ile razy dana awaria wystąpiła z rzędu. Zero znaczy "następną zgłoś".
     *
     * ## Dlaczego to w ogóle istnieje
     * Wszystkie trzy tryby dostępności działają w pętli i po każdym
     * niepowodzeniu po prostu leciały dalej. Dla osoby widzącej to niezauważalne
     * opóźnienie; dla niewidomej jedynym objawem jest CISZA - nie do odróżnienia
     * od niedziałającej aplikacji. Zgłoszono to dwa razy: raz jako "asystent
     * niewidomych w ogóle nie działa", raz jako "słychać, że okulary robią
     * zdjęcia, ale AI nic nie mówi".
     */
    private val failureCounters = mutableMapOf<String, Int>()

    /**
     * Powód, dla którego dalsze pytanie modelu NIE MA SENSU - albo `null`.
     *
     * Ustawiany tylko przy awariach trwałych (puste konto, odrzucony klucz);
     * patrz [ProviderFailure.isPermanent]. Zerwana sieć tu nie trafia, bo sieć
     * wraca sama i ponawianie jest wtedy właściwym zachowaniem.
     */
    @Volatile
    private var modelDead: String? = null

    /** Ile zapytań do modelu nie udało się z rzędu - do odczekania. */
    @Volatile
    private var modelFailures = 0

    /** Ile udanych zapytań od włączenia trybu - do zapisania tempa w dzienniku. */
    @Volatile
    private var modelCalls = 0

    /** Kasuje ślady poprzedniego trybu. Bez tego wczorajsza awaria gasi dzisiejszy tryb. */
    private fun resetHealth() {
        modelDead = null
        modelFailures = 0
        modelCalls = 0
        failureCounters.clear()
        runCatching { pl.victor.app.VictorApplication.get().usage.resetRate() }
    }

    /**
     * Ile DODATKOWO odczekać po nieudanych zapytaniach.
     *
     * ## Czemu to nie jest kosmetyka
     * Bo pętla trybu ciągłego pyta model co półtorej sekundy i po awarii leciała
     * dalej w tym samym tempie. Przy zerwanej sieci znaczyło to czterdzieści
     * nieudanych zapytań na minutę - obciążenie łącza i baterii za nic, w
     * sytuacji, w której i tak nic nie zadziała.
     *
     * Rośnie dwukrotnie i ma sufit: awaria przejściowa ma zostać zauważona
     * szybko, gdy minie, a nie po kwadransie ciszy.
     */
    private fun failureBackoffMs(): Long {
        if (modelFailures <= 0) return 0L
        val shift = (modelFailures - 1).coerceAtMost(BACKOFF_MAX_SHIFT)
        return (BACKOFF_STEP_MS shl shift).coerceAtMost(BACKOFF_CEILING_MS)
    }

    /**
     * Kończy tryb, gdy dalsze pytanie modelu nie ma sensu.
     *
     * ## Czemu wyłączenie, a nie samo milczenie
     * Bo przy pustym koncie pętla dobijała się do serwera co półtorej sekundy w
     * nieskończoność, a użytkownik słyszał w kółko ten sam komunikat i nie miał
     * skąd wiedzieć, że to nie minie samo. Tryb, który nie może działać, ma to
     * powiedzieć RAZ i się skończyć - wtedy widać, że trzeba coś zrobić.
     *
     * @return `true`, gdy tryb został zakończony
     */
    private fun stopIfModelDead(): Boolean {
        val reason = modelDead ?: return false
        audio.speak(reason, language = "pl")
        disable(reason = "model trwale niedostępny")
        return true
    }

    /**
     * Zapisuje w dzienniku, ile ten tryb naprawdę pali.
     *
     * ## Po co
     * Bo to jest ta liczba, której do tej pory nie było, a bez której nie da
     * się uczciwie ustawić żadnego limitu. Zapytanie o obraz to zmierzone circa
     * 1600 tokenów; ile ich wychodzi na minutę, zależy od tempa pętli, czasu
     * odpowiedzi modelu i tego, jak często zmienia się scena - czyli od rzeczy,
     * których nie policzę z kodu. Dziennik policzy je na sprzęcie.
     */
    private fun logUsageRate() {
        if (modelCalls == 0 || modelCalls % RATE_REPORT_EVERY != 0) return
        runCatching {
            val app = pl.victor.app.VictorApplication.get()
            // Bez wyjścia z funkcji spod `runCatching`: wyjście spod wbudowanej
            // lambdy jest legalne, ale to jest dokładnie ta subtelność, na
            // której nie chcę opierać buildu trwającego siedem minut.
            val rate = app.usage.ratePerMinute()
            val day = app.usage.todayAsOf()
            if (rate != null) app.diag.event(
                pl.victor.app.diagnostics.DiagFormat.Phase.MODEL,
                "TEMPO trybu ${_mode.value.displayName}",
                mapOf(
                    "tokenówNaMinutę" to rate,
                    "zapytańWTrybie" to modelCalls,
                    "tokenówDziś" to day.tokens,
                    "zapytańDziś" to day.requests
                )
            )
        }
    }


    /**
     * Mówi o awarii - ale przy trwałej usterce nie za każdym obrotem pętli,
     * bo to zamieniłoby pomoc w hałas.
     */
    private fun reportFailure(key: String, message: String) {
        val seen = failureCounters[key] ?: 0
        if (seen == 0) audio.speak(message, language = "pl")
        failureCounters[key] = (seen + 1) % FAILURES_BETWEEN_REPORTS
    }

    /** Po udanym obrocie kasujemy licznik - następna awaria ma być słyszalna. */
    private fun clearFailure(key: String) {
        failureCounters[key] = 0
    }

    /**
     * Robi zdjęcie, a gdy się nie uda - MÓWI, czemu.
     *
     * @param sharp czy potrzebny jest ORYGINAŁ z pamięci okularów zamiast
     *   miniatury. Kosztuje kilkanaście sekund (Wi-Fi Direct), więc używa go
     *   tylko czytanie tekstu - i dopiero wtedy, gdy miniatura nie wystarczyła.
     */
    private suspend fun capturePhotoOrExplain(sharp: Boolean = false): ByteArray? {
        // KLATKA ZE STRUMIENIA MA PIERWSZEŃSTWO - gdy strumień stoi.
        //
        // Różnica nie jest kosmetyczna. Dotąd opis otoczenia dostawał miniaturę
        // 9 KB, bo pełne zdjęcie kosztuje kilkanaście sekund przez Wi-Fi
        // Direct. Klatka ma 1600x1200, jest od ręki i nie wymaga migawki -
        // więc pamięć okularów przestaje się zapełniać przy każdym obrocie
        // pętli.
        //
        // `sharp` znaczy tu to samo co przy zdjęciu: potrzebny SZCZEGÓŁ, czyli
        // litery. Tam kosztuje kilkanaście sekund, tutaj jedną klatkę.
        glassesManager.liveFrame(detail = sharp)?.let { frame ->
            clearFailure(FAILURE_PHOTO)
            return frame
        }

        val photo = if (sharp) {
            glassesManager.captureSharpPhoto()
        } else {
            glassesManager.capturePhoto()
        }
        if (photo != null) {
            clearFailure(FAILURE_PHOTO)
            return photo
        }
        reportFailure(
            FAILURE_PHOTO,
            "Nie mam obrazu z okularów. " +
                (glassesManager.lastPhotoFailure ?: "Okulary nie przysłały zdjęcia.")
        )
        return null
    }

    /**
     * Pyta model o opis i MÓWI, gdy zapytanie się nie uda.
     *
     * Bez tego każdy błąd sieci, brak klucza API i limit u dostawcy kończyły
     * się wpisem w dzienniku i ciszą - a użytkownik słyszał tylko migawkę
     * okularów i nic więcej.
     */
    private suspend fun askOrExplain(
        photo: ByteArray,
        key: String,
        ask: suspend (ByteArray) -> String
    ): String? = try {
        val answer = ask(photo)
        clearFailure(key)
        modelFailures = 0
        modelCalls++
        logUsageRate()
        answer.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e(tag, "Zapytanie do modelu nie powiodło się", e)
        modelFailures++
        // ProviderFailure zamiast doklejania treści wyjątku: to jest klasa
        // zrobiona dokładnie do tego i mówi, CO ZROBIĆ ("konto nie ma
        // środków - sprawdź w ustawieniach"), a nie jak brzmi odpowiedź HTTP.
        // Surowa treść zostaje w dzienniku, gdzie się przydaje.
        val spoken = ProviderFailure.describe(e.message)
        if (ProviderFailure.isPermanent(e.message)) modelDead = spoken
        reportFailure(key, spoken)
        null
    }

    /**
     * Jednorazowy opis sceny (komenda "co przede mną").
     */
    suspend fun describeOnce(): String? {
        val photo = capturePhotoOrExplain() ?: return null
        return askOrExplain(photo, FAILURE_DESCRIBE, onDescribeScene)
    }

    /**
     * Loop dla trybu czytania tekstu.
     *
     * ## Dwa podejścia, nie jedno
     * Najpierw miniatura po BLE - jest w sekundę i do dużego druku (szyld,
     * nagłówek, tablica) w zupełności wystarcza. Dopiero gdy OCR nic na niej nie
     * znajdzie, sięgamy po ORYGINAŁ przez Wi-Fi Direct: kilkanaście sekund, ale
     * to jedyna droga do drobnego druku. Zgłoszone jako "AI nie potrafi
     * rozczytać większości tekstu ze zdjęć" - bo do tej pory istniała tylko
     * miniatura i nic poza nią.
     */
    /**
     * Czytanie NA ŻĄDANIE, a nie w kółko.
     *
     * ## Dlaczego pętla była złym pomysłem
     * Poprzednia wersja robiła zdjęcie co półtorej sekundy przez cały czas
     * trwania trybu. Zgłoszone wprost: "czytanie powinno robić zdjęcie, gdy mu
     * każemy, bo jeśli robi ciągle, to jest bez sensu - przecież ktoś mógł
     * jeszcze nie zmienić strony".
     *
     * To nie była tylko strata baterii. Każde zdjęcie zajmuje okulary i łącze
     * BLE, więc czytanie samo sobie przeszkadzało, a użytkownik nie miał wpływu
     * na moment, w którym asystent patrzy.
     *
     * Teraz jedno czytanie następuje od razu po włączeniu trybu (bo po to się go
     * włącza), a każde następne dopiero na [requestRead] - z przycisku okularów,
     * z komendy głosowej albo z aplikacji.
     */
    private suspend fun readTextLoop() {
        readOnce()
        while (active.get()) {
            // Zawieszamy się tu do skutku - żadnego odpytywania, żadnego zdjęcia
            // "na wszelki wypadek".
            readRequests.receive()
            if (!active.get()) break
            readOnce()
        }
    }

    /** Jedno spojrzenie na tekst: zdjęcie, rozpoznanie, przeczytanie na głos. */
    private suspend fun readOnce() {
        try {
            // PRZY STRUMIENIU IDZIEMY OD RAZU PO SZCZEGÓŁ.
            //
            // Dwa podejścia - najpierw miniatura, potem oryginał - miały sens,
            // dopóki oryginał kosztował kilkanaście sekund przez Wi-Fi Direct.
            // Ze strumienia pełna klatka jest po JEDNEJ KLATCE, circa 33 ms, a
            // litery z odległości to dokładnie ten przypadek, w którym
            // pomniejszony obraz nie wystarcza.
            //
            // Odpada przy tym "Przyglądam się dokładniej" i czekanie na
            // wypowiedzenie tego zdania - kilka sekund ciszy za nic.
            val haveStream = glassesManager.isLiveVisionRunning
            var ocr: OCRResult? = capturePhotoOrExplain(sharp = haveStream)
                ?.let { ocrReader.readBytes(it) }

            if (!haveStream && (ocr?.isSuccess != true || ocr.fullText.isBlank())) {
                if (!active.get()) return
                Log.i(tag, "Miniatura bez tekstu - biorę zdjęcie w pełnej jakości")
                audio.speakAndAwait("Przyglądam się dokładniej.", language = "pl")
                ocr = capturePhotoOrExplain(sharp = true)?.let { ocrReader.readBytes(it) }
            }

            // KOLEJNOŚĆ, NIE SUROWY fullText.
            //
            // ML Kit oddaje bloki z grubsza od góry do dołu, a na tabliczce,
            // plakacie albo opakowaniu najważniejszy jest napis NAJWIĘKSZY -
            // i ten stoi gdzie indziej niż najwyżej. Zgłoszone z terenu:
            // "musi zaczynać od tego, co największe i najważniejsze, ale potem
            // niech czyta dalej". Nic nie wypada, to jest przestawienie.
            val newText = ocr?.let {
                pl.victor.app.vision.ReadingOrder.arrange(it.blocks, it.fullText)
            }?.trim().orEmpty()
            if (ocr?.isSuccess == true && newText.length > MIN_READABLE_TEXT) {
                clearFailure(FAILURE_NO_TEXT)
                Log.d(tag, "Odczytany tekst: ${newText.length} znaków")
                playBeep(BeepType.TEXT_DETECTED)
                _lastDescription.value = newText
                // speakAndAwait, nie speak: przy czytaniu na żądanie użytkownik
                // może poprosić o kolejną stronę zaraz po ostatnim słowie, a
                // dwa czytania naraz są nie do słuchania.
                audio.speakAndAwait(newText, language = "pl")
            } else {
                reportFailure(
                    FAILURE_NO_TEXT,
                    "Nie widzę tu tekstu. Skieruj okulary prosto na napis " +
                        "i przybliż się."
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Czytanie nie powiodło się", e)
            reportFailure(FAILURE_READ, "Czytanie się nie powiodło. Powiedz \"czytaj\", spróbuję znowu.")
        }
    }

    /**
     * Loop dla trybu opisu sceny.
     */
    /**
     * Opis otoczenia: zdjęcie, opis, przerwa - i dopiero wtedy następne zdjęcie.
     *
     * ## Co było nie tak
     * Dwie rzeczy naraz, a obie dawały ten sam objaw: "gdy ma opisywać, co jest
     * przede mną, robi bardzo dużo zdjęć".
     *
     * Po pierwsze, odstęp liczył się od ZROBIENIA zdjęcia, nie od skończenia
     * mówienia. Opis potrafi trwać dłużej niż odstęp, więc kolejne zdjęcia
     * ustawiały się w kolejce, zanim użytkownik usłyszał poprzedni.
     *
     * Po drugie, warunek "nowa scena" porównywał SUMĘ KONTROLNĄ BAJTÓW zdjęcia.
     * Dwa zdjęcia tej samej nieruchomej sceny nigdy nie są identyczne co do
     * bajtu - wystarczy szum matrycy - więc ten warunek przepuszczał wszystko i
     * nie oszczędzał niczego. Lepiej go nie mieć niż udawać, że działa.
     *
     * Teraz cykl jest dokładnie taki, jak opisano w zgłoszeniu: zdjęcie, opis,
     * dziesięć sekund przerwy, znowu zdjęcie.
     */
    private suspend fun describeSceneLoop() {
        var describedScene: IntArray? = null
        while (active.get()) {
            try {
                // NIE OPISUJEMY W KÓŁKO TEGO SAMEGO.
                //
                // Poprzednia próba tego warunku porównywała SUMĘ KONTROLNĄ
                // BAJTÓW zdjęcia i przepuszczała wszystko, bo szum matrycy
                // zmienia każdy bajt - komentarz wyżej opisuje, czemu została
                // usunięta jako udawanie. Klatki ze strumienia pozwalają
                // porównać OBRAZ: siatkę średnich jasności, na którą szum się
                // uśrednia do zera, a człowiek wchodzący w kadr nie.
                //
                // Zysk jest podwójny. Dla użytkownika: nie słyszy co dziesięć
                // sekund tego samego opisu nieruchomego pokoju. Dla rachunku:
                // pominięte zapytanie to circa 1600 tokenów, za które nie ma
                // powodu płacić.
                //
                // Działa tylko przy strumieniu. Przy zdjęciach odcisku nie ma,
                // `sceneChanged` oddaje wtedy `true` i wszystko idzie po
                // staremu - czyli pogorszenia nie ma nigdzie.
                val sceneNow = glassesManager.liveFingerprint()
                if (describedScene != null &&
                    !YuvFrame.sceneChanged(describedScene, sceneNow)
                ) {
                    delay(describeIntervalMs)
                    continue
                }

                val photo = capturePhotoOrExplain()
                if (photo != null) {
                    val description = askOrExplain(photo, FAILURE_DESCRIBE, onDescribeScene)
                    if (stopIfModelDead()) return
                    if (description != null) {
                        // Zapamiętujemy scenę, KTÓRĄ OPISALIŚMY, a nie tę z
                        // chwili sprawdzania: między jednym a drugim mija
                        // sekunda i porównywanie do świeższej klatki gubiłoby
                        // zmiany, które zaszły w tym czasie.
                        describedScene = glassesManager.liveFingerprint() ?: sceneNow
                        playBeep(BeepType.NEW_SCENE)
                        _lastDescription.value = description
                        // speakAndAwait: przerwa ma się liczyć od chwili, gdy
                        // użytkownik SKOŃCZYŁ SŁUCHAĆ, a nie od zrobienia zdjęcia.
                        audio.speakAndAwait(description, language = "pl")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "describeSceneLoop error", e)
            }
            if (!active.get()) break
            delay(describeIntervalMs + failureBackoffMs())
        }
    }

    /**
     * Prosi o kolejne czytanie - to jest ten moment, w którym powstaje zdjęcie.
     *
     * Wołane z komendy głosowej ("czytaj"), z przycisku na okularach i z
     * aplikacji. Poza trybem czytania nie robi nic, żeby przycisk nie uruchamiał
     * funkcji, której użytkownik nie włączył.
     *
     * @return czy prośba została przyjęta - `false` znaczy, że tryb czytania
     *   jest wyłączony i wołający ma powiedzieć o tym użytkownikowi
     */
    fun requestRead(): Boolean {
        if (_mode.value != AccessibilityMode.READ_TEXT || !active.get()) return false
        readRequests.trySend(Unit)
        Log.i(tag, "Prośba o kolejne czytanie")
        return true
    }

    /**
     * Loop dla trybu nawigacji.
     *
     * ## Czemu ten tryb dostał bramkę zmiany sceny dopiero teraz
     * Bo do czasu strumienia `navigateIntervalMs` było MARTWĄ LITERĄ: zdjęcie
     * przez Wi-Fi Direct kosztowało kilka sekund, więc obieg i tak nie schodził
     * poniżej dziesięciu. Klatka ze strumienia jest od ręki, przez co obieg
     * skrócił się do czasu odpowiedzi modelu - a półtorasekundowy odstęp, dotąd
     * nieszkodliwy, zaczął znaczyć kilkadziesiąt zapytań na minutę po circa
     * 1600 tokenów każde. Przy koncie przedpłaconym to są minuty, nie godziny.
     *
     * Bramka jest ta sama co w opisie otoczenia i z tym samym progiem -
     * dobranym ostrożnie W STRONĘ PYTANIA: brak odcisku (czyli brak strumienia)
     * znaczy "pytaj", więc przy zdjęciach nic się nie zmienia.
     *
     * ## Czego bramka NIE załatwia
     * Idącemu człowiekowi scena zmienia się bez przerwy, więc dokładnie wtedy,
     * gdy tryb jest używany zgodnie z przeznaczeniem, oszczędność jest
     * najmniejsza. Bramka ucina przypadek postoju - realny i częsty (przystanek,
     * winda, czekanie na przejściu) - ale prawdziwego tempa marszu nie zmieni.
     * Ile ono wynosi, powie [logUsageRate] z dziennika; dopiero wtedy da się
     * uczciwie ustawić odstęp albo limit, zamiast zgadywać liczbę.
     */
    private suspend fun navigateLoop() {
        var warnedScene: IntArray? = null
        while (active.get()) {
            try {
                val sceneNow = glassesManager.liveFingerprint()
                if (warnedScene != null && !YuvFrame.sceneChanged(warnedScene, sceneNow)) {
                    // Nic się nie zmieniło od ostatniego ostrzeżenia. Powtarzanie
                    // go co półtorej sekundy nie dokłada wiedzy o przeszkodzie -
                    // o schodach użytkownik już usłyszał - a kosztuje tyle samo,
                    // co ostrzeżenie o czymś nowym.
                    delay(navigateIntervalMs)
                    continue
                }

                val photo = capturePhotoOrExplain()
                if (photo != null) {
                    val alert = askOrExplain(photo, FAILURE_NAVIGATE, onNavigate)
                    if (stopIfModelDead()) return
                    if (alert != null) {
                        // Zapamiętujemy scenę, O KTÓREJ OSTRZEGLIŚMY, a nie tę z
                        // chwili sprawdzania - między jednym a drugim mija czas
                        // odpowiedzi modelu, a w marszu to jest kilka kroków.
                        warnedScene = glassesManager.liveFingerprint() ?: sceneNow
                        // Alert nawigacyjny - krótszy, bardziej pilny
                        playBeep(BeepType.NAVIGATION_ALERT)
                        audio.speak(alert, language = "pl")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "navigateLoop error", e)
            }
            if (!active.get()) break
            delay(navigateIntervalMs + failureBackoffMs())
        }
    }

    /**
     * Sygnały dźwiękowe - pomagają użytkownikowi wiedzieć co się dzieje
     * nawet bez patrzenia na ekran.
     */
    private fun playBeep(type: BeepType) {
        // Tu można użyć ToneGenerator dla systemowych sygnałów
        // Na razie uproszczone - log + brak dźwięku
        Log.d(tag, "Beep: $type")
    }

    private companion object {
        /**
         * Co ile nieudanych zdjęć powtarzamy komunikat. Przy trwałej awarii
         * pętla kręci się co kilka sekund - mówienie za każdym razem zamieniłoby
         * pomoc w hałas.
         */
        const val FAILURES_BETWEEN_REPORTS = 10

        /** Klucze liczników - każda awaria ma własny, żeby jedna nie uciszała drugiej. */
        const val FAILURE_PHOTO = "photo"
        const val FAILURE_DESCRIBE = "describe"
        const val FAILURE_NAVIGATE = "navigate"
        const val FAILURE_NO_TEXT = "no_text"
        const val FAILURE_READ = "read"

        /**
         * Pierwszy krok odczekania po nieudanym zapytaniu - patrz [failureBackoffMs].
         *
         * Dwie sekundy, bo tyle mniej więcej trwa przełączenie sieci w telefonie:
         * krócej znaczyłoby ponawiać w trakcie, dłużej - przegapić moment, gdy
         * łączność wróciła.
         */
        const val BACKOFF_STEP_MS = 2_000L

        /** Ile razy odczekanie zdąży się podwoić, zanim trafi na sufit. */
        const val BACKOFF_MAX_SHIFT = 5

        /**
         * Sufit odczekania.
         *
         * Pół minuty: awaria przejściowa ma zostać zauważona, gdy minie, a nie
         * po kwadransie ciszy - zwłaszcza w trybie, z którego ktoś korzysta
         * idąc ulicą.
         */
        const val BACKOFF_CEILING_MS = 30_000L

        /** Co ile udanych zapytań zapisujemy tempo - patrz [logUsageRate]. */
        const val RATE_REPORT_EVERY = 10

        /** Krótszy tekst to zwykle szum OCR, nie napis. */
        const val MIN_READABLE_TEXT = 5

        /**
         * Odstęp między obrotami pętli czytania. Dłuższy niż dawne 500 ms, bo
         * jeden obrót potrafi teraz zawierać zdjęcie w pełnej jakości.
         */
        const val READ_LOOP_INTERVAL_MS = 1_500L
    }
}

enum class AccessibilityMode(val displayName: String, val emoji: String) {
    OFF("Wyłączony", "⏹️"),
    READ_TEXT("Czytanie tekstu", "📖"),
    DESCRIBE_SCENE("Opis otoczenia", "👁️"),
    NAVIGATE("Nawigacja", "🧭")
}

enum class BeepType {
    MODE_CHANGED,    // Tryb włączony/wyłączony
    TEXT_DETECTED,   // Wykryto nowy tekst
    NEW_SCENE,       // Nowa scena
    FACE_DETECTED,   // Wykryto twarz
    NAVIGATION_ALERT // Alert nawigacyjny
}
