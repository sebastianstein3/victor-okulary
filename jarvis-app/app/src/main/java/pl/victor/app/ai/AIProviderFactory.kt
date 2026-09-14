package pl.victor.app.ai

import android.content.Context
import android.util.Log
import pl.victor.app.data.ModelRegistry
import pl.victor.app.data.ModelResolution
import pl.victor.app.data.ModelSource
import pl.victor.app.data.SmartModelResolver
import pl.victor.app.localmodel.LocalModelCatalog

/**
 * Fabryka providerów AI - tworzy odpowiednią implementację na podstawie ID.
 * Centralne miejsce do dodawania nowych providerów.
 *
 * Używa SmartModelResolver do wybrania właściwego modelu:
 * - Jeśli zapisany model jest deprecated → automatyczny fallback
 * - Jeśli model nie istnieje u providera → fallback do domyślnego
 * - Jeśli nie ma preferencji → domyślny model
 */
object AIProviderFactory {

    private const val TAG = "AIProviderFactory"
    const val LOCAL_PROVIDER_ID = "local"

    /**
     * Zwraca capabilities dla danego providera (bez tworzenia instancji).
     */
    fun getCapabilitiesFor(providerId: String): ProviderCapabilities = when (providerId) {
        "gemini" -> ProviderCapabilities(
            supportsImages = true,
            supportsVideo = true,
            supportsAudio = true,
            maxImagesPerRequest = 16,
            maxVideoBytes = 20L * 1024 * 1024,
            supportsFunctionCalling = true
        )
        "openai" -> ProviderCapabilities(
            supportsImages = true,
            supportsVideo = false,
            supportsAudio = false,
            maxImagesPerRequest = 10,
            supportsFunctionCalling = true
        )
        "claude" -> ProviderCapabilities(
            supportsImages = true,
            supportsVideo = false,
            supportsAudio = false,
            maxImagesPerRequest = 20,
            supportsFunctionCalling = true
        )
        "minimax" -> ProviderCapabilities(
            supportsImages = true,
            supportsVideo = false,
            supportsAudio = false,
            maxImagesPerRequest = 8,
            supportsFunctionCalling = false
        )
        // Obrazy włączone, bo to WYBÓR MODELU decyduje, czy zdjęcie ma dokąd
        // pójść - a lista modeli przychodzi z API, nie jest tu wpisana.
        "deepseek" -> ProviderCapabilities(
            supportsImages = true,
            supportsVideo = false,
            supportsAudio = false,
            maxImagesPerRequest = 4,
            supportsFunctionCalling = true
        )
        "local" -> ProviderCapabilities(
            supportsImages = false,
            supportsVideo = false,
            supportsAudio = false,
            supportsFunctionCalling = false
        )
        else -> ProviderCapabilities()
    }
    private val resolver = SmartModelResolver()

    /**
     * Tworzy provider z automatycznym wyborem modelu.
     *
     * @param providerId ID providera ("gemini", "openai", ...)
     * @param apiKey klucz API
     * @param preferredModelId model preferowany przez użytkownika (null = domyślny)
     * @param availableFromProvider lista modeli dostępnych u providera (z RemoteModelValidator)
     */
    fun create(
        providerId: String,
        apiKey: String,
        context: Context,
        preferredModelId: String? = null,
        availableFromProvider: List<String> = emptyList()
    ): AIProviderWithMetadata {
        if (providerId.lowercase() == LOCAL_PROVIDER_ID) {
            // Bez klucza API, bez resolvera modeli chmurowych - katalog lokalny
            // ma dziś jeden wpis i nie jest wersjonowany przez żadnego providera.
            val modelId = LocalModelCatalog.QWEN_0_8B.id
            Log.i(TAG, "Creating $LOCAL_PROVIDER_ID with model $modelId")
            return AIProviderWithMetadata(
                provider = LocalAIProvider(context.applicationContext),
                modelId = modelId,
                resolution = ModelResolution(modelId = modelId, source = ModelSource.PREFERRED, warning = null)
            )
        }

        require(apiKey.isNotBlank()) { "API key for $providerId is empty" }

        // Rozwiąż model
        val resolution = resolver.resolve(
            providerId = providerId,
            preferredModelId = preferredModelId,
            availableFromProvider = availableFromProvider
        )

        if (resolution.source == ModelSource.FAILED) {
            throw IllegalStateException("Cannot create provider: ${resolution.warning?.toUserMessage()}")
        }

        Log.i(TAG, "Creating $providerId with model ${resolution.modelId} (source: ${resolution.source})")
        if (resolution.warning != null) {
            Log.w(TAG, "Model warning: ${resolution.warning.toUserMessage()}")
        }

        val provider = when (providerId.lowercase()) {
            "gemini" -> GeminiProvider(
                apiKey = apiKey,
                model = resolution.modelId,
                // Ustawienie czytamy TU, przy tworzeniu, a nie w providerze -
                // dzięki temu provider zostaje czystą warstwą nad API i daje
                // się utworzyć bez kontekstu Androida.
                limitThinking = pl.victor.app.data.SettingsRepository
                    .getInstance(context).isThinkingLimitEnabled()
            )
            "openai" -> OpenAIProvider(apiKey = apiKey, model = resolution.modelId)
            "claude" -> ClaudeProvider(apiKey = apiKey, model = resolution.modelId)
            "minimax" -> MiniMaxProvider(apiKey = apiKey, model = resolution.modelId)
            "deepseek" -> DeepSeekProvider(apiKey = apiKey, model = resolution.modelId)
            else -> throw IllegalArgumentException("Unknown AI provider: $providerId")
        }

        return AIProviderWithMetadata(
            provider = provider,
            modelId = resolution.modelId,
            resolution = resolution
        )
    }

    /**
     * Szybki helper - tworzy provider bez metadanych (dla prostych przypadków).
     */
    fun createSimple(
        providerId: String,
        apiKey: String,
        context: Context,
        preferredModelId: String? = null
    ): AIProvider {
        return create(providerId, apiKey, context, preferredModelId).provider
    }

    /**
     * Lista wszystkich wspieranych providerów (do UI settings).
     */
    fun supportedProviders(): List<ProviderInfo> = listOf(
        ProviderInfo(
            id = "gemini",
            displayName = "Google Gemini",
            description = "Darmowy tier, multimodal in, web search (grounding), polski świetny",
            keyUrl = "https://aistudio.google.com/",
            available = true
        ),
        ProviderInfo(
            id = "openai",
            displayName = "OpenAI GPT",
            description = "Płatny (~$0.15/1M tok), multimodal in, najlepszy angielski, polski OK",
            keyUrl = "https://platform.openai.com/api-keys",
            available = true
        ),
        ProviderInfo(
            id = "claude",
            displayName = "Anthropic Claude",
            description = "Płatny (~$3/1M tok), multimodal in, świetny polski, świetny do analizy",
            keyUrl = "https://console.anthropic.com/",
            available = true
        ),
        ProviderInfo(
            id = "minimax",
            displayName = "MiniMax M2/M3",
            description = "Płatny (~$1/1M tok), text+vision, dobra alternatywa, 1M context",
            keyUrl = "https://platform.minimax.io/",
            available = true
        ),
        ProviderInfo(
            id = "deepseek",
            displayName = "DeepSeek",
            description = "Bardzo tani (~$0,3/1M tok), dobry polski, modele tekstowe " +
                "i rozumujące. Listę modeli pobieramy z API, więc nowe pojawiają się same.",
            keyUrl = "https://platform.deepseek.com/api_keys",
            available = true
        ),
        ProviderInfo(
            id = LOCAL_PROVIDER_ID,
            displayName = "Model lokalny (offline)",
            description = "Darmowy, działa bez internetu po jednorazowym pobraniu (~560MB). Mniejsza jakość niż modele chmurowe.",
            keyUrl = "",
            available = true
        )
    )

    /**
     * Lista aktywnych modeli dla providera (z ModelRegistry).
     */
    fun modelsFor(providerId: String) = ModelRegistry.forProvider(providerId)
}

data class ProviderInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val keyUrl: String,
    val available: Boolean
)

/**
 * Provider + metadane o wybranym modelu (do wyświetlenia ostrzeżeń).
 */
data class AIProviderWithMetadata(
    val provider: AIProvider,
    val modelId: String,
    val resolution: ModelResolution
)
