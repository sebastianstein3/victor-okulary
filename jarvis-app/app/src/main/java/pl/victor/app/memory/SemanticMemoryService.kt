package pl.victor.app.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.victor.app.data.HistoryRepository
import pl.victor.app.data.SettingsRepository
import pl.victor.app.diagnostics.DiagFormat
import pl.victor.app.diagnostics.DiagnosticLog
import java.io.File
import java.time.LocalDate

/**
 * Pamięć po znaczeniu - część z Androidem i siecią. Logika jest w
 * [SemanticMemory], tu jest tylko: skąd wziąć wpisy, gdzie trzymać indeks,
 * kiedy liczyć wektory i jak nie spowolnić tury.
 *
 * ## Indeksowanie (w tle)
 * Kolejką jest sama RÓŻNICA między wpisami a indeksem: wpis bez wektora
 * czeka, aż [sync] go policzy. Nie ma osobnej listy do zgubienia - po
 * restarcie, braku sieci czy błędzie usługi różnica jest ta sama, więc
 * kolejna próba podejmie dokładnie to, co zostało. Po błędzie kolejna próba
 * czeka coraz dłużej ([SemanticMemory.retryDelayMs]).
 *
 * Wyzwalacze: start aplikacji, każda zmiana notatek i faktów
 * ([SettingsRepository.memoryRevision]) i zapis rozmowy do historii.
 *
 * ## Wyszukiwanie (w turze)
 * Wektor pytania liczony z limitem [SemanticMemory.QUERY_TIMEOUT_MS]. Gdy
 * nie zdąży - sam dawny sposób (słowa), z wpisem `limitCzasu=true` w
 * dzienniku.
 */
class SemanticMemoryService(
    context: Context,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val diag: () -> DiagnosticLog,
    private val scope: CoroutineScope
) {
    private val file = File(context.filesDir, INDEX_FILE)
    private val client = EmbeddingClient()
    private val mutex = Mutex()

    /** Bieżący indeks - podmieniany w całości, więc czytanie nie potrzebuje blokady. */
    @Volatile
    private var index: SemanticIndexCodec.Index? = null

    @Volatile
    private var failures = 0

    @Volatile
    private var retryAtMs = 0L

    private var retryJob: Job? = null

    /** Uruchamia nasłuch zmian i pierwsze indeksowanie. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch(Dispatchers.IO) {
            // Seria edycji (np. porządkowanie notatek) daje jedno indeksowanie.
            settings.memoryRevision.drop(1).debounce(SYNC_DEBOUNCE_MS).collect { requestSync("zmiana notatek/faktów") }
        }
        requestSync("start")
    }

    /** Prośba o dociągnięcie brakujących wektorów - w tle, nigdy nie blokuje. */
    fun requestSync(reason: String) {
        if (!settings.isSemanticMemoryEnabled()) return
        scope.launch(Dispatchers.IO) {
            try {
                sync(reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Indeksowanie nie powiodło się", e)
            }
        }
    }

    private fun loadIndex(): SemanticIndexCodec.Index? {
        index?.let { return it }
        val loaded = runCatching { if (file.exists()) SemanticIndexCodec.decode(file.readBytes()) else null }
            .getOrNull()
        index = loaded
        return loaded
    }

    /** Zapis przez plik tymczasowy - przerwany zapis nie może zostawić połowy indeksu. */
    private fun saveIndex(value: SemanticIndexCodec.Index) {
        index = value
        runCatching {
            val tmp = File(file.parentFile, "$INDEX_FILE.tmp")
            tmp.writeBytes(SemanticIndexCodec.encode(value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Nie zapisałem indeksu", it) }
    }

    /** Wszystkie wpisy pamięci: rozmowy z historii, notatki, fakty. */
    private suspend fun collectDocs(): List<SemanticMemory.Doc> {
        val conversations = runCatching { history.getRecent(HISTORY_POOL) }.getOrDefault(emptyList())
            .filter { it.aiResponse.isNotBlank() }
            .map { SemanticMemory.conversationDoc(it.id, it.userQuestion, it.aiResponse, it.timestamp) }
        val notes = settings.getNotes().map { SemanticMemory.noteDoc(it.text, it.createdAtMs) }
        val facts = settings.getFacts().map { SemanticMemory.factDoc(it.text, it.createdAtMs) }
        return conversations + notes + facts
    }

    private suspend fun sync(reason: String) = mutex.withLock {
        if (!settings.isSemanticMemoryEnabled()) return@withLock
        val provider = settings.getSemanticMemoryProvider() ?: return@withLock
        val apiKey = settings.getApiKey(provider.id)?.takeIf { it.isNotBlank() } ?: return@withLock
        val now = System.currentTimeMillis()
        if (now < retryAtMs) {
            scheduleRetry()
            return@withLock
        }
        val startedAt = now
        var current = loadIndex()
        var modelChanged = false
        if (current == null || current.modelTag != provider.modelTag) {
            // Wektory innego modelu są nieporównywalne - przeliczamy od zera.
            modelChanged = current != null
            current = SemanticIndexCodec.Index(provider.modelTag, provider.dims, emptyMap())
        }
        val docs = collectDocs()
        val stale = SemanticMemory.stale(current.vectors.keys, docs)
        val vectors = LinkedHashMap(current.vectors)
        stale.forEach { vectors.remove(it) }
        val missing = SemanticMemory.missing(docs, vectors.keys)
        val today = LocalDate.now().toEpochDay()
        val allowance = SemanticMemory.allowance(current.budgetDay, current.usedThatDay, today)
        var used = if (current.budgetDay == today) current.usedThatDay else 0
        val todo = missing.take(allowance)

        var done = 0
        var error: String? = null
        var retryable = false
        for (batch in todo.chunked(SemanticMemory.BATCH_SIZE)) {
            val result = client.embed(provider, apiKey, batch.map { SemanticMemory.documentText(provider, it.text) })
            val got = result.vectors
            if (got == null) {
                error = result.error
                retryable = result.retryable
                break
            }
            batch.forEachIndexed { i, doc -> vectors[doc.key] = got[i] }
            done += batch.size
            used += batch.size
        }

        if (done > 0 || stale.isNotEmpty() || modelChanged) {
            saveIndex(SemanticIndexCodec.Index(provider.modelTag, provider.dims, vectors, today, used))
        }
        if (error != null) {
            failures++
            retryAtMs = System.currentTimeMillis() + SemanticMemory.retryDelayMs(failures)
            scheduleRetry()
        } else {
            failures = 0
            retryAtMs = 0L
        }

        if (done > 0 || stale.isNotEmpty() || error != null || modelChanged) {
            runCatching {
                diag().event(
                    DiagFormat.Phase.KONTEKST, "pamięć po znaczeniu: indeksowanie",
                    mapOf(
                        "powód" to reason,
                        "dostawca" to provider.id,
                        "wpisów" to vectors.size,
                        "nowych" to done,
                        "czeka" to (missing.size - done),
                        "usunięto" to stale.size,
                        "zmianaModelu" to modelChanged.takeIf { it },
                        "limitDzienny" to (missing.size > allowance).takeIf { it },
                        "błąd" to error,
                        "ponowienieZaS" to (if (error != null) SemanticMemory.retryDelayMs(failures) / 1000 else null),
                        "ms" to (System.currentTimeMillis() - startedAt)
                    )
                )
            }
        }
        Log.i(TAG, "Indeks: ${vectors.size} wpisów, nowych $done, błąd=$error (powtarzalny=$retryable)")
    }

    /** Jedna zaplanowana ponowna próba po błędzie - kolejka sama się nie ruszy bez wyzwalacza. */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val wait = (retryAtMs - System.currentTimeMillis()).coerceAtLeast(1_000L)
        retryJob = scope.launch(Dispatchers.IO) {
            delay(wait)
            requestSync("ponowienie")
        }
    }

    /**
     * Wynik wyszukiwania - do kontekstu i do dziennika.
     *
     * @param docs wybrane wpisy, najlepsze pierwsze
     */
    data class Outcome(val docs: List<SemanticMemory.Doc>)

    /**
     * Szuka wpisów pasujących do pytania: słowami i po znaczeniu naraz.
     *
     * @param excludeKinds rodzaje, które i tak są już w poleceniu (np. notatki,
     *   gdy pytanie o nie dokleiło całą listę)
     * @param excludeKeys pojedyncze wpisy już obecne w poleceniu (fakty)
     */
    suspend fun search(
        question: String,
        excludeKinds: Set<SemanticMemory.Kind> = emptySet(),
        excludeKeys: Set<String> = emptySet()
    ): Outcome = withContext(Dispatchers.IO) {
        val provider = settings.getSemanticMemoryProvider()
        val apiKey = provider?.let { settings.getApiKey(it.id) }?.takeIf { it.isNotBlank() }
        val current = loadIndex()?.takeIf { provider != null && it.modelTag == provider.modelTag }
        val vectors = current?.vectors.orEmpty()

        coroutineScope {
            // Wektor pytania liczy się RÓWNOLEGLE ze słowami i odczytem wpisów.
            val queryStartedAt = System.currentTimeMillis()
            val queryDeferred = if (provider != null && apiKey != null && vectors.isNotEmpty()) {
                async {
                    withTimeoutOrNull(SemanticMemory.QUERY_TIMEOUT_MS) {
                        client.embed(provider, apiKey, listOf(SemanticMemory.queryText(provider, question)))
                    }
                }
            } else {
                null
            }

            val docs = collectDocs()
                .filter { it.kind !in excludeKinds && it.key !in excludeKeys }
                .distinctBy { it.key }
            val byKey = docs.associateBy { it.key }
            val keyword = KeywordRanker.rank(
                question, docs.map { it.text }, KEYWORD_MIN_SCORE, SemanticMemory.CANDIDATES_PER_LIST
            ).map { docs[it.first].key }

            val queryResult = queryDeferred?.await()
            val embedMs = System.currentTimeMillis() - queryStartedAt
            val timedOut = queryDeferred != null && queryResult == null
            val queryVector = queryResult?.vectors?.firstOrNull()
            val candidates = vectors.filterKeys { it in byKey }
            val semantic = if (queryVector != null && provider != null) {
                SemanticMemory.nearest(queryVector, candidates, provider.minSimilarity)
            } else {
                emptyList()
            }
            val best = queryVector?.let { SemanticMemory.bestSimilarity(it, candidates) }

            val fused = SemanticMemory.fuse(listOf(keyword, semantic.map { it.key }))
            val picked = fused.mapNotNull { byKey[it] }

            runCatching {
                diag().event(
                    DiagFormat.Phase.KONTEKST, "pamięć po znaczeniu: wyszukiwanie",
                    mapOf(
                        "dostawca" to provider?.id,
                        "indeks" to vectors.size,
                        "wpisów" to docs.size,
                        "zSłów" to keyword.size,
                        "zWektorów" to semantic.size,
                        "wspólne" to keyword.count { k -> semantic.any { it.key == k } },
                        "wynik" to picked.size,
                        "rodzaje" to picked.joinToString(",") { it.kind.label }.ifEmpty { null },
                        "najlepsze" to best?.let { "%.2f".format(java.util.Locale.ROOT, it) },
                        "embMs" to (if (queryDeferred != null) embedMs else null),
                        "limitCzasu" to timedOut,
                        "błąd" to queryResult?.error,
                        "bezWektora" to (if (queryDeferred == null) {
                            if (vectors.isEmpty()) "indeks pusty" else "brak klucza"
                        } else {
                            null
                        })
                    )
                )
            }
            Outcome(picked)
        }
    }

    companion object {
        private const val TAG = "SemanticMemory"
        private const val INDEX_FILE = "semantic_memory.bin"

        /** Historia jest przycinana do limitu z Ustawień (domyślnie 20) - to sufit z zapasem. */
        private const val HISTORY_POOL = 2_000

        /** Ten sam próg co dotychczasowe dopasowanie słów w orkiestratorze. */
        private const val KEYWORD_MIN_SCORE = 0.15f

        private const val SYNC_DEBOUNCE_MS = 3_000L
    }
}
