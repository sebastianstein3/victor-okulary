package pl.victor.app.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.wakeword.OcenaNagrania.Werdykt
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Ocena jakości nagrań na sztucznym dźwięku: szum tła plus "mowa" (ton z
 * harmonicznymi i drganiem amplitudy) w zadanym miejscu i głośności.
 */
class OcenaNagraniaTest {

    private val sr = 16_000

    /**
     * @param mowa przedziały mowy w ms z amplitudą
     * @param tlo amplituda szumu tła
     */
    private fun nagranie(
        dlugoscMs: Int,
        mowa: List<Triple<Int, Int, Int>>,
        tlo: Int = 30,
        seed: Long = 1
    ): ShortArray {
        val r = Random(seed)
        val n = dlugoscMs * sr / 1000
        return ShortArray(n) { i ->
            var v = r.nextGaussian() * tlo
            val ms = i * 1000 / sr
            for ((od, doMs, amp) in mowa) if (ms in od until doMs) {
                val t = i.toDouble() / sr
                val obwiednia = 0.6 + 0.4 * sin(2 * PI * 4 * t)
                v += amp * obwiednia * (0.6 * sin(2 * PI * 180 * t) + 0.3 * sin(2 * PI * 360 * t) + 0.1 * sin(2 * PI * 900 * t))
            }
            v.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    @Test
    fun `dobra fraza`() {
        val o = OcenaNagrania.ocenFraze(nagranie(3500, listOf(Triple(900, 1700, 6000))))
        assertEquals(Werdykt.DOBRE, o.werdykt)
        assertTrue(o.dobre)
        assertTrue("mowa ${o.mowaMs}", o.mowaMs in 600..900)
        assertNotNull(o.poczatekMowyMs)
        assertTrue("początek ${o.poczatekMowyMs}", o.poczatekMowyMs!! in 860..960)
        assertTrue("koniec ${o.koniecMowyMs}", o.koniecMowyMs!! in 1650..1760)
        assertEquals(3500, o.dlugoscMs)
        assertTrue(o.doDziennika().containsKey("ocena"))
    }

    @Test
    fun `za cicho`() {
        val o = OcenaNagrania.ocenFraze(nagranie(3500, listOf(Triple(900, 1700, 300)), tlo = 3))
        assertEquals(Werdykt.ZA_CICHO, o.werdykt)
    }

    @Test
    fun `przester`() {
        val o = OcenaNagrania.ocenFraze(nagranie(3500, listOf(Triple(900, 1700, 60_000))))
        assertEquals(Werdykt.ZA_GLOSNO, o.werdykt)
        assertTrue(o.przesterowanych > 0)
    }

    @Test
    fun `sam szum bez mowy`() {
        val o = OcenaNagrania.ocenFraze(nagranie(3500, emptyList(), tlo = 800))
        assertEquals(Werdykt.BRAK_MOWY, o.werdykt)
    }

    @Test
    fun `mowa przed napisem Mow teraz`() {
        val o = OcenaNagrania.ocenFraze(nagranie(3500, listOf(Triple(100, 900, 6000))))
        assertEquals(Werdykt.ZA_WCZESNIE, o.werdykt)
    }

    @Test
    fun `fraza ucieta na koncu`() {
        val o = OcenaNagrania.ocenFraze(nagranie(3500, listOf(Triple(2900, 3500, 6000))))
        assertEquals(Werdykt.UCIETE, o.werdykt)
    }

    @Test
    fun `cale zdanie zamiast frazy`() {
        val o = OcenaNagrania.ocenFraze(nagranie(3500, listOf(Triple(600, 3200, 6000))))
        assertEquals(Werdykt.ZA_DLUGO, o.werdykt)
    }

    @Test
    fun `tlo - dobre, za krotkie, cisza`() {
        val mowa = (0 until 12).map { k -> Triple(1000 + k * 2000, 2400 + k * 2000, 5000) }
        assertEquals(Werdykt.DOBRE, OcenaNagrania.ocenTlo(nagranie(25_000, mowa)).werdykt)
        assertEquals(Werdykt.ZA_KROTKO, OcenaNagrania.ocenTlo(nagranie(5_000, mowa)).werdykt)
        assertEquals(Werdykt.ZA_CICHO, OcenaNagrania.ocenTlo(nagranie(20_000, emptyList(), tlo = 2)).werdykt)
    }

    @Test
    fun `puste nagranie nie wywraca`() {
        assertEquals(Werdykt.ZA_CICHO, OcenaNagrania.ocenFraze(ShortArray(0)).werdykt)
        assertEquals(Werdykt.ZA_KROTKO, OcenaNagrania.ocenTlo(ShortArray(100)).werdykt)
    }
}
