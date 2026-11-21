package pl.victor.app.memory

import android.content.Context
import android.util.Log
import pl.victor.app.data.ConversationEntry
import pl.victor.app.data.HistoryRepository
import java.security.MessageDigest

/**
 * Prosta pamięć długoterminowa - TF-IDF similarity (bez zewnętrznych bibliotek).
 *
 * Dopasowuje SŁOWA - liczenie jest w [KeywordRanker]. Wyszukiwanie po
 * ZNACZENIU (embeddingi) dokłada [SemanticMemory]; to tutaj zostaje darmowym
 * odpowiednikiem, gdy tamto jest wyłączone.
 *
 * Use case:
 * - User pyta "Pamiętasz o czym rozmawialiśmy z Anią?"
 * - Apka szuka w historii podobnych rozmów
 * - Wkłada kontekst do promptu AI
 */
class LongTermMemory(
    private val context: Context,
    private val history: HistoryRepository
) {
    private val tag = "LongTermMemory"

    /**
     * Szuka podobnych rozmów w historii.
     * Wymaga listy wpisów (pobranej wcześniej przez obserwatora).
     *
     * @param query tekst zapytania
     * @param entries lista rozmów (z HistoryRepository)
     * @param limit ile wyników
     * @return lista posortowana po similarity
     */
    fun findSimilar(query: String, entries: List<ConversationEntry>, limit: Int = 5): List<MemoryMatch> {
        Log.d(tag, "Searching for: \"$query\" among ${entries.size} entries")
        return findSimilarInList(query, entries, limit)
    }

    /**
     * Wersja bezpośrednia - przyjmuje listę wpisów i znajduje pasujące.
     */
    fun findSimilarInList(query: String, entries: List<ConversationEntry>, limit: Int = 5): List<MemoryMatch> {
        if (entries.isEmpty()) return emptyList()
        // Liczenie wydzielone do [KeywordRanker] (to samo służy pamięci po
        // znaczeniu) - wynik i próg bez zmian.
        val scores = KeywordRanker.scores(query, entries.map { it.userQuestion + " " + it.aiResponse })
        return entries.mapIndexed { index, entry -> MemoryMatch(entry, scores[index]) }
            .filter { it.score > 0.1f }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Generuje hash ID dla pytania (do cache'owania).
     */
    fun hashQuery(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
}

data class MemoryMatch(
    val entry: ConversationEntry,
    val score: Float  // 0.0 - 1.0+
) {
    fun summary(): String = "Q: ${entry.userQuestion.take(60)}... A: ${entry.aiResponse.take(80)}..."
}
