package pl.victor.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import pl.victor.app.actions.Action
import pl.victor.app.actions.SmartActionDetector
import pl.victor.app.notes.Notes

/**
 * Zachowanie bez sieci, które zastąpiło model lokalny.
 *
 * Z dziennika na Galaxy A23: gemini i deepseek padły na "Unable to resolve
 * host", a model lokalny po 13,8 s wczytywania i 19 s liczenia nie oddał ani
 * tokena. Teraz po takiej turze idą polecenia wzorcami albo krótki komunikat.
 */
class OfflineFallbackTest {

    private val gemini = "Streaming error: Unable to resolve host " +
        "\"generativelanguage.googleapis.com\": No address associated with hostname"
    private val deepseek = "Network error: Unable to resolve host \"api.deepseek.com\""

    @Test
    fun `obaj dostawcy bez DNS to brak sieci`() {
        assertTrue(OfflineFallback.allFailedOffline(listOf(gemini, deepseek)))
    }

    @Test
    fun `zerwane polaczenie w trakcie to tez brak sieci`() {
        assertTrue(
            OfflineFallback.allFailedOffline(
                listOf(
                    "Streaming error: Software caused connection abort",
                    "Network error: failed to connect to api.deepseek.com/1.2.3.4 (port 443)",
                    "Network error: No route to host"
                )
            )
        )
    }

    @Test
    fun `jeden dostawca z odpowiedzia serwera to nie brak sieci`() {
        // DeepSeek odpowiedział 402 - internet BYŁ, zawiodło konto.
        assertFalse(
            OfflineFallback.allFailedOffline(
                listOf(gemini, "HTTP 402: {\"error\":{\"message\":\"Insufficient Balance\"}}")
            )
        )
    }

    @Test
    fun `przekroczony czas tury to nie brak sieci`() {
        // Wyjątek z AIOrchestrator przy limicie czasu na dostawcę - dostawca
        // mógł mielić odpowiedź, sieć mogła działać.
        assertFalse(
            OfflineFallback.allFailedOffline(listOf("Dostawca gemini nie odpowiedział w 45 s"))
        )
    }

    @Test
    fun `pusta lista prob to nie brak sieci`() {
        assertFalse(OfflineFallback.allFailedOffline(emptyList()))
    }

    @Test
    fun `brak komunikatu wyjatku to nie brak sieci`() {
        assertFalse(OfflineFallback.allFailedOffline(listOf(null)))
    }

    @Test
    fun `komunikat mowi o internecie i wylicza przyklady`() {
        val s = OfflineFallback.SPEECH
        assertTrue(s, s.startsWith("Nie mam internetu"))
        OfflineFallback.EXAMPLES.forEach { assertTrue("brak przykładu: $it", it in s) }
        // Mówione w okularach - ma być krótkie.
        assertTrue("za długie: ${s.length}", s.length < 220)
    }

    /**
     * Każdy przykład z komunikatu MUSI działać bez sieci - człowiek powtórzy
     * go dosłownie. Sprawdzamy na tych wzorcach, które go naprawdę obsługują.
     */
    @Test
    fun `kazdy przyklad dziala bez modelu`() {
        val detector = SmartActionDetector()
        val handled = OfflineFallback.EXAMPLES.associateWith { example ->
            detector.detectCritical(example).isNotEmpty() ||
                detector.detect(example).isNotEmpty() ||
                Notes.extract(example) != null
        }
        assertEquals(
            "przykłady bez obsługi offline",
            emptyList<String>(),
            handled.filterValues { !it }.keys.toList()
        )
    }

    @Test
    fun `orkiestrator przy braku sieci uruchamia warstwe 2 zamiast modelu lokalnego`() {
        val src = File("src/main/java/pl/victor/app/AIOrchestrator.kt")
        if (!src.exists()) return
        val text = src.readText()
        val start = text.indexOf("OfflineFallback.allFailedOffline(attemptFailures)")
        assertTrue("brak rozstrzygnięcia o braku sieci po ostatniej próbie", start > 0)
        val branch = text.substring(start, start + 3000)
        assertTrue(branch.contains("actionDetector.detect(textQuestion)"))
        assertTrue(branch.contains("announceTurnFailure(trigger, pl.victor.app.ai.OfflineFallback.SPEECH)"))
        assertTrue(branch.contains("return@launchTurn"))
        assertFalse(text.contains("Przechodzę na model lokalny"))
        assertFalse(text.contains("LocalAIProvider"))
        assertFalse(text.contains("localmodel"))
    }

    @Test
    fun `timer budzik i telefon lapie dopiero warstwa 2`() {
        // Dlatego przy braku sieci uruchamiamy detect(), a nie tylko warstwę 0:
        // detectCritical tych poleceń nie zna.
        val detector = SmartActionDetector()
        for (example in listOf("timer na 5 minut", "ustaw alarm na 7", "zadzwoń do mamy")) {
            assertTrue("warstwa 0 nie powinna: $example", detector.detectCritical(example).isEmpty())
        }
        assertTrue(detector.detect("timer na 5 minut").any { it is Action.SetTimer })
        assertTrue(detector.detect("ustaw alarm na 7").any { it is Action.SetAlarm })
        val call = detector.detect("zadzwoń do mamy").filterIsInstance<Action.MakeCall>().firstOrNull()
        assertNotNull(call)
        assertEquals("mamy", call!!.to)
    }
}
