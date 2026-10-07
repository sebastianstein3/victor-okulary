package pl.victor.app.features.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.victor.app.memory.PlaceMemory
import pl.victor.app.proactive.LocationContext
import java.util.concurrent.TimeUnit

/**
 * Przypomnienia związane z miejscem - zapis i pilnowanie.
 *
 * Co [INTERWAŁ_MS] sprawdza położenie. Dom i praca są porównywane z
 * zapisanymi współrzędnymi; sklepy i rodzaje miejsc ("apteka") są szukane w
 * OpenStreetMap wokół bieżącego położenia (Overpass, darmowe), ale tylko po
 * przesunięciu - stojąc w miejscu, nie pytamy w kółko o to samo.
 *
 * Gdy przypomnienie wypada: powiadomienie na telefonie ZAWSZE (działa też bez
 * okularów), a w okularach - gdy są połączone i nikt nie mówi.
 */
class PlaceReminders(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mów: suspend (String) -> Unit,
    private val wolno: () -> Boolean,
    private val okulary: () -> Boolean,
    private val miejsce: (String) -> PlaceMemory.Place?,
    private val dziennik: (String, Map<String, Any?>) -> Unit,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
) {
    private data class Zapis(val id: Long, val co: String, val gdzie: String, val utworzoneMs: Long)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _lista = MutableStateFlow(wczytaj())
    val lista: StateFlow<List<PlaceReminder>> = _lista.asStateFlow()

    private var pilnowanie: Job? = null
    private val ostatnieSzukanie = HashMap<Long, Pair<Double, Double>>()

    fun dodaj(p: PlaceReminderLogic.Prośba): PlaceReminder {
        val r = PlaceReminder(System.currentTimeMillis(), p.co, p.gdzie, PlaceReminderLogic.cel(p.gdzie), System.currentTimeMillis())
        _lista.value = _lista.value + r
        zapisz()
        dziennik("przypomnienie w miejscu: dodane", mapOf("gdzie" to p.gdzie, "cel" to r.cel::class.simpleName))
        pilnuj()
        return r
    }

    fun usuń(id: Long) {
        _lista.value = _lista.value.filterNot { it.id == id }
        zapisz()
    }

    fun usuńWszystkie() {
        _lista.value = emptyList()
        zapisz()
    }

    /** Uruchamia pilnowanie, jeśli jest czego pilnować. Bezpieczne do wołania wielokrotnie. */
    fun pilnuj() {
        if (_lista.value.isEmpty() || pilnowanie?.isActive == true) return
        pilnowanie = scope.launch {
            while (_lista.value.isNotEmpty()) {
                runCatching { sprawdź() }.onFailure { Log.w(TAG, "Sprawdzanie przypomnień", it) }
                delay(INTERWAŁ_MS)
            }
        }
    }

    private suspend fun sprawdź() {
        val tu = LocationContext.tutaj(context, naŚwieżąMs = GPS_MS) ?: return
        for (r in _lista.value.toList()) {
            val trafienie: String? = when (val cel = r.cel) {
                is Cel.Zapisane -> miejsce(cel.nazwa)?.let { m ->
                    val d = PlaceReminderLogic.odległośćM(tu.lat, tu.lon, m.latitude, m.longitude)
                    if (d <= PlaceReminderLogic.PROMIEŃ_ZAPISANE_M) (if (cel.nazwa == PlaceReminderLogic.DOM) "dom" else "praca") else null
                }
                else -> {
                    val ost = ostatnieSzukanie[r.id]
                    val przesunięcie = ost?.let { PlaceReminderLogic.odległośćM(it.first, it.second, tu.lat, tu.lon) }
                    if (przesunięcie != null && przesunięcie < PRZESUNIĘCIE_M) {
                        null
                    } else {
                        ostatnieSzukanie[r.id] = tu.lat to tu.lon
                        szukaj(cel, tu.lat, tu.lon)
                    }
                }
            }
            if (trafienie != null) wypadło(r, trafienie)
        }
    }

    private suspend fun szukaj(cel: Cel, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val q = PlaceReminderLogic.zapytanieOverpass(cel, lat, lon, PlaceReminderLogic.PROMIEŃ_OBIEKT_M)
            ?: return@withContext null
        runCatching {
            val req = Request.Builder()
                .url(OVERPASS)
                .post(FormBody.Builder().add("data", q).build())
                .header("User-Agent", "VICTOR-glasses-assistant/0.1 (Android)")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val obiekty = PlaceReminderLogic.parsujOverpass(r.body?.string().orEmpty())
                obiekty.minByOrNull { PlaceReminderLogic.odległośćM(lat, lon, it.lat, it.lon) }
                    ?.let { it.nazwa ?: (cel as? Cel.Rodzaj)?.opis ?: "" }
            }
        }.onFailure { Log.w(TAG, "Overpass nie odpowiedział", it) }.getOrNull()
    }

    private suspend fun wypadło(r: PlaceReminder, gdzie: String) {
        usuń(r.id)
        val tekst = PlaceReminderLogic.komunikat(r, gdzie.ifBlank { null })
        dziennik("przypomnienie w miejscu: wypadło", mapOf("gdzie" to gdzie))
        powiadomienie(tekst)
        if (okulary()) {
            val doKiedy = System.currentTimeMillis() + CZEKAJ_NA_CISZĘ_MS
            while (!wolno() && System.currentTimeMillis() < doKiedy) delay(500)
            if (wolno()) mów(tekst)
        }
    }

    private fun powiadomienie(tekst: String) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(KANAŁ) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(KANAŁ, "Przypomnienia w miejscach", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            nm.notify(
                (System.currentTimeMillis() % 100_000).toInt(),
                NotificationCompat.Builder(context, KANAŁ)
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle("Przypomnienie")
                    .setContentText(tekst)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(tekst))
                    .setAutoCancel(true)
                    .build()
            )
        }.onFailure { Log.w(TAG, "Powiadomienie nie poszło", it) }
    }

    private fun wczytaj(): List<PlaceReminder> = runCatching {
        val typ = object : TypeToken<List<Zapis>>() {}.type
        val zapisy: List<Zapis> = gson.fromJson(prefs.getString(KLUCZ, "[]"), typ) ?: emptyList()
        zapisy.map { PlaceReminder(it.id, it.co, it.gdzie, PlaceReminderLogic.cel(it.gdzie), it.utworzoneMs) }
    }.getOrDefault(emptyList())

    private fun zapisz() {
        val zapisy = _lista.value.map { Zapis(it.id, it.co, it.gdzie, it.utworzoneMs) }
        prefs.edit().putString(KLUCZ, gson.toJson(zapisy)).apply()
    }

    companion object {
        private const val TAG = "PlaceReminders"
        private const val PREFS = "place_reminders"
        private const val KLUCZ = "lista"
        private const val KANAŁ = "place_reminders"
        private const val OVERPASS = "https://overpass-api.de/api/interpreter"

        /** Co ile sprawdzać - spacerem to circa 150 m, w sam raz na wejście do sklepu. */
        const val INTERWAŁ_MS = 2 * 60_000L
        private const val GPS_MS = 6_000L
        private const val PRZESUNIĘCIE_M = 60.0
        private const val CZEKAJ_NA_CISZĘ_MS = 60_000L
    }
}
