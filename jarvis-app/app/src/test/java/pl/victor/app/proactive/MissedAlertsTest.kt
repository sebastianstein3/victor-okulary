package pl.victor.app.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedAlertsTest {

    private var clock = 1_000_000L
    private val missed = MissedAlerts { clock }

    @Test
    fun `swiezy alert wraca raz`() {
        missed.remember("Deszcz za kwadrans.")
        clock += 60_000
        assertEquals(listOf("Deszcz za kwadrans."), missed.takeFresh())
        assertTrue(missed.takeFresh().isEmpty())
    }

    @Test
    fun `stary alert przepada`() {
        missed.remember("Deszcz za kwadrans.")
        clock += MissedAlerts.MAX_AGE_MS + 1
        assertTrue(missed.takeFresh().isEmpty())
    }

    @Test
    fun `powtorka nie dubluje a nadmiar wypycha najstarsze`() {
        missed.remember("A")
        missed.remember("B")
        missed.remember("A")
        assertEquals(listOf("B", "A"), missed.takeFresh())
        missed.remember("1")
        missed.remember("2")
        missed.remember("3")
        assertEquals(listOf("2", "3"), missed.takeFresh())
    }

    @Test
    fun `pusty tekst nie jest alertem`() {
        missed.remember("  ")
        assertTrue(missed.takeFresh().isEmpty())
    }
}
