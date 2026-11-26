package pl.victor.app.hermes

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * patrz [HermesProtocol] i [HermesRuns].
 *
 * ## Dwie drogi
 * 1. **Zadania** (`POST /v1/runs` + `GET /v1/runs/{id}/events`) - droga
 *    główna. Tylko tu da się odpowiedzieć na prośbę o zgodę, a przerwanie
 *    tury naprawdę zatrzymuje zadanie (`/v1/runs/{id}/stop`).
 * 2. **Rozmowa** (`POST /v1/chat/completions`) - zapas, gdy brama nie zna
 *    zadań (404/405/501). Bez zgód i bez zatrzymywania.
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
        val zgód: Int,
        /** "zadanie" (/v1/runs) albo "rozmowa" (/v1/chat/completions). */
        val droga: String = "zadanie",
        val runId: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Klient do krótkich wywołań (start, zgoda, stop) - z limitem na całość. */
    private val krótki = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    val historia = HermesHistoria()

    fun skonfigurowany(): Boolean =
        HermesAdres.sprawdź(adres().orEmpty()) is HermesAdres.Wynik.Dobry && !klucz().isNullOrBlank()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Zbiera wynik niezależnie od drogi (zadanie / rozmowa). */
    private inner class Zbiór(
        val od: Long,
        val naRamce: suspend (HermesRamka) -> Unit
    ) {
        val tekst = StringBuilder()
        var pierwszy: Long? = null
        var narzędzi = 0
        var nieznanych = 0
        var zgód = 0
        var błąd: HermesBłąd? = null
        var szczegół: String? = null
        var wynikKońcowy: String? = null
        var zakończone = false
        var runId: String? = null

        /** @return `true`, gdy to koniec strumienia */
        suspend fun przyjmij(r0: HermesRamka): Boolean {
            val r = if (r0 is HermesRamka.Zgoda && runId != null) r0.copy(runId = runId) else r0
            when (r) {
                is HermesRamka.Tekst -> if (r.kawałek.isNotEmpty()) {
                    if (pierwszy == null) pierwszy = System.currentTimeMillis() - od
                    tekst.append(r.kawałek)
                }
                is HermesRamka.Narzędzie -> if (!r.zakończone) narzędzi++
                is HermesRamka.Zgoda -> zgód++
                is HermesRamka.Nieznana -> nieznanych++
                is HermesRamka.Błąd -> {
                    błąd = HermesBłąd.SERWER
                    szczegół = r.komunikat
                    zakończone = true
                }
                is HermesRamka.Koniec -> {
                    wynikKońcowy = r.wynik
                    if (r.powód == "cancelled" || r.powód == "interrupted") {
                        błąd = HermesBłąd.ZERWANE
                        szczegół = r.powód
                    }
                    zakończone = true
                }
                HermesRamka.Gotowe -> zakończone = true
            }
            naRamce(r)
            return zakończone
        }
    }

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
        val baza = a.baza
        val k = klucz().orEmpty()
        val hist = historia.wiadomości(od)
        val zb = Zbiór(od, naRamce)
        var droga = "zadanie"
        var kod: Int? = null

        try {
            val start = wystartuj(baza, k, tekst, hist)
            when (start) {
                is Start.Zadanie -> {
                    zb.runId = start.runId
                    kod = czytajZdarzenia(baza, k, start.runId, zb)
                    if (zb.błąd == null && zb.tekst.isBlank()) {
                        // Tekst mógł przyjść tylko w ostatnim zdarzeniu albo wcale -
                        // ostateczne źródło to `output` zadania.
                        zb.wynikKońcowy?.takeIf { it.isNotBlank() }?.let { zb.przyjmij(HermesRamka.Tekst(it)) }
                    }
                    if (zb.błąd == null && zb.tekst.isBlank()) {
                        pobierzRun(baza, k, start.runId).second?.takeIf { it.isNotBlank() }
                            ?.let { zb.przyjmij(HermesRamka.Tekst(it)) }
                    }
                }
                is Start.Rozmowa -> {
                    droga = "rozmowa"
                    kod = rozmowa(baza, k, tekst, hist, zb)
                }
                is Start.Błąd -> {
                    kod = start.kod
                    zb.błąd = start.błąd
                    zb.szczegół = start.szczegół
                }
            }
        } catch (e: java.io.InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Hermes: limit ciszy na łączu")
            zb.błąd = if (zb.tekst.isEmpty()) HermesBłąd.CZAS else HermesBłąd.ZERWANE
            zb.szczegół = e::class.java.simpleName
        } catch (e: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            // Tylko klasa wyjątku - komunikat bywa z adresem, nigdy z kluczem.
            Log.w(TAG, "Hermes: ${e::class.java.simpleName}")
            zb.błąd = if (zb.tekst.isEmpty()) HermesBłąd.BRAK_SIECI else HermesBłąd.ZERWANE
            zb.szczegół = e::class.java.simpleName
        } finally {
            // Tura przerwana (albo połączenie padło), a zadanie jeszcze trwa:
            // zatrzymujemy je u Hermesa - inaczej agent kończyłby w tle to, czego
            // użytkownik kazał zaniechać ("przerwij", dotknięcie zausznika).
            val id = zb.runId
            if (id != null && !zb.zakończone) {
                withContext(NonCancellable) { zatrzymaj(baza, k, id) }
            }
        }

        val ms = System.currentTimeMillis() - od
        if (zb.błąd == null && zb.tekst.isBlank()) zb.błąd = HermesBłąd.PUSTA
        if (zb.błąd == null) historia.dodaj(tekst, zb.tekst.toString().trim(), System.currentTimeMillis())
        Wynik(
            zb.tekst.toString().trim(), zb.błąd, kod, zb.szczegół, ms, zb.pierwszy,
            zb.narzędzi, zb.nieznanych, zb.zgód, droga, zb.runId
        )
    }

    private sealed class Start {
        data class Zadanie(val runId: String) : Start()
        object Rozmowa : Start()
        data class Błąd(val błąd: HermesBłąd, val kod: Int?, val szczegół: String?) : Start()
    }

    /** `POST /v1/runs`; przy 404/405/501 - zapas na rozmowę, przy 400/422 z historią - bez historii. */
    private suspend fun wystartuj(
        baza: String, k: String, tekst: String, hist: List<HermesProtocol.Wiadomość>
    ): Start {
        var historiaDoWysłania = hist
        repeat(2) {
            val b = Request.Builder().url("$baza/v1/runs")
                .post(HermesRuns.żądanieStartu(tekst, historiaDoWysłania).toRequestBody(JSON))
            HermesProtocol.nagłówki(k, kluczSesji()).forEach { (n, w) -> b.header(n, w) }
            b.header("Accept", "application/json")
            var kod = 0
            var ciało: String? = null
            krótki.newCall(b.build()).executeCancellable { odp ->
                kod = odp.code
                ciało = odp.body?.string()
            }
            when {
                kod in 200..299 -> {
                    val id = HermesRuns.runId(ciało)
                    return if (id != null) Start.Zadanie(id)
                    else Start.Błąd(HermesBłąd.SERWER, kod, "brak run_id w odpowiedzi")
                }
                kod == 404 || kod == 405 || kod == 501 -> return Start.Rozmowa
                (kod == 400 || kod == 422) && historiaDoWysłania.isNotEmpty() -> historiaDoWysłania = emptyList()
                else -> return Start.Błąd(
                    HermesBłąd.zKoduHttp(kod), kod,
                    HermesProtocol.komunikatBłędu(ciało) ?: HermesProtocol.początek(ciało.orEmpty(), 120)
                )
            }
        }
        return Start.Błąd(HermesBłąd.SERWER, 400, "serwer odrzucił zadanie")
    }

    /** Strumień zdarzeń zadania; @return kod HTTP strumienia. */
    private suspend fun czytajZdarzenia(baza: String, k: String, runId: String, zb: Zbiór): Int {
        val b = Request.Builder().url("$baza/v1/runs/$runId/events").get()
        HermesProtocol.nagłówki(k, kluczSesji()).forEach { (n, w) -> b.header(n, w) }
        var kod = 0
        client.newCall(b.build()).executeCancellable { odp ->
            kod = odp.code
            if (!odp.isSuccessful) {
                zb.błąd = HermesBłąd.zKoduHttp(odp.code)
                zb.szczegół = HermesProtocol.komunikatBłędu(odp.body?.string())
                zb.zakończone = true
                return@executeCancellable
            }
            val źródło = odp.body?.source() ?: return@executeCancellable
            val parser = SseParser()
            while (true) {
                val l = źródło.readUtf8Line() ?: break
                val z = parser.linia(l) ?: continue
                if (zb.przyjmij(HermesRuns.interpretuj(z))) return@executeCancellable
            }
            parser.reszta()?.let { z -> zb.przyjmij(HermesRuns.interpretuj(z)) }
        }
        if (!zb.zakończone) {
            // Strumień urwał się bez zdarzenia końcowego - sprawdzamy, czy zadanie się skończyło.
            val (status, output) = pobierzRun(baza, k, runId)
            when (status?.lowercase()) {
                "completed" -> {
                    zb.zakończone = true
                    if (zb.tekst.isBlank() && !output.isNullOrBlank()) zb.przyjmij(HermesRamka.Tekst(output))
                }
                "failed", "cancelled", "interrupted" -> {
                    zb.zakończone = true
                    zb.błąd = HermesBłąd.ZERWANE
                    zb.szczegół = status
                }
                else -> {
                    zb.błąd = HermesBłąd.ZERWANE
                    zb.szczegół = "strumień urwał się (status ${status ?: "nieznany"})"
                }
            }
        }
        return kod
    }

    /** `GET /v1/runs/{id}` - (status, output). */
    private suspend fun pobierzRun(baza: String, k: String, runId: String): Pair<String?, String?> {
        val b = Request.Builder().url("$baza/v1/runs/$runId").get()
        HermesProtocol.nagłówki(k, kluczSesji()).forEach { (n, w) -> b.header(n, w) }
        b.header("Accept", "application/json")
        return try {
            var ciało: String? = null
            krótki.newCall(b.build()).executeCancellable { odp -> ciało = odp.body?.string()?.takeIf { odp.isSuccessful } }
            HermesRuns.stanRuna(ciało)
        } catch (e: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            null to null
        }
    }

    /** Zapas: zwykła rozmowa `chat/completions` (bez zgód i zatrzymywania). */
    private suspend fun rozmowa(
        baza: String, k: String, tekst: String, hist: List<HermesProtocol.Wiadomość>, zb: Zbiór
    ): Int {
        val b = Request.Builder().url("$baza/v1/chat/completions")
            .post(HermesProtocol.żądanie(tekst, hist).toRequestBody(JSON))
        HermesProtocol.nagłówki(k, kluczSesji()).forEach { (n, w) -> b.header(n, w) }
        var kod = 0
        client.newCall(b.build()).executeCancellable { odp ->
            kod = odp.code
            if (!odp.isSuccessful) {
                val treść = odp.body?.string()
                zb.błąd = HermesBłąd.zKoduHttp(odp.code)
                zb.szczegół = HermesProtocol.komunikatBłędu(treść) ?: HermesProtocol.początek(treść.orEmpty(), 120)
                return@executeCancellable
            }
            val typ = odp.header("Content-Type").orEmpty().lowercase()
            val źródło = odp.body?.source() ?: return@executeCancellable
            if (!typ.contains("event-stream")) {
                // Serwer zignorował strumień - cała odpowiedź w jednym JSON-ie.
                val całość = źródło.readUtf8()
                val t = HermesProtocol.odpowiedźBezStrumienia(całość)
                if (!t.isNullOrEmpty()) {
                    zb.przyjmij(HermesRamka.Tekst(t))
                } else {
                    zb.szczegół = "odpowiedź bez strumienia: " + HermesProtocol.początek(całość, 120)
                }
                return@executeCancellable
            }
            val parser = SseParser()
            while (true) {
                val l = źródło.readUtf8Line() ?: break
                val z = parser.linia(l) ?: continue
                if (zb.przyjmij(HermesProtocol.interpretuj(z))) return@executeCancellable
            }
            parser.reszta()?.let { z -> zb.przyjmij(HermesProtocol.interpretuj(z)) }
        }
        return kod
    }

    /**
     * Odpowiedź użytkownika na prośbę Hermesa o zgodę.
     *
     * Gdy w ramce brakło identyfikatora zgody (format nieznany), bierzemy go
     * z `GET /v1/runs/{id}` (`pending_controls`).
     */
    suspend fun odpowiedzNaZgodę(z: HermesRamka.Zgoda, zezwól: Boolean): Pair<WynikZgody, Int?> =
        withContext(Dispatchers.IO + NonCancellable) {
            val a = HermesAdres.sprawdź(adres().orEmpty()) as? HermesAdres.Wynik.Dobry
                ?: return@withContext WynikZgody.SIEĆ to null
            val k = klucz().orEmpty()
            val runId = z.runId ?: return@withContext WynikZgody.BRAK_ZADANIA to null
            try {
                var rid = z.requestId
                var gen = z.generacja
                if (rid == null) {
                    val b = Request.Builder().url("${a.baza}/v1/runs/$runId").get()
                    HermesProtocol.nagłówki(k, kluczSesji()).forEach { (n, w) -> b.header(n, w) }
                    var ciało: String? = null
                    krótki.newCall(b.build()).execute().use { odp -> ciało = odp.body?.string() }
                    HermesRuns.oczekująceZgody(ciało).firstOrNull { it.requestId != null }?.let {
                        rid = it.requestId
                        gen = it.generacja ?: gen
                    }
                }
                val identyfikator = rid ?: return@withContext WynikZgody.INNE to null
                val b = Request.Builder().url("${a.baza}/v1/runs/$runId/approval")
                    .post(HermesRuns.żądanieZgody(identyfikator, gen, zezwól).toRequestBody(JSON))
                HermesProtocol.nagłówki(k, kluczSesji()).forEach { (n, w) -> b.header(n, w) }
                var kod: Int? = null
                krótki.newCall(b.build()).execute().use { odp -> kod = odp.code }
                WynikZgody.zKoduHttp(kod) to kod
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Hermes: zgoda - ${e::class.java.simpleName}")
                WynikZgody.SIEĆ to null
            }
        }

    /** `POST /v1/runs/{id}/stop` - najlepsza próba, bez wyjątków. */
    private fun zatrzymaj(baza: String, k: String, runId: String) {
        try {
            val b = Request.Builder().url("$baza/v1/runs/$runId/stop")
                .post("{}".toRequestBody(JSON))
            HermesProtocol.nagłówki(k, kluczSesji()).forEach { (n, w) -> b.header(n, w) }
            krótki.newBuilder().callTimeout(4, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { }
        } catch (e: Exception) {
            Log.w(TAG, "Hermes: nie zatrzymałem zadania (${e::class.java.simpleName})")
        }
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
            200 -> {
                // Zadania ze zgodami wymagają /v1/runs - brama podaje to w /v1/capabilities.
                val (zdolności, ciało) = pobierz("/v1/capabilities", true)
                val zgody = zdolności == 200 && ciało.orEmpty().contains("run_approval")
                val zadania = zdolności == 200 && ciało.orEmpty().contains("runs")
                "Połączono z Hermesem (${System.currentTimeMillis() - od} ms)." + when {
                    zgody -> " Zadania i zgody głosem dostępne."
                    zadania -> " Zadania dostępne (zgód nie widać w zdolnościach bramy)."
                    zdolności == 200 -> " Uwaga: brama nie zgłasza zadań - zgody głosem mogą nie działać."
                    else -> ""
                }
            }
            401, 403 -> "Brama odpowiada, ale odrzuciła klucz ($modele). Sprawdź API_SERVER_KEY."
            null -> "Brama przestała odpowiadać w trakcie sprawdzania."
            else -> "Brama odpowiada (kod $modele na /v1/models) - klucz mógł zostać przyjęty, spróbuj zapytania."
        }
    }

    companion object {
        private const val TAG = "HermesClient"
    }
}
