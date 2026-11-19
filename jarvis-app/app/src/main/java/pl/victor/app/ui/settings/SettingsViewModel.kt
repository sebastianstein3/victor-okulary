package pl.victor.app.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.victor.app.AIOrchestrator
import pl.victor.app.ai.AIProviderFactory
import pl.victor.app.data.SettingsRepository
import pl.victor.app.VictorApplication
import pl.victor.app.ai.AIProviderException

/**
 * ViewModel dla ekranu ustawień.
 * Ładuje i zapisuje preferencje przez SettingsRepository.
 * Testuje połączenie z aktywnym providerem AI.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VictorApplication
    private val settings: SettingsRepository = app.settings

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun loadState(): SettingsState = SettingsState(
        activeProviderId = settings.getActiveProvider(),
        selectedModelId = settings.getSelectedModel(settings.getActiveProvider()),
        apiKeys = loadAllKeys(),
        webSearchEnabled = settings.isWebSearchEnabled(),
        responseLanguage = settings.getResponseLanguage(),
        wakeWordEnabled = settings.isWakeWordEnabled(),
        captureCount = settings.getCaptureCount(),
        captureIntervalMs = settings.getCaptureIntervalMs(),
        ttsVoice = settings.getTtsVoiceName(),
        ttsSpeechRate = settings.getTtsSpeechRate(),
        ttsPitch = settings.getTtsPitch(),
        availableVoices = app.audio.availableVoices.value,
        currentVoice = app.audio.currentVoice.value,
        selectedPersonaId = settings.getSelectedPersonaId(),
        customPersonaPrompt = settings.getCustomPersonaPrompt(),
        statusMessage = null,
        isTestRunning = false
    )

    private fun loadAllKeys(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        AIProviderFactory.supportedProviders().forEach { provider ->
            map[provider.id] = settings.getApiKey(provider.id) ?: ""
        }
        return map
    }

    init {
        // Obserwuj listę głosów (może się załadować z opóźnieniem)
        viewModelScope.launch {
            app.audio.availableVoices.collect { voices ->
                _state.value = _state.value.copy(availableVoices = voices)
            }
        }

        // Obserwuj aktualny głos
        viewModelScope.launch {
            app.audio.currentVoice.collect { voice ->
                _state.value = _state.value.copy(currentVoice = voice)
            }
        }

        // Przy starcie - zastosuj zapisane ustawienia głosu
        applyStoredVoiceSettings()
    }

    private fun applyStoredVoiceSettings() {
        val storedVoice = settings.getTtsVoiceName()
        if (!storedVoice.isNullOrBlank()) {
            app.audio.setVoice(storedVoice)
        }
        app.audio.setSpeechRate(settings.getTtsSpeechRate())
        app.audio.setPitch(settings.getTtsPitch())
    }

    fun setTtsVoice(voiceName: String) {
        settings.setTtsVoiceName(voiceName)
        app.audio.setVoice(voiceName)
    }

    fun setTtsRate(rate: Float) {
        settings.setTtsSpeechRate(rate)
        app.audio.setSpeechRate(rate)
        _state.value = _state.value.copy(ttsSpeechRate = rate)
    }

    fun setTtsPitch(pitch: Float) {
        settings.setTtsPitch(pitch)
        app.audio.setPitch(pitch)
        _state.value = _state.value.copy(ttsPitch = pitch)
    }

    fun testVoice() {
        app.audio.testCurrentVoice()
        _state.value = _state.value.copy(statusMessage = "🔊 Odsłuchuję...")
    }

    fun setPersona(personaId: String) {
        settings.setSelectedPersonaId(personaId)
        _state.value = _state.value.copy(selectedPersonaId = personaId)
    }

    fun setCustomPersonaPrompt(prompt: String) {
        settings.setCustomPersonaPrompt(prompt)
        _state.value = _state.value.copy(customPersonaPrompt = prompt)
    }

    fun getApiKey(providerId: String): String =
        _state.value.apiKeys[providerId] ?: ""

    fun setActiveProvider(providerId: String) {
        settings.setActiveProvider(providerId)
        // Załaduj model dla nowego providera
        val model = settings.getSelectedModel(providerId)
        _state.value = _state.value.copy(
            activeProviderId = providerId,
            selectedModelId = model,
            statusMessage = null
        )
    }

    fun setSelectedModel(modelId: String?) {
        val providerId = _state.value.activeProviderId
        if (modelId == null) {
            settings.clearSelectedModel(providerId)
        } else {
            settings.setSelectedModel(providerId, modelId)
        }
        _state.value = _state.value.copy(selectedModelId = modelId)
    }

    fun setApiKey(providerId: String, key: String) {
        if (key.isBlank()) {
            settings.setApiKey(providerId, key)  // zapisze pusty? nie, setApiKey tego nie pozwala
            // actually: sprawdzimy i pokażemy error
            _state.value = _state.value.copy(
                statusMessage = "Klucz API nie może być pusty"
            )
            return
        }
        settings.setApiKey(providerId, key)
        val newKeys = _state.value.apiKeys.toMutableMap()
        newKeys[providerId] = key
        _state.value = _state.value.copy(
            apiKeys = newKeys,
            statusMessage = "Zapisano klucz dla ${AIProviderFactory.supportedProviders().find { it.id == providerId }?.displayName}"
        )
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        settings.setWebSearchEnabled(enabled)
        _state.value = _state.value.copy(webSearchEnabled = enabled)
    }

    fun setResponseLanguage(lang: String) {
        settings.setResponseLanguage(lang)
        _state.value = _state.value.copy(responseLanguage = lang)
    }

    /**
     * Włącza albo wyłącza nasłuch frazy „Hej Wiktor” na telefonie.
     *
     * Sam zapis wystarcza do startu i zatrzymania: VictorApplication obserwuje
     * ten przełącznik i prowadzi nasłuch (mikrofon jest wyłączny, więc jego
     * cyklem życia rządzi aplikacja, nie ekran). Tutaj tylko mówimy wprost, czy
     * nasłuch ma czym ruszyć - brak modelu wyglądałby inaczej jak "nic się nie
     * dzieje".
     */
    fun setWakeWordEnabled(enabled: Boolean) {
        settings.setWakeWordEnabled(enabled)
        _state.value = _state.value.copy(wakeWordEnabled = enabled)
        if (!enabled) return
        if (settings.getWakeEngine() != SettingsRepository.WAKE_ENGINE_HEJ_WIKTOR) return

        viewModelScope.launch {
            // Pierwsze sprawdzenie wczytuje sieci - nie na wątku głównym.
            val problem = withContext(Dispatchers.Default) {
                runCatching { app.phoneWake.modelProblem() }
                    .getOrElse { it.message ?: pl.victor.app.wakeword.OpenWakeWord.BRAK_MODELU }
            }
            _state.value = _state.value.copy(
                statusMessage = problem ?: "Nasłuch „Hej Wiktor” włączony."
            )
        }
    }

    fun setCaptureCount(count: Int) {
        val clamped = count.coerceIn(1, 20)
        settings.setCaptureCount(clamped)
        _state.value = _state.value.copy(captureCount = clamped)
    }

    fun setCaptureInterval(ms: Long) {
        val clamped = ms.coerceIn(500, 10000)
        settings.setCaptureIntervalMs(clamped)
        _state.value = _state.value.copy(captureIntervalMs = clamped)
    }

    /**
     * Testuje połączenie z providerem - wysyła krótkie pytanie do AI.
     *
     * @param providerId który provider sprawdzić; `null` znaczy aktywny.
     *   Możliwość wskazania konkretnego jest istotna przy wklejaniu klucza:
     *   dotąd przycisk sprawdzał ZAWSZE aktywnego providera, więc po wklejeniu
     *   klucza Gemini przy aktywnym Claude test mówił o zupełnie czymś innym -
     *   i "klucz nie działa" wyglądało tak samo jak "klucz działa".
     */
    fun testConnection(requestedProviderId: String? = null) {
        val providerId = requestedProviderId ?: _state.value.activeProviderId
        val apiKey = _state.value.apiKeys[providerId]
        val name = AIProviderFactory.supportedProviders()
            .find { it.id == providerId }?.displayName ?: providerId

        if (apiKey.isNullOrBlank()) {
            _state.value = _state.value.copy(
                statusMessage = "Brak klucza API dla $name. Wpisz go powyżej."
            )
            return
        }

        _state.value = _state.value.copy(
            isTestRunning = true,
            statusMessage = "Sprawdzam $name…"
        )

        viewModelScope.launch {
            try {
                val provider = withContext(Dispatchers.IO) {
                    AIProviderFactory.createSimple(
                        providerId = providerId,
                        apiKey = apiKey,
                        context = app,
                        preferredModelId = settings.getSelectedModel(providerId)
                    )
                }
                val response = provider.analyze(
                    textQuestion = "Odpowiedz krótko: dostałeś tę wiadomość?",
                    images = emptyList(),
                    audioBytes = null,
                    scannedCodes = emptyList(),
                    enableWebSearch = false
                )
                _state.value = _state.value.copy(
                    isTestRunning = false,
                    statusMessage = "✓ Sukces! ${response.providerId} odpowiedział: \"${response.text.take(80)}...\""
                )
            } catch (e: AIProviderException) {
                Log.e("SettingsVM", "Test failed", e)
                _state.value = _state.value.copy(
                    isTestRunning = false,
                    statusMessage = "✗ Błąd: ${e.message}"
                )
            } catch (e: Exception) {
                Log.e("SettingsVM", "Test failed", e)
                _state.value = _state.value.copy(
                    isTestRunning = false,
                    statusMessage = "✗ Nieoczekiwany błąd: ${e.message}"
                )
            }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }

}

/**
 * Stan ekranu ustawień.
 */
data class SettingsState(
    val activeProviderId: String,
    val selectedModelId: String? = null,
    val apiKeys: Map<String, String>,
    val webSearchEnabled: Boolean,
    val responseLanguage: String,
    val wakeWordEnabled: Boolean,
    val captureCount: Int,
    val captureIntervalMs: Long,
    // TTS
    val ttsVoice: String? = null,
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val availableVoices: List<pl.victor.app.audio.VoiceInfo> = emptyList(),
    val currentVoice: pl.victor.app.audio.VoiceInfo? = null,
    // Persona
    val selectedPersonaId: String = "default",
    val customPersonaPrompt: String = "",
    val statusMessage: String? = null,
    val isTestRunning: Boolean = false
)
