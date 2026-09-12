package pl.victor.app.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy docinania promptu do okna małego modelu.
 *
 * Powód istnienia: dziennik z 12 września ma `znakówPromptu=8943`, a katalog
 * daje Qwenowi 0.8B okno 2048 tokenów. Prompt nie mieścił się w całości, więc
 * model nie miał z czego odpowiedzieć.
 */
class PromptBudgetTest {

    @Test
    fun `krotki kontekst zostaje nietkniety`() {
        val ctx = "Kalendarz: nic na dziś."
        assertEquals(ctx, PromptBudget.fitContext(ctx, "jaka pogoda", 1000))
    }

    @Test
    fun `dlugi kontekst schodzi do limitu razem z pytaniem`() {
        val ctx = (1..200).joinToString("\n") { "wiersz kontekstu numer $it" }
        val question = "co mam dzisiaj w planie"
        val kept = PromptBudget.fitContext(ctx, question, 500)
        assertTrue("za długo: ${kept.length + question.length}", kept.length + question.length <= 500)
        assertTrue("brak dopisku o skróceniu", kept.endsWith("[kontekst skrócony]"))
    }

    @Test
    fun `obcinanie idzie po granicy wiersza`() {
        val ctx = (1..200).joinToString("\n") { "wiersz kontekstu numer $it" }
        val kept = PromptBudget.fitContext(ctx, "pytanie", 500)
        val body = kept.removeSuffix("\n[kontekst skrócony]")
        assertTrue(body.lines().last().startsWith("wiersz kontekstu numer "))
    }

    @Test
    fun `gdy samo pytanie nie miesci sie w oknie kontekst znika`() {
        val question = "x".repeat(400)
        assertEquals("", PromptBudget.fitContext("cokolwiek", question, 300))
    }

    @Test
    fun `zerowy limit nie oddaje niczego`() {
        assertEquals("", PromptBudget.fitContext("cokolwiek", "pytanie", 0))
    }

    @Test
    fun `okno 2048 tokenow daje jakies trzy tysiace znakow`() {
        assertEquals(3072, PromptBudget.charsFor(2048))
    }
}
