package pl.victor.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * Provider MiniMax - MiniMax-Text-01 (text) i MiniMax-VL-01 (vision).
 *
 * API: https://api.minimax.io/v1/text/chatcompletion_v2
 * Model: MiniMax-Text-01 (text only) lub MiniMax-VL-01 (vision, multimodal)
 * Auth: Authorization: Bearer eyJ... (klucze zaczynają się od eyJ - JWT format)
 *
 * MiniMax jest kompatybilny z OpenAI Chat Completions API.
 */
class MiniMaxProvider(
    private val apiKey: String,
    val model: String = "MiniMax-Text-01"
) : AIProvider {

    override val id = "minimax"
    override val displayName = "MiniMax M2/M3"
    override val supportsNativeAudio = false
    override val supportsWebSearch = false  // wbudowany search_tool: optional

    /**
     * MiniMax (M2/M3) - zależy od modelu.
     * Conservative: tylko images, bez video.
     */
    override val capabilities = ProviderCapabilities(
        supportsImages = true,
        supportsVideo = false,
        supportsAudio = false,
        maxImagesPerRequest = 8,
        maxImageBytes = 4L * 1024 * 1024,
        recommendedImageResolution = ImageResolution.MEDIUM,
        supportsStreaming = true,
        supportsFunctionCalling = false
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
        require(apiKey.isNotBlank()) { "MiniMax API key is empty" }

        val finalSystemPrompt = systemPrompt ?: """
            Jesteś asystentem AI wbudowanym w inteligentne okulary.
            Odpowiadaj zwięźle, po polsku. Mów naturalnie, jakbyś rozmawiał.
            Nie używaj formatowania markdown. Max 2-3 zdania.
        """.trimIndent()

        val useVision = images.isNotEmpty() && model == "MiniMax-VL-01"

        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", finalSystemPrompt)
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildUserContent(textQuestion, images, audioBytes, useVision))
            })
        }

        val requestBody = buildJsonObject {
            put("model", if (useVision) "MiniMax-VL-01" else model)
            put("messages", messages)
            put("max_tokens", MAX_ANSWER_TOKENS)
            put("temperature", 0.7)
            // MiniMax-specific: response format
            put("response_format", buildJsonObject {
                put("type", "text")
            })
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
                        "MiniMax API error ${response.code}: $errorBody",
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
            Log.e(TAG, "MiniMax call failed", e)
            throw AIProviderException(
                "Network error: ${e.message}",
                providerId = id,
                isRetryable = true
            )
        }
    }

    private fun buildUserContent(
        question: String,
        images: List<ByteArray>,
        audio: ByteArray?,
        useVision: Boolean
    ) = buildJsonArray {
        // Zdjęcia (tylko VL-01)
        if (useVision) {
            images.forEach { imageBytes ->
                val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:image/jpeg;base64,$base64")
                    })
                })
            }
        }

        // Audio - MiniMax nie obsługuje bezpośrednio
        if (audio != null) {
            Log.w(TAG, "MiniMax doesn't support direct audio input, skipping")
        }

        // Tekst
        val finalText = when {
            question.isNotBlank() -> question
            images.isNotEmpty() -> "Co widzisz? Opisz krótko po polsku."
            else -> "Cześć!"
        }
        add(buildJsonObject {
            put("type", "text")
            put("text", finalText)
        })
    }

    private fun parseResponse(body: String): AIResponse {
        val resp = json.decodeFromString(MiniMaxResponse.serializer(), body)

        val text = resp.choices?.firstOrNull()?.message?.content
            ?: throw AIProviderException("No content in MiniMax response", providerId = id)

        val tokens = resp.usage?.total_tokens ?: 0

        return AIResponse(
            text = text.trim(),
            sources = emptyList(),
            tokensUsed = tokens,
            providerId = id
        )
    }

    companion object {
        private const val TAG = "MiniMaxProvider"

        /**
         * Sufit na odpowiedź - patrz [DeepSeekProvider]. Przy 500 urywał się
         * znacznik akcji doklejany na końcu wypowiedzi.
         */
        private const val MAX_ANSWER_TOKENS = 2000
        private const val API_URL = "https://api.minimax.io/v1/text/chatcompletion_v2"
    }
}

@Serializable
data class MiniMaxResponse(
    val id: String? = null,
    val choices: List<MiniMaxChoice>? = null,
    val usage: MiniMaxUsage? = null,
    val created: Long? = null,
    val model: String? = null
)

@Serializable
data class MiniMaxChoice(
    val index: Int? = null,
    val message: MiniMaxMessage? = null,
    val finish_reason: String? = null
)

@Serializable
data class MiniMaxMessage(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class MiniMaxUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)
