package pl.victor.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progi wybudzenia z dźwięku sprawdzone na LICZBACH Z TERENU.
 *
 * Rozkład okien trzysekundowych z dwóch dzienników:
 *
 *     1-20 pakietów   - 44 razy (szum między turami)
 *     61 pakietów     - 1 raz
 *     125, 150, 150   - 3 razy, jedna seria po "hej lens"
 *
 * Testy odtwarzają OBA końce tego rozkładu, więc pilnują nie samej arytmetyki,
 * tylko tego, że próg dalej rozdziela te dwie rzeczy.
 */
class MicWakeDetectorTest {

    /** Tyle pakietów na sekundę dawał szum między turami (20 na 3 s). */
    private val szumNaSekunde = 7

    /** Tyle dawała seria po frazie (150 na 3 s). */
    private val frazaNaSekunde = 50

    @Test
    fun `szum miedzy turami NIE budzi`() {
        val d = MicWakeDetector()
        var czas = 0L
        var wybudzen = 0
        // Minuta szumu w tempie z dziennika.
        repeat(60) {
            repeat(szumNaSekunde) {
                if (d.onStrayPacket(czas)) wybudzen++
                czas += 1000L / szumNaSekunde
            }
        }
        assertEquals("szum między turami nie może budzić", 0, wybudzen)
    }

    @Test
    fun `seria po frazie budzi`() {
        val d = MicWakeDetector()
        var czas = 0L
        var wybudzen = 0
        // Sześć sekund nadawania, tak jak w dzienniku o 17:17:56-17:18:02.
        repeat(6) {
            repeat(frazaNaSekunde) {
                if (d.onStrayPacket(czas)) wybudzen++
                czas += 1000L / frazaNaSekunde
            }
        }
        assertTrue("seria po frazie ma obudzić", wybudzen >= 1)
    }

    @Test
    fun `jedna fraza to jedno wybudzenie`() {
        // W dzienniku seria zajęła TRZY kolejne okna. Bez karencji byłyby trzy
        // tury i trzy zapytania do modelu.
        val d = MicWakeDetector()
        var czas = 0L
        var wybudzen = 0
        repeat(9) {
            repeat(frazaNaSekunde) {
                if (d.onStrayPacket(czas)) wybudzen++
                czas += 1000L / frazaNaSekunde
            }
        }
        assertEquals("dziewięć sekund jednej serii to nadal jedno wybudzenie", 1, wybudzen)
    }

    @Test
    fun `po karencji kolejna fraza budzi znowu`() {
        val d = MicWakeDetector()
        var czas = 0L
        repeat(MicWakeDetector.WAKE_PACKETS) { d.onStrayPacket(czas); czas += 20 }
        czas += MicWakeDetector.WAKE_COOLDOWN_MS + 1000
        var drugie = false
        repeat(MicWakeDetector.WAKE_PACKETS) {
            if (d.onStrayPacket(czas)) drugie = true
            czas += 20
        }
        assertTrue("po karencji druga fraza ma obudzić", drugie)
    }

    @Test
    fun `pakiety rozlozone w czasie nie sumuja sie przez okno`() {
        // Warunek poprawności pierwszego testu: okno ma się resetować, inaczej
        // dowolnie rzadki szum uzbierałby próg po kilku minutach.
        val d = MicWakeDetector()
        var czas = 0L
        var wybudzen = 0
        repeat(MicWakeDetector.WAKE_PACKETS * 3) {
            if (d.onStrayPacket(czas)) wybudzen++
            czas += MicWakeDetector.WAKE_WINDOW_MS + 100
        }
        assertFalse("rzadkie pakiety nie mogą uzbierać progu", wybudzen > 0)
    }
}
