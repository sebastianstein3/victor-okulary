package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Polecenia rozmowy na żywo - start w warstwie 0, koniec z transkrypcji w trakcie trybu. */
class LiveConversationCommandsTest {

    @Test
    fun `start - typowe wypowiedzi`() {
        listOf(
            "Porozmawiajmy",
            "porozmawiajmy.",
            "Pogadajmy sobie",
            "No to porozmawiajmy!",
            "Rozmowa na żywo",
            "Włącz rozmowę na żywo",
            "tryb rozmowy na żywo",
            "Porozmawiaj ze mną",
            "Możesz porozmawiać ze mną?",
            "pogadaj ze mną chwilę",
            "Chcę z tobą porozmawiać",
            "chcę pogadać"
        ).forEach { assertTrue(it, MetaCommands.startsLiveConversation(it)) }
    }

    @Test
    fun `start - pytania i inne tryby nie wlaczaja`() {
        listOf(
            "porozmawiajmy o pogodzie",
            "o czym porozmawiamy",
            "rozmowa z mamą była długa",
            "tryb rozmowy",
            "tłumaczenie na żywo",
            "nowa rozmowa",
            "koniec rozmowy"
        ).forEach { assertFalse(it, MetaCommands.startsLiveConversation(it)) }
    }

    @Test
    fun `start nie koliduje z tlumaczeniem rozmowy`() {
        // "tryb rozmowy" od dawna włącza tłumaczenie w dwie strony - tak zostaje.
        assertEquals("", MetaCommands.twoWayCommand("tryb rozmowy"))
        assertNull(MetaCommands.twoWayCommand("rozmowa na żywo"))
        assertNull(MetaCommands.twoWayCommand("porozmawiajmy"))
        assertFalse(MetaCommands.startsEarTranslation("rozmowa na żywo"))
        assertFalse(MetaCommands.startsEarTranslation("porozmawiajmy"))
        assertFalse(MetaCommands.detectContextReset("porozmawiajmy"))
    }

    @Test
    fun `koniec - na koncu wypowiedzi`() {
        listOf(
            "Koniec rozmowy",
            "koniec rozmowy.",
            "Zakończ rozmowę",
            "zakończmy rozmowę, dziękuję",
            "Dobra, dzięki, koniec rozmowy!",
            "kończymy rozmowę",
            "Skończmy tę rozmowę",
            "wyłącz rozmowę na żywo",
            "Do widzenia",
            "dzięki, do usłyszenia"
        ).forEach { assertTrue(it, MetaCommands.endsLiveConversation(it)) }
    }

    @Test
    fun `koniec - zaprzeczenie, srodek zdania i cytat nie koncza`() {
        listOf(
            "nie kończ rozmowy",
            "nie kończymy rozmowy",
            "koniec rozmowy był dziwny",
            "powiedział mi do widzenia i wyszedł",
            "rozmowa",
            "",
            "   "
        ).forEach { assertFalse(it, MetaCommands.endsLiveConversation(it)) }
    }
}
