package pl.victor.app.orchestrator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.OrchestratorState
import pl.victor.app.ble.VictorManager
import pl.victor.app.data.SettingsRepository
import pl.victor.app.diagnostics.DiagFormat
import pl.victor.app.vision.OCRReader
import pl.victor.app.vision.QRScanner

/**
 * Obraz ze STRUMIENIA okularów pod litery i kody: podniesienie strumienia z
 * wyprzedzeniem (z samego nasłuchu wiadomo, że pytanie dotyczy kodu), klatka
 * pod litery, gdy migawka nie odda ostrego zdjęcia, i skan kolejnych klatek,
 * aż któraś da kod.
 *
 * ## Po co osobna klasa
 * Wydzielone z [pl.victor.app.AIOrchestrator] BEZ ZMIANY ZACHOWANIA: te same
 * warunki, limity czasu i wpisy w dzienniku. Strumień podnoszony z
 * wyprzedzeniem ([codeStreamWarmup]) należy do tej klasy; tura tylko pyta,
 * czy taki jest.
 */
internal class CodeStreamScanner(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val glassesManager: VictorManager,
    private val qrScanner: QRScanner,
    private val ocrReader: OCRReader,
    /** Stan orkiestratora - pokazuje, że trwa branie obrazu ze strumienia. */
    private val state: MutableStateFlow<OrchestratorState>,
    /** Dziennik leniwie - z tego samego powodu co `AIOrchestrator.diag`. */
    private val dziennik: () -> pl.victor.app.diagnostics.DiagnosticLog
) {
    private val diag: pl.victor.app.diagnostics.DiagnosticLog
        get() = dziennik()

    /**
     * Ostatnia droga do LITER, gdy Wi-Fi Direct tu nie wstaje.
     *
     * ## Czemu to nie przeczy wąskiemu warunkowi wyżej
     * Tamten mówi: nie podnoś strumienia pod jedno pytanie, bo kosztuje circa
     * 12 s, a migawka odda ostre zdjęcie szybciej. To prawda, DOPÓKI migawka
     * faktycznie je oddaje. Gdy bezpiecznik Wi-Fi Direct jest zatrzaśnięty,
     * wiemy z POMIARU, że nie odda - wróci miniatura, na której liter nie ma.
     * Wtedy wybór nie brzmi "szybciej czy wolniej", tylko "czekanie czy
     * odpowiedź zgadywana z szarej plamy".
     *
     * ## Ile to naprawdę kosztuje, łącznie z gorszym przypadkiem
     * Circa 12 s, gdy strumień wstaje - i to jest przypadek typowy. Gdy NIE
     * wstaje, [pl.victor.app.ble.VictorManager.startLiveVision] czeka na
     * pierwszą klatkę do dwudziestu sekund, zanim się podda. Tyle wynosi
     * sufit, i dopiero po nim schodzimy na zwykłą drogę ze zdjęciem.
     *
     * Ten sufit jest świadomie przyjęty: dotyczy wyłącznie pytań O LITERY przy
     * zatrzaśniętym bezpieczniku, czyli sytuacji, w której druga droga jest
     * już zmierzona jako nieskuteczna. Gdyby pomiar z terenu pokazał, że
     * strumień też tam nie wstaje, ten warunek trzeba będzie odwołać - i
     * dlatego wynik idzie do dziennika z czasem.
     *
     * Dziennik z 21:55 pokazuje obie strony naraz: pobranie oryginału zawiodło
     * po 10,4 s i model dostał 17 761 bajtów, a strumień w tym samym czasie
     * oddawał 1600x1200 bez zarzutu.
     *
     * ## Warunki są wąskie z rozmysłu
     * Wchodzi wyłącznie wtedy, gdy pytanie DOTYCZY liter, strumień jeszcze nie
     * stoi, a bezpiecznik jest zatrzaśnięty. Przy "co przede mną jest"
     * miniatura wystarcza i nikt nie ma czekać 12 sekund za nic.
     *
     * Strumień jest po wszystkim gaszony: podniesiony pod jedno pytanie i
     * zostawiony trzymałby łącze oraz baterię okularów bez powodu.
     */
    /** Strumień podnoszony z wyprzedzeniem pod pytanie o kod - patrz [warmUpStreamForCode]. */
    @Volatile
    var codeStreamWarmup: kotlinx.coroutines.Deferred<Boolean>? = null
        private set

    /**
     * Zaczyna podnosić strumień, gdy z samego nasłuchu wiadomo, że pytanie
     * dotyczy kodu. Odbiera go [streamFrameForText] (i on go gasi); gdy tura
     * po strumień nie sięgnie, gasimy go sami po [CODE_WARMUP_MAX_MS].
     */
    fun warmUpStreamForCode(heard: String) {
        if (settings.isCameraFree()) return
        if (!pl.victor.app.ai.VisionDetail.isAboutCode(heard)) return
        if (codeStreamWarmup != null || glassesManager.isLiveVisionRunning) return
        if (!glassesManager.isConnected()) return
        val warm = scope.async { glassesManager.startLiveVision() }
        codeStreamWarmup = warm
        runCatching { diag.event(DiagFormat.Phase.ZDJĘCIE, "strumień pod kod rusza z wyprzedzeniem") }
        scope.launch {
            delay(CODE_WARMUP_MAX_MS)
            if (codeStreamWarmup === warm) {
                codeStreamWarmup = null
                if (warm.await()) glassesManager.stopLiveVision()
            }
        }
    }

    suspend fun streamFrameForText(useVision: Boolean, textQuestion: String): ByteArray? {
        if (!useVision) return null
        if (glassesManager.isLiveVisionRunning && codeStreamWarmup == null) return null
        // KOD IDZIE STRUMIENIEM OD RAZU, nie dopiero po zatrzaśniętym
        // bezpieczniku. Dziennik z biegu 152: pierwsza próba szła migawką -
        // miniatura bez kodu, potem 41 s nieudanego dołączania do sieci
        // okularów - a druga, już strumieniem, miała klatkę 1600x1200 po
        // dziewięciu sekundach. Do kodu potrzeba pełnej rozdzielczości i kilku
        // szans (patrz [scanStreamForCode]), a migawka daje jedną miniaturę.
        val forCode = pl.victor.app.ai.VisionDetail.isAboutCode(textQuestion)
        // Ostateczne pytanie jednak nie o kod - strumień z wyprzedzeniem
        // gasimy od razu, żeby nie wisiał przy robieniu zdjęcia.
        if (!forCode) {
            codeStreamWarmup?.let { warm ->
                codeStreamWarmup = null
                scope.launch { if (warm.await()) glassesManager.stopLiveVision() }
            }
        }
        if (!glassesManager.wifiDirectKnownBroken && !forCode) return null
        // "co jest pod tym kodem kreskowym" łapie isAboutCode, ale nie
        // needsDetail - i w biegu 153 szło przez to migawką, z miniaturą.
        if (!forCode && !pl.victor.app.ai.VisionDetail.needsDetail(textQuestion)) return null

        state.value = OrchestratorState.Capturing(
            progress = 1,
            total = 1,
            label = "Ostre zdjęcie tu nie przechodzi - biorę obraz ze strumienia. " +
                "Chwilę to potrwa."
        )
        val startedAt = System.currentTimeMillis()
        val warm = codeStreamWarmup?.also { codeStreamWarmup = null }
        // try obejmuje TAKŻE czekanie na start: anulowanie tury w trakcie 8-25 s
        // podnoszenia strumienia zostawiało go włączonego na stałe (bateria
        // okularów), bo finally zaczynało się dopiero po udanym starcie.
        var failedCleanly = false
        return try {
            if (!(warm?.await() ?: glassesManager.startLiveVision())) {
                // POWÓD, NIE TYLKO CZAS. Pierwszy pomiar z terenu oddał samo
                // "nie wstał ms=1242" i trzeba było szukać przyczyny trzy wiersze
                // wyżej, w zdarzeniu z innej fazy. A przyczyna była jedna i
                // banalna: wyłączone Wi-Fi w telefonie, które blokuje OBIE drogi
                // do ostrego obrazu naraz.
                diag.event(
                    DiagFormat.Phase.ZDJĘCIE, "strumień dla liter nie wstał",
                    mapOf(
                        "ms" to (System.currentTimeMillis() - startedAt),
                        "powód" to glassesManager.lastTransferFailure
                    )
                )
                failedCleanly = true
                null
            } else {
                glassesManager.liveFrame(detail = true)?.also { frame ->
                    diag.event(
                        DiagFormat.Phase.ZDJĘCIE, "litery ze strumienia zamiast miniatury",
                        mapOf(
                            "bajtów" to frame.size,
                            "ms" to (System.currentTimeMillis() - startedAt)
                        )
                    )
                }?.let { frame ->
                    // Skanowanie PRZED zgaszeniem strumienia - po nim nie ma już
                    // skąd brać kolejnych klatek.
                    if (pl.victor.app.ai.VisionDetail.isAboutCode(textQuestion)) scanStreamForCode(frame) else frame
                }
            }
        } finally {
            if (!failedCleanly) glassesManager.stopLiveVision()
        }
    }

    /**
     * Przy pytaniu o kod: skanuje KOLEJNE klatki strumienia, aż któraś da kod.
     *
     * Dziennik z biegu 152 (17:51): strumień wstał, pierwsza klatka 1600x1200
     * przyszła sekundę po starcie - i kodu na niej nie było. Jedna klatka to
     * jeden moment: kamera tuż po starcie jeszcze ustawia ostrość i
     * naświetlenie, a głowa się rusza. Aplikacja gasiła wtedy strumień i szła
     * po "ostre zdjęcie", które przez Wi-Fi okularów nie doszło, więc po 30 s
     * skończyło się miniaturą bez kodu. Strumień daje kilka klatek na sekundę
     * w pełnej rozdzielczości, a ML Kit czyta klatkę w ułamku sekundy - kilka
     * sekund skanowania to kilkanaście szans zamiast jednej.
     *
     * @return klatka z kodem, a gdy żadna go nie dała - ostatnia klatka
     */
    suspend fun scanStreamForCode(first: ByteArray): ByteArray {
        if (qrScanner.scanImageBytesSync(first).isNotEmpty()) return first
        val startedAt = System.currentTimeMillis()
        var last = first
        var klatek = 1
        var seen = glassesManager.liveFrameCount()
        var ostatniOcr = 0L
        state.value = OrchestratorState.Capturing(
            progress = 1,
            total = 1,
            label = "Szukam kodu w obrazie z okularów - patrz chwilę na kod."
        )
        while (System.currentTimeMillis() - startedAt < CODE_STREAM_SCAN_MS) {
            delay(CODE_STREAM_POLL_MS)
            val count = glassesManager.liveFrameCount()
            if (count == seen) continue
            seen = count
            val frame = glassesManager.liveFrame(detail = true) ?: continue
            klatek++
            last = frame
            if (qrScanner.scanImageBytesSync(frame).isNotEmpty()) {
                diag.event(
                    DiagFormat.Phase.ZDJĘCIE, "kod znaleziony w strumieniu",
                    mapOf("klatek" to klatek, "ms" to (System.currentTimeMillis() - startedAt))
                )
                return frame
            }
            // Co druga klatka - środek kadru w powiększeniu, patrz scanCenterSync.
            if (klatek % 2 == 0 && qrScanner.scanCenterSync(frame).isNotEmpty()) {
                diag.event(
                    DiagFormat.Phase.ZDJĘCIE, "kod znaleziony w strumieniu - środek kadru",
                    mapOf("klatek" to klatek, "ms" to (System.currentTimeMillis() - startedAt))
                )
                return frame
            }
            // CYFRY POD KRESKAMI TEŻ SĄ KODEM.
            //
            // W biegu 153 to one, odczytane z ostatniej klatki, rozpoznały
            // produkt (Alpro), gdy kreski nie wyszły z żadnej z 38. Rozpoznanie
            // tekstu jest wolniejsze, więc najwyżej raz na [CODE_STREAM_OCR_MS].
            val teraz = System.currentTimeMillis()
            if (teraz - ostatniOcr >= CODE_STREAM_OCR_MS) {
                ostatniOcr = teraz
                val cyfry = runCatching { ocrReader.readBytes(frame) }.getOrNull()
                    ?.takeIf { it.isSuccess }
                    ?.let { pl.victor.app.vision.EanFromText.find(it.fullText) }
                if (cyfry != null) {
                    diag.event(
                        DiagFormat.Phase.ZDJĘCIE, "cyfry kodu odczytane ze strumienia",
                        mapOf("kod" to cyfry, "klatek" to klatek, "ms" to (teraz - startedAt))
                    )
                    return frame
                }
            }
        }
        diag.event(
            DiagFormat.Phase.ZDJĘCIE, "strumień bez kodu",
            mapOf("klatek" to klatek, "ms" to (System.currentTimeMillis() - startedAt))
        )
        return last
    }

    companion object {
        /** Po tylu ms nieodebrany strumień z wyprzedzeniem gaśnie - patrz [warmUpStreamForCode]. */
        private const val CODE_WARMUP_MAX_MS = 75_000L

        /** Jak długo skanować strumień w poszukiwaniu kodu - patrz [scanStreamForCode]. */
        private const val CODE_STREAM_SCAN_MS = 6_000L

        private const val CODE_STREAM_POLL_MS = 150L

        /** Jak często w skanie strumienia czytać cyfry pod kodem. */
        private const val CODE_STREAM_OCR_MS = 1_200L
    }
}
