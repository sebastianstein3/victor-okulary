package pl.victor.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Długa ramka 0x12 - PRAWDZIWE bajty zDziennika osoby testującej.
 *
 * Wszystkie trzy przyszły ze sprzętu 15 września i wszystkie trzy aplikacja
 * wyrzucała jako "nieobsługiwana ramka notify". Układ pól wzięty z aplikacji
 * producenta (MainActivity, case 18 tablicy skoków po loadData[6]).
 */
class VolumeSettingsNotifyTest {

    private fun frame(hex: String): ByteArray =
        hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // Trzy ramki różnią się WYŁĄCZNIE ostatnim bajtem (02 / 03 / 01) i sumą
    // kontrolną. Reszta stoi w miejscu - to jest cała obserwacja, na której
    // opiera się wniosek, że to ta ostatnia liczba coś znaczy.
    private val zDziennika = listOf(
        "BC 73 0E 00 EB B6 12 01 00 10 0A 02 00 0F 00 03 00 10 0A 02" to 2,
        "BC 73 0E 00 2A 76 12 01 00 10 0A 02 00 0F 00 03 00 10 0A 03" to 3,
        "BC 73 0E 00 AB B7 12 01 00 10 0A 02 00 0F 00 03 00 10 0A 01" to 1
    )

    @Test
    fun `dluga ramka 0x12 przestaje byc nieznana`() {
        for ((hex, _) in zDziennika) {
            val event = GlassesProtocol.decodeNotify(frame(hex))
            assertTrue(
                "ramka nadal nieznana: $hex -> $event",
                event is NotifyEvent.VolumeSettings
            )
        }
    }

    @Test
    fun `dziesiec wartosci, dokladnie z pozycji producenta`() {
        val event = GlassesProtocol.decodeNotify(frame(zDziennika[0].first))
                as NotifyEvent.VolumeSettings
        // Bajty 8,9,10, 12,13,14, 16,17,18,19 - pozycje 7, 11 i 15 to
        // znaczniki grup (1, 2, 3) i producent je pomija.
        assertEquals(listOf(0, 16, 10, 0, 15, 0, 0, 16, 10, 2), event.values)
    }

    @Test
    fun `rozni sie tylko ostatnia liczba`() {
        val decoded = zDziennika.map { (hex, _) ->
            (GlassesProtocol.decodeNotify(frame(hex)) as NotifyEvent.VolumeSettings).values
        }
        val heads = decoded.map { it.dropLast(1) }.distinct()
        assertEquals("dziewięć pierwszych liczb ma stać w miejscu", 1, heads.size)
        assertEquals(listOf(2, 3, 1), decoded.map { it.last() })
    }

    @Test
    fun `krotka ramka 0x12 to nadal pojedynczy poziom glosnosci`() {
        // Warunek rozdzielności: dodanie długiej ramki nie może zabrać
        // obsługi krótkiej, jednowartościowej.
        val event = GlassesProtocol.decodeNotify(frame("BC 73 02 00 C0 80 12 07"))
        assertTrue(event is NotifyEvent.VolumeChanged)
        assertEquals(7, (event as NotifyEvent.VolumeChanged).level)
    }

    @Test
    fun `urwana dluga ramka nie wywala sie`() {
        val event = GlassesProtocol.decodeNotify(frame("BC 73 0E 00 EB B6 12 01 00 10"))
        assertTrue(event is NotifyEvent.Unknown)
    }
}
