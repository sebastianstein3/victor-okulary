package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raport z pomiaru strumienia jest jedyną rzeczą, po której da się odróżnić
 * trzy zupełnie różne awarie: okulary nie nadają, nadają w innym ramkowaniu,
 * albo wszystko działa. Te testy pilnują, żeby każda z nich brzmiała inaczej.
 */
class GlassesVoiceCaptureResultTest {

    private fun result(
        packets: Int = 0,
        bytes: Int = 0,
        decoded: Int = 0,
        failed: Int = 0,
        sizes: List<Int> = emptyList(),
        pcm: Int = 0,
        offset: Int = -1
    ) = GlassesVoiceCapture.Result(
        packets = packets,
        bytes = bytes,
        decodedPackets = decoded,
        failedPackets = failed,
        packetSizes = sizes,
        firstPacketHex = null,
        pcmBytes = pcm,
        wav = if (pcm == 0) null else ByteArray(pcm + 44),
        payloadOffset = offset
    )

    @Test
    fun `brak pakietow mowi wprost ze okulary nie nadaja`() {
        val text = result().describe()
        assertTrue(text.contains("ani jednego pakietu"))
    }

    @Test
    fun `pakiety bez dekodowania to inne ramkowanie, nie cisza`() {
        val text = result(packets = 40, bytes = 3200, sizes = List(40) { 80 }).describe()
        assertTrue(text.contains("Pakiety: 40"))
        assertTrue(text.contains("ANI JEDNEGO"))
    }

    @Test
    fun `komplet rozkodowanych podaje czas trwania`() {
        val text = result(
            packets = 50, bytes = 4000, decoded = 50,
            sizes = List(50) { 80 }, pcm = SEKUNDA_PCM
        ).describe()
        assertTrue(text.contains("Rozkodowano wszystko"))
        assertTrue(text.contains("1,0 s") || text.contains("1.0 s"))
    }

    @Test
    fun `czesc odrzuconych jest widoczna`() {
        val text = result(
            packets = 50, bytes = 4000, decoded = 30, failed = 20,
            sizes = List(50) { 80 }, pcm = 96_000
        ).describe()
        assertTrue(text.contains("30 z 50"))
    }

    @Test
    fun `naglowek producenta jest raportowany`() {
        val text = result(
            packets = 10, bytes = 800, decoded = 10,
            sizes = List(10) { 80 }, pcm = 96_000, offset = 2
        ).describe()
        assertTrue(text.contains("2-bajtowy nagłówek"))
    }

    @Test
    fun `zerowe przesuniecie nie dokleja zdania o naglowku`() {
        val text = result(
            packets = 10, bytes = 800, decoded = 10,
            sizes = List(10) { 80 }, pcm = 96_000, offset = 0
        ).describe()
        assertFalse(text.contains("nagłówek producenta"))
    }

    @Test
    fun `krotkie nagranie nie idzie do modelu`() {
        // 0,1 s to nawet nie jedno słowo - zapytanie kosztowałoby na darmo.
        assertFalse(result(packets = 5, decoded = 5, pcm = 9_600).hasAudio)
        assertTrue(result(packets = 50, decoded = 50, pcm = 96_000).hasAudio)
    }

    @Test
    fun `rozmiary pakietow sa streszczane`() {
        val few = result(packets = 3, bytes = 240, sizes = listOf(80, 80, 80)).describe()
        assertTrue(few.contains("rozmiary: 80"))
        val many = result(packets = 7, bytes = 500, sizes = listOf(10, 20, 30, 40, 50, 60, 70))
            .describe()
        assertTrue(many.contains("rozmiary 10-70"))
    }

    @Test
    fun `czas nagrania liczy sie z PCM`() {
        assertEquals(1.0, result(pcm = SEKUNDA_PCM).audioSeconds, 0.0001)
    }

    companion object {
        /**
         * Ile bajtów PCM to DOKŁADNIE jedna sekunda - liczone z częstotliwości
         * dekodera, nie wpisane na sztywno.
         *
         * Stało tu `96_000` z komentarzem "przy 48 kHz mono 16 bit = 1 s".
         * Gdy przestawiłem [OpusDecoder.SAMPLE_RATE] na 16 kHz, te same
         * 96 000 bajtów przestały być sekundą i oba testy oblały - słusznie,
         * bo pilnowały liczby, a nie sekundy. Częstotliwość wróciła potem na
         * 48 kHz (pomiar z dziennika, opis przy samej stałej), a te testy
         * przeszły obie zmiany bez dotykania - i o to w nich chodziło.
         *
         * Gdyby ktoś zepsuł samo LICZENIE czasu, dalej zapalą czerwone.
         */
        private val SEKUNDA_PCM = OpusDecoder.SAMPLE_RATE * 2
    }
}
