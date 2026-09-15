package pl.victor.app.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Przypadki WPROST z dziennika z 15 września, godzina 20:48-20:49.
 *
 * Każdy z nich został wtedy naprawdę powiedziany do okularów i każdy poszedł do
 * modelu zamiast do notatnika. Cytaty w nazwach testów są tym, co zapisał
 * `telefonUsłyszał=` - łącznie z przesłyszeniami, bo kod ma sobie radzić z
 * tekstem z rozpoznawania mowy, a nie z tym, co ktoś chciał powiedzieć.
 */
class NotesFieldTest {

    @Test
    fun `lista zakupow z trescia dopisuje, nie odczytuje`() {
        // 20:48:43 telefonUsłyszał=lista zakupów mleko bułki chleb jajka ogórki
        val body = Notes.extract("lista zakupów mleko bułki chleb jajka ogórki")
        assertEquals("Mleko bułki chleb jajka ogórki", body)
    }

    @Test
    fun `samo lista zakupow zostaje prosba o ODCZYTANIE`() {
        // Warunek poprawności poprzedniego testu: gdyby "lista zakupów" bez
        // treści też zapisywało, odczytanie listy przestałoby istnieć.
        assertNull(Notes.extract("lista zakupów"))
        assertTrue(Notes.isListRequest("lista zakupów"))
    }

    @Test
    fun `wpisz to jak notatke - lacznik miedzy odsylaczem a rzeczownikiem`() {
        // 20:49:40 telefonUsłyszał=wpisz to jak notatkę
        val request = Notes.describeRequest("wpisz to jak notatkę")
        assertTrue("nie rozpoznane jako prośba o notatkę", request != null)
    }

    @Test
    fun `zapisz to jako notatke tez`() {
        assertTrue(Notes.describeRequest("zapisz to jako notatkę") != null)
    }

    @Test
    fun `lacznik nie przepuszcza czegos, co notatka nie jest`() {
        // Warunek ścisłości: łącznik ma dodawać jeden szyk, a nie otwierać
        // furtkę na wszystko po czasowniku.
        assertNull(Notes.describeRequest("zrób z tego jak zdjęcie"))
        assertNull(Notes.describeRequest("zrób z tego zdjęcie"))
    }

    @Test
    fun `zapisz notatke z trescia dalej dziala`() {
        // 20:47:54 - jedyna z siedmiu prób, która zadziałała. Ma działać dalej.
        assertEquals("Notatkę testową", Notes.extract("zapisz notatkę notatkę testową"))
    }

    @Test
    fun `na liste zakupow tez dopisuje`() {
        assertEquals("Chleb i masło", Notes.extract("na listę zakupów chleb i masło"))
    }
}
