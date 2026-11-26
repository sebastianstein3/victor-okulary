package pl.victor.app.hermes

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.victor.app.ai.executeCancellable
import java.util.concurrent.TimeUnit

/**
 * Połączenie z bramą Hermesa (`hermes gateway`) na komputerze użytkownika -
 * patrz [HermesProtocol].
 *
 * ## Klucz
 * Idzie WYŁĄCZNIE w nagłówku `Authorization` do adresu, który wpisał
 * użytkownik. Nie trafia do dziennika (logujemy kod HTTP i czasy; klient nie
 * ma przechwytywacza logów) ani do modelu.
 *
 * ## Czasy
 * Zadanie agenta bywa długie, więc nie ma limitu na całe wywołanie. Pilnuje
 * go limit ciszy na łączu: Hermes wysyła znak życia co ok. 10 s, więc 60 s bez
 * niczego znaczy, że połączenie umarło. Przerwana tura zamyka połączenie od
 * razu ([executeCancellable]).
 */
class HermesClient(
    private val adres: () -> String?,
    private val klucz: () -> String?,
    private val kluczSesji: () -> String?
) {

    /** Wynik zapytania - [tekst] to cała odpowiedź zebrana z kawałków. */
    data class Wynik(
        val tekst: String,
        val błąd: HermesBłąd?,
        /** Kod HTTP albo `null`, gdy połączenie się nie udało. */
        val kodHttp: Int?,
        /** Treść błędu z serwera (krótka) albo klasa wyjątku. */
        val szczegół: String?,
        val ms: Long,
        /** Po ilu ms przyszedł pierwszy kawałek tekstu. */
        val pierwszyTekstMs: Long?,
        val narzędzi: Int,
        val nieznanychRamek: Int,
        val zgód: Int
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val historia = HermesHistoria()

    fun skonfigurowany(): Boolean =
        HermesAdres.sprawdź(adres().orEmpty()) is HermesAdres.Wynik.Dobry && !klucz().isNullOrBlank()

    /**
     * Wysyła [tekst] i woła [naRamce] dla każdej ramki ze strumienia (na
     * wątku IO). Wynik zawiera pełny tekst odpowiedzi.
     */
    suspend fun zapytaj(
        tekst: String,
        naRamce: suspend (HermesRamka) -> Unit
    ): Wynik = withContext(Dispatchers.IO) {
        val od = System.currentTimeMillis()
        val a = HermesAdres.sprawdź(adres().orEmpty()) as? HermesAdres.Wynik.Dobry
            ?: return@withContext bezPołączenia("zły adres", od)
        val k = klucz().orEmpty()
        val ciało = HermesProtocol.żądanie(tekst, historia.wiadomości(od))
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val b = Request.Builder().url(a.baza + "/v1/chat/completions").post(ciało)
        HermesProtocol.nagłówki(k, kluczSesji()).forEach { (n, w) -> b.header(n, w) }

        val zebrane = StringBuilder()
        var pierwszy: Long? = null
        var narzędzi = 0
        var nieznanych = 0
        var zgód = 0
        var kod: Int? = null
        var błąd: HermesBłąd? = null
        var szczegół: String? = null

        suspend fun obsłuż(r: HermesRamka): Boolean {
            when (r) {
                is HermesRamka.Tekst -> if (r.kawałek.isNotEmpty()) {
                    if (pierwszy == null) pierwszy = System.currentTimeMillis() - od
                    zebrane.append(r.kawałek)
                }
                is HermesRamka.Narzędzie -> narzędzi++
                is HermesRamka.Zgoda -> zgód++
                is HermesRamka.Nieznana -> nieznanych++
                is HermesRamka.Błąd -> {
                    błąd = HermesBłąd.SERWER
                    szczegół = r.komunikat
                }
                is HermesRamka.Koniec, HermesRamka.Gotowe -> Unit
            }
            naRamce(r)
            return r is HermesRamka.Gotowe
        }

        try {
            client.newCall(b.build()).executeCancellable { odp ->
                kod = odp.code
                if (!odp.isSuccessful) {
                    val treść = odp.body?.string()
                    błąd = HermesBłąd.zKoduHttp(odp.code)
                    szczegół = HermesProtocol.komunikatBłędu(treść) ?: HermesProtocol.początek(treść.orEmpty(), 120)
                    return@executeCancellable
                }
                val typ = odp.header("Content-Type").orEmpty().lowercase()
                val źródło = odp.body?.source() ?: return@executeCancellable
                if (!typ.contains("event-stream")) {
                    // Serwer zignorował strumień - cała odpowiedź w jednym JSON-ie.
                    val całość = źródło.readUtf8()
                    val t = HermesProtocol.odpowiedźBezStrumienia(całość)
                    if (!t.isNullOrEmpty()) {
                        obsłuż(HermesRamka.Tekst(t))
                    } else {
                        szczegół = "odpowiedź bez strumienia: " + HermesProtocol.początek(całość, 120)
                    }
                    return@executeCancellable
                }
                val parser = SseParser()
                while (true) {
                    val l = źródło.readUtf8Line() ?: break
                    val z = parser.linia(l) ?: continue
                    val koniec = obsłuż(HermesProtocol.interpretuj(z))
                    if (koniec) return@executeCancellable
                }
                parser.reszta()?.let { z -> obsłuż(HermesProtocol.interpretuj(z)) }
            }
        } catch (e: java.io.InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Hermes: limit ciszy na łączu")
            błąd = if (zebrane.isEmpty()) HermesBłąd.CZAS else HermesBłąd.ZERWANE
            szczegół = e::class.java.simpleName
        } catch (e: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            // Tylko klasa wyjątku - komunikat bywa z adresem, nigdy z kluczem.
            Log.w(TAG, "Hermes: ${e::class.java.simpleName}")
            błąd = if (zebrane.isEmpty()) HermesBłąd.BRAK_SIECI else HermesBłąd.ZERWANE
            szczegół = e::class.java.simpleName
        }

        val ms = System.currentTimeMillis() - od
        if (błąd == null && zebrane.isBlank()) błąd = HermesBłąd.PUSTA
        if (błąd == null) historia.dodaj(tekst, zebrane.toString().trim(), System.currentTimeMillis())
        Wynik(zebrane.toString().trim(), błąd, kod, szczegół, ms, pierwszy, narzędzi, nieznanych, zgód)
    }

    private fun bezPołączenia(powód: String, od: Long) = Wynik(
        "", HermesBłąd.BRAK_SIECI, null, powód, System.currentTimeMillis() - od, null, 0, 0, 0
    )

    /** "Sprawdź połączenie" w ustawieniach - zdanie do pokazania. */
    suspend fun sprawdźPołączenie(): String = withContext(Dispatchers.IO) {
        val a = HermesAdres.sprawdź(adres().orEmpty())
        if (a is HermesAdres.Wynik.Zły) return@withContext a.powód
        val baza = (a as HermesAdres.Wynik.Dobry).baza
        val k = klucz().orEmpty()
        if (k.isBlank()) return@withContext "Wklej klucz API (API_SERVER_KEY z pliku ~/.hermes/.env na komputerze)."
        val od = System.currentTimeMillis()
        fun pobierz(ścieżka: String, zKluczem: Boolean): Pair<Int?, String?> = try {
            val b = Request.Builder().url(baza + ścieżka).get()
            if (zKluczem) b.header("Authorization", "Bearer ${k.filterNot { it.isWhitespace() }}")
            client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { odp ->
                odp.code to odp.body?.string()
            }
        } catch (e: java.io.InterruptedIOException) {
            null to "czas"
        } catch (e: java.io.IOException) {
            null to e::class.java.simpleName
        }
        val (zdrowie, _) = pobierz("/health", false)
        if (zdrowie == null) {
            return@withContext "Nie łączę się z $baza. Hermes działa (hermes gateway) i telefon jest w tej samej sieci albo w Tailscale?"
        }
        if (zdrowie != 200) {
            return@withContext "Serwer odpowiedział kodem $zdrowie na /health - to chyba nie brama Hermesa."
        }
        // /health bywa otwarte bez klucza - klucz sprawdzamy na punkcie, który go wymaga.
        val (modele, _) = pobierz("/v1/models", true)
        when (modele) {
            200 -> "Połączono z Hermesem (${System.currentTimeMillis() - od} ms)."
            401, 403 -> "Brama odpowiada, ale odrzuciła klucz ($modele). Sprawdź API_SERVER_KEY."
            null -> "Brama przestała odpowiadać w trakcie sprawdzania."
            else -> "Brama odpowiada (kod $modele na /v1/models) - klucz mógł zostać przyjęty, spróbuj zapytania."
        }
    }

    companion object {
        private const val TAG = "HermesClient"
    }
}
