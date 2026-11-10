package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.victor.app.conversation.TurnClaim.Verdict

class TurnClaimTest {

    private fun decide(
        turnAlive: Boolean = true,
        insideTurn: Boolean = false,
        takeOver: Boolean = false,
        ageMs: Long = 500
    ) = TurnClaim.whenIdle(turnAlive, insideTurn, takeOver, ageMs, graceMs = 1_500, stuckMs = 180_000)

    @Test
    fun `bez tury wchodzimy zawsze`() {
        assertEquals(Verdict.ENTER, decide(turnAlive = false))
        assertEquals(Verdict.ENTER, decide(turnAlive = false, takeOver = true, ageMs = 0))
    }

    @Test
    fun `tura przekazuje pytanie sama sobie`() {
        assertEquals(Verdict.ENTER, decide(insideTurn = true, ageMs = 0))
        assertEquals(Verdict.ENTER, decide(insideTurn = true, ageMs = 60_000))
    }

    @Test
    fun `obcy trigger w trakcie przygotowania tury nie rusza drugiej`() {
        // Ramka "zdjęcie gotowe" albo briefing - nie przerywają i nie dublują.
        assertEquals(Verdict.REFUSE, decide(takeOver = false, ageMs = 5_000))
    }

    @Test
    fun `podwojna fraza w karencji jest odrzucana a pozniejsze wywolanie przejmuje`() {
        assertEquals(Verdict.REFUSE, decide(takeOver = true, ageMs = 300))
        assertEquals(Verdict.REFUSE, decide(takeOver = true, ageMs = 1_500))
        assertEquals(Verdict.TAKE_OVER, decide(takeOver = true, ageMs = 1_501))
    }

    @Test
    fun `tura ktora utknela ustepuje kazdemu`() {
        assertEquals(Verdict.TAKE_OVER, decide(takeOver = false, ageMs = 180_001))
    }
}
