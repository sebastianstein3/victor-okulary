package pl.victor.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fałszywy fakt jest gorszy niż jego brak: idzie do modelu przy KAŻDYM pytaniu
 * i będzie powtarzany pewnym głosem bez końca. Dlatego rozpoznawanie,
 * zastępowanie i usuwanie są sprawdzane testem.
 */
class UserFactsTest {

    @Test
    fun `zapamietaj zaczyna fakt`() {
        assertEquals("Mieszkam w Krakowie", UserFacts.extract("zapamiętaj, że mieszkam w Krakowie"))
        assertEquals("Mieszkam w Krakowie", UserFacts.extract("Zapamiętaj: mieszkam w Krakowie"))
        assertEquals("Nie jem laktozy", UserFacts.extract("zapamietaj ze nie jem laktozy"))
    }

    @Test
    fun `granica z notatkami jest ostra`() {
        assertNull(UserFacts.extract("zapisz, że mam kupić mleko"))
        assertNull(UserFacts.extract("notatka: kupić mleko"))
        assertNull(UserFacts.extract("dodaj kupić mleko"))
    }

    @Test
    fun `sam czasownik to nie fakt`() {
        assertNull(UserFacts.extract("zapamiętaj"))
        assertNull(UserFacts.extract("zapamiętaj "))
    }

    @Test
    fun `fakt jednoznaczny zastepuje poprzedni`() {
        val start = listOf(UserFacts.Fact("Mieszkam w Warszawie", 1L))
        val after = UserFacts.merge(start, UserFacts.Fact("Mieszkam w Krakowie", 2L))
        assertEquals(1, after.size)
        assertEquals("Mieszkam w Krakowie", after.first().text)
    }

    @Test
    fun `fakty listowe sie kumuluja`() {
        val start = listOf(UserFacts.Fact("Nie lubię herbaty", 1L))
        val after = UserFacts.merge(start, UserFacts.Fact("Nie lubię kawy", 2L))
        assertEquals(2, after.size)
    }

    @Test
    fun `powtorzony fakt sie nie dubluje`() {
        val start = listOf(UserFacts.Fact("Nie jem laktozy", 1L))
        val after = UserFacts.merge(start, UserFacts.Fact("Nie jem laktozy", 2L))
        assertEquals(1, after.size)
    }

    @Test
    fun `zapominanie dziala po fragmencie`() {
        val start = listOf(
            UserFacts.Fact("Mieszkam w Krakowie", 1L),
            UserFacts.Fact("Nie jem laktozy", 2L)
        )
        // "zapomnij o" jest wzorcem, więc do dopasowania zostaje sama treść.
        assertEquals("Krakowie", UserFacts.extractForget("zapomnij o Krakowie"))
        val after = UserFacts.forget(start, "krakowie")
        assertEquals(1, after.size)
        assertEquals("Nie jem laktozy", after.first().text)
    }

    @Test
    fun `zapomnij o tym nie kasuje wszystkiego`() {
        val start = listOf(
            UserFacts.Fact("W tym roku jadę do Hiszpanii", 1L),
            UserFacts.Fact("Mam psa, który boi się burzy", 2L)
        )
        assertEquals(2, UserFacts.forget(start, "tym").size)
        assertEquals(2, UserFacts.forget(start, "niej").size)
        // Fragment w środku słowa nie wystarcza ("psa" w "przepsa"), początek słowa tak.
        assertEquals(1, UserFacts.forget(start, "hiszpan").size)
        assertEquals(2, UserFacts.forget(start, "panii").size)
    }

    @Test
    fun `pytanie o pamiec jest rozpoznawane`() {
        assertTrue(UserFacts.isListRequest("co o mnie wiesz"))
        assertTrue(UserFacts.isListRequest("Co o mnie pamiętasz?"))
        assertFalse(UserFacts.isListRequest("zapamiętaj, że mieszkam w Krakowie"))
    }

    @Test
    fun `kontekst dla modelu ma fakty i zakaz zmyslania`() {
        val ctx = UserFacts.buildPromptContext(
            listOf(UserFacts.Fact("Mieszkam w Krakowie", 1L))
        )!!
        assertTrue(ctx.contains("Mieszkam w Krakowie"))
        assertTrue(ctx.contains("Nie wymyślaj"))
    }

    @Test
    fun `brak faktow to brak sekcji`() {
        assertNull(UserFacts.buildPromptContext(emptyList()))
        assertTrue(UserFacts.speak(emptyList()).contains("Nie zapamiętałem"))
    }
}
