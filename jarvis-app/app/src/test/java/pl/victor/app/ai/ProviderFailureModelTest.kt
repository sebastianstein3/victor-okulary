package pl.victor.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zła nazwa modelu ma być nazwana po imieniu.
 *
 * Z dziennika z 15 września: w ustawieniach siedziały `deepseek-flash` i
 * `gemini-3.8-flash`. Obie próby padały po 70 ms, aplikacja schodziła na model
 * lokalny (94 s do pierwszego tokenu), a użytkownik widział „AI myśli" i nic.
 * Powód dało się odczytać z odpowiedzi HTTP przez cały czas.
 */
class ProviderFailureModelTest {

    private fun saysUnknownModel(message: String) =
        ProviderFailure.describe(message).contains("model nie istnieje")

    @Test
    fun `komunikaty dostawcow o nieznanym modelu sa rozpoznane`() {
        assertTrue(saysUnknownModel("HTTP 404: model not found"))
        assertTrue(saysUnknownModel("""{"error":{"code":404,"message":"models/gemini-3.8-flash is not found"}}"""))
        assertTrue(saysUnknownModel("Invalid model: deepseek-flash"))
        assertTrue(saysUnknownModel("The model `x` does not exist"))
        assertTrue(saysUnknownModel("unknown model"))
    }

    @Test
    fun `odsyla tam, gdzie to sie zmienia`() {
        assertTrue(ProviderFailure.describe("404 model not found").contains("ustawieniach"))
    }

    @Test
    fun `zly klucz to nadal zly klucz, nie zly model`() {
        // Warunek rozdzielności: gdyby 401/403 wpadało do nowej gałęzi,
        // wysyłalibyśmy człowieka poprawiać model, który jest w porządku.
        assertFalse(saysUnknownModel("HTTP 401 unauthorized"))
        assertTrue(ProviderFailure.describe("HTTP 401 unauthorized").contains("Klucz"))
    }

    @Test
    fun `brak srodkow ma pierwszenstwo przed nieznanym modelem`() {
        // DeepSeek potrafi w jednej odpowiedzi nieść i kod, i słowo "balance".
        // Brak środków jest wtedy prawdziwszym powodem.
        assertTrue(ProviderFailure.describe("402 insufficient balance").contains("środków"))
    }

    @Test
    fun `nieznany model nie jest awaria trwala`() {
        // isPermanent wyłącza tryby ciągłe. Zła nazwa modelu jest do poprawienia
        // w ustawieniach, ale nie jest tym samym co pusty portfel - i nie ma
        // powodu, żeby traktować ją inaczej niż dotąd.
        assertFalse(ProviderFailure.isPermanent("404 model not found"))
    }
}
