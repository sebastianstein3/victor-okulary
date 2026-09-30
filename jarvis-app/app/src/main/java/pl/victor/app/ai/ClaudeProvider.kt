package pl.victor.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.victor.app.vision.ScannedCode
import java.util.concurrent.TimeUnit

/**
 * Provider Anthropic Claude - Claude Sonnet 4 (multimodal in, text out).
 *
 * API: https://docs.anthropic.com/en/api/messages
 * Model: claude-sonnet-4-20250514
 * Auth: x-api-key: sk-ant-... + anthropic-version header
 *
 * Specjalny nagłówek: anthropic-dangerous-direct-browser-access: true
 * (wymagany dla wywołań z aplikacji mobilnych - Claude API blokuje mobilne origins)
 */
class ClaudeProvider(
    private val apiKey: String,
    val model: String = "claude-sonnet-4-5"
) : AIProvider {

    override val id = "claude"
    override val displayName = "Anthropic Claude Sonnet 4"
    override val supportsNativeAudio = false
    override val supportsWebSearch = false  // wymaga osobnej integracji

    /**
     * Claude 3.5+ obsługuje: images, PDF. Wideo: nie bezpośrednio.
     * Ale Gemini-style multi-frame analizę Claude Vision też ogarnia.
     */
    override val capabilities = ProviderCapabilities(
        supportsImages = true,
        supportsVideo = false,            // Claude nie ma video API
        supportsAudio = false,
        maxImagesPerRequest = 20,         // Claude ma duży kontekst
        maxImageBytes = 5L * 1024 * 1024,
        recommendedImageResolution = ImageResolution.MEDIUM,
        supportsStreaming = true,
        supportsFunctionCalling = true
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun analyze(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray?,
        scannedCodes: List<pl.victor.app.vision.ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): AIResponse = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Claude API key is empty" }

        val finalSystemPrompt = systemPrompt ?: """
            Jesteś asystentem AI wbudowanym w inteligentne okulary.
            Odpowiadaj zwięźle, po polsku. Mów naturalnie, jakbyś rozmawiał.
            Nie używaj formatowania markdown. Max 2-3 zdania.
        """.trimIndent()

        // Claude ma inny format: content to array, nie string
        val contentParts = buildJsonArray {
            // 1. Zdjęcia
            images.forEach { imageBytes ->
                val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                add(buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", base64)
                    })
                })
            }

            // 2. Audio - Claude nie obsługuje bezpośrednio, pomijamy
            if (audioBytes != null) {
                Log.w(TAG, "Claude doesn't support direct audio input, skipping")
            }

            // 3. Tekst
            val finalText = if (textQuestion.isNotBlank()) {
                textQuestion
            } else if (images.isNotEmpty()) {
                "Co widzisz? Opisz krótko po polsku."
            } else {
                "Cześć!"
            }
            add(buildJsonObject {
                put("type", "text")
                put("text", finalText)
            })
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", 1024)
            put("system", finalSystemPrompt)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", contentParts)
                })
            })
        }

        val httpRequest = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("anthropic-dangerous-direct-browser-access", "true")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(httpRequest).executeCancellable { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw AIProviderException(
                        "Claude API error ${response.code}: $errorBody",
                        providerId = id,
                        isRetryable = response.code in 500..599 || response.code == 429
                    )
                }

                val body = response.body?.string() ?: throw AIProviderException(
                    "Empty response",
                    providerId = id
                )
                parseResponse(body)
            }
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Claude call failed", e)
            throw AIProviderException(
                "Network error: ${e.message}",
                providerId = id,
                isRetryable = true
            )
        }
    }

    private fun parseResponse(body: String): AIResponse {
        val resp = json.decodeFromString(ClaudeResponse.serializer(), body)

        val text = resp.content
            ?.filter { it.type == "text" }
            ?.mapNotNull { it.text }
            ?.joinToString(" ")
            ?: throw AIProviderException("No text in Claude response", providerId = id)

        val tokens = (resp.usage?.input_tokens ?: 0) + (resp.usage?.output_tokens ?: 0)

        return AIResponse(
            text = text.trim(),
            sources = emptyList(),
            tokensUsed = tokens,
            providerId = id
        )
    }

    companion object {
        private const val TAG = "ClaudeProvider"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
    }
}

@Serializable
data class ClaudeResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<ClaudeContent>? = null,
    val model: String? = null,
    val usage: ClaudeUsage? = null
)

@Serializable
data class ClaudeContent(
    val type: String,
    val text: String? = null,
    val source: ClaudeSource? = null
)

@Serializable
data class ClaudeSource(
    val type: String? = null,
    val media_type: String? = null,
    val data: String? = null
)

@Serializable
data class ClaudeUsage(
    val input_tokens: Int? = null,
    val output_tokens: Int? = null
)
