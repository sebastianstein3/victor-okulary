package pl.victor.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dziennik z biegu 152: kod podyktowany cyfra po cyfrze nie był rozpoznawany,
 * a kod sklepowy (prefiks 2) szedł do bazy, w której nigdy go nie będzie.
 */
class DictatedAndShopCodeTest {

    @Test
    fun `kod dyktowany cyfra po cyfrze - dokladnie jak w dzienniku`() {
        assertEquals("2000199317215", EanFromText.zMowy("2 0 0 0 1 9 9 3 1 7 2 1 5"))
    }

    @Test
    fun `kod dyktowany parami i z kropkami`() {
        assertEquals("5901234123457", EanFromText.zMowy("sprawdź produkt 59 01 23 41 23 45 7"))
        assertEquals("5901234123457", EanFromText.zMowy("5.9.0.1.2.3.4.1.2.3.4.5.7"))
    }

    @Test
    fun `data, telefon i zla cyfra kontrolna nie sa kodem`() {
        assertNull(EanFromText.zMowy("spotkanie 12.05.2024 o 17.30"))
        assertNull(EanFromText.zMowy("zadzwoń na 48 123 456 789"))
        assertNull(EanFromText.zMowy("2 0 0 0 1 9 9 3 1 7 2 1 6"))
        assertNull(EanFromText.zMowy("sprawdź produkt 2.00"))
    }

    @Test
    fun `kod drukowany w jednym ciagu nie jest psuty przez wersje mowiona`() {
        // Sześciocyfrowe grupy nie należą do mowy - te łapie EanFromText.find.
        assertNull(EanFromText.zMowy("5 901234 123457"))
        assertEquals("5901234123457", EanFromText.find("5 901234 123457"))
    }

    @Test
    fun `prefiks 2 i 02 to kody wewnetrzne sklepu`() {
        assertTrue(ProductCode.jestWewnętrzny("2000199317215"))
        assertTrue(ProductCode.jestWewnętrzny("0212345678906"))
        assertTrue(ProductCode.jestWewnętrzny("212345678906")) // UPC-A -> 0212...
        assertTrue(ProductCode.jestWewnętrzny("20123451"))
        assertFalse(ProductCode.jestWewnętrzny("5901234123457"))
        assertFalse(ProductCode.jestWewnętrzny("96385074"))
    }

    @Test
    fun `model slyszy, ze kodu sklepu nie ma w zadnej bazie`() {
        val raport = CodeScanReport.dlaModelu(true, "2000199317215", false, true, null)
        assertNotNull(raport)
        assertTrue(raport!!.contains("WEWNĘTRZNY"))
        assertTrue(raport.contains("nie obiecuj"))
    }
}
