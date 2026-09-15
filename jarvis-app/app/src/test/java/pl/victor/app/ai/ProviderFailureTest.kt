package pl.victor.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFailureTest {

    @Test
    fun `brak srodkow w DeepSeeku mowi o srodkach`() {
        val text = ProviderFailure.describe(
            "HTTP 402: {\"error\":{\"message\":\"Insufficient Balance\"}}"
        )
        assertTrue(text, "środków" in text)
    }

    @Test
    fun `limit zapytan mowi o odczekaniu`() {
        val text = ProviderFailure.describe("HTTP 429 Too Many Requests")
        assertTrue(text, "odczekać" in text)
    }

    @Test
    fun `zly klucz Gemini mowi o kluczu`() {
        val text = ProviderFailure.describe(
            "HTTP 400: API key not valid. Please pass a valid API key."
        )
        assertTrue(text, "Klucz API" in text)
    }

    @Test
    fun `brak sieci mowi o sieci`() {
        val text = ProviderFailure.describe(
            "java.net.UnknownHostException: Unable to resolve host \"api.deepseek.com\""
        )
        assertTrue(text, "sieci" in text)
    }

    @Test
    fun `blad przejsciowy prosi o powtorzenie`() {
        val text = ProviderFailure.describe("HTTP 503 service unavailable", retryable = true)
        assertTrue(text, "jeszcze raz" in text)
    }

    @Test
    fun `nieznany blad nie jest pusty i nie cytuje HTTP`() {
        val text = ProviderFailure.describe("HTTP 500 internal server error")
        assertTrue(text.isNotBlank())
        assertTrue(text, "HTTP" !in text)
    }

    @Test
    fun `brak komunikatu tez daje zdanie`() {
        assertTrue(ProviderFailure.describe(null).isNotBlank())
    }

    @Test
    fun `urwanie na limicie radzi zmienic model`() {
        val text = ProviderFailure.describe(
            "DeepSeek urwał odpowiedź na limicie tokenów (finish_reason=length) - " +
                "model zużył budżet, zanim cokolwiek powiedział."
        )
        assertTrue(text, "rozumowanie" in text)
    }

    @Test
    fun `urwanie na limicie nie jest mylone z brakiem srodkow`() {
        val text = ProviderFailure.describe("finish_reason=length")
        assertTrue(text, "środków" !in text)
    }

    @Test
    fun `wielkosc liter nie ma znaczenia`() {
        assertEquals(
            ProviderFailure.describe("INSUFFICIENT BALANCE"),
            ProviderFailure.describe("insufficient balance")
        )
    }

    @Test
    fun `srodki wygrywaja z limitem gdy sa oba`() {
        val text = ProviderFailure.describe("429: insufficient quota for this request")
        assertTrue(text, "środków" in text)
    }

    // --- awaria trwała czy przejściowa ---
    //
    // Na tym rozróżnieniu wisi zachowanie trybów ciągłych: przy awarii trwałej
    // tryb ma się wyłączyć i powiedzieć czemu, przy przejściowej - odczekać i
    // spróbować dalej. Pomyłka w jedną stronę daje pętlę dobijającą się do
    // serwera bez końca, w drugą - tryb gasnący przy chwilowym braku zasięgu.

    @Test
    fun `brak srodkow jest trwaly`() {
        assertTrue(ProviderFailure.isPermanent("402 insufficient balance"))
        assertTrue(ProviderFailure.isPermanent("You exceeded your current quota"))
    }

    @Test
    fun `zly klucz jest trwaly`() {
        assertTrue(ProviderFailure.isPermanent("401 invalid_api_key"))
        assertTrue(ProviderFailure.isPermanent("PERMISSION_DENIED"))
    }

    @Test
    fun `brak sieci NIE jest trwaly`() {
        // Najważniejszy z tych testów: sieć wraca sama, a tryb wyłączony przy
        // wjeździe do tunelu nie włączy się z powrotem po wyjeździe.
        assertFalse(ProviderFailure.isPermanent("failed to connect to api"))
        assertFalse(ProviderFailure.isPermanent("timeout"))
    }

    @Test
    fun `limit zapytan NIE jest trwaly`() {
        assertFalse(ProviderFailure.isPermanent("429 rate limit exceeded"))
    }

    @Test
    fun `nieznany blad NIE jest trwaly`() {
        // Domyślnie ponawiamy: wyłączenie trybu niewidomemu z powodu, którego
        // nie umiem nazwać, jest gorsze niż kilka nieudanych zapytań.
        assertFalse(ProviderFailure.isPermanent("something went wrong"))
        assertFalse(ProviderFailure.isPermanent(null))
        assertFalse(ProviderFailure.isPermanent(""))
    }
}
