package pl.victor.app.navigation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * Komunikacja miejska - czysta logika (bez Androida): adresy Map, rozpoznanie
 * pytania o odjazdy, zapytanie Overpass o przystanki i zdanie do powiedzenia.
 *
 * ## Po co
 * "Kiedy najbliższy tramwaj?" szło dotąd do modelu, który odjazdów nie zna i
 * mógł je co najwyżej zmyślić. Za darmo da się powiedzieć prawdę połowiczną,
 * ale sprawdzalną: który przystanek jest najbliżej i jakie linie tam stają
 * (OpenStreetMap ma przystanki i trasy linii). Odjazdów na żywo darmowo nie
 * ma - lokalne API przewoźników wymagają kluczy, a tych celowo nie dodajemy -
 * więc asystent mówi to wprost i proponuje Mapy Google, które rozkład mają.
 *
 * Pobieranie jest w [PrzystankiClient].
 */
object KomunikacjaLinki {

    /**
     * Plan podróży komunikacją w Mapach Google.
     *
     * `google.navigation:` zna tylko tryby d (auto), w (pieszo), b (rower) i
     * l (jednoślad) - komunikacji miejskiej NIE. Dla niej jest adres Map z
     * `travelmode=transit`: otwiera plan z liniami, przesiadkami i godzinami.
     */
    fun planPodróży(cel: String): String =
        "https://www.google.com/maps/dir/?api=1&destination=${koduj(cel)}&travelmode=transit"

    /**
     * Zapytanie do wyszukiwarki Map o przystanek - Mapy pokazują przy nim
     * najbliższe odjazdy. Miasto dokładamy, bo "Rondo Mogilskie" bez niego
     * bywa rondem, a nie przystankiem.
     */
    fun przystanekDoMap(nazwa: String, miasto: String?): String =
        buildString {
            append("przystanek ").append(nazwa)
            miasto?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
        }

    /** Kodowanie do adresu: spacja jako %20, nie "+" - tak jak `Uri.encode`. */
    fun koduj(tekst: String): String =
        java.net.URLEncoder.encode(tekst, "UTF-8").replace("+", "%20")
}

/** Rodzaj pojazdu - nazwa w OSM (tag `route`) i po polsku. */
enum class Pojazd(val trasyOsm: List<String>, val mnoga: String) {
    TRAMWAJ(listOf("tram"), "tramwaje"),
    AUTOBUS(listOf("bus"), "autobusy"),
    TROLEJBUS(listOf("trolleybus"), "trolejbusy"),
    METRO(listOf("subway"), "metro"),
    KOLEJ(listOf("train", "light_rail"), "pociągi");

    companion object {
        fun zOsm(route: String?): Pojazd? = entries.firstOrNull { route in it.trasyOsm }
    }
}

/** Rozpoznanie pytania o odjazdy - "kiedy najbliższy tramwaj z przystanku X?". */
object OdjazdyPytanie {

    data class Pytanie(
        /** Pojazd, o który pytano, albo `null` - "jakie linie stąd jeżdżą". */
        val pojazd: Pojazd?,
        /** Nazwa przystanku z pytania (tak jak padła) albo `null` = "stąd". */
        val przystanek: String?
    )

    private val PL = Locale("pl")

    /** Pytanie o CZAS odjazdu. */
    private val CZAS = Regex("""(^|\s)(kiedy|o\s+kt[oó]rej|za\s+ile|jak\s+cz[eę]sto|co\s+ile)(\s|$)""")

    /** Pytanie o LINIE: "jakie tramwaje stąd jeżdżą", "które autobusy stają na…". */
    private val LINIE = Regex(
        """(jakie|kt[oó]re|co\s+za)\s+(tramwaje|autobusy|trolejbusy|linie|pojazdy|numery)\b"""
    )

    private val ROZKŁAD = Regex("""rozk[lł]ad\S*\s+(jazdy|tramwaj|autobus|z\s|ze\s|dla\s|przystank|na\s+przystank)""")

    /** Słowa pojazdu albo odjazdu - bez nich "kiedy" to zwykłe pytanie. */
    private val POJAZD_SŁOWO = Regex(
        // Bez "przystanek": "kiedy będę na przystanku" to pytanie o trasę.
        """(tramwaj|autobus|trolejbus|(^|\s)metr(o|a)(\s|$)|poci[aą]g|kolejk|odjazd|odje[zż]d[zż]a)"""
    )

    /** Czas przeszły - "kiedy jechałem tramwajem" to wspomnienie, nie pytanie o rozkład. */
    private val PRZESZŁOŚĆ = Regex(
        """(jecha[lł]\S*|by[lł](em|am|o|a|y|i)?(\s|$)|zacz[eę][lł]\S*|powsta[lł]\S*|kursowa[lł]\S*|je[zź]dzi[lł]\S*|wprowadzon|zbudowan|uruchomion|""" +
            // Formy bezosobowe: "kiedy otwarto metro", "kiedy wynaleziono tramwaj".
            """otwarto|otwart[aey]|wynalezion\S*|zbudowano|wprowadzono|uruchomiono)"""
    )

    /** Pytanie o drogę ("jak dojadę tramwajem do X") to trasa, nie odjazdy. */
    private val TRASA = Regex("""(^|\s)(jak|kt[oó]r[eę]dy)\s+(mam\s+)?(si[eę]\s+)?(doja|dojecha|dotr|dosta|pojecha|jecha|doj[sś])""")

    /** "z przystanku Rondo Mogilskie", "na przystanku Teatr Bagatela". */
    private val NAZWANY_PRZYSTANEK = Regex("""(?:z|ze|spod|sprzed|na|dla)\s+przystank\S*\s+(.+)$""")

    /** "tramwaj z Ronda Mogilskiego" - nazwa po "z" zaraz za pojazdem. */
    private val Z_MIEJSCA = Regex(
        """(?:tramwaj\S*|autobus\S*|trolejbus\S*|metr(?:o|a)|poci[aą]g\S*|kolejk\S*|linie|lini[aę]|odjazd\S*|odje[zż]d[zż]a\S*)""" +
            """(?:\s+\S+){0,2}?\s+(?:z|ze|spod|sprzed)\s+(.+)$"""
    )

    /** Słowa, które po "z" oznaczają "stąd", a nie nazwę przystanku. */
    private val TU = Regex("""^(tego\s+przystanku|tego\s+miejsca|tej\s+okolicy|tąd|tad|okolicy|pobliża|pobliza|naprzeciwka|tutaj|tu)\b""")

    /** Ogon pytania, który nie jest nazwą: kierunek, "teraz", "dzisiaj". */
    private val OGON = Regex("""\s+(do|w\s+stron[eę]|w\s+kierunku|na)\s+.*$|\s+(teraz|dzisiaj|dziś|jutro|rano|wieczorem|stąd|stad)\b.*$""")

    fun rozpoznaj(wypowiedź: String): Pytanie? {
        val q = wypowiedź.lowercase(PL).trim().trimEnd('?', '.', '!').replace(Regex("""\s+"""), " ")
        if (q.isEmpty()) return null
        if (TRASA.containsMatchIn(q)) return null
        if (PRZESZŁOŚĆ.containsMatchIn(q)) return null
        // "O której mam pociąg?" to pytanie o WŁASNY plan (kalendarz, bilet),
        // a nie o przystanki wokół (przegląd 22.11).
        // "Kiedy mam tramwaj stąd" zostaje - jest wskazanie miejsca.
        if (Regex("""(^|\s)(mam|m[oó]j|moja|mojego|moim)(\s|$)""").containsMatchIn(q) &&
            !Regex("""(^|\s)(stąd|stad|tutaj|tu|przystank\S*)(\s|$)""").containsMatchIn(q)
        ) return null
        val oCzas = CZAS.containsMatchIn(q) && POJAZD_SŁOWO.containsMatchIn(q)
        val oLinie = LINIE.containsMatchIn(q) &&
            (POJAZD_SŁOWO.containsMatchIn(q) || q.contains("stąd") || q.contains("stad") || q.contains("tutaj") || Regex("""(^|\s)tu(\s|$)""").containsMatchIn(q))
        val oRozkład = ROZKŁAD.containsMatchIn(q)
        if (!oCzas && !oLinie && !oRozkład) return null
        return Pytanie(pojazd(q), przystanek(q))
    }

    fun pojazd(q: String): Pojazd? = when {
        q.contains("tramwaj") -> Pojazd.TRAMWAJ
        q.contains("trolejbus") -> Pojazd.TROLEJBUS
        q.contains("autobus") -> Pojazd.AUTOBUS
        Regex("""(^|\s)metr(o|a|em)(\s|$)""").containsMatchIn(q) -> Pojazd.METRO
        Regex("""poci[aą]g|kolejk""").containsMatchIn(q) -> Pojazd.KOLEJ
        else -> null
    }

    fun przystanek(q: String): String? {
        val surowa = NAZWANY_PRZYSTANEK.find(q)?.groupValues?.get(1)
            ?: Z_MIEJSCA.find(q)?.groupValues?.get(1)
            ?: return null
        if (TU.containsMatchIn(surowa)) return null
        val nazwa = surowa.replace(OGON, "").trim().trim(',', '.', '?', '!').trim()
        return nazwa.takeIf { it.length >= 3 }
    }
}

/**
 * Przystanki z OpenStreetMap: zapytanie Overpass, rozbiór i zdanie dla
 * użytkownika.
 */
object Przystanki {

    data class Przystanek(
        val nazwa: String,
        val odległośćM: Int,
        /** Numery linii według pojazdu, posortowane "po ludzku" (2 przed 10). */
        val linie: Map<Pojazd, List<String>>
    )

    /** Najbliższa okolica - tyle się dochodzi na przystanek bez planowania. */
    const val PROMIEŃ_M = 450

    /** Przystanek wskazany z nazwy może być w innej dzielnicy. */
    const val PROMIEŃ_NAZWANY_M = 6000

    private const val TRASY = "^(bus|tram|trolleybus|subway|light_rail|train)$"

    /**
     * Zapytanie Overpass QL.
     *
     * `foreach` wypisuje przystanek, a ZARAZ ZA NIM trasy linii, do których
     * należy - dzięki temu przy rozbiorze wiadomo, która linia staje gdzie,
     * bez pobierania członków relacji (trasa autobusu to setki odcinków drogi).
     */
    fun zapytanie(lat: Double, lon: Double, nazwa: String?): String {
        val r = if (nazwa != null) PROMIEŃ_NAZWANY_M else PROMIEŃ_M
        val wokół = String.format(Locale.US, "(around:%d,%.6f,%.6f)", r, lat, lon)
        val filtrNazwy = nazwa?.let { wzorzecNazwy(it) }?.takeIf { it.isNotEmpty() }
            ?.let { """[name~"$it"]""" } ?: "[name]"
        val rodzaje = listOf("[highway=bus_stop]", "[railway=tram_stop]", "[public_transport=platform]", "[public_transport=stop_position]")
        val części = rodzaje.joinToString("") { "node$wokół$it$filtrNazwy;" }
        return "[out:json][timeout:10];($części)->.p;" +
            "foreach.p->.s(.s out body;rel(bn.s)[type=route][route~\"$TRASY\"];out tags;);"
    }

    /**
     * Wzorzec nazwy odporny na odmianę i wielkość liter.
     *
     * Mówi się "z Ronda Mogilskiego", a w mapie stoi "Rondo Mogilskie". Z
     * każdego słowa bierzemy więc sam rdzeń (bez końcówki), a każdą literę
     * zapisujemy jako [Xx] - Overpass nie zawsze porównuje polskie litery bez
     * względu na wielkość, a z klasą znaków nie musi.
     */
    fun wzorzecNazwy(nazwa: String): String {
        val słowa = nazwa.lowercase(Locale("pl"))
            .split(Regex("""[\s\-./,]+"""))
            .map { it.filter { c -> c.isLetterOrDigit() } }
            .filter { it.isNotEmpty() && it !in POMIJANE }
        return słowa.joinToString(".*") { słowo ->
            val rdzeń = if (słowo.length <= 3 || słowo.all { it.isDigit() }) słowo
            else słowo.take((słowo.length - 3).coerceIn(3, 5))
            rdzeń.map { c ->
                val duża = c.uppercaseChar()
                if (duża != c) "[$duża$c]" else c.toString()
            }.joinToString("")
        }
    }

    private val POMIJANE = setOf("przystanek", "przystanku", "ulica", "ulicy", "ul", "al", "aleja", "alei")

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.s(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

    /**
     * Rozbiór odpowiedzi: węzeł przystanku, a za nim jego trasy. Przystanki o
     * tej samej nazwie (dwa perony, słupek i miejsce zatrzymania) łączymy w
     * jeden - dla człowieka to jeden przystanek.
     */
    fun rozbierz(odpowiedź: String, lat: Double, lon: Double): List<Przystanek> {
        val elementy = runCatching {
            json.parseToJsonElement(odpowiedź).jsonObject["elements"]?.jsonArray
        }.getOrNull() ?: return emptyList()

        class Zbiór(val nazwa: String, var odległośćM: Int) {
            val linie = linkedMapOf<Pojazd, MutableSet<String>>()
        }
        val zbiory = linkedMapOf<String, Zbiór>()
        var bieżący: Zbiór? = null
        for (e in elementy) {
            val o = e as? JsonObject ?: continue
            val tags = o["tags"] as? JsonObject
            when (o.s("type")) {
                "node" -> {
                    val nazwa = tags?.s("name")
                    val la = o.d("lat")
                    val lo = o.d("lon")
                    if (nazwa == null || la == null || lo == null) {
                        bieżący = null
                        continue
                    }
                    val d = odległość(lat, lon, la, lo)
                    val klucz = nazwa.lowercase(Locale("pl"))
                    val z = zbiory.getOrPut(klucz) { Zbiór(nazwa, d) }
                    if (d < z.odległośćM) z.odległośćM = d
                    bieżący = z
                }
                "relation" -> {
                    val z = bieżący ?: continue
                    val pojazd = Pojazd.zOsm(tags?.s("route")) ?: continue
                    val numer = tags?.s("ref") ?: continue
                    z.linie.getOrPut(pojazd) { linkedSetOf() }.add(numer)
                }
            }
        }
        return zbiory.values
            .map { z -> Przystanek(z.nazwa, z.odległośćM, z.linie.mapValues { (_, v) -> v.sortedWith(PO_LUDZKU) }) }
            .sortedBy { it.odległośćM }
    }

    /** "2" przed "10", a "N1" po liczbach. */
    val PO_LUDZKU: Comparator<String> = compareBy<String>(
        { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE },
        { it }
    )

    fun odległość(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int =
        pl.victor.app.proactive.OsmPlaces.odległość(lat1, lon1, lat2, lon2)

    /** Przystanek, o który pytano: z linią tego pojazdu, jeśli taki jest w pobliżu. */
    fun wybierz(lista: List<Przystanek>, pytanie: OdjazdyPytanie.Pytanie): List<Przystanek> {
        val p = pytanie.pojazd ?: return lista
        val zPojazdem = lista.filter { it.linie[p]?.isNotEmpty() == true }
        return zPojazdem.ifEmpty { lista }
    }

    /** Zdanie do powiedzenia - bez odjazdów, bo ich nie znamy. */
    fun odpowiedź(lista: List<Przystanek>, pytanie: OdjazdyPytanie.Pytanie): String {
        if (lista.isEmpty()) {
            return if (pytanie.przystanek != null) {
                "Nie znalazłem w mapie przystanku „${pytanie.przystanek}” w promieniu " +
                    "${NavMowa.odległość(PROMIEŃ_NAZWANY_M)}."
            } else {
                "W promieniu ${NavMowa.odległość(PROMIEŃ_M)} mapa nie pokazuje żadnego przystanku."
            }
        }
        val wybrane = wybierz(lista, pytanie)
        val brakPojazdu = pytanie.pojazd != null && wybrane.none { it.linie[pytanie.pojazd]?.isNotEmpty() == true }
        return buildString {
            if (brakPojazdu) {
                append("W pobliżu mapa nie pokazuje linii: ${pytanie.pojazd!!.mnoga}. ")
            }
            val pierwszy = wybrane.first()
            if (pytanie.przystanek != null) {
                append("Przystanek ${pierwszy.nazwa}, ${NavMowa.odległość(pierwszy.odległośćM)} stąd. ")
            } else {
                append("Najbliższy przystanek: ${pierwszy.nazwa}, ${NavMowa.odległość(pierwszy.odległośćM)}. ")
            }
            append(linie(pierwszy, pytanie.pojazd.takeUnless { brakPojazdu }))
            if (pytanie.przystanek == null) {
                wybrane.drop(1).firstOrNull()?.let { drugi ->
                    append(" Dalej: ${drugi.nazwa}, ${NavMowa.odległość(drugi.odległośćM)}")
                    val l = linie(drugi, pytanie.pojazd.takeUnless { brakPojazdu }, krótko = true)
                    if (l.isNotEmpty()) append(" - ").append(l) else append('.')
                }
            }
        }.trim()
    }

    /** "Tramwaje: 1, 4 i 5; autobusy: 129." */
    fun linie(p: Przystanek, tylko: Pojazd?, krótko: Boolean = false): String {
        val grupy = p.linie.filterKeys { tylko == null || it == tylko }.filterValues { it.isNotEmpty() }
        if (grupy.isEmpty()) return if (krótko) "" else "Linii, które tu stają, mapa nie podaje."
        val tekst = Pojazd.entries.mapNotNull { pojazd ->
            grupy[pojazd]?.let { numery ->
                val pokazane = numery.take(MAKS_LINII)
                val reszta = numery.size - pokazane.size
                val lista = wymień(pokazane) + if (reszta > 0) " i $reszta innych" else ""
                "${pojazd.mnoga}: $lista"
            }
        }.joinToString("; ")
        return (if (krótko) tekst else tekst.replaceFirstChar { it.uppercase() }) + "."
    }

    /** "1, 4 i 5". */
    fun wymień(elementy: List<String>): String = when (elementy.size) {
        0 -> ""
        1 -> elementy[0]
        else -> elementy.dropLast(1).joinToString(", ") + " i " + elementy.last()
    }

    /**
     * Sekcja promptu dla modelu z Mapami Google - lista przystanków i linii z
     * OSM oraz uczciwość co do tego, czego nie wiemy.
     */
    fun kontekstDlaModelu(lista: List<Przystanek>, pytanie: OdjazdyPytanie.Pytanie): String = buildString {
        append("=== KOMUNIKACJA MIEJSKA (pytanie o odjazdy) ===\n")
        if (lista.isEmpty()) {
            append("OpenStreetMap nie pokazuje przystanków w pobliżu.\n")
        } else {
            append("Przystanki z OpenStreetMap (odległość w linii prostej):\n")
            wybierz(lista, pytanie).take(4).forEach { p ->
                append("- ").append(p.nazwa).append(" - ").append(p.odległośćM).append(" m")
                val l = p.linie.entries.joinToString("; ") { (k, v) -> "${k.mnoga}: ${v.joinToString(", ")}" }
                if (l.isNotEmpty()) append(" - ").append(l)
                append('\n')
            }
        }
        append("Godziny odjazdów sprawdź w Mapach Google. Jeśli Mapy podają tylko ROZKŁAD, ")
        append("powiedz, że to rozkład, a nie odjazdy na żywo, i że tramwaj może się spóźnić. ")
        append("Jeśli godzin nie masz, nie wymyślaj ich - powiedz, jakie linie tu stają.\n")
    }

    const val MAKS_LINII = 8
}
