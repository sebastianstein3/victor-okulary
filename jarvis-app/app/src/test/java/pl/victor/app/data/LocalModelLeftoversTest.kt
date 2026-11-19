package pl.victor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sprzątanie po usuniętym modelu lokalnym - kogo ustawić jako dostawcę i
 * które klucze ustawień są osierocone.
 */
class LocalModelLeftoversTest {

    private val order = listOf("gemini", "openai", "claude", "minimax", "deepseek")

    @Test
    fun `inny aktywny dostawca zostaje bez zmian`() {
        assertNull(
            LocalModelLeftovers.replacementProvider("deepseek", order, { true }, "gemini")
        )
    }

    @Test
    fun `brak zapisanego dostawcy zostaje bez zmian`() {
        assertNull(LocalModelLeftovers.replacementProvider(null, order, { true }, "gemini"))
    }

    @Test
    fun `model lokalny przechodzi na pierwszego dostawce z kluczem`() {
        // Klucz tylko do DeepSeeka - Gemini bez klucza dałby komunikat o braku klucza.
        assertEquals(
            "deepseek",
            LocalModelLeftovers.replacementProvider("local", order, { it == "deepseek" }, "gemini")
        )
    }

    @Test
    fun `kolejnosc rozstrzyga przy kilku kluczach`() {
        assertEquals(
            "gemini",
            LocalModelLeftovers.replacementProvider(
                "local", order, { it == "deepseek" || it == "gemini" }, "gemini"
            )
        )
    }

    @Test
    fun `bez zadnego klucza zostaje domyslny`() {
        assertEquals(
            "gemini",
            LocalModelLeftovers.replacementProvider("local", order, { false }, "gemini")
        )
    }

    @Test
    fun `local na liscie dostawcow nie wraca jako nastepca`() {
        // Gdyby lista dostawców z jakiegoś powodu jeszcze go zawierała,
        // przestawienie "local" na "local" niczego by nie naprawiło.
        assertEquals(
            "gemini",
            LocalModelLeftovers.replacementProvider("local", listOf("local"), { true }, "gemini")
        )
    }

    @Test
    fun `osierocone klucze pasuja do przedrostkow z SettingsRepository`() {
        assertEquals(
            listOf("api_key_local", "selected_model_local", "last_validation_local"),
            LocalModelLeftovers.ORPHAN_KEYS
        )
        // Przedrostki czytamy ze źródła - zmiana nazwy klucza bez zmiany tej
        // listy zostawiłaby śmieci na telefonach.
        val src = File("src/main/java/pl/victor/app/data/SettingsRepository.kt")
        if (src.exists()) {
            val text = src.readText()
            assertTrue(text.contains("KEY_API_PREFIX = \"api_key_\""))
            assertTrue(text.contains("KEY_MODEL_PREFIX = \"selected_model_\""))
            assertTrue(text.contains("KEY_VALIDATION_PREFIX = \"last_validation_\""))
        }
    }

    @Test
    fun `dostawca local zniknal z fabryki`() {
        val src = File("src/main/java/pl/victor/app/ai/AIProviderFactory.kt")
        if (src.exists()) {
            val text = src.readText()
            assertFalse(text.contains("\"local\""))
            assertFalse(text.contains("LOCAL_PROVIDER_ID"))
        }
    }
}
