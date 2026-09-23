package pl.victor.app.vision

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Zdjęcie pod kod ma omijać ustawienie "miniatura" - i ma to być WPIĘTE.
 *
 * Z dziennika z 23 września, każda tura o kod: pierwsze zdjęcie 36 kB,
 * "ostrzejsze" 19 kB. Ustawienie "źródło zdjęcia: miniatura" kończyło próbę pod
 * kod, zanim sięgnęła po Wi-Fi. Orkiestratora nie da się tu skompilować (brak
 * biblioteki producenta), więc wiązanie sprawdzamy w źródle.
 */
class CodePhotoPathTest {

    private fun zrodlo(sciezka: String): String? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "jarvis-app/app/$sciezka"), File(dir, sciezka))
                .firstOrNull { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `ustawienie miniatury nie blokuje zdjecia pod kod`() {
        val vm = zrodlo("src/main/java/pl/victor/app/ble/VictorManager.kt")
        assumeTrue("Nie znalazłem VictorManager.kt", vm != null)
        val start = vm!!.indexOf("suspend fun captureSharpPhoto(")
        val miniatura = vm.indexOf("PHOTO_THUMBNAIL", start)
        val warunek = vm.lastIndexOf("if (", miniatura)
        assertTrue(
            "wczesny powrót z miniaturą musi być warunkowany `!forCode`",
            start >= 0 && vm.substring(warunek, miniatura).contains("!forCode")
        )
    }

    @Test
    fun `proba pod kod prosi o forCode`() {
        val orch = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", orch != null)
        val i = orch!!.indexOf("sharpForCode = glassesManager.captureSharpPhoto(")
        assertTrue("brak próby pod kod", i >= 0)
        val wywołanie = orch.substring(i, orch.indexOf(")", i) + 1)
        assertTrue("próba pod kod musi mieć forCode = true", wywołanie.contains("forCode = true"))
    }

    @Test
    fun `model dostaje raport o odczycie kodu`() {
        val orch = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", orch != null)
        assertTrue(
            "bez raportu model zgaduje: \"kod zasłonięty\", \"nie mam dostępu do bazy\"",
            orch!!.contains("CodeScanReport.dlaModelu(")
        )
    }
}
