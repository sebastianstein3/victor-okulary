package pl.victor.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceMemoryTest {

    // Plac Defilad w Warszawie jako punkt odniesienia.
    private val here = PlaceMemory.Here(52.2317, 21.0060)

    private fun place(lat: Double, lon: Double, savedAtMs: Long = 0L) =
        PlaceMemory.Place("Samochód", lat, lon, savedAtMs)

    @Test
    fun `odleglosc zgadza sie z rzeczywistoscia`() {
        // Jeden stopień szerokości to circa 111,2 km - stała geograficzna,
        // więc to jest sprawdzenie wzoru, a nie przepisanie wyniku.
        val km = PlaceMemory.distanceMeters(here, place(53.2317, 21.0060)) / 1000
        assertTrue("wyszło $km km zamiast circa 111", km in 110.0..112.5)
    }

    @Test
    fun `sto metrow na polnoc to sto metrow na polnoc`() {
        // 0,0009 stopnia szerokości to circa 100 m.
        val north = place(52.2317 + 0.0009, 21.0060)
        val m = PlaceMemory.distanceMeters(here, north)
        assertTrue("wyszło $m m", m in 95.0..105.0)
        assertEquals("północ", PlaceMemory.bearingName(PlaceMemory.bearingDegrees(here, north)))
    }

    @Test
    fun `cztery strony swiata`() {
        assertEquals("północ", PlaceMemory.bearingName(0.0))
        assertEquals("wschód", PlaceMemory.bearingName(90.0))
        assertEquals("południe", PlaceMemory.bearingName(180.0))
        assertEquals("zachód", PlaceMemory.bearingName(270.0))
        assertEquals("północ", PlaceMemory.bearingName(359.9))
        assertEquals("północny wschód", PlaceMemory.bearingName(45.0))
    }

    @Test
    fun `blisko nie podajemy metrow`() {
        // Pięć metrów "na południowy zachód" jest gorsze niż "tuż obok":
        // przy takiej odległości błąd GPS jest większy niż sama odległość.
        val out = PlaceMemory.describe(place(52.23172, 21.00601), here, 0L)
        assertTrue(out, out.contains("tuż obok"))
    }

    @Test
    fun `odleglosc jest zaokraglona, nie udaje dokladnosci`() {
        val out = PlaceMemory.describe(place(52.2317 + 0.0009, 21.0060), here, 0L)
        // Ma wyjść okrągłe "100 metrów", a nie "97,3 metra".
        assertTrue(out, Regex("\\b\\d+0 metrów\\b").containsMatchIn(out))
    }

    @Test
    fun `powyzej kilometra mowimy kilometrami`() {
        val out = PlaceMemory.describe(place(52.2317 + 0.02, 21.0060), here, 0L)
        assertTrue(out, out.contains("km"))
        // Przecinek dziesiętny, nie kropka - "dwa kropka dwa kilometra" brzmi
        // jak odczyt z kalkulatora. Kropka na KOŃCU zdania jest w porządku,
        // więc sprawdzamy sam zapis liczby.
        assertTrue("oczekiwany przecinek dziesiętny: $out",
            Regex("\\d+,\\d km").containsMatchIn(out))
        assertTrue("kropka w liczbie: $out", !Regex("\\d\\.\\d").containsMatchIn(out))
    }

    @Test
    fun `polska odmiana po liczbie`() {
        // "5 minuty temu" słychać w wypowiedzi natychmiast.
        assertEquals("przed chwilą", PlaceMemory.ago(30_000))
        assertEquals("1 minutę temu", PlaceMemory.ago(60_000))
        assertEquals("3 minuty temu", PlaceMemory.ago(3 * 60_000L))
        assertEquals("5 minut temu", PlaceMemory.ago(5 * 60_000L))
        assertEquals("12 minut temu", PlaceMemory.ago(12 * 60_000L))
        assertEquals("22 minuty temu", PlaceMemory.ago(22 * 60_000L))
        assertEquals("1 godzinę temu", PlaceMemory.ago(60 * 60_000L))
        assertEquals("2 godziny temu", PlaceMemory.ago(2 * 60 * 60_000L))
        assertEquals("7 godzin temu", PlaceMemory.ago(7 * 60 * 60_000L))
        assertEquals("wczoraj", PlaceMemory.ago(30 * 60 * 60_000L))
    }

    @Test
    fun `cale zdanie sklada sie po polsku`() {
        val out = PlaceMemory.describe(
            place(52.2317 + 0.0009, 21.0060, savedAtMs = 0L),
            here,
            nowMs = 20 * 60_000L
        )
        assertEquals("Samochód: 100 metrów na północ. Zapisane 20 minut temu.", out)
    }
}
