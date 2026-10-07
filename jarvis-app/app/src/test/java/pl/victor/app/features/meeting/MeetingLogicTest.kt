package pl.victor.app.features.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class MeetingLogicTest {

    private val hz = 16_000

    /** Ramki po 20 ms: cisza (szum ±50) albo "mowa" (sinus 3000). */
    private fun ramki(ms: Int, mowa: Boolean): List<ShortArray> = (0 until ms / 20).map { i ->
        ShortArray(hz / 50) { j ->
            if (mowa) (3000 * sin((i * 320 + j) * 0.05)).toInt().toShort() else ((j * 37 % 100) - 50).toShort()
        }
    }

    private fun podaj(s: SpeechSegmenter, r: List<ShortArray>): List<ShortArray> = r.mapNotNull { s.podaj(it) }

    @Test
    fun `wypowiedz konczy sie po pauzie`() {
        val s = SpeechSegmenter()
        assertTrue(podaj(s, ramki(1000, false)).isEmpty())
        assertTrue(podaj(s, ramki(2000, true)).isEmpty())
        val wynik = podaj(s, ramki(800, false))
        assertEquals(1, wynik.size)
        val ms = wynik[0].size * 1000 / hz
        assertTrue("$ms ms", ms in 2_300..3_200)
    }

    @Test
    fun `krotki halas odpada, dluga mowa jest cieta`() {
        val s = SpeechSegmenter()
        podaj(s, ramki(1000, false))
        assertTrue(podaj(s, ramki(300, true) + ramki(900, false)).isEmpty())
        val długa = podaj(s, ramki(45_000, true))
        assertEquals(2, długa.size)
        assertNotNull(s.domknij())
        assertNull(SpeechSegmenter().domknij())
    }

    @Test
    fun `podsumowanie na glos i temat`() {
        val p = "TEMAT: Plan wdrożenia aplikacji.\nUSTALENIA:\n- start w listopadzie\n" +
            "ZADANIA:\n- Sebastian: testy okularów (piątek)\n- Ania: opis funkcji\nOTWARTE PYTANIA:\n- brak"
        assertEquals("Plan wdrożenia aplikacji.", MeetingNotes.temat(p))
        assertEquals(2, MeetingNotes.sekcja(p, "ZADANIA").size)
        val g = MeetingNotes.naGłos(p, 12)
        assertTrue(g.contains("Plan wdrożenia aplikacji"))
        assertTrue(g.contains("Zadań: 2"))
        assertTrue(MeetingNotes.naGłos(null, 0).contains("nie udało się nic przepisać"))
    }

    @Test
    fun `polecenie zawiera transkrypcje z czasem`() {
        val p = MeetingNotes.polecenie(listOf(MeetingLine(65_000, "Zaczynamy.")), 600_000)
        assertTrue(p.contains("[01:05] Zaczynamy."))
        assertTrue(p.contains("ZADANIA:"))
    }
}
