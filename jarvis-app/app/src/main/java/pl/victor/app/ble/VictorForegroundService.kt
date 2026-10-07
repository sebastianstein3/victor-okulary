package pl.victor.app.ble

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import pl.victor.app.R
import pl.victor.app.ui.MainActivity

/**
 * Usługa pierwszoplanowa, która trzyma proces przy życiu, gdy V.I.C.T.O.R. nasłuchuje
 * wake worda i/lub jest połączony z okularami przez BLE.
 *
 * Bez tego Android usypia proces kilka minut po zgaszeniu ekranu (Doze/App Standby)
 * i połączenie BLE oraz `AudioRecord` używany przez nasłuch frazy przestają działać po
 * cichu - funkcje "wyglądają" jak włączone w Ustawieniach, ale nic nie reaguje.
 * Start/stop jest sterowany reaktywnie z [pl.victor.app.VictorApplication] na
 * podstawie stanu połączenia i przełącznika wake worda, więc nic innego nie musi
 * pamiętać o wywołaniu tej usługi.
 *
 * ## Dlaczego typ usługi jest tu tak ostrożnie dobierany
 *
 * Od Androida 14 typ `microphone` jest przywilejem "while in use": system pozwala go
 * WZIĄĆ tylko wtedy, gdy aplikacja w tej chwili ma prawo nagrywać, czyli jest na
 * wierzchu. Wywołanie `startForeground` z tym typem Z TŁA kończy się wyjątkiem.
 *
 * A my wołaliśmy je z tła regularnie: każda zmiana stanu połączenia okularów i każde
 * przełączenie frazy wybudzenia woła `refreshBackgroundService()`, a ono za każdym
 * razem wchodziło tą samą drogą. Przy zablokowanym telefonie okulary lubią się
 * rozłączyć i połączyć ponownie - i wtedy usługa dostawała wyjątek, a poprzednia
 * wersja odpowiadała na to `stopSelf()`. Ginęła CAŁA usługa: BLE, nasłuch, wszystko,
 * aż do odblokowania telefonu i wejścia w aplikację.
 *
 * Dlatego teraz:
 * 1. Kolejne odświeżenia zmieniają wyłącznie TREŚĆ powiadomienia. Skoro usługa już
 *    działa na wierzchu, nie ma po co (ani po bezpiecznemu) prosić o to drugi raz.
 * 2. Typ `microphone` bierzemy tylko wtedy, gdy da się go wziąć, i raz wzięty
 *    zostaje - system go nie odbiera po zgaszeniu ekranu.
 * 3. Odmowa typu z mikrofonem obniża usługę do samego `connectedDevice`, zamiast ją
 *    zabijać. Połączenie z okularami przeżywa nawet wtedy, gdy nasłuch nie może.
 */
class VictorForegroundService : Service() {

    /** Czy `startForeground` już się udało - patrz punkt 1 w opisie klasy. */
    private var foregroundStarted = false

    /** Czy udało się wziąć typ z mikrofonem. Raz wzięty, zostaje na czas życia usługi. */
    private var microphoneClaimed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: DEFAULT_REASON
        if (foregroundStarted) {
            // Samo odświeżenie tekstu. Ani nie potrzebuje uprawnień, ani nie może
            // zostać odrzucone - a to właśnie odrzucenie zabijało usługę w tle.
            updateNotification(reason)
            // Usługa mogła wystartować BEZ mikrofonu, bo w tamtej chwili ekran był
            // zablokowany. Bez tej próby nasłuch frazy zostałby martwy do końca
            // życia usługi - a wystarczy, że użytkownik wróci do aplikacji.
            // Nieudana próba nie rusza stanu: system rzuca wyjątkiem, zanim
            // cokolwiek zmieni, więc usługa zostaje na pierwszym planie jak była.
            if (!microphoneClaimed) {
                startInForeground(reason, withMicrophone = true, withLocation = true) ||
                    startInForeground(reason, withMicrophone = true, withLocation = false)
            }
            return START_STICKY
        }
        // Kolejność: z mikrofonem i lokalizacją, z samym mikrofonem, bez obu.
        // Odmowa lokalizacji (brak zgody) nie może odebrać mikrofonu.
        if (!startInForeground(reason, withMicrophone = true, withLocation = true) &&
            !startInForeground(reason, withMicrophone = true, withLocation = false) &&
            !startInForeground(reason, withMicrophone = false, withLocation = false)
        ) {
            // Obie drogi odmówiły - to ograniczenie systemowe (brak wyjątku na start
            // usługi z tła), nie błąd aplikacji. Kończymy po cichu zamiast wywracać
            // proces; następna zmiana stanu spróbuje ponownie.
            Log.w(TAG, "System nie pozwolił wystartować usługi pierwszoplanowej")
            stopSelf()
        }
        return START_STICKY
    }

    /**
     * Jedna próba wejścia na pierwszy plan.
     *
     * @param withMicrophone czy prosić także o typ `microphone` (potrzebny do nasłuchu
     *   frazy przy zgaszonym ekranie, ale możliwy do wzięcia tylko z wierzchu)
     * @return czy się udało
     */
    private fun startInForeground(
        reason: String,
        withMicrophone: Boolean,
        withLocation: Boolean = false
    ): Boolean {
        if (withMicrophone && !canClaimMicrophone()) return false
        // LOKALIZACJA W TLE. Bez typu `location` Android uznaje odczyt położenia
        // przy zablokowanym telefonie za dostęp w tle i go odmawia - przewodnik,
        // przypomnienia w miejscach i pogoda "tam, gdzie jesteś" działałyby
        // tylko z włączonym ekranem. Bierzemy go tylko przy zgodzie na
        // lokalizację; bez niej system rzuciłby wyjątkiem.
        if (withLocation && !hasLocationPermission()) return false
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (withMicrophone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (withLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return try {
            // Wprost API platformy, nie ServiceCompat: trzyargumentowe
            // startForeground istnieje od Androida 10, a niżej typu i tak się nie
            // podaje - system bierze go wtedy wyłącznie z manifestu.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(reason), type)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(reason))
            }
            foregroundStarted = true
            microphoneClaimed = withMicrophone
            Log.i(TAG, "Usługa na pierwszym planie, mikrofon: $withMicrophone, lokalizacja: $withLocation")
            true
        } catch (e: Exception) {
            // Android 12+ potrafi odmówić startu z tła, a Android 14+ osobno odmawia
            // samego typu `microphone`. Rozróżnienie zostawiamy kolejnej próbie -
            // ona odpowie na pytanie, czy problem był w mikrofonie, czy w starcie.
            Log.w(TAG, "Start na pierwszym planie odrzucony (mikrofon: $withMicrophone): ${e.message}")
            false
        }
    }

    /**
     * Czy wolno nam w tej chwili wziąć typ `microphone`.
     *
     * Do Androida 13 typ nie był w ogóle sprawdzany przy starcie. Od 14 wolno go
     * wziąć tylko z wierzchu - a pytanie "czy jesteśmy na wierzchu" sprowadza się
     * tu do tego, czy ekran jest odblokowany: usługa startuje z aplikacji, więc gdy
     * użytkownik ją widzi, warunek jest spełniony.
     */
    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun canClaimMicrophone(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return runCatching {
            val keyguard = getSystemService(KeyguardManager::class.java)
            keyguard != null && !keyguard.isKeyguardLocked
        }.getOrDefault(false)
    }

    /**
     * Podmienia treść powiadomienia bez ruszania stanu usługi.
     *
     * Identyfikator ten sam, co w [startInForeground], więc system traktuje to jako
     * aktualizację tego samego powiadomienia, a nie drugie obok.
     */
    private fun updateNotification(reason: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(reason))
        }.onFailure { Log.w(TAG, "Nie udało się odświeżyć powiadomienia", it) }
    }

    private fun buildNotification(reason: String): android.app.Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("V.I.C.T.O.R.")
            .setContentText(reason)
            .setSmallIcon(R.drawable.ic_stat_victor)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "V.I.C.T.O.R. w tle", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Utrzymuje połączenie z okularami i nasłuch wake worda w tle."
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "VictorFgService"
        private const val CHANNEL_ID = "victor_background"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_REASON = "reason"
        private const val DEFAULT_REASON = "Aktywny w tle"

        fun start(context: Context, reason: String) {
            try {
                val intent = Intent(context, VictorForegroundService::class.java)
                    .putExtra(EXTRA_REASON, reason)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Nie udało się uruchomić usługi w tle: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VictorForegroundService::class.java))
        }
    }
}
