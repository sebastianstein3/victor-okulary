package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {

    /** Dokładnie ten tekst poszedł do modelu jako pytanie w turze e117. */
    @Test
    fun `sama fraza z dziennika jest rozpoznana jako wybudzenie`() {
        assertTrue(WakePhrase.isOnlyWakePhrase("okej lens"))
    }

    @Test
    fun `zapis frazy nie ma znaczenia`() {
        for (variant in listOf("OK Lens", "ok lens.", "  Okej, Lens  ", "OKEJ LENS!")) {
            assertTrue(variant, WakePhrase.isOnlyWakePhrase(variant))
        }
    }

    @Test
    fun `prawdziwe pytanie nie jest fraza wybudzenia`() {
        assertFalse(WakePhrase.isOnlyWakePhrase("jaka jest pogoda"))
        assertFalse(WakePhrase.isOnlyWakePhrase("okej lens jaka jest pogoda"))
    }

    @Test
    fun `pusta wypowiedz nie jest fraza wybudzenia`() {
        assertFalse(WakePhrase.isOnlyWakePhrase("   "))
    }

    @Test
    fun `fraza z pytaniem zostawia samo pytanie`() {
        assertEquals(
            "jaka jest pogoda",
            WakePhrase.stripLeadingWakePhrase("okej lens jaka jest pogoda")
        )
    }

    @Test
    fun `pytanie bez frazy zostaje nietkniete`() {
        val question = "Jaka jest pogoda?"
        assertEquals(question, WakePhrase.stripLeadingWakePhrase(question))
    }

    /**
     * Gdyby po odcięciu nie zostawało nic, wołający dostałby pusty tekst i
     * wysłał go do modelu - czyli dokładnie tę usterkę, którą naprawiamy.
     */
    @Test
    fun `sama fraza zostaje nietknieta zamiast zamienic sie w pustke`() {
        assertEquals("okej lens", WakePhrase.stripLeadingWakePhrase("okej lens"))
    }

    @Test
    fun `fraza telefonu tez dziala gdy ja podamy`() {
        val extra = listOf("computer")
        assertTrue(WakePhrase.isOnlyWakePhrase("Computer", extra))
        // Oryginalny zapis, z ogonkami: to idzie do modelu, a nie do porównania.
        assertEquals(
            "zgaś światło",
            WakePhrase.stripLeadingWakePhrase("computer zgaś światło", extra)
        )
    }

    @Test
    fun `dluzsza fraza wygrywa z krotsza`() {
        val extra = listOf("lens")
        assertEquals(
            "co widzisz",
            WakePhrase.stripLeadingWakePhrase("okej lens co widzisz", extra)
        )
    }
}
