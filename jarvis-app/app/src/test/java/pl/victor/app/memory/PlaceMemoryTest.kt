package pl.victor.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/** Rozpoznanie zdania - tekst przychodzi z rozpoznawania mowy, więc bywa bez ogonków. */
class PlaceMemoryPhrasesTest {

    @Test
    fun `zapamietanie parkingu`() {
        assertEquals(PlaceMemory.CAR, PlaceMemory.saveRequest("zapamiętaj, gdzie zaparkowałem"))
        assertEquals(PlaceMemory.CAR, PlaceMemory.saveRequest("zapamiętaj gdzie stoi samochód"))
        assertEquals(PlaceMemory.CAR, PlaceMemory.saveRequest("zapisz gdzie zaparkowałem auto"))
    }

    @Test
    fun `zapamietanie zwyklego miejsca`() {
        assertEquals(PlaceMemory.SPOT, PlaceMemory.saveRequest("zapamiętaj to miejsce"))
        assertEquals(PlaceMemory.SPOT, PlaceMemory.saveRequest("zapamiętaj gdzie jestem"))
    }

    @Test
    fun `pytanie o parking`() {
        assertEquals(PlaceMemory.CAR, PlaceMemory.recallRequest("gdzie zaparkowałem"))
        assertEquals(PlaceMemory.CAR, PlaceMemory.recallRequest("gdzie jest mój samochód"))
        assertEquals(PlaceMemory.CAR, PlaceMemory.recallRequest("zaprowadź mnie do samochodu"))
    }

    @Test
    fun `bez ogonkow tez dziala`() {
        // Rozpoznawanie mowy oddaje raz tak, raz tak.
        assertEquals(PlaceMemory.CAR, PlaceMemory.saveRequest("zapamietaj gdzie zaparkowalem"))
        assertEquals(PlaceMemory.CAR, PlaceMemory.recallRequest("gdzie zaparkowalem"))
    }

    @Test
    fun `zdanie w rozmowie to nie polecenie`() {
        // Bez wymagania słowa otwierającego NA POCZĄTKU to zdanie byłoby
        // pytaniem o parking - a jest wtrąceniem w rozmowie.
        assertNull(PlaceMemory.recallRequest("nie pamiętam, gdzie zaparkowałem, ale to nieważne"))
        assertNull(PlaceMemory.saveRequest("wczoraj zapamiętałem gdzie zaparkowałem"))
    }

    @Test
    fun `zapamietaj bez przedmiotu to nie jest prosba o miejsce`() {
        // "Zapamiętaj, że mam oddać książkę" to notatka, nie miejsce.
        assertNull(PlaceMemory.saveRequest("zapamiętaj, że mam oddać książkę"))
        assertNull(PlaceMemory.saveRequest("zapamiętaj mój numer telefonu"))
    }

    @Test
    fun `gdzie o czyms innym nie porywa parkingu`() {
        assertNull(PlaceMemory.recallRequest("gdzie jest najbliższa apteka"))
        assertNull(PlaceMemory.recallRequest("gdzie leży Bodrum"))
    }
}


/** Napis ze zdjęcia - to on ratuje sytuację tam, gdzie GPS nie działa. */
class PlaceSignTest {

    private val here = PlaceMemory.Here(52.2317, 21.0060)

    @Test
    fun `oznaczenie miejsca dochodzi do odpowiedzi`() {
        val place = PlaceMemory.Place(
            "Samochód", 52.2317 + 0.0009, 21.0060, 0L, signText = "POZIOM -2, SEKTOR B"
        )
        val out = PlaceMemory.describe(place, here, 20 * 60_000L)
        assertEquals(
            "Samochód: 100 metrów na północ. Zapisane 20 minut temu. " +
                "Na zdjęciu widniało: POZIOM -2, SEKTOR B.",
            out
        )
    }

    @Test
    fun `bez napisu zdanie zostaje takie jak bylo`() {
        val place = PlaceMemory.Place("Samochód", 52.2317 + 0.0009, 21.0060, 0L)
        assertEquals(
            "Samochód: 100 metrów na północ. Zapisane 20 minut temu.",
            PlaceMemory.describe(place, here, 20 * 60_000L)
        )
    }

    @Test
    fun `krotkie oznaczenia zostaja, regulamin odpada`() {
        val raw = """
            POZIOM -2
            SEKTOR B
            Parking płatny od poniedziałku do piątku w godzinach 8-18
            RZĄD 14
        """.trimIndent()
        assertEquals("POZIOM -2, SEKTOR B, RZĄD 14", PlaceMemory.tidySign(raw))
    }

    @Test
    fun `zwykly tekst bez cyfr i wersalikow nie jest oznaczeniem`() {
        assertNull(PlaceMemory.tidySign("wjazd\nwyjazd\nkasa"))
    }

    @Test
    fun `powtorzenia znikaja`() {
        assertEquals("P3", PlaceMemory.tidySign("P3\nP3\nP3"))
    }

    @Test
    fun `bierzemy najwyzej trzy oznaczenia`() {
        val raw = (1..10).joinToString("\n") { "SEKTOR $it" }
        assertEquals("SEKTOR 1, SEKTOR 2, SEKTOR 3", PlaceMemory.tidySign(raw))
    }

    @Test
    fun `pusty odczyt to brak napisu, nie pusty napis`() {
        assertNull(PlaceMemory.tidySign(null))
        assertNull(PlaceMemory.tidySign("   "))
    }
}
