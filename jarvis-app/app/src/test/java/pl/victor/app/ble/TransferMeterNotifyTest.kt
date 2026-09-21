package pl.victor.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ramka 0x0B - puls co trzy sekundy w trakcie transferu plików.
 *
 * ## Dlaczego przestała być "nieobsługiwana"
 * W dzienniku ramek z 21 września zajmowała ponad dwadzieścia wierszy opisanych
 * jako "Nieobsługiwany typ 0x0B". Taki wiersz czyta się jak usterka, a przy
 * dwudziestu obok siebie zasłania wszystko inne - łącznie z błędami P2P, które
 * padały dokładnie w tych samych oknach.
 *
 * ## Co wiem, a czego nie
 * Wiem, że puls ma stałe trzy sekundy, że drugie okno zaczęło się co do
 * milisekundy razem z odpowiedzią na "Tryb transferu plików (hotspot)", że poza
 * transferem ramka nie pada wcale (128 sekund ciszy między oknami, mimo
 * wciśnięć przycisku i dwóch zdjęć) i że wartości 33-45 są NIEMONOTONICZNE.
 *
 * Ostatnie wyklucza postęp i procent. Reszty nie potwierdziłem, więc kod nie
 * nadaje tej liczbie znaczenia - niesie ją surową.
 */
class TransferMeterNotifyTest {

    /** Prawdziwe bajty z dziennika: `BC 73 02 00 87 5A 0B 26`. */
    private fun ramkaZDziennika(wartosc: Int) = byteArrayOf(
        0xBC.toByte(), 0x73, 0x02, 0x00, 0x87.toByte(), 0x5A, 0x0B, wartosc.toByte()
    )

    @Test
    fun `ramka z dziennika rozkodowuje sie na miernik`() {
        val zdarzenie = GlassesProtocol.decodeNotify(ramkaZDziennika(0x26))
        assertTrue(
            "0x0B ma być rozpoznane, nie odrzucone jako nieznane: $zdarzenie",
            zdarzenie is NotifyEvent.TransferMeter
        )
        assertEquals(38, (zdarzenie as NotifyEvent.TransferMeter).value)
    }

    @Test
    fun `cale okno z dziennika daje wartosci z zakresu pomiaru`() {
        // Dosłownie wartości z drugiego okna (17:20:33 - 17:21:00).
        val zOkna = listOf(0x21, 0x23, 0x25, 0x26, 0x28, 0x27, 0x27, 0x28, 0x2A, 0x28)
        val odczyty = zOkna.map {
            (GlassesProtocol.decodeNotify(ramkaZDziennika(it)) as NotifyEvent.TransferMeter).value
        }
        assertEquals(listOf(33, 35, 37, 38, 40, 39, 39, 40, 42, 40), odczyty)
        // Utrwalone wprost: to NIE jest postęp. Gdyby ktoś kiedyś chciał
        // pokazać z tego pasek postępu, ten wiersz go zatrzyma.
        assertTrue(
            "ciąg jest niemonotoniczny, więc nie wolno czytać go jako postępu",
            odczyty != odczyty.sorted()
        )
    }

    @Test
    fun `krotka ramka nie wywraca dekodowania`() {
        val obcieta = byteArrayOf(0xBC.toByte(), 0x73, 0x02, 0x00, 0x87.toByte(), 0x5A, 0x0B)
        val zdarzenie = GlassesProtocol.decodeNotify(obcieta)
        assertEquals(-1, (zdarzenie as NotifyEvent.TransferMeter).value)
    }
}
