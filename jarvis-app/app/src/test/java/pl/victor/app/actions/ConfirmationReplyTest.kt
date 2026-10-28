package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Obie pomyłki są kosztowne inaczej: fałszywe "tak" wysyła maila, którego nikt
 * nie chciał, fałszywe "nie" kasuje pracę właśnie zleconą. Przy głosie nie ma
 * drugiego ekranu, na którym dałoby się to złapać.
 */
class ConfirmationReplyTest {

    private fun parse(text: String?) = ConfirmationReply.parse(text)

    @Test
    fun `proste zgody`() {
        listOf("tak", "Tak", "potwierdzam", "dobra", "jasne", "ok", "dawaj", "wyślij")
            .forEach { assertEquals(it, ConfirmationReply.Reply.YES, parse(it)) }
    }

    @Test
    fun `proste odmowy`() {
        listOf("nie", "Nie", "anuluj", "rezygnuję", "zostaw", "odrzucam")
            .forEach { assertEquals(it, ConfirmationReply.Reply.NO, parse(it)) }
    }

    @Test
    fun `rozstrzyga pierwsze slowo decyzji`() {
        // "tak, ale nie teraz" to zgoda czy odmowa? Odmowa - bo "nie teraz" jest
        // zwrotem złożonym i sprawdzamy go przed pojedynczymi słowami.
        assertEquals(ConfirmationReply.Reply.NO, parse("tak, ale nie teraz"))
        assertEquals(ConfirmationReply.Reply.NO, parse("nie, dziękuję"))
        assertEquals(ConfirmationReply.Reply.YES, parse("tak, dodaj to"))
    }

    @Test
    fun `zwroty zlozone sa odmowa`() {
        listOf("nie trzeba", "daj spokój", "jednak nie", "nie chcę tego")
            .forEach { assertEquals(it, ConfirmationReply.Reply.NO, parse(it)) }
    }

    @Test
    fun `watpliwosc to NIE jest zgoda`() {
        // Najważniejszy test w tym pliku: wahanie nie może wysłać maila.
        listOf("może", "chyba tak by było dobrze", "hmm", "nie wiem", "a co to znaczy")
            .forEach {
                assertEquals(it, ConfirmationReply.Reply.UNCLEAR, parse(it))
            }
    }

    @Test
    fun `stop nie jest odmowa`() {
        // "stop" ucisza syntezator. Gdyby kasowało akcję, każdy, kto chciał tylko
        // przerwać czytanie pytania, traciłby to, co zlecił.
        assertEquals(ConfirmationReply.Reply.UNCLEAR, parse("stop"))
    }

    @Test
    fun `pusto i null to brak decyzji`() {
        assertEquals(ConfirmationReply.Reply.UNCLEAR, parse(null))
        assertEquals(ConfirmationReply.Reply.UNCLEAR, parse(""))
        assertEquals(ConfirmationReply.Reply.UNCLEAR, parse("   "))
    }

    @Test
    fun `ogonki nie zmieniaja decyzji`() {
        assertEquals(ConfirmationReply.Reply.YES, parse("wyslij"))
        assertEquals(ConfirmationReply.Reply.YES, parse("wyślij"))
        assertEquals(ConfirmationReply.Reply.NO, parse("rezygnuje"))
    }

    @Test
    fun `scisle potwierdzenie nie lapie polecen`() {
        assertEquals(ConfirmationReply.Reply.UNCLEAR, ConfirmationReply.parseStrict("zrób zdjęcie"))
        assertEquals(ConfirmationReply.Reply.UNCLEAR, ConfirmationReply.parseStrict("dodaj mleko"))
        assertEquals(ConfirmationReply.Reply.UNCLEAR, ConfirmationReply.parseStrict("dobra, opowiedz kawał"))
        assertEquals(ConfirmationReply.Reply.YES, ConfirmationReply.parseStrict("tak, proszę"))
        assertEquals(ConfirmationReply.Reply.YES, ConfirmationReply.parseStrict("wyślij"))
        assertEquals(ConfirmationReply.Reply.NO, ConfirmationReply.parseStrict("nie, dziękuję"))
        assertEquals(ConfirmationReply.Reply.NO, ConfirmationReply.parseStrict("nie trzeba"))
        assertEquals(ConfirmationReply.Reply.UNCLEAR, ConfirmationReply.parseStrict("chyba tak"))
    }
}
