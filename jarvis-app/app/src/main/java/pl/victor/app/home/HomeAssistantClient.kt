package pl.victor.app.home

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Połączenie z serwerem Home Assistant użytkownika - patrz [DomPolecenia].
 *
 * ## Token
 * Idzie WYŁĄCZNIE w nagłówku `Authorization` do serwera, który wpisał
 * użytkownik. Nie trafia do dziennika (logujemy kod HTTP i czas, nie
 * nagłówki; OkHttp nie ma tu przechwytywacza logów) ani do modelu.
 *
 * ## Limit czasu
 * 5 sekund na całe wywołanie. Polecenie głosowe, na które dom nie odpowiada
 * dłużej, i tak jest dla człowieka porażką - lepiej szybko powiedzieć, że
 * serwera nie widać, niż kazać stać w ciszy.
 */
class HomeAssistantClient(
    private val adres: () -> String?,
    private val token: () -> String?
) {

    data class Wynik(
        val kodHttp: Int?,
        val błąd: HaDecyzja.Błąd?,
        val ciało: String?,
        val ms: Long,
        /** Sam serwer (schemat i host) - bezpieczny do dziennika i komunikatu. */
        val host: String
    )

    /** Ostatnia rozmowa HA - dopowiedzenie ("a w kuchni?") trafia w ten sam wątek. */
    @Volatile
    private var rozmowa: Pair<String, Long>? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(LIMIT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(LIMIT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(LIMIT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** Czy jest adres (poprawny) i token - bez tego nie ma po co dzwonić. */
    fun skonfigurowany(): Boolean =
        HaAdres.sprawdź(adres().orEmpty()) is HaAdres.Wynik.Dobry && !token().isNullOrBlank()

    /** Polecenie po polsku przez conversation API. */
    suspend fun polecenie(tekst: String): Pair<Wynik, HaJson.Odpowiedź?> {
        val teraz = System.currentTimeMillis()
        val wątek = rozmowa?.takeIf { teraz - it.second < WĄTEK_MS }?.first
        val w = wyślij("/api/conversation/process", HaJson.żądanie(tekst, "pl", wątek))
        val odp = w.ciało?.takeIf { w.kodHttp in 200..299 }?.let { HaJson.rozbierz(it) }
        odp?.rozmowa?.let { rozmowa = it to System.currentTimeMillis() }
        return w to odp
    }

    /** "Sprawdź połączenie" w ustawieniach - zdanie do pokazania. */
    suspend fun sprawdźPołączenie(): String {
        val a = HaAdres.sprawdź(adres().orEmpty())
        if (a is HaAdres.Wynik.Zły) return a.powód
        if (token().isNullOrBlank()) return "Wklej token długoterminowy (profil w Home Assistant → Bezpieczeństwo)."
        val w = wyślij("/api/", null)
        return when {
            w.kodHttp == null && w.błąd == HaDecyzja.Błąd.CZAS -> "Serwer nie odpowiedział w 5 sekund."
            w.kodHttp == null -> "Nie łączę się z ${w.host}. Telefon jest w sieci domowej?"
            w.kodHttp == 401 || w.kodHttp == 403 -> "Serwer odrzucił token (${w.kodHttp}). Utwórz nowy token."
            w.kodHttp != 200 -> "Serwer odpowiedział kodem ${w.kodHttp} - to chyba nie Home Assistant."
            !HaJson.apiDziała(w.ciało.orEmpty()) -> "Serwer odpowiada, ale nie jak API Home Assistant."
            else -> "Połączono z Home Assistant (${w.ms} ms)."
        }
    }

    /** Nazwy obszarów (pokoi) z HA albo `null`, gdy się nie udało. */
    suspend fun obszary(): List<String>? {
        val w = wyślij("/api/template", HaJson.żądanieObszarów())
        if (w.kodHttp != 200) return null
        return HaJson.obszary(w.ciało.orEmpty())
    }

    private suspend fun wyślij(ścieżka: String, json: String?): Wynik = withContext(Dispatchers.IO) {
        val od = System.currentTimeMillis()
        val a = HaAdres.sprawdź(adres().orEmpty())
        val baza = (a as? HaAdres.Wynik.Dobry)?.baza
            ?: return@withContext Wynik(null, HaDecyzja.Błąd.INNY, null, 0, "?")
        val t = token().orEmpty()
        try {
            val b = Request.Builder()
                .url(baza + ścieżka)
                .header("Authorization", "Bearer $t")
            if (json != null) b.post(json.toRequestBody(JSON)) else b.get()
            client.newCall(b.build()).execute().use { odp ->
                Wynik(odp.code, null, odp.body?.string(), System.currentTimeMillis() - od, baza)
            }
        } catch (e: java.io.InterruptedIOException) {
            // Przekroczony limit czasu (SocketTimeout dziedziczy po tym typie).
            Log.w(TAG, "Home Assistant: limit czasu ($ścieżka)")
            Wynik(null, HaDecyzja.Błąd.CZAS, null, System.currentTimeMillis() - od, baza)
        } catch (e: Exception) {
            // Tylko klasa wyjątku - komunikat bywa z adresem, nigdy z tokenem,
            // ale nie ma potrzeby go nosić dalej.
            Log.w(TAG, "Home Assistant: brak połączenia ($ścieżka): ${e::class.java.simpleName}")
            Wynik(null, HaDecyzja.Błąd.BRAK_SIECI, null, System.currentTimeMillis() - od, baza)
        }
    }

    companion object {
        private const val TAG = "HomeAssistant"
        const val LIMIT_MS = 5_000L
        private const val WĄTEK_MS = 5 * 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
