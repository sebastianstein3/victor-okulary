package pl.victor.app.translation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Twoja kolej w rozmowie w dwie strony musi słuchać TELEFONEM, nie tylko
 * okularami.
 *
 * Kliknięcie na oprawce brało wyłącznie nagranie z okularów i przepisanie na
 * urządzeniu - a na telefonie użytkownika ta droga nie oddała tekstu ani razu
 * w ponad 250 próbach. Każde "moje zdanie" kończyłoby się "Nie usłyszałem".
 * Orkiestratora nie da się tu skompilować, więc sprawdzamy źródło.
 */
class TwoWayMyTurnTest {

    private fun zrodlo(sciezka: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "jarvis-app/app/$sciezka"), File(dir, sciezka))
                .firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `moja kolej slucha telefonem w moim jezyku`() {
        val plik = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("brak źródła orkiestratora", plik != null)
        val kod = plik!!.readText()
        val start = kod.indexOf("fun myTurn(zOkularów: Boolean)")
        assumeTrue("brak myTurn", start >= 0)
        val ciało = kod.substring(start, kod.indexOf("private val _earTranslation", start))
        assertTrue(
            "kliknięcie na oprawce ma uruchomić rozpoznawanie telefonu",
            ciało.contains("listenUntilSpeechEnds(") && ciało.contains("languageTag = languageTagFor(mój)")
        )
        assertTrue(
            "przekład mojego zdania ma iść głośnikiem telefonu",
            ciało.contains("speakOnPhoneSpeaker(przekład, language = jegoJęzyk)")
        )
    }

    @Test
    fun `jedno klikniecie w rozmowie oddaje glos zamiast konczyc tryb`() {
        val plik = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("brak źródła orkiestratora", plik != null)
        val kod = plik!!.readText()
        assertTrue(
            kod.contains(
                "if (_earTranslation.value && _earTwoWay.value && action == ButtonAction.QUICK_QUESTION) {\n" +
                    "            myTurn(zOkularów = true)"
            )
        )
    }
}
