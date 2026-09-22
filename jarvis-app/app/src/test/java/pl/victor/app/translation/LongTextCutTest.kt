package pl.victor.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Przycięcie ma być widoczne, a nie milczące.
 *
 * Stało tu gołe `take(1000)`: urwanie w połowie zdania bez żadnego śladu.
 * Tłumaczenie wyglądało na kompletne i po prostu kończyło się w środku.
 */
class LongTextCutTest {

    @Test
    fun `krotki tekst przechodzi bez zmian i bez znacznika`() {
        val w = LongTextCut.przytnij("Krótkie zdanie.")
        assertEquals("Krótkie zdanie.", w.tekst)
        assertFalse(w.przycięte)
    }

    @Test
    fun `dlugi tekst zostaje oznaczony jako przyciety`() {
        val w = LongTextCut.przytnij("a".repeat(5_000))
        assertTrue(w.przycięte)
        assertTrue(w.tekst.length <= LongTextCut.MAX_ZNAKÓW)
    }

    @Test
    fun `ciecie idzie po granicy zdania`() {
        val tekst = "Pierwsze zdanie. Drugie zdanie. Trzecie zdanie, które już się nie zmieści."
        val w = LongTextCut.przytnij(tekst, limit = 40)
        assertEquals("Pierwsze zdanie. Drugie zdanie.", w.tekst)
        assertTrue(w.przycięte)
    }

    @Test
    fun `bez interpunkcji tnie po slowie, nie w polowie wyrazu`() {
        val w = LongTextCut.przytnij("alfa beta gamma delta epsilon", limit = 22)
        assertFalse("urwane słowo trafiłoby do tłumacza", w.tekst.endsWith("del"))
        assertEquals("alfa beta gamma delta", w.tekst)
    }

    @Test
    fun `wczesna kropka nie obcina tekstu do kilku znakow`() {
        // Menu, lista albo tabela zaczynają się często krótkim wpisem z kropką.
        // Cięcie po OSTATNIEJ kropce bez progu zostawiłoby z całej strony
        // pierwsze trzy słowa - czyli dokładnie ten błąd, który naprawiamy.
        val tekst = "Zupa. " + "składnik ".repeat(50)
        val w = LongTextCut.przytnij(tekst, limit = 100)
        assertTrue("zostało tylko ${w.tekst.length} znaków", w.tekst.length > 50)
    }

    @Test
    fun `limit jest ten sam, co przy opisie OCR - dwa rozne bylyby pomylka`() {
        assertEquals(3_000, LongTextCut.MAX_ZNAKÓW)
    }

    @Test
    fun `limit zero nie wywala sie`() {
        val w = LongTextCut.przytnij("cokolwiek", limit = 0)
        assertEquals("", w.tekst)
        assertTrue(w.przycięte)
    }
}
