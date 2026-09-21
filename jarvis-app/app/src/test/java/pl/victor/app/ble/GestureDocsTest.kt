package pl.victor.app.ble

import java.io.File
import org.junit.Assert.assertEquals
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

    /** Instrukcja dla osób testujących - szukana od korzenia repozytorium. */
    private fun instrukcja(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, "docs/instrukcja-dla-osob-testujacych.html")
                .takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `tabela gestow w instrukcji zgadza sie z kodem`() {
        // STRAŻNIK NA INTERFEJS NIE OBEJMOWAŁ DOKUMENTÓW - I TAM NIEPRAWDA
        // PRZEŻYŁA NAJDŁUŻEJ.
        //
        // Instrukcja uczyła, że "przytrzymaj" czyta napisy, a "3 × klik" zaczyna
        // nową rozmowę. Jedno i drugie fałsz: przytrzymania te okulary nie
        // zgłaszają wcale, a trzy kliknięcia to czytanie z tłumaczeniem - nowa
        // rozmowa siedzi pod czterema.
        //
        // Osoba testująca dostaje ten plik do ręki i próbuje gestu, którego nie
        // ma. Wygląda to dla niej dokładnie jak zepsuta aplikacja, a kosztuje
        // zaufanie do wszystkich pozostałych zdań w instrukcji.
        val plik = instrukcja()
        assumeTrue("Nie znalazłem instrukcji", plik != null)
        val html = plik!!.readText()

        val ruchy = Regex("""<span class="move">(.+?)</span>""")
            .findAll(html).map { it.groupValues[1] }.toList()
        assertEquals("instrukcja ma opisywać dokładnie cztery gesty", 4, ruchy.size)

        // Kolejność jest istotna: to jest tabelka czytana z góry na dół.
        for ((i, oczekiwany) in listOf("1 ×", "2 ×", "3 ×", "4 ×").withIndex()) {
            assertTrue(
                "gest numer ${i + 1} ma zaczynać się od \"$oczekiwany klik\", a jest \"${ruchy[i]}\"",
                ruchy[i].startsWith(oczekiwany)
            )
        }
    }

    @Test
    fun `instrukcja nie uczy przytrzymania jako gestu aplikacji`() {
        val plik = instrukcja()
        assumeTrue("Nie znalazłem instrukcji", plik != null)
        val html = plik!!.readText()

        // Samo słowo "przytrzymanie" jest dozwolone - instrukcja MUSI móc
        // napisać, że tego gestu nie ma, i że kilkusekundowe przytrzymanie
        // włącza sam sprzęt. Zakazane jest UCZENIE go jako sposobu wywołania
        // funkcji aplikacji, czyli zdania trybu rozkazującego.
        val rozkazy = Regex("""przytrzymaj\s+przycisk""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.value }.toList()
        assertEquals(
            "instrukcja każe przytrzymać przycisk - a okulary zgłaszają " +
                "wyłącznie pojedyncze kliknięcia",
            emptyList<String>(),
            rozkazy
        )
    }

    @Test
    fun `instrukcja podaje komende glosowa dla gestow, ktore bywaja gubione`() {
        // Trzy i cztery kliknięcia zgłoszono jako niedziałające. Dopóki nie wiem
        // dlaczego, instrukcja ma podawać drogę, która nie zależy od przycisku -
        // inaczej osoba testująca zostaje z funkcją, której nie umie uruchomić.
        val plik = instrukcja()
        assumeTrue("Nie znalazłem instrukcji", plik != null)
        val html = plik!!.readText()

        for (komenda in listOf("przetłumacz to", "nowy temat", "zeskanuj kod")) {
            assertTrue(
                "instrukcja nie podaje komendy głosowej \"$komenda\"",
                html.contains(komenda, ignoreCase = true)
            )
        }
    }
}
