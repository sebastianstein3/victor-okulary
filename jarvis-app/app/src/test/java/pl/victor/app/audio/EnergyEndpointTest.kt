package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyEndpointTest {

    /** Dokłada [ms] dźwięku o stałej głośności, ramkami po 20 ms. */
    private fun EnergyEndpoint.feed(rms: Double, ms: Long) {
        repeat((ms / 20).toInt()) { add(rms, 20) }
    }

    @Test
    fun `obwiednia z dziennika - pytanie i cisza po nim`() {
        // victor-2026-10-07 17:45:08: okna 500 ms "0 1 1 0 2963 3030 1618 875 0 0 0 0"
        val e = EnergyEndpoint()
        listOf(0, 1, 1, 0, 2963, 3030, 1618, 875).forEach { e.feed(it.toDouble(), 500) }
        assertFalse("zaraz po mowie to jeszcze nie koniec", e.ended(4_000))
        e.feed(0.0, 1_200)
        assertFalse(e.ended(5_200))
        e.feed(0.0, 100)
        assertTrue(e.ended(5_300))
        assertEquals(2_000, e.voicedMs)
    }

    @Test
    fun `cisza przed pytaniem nie konczy nasluchu`() {
        val e = EnergyEndpoint()
        e.feed(5.0, 6_000)
        assertFalse(e.ended(6_000))
        assertEquals(0, e.voicedMs)
    }

    @Test
    fun `krotki dzwiek na starcie i cisza to nie wypowiedz`() {
        val e = EnergyEndpoint()
        e.feed(2_000.0, 200)
        e.feed(0.0, 3_000)
        assertFalse(e.ended(3_200))
    }

    @Test
    fun `pauza w srodku zdania zeruje cisze`() {
        val e = EnergyEndpoint()
        e.feed(1_500.0, 1_000)
        e.feed(10.0, 1_000)
        e.feed(1_500.0, 600)
        e.feed(10.0, 1_000)
        assertFalse(e.ended(3_600))
        e.feed(10.0, 300)
        assertTrue(e.ended(3_900))
    }

    @Test
    fun `karencja od startu obowiazuje takze po szybkim zdaniu`() {
        val e = EnergyEndpoint()
        e.feed(1_500.0, 1_000)
        e.feed(0.0, 1_400)
        assertFalse(e.ended(2_400))
        assertTrue(e.ended(2_500))
    }

    @Test
    fun `po krotkim dzwieku czekamy dluzej`() {
        // victor-2026-10-06 22:12:19: "159 306 0 1 0 999 693..." - cichy dźwięk,
        // półtorej sekundy ciszy i dopiero pytanie. Nie wolno uciąć przed nim.
        val e = EnergyEndpoint()
        e.feed(159.0, 500)
        e.feed(306.0, 500)
        e.feed(0.0, 1_500)
        assertFalse(e.ended(2_500))
        e.feed(999.0, 500)
        e.feed(693.0, 500)
        e.feed(0.0, 1_300)
        assertTrue(e.ended(4_800))
    }

    @Test
    fun `slabe tlo nie jest mowa`() {
        // victor-2026-10-01 18:07:25: "0 1 0 0 179 0 0 0 0 0 20 4629" - okno 179
        // to nie pytanie; właściwe pada pięć sekund później.
        val e = EnergyEndpoint()
        listOf(0, 1, 0, 0, 179, 0, 0, 0, 0, 0, 20).forEach { e.feed(it.toDouble(), 500) }
        assertFalse(e.ended(5_500))
        assertEquals(0, e.voicedMs)
    }

    @Test
    fun `krotka odpowiedz tez sie konczy tylko pozniej`() {
        val e = EnergyEndpoint()
        e.feed(0.0, 600)
        e.feed(1_800.0, 400)
        e.feed(0.0, 2_400)
        assertFalse(e.ended(3_400))
        e.feed(0.0, 100)
        assertTrue(e.ended(3_500))
    }

    @Test
    fun `trzask nie robi z mowy ciszy`() {
        val e = EnergyEndpoint()
        e.feed(30_000.0, 40)
        // Zwykła mowa po trzasku: gdyby próg szedł za szczytem, byłaby "ciszą".
        e.feed(900.0, 2_000)
        assertFalse(e.ended(3_000))
        assertTrue(e.voicedMs >= 2_000)
    }

    @Test
    fun `w halasie miernik milczy`() {
        val e = EnergyEndpoint()
        e.feed(1_500.0, 1_000)
        // Tło głośniejsze od progu - nigdy nie zgłosimy końca.
        e.feed(450.0, 8_000)
        assertFalse(e.ended(9_000))
    }

    @Test
    fun `rms liczy probki little endian`() {
        // Dwie próbki: 1000 i -1000.
        val pcm = byteArrayOf(0xE8.toByte(), 0x03, 0x18, 0xFC.toByte())
        assertEquals(1000.0, EnergyEndpoint.rms(pcm), 0.001)
        assertEquals(0.0, EnergyEndpoint.rms(ByteArray(0)), 0.0)
    }
}
