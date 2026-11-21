package pl.victor.app.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Odpowiedź bez nagłówków wątku ląduje u adresata jako NOWY wątek, temat bez
 * RFC 2047 przychodzi jako krzaki, a nowa linia w temacie dopisałaby nagłówek -
 * żadnej z tych pomyłek nie widać w aplikacji, tylko u adresata.
 */
class EmailComposeTest {

    private fun candidate(
        id: String,
        from: String,
        received: Long,
        subject: String = "Spotkanie",
        replyTo: String? = null,
        messageId: String? = "<$id@mail.example.com>",
        references: String? = null
    ) = EmailCompose.ReplyCandidate(
        id = id, threadId = "t-$id", from = from, replyTo = replyTo, subject = subject,
        messageIdHeader = messageId, references = references, receivedMs = received
    )

    /** Rozkodowanie pola raw tak, jak zrobi to Gmail. */
    private fun decodeRaw(raw: String): String = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)

    private fun bodyOf(mime: String): String {
        val encoded = mime.substringAfter("\r\n\r\n").replace("\r\n", "")
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    @Test
    fun `rozbiera nadawce z nazwa i bez`() {
        assertEquals(
            EmailCompose.Address("Ania Kowalska", "ania@example.com"),
            EmailCompose.parseAddress("Ania Kowalska <ania@example.com>")
        )
        assertEquals(
            EmailCompose.Address("Nowak, Ania", "ania@example.com"),
            EmailCompose.parseAddress("\"Nowak, Ania\" <ania@example.com>")
        )
        assertEquals(EmailCompose.Address(null, "tomek@example.com"), EmailCompose.parseAddress("tomek@example.com"))
        // Lista w Reply-To - pierwszy adres.
        assertEquals("a@x.pl", EmailCompose.parseAddress("A <a@x.pl>, B <b@x.pl>")?.email)
        assertNull(EmailCompose.parseAddress("Ania"))
        assertNull(EmailCompose.parseAddress(null))
    }

    @Test
    fun `temat odpowiedzi bez podwojnego Re`() {
        assertEquals("Re: Spotkanie", EmailCompose.replySubject("Spotkanie"))
        assertEquals("Re: Spotkanie", EmailCompose.replySubject("Re: Spotkanie"))
        assertEquals("RE: Spotkanie", EmailCompose.replySubject("RE: Spotkanie"))
        assertEquals("Odp: Spotkanie", EmailCompose.replySubject("Odp: Spotkanie"))
        assertEquals("Re:", EmailCompose.replySubject(""))
    }

    @Test
    fun `references dokladaja message-id oryginalu`() {
        assertEquals("<a@x> <b@x>", EmailCompose.referencesFor("<a@x>", "<b@x>"))
        assertEquals("<b@x>", EmailCompose.referencesFor(null, "<b@x>"))
        // Bez dublowania, gdy już jest.
        assertEquals("<a@x> <b@x>", EmailCompose.referencesFor("<a@x> <b@x>", "<b@x>"))
        assertNull(EmailCompose.referencesFor(null, null))
    }

    @Test
    fun `odpowiedz ma watek naglowki i adres z reply-to`() {
        val original = candidate(
            "m1", "Ania Kowalska <ania@example.com>", 100,
            subject = "Plan na piątek", replyTo = "biuro@example.com", references = "<r0@x>"
        )
        val reply = EmailCompose.replyTo(original, "Będę o piątej.")!!
        assertEquals("biuro@example.com", reply.to.email)
        assertEquals("Re: Plan na piątek", reply.subject)
        assertEquals("t-m1", reply.threadId)
        assertEquals("<m1@mail.example.com>", reply.inReplyTo)
        assertEquals("<r0@x> <m1@mail.example.com>", reply.references)
    }

    @Test
    fun `odpowiedz bez reply-to idzie do nadawcy z jego nazwa`() {
        val reply = EmailCompose.replyTo(candidate("m1", "Ania Kowalska <ania@example.com>", 1), "ok")!!
        assertEquals(EmailCompose.Address("Ania Kowalska", "ania@example.com"), reply.to)
        assertEquals("Ania Kowalska", reply.to.spoken)
    }

    @Test
    fun `mime ma naglowki watku i zakodowany temat`() {
        val mail = EmailCompose.replyTo(
            candidate("m1", "Ania Kowalska <ania@example.com>", 1, subject = "Zażółć gęślą jaźń"),
            "Dzięki, będę o piątej.\nPozdrawiam"
        )!!
        val mime = decodeRaw(EmailCompose.toRaw(EmailCompose.buildMime("ja@example.com", mail)))
        val headers = mime.substringBefore("\r\n\r\n").split("\r\n")
        assertTrue(headers.contains("From: ja@example.com"))
        assertTrue(headers.contains("To: Ania Kowalska <ania@example.com>"))
        assertTrue(headers.contains("In-Reply-To: <m1@mail.example.com>"))
        assertTrue(headers.contains("References: <m1@mail.example.com>"))
        val subject = headers.first { it.startsWith("Subject: ") }.removePrefix("Subject: ")
        assertTrue(subject.startsWith("=?UTF-8?B?") && subject.endsWith("?="))
        val decoded = String(
            Base64.getDecoder().decode(subject.removePrefix("=?UTF-8?B?").removeSuffix("?=")), Charsets.UTF_8
        )
        assertEquals("Re: Zażółć gęślą jaźń", decoded)
        assertEquals("Dzięki, będę o piątej.\r\nPozdrawiam", bodyOf(mime))
    }

    @Test
    fun `raw jest base64url bez plusow i ukosnikow`() {
        val mail = EmailCompose.Outgoing(EmailCompose.Address(null, "a@x.pl"), "?>?>?>", "ÿÿÿ???>>>")
        val raw = EmailCompose.toRaw(EmailCompose.buildMime(null, mail))
        assertFalse(raw.contains('+'))
        assertFalse(raw.contains('/'))
    }

    @Test
    fun `nowa linia w temacie nie dopisze naglowka`() {
        val mail = EmailCompose.Outgoing(
            EmailCompose.Address(null, "a@x.pl"), "Urlop\r\nBcc: obcy@x.pl", "treść"
        )
        val mime = EmailCompose.buildMime(null, mail)
        val headers = mime.substringBefore("\r\n\r\n").split("\r\n")
        assertTrue(headers.none { it.startsWith("Bcc:") })
        assertTrue(headers.contains("Subject: Urlop Bcc: obcy@x.pl"))
    }

    @Test
    fun `nazwa z polskimi znakami kodowana w adresie`() {
        val formatted = EmailCompose.formatAddress(EmailCompose.Address("Łukasz", "l@x.pl"))
        assertTrue(formatted.startsWith("=?UTF-8?B?"))
        assertTrue(formatted.endsWith(" <l@x.pl>"))
        assertEquals("\"Nowak, Jan\" <j@x.pl>", EmailCompose.formatAddress(EmailCompose.Address("Nowak, Jan", "j@x.pl")))
    }

    @Test
    fun `wybiera najnowszy mail od wskazanej osoby`() {
        val mails = listOf(
            candidate("a1", "Ania Kowalska <ania@example.com>", 100),
            candidate("t1", "Tomasz Nowak <tomek@example.com>", 300),
            candidate("a2", "Ania Kowalska <ania@example.com>", 200)
        )
        assertEquals("a2", EmailCompose.pickMessage(mails, "Ania")?.id)
        // Odmiana: "od Ani".
        assertEquals("a2", EmailCompose.pickMessage(mails, "Ani")?.id)
        assertEquals("t1", EmailCompose.pickMessage(mails, null)?.id)
        assertEquals("t1", EmailCompose.pickMessage(mails, "tomek@example.com")?.id)
        assertNull(EmailCompose.pickMessage(mails, "Zenon"))
    }

    @Test
    fun `adres bez nazwy dopasowany po czesci przed malpa`() {
        val mails = listOf(candidate("b1", "bartek.zielinski@example.com", 1))
        assertEquals("b1", EmailCompose.pickMessage(mails, "Bartek")?.id)
        assertEquals("bartek.zielinski@example.com", EmailCompose.pickSender(mails, "Bartek")?.email)
        assertNull(EmailCompose.pickSender(mails, "Zenon"))
    }

    @Test
    fun `temat zastepczy z poczatku tresci`() {
        assertEquals("Spotkanie przesuwamy na piątek", EmailCompose.defaultSubject("Spotkanie przesuwamy na piątek. Daj znać."))
        assertEquals("(bez tematu)", EmailCompose.defaultSubject("  "))
        val long = EmailCompose.defaultSubject("a ".repeat(60))
        assertTrue(long.length <= 51)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `rozpoznaje adres e-mail`() {
        assertTrue(EmailCompose.isEmailAddress("jan.kowalski@firma.pl"))
        assertFalse(EmailCompose.isEmailAddress("Tomek"))
        assertFalse(EmailCompose.isEmailAddress("jan@"))
        assertNotNull(EmailCompose.parseAddress("<jan@firma.pl>"))
    }
}
