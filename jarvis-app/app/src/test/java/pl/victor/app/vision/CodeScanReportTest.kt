package pl.victor.app.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Z dziennika z 23 września: model mówił "kod zasłonięty", "kod niewyraźny" i
 * "nie mam dostępu do bazy produktów" - bo nie wiedział, co zrobiła aplikacja.
 */
class CodeScanReportTest {

    @Test
    fun `zwykle pytanie nic nie dostaje`() {
        assertNull(CodeScanReport.dlaModelu(false, null, false, false, null))
    }

    @Test
    fun `znaleziony produkt nie potrzebuje dopowiedzenia`() {
        assertNull(CodeScanReport.dlaModelu(true, "5901234123457", true, false, null))
    }

    @Test
    fun `kodu nie ma w bazie - model nie moze mowic, ze nie ma dostepu`() {
        val t = CodeScanReport.dlaModelu(true, "5901234123457", false, false, null)!!
        assertTrue(t.contains("5901234123457"))
        assertTrue(t.contains("NIE MA"))
        assertTrue("to było dokładnie zdanie z dziennika", t.contains("Nie mów, że nie masz dostępu"))
    }

    @Test
    fun `miniatura - powod idzie do modelu zamiast zgadywania`() {
        val t = CodeScanReport.dlaModelu(true, null, false, false, "Wi-Fi w telefonie jest wyłączone")!!
        assertTrue(t.contains("MINIATURĄ"))
        assertTrue(t.contains("Wi-Fi w telefonie jest wyłączone"))
        assertTrue("model ma nie wymyślać zasłonięcia", t.contains("nie mów, że kod jest zasłonięty"))
    }

    @Test
    fun `model wie, ze baza produktow istnieje i jak z niej skorzystac`() {
        val t = CodeScanReport.dlaModelu(true, null, false, false, null)!!
        assertTrue(t.contains("Open Food Facts"))
        assertTrue(t.contains("sprawdź produkt"))
    }

    @Test
    fun `przy pelnej rozdzielczosci nie zwala winy na miniature`() {
        val t = CodeScanReport.dlaModelu(true, null, false, true, null)
        assertNotNull(t)
        assertFalse(t!!.contains("MINIATURĄ"))
    }
}
