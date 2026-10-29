package pl.victor.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiModelsTest {
    @Test
    fun `nowe rodziny uzywaja max_completion_tokens`() {
        listOf("gpt-5", "gpt-5.4-mini", "gpt-6-luna", "gpt-6.1-sol", "o3", "o4-mini", "o1-preview")
            .forEach { assertTrue(it, OpenAiModels.usesCompletionTokens(it)) }
    }

    @Test
    fun `starsze zostaja przy max_tokens`() {
        listOf("gpt-4o-mini", "gpt-4.1", "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo")
            .forEach { assertFalse(it, OpenAiModels.usesCompletionTokens(it)) }
    }
}
