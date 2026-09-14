package pl.victor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tu łatwo o pomyłkę w OBIE strony: ukryty działający model to funkcja, której
 * nikt nie znajdzie, a pokazany nieistniejący to błąd przy pierwszym pytaniu.
 */
class ModelCatalogTest {

    private val known = ModelRegistry.forProvider("openai").map { it.id }

    @Test
    fun `brak odpowiedzi z API pokazuje katalog`() {
        // Bez klucza albo bez sieci lista przychodzi pusta. Pusty wybór
        // zostawiłby użytkownika bez możliwości cokolwiek ustawić.
        val list = ModelCatalog.forPicker("openai", emptyList())
        assertTrue(list.isNotEmpty())
    }

    @Test
    fun `model ktorego API nie zwraca wypada`() {
        val list = ModelCatalog.forPicker("openai", listOf(known.first()))
        assertEquals(1, list.size)
        assertEquals(known.first(), list.first().id)
    }

    @Test
    fun `nieznany model z API wchodzi z surowym ID`() {
        val list = ModelCatalog.forPicker("deepseek", listOf("deepseek-cos-nowego"))
        val found = list.single { it.id == "deepseek-cos-nowego" }
        assertEquals("deepseek-cos-nowego", found.displayName)
        assertTrue(found.description.contains("Wykryty"))
    }

    @Test
    fun `opisane modele ida przed wykrytymi`() {
        val list = ModelCatalog.forPicker("openai", listOf("zzz-nieznany", known.first()))
        assertEquals(known.first(), list.first().id)
    }

    @Test
    fun `wizja jest zgadywana tylko z jednoznacznej nazwy`() {
        // Fałszywe "obsługuje obrazy" kończy się wysłaniem zdjęcia do modelu
        // tekstowego i błędem zamiast odpowiedzi.
        val vision = ModelCatalog.forPicker("deepseek", listOf("deepseek-vl2-small")).single()
        assertTrue(vision.supportsVision)
        val text = ModelCatalog.forPicker("deepseek", listOf("deepseek-chat")).single()
        assertFalse(text.supportsVision)
    }

    @Test
    fun `zniknięty wybor uzytkownika wraca jako null`() {
        val available = ModelCatalog.forPicker("deepseek", listOf("deepseek-chat"))
        assertEquals("deepseek-chat", ModelCatalog.keepIfStillThere("deepseek-chat", available))
        assertNull(ModelCatalog.keepIfStillThere("model-ktory-zniknal", available))
        assertNull(ModelCatalog.keepIfStillThere(null, available))
    }

    @Test
    fun `puste i powtorzone ID z API sa czyszczone`() {
        val list = ModelCatalog.forPicker("deepseek", listOf("deepseek-chat", " ", "deepseek-chat"))
        assertEquals(1, list.size)
    }

    // --- Skąd wzięła się lista ---
    //
    // Zgłoszenie: "pokazuje bardzo mało modeli, jakby się nie aktualizowały".
    // Cztery różne przyczyny dawały ten sam ekran, więc każda musi teraz
    // powiedzieć o sobie sama.

    @Test
    fun `lista z API mowi ile modeli`() {
        val opis = ModelCatalog.Source.FromApi(7).message()
        assertTrue(opis, opis.contains("7 modeli"))
        assertTrue(opis, opis.contains("API"))
    }

    @Test
    fun `liczebnik po polsku zamiast 1 modeli`() {
        assertTrue(ModelCatalog.Source.FromApi(1).message().contains("(1 model)"))
        assertTrue(ModelCatalog.Source.FromApi(2).message().contains("2 modele"))
        assertTrue(ModelCatalog.Source.FromApi(5).message().contains("5 modeli"))
        // Nastki są wyjątkiem: "12 modeli", nie "12 modele".
        assertTrue(ModelCatalog.Source.FromApi(12).message().contains("12 modeli"))
        assertTrue(ModelCatalog.Source.FromApi(22).message().contains("22 modele"))
    }

    @Test
    fun `brak klucza mowi o kluczu a nie o bledzie`() {
        val opis = ModelCatalog.Source.NoApiKey.message()
        assertTrue(opis, opis.contains("klucza"))
        assertTrue(opis, opis.contains("katalog aplikacji"))
    }

    @Test
    fun `nieudane pobranie niesie powod`() {
        val opis = ModelCatalog.Source.AskFailed("HTTP 401").message()
        assertTrue(opis, opis.contains("HTTP 401"))
    }

    @Test
    fun `nieudane pobranie bez powodu nie zostawia pustego nawiasu`() {
        val opis = ModelCatalog.Source.AskFailed(null).message()
        assertFalse(opis, opis.contains("()"))
    }

    @Test
    fun `pusta odpowiedz API to inny komunikat niz brak klucza`() {
        assertNotEquals(
            ModelCatalog.Source.ApiEmpty.message(),
            ModelCatalog.Source.NoApiKey.message()
        )
    }
}
