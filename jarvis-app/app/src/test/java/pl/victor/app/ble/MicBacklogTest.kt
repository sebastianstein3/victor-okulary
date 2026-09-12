package pl.victor.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy bufora pakietów sprzed startu tury.
 *
 * To jedyna część naprawy „pierwsza sekunda pytania ginie", którą da się
 * sprawdzić bez okularów - i zarazem jedyna, w której da się pomylić kolejność
 * albo okno czasu. Pomyłka w którejkolwiek daje objaw nie do odróżnienia od
 * zerwanego transferu: model mówi, że nagranie jest niewyraźne.
 */
class MicBacklogTest {

    private fun p(n: Int) = byteArrayOf(n.toByte())

    @Test
    fun `oddaje pakiety w kolejnosci dodawania`() {
        val backlog = MicBacklog()
        backlog.add(1_000, p(1))
        backlog.add(1_020, p(2))
        backlog.add(1_040, p(3))
        val taken = backlog.drainSince(sinceMs = 900, nowMs = 1_050)
        assertEquals(3, taken.size)
        assertArrayEquals(p(1), taken[0].payload)
        assertArrayEquals(p(2), taken[1].payload)
        assertArrayEquals(p(3), taken[2].payload)
    }

    @Test
    fun `pakiet sprzed wcisniecia przycisku nie nalezy do pytania`() {
        // Okulary nadają także MIĘDZY turami. Ogon poprzedniej wypowiedzi nie
        // może się przykleić do nowego pytania.
        val backlog = MicBacklog()
        backlog.add(1_000, p(1)) // przed przyciskiem
        backlog.add(1_200, p(2)) // po przycisku
        val taken = backlog.drainSince(sinceMs = 1_100, nowMs = 1_300)
        assertEquals(1, taken.size)
        assertArrayEquals(p(2), taken[0].payload)
    }

    @Test
    fun `okno czasu obowiazuje takze przy starym wcisnieciu przycisku`() {
        // Przycisk wciśnięty minutę temu nie czyni dźwięku sprzed minuty
        // częścią pytania - okno liczy się od TERAZ, nie od przycisku.
        val backlog = MicBacklog(windowMs = 1_500)
        backlog.add(10_000, p(1))
        backlog.add(69_000, p(2))
        val taken = backlog.drainSince(sinceMs = 5_000, nowMs = 69_100)
        assertEquals(1, taken.size)
        assertArrayEquals(p(2), taken[0].payload)
    }

    @Test
    fun `odbior czysci caly bufor`() {
        // Także to, co odpadło po czasie: zostawione, doczekałoby następnej
        // tury i tam wyglądałoby już na świeże.
        val backlog = MicBacklog()
        backlog.add(1_000, p(1))
        backlog.add(2_000, p(2))
        backlog.drainSince(sinceMs = 1_900, nowMs = 2_100)
        assertEquals(0, backlog.size)
        assertTrue(backlog.drainSince(sinceMs = 0, nowMs = 2_200).isEmpty())
    }

    @Test
    fun `bufor nie rosnie bez konca`() {
        val backlog = MicBacklog(maxPackets = 3)
        repeat(10) { backlog.add(1_000L + it, p(it)) }
        assertEquals(3, backlog.size)
        // Zostają NAJNOWSZE, bo to one są najbliżej pytania.
        val taken = backlog.drainSince(sinceMs = 0, nowMs = 1_010)
        assertArrayEquals(p(7), taken[0].payload)
        assertArrayEquals(p(9), taken[2].payload)
    }

    @Test
    fun `pusty bufor oddaje pusta liste`() {
        assertTrue(MicBacklog().drainSince(sinceMs = 0, nowMs = 1_000).isEmpty())
    }

    @Test
    fun `clear zapomina wszystko`() {
        val backlog = MicBacklog()
        backlog.add(1_000, p(1))
        backlog.clear()
        assertEquals(0, backlog.size)
    }
}
