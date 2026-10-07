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

class GuideCommandTest {
    @org.junit.Test
    fun `wlaczanie i wylaczanie przewodnika`() {
        org.junit.Assert.assertEquals(true, MetaCommands.guideCommand("włącz przewodnika"))
        org.junit.Assert.assertEquals(true, MetaCommands.guideCommand("Tryb przewodnika."))
        org.junit.Assert.assertEquals(true, MetaCommands.guideCommand("oprowadź mnie po okolicy"))
        org.junit.Assert.assertEquals(false, MetaCommands.guideCommand("wyłącz przewodnika"))
        org.junit.Assert.assertEquals(false, MetaCommands.guideCommand("koniec trybu przewodnika"))
        org.junit.Assert.assertNull(MetaCommands.guideCommand("kto był przewodnikiem tej wycieczki"))
    }
}

class MeetingCommandTest {
    @org.junit.Test
    fun `start i koniec spotkania`() {
        org.junit.Assert.assertEquals(true, MetaCommands.meetingCommand("nagrywaj spotkanie"))
        org.junit.Assert.assertEquals(true, MetaCommands.meetingCommand("Notatki ze spotkania."))
        // Bieg 160 - tak to zostało powiedziane i poszło do modelu.
        org.junit.Assert.assertEquals(true, MetaCommands.meetingCommand("notatka ze spotkania"))
        org.junit.Assert.assertEquals(true, MetaCommands.meetingCommand("zrób notatkę ze spotkania"))
        org.junit.Assert.assertEquals(true, MetaCommands.meetingCommand("zacznij nagrywać spotkanie"))
        org.junit.Assert.assertEquals(true, MetaCommands.meetingCommand("rób protokół"))
        org.junit.Assert.assertEquals(false, MetaCommands.meetingCommand("zakończ spotkanie"))
        org.junit.Assert.assertEquals(false, MetaCommands.meetingCommand("koniec nagrywania"))
        org.junit.Assert.assertNull(MetaCommands.meetingCommand("kiedy mam spotkanie"))
        org.junit.Assert.assertNull(MetaCommands.meetingCommand("przesuń spotkanie na jutro"))
    }
}

class TwoWayCommandTest {
    @org.junit.Test
    fun `rozmowa w dwie strony`() {
        org.junit.Assert.assertEquals("", MetaCommands.twoWayCommand("tłumacz rozmowę"))
        org.junit.Assert.assertEquals("en", MetaCommands.twoWayCommand("tłumacz rozmowę z angielskim"))
        org.junit.Assert.assertEquals("de", MetaCommands.twoWayCommand("pomóż mi rozmawiać po niemiecku"))
        org.junit.Assert.assertEquals("", MetaCommands.twoWayCommand("Tryb rozmowy."))
        org.junit.Assert.assertNull(MetaCommands.twoWayCommand("tłumaczenie na żywo"))
        org.junit.Assert.assertNull(MetaCommands.twoWayCommand("o czym była ta rozmowa"))
        org.junit.Assert.assertTrue(pl.victor.app.translation.TwoWay.czyMoje(pl.victor.app.translation.TwoWay.mojeZdanie("a")))
    }
}
