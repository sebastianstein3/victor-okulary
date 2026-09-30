package pl.victor.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ramka 0x73 typu 0x01 - PRAWDZIWE bajty z dziennika osoby testującej.
 *
 * W dziennikach z 29 września szła jako "nieobsługiwana ramka notify typ=0x1".
 * Układ pól z aplikacji producenta (galeria, obsługa typu 1).
 */
class MediaCountNotifyTest {

    private fun frame(hex: String): ByteArray =
        hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `liczniki plikow z trzech prawdziwych ramek`() {
        listOf(
            "BC 73 09 00 47 29 01 06 00 03 00 00 00 01 01" to 6,
            "BC 73 09 00 86 E5 01 07 00 03 00 00 00 01 01" to 7,
            "BC 73 09 00 C6 A5 01 08 00 03 00 00 00 01 01" to 8
        ).forEach { (hex, zdjęć) ->
            val event = GlassesProtocol.decodeNotify(frame(hex))
            assertTrue("nadal nieznana: $event", event is NotifyEvent.MediaCountReport)
            event as NotifyEvent.MediaCountReport
            assertEquals(MediaCount(images = zdjęć, videos = 3, records = 0), event.count)
            assertTrue(event.apImportOnly)
        }
    }

    @Test
    fun `liczba ponad 255 czytana jako little endian`() {
        val event = GlassesProtocol.decodeNotify(frame("BC 73 09 00 00 00 01 2C 01 00 00 00 00 01 00"))
            as NotifyEvent.MediaCountReport
        assertEquals(300, event.count.images)
        assertEquals(false, event.apImportOnly)
    }

    @Test
    fun `za krotka ramka nie wywraca dekodera`() {
        val event = GlassesProtocol.decodeNotify(frame("BC 73 09 00 00 00 01 06 00"))
        assertTrue(event is NotifyEvent.Malformed)
    }
}
