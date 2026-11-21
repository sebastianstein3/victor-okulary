package pl.victor.app.memory

import java.util.Locale

/**
 * Dopasowanie słów kluczowych (TF-IDF + cosinus) - wydzielone z
 * [LongTermMemory], żeby to samo liczenie służyło historii rozmów i pamięci
 * po znaczeniu ([SemanticMemory]), i żeby dało się je sprawdzić testem bez
 * Androida.
 *
 * Matematyka jest DOKŁADNIE ta, co wcześniej w [LongTermMemory] - wyłączona
 * pamięć po znaczeniu ma zachowywać się jak dotąd.
 */
object KeywordRanker {

    /** Stopwords PL + EN. */
    private val stopwords = setOf(
        "i", "w", "na", "z", "do", "to", "że", "jest", "się", "nie",
        "the", "a", "an", "is", "are", "was", "were", "be", "been",
        "of", "in", "on", "at", "by", "for", "with", "as", "from"
    )

    /**
     * Ocena każdego tekstu względem zapytania.
     *
     * @return wyniki w kolejności [texts] (0 = brak wspólnych słów)
     */
    fun scores(query: String, texts: List<String>): List<Float> {
        if (texts.isEmpty()) return emptyList()
        val queryTf = computeTf(tokenize(query))
        val tokenized = texts.map { tokenize(it) }
        val docFreq = mutableMapOf<String, Int>()
        tokenized.forEach { tokens ->
            tokens.toSet().forEach { token -> docFreq[token] = (docFreq[token] ?: 0) + 1 }
        }
        val idf = computeIdf(docFreq, texts.size)
        return tokenized.map { cosineSimilarity(queryTf, computeTf(it), idf) }
    }

    /**
     * Indeksy tekstów od najlepiej pasującego, tylko z wynikiem co najmniej
     * [minScore].
     */
    fun rank(query: String, texts: List<String>, minScore: Float, limit: Int): List<Pair<Int, Float>> =
        scores(query, texts)
            .withIndex()
            .filter { it.value >= minScore }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.index to it.value }

    /** Tokenizacja - dzieli tekst na słowa, normalizuje, usuwa stopwords. */
    fun tokenize(text: String): List<String> {
        return text.lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")  // tylko litery i cyfry
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopwords }
    }

    /** TF (term frequency) - jak często słowo w dokumencie. */
    private fun computeTf(tokens: List<String>): Map<String, Float> {
        val tf = mutableMapOf<String, Float>()
        tokens.forEach { token -> tf[token] = (tf[token] ?: 0f) + 1f }
        val max = tf.values.maxOrNull() ?: 1f
        return tf.mapValues { it.value / max }
    }

    /** IDF (inverse document frequency) - jak rzadkie słowo w korpusie. */
    private fun computeIdf(docFreq: Map<String, Int>, totalDocs: Int): Map<String, Float> {
        return docFreq.mapValues { (_, df) ->
            Math.log((totalDocs.toFloat() / (df + 1)).toDouble()).toFloat() + 1f
        }
    }

    /** Cosine similarity między dwoma wektorami TF-IDF. */
    private fun cosineSimilarity(
        queryTf: Map<String, Float>,
        docTf: Map<String, Float>,
        idf: Map<String, Float>
    ): Float {
        var dot = 0f
        var queryNorm = 0f
        var docNorm = 0f
        val allTerms = queryTf.keys + docTf.keys
        allTerms.forEach { term ->
            val qVal = (queryTf[term] ?: 0f) * (idf[term] ?: 0f)
            val dVal = (docTf[term] ?: 0f) * (idf[term] ?: 0f)
            dot += qVal * dVal
            queryNorm += qVal * qVal
            docNorm += dVal * dVal
        }
        val denominator = Math.sqrt((queryNorm * docNorm).toDouble())
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }
}
