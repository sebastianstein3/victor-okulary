package pl.victor.app.ui.onboarding

/**
 * Stan onboardingu - śledzi postęp użytkownika przez setup.
 */
data class OnboardingState(
    val currentStep: Int = 0,
    val totalSteps: Int = 13,
    val providerId: String = "gemini",
    val apiKey: String = "",
    val openWeatherKey: String = "",
    /**
     * Wybrana fraza wybudzenia ([pl.victor.app.data.SettingsRepository.WAKE_ENGINE_HEJ_WIKTOR]
     * albo [pl.victor.app.data.SettingsRepository.WAKE_ENGINE_HEJ_LENS]);
     * `null` - krok pominięty, ustawienia zostają, jak były.
     */
    val wakeEngine: String? = null,
    val city: String = "",
    /** Mikrofon - bez niego nie działa NIC, co dotyczy głosu. */
    val hasMicPermission: Boolean = false,
    val hasCalendarPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasBatteryExemption: Boolean = false,
    val glassesPaired: Boolean = false,
    val finished: Boolean = false
) {
    /**
     * Czy dany krok jest "gotowy" - przejście dalej jest możliwe.
     */
    fun isStepReady(step: OnboardingStep): Boolean = when (step) {
        OnboardingStep.WELCOME -> true
        OnboardingStep.FEATURE_AI -> true
        OnboardingStep.FEATURE_VOICE -> true
        OnboardingStep.FEATURE_GLASSES -> true
        OnboardingStep.FEATURE_SMART -> true
        OnboardingStep.PROVIDER -> providerId.isNotBlank()
        OnboardingStep.API_KEY -> apiKey.isNotBlank()
        OnboardingStep.WEATHER -> true  // opcjonalne
        OnboardingStep.WAKE_WORD -> true  // opcjonalne
        OnboardingStep.LOCATION -> true  // opcjonalne
        OnboardingStep.PERMISSIONS -> true  // uprawnienia można dać później
        OnboardingStep.GLASSES -> true  // opcjonalne
        OnboardingStep.DONE -> true
    }
}

/**
 * Kroki onboardingu - wyświetlane w określonej kolejności.
 */
enum class OnboardingStep(val title: String, val emoji: String) {
    WELCOME("Witaj w V.I.C.T.O.R.", "👋"),
    FEATURE_AI("AI, które działa", "🧠"),
    FEATURE_VOICE("Steruj głosem", "🎙️"),
    FEATURE_GLASSES("Przycisk i panel", "👓"),
    FEATURE_SMART("Kalendarz i mail", "📅"),
    PROVIDER("Wybierz AI", "🧠"),
    API_KEY("Klucz API", "🔑"),
    WEATHER("Pogoda", "🌦️"),
    WAKE_WORD("Fraza wybudzenia", "🎙️"),
    LOCATION("Lokalizacja", "📍"),
    PERMISSIONS("Uprawnienia", "🔐"),
    GLASSES("Okulary", "👓"),
    DONE("Gotowe!", "🎉")
}

/**
 * Czy onboarding został ukończony.
 */
object OnboardingPrefs {
    fun isCompleted(): Boolean = false  // sprawdzane w SettingsRepository
    fun markCompleted() { /* zapis */ }
}
