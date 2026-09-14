package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tu chodzi o jedno rozróżnienie: milczący człowiek kontra milczący mikrofon.
 * Dopóki obie sytuacje dawały w dzienniku ten sam wiersz, nie było czego
 * naprawiać - bo nie było wiadomo, czy jest co.
 */
class MicSignalTest {

    @Test
    fun `brak probek to nieruszony mikrofon`() {
        val opis = MicSignal.describe(samples = 0, peakDb = null, speechDetected = false)
        assertTrue(opis, opis.contains("NIE RUSZYŁ"))
    }

    @Test
    fun `same ciche probki to cisza na mikrofonie`() {
        val opis = MicSignal.describe(samples = 120, peakDb = -1.5f, speechDetected = false)
        assertTrue(opis, opis.contains("CISZA"))
    }

    @Test
    fun `wykryta mowa wygrywa z progiem`() {
        // Silnik potrafi uznać mowę przy poziomie poniżej naszego progu -
        // wtedy to ON ma rację, nie próg. Bez tego pierwszeństwa cicha, ale
        // rozpoznana wypowiedź byłaby w dzienniku opisana jako cisza.
        assertEquals(
            "mowa wykryta",
            MicSignal.describe(samples = 40, peakDb = -1f, speechDetected = true)
        )
    }

    @Test
    fun `glosny sygnal bez mowy to trzecia sytuacja`() {
        val opis = MicSignal.describe(samples = 200, peakDb = 6.2f, speechDetected = false)
        assertTrue(opis, opis.contains("sygnał był"))
    }

    @Test
    fun `szczyt bierze najglosniejsza chwile`() {
        val signal = MicSignal()
        signal.noteLevel(-2f)
        signal.noteLevel(7.5f)
        signal.noteLevel(1f)
        assertEquals(7.5f, signal.peakDb!!, 0.001f)
        assertEquals(3, signal.samples)
    }

    @Test
    fun `NaN nie zatruwa szczytu`() {
        // Porównanie z NaN jest zawsze fałszywe, więc bez odsiewu szczyt
        // potrafiłby utknąć na NaN i już nigdy nie pokazać prawdziwego poziomu.
        val signal = MicSignal()
        signal.noteLevel(Float.NaN)
        assertNull(signal.peakDb)
        assertEquals(0, signal.samples)
        signal.noteLevel(3f)
        assertEquals(3f, signal.peakDb!!, 0.001f)
    }

    @Test
    fun `nieskonczonosc tez jest odsiewana`() {
        val signal = MicSignal()
        signal.noteLevel(Float.NEGATIVE_INFINITY)
        signal.noteLevel(Float.POSITIVE_INFINITY)
        assertNull(signal.peakDb)
        assertEquals(0, signal.samples)
    }

    @Test
    fun `swiezy sygnal mowi ze mikrofon nie ruszyl`() {
        assertTrue(MicSignal().verdict().contains("NIE RUSZYŁ"))
    }

    @Test
    fun `gotowosc zapisuje sie raz`() {
        val signal = MicSignal()
        signal.noteReady()
        val first = signal.readyMs
        signal.noteReady()
        assertEquals(first, signal.readyMs)
    }
}
