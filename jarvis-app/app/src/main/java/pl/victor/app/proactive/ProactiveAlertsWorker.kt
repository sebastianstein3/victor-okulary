package pl.victor.app.proactive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pl.victor.app.VictorApplication
import pl.victor.app.R
import pl.victor.app.ui.MainActivity

/**
 * Worker który sprawdza alerty proaktywne w tle.
 * Uruchamiany co 15 minut przez WorkManager.
 *
 * Sprawdza:
 * 1. Kalendarz - następne spotkanie
 * 2. Pogodę - czy będzie padać w oknie (teraz → wyjście)
 * 3. Generuje alerty jeśli warunki są spełnione
 * 4. Wyświetla notyfikację z rekomendacją
 */
class ProactiveAlertsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val tag = "ProactiveAlertsWorker"

    override suspend fun doWork(): Result {
        Log.d(tag, "Running proactive alerts check")

        val app = applicationContext as? VictorApplication
            ?: return Result.success()

        // 1. Sprawdź czy worker jest włączony
        if (!app.settings.isProactiveAlertsEnabled()) {
            Log.d(tag, "Proactive alerts disabled in settings")
            return Result.success()
        }

        // 2. Sprawdź kalendarz
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            Log.d(tag, "Brak READ_CALENDAR - skip")
            return Result.success()
        }

        val calendarService = CalendarService(applicationContext)
        // Brak spotkania nie kończy pracy - alerty pogodowe mają sens same z siebie.
        val nextEvent = calendarService.getNextEventToLeaveFor()
        if (nextEvent == null) {
            Log.d(tag, "Brak nadchodzących spotkań - sprawdzam samą pogodę")
        }

        // 3. Sprawdź pogodę
        val apiKey = app.settings.getOpenWeatherApiKey()
        if (apiKey.isBlank()) {
            Log.d(tag, "Brak klucza OpenWeatherMap")
            return Result.success()
        }

        val location = app.settings.getWeatherLocation()
        if (location.isBlank()) {
            Log.d(tag, "Brak lokalizacji dla pogody")
            return Result.success()
        }

        val weatherService = WeatherService(apiKey)
        val geo = weatherService.geocode(location)
        if (geo == null) {
            Log.d(tag, "Nie znalazłem lokalizacji: $location")
            return Result.success()
        }

        val forecast = weatherService.getForecast(geo.lat, geo.lon)
        if (forecast == null) {
            Log.d(tag, "Nie udało się pobrać prognozy")
        }

        // 4. Jakość powietrza (osobny endpoint OWM)
        val airQuality = weatherService.getAirQuality(geo.lat, geo.lon)

        // 5. Analizuj i wygeneruj alerty
        val engine = ProactiveAlertsEngine()
        val alerts = engine.analyze(nextEvent, forecast, airQuality)

        // 6. Wyślij notyfikacje (ale nie spamuj - tylko raz na alert)
        val prefs = app.settings
        val halfHourSlot = System.currentTimeMillis() / (30 * 60 * 1000)
        alerts.forEach { alert ->
            val alertKey = if (nextEvent != null) {
                "${alert.type}-${nextEvent.id}-${nextEvent.beginMs / (30 * 60 * 1000)}"
            } else {
                "${alert.type}-weather-$halfHourSlot"
            }
            if (!prefs.isAlertAlreadyShown(alertKey)) {
                sendNotification(alert)
                speakAlert(app, alert)
                prefs.markAlertShown(alertKey)
            } else {
                Log.d(tag, "Alert $alertKey już wysłany, skip")
            }
        }

        return Result.success()
    }

    /**
     * Wypowiada alert - w okularach, jeśli są na głowie.
     *
     * ## Po co, skoro jest powiadomienie
     * Bo powiadomienie wymaga wyjęcia telefonu, a to mija się z całym sensem
     * okularów. Zgłoszono to wprost: "alerty pogodowe wyświetlają się tylko
     * jako powiadomienia, a nie głosowo na okularach".
     *
     * ## Czego świadomie NIE robimy
     * Nie mówimy w trakcie rozmowy z asystentem - alert wszedłby w słowo w
     * połowie odpowiedzi. Nie mówimy też domyślnie bez okularów: nagły głos z
     * telefonu w kieszeni jest zaskoczeniem, a nie pomocą (da się to włączyć w
     * ustawieniach). Powiadomienie idzie zawsze, więc żaden alert nie ginie -
     * mowa jest dodatkiem, nie zamiennikiem.
     */
    private suspend fun speakAlert(app: VictorApplication, alert: ProactiveAlert) {
        if (!app.settings.isAlertsSpokenEnabled()) return

        val glassesOn = runCatching {
            app.glassesManager.connectionState.value ==
                pl.victor.app.ble.ConnectionState.READY
        }.getOrDefault(false)
        if (!glassesOn && !app.settings.isAlertsSpokenWithoutGlasses()) {
            Log.d(tag, "Alert nie wypowiedziany - okulary niepołączone")
            runCatching {
                app.diag.event(
                    pl.victor.app.diagnostics.DiagFormat.Phase.MOWA,
                    "alert pominięty - okulary niepołączone",
                    mapOf("rodzaj" to alert.type.name)
                )
            }
            return
        }

        // Rozmowa ma pierwszeństwo: alert nie może wejść w słowo w połowie
        // odpowiedzi. Przepadnie tylko mowa - powiadomienie już poszło.
        val busy = runCatching {
            app.orchestrator.state.value !is pl.victor.app.OrchestratorState.Idle
        }.getOrDefault(false)
        if (busy) {
            Log.d(tag, "Alert nie wypowiedziany - trwa rozmowa")
            return
        }

        val spoken = "${alert.title}. ${alert.message}"

        // POPROŚ OKULARY O TRYB MULTIMEDIÓW - tak samo jak zwykła tura.
        //
        // Zwykła odpowiedź robi to przed mówieniem (VictorManager.requestClassicAudio),
        // alert nie robił tego nigdy. Okulary, które telefon widzi wyłącznie
        // jako zestaw głośnomówiący, mają wtedy czynny sam profil rozmowy - i
        // alert szedł albo przez telefon, albo przez gorszy kanał. Zgłoszone:
        // "chyba nie czyta tych powiadomień o pogodzie normalnie przez okulary".
        //
        // Prośba jest bez czekania na skutek (profil zestawia system, nie my),
        // więc nie opóźnia tego alertu - poprawia kolejny. To ta sama zasada,
        // co na ścieżce tury.
        if (glassesOn) {
            runCatching { app.glassesManager.requestClassicAudio("alert proaktywny") }
        }

        val held = runCatching { app.audio.beginConversationRouting() }.getOrDefault(false)
        try {
            runCatching { app.audio.speakAndAwait(spoken, language = "pl") }
                .onSuccess {
                    // DO DZIENNIKA, nie tylko do logcata: to jest jedyny ślad
                    // po alercie, a zgłoszenie brzmiało "chyba nie czyta" -
                    // czyli osoba testująca sama nie była pewna, czy zadziałało.
                    runCatching {
                        app.diag.event(
                            pl.victor.app.diagnostics.DiagFormat.Phase.MOWA,
                            "alert wypowiedziany",
                            mapOf("rodzaj" to alert.type.name, "okulary" to glassesOn)
                        )
                    }
                }
                .onFailure { Log.w(tag, "Nie udało się wypowiedzieć alertu", it) }
        } finally {
            if (held) runCatching { app.audio.endConversationRouting() }
        }
    }

    private fun sendNotification(alert: ProactiveAlert) {
        if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            Log.w(tag, "Brak POST_NOTIFICATIONS - nie wyślę notyfikacji")
            return
        }

        // Android 8+ - kanał
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proaktywne alerty",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pogoda, kalendarz, sugestie wyjścia"
                enableVibration(true)
            }
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_victor)
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(alert.type.ordinal + 100, notification)
            Log.i(tag, "Wysłano notyfikację: ${alert.title}")
        } catch (e: SecurityException) {
            Log.w(tag, "Notification permission missing", e)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(applicationContext, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_ID = "proactive_alerts"
        const val WORK_NAME = "proactive_alerts_worker"
    }
}
