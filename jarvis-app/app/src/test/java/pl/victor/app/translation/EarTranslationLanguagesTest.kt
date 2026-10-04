package pl.victor.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.conversation.MetaCommands

/**
 * Dziennik z biegu 153: "tłumacz z polskiego na angielski" poszło do modelu
 * ("podaj mi tekst po polsku") zamiast włączyć tryb z tymi językami.
 */
class EarTranslationLanguagesTest {

    @Test
    fun `zdanie z dziennika wlacza tryb z jezykami z komendy`() {
        val tekst = "tłumacz z polskiego na angielski"
        assertTrue(MetaCommands.startsEarTranslation(tekst))
        assertEquals("pl" to "en", MetaCommands.earTranslationLanguages(tekst))
    }

    @Test
    fun `odmiany i grzecznosci`() {
        assertEquals("en" to "pl", MetaCommands.earTranslationLanguages("Tłumaczenie na żywo z angielskiego na polski."))
        assertEquals("de" to "pl", MetaCommands.earTranslationLanguages("włącz tłumacza z niemieckiego na polski"))
        assertEquals("pl" to "uk", MetaCommands.earTranslationLanguages("a możesz tłumacz z polskiego na ukraiński proszę"))
        assertEquals("es" to "pl", MetaCommands.earTranslationLanguages("tłumacz z hiszpańskiego na polski na żywo"))
    }

    @Test
    fun `jednorazowe tlumaczenie i nieznany jezyk zostaja dla modelu`() {
        assertNull(MetaCommands.earTranslationLanguages("przetłumacz dzień dobry na angielski"))
        assertFalse(MetaCommands.startsEarTranslation("przetłumacz z polskiego na angielski słowo kot"))
        assertNull(MetaCommands.earTranslationLanguages("tłumacz z elfickiego na polski"))
        assertNull(MetaCommands.earTranslationLanguages("tłumacz z polskiego na polski"))
    }

    @Test
    fun `stara komenda bez jezykow dziala jak dotad`() {
        assertTrue(MetaCommands.startsEarTranslation("tłumaczenie na żywo"))
        assertNull(MetaCommands.earTranslationLanguages("tłumaczenie na żywo"))
    }
}
