package pl.victor.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Provider DeepSeek.
 *
 * API: https://api.deepseek.com/chat/completions - zgodne z OpenAI Chat
 * Completions, więc format zapytania i odpowiedzi jest ten sam co u OpenAI.
 *
 * ## O modelach
 * Lista modeli NIE jest tu wpisana na sztywno. DeepSeek zmienia ofertę
 * (nazwy, warianty, nowe rodziny), a wpisana lista starzeje się w tygodniach i
 * kończy błędem "model nie istnieje" przy pierwszym pytaniu. Aplikacja pyta
 * o listę samo API (`GET /models`, patrz
 * [pl.victor.app.data.RemoteModelValidator]) i pokazuje to, co naprawdę
 * odpowiada - patrz [pl.victor.app.data.ModelCatalog].
 *
 * ## O obrazach
 * Modele z rodziny DeepSeek-VL, VL2 i Janus są publikowane jako otwarte wagi
 * (Hugging Face), a nie serwowane przez to API. Jeśli kiedyś się tu pojawią,
 * wejdą do listy same - dlatego [ProviderCapabilities.supportsImages] jest
 * włączone, a decyzję "czy TEN model przyjmie zdjęcie" podejmuje wybór modelu,
 * nie provider.
 */
class DeepSeekProvider(
    private val apiKey: String,
    val model: String = DEFAULT_MODEL
) : AIProvider {

    override val id = "deepseek"
    override val displayName = "DeepSeek"
    override val supportsNativeAudio = false
    override val supportsWebSearch = false

    override val capabilities = ProviderCapabilities(
        supportsImages = true,
        supportsVideo = false,
        supportsAudio = false,
        maxImagesPerRequest = 4,
        maxImageBytes = 4L * 1024 * 1024,
        recommendedImageResolution = ImageResolution.MEDIUM,
        supportsStreaming = true,
        supportsFunctionCalling = true
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
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
        require(apiKey.isNotBlank()) { "DeepSeek API key is empty" }

        val finalSystemPrompt = systemPrompt ?: """
            Jesteś asystentem AI wbudowanym w inteligentne okulary.
            Odpowiadaj zwięźle, po polsku. Mów naturalnie, jakbyś rozmawiał.
            Nie używaj formatowania markdown. Max 2-3 zdania.
        """.trimIndent()

        if (audioBytes != null) {
            Log.w(TAG, "DeepSeek nie przyjmuje audio - pomijam nagranie")
        }

        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", finalSystemPrompt)
            })
            add(buildJsonObject {
                put("role", "user")
                // Modele tekstowe odrzucają tablicę części z obrazem, więc
                // przy braku zdjęć wysyłamy zwykły string - tak jak robi to
                // każdy klient zgodny z OpenAI.
                if (images.isEmpty()) {
                    put("content", textFor(textQuestion, images))
                } else {
                    put("content", buildUserContent(textQuestion, images))
                }
            })
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("max_tokens", MAX_ANSWER_TOKENS)
            put("temperature", 0.7)
            put("stream", false)
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
                        "DeepSeek API error ${response.code}: $errorBody",
                        providerId = id,
                        isRetryable = response.code in 500..599 || response.code == 429
                    )
                }
                val body = response.body?.string()
                    ?: throw AIProviderException("Empty response", providerId = id)
                parseResponse(body)
            }
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek call failed", e)
            throw AIProviderException(
                "Network error: ${e.message}",
                providerId = id,
                isRetryable = true
            )
        }
    }

    private fun textFor(question: String, images: List<ByteArray>): String = when {
        question.isNotBlank() -> question
        images.isNotEmpty() -> "Co widzisz? Opisz krótko po polsku."
        else -> "Cześć!"
    }

    private fun buildUserContent(question: String, images: List<ByteArray>) = buildJsonArray {
        images.forEach { imageBytes ->
            val base64 = android.util.Base64.encodeToString(
                imageBytes,
                android.util.Base64.NO_WRAP
            )
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$base64") })
            })
        }
        add(buildJsonObject {
            put("type", "text")
            put("text", textFor(question, images))
        })
    }

    private fun parseResponse(body: String): AIResponse {
        val resp = json.decodeFromString(MiniMaxResponse.serializer(), body)
        val choice = resp.choices?.firstOrNull()
        val text = choice?.message?.content?.takeIf { it.isNotBlank() }
            ?: throw AIProviderException(
                // Powód, nie samo "brak treści". Najczęstszy jest jeden i da się
                // go rozpoznać: model rozumujący wyczerpał limit na samo
                // myślenie i na odpowiedź nie zostało nic. Przy
                // finish_reason="length" komunikat ma mówić WPROST, że to limit,
                // bo inaczej wygląda to jak awaria dostawcy.
                if (choice?.finish_reason == "length") {
                    "DeepSeek urwał odpowiedź na limicie tokenów (finish_reason=length) - " +
                        "model zużył budżet, zanim cokolwiek powiedział. " +
                        "Wybierz w ustawieniach model bez rozumowania."
                } else {
                    "DeepSeek nie odesłał treści (finish_reason=${choice?.finish_reason})"
                },
                providerId = id,
                isRetryable = choice?.finish_reason != "length"
            )
        return AIResponse(
            text = text.trim(),
            sources = emptyList(),
            tokensUsed = resp.usage?.total_tokens ?: 0,
            providerId = id
        )
    }

    companion object {
        private const val TAG = "DeepSeekProvider"

        /**
         * Sufit na odpowiedź. Było 500 i to było za mało - z dwóch powodów.
         *
         * Po pierwsze, model dokleja na końcu znacznik `[[ACTION: ...]]`, którym
         * prosi o wykonanie czynności. Gdy generowanie urywa się na limicie,
         * urywa się WŁAŚNIE ON - odpowiedź brzmi normalnie, a nic się nie
         * dzieje. Zgłoszone jako "nie robi tego, co mu każę".
         *
         * Po drugie, modele rozumujące (deepseek-reasoner) liczą do tego limitu
         * także swoje rozumowanie. Pięćset tokenów potrafi zejść na samo
         * myślenie i wtedy `content` wraca PUSTE - czyli "puste pole" ze
         * zgłoszenia.
         *
         * Odpowiedzi i tak są krótkie (prompt każe mówić 2-3 zdaniami), więc
         * wyższy sufit nic nie kosztuje: płaci się za tokeny wygenerowane, nie
         * za dozwolone.
         */
        private const val MAX_ANSWER_TOKENS = 2000
        private const val API_URL = "https://api.deepseek.com/chat/completions"

        /** Model startowy; realną listę i tak przynosi API. */
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}
