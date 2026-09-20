package pl.victor.app.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Dziennik diagnostyczny na czas testów ze sprzętem.
 *
 * ## Po co to powstało
 * Bo przy okularach na głowie nie da się patrzeć w logcat, a bez tego i
 * użytkownik, i ja zgadywaliśmy. Kilka poprawek z rzędu trafiło obok, bo
 * naprawiały to, co wyglądało na przyczynę, a nie to, co się naprawdę działo.
 * Zgłoszone wprost: "chciałbym, żebyś zapisywał wewnętrzne logi, które trafiają
 * gdzieś na GitHuba i Ty możesz je przeglądać".
 *
 * ## Co zapisuje
 * KAŻDY etap tury z czasem od jej początku - wybudzenie, nasłuch, która droga
 * przepisała mowę i ile to trwało, zdjęcie, budowanie promptu, wysłanie do
 * modelu, PIERWSZY fragment odpowiedzi (to jest ta liczba, o którą chodzi przy
 * "długo trwa"), koniec odpowiedzi, mówienie. Do tego zdarzenia sprzętowe:
 * połączenia i rozłączenia BLE, przyciski, profil audio.
 *
 * ## Czego NIE zapisuje
 * Kluczy API i tokenów - patrz [DiagFormat.redact]. Każdy wiersz przechodzi
 * przez zaciemnianie, także treść i pola, bo plik trafia do repozytorium.
 *
 * ## Gdzie trafia
 * Do pliku w pamięci aplikacji, a stamtąd - gdy użytkownik poda token - do
 * gałęzi diagnostycznej w repozytorium ([DiagnosticUploader]). Plik jest
 * dopisywany na bieżąco, więc przeżywa zabicie aplikacji: przy usterce typu
 * "zawiesiło się" to jedyny ślad, jaki zostaje.
 */
class DiagnosticLog(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR_NAME).apply { mkdirs() }

    // Pojedynczy wątek IO: kolejność wierszy w pliku ma odpowiadać kolejności
    // zdarzeń, bo cała wartość dziennika siedzi właśnie w kolejności.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // DateTimeFormatter, nie SimpleDateFormat: event() jest wołane równolegle z
    // BLE, audio i orkiestratora, a SimpleDateFormat nie jest bezpieczny
    // wątkowo - psuł dokładnie tę kolumnę, dla której ten dziennik istnieje.
    private val clock = DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())
    private val fileStamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH-mm-ss", Locale.US)
        .withZone(ZoneId.systemDefault())

    @Volatile
    private var sessionFile: File? = null

    /** Początek bieżącej tury; 0 = tura nie trwa. */
    private val turnStartedAt = AtomicLong(0L)

    @Volatile
    private var turnId: String = "-"

    private val _recent = MutableStateFlow<List<String>>(emptyList())

    /** Ostatnie wiersze - do podglądu na ekranie diagnostyki. */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    /** Ścieżka pliku bieżącej sesji albo `null`, gdy jeszcze nie zaczęta. */
    fun currentFile(): File? = sessionFile

    /**
     * Zaczyna nowy plik sesji. Wołane przy starcie aplikacji.
     *
     * @param header linijki opisujące urządzenie i wersję - bez nich log z
     *   telefonu, którego nie mam, nie znaczy nic
     */
    fun startSession(header: List<String>) {
        val file = File(dir, "victor-${fileStamp.format(Instant.now())}.log")
        sessionFile = file
        scope.launch {
            runCatching {
                // Nagłówek też przez redact: to jedyny wiersz, który omijał
                // zaciemnianie, a dokumentacja tej klasy obiecuje KAŻDY.
                file.appendText(
                    header.joinToString("\n", postfix = "\n") { "# ${DiagFormat.redact(it)}" }
                )
            }.onFailure { Log.w(TAG, "Nie udało się otworzyć pliku dziennika", it) }
        }
        event(DiagFormat.Phase.SESJA, "start sesji")
        pruneOldSessions()
    }

    /**
     * Zaczyna turę - od tej chwili każdy wiersz niesie czas od TEGO momentu.
     *
     * To jest sedno dziennika. Czas bezwzględny mówi "kiedy", ale przy
     * zgłoszeniu "długo trwa od pytania do odpowiedzi" liczy się wyłącznie
     * odstęp - a ręczne odejmowanie godzin z kilkunastu wierszy jest dokładnie
     * tym, czego przy diagnozie nikt nie robi.
     */
    fun startTurn(trigger: String) {
        turnStartedAt.set(System.currentTimeMillis())
        turnId = java.util.UUID.randomUUID().toString().take(4)
        event(DiagFormat.Phase.SESJA, "--- TURA $turnId ---", mapOf("źródło" to trigger))
    }

    /**
     * Zaczyna turę tylko wtedy, gdy żadna nie trwa.
     *
     * Tura użytkownika zaczyna się w chwili, gdy OTWIERAMY MIKROFON - nie wtedy,
     * gdy tekst trafia do orkiestratora. Gdyby drugi etap resetował licznik,
     * z pomiaru wypadłby nasłuch i transkrypcja, czyli dokładnie ta część, o
     * którą chodzi w zgłoszeniu "długo trwa od pytania do przetwarzania".
     */
    fun continueOrStartTurn(trigger: String) {
        if (turnStartedAt.get() == 0L) startTurn(trigger)
    }

    /**
     * Kończy turę - kolejne wiersze idą już bez licznika.
     *
     * Wywołanie na zamkniętej turze nic nie robi. Dzięki temu zamknięcie da się
     * wpiąć w jedno miejsce dla WSZYSTKICH dróg wyjścia (patrz obserwator stanu
     * w [pl.victor.app.AIOrchestrator]) bez ryzyka, że ścieżka, która kończy
     * turę sama, dopisze drugi wiersz „KONIEC TURY".
     */
    fun endTurn(outcome: String, fields: Map<String, Any?> = emptyMap()) {
        if (turnStartedAt.get() == 0L) return
        event(DiagFormat.Phase.SESJA, "--- KONIEC TURY $turnId: $outcome ---", fields)
        turnStartedAt.set(0L)
    }

    /** Zapisuje zdarzenie. Bezpieczne z dowolnego wątku i w dowolnym momencie. */
    fun event(
        phase: DiagFormat.Phase,
        message: String,
        fields: Map<String, Any?> = emptyMap()
    ) {
        val started = turnStartedAt.get()
        val line = DiagFormat.line(
            wallClock = clock.format(Instant.now()),
            sinceTurnMs = if (started == 0L) null else System.currentTimeMillis() - started,
            phase = phase,
            message = message,
            fields = fields
        )
        // Do logcat też - gdy telefon akurat wisi na kablu, to wygodniejsze.
        Log.i(TAG, line)
        // update{}, nie przypisanie: odczyt-modyfikacja-zapis z kilku wątków
        // gubił wiersze w podglądzie.
        _recent.update { (it + line).takeLast(MEMORY_LINES) }
        val file = sessionFile ?: return
        scope.launch {
            runCatching { file.appendText(line + "\n") }
                .onFailure { Log.w(TAG, "Nie udało się dopisać wiersza dziennika", it) }
        }
    }

    /** Skrót na najczęstszy przypadek: zdarzenie z jednym polem czasu. */
    fun took(
        phase: DiagFormat.Phase,
        message: String,
        startedAtMs: Long,
        // Czas SAM W SOBIE rzadko wystarcza. Przy "koniec odpowiedzi" trzeba
        // jeszcze wiedzieć, KTÓRY dostawca ją dał i jak się zaczynała - bez tego
        // sensowne zdanie i bełkot wyglądają w dzienniku identycznie.
        fields: Map<String, Any?> = emptyMap()
    ) {
        event(phase, message, mapOf("ms" to System.currentTimeMillis() - startedAtMs) + fields)
    }

    /**
     * Cała treść bieżącej sesji - do wysyłki i do podglądu.
     *
     * Suspend, bo plik rośnie przez cały dzień, a obaj wołający siedzą na
     * Dispatchers.Main: pod wieczór każdy odczyt zacinał interfejs.
     */
    suspend fun readSession(): String = withContext(Dispatchers.IO) {
        sessionFile?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
            ?: ""
    }

    /** Pliki poprzednich sesji, od najnowszej. */
    fun sessions(): List<File> =
        dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    /**
     * Kasuje stare sesje. Dziennik jest narzędziem na czas testów, nie archiwum -
     * bez tego zjadałby pamięć telefonu w tygodniach, a szuka się i tak zawsze
     * w ostatnich kilku.
     */
    private fun pruneOldSessions() {
        scope.launch {
            runCatching {
                sessions().drop(MAX_SESSIONS).forEach { it.delete() }
            }.onFailure { Log.w(TAG, "Nie udało się posprzątać starych dzienników", it) }
        }
    }

    companion object {
        private const val TAG = "VictorDiag"
        private const val DIR_NAME = "diagnostics"
        private const val MEMORY_LINES = 400
        private const val MAX_SESSIONS = 12
    }
}
