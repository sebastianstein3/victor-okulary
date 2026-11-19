package pl.victor.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsNavigationTest {

    private fun stan(tytuł: String?, tekst: String?, pod: String? = null) =
        MapsNotificationParser.rozbierz(tytuł, tekst, pod, 0L)

    @Test
    fun `odleglosc w tytule manewr w tekscie`() {
        val s = stan("300 m", "Skręć w prawo w ul. Długą", "12 min · 900 m · 12:45")!!
        assertEquals(300, s.dystansM)
        assertEquals("Skręć w prawo w ul. Długą", s.manewr)
        assertEquals("12 min, 900 m, przyjazd o 12:45", NavMowa.doCelu(s.doCelu!!))
    }

    @Test
    fun `manewr w tytule z odlegloscia na poczatku`() {
        val s = stan("Za 1,2 km skręć w lewo", "w kierunku Krakowa")!!
        assertEquals(1200, s.dystansM)
        assertEquals("Skręć w lewo w kierunku Krakowa", s.manewr)
        val e = stan("200 ft", "Turn left onto Main St")!!
        assertEquals(60, e.dystansM)
    }

    @Test
    fun `bez odleglosci i powiadomienia tla`() {
        val s = stan("Wyznaczanie nowej trasy…", null)!!
        assertNull(s.dystansM)
        assertNull(stan("Mapy Google", "Działa w tle"))
        assertNull(stan(null, "  "))
        // "5 min" to nie odległość
        assertNull(stan("5 min", null)?.dystansM)
    }

    @Test
    fun `mowimy przy nowym manewrze i raz tuz przed nim`() {
        val z = NavZapowiedzi()
        fun s(m: Int?, manewr: String = "Skręć w prawo w ul. Długą") =
            MapsNavigation.Stan(manewr, m, null, 0L)
        assertEquals("Za 300 metrów skręć w prawo w ul. Długą.", z.decyzja(s(300)))
        assertNull(z.decyzja(s(250)))
        assertNull(z.decyzja(s(120)))
        assertEquals("Teraz skręć w prawo w ul. Długą.", z.decyzja(s(30)))
        assertNull(z.decyzja(s(10)))
        // Nowy manewr tuż obok - od razu "teraz".
        assertEquals("Teraz skręć w lewo.", z.decyzja(s(20, "Skręć w lewo")))
        assertEquals("Za 1,5 kilometra jedź prosto.", z.decyzja(s(1500, "Jedź prosto")))
    }

    @Test
    fun `odmiana odleglosci`() {
        assertEquals("1 metr", NavMowa.odległość(1))
        assertEquals("22 metry", NavMowa.odległość(22))
        assertEquals("12 metrów", NavMowa.odległość(12))
        assertEquals("150 metrów", NavMowa.odległość(147))
        assertEquals("2 kilometry", NavMowa.odległość(2000))
        assertEquals("5 kilometrów", NavMowa.odległość(5000))
    }

    @Test
    fun `pytania o trase tylko gdy prowadzimy`() {
        val s = MapsNavigation.Stan("Skręć w prawo", 300, "12 min · 900 m · 12:45", 0L)
        assertEquals("Do celu: 12 min, 900 m, przyjazd o 12:45.", NavPytania.odpowiedź("Ile jeszcze?", s))
        assertEquals("Za 300 metrów skręć w prawo.", NavPytania.odpowiedź("gdzie skręcam", s))
        assertNull(NavPytania.odpowiedź("ile jeszcze", null))
        assertNull(NavPytania.odpowiedź("jaka jest pogoda", s))
    }

    @Test
    fun `kontekst dla modelu`() {
        MapsNavigation.przyjmij("k", MapsNavigation.Stan("Skręć w prawo", 300, "12 min", System.currentTimeMillis()))
        val k = MapsNavigation.kontekstDlaModelu()!!
        assertTrue(k.contains("NAWIGACJA") && k.contains("Za 300 metrów"))
        MapsNavigation.usunięte("inny")
        assertTrue(MapsNavigation.kontekstDlaModelu() != null)
        MapsNavigation.usunięte("k")
        assertNull(MapsNavigation.kontekstDlaModelu())
    }
}
