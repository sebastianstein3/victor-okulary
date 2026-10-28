package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Komendy meta nie mogą łapać zwykłych zdań (audyt 28.10). */
class MetaCommandsGuardTest {

    @Test
    fun `zmiana persony tylko na poczatku zdania`() {
        assertEquals(PersonaSwitchAttempt.Recognized("sternik"), MetaCommands.detectPersonaSwitchAttempt("bądź sternikiem"))
        assertEquals(PersonaSwitchAttempt.Recognized("profesor"), MetaCommands.detectPersonaSwitchAttempt("przełącz się na profesora"))
        assertEquals(PersonaSwitchAttempt.Recognized("przyjaciel"), MetaCommands.detectPersonaSwitchAttempt("proszę, bądź przyjacielem"))
        assertNull(MetaCommands.detectPersonaSwitchAttempt("kawa bądź herbata?"))
        assertNull(MetaCommands.detectPersonaSwitchAttempt("przełącz na następny utwór"))
    }

    @Test
    fun `reset kontekstu tylko jako cale polecenie`() {
        assertTrue(MetaCommands.detectContextReset("nowy temat"))
        assertTrue(MetaCommands.detectContextReset("dobra, zacznijmy od nowa"))
        assertTrue(MetaCommands.detectContextReset("wyczyść kontekst."))
        assertFalse(MetaCommands.detectContextReset("zaproponuj nowy temat na prezentację"))
    }
}
