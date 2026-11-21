package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.messages.MessageReplyParser

/**
 * Mail po pytaniu „Wysłać?” można jeszcze poprawiać. „Popraw, napisz że nie
 * przyjdę” zawiera „nie” - bez rozpoznania poprawki PRZED tak/nie szkic
 * zostałby skasowany jako odmowa.
 */
class EmailVoiceTest {

    @Test
    fun `poprawki rozpoznane`() {
        listOf(
            "popraw, napisz że będę o piątej",
            "Zmień temat na urlop",
            "dopisz, że przyniosę dokumenty",
            "skróć to",
            "napisz raczej, że nie dam rady",
            "bardziej formalnie"
        ).forEach { assertNotNull(it, EmailVoice.correction(it)) }
    }

    @Test
    fun `poprawka z nie to nadal poprawka a nie odmowa`() {
        val t = "popraw, napisz że nie przyjdę"
        assertEquals(t, EmailVoice.correction(t))
        // Dla porównania - sam ConfirmationReply wziąłby to za "nie".
        assertEquals(ConfirmationReply.Reply.NO, ConfirmationReply.parse(t))
    }

    @Test
    fun `tak nie i sam czasownik to nie poprawki`() {
        listOf("tak", "wyślij", "nie", "anuluj", "popraw", "", null, "dobra wyślij")
            .forEach { assertNull(it.toString(), EmailVoice.correction(it)) }
    }

    @Test
    fun `pytanie czyta adresata temat i tresc a wyslac na koncu`() {
        val s = EmailVoice.confirmationSpeech("Ania Kowalska", "Re: Plan", "Będę o piątej", isReply = true)
        assertTrue(s.startsWith("Odpowiedź do: Ania Kowalska."))
        assertTrue(s.indexOf("Temat: Re: Plan") < s.indexOf("Treść: Będę o piątej"))
        assertTrue(s.endsWith("Wysłać?"))
        assertTrue(EmailVoice.confirmationSpeech("Tomek", "", "Hej.", false).startsWith("Mail do: Tomek. Treść: Hej."))
    }

    @Test
    fun `okienko pokazuje adres obok nazwy`() {
        val m = EmailVoice.confirmationMessage("Ania", "ania@x.pl", "Plan", "Treść")
        assertTrue(m.startsWith("Do: Ania <ania@x.pl>"))
        assertTrue(EmailVoice.confirmationMessage("a@x.pl", "a@x.pl", "", "t").startsWith("Do: a@x.pl\n"))
    }

    @Test
    fun `rozbiera poprawiony szkic`() {
        assertEquals(
            "Urlop" to "Będę od poniedziałku.\nPozdrawiam",
            EmailVoice.parseRevision("TEMAT: Urlop\nTREŚĆ: Będę od poniedziałku.\nPozdrawiam", "Stary")
        )
        // Bez tematu - zostaje poprzedni.
        assertEquals("Stary" to "Nowa treść", EmailVoice.parseRevision("Treść: Nowa treść", "Stary"))
        // Model zignorował format - sama treść.
        assertEquals("Stary" to "Po prostu tekst", EmailVoice.parseRevision("Po prostu tekst", "Stary"))
        assertNull(EmailVoice.parseRevision("", "Stary"))
        assertNull(EmailVoice.parseRevision("TEMAT: Coś", "Stary"))
        assertNull(EmailVoice.parseRevision(null, "Stary"))
    }

    @Test
    fun `cisza to brak odpowiedzi`() {
        assertTrue(EmailVoice.isSilence(null))
        assertTrue(EmailVoice.isSilence("  "))
        assertFalse(EmailVoice.isSilence("tak"))
    }

    @Test
    fun `zdania o mailu nie ida do odpowiedzi na SMS`() {
        assertTrue(EmailVoice.mentionsMail("Odpowiedz na ostatni mail od Ani: będę jutro"))
        assertTrue(EmailVoice.mentionsMail("odpisz na maila"))
        assertTrue(EmailVoice.mentionsMail("odpowiedz na e-mail od szefa"))
        assertFalse(EmailVoice.mentionsMail("odpowiedz jej, że będę za dziesięć minut"))
        assertNull(MessageReplyParser.parse("Odpowiedz na ostatni mail od Ani: będę jutro"))
        assertNull(MessageReplyParser.prośbaBezTreści("odpisz na maila od Ani"))
        // Zwykła odpowiedź na wiadomość dalej działa.
        assertNotNull(MessageReplyParser.parse("Odpowiedz jej, że będę za dziesięć minut"))
    }

    @Test
    fun `znacznik reply_email od modelu`() {
        val (_, actions) = SmartActionDetector().detectAiMarkedActions(
            "Odpowiadam Ani.\n[[ACTION: type=reply_email from=\"Ania\" body=\"Będę o piątej.\"]]"
        )
        assertEquals(listOf(Action.ReplyEmail("Ania", "Będę o piątej.")), actions)
        val (_, last) = SmartActionDetector().detectAiMarkedActions(
            "[[ACTION: type=reply_email body=\"Dzięki!\"]]"
        )
        assertEquals(listOf(Action.ReplyEmail(null, "Dzięki!")), last)
    }

    @Test
    fun `znacznik send_email z imieniem`() {
        val (_, actions) = SmartActionDetector().detectAiMarkedActions(
            "[[ACTION: type=send_email to=\"Tomek\" subject=\"Spotkanie\" body=\"Przesuwamy na piątek.\"]]"
        )
        val mail = actions.single() as Action.SendEmail
        assertEquals("Tomek", mail.to)
        assertFalse(mail.isReply)
    }
}
