package pl.victor.app.ai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Model ma wiedzieć, czy ma internet, a przerwana tura nie może udawać awarii.
 *
 * Oba przypadki z dziennika z 18:29-18:37: trzy razy "nie mam dostępu do
 * aktualnych cen" przy włączonym wyszukiwaniu Google i przerwanie tury, po
 * którym aplikacja przeszła przez wszystkich dostawców aż do modelu lokalnego.
 */
class WebSearchPromptTest {

    @Test
    fun `z wyszukiwarka kaze szukac samemu, nie przez znacznik`() {
        val t = WebSearchPrompt.dlaModelu(maWyszukiwarkę = true)
        assertTrue(t.contains("WYSZUKAJ"))
        assertTrue(t.contains("ceny"))
        assertTrue(t.contains("pogoda w innym miejscu"))
        assertTrue("musi zakazać wymówki o braku internetu", t.contains("Nie mów, że nie masz dostępu"))
        assertTrue("musi odradzić web_search jako drogę do wyników", t.contains("nie używaj do tego znacznika web_search"))
    }

    @Test
    fun `bez wyszukiwarki kaze sie przyznac, nie udawac szukania`() {
        val t = WebSearchPrompt.dlaModelu(maWyszukiwarkę = false)
        assertTrue(t.contains("NIE masz dostępu do wyszukiwarki"))
        assertTrue(t.contains("Nie mów, że właśnie szukasz"))
        assertFalse(t.contains("WYSZUKAJ"))
    }

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
    fun `podpowiedz o internecie jest wpieta w prompt systemowy`() {
        val orch = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", orch != null)
        val start = orch!!.indexOf("val effectiveSystemPrompt = persona.systemPrompt")
        val end = orch.indexOf("Log.d(TAG, \"Using persona", start)
        assertTrue(start >= 0 && end > start)
        val blok = orch.substring(start, end)
        assertTrue(blok.contains("WebSearchPrompt.dlaModelu("))
        assertTrue(blok.contains("provider.supportsWebSearch && settings.isWebSearchEnabled()"))
    }

    @Test
    fun `anulowana tura nie przechodzi do kolejnego dostawcy`() {
        val orch = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", orch != null)
        val zawiodl = orch!!.indexOf("DiagFormat.Phase.MODEL, \"dostawca zawiódł\"")
        val catchStart = orch.lastIndexOf("} catch (e: Exception) {", zawiodl)
        assertTrue(zawiodl > 0 && catchStart > 0)
        assertTrue(
            "zanim uznamy to za awarię dostawcy, anulowanie tury musi rzucić dalej",
            orch.substring(catchStart, zawiodl).contains("currentCoroutineContext().ensureActive()")
        )
    }
}
