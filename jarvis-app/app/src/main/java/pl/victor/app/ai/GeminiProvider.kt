package pl.victor.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.victor.app.vision.ScannedCode
import java.util.concurrent.TimeUnit

/**
 * Provider dla Google Gemini 2.5 Flash.
 * Obsługuje: text + image + audio IN, text OUT.
 * Wbudowany web search (Google Search grounding).
 *
 * Obsługuje oba formaty klucza:
 * - Stary: AIza... (Standard Key) - działa do września 2026
 * - Nowy: AQ.Ab... (Auth Key) - zalecany od 2026
 *
 * Używa NATYWNEGO endpointu (nie OpenAI-compatible):
 *   https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key=...
 *
 * UWAGA: Nie zawiera klucza API. Klucz przekazywany z SettingsRepository.
 */
class GeminiProvider(
    private val apiKey: String,
    val model: String = "gemini-2.5-flash",
    /**
     * Czy prosić model, żeby nie rozmyślał przed odpowiedzią.
     *
     * Domyślnie NIE, bo to zmiana jakości, a nie tylko kosztu - i decyzja
     * należy do użytkownika. Pomiar z 14 września pokazuje, o co gra idzie:
     *
     *     wejście=1288  odpowiedź=50  myślenie=273  razem=1611
     *     wejście=1288  odpowiedź=42  myślenie=441  razem=1771
     *
     * Myślenie zjada cztery do ośmiu razy więcej niż sama odpowiedź, a tokeny
     * wyjściowe są najdroższe. Ale krótsze myślenie to też gorsze rozumowanie,
     * więc włącza się to świadomie w ustawieniach.
     */
    private val limitThinking: Boolean = false
) : AIProvider {

    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val supportsNativeAudio = false  // audio out przez Android TTS
    override val supportsWebSearch = true      // Google Search grounding

    /**
     * Gemini 1.5+ obsługuje: images, video, audio, streaming.
     * Max: 3600s wideo, 20MB inline / 2GB przez Files API.
     */
    override val capabilities = ProviderCapabilities(
        supportsImages = true,
        supportsVideo = true,         // Gemini 1.5+ ma video understanding
        supportsAudio = true,         // multimodal in
        maxImagesPerRequest = 16,
        maxVideoBytes = 20L * 1024 * 1024,   // 20MB inline
        maxImageBytes = 4L * 1024 * 1024,
        recommendedImageResolution = ImageResolution.MEDIUM,
        supportsStreaming = true,
        supportsFunctionCalling = true
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Analiza wideo (Gemini 1.5+ obsługuje natywnie).
     * Wysyła MP4 inline (max 20MB) do endpoint.
     */
    override suspend fun analyzeVideo(
        textQuestion: String,
        videoBytes: ByteArray,
        videoDurationMs: Long,
        audioBytes: ByteArray?,
        scannedCodes: List<ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): AIResponse {
        if (videoBytes.size > capabilities.maxVideoBytes) {
            Log.w("GeminiProvider", "Wideo ${videoBytes.size} > max ${capabilities.maxVideoBytes}, " +
                    "próbuję zmniejszyć lub wrócić do analizy klatek")
            // Fallback - tu w produkcji byłaby kompresja lub extract frames
            // Na teraz rzucamy exception - lepiej wiedzieć
            throw AIProviderException(
                "Wideo za duże (${videoBytes.size} > ${capabilities.maxVideoBytes}). " +
                "Spróbuj krótszego lub niższej jakości.",
                providerId = id,
                isRetryable = false
            )
        }

        val url = buildString {
            append("https://generativelanguage.googleapis.com/v1beta/models/")
            append(model)
            append(":generateContent?key=")
            append(apiKey)
        }

        // Inline data z MIME type video/mp4
        val base64Video = android.util.Base64.encodeToString(videoBytes, android.util.Base64.NO_WRAP)
        val videoPart = buildJsonObject {
            put("inlineData", buildJsonObject {
                put("mimeType", "video/mp4")
                put("data", base64Video)
            })
        }

        val textPart = buildJsonObject {
            put("text", textQuestion.ifBlank { "Co widzisz na tym wideo? Opisz szczegółowo." })
        }

        val contents = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(videoPart)
                    add(textPart)
                }
            })
        }

        val requestBody = buildJsonObject {
            put("contents", contents)
            if (systemPrompt != null) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", systemPrompt) })
                    }
                }
            }
            if (enableWebSearch) {
                putJsonArray("tools") {
                    add(buildJsonObject { put("googleSearch", buildJsonObject {}) })
                }
            }
            // Ta ścieżka składa JSON ręcznie, więc limit trzeba tu dopisać
            // OSOBNO - inaczej analiza wideo zostałaby jedynym zapytaniem bez
            // żadnej granicy, czyli najdroższym z nich wszystkich.
            putJsonObject("generationConfig") {
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                if (limitThinking && !thinkingRejected) {
                    putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
                }
            }
        }

        return try {
            val body = requestBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw AIProviderException(
                        explainHttpError(response.code, responseBody),
                        providerId = id,
                        isRetryable = response.code in 500..599
                    )
                }
                parseResponse(responseBody)
            }
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            throw AIProviderException(
                "Video analysis failed: ${e.message}",
                providerId = id,
                isRetryable = true,
                cause = e
            )
        }
    }

    /**
     * Zapytanie z JEDNYM ponowieniem, gdy API odrzuci prośbę o ograniczenie
     * myślenia.
     *
     * Ponowienie jest darmowe pod względem treści - odrzucenie przychodzi PRZED
     * wygenerowaniem czegokolwiek, więc nie ma czego zdublować. Zdarza się raz
     * na uruchomienie, bo odmowa jest zapamiętywana.
     */
    override suspend fun analyze(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray?,
        scannedCodes: List<ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): AIResponse = try {
        analyzeOnce(
            textQuestion = textQuestion,
            images = images,
            audioBytes = audioBytes,
            scannedCodes = scannedCodes,
            enableWebSearch = enableWebSearch,
            systemPrompt = systemPrompt
        )
    } catch (e: ThinkingRejectedRetry) {
        analyzeOnce(
            textQuestion = textQuestion,
            images = images,
            audioBytes = audioBytes,
            scannedCodes = scannedCodes,
            enableWebSearch = enableWebSearch,
            systemPrompt = systemPrompt
        )
    }

    private suspend fun analyzeOnce(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray?,
        scannedCodes: List<ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): AIResponse = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API key is empty" }

        val url = "$API_BASE/$model:generateContent?key=$apiKey"

        // Zbuduj request do Gemini API
        val parts = mutableListOf<GeminiPart>()

        // 1. Zdjęcia (inline base64)
        images.forEach { imageBytes ->
            parts.add(
                GeminiPart(
                    inlineData = GeminiInlineData(
                        mimeType = "image/jpeg",
                        data = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                    )
                )
            )
        }

        // 2. Audio (opcjonalne)
        audioBytes?.let { audio ->
            parts.add(
                GeminiPart(
                    inlineData = GeminiInlineData(
                        mimeType = "audio/wav",
                        data = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
                    )
                )
            )
        }

        // 3. Prompt (użyj persony jeśli podana)
        val prompt = buildPrompt(
            question = textQuestion,
            hasImages = images.isNotEmpty(),
            scannedCodes = scannedCodes,
            systemPrompt = systemPrompt
        )
        parts.add(GeminiPart(text = prompt))

        val tools = if (enableWebSearch) {
            listOf(GeminiTool(googleSearch = GoogleSearchTool()))
        } else null

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = parts)),
            tools = tools,
            generationConfig = generationConfig()
        )

        val requestBody = json.encodeToString(GeminiRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    // Odmowa dotycząca myślenia jest DO NAPRAWIENIA W LOCIE:
                    // zapamiętujemy ją i powtarzamy zapytanie bez tej prośby,
                    // zamiast zwracać użytkownikowi błąd za coś, co jest tylko
                    // optymalizacją kosztu.
                    if (noteThinkingRejected(response.code, errorBody)) {
                        throw ThinkingRejectedRetry()
                    }
                    throw AIProviderException(
                        explainHttpError(response.code, errorBody),
                        providerId = id,
                        isRetryable = response.code in 500..599 || response.code == 429
                    )
                }

                val body = response.body?.string() ?: throw AIProviderException(
                    "Empty response body",
                    providerId = id
                )

                parseResponse(body)
            }
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API call failed", e)
            throw AIProviderException(
                "Network error: ${e.message}",
                providerId = id,
                isRetryable = true
            )
        }
    }

    private fun buildPrompt(
        question: String,
        hasImages: Boolean,
        scannedCodes: List<ScannedCode> = emptyList(),
        systemPrompt: String? = null
    ): String {
        val baseContext = if (hasImages) {
            "User wysłał $IMAGES_IN_REQUEST zdjęć ze swojego otoczenia (POV) i zadaje pytanie."
        } else {
            "User zadaje pytanie tekstowe."
        }

        // Jeśli persona podana - użyj jej jako system prompt
        // W przeciwnym razie użyj domyślnego
        val systemContext = systemPrompt ?: """
            Jesteś asystentem AI wbudowanym w inteligentne okulary. Odpowiadaj zwięźle, po polsku.
            Jeśli widzisz coś na zdjęciach, opisz to krótko i odnieś do pytania.
            Nie używaj formatowania markdown. Mów naturalnie, jakbyś rozmawiał.
        """.trimIndent()

        // QR kody - specjalna obsługa
        val qrSection = if (scannedCodes.isNotEmpty()) {
            val codes = scannedCodes.joinToString("\n") { "- [${it.type}] ${it.describe()}" }
            "\n\nWykryte kody (QR/barcode) na zdjęciach:\n$codes\n\nJeśli to URL - odwiedź i streść. Jeśli wizytówka - przedstaw kontakt. Jeśli WiFi - powiedz hasło."
        } else {
            ""
        }

        val userPart = if (question.isNotBlank()) {
            "\n\nPytanie: $question"
        } else {
            "\n\n(Pytanie może być w audio - jeśli tak, odpowiedz na nie)"
        }

        return "$systemContext\n\n$baseContext$qrSection$userPart"
    }

    private fun parseResponse(body: String): AIResponse {
        val geminiResponse = json.decodeFromString(GeminiResponse.serializer(), body)

        val text = geminiResponse.candidates
            ?.firstOrNull()
            ?.content
            ?.parts
            ?.filter { it.text != null }
            ?.joinToString(" ") { it.text!! }
            ?: throw AIProviderException("No text in Gemini response", providerId = id)

        val sources = geminiResponse.candidates
            ?.firstOrNull()
            ?.groundingMetadata
            ?.groundingChunks
            ?.mapNotNull { chunk ->
                chunk.web?.let { web ->
                    Source(
                        title = web.title ?: "",
                        url = web.uri ?: "",
                        snippet = ""
                    )
                }
            }
            ?: emptyList()

        val tokens = geminiResponse.usageMetadata?.totalTokenCount ?: 0
        reportUsage(geminiResponse)

        return AIResponse(
            text = text.trim(),
            sources = sources,
            tokensUsed = tokens,
            providerId = id
        )
    }

    /**
     * Zapisuje w dzienniku, NA CO poszły tokeny tej odpowiedzi.
     *
     * ## Po co
     * Bo "2817 tokenów" nie wyjaśnia rachunku, tylko go pogłębia. Polecenie
     * miało circa 250 tokenów, odpowiedź circa 100 - reszta była niewidoczna, a
     * na rachunku w Google Cloud najdroższą pozycją jest SKU tokenów
     * wyjściowych. Rozbicie odpowiada na to jedną linijką, zamiast zostawiać
     * mnie z hipotezą o myśleniu.
     *
     * Nie zgadujemy przy tym cen: liczby są prawdą, stawki zmieniają się bez
     * naszej wiedzy i to rachunek jest od nich.
     */
    private fun reportUsage(response: GeminiResponse) {
        val usage = response.usageMetadata ?: return
        val finish = response.candidates?.firstOrNull()?.finishReason
        runCatching {
            pl.victor.app.VictorApplication.get().diag.event(
                pl.victor.app.diagnostics.DiagFormat.Phase.MODEL,
                if (finish == "MAX_TOKENS") "zużycie tokenów - ODPOWIEDŹ UCIĘTA NA LIMICIE"
                else "zużycie tokenów",
                mapOf(
                    "wejście" to usage.promptTokenCount,
                    "odpowiedź" to usage.candidatesTokenCount,
                    "myślenie" to usage.thoughtsTokenCount,
                    "razem" to usage.totalTokenCount,
                    "limit" to MAX_OUTPUT_TOKENS,
                    "koniec" to finish
                )
            )
        }
    }

    /**
     * Konfiguracja generowania dla tego zapytania.
     *
     * Prośbę o ograniczenie myślenia dokładamy tylko wtedy, gdy użytkownik ją
     * włączył I gdy API jeszcze jej nie odrzuciło.
     */
    private fun generationConfig(): GeminiGenerationConfig = GeminiGenerationConfig(
        maxOutputTokens = MAX_OUTPUT_TOKENS,
        thinkingConfig = if (limitThinking && !thinkingRejected) {
            GeminiThinkingConfig(thinkingBudget = 0)
        } else {
            null
        }
    )

    /**
     * Rozpoznaje odmowę dotyczącą myślenia i zapamiętuje ją na stałe.
     *
     * @return `true` gdy warto powtórzyć zapytanie BEZ tej prośby
     */
    private fun noteThinkingRejected(code: Int, body: String): Boolean {
        if (!limitThinking || thinkingRejected) return false
        if (code != HTTP_BAD_REQUEST) return false
        if (!body.contains("thinking", ignoreCase = true)) return false
        thinkingRejected = true
        runCatching {
            pl.victor.app.VictorApplication.get().diag.event(
                pl.victor.app.diagnostics.DiagFormat.Phase.MODEL,
                "Gemini odrzucił prośbę o ograniczenie myślenia - ponawiam bez niej",
                mapOf("odpowiedź" to body.take(ERROR_BODY_CHARS))
            )
        }
        Log.w(TAG, "Prośba o ograniczenie myślenia odrzucona: ${body.take(ERROR_BODY_CHARS)}")
        return true
    }

    companion object {
        private const val TAG = "GeminiProvider"

        /**
         * Czy API odrzuciło już prośbę o ograniczenie myślenia.
         *
         * Wspólne dla wszystkich egzemplarzy i na całe uruchomienie: skoro
         * nazwa pola nie pasuje, nie pasuje dla każdego zapytania, a powtarzanie
         * odrzucanej próby kosztowałoby dodatkowy obieg za każdym razem.
         */
        @Volatile
        private var thinkingRejected = false

        private const val HTTP_BAD_REQUEST = 400

        /** Ile znaków odpowiedzi serwera zapisać przy odmowie. */
        private const val ERROR_BODY_CHARS = 200
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val STREAM_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val IMAGES_IN_REQUEST = 5

        /**
         * Górna granica tokenów odpowiedzi - razem z myśleniem.
         *
         * Dobrana z zapasem, bo dokumentacja ostrzega wprost: limit obejmuje
         * tokeny myślenia, więc za ciasny potrafi oddać PUSTKĘ zamiast zdania.
         * Tysiąc dwieście mieści długą odpowiedź głosową (circa 3000 znaków) i
         * spory zapas na myślenie, a przycina dopiero to, czego i tak nikt nie
         * wysłucha - w dzienniku z 14 września jedna odpowiedź na 369 znaków
         * zajęła osiemnaście sekund mówienia.
         *
         * Gdyby okazało się za ciasne, powie to [GeminiCandidate.finishReason]
         * wartością "MAX_TOKENS" - nie trzeba będzie zgadywać.
         */
        /**
         * Sufit na odpowiedź - PODNIESIONY Z 1200, BO TEN LIMIT OBEJMUJE TEŻ
         * MYŚLENIE.
         *
         * Tysiąc dwieście wystarczało, dopóki liczyło się tylko to, co model
         * wypisuje. Ale `maxOutputTokens` w Gemini obejmuje także tokeny
         * myślenia (napisane wprost w [GeminiGenerationConfig]), a modele
         * `flash` myślą z domysłu i ograniczanie myślenia jest u nas domyślnie
         * WYŁĄCZONE. Gdy myślenie zje tysiąc, na odpowiedź zostaje dwieście - i
         * urywa się ona w pół słowa.
         *
         * Zgłoszone jako "przycina odpowiedzi", a widać to na dwóch zrzutach:
         * "Oprócz tego dostałeś maila od Sie" i znacznik akcji ucięty na
         * `kind="TRANSIT_`. Ten drugi kosztował całą funkcję: niedokończonego
         * znacznika nie da się sparsować, więc asystent obiecał trasę i nie
         * uruchomił niczego.
         *
         * Płacimy za to rachunkiem - i to jest świadomy wybór, bo ucięta
         * odpowiedź kosztuje tyle samo tokenów co pełna, tylko jest
         * bezużyteczna. Tańsza droga istnieje i jest w Ustawieniach:
         * "ogranicz myślenie" oddaje cały ten budżet odpowiedzi.
         */
        private const val MAX_OUTPUT_TOKENS = 3000

        /** Ile znaków oryginalnej odpowiedzi serwera dokładamy do komunikatu. */
        private const val RAW_ERROR_CHARS = 200

        /**
         * Tłumaczy kod HTTP z Gemini na zdanie, z którym da się coś zrobić.
         *
         * Dotąd użytkownik dostawał "Gemini API error 400: {surowy JSON}" - czyli
         * informację, że coś nie działa, bez żadnej wskazówki CO. A akurat przy
         * tym providerze prawie wszystkie odmowy mają jedną z czterech przyczyn i
         * każda wymaga czegoś innego: zły klucz, klucz z niewłaściwego projektu,
         * wyczerpany limit albo model niedostępny dla tego konta. Surową
         * odpowiedź zostawiamy na końcu - dla dziennika i dla zgłoszeń.
         */
        fun explainHttpError(code: Int, body: String): String {
            val hint = when (code) {
                400 -> if (body.contains("API_KEY_INVALID", ignoreCase = true) ||
                    body.contains("API key not valid", ignoreCase = true)
                ) {
                    "Klucz API Gemini jest nieprawidłowy. Wygeneruj nowy w " +
                        "aistudio.google.com i wklej go w Ustawieniach."
                } else {
                    "Gemini odrzuciło zapytanie jako błędne."
                }
                401, 403 ->
                    "Klucz API nie ma dostępu do Gemini. Sprawdź, czy pochodzi z " +
                        "projektu z włączonym Generative Language API i czy nie ma " +
                        "ograniczeń (adres IP, aplikacja) blokujących telefon."
                404 ->
                    "Wybrany model nie jest dostępny dla tego klucza. Wybierz inny " +
                        "model w Ustawieniach."
                429 ->
                    "Przekroczony limit zapytań Gemini. Odczekaj chwilę albo " +
                        "podnieś limit w Google Cloud Console."
                in 500..599 ->
                    "Serwer Gemini ma awarię - to nie jest problem po stronie " +
                        "telefonu. Spróbuj za chwilę."
                else -> "Gemini odpowiedziało kodem $code."
            }
            return "$hint (HTTP $code: ${body.take(RAW_ERROR_CHARS)})"
        }
    }

    /**
     * Streaming przez Gemini API: streamGenerateContent
     * Zwraca SSE - każda linia "data: {...}" to fragment odpowiedzi.
     */
    /**
     * Strumień z jednym ponowieniem - patrz [analyze].
     *
     * `retry` powtarza CAŁY strumień, co byłoby groźne, gdyby zdążył cokolwiek
     * wypuścić. Tu nie zdąży: odmowa przychodzi przy sprawdzeniu odpowiedzi
     * HTTP, przed pierwszym fragmentem tekstu.
     */
    override fun analyzeStream(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray?,
        scannedCodes: List<ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): kotlinx.coroutines.flow.Flow<AIResponseChunk> = streamOnce(
            textQuestion = textQuestion,
            images = images,
            audioBytes = audioBytes,
            scannedCodes = scannedCodes,
            enableWebSearch = enableWebSearch,
            systemPrompt = systemPrompt
    ).retry(1) { it is ThinkingRejectedRetry }

    private fun streamOnce(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray?,
        scannedCodes: List<ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): kotlinx.coroutines.flow.Flow<AIResponseChunk> = kotlinx.coroutines.flow.flow {
        require(apiKey.isNotBlank()) { "Gemini API key is empty" }

        val url = "$STREAM_API_BASE/$model:streamGenerateContent?alt=sse&key=$apiKey"

        val parts = mutableListOf<GeminiPart>()
        images.forEach { imageBytes ->
            parts.add(GeminiPart(inlineData = GeminiInlineData(
                mimeType = "image/jpeg",
                data = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
            )))
        }
        // Nagranie MUSI tu być. Ta metoda gubiła je po cichu, a analyze() nie -
        // czyli akurat ta ścieżka, którą chodzi każda tura rozmowy, wysyłała
        // modelowi polecenie "odpowiedz na pytanie z załączonego nagrania" BEZ
        // nagrania. Model odpowiadał wtedy dokładnie tak, jak zgłoszono: że
        // dostał nagranie, nie umie go otworzyć i nie wie, co użytkownik mówi.
        // Gemini jest przy tym JEDYNYM providerem, który przyjmuje audio, i
        // jedynym, który nadpisuje analyzeStream - więc cała ścieżka głosowa po
        // BLE nie miała prawa zadziałać ani razu.
        audioBytes?.let { audio ->
            parts.add(GeminiPart(inlineData = GeminiInlineData(
                mimeType = "audio/wav",
                data = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
            )))
        }
        val prompt = buildPrompt(textQuestion, images.isNotEmpty(), scannedCodes, systemPrompt)
        parts.add(GeminiPart(text = prompt))

        val tools = if (enableWebSearch) listOf(GeminiTool(googleSearch = GoogleSearchTool())) else null

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = parts)),
            tools = tools,
            generationConfig = generationConfig()
        )

        val requestBody = json.encodeToString(GeminiRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    // Odmowa dotycząca myślenia jest DO NAPRAWIENIA W LOCIE:
                    // zapamiętujemy ją i powtarzamy zapytanie bez tej prośby,
                    // zamiast zwracać użytkownikowi błąd za coś, co jest tylko
                    // optymalizacją kosztu.
                    if (noteThinkingRejected(response.code, errorBody)) {
                        throw ThinkingRejectedRetry()
                    }
                    throw AIProviderException(
                        explainHttpError(response.code, errorBody),
                        providerId = id,
                        isRetryable = response.code in 500..599 || response.code == 429
                    )
                }

                val source = response.body?.source() ?: throw AIProviderException(
                    "No response body", providerId = id
                )

                // Parsuj SSE: każda linia "data: {...}\n\n"
                val fullText = StringBuilder()
                var totalTokens = 0
                var lastUsage: GeminiResponse? = null

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val jsonStr = line.removePrefix("data: ").trim()
                        if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                        try {
                            val chunk = json.decodeFromString(
                                GeminiResponse.serializer(), jsonStr
                            )
                            chunk.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                                part.text?.let { textFragment ->
                                    fullText.append(textFragment)
                                    emit(AIResponseChunk(
                                        text = textFragment,
                                        isFinal = false
                                    ))
                                }
                            }
                            chunk.usageMetadata?.totalTokenCount?.let { totalTokens = it }
                            // ROZBICIE ZUŻYCIA MUSI BYĆ WŁAŚNIE TUTAJ.
                            //
                            // To jest ścieżka, którą idzie ROZMOWA - w dzienniku
                            // widać ją po wierszu "PIERWSZY FRAGMENT odpowiedzi".
                            // Pomiar zapięty tylko przy zapytaniu bez strumienia
                            // nie odpalałby się w normalnym użyciu ani razu, czyli
                            // mierzyłby dokładnie to, o co nikt nie pyta.
                            //
                            // Zużycie przychodzi w OSTATNIM fragmencie, więc
                            // zapisujemy ten, w którym w ogóle jest.
                            if (chunk.usageMetadata != null) lastUsage = chunk
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse chunk: ${e.message}")
                        }
                    }
                }
                lastUsage?.let { reportUsage(it) }

                // Ostatni chunk - z summary
                emit(AIResponseChunk(
                    text = "",
                    isFinal = true,
                    tokensUsed = totalTokens
                ))
            }
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed", e)
            throw AIProviderException(
                "Streaming error: ${e.message}",
                providerId = id,
                isRetryable = true
            )
        }
    }.flowOn(Dispatchers.IO)
}

// === Gemini API request/response DTOs (snake_case jak w ich API) ===

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val tools: List<GeminiTool>? = null,
    val generationConfig: GeminiGenerationConfig? = null
)

/**
 * Granice dla generowania - dotąd NIE WYSYŁANE WCALE.
 *
 * ## Czemu to zaczęło mieć znaczenie
 * Bo bez `maxOutputTokens` jedna tura potrafi kosztować wielokrotność tego, co
 * musi. W dzienniku z 14 września zwykłe pytanie o zamek daje `2817 tokenów`
 * przy poleceniu na circa 250 i odpowiedzi na circa 100 - a największą pozycją
 * na rachunku jest SKU tokenów WYJŚCIOWYCH.
 *
 * ## Czego tu świadomie NIE MA
 * Sterowania myśleniem. Dokumentacja Gemini pokazuje dziś `thinking_level` w
 * nowym API rozmów, a starsze źródła `thinkingConfig.thinkingBudget`; nie
 * udało mi się potwierdzić, którego z nich oczekuje `generateContent` dla
 * modelu ustawionego w tej aplikacji. Zgadnięta nazwa pola to odpowiedź 400 i
 * asystent, który przestaje odpowiadać W OGÓLE - a to znacznie gorsze niż
 * rachunek wyższy, niż trzeba. Najpierw [GeminiUsageMetadata.thoughtsTokenCount]
 * pokaże w dzienniku, ile tokenów naprawdę idzie na myślenie; dopiero mając tę
 * liczbę warto sięgać po sterowanie nim.
 *
 * `maxOutputTokens` ogranicza tymczasem JEDNO I DRUGIE naraz: dokumentacja
 * mówi wprost, że limit obejmuje także tokeny myślenia.
 */
@Serializable
data class GeminiGenerationConfig(
    val maxOutputTokens: Int? = null,
    val temperature: Float? = null,
    val thinkingConfig: GeminiThinkingConfig? = null
)

/**
 * Sygnał wewnętrzny: powtórz zapytanie bez prośby o ograniczenie myślenia.
 *
 * Nie wychodzi poza [GeminiProvider] - wołający ma zobaczyć odpowiedź albo
 * prawdziwy błąd, nigdy tego wyjątku.
 */
private class ThinkingRejectedRetry : Exception("Powtórka bez ograniczenia myślenia")

/**
 * Prośba o ograniczenie myślenia.
 *
 * ## Czemu to jest napisane OSTROŻNIE
 * Bo nie udało mi się potwierdzić w dokumentacji, jakiej nazwy pola oczekuje
 * `generateContent` dla modelu ustawionego w tej aplikacji: starsze źródła
 * podają `thinkingConfig.thinkingBudget`, nowsze pokazują `thinking_level` w
 * osobnym API rozmów. Zgadnięta nazwa to odpowiedź 400 i asystent, który
 * przestaje odpowiadać W OGÓLE.
 *
 * Dlatego zamiast zgadywać w ciemno, aplikacja PRÓBUJE i uczy się z odmowy -
 * patrz [GeminiProvider.noteThinkingRejected]. Nieudana próba kosztuje jedno
 * dodatkowe zapytanie raz na uruchomienie, a nie zepsutą rozmowę.
 */
@Serializable
data class GeminiThinkingConfig(
    val thinkingBudget: Int? = null
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null
)

@Serializable
data class GeminiInlineData(
    val mimeType: String,
    val data: String  // base64
)

@Serializable
data class GeminiTool(
    val googleSearch: GoogleSearchTool? = null
)

@Serializable
data class GoogleSearchTool(
    val dynamicRetrievalConfig: DynamicRetrievalConfig? = null
)

@Serializable
data class DynamicRetrievalConfig(
    val mode: String = "MODE_DYNAMIC",
    val dynamicThreshold: Double = 0.3
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val usageMetadata: GeminiUsageMetadata? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val groundingMetadata: GroundingMetadata? = null,
    /**
     * Czemu model przestał pisać - "STOP" to koniec zdania, "MAX_TOKENS" to
     * ucięcie na limicie.
     *
     * Potrzebne od chwili, gdy ustawiamy [GenerationConfig.maxOutputTokens]:
     * limit liczy tokeny myślenia RAZEM z odpowiedzią, więc za ciasny potrafi
     * oddać pustkę zamiast zdania. Bez tego pola takie ucięcie wyglądałoby
     * identycznie jak model, który nie miał nic do powiedzenia.
     */
    val finishReason: String? = null
)

@Serializable
data class GroundingMetadata(
    val groundingChunks: List<GroundingChunk>? = null
)

@Serializable
data class GroundingChunk(
    val web: WebSource? = null
)

@Serializable
data class WebSource(
    val uri: String? = null,
    val title: String? = null
)

@Serializable
data class GeminiUsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    /**
     * Tokeny zużyte na MYŚLENIE - rozliczane jak wyjściowe, czyli najdrożej.
     *
     * Tego pola tu nie było, a bez niego rachunek nie dawał się wytłumaczyć.
     * W dzienniku z 14 września jedna zwykła tura to `2817 tokenów` przy
     * poleceniu na 998 znaków (circa 250 tokenów) i odpowiedzi na 369 znaków
     * (circa 100). Brakujących dwóch i pół tysiąca nie dało się przypisać do
     * niczego - a na rachunku w Google Cloud największą pozycją jest właśnie
     * SKU tokenów WYJŚCIOWYCH.
     *
     * Model może tego pola nie odsyłać; `ignoreUnknownKeys` po obu stronach
     * sprawia, że jego brak nic nie psuje, a obecność wszystko wyjaśnia.
     */
    val thoughtsTokenCount: Int? = null,
    val totalTokenCount: Int? = null
)
