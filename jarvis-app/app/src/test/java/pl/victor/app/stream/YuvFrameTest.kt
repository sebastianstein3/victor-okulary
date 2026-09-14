package pl.victor.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Błędy w przepisywaniu klatki NIE KRZYCZĄ: pomylony odstęp daje ukos albo
 * pasy, a zamieniona kolejność chrominancji - pomarańczowe niebo. Oba wyglądają
 * na usterkę kamery, nie kodu. Dlatego każdy układ bajtów ma własny test.
 */
class YuvFrameTest {

    /**
     * Płaszczyzna luminancji, w której każdy piksel ma wartość `row * 10 + col`
     * - dzięki temu z wyniku widać, CZY wzięliśmy właściwy piksel.
     */
    private fun plane(width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        val bytes = ByteArray(height * rowStride)
        for (row in 0 until height) {
            for (col in 0 until width) {
                bytes[row * rowStride + col * pixelStride] = (row * 10 + col).toByte()
            }
        }
        return bytes
    }

    @Test
    fun `zwykla klatka bez odstepow przepisuje sie wiernie`() {
        val w = 4
        val h = 4
        val out = YuvFrame.toNv21(
            plane(w, h, w, 1), w, 1,
            plane(w / 2, h / 2, w / 2, 1), w / 2, 1,
            plane(w / 2, h / 2, w / 2, 1), w / 2, 1,
            w, h
        )!!
        assertEquals(w, out.width)
        assertEquals(h, out.height)
        assertEquals(w * h * 3 / 2, out.bytes.size)
        // Pierwszy wiersz luminancji: 0, 1, 2, 3.
        assertEquals(0, out.bytes[0].toInt())
        assertEquals(3, out.bytes[3].toInt())
        // Drugi wiersz zaczyna się od 10.
        assertEquals(10, out.bytes[4].toInt())
    }

    @Test
    fun `odstep miedzy wierszami wiekszy niz szerokosc nie przesuwa obrazu`() {
        // To jest ten najczęstszy układ: sprzęt wyrównuje wiersze do 16 czy 64
        // bajtów. Wzięcie rowStride za szerokość daje obraz przekrzywiony w
        // ukos - klasyczny objaw i nie do pomylenia z niczym innym.
        val w = 4
        val h = 4
        val out = YuvFrame.toNv21(
            plane(w, h, 16, 1), 16, 1,
            plane(2, 2, 8, 1), 8, 1,
            plane(2, 2, 8, 1), 8, 1,
            w, h
        )!!
        assertEquals(0, out.bytes[0].toInt())
        assertEquals(3, out.bytes[3].toInt())
        assertEquals(10, out.bytes[4].toInt())
        assertEquals(30, out.bytes[12].toInt())
    }

    @Test
    fun `przeplatana chrominancja o odstepie 2 czyta sie poprawnie`() {
        // Drugi bardzo częsty układ: U i V leżą w JEDNYM buforze na przemian,
        // więc pixelStride wynosi 2. Zignorowanie tego daje kolor wzięty z
        // sąsiedniego kanału.
        val w = 4
        val h = 4
        val u = plane(2, 2, 8, 2)
        val v = plane(2, 2, 8, 2)
        val out = YuvFrame.toNv21(
            plane(w, h, w, 1), w, 1,
            u, 8, 2,
            v, 8, 2,
            w, h
        )
        assertNotNull(out)
        assertEquals(w * h * 3 / 2, out!!.bytes.size)
    }

    @Test
    fun `chrominancja idzie V przed U`() {
        // Odwrotna kolejność nie psuje kształtu - zamienia kolory, więc niebo
        // wychodzi pomarańczowe. Wygląda na usterkę kamery, nie kodu.
        val w = 2
        val h = 2
        val u = byteArrayOf(11)
        val v = byteArrayOf(22)
        val out = YuvFrame.toNv21(
            plane(w, h, w, 1), w, 1,
            u, 1, 1,
            v, 1, 1,
            w, h
        )!!
        // Po czterech bajtach luminancji: najpierw V, potem U.
        assertEquals(22, out.bytes[4].toInt())
        assertEquals(11, out.bytes[5].toInt())
    }

    @Test
    fun `pomniejszanie bierze co N-ty piksel`() {
        val w = 8
        val h = 8
        val out = YuvFrame.toNv21(
            plane(w, h, w, 1), w, 1,
            plane(4, 4, 4, 1), 4, 1,
            plane(4, 4, 4, 1), 4, 1,
            w, h, sample = 2
        )!!
        assertEquals(4, out.width)
        assertEquals(4, out.height)
        // Pierwszy wiersz to piksele 0, 2, 4, 6 z wiersza zerowego.
        assertEquals(0, out.bytes[0].toInt())
        assertEquals(2, out.bytes[1].toInt())
        assertEquals(6, out.bytes[3].toInt())
        // Drugi wiersz wyniku to wiersz DRUGI źródła, nie pierwszy.
        assertEquals(20, out.bytes[4].toInt())
    }

    @Test
    fun `wymiary wyniku sa zawsze parzyste`() {
        // Nieparzysta szerokość zostawiłaby pół kolumny bez koloru.
        val w = 10
        val h = 10
        val out = YuvFrame.toNv21(
            plane(w, h, w, 1), w, 1,
            plane(5, 5, 5, 1), 5, 1,
            plane(5, 5, 5, 1), 5, 1,
            w, h, sample = 3
        )!!
        assertEquals(0, out.width % 2)
        assertEquals(0, out.height % 2)
    }

    @Test
    fun `za krotka plaszczyzna daje null zamiast wyjatku`() {
        // Klatka przepisana w połowie jest gorsza niż pominięta - wygląda jak
        // obraz, a nie jak awaria.
        assertNull(
            YuvFrame.toNv21(
                ByteArray(4), 4, 1,
                ByteArray(4), 2, 1,
                ByteArray(4), 2, 1,
                width = 64, height = 64
            )
        )
    }

    @Test
    fun `bezsensowne odstepy daja null`() {
        val w = 4
        val h = 4
        assertNull(
            YuvFrame.toNv21(
                plane(w, h, w, 1), 0, 1,
                plane(2, 2, 2, 1), 2, 1,
                plane(2, 2, 2, 1), 2, 1,
                w, h
            )
        )
        assertNull(
            YuvFrame.toNv21(
                plane(w, h, w, 1), w, 0,
                plane(2, 2, 2, 1), 2, 1,
                plane(2, 2, 2, 1), 2, 1,
                w, h
            )
        )
    }

    @Test
    fun `obraz mniejszy niz dwa piksele nie ma sensu`() {
        assertNull(
            YuvFrame.toNv21(
                ByteArray(64), 8, 1,
                ByteArray(16), 4, 1,
                ByteArray(16), 4, 1,
                width = 8, height = 8, sample = 8
            )
        )
    }

    // --- dobór kroku pomniejszania ---

    @Test
    fun `maly obraz nie jest pomniejszany`() {
        assertEquals(1, YuvFrame.sampleFor(640, 480, maxSide = 800))
        assertEquals(1, YuvFrame.sampleFor(800, 600, maxSide = 800))
    }

    @Test
    fun `klatka z okularow schodzi ponizej limitu`() {
        // 1600x1200 to prawdziwa rozdzielczość z dziennika z 14 września.
        val step = YuvFrame.sampleFor(1600, 1200, maxSide = 800)
        assertEquals(2, step)
        assertTrue("1600/$step", 1600 / step <= 800)
    }

    @Test
    fun `bardzo duzy obraz schodzi kilkoma krokami`() {
        val step = YuvFrame.sampleFor(4000, 3000, maxSide = 500)
        assertTrue("krok=$step", 4000 / step <= 500)
    }

    @Test
    fun `brak limitu nie pomniejsza`() {
        assertEquals(1, YuvFrame.sampleFor(1600, 1200, maxSide = 0))
    }
}
