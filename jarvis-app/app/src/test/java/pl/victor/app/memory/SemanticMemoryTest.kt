package pl.victor.app.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * Pamięć po znaczeniu: arytmetyka wektorów, łączenie rankingów, zapis
 * indeksu, JSON usług i decyzja włączenia. Sieć i Android są poza testem -
 * to, co tu jest, decyduje o tym, CO trafi do modelu.
 */
class SemanticMemoryTest {

    private val gemini = SemanticMemory.Provider.GEMINI
    private val openai = SemanticMemory.Provider.OPENAI

    // === Wektory ===

    @Test
    fun `normalizacja daje dlugosc jeden`() {
        val n = SemanticMemory.normalize(floatArrayOf(3f, 4f))!!
        assertEquals(0.6f, n[0], 1e-6f)
        assertEquals(0.8f, n[1], 1e-6f)
        assertEquals(1f, SemanticMemory.dot(n, n), 1e-6f)
    }

    @Test
    fun `wektor zerowy albo z NaN odpada`() {
        assertNull(SemanticMemory.normalize(floatArrayOf(0f, 0f)))
        assertNull(SemanticMemory.normalize(floatArrayOf(1f, Float.NaN)))
        assertNull(SemanticMemory.normalize(floatArrayOf()))
    }

    @Test
    fun `cosinus rozpoznaje kierunek a nie dlugosc`() {
        assertEquals(1f, SemanticMemory.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6f)
        assertEquals(0f, SemanticMemory.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 5f)), 1e-6f)
        assertEquals(-1f, SemanticMemory.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-6f)
        // Różne wymiary to błąd danych, nie podobieństwo.
        assertEquals(0f, SemanticMemory.dot(floatArrayOf(1f), floatArrayOf(1f, 0f)), 0f)
    }

    @Test
    fun `najblizsze ponad progiem od najlepszego`() {
        val q = SemanticMemory.normalize(floatArrayOf(1f, 0f, 0f))!!
        val vectors = mapOf(
            "n:klucze" to SemanticMemory.normalize(floatArrayOf(0.9f, 0.1f, 0f))!!,
            "n:mleko" to SemanticMemory.normalize(floatArrayOf(0f, 1f, 0f))!!,
            "n:szafka" to SemanticMemory.normalize(floatArrayOf(0.7f, 0.7f, 0f))!!,
            "n:zlywymiar" to floatArrayOf(1f, 0f)
        )
        val hits = SemanticMemory.nearest(q, vectors, minSimilarity = 0.5f)
        assertEquals(listOf("n:klucze", "n:szafka"), hits.map { it.key })
        assertTrue(hits[0].similarity > hits[1].similarity)
        assertEquals(hits[0].similarity, SemanticMemory.bestSimilarity(q, vectors)!!, 1e-6f)
    }

    // === Łączenie rankingów ===

    @Test
    fun `wpis wysoko na obu listach wygrywa`() {
        val keyword = listOf("a", "b", "c")
        val semantic = listOf("d", "b", "a")
        val fused = SemanticMemory.fuse(listOf(keyword, semantic), limit = 4)
        // a: 1/61 + 1/63, b: 2/62 - a minimalnie wyżej (wyższe miejsce liczy się mocniej); oba przed d i c.
        assertEquals(listOf("a", "b", "d", "c"), fused)
    }

    @Test
    fun `lista semantyczna dorzuca to czego slowa nie znalazly`() {
        // "gdzie zostawiłem klucze" - słowa nic, wektory trafiają w notatkę.
        val fused = SemanticMemory.fuse(listOf(emptyList(), listOf("n:szafka")))
        assertEquals(listOf("n:szafka"), fused)
    }

    @Test
    fun `laczenie respektuje limit i duplikaty w liscie`() {
        val fused = SemanticMemory.fuse(listOf(listOf("a", "a", "b", "c", "d", "e")), limit = 2)
        assertEquals(listOf("a", "b"), fused)
        assertTrue(SemanticMemory.fuse(emptyList()).isEmpty())
    }

    // === Indeks na dysku ===

    @Test
    fun `indeks przezywa zapis i odczyt`() {
        val index = SemanticIndexCodec.Index(
            modelTag = gemini.modelTag,
            dims = 3,
            vectors = linkedMapOf(
                "r:12" to floatArrayOf(0.1f, -0.2f, 0.97f),
                "n:abc" to floatArrayOf(1f, 0f, 0f)
            ),
            budgetDay = 20_000L,
            usedThatDay = 42
        )
        val back = SemanticIndexCodec.decode(SemanticIndexCodec.encode(index))!!
        assertEquals(index.modelTag, back.modelTag)
        assertEquals(3, back.dims)
        assertEquals(20_000L, back.budgetDay)
        assertEquals(42, back.usedThatDay)
        assertEquals(listOf("r:12", "n:abc"), back.vectors.keys.toList())
        assertArrayEquals(floatArrayOf(0.1f, -0.2f, 0.97f), back.vectors["r:12"], 0f)
    }

    @Test
    fun `wektor o zlym wymiarze nie trafia do pliku`() {
        val index = SemanticIndexCodec.Index(
            "x", 2, mapOf("ok" to floatArrayOf(1f, 0f), "zly" to floatArrayOf(1f, 0f, 0f))
        )
        val back = SemanticIndexCodec.decode(SemanticIndexCodec.encode(index))!!
        assertEquals(setOf("ok"), back.vectors.keys)
    }

    @Test
    fun `uszkodzony plik to brak indeksu a nie wyjatek`() {
        assertNull(SemanticIndexCodec.decode(byteArrayOf()))
        assertNull(SemanticIndexCodec.decode("to nie jest indeks".toByteArray()))
        val good = SemanticIndexCodec.encode(SemanticIndexCodec.Index("x", 4, mapOf("a" to FloatArray(4))))
        assertNull(SemanticIndexCodec.decode(good.copyOf(good.size - 3)))
    }

    @Test
    fun `zmiana modelu zmienia znacznik`() {
        assertNotEquals(gemini.modelTag, openai.modelTag)
        assertTrue(gemini.modelTag.contains(gemini.model))
        assertTrue(gemini.modelTag.endsWith("/${gemini.dims}"))
    }

    // === JSON zapytań ===

    @Test
    fun `zapytanie Gemini ma model wymiar i tekst z przedrostkiem`() {
        val texts = listOf(
            SemanticMemory.documentText(gemini, "Położyłem klucze na szafce"),
            SemanticMemory.queryText(gemini, "gdzie zostawiłem klucze?")
        )
        val body = Json.parseToJsonElement(SemanticMemory.requestBody(gemini, texts)).jsonObject
        val requests = body["requests"]!!.jsonArray
        assertEquals(2, requests.size)
        val first = requests[0].jsonObject
        assertEquals("models/gemini-embedding-2", first["model"]!!.jsonPrimitive.content)
        assertEquals(gemini.dims, first["outputDimensionality"]!!.jsonPrimitive.int)
        val text0 = first["content"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("title: none | text: Położyłem klucze na szafce", text0)
        val text1 = requests[1].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("task: search result | query: gdzie zostawiłem klucze?", text1)
        // gemini-embedding-2 nie przyjmuje taskType.
        assertNull(first["taskType"])
        assertTrue(SemanticMemory.url(gemini).endsWith("/models/gemini-embedding-2:batchEmbedContents"))
    }

    @Test
    fun `zapytanie OpenAI ma liste wejsc i wymiar`() {
        val body = Json.parseToJsonElement(
            SemanticMemory.requestBody(openai, listOf("a \"cytat\"", "b"))
        ).jsonObject
        assertEquals("text-embedding-3-small", body["model"]!!.jsonPrimitive.content)
        assertEquals(openai.dims, body["dimensions"]!!.jsonPrimitive.int)
        assertEquals(listOf("a \"cytat\"", "b"), body["input"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("float", body["encoding_format"]!!.jsonPrimitive.content)
        // OpenAI nie dostaje przedrostków Gemini.
        assertEquals("pytanie", SemanticMemory.queryText(openai, "  pytanie "))
    }

    @Test
    fun `dlugi tekst jest przycinany`() {
        val long = "x".repeat(SemanticMemory.MAX_DOC_CHARS + 500)
        assertEquals(SemanticMemory.MAX_DOC_CHARS, SemanticMemory.documentText(openai, long).length)
    }

    // === Rozbiór odpowiedzi ===

    private fun vecJson(dims: Int, first: Float): String =
        (0 until dims).joinToString(",", "[", "]") { if (it == 0) first.toString() else "0" }

    @Test
    fun `odpowiedz Gemini batch daje wektory w kolejnosci i znormalizowane`() {
        val body = """{"embeddings":[{"values":${vecJson(gemini.dims, 2f)}},{"values":${vecJson(gemini.dims, -3f)}}]}"""
        val vectors = SemanticMemory.parseResponse(gemini, body, 2)!!
        assertEquals(2, vectors.size)
        assertEquals(1f, vectors[0][0], 1e-6f)
        assertEquals(-1f, vectors[1][0], 1e-6f)
    }

    @Test
    fun `odpowiedz Gemini pojedyncza tez sie czyta`() {
        val body = """{"embedding":{"values":${vecJson(gemini.dims, 5f)}}}"""
        assertNotNull(SemanticMemory.parseResponse(gemini, body, 1))
    }

    @Test
    fun `odpowiedz OpenAI ukladana wedlug index`() {
        val body = """{"object":"list","data":[
            {"object":"embedding","index":1,"embedding":${vecJson(openai.dims, -1f)}},
            {"object":"embedding","index":0,"embedding":${vecJson(openai.dims, 1f)}}
        ],"usage":{"prompt_tokens":5,"total_tokens":5}}"""
        val vectors = SemanticMemory.parseResponse(openai, body, 2)!!
        assertEquals(1f, vectors[0][0], 1e-6f)
        assertEquals(-1f, vectors[1][0], 1e-6f)
    }

    @Test
    fun `zla liczba albo wymiar albo smieci to null`() {
        val one = """{"embeddings":[{"values":${vecJson(gemini.dims, 1f)}}]}"""
        assertNull(SemanticMemory.parseResponse(gemini, one, 2))
        assertNull(SemanticMemory.parseResponse(gemini, """{"embeddings":[{"values":[1,2,3]}]}""", 1))
        assertNull(SemanticMemory.parseResponse(gemini, "<html>", 1))
        assertNull(SemanticMemory.parseResponse(openai, """{"error":{"message":"bad"}}""", 1))
        assertNull(SemanticMemory.parseResponse(gemini, """{"embeddings":[{"values":${vecJson(gemini.dims, 0f)}}]}""", 1))
    }

    @Test
    fun `opis bledu bierze komunikat uslugi`() {
        assertEquals(
            "HTTP 429: Quota exceeded",
            SemanticMemory.errorSummary(429, """{"error":{"code":429,"message":"Quota exceeded"}}""")
        )
        assertEquals("HTTP 500", SemanticMemory.errorSummary(500, "<html>"))
    }

    // === Decyzje ===

    @Test
    fun `tylko darmowe wylacza zawsze`() {
        assertFalse(SemanticMemory.isEnabled(pref = true, freeOnly = true, hasKey = true))
        assertFalse(SemanticMemory.isEnabled(pref = null, freeOnly = true, hasKey = true))
    }

    @Test
    fun `bez klucza wylaczone nawet przy wyborze`() {
        assertFalse(SemanticMemory.isEnabled(pref = true, freeOnly = false, hasKey = false))
        assertFalse(SemanticMemory.isEnabled(pref = null, freeOnly = false, hasKey = false))
    }

    @Test
    fun `domyslnie wlaczone gdy jest klucz a wybor uzytkownika wygrywa`() {
        assertTrue(SemanticMemory.isEnabled(pref = null, freeOnly = false, hasKey = true))
        assertFalse(SemanticMemory.isEnabled(pref = false, freeOnly = false, hasKey = true))
        assertTrue(SemanticMemory.isEnabled(pref = true, freeOnly = false, hasKey = true))
    }

    @Test
    fun `dostawca aktywny z kluczem inaczej ten z kluczem`() {
        assertEquals(openai, SemanticMemory.chooseProvider("openai", true, true))
        assertEquals(gemini, SemanticMemory.chooseProvider("gemini", true, true))
        assertEquals(openai, SemanticMemory.chooseProvider("gemini", false, true))
        assertEquals(gemini, SemanticMemory.chooseProvider("deepseek", true, true))
        assertEquals(gemini, SemanticMemory.chooseProvider("openai", true, false))
        assertNull(SemanticMemory.chooseProvider("gemini", false, false))
    }

    // === Kolejka i limity ===

    @Test
    fun `brakujace najnowsze pierwsze a stare do usuniecia`() {
        val docs = listOf(
            SemanticMemory.noteDoc("stara", 1L),
            SemanticMemory.noteDoc("nowa", 3L),
            SemanticMemory.conversationDoc(7, "q", "a", 2L)
        )
        val indexed = setOf(SemanticMemory.noteDoc("stara", 1L).key, "n:usunieta")
        assertEquals(
            listOf(SemanticMemory.noteDoc("nowa", 3L).key, "r:7"),
            SemanticMemory.missing(docs, indexed).map { it.key }
        )
        assertEquals(setOf("n:usunieta"), SemanticMemory.stale(indexed, docs))
    }

    @Test
    fun `klucz notatki zalezy od tresci`() {
        assertEquals(SemanticMemory.noteDoc("a", 1L).key, SemanticMemory.noteDoc(" a ", 2L).key)
        assertNotEquals(SemanticMemory.noteDoc("a", 1L).key, SemanticMemory.noteDoc("b", 1L).key)
        assertNotEquals(SemanticMemory.noteDoc("a", 1L).key, SemanticMemory.factDoc("a", 1L).key)
        assertEquals(SemanticMemory.Kind.FAKT, SemanticMemory.Kind.ofKey(SemanticMemory.factDoc("a", 1L).key))
        assertEquals(SemanticMemory.Kind.ROZMOWA, SemanticMemory.Kind.ofKey("r:5"))
    }

    @Test
    fun `dzienny limit odnawia sie nastepnego dnia`() {
        assertEquals(1000, SemanticMemory.allowance(budgetDay = 10, usedThatDay = 900, today = 11, limit = 1000))
        assertEquals(100, SemanticMemory.allowance(budgetDay = 11, usedThatDay = 900, today = 11, limit = 1000))
        assertEquals(0, SemanticMemory.allowance(budgetDay = 11, usedThatDay = 1500, today = 11, limit = 1000))
    }

    @Test
    fun `ponowienia rosna do pol godziny`() {
        assertEquals(0L, SemanticMemory.retryDelayMs(0))
        assertEquals(60_000L, SemanticMemory.retryDelayMs(1))
        assertEquals(120_000L, SemanticMemory.retryDelayMs(2))
        assertEquals(30 * 60_000L, SemanticMemory.retryDelayMs(10))
    }

    // === Kontekst i koszt ===

    @Test
    fun `kontekst ma rodzaj date i tresc w jednej linii`() {
        val ms = java.time.LocalDateTime.of(2026, 11, 20, 18, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        val ctx = SemanticMemory.buildPromptContext(
            listOf(SemanticMemory.noteDoc("Położyłem klucze\nna szafce", ms)),
            ZoneOffset.UTC
        )!!
        assertTrue(ctx, ctx.contains("- [notatka, 2026-11-20 18:05] Położyłem klucze / na szafce"))
        assertNull(SemanticMemory.buildPromptContext(emptyList()))
    }

    @Test
    fun `koszt miesieczny to grosze`() {
        val g = SemanticMemory.monthlyCostPln(gemini)
        val o = SemanticMemory.monthlyCostPln(openai)
        assertTrue("gemini $g", g in 0.05..2.0)
        assertTrue(o < g)
        assertEquals("0,31", SemanticMemory.formatPln(0.3096))
        assertEquals("0,01", SemanticMemory.formatPln(0.0001))
    }

    // === Słowa (wydzielone z LongTermMemory) ===

    @Test
    fun `slowa trafiaja tylko przy wspolnym slowie - stad wektory`() {
        val texts = listOf("Położyłem klucze na szafce", "Kupić mleko")
        val ranked = KeywordRanker.rank("gdzie zostawiłem klucze?", texts, 0.15f, 10)
        // "klucze" pasuje - ale np. "urlop" vs "wakacje" już nie.
        assertEquals(listOf(0), ranked.map { it.first })
        assertTrue(KeywordRanker.rank("co mówił Tomek o urlopie", listOf("Wakacje w lipcu"), 0.15f, 10).isEmpty())
    }

    @Test
    fun `body JSON jest poprawnym obiektem`() {
        val parsed = Json.parseToJsonElement(SemanticMemory.requestBody(gemini, listOf("a")))
        assertTrue(parsed is JsonObject)
        assertTrue((parsed as JsonObject)["requests"] is JsonArray)
    }
}
