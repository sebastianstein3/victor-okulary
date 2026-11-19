package pl.victor.app.power

/**
 * Profil zasilania - kontroluje ile baterii apka może zużywać.
 *
 * ECO (oszczędny):
 * - Wake word OFF
 * - Proaktywne alerty co 60 min
 * - AI: preferuje mniejsze modele (Flash zamiast Pro)
 * - Bez video
 * - Skrócenie historii do 10 wpisów
 * - Cel: < 3% baterii/godz
 *
 * NORMAL (zbalansowany):
 * - Wake word ON (nasłuch "Hej Wiktor" na telefonie)
 * - Proaktywne alerty co 15 min
 * - AI: domyślny model
 * - Video dostępne ale ostrzeżenie
 * - Historia 20 wpisów
 * - Cel: < 8% baterii/godz
 *
 * PERFORMANCE (wydajność):
 * - Wake word ON, szybka reakcja
 * - Proaktywne alerty co 5 min
 * - AI: najlepszy model
 * - Video zawsze dostępne
 * - Historia 50 wpisów
 * - Cel: 15-20% baterii/godz
 */
enum class PowerMode(
    val displayName: String,
    val emoji: String,
    val description: String,
    val batteryPerHourPercent: Int,
    val wakeWordEnabled: Boolean,
    val proactiveIntervalMinutes: Int,
    val preferredModelQuality: ModelQuality,
    val allowVideo: Boolean,
    val historyLimit: Int,
    val allowStreaming: Boolean,
    val allowTTSContinuous: Boolean
) {
    ECO(
        displayName = "Oszczędny",
        emoji = "🔋",
        description = "Minimalne zużycie. Bez wake word, alerty co godzinę.",
        batteryPerHourPercent = 3,
        wakeWordEnabled = false,
        proactiveIntervalMinutes = 60,
        preferredModelQuality = ModelQuality.SMALL,
        allowVideo = false,
        historyLimit = 10,
        allowStreaming = false,
        allowTTSContinuous = false
    ),

    NORMAL(
        displayName = "Normalny",
        emoji = "⚡",
        description = "Zbalansowany. Wake word, alerty co 15 min.",
        batteryPerHourPercent = 8,
        wakeWordEnabled = true,
        proactiveIntervalMinutes = 15,
        preferredModelQuality = ModelQuality.MEDIUM,
        allowVideo = true,
        historyLimit = 20,
        allowStreaming = true,
        allowTTSContinuous = true
    ),

    PERFORMANCE(
        displayName = "Wydajność",
        emoji = "🚀",
        description = "Pełna moc. Najlepsze AI, video, alerty co 5 min.",
        batteryPerHourPercent = 20,
        wakeWordEnabled = true,
        proactiveIntervalMinutes = 5,
        preferredModelQuality = ModelQuality.LARGE,
        allowVideo = true,
        historyLimit = 50,
        allowStreaming = true,
        allowTTSContinuous = true
    );

    /**
     * Czy ten tryb powinien być użyty przy danym % baterii.
     */
    fun isSuitable(batteryPercent: Int, charging: Boolean): Boolean {
        if (charging) return true  // Na ładowarce wszystko
        return when (this) {
            ECO -> batteryPercent <= 100  // zawsze OK
            NORMAL -> batteryPercent >= 20
            PERFORMANCE -> batteryPercent >= 50
        }
    }
}

enum class ModelQuality {
    SMALL,   // Gemini Flash, GPT-4o mini - szybkie, tanie
    MEDIUM,  // Gemini Flash, GPT-4o
    LARGE    // Gemini Pro, GPT-4o, Claude Sonnet
}

/**
 * Aktualny stan baterii (snapshot).
 */
data class BatteryState(
    val percent: Int,           // 0-100
    val charging: Boolean,
    val temperature: Float,     // °C
    val voltage: Float,         // mV
    val health: BatteryHealth,
    val isPowerSaveMode: Boolean
) {
    /**
     * Automatyczny wybór trybu na bazie stanu.
     */
    fun autoSelectMode(): PowerMode = when {
        charging && percent >= 80 -> PowerMode.PERFORMANCE
        charging -> PowerMode.NORMAL
        isPowerSaveMode -> PowerMode.ECO
        percent <= 15 -> PowerMode.ECO
        percent <= 40 -> PowerMode.NORMAL
        else -> PowerMode.NORMAL  // domyślnie
    }
}

enum class BatteryHealth {
    GOOD, OVERHEATING, DEAD, COLD, UNKNOWN
}
