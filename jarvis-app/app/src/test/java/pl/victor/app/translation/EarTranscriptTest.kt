package pl.victor.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stan panelu tłumaczenia - patrz [EarTranscript]. */
class EarTranscriptTest {

    private val start = EarTranscript().start("en", "pl")

    @Test
    fun `tekst czesciowy nie cofa sie przy krotszej wersji tego samego zdania`() {
        val t = start.zCzęściowym("good morning how").zCzęściowym("good morning")
        assertEquals("good morning how", t.częściowyOryginał)
    }

    @Test
    fun `zupelnie nowe zdanie zastepuje ogon i kasuje jego przeklad`() {
        val t = start.zCzęściowym("good morning how")
            .zCzęściowymPrzekładem("good morning", "dzień dobry")
            .zCzęściowym("where is")
        assertEquals("where is", t.częściowyOryginał)
        assertEquals("", t.częściowyPrzekład)
    }

    @Test
    fun `przeklad czesciowy starego ogona nie trafia do nowego`() {
        val t = start.zCzęściowym("where is the station")
            .zCzęściowymPrzekładem("good morning", "dzień dobry")
        assertEquals("", t.częściowyPrzekład)
    }

    @Test
    fun `gotowy odcinek czysci ogon i dostaje kolejny numer`() {
        val t = start.zCzęściowym("hello")
            .zSegmentem("hello there", "cześć", 1L)
            .zSegmentem("how are you", "jak się masz", 2L)
        assertEquals("", t.częściowyOryginał)
        assertEquals(listOf(1L, 2L), t.segmenty.map { it.id })
        assertEquals("hello there\nhow are you", t.oryginałCały())
        assertEquals("cześć\njak się masz", t.przekładCały())
    }

    @Test
    fun `historia jest przycieta`() {
        var t = start
        repeat(EarTranscript.MAX_SEGMENTÓW + 10) { t = t.zSegmentem("a$it", "b$it", it.toLong()) }
        assertEquals(EarTranscript.MAX_SEGMENTÓW, t.segmenty.size)
        assertEquals("a10", t.segmenty.first().oryginał)
    }

    @Test
    fun `stop zostawia tekst, a czysci ogon i podswietlenie`() {
        val t = start.zSegmentem("hello", "cześć", 1L).czyta(1L).zCzęściowym("next").stop("przycisk")
        assertFalse(t.trwa)
        assertEquals(1, t.segmenty.size)
        assertEquals("", t.częściowyOryginał)
        assertNull(t.czytanyId)
        assertEquals("przycisk", t.komunikat)
    }

    @Test
    fun `tekst do udostepnienia ma pary z godzina`() {
        val t = start.zSegmentem("hello", "cześć", 1L)
        val tekst = t.doUdostępnienia("English", "Polski") { "12:00" }
        assertTrue(tekst.startsWith("Tłumaczenie: English → Polski"))
        assertTrue(tekst.contains("[12:00] hello\n→ cześć"))
    }
}
