package pl.victor.app.diagnostics

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Katalog dziennika jest zapisany w DWÓCH miejscach i muszą się zgadzać.
 *
 * `DiagnosticLog.DIR_NAME` mówi, gdzie plik LEŻY, a `res/xml/file_paths.xml`,
 * co FileProvider wolno WYDAĆ na zewnątrz. Gdy się rozjadą, przycisk
 * "Udostępnij dziennik" rzuca `IllegalArgumentException: Failed to find
 * configured root` - czyli dokładnie w chwili, w której ktoś próbuje zgłosić
 * usterkę, dostaje drugą.
 *
 * ## Czemu to jest warte testu
 * Bo droga wysyłki dziennika była do tej pory jedna i wymagała TOKENU GITHUBA.
 * Osoba testująca, która dostaje okulary na tydzień, takiego konta nie założy -
 * i przez to dzienniki po prostu nie docierały, a każda diagnoza stawała w
 * miejscu. Druga droga, bez konta, jest tu rzeczą krytyczną, nie wygodą.
 */
class DiagnosticSharePathTest {

    private fun zrodlo(sciezka: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "jarvis-app/app/$sciezka"), File(dir, sciezka))
                .firstOrNull { it.isFile }
                ?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `FileProvider wydaje dokladnie ten katalog, w ktorym lezy dziennik`() {
        val log = zrodlo("src/main/java/pl/victor/app/diagnostics/DiagnosticLog.kt")
        val paths = zrodlo("src/main/res/xml/file_paths.xml")
        assumeTrue("Nie znalazłem plików", log != null && paths != null)

        val katalog = Regex("""DIR_NAME\s*=\s*"([^"]+)"""")
            .find(log!!.readText())?.groupValues?.get(1)
        assertTrue("Nie znalazłem DIR_NAME w DiagnosticLog", katalog != null)

        // files-path, nie cache-path: DiagnosticLog trzyma sesje w filesDir.
        val wzorzec = Regex(
            """<files-path[^>]*path="${Regex.escape(katalog!!)}/?"[^>]*/>"""
        )
        assertTrue(
            "file_paths.xml nie wydaje katalogu \"$katalog\" jako files-path - " +
                "udostępnianie dziennika padnie na \"Failed to find configured root\"",
            wzorzec.containsMatchIn(paths!!.readText())
        )
    }

    @Test
    fun `ekran diagnostyki oferuje wysylke bez tokenu`() {
        val ustawienia = zrodlo("src/main/java/pl/victor/app/ui/settings/SettingsActivity.kt")
        assumeTrue("Nie znalazłem SettingsActivity", ustawienia != null)
        val tekst = ustawienia!!.readText()

        assertTrue(
            "zniknęła droga wysyłki dziennika nie wymagająca konta - to jedyna, " +
                "z której skorzysta osoba testująca",
            tekst.contains("ACTION_SEND") && tekst.contains("FileProvider.getUriForFile")
        )
    }
}
