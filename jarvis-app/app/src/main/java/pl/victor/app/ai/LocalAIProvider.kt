package pl.victor.app.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import pl.victor.app.localmodel.LlamaCppInferenceEngine
import pl.victor.app.localmodel.LocalInferenceEngine
import pl.victor.app.localmodel.LocalModelCatalog
import pl.victor.app.localmodel.LocalModelStorage
import pl.victor.app.localmodel.PromptBudget
import pl.victor.app.localmodel.PromptTemplates
import pl.victor.app.vision.ScannedCode

/**
 * Model lokalny (Qwen3.5 0.8B, offline) jako zwykły [AIProvider] - żeby
 * reszta aplikacji (orchestrator, fallback, UI) nie musiała wiedzieć, że to
 * coś innego niż Gemini czy OpenAI. Bez klucza API, bez internetu po
 * pobraniu modelu raz.
 *
 * Obrazy/wideo są ignorowane (log ostrzeżenia) - katalog ma dziś tylko
 * model tekstowy, ścieżka wizyjna llama.cpp nie jest tu włączona (tak samo
 * jak w referencyjnej aplikacji, z której portowany jest silnik).
 *
 * Silnik jest trzymany jako singleton w companion object, nie pole instancji
 * - załadowanie modelu do pamięci trwa realnie kilka sekund, więc [AIProviderFactory],
 * które tworzy świeżego providera na każde zapytanie, i tak dzieli jeden
 * załadowany kontekst między wywołaniami.
 */
class LocalAIProvider(private val context: Context) : AIProvider {

    override val id = "local"
    override val displayName = "Model lokalny (offline)"
    override val supportsNativeAudio = false
    override val supportsWebSearch = false
    override val capabilities = ProviderCapabilities(
        supportsImages = false,
        supportsVideo = false,
        supportsAudio = false,
        supportsStreaming = true,
        supportsFunctionCalling = false
    )

    override suspend fun analyze(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray?,
        scannedCodes: List<ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): AIResponse {
        warnIfUnsupportedMedia(images)
        ensureModelLoaded()
        val prompt = buildPrompt(textQuestion, scannedCodes, systemPrompt)
        val result = engine.generate(prompt, MAX_TOKENS) {}
            .getOrElse { e -> throw toProviderException(e) }
        return AIResponse(text = result.fullText.trim(), tokensUsed = result.tokenCount, providerId = id)
    }

    override fun analyzeStream(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray?,
        scannedCodes: List<ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): Flow<AIResponseChunk> = callbackFlow {
        try {
            warnIfUnsupportedMedia(images)
            ensureModelLoaded()
            val prompt = buildPrompt(textQuestion, scannedCodes, systemPrompt)
            val result = engine.generate(prompt, MAX_TOKENS) { token ->
                trySend(AIResponseChunk(text = token, isFinal = false))
            }
            result.fold(
                onSuccess = { gen ->
                    trySend(AIResponseChunk(text = "", isFinal = true, tokensUsed = gen.tokenCount))
                    close()
                },
                onFailure = { e -> close(toProviderException(e)) }
            )
        } catch (e: Exception) {
            close(toProviderException(e))
        }
        awaitClose { }
    }.buffer(Channel.UNLIMITED)

    private fun warnIfUnsupportedMedia(images: List<ByteArray>) {
        if (images.isNotEmpty()) {
            Log.w(TAG, "Model lokalny nie obsługuje obrazów - pomijam ${images.size} zdjęć")
        }
    }

    private fun buildPrompt(
        textQuestion: String,
        scannedCodes: List<ScannedCode>,
        systemPrompt: String?
    ): String {
        val userMessage = if (scannedCodes.isEmpty()) {
            textQuestion
        } else {
            textQuestion + "\n\nZeskanowane kody: " + scannedCodes.joinToString(", ") { it.rawValue }
        }
        // PROMPT MUSI SIĘ ZMIEŚCIĆ W OKNIE MODELU - I TO BYŁA PRZYCZYNA CISZY.
        //
        // Zgłoszone: "lokalny model AI zupełnie nie odpowiada". Katalog daje
        // temu modelowi okno 2048 tokenów, a w dzienniku z 12 września prompt
        // ma `znakówPromptu=8943`. Polski tekst to grubo licząc 3-4 znaki na
        // token, więc same instrukcje zajmowały 2500-3000 tokenów - WIĘCEJ NIŻ
        // CAŁE OKNO, zanim model wygenerował choć jeden token odpowiedzi.
        //
        // Zaczęło się psuć samo z siebie: we wcześniejszych dziennikach prompt
        // miał 1709-3646 znaków, a po podłączeniu kalendarza i poczty skoczył do
        // 7117-8943. Kod modelu lokalnego nie zmienił się ani o wiersz.
        //
        // Docinamy KONTEKST, nigdy pytania - patrz [PromptBudget].
        val budget = PromptBudget.charsFor(LocalModelCatalog.QWEN_0_8B.contextSize)
        val fittedSystem = PromptBudget.fitContext(
            context = systemPrompt ?: DEFAULT_SYSTEM_PROMPT,
            question = userMessage,
            limitChars = budget
        )
        if (fittedSystem.length < (systemPrompt ?: DEFAULT_SYSTEM_PROMPT).length) {
            Log.i(
                TAG,
                "Kontekst skrócony do okna modelu: " +
                    "${(systemPrompt ?: DEFAULT_SYSTEM_PROMPT).length} -> ${fittedSystem.length} znaków"
            )
        }
        return PromptTemplates.qwenChat(
            fittedSystem.ifBlank { DEFAULT_SYSTEM_PROMPT },
            userMessage
        )
    }

    private suspend fun ensureModelLoaded() {
        if (engine.isModelLoaded()) return
        val entry = LocalModelCatalog.QWEN_0_8B
        if (!LocalModelStorage.isDownloaded(context, entry)) {
            throw AIProviderException(
                "Model lokalny nie jest jeszcze pobrany. Pobierz go w Ustawieniach.",
                providerId = id,
                isRetryable = false
            )
        }
        val file = LocalModelStorage.targetFile(context, entry)
        engine.loadModel(file.absolutePath, entry.contextSize).getOrElse { e ->
            throw toProviderException(e)
        }
    }

    private fun toProviderException(e: Throwable): AIProviderException =
        if (e is AIProviderException) e
        else AIProviderException("Model lokalny: ${e.message}", providerId = id, isRetryable = false, cause = e)

    companion object {
        private const val TAG = "LocalAIProvider"
        private const val MAX_TOKENS = 256
        private const val DEFAULT_SYSTEM_PROMPT = "Jesteś pomocnym asystentem głosowym V.I.C.T.O.R. Odpowiadaj krótko i po polsku."

        /**
         * Jeden załadowany model na cały proces aplikacji - drugi kontekst
         * llama.cpp obok pierwszego nie ma sensu na telefonie.
         */
        private val engine: LocalInferenceEngine by lazy { LlamaCppInferenceEngine() }
    }
}
