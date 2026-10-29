package pl.victor.app.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager as AndroidPowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.data.SettingsRepository

/**
 * Centralny zarządzający zasilaniem.
 *
 * 1. Monitoruje baterię (BroadcastReceiver)
 * 2. Automatycznie przełącza tryb (PowerMode) na bazie %
 * 3. Włącza/wyłącza usługi (wake word, proaktywne alerty)
 * 4. Informuje inne komponenty przez StateFlow
 *
 * Hooks:
 * - onModeChange callback - gdy tryb się zmienił automatycznie
 * - subscribers mogą pytać o `currentMode` przez StateFlow
 */
class PowerManager(
    private val context: Context,
    private val settings: SettingsRepository
) {
    private val tag = "PowerManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val androidPM = context.getSystemService(Context.POWER_SERVICE) as AndroidPowerManager

    private val _currentMode = MutableStateFlow(loadInitialMode())
    val currentMode: StateFlow<PowerMode> = _currentMode.asStateFlow()

    private val _batteryState = MutableStateFlow(readBatteryState())
    val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()

    private val _autoModeEnabled = MutableStateFlow(settings.isAutoPowerModeEnabled())
    val autoModeEnabled: StateFlow<Boolean> = _autoModeEnabled.asStateFlow()

    private val _userOverride = MutableStateFlow<PowerMode?>(null)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = readBatteryState()
            _batteryState.value = state
            Log.d(tag, "Battery: ${state.percent}%, charging=${state.charging}")

            if (_autoModeEnabled.value) {
                autoAdjustMode(state)
            }
        }
    }

    init {
        // Rejestruj receiver na zmiany baterii
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(batteryReceiver, filter)

        // Sprawdzaj co 5 min (WorkManager-like)
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5 * 60 * 1000)
                val state = readBatteryState()
                _batteryState.value = state
                if (_autoModeEnabled.value) {
                    autoAdjustMode(state)
                }
            }
        }
    }

    /**
     * Wczytuje tryb z ustawień (user choice) lub automatyczny.
     */
    private fun loadInitialMode(): PowerMode {
        val savedName = settings.getPowerMode()
        return try {
            PowerMode.valueOf(savedName)
        } catch (e: Exception) {
            PowerMode.NORMAL
        }
    }

    /**
     * Ustawia tryb ręcznie (user z Settings).
     * Wyłącza auto-mode.
     */
    fun setMode(mode: PowerMode) {
        _userOverride.value = mode
        _currentMode.value = mode
        settings.setPowerMode(mode.name)
        Log.i(tag, "Mode set manually: $mode")
        applyModeSettings(mode)
    }

    /**
     * Włącza/wyłącza tryb automatyczny.
     */
    fun setAutoMode(enabled: Boolean) {
        _autoModeEnabled.value = enabled
        settings.setAutoPowerModeEnabled(enabled)
        if (enabled) {
            autoAdjustMode(_batteryState.value)
        }
    }

    /**
     * Automatycznie dostosowuje tryb do stanu baterii.
     */
    private fun autoAdjustMode(state: BatteryState) {
        val recommended = state.autoSelectMode()
        val current = _currentMode.value

        if (current == recommended) return

        // Nie degraduj poniżej tego co user wymusił
        _userOverride.value?.let { override ->
            if (override.batteryPerHourPercent < recommended.batteryPerHourPercent) {
                Log.d(tag, "Keeping user override: $override")
                return
            }
        }

        Log.i(tag, "Auto-adjusting mode: $current → $recommended (battery: ${state.percent}%)")
        _currentMode.value = recommended
        applyModeSettings(recommended)
    }

    /**
     * Aplikuje ustawienia trybu (wyłącza usługi których tryb nie potrzebuje).
     */
    @Volatile
    private var wakeWordDisabledByPower = false

    private fun applyModeSettings(mode: PowerMode) {
        // 1. Wake word
        val shouldHaveWakeWord = mode.wakeWordEnabled
        val currentlyHasWakeWord = settings.isWakeWordEnabled()
        // Tryb zasilania może wyłączyć nasłuch frazy, ale TYLKO WŁĄCZAĆ to, co sam
        // wyłączył: wcześniej po podłączeniu ładowarki włączał go nawet komuś, kto
        // świadomie go wyłączył - telefon zaczynał słuchać bez zgody (audyt 28.10).
        if (currentlyHasWakeWord && !shouldHaveWakeWord) {
            Log.d(tag, "Wake word: wyłączam na czas oszczędzania baterii")
            wakeWordDisabledByPower = true
            settings.setWakeWordEnabled(false)
            // (w MainActivity jest listener który to obsłuży)
        } else if (!currentlyHasWakeWord && shouldHaveWakeWord && wakeWordDisabledByPower) {
            Log.d(tag, "Wake word: przywracam po oszczędzaniu baterii")
            wakeWordDisabledByPower = false
            settings.setWakeWordEnabled(true)
        }

        // 2. Proaktywne alerty - zmiana interwału
        Log.d(tag, "Proactive interval: ${mode.proactiveIntervalMinutes} min")
        settings.setProactiveIntervalMinutes(mode.proactiveIntervalMinutes)

        // 3. Historia
        Log.d(tag, "History limit: ${mode.historyLimit}")
        settings.setHistoryLimit(mode.historyLimit)
    }

    /**
     * Czyta aktualny stan baterii z systemu.
     */
    private fun readBatteryState(): BatteryState {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent: Int = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10f) ?: 0f
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.toFloat() ?: 0f
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL ||
                       isCharging

        val healthInt = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val health = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEATING
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
            else -> BatteryHealth.UNKNOWN
        }

        val isPowerSave = androidPM.isPowerSaveMode

        return BatteryState(
            percent = percent,
            charging = charging,
            temperature = temperature,
            voltage = voltage,
            health = health,
            isPowerSaveMode = isPowerSave
        )
    }

    /**
     * Szacowany czas pracy na baterii (w godzinach).
     */
    fun estimateRemainingHours(): Float {
        val state = _batteryState.value
        val mode = _currentMode.value
        if (state.charging) return Float.POSITIVE_INFINITY
        return state.percent.toFloat() / mode.batteryPerHourPercent.toFloat()
    }

    /**
     * Hook: czy dany ficzer powinien działać.
     */
    fun isFeatureEnabled(feature: PowerFeature): Boolean {
        return when (feature) {
            PowerFeature.WAKE_WORD -> _currentMode.value.wakeWordEnabled
            PowerFeature.PROACTIVE -> _currentMode.value.proactiveIntervalMinutes > 0
            PowerFeature.VIDEO_CAPTURE -> _currentMode.value.allowVideo
            PowerFeature.STREAMING_TTS -> _currentMode.value.allowStreaming
            PowerFeature.CONTINUOUS_TTS -> _currentMode.value.allowTTSContinuous
        }
    }

    fun cleanup() {
        // Także pętla pomiarów (co 5 min) - bez anulowania żyła do końca procesu.
        scope.cancel()
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}
    }
}

enum class PowerFeature {
    WAKE_WORD,
    PROACTIVE,
    VIDEO_CAPTURE,
    STREAMING_TTS,
    CONTINUOUS_TTS
}
