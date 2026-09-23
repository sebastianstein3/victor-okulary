package pl.victor.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kod produktu z cyfr pod kreskami.
 *
 * Z dziennika z 23 września: ML Kit nie odczytał kresek ani razu, a model na tym
 * samym zdjęciu widział puszkę i "kod w pełni widoczny". Cyfry pod kodem są
 * kilkukrotnie większe od kresek - i to je czyta ta droga.
 */
class EanFromTextTest {

    @Test
    fun `cyfra kontrolna EAN-13`() {
        assertTrue(EanFromText.poprawnaCyfraKontrolna("5901234123457"))
        assertFalse("zmiana ostatniej cyfry ma oblać", EanFromText.poprawnaCyfraKontrolna("5901234123458"))
    }

    @Test
    fun `cyfra kontrolna EAN-8 i UPC-A`() {
        assertTrue(EanFromText.poprawnaCyfraKontrolna("96385074"))
        assertTrue(EanFromText.poprawnaCyfraKontrolna("036000291452"))
    }

    @Test
    fun `EAN rozdzielony spacjami, tak jak jest drukowany`() {
        assertEquals("5901234123457", EanFromText.find("Bonduelle\n5 901234 123457\n400 g"))
    }

    @Test
    fun `kod sklejony z sasiednia liczba`() {
        assertEquals("5901234123457", EanFromText.find("x9 5901234123457"))
    }

    @Test
    fun `UPC-A i EAN-8 w ukladzie druku`() {
        assertEquals("036000291452", EanFromText.find("0 36000 29145 2"))
        assertEquals("96385074", EanFromText.find("9638 5074"))
    }

    @Test
    fun `numer telefonu nie staje sie kodem`() {
        // Pierwsza wersja przesuwała okno po każdym ciągu cyfr - a to daje 1 na
        // 10 szans NA KAŻDĄ POZYCJĘ, że przypadkowy fragment przejdzie cyfrę
        // kontrolną. Sprawdzamy wszystkie numery z jednej setki: żaden nie może
        // przejść, bo żaden nie ma układu druku kodu.
        for (koniec in 0..99) {
            val tel = "infolinia 48 123 456 7" + koniec.toString().padStart(2, '0')
            assertNull("„$tel” nie może zostać kodem", EanFromText.find(tel))
        }
    }

    @Test
    fun `losowe liczby z opakowania nie udaja kodu`() {
        // Data, partia, cena, gramatura - nic z tego nie ma prawa pytać bazy.
        assertNull(EanFromText.find("Najlepiej spożyć przed: 12.10.2027 partia L2034 cena 5,99 zł 340 g"))
    }

    @Test
    fun `daty z myslnikami nie sa sklejane w jeden ciag`() {
        assertNull(EanFromText.find("2027-10-12 2026-09-23"))
    }

    @Test
    fun `same zera to nie kod`() {
        assertFalse(EanFromText.poprawnaCyfraKontrolna("0000000000000"))
    }

    @Test
    fun `nie poprawiamy liter na cyfry`() {
        // "59O1234123457" z literą O - zamiana na zero dałaby prawdziwy kod,
        // ale nie ten z opakowania, tylko zgadnięty. Lepiej nic.
        assertNull(EanFromText.find("59O1234123457"))
    }

    @Test
    fun `kod powiedziany glosem tez sie liczy`() {
        // Człowiek może przeczytać cyfry sam: "sprawdź produkt 5 901234 123457".
        assertEquals("5901234123457", EanFromText.find("sprawdź produkt 5 901234 123457"))
    }

    @Test
    fun `pusty tekst nie wywala sie`() {
        assertNull(EanFromText.find(null))
        assertNull(EanFromText.find(""))
    }
}
