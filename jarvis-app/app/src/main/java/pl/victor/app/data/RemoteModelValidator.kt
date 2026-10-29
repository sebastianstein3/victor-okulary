package pl.victor.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Walidator modeli - sprawdza przez API providera czy dany model nadal istnieje.
 *
 * Nie modyfikuje ModelRegistry - tylko raportuje co widzi.
 * Na podstawie tych danych SmartModelResolver decyduje co zrobić.
 */
class RemoteModelValidator(
    private val apiKey: String,
    private val providerId: String
) {
    private val tag = "RemoteModelValidator"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pobiera listę modeli dostępnych dla tego providera i klucza API.
     * Zwraca listę ID modeli (np. ["gemini-2.5-flash", "gemini-2.5-pro"]).
     * Pusta lista jeśli provider nie ma endpointu lub błąd.
     */
    suspend fun fetchAvailableModels(): List<String> = withContext(Dispatchers.IO) {
        // Lista modeli nie zmienia się w ciągu godzin, a pobieranie jej przy KAŻDEJ
        // turze (i każdej próbie dostawcy zapasowego) dokładało opóźnienie i
        // zapytanie sieciowe przed modelem (audyt 28.10). Cache na 6 h per
        // dostawca i klucz; pusty wynik (błąd) nie jest zapamiętywany.
        val cacheKey = providerId + ":" + apiKey.hashCode()
        cache[cacheKey]?.let { (kiedy, lista) ->
            if (System.currentTimeMillis() - kiedy < CACHE_MS) return@withContext lista
        }
        val wynik = pobierz()
        if (wynik.isNotEmpty()) cache[cacheKey] = System.currentTimeMillis() to wynik
        wynik
    }

    private suspend fun pobierz(): List<String> {
        return try {
            when (providerId) {
                "gemini" -> fetchGemini()
                "openai" -> fetchOpenAI()
                "claude" -> fetchClaude()
                "minimax" -> fetchMiniMax()
                "deepseek" -> fetchDeepSeek()
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to fetch models for $providerId: ${e.message}")
            emptyList()
        }
    }

    private companion object {
        const val CACHE_MS = 6 * 3_600_000L
        const val MAX_PAGES = 5
        val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()
    }

    /**
     * Sprawdza czy konkretny model istnieje u providera.
     */
    suspend fun isModelAvailable(modelId: String): Boolean {
        val available = fetchAvailableModels()
        return available.isEmpty() || available.contains(modelId)
    }

    private fun fetchGemini(): List<String> {
        // GET https://generativelanguage.googleapis.com/v1beta/models, klucz w
        // nagłówku (w adresie lądował w logach i historii). Lista jest
        // STRONICOWANA (domyślnie 50) - bez pętli po nextPageToken poprawny model
        // z dalszej strony uchodził za "nieistniejący" i był po cichu podmieniany.
        val wynik = mutableListOf<String>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val url = StringBuilder("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
            token?.let { url.append("&pageToken=").append(it) }
            val request = Request.Builder()
                .url(url.toString())
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(tag, "Gemini models list: HTTP ${response.code}")
                    return if (wynik.isEmpty()) emptyList() else wynik
                }
                val body = response.body?.string() ?: return wynik
                val obj = json.parseToJsonElement(body).jsonObject
                obj["models"]?.jsonArray?.forEach { element ->
                    val name = element.jsonObject["name"]?.jsonPrimitive?.content ?: return@forEach
                    // Format: "models/gemini-2.5-flash" -> "gemini-2.5-flash"
                    wynik += name.removePrefix("models/")
                }
                token = obj["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            }
            if (token == null) return wynik
        }
        return wynik
    }

    private suspend fun fetchOpenAI(): List<String> {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "OpenAI models list: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val obj = json.parseToJsonElement(body).jsonObject
            val models = obj["data"]?.jsonArray ?: return emptyList()
            return models.mapNotNull { element ->
                element.jsonObject["id"]?.jsonPrimitive?.content
            }
        }
    }

    private suspend fun fetchClaude(): List<String> {
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models?limit=100")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "Anthropic models list: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val obj = json.parseToJsonElement(body).jsonObject
            val models = obj["data"]?.jsonArray ?: return emptyList()
            return models.mapNotNull { element ->
                element.jsonObject["id"]?.jsonPrimitive?.content
            }
        }
    }

    /**
     * DeepSeek wystawia listę modeli pod endpointem zgodnym z OpenAI.
     *
     * To jest jedyne źródło prawdy o tym, co da się u nich wybrać: wpisana na
     * sztywno lista starzeje się w tygodniach i kończy błędem "model nie
     * istnieje" przy pierwszym pytaniu.
     */
    private suspend fun fetchDeepSeek(): List<String> {
        val request = Request.Builder()
            .url("https://api.deepseek.com/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "DeepSeek models list: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val obj = json.parseToJsonElement(body).jsonObject
            val data = obj["data"]?.jsonArray ?: return emptyList()
            return data.mapNotNull { element ->
                element.jsonObject["id"]?.jsonPrimitive?.content
            }
        }
    }

    private suspend fun fetchMiniMax(): List<String> {
        val request = Request.Builder()
            .url("https://api.minimax.io/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "MiniMax models list: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val obj = json.parseToJsonElement(body).jsonObject
            val data = obj["data"]?.jsonArray ?: return emptyList()
            return data.mapNotNull { element ->
                element.jsonObject["id"]?.jsonPrimitive?.content
            }
        }
    }
}

/**
 * Wynik walidacji modelu.
 */
sealed class ModelValidationResult {
    /** Model istnieje u providera i jest aktywny */
    object Available : ModelValidationResult()

    /** Model nie istnieje u providera (404 lub nie ma na liście) */
    object NotFound : ModelValidationResult()

    /** Model istnieje ale jest deprecated */
    data class Deprecated(val replacementId: String?) : ModelValidationResult()

    /** Nie udało się sprawdzić (błąd sieci, brak endpointu) */
    data class Unknown(val reason: String) : ModelValidationResult()
}
