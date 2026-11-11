package pl.victor.app.proactive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import pl.victor.app.actions.SelfKnowledge

/**
 * Briefing to polecenie aplikacji, nie słowa użytkownika.
 *
 * Bieg 174: polecenie briefingu niosło w sobie kalendarz i pocztę, a w nich
 * "masz" i "styl" - warstwa 0 wzięła je za pytanie o persony i asystent zamiast
 * porannego podsumowania wyrecytował listę styli rozmowy. Orkiestratora nie da
 * się tu skompilować (biblioteka producenta), więc wiązanie sprawdzamy w źródle.
 */
class BriefingRoutingTest {

    private fun zrodlo(sciezka: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "jarvis-app/app/$sciezka"), File(dir, sciezka))
                .firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `tresc briefingu potrafi wygladac jak pytanie o persony`() {
        // Dowód, że sam wzorzec tego nie odsieje - musi to zrobić wywołanie.
        val polecenie = "Przygotuj poranny briefing. Pisz tak, jak się mówi, w swoim stylu. " +
            "Kalendarz: masz dziś urodziny Ani."
        assertEquals(SelfKnowledge.Topic.PERSONAS, SelfKnowledge.topicOf(polecenie))
    }

    @Test
    fun `briefing idzie do modelu z pominieciem warstwy zero`() {
        val plik = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("brak źródła orkiestratora", plik != null)
        val kod = plik!!.readText()
        assertTrue(
            "runBriefing musi oznaczać polecenie jako wewnętrzne",
            kod.contains("handleUserTrigger(TriggerSource.TEXT_INPUT, prompt, internalPrompt = true)")
        )
        assertTrue(
            "polecenie wewnętrzne nie może być traktowane jak słowa użytkownika",
            kod.contains("val textIsQuestion = audioQuestion == null && !internalPrompt")
        )
    }
}
