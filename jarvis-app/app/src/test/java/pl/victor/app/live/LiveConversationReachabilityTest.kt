package pl.victor.app.live

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Rozmowa na żywo musi mieć działające wejścia i wyjścia - i strażników
 * mikrofonu.
 *
 * Orkiestratora nie da się tu skompilować (brak biblioteki producenta), więc
 * wiązania sprawdzamy w źródle, jak w EarTranslationReachabilityTest.
 */
class LiveConversationReachabilityTest {

    private fun zrodlo(sciezka: String): String? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "jarvis-app/app/$sciezka"), File(dir, sciezka))
                .firstOrNull { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `wejscia - polecenie glosowe i kafelek`() {
        val orch = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        val ekran = zrodlo("src/main/java/pl/victor/app/ui/MainScreen.kt")
        assumeTrue(orch != null && ekran != null)
        assertTrue(orch!!.contains("MetaCommands.startsLiveConversation(text)"))
        assertTrue(orch.contains("startLiveConversation(\"polecenie głosowe\")"))
        assertTrue(ekran!!.contains("orch.toggleLiveConversation("))
        assertTrue(ekran.contains("orch.stopLiveConversation("))
    }

    @Test
    fun `wyjscia - przycisk, dotyk skroni, przerwij`() {
        val orch = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue(orch != null)
        assertTrue(orch!!.contains("stopLiveConversation(\"dotknięcie skroni\")"))
        assertTrue(orch.contains("stopLiveConversation(\"przycisk na okularach"))
        assertTrue(orch.contains("stopLiveConversation(reason)"))
    }

    @Test
    fun `w trakcie trybu tury i nasluch nie biora mikrofonu`() {
        val orch = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue(orch != null)
        val trigger = orch!!.substringAfter("fun handleUserTrigger(").substringBefore("claimIdle(")
        assertTrue("strażnik przed claimIdle w handleUserTrigger", trigger.contains("liveConversationOn()"))
        val voice = orch.substringAfter("private fun startVoiceTurn(").substringBefore("claimIdle(")
        assertTrue("strażnik przed claimIdle w startVoiceTurn", voice.contains("liveConversationOn()"))
        assertTrue(orch.contains("WAKE_HOLDER_LIVE"))
    }

    @Test
    fun `platne - przelacznik respektuje tylko darmowe`() {
        val ust = zrodlo("src/main/java/pl/victor/app/data/SettingsRepository.kt")
        assumeTrue(ust != null)
        val f = ust!!.substringAfter("fun isLiveConversationEnabled()").substringBefore("fun ")
        assertTrue(f.contains("!isFreeOnly()"))
    }
}
