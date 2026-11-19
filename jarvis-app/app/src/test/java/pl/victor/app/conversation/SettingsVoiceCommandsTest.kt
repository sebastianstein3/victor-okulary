package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.victor.app.conversation.SettingsVoiceCommands.Polecenie

class SettingsVoiceCommandsTest {
    @Test
    fun `tryb bez kamery i motyw`() {
        assertEquals(Polecenie.BezKamery(true), SettingsVoiceCommands.rozpoznaj("Włącz tryb bez aparatu."))
        assertEquals(Polecenie.BezKamery(false), SettingsVoiceCommands.rozpoznaj("wyłącz tryb bez kamery"))
        assertEquals(Polecenie.Motyw("light"), SettingsVoiceCommands.rozpoznaj("Zmień motyw na jasny"))
        assertEquals(Polecenie.Motyw("dark"), SettingsVoiceCommands.rozpoznaj("włącz tryb ciemny"))
        assertEquals(Polecenie.Motyw("system"), SettingsVoiceCommands.rozpoznaj("ustaw wygląd jak telefon"))
        assertNull(SettingsVoiceCommands.rozpoznaj("jaka jest pogoda"))
        assertNull(SettingsVoiceCommands.rozpoznaj("zrób zdjęcie"))
    }
}
