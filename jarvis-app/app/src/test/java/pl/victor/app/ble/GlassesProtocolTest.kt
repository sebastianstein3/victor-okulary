package pl.victor.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy protokołu okularów.
 *
 * Bajty komend i układ ramek notify pochodzą z oficjalnego przewodnika SDK
 * producenta. Te testy pilnują, żeby refaktor albo pomyłka w edycji nie
 * zmieniły ich po cichu - błąd tutaj oznacza okulary, które nie reagują,
 * a bez sprzętu nie da się tego zauważyć inaczej.
 */
class GlassesProtocolTest {

    // === Kodowanie komend ===

    @Test
    fun `zdjecie to 02 01 01`() {
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x01), GlassesProtocol.takePhoto())
    }

    @Test
    fun `start i stop wideo roznia sie tylko trybem`() {
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x02), GlassesProtocol.startVideo())
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x03), GlassesProtocol.stopVideo())
    }

    @Test
    fun `nagrywanie audio uzywa trybow 08 i 0C`() {
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x08), GlassesProtocol.startAudio())
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x0C), GlassesProtocol.stopAudio())
    }

    @Test
    fun `tryb transferu to 02 01 04`() {
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x04), GlassesProtocol.enableTransferMode())
    }

    @Test
    fun `reset p2p to 02 01 0F`() {
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x0F), GlassesProtocol.resetP2p())
    }

    @Test
    fun `zdjecie AI ma piec bajtow z jakoscia podana dwukrotnie`() {
        // PIĘĆ, nie sześć. Szósty bajt "domykający ramkę" był naszym wymysłem;
        // działająca aplikacja referencyjna na tym samym SDK wysyła pięć. Przy
        // sześciu okulary robiły zdjęcie, ale miniatura po nim nie przychodziła.
        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02),
            GlassesProtocol.captureAiPhoto(2)
        )
    }

    @Test
    fun `jakosc miniatury jest przycinana do zakresu`() {
        // Poza zakresem okulary potrafią nie odpowiedzieć w ogóle.
        assertEquals(0, GlassesProtocol.captureAiPhoto(-5)[3].toInt())
        assertEquals(5, GlassesProtocol.captureAiPhoto(99)[3].toInt())
    }

    @Test
    fun `miniatura jest przyjmowana tylko gdy to JPEG`() {
        assertTrue(
            GlassesProtocol.looksLikeJpeg(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00)
            )
        )
        assertFalse(GlassesProtocol.looksLikeJpeg(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        assertFalse(GlassesProtocol.looksLikeJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertFalse(GlassesProtocol.looksLikeJpeg(ByteArray(0)))
        assertFalse(GlassesProtocol.looksLikeJpeg(null))
    }

    // Urwany transfer ma poprawny nagłówek i dotąd wyglądał jak zdrowe zdjęcie.
    // To jest ta różnica, której dziennik nie umiał zapisać.

    @Test
    fun `urwany transfer ma naglowek ale nie jest kompletny`() {
        val truncated = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(900)
        assertTrue(GlassesProtocol.looksLikeJpeg(truncated))
        assertFalse(GlassesProtocol.isCompleteJpeg(truncated))
    }

    @Test
    fun `pelne zdjecie ma znacznik konca`() {
        val complete = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) +
            ByteArray(900) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertTrue(GlassesProtocol.isCompleteJpeg(complete))
    }

    @Test
    fun `krotki ogon po znaczniku konca jest dopuszczalny`() {
        // Firmware bywa, że dokłada wyrównanie za znacznikiem końca.
        val padded = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) +
            ByteArray(900) + byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + ByteArray(8)
        assertTrue(GlassesProtocol.isCompleteJpeg(padded))
    }

    @Test
    fun `to co nie jest JPEG nie jest tez kompletne`() {
        assertFalse(GlassesProtocol.isCompleteJpeg(byteArrayOf(0x00, 0x01, 0x02)))
        assertFalse(GlassesProtocol.isCompleteJpeg(null))
    }

    @Test
    fun `zapytanie o liczbe plikow ma dwa bajty`() {
        assertArrayEquals(byteArrayOf(0x02, 0x04), GlassesProtocol.requestMediaCount())
    }

    // === Dekodowanie ramek notify ===

    /** Buduje ramkę notify: sześć bajtów nagłówka, potem typ i dane. */
    private fun frame(type: Int, vararg payload: Int): ByteArray =
        ByteArray(7 + payload.size).also { out ->
            out[GlassesProtocol.NOTIFY_TYPE_INDEX] = type.toByte()
            payload.forEachIndexed { i, v -> out[7 + i] = v.toByte() }
        }

    @Test
    fun `ramka 02 oznacza gotowe zdjecie`() {
        assertEquals(NotifyEvent.PhotoReady(), GlassesProtocol.decodeNotify(frame(0x02)))
    }

    @Test
    fun `dwojka w bajcie trybu to prosba o opis zdjecia`() {
        // Tak robi aplikacja producenta: bajt 9 równy 2 znaczy "opisz, co
        // widzisz". Bez tego rozróżnienia drugi przycisk okularów albo nie
        // robiłby nic, albo komentowałby każdą zwykłą fotkę.
        val described = GlassesProtocol.decodeNotify(frame(0x02, 0, 0, 2)) as NotifyEvent.PhotoReady
        assertTrue(described.aiVision)
        val plain = GlassesProtocol.decodeNotify(frame(0x02, 0, 0, 1)) as NotifyEvent.PhotoReady
        assertFalse(plain.aiVision)
    }

    @Test
    fun `krotka ramka zdjecia to zwykle zdjecie`() {
        // Starszy firmware może nie dosyłać bajtu trybu. Brak trybu to nie
        // prośba o opis - inaczej każda taka ramka odpalałaby model.
        val event = GlassesProtocol.decodeNotify(frame(0x02)) as NotifyEvent.PhotoReady
        assertFalse(event.aiVision)
    }

    @Test
    fun `ramka 03 z jedynka oznacza wcisniety przycisk`() {
        assertEquals(
            NotifyEvent.ButtonPressed(GlassesProtocol.AI_BUTTON),
            GlassesProtocol.decodeNotify(frame(0x03, 1))
        )
    }

    @Test
    fun `ramka 03 z dwojka niesie numer drugiego przycisku`() {
        // Dotąd przepuszczaliśmy wyłącznie jedynkę, więc drugi przycisk
        // znikał jako "nieznane zdarzenie" - nie dało się go nawet zobaczyć
        // w dzienniku diagnostycznym.
        val event = GlassesProtocol.decodeNotify(frame(0x03, 2)) as NotifyEvent.ButtonPressed
        assertEquals(2, event.button)
    }

    @Test
    fun `ramka 03 z zerem to nie wcisniecie`() {
        // Okulary wysyłają też zdarzenie zwolnienia przycisku - nie może
        // uruchamiać zapytania do AI drugi raz.
        val event = GlassesProtocol.decodeNotify(frame(0x03, 0))
        assertTrue(event is NotifyEvent.Unknown)
    }

    @Test
    fun `ramka 05 niesie poziom baterii i stan ladowania`() {
        val event = GlassesProtocol.decodeNotify(frame(0x05, 87, 1))
        assertEquals(NotifyEvent.Battery(level = 87, charging = true), event)
    }

    @Test
    fun `bateria powyzej 127 nie jest ujemna`() {
        // Bajty w Kotlinie są ze znakiem - bez maskowania 200 wyszłoby jako -56.
        val event = GlassesProtocol.decodeNotify(frame(0x05, 200, 0))
        assertEquals(200, (event as NotifyEvent.Battery).level)
    }

    @Test
    fun `ramka 08 sklada adres IP z czterech bajtow`() {
        val event = GlassesProtocol.decodeNotify(frame(0x08, 192, 168, 49, 33))
        assertEquals("192.168.49.33", (event as NotifyEvent.GlassesIp).ip)
    }

    @Test
    fun `ramka 09 niesie kod bledu p2p`() {
        val event = GlassesProtocol.decodeNotify(frame(0x09, 255))
        assertEquals(255, (event as NotifyEvent.P2pError).code)
    }

    @Test
    fun `ramka 04 niesie trzy postepy OTA`() {
        val event = GlassesProtocol.decodeNotify(frame(0x04, 10, 20, 30))
        assertEquals(NotifyEvent.OtaProgress(download = 10, soc = 20, nor = 30), event)
    }

    @Test
    fun `ramka 0E oznacza malo pamieci`() {
        assertEquals(NotifyEvent.LowMemory, GlassesProtocol.decodeNotify(frame(0x0E)))
    }

    @Test
    fun `nieznany typ nie wywala dekodera`() {
        val event = GlassesProtocol.decodeNotify(frame(0x7A))
        assertEquals(NotifyEvent.Unknown(0x7A), event)
    }

    @Test
    fun `za krotka ramka jest zgloszona jako uszkodzona`() {
        assertTrue(GlassesProtocol.decodeNotify(ByteArray(3)) is NotifyEvent.Malformed)
    }

    @Test
    fun `pusta ramka jest zgloszona jako uszkodzona`() {
        assertTrue(GlassesProtocol.decodeNotify(null) is NotifyEvent.Malformed)
        assertTrue(GlassesProtocol.decodeNotify(ByteArray(0)) is NotifyEvent.Malformed)
    }

    @Test
    fun `bateria bez bajtu ladowania jest uszkodzona`() {
        // Lepiej zgłosić uszkodzoną ramkę niż wpisać przypadkowy stan ładowania.
        assertTrue(GlassesProtocol.decodeNotify(frame(0x05, 50)) is NotifyEvent.Malformed)
    }

    @Test
    fun `niepelny adres IP jest uszkodzony`() {
        assertTrue(GlassesProtocol.decodeNotify(frame(0x08, 192, 168)) is NotifyEvent.Malformed)
    }

    // === Podgląd ramki ===

    @Test
    fun `podglad ramki jest szesnastkowy`() {
        assertEquals("00 FF 10", GlassesProtocol.formatFrame(byteArrayOf(0, -1, 16)))
    }

    @Test
    fun `podglad pustej ramki nie rzuca`() {
        assertEquals("(pusta ramka)", GlassesProtocol.formatFrame(null))
    }

    // === Round-trip: co zbuduje builder, to musi zrozumieć dekoder ===

    @Test
    fun `ramka gotowego zdjecia dekoduje sie na PhotoReady`() {
        assertTrue(GlassesProtocol.decodeNotify(GlassesProtocol.photoReadyFrame())
            is NotifyEvent.PhotoReady)
    }

    @Test
    fun `ramka przycisku dekoduje sie na ButtonPressed`() {
        assertTrue(GlassesProtocol.decodeNotify(GlassesProtocol.buttonPressedFrame())
            is NotifyEvent.ButtonPressed)
    }

    @Test
    fun `ramka zdjecia z prosba o opis przechodzi tam i z powrotem`() {
        val event = GlassesProtocol.decodeNotify(
            GlassesProtocol.photoReadyFrame(aiVision = true)
        ) as NotifyEvent.PhotoReady
        assertTrue(event.aiVision)
    }

    @Test
    fun `jakosc zdjecia dla AI to 02 0B q q`() {
        // Producent ustawia jakość INNĄ rodziną komend: drugi bajt to 0x0B,
        // nie 0x01. Nasze `0x02 0x01 0x06 q q` okulary ignorowały - w dzienniku
        // ze sprzętu nie było po nim ani jednej odpowiedzi.
        assertArrayEquals(
            byteArrayOf(0x02, 0x0B, 0x03, 0x03),
            GlassesProtocol.setAiPhotoQuality(3)
        )
        assertTrue(GlassesProtocol.isAiPhotoQuality(GlassesProtocol.setAiPhotoQuality(0)))
        assertFalse(GlassesProtocol.isAiPhotoQuality(GlassesProtocol.takePhoto()))
    }

    @Test
    fun `jakosc zdjecia jest przycinana do zakresu`() {
        assertArrayEquals(
            byteArrayOf(0x02, 0x0B, 0x05, 0x05),
            GlassesProtocol.setAiPhotoQuality(99)
        )
        assertArrayEquals(
            byteArrayOf(0x02, 0x0B, 0x00, 0x00),
            GlassesProtocol.setAiPhotoQuality(-1)
        )
    }

    @Test
    fun `dluga ramka 0x12 nie jest zmiana glosnosci`() {
        // Ze sprzętu przychodzi co sekundę czternastobajtowa ramka o tym samym
        // pierwszym bajcie. Braliśmy ją za głośność i czytaliśmy bajt DŁUGOŚCI
        // jako poziom - dziennik zapełniał się setkami wpisów "Głośność: 1".
        val long = ByteArray(20).also {
            it[GlassesProtocol.NOTIFY_LENGTH_INDEX] = 0x0E
            it[GlassesProtocol.NOTIFY_TYPE_INDEX] = 0x12
            it[7] = 0x01
        }
        assertTrue(GlassesProtocol.decodeNotify(long) is NotifyEvent.Unknown)

        val short = ByteArray(8).also {
            it[GlassesProtocol.NOTIFY_LENGTH_INDEX] = 0x02
            it[GlassesProtocol.NOTIFY_TYPE_INDEX] = 0x12
            it[7] = 0x2A
        }
        assertEquals(NotifyEvent.VolumeChanged(42), GlassesProtocol.decodeNotify(short))
    }

    @Test
    fun `komenda konca nasluchu to 02 01 0B`() {
        // Dokładnie ta trójka bajtów kończy sesję AI w aplikacji producenta -
        // i jest jedyną rzeczą, która w ogóle coś wysyła do okularów przy
        // kończeniu nasłuchu.
        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0x0B),
            GlassesProtocol.stopAiSession()
        )
    }

    @Test
    fun `ramka baterii zachowuje poziom i stan ladowania`() {
        val event = GlassesProtocol.decodeNotify(
            GlassesProtocol.batteryFrame(64, charging = true)
        ) as NotifyEvent.Battery
        assertEquals(64, event.level)
        assertTrue(event.charging)
    }

    @Test
    fun `poziom baterii poza zakresem jest przycinany`() {
        val low = GlassesProtocol.decodeNotify(
            GlassesProtocol.batteryFrame(-5, charging = false)
        ) as NotifyEvent.Battery
        val high = GlassesProtocol.decodeNotify(
            GlassesProtocol.batteryFrame(150, charging = false)
        ) as NotifyEvent.Battery
        assertEquals(0, low.level)
        assertEquals(100, high.level)
    }

    @Test
    fun `ramka IP zachowuje adres w obie strony`() {
        val event = GlassesProtocol.decodeNotify(
            GlassesProtocol.glassesIpFrame("192.168.49.1")
        ) as NotifyEvent.GlassesIp
        assertEquals("192.168.49.1", event.ip)
    }

    @Test
    fun `ramka IP obsluguje oktety powyzej 127`() {
        // Bajty w Kotlinie są ze znakiem - to tu najłatwiej o pomyłkę.
        val event = GlassesProtocol.decodeNotify(
            GlassesProtocol.glassesIpFrame("255.200.128.1")
        ) as NotifyEvent.GlassesIp
        assertEquals("255.200.128.1", event.ip)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ramka IP odrzuca niepoprawny adres`() {
        GlassesProtocol.glassesIpFrame("192.168.1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ramka IP odrzuca oktet spoza zakresu`() {
        GlassesProtocol.glassesIpFrame("192.168.1.300")
    }

    @Test
    fun `ramka OTA zachowuje trzy liczniki`() {
        val event = GlassesProtocol.decodeNotify(
            GlassesProtocol.otaProgressFrame(10, 20, 30)
        ) as NotifyEvent.OtaProgress
        assertEquals(10, event.download)
        assertEquals(20, event.soc)
        assertEquals(30, event.nor)
    }

    @Test
    fun `ramka bledu P2P zachowuje kod 255`() {
        val event = GlassesProtocol.decodeNotify(
            GlassesProtocol.p2pErrorFrame(255)
        ) as NotifyEvent.P2pError
        assertEquals(255, event.code)
    }

    // === Dekodowanie komend wychodzących ===

    @Test
    fun `workTypeOf rozpoznaje tryb pracy`() {
        assertEquals(GlassesProtocol.WORK_PHOTO, GlassesProtocol.workTypeOf(GlassesProtocol.takePhoto()))
        assertEquals(
            GlassesProtocol.WORK_AI_PHOTO,
            GlassesProtocol.workTypeOf(GlassesProtocol.captureAiPhoto(2))
        )
        assertEquals(
            GlassesProtocol.WORK_AUDIO_STOP,
            GlassesProtocol.workTypeOf(GlassesProtocol.stopAudio())
        )
    }

    @Test
    fun `workTypeOf zwraca null dla zapytania o liczbe plikow`() {
        // To nie jest komenda 0x02 0x01 - ma inny drugi bajt.
        assertNull(GlassesProtocol.workTypeOf(GlassesProtocol.requestMediaCount()))
        assertTrue(GlassesProtocol.isMediaCountRequest(GlassesProtocol.requestMediaCount()))
    }

    @Test
    fun `workTypeOf zwraca null dla smieci`() {
        assertNull(GlassesProtocol.workTypeOf(byteArrayOf()))
        assertNull(GlassesProtocol.workTypeOf(byteArrayOf(0x02)))
        assertNull(GlassesProtocol.workTypeOf(byteArrayOf(0x09, 0x09, 0x09)))
        assertNull(GlassesProtocol.workTypeOf(null))
    }

    @Test
    fun `describeCommand opisuje komendy po polsku`() {
        assertEquals("Zdjęcie", GlassesProtocol.describeCommand(GlassesProtocol.takePhoto()))
        assertEquals(
            "Start nagrywania wideo",
            GlassesProtocol.describeCommand(GlassesProtocol.startVideo())
        )
        assertEquals(
            "Zdjęcie AI z miniaturą (jakość 2)",
            GlassesProtocol.describeCommand(GlassesProtocol.captureAiPhoto(2))
        )
        assertEquals("(pusta komenda)", GlassesProtocol.describeCommand(byteArrayOf()))
    }

}
