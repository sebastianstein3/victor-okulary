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

    // === Komunikacja miejska ===
    // Prowadzenie Map w trybie transit też trzyma powiadomienie - ale zamiast
    // odległości są w nim przystanki, linie i godziny. Parser nie może wziąć
    // "2 przystanki" ani "5 min" za metry.

    @Test
    fun `powiadomienia transit nie sa odlegloscia`() {
        val wysiadka = stan("Wysiądź za 2 przystanki", "Rondo Mogilskie")!!
        assertNull(wysiadka.dystansM)
        assertEquals("Wysiądź za 2 przystanki Rondo Mogilskie", wysiadka.manewr)

        val autobus = stan("Autobus 175 o 12:40", "Kierunek: Lotnisko", "25 min · 13:05")!!
        assertNull(autobus.dystansM)
        assertEquals("Autobus 175 o 12:40 Kierunek: Lotnisko", autobus.manewr)

        // Liczby z jednostką czasu nie są metrami - "m" z "min" nie wchodzi.
        assertNull(stan("Za 10 minut odjazd", "Tramwaj 4")!!.dystansM)
        assertNull(stan("2 przystanki", "Wysiądź na przystanku Teatr Bagatela")!!.dystansM)
        assertNull(stan("4 · Kurdwanów", null)!!.dystansM)
        assertNull(stan("Odjazd za 3 min", "Autobus 129")!!.dystansM)

        // Dojście pieszo na przystanek ma prawdziwą odległość.
        val dojście = stan("250 m", "Idź do przystanku Lubicz")!!
        assertEquals(250, dojście.dystansM)
        assertEquals("Idź do przystanku Lubicz", dojście.manewr)
    }

    @Test
    fun `odliczanie minut do odjazdu nie jest nowym manewrem`() {
        val z = NavZapowiedzi()
        fun s(m: String) = MapsNavigation.Stan(m, null, null, 0L)
        assertEquals("Autobus 175 za 5 min.", z.decyzja(s("Autobus 175 za 5 min")))
        assertNull(z.decyzja(s("Autobus 175 za 4 min")))
        assertNull(z.decyzja(s("Autobus 175 za 3 min")))
        // Przystanki do wysiadki - to warto usłyszeć za każdym razem.
        assertEquals("Wysiądź za 2 przystanki.", z.decyzja(s("Wysiądź za 2 przystanki")))
        assertEquals("Wysiądź za 1 przystanek.", z.decyzja(s("Wysiądź za 1 przystanek")))
        assertEquals(NavZapowiedzi.kluczManewru("Odjazd o 12:40"), NavZapowiedzi.kluczManewru("Odjazd o 12:41"))
    }

    @Test
    fun `pytania o wysiadke w trakcie jazdy`() {
        val s = MapsNavigation.Stan("Wysiądź za 2 przystanki", null, "12 min · 13:05", 0L)
        assertEquals("Wysiądź za 2 przystanki.", NavPytania.odpowiedź("ile jeszcze przystanków?", s))
        assertEquals("Wysiądź za 2 przystanki.", NavPytania.odpowiedź("kiedy wysiadam", s))
        assertEquals("Wysiądź za 2 przystanki.", NavPytania.odpowiedź("gdzie mam się przesiąść", s))
        // "Ile jeszcze" bez przystanków to dalej pytanie o czas.
        assertEquals("Do celu: 12 min, przyjazd o 13:05.", NavPytania.odpowiedź("ile jeszcze", s))
        // Pytanie o przystanek w ogóle (nie o wysiadkę) idzie do modelu.
        assertNull(NavPytania.odpowiedź("gdzie jest najbliższy przystanek", s))
    }
}
