package pl.victor.app.proactive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Pobieranie miejsc z serwera Overpass (OpenStreetMap) - patrz [OsmPlaces].
 *
 * Pamięta ostatnią odpowiedź: kolejne pytanie z tego samego miejsca ("a która
 * z nich jest otwarta?") nie pyta serwera drugi raz. Overpass jest darmowy i
 * prosi o umiar - to też jest powód.
 */
object OsmPlacesClient {

    private const val TAG = "OsmPlaces"
    private const val URL = "https://overpass-api.de/api/interpreter"

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private data class Pamięć(val zapytanie: String, val lat: Double, val lon: Double, val czasMs: Long, val miejsca: List<OsmPlaces.Miejsce>)

    @Volatile
    private var pamięć: Pamięć? = null

    /** Opis dla modelu albo `null`, gdy serwer nie odpowiedział. */
    suspend fun kontekst(pytanie: String, lat: Double, lon: Double): String? {
        val miejsca = miejsca(pytanie, lat, lon) ?: return null
        return OsmPlaces.opisDlaModelu(miejsca, pytanie)
    }

    suspend fun miejsca(pytanie: String, lat: Double, lon: Double): List<OsmPlaces.Miejsce>? =
        withContext(Dispatchers.IO) {
            val zapytanie = OsmPlaces.zapytanie(lat, lon, pytanie)
            val teraz = System.currentTimeMillis()
            // Klucz pamięci bez współrzędnych - te porównujemy odległością.
            val rodzajZapytania = zapytanie.substringAfter(";(").replace(Regex("""\(around:[^)]*\)"""), "")
            pamięć?.let { p ->
                if (p.zapytanie == rodzajZapytania && teraz - p.czasMs < PAMIĘĆ_MS &&
                    OsmPlaces.odległość(p.lat, p.lon, lat, lon) < PAMIĘĆ_M
                ) {
                    return@withContext p.miejsca
                }
            }
            try {
                val żądanie = Request.Builder()
                    .url(URL)
                    // Ogólna nazwa klienta, bez danych użytkownika - Overpass prosi
                    // o przedstawianie się, nie o to, kto pyta.
                    .header("User-Agent", "VICTOR-glasses-app/1.0")
                    .post(FormBody.Builder().add("data", zapytanie).build())
                    .build()
                client.newCall(żądanie).execute().use { odp ->
                    if (!odp.isSuccessful) {
                        Log.w(TAG, "Overpass odrzucił zapytanie: ${odp.code}")
                        return@withContext null
                    }
                    val miejsca = OsmPlaces.rozbierz(odp.body?.string().orEmpty(), lat, lon)
                    pamięć = Pamięć(rodzajZapytania, lat, lon, teraz, miejsca)
                    miejsca
                }
            } catch (e: Exception) {
                Log.w(TAG, "Overpass nie odpowiedział", e)
                null
            }
        }

    private const val PAMIĘĆ_MS = 10 * 60_000L
    private const val PAMIĘĆ_M = 150
}
