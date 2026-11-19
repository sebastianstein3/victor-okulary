package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEndWaitTest {

    @Test
    fun `krotkie i normalne skracaja cisze po zdaniu`() {
        assertEquals(900L, SpeechEndWait.SHORT.recognizerSilenceMs)
        assertEquals(1_100L, SpeechEndWait.NORMAL.recognizerSilenceMs)
        assertEquals(900L, SpeechEndWait.SHORT.glassesEnergySilenceMs)
        assertEquals(1_100L, SpeechEndWait.NORMAL.glassesEnergySilenceMs)
        assertEquals(900L, SpeechEndWait.SHORT.glassesPacketSilenceMs)
        assertEquals(1_100L, SpeechEndWait.NORMAL.glassesPacketSilenceMs)
    }

    @Test
    fun `dlugie to dokladnie dawne zachowanie`() {
        assertNull("silnik decyduje sam", SpeechEndWait.LONG.recognizerSilenceMs)
        assertEquals(SpeechEnd.SILENCE_ENDS_SPEECH_MS, SpeechEndWait.LONG.glassesPacketSilenceMs)
        assertEquals(EnergyEndpoint.SILENCE_ENDS_SPEECH_MS, SpeechEndWait.LONG.glassesEnergySilenceMs)
    }

    @Test
    fun `kolejnosc krotkie - normalne - dlugie`() {
        assertTrue(SpeechEndWait.SHORT.glassesEnergySilenceMs < SpeechEndWait.NORMAL.glassesEnergySilenceMs)
        assertTrue(SpeechEndWait.NORMAL.glassesEnergySilenceMs < SpeechEndWait.LONG.glassesEnergySilenceMs)
        assertTrue(SpeechEndWait.NORMAL.glassesPacketSilenceMs < SpeechEndWait.LONG.glassesPacketSilenceMs)
    }

    @Test
    fun `nie rusza czekania na poczatek mowy`() {
        // Karencja od startu i dłuższa cisza po bardzo krótkim dźwięku to
        // zabezpieczenia przed ucięciem pytania, zanim padło - nie zależą od wyboru.
        assertEquals(2_500L, SpeechEnd.MIN_LISTEN_MS)
        val e = EnergyEndpoint()
        repeat(15) { e.add(2_000.0, 20) } // 300 ms dźwięku
        assertEquals(
            EnergyEndpoint.SILENCE_AFTER_SHORT_SPEECH_MS,
            e.requiredSilenceMs(SpeechEndWait.SHORT.glassesEnergySilenceMs)
        )
    }

    @Test
    fun `odczyt z ustawien - nieznane i puste daje domyslne normalne`() {
        assertEquals(SpeechEndWait.SHORT, SpeechEndWait.fromId("short"))
        assertEquals(SpeechEndWait.LONG, SpeechEndWait.fromId("long"))
        assertEquals(SpeechEndWait.NORMAL, SpeechEndWait.fromId(null))
        assertEquals(SpeechEndWait.NORMAL, SpeechEndWait.fromId("bzdura"))
        assertEquals(SpeechEndWait.NORMAL, SpeechEndWait.DEFAULT)
        SpeechEndWait.values().forEach { assertEquals(it, SpeechEndWait.fromId(it.id)) }
    }

    @Test
    fun `opis do dziennika`() {
        assertEquals("normalne (1100 ms)", SpeechEndWait.NORMAL.describe())
        assertEquals("długie (jak dawniej)", SpeechEndWait.LONG.describe())
    }

    @Test
    fun `miernik glosnosci konczy wczesniej przy krotkim czekaniu`() {
        val e = EnergyEndpoint()
        repeat(100) { e.add(1_500.0, 20) } // 2 s mowy
        repeat(50) { e.add(0.0, 20) } // 1 s ciszy
        assertTrue(e.ended(4_000, SpeechEndWait.SHORT.glassesEnergySilenceMs))
        assertTrue(!e.ended(4_000, SpeechEndWait.NORMAL.glassesEnergySilenceMs))
        assertTrue(!e.ended(4_000))
    }
}
