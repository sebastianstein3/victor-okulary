package pl.victor.app.navigation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Przystanki i linie z serwera Overpass (OpenStreetMap) - patrz [Przystanki].
 *
 * Darmowe i bez klucza. Do serwera idą same współrzędne i ewentualnie nazwa
 * przystanku z pytania - bez treści pytania i danych użytkownika. Ostatnią
 * odpowiedź pamiętamy kilka minut: "a które autobusy?" zaraz po "jakie
 * tramwaje?" nie pyta serwera drugi raz (Overpass prosi o umiar).
 */
object PrzystankiClient {

    private const val TAG = "Przystanki"
    private const val URL = "https://overpass-api.de/api/interpreter"

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class Pamięć(val nazwa: String?, val lat: Double, val lon: Double, val czasMs: Long, val wynik: List<Przystanki.Przystanek>)

    @Volatile
    private var pamięć: Pamięć? = null

    /** Przystanki od najbliższego albo `null`, gdy serwer nie odpowiedział. */
    suspend fun przystanki(lat: Double, lon: Double, nazwa: String?): List<Przystanki.Przystanek>? =
        withContext(Dispatchers.IO) {
            val teraz = System.currentTimeMillis()
            pamięć?.let { p ->
                if (p.nazwa == nazwa && teraz - p.czasMs < PAMIĘĆ_MS &&
                    Przystanki.odległość(p.lat, p.lon, lat, lon) < PAMIĘĆ_M
                ) {
                    return@withContext p.wynik
                }
            }
            try {
                val żądanie = Request.Builder()
                    .url(URL)
                    // Ogólna nazwa klienta, bez danych użytkownika.
                    .header("User-Agent", "VICTOR-glasses-app/1.0")
                    .post(FormBody.Builder().add("data", Przystanki.zapytanie(lat, lon, nazwa)).build())
                    .build()
                client.newCall(żądanie).execute().use { odp ->
                    if (!odp.isSuccessful) {
                        Log.w(TAG, "Overpass odrzucił zapytanie o przystanki: ${odp.code}")
                        return@withContext null
                    }
                    val wynik = Przystanki.rozbierz(odp.body?.string().orEmpty(), lat, lon)
                    pamięć = Pamięć(nazwa, lat, lon, teraz, wynik)
                    wynik
                }
            } catch (e: Exception) {
                Log.w(TAG, "Overpass nie odpowiedział (przystanki)", e)
                null
            }
        }

    private const val PAMIĘĆ_MS = 5 * 60_000L
    private const val PAMIĘĆ_M = 100
}
