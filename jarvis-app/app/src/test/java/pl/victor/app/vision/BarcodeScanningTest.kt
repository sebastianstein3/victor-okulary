package pl.victor.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skanowanie kodu kreskowego - to, co da się sprawdzić bez aparatu.
 *
 * Samego wywołania ML Kit nie da się tu uruchomić, ale wszystkie decyzje wokół
 * niego są arytmetyką i dopasowaniem tekstu. Zgłoszone: "nie rozpoznaje kodów
 * kreskowych" - przy działającym skanowaniu QR.
 */
class BarcodeScanningTest {

    @Test
    fun `duzy obraz skanujemy raz - nie ma czego powiekszac`() {
        assertEquals(listOf(1), BarcodeAttempts.mnożniki(2_000))
        assertEquals(listOf(1), BarcodeAttempts.mnożniki(BarcodeAttempts.PRÓG_MAŁEGO_OBRAZU))
    }

    @Test
    fun `miniature powiekszamy, bo kreska ma tam piksel`() {
        val m = BarcodeAttempts.mnożniki(480)
        assertEquals("pierwsza próba zawsze na oryginale", 1, m.first())
        assertTrue("miniatura ma dostać drugą szansę", m.size > 1)
        assertTrue("powiększenia idą od najtańszego", m == m.sorted())
    }

    @Test
    fun `nie powiekszamy w nieskonczonosc`() {
        val m = BarcodeAttempts.mnożniki(100)
        assertTrue("mnożnik ${m.last()} przekracza limit", m.last() <= BarcodeAttempts.MAX_POWIĘKSZENIE)
        // Powiększony obraz nie może urosnąć bez opamiętania - to pamięć
        // telefonu, a przy serii zdjęć w sklepie kilka takich naraz.
        assertTrue(100 * m.last() <= BarcodeAttempts.PRÓG_MAŁEGO_OBRAZU * 2)
    }

    @Test
    fun `zerowy albo ujemny rozmiar nie wywala sie`() {
        assertEquals(listOf(1), BarcodeAttempts.mnożniki(0))
        assertEquals(listOf(1), BarcodeAttempts.mnożniki(-5))
    }

    @Test
    fun `za waski obraz jest rozpoznawany jako beznadziejny`() {
        // EAN-13 to 95 modułów. Poniżej tej szerokości nie ma o czym mówić i
        // trzeba to powiedzieć, zamiast udawać, że kodu po prostu nie było.
        assertTrue(BarcodeAttempts.beznadziejnyRozmiar(320))
        assertFalse(BarcodeAttempts.beznadziejnyRozmiar(1_280))
        assertFalse("brak obrazu to nie jest ten sam przypadek", BarcodeAttempts.beznadziejnyRozmiar(0))
    }

    @Test
    fun `UPC to kod produktu, numer palety nie`() {
        assertEquals("EAN_13", ProductCode.toKodProduktu("EAN_13"))
        assertEquals("UPC_A", ProductCode.toKodProduktu("UPC_A"))
        assertEquals("UPC_E", ProductCode.toKodProduktu("UPC_E"))
        // Te formaty czytamy, ale niosą numery palet, partii i wypożyczeń.
        // Pytanie o nie Open Food Facts byłoby zawsze chybione.
        assertNull(ProductCode.toKodProduktu("CODE_128"))
        assertNull(ProductCode.toKodProduktu("ITF"))
        assertNull(ProductCode.toKodProduktu("QR_CODE"))
    }

    @Test
    fun `dwunastocyfrowy UPC dopelniamy zerem do EAN-13`() {
        // Open Food Facts trzyma UPC-A jako EAN-13 z wiodącym zerem. Zapytanie
        // o dwanaście cyfr wraca pustką, choć produkt tam jest.
        assertEquals("0036000291452", ProductCode.znormalizuj("036000291452"))
    }

    @Test
    fun `EAN-8 zostaje osmiocyfrowy`() {
        // To osobny, krótszy kod - nie skrócony EAN-13. Dopełnianie go
        // zerami dałoby zapytanie o nieistniejący produkt.
        assertEquals("96385074", ProductCode.znormalizuj("96385074"))
    }

    @Test
    fun `EAN-13 przechodzi bez zmian`() {
        assertEquals("5901234123457", ProductCode.znormalizuj("5901234123457"))
    }

    @Test
    fun `cos, co nie jest cyframi, zostawiamy w spokoju`() {
        assertEquals("https://example.com", ProductCode.znormalizuj("https://example.com"))
    }
}
