package pl.victor.app.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/**
 * `execute().use { }`, które da się PRZERWAĆ.
 *
 * ## Po co
 * Anulowanie korutyny nie przerywa blokującego `execute()` ani odczytu
 * odpowiedzi. Przerwana tura (dotknięcie zausznika, "przerwij", limit czasu
 * dostawcy) czekała więc do końca odpowiedzi - płacąc za wszystkie jej tokeny -
 * a limit MODEL_ATTEMPT_TIMEOUT_MS w praktyce znaczył tyle, ile limit odczytu
 * klienta HTTP (60-90 s). Odpowiedź, która doszła po 50 s, i tak szła do kosza
 * jako "nie odpowiedział w 45 s".
 *
 * ## Czemu czujka, a nie invokeOnCompletion
 * Bo zadanie zablokowane w odczycie jest po anulowaniu tylko "anulowane", a nie
 * "zakończone" - zwykły `invokeOnCompletion` odpaliłby dopiero po powrocie z
 * odczytu, czyli nigdy wtedy, gdy jest potrzebny. Osobna korutyna sprawdza co
 * [POLL_MS], czy tura wciąż żyje, i zamyka połączenie; zamknięte połączenie
 * rzuca w blokującym odczycie, a to zamieniamy z powrotem w anulowanie.
 */
suspend inline fun <T> Call.executeCancellable(block: (Response) -> T): T {
    val watcher = CancellableCall.watch(this, currentCoroutineContext()[Job])
    try {
        return execute().use(block)
    } catch (e: java.io.IOException) {
        // Zamknięte przez czujkę połączenie to przerwanie, nie awaria dostawcy.
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        watcher?.cancel()
    }
}

object CancellableCall {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val POLL_MS = 200L

    fun watch(call: Call, job: Job?): Job? {
        job ?: return null
        return scope.launch {
            while (job.isActive) delay(POLL_MS)
            call.cancel()
        }
    }
}
