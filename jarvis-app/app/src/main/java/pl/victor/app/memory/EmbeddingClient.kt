package pl.victor.app.memory

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Zapytania o embeddingi - jedno miejsce sieci dla [SemanticMemory].
 *
 * Wywołanie jest PRZERYWALNE: przy pytaniu czekamy najwyżej
 * [SemanticMemory.QUERY_TIMEOUT_MS], a anulowana korutyna zrywa połączenie
 * (`Call.cancel`), zamiast zostawiać je w tle do końca odpowiedzi.
 */
class EmbeddingClient {

    /**
     * @param vectors znormalizowane wektory w kolejności tekstów albo `null`
     * @param error opis błędu do dziennika (kod HTTP, wyjątek), bez treści wpisów
     * @param retryable czy błąd może minąć sam (brak sieci, 429, 5xx)
     */
    data class Result(val vectors: List<FloatArray>?, val error: String?, val retryable: Boolean)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * @param texts teksty już w postaci dla modelu ([SemanticMemory.documentText]
     *   albo [SemanticMemory.queryText])
     */
    suspend fun embed(provider: SemanticMemory.Provider, apiKey: String, texts: List<String>): Result {
        if (texts.isEmpty()) return Result(emptyList(), null, false)
        val builder = Request.Builder()
            .url(SemanticMemory.url(provider))
            .post(SemanticMemory.requestBody(provider, texts).toRequestBody(JSON))
        when (provider) {
            SemanticMemory.Provider.GEMINI -> builder.addHeader("x-goog-api-key", apiKey)
            SemanticMemory.Provider.OPENAI -> builder.addHeader("Authorization", "Bearer $apiKey")
        }
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resume(Result(null, e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: ""), true))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    // Wyjątek przy czytaniu ciała (zerwane połączenie) nie może
                    // zostawić korutyny bez odpowiedzi na zawsze.
                    val result = runCatching {
                        response.use { r ->
                            val body = r.body?.string().orEmpty()
                            if (!r.isSuccessful) {
                                Result(null, SemanticMemory.errorSummary(r.code, body), r.code == 429 || r.code >= 500)
                            } else {
                                val vectors = SemanticMemory.parseResponse(provider, body, texts.size)
                                if (vectors == null) {
                                    Result(null, "nieczytelna odpowiedź (${body.length} znaków)", false)
                                } else {
                                    Result(vectors, null, false)
                                }
                            }
                        }
                    }.getOrElse { e -> Result(null, e.javaClass.simpleName, true) }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
