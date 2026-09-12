package pl.victor.app.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaThumbnailsTest {

    @Test
    fun `obraz mniejszy od celu nie jest pomniejszany`() {
        assertEquals(1, MediaThumbnails.sampleSizeFor(200, 150, 256))
    }

    @Test
    fun `dzielnik jest zawsze potega dwojki`() {
        for (w in listOf(300, 640, 1000, 4000, 5123)) {
            val sample = MediaThumbnails.sampleSizeFor(w, w / 2, 256)
            assertEquals(
                "dzielnik $sample dla szerokości $w nie jest potęgą dwójki",
                0,
                sample and (sample - 1)
            )
        }
    }

    @Test
    fun `dzielnik zaniza, nigdy nie zawyza`() {
        // 4000 px przy celu 256: /8 daje 500 (za duże, ale ostre),
        // /16 dałoby 250 - czyli MNIEJ niż cel, a tego nie chcemy.
        assertEquals(8, MediaThumbnails.sampleSizeFor(4000, 3000, 256))
    }

    @Test
    fun `dluzszy bok decyduje`() {
        assertEquals(
            MediaThumbnails.sampleSizeFor(4000, 100, 256),
            MediaThumbnails.sampleSizeFor(100, 4000, 256)
        )
    }

    @Test
    fun `bezsensowne wymiary nie wywracaja licznika`() {
        assertEquals(1, MediaThumbnails.sampleSizeFor(0, 0, 256))
        assertEquals(1, MediaThumbnails.sampleSizeFor(-5, 10, 256))
        assertEquals(1, MediaThumbnails.sampleSizeFor(4000, 3000, 0))
    }

    @Test
    fun `typ MIME po rozszerzeniu`() {
        assertEquals("image/jpeg", MediaThumbnails.mimeTypeOf("IMG_0001.JPG"))
        assertEquals("video/mp4", MediaThumbnails.mimeTypeOf("VID_0002.mp4"))
        assertEquals("audio/wav", MediaThumbnails.mimeTypeOf("REC_0003.wav"))
        assertEquals("application/octet-stream", MediaThumbnails.mimeTypeOf("plik-bez-typu"))
        assertEquals("application/octet-stream", MediaThumbnails.mimeTypeOf("bezkropki"))
    }
}
