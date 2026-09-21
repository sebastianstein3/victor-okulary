package pl.victor.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Martwy model ma być pytany RAZ, nie co turę.
 *
 * Z dziennika użytkownika: od 21:11 każda tura zaczynała się próbą na
 * `gemini-2.5-flash-lite`, dostawała 404 i schodziła na kolejnego dostawcę -
 * dwadzieścia kilka razy pod rząd, za każdym razem z opóźnieniem doliczonym do
 * odpowiedzi.
 */
class DeadModelsTest {

    @Test
    fun `404 to martwy model`() {
        assertTrue(ProviderFailure.isMissingModel("HTTP 404: model not found"))
        assertTrue(ProviderFailure.isMissingModel("models/gemini-2.5-flash-lite is not found"))
    }

    @Test
    fun `puste konto NIE jest martwym modelem`() {
        // Brak środków bywa niesiony tym samym kodem. Uznanie go za złą nazwę
        // modelu odstawiłoby działającego dostawcę i wysłało człowieka
        // poprawiać coś, co jest w porządku.
        assertFalse(ProviderFailure.isMissingModel("404 insufficient balance"))
        assertFalse(ProviderFailure.isMissingModel("429 rate limit, 404"))
    }

    @Test
    fun `zwykla awaria to nie martwy model`() {
        assertFalse(ProviderFailure.isMissingModel("timeout"))
        assertFalse(ProviderFailure.isMissingModel(null))
    }

    @Test
    fun `ogloszenie idzie raz, zapamietanie zostaje`() {
        val d = DeadModels()
        assertTrue("pierwszy raz ma się ogłosić", d.zapamiętaj("gemini", "gemini-2.5-flash-lite"))
        assertFalse("drugi raz ma milczeć", d.zapamiętaj("gemini", "gemini-2.5-flash-lite"))
        assertTrue(d.czyMartwy("gemini", "gemini-2.5-flash-lite"))
    }

    @Test
    fun `martwy jest MODEL, nie dostawca`() {
        val d = DeadModels()
        d.zapamiętaj("gemini", "gemini-2.5-flash-lite")
        assertFalse(
            "zmiana modelu w ustawieniach ma od razu odblokować dostawcę",
            d.czyMartwy("gemini", "gemini-3.5-flash")
        )
    }

    @Test
    fun `dostawca z martwym modelem idzie na koniec, a nie znika`() {
        val d = DeadModels()
        d.zapamiętaj("gemini", "gemini-2.5-flash-lite")
        val modele = mapOf(
            "gemini" to "gemini-2.5-flash-lite",
            "deepseek" to "deepseek-chat",
            "local" to null
        )
        val kolejka = listOf("gemini", "deepseek", "local")
        assertEquals(
            listOf("deepseek", "local", "gemini"),
            d.przestaw(kolejka) { modele[it] }
        )
    }

    @Test
    fun `gdy wszyscy martwi, kolejka zostaje pelna`() {
        // Usuwanie zamiast przestawiania dałoby pustą listę - i turę padającą
        // na indeksie zamiast na modelu.
        val d = DeadModels()
        d.zapamiętaj("gemini", "a")
        d.zapamiętaj("deepseek", "b")
        val modele = mapOf("gemini" to "a", "deepseek" to "b")
        assertEquals(
            listOf("gemini", "deepseek"),
            d.przestaw(listOf("gemini", "deepseek")) { modele[it] }
        )
    }

    @Test
    fun `kolejnosc zywych zostaje nietknieta`() {
        val d = DeadModels()
        val kolejka = listOf("gemini", "deepseek", "openai")
        assertEquals(kolejka, d.przestaw(kolejka) { "x" })
    }
}
