package pl.victor.app.external

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pilnuje, że manifest nadąża za kodem.
 *
 * Android 11+ nie mówi "brak uprawnień" - po prostu UDAJE, że aplikacji nie ma:
 * `getLaunchIntentForPackage` oddaje null, a `setPackage` rzuca wyjątkiem, choć
 * aplikacja jest zainstalowana. Cała lista prób przelatuje wtedy do końca i
 * użytkownik słyszy "nie mam takiej aplikacji", stojąc z nią na ekranie.
 *
 * Dokładnie to zdarzyło się przy dodaniu Jakdojade, Shazama, Ubera, Bolta i
 * Yanosika: kod był gotowy, testy zielone, a funkcja nie miała prawa zadziałać
 * na żadnym współczesnym telefonie. Żaden test kodu tego nie łapał, bo błąd
 * leżał POMIĘDZY kodem a manifestem.
 */
class AppLinksManifestTest {

    private fun manifest(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "jarvis-app/app/src/main/AndroidManifest.xml")
            if (candidate.isFile) return candidate
            val inModule = File(dir, "src/main/AndroidManifest.xml")
            if (inModule.isFile) return inModule
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `kazdy pakiet z AppLinks jest widoczny w manifescie`() {
        val file = manifest()
        // Gdyby test uruchamiać spoza drzewa projektu, lepiej go pominąć niż
        // wywrócić build na czymś, co nie jest błędem w kodzie.
        assumeTrue("Nie znalazłem manifestu", file != null)
        val xml = file!!.readText()
        val missing = AppLinks.Target.entries
            .map { it.packageName }
            .filterNot { xml.contains("<package android:name=\"$it\" />") }
        assertTrue(
            "Brak wpisu <package> w <queries> manifestu dla: $missing - " +
                "na Androidzie 11+ te aplikacje będą niewidoczne mimo instalacji",
            missing.isEmpty()
        )
    }

    @Test
    fun `manifest pyta o aplikacje z pulpitu`() {
        val file = manifest()
        assumeTrue("Nie znalazłem manifestu", file != null)
        val xml = file!!.readText()
        // Bez tego "otwórz dowolną aplikację" widzi tylko listę wpisaną ręcznie.
        assertTrue(
            "Brak zapytania o MAIN/LAUNCHER w <queries>",
            xml.contains("android.intent.category.LAUNCHER")
        )
    }
}
