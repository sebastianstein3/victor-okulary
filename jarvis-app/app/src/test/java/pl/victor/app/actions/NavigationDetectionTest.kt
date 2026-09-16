package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trasa musi dać się rozpoznać BEZ modelu.
 *
 * Zgłoszenie z terenu (dziennik z 15 września, 20:52:01): na "nawiguj do
 * najbliższej biedronki" model zapowiedział włączenie nawigacji i nie uruchomił
 * niczego - w logu nie ma żadnej akcji. Te testy pilnują warstwy 0, która teraz
 * łapie to zdanie, zanim w ogóle dojdzie do modelu.
 */
class NavigationDetectionTest {

    private val detector = SmartActionDetector()

    @Test
    fun `zdanie z dziennika daje trase`() {
        val route = detector.detectNavigation("nawiguj do najbliższej biedronki")
        assertNotNull("to zdanie poszło do modelu zamiast uruchomić trasę", route)
        // "najbliższej" jest zdejmowane celowo: Mapy i tak szukają najbliższego
        // wyniku od bieżącej pozycji, a słowo w zapytaniu tylko psuje trafienie.
        assertEquals("biedronki", route!!.destination)
    }

    @Test
    fun `nawiguj znaczy samochodem`() {
        // Zgłoszone wprost: "ma nawigować jakby pieszo a nie autem".
        assertTrue(detector.detectNavigation("nawiguj do krakowa")!!.byCar)
    }

    @Test
    fun `jedz tez znaczy samochodem`() {
        assertTrue(detector.detectNavigation("jedź do krakowa")!!.byCar)
    }

    @Test
    fun `prowadz zostaje pieszo`() {
        // Tego zwrotu używa osoba niewidoma, dla której ta aplikacja powstała,
        // i idzie ona na własnych nogach. Zmiana trybu przy "nawiguj" nie może
        // zabrać jej trasy pieszej.
        assertFalse(detector.detectNavigation("prowadź do apteki")!!.byCar)
    }

    @Test
    fun `asystent wyciety spomiedzy czasownika a celu`() {
        val route = detector.detectNavigation("prowadź bez asystenta do apteki")
        assertNotNull(route)
        assertEquals("apteki", route!!.destination)
    }

    @Test
    fun `rozmowa o drodze to nie prosba o trase`() {
        // Warunek ścisłości warstwy 0: gdyby łapała wszystko ze słowem "do",
        // przechwytywałaby zwykłe pytania i nikt by się do modelu nie dodzwonił.
        assertNull(detector.detectNavigation("ile jest kilometrów do krakowa"))
        assertNull(detector.detectNavigation("czy daleko do apteki"))
        assertNull(detector.detectNavigation("opowiedz mi o drodze do rzymu"))
    }

    @Test
    fun `sam czasownik bez celu nie jest trasa`() {
        assertNull(detector.detectNavigation("nawiguj"))
        assertNull(detector.detectNavigation("prowadź do"))
    }
}

/** Komunikacja miejska - inna droga niż samochód i pieszo. */
class TransitDetectionTest {

    private val detector = SmartActionDetector()

    private fun route(text: String) = detector.detectNavigation(text)

    @Test
    fun `nazwa pojazdu wlacza komunikacje miejska`() {
        // Nikt nie mówi "transportem publicznym" - mówi nazwą pojazdu.
        assertTrue(route("jedź autobusem do dworca")!!.byTransit)
        assertTrue(route("nawiguj tramwajem do ratusza")!!.byTransit)
        assertTrue(route("prowadź metrem do centrum")!!.byTransit)
        assertTrue(route("jedź pociągiem do Krakowa")!!.byTransit)
    }

    @Test
    fun `komunikacja wyklucza samochod`() {
        // Bez tego "jedź autobusem" byłoby JEDNOCZEŚNIE trasą samochodową -
        // czasownik jest przecież ten sam.
        val r = route("jedź autobusem do dworca")!!
        assertTrue(r.byTransit)
        assertFalse("trasa nie może być naraz autem i autobusem", r.byCar)
    }

    @Test
    fun `bez nazwy pojazdu nic sie nie zmienia`() {
        assertFalse(route("jedź do Krakowa")!!.byTransit)
        assertTrue(route("jedź do Krakowa")!!.byCar)
        assertFalse(route("prowadź do apteki")!!.byTransit)
        assertFalse(route("prowadź do apteki")!!.byCar)
    }

    @Test
    fun `cel nie wciaga nazwy pojazdu`() {
        assertEquals("dworca", route("jedź autobusem do dworca")!!.destination)
    }
}
