package pl.victor.app.proactive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * Miejsca w pobliżu z OpenStreetMap - dla modeli BEZ Map Google.
 *
 * ## Po co
 * Gemini dostaje przy pytaniu o okolicę Mapy Google (miejsca, oceny, opinie).
 * OpenAI i Claude dostawały sam adres - o tym, że za rogiem jest Biedronka,
 * mogły co najwyżej zgadywać. OpenStreetMap ma te sklepy, lokale, apteki i
 * bankomaty z adresami i często godzinami otwarcia - za darmo, bez klucza.
 * Opinii w nim nie ma i model ma tego nie udawać.
 *
 * ## Prywatność
 * Do serwera Overpass idą same współrzędne i rodzaj miejsca - bez treści
 * pytania i bez danych użytkownika.
 *
 * Ten plik to czysta logika (rozpoznanie rodzaju, zapytanie, rozbiór,
 * opis) - pobieranie jest w [OsmPlacesClient].
 */
object OsmPlaces {

    /** Rodzaj miejsca: słowa, po których go poznajemy, i filtry Overpass. */
    data class Rodzaj(val nazwa: String, val słowa: List<String>, val filtry: List<String>)

    val RODZAJE = listOf(
        Rodzaj(
            "jedzenie",
            listOf("zjeść", "zjesc", "zjem", "restaurac", "jedzeni", "pizz", "kebab", "burger", "sushi", "bistro", "bar mleczny", "pierog"),
            listOf("""[amenity~"^(restaurant|fast_food|food_court)$"]""")
        ),
        Rodzaj("kawiarnia", listOf("kawiar", "kawę", "kawe ", "na kawę", "kawka"), listOf("""[amenity=cafe]""")),
        Rodzaj(
            "piekarnia i słodycze",
            listOf("piekarn", "cukiern", "lodziarn", "lody", "ciastk"),
            listOf("""[shop~"^(bakery|pastry|confectionery)$"]""", """[amenity=ice_cream]""")
        ),
        Rodzaj("bar i pub", listOf(" pub", "na piwo", "na drinka"), listOf("""[amenity~"^(bar|pub|biergarten)$"]""")),
        Rodzaj(
            "sklep spożywczy",
            listOf("sklep", "market", "zakupy", "spożyw", "spozyw", "warzywniak"),
            listOf("""[shop~"^(supermarket|convenience|grocery|greengrocer|discount)$"]""")
        ),
        Rodzaj("drogeria", listOf("drogeri", "kosmetyki"), listOf("""[shop~"^(chemist|cosmetics|drugstore)$"]""")),
        Rodzaj("apteka", listOf("aptek"), listOf("""[amenity=pharmacy]""")),
        Rodzaj("fryzjer i kosmetyczka", listOf("fryzjer", "barber", "kosmetycz", "paznokci", "manicure"), listOf("""[shop~"^(hairdresser|beauty)$"]""")),
        Rodzaj(
            "bankomat i bank",
            listOf("bankomat", "wypłac", "wyplac", "kantor"),
            listOf("""[amenity~"^(atm|bank|bureau_de_change)$"]""")
        ),
        Rodzaj("poczta i paczkomat", listOf("poczt", "paczkomat", "paczk", "nadać", "nadac"), listOf("""[amenity~"^(post_office|parcel_locker)$"]""")),
        Rodzaj("stacja paliw", listOf("stacj", "tankow", "benzyn", "paliw", "diesel", "ładowark", "ladowark"), listOf("""[amenity~"^(fuel|charging_station)$"]""")),
        Rodzaj("parking", listOf("parking", "zaparkow"), listOf("""[amenity=parking]""")),
        Rodzaj("toaleta", listOf("toalet", " wc", "ubikacj"), listOf("""[amenity=toilets]""")),
        Rodzaj(
            "zdrowie",
            listOf("lekarz", "przychodni", "szpital", "dentyst", "weterynar", "pogotowi"),
            listOf("""[amenity~"^(doctors|clinic|hospital|dentist|veterinary)$"]""")
        ),
        Rodzaj("nocleg", listOf("hotel", "nocleg", "przenocow", "hostel", "spać", "spac "), listOf("""[tourism~"^(hotel|hostel|guest_house|motel)$"]""")),
        Rodzaj("siłownia", listOf("siłowni", "silowni", "fitness"), listOf("""[leisure=fitness_centre]""")),
        Rodzaj(
            "komunikacja",
            listOf("przystan", "dworzec", "dworca", "tramwaj", "autobus", "metro", "pociąg", "pociag"),
            listOf("""[highway=bus_stop]""", """[railway~"^(station|tram_stop|halt)$"]""")
        )
    )

    /** Sieci rozpoznawane po nazwie: słowo z pytania -> nazwa w OSM. */
    val MARKI = linkedMapOf(
        "biedronk" to "Biedronka", "lidl" to "Lidl", "żabk" to "Żabka", "zabk" to "Żabka",
        "kaufland" to "Kaufland", "auchan" to "Auchan", "carrefour" to "Carrefour",
        "netto" to "Netto", "dino" to "Dino", "stokrotk" to "Stokrotka", "lewiatan" to "Lewiatan",
        "aldi" to "Aldi", "rossmann" to "Rossmann", "hebe" to "Hebe", "pepco" to "Pepco",
        "orlen" to "Orlen", "circle k" to "Circle K", "shell" to "Shell",
        "mcdonald" to "McDonald", "kfc" to "KFC", "starbucks" to "Starbucks",
        "inpost" to "InPost", "żabce" to "Żabka", "zabce" to "Żabka", "biedronce" to "Biedronka"
    )

    /**
     * Słowa, które wiążą pytanie z MIEJSCEM, w którym się stoi. Celowo bez
     * "jaki" i "polecasz": "jaki hotel polecasz w Paryżu" nie dotyczy okolicy,
     * a dostałoby położenie z Torunia.
     */
    private val PYTANIE = Regex(
        """(\bgdzie\b|\bczy (jest|są|sa|ma|mają|maja|znajdzie)\b|\btu\b|\btutaj\b|\bblisko|najbli[zż]|otwart|doj[sś][cć]|dojecha[cć]|znajd[eę])"""
    )

    fun rodzaj(pytanie: String): Rodzaj? {
        val q = " " + pytanie.lowercase(Locale("pl")) + " "
        return RODZAJE.firstOrNull { r -> r.słowa.any { q.contains(it) } }
    }

    fun marka(pytanie: String): String? {
        val q = pytanie.lowercase(Locale("pl"))
        return MARKI.entries.firstOrNull { q.contains(it.key) }?.value
    }

    /**
     * Czy pytanie szuka konkretnego RODZAJU miejsca ("gdzie jest fryzjer",
     * "czy jest tu Biedronka") - wtedy też dostaje położenie i mapę, choć nie
     * mówi "w pobliżu". Samo słowo nie wystarczy: "kupiłem chleb w Biedronce"
     * to nie pytanie o drogę.
     */
    fun szukaMiejsca(pytanie: String): Boolean {
        val q = pytanie.lowercase(Locale("pl"))
        return (rodzaj(q) != null || marka(q) != null) && PYTANIE.containsMatchIn(q)
    }

    /** Promień: konkretny rodzaj albo sieć - dalej, wszystko naraz - tylko najbliższa okolica. */
    fun promień(rodzaj: Rodzaj?, marka: String?): Int = if (rodzaj != null || marka != null) 2000 else 500

    /** Zapytanie Overpass QL. */
    fun zapytanie(lat: Double, lon: Double, pytanie: String): String {
        val r = rodzaj(pytanie)
        val m = marka(pytanie)
        val wokół = String.format(Locale.US, "(around:%d,%.6f,%.6f)", promień(r, m), lat, lon)
        val części = when {
            m != null -> listOf("""nwr$wokół[~"^(name|brand)$"~"${m.replace("\"", "")}",i]""")
            r != null -> r.filtry.map { "nwr$wokół$it" }
            else -> OGÓLNE.map { "nwr$wokół$it[name]" }
        }
        return "[out:json][timeout:6];(" + części.joinToString(";") + ";);out center tags 80;"
    }

    /** Bez rodzaju w pytaniu: to, o co ludzie pytają najczęściej. */
    private val OGÓLNE = listOf(
        """[amenity~"^(restaurant|fast_food|cafe|bar|pub|pharmacy|atm|bank|post_office|fuel|ice_cream)$"]""",
        """[shop~"^(supermarket|convenience|bakery|hairdresser|chemist|beauty|greengrocer|kiosk)$"]"""
    )

    data class Miejsce(
        val nazwa: String,
        val rodzaj: String,
        val adres: String?,
        val godziny: String?,
        val kuchnia: String?,
        val odległośćM: Int
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.s(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

    /** Rozbiór odpowiedzi Overpass: najbliższe najpierw, bez powtórzeń. */
    fun rozbierz(odpowiedź: String, lat: Double, lon: Double, ile: Int = MAKS): List<Miejsce> {
        val elementy = runCatching {
            json.parseToJsonElement(odpowiedź).jsonObject["elements"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        val wynik = elementy.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val tags = o["tags"] as? JsonObject ?: return@mapNotNull null
            val środek = o["center"] as? JsonObject
            val la = o.d("lat") ?: środek?.d("lat") ?: return@mapNotNull null
            val lo = o.d("lon") ?: środek?.d("lon") ?: return@mapNotNull null
            val typ = typ(tags)
            val nazwa = tags.s("name") ?: tags.s("brand") ?: typ ?: return@mapNotNull null
            val ulica = tags.s("addr:street")
            val adres = ulica?.let { u -> tags.s("addr:housenumber")?.let { "$u $it" } ?: u }
            Miejsce(
                nazwa = nazwa,
                rodzaj = typ ?: "miejsce",
                adres = adres,
                godziny = tags.s("opening_hours"),
                kuchnia = tags.s("cuisine")?.split(';')?.joinToString(", ") { it.trim().replace('_', ' ') },
                odległośćM = odległość(lat, lon, la, lo)
            )
        }
        return wynik.sortedBy { it.odległośćM }
            .distinctBy { it.nazwa.lowercase() + "|" + (it.odległośćM / 30) }
            .take(ile)
    }

    /** Polska nazwa rodzaju z tagów OSM. */
    fun typ(tags: JsonObject): String? {
        val wartość = tags.s("amenity") ?: tags.s("shop") ?: tags.s("tourism") ?: tags.s("leisure")
            ?: tags.s("railway") ?: tags.s("highway")
        return wartość?.let { NAZWY[it] ?: it.replace('_', ' ') }
    }

    private val NAZWY = mapOf(
        "restaurant" to "restauracja", "fast_food" to "szybkie jedzenie", "food_court" to "strefa jedzenia",
        "cafe" to "kawiarnia", "bar" to "bar", "pub" to "pub", "biergarten" to "ogródek piwny",
        "ice_cream" to "lodziarnia", "pharmacy" to "apteka", "atm" to "bankomat", "bank" to "bank",
        "bureau_de_change" to "kantor", "post_office" to "poczta", "parcel_locker" to "paczkomat",
        "fuel" to "stacja paliw", "charging_station" to "ładowarka", "parking" to "parking",
        "toilets" to "toaleta", "doctors" to "lekarz", "clinic" to "przychodnia", "hospital" to "szpital",
        "dentist" to "dentysta", "veterinary" to "weterynarz", "supermarket" to "supermarket",
        "convenience" to "sklep osiedlowy", "grocery" to "sklep spożywczy", "greengrocer" to "warzywniak",
        "discount" to "dyskont", "bakery" to "piekarnia", "pastry" to "cukiernia",
        "confectionery" to "słodycze", "chemist" to "drogeria", "cosmetics" to "kosmetyki",
        "drugstore" to "drogeria", "hairdresser" to "fryzjer", "beauty" to "salon kosmetyczny",
        "kiosk" to "kiosk", "hotel" to "hotel", "hostel" to "hostel", "guest_house" to "pensjonat",
        "motel" to "motel", "fitness_centre" to "siłownia", "bus_stop" to "przystanek autobusowy",
        "station" to "dworzec", "tram_stop" to "przystanek tramwajowy", "halt" to "przystanek kolejowy"
    )

    /** Odległość po kuli w metrach. */
    fun odległość(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).let { it * it }
        return (2 * r * Math.asin(Math.sqrt(a))).toInt()
    }

    /** Sekcja promptu - albo informacja, że w promieniu nic nie ma. */
    fun opisDlaModelu(miejsca: List<Miejsce>, pytanie: String): String = buildString {
        val r = rodzaj(pytanie)
        val m = marka(pytanie)
        append("=== MIEJSCA W POBLIŻU (OpenStreetMap, wokół pozycji użytkownika) ===\n")
        if (miejsca.isEmpty()) {
            append("W promieniu ${promień(r, m)} m mapa nie ma ")
            append(m ?: r?.nazwa ?: "takich miejsc")
            append(". Powiedz to wprost - nie wymyślaj adresów.\n")
            return@buildString
        }
        for (p in miejsca) {
            append("- ").append(p.nazwa).append(" (").append(p.rodzaj)
            p.kuchnia?.let { append(", kuchnia: ").append(it) }
            append(") - ").append(p.odległośćM).append(" m")
            p.adres?.let { append(", ").append(it) }
            p.godziny?.let { append(", godziny: ").append(it) }
            append('\n')
        }
        append("Odległości są w linii prostej - pieszo wyjdzie trochę więcej. ")
        append("Ocen ani opinii tu NIE MA: nie wymyślaj ich, a gdy ktoś o nie pyta, powiedz, ")
        append("że mapa ich nie podaje. Godziny są w zapisie OpenStreetMap (Mo-Fr = pon.-pt.).\n")
    }

    const val MAKS = 12
}
