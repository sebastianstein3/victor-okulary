package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseLogicTest {

    private fun szum(amplituda: Int, n: Int = 16_000): ShortArray {
        val r = java.util.Random(1)
        return ShortArray(n) { ((r.nextDouble() * 2 - 1) * amplituda).toInt().toShort() }
    }

    @Test
    fun `cichy pokój nie zmienia głośności`() {
        val db = NoiseLogic.tłoDb(szum(30), 16_000, 16_000)!!
        assertTrue(db < -50)
        assertEquals(0f, NoiseLogic.dodatek(db))
        assertEquals(5, NoiseLogic.cel(5, 15, NoiseLogic.dodatek(db)))
    }

    @Test
    fun `gwar podnosi głośność do granicy`() {
        val db = NoiseLogic.tłoDb(szum(3000), 16_000, 16_000)!!
        assertTrue(db > -34)
        assertEquals(15, NoiseLogic.cel(12, 15, NoiseLogic.dodatek(db)))
        assertEquals(10, NoiseLogic.cel(7, 7, 0.45f).coerceAtLeast(10))
    }

    @Test
    fun `pojedynczy trzask nie robi z pokoju ulicy`() {
        val s = szum(30)
        for (i in 0 until 800) s[i] = 30000
        val db = NoiseLogic.tłoDb(s, s.size, 16_000)!!
        assertEquals(0f, NoiseLogic.dodatek(db))
    }

    @Test
    fun `cisza cyfrowa to brak pomiaru`() {
        assertNull(NoiseLogic.tłoDb(ShortArray(16_000), 16_000, 16_000))
    }
}
