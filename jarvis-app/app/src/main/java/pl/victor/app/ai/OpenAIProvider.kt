package pl.victor.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
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
 * Provider OpenAI - GPT-4o (multimodal in, text out).
 *
 * API: https://platform.openai.com/docs/api-reference/chat/create
 * Model: gpt-4o (vision) lub gpt-4o-mini (tańszy)
 * Auth: Authorization: Bearer sk-...
 *
 * Web search: OpenAI ma własne narzędzie (web_search) ale wymaga specjalnego
 * modelu. Na razie zwracamy własną wiedź.
 */
class OpenAIProvider(
    private val apiKey: String,
    val model: String = "gpt-4o-mini"
) : AIProvider {

    override val id = "openai"
    override val displayName = "OpenAI GPT-4o"
    override val supportsNativeAudio = false
    override val supportsWebSearch = false  // wymaga osobnej konfiguracji

    /**
     * GPT-4o obsługuje: images (frames z wideo też akceptuje).
     * Wideo: tylko jako sekwencja klatek. Nie ma native video.
     */
    override val capabilities = ProviderCapabilities(
        supportsImages = true,
        supportsVideo = false,            // GPT-4o vision nie ma video API
        supportsAudio = false,            // tylko przez Whisper transkrypcja
        maxImagesPerRequest = 10,
        maxImageBytes = 4L * 1024 * 1024,
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
        require(apiKey.isNotBlank()) { "OpenAI API key is empty" }

        val finalSystemPrompt = systemPrompt ?: """
            Jesteś asystentem AI wbudowanym w inteligentne okulary.
            Odpowiadaj zwięźle, po polsku. Mów naturalnie, jakbyś rozmawiał.
            Nie używaj formatowania markdown. Max 2-3 zdania.
        """.trimIndent()

        val userContentParts = buildJsonArray {
            // 1. Zdjęcia (multimodal vision)
            images.forEach { imageBytes ->
                val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:image/jpeg;base64,$base64")
                    })
                })
            }

            // 2. Audio (wav/mp3 - OpenAI obsługuje)
            audioBytes?.let { audio ->
                val base64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
                add(buildJsonObject {
                    put("type", "input_audio")
                    put("input_audio", buildJsonObject {
                        put("data", base64)
                        put("format", "wav")
                    })
                })
            }

            // 3. Tekst pytania
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
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", finalSystemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContentParts)
                })
            })
            put("max_tokens", MAX_ANSWER_TOKENS)
            put("temperature", 0.7)
        }

        val httpRequest = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(httpRequest).executeCancellable { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw AIProviderException(
                        "OpenAI API error ${response.code}: $errorBody",
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
            Log.e(TAG, "OpenAI call failed", e)
            throw AIProviderException(
                "Network error: ${e.message}",
                providerId = id,
                isRetryable = true
            )
        }
    }

    private fun parseResponse(body: String): AIResponse {
        val resp = json.decodeFromString(OpenAIResponse.serializer(), body)

        val text = resp.choices?.firstOrNull()?.message?.content
            ?: throw AIProviderException("No content in OpenAI response", providerId = id)

        val tokens = resp.usage?.total_tokens ?: 0

        return AIResponse(
            text = text.trim(),
            sources = emptyList(),
            tokensUsed = tokens,
            providerId = id
        )
    }

    companion object {
        private const val TAG = "OpenAIProvider"

        /**
         * Sufit na odpowiedź - patrz [DeepSeekProvider]. Przy 500 urywał się
         * znacznik akcji doklejany na końcu wypowiedzi.
         */
        private const val MAX_ANSWER_TOKENS = 2000
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
    }
}

@Serializable
data class OpenAIResponse(
    val id: String? = null,
    val choices: List<OpenAIChoice>? = null,
    val usage: OpenAIUsage? = null
)

@Serializable
data class OpenAIChoice(
    val index: Int? = null,
    val message: OpenAIMessage? = null,
    val finish_reason: String? = null
)

@Serializable
data class OpenAIMessage(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class OpenAIUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)
