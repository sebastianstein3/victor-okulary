package pl.victor.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tryb bez kamery: żadna droga nie może uruchomić aparatu ani pobrać zdjęcia.
 *
 * Orkiestratora nie da się tu skompilować (biblioteka producenta), więc
 * bramki sprawdzamy w źródle - tak jak w BriefingRoutingTest.
 */
class CameraFreeGatingTest {

    /**
     * Orkiestrator razem z modułem obrazu ze strumienia, który z niego
     * wydzielono (orchestrator/CodeStreamScanner.kt) - bramki są w obu.
     */
    private fun kod(): String? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val orkiestrator = listOf(
                File(dir, "jarvis-app/app/src/main/java/pl/victor/app/AIOrchestrator.kt"),
                File(dir, "src/main/java/pl/victor/app/AIOrchestrator.kt")
            ).firstOrNull { it.isFile }
            if (orkiestrator != null) {
                val strumień = File(orkiestrator.parentFile, "orchestrator/CodeStreamScanner.kt")
                assertTrue("brak modułu: $strumień", strumień.isFile)
                return orkiestrator.readText() + "\n" + strumień.readText()
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun ciało(kod: String, początek: String): String {
        val i = kod.indexOf(początek)
        assertTrue("brak: $początek", i >= 0)
        return kod.substring(i, minOf(kod.length, i + 1500))
    }

    @Test
    fun `obraz w turze zalezy od trybu bez kamery`() {
        val k = kod()
        assumeTrue("brak źródła orkiestratora", k != null)
        assertTrue(k!!.contains("val useVision = !kameraWyłączona &&"))
        assertTrue(k.contains("if (kameraWyłączona && forceVision)"))
        assertTrue(k.contains("val wantsPhoto = !settings.isCameraFree() &&"))
    }

    @Test
    fun `zdjecie z przycisku i strumien kodu sa pomijane`() {
        val k = kod()
        assumeTrue("brak źródła orkiestratora", k != null)
        assertTrue(ciało(k!!, "private fun handleGlassesPhoto(").contains("if (settings.isCameraFree())"))
        assertTrue(ciało(k, "fun warmUpStreamForCode(").contains("if (settings.isCameraFree()) return"))
    }

    @Test
    fun `klikniecia dostaja znaczenie bez kamery`() {
        val k = kod()
        assumeTrue("brak źródła orkiestratora", k != null)
        val przycisk = ciało(k!!, "private fun handleButtonAction(")
        val i = k.indexOf("if (settings.isCameraFree()) {\n            when (action) {")
        assertTrue("brak bramki kliknięć", i > k.indexOf("private fun handleButtonAction("))
        val bramka = k.substring(i, i + 900)
        assertTrue(bramka.contains("readNewMessages()"))
        assertTrue(bramka.contains("runBriefing()"))
        assertTrue(przycisk.isNotEmpty())
    }
}
