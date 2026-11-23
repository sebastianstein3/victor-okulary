package pl.victor.app.orchestrator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.victor.app.ai.AIProvider
import pl.victor.app.ai.AIProviderException
import pl.victor.app.ai.AIProviderFactory
import pl.victor.app.data.RemoteModelValidator
import pl.victor.app.data.SettingsRepository

/**
 * Dostawcy modelu: bieżący (tworzony od nowa po zmianie dostawcy albo
 * modelu w ustawieniach), kolejka zapasowych dla tury i modele odstawione w
 * tej sesji, a dla ekranu - używany model i ostrzeżenie o nim.
 *
 * ## Po co osobna klasa
 * Wydzielone z [pl.victor.app.AIOrchestrator] BEZ ZMIANY ZACHOWANIA: ta sama
 * walidacja, migracja i kolejność dostawców. Stan (bieżący dostawca, martwe
 * modele, ostrzeżenie) należy do tej klasy; tura z orkiestratora tylko o
 * dostawców prosi.
 */
internal class ProviderManager(
    private val context: Context,
    private val settings: SettingsRepository
) {
    // Ostrzeżenia o modelu (deprecated, migration, itd.)
    private val _modelWarning = MutableStateFlow<String?>(null)
    val modelWarning: StateFlow<String?> = _modelWarning.asStateFlow()

    // Aktualnie używany model
    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId.asStateFlow()

    private var currentProvider: AIProvider? = null
    var currentProviderId: String? = null
        private set
    private var activeModelId: String? = null

    /**
     * Modele, które w tej sesji odpowiedziały "nie ma takiego modelu".
     *
     * Patrz [pl.victor.app.ai.DeadModels] - powód i koszt są tam opisane.
     */
    val deadModels = pl.victor.app.ai.DeadModels()

    /**
     * Publikuje model wynikający z BIEŻĄCYCH ustawień, bez tworzenia providera i bez sieci.
     *
     * [_currentModelId] było dotąd aktualizowane wyłącznie w [getOrCreateProvider], czyli
     * dopiero przy pierwszym realnym zapytaniu do AI. Po zmianie modelu w ustawieniach
     * badge na ekranie głównym pokazywał więc poprzedni model aż do następnego pytania.
     * Ekran główny woła to przy wejściu i po powrocie z ustawień.
     */
    fun publishConfiguredModel() {
        val providerId = settings.getActiveProvider()
        _currentModelId.value = settings.getSelectedModel(providerId)
            ?: pl.victor.app.data.ModelRegistry.defaultFor(providerId)?.id
    }

    suspend fun getOrCreateProvider(): AIProvider {
        val providerId = settings.getActiveProvider()

        val apiKey = settings.getApiKey(providerId)
            ?: throw AIProviderException(
                "Brak klucza API dla $providerId. Ustaw go w ustawieniach.",
                providerId = providerId,
                isRetryable = false
            )

        val preferredModel = settings.getSelectedModel(providerId)

        // Sprawdź czy trzeba odświeżyć (provider lub model się zmienił)
        if (currentProviderId != providerId || activeModelId != preferredModel) {

            // Walidacja modeli u providera (async, nie blokuje UI)
            val available = try {
                val validator = RemoteModelValidator(apiKey, providerId)
                validator.fetchAvailableModels()
            } catch (e: Exception) {
                Log.w(TAG, "Could not validate models", e)
                emptyList()
            }

            // Stwórz provider z resolverem
            val withMetadata = AIProviderFactory.create(
                providerId = providerId,
                apiKey = apiKey,
                context = context,
                preferredModelId = preferredModel,
                availableFromProvider = available
            )

            currentProvider = withMetadata.provider
            currentProviderId = providerId
            activeModelId = withMetadata.modelId
            _currentModelId.value = withMetadata.modelId  // publikuj dla UI

            // Pokaż ostrzeżenie jeśli model jest deprecated lub zmigrowany
            withMetadata.resolution.warning?.let { warning ->
                _modelWarning.value = warning.toUserMessage()
                Log.w(TAG, "Model warning: ${warning.toUserMessage()}")
            }

            // Jeśli automatyczna migracja - zaktualizuj ustawienia
            if (withMetadata.resolution.source ==
                pl.victor.app.data.ModelSource.AUTO_MIGRATED) {
                withMetadata.resolution.warning?.let { warning ->
                    if (warning is pl.victor.app.data.ModelWarning.AutoMigrated) {
                        settings.setSelectedModel(providerId, warning.newModelId)
                    }
                }
            }
        }
        return currentProvider!!
    }

    /**
     * Kolejność providerów do próby: aktywny z Ustawień pierwszy, potem reszta
     * providerów, dla których user w ogóle ma wpisany klucz API - w kolejności
     * z [AIProviderFactory.supportedProviders]. Nie próbujemy providera bez klucza,
     * bo to i tak od razu by zawiodło.
     */
    fun fallbackProviderOrder(needsAudio: Boolean = false, needsImages: Boolean = false): List<String> {
        val primary = settings.getActiveProvider()
        val others = AIProviderFactory.supportedProviders()
            .map { it.id }
            .filter { it != primary && settings.hasApiKey(it) }
            // Zapasowy dostawca musi UMIEĆ to, czego wymaga tura: nagranie i zdjęcia
            // nie mają sensu dla modelu tekstowego - dostawał polecenie "odpowiedz
            // na pytanie z nagrania" bez nagrania i odpowiadał (płatnie) bzdurę.
            .filter { id ->
                val info = pl.victor.app.data.ModelRegistry.defaultFor(id)
                (!needsAudio || info?.supportsAudio == true) && (!needsImages || info?.supportsVision == true)
            }
        // DOSTAWCA Z MARTWYM MODELEM IDZIE NA KONIEC, NIE ZNIKA.
        //
        // Znikanie byłoby groźne: gdyby wszystkie okazały się martwe, kolejka
        // zrobiłaby się pusta i tura padałaby na indeksie zamiast na modelu.
        // Przesunięcie kosztuje tyle samo, a zachowuje ostatnią deskę ratunku.
        return deadModels.przestaw(listOf(primary) + others) {
            settings.getSelectedModel(it)
        }
    }

    /**
     * Buduje provider dla próby fallbacku - celowo NIE dotyka `currentProvider`/
     * `currentProviderId` ani ustawień. Fallback jest jednorazowy, dla tego
     * konkretnego zapytania - nie zmienia trwale wybranego providera usera.
     */
    suspend fun buildProviderForFallback(providerId: String): AIProvider {
        val apiKey = settings.getApiKey(providerId)
            ?: throw AIProviderException(
                "Brak klucza API dla $providerId",
                providerId = providerId,
                isRetryable = false
            )
        val preferredModel = settings.getSelectedModel(providerId)
        val available = try {
            RemoteModelValidator(apiKey, providerId).fetchAvailableModels()
        } catch (e: Exception) {
            Log.w(TAG, "Could not validate models for fallback provider $providerId", e)
            emptyList()
        }
        return AIProviderFactory.create(
            providerId = providerId,
            apiKey = apiKey,
            context = context,
            preferredModelId = preferredModel,
            availableFromProvider = available
        ).provider
    }

    /**
     * Czyści aktywne ostrzeżenie (po wyświetleniu użytkownikowi).
     */
    fun clearModelWarning() {
        _modelWarning.value = null
    }

    companion object {
        /** Ten sam znacznik logcata co orkiestrator - filtry logów działają jak dotąd. */
        private const val TAG = "AIOrchestrator"
    }
}
