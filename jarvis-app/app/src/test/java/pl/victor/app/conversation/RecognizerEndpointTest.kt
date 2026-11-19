package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerEndpointTest {

    /** Poziomy co 50 ms przez [ms], jak onRmsChanged. */
    private fun RecognizerEndpoint.levels(fromMs: Long, ms: Long, db: Float): Long {
        var t = fromMs
        while (t < fromMs + ms) {
            onLevel(db, t)
            t += 50
        }
        return t
    }

    @Test
    fun `pytanie i cisza - koniec po ustawionej ciszy`() {
        val e = RecognizerEndpoint(silenceMs = 1_100)
        var t = e.levels(0, 600, -2f) // cisza przed mową
        e.onPartial("jaka", t)
        t = e.levels(t, 400, 8f)
        e.onPartial("jaka jest pogoda", t)
        t = e.levels(t, 300, 7f)
        val lastLoud = t - 50
        t = e.levels(t, 1_000, -2f)
        assertFalse(e.shouldStop(lastLoud + 1_000))
        e.levels(t, 200, -2f)
        assertTrue(e.shouldStop(lastLoud + 1_100))
    }

    @Test
    fun `przed mowa nigdy nie konczy - czekanie na poczatek bez zmian`() {
        val e = RecognizerEndpoint(silenceMs = 900)
        e.levels(0, 8_000, -2f)
        assertFalse(e.shouldStop(8_000))
    }

    @Test
    fun `jedno slowo nie wystarcza - ogon frazy albo eee`() {
        val e = RecognizerEndpoint(silenceMs = 900)
        e.onPartial("Wiktor", 100)
        e.levels(100, 5_000, -2f)
        assertFalse(e.shouldStop(5_100))
        assertEquals(1, RecognizerEndpoint.wordCount("Wiktor"))
        assertEquals(0, RecognizerEndpoint.wordCount("  ... "))
        assertEquals(3, RecognizerEndpoint.wordCount("która jest godzina?"))
    }

    @Test
    fun `nowy wynik czesciowy zeruje odliczanie - pauza w zdaniu`() {
        val e = RecognizerEndpoint(silenceMs = 1_000)
        e.onPartial("zadzwoń do", 0)
        e.levels(0, 900, -2f)
        e.onPartial("zadzwoń do mamy", 900)
        assertFalse(e.shouldStop(1_500))
        assertTrue(e.shouldStop(1_900))
    }

    @Test
    fun `ten sam tekst czesciowy nie zeruje odliczania`() {
        val e = RecognizerEndpoint(silenceMs = 1_000)
        e.onPartial("włącz latarkę", 0)
        e.onPartial(" włącz latarkę ", 600)
        assertTrue(e.shouldStop(1_000))
    }

    @Test
    fun `glosno mimo braku nowych slow - jeszcze nie koniec`() {
        val e = RecognizerEndpoint(silenceMs = 1_000)
        e.onPartial("opisz co", 0)
        // Ktoś mówi dalej, a silnik jeszcze nie podał słów.
        e.levels(0, 1_500, 9f)
        assertFalse(e.shouldStop(1_500))
    }

    @Test
    fun `w halasie ponad progiem nie konczymy nigdy - konczy silnik`() {
        val e = RecognizerEndpoint(silenceMs = 900)
        e.onLevel(10f, 0)
        e.onPartial("jaka pogoda", 100)
        e.levels(100, 5_000, 6f) // tło 4 dB pod szczytem
        assertFalse(e.shouldStop(5_100))
    }

    @Test
    fun `prog wzgledem szczytu z dolna granica`() {
        val e = RecognizerEndpoint(silenceMs = 900)
        assertEquals(RecognizerEndpoint.MIN_LOUD_DB, e.loudThresholdDb())
        e.onLevel(10f, 0)
        assertEquals(10f - RecognizerEndpoint.DROP_FROM_PEAK_DB, e.loudThresholdDb())
        e.onLevel(Float.NaN, 10)
        assertEquals(4f, e.loudThresholdDb())
    }

    @Test
    fun `po zatrzymaniu pusty wynik zastepuje ostatni czesciowy`() {
        val e = RecognizerEndpoint(silenceMs = 900)
        e.onPartial("jaka jest pogoda", 0)
        assertNull("bez naszego zatrzymania pusty wynik zostaje pusty", e.pickResult(null))
        assertTrue(e.shouldStop(1_000))
        e.markStopped()
        assertFalse("raz", e.shouldStop(2_000))
        assertEquals("jaka jest pogoda", e.pickResult(null))
        assertEquals("jaka jest pogoda", e.pickResult("  "))
        assertEquals("końcowy wygrywa", "Jaka jest pogoda?", e.pickResult("Jaka jest pogoda?"))
    }
}
