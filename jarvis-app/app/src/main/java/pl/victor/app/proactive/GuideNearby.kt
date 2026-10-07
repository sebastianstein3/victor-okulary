package pl.victor.app.proactive

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Tryb przewodnika: co ciekawego jest tuż obok - z Wikipedii, po położeniu.
 *
 * ## Czemu Wikipedia, a nie model
 * Bo przewodnik mówi SAM, w trakcie spaceru, kilkanaście razy na godzinę.
 * Model za każdym razem to koszt i dwie, trzy sekundy; Wikipedia ma darmowe
 * wyszukiwanie po współrzędnych (`list=geosearch`) i gotowe streszczenia
 * artykułów. Do modelu idzie się dopiero wtedy, gdy człowiek zapyta o więcej -
 * a wtedy położenie i tak jest w kontekście (patrz [LocationContext.Cel]).
 *
 * Logika wyboru ("czy już o tym mówiłem", "czy się ruszyłem", "jak przyciąć
 * opis") jest w [GuideLogic] - bez sieci, sprawdzana testem.
 */
class GuideNearby(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
) {
    /** Artykuły w promieniu [promieńM] - najbliższe pierwsze. */
    suspend fun wPobliżu(lat: Double, lon: Double, promieńM: Int = GuideLogic.PROMIEŃ_M): List<GuideLogic.Miejsce> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://pl.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                    .addQueryParameter("action", "query")
                    .addQueryParameter("list", "geosearch")
                    .addQueryParameter("gscoord", "$lat|$lon")
                    .addQueryParameter("gsradius", promieńM.toString())
                    .addQueryParameter("gslimit", "15")
                    .addQueryParameter("format", "json")
                    .build()
                pobierz(url.toString())?.let { GuideLogic.parsujGeosearch(it) }
            }.onFailure { Log.w(TAG, "Wyszukiwanie w okolicy nie powiodło się", it) }
                .getOrNull().orEmpty()
        }

    /** Pierwsze zdania artykułu albo `null`. */
    suspend fun streszczenie(tytuł: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://pl.wikipedia.org/api/rest_v1/page/summary/".toHttpUrl().newBuilder()
                .addPathSegment(tytuł.replace(' ', '_'))
                .build()
            pobierz(url.toString())?.let { json ->
                JsonParser.parseString(json).asJsonObject.get("extract")?.asString
            }?.let { GuideLogic.przytnij(it) }
        }.onFailure { Log.w(TAG, "Streszczenie \"$tytuł\" nie przyszło", it) }.getOrNull()
    }

    private fun pobierz(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    companion object {
        private const val TAG = "GuideNearby"
        private const val TIMEOUT_MS = 6_000L

        /** Wikimedia wymaga opisowego User-Agenta - bez niego potrafi odciąć. */
        private const val USER_AGENT = "VICTOR-glasses-assistant/0.1 (Android)"
    }
}

/** Decyzje przewodnika - czysta logika, bez sieci i bez Androida. */
object GuideLogic {

    data class Miejsce(val tytuł: String, val odległośćM: Int)

    /** Jak daleko szukać - w zasięgu wzroku na ulicy. */
    const val PROMIEŃ_M = 350

    /** O ile trzeba się przesunąć, żeby szukać na nowo. */
    const val PRZESUNIĘCIE_M = 120.0

    /** Ile zdań opisu - dłużej nikt nie słucha w marszu. */
    private const val ZDAŃ = 2
    private const val MAKS_ZNAKÓW = 320

    fun parsujGeosearch(json: String): List<Miejsce> {
        val root = JsonParser.parseString(json).asJsonObject
        val lista = root.getAsJsonObject("query")?.getAsJsonArray("geosearch") ?: return emptyList()
        return lista.mapNotNull { e ->
            val o = e.asJsonObject
            val t = o.get("title")?.asString ?: return@mapNotNull null
            val d = o.get("dist")?.asDouble ?: return@mapNotNull null
            Miejsce(t, d.toInt())
        }.filterNot { nieCiekawe(it.tytuł) }.sortedBy { it.odległośćM }
    }

    /**
     * Pierwsze miejsce, o którym jeszcze nie mówiliśmy.
     */
    fun następne(miejsca: List<Miejsce>, powiedziane: Set<String>): Miejsce? =
        miejsca.firstOrNull { it.tytuł !in powiedziane }

    /** Pierwsze [ZDAŃ] zdania, nie dłużej niż [MAKS_ZNAKÓW], bez nawiasów z wymową. */
    fun przytnij(tekst: String): String {
        val bezNawiasów = tekst.replace(Regex("""\s*\([^)]*\)"""), "").replace(Regex("""\s+"""), " ").trim()
        val zdania = Regex("""(?<=[.!?])\s+(?=[A-ZĄĆĘŁŃÓŚŹŻ])""").split(bezNawiasów)
        var wynik = zdania.take(ZDAŃ).joinToString(" ")
        if (wynik.length > MAKS_ZNAKÓW) {
            wynik = wynik.take(MAKS_ZNAKÓW).substringBeforeLast(' ') + "…"
        }
        return wynik
    }

    /** Odległość w metrach (wzór haversine) - do decyzji "czy się ruszyłem". */
    fun odległośćM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    /** Zdanie do powiedzenia na głos. */
    fun zapowiedź(m: Miejsce, opis: String?): String {
        val gdzie = when {
            m.odległośćM < 40 -> "Jesteś przy: ${m.tytuł}."
            else -> "Około ${zaokrąglij(m.odległośćM)} metrów stąd: ${m.tytuł}."
        }
        return if (opis.isNullOrBlank()) gdzie else "$gdzie $opis"
    }

    private fun zaokrąglij(m: Int): Int = ((m + 25) / 50) * 50

    /**
     * Artykuły, które są o OBSZARZE, a nie o czymś do zobaczenia - "Toruń",
     * "Stare Miasto w Toruniu" padałyby przy każdym kroku.
     */
    private fun nieCiekawe(tytuł: String): Boolean {
        val t = tytuł.lowercase()
        return NIECIEKAWE.any { t.startsWith(it) }
    }

    private val NIECIEKAWE = listOf("gmina ", "powiat ", "województwo ", "osiedle ", "przystanek ")
}
