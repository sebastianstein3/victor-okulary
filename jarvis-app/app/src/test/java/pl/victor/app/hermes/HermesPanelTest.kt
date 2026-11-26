package pl.victor.app.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesPanelTest {

    private fun store(limit: Int = 200, znaki: Int = 20_000) =
        HermesPanelStore(teraz = { 1000L }, limitWpisów = limit, limitZnaków = znaki)

    @Test
    fun `rozmowa zaczyna sie od wypowiedzi i trwa`() {
        val s = store()
        s.rozpocznij("  znajdź fakturę ", głosem = true)
        val w = s.wpisy.value.single() as HermesWpis.Użytkownik
        assertEquals("znajdź fakturę", w.tekst)
        assertTrue(w.głosem)
        assertTrue(s.trwa.value)
    }

    @Test
    fun `kawalki odpowiedzi skladaja sie w jeden dymek`() {
        val s = store()
        s.rozpocznij("cześć", false)
        s.tekst("Mam ")
        s.tekst("dla ciebie ")
        s.tekst("wynik.")
        val odp = s.wpisy.value.filterIsInstance<HermesWpis.Hermes>().single()
        assertEquals("Mam dla ciebie wynik.", odp.tekst)
        assertTrue(odp.trwa)
    }

    @Test
    fun `tekst po narzedziu zaczyna nowy dymek`() {
        val s = store()
        s.rozpocznij("zrób", false)
        s.tekst("Szukam.")
        s.narzędzie("czytam plik", zakończone = false)
        s.narzędzie("czytam plik", zakończone = true)
        s.tekst("Znalazłem.")
        val dymki = s.wpisy.value.filterIsInstance<HermesWpis.Hermes>()
        assertEquals(listOf("Szukam.", "Znalazłem."), dymki.map { it.tekst })
        // Pierwszy dymek jest już skończony, drugi jeszcze się dopisuje.
        assertFalse(dymki[0].trwa)
        assertTrue(dymki[1].trwa)
        val narz = s.wpisy.value.filterIsInstance<HermesWpis.Narzędzie>().single()
        assertTrue(narz.zakończone)
    }

    @Test
    fun `koniec narzedzia domyka ostatnie takie samo, nie pierwsze`() {
        val s = store()
        s.rozpocznij("x", false)
        s.narzędzie("ls", false)
        s.narzędzie("ls", false)
        s.narzędzie("ls", true)
        val n = s.wpisy.value.filterIsInstance<HermesWpis.Narzędzie>()
        assertEquals(listOf(false, true), n.map { it.zakończone })
    }

    @Test
    fun `ta sama zgoda nie dopisuje sie dwa razy`() {
        val s = store()
        s.rozpocznij("x", false)
        s.zgoda("usunąć plik", "req-1")
        s.zgoda("usunąć plik", "req-1")
        s.zgoda("inna operacja", "req-2")
        assertEquals(2, s.wpisy.value.filterIsInstance<HermesWpis.Zgoda>().size)
    }

    @Test
    fun `decyzja rozstrzyga oczekujaca zgode`() {
        val s = store()
        s.rozpocznij("x", false)
        s.zgoda("usunąć plik", "req-1")
        s.rozstrzygnijZgodę(zezwól = true)
        val z = s.wpisy.value.filterIsInstance<HermesWpis.Zgoda>().single()
        assertEquals(StatusZgody.ZEZWOLONO, z.status)
        // Druga decyzja nie ma czego rozstrzygać i niczego nie zmienia.
        s.rozstrzygnijZgodę(zezwól = false)
        assertEquals(StatusZgody.ZEZWOLONO, s.wpisy.value.filterIsInstance<HermesWpis.Zgoda>().single().status)
    }

    @Test
    fun `koniec zadania wygasza wisząca zgode i domyka wszystko`() {
        val s = store()
        s.rozpocznij("x", false)
        s.tekst("Pracuję")
        s.narzędzie("rm", false)
        s.zgoda("usunąć", "r")
        s.zakończ()
        assertFalse(s.trwa.value)
        val w = s.wpisy.value
        assertFalse((w.filterIsInstance<HermesWpis.Hermes>().single()).trwa)
        assertTrue((w.filterIsInstance<HermesWpis.Narzędzie>().single()).zakończone)
        assertEquals(StatusZgody.WYGASŁA, (w.filterIsInstance<HermesWpis.Zgoda>().single()).status)
    }

    @Test
    fun `blad dopisuje sie jako informacja`() {
        val s = store()
        s.rozpocznij("x", false)
        s.zakończ("Brak połączenia z Hermesem.")
        val info = s.wpisy.value.last() as HermesWpis.Informacja
        assertTrue(info.błąd)
        assertEquals("Brak połączenia z Hermesem.", info.tekst)
        assertFalse(s.trwa.value)
    }

    @Test
    fun `limit wierszy zostawia najnowsze`() {
        val s = store(limit = 5)
        repeat(10) { s.informacja("wpis $it") }
        val teksty = s.wpisy.value.map { (it as HermesWpis.Informacja).tekst }
        assertEquals(listOf("wpis 5", "wpis 6", "wpis 7", "wpis 8", "wpis 9"), teksty)
    }

    @Test
    fun `odpowiedz ma limit znakow`() {
        val s = store(znaki = 10)
        s.rozpocznij("x", false)
        s.tekst("0123456789")
        s.tekst("abc")
        val t = s.wpisy.value.filterIsInstance<HermesWpis.Hermes>().single().tekst
        assertTrue(t.length <= 11)
        assertTrue(t.startsWith("0123456789"))
    }

    @Test
    fun `wyczysc w trakcie zadania zostawia ostatnie pytanie z odpowiedzia`() {
        val s = store()
        s.rozpocznij("pierwsze", false)
        s.tekst("odp 1")
        s.zakończ()
        s.rozpocznij("drugie", false)
        s.tekst("odp 2")
        s.wyczyść()
        val w = s.wpisy.value
        assertEquals(2, w.size)
        assertEquals("drugie", (w[0] as HermesWpis.Użytkownik).tekst)
        // Po zakończeniu czyści do cna.
        s.zakończ()
        s.wyczyść()
        assertTrue(s.wpisy.value.isEmpty())
    }

    @Test
    fun `jako tekst do skopiowania`() {
        val s = store()
        s.rozpocznij("cześć", false)
        s.tekst("Witaj")
        s.zakończ()
        assertEquals("Ja: cześć\n\nHermes: Witaj", s.jakoTekst())
    }
}
