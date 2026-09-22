package pl.victor.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Polecenie idzie prosto do płatnego modelu, a jego treść decyduje o tym, co
 * usłyszy osoba trzymająca przycisk. Literówka w odmianie nie wywali buildu i
 * nie pojawi się w żadnym logu - zobaczy ją dopiero ktoś w terenie.
 */
class ReadTextPromptTest {

    @Test
    fun `po polsku, nie po polskiu - odmiana we wszystkich obslugiwanych jezykach`() {
        // Pierwsza wersja sklejała nazwę z końcówką „u". Wszystkie nazwy z tej
        // listy kończą się na „i", więc błąd dotyczył każdego języka naraz.
        for (code in ReadTextPrompt.SUPPORTED) {
            val prompt = ReadTextPrompt.forLanguage(code)
            assertFalse(
                "język $code: zła odmiana po słowie „po” w: $prompt",
                Regex("po [a-ząćęłńóśźż]+(ki|kiu|kiego)\\b").containsMatchIn(prompt)
            )
            assertTrue(
                "język $code: brak poprawnej formy miejscownika",
                Regex("po [a-ząćęłńóśźż]+ku\\b").containsMatchIn(prompt)
            )
        }
    }

    @Test
    fun `polski jest domyslny i wychodzi poprawnie`() {
        val prompt = ReadTextPrompt.forLanguage("pl")
        assertTrue(prompt.contains("na polski"))
        assertTrue(prompt.contains("po polsku"))
    }

    @Test
    fun `nieznany kod jezyka nie wywala sie i schodzi na polski`() {
        // getResponseLanguage() czyta z SharedPreferences, więc może oddać
        // cokolwiek - np. kod z wersji, w której lista języków była inna.
        assertEquals(ReadTextPrompt.forLanguage("pl"), ReadTextPrompt.forLanguage("sv"))
        assertEquals(ReadTextPrompt.forLanguage("pl"), ReadTextPrompt.forLanguage(""))
    }

    @Test
    fun `angielski tlumaczy na angielski, nie na polski`() {
        val prompt = ReadTextPrompt.forLanguage("en")
        assertTrue(prompt.contains("na angielski"))
        assertTrue(prompt.contains("po angielsku"))
        assertFalse("ustawienie na angielski nie może wspominać o polskim", prompt.contains("polsk"))
    }

    @Test
    fun `polecenie zabrania czytania oryginalu i zapowiedzi jezyka`() {
        // Bez tego model lubi powiedzieć „Po angielsku napisano…", a potem
        // przeczytać oryginał I tłumaczenie - czyli trzy razy dłużej, niż
        // trzeba, przy funkcji, której cały sens to szybkość.
        val prompt = ReadTextPrompt.forLanguage("pl")
        assertTrue(prompt.contains("nie czytaj oryginału"))
        assertTrue(prompt.contains("nie zapowiadaj"))
    }

    @Test
    fun `nazwy wlasne zostaja w oryginale, ale opis ma byc przetlumaczony`() {
        // Przetłumaczona nazwa ulicy jest bezużyteczna - nie da się o nią
        // zapytać przechodnia ani znaleźć jej na tabliczce. Ale samo „Ariel"
        // też nic nie mówi: całą informacją jest stojące obok „mosópor".
        val prompt = ReadTextPrompt.forLanguage("pl")
        assertTrue(prompt.contains("nazwy własne"))
        assertTrue(prompt.contains("OPISUJE"))
    }

    @Test
    fun `przy wielu OSOBNYCH napisach czyta tylko dominujacy`() {
        // Zdjęcie półki w sklepie to kilkanaście nazw, cen i naklejek. Bez tego
        // warunku „przeczytaj tekst ze zdjęcia" znaczy minutę wyliczanki -
        // czyli dokładnie to, czego przy szybkim tłumaczu nikt nie chce.
        val prompt = ReadTextPrompt.forLanguage("pl")
        assertTrue(prompt.contains("WIELE OSOBNYCH napisów"))
        assertTrue(prompt.contains("TYLKO ten najważniejszy"))
        assertTrue(prompt.contains("nie wyliczaj pozostałych"))
    }

    @Test
    fun `jeden dlugi tekst idzie w CALOSCI`() {
        // TO JEST POPRAWKA DO POPRZEDNIEJ WERSJI TEGO TESTU.
        //
        // Stał tu warunek „odpowiedz ma być krótka" sprawdzający frazę „jednym
        // lub dwoma zdaniami" - i pilnował błędu. Wybór dominującego napisu
        // jest słuszny przy PÓŁCE, ale stosował się też do kadru, w którym
        // jest jeden tekst, po prostu długi: wtedy „najważniejszy napis" to
        // był pierwszy nagłówek, a reszta strony przepadała. Zgłoszone
        // wprost: „gdy proszę o tłumaczenie większego tekstu, tłumaczy tylko
        // fragment".
        //
        // Teraz test pilnuje obu gałęzi naraz - bo to ich WSPÓŁISTNIENIE jest
        // tu całą treścią, a skasowanie którejkolwiek psuje drugi przypadek.
        val prompt = ReadTextPrompt.forLanguage("pl")
        assertTrue(prompt.contains("JEDEN spójny tekst"))
        assertTrue(prompt.contains("W CAŁOŚCI"))
        assertTrue(prompt.contains("choćby był długi"))
    }

    @Test
    fun `dlugosc odpowiedzi idzie za dlugoscia napisu, a nie za stala regula`() {
        val prompt = ReadTextPrompt.forLanguage("pl")
        assertTrue(
            "polecenie nie może już narzucać stałej długości odpowiedzi",
            !prompt.contains("jednym lub dwoma zdaniami")
        )
        assertTrue(prompt.contains("Długość odpowiedzi dopasuj do długości napisu"))
    }

    @Test
    fun `cena i gramatura sa czescia odpowiedzi na pytanie co to jest`() {
        assertTrue(ReadTextPrompt.forLanguage("pl").contains("Cenę, wagę lub pojemność"))
    }

    @Test
    fun `brak tekstu ma byc skwitowany jednym zdaniem`() {
        assertTrue(ReadTextPrompt.forLanguage("pl").contains("jednym zdaniem"))
    }
}
