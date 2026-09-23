package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Obwiednia ma rozstrzygnąć jedno pytanie z dziennika z 23 września: czy w
 * nagraniu z okularów JEST mowa z przerwami, czy tylko chwila dźwięku na
 * początku i zera do końca. Tło i szczyt wyglądają w obu przypadkach tak samo.
 */
class AudioEnvelopeTest {

    private fun pcm(vararg okna: Int, próbekNaOkno: Int = 100): ByteArray {
        val out = ByteArray(okna.size * próbekNaOkno * 2)
        var i = 0
        for (amp in okna) repeat(próbekNaOkno) {
            // Naprzemienny znak, żeby RMS był równy amplitudzie.
            val v = if (i % 2 == 0) amp else -amp
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
            i++
        }
        return out
    }

    @Test
    fun `jedna liczba na okno, rowna glosnosci`() {
        // 100 próbek na okno przy 200 Hz i oknie 500 ms.
        val e = AudioEnvelope.rms(pcm(0, 1000, 0), sampleRate = 200, oknoMs = 500)
        assertEquals(listOf(0, 1000, 0), e)
    }

    @Test
    fun `ujemne probki licza sie tak samo jak dodatnie`() {
        // Błąd znaku przy składaniu bajtów dałby tu śmieci, a nie 3000.
        val e = AudioEnvelope.rms(pcm(3000), sampleRate = 200, oknoMs = 500)
        assertEquals(listOf(3000), e)
    }

    @Test
    fun `cisza na koncu rozpoznaje przypadek bez pytania`() {
        val tylkoPoczątek = AudioEnvelope.rms(pcm(800, 1200, 0, 0, 0, 0), 200, 500)
        assertEquals(4, AudioEnvelope.ciszaNaKońcu(tylkoPoczątek))
        val mowaZPrzerwami = AudioEnvelope.rms(pcm(800, 0, 1200, 0, 900), 200, 500)
        assertEquals(0, AudioEnvelope.ciszaNaKońcu(mowaZPrzerwami))
    }

    @Test
    fun `wpis do dziennika jest krotki`() {
        val e = AudioEnvelope.rms(pcm(*IntArray(100) { 5 }), sampleRate = 200, oknoMs = 500, maxOkien = 30)
        assertTrue(e.size <= 30)
        assertEquals("0 812 0", AudioEnvelope.doDziennika(listOf(0, 812, 0)))
    }

    @Test
    fun `puste albo zle wejscie nie wywala sie`() {
        assertEquals(emptyList<Int>(), AudioEnvelope.rms(ByteArray(0), 48_000))
        assertEquals(emptyList<Int>(), AudioEnvelope.rms(ByteArray(10), 0))
    }
}
