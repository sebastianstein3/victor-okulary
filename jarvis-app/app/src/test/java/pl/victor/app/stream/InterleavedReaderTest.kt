package pl.victor.app.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Rozjazd o jeden bajt w tym czytniku nie rzuca wyjątkiem - psuje wszystko, co
 * przeczyta się PO nim. Dlatego testy pilnują granic ramek, a nie tylko tego,
 * czy pierwsza się udała.
 */
class InterleavedReaderTest {

    private fun media(channel: Int, body: ByteArray): ByteArray =
        byteArrayOf(0x24, channel.toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body

    private fun reader(vararg chunks: ByteArray) =
        InterleavedReader(ByteArrayInputStream(chunks.reduce { a, b -> a + b }))

    @Test
    fun `ramka binarna oddaje kanal i tresc`() {
        val frame = reader(media(0, byteArrayOf(1, 2, 3))).read() as InterleavedReader.Frame.Media
        assertEquals(0, frame.channel)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.payload)
    }

    @Test
    fun `dwie ramki pod rzad nie zlewaja sie`() {
        val r = reader(media(0, byteArrayOf(1, 1)), media(1, byteArrayOf(2, 2, 2)))
        val a = r.read() as InterleavedReader.Frame.Media
        val b = r.read() as InterleavedReader.Frame.Media
        assertArrayEquals(byteArrayOf(1, 1), a.payload)
        assertEquals(1, b.channel)
        assertArrayEquals(byteArrayOf(2, 2, 2), b.payload)
        assertNull(r.read())
    }

    @Test
    fun `dlugosc ponad 255 czyta sie w calosci`() {
        // Dwubajtowa długość, starszy bajt pierwszy. Wzięcie tylko młodszego
        // działa do 255 bajtów i wykłada się dokładnie na klatkach kluczowych.
        val body = ByteArray(700) { (it % 251).toByte() }
        val frame = reader(media(0, body)).read() as InterleavedReader.Frame.Media
        assertArrayEquals(body, frame.payload)
    }

    @Test
    fun `tresc przychodzaca w kawalkach jest doczytywana`() {
        // Gniazdo oddaje tyle, ile akurat przyszło. Pojedyncze `read` wystarcza
        // przy małych ramkach i zawodzi przy dużych - czyli tam, gdzie boli.
        val body = ByteArray(300) { 7 }
        val full = media(0, body)
        val choppy = object : InputStream() {
            private var i = 0
            override fun read(): Int = if (i < full.size) full[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= full.size) return -1
                // Zawsze po jednym bajcie - najgorszy możliwy przypadek.
                b[off] = full[i++]
                return 1
            }
        }
        val frame = InterleavedReader(choppy).read() as InterleavedReader.Frame.Media
        assertArrayEquals(body, frame.payload)
    }

    @Test
    fun `odpowiedz tekstowa jest rozpoznawana`() {
        val raw = "RTSP/1.0 200 OK\r\nCSeq: 4\r\nSession: abc\r\n\r\n"
        val frame = reader(raw.toByteArray()).read() as InterleavedReader.Frame.Text
        assertEquals(raw, frame.raw)
    }

    @Test
    fun `tresc odpowiedzi jest doczytywana co do bajtu`() {
        // Zostawienie choćby jednego bajtu treści przesuwa KAŻDĄ kolejną ramkę.
        val sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\n"
        val raw = "RTSP/1.0 200 OK\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp"
        val r = reader(raw.toByteArray(), media(0, byteArrayOf(9, 9)))

        val text = r.read() as InterleavedReader.Frame.Text
        assertTrue(text.raw, text.raw.endsWith(sdp))

        val next = r.read() as InterleavedReader.Frame.Media
        assertArrayEquals(byteArrayOf(9, 9), next.payload)
    }

    @Test
    fun `odpowiedz wpleciona miedzy ramki nie rozjezdza strumienia`() {
        // Podtrzymanie sesji przychodzi w środku obrazu. Czytnik zakładający
        // tylko ramki binarne rozjeżdża się tutaj i już się nie pozbiera.
        val keepAlive = "RTSP/1.0 200 OK\r\nCSeq: 9\r\n\r\n"
        val r = reader(
            media(0, byteArrayOf(1)),
            keepAlive.toByteArray(),
            media(0, byteArrayOf(2))
        )
        assertArrayEquals(byteArrayOf(1), (r.read() as InterleavedReader.Frame.Media).payload)
        assertTrue(r.read() is InterleavedReader.Frame.Text)
        assertArrayEquals(byteArrayOf(2), (r.read() as InterleavedReader.Frame.Media).payload)
    }

    @Test
    fun `content-length czyta sie bez wzgledu na wielkosc liter`() {
        val raw = "RTSP/1.0 200 OK\r\ncontent-length: 3\r\n\r\nabc"
        val frame = reader(raw.toByteArray()).read() as InterleavedReader.Frame.Text
        assertTrue(frame.raw, frame.raw.endsWith("abc"))
    }

    @Test
    fun `koniec polaczenia daje null a nie wyjatek`() {
        assertNull(InterleavedReader(ByteArrayInputStream(ByteArray(0))).read())
    }

    @Test
    fun `zerwanie w srodku ramki jest bledem a nie cicha pustka`() {
        // Obcięta ramka oddana jako poprawna to śmieci w dekoderze.
        val truncated = byteArrayOf(0x24, 0, 0, 10, 1, 2)
        try {
            InterleavedReader(ByteArrayInputStream(truncated)).read()
            throw AssertionError("Powinien polecieć wyjątek")
        } catch (e: java.io.EOFException) {
            assertTrue(e.message!!, e.message!!.contains("ramki"))
        }
    }

    @Test
    fun `pusta ramka jest poprawna`() {
        val frame = reader(media(1, ByteArray(0))).read() as InterleavedReader.Frame.Media
        assertEquals(1, frame.channel)
        assertEquals(0, frame.payload.size)
    }
}
