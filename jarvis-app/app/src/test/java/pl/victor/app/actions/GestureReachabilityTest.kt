package pl.victor.app.actions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import pl.victor.app.ble.ButtonAction

/**
 * Żadna funkcja nie ma prawa wisieć WYŁĄCZNIE na geście przycisku.
 *
 * ## Skąd ta reguła
 * To trzeci raz, kiedy ta sama usterka wraca w innym przebraniu: funkcja
 * podpięta pod wyzwalacz, którego na tym sprzęcie nie ma.
 *
 *  1. "Przeczytaj i przetłumacz" wisiało na PRZYTRZYMANIU - okulary nie
 *     zgłaszają przytrzymania w ogóle.
 *  2. Przeniesione na trzy kliknięcia - te z terenu zgłoszono jako martwe.
 *  3. [pl.victor.app.vision.ReadTextPrompt] miał wtedy w całej aplikacji
 *     DOKŁADNIE JEDNEGO wywołującego: tę właśnie akcję. Funkcja, o którą
 *     poproszono wprost, nie dawała się uruchomić żadną drogą.
 *
 * Gest jest wygodą, nie drogą jedyną. Test pilnuje, żeby każdy gest miał
 * odpowiednik wypowiadany głosem - albo żeby było WPROST napisane, czemu nie.
 */
class GestureReachabilityTest {

    private val detector = SmartActionDetector()

    @Test
    fun `przetlumacz to wywoluje gest czytania i tlumaczenia`() {
        // Dokładnie ta funkcja była nieosiągalna. Sprawdzamy kilka naturalnych
        // sposobów powiedzenia tego samego, bo człowiek nie zna naszych wzorców.
        for (zdanie in listOf(
            "przetłumacz to",
            "Przetłumacz to.",
            "przetlumacz ten napis",
            "przeczytaj i przetłumacz",
            "przetłumacz napis"
        )) {
            assertEquals(
                "\"$zdanie\" ma uruchamiać czytanie z tłumaczeniem",
                ButtonAction.READ_AND_TRANSLATE,
                detector.detectGesture(zdanie)
            )
        }
    }

    @Test
    fun `polskie litery nie wypadaja ze wzorca`() {
        // \w i \b nie obejmują polskich liter - ta pułapka zjadła w tym
        // projekcie sześć wzorców. "tabliczkę" i "się" kończą się nimi.
        assertEquals(
            ButtonAction.READ_AND_TRANSLATE,
            detector.detectGesture("przetłumacz tę tabliczkę")
        )
        assertEquals(
            ButtonAction.LOOK_AND_DESCRIBE,
            detector.detectGesture("rozejrzyj się")
        )
    }

    @Test
    fun `opis otoczenia da sie poprosic glosem`() {
        for (zdanie in listOf("rozejrzyj się", "opisz otoczenie", "opisz co widzisz")) {
            assertEquals(
                "\"$zdanie\" ma uruchamiać zdjęcie z opisem",
                ButtonAction.LOOK_AND_DESCRIBE,
                detector.detectGesture(zdanie)
            )
        }
    }

    @Test
    fun `zdanie o czyms innym nie przechwytuje gestu`() {
        // Warstwa 0 działa PRZED modelem, więc fałszywe trafienie jest tu
        // droższe niż przeoczenie: zabiera pytanie, na które model by odpowiedział.
        for (zdanie in listOf(
            "przetłumacz to na angielski i wyślij Ani",
            "opisz mi jak działa fotosynteza",
            "co to znaczy przetłumaczyć tekst",
            "rozejrzyj się za nowym mieszkaniem dla mnie"
        )) {
            assertNull(
                "\"$zdanie\" nie jest prośbą o gest - ma iść do modelu",
                detector.detectGesture(zdanie)
            )
        }
    }

    @Test
    fun `czytanie ciagle nie zostalo przechwycone przez tlumaczenie`() {
        // Action.ReadText (ciągły tryb czytania dla osoby niewidomej) i
        // ButtonAction.READ_AND_TRANSLATE to DWIE RÓŻNE funkcje o mylących
        // nazwach - raz już kosztowało to złą diagnozę. "przeczytaj to" należy
        // do tej pierwszej i nie wolno go tu zabrać.
        assertNull(
            "\"przeczytaj to\" należy do ciągłego trybu czytania",
            detector.detectGesture("przeczytaj to")
        )
    }

    @Test
    fun `kazdy gest ma droge glosowa albo udokumentowany powod`() {
        // Strażnik przeciw nawrotowi całej klasy usterek: nowy gest dodany bez
        // drogi głosowej ma tu zapalić czerwone.
        val gesty = listOf(
            ButtonAction.QUICK_QUESTION,
            ButtonAction.LOOK_AND_DESCRIBE,
            ButtonAction.READ_AND_TRANSLATE,
            ButtonAction.NEW_CONVERSATION,
            ButtonAction.SCAN_QR
        )
        val zGlosu = listOf(
            "przetłumacz to",
            "rozejrzyj się",
            "opisz otoczenie"
        ).mapNotNull { detector.detectGesture(it) }.toSet()

        val bezDrogi = gesty.filter { it !in zGlosu }
        // QUICK_QUESTION nie potrzebuje komendy: sama komenda JEST już mówieniem.
        // NEW_CONVERSATION obsługuje MetaCommands ("nowy temat"), wcześniej niż
        // warstwa 0. SCAN_QR ma własną komendę w spisie komend.
        val udokumentowane = setOf(
            ButtonAction.QUICK_QUESTION,
            ButtonAction.NEW_CONVERSATION,
            ButtonAction.SCAN_QR
        )
        assertEquals(
            "gest bez drogi głosowej i bez udokumentowanego powodu",
            emptyList<ButtonAction>(),
            bezDrogi.filter { it !in udokumentowane }
        )
    }

    @Test
    fun `nowy temat naprawde jest obslugiwany przez MetaCommands`() {
        // Powyższy test przyjmuje to na słowo - ten sprawdza, że słowo jest
        // prawdziwe. Bez tego "udokumentowany powód" byłby wymówką.
        var dir: File? = File("").absoluteFile
        var plik: File? = null
        while (dir != null && plik == null) {
            plik = listOf(
                File(dir, "jarvis-app/app/src/main/java/pl/victor/app/conversation/MetaCommands.kt"),
                File(dir, "src/main/java/pl/victor/app/conversation/MetaCommands.kt")
            ).firstOrNull { it.isFile }
            dir = dir.parentFile
        }
        assumeTrue("Nie znalazłem MetaCommands.kt", plik != null)
        assertTrue(
            "MetaCommands ma rozpoznawać \"nowy temat\" - inaczej cztery kliknięcia " +
                "są jedyną drogą do resetu rozmowy",
            plik!!.readText().contains("nowy\\s+temat")
        )
    }
}
