package pl.victor.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Okulary oddają płaską listę nazw - bez typów i dat. Pomyłka w rozpoznaniu
 * typu pokazuje wideo w zakładce ze zdjęciami, a tego nie widać w kodzie ekranu.
 */
class MediaLibraryTest {

    @Test
    fun `rozpoznaje typy po rozszerzeniu`() {
        assertEquals(MediaLibrary.Kind.PHOTO, MediaLibrary.kindOf("IMG_0001.JPG"))
        assertEquals(MediaLibrary.Kind.VIDEO, MediaLibrary.kindOf("VID_0002.mp4"))
        assertEquals(MediaLibrary.Kind.AUDIO, MediaLibrary.kindOf("REC_0003.wav"))
        assertEquals(MediaLibrary.Kind.OTHER, MediaLibrary.kindOf("media.config"))
    }




}
