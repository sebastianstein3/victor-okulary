package pl.victor.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Napisy dla rozmówcy - co pokazać z historii panelu. */
class TwoWayPartnerViewTest {

    private fun rozmowa(vararg zdania: Pair<String, String>): EarTranscript =
        zdania.fold(EarTranscript().start("en", "pl")) { t, (o, p) -> t.zSegmentem(o, p, 0L) }

    @Test
    fun `pusta historia - nic do pokazania`() {
        val d = TwoWay.dlaRozmówcy(EarTranscript())
        assertNull(d.mójPrzekład)
        assertNull(d.jegoOryginał)
        assertNull(d.mójId)
    }

    @Test
    fun `ostatni moj przeklad i ostatnie zdanie rozmowcy`() {
        val t = rozmowa(
            "Where is the station?" to "Gdzie jest dworzec?",
            TwoWay.mojeZdanie("Prosto i w lewo") to "Straight ahead and left",
            "Thank you!" to "Dziękuję!",
            TwoWay.mojeZdanie("Nie ma za co") to "You're welcome",
            "Is it far?" to "Czy to daleko?"
        )
        val d = TwoWay.dlaRozmówcy(t)
        assertEquals("You're welcome", d.mójPrzekład)
        assertEquals("Nie ma za co", d.mojeZdanie)
        // Jego ORYGINAŁ (w jego języku), nie przekład na polski.
        assertEquals("Is it far?", d.jegoOryginał)
        assertEquals(t.segmenty[3].id, d.mójId)
    }

    @Test
    fun `tylko rozmowca mowil - bez mojego przekladu`() {
        val d = TwoWay.dlaRozmówcy(rozmowa("Hello" to "Cześć"))
        assertNull(d.mójPrzekład)
        assertEquals("Hello", d.jegoOryginał)
    }

    @Test
    fun `nowy przeklad zmienia id - widok wie, ze to nowe zdanie`() {
        val t1 = rozmowa(TwoWay.mojeZdanie("Tak") to "Yes")
        val t2 = t1.zSegmentem(TwoWay.mojeZdanie("Tak"), "Yes", 1L)
        assertNotEquals(TwoWay.dlaRozmówcy(t1).mójId, TwoWay.dlaRozmówcy(t2).mójId)
    }

    @Test
    fun `czcionka maleje z dlugoscia, ale zostaje duza`() {
        val krótka = TwoWay.czcionkaDlaRozmówcy("Thank you")
        val średnia = TwoWay.czcionkaDlaRozmówcy("a".repeat(100))
        val długa = TwoWay.czcionkaDlaRozmówcy("a".repeat(400))
        assertTrue(krótka > średnia && średnia > długa)
        assertTrue("czytelna z metra", długa >= 28)
        assertEquals(TwoWay.czcionkaDlaRozmówcy(""), TwoWay.czcionkaDlaRozmówcy(null))
    }
}
