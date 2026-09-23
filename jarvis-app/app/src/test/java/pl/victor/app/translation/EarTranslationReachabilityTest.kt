package pl.victor.app.translation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import pl.victor.app.conversation.MetaCommands

/**
 * Tryb tłumaczenia ze słuchu musi mieć DZIAŁAJĄCE wejście.
 *
 * ## Skąd ten test
 * Z powtarzającego się błędu w tej aplikacji: funkcja podpięta pod wyzwalacz,
 * którego nie ma. Zdarzyło się to już cztery razy - ostatnio przy skanowaniu
 * kodu, które działało, ale nie było jak o nie poprosić.
 *
 * Tu ryzyko jest takie samo i nawet większe, bo NATURALNE wejście - ramka
 * "tekst na żywo" (0x17/0x18) - na egzemplarzu użytkownika prawdopodobnie
 * nigdy nie przychodzi: te okulary wysyłają na wszystko ramkę przycisku.
 * Komenda głosowa jest więc jedynym wejściem, na które można liczyć, i to ona
 * jest tu pilnowana.
 *
 * Pętli ani orkiestratora nie da się tu skompilować (brak biblioteki
 * producenta), więc wiązanie sprawdzamy w źródle. To słabsze niż wywołanie,
 * ale mocniejsze niż nic - a "nic" już raz kosztowało wydanie z martwą funkcją.
 */
class EarTranslationReachabilityTest {

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
    fun `komendy z katalogu naprawde wlaczaja tryb`() {
        // Zdania wzięte DOSŁOWNIE z CommandCatalog - bo to ich uczy się
        // użytkownik. Wzorzec, który ich nie łapie, jest obietnicą bez pokrycia.
        listOf(
            "Tłumaczenie na żywo",
            "Włącz tłumacza ze słuchu",
            "tłumaczenie ze słuchu",
            "włącz tłumaczenie na żywo",
            "tłumacz mi na bieżąco"
        ).forEach {
            assertTrue("„$it” miało włączyć tryb", MetaCommands.startsEarTranslation(it))
        }
    }

    @Test
    fun `zwykla prosba o tlumaczenie NIE wlacza trybu ciaglego`() {
        // Te zdania mają iść do modelu albo do tłumaczenia napisu z kamery.
        // Przechwycenie ich tutaj zabrałoby dwie działające funkcje.
        listOf(
            "przetłumacz to na niemiecki",
            "jak powiedzieć po angielsku dziękuję",
            "przetłumacz ten napis",
            "co tu jest napisane po polsku"
        ).forEach {
            assertTrue(
                "„$it” NIE miało włączać trybu ciągłego",
                !MetaCommands.startsEarTranslation(it)
            )
        }
    }

    @Test
    fun `komenda jest wpieta w warstwe komend meta`() {
        val plik = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", plik != null)
        val tekst = plik!!.readText()
        assertTrue(
            "startsEarTranslation musi być sprawdzane w handleMetaCommand - " +
                "inaczej komenda nie ma żadnej drogi do trybu",
            tekst.contains("MetaCommands.startsEarTranslation(text)")
        )
        assertTrue(
            "komenda ma WŁĄCZAĆ tryb, nie tylko go rozpoznawać",
            tekst.contains("startEarTranslation()")
        )
    }

    @Test
    fun `ramka tekstu na zywo z okularow tez otwiera tryb`() {
        val plik = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", plik != null)
        val tekst = plik!!.readText()
        // Drugie wejście - u producenta to ONO otwiera tłumaczenie. U nas
        // stała tu zaślepka "obsługuję jak zwykłe pytanie".
        val warunek = Regex(
            """startGlassesConversation\(realtimeText: Boolean\) \{\s*if \(realtimeText\) \{""" +
                """(.|\n)*?toggleEarTranslation\(\)"""
        )
        assertTrue(
            "ramka „tekst na żywo” ma otwierać tłumaczenie ze słuchu, " +
                "a nie zwykłą turę pytania",
            warunek.containsMatchIn(tekst)
        )
    }

    @Test
    fun `przycisk na okularach konczy tryb - wyjscie niezalezne od jezyka`() {
        // Pętla nasłuchuje w języku ŹRÓDŁOWYM. Przy angielskim polskie
        // "koniec tłumaczenia" nie ma jak zostać rozpoznane, więc samo wyjście
        // głosem zostawiało człowieka w trybie bez wyjścia. Przycisk nie zależy
        // od żadnego języka.
        //
        // indexOf zamiast regexu z `(.|\n)*?`: na pliku tej wielkości taki
        // regex wywraca stos Javy (sprawdzone - pierwsza wersja tego testu
        // padła na StackOverflowError, a nie na asercji).
        val plik = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", plik != null)
        val tekst = plik!!.readText()

        val przycisk = tekst.indexOf("private fun handleButtonAction(action: ButtonAction)")
        assertTrue("nie znalazłem handleButtonAction", przycisk >= 0)
        val wyjście = tekst.indexOf("if (_earTranslation.value) {", przycisk)
        val tura = tekst.indexOf("when (action) {", przycisk)
        assertTrue(
            "handleButtonAction ma kończyć tłumaczenie ze słuchu, ZANIM zacznie turę",
            wyjście in (przycisk + 1) until tura &&
                tekst.indexOf("stopEarTranslation(", wyjście) in wyjście until tura
        )

        val przerwij = tekst.indexOf("fun cancelCurrentTurn(")
        val koniecPrzerwij = tekst.indexOf("\n    }\n", przerwij)
        assertTrue(
            "\"Przerwij\" w aplikacji ma przerywać także tryb tłumaczenia",
            przerwij >= 0 &&
                tekst.indexOf("stopEarTranslation(reason)", przerwij) in przerwij until koniecPrzerwij
        )
    }

    @Test
    fun `tryb ma wyjscie, ktore da sie powiedziec`() {
        // Tryb ciągły bez wyjścia to tryb, z którego wychodzi się przez
        // wyłączenie Bluetootha. Frazy muszą działać na CAŁEJ wypowiedzi.
        listOf("koniec tłumaczenia", "Przestań tłumaczyć.", "wyłącz tłumaczenie")
            .forEach { assertTrue("„$it” miało kończyć tryb", EarTranslation.toKoniec(it)) }
    }
}
