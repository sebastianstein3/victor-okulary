package pl.victor.app.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Weryfikator głosu na syntetycznych wektorach o kształcie prawdziwego okna
 * (16 x 96 = 1536 cech).
 *
 * Dane udają to, co daje nauka na telefonie: wspólny "głos" (duży wektor
 * obecny w obu klasach - jak barwa głosu i mikrofon), w pozytywach wzór
 * frazy, w negatywach losowa "inna mowa", wszędzie szum. Weryfikator ma
 * nauczyć się wzoru frazy, a nie głosu - dokładnie to, czego podobieństwo
 * cosinusowe by nie umiało.
 */
class WeryfikatorGlosuTest {

    private val d = 16 * 96
    private val los = Random(7)
    private val glos = FloatArray(d) { (los.nextGaussian() * 3).toFloat() }
    private val fraza = FloatArray(d) { los.nextGaussian().toFloat() }

    private fun pozytyw(r: Random) = FloatArray(d) { glos[it] + 0.6f * fraza[it] + r.nextGaussian().toFloat() }
    private fun negatyw(r: Random): FloatArray {
        val inna = FloatArray(d) { r.nextGaussian().toFloat() }
        return FloatArray(d) { glos[it] + 0.6f * inna[it] + r.nextGaussian().toFloat() }
    }

    /** 8 "nagrań" po 5 okien pozytywnych i [negatywow] okien tła. */
    private fun zbior(negatywow: Int = 200, nagran: Int = 8, seed: Long = 1): List<WeryfikatorGlosu.Przyklad> {
        val r = Random(seed)
        val wynik = mutableListOf<WeryfikatorGlosu.Przyklad>()
        repeat(nagran) { g ->
            repeat(5) { k -> wynik += WeryfikatorGlosu.Przyklad(pozytyw(r), true, grupa = g, glowny = k == 2) }
        }
        repeat(negatywow) { wynik += WeryfikatorGlosu.Przyklad(negatyw(r), false, grupa = -1) }
        return wynik
    }

    @Test
    fun `odroznia fraze wlasciciela od jego zwyklej mowy na nowych danych`() {
        val model = WeryfikatorGlosu.naucz(zbior())
        assertEquals(d, model.wymiar)
        val r = Random(99)
        val poz = List(30) { model.ocen(pozytyw(r)) }
        val neg = List(300) { model.ocen(negatyw(r)) }
        assertTrue("pozytywy: $poz", poz.count { it >= 0.5f } >= 27)
        assertTrue("fałszywe akceptacje: ${neg.count { it >= 0.5f }}", neg.count { it >= 0.5f } <= 6)
        // Mocne odrzucenie (< 0,1) dla większości zwykłej mowy - inaczej weto nie zadziała.
        assertTrue(neg.count { it < DecyzjaWybudzenia.PROG_MOCNEGO_ODRZUCENIA } >= 200)
    }

    @Test
    fun `nierownowaga klas - kilka pozytywow wobec setek negatywow nadal przechodzi`() {
        val dane = zbior(negatywow = 600, nagran = 6)
        val model = WeryfikatorGlosu.naucz(dane)
        val r = Random(5)
        val poz = List(20) { model.ocen(pozytyw(r)) }
        assertTrue("pozytywy: $poz", poz.count { it >= 0.5f } >= 17)
    }

    @Test
    fun `uczenie jest deterministyczne`() {
        val a = WeryfikatorGlosu.naucz(zbior())
        val b = WeryfikatorGlosu.naucz(zbior())
        assertArrayEquals(a.wagi, b.wagi, 0f)
        assertEquals(a.wyraz, b.wyraz, 0f)
    }

    @Test
    fun `zapis i odczyt daje ten sam wynik`() {
        val model = WeryfikatorGlosu.naucz(zbior(negatywow = 80))
        val odczyt = WeryfikatorGlosu.zBajtow(model.doBajtow())
        assertNotNull(odczyt)
        val r = Random(3)
        repeat(10) {
            val x = if (it % 2 == 0) pozytyw(r) else negatyw(r)
            assertEquals(model.ocen(x), odczyt!!.ocen(x), 0f)
        }
    }

    @Test
    fun `uszkodzony plik daje null zamiast wyjatku`() {
        val bajty = WeryfikatorGlosu.naucz(zbior(negatywow = 40)).doBajtow()
        assertNull(WeryfikatorGlosu.zBajtow(bajty.copyOf(bajty.size / 2)))
        assertNull(WeryfikatorGlosu.zBajtow(ByteArray(0)))
        assertNull(WeryfikatorGlosu.zBajtow(bajty.copyOf().also { it[0] = 0 }))
    }

    @Test
    fun `ocena okna 16x96 rowna ocenie splaszczonego`() {
        val model = WeryfikatorGlosu.naucz(zbior(negatywow = 40))
        val x = pozytyw(Random(1))
        val okno = Array(16) { i -> x.copyOfRange(i * 96, (i + 1) * 96) }
        assertArrayEquals(x, WeryfikatorGlosu.splaszcz(okno), 0f)
        assertEquals(model.ocen(x), model.ocen(okno), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bez negatywow nie uczy`() {
        WeryfikatorGlosu.naucz(zbior().filter { it.pozytywny })
    }

    @Test
    fun `walidacja krzyzowa odklada cale nagrania i daje sensowne liczby`() {
        val postep = mutableListOf<Int>()
        val w = WeryfikatorGlosu.walidacjaKrzyzowa(zbior(), postep = { k, _ -> postep += k })
        assertNotNull(w)
        assertEquals(8, w!!.rund)
        assertEquals((0..8).toList(), postep)
        assertTrue("trafność pozytywów ${w.trafnoscPozytywow}", w.trafnoscPozytywow >= 0.85f)
        assertTrue("fałszywe ${w.falszyweAkceptacje}", w.falszyweAkceptacje <= 0.05f)
        assertTrue(w.trafnosc >= 0.9f)
    }

    @Test
    fun `walidacja wymaga co najmniej dwoch nagran`() {
        assertNull(WeryfikatorGlosu.walidacjaKrzyzowa(zbior(nagran = 1)))
    }

    @Test
    fun `nieodroznialne dane - walidacja to pokazuje`() {
        // Pozytywy i negatywy z tego samego rozkładu: nie ma czego się nauczyć.
        val r = Random(11)
        val dane = List(8 * 5) { WeryfikatorGlosu.Przyklad(negatyw(r), true, grupa = it / 5, glowny = it % 5 == 2) } +
            List(200) { WeryfikatorGlosu.Przyklad(negatyw(r), false, grupa = -1) }
        val w = WeryfikatorGlosu.walidacjaKrzyzowa(dane)!!
        assertTrue("trafność ${w.trafnosc}", w.trafnosc < 0.75f)
    }
}
