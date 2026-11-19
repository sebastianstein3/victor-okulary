package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSoundMatchTest {

    private val kontakty = listOf(1L to "Sebastian Steinke", 2L to "Sebastian Nowak", 3L to "Jacek Kowalski", 4L to "Janek Wiśniewski")

    @Test
    fun `nazwisko przekrecone przez transkrypcje trafia po brzmieniu`() {
        // Bieg 183: tak przychodziło "Steinke" z rozpoznawania mowy.
        listOf("Sebastian Sztainke", "Sebastian Sztejnke", "sebastian steinke", "Sebastiana Sztainke")
            .forEach { assertEquals(it, 1L, ContactMatch.best(it, kontakty)) }
    }

    @Test
    fun `inne nazwisko nie pasuje i krotkie imiona sie nie mieszaja`() {
        assertNull(ContactMatch.best("Sebastian Stalmach", kontakty))
        assertFalse(ContactMatch.brzmiPodobnie("jacek", "janek"))
        assertEquals(3L, ContactMatch.best("Jacek", kontakty))
    }

    @Test
    fun `brzmienie`() {
        assertTrue(ContactMatch.brzmiPodobnie("wiktor", "victor"))
        assertTrue(ContactMatch.brzmiPodobnie("hoffmann", "hofman"))
        assertTrue(ContactMatch.brzmiPodobnie("sztainke", "steinke"))
    }

    @Test
    fun `kandydaci dla modelu`() {
        val nazwy = kontakty.map { it.second }
        assertEquals(listOf("Sebastian Steinke", "Sebastian Nowak"), ContactMatch.podobne("zadzwoń do Sebastian Sztainke", nazwy))
        assertTrue(ContactMatch.podobne("zadzwoń do mamy", nazwy).isEmpty())
    }
}
