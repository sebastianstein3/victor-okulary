package pl.victor.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesVolumeTest {

    /** Wartości z dziennika sprzętu: muzyka 0-16 (10), rozmowa 0-15 (0), system 0-16 (10). */
    private val fromLog = GlassesVolume.fromValues(listOf(0, 16, 10, 0, 15, 0, 0, 16, 10, 1))!!

    @Test
    fun `uklad komendy to dokladnie to co przyslaly okulary`() {
        assertEquals(listOf(0, 16, 10, 0, 15, 0, 0, 16, 10, 1), fromLog.toCommand())
        assertTrue(fromLog.writable)
        assertEquals(62, fromLog.percent())
    }

    @Test
    fun `procent zmienia tylko wartosci biezace mowy`() {
        val max = fromLog.withSpeechPercent(100)
        assertEquals(listOf(0, 16, 16, 0, 15, 15, 0, 16, 10, 1), max.toCommand())
        val half = fromLog.withSpeechPercent(50)
        assertEquals(8, half.music.current)
        assertEquals(8, half.call.current)
    }

    @Test
    fun `kroki nie wychodza poza zakres i nie wyciszaja`() {
        val up = fromLog.withSpeechStepped(1)
        assertEquals(13, up.music.current)
        assertEquals(16, fromLog.withSpeechStepped(5).music.current)
        val down = fromLog.withSpeechStepped(-10)
        assertEquals(1, down.music.current)
        assertEquals(1, down.call.current)
    }

    @Test
    fun `za krotka albo bezsensowna ramka nie nadaje sie do zapisu`() {
        assertNull(GlassesVolume.fromValues(listOf(0, 16, 10)))
        val broken = GlassesVolume.fromValues(listOf(0, 0, 0, 0, 15, 0, 0, 16, 10, 1))!!
        assertFalse(broken.writable)
        // Kanał bez zakresu zostaje nietknięty, zamiast dostać wymyślony poziom.
        assertEquals(0, broken.withSpeechPercent(100).music.current)
        assertFalse(GlassesVolume.fromValues(listOf(0, 255, 10, 0, 15, 0, 0, 16, 10, 1))!!.writable)
    }
}
