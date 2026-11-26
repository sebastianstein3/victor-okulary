package pl.victor.app.hermes

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Hermes musi być podpięty tak, jak zamierzono - orkiestratora nie da się tu
 * skompilować, więc sprawdzamy źródło (jak przy tłumaczeniu i rozmowie na żywo).
 *
 * Pilnuje trzech rzeczy, których pomyłka wychodzi dopiero na telefonie:
 * kolejności w warstwie 0, tego, że zadanie to prawdziwa tura (da się je
 * przerwać), i tego, że zasoby tury są zwalniane.
 */
class HermesReachabilityTest {

    private fun zrodlo(): String? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = listOf(
                File(dir, "jarvis-app/app/src/main/java/pl/victor/app/AIOrchestrator.kt"),
                File(dir, "src/main/java/pl/victor/app/AIOrchestrator.kt")
            ).firstOrNull { it.isFile }
            if (f != null) return f.readText()
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `jawne wolanie Hermesa jest sprawdzane przed domem i odjazdami`() {
        val kod = zrodlo()
        assumeTrue("brak źródła orkiestratora", kod != null)
        val hermes = kod!!.indexOf("if (handleHermes(trigger, textQuestion)) return")
        val odjazdy = kod.indexOf("if (handleTransitDepartures(textQuestion)) return")
        val dom = kod.indexOf("if (handleHomeCommand(trigger, textQuestion)) return")
        assertTrue("brak wywołania handleHermes w warstwie 0", hermes > 0)
        assertTrue("Hermes ma być przed odjazdami", hermes < odjazdy)
        assertTrue("Hermes ma być przed domem", hermes < dom)
    }

    @Test
    fun `zadanie Hermesa to prawdziwa tura ze zwolnieniem zasobow`() {
        val kod = zrodlo()
        assumeTrue("brak źródła orkiestratora", kod != null)
        val start = kod!!.indexOf("private fun runHermes(")
        assertTrue("brak runHermes", start > 0)
        val koniec = kod.indexOf("turnJob.invokeOnCompletion { releaseMic() }", start)
        assertTrue("runHermes nie zwalnia mikrofonu po anulowaniu przed startem", koniec > start)
        val ciało = kod.substring(start, koniec)
        assertTrue("zadanie ma iść przez launchTurn (da się je przerwać)", ciało.contains("launchTurn {"))
        assertTrue("brak blokady uśpienia", ciało.contains("wakeLock.acquire(") && ciało.contains("wakeLock.release("))
        assertTrue("brak mikrofonu frazy", ciało.contains("holdMicForTurn()") && ciało.contains("releaseMic()"))
        assertTrue("łącze audio musi się zwinąć nawet po anulowaniu", ciało.contains("NonCancellable"))
        assertTrue("odpowiedź ma iść strumieniem mowy", ciało.contains("audio.beginStream(") && ciało.contains("audio.awaitStreamSpoken()"))
    }

    @Test
    fun `niedostepny Hermes tlumaczy sie tylko przy jawnym wolaniu`() {
        val kod = zrodlo()
        assumeTrue("brak źródła orkiestratora", kod != null)
        val start = kod!!.indexOf("private fun handleHermes(")
        assertTrue("brak handleHermes", start > 0)
        val ciało = kod.substring(start, kod.indexOf("runHermes(p)", start))
        assertTrue("bez jawnego wołania zdanie ma iść dalej", ciało.contains("if (!p.jawne) return false"))
    }

    @Test
    fun `zgody Hermesa nie przyjmuja glosu z nowej tury i sa czyszczone po zadaniu`() {
        val kod = zrodlo()
        assumeTrue("brak źródła orkiestratora", kod != null)
        // Przypadkowe "ok" po innej rozmowie nie może zatwierdzić polecenia na komputerze.
        assertTrue(
            "nowa tura nie może zatwierdzać zgód Hermesa",
            kod!!.contains("if (textIsQuestion && pending != null && pending.głosZNowejTury &&")
        )
        val start = kod.indexOf("private fun runHermes(")
        val koniec = kod.indexOf("turnJob.invokeOnCompletion { releaseMic() }", start)
        val ciało = kod.substring(start, koniec)
        assertTrue("zgoda ma być pytana bez głosu z nowej tury", ciało.contains("głosZNowejTury = false"))
        assertTrue("wisząca zgoda musi znikać po zadaniu", ciało.contains("actionFlow.clearHermesApproval()"))
        assertTrue("zgoda to tylko jednorazowe once/deny", !ciało.contains("always") && !ciało.contains("\"session\""))
    }

    @Test
    fun `panel dostaje rozmowe, a zadanie bez mowy nie rusza mikrofonu ani audio`() {
        val kod = zrodlo()
        assumeTrue("brak źródła orkiestratora", kod != null)
        val start = kod!!.indexOf("private fun runHermes(")
        val koniec = kod.indexOf("turnJob.invokeOnCompletion { releaseMic() }", start)
        val ciało = kod.substring(start, koniec)
        for (wywołanie in listOf("hermesPanel.rozpocznij(", "hermesPanel.tekst(", "hermesPanel.narzędzie(", "hermesPanel.zgoda(", "hermesPanel.zakończ(")) {
            assertTrue("panel nie dostaje: $wywołanie", ciało.contains(wywołanie))
        }
        assertTrue("mikrofon frazy tylko gdy zadanie mówi", ciało.contains("if (mów) holdMicForTurn()"))
        assertTrue("strumień mowy tylko gdy zadanie mówi", ciało.contains("if (mów) mówienie.withLock { audio.addStreamFragment("))
        assertTrue("zgoda bez mowy ma iść bez nasłuchu", ciało.contains("actionFlow.showConfirmationQuiet("))
        assertTrue("panel ma się domknąć nawet po przerwaniu", ciało.contains("if (hermesPanel.trwa.value) hermesPanel.zakończ("))
    }

    @Test
    fun `zgoda bez mowy zamyka odpowiedz glosem z innych tur`() {
        var dir: File? = File("").absoluteFile
        var kod: String? = null
        while (dir != null && kod == null) {
            kod = listOf(
                File(dir, "jarvis-app/app/src/main/java/pl/victor/app/orchestrator/ActionFlow.kt"),
                File(dir, "src/main/java/pl/victor/app/orchestrator/ActionFlow.kt")
            ).firstOrNull { it.isFile }?.readText()
            dir = dir.parentFile
        }
        assumeTrue("brak źródła ActionFlow", kod != null)
        val start = kod!!.indexOf("fun showConfirmationQuiet(")
        assertTrue("brak showConfirmationQuiet", start > 0)
        val ciało = kod.substring(start, kod.indexOf("}", kod.indexOf("_pendingActionConfirmation.value", start)))
        assertTrue("zgoda bez mowy musi mieć createdAtMs = 0", ciało.contains("createdAtMs = 0L"))
        assertTrue("zgoda bez mowy nie może zostawiać nasłuchu", ciało.contains("confirmationJob?.cancel()"))
    }
}
