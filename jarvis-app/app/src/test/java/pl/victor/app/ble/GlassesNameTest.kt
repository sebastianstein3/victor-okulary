package pl.victor.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rozpoznawanie okularów wśród sparowanych urządzeń.
 *
 * ## Skąd to się wzięło
 * Ze zgłoszenia "okulary nie chcą się połączyć z aplikacją, choć z Bluetooth są
 * połączone". Urządzenie BLE, które JEST połączone, zwykle przestaje rozgłaszać
 * - nie ma po co. Skan go wtedy nie widzi, więc ekran parowania świecił pustką
 * albo cudzymi urządzeniami, choć okulary siedziały na liście sparowanych.
 *
 * Lista sparowanych zawiera jednak też samochód i klawiaturę, stąd ten filtr.
 *
 * Wzorzec jest ze sprzętu: nasz egzemplarz przedstawia się jako "W610T_04C5".
 */
class GlassesNameTest {

    @Test
    fun `nazwa z naszego egzemplarza jest rozpoznawana`() {
        assertTrue(GlassesProtocol.looksLikeGlassesName("W610T_04C5"))
    }

    @Test
    fun `inne wersje po slowie kluczowym`() {
        listOf("HeyCyan Lens", "Smart Glasses", "Okulary AI", "Prism Pro").forEach {
            assertTrue("„$it” powinno wyglądać na okulary", GlassesProtocol.looksLikeGlassesName(it))
        }
    }

    @Test
    fun `typowe sparowane urzadzenia NIE sa okularami`() {
        // To jest cały sens filtra: lista sparowanych ma u każdego kilka pozycji.
        listOf(
            "Moje BMW", "Galaxy Buds", "Klawiatura Logitech",
            "JBL Flip 5", "Mi Band 7", "Samsung TV"
        ).forEach {
            assertFalse("„$it” nie jest okularami", GlassesProtocol.looksLikeGlassesName(it))
        }
    }

    @Test
    fun `pusta nazwa nie przechodzi`() {
        assertFalse(GlassesProtocol.looksLikeGlassesName(""))
        assertFalse(GlassesProtocol.looksLikeGlassesName("   "))
    }

    @Test
    fun `ksztalt wymaga czterech znakow hex`() {
        // Warunek ścisłości: sam podkreślnik nie czyni okularów, bo inaczej
        // wpadłby tu każdy "Glosnik_Kuchnia".
        assertTrue(GlassesProtocol.looksLikeGlassesName("ABC_1A2B"))
        assertFalse(GlassesProtocol.looksLikeGlassesName("Glosnik_Kuchnia"))
        assertFalse(GlassesProtocol.looksLikeGlassesName("ABC_12345"))
    }

    @Test
    fun `nazwa sieci buduje sie z nazwy BLE i adresu`() {
        // Powód, dla którego urządzenie BEZ nazwy nie ma prawa trafić na listę:
        // bez niej nie zbudujemy nazwy sieci Wi-Fi okularów.
        val ssid = GlassesProtocol.glassesApSsid("W610T_04C5", "6A:72:34:AD:04:C5")
        assertTrue("SSID ma zawierać adres: $ssid", ssid.contains("6A7234AD04C5"))
    }
}
