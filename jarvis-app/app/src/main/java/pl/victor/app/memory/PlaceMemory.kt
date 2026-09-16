package pl.victor.app.memory

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Zapamiętaj, gdzie zaparkowałem" - i odnalezienie tego potem.
 *
 * ## Skąd pomysł
 * Z przeglądu tego, co inne okulary AI robią lepiej. Ray-Ban Meta ma to jako
 * funkcję sztandarową i nie bez powodu: to jedyna rzecz, której telefon w
 * kieszeni NIE zrobi wygodniej. Zapamiętanie miejsca musi kosztować jedno
 * zdanie w chwili, w której się odchodzi - a nie wyjęcie telefonu, odblokowanie
 * i szukanie aplikacji, bo wtedy nikt tego nie zrobi.
 *
 * Mamy wszystkie części od dawna: GPS, aparat i pamięć. Brakowało tylko
 * spięcia.
 *
 * ## Czemu odległość I KIERUNEK, a nie sam adres
 * Bo adres nie pomaga stojącemu na parkingu. "Sto dwadzieścia metrów na
 * północny wschód" da się wykonać; "ulica Kwiatowa 5" nie, gdy stoi się między
 * dwoma rzędami samochodów. Dla osoby niewidomej ta różnica jest całą
 * funkcją.
 *
 * Ten plik to sama arytmetyka - bez Androida, bez sieci - więc da się go
 * uruchomić w teście. Zapis i odczyt robi wołający.
 */
object PlaceMemory {

    /** Zapamiętane miejsce. */
    data class Place(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val savedAtMs: Long
    )

    /** Punkt, w którym stoi użytkownik. */
    data class Here(val latitude: Double, val longitude: Double)

    /**
     * Odległość w metrach po powierzchni Ziemi (wzór haversine).
     *
     * Dla dystansów parkingowych błąd kulistego przybliżenia jest poniżej
     * metra, a przy takich odległościach i tak nie ma znaczenia - liczy się
     * rząd wielkości i kierunek.
     */
    fun distanceMeters(from: Here, to: Place): Double {
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Azymut w stopniach, 0 = północ, rosnąco na wschód. */
    fun bearingDegrees(from: Here, to: Place): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Kierunek słowem.
     *
     * Osiem kierunków, nie szesnaście: "wschodnio-północny wschód" jest
     * bezużyteczne dla kogoś, kto ma po tym iść.
     */
    fun bearingName(degrees: Double): String {
        val index = ((degrees % 360.0 + 360.0) % 360.0 / 45.0).roundToInt() % 8
        return COMPASS[index]
    }

    /**
     * Zdanie do wypowiedzenia.
     *
     * Odległość zaokrąglamy Z ROZMYSŁEM: "sto dwadzieścia metrów" jest do
     * wykonania, "sto siedemnaście i pół" udaje dokładność, której GPS w
     * telefonie nie ma.
     */
    fun describe(place: Place, here: Here, nowMs: Long): String {
        val meters = distanceMeters(here, place)
        val direction = bearingName(bearingDegrees(here, place))
        val distance = when {
            meters < VERY_CLOSE_M -> "tuż obok"
            meters < KILOMETER_M -> "${roundMeters(meters)} metrów na $direction"
            else -> "${roundKilometers(meters)} km na $direction"
        }
        return "${place.name}: $distance. Zapisane ${ago(nowMs - place.savedAtMs)}."
    }

    /** "20 minut temu", "3 godziny temu", "wczoraj". */
    fun ago(elapsedMs: Long): String {
        val minutes = elapsedMs / 60_000
        return when {
            minutes < 1 -> "przed chwilą"
            minutes < 60 -> "$minutes ${plural(minutes, "minutę", "minuty", "minut")} temu"
            minutes < 24 * 60 -> {
                val hours = minutes / 60
                "$hours ${plural(hours, "godzinę", "godziny", "godzin")} temu"
            }
            minutes < 48 * 60 -> "wczoraj"
            else -> "${minutes / (24 * 60)} dni temu"
        }
    }

    /**
     * Polska odmiana po liczbie.
     *
     * Bez tego wychodzi "5 minuty temu" - drobiazg, który w wypowiedzi na głos
     * słychać natychmiast.
     */
    private fun plural(n: Long, one: String, few: String, many: String): String {
        val last = abs(n) % 10
        val lastTwo = abs(n) % 100
        return when {
            n == 1L -> one
            last in 2..4 && lastTwo !in 12..14 -> few
            else -> many
        }
    }

    private fun roundMeters(m: Double): Int = when {
        m < 100 -> (m / 5).roundToInt() * 5
        else -> (m / 10).roundToInt() * 10
    }

    private fun roundKilometers(m: Double): String =
        String.format(java.util.Locale.US, "%.1f", m / KILOMETER_M).replace('.', ',')

    // === Rozpoznanie zdania ===
    //
    // Osobno od arytmetyki, ale w tym samym pliku, bo to jest ta sama funkcja
    // opisana z dwóch stron. Czyste napisy - da się uruchomić w teście.

    /** Domyślna nazwa, gdy ktoś mówi o parkowaniu, a nie o "tym miejscu". */
    const val CAR = "Samochód"

    /** Nazwa dla ogólnego "zapamiętaj to miejsce". */
    const val SPOT = "To miejsce"

    /**
     * Czy to prośba o ZAPAMIĘTANIE miejsca - i pod jaką nazwą.
     *
     * @return nazwa miejsca albo `null`, gdy zdanie nie jest taką prośbą
     */
    fun saveRequest(text: String): String? {
        val t = simplify(text)
        if (SAVE_OPENERS.none { t.startsWith(it) }) return null
        return when {
            CAR_WORDS.any { t.contains(it) } -> CAR
            PLACE_WORDS.any { t.contains(it) } -> SPOT
            else -> null
        }
    }

    /**
     * Czy to pytanie O ZAPAMIĘTANE miejsce.
     *
     * Wymaga słowa pytającego NA POCZĄTKU. Bez tego "nie pamiętam, gdzie
     * zaparkowałem, ale to nieważne" byłoby pytaniem - a jest zdaniem w
     * rozmowie.
     */
    fun recallRequest(text: String): String? {
        val t = simplify(text)
        if (ASK_OPENERS.none { t.startsWith(it) }) return null
        return when {
            CAR_WORDS.any { t.contains(it) } -> CAR
            PLACE_WORDS.any { t.contains(it) } -> SPOT
            else -> null
        }
    }

    /** Małe litery, bez ogonków i interpunkcji - tekst idzie z rozpoznawania mowy. */
    private fun simplify(text: String): String =
        text.lowercase()
            .replace('ł', 'l')
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private val SAVE_OPENERS = listOf(
        "zapamietaj", "zapisz gdzie", "zapisz sobie gdzie", "zanotuj gdzie"
    )

    private val ASK_OPENERS = listOf(
        "gdzie", "znajdz", "zaprowadz mnie do", "doprowadz mnie do", "jak wrocic do"
    )

    private val CAR_WORDS = listOf(
        "zaparkowal", "parkowal", "parking", "samochod", "auto", "auta", "wozu", "woz"
    )

    private val PLACE_WORDS = listOf(
        "to miejsce", "tego miejsca", "tym miejscu", "gdzie jestem", "gdzie stoje",
        "miejsce", "miejsca"
    )

    private val COMPASS = listOf(
        "północ", "północny wschód", "wschód", "południowy wschód",
        "południe", "południowy zachód", "zachód", "północny zachód"
    )

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val VERY_CLOSE_M = 15.0
    private const val KILOMETER_M = 1000.0
}
