package pl.victor.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kolejność decyzji w sesji tłumaczenia ze słuchu.
 *
 * Same reguły sprawdza [EarTranslationTest]. Tu chodzi o to, co z nich wynika
 * razem - a kolejność jest tu istotna i nieoczywista.
 */
class EarTranslationSessionTest {

    private var zegar = 100_000L
    private fun sesja() = EarTranslationSession { zegar }

    @Test
    fun `pierwsze zdanie idzie w calosci`() {
        val s = sesja()
        val d = s.rozstrzygnij("gdzie jest dworzec")
        assertTrue(d is EarTranslationSession.Decyzja.Tłumacz)
        assertEquals("gdzie jest dworzec", (d as EarTranslationSession.Decyzja.Tłumacz).fragment)
    }

    @Test
    fun `drugie zdanie oddaje sam ogon`() {
        val s = sesja()
        s.rozstrzygnij("gdzie jest dworzec")
        zegar += 2_000
        val d = s.rozstrzygnij("gdzie jest dworzec i jak daleko")
        assertEquals("i jak daleko", (d as EarTranslationSession.Decyzja.Tłumacz).fragment)
    }

    @Test
    fun `zdlawiony fragment nie przepada - wraca przy nastepnym nasluchu`() {
        val s = sesja()
        s.rozstrzygnij("raz")
        // 300 ms później - za wcześnie.
        zegar += 300
        assertTrue(s.rozstrzygnij("raz dwa") is EarTranslationSession.Decyzja.Pomiń)
        // Ogon liczy się dalej od "raz", więc "dwa" nie zginęło.
        zegar += 2_000
        val d = s.rozstrzygnij("raz dwa trzy")
        assertEquals("dwa trzy", (d as EarTranslationSession.Decyzja.Tłumacz).fragment)
    }

    @Test
    fun `wlasny glos nie przesuwa punktu odniesienia`() {
        // Gdyby echo zapisało się jako "ostatnie rozpoznanie", ogon liczyłby
        // się od NASZEJ wypowiedzi i uciąłby początek następnego zdania
        // rozmówcy. To jest powód, dla którego echo sprawdzamy PRZED ogonem.
        val s = sesja()
        s.rozstrzygnij("hello")
        s.zapamiętajWłasnąWypowiedź("cześć")
        zegar += 2_000
        assertTrue(s.rozstrzygnij("cześć") is EarTranslationSession.Decyzja.Pomiń)
        zegar += 2_000
        val d = s.rozstrzygnij("hello there")
        assertEquals("there", (d as EarTranslationSession.Decyzja.Tłumacz).fragment)
    }

    @Test
    fun `polecenie konca dziala takze w trakcie dlawienia`() {
        val s = sesja()
        s.rozstrzygnij("coś")
        // Bez odczekania - dławienie trwa.
        assertTrue(s.rozstrzygnij("koniec tłumaczenia") is EarTranslationSession.Decyzja.Koniec)
    }

    @Test
    fun `wyzerowanie zaczyna od czysta`() {
        val s = sesja()
        s.rozstrzygnij("gdzie jest dworzec")
        s.wyzeruj()
        zegar += 2_000
        val d = s.rozstrzygnij("gdzie jest dworzec")
        assertEquals("gdzie jest dworzec", (d as EarTranslationSession.Decyzja.Tłumacz).fragment)
    }
}
