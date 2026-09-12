package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Testy rozstrzygania, czy usłyszane zdanie jest do asystenta.
 *
 * Pierwszy przypadek to ten z dziennika - urywek czyjejś rozmowy, na który
 * asystent odpowiedział czternaście sekund po starcie aplikacji.
 */
class OverheardSpeechTest {

    private val frazy = listOf("okej lens")

    private fun verdict(text: String, open: Boolean) =
        OverheardSpeech.classify(text, frazy, open)

    @Test
    fun `urywek cudzej rozmowy poza rozmowa jest ignorowany`() {
        assertEquals(
            OverheardSpeech.Verdict.IGNORE,
            verdict("bardzo by chciał", open = false)
        )
    }

    @Test
    fun `ten sam urywek W TRAKCIE rozmowy jest dopowiedzeniem`() {
        // Tuż po odpowiedzi asystenta wymaganie frazy byłoby uciążliwe:
        // "a ile to kosztuje?" to normalna kontynuacja.
        assertEquals(
            OverheardSpeech.Verdict.ASK,
            verdict("a ile to kosztuje", open = true)
        )
    }

    @Test
    fun `sama fraza otwiera nasluch zamiast pytac model`() {
        assertEquals(
            OverheardSpeech.Verdict.OPEN_LISTENING,
            verdict("okej lens", open = false)
        )
        assertEquals(
            OverheardSpeech.Verdict.OPEN_LISTENING,
            verdict("Okej Lens!", open = false)
        )
    }

    @Test
    fun `fraza z pytaniem liczy sie zawsze`() {
        // Człowiek zawołał asystenta po imieniu - nie ma znaczenia, czy rozmowa
        // trwała.
        assertEquals(
            OverheardSpeech.Verdict.ASK,
            verdict("okej lens jaka jest pogoda", open = false)
        )
    }

    @Test
    fun `pustka jest ignorowana niezaleznie od stanu`() {
        assertEquals(OverheardSpeech.Verdict.IGNORE, verdict("   ", open = true))
        assertEquals(OverheardSpeech.Verdict.IGNORE, verdict("", open = false))
    }
}
