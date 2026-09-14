package pl.victor.app.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tu błąd nie krzyczy - daje dekoderowi śmieci udające obraz, a na ekranie
 * wychodzi z tego czarne pole albo zielona kasza. Dlatego każdy kształt
 * pakietu ma własny test, a nie jeden "happy path".
 */
class H264DepacketizerTest {

    private val startCode = byteArrayOf(0, 0, 0, 1)

    private fun nal(type: Int, vararg body: Int): ByteArray =
        byteArrayOf((0x60 or type).toByte()) + body.map { it.toByte() }.toByteArray()

    @Test
    fun `pojedyncza jednostka dostaje znacznik poczatku`() {
        val out = H264Depacketizer().push(nal(1, 0xAA, 0xBB))
        assertEquals(1, out.size)
        assertArrayEquals(startCode + nal(1, 0xAA, 0xBB), out[0])
    }

    @Test
    fun `sklejone jednostki sa rozdzielane`() {
        // STAP-A: bajt nagłówka, potem pary (dwubajtowa długość, treść).
        val sps = nal(7, 0x11)
        val pps = nal(8, 0x22)
        val packet = byteArrayOf(0x78) +
            byteArrayOf(0, sps.size.toByte()) + sps +
            byteArrayOf(0, pps.size.toByte()) + pps

        val out = H264Depacketizer().push(packet)
        assertEquals(2, out.size)
        assertArrayEquals(startCode + sps, out[0])
        assertArrayEquals(startCode + pps, out[1])
    }

    @Test
    fun `parametry obrazu ze strumienia sa zauwazane`() {
        // To jest sedno sprawy: media3 wymaga SPS i PPS w opisie sesji, a te
        // okulary wysyłają je TUTAJ, w strumieniu. Dekoder Androida przyjmuje
        // je tą drogą bez problemu.
        val d = H264Depacketizer()
        assertTrue(!d.sawParameterSets)
        d.push(nal(7, 0x11))
        assertTrue(d.sawParameterSets)
    }

    @Test
    fun `pocieta jednostka sklada sie w calosc`() {
        // FU-A: bajt wskaźnika (typ 28), bajt nagłówka (bity S/E i prawdziwy typ).
        val d = H264Depacketizer()
        val first = byteArrayOf(0x7C, 0x85.toByte(), 0x01, 0x02) // S=1, typ 5
        val middle = byteArrayOf(0x7C, 0x05, 0x03)
        val last = byteArrayOf(0x7C, 0x45, 0x04)

        assertTrue(d.push(first).isEmpty())
        assertTrue(d.push(middle).isEmpty())
        val out = d.push(last)

        assertEquals(1, out.size)
        // Ważność (0x60) z wskaźnika, typ 5 z nagłówka FU.
        assertArrayEquals(startCode + byteArrayOf(0x65, 0x01, 0x02, 0x03, 0x04), out[0])
    }

    @Test
    fun `fragment bez poczatku jest pomijany`() {
        // Początek przepadł w sieci. Sklejanie od środka dałoby dekoderowi
        // kawałek klatki udający całą - gorzej niż brak klatki.
        val d = H264Depacketizer()
        val out = d.push(byteArrayOf(0x7C, 0x45, 0x04))
        assertTrue(out.isEmpty())
        assertEquals(1, d.dropped)
    }

    @Test
    fun `reset przerywa sklejanie`() {
        val d = H264Depacketizer()
        d.push(byteArrayOf(0x7C, 0x85.toByte(), 0x01))
        d.reset()
        // Po zerwaniu połączenia zebrane fragmenty są bezwartościowe.
        assertTrue(d.push(byteArrayOf(0x7C, 0x45, 0x04)).isEmpty())
    }

    @Test
    fun `uszkodzona dlugosc w sklejce nie gubi tego co juz wyjete`() {
        val sps = nal(7, 0x11)
        val packet = byteArrayOf(0x78) +
            byteArrayOf(0, sps.size.toByte()) + sps +
            byteArrayOf(0x7F, 0xFF.toByte()) + byteArrayOf(0x01) // długość poza pakietem

        val d = H264Depacketizer()
        val out = d.push(packet)
        assertEquals(1, out.size)
        assertArrayEquals(startCode + sps, out[0])
        assertEquals(1, d.dropped)
    }

    @Test
    fun `nieznany typ pomija pakiet a nie strumien`() {
        val d = H264Depacketizer()
        assertTrue(d.push(byteArrayOf(0x1A)).isEmpty()) // typ 26, nieobsługiwany
        assertEquals(1, d.dropped)
        // Strumień leci dalej.
        assertEquals(1, d.push(nal(1, 0x01)).size)
    }

    @Test
    fun `pusty pakiet nie wywraca sie`() {
        val d = H264Depacketizer()
        assertTrue(d.push(ByteArray(0)).isEmpty())
        assertEquals(1, d.dropped)
    }

    // --- nagłówek RTP ---

    private fun rtp(
        first: Int = 0x80,
        payloadType: Int = 96,
        body: ByteArray = byteArrayOf(1, 2, 3)
    ): ByteArray = byteArrayOf(first.toByte(), payloadType.toByte()) + ByteArray(10) + body

    @Test
    fun `zwykly pakiet oddaje sama tresc`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), RtpPacket.payload(rtp()))
        assertEquals(96, RtpPacket.payloadType(rtp()))
    }

    @Test
    fun `lista nadawcow przesuwa poczatek tresci`() {
        // Cztery bajty na każdego nadawcę. Pominięcie tego wpuszcza do dekodera
        // kilka bajtów nagłówka udających obraz.
        val packet = byteArrayOf(0x81.toByte(), 96) + ByteArray(10) + ByteArray(4) +
            byteArrayOf(9, 9)
        assertArrayEquals(byteArrayOf(9, 9), RtpPacket.payload(packet))
    }

    @Test
    fun `dlugosc rozszerzenia liczy sie w slowach nie bajtach`() {
        // Cztery bajty nagłówka rozszerzenia plus długość RAZY CZTERY.
        // Pomyłka tutaj psuje każdą klatkę po cichu.
        val packet = byteArrayOf(0x90.toByte(), 96) + ByteArray(10) +
            byteArrayOf(0, 0, 0, 2) + ByteArray(8) + byteArrayOf(7, 7)
        assertArrayEquals(byteArrayOf(7, 7), RtpPacket.payload(packet))
    }

    @Test
    fun `wypelnienie na koncu jest odcinane`() {
        // Licznik wypełnienia stoi w OSTATNIM bajcie i liczy SAM SIEBIE: dwa
        // bajty dopełnienia to wartość 2, nie 2 plus licznik. Pomyłka w tę
        // stronę zabiera z obrazu jeden bajt na pakiet.
        val packet = byteArrayOf(0xA0.toByte(), 96) + ByteArray(10) +
            byteArrayOf(5, 5) + byteArrayOf(0, 2)
        assertArrayEquals(byteArrayOf(5, 5), RtpPacket.payload(packet))
    }

    @Test
    fun `wypelnienie zjadajace cala tresc jest odrzucane`() {
        // Uszkodzony licznik potrafi wskazać poza ładunek - wtedy lepiej nie
        // oddać nic niż oddać kawałek nagłówka jako obraz.
        val packet = byteArrayOf(0xA0.toByte(), 96) + ByteArray(10) +
            byteArrayOf(5, 5, 99)
        assertNull(RtpPacket.payload(packet))
    }

    @Test
    fun `pakiet zbyt krotki albo zlej wersji jest odrzucany`() {
        assertNull(RtpPacket.payload(ByteArray(5)))
        assertNull(RtpPacket.payloadType(ByteArray(5)))
        // Wersja inna niż 2 - to nie jest RTP.
        assertNull(RtpPacket.payload(byteArrayOf(0x40, 96) + ByteArray(10) + byteArrayOf(1)))
    }

    @Test
    fun `pakiet bez tresci jest odrzucany`() {
        assertNull(RtpPacket.payload(byteArrayOf(0x80.toByte(), 96) + ByteArray(10)))
    }
}
