package pl.victor.app.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Testy jednostkowe dla SmartModelResolver.
 *
 * Pokrywają wszystkie scenariusze wyboru modelu:
 * - Preferred i aktywny
 * - Preferred i deprecated (ale jeszcze działa)
 * - Preferred i wycofany przez providera (auto-migrate)
 * - Brak preferencji (default)
 * - Nieznany model (custom)
 * - Model z fallbackiem
 */
class SmartModelResolverTest {

    private lateinit var resolver: SmartModelResolver

    @Before
    fun setUp() {
        resolver = SmartModelResolver()
    }

    // === PRZYPADEK 1: Preferowany model, aktywny, istnieje u providera ===

    @Test
    fun `preferred active model returns preferred`() {
        val result = resolver.resolve(
            providerId = "gemini",
            preferredModelId = "gemini-3.5-flash-lite",
            availableFromProvider = listOf("gemini-3.5-flash-lite", "gemini-2.5-pro", "gemini-2.0-flash")
        )

        assertEquals("gemini-3.5-flash-lite", result.modelId)
        assertEquals(ModelSource.PREFERRED, result.source)
        assertNull(result.warning)
    }

    @Test
    fun `preferred active model works without provider validation`() {
        val result = resolver.resolve(
            providerId = "gemini",
            preferredModelId = "gemini-3.5-flash-lite",
            availableFromProvider = emptyList()  // nie sprawdzono
        )

        assertEquals("gemini-3.5-flash-lite", result.modelId)
        assertEquals(ModelSource.PREFERRED, result.source)
        assertNull(result.warning)
    }

    // === PRZYPADEK 2: Preferowany model, deprecated, ale provider jeszcze ma ===

    @Test
    fun `deprecated model still works with warning`() {
        val result = resolver.resolve(
            providerId = "gemini",
            preferredModelId = "gemini-1.5-flash",
            availableFromProvider = listOf("gemini-1.5-flash", "gemini-3.5-flash-lite")
        )

        // Deprecated ale dostępny - używamy go z warning
        assertEquals("gemini-1.5-flash", result.modelId)
        assertEquals(ModelSource.DEPRECATED, result.source)
        assertNotNull(result.warning)
        assertTrue(result.warning is ModelWarning.Deprecated)
        val warning = result.warning as ModelWarning.Deprecated
        assertEquals("gemini-1.5-flash", warning.oldModelId)
        assertEquals("gemini-3.5-flash-lite", warning.newModelId)
    }

    // === PRZYPADEK 3: Deprecated i usunięty przez providera → auto-migrate ===

    @Test
    fun `deprecated model removed by provider auto-migrates to replacement`() {
        val result = resolver.resolve(
            providerId = "gemini",
            preferredModelId = "gemini-1.5-flash",
            availableFromProvider = listOf("gemini-3.5-flash-lite", "gemini-2.5-pro")
        )

        // Provider usunął - automatycznie używamy następcy
        assertEquals("gemini-3.5-flash-lite", result.modelId)
        assertEquals(ModelSource.AUTO_MIGRATED, result.source)
        assertTrue(result.warning is ModelWarning.AutoMigrated)
        val warning = result.warning as ModelWarning.AutoMigrated
        assertEquals("gemini-1.5-flash", warning.oldModelId)
        assertEquals("gemini-3.5-flash-lite", warning.newModelId)
    }

    @Test
    fun `model removed by provider falls back to default`() {
        // Pusta lista oznacza w kontrakcie resolvera "nie sprawdzono u providera",
        // a nie "provider nic nie ma". Żeby zasymulować wycofanie modelu, trzeba
        // podać niepustą listę, która go nie zawiera.
        val result = resolver.resolve(
            providerId = "minimax",
            preferredModelId = "MiniMax-VL-01",
            availableFromProvider = listOf("MiniMax-Text-01")
        )

        assertNotNull(result)
        assertNotEquals(ModelSource.PREFERRED, result.source)
        // Provider go nie ma, więc schodzimy na model domyślny.
        assertEquals("MiniMax-Text-01", result.modelId)
    }

    @Test
    fun `empty provider list means no validation, keeps preferred model`() {
        // Druga strona tego samego kontraktu: brak listy nie może degradować
        // wyboru użytkownika.
        val result = resolver.resolve(
            providerId = "minimax",
            preferredModelId = "MiniMax-VL-01",
            availableFromProvider = emptyList()
        )

        assertEquals(ModelSource.PREFERRED, result.source)
        assertEquals("MiniMax-VL-01", result.modelId)
    }

    // === PRZYPADEK 4: Brak preferencji → default ===

    @Test
    fun `no preferred returns default model`() {
        val result = resolver.resolve(
            providerId = "gemini",
            preferredModelId = null,
            availableFromProvider = listOf("gemini-3.5-flash-lite")
        )

        assertEquals("gemini-3.5-flash-lite", result.modelId)
        assertEquals(ModelSource.DEFAULT, result.source)
        assertNull(result.warning)
    }

    @Test
    fun `no preferred works without provider validation`() {
        val result = resolver.resolve(
            providerId = "openai",
            preferredModelId = null,
            availableFromProvider = emptyList()
        )

        // OpenAI default to gpt-4o-mini
        assertEquals("gpt-4o-mini", result.modelId)
        assertEquals(ModelSource.DEFAULT, result.source)
    }

    // === PRZYPADEK 5: Model nieznany w naszym katalogu, ale provider ma ===

    @Test
    fun `unknown model but provider has it - custom with warning`() {
        val result = resolver.resolve(
            providerId = "openai",
            preferredModelId = "gpt-5-future",  // jeszcze nie ma w katalogu
            availableFromProvider = listOf("gpt-5-future", "gpt-4o-mini")
        )

        assertEquals("gpt-5-future", result.modelId)
        assertEquals(ModelSource.CUSTOM, result.source)
        assertTrue(result.warning is ModelWarning.UnknownModel)
    }

    // === PRZYPADEK 6: Model nieznany u nas i u providera → fallback ===

    @Test
    fun `unknown model and provider does not have it - fallback to default`() {
        val result = resolver.resolve(
            providerId = "openai",
            preferredModelId = "gpt-99-imaginary",
            availableFromProvider = listOf("gpt-4o-mini", "gpt-4o")
        )

        assertEquals("gpt-4o-mini", result.modelId)
        assertEquals(ModelSource.FALLBACK, result.source)
        assertTrue(result.warning is ModelWarning.ModelNotFound)
        val warning = result.warning as ModelWarning.ModelNotFound
        assertEquals("gpt-99-imaginary", warning.modelId)
        assertEquals("gpt-4o-mini", warning.fallbackId)
    }

    // === PRZYPADEK 7: Różne providery ===

    @Test
    fun `claude preferred and active works`() {
        val result = resolver.resolve(
            providerId = "claude",
            preferredModelId = "claude-sonnet-5-5",
            availableFromProvider = listOf("claude-sonnet-5-5", "claude-opus-5-5")
        )

        assertEquals("claude-sonnet-5-5", result.modelId)
        assertEquals(ModelSource.PREFERRED, result.source)
    }

    @Test
    fun `claude deprecated sonnet 3_5 migrates to sonnet 4_5`() {
        val result = resolver.resolve(
            providerId = "claude",
            preferredModelId = "claude-3-5-sonnet-20241022",
            availableFromProvider = listOf("claude-sonnet-5-5")  // stary usunięty
        )

        assertEquals("claude-sonnet-5-5", result.modelId)
        assertEquals(ModelSource.AUTO_MIGRATED, result.source)
    }

    @Test
    fun `minimax preferred active works`() {
        val result = resolver.resolve(
            providerId = "minimax",
            preferredModelId = "MiniMax-Text-01",
            availableFromProvider = listOf("MiniMax-Text-01", "MiniMax-VL-01")
        )

        assertEquals("MiniMax-Text-01", result.modelId)
        assertEquals(ModelSource.PREFERRED, result.source)
    }

    // === PRZYPADEK 8: Edge cases ===

    @Test
    fun `unknown provider returns failed`() {
        val result = resolver.resolve(
            providerId = "unknown-provider",
            preferredModelId = null,
            availableFromProvider = emptyList()
        )

        assertEquals("", result.modelId)
        assertEquals(ModelSource.FAILED, result.source)
        assertNotNull(result.warning)
    }

    @Test
    fun `modelId in our registry but not at provider - fallback`() {
        // gemini-3.6-flash jest w naszym katalogu (aktywny), ale provider go nie ma
        val result = resolver.resolve(
            providerId = "gemini",
            preferredModelId = "gemini-3.6-flash",
            availableFromProvider = listOf("gemini-3.5-flash-lite", "gemini-2.5-pro")
        )

        assertEquals("gemini-3.5-flash-lite", result.modelId)  // fallback do default
        assertEquals(ModelSource.FALLBACK, result.source)
        assertTrue(result.warning is ModelWarning.ModelNotFound)
    }

    // === Testy ModelWarning.toUserMessage ===

    @Test
    fun `Deprecated warning has user friendly message`() {
        val warning = ModelWarning.Deprecated(
            oldModelId = "gpt-3.5-turbo",
            newModelId = "gpt-4o-mini",
            deprecationDate = "2025-12"
        )
        val msg = warning.toUserMessage()
        assertTrue(msg.contains("gpt-3.5-turbo"))
        assertTrue(msg.contains("gpt-4o-mini"))
        assertTrue(msg.contains("2025-12"))
    }

    @Test
    fun `AutoMigrated warning has user friendly message`() {
        val warning = ModelWarning.AutoMigrated(
            oldModelId = "gemini-1.5-pro",
            newModelId = "gemini-2.5-pro"
        )
        val msg = warning.toUserMessage()
        assertTrue(msg.contains("gemini-1.5-pro"))
        assertTrue(msg.contains("gemini-2.5-pro"))
        assertTrue(msg.contains("automatycznie") || msg.contains("wycofany"))
    }

    // === Testy ModelRegistry ===

    @Test
    fun `ModelRegistry has defaults for all providers`() {
        assertNotNull(ModelRegistry.defaultFor("gemini"))
        assertNotNull(ModelRegistry.defaultFor("openai"))
        assertNotNull(ModelRegistry.defaultFor("claude"))
        assertNotNull(ModelRegistry.defaultFor("minimax"))
    }

    @Test
    fun `ModelRegistry forProvider returns sorted models`() {
        val models = ModelRegistry.forProvider("gemini")
        assertTrue(models.isNotEmpty())
        // Aktywne powinny być przed deprecated
        val firstDeprecated = models.indexOfFirst { it.deprecated }
        val lastActive = models.indexOfLast { !it.deprecated }
        if (firstDeprecated != -1 && lastActive != -1) {
            assertTrue("Active models should come before deprecated",
                lastActive < firstDeprecated)
        }
    }

    @Test
    fun `ModelRegistry activeForProvider excludes deprecated`() {
        val active = ModelRegistry.activeForProvider("gemini")
        assertTrue(active.isNotEmpty())
        assertTrue(active.none { it.deprecated })
    }

    @Test
    fun `ModelRegistry findById returns correct model`() {
        val model = ModelRegistry.findById("gemini-3.5-flash-lite")
        assertNotNull(model)
        assertEquals("gemini", model!!.providerId)
        assertFalse(model.deprecated)
    }

    @Test
    fun `ModelRegistry findById returns null for unknown`() {
        assertNull(ModelRegistry.findById("imaginary-model"))
    }

    @Test
    fun `every deprecated model has replacement`() {
        val deprecated = ModelRegistry.ALL_MODELS.filter { it.deprecated }
        deprecated.forEach { model ->
            assertNotNull("Deprecated ${model.id} should have replacement",
                model.replacementId)
            // Replacement powinien być aktywny
            val replacement = ModelRegistry.findById(model.replacementId!!)
            assertNotNull("Replacement ${model.replacementId} not in registry", replacement)
            assertFalse("Replacement should be active", replacement!!.deprecated)
        }
    }
}
