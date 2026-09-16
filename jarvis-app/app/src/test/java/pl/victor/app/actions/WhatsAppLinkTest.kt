package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppLinkTest {

    @Test
    fun `polski numer bez kierunkowego dostaje 48`() {
        assertEquals("48123456789", WhatsAppLink.normalizeNumber("123 456 789"))
    }

    @Test
    fun `numer z plusem zostaje jak jest`() {
        assertEquals("491701234567", WhatsAppLink.normalizeNumber("+49 170 1234567"))
    }

    @Test
    fun `zera wiodace zamieniaja sie na nic`() {
        assertEquals("48123456789", WhatsAppLink.normalizeNumber("0048-123-456-789"))
    }

    @Test
    fun `myslniki i nawiasy nie przeszkadzaja`() {
        assertEquals("48123456789", WhatsAppLink.normalizeNumber("(123) 456-789"))
    }

    @Test
    fun `przeslyszenie zamiast numeru nie otwiera WhatsAppa`() {
        // Otwarcie rozmowy na śmieciach wygląda gorzej niż przyznanie, że nie
        // zrozumiałem.
        assertNull(WhatsAppLink.normalizeNumber("123"))
        assertNull(WhatsAppLink.normalizeNumber("dwadzieścia"))
        assertNull(WhatsAppLink.normalizeNumber(""))
        assertNull(WhatsAppLink.normalizeNumber("1234567890123456789"))
    }

    @Test
    fun `tresc jest zakodowana, ze spacja jako 20`() {
        val url = WhatsAppLink.forNumber("123456789", "Spóźnię się 15 minut")!!
        assertTrue(url.startsWith("https://wa.me/48123456789?text="))
        // Plus w adresie WhatsApp pokazuje dosłownie, zamiast jako spację.
        assertTrue("spacja nie może być plusem: $url", !url.contains("+"))
        assertTrue(url.contains("%20"))
    }

    @Test
    fun `polskie znaki przezywaja kodowanie`() {
        val url = WhatsAppLink.forNumber("123456789", "zażółć gęślą jaźń")!!
        assertTrue(url.contains("%C5%BC"))
    }

    @Test
    fun `bez sensownego numeru nie ma adresu`() {
        assertNull(WhatsAppLink.forNumber("nie numer", "cokolwiek"))
    }
}

/** Rozpoznanie zdania i znacznika od modelu. */
class WhatsAppDetectionTest {

    private val detector = SmartActionDetector()

    private fun whatsApp(text: String) =
        detector.detect(text).filterIsInstance<Action.SendWhatsApp>().firstOrNull()

    @Test
    fun `napisz na whatsappie do kogos, ze cos`() {
        val a = whatsApp("napisz na whatsappie do ani, że się spóźnię")
        assertEquals("ani", a?.to)
        assertEquals("się spóźnię", a?.body)
    }

    @Test
    fun `wyslij whatsappem tez dziala`() {
        assertEquals("tomka", whatsApp("wyślij whatsappem do tomka, że już jadę")?.to)
    }

    @Test
    fun `whatsapp nie porywa zwyklego SMS-a`() {
        // Bez tego rozdziału wiadomość szłaby kanałem, którego adresat może nie
        // sprawdzać - albo odwrotnie.
        assertNull(whatsApp("wyślij sms do ani, że się spóźnię"))
        val sms = detector.detect("wyślij sms do ani, że się spóźnię")
            .filterIsInstance<Action.SendSms>().firstOrNull()
        assertEquals("ani", sms?.to)
    }

    @Test
    fun `sama wzmianka o whatsappie to nie polecenie`() {
        assertNull(whatsApp("czy dostałeś mojego whatsappa"))
        assertNull(whatsApp("napisz na whatsappie"))
    }

    @Test
    fun `znacznik od modelu tworzy akcje`() {
        val (_, actions) = detector.detectAiMarkedActions(
            """Jasne. [[ACTION: type=send_whatsapp to="Ania" body="spóźnię się"]]"""
        )
        val a = actions.filterIsInstance<Action.SendWhatsApp>().firstOrNull()
        assertEquals("Ania", a?.to)
        assertEquals("spóźnię się", a?.body)
    }

    @Test
    fun `opis akcji NIE obiecuje wyslania`() {
        // WhatsApp nie pozwala obcej aplikacji wysłać wiadomości. Opis, który
        // mówi "wyślij", kazałby komuś wyjść z domu w przekonaniu, że uprzedził
        // o spóźnieniu.
        val opis = Action.SendWhatsApp(to = "Ania", body = "spóźnię się").description
        assertTrue(opis.contains("Otwórz"))
        assertTrue(!opis.startsWith("Wyślij"))
    }
}

/**
 * Przykłady WPROST z katalogu komend aplikacji - te, które pokazujemy
 * użytkownikowi jako "tak się tego używa". Żaden z nich nie działał.
 */
class MessageRegexRegressionTest {

    private val detector = SmartActionDetector()

    private fun sms(text: String) =
        detector.detect(text).filterIsInstance<Action.SendSms>().firstOrNull()

    @Test
    fun `przyklad z katalogu - SMS z przecinkiem i spojnikiem ze`() {
        val a = sms("wyślij sms do ani, że się spóźnię")
        assertEquals("ani", a?.to)
        assertEquals("się spóźnię", a?.body)
    }

    @Test
    fun `przyklad z katalogu - slowo wiadomosc z ogonkiem`() {
        val a = sms("wyślij wiadomość do mamy, że już jadę")
        assertEquals("mamy", a?.to)
        assertEquals("już jadę", a?.body)
    }

    @Test
    fun `dwukropek dalej dziala - nie zepsulem tego, co dzialalo`() {
        val a = sms("wyślij sms do ani: się spóźnię")
        assertEquals("ani", a?.to)
        assertEquals("się spóźnię", a?.body)
    }

    @Test
    fun `zapis bez ogonkow dalej dziala`() {
        val a = sms("wyslij sms do ani ze sie spoznie")
        assertEquals("ani", a?.to)
        assertEquals("sie spoznie", a?.body)
    }

    @Test
    fun `odbiorca nie wciaga przecinka`() {
        assertEquals("ani", sms("wyślij sms do ani, że cześć")?.to)
    }

    @Test
    fun `bez tresci nie wysylamy pustego SMS-a`() {
        assertNull(sms("wyślij sms do ani"))
    }
}
