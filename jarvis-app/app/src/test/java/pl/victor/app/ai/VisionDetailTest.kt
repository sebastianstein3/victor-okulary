package pl.victor.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pełna rozdzielczość kosztuje kilkanaście sekund (Wi-Fi Direct), więc ta
 * decyzja musi być trafna w obie strony: bez niej "przeczytaj, co tu pisze"
 * nie ma prawa zadziałać, a z nią wszędzie - każde "co przede mną jest"
 * czekałoby pół minuty.
 */
class VisionDetailTest {

    @Test
    fun `prosba o przeczytanie wymaga szczegolu`() {
        assertTrue(VisionDetail.needsDetail("Przeczytaj na głos cały tekst widoczny na zdjęciu"))
        assertTrue(VisionDetail.needsDetail("co tu pisze?"))
        assertTrue(VisionDetail.needsDetail("Jaki jest skład tego produktu?"))
        assertTrue(VisionDetail.needsDetail("Sprawdź datę ważności"))
        assertTrue(VisionDetail.needsDetail("Ile kosztuje? Zobacz cenę"))
    }

    @Test
    fun `zwykly opis otoczenia nie wymaga szczegolu`() {
        assertFalse(VisionDetail.needsDetail("Co widzisz przede mną?"))
        assertFalse(VisionDetail.needsDetail("Opisz, co jest w tym pokoju"))
        assertFalse(VisionDetail.needsDetail("Czy to jest bezpieczne?"))
        assertFalse(VisionDetail.needsDetail(""))
    }

    @Test
    fun `pytanie o kod jest rozpoznawane`() {
        // Dawny wzorzec "kod qr" nie łapał NICZEGO, bo polskie zdania odmieniają
        // obie części: "co jest na tym QR kodzie". Stąd "AI nie czyta kodów QR".
        assertTrue(VisionDetail.isAboutCode("Co jest na tym QR kodzie?"))
        assertTrue(VisionDetail.isAboutCode("zeskanuj kod"))
        assertTrue(VisionDetail.isAboutCode("co to za kod kreskowy"))
    }

    @Test
    fun `kod kreskowy we WSZYSTKICH przypadkach - nie tylko w mianowniku`() {
        // ZGŁOSZONE: "nie rozpoznaje kodów kreskowych". Lista zbitek łapała
        // wyłącznie mianownik ("kod kreskowy"); każda inna końcówka rozsuwała
        // "kod" od "kresk" i dopasowanie znikało.
        //
        // To nie jest kosmetyka: od tej decyzji zależy, czy po nieudanym
        // skanie sięgamy po zdjęcie w PEŁNEJ ROZDZIELCZOŚCI. Na miniaturze
        // kreski EAN mają grubość jednego piksela i nie odczyta ich żadna
        // biblioteka - więc zła odmiana znaczyła "kod nie do odczytania".
        listOf(
            "nie rozpoznaje kodów kreskowych",
            "sprawdź to z kodu kreskowego",
            "co jest pod tym kodem kreskowym",
            "te kody kreskowe są nieczytelne",
            "odczytaj kody paskowe",
            "sprawdź w kodzie ean",
            "podaj mi kodu produktu"
        ).forEach {
            assertTrue("„$it” miało zostać uznane za pytanie o kod", VisionDetail.isAboutCode(it))
        }
    }

    @Test
    fun `sama kreska i kreskowka to nie kod`() {
        // Rdzeń "kresk" bez poprzedzającego "kod..." byłby za szeroki.
        assertFalse(VisionDetail.isAboutCode("co to za kreskówka"))
        assertFalse(VisionDetail.isAboutCode("narysuj kreskę"))
        assertTrue(VisionDetail.needsDetail("Co jest na tym QR kodzie?"))
    }

    @Test
    fun `zwykle pytanie to nie kod`() {
        assertFalse(VisionDetail.isAboutCode("Co widzisz przede mną?"))
        assertFalse(VisionDetail.isAboutCode("Przeczytaj ten napis"))
        // Czytanie napisu wymaga szczegółu, ale kodem nie jest.
        assertTrue(VisionDetail.needsDetail("Przeczytaj ten napis"))
    }

    @Test
    fun `odmiana nie gubi trafienia`() {
        // Rdzenie, nie całe słowa - inaczej polska odmiana zjada połowę.
        assertTrue(VisionDetail.needsDetail("Co jest na etykiecie?"))
        assertTrue(VisionDetail.needsDetail("Przeczytaj mi instrukcję"))
        assertTrue(VisionDetail.needsDetail("Jakie są litery na tabliczce?"))
    }
}
