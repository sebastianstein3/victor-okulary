package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy dopasowania kontaktów.
 *
 * Wynikiem tego kodu jest telefon albo SMS do konkretnej osoby, więc każdy
 * przypadek "pomyliło kogoś z kimś" jest tu ważniejszy niż "nie znalazło".
 */
class ContactMatchTest {

    // === To, co było zepsute ===

    @Test
    fun `prosba o Janusza nie trafia w kontakt Jan`() {
        // Poprzednia wersja spełniała tu warunek query.contains(displayName)
        // i dzwoniła do Jana. To jest cała przyczyna istnienia tej klasy.
        assertEquals(ContactMatch.NO_MATCH, ContactMatch.score("janusz", "Jan"))
    }

    @Test
    fun `polskie imie z ogonkami daje sie znalezc`() {
        // Stary kod normalizował zapytanie, a potem szukał nim w bazie przez
        // SQL LIKE - a kolumna z nazwami ogonki ma. Żaden kontakt z polskim
        // imieniem nie dawał się znaleźć.
        assertTrue(ContactMatch.score("lukasz", "Łukasz Nowak") > ContactMatch.NO_MATCH)
        assertTrue(ContactMatch.score("Łukasz", "lukasz nowak") > ContactMatch.NO_MATCH)
        assertTrue(ContactMatch.score("zosia", "Zośka") == ContactMatch.NO_MATCH)
        assertTrue(ContactMatch.score("agnieszka", "Agnieszka Wójcik") > ContactMatch.NO_MATCH)
    }

    @Test
    fun `wybiera najlepszego, nie pierwszego z brzegu`() {
        val kontakty = listOf(
            1L to "Anna Kowalska",
            2L to "Ania",
            3L to "Aniela Nowak"
        )
        // "ania" jest DOKŁADNIE nazwą kontaktu 2 - to bije prefiksy.
        assertEquals(2L, ContactMatch.best("ania", kontakty))
    }

    // === Zwykłe przypadki ===

    @Test
    fun `imie i nazwisko w dowolnej kolejnosci`() {
        assertEquals(100, ContactMatch.score("ania nowak", "Ania Nowak"))
        assertEquals(90, ContactMatch.score("nowak ania", "Ania Nowak"))
    }

    @Test
    fun `samo imie pasuje do imienia z nazwiskiem`() {
        // Jedno słowo też jest kompletem słów, więc łapie je ta sama reguła
        // co "imię i nazwisko w dowolnej kolejności".
        assertEquals(90, ContactMatch.score("ania", "Ania Nowak"))
    }

    @Test
    fun `skrocenie imienia pasuje od trzech liter`() {
        assertTrue(ContactMatch.score("agni", "Agnieszka Wójcik") > ContactMatch.NO_MATCH)
        assertEquals(ContactMatch.NO_MATCH, ContactMatch.score("ag", "Agnieszka Wójcik"))
    }

    @Test
    fun `odmiana przez przypadki dziala przy dluzszym imieniu`() {
        // "powiedz Łukaszowi" - nazwa kontaktu jest POCZĄTKIEM zapytania.
        assertTrue(ContactMatch.score("lukaszowi", "Łukasz") > ContactMatch.NO_MATCH)
        // ...ale przy krótkim imieniu ta sama reguła myliłaby ludzi, więc nie.
        assertEquals(ContactMatch.NO_MATCH, ContactMatch.score("marka", "Mar"))
    }

    // === Odmowy ===

    @Test
    fun `remis miedzy dwiema osobami konczy sie odmowa`() {
        // Dwie Anie. Zgadywanie, o którą chodzi, kończy się rozmową z obcą
        // osobą - lepiej powiedzieć "nie znalazłem".
        val kontakty = listOf(1L to "Ania", 2L to "ANIA")
        assertNull(ContactMatch.best("ania", kontakty))
    }

    @Test
    fun `ten sam kontakt w kilku wierszach to nie remis`() {
        // Jeden człowiek, dwa numery - to nie jest niejednoznaczność.
        val kontakty = listOf(7L to "Ania Nowak", 7L to "Ania Nowak")
        assertEquals(7L, ContactMatch.best("ania nowak", kontakty))
    }

    @Test
    fun `pusta nazwa nigdy nie pasuje`() {
        assertEquals(ContactMatch.NO_MATCH, ContactMatch.score("", "Ania"))
        assertEquals(ContactMatch.NO_MATCH, ContactMatch.score("ania", "   "))
        assertNull(ContactMatch.best("ania", emptyList<Pair<Long, String>>()))
    }

    @Test
    fun `nic nie pasuje to null`() {
        assertNull(ContactMatch.best("bartosz", listOf(1L to "Ania Nowak")))
    }
}
