package pl.victor.app.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.victor.app.data.SettingsRepository
import pl.victor.app.VictorApplication
import pl.victor.app.ai.AIProviderFactory

/**
 * ViewModel onboardingu - zarządza stanem i zapisuje do SettingsRepository.
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VictorApplication

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private fun loadInitialState(): OnboardingState {
        val s = app.settings
        return OnboardingState(
            providerId = s.getActiveProvider(),
            apiKey = s.getApiKey(s.getActiveProvider()) ?: "",
            openWeatherKey = s.getOpenWeatherApiKey(),
            city = s.getWeatherLocation(),
            hasBatteryExemption = pl.victor.app.power.BatteryOptimizationHelper.isIgnoringOptimizations(app)
        )
    }

    /** Wywoływane po powrocie z ekranu Ustawień systemowych - stan mógł się zmienić. */
    fun refreshBatteryExemption() {
        setBatteryExemption(pl.victor.app.power.BatteryOptimizationHelper.isIgnoringOptimizations(app))
    }

    fun nextStep() {
        val current = _state.value
        if (current.currentStep < current.totalSteps - 1) {
            _state.value = current.copy(currentStep = current.currentStep + 1)
        } else {
            finish()
        }
    }

    fun previousStep() {
        val current = _state.value
        if (current.currentStep > 0) {
            _state.value = current.copy(currentStep = current.currentStep - 1)
        }
    }

    fun skipStep() {
        val current = _state.value
        val step = OnboardingStep.values().getOrNull(current.currentStep)
        when (step) {
            // Skróconą "co potrafi V.I.C.T.O.R." można pominąć w całości jednym kliknięciem.
            OnboardingStep.FEATURE_AI,
            OnboardingStep.FEATURE_VOICE,
            OnboardingStep.FEATURE_GLASSES,
            OnboardingStep.FEATURE_SMART ->
                _state.value = current.copy(currentStep = OnboardingStep.PROVIDER.ordinal)
            // Dla pozostałych opcjonalnych kroków - zwykłe "dalej o jeden".
            OnboardingStep.WEATHER,
            OnboardingStep.WAKE_WORD,
            OnboardingStep.LOCATION,
            OnboardingStep.GLASSES -> nextStep()
            else -> {}
        }
    }

    fun setProvider(providerId: String) {
        _state.value = _state.value.copy(providerId = providerId)
    }

    fun setApiKey(key: String) {
        _state.value = _state.value.copy(apiKey = key)
    }

    fun setOpenWeatherKey(key: String) {
        _state.value = _state.value.copy(openWeatherKey = key)
    }

    /** Wybór frazy wybudzenia - patrz [OnboardingState.wakeEngine]. */
    fun setWakeEngine(engine: String) {
        _state.value = _state.value.copy(wakeEngine = engine)
    }

    fun setCity(city: String) {
        _state.value = _state.value.copy(city = city)
    }

    /** Mikrofon - patrz [OnboardingState.hasMicPermission]. */
    fun setMicPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasMicPermission = granted)
    }

    fun setCalendarPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasCalendarPermission = granted)
    }

    fun setNotificationPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasNotificationPermission = granted)
    }

    fun setBluetoothPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasBluetoothPermission = granted)
    }

    fun setLocationPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasLocationPermission = granted)
    }

    fun setBatteryExemption(granted: Boolean) {
        _state.value = _state.value.copy(hasBatteryExemption = granted)
    }

    fun setGlassesPaired(paired: Boolean) {
        _state.value = _state.value.copy(glassesPaired = paired)
    }

    /**
     * Zapisuje wszystko do SettingsRepository.
     */
    fun saveAndFinish() {
        val s = _state.value
        val settings = app.settings

        // Provider
        settings.setActiveProvider(s.providerId)
        if (s.apiKey.isNotBlank()) {
            settings.setApiKey(s.providerId, s.apiKey)
        }

        // OpenWeatherMap
        if (s.openWeatherKey.isNotBlank()) {
            settings.setOpenWeatherApiKey(s.openWeatherKey)
        }
        if (s.city.isNotBlank()) {
            settings.setWeatherLocation(s.city)
        }

        // Fraza wybudzenia - tylko gdy wybrana. "Hej Wiktor" włącza nasłuch
        // telefonu, bo to wprost wybór "telefon ma słuchać"; "Hej Lens" zostawia
        // mikrofon telefonu w spokoju.
        when (s.wakeEngine) {
            SettingsRepository.WAKE_ENGINE_HEJ_WIKTOR -> {
                settings.setWakeEngine(SettingsRepository.WAKE_ENGINE_HEJ_WIKTOR)
                settings.setWakeWordEnabled(true)
            }
            SettingsRepository.WAKE_ENGINE_HEJ_LENS ->
                settings.setWakeEngine(SettingsRepository.WAKE_ENGINE_HEJ_LENS)
        }

        settings.setOnboardingCompleted(true)
        _state.value = s.copy(finished = true)
    }

    fun finish() {
        saveAndFinish()
    }

    /**
     * Sprawdza czy dany krok jest gotowy (ma wymagane dane).
     * Używane przez UI do enable/disable przycisku "Dalej".
     */
    fun isStepReady(step: OnboardingStep): Boolean {
        val s = _state.value
        return when (step) {
            OnboardingStep.WELCOME -> true
            OnboardingStep.FEATURE_AI -> true
            OnboardingStep.FEATURE_VOICE -> true
            OnboardingStep.FEATURE_GLASSES -> true
            OnboardingStep.FEATURE_SMART -> true
            OnboardingStep.PROVIDER -> s.providerId.isNotBlank()
            OnboardingStep.API_KEY -> s.apiKey.isNotBlank() && s.apiKey.length > 5
            OnboardingStep.WEATHER -> true  // opcjonalne
            OnboardingStep.WAKE_WORD -> true  // opcjonalne
            OnboardingStep.LOCATION -> true   // opcjonalne
            OnboardingStep.PERMISSIONS -> true  // nawet bez uprawnień idź dalej
            OnboardingStep.GLASSES -> true    // opcjonalne
            OnboardingStep.DONE -> true
        }
    }
}
