package pl.victor.app.ble

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pilnuje, że to, czego uczymy człowieka, zgadza się z tym, co robi kod.
 *
 * ## Rozjechało się już dwa razy
 * Samouczek uczył, że potrójne kliknięcie czyta kod QR, a przytrzymanie
 * zaczyna nową rozmowę - kod robił odwrotnie. Poprawiono samouczek, ale
 * panel okularów został z tą samą nieprawdą jeszcze dłużej.
 *
 * A od 16 września doszło coś gorszego niż rozjazd: PRZYTRZYMANIE NIE
 * ISTNIEJE. Okulary zgłaszają wyłącznie pojedyncze kliknięcia (patrz
 * [ButtonActionDetector]), więc każde zdanie uczące przytrzymania uczy gestu,
 * który nie zrobi nic - a człowiek uzna, że aplikacja jest zepsuta.
 *
 * Test jest celowo wąski: sprawdza jedną rzecz, której nie wolno tam napisać,
 * zamiast udawać, że porówna dwa teksty w naturalnym języku.
 */
class GestureDocsTest {

    private fun find(relative: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, "jarvis-app/app/src/main/java/pl/victor/app/$relative")
                .takeIf { it.isFile }?.let { return it }
            File(dir, "src/main/java/pl/victor/app/$relative")
                .takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    /** Wiersze z tekstem dla użytkownika, bez komentarzy i bez KDoc. */
    private fun userFacingLines(file: File): List<String> =
        file.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    @Test
    fun `interfejs nie uczy przytrzymania, bo okulary go nie wysylaja`() {
        val pliki = listOfNotNull(
            find("ui/components/GlassesPanel.kt"),
            find("ui/onboarding/OnboardingActivity.kt")
        )
        assumeTrue("Nie znalazłem plików interfejsu", pliki.isNotEmpty())

        val winne = pliki.flatMap { plik ->
            userFacingLines(plik)
                // WŁĄCZANIE okularów to co innego niż sterowanie nimi.
                // "Przytrzymaj przycisk 3 sekundy" / "3s" obsługuje sam sprzęt,
                // zanim jakiekolwiek BLE w ogóle stoi - i to jest prawda.
                // Wyjmujemy więc wiersze mówiące o czasie trzymania.
                .filter { it.contains("rzytrzym") && !Regex("""\d\s?(s\b|sekund)""").containsMatchIn(it) }
                .map { "${plik.name}: $it" }
        }
        assertTrue(
            "interfejs uczy gestu, którego okulary nie zgłaszają: $winne",
            winne.isEmpty()
        )
    }
}
