package pl.victor.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingLimitTest {
    @Test
    fun `gemini 3 dostaje poziom, nie budżet zero`() {
        assertEquals(ThinkingLimit.Prośba(poziom = "low"), ThinkingLimit.dla("gemini-3-flash-preview"))
        assertEquals(ThinkingLimit.Prośba(poziom = "low"), ThinkingLimit.dla("models/gemini-3.1-pro-preview"))
    }

    @Test
    fun `starsze modele dostają budżet`() {
        assertEquals(ThinkingLimit.Prośba(budżet = 0), ThinkingLimit.dla("gemini-2.5-flash"))
        assertEquals(ThinkingLimit.Prośba(budżet = 128), ThinkingLimit.dla("gemini-2.5-pro"))
    }
}
