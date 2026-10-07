package pl.victor.app.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesLogicTest {

    private fun msg(od: String, tekst: String, czas: Long = 1_000L, app: String = "WhatsApp") =
        IncomingMessage("k-$od", "com.whatsapp", app, od, tekst, czas, możnaOdpowiedzieć = true)

    @Test
    fun `ta sama wiadomosc nie jest oglaszana drugi raz`() {
        val inbox = InboxLogic()
        assertTrue(inbox.dodaj(msg("Ania", "będziesz na obiedzie?")))
        assertFalse(inbox.dodaj(msg("Ania", "będziesz na obiedzie?", czas = 5_000L)))
        assertTrue(inbox.dodaj(msg("Ania", "halo?", czas = 6_000L)))
        assertEquals(2, inbox.ostatnie().size)
    }

    @Test
    fun `adresat po imieniu w odmianie albo ostatni`() {
        val inbox = InboxLogic()
        inbox.dodaj(msg("Ania Kowalska", "a"))
        inbox.dodaj(msg("Tomek", "b", czas = 2_000L))
        assertEquals("Tomek", inbox.adresat(null)?.nadawca)
        assertEquals("Ania Kowalska", inbox.adresat("Ani")?.nadawca)
        assertEquals("Tomek", inbox.adresat("Tomkowi")?.nadawca)
        assertNull(inbox.adresat("Zbyszkowi"))
    }

    @Test
    fun `odpowiedz dyktowana doslownie`() {
        assertEquals(
            MessageReplyParser.Odpowiedź(null, "Będę za dziesięć minut"),
            MessageReplyParser.parse("odpowiedz jej, że będę za dziesięć minut")
        )
        assertEquals(
            MessageReplyParser.Odpowiedź("Ani", "Jasne, do zobaczenia"),
            MessageReplyParser.parse("Odpisz Ani: jasne, do zobaczenia.")
        )
        assertEquals("Ok", MessageReplyParser.parse("odpowiedz ok")?.treść)
    }

    @Test
    fun `odpowiedz do ulozenia idzie do modelu`() {
        assertNull(MessageReplyParser.parse("odpowiedz jej grzecznie, że nie dam rady"))
        assertNull(MessageReplyParser.parse("odpisz po angielsku że się spóźnię"))
        assertNull(MessageReplyParser.parse("jak odpowiedzieć na taką wiadomość"))
    }

    @Test
    fun `ogloszenie i sekcja dla modelu`() {
        val m = msg("Ania", "będziesz na obiedzie?")
        assertEquals("WhatsApp od Ania: będziesz na obiedzie?", MessagesPrompt.ogłoszenie(m, true))
        assertEquals("WhatsApp od Ania.", MessagesPrompt.ogłoszenie(m, false))
        val p = MessagesPrompt.dlaModelu(listOf(m))
        assertTrue(p.contains("reply_message"))
        assertTrue(p.contains("Ania"))
        assertTrue(MessagesPrompt.dotyczyWiadomości("odpowiedz Ani że się spóźnię"))
        assertFalse(MessagesPrompt.dotyczyWiadomości("jaka jest pogoda"))
    }

    @Test
    fun `odpisz bez tresci`() {
        assertEquals("inpostu", MessageReplyParser.prośbaBezTreści("podpisz na wiadomość od inpostu"))
        assertEquals("", MessageReplyParser.prośbaBezTreści("odpisz na tę wiadomość"))
        assertEquals(null, MessageReplyParser.parse("odpisz na wiadomość od Ani"))
        assertEquals(null, MessageReplyParser.prośbaBezTreści("odpisz Ani, że będę za 10 minut"))
    }
}
