package pl.victor.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reguły trybu "tłumacz ze słuchu".
 *
 * Pętli nie da się sprawdzić bez okularów, ale każda decyzja w niej jest
 * czystą funkcją - i tu jest sprawdzana. Dwie z nich (ogon i dławienie) są
 * przepisane z aplikacji producenta, trzecia (echo) jest moja, bo producent
 * pokazuje tłumaczenie na ekranie, a my mówimy je w te same okulary, którymi
 * słuchamy.
 */
class EarTranslationTest {

    @Test
    fun `ogon to tylko to, co dopisano`() {
        assertEquals("i dobry wieczór", EarTranslation.ogon("dzień dobry", "dzień dobry i dobry wieczór"))
    }

    @Test
    fun `powtorzone zdanie nie ma ogona`() {
        assertEquals("", EarTranslation.ogon("dzień dobry", "dzień dobry"))
    }

    @Test
    fun `rozpoznawanie od nowa oddaje calosc`() {
        assertEquals("zupełnie co innego", EarTranslation.ogon("dzień dobry", "zupełnie co innego"))
    }

    @Test
    fun `wielkosc liter nie robi z powtorzenia nowego zdania`() {
        // Rozpoznawanie oddaje to samo raz z wielkiej, raz z małej litery.
        // Na surowym startsWith całość poszłaby do tłumaczenia drugi raz.
        assertEquals("dalej", EarTranslation.ogon("Dzień dobry", "dzień dobry dalej"))
    }

    @Test
    fun `pusty poprzednik oddaje calosc`() {
        assertEquals("dzień dobry", EarTranslation.ogon("", "dzień dobry"))
    }

    @Test
    fun `sama interpunkcja nie idzie do tlumacza`() {
        assertFalse(EarTranslation.wartoTłumaczyć("..."))
        assertFalse(EarTranslation.wartoTłumaczyć("   "))
        assertTrue(EarTranslation.wartoTłumaczyć("tak"))
    }

    @Test
    fun `wlasny glos jest rozpoznawany mimo interpunkcji i wielkosci liter`() {
        assertTrue(EarTranslation.jestEchem("dzień dobry", "Dzień dobry!"))
    }

    @Test
    fun `urwane echo tez jest echem`() {
        // Mikrofon łapie zwykle KAWAŁEK tego, co powiedzieliśmy - nasłuch
        // zaczyna się w połowie zdania. Porównanie na równość by tego nie
        // złapało i tryb tłumaczyłby własny ogon w kółko.
        assertTrue(EarTranslation.jestEchem("dobry wieczór państwu", "Dzień dobry, dobry wieczór państwu, witam"))
    }

    @Test
    fun `krotkie wlasne slowo nie polyka cudzego zdania`() {
        // Gdyby zawieranie działało bez progu, po wypowiedzeniu "tak" każde
        // cudze zdanie z tym słowem przepadłoby bez tłumaczenia.
        assertFalse(EarTranslation.jestEchem("tak jak mówiłem wczoraj", "Tak"))
    }

    @Test
    fun `cudze zdanie nie jest echem`() {
        assertFalse(EarTranslation.jestEchem("gdzie jest dworzec", "Dzień dobry"))
    }

    @Test
    fun `bez wlasnej wypowiedzi nic nie jest echem`() {
        assertFalse(EarTranslation.jestEchem("cokolwiek", null))
    }

    @Test
    fun `dlawienie trzyma sekunde - tyle bierze producent`() {
        assertFalse(EarTranslation.czasNaTłumaczenie(teraz = 10_500, ostatnieTłumaczenie = 10_000))
        assertTrue(EarTranslation.czasNaTłumaczenie(teraz = 11_000, ostatnieTłumaczenie = 10_000))
        assertEquals(1_000L, EarTranslation.MIN_ODSTĘP_MS)
    }

    @Test
    fun `polecenie konca dziala mimo interpunkcji`() {
        assertTrue(EarTranslation.toKoniec("Koniec tłumaczenia."))
        assertTrue(EarTranslation.toKoniec("wyłącz tłumacza"))
    }

    @Test
    fun `koniec w srodku zdania nie wylacza trybu`() {
        // To jest tryb, w którym z założenia słychać cudzą mowę. Gdyby samo
        // słowo w zdaniu wyłączało tłumacza, wyłączałby go rozmówca.
        assertFalse(EarTranslation.toKoniec("to już koniec zebrania, dziękuję"))
        assertFalse(EarTranslation.toKoniec("koniec"))
    }
}
