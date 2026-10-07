package pl.victor.app.features.reminders

import com.google.gson.JsonParser
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Przypomnienie związane z miejscem: "przypomnij mi kupić mleko, gdy będę w
 * Biedronce".
 *
 * @param gdzie jak użytkownik nazwał miejsce ("Biedronce", "domu")
 * @param cel do czego je dopasowujemy - patrz [Cel]
 * @param odMs nie wcześniej niż ("jutro po 16:00"); 0 = od razu
 * @param czekaNaWyjście "gdy WRÓCĘ do domu" powiedziane w domu nie może wypaść
 *   za dwie minuty - najpierw trzeba z tego miejsca wyjść
 */
data class PlaceReminder(
    val id: Long,
    val co: String,
    val gdzie: String,
    val cel: Cel,
    val utworzoneMs: Long,
    val odMs: Long = 0L,
    val czekaNaWyjście: Boolean = false
)

/**
 * Trzy rodzaje miejsc - bo każde rozpoznaje się inaczej.
 *
 * - [Zapisane]: dom, praca - współrzędne z pamięci miejsc.
 * - [Nazwa]: sieć albo konkretny lokal ("Biedronka", "Rossmann") - szukany w
 *   OpenStreetMap po nazwie i marce wokół bieżącego położenia.
 * - [Rodzaj]: "apteka", "bankomat" - szukany po rodzaju obiektu w OSM.
 */
sealed class Cel {
    data class Zapisane(val nazwa: String) : Cel()
    data class Nazwa(val tekst: String) : Cel()
    data class Rodzaj(val klucz: String, val wartości: List<String>, val opis: String) : Cel()
}

object PlaceReminderLogic {

    data class Prośba(val co: String, val gdzie: String, val odMs: Long = 0L)

    /**
     * Rozpoznaje prośbę w trzech szykach:
     * "przypomnij mi kupić mleko, gdy będę w Biedronce",
     * "gdy będę w aptece, przypomnij mi o lekach" i
     * "przypomnij mi, gdy wrócę do domu, żebym wyniósł śmieci".
     * Czas ("jutro", "po 16:00") jest wycinany i trafia do [Prośba.odMs].
     */
    fun prośba(tekst: String, teraz: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): Prośba? {
        val surowy = tekst.trim().trimEnd('.', '!').replace(Regex("""\s+"""), " ")
        if (!surowy.lowercase().startsWith("przypomnij") && !GDZIE_NA_POCZĄTKU.containsMatchIn(surowy)) return null
        val (t, odMs) = wytnijCzas(surowy, teraz)
        PRZYPOMNIJ_GDZIE_POTEM_CO.matchEntire(t)?.let { m ->
            return zbuduj(m.groups["co"]!!.value, m.groups["gdzie"]!!.value, odMs)
        }
        PRZYPOMNIJ_POTEM_GDZIE.matchEntire(t)?.let { m ->
            return zbuduj(m.groups["co"]!!.value, m.groups["gdzie"]!!.value, odMs)
        }
        GDZIE_POTEM_PRZYPOMNIJ.matchEntire(t)?.let { m ->
            return zbuduj(m.groups["co"]!!.value, m.groups["gdzie"]!!.value, odMs)
        }
        return null
    }

    /**
     * "jutro", "pojutrze", "dziś" i "po/od/około 16(:30)" - wycina z tekstu i
     * zamienia na chwilę, od której przypomnienie może wypaść.
     */
    fun wytnijCzas(tekst: String, teraz: java.time.ZonedDateTime): Pair<String, Long> {
        var t = tekst
        var dni: Long? = null
        DZIEŃ.find(t)?.let { m ->
            dni = when (m.value.lowercase()) {
                "pojutrze" -> 2L
                "jutro" -> 1L
                else -> 0L
            }
            t = t.removeRange(m.range)
        }
        var godzina: Pair<Int, Int>? = null
        GODZINA.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h in 0..23 && min in 0..59) {
                godzina = h to min
                t = t.removeRange(m.range)
            }
        }
        t = t.replace(Regex("""\s+"""), " ").replace(" ,", ",").trim().trim(',').trim()
        if (dni == null && godzina == null) return t to 0L
        val dzień = teraz.toLocalDate().plusDays(dni ?: 0L)
        val (h, min) = godzina ?: (0 to 0)
        val od = dzień.atTime(h, min).atZone(teraz.zone).toInstant().toEpochMilli()
        return t to od
    }

    private fun zbuduj(co: String, gdzie: String, odMs: Long): Prośba? {
        var c = co.trim().trim(',').trim()
        // "żebym kupił mleko" -> "żebyś kupił mleko": przypomnienie mówi do
        // użytkownika, a forma czasownika zostaje ta sama.
        Regex("""^(?:[zż]ebym|abym)(?!\p{L})""", RegexOption.IGNORE_CASE).find(c)?.let { m ->
            c = (if (m.value.lowercase().startsWith("a")) "abyś" else "żebyś") + c.substring(m.range.last + 1)
        }
        c = c.removePrefix("o ").removePrefix("żeby ").removePrefix("że ").trim()
        val g = gdzie.trim().trim(',').trim()
        if (c.isEmpty() || g.isEmpty()) return null
        return Prośba(c, g, odMs)
    }

    /** Do czego dopasować miejsce z prośby. */
    fun cel(gdzie: String): Cel {
        val g = gdzie.lowercase().removePrefix("moim ").removePrefix("mojej ").removePrefix("mojego ").trim()
        if (g.startsWith("dom")) return Cel.Zapisane(DOM)
        if (g.startsWith("prac") || g.startsWith("biur")) return Cel.Zapisane(PRACA)
        RODZAJE.firstOrNull { (rdzenie, _) -> rdzenie.any { g.startsWith(it) } }?.let { return it.second }
        return Cel.Nazwa(mianownik(gdzie.trim()))
    }

    /**
     * "Biedronce" -> "Biedronk", "Lidlu" -> "Lidl": rdzeń do wyszukiwania,
     * odporny na odmianę. Zgrubny, ale szukamy podciągu w nazwie, więc
     * wystarczy, żeby nie zawierał końcówki przypadku.
     */
    fun mianownik(słowo: String): String {
        val s = słowo.trim()
        val końcówki = listOf("ce", "ze", "ie", "u", "a", "y", "ę", "ą", "i", "e")
        val k = końcówki.firstOrNull { s.length - it.length >= 4 && s.lowercase().endsWith(it) } ?: return s
        return s.dropLast(k.length)
    }

    /**
     * "Tu jest mój dom" / "tu pracuję" - zapis domu albo pracy. Także "w tym
     * miejscu jest mój dom" i "zapisz, że ta lokalizacja to mój dom" - bieg
     * 160, tak to naprawdę zostało powiedziane.
     */
    fun zapisDomuLubPracy(tekst: String): String? {
        val t = tekst.lowercase().trim().trimEnd('.', '!')
            .replace(Regex("""^(?:zapami[eę]taj|zapisz|pami[eę]taj)(?:\s+sobie)?,?\s+(?:[zż]e\s+)?"""), "")
        return when {
            ZAPIS_DOMU.matches(t) -> DOM
            ZAPIS_PRACY.matches(t) -> PRACA
            else -> null
        }
    }

    fun czyLista(tekst: String): Boolean =
        LISTA.matches(tekst.lowercase().trim().trimEnd('?', '.'))

    fun czyUsuńWszystkie(tekst: String): Boolean =
        USUŃ.matches(tekst.lowercase().trim().trimEnd('.', '!'))

    /** Zapytanie do Overpass (OpenStreetMap) o obiekty w promieniu [promieńM]. */
    fun zapytanieOverpass(cel: Cel, lat: Double, lon: Double, promieńM: Int): String? {
        val wokół = "around:$promieńM,$lat,$lon"
        val filtr = when (cel) {
            is Cel.Nazwa -> {
                val wzór = Regex.escape(cel.tekst).replace("\"", "")
                """[~"^(name|brand)$"~"$wzór",i]"""
            }
            is Cel.Rodzaj -> """["${cel.klucz}"~"^(${cel.wartości.joinToString("|")})$"]"""
            is Cel.Zapisane -> return null
        }
        return """[out:json][timeout:8];nwr($wokół)$filtr;out center 5;"""
    }

    data class Obiekt(val nazwa: String?, val lat: Double, val lon: Double)

    fun parsujOverpass(json: String): List<Obiekt> {
        val el = JsonParser.parseString(json).asJsonObject.getAsJsonArray("elements") ?: return emptyList()
        return el.mapNotNull { e ->
            val o = e.asJsonObject
            val (lat, lon) = when {
                o.has("lat") -> o.get("lat").asDouble to o.get("lon").asDouble
                o.has("center") -> o.getAsJsonObject("center").let { it.get("lat").asDouble to it.get("lon").asDouble }
                else -> return@mapNotNull null
            }
            val nazwa = o.getAsJsonObject("tags")?.get("name")?.asString
            Obiekt(nazwa, lat, lon)
        }
    }

    fun odległośćM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    /** Co powiedzieć, gdy przypomnienie wypada. */
    fun komunikat(r: PlaceReminder, nazwaMiejsca: String?): String {
        val gdzie = nazwaMiejsca ?: r.gdzie
        return "Przypomnienie, bo jesteś przy: $gdzie. ${naGłos(r.co)}."
    }

    private fun naGłos(co: String): String =
        if (Regex("""^(żebyś|abyś)(?!\p{L})""").containsMatchIn(co)) "Pamiętaj, $co" else co.replaceFirstChar { it.uppercase() }

    fun potwierdzenie(r: PlaceReminder): String {
        val kiedy = if (r.odMs > 0) {
            " Nie wcześniej niż " + java.text.SimpleDateFormat("d.MM 'o' HH:mm", java.util.Locale("pl", "PL"))
                .format(java.util.Date(r.odMs)) + "."
        } else {
            ""
        }
        return when (r.cel) {
            is Cel.Zapisane ->
                "Dobrze. Przypomnę, gdy będziesz ${if (r.cel.nazwa == DOM) "w domu" else "w pracy"}: ${r.co}.$kiedy"
            else -> "Dobrze. Przypomnę, gdy będziesz przy: ${r.gdzie}. ${naGłos(r.co)}.$kiedy"
        }
    }

    const val DOM = "Dom"
    const val PRACA = "Praca"

    /** Jak blisko trzeba być - zapisane miejsce (GPS w budynku pływa) i obiekt z mapy. */
    const val PROMIEŃ_ZAPISANE_M = 150.0
    const val PROMIEŃ_OBIEKT_M = 70

    private val CZASOWNIK_BYCIA =
        """(?:b[eę]d[eę]|dojd[eę]|dojad[eę]|wejd[eę]|wr[oó]c[eę]|znajd[eę]\s+si[eę]|przejd[eę]|przyjad[eę]|dotr[eę]|zajad[eę]|wpadn[eę])"""
    private val PRZYIMEK = """(?:w|we|na|przy|do|ko[lł]o|obok|pod)"""

    private val PRZYPOMNIJ_POTEM_GDZIE = Regex(
        """^przypomnij\s+mi\s+(?<co>.+?),?\s+(?:gdy|kiedy|jak|jak\s+tylko)\s+$CZASOWNIK_BYCIA\s+$PRZYIMEK\s+(?<gdzie>.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val PRZYPOMNIJ_GDZIE_POTEM_CO = Regex(
        """^przypomnij\s+mi,?\s+(?:gdy|kiedy|jak|jak\s+tylko)\s+$CZASOWNIK_BYCIA\s+$PRZYIMEK\s+(?<gdzie>.+?),?\s+""" +
            """(?<co>(?:[zż]ebym|[zż]eby|abym|aby|[zż]e|o)\s+.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val GDZIE_NA_POCZĄTKU = Regex("""^(?:gdy|kiedy|jak)\s""", RegexOption.IGNORE_CASE)
    private val DZIEŃ = Regex("""(?<!\p{L})(?:pojutrze|jutro|dzi[sś](?:iaj)?)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val GODZINA = Regex(
        """\b(?:po|od|oko[lł]o|ko[lł]o|o)\s+(?:godzinie\s+)?(\d{1,2})(?:[:.](\d{2}))?\b""",
        RegexOption.IGNORE_CASE
    )
    private val GDZIE_POTEM_PRZYPOMNIJ = Regex(
        """^(?:gdy|kiedy|jak|jak\s+tylko)\s+$CZASOWNIK_BYCIA\s+$PRZYIMEK\s+(?<gdzie>.+?),?\s+przypomnij\s+mi\s+(?<co>.+)$""",
        RegexOption.IGNORE_CASE
    )

    private const val TUTAJ = """(?:tu(?:taj)?|w\s+tym\s+miejscu|to\s+miejsce|ta\s+lokalizacja|to)"""
    private val ZAPIS_DOMU = Regex(
        """^$TUTAJ\s+(?:to\s+|jest\s+)?(?:m[oó]j\s+)?dom$|^tu(?:taj)?\s+mieszkam$|^mieszkam\s+tu(?:taj)?$"""
    )
    private val ZAPIS_PRACY = Regex(
        """^$TUTAJ\s+(?:to\s+|jest\s+)?(?:moja\s+)?praca$|^tu(?:taj)?\s+pracuj[eę]$|^pracuj[eę]\s+tu(?:taj)?$"""
    )
    private val LISTA = Regex("""^(jakie\s+mam|poka[zż]|wymie[nń]|przeczytaj)\s+(moje\s+)?przypomnienia(\s+(o|w)\s+miejsc\w*)?$""")
    private val USUŃ = Regex("""^(usu[nń]|skasuj|wyczy[sś][cć])\s+(wszystkie\s+)?przypomnienia(\s+o\s+miejscach)?$""")

    private val RODZAJE: List<Pair<List<String>, Cel.Rodzaj>> = listOf(
        listOf("aptec", "apteka") to Cel.Rodzaj("amenity", listOf("pharmacy"), "apteka"),
        listOf("bankomat") to Cel.Rodzaj("amenity", listOf("atm", "bank"), "bankomat"),
        listOf("poczt") to Cel.Rodzaj("amenity", listOf("post_office"), "poczta"),
        listOf("stacj", "stacji benzyn", "tankow") to Cel.Rodzaj("amenity", listOf("fuel"), "stacja paliw"),
        listOf("piekarn") to Cel.Rodzaj("shop", listOf("bakery"), "piekarnia"),
        listOf("sklep", "spożywcz", "spozywcz", "market", "supermarket") to
            Cel.Rodzaj("shop", listOf("supermarket", "convenience"), "sklep spożywczy"),
        listOf("kiosk") to Cel.Rodzaj("shop", listOf("kiosk", "newsagent"), "kiosk"),
        listOf("drogeri") to Cel.Rodzaj("shop", listOf("chemist", "cosmetics"), "drogeria"),
        listOf("paczkomat") to Cel.Rodzaj("amenity", listOf("parcel_locker"), "paczkomat")
    )
}
