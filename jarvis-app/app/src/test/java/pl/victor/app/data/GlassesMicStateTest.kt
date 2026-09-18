package pl.victor.app.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Ustawienie "Pytania mikrofonem okularów" zmienia nie tylko człowiek.
 *
 * ## Co się działo
 * Po trzech turach zakończonych ciszą przez profil rozmowy aplikacja SAMA
 * przestawia to ustawienie na `false` ([pl.victor.app.AIOrchestrator]
 * `noteSilentTurn`) - żeby zestaw, który zgłasza SCO, ale go nie obsługuje, nie
 * zabijał każdej kolejnej tury. Przełączenie jest trwałe.
 *
 * Trzy miejsca kłamały wtedy na trzy różne sposoby:
 *
 *  1. Ekran Ustawień czytał wartość RAZ, przy wejściu (`remember { mutableStateOf }`),
 *     więc pokazywał stan sprzed przełączenia - przełącznik stał na "włączony",
 *     gdy mikrofon był już wyłączony.
 *  2. Nagłówek dziennika powstaje raz, w `onCreate` procesu. Czytany jako stan
 *     bieżący mówił "false" o ustawieniu, które człowiek zdążył potem włączyć.
 *  3. Samo przełączenie nie zostawiało w dzienniku ŻADNEGO śladu, więc nie było
 *     czym połączyć jednego z drugim.
 *
 * Zgłoszone dokładnie tak: "W aplikacji mikrofon był włączony, zweryfikuj to" -
 * przy dzienniku, który twierdził coś przeciwnego. Obie strony miały rację.
 *
 * Zachowania [SettingsRepository] nie da się sprawdzić bez Androida
 * (`EncryptedSharedPreferences`), więc strażnik pilnuje tego, co było usterką:
 * SPOSOBU odczytu w każdym z tych trzech miejsc.
 */
class GlassesMicStateTest {

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
    fun `ustawienie mikrofonu okularow jest obserwowalne`() {
        val plik = zrodlo("src/main/java/pl/victor/app/data/SettingsRepository.kt")
        assumeTrue("Nie znalazłem SettingsRepository.kt", plik != null)
        val tekst = plik!!.readText()

        assertTrue(
            "isGlassesMicEnabled ma czytać strumień, nie prefs - inaczej ekran " +
                "Ustawień nie dowie się o przełączeniu zrobionym przez aplikację",
            tekst.contains("fun isGlassesMicEnabled(): Boolean = _glassesMicEnabledFlow.value")
        )
        assertTrue(
            "setGlassesMicEnabled ma aktualizować strumień, nie tylko prefs",
            Regex(
                """fun setGlassesMicEnabled\([^)]*\)\s*\{[^}]*_glassesMicEnabledFlow\.value""",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(tekst)
        )
    }

    @Test
    fun `przelacznik w Ustawieniach czyta stan na zywo`() {
        val plik = zrodlo("src/main/java/pl/victor/app/ui/settings/SettingsActivity.kt")
        assumeTrue("Nie znalazłem SettingsActivity.kt", plik != null)
        val tekst = plik!!.readText()

        assertTrue(
            "przełącznik mikrofonu okularów ma zbierać strumień - odczyt raz przy " +
                "wejściu pokazywał stan sprzed samoczynnego przełączenia",
            tekst.contains("app.settings.glassesMicEnabledFlow.collectAsState()")
        )
        assertTrue(
            "jednorazowy odczyt isGlassesMicEnabled() w remember wrócił do kodu",
            !Regex("""remember\s*\{\s*mutableStateOf\(\s*app\.settings\.isGlassesMicEnabled\(\)""")
                .containsMatchIn(tekst)
        )
    }

    @Test
    fun `naglowek dziennika nie udaje stanu biezacego`() {
        val plik = zrodlo("src/main/java/pl/victor/app/VictorApplication.kt")
        assumeTrue("Nie znalazłem VictorApplication.kt", plik != null)
        val linia = plik!!.readLines()
            .firstOrNull { it.contains("isGlassesMicEnabled()") && it.contains("\"") }
        assertTrue("Nie znalazłem wiersza nagłówka o mikrofonie okularów", linia != null)
        assertTrue(
            "nagłówek powstaje raz w onCreate - musi to mówić wprost, bo inaczej " +
                "czyta się go jak stan bieżący: $linia",
            linia!!.contains("przy starcie")
        )
    }

    @Test
    fun `samoczynne wylaczenie mikrofonu trafia do dziennika`() {
        val plik = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", plik != null)
        val tekst = plik!!.readText()

        val ciało = Regex(
            """private fun noteSilentTurn\([^)]*\)[^{]*\{.*?\n    \}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(tekst)?.value
        assertTrue("Nie znalazłem noteSilentTurn", ciało != null)
        assertTrue(
            "przełączenie mikrofonu na telefon musi zostawić ślad w dzienniku - " +
                "bez niego widać tylko skutek w następnej sesji",
            ciało!!.contains("diag.event(")
        )
    }

    @Test
    fun `kazda tura zapisuje biezace ustawienie mikrofonu`() {
        val plik = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", plik != null)
        assertTrue(
            "zdarzenie NASŁUCH/start ma nieść bieżące ustawienie mikrofonu okularów - " +
                "nagłówek sesji na to nie odpowiada",
            plik!!.readText().contains("\"ustawienieMikOkularów\" to wantsGlassesMic")
        )
    }

    @Test
    fun `dwa przelaczniki mikrofonu maja rozne nazwy`() {
        val plik = zrodlo("src/main/java/pl/victor/app/ui/settings/SettingsActivity.kt")
        assumeTrue("Nie znalazłem SettingsActivity.kt", plik != null)
        val tekst = plik!!.readText()

        // Jeden dotyczy FRAZY wybudzenia (isWakeWordOverGlassesMic), drugi PYTAŃ
        // po wybudzeniu (isGlassesMicEnabled). Gdy oba nazywały się prawie tak
        // samo, pomyliły się przy pierwszej rozmowie o dzienniku.
        assertTrue(
            "przełącznik frazy musi nazywać się tak, by nie mylił się z pytaniami",
            tekst.contains("Sama FRAZA wybudzenia mikrofonem okularów")
        )
        assertTrue(
            "przełącznik pytań musi zostać nazwany wprost",
            tekst.contains("Pytania mikrofonem okularów")
        )
    }
}
