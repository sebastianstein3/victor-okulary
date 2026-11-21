package pl.victor.app.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudVoiceLogicTest {

    // === Dzielenie na zdania ===

    @Test
    fun `zwykle zdania sa dzielone`() {
        assertEquals(
            listOf("Dzień dobry.", "Jest dwadzieścia stopni!", "Czy wziąć parasol?"),
            CloudVoiceLogic.splitSentences("Dzień dobry. Jest dwadzieścia stopni! Czy wziąć parasol?")
        )
    }

    @Test
    fun `skroty nie koncza zdania`() {
        assertEquals(
            listOf("Apteka jest na ul. Marszałkowskiej 10.", "Otwarta do 20."),
            CloudVoiceLogic.splitSentences("Apteka jest na ul. Marszałkowskiej 10. Otwarta do 20.")
        )
        assertEquals(
            listOf("Weź np. Kurtkę, m.in. Dlatego że pada.", "Potem wyjdź."),
            CloudVoiceLogic.splitSentences("Weź np. Kurtkę, m.in. Dlatego że pada. Potem wyjdź.")
        )
        assertEquals(
            listOf("Umów się z dr. Nowakiem albo prof. Kowalską.", "Wizyta o godz. 15."),
            CloudVoiceLogic.splitSentences("Umów się z dr. Nowakiem albo prof. Kowalską. Wizyta o godz. 15.")
        )
    }

    @Test
    fun `liczby z kropka i porzadkowe nie dziela`() {
        assertEquals(
            listOf("Kurs to 4.35 zł, a spotkanie o 12.30 w sali nr 2.", "Zajęliśmy 3. miejsce."),
            CloudVoiceLogic.splitSentences("Kurs to 4.35 zł, a spotkanie o 12.30 w sali nr 2. Zajęliśmy 3. miejsce.")
        )
        // Liczba na końcu zdania, następne od wielkiej litery - to JEST koniec.
        assertEquals(
            listOf("Temperatura wynosi 12.", "Jutro cieplej."),
            CloudVoiceLogic.splitSentences("Temperatura wynosi 12. Jutro cieplej.")
        )
    }

    @Test
    fun `inicjal i mala litera po kropce nie dziela`() {
        assertEquals(
            listOf("Napisał to J. Kowalski w 1990 r. w Krakowie."),
            CloudVoiceLogic.splitSentences("Napisał to J. Kowalski w 1990 r. w Krakowie.")
        )
    }

    @Test
    fun `nowa linia i wielokropek dziela, cudzyslow zostaje przy zdaniu`() {
        assertEquals(
            listOf("Pierwsza linia", "Hmm...", "Dobrze, \"zrobione.\"", "Gotowe!"),
            CloudVoiceLogic.splitSentences("Pierwsza linia\nHmm... Dobrze, \"zrobione.\" Gotowe!")
        )
    }

    @Test
    fun `krotki kawalek doklejany do nastepnego`() {
        assertEquals(
            listOf("Tak. Już sprawdzam."),
            CloudVoiceLogic.splitSentences("Tak. Już sprawdzam.")
        )
        assertEquals(listOf("Słucham."), CloudVoiceLogic.splitSentences("Słucham."))
        assertEquals(listOf("Ok."), CloudVoiceLogic.splitSentences("  Ok.  "))
        assertTrue(CloudVoiceLogic.splitSentences("   \n ").isEmpty())
    }

    @Test
    fun `za dlugie zdanie ciete po przecinku`() {
        val part = "to jest dość długi fragment zdania bez kropki"
        val long = (1..8).joinToString(", ") { "$part $it" } + "."
        val out = CloudVoiceLogic.splitSentences(long)
        assertTrue(out.size > 1)
        out.forEach { assertTrue(it, it.length <= CloudVoiceConfig.MAX_SENTENCE_CHARS) }
        // Nic nie zginęło.
        assertEquals(long.replace(" ", ""), out.joinToString("").replace(" ", ""))
    }

    // === Zapytania ===

    @Test
    fun `zapytanie gemini ma model glos styl i surowe pcm`() {
        val o = Json.parseToJsonElement(
            CloudVoiceLogic.geminiRequest("Cześć.", "Kore").toString()
        ).jsonObject
        assertEquals(CloudVoiceConfig.GEMINI_MODEL, o["model"]!!.jsonPrimitive.content)
        val input = o["input"]!!.jsonArray[0].jsonObject
        assertEquals("user_input", input["type"]!!.jsonPrimitive.content)
        val text = input["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", text["type"]!!.jsonPrimitive.content)
        assertEquals("Cześć.", text["text"]!!.jsonPrimitive.content)
        val ann = text["annotations"]!!.jsonArray[0].jsonObject
        assertEquals("speech_metadata", ann["type"]!!.jsonPrimitive.content)
        assertEquals(CloudVoiceConfig.GEMINI_STYLE, ann["style"]!!.jsonPrimitive.content)
        val fmt = o["response_format"]!!.jsonObject
        assertEquals("audio", fmt["type"]!!.jsonPrimitive.content)
        assertEquals("audio/l16", fmt["mime_type"]!!.jsonPrimitive.content)
        assertEquals(24_000, fmt["sample_rate"]!!.jsonPrimitive.int)
        val voice = o["generation_config"]!!.jsonObject["speech_config"]!!.jsonArray[0].jsonObject
        assertEquals("Kore", voice["voice"]!!.jsonPrimitive.content)
    }

    @Test
    fun `zapytanie openai ma pcm i instrukcje`() {
        val o = Json.parseToJsonElement(
            CloudVoiceLogic.openAiRequest("Dzień \"dobry\"", "marin").toString()
        ).jsonObject
        assertEquals("gpt-4o-mini-tts", o["model"]!!.jsonPrimitive.content)
        assertEquals("Dzień \"dobry\"", o["input"]!!.jsonPrimitive.content)
        assertEquals("marin", o["voice"]!!.jsonPrimitive.content)
        assertEquals("pcm", o["response_format"]!!.jsonPrimitive.content)
        assertEquals(CloudVoiceConfig.OPENAI_INSTRUCTIONS, o["instructions"]!!.jsonPrimitive.content)
    }

    // === Odpowiedź Gemini ===

    private fun geminiResponse(vararg audio: ByteArray): String = buildJsonObject {
        put("id", "x")
        put("steps", buildJsonArray {
            add(buildJsonObject { put("type", "user_input") })
            add(buildJsonObject {
                put("type", "model_output")
                put("content", buildJsonArray {
                    audio.forEach { a ->
                        add(buildJsonObject {
                            put("type", "audio")
                            put("mime_type", "audio/l16")
                            put("data", java.util.Base64.getEncoder().encodeToString(a))
                        })
                    }
                })
            })
        })
    }.toString()

    @Test
    fun `rozbior odpowiedzi gemini bierze ostatni blok audio`() {
        val first = byteArrayOf(9, 9)
        val last = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(last, CloudVoiceLogic.parseGeminiAudio(geminiResponse(first, last)))
    }

    @Test
    fun `odpowiedz gemini w wav traci naglowek, nieparzysty bajt obciety`() {
        val pcm = byteArrayOf(10, 20, 30, 40, 50, 60)
        val wav = WavWriter.wrap(pcm, 24_000)
        assertArrayEquals(pcm, CloudVoiceLogic.parseGeminiAudio(geminiResponse(wav)))
        assertArrayEquals(byteArrayOf(1, 2), CloudVoiceLogic.parseGeminiAudio(geminiResponse(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `zla odpowiedz gemini daje null, blad da sie odczytac`() {
        assertNull(CloudVoiceLogic.parseGeminiAudio("{\"steps\":[]}"))
        assertNull(CloudVoiceLogic.parseGeminiAudio("nie json"))
        val err = "{\"error\":{\"code\":400,\"message\":\"Invalid voice\"}}"
        assertNull(CloudVoiceLogic.parseGeminiAudio(err))
        assertEquals("Invalid voice", CloudVoiceLogic.errorMessage(err))
    }

    @Test
    fun `surowe pcm bez naglowka zostaje bez zmian`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(pcm, CloudVoiceLogic.stripWavHeader(pcm))
    }

    // === Pamięć podręczna ===

    @Test
    fun `klucz pamieci zalezy od glosu i tekstu, nie od spacji`() {
        val g = CloudVoiceProvider.GEMINI
        val m = CloudVoiceConfig.GEMINI_MODEL
        val s = CloudVoiceConfig.GEMINI_STYLE
        val a = CloudVoiceLogic.cacheKey(g, m, "Kore", s, "Słucham.")
        assertEquals(a, CloudVoiceLogic.cacheKey(g, m, "Kore", s, "  Słucham. "))
        assertNotEquals(a, CloudVoiceLogic.cacheKey(g, m, "Charon", s, "Słucham."))
        assertNotEquals(a, CloudVoiceLogic.cacheKey(CloudVoiceProvider.OPENAI, m, "Kore", s, "Słucham."))
        assertNotEquals(a, CloudVoiceLogic.cacheKey(g, m, "Kore", s, "Już sprawdzam."))
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `pamietamy tylko krotkie zdania i wyrzucamy najstarsze`() {
        assertTrue(CloudVoiceLogic.isCacheable("Już sprawdzam."))
        assertFalse(CloudVoiceLogic.isCacheable("x".repeat(CloudVoiceConfig.CACHE_MAX_CHARS + 1)))
        assertFalse(CloudVoiceLogic.isCacheable("   "))
        val entries = listOf("a" to 30L, "b" to 10L, "c" to 20L, "d" to 40L)
        assertEquals(listOf("b", "c"), CloudVoiceLogic.evictionVictims(entries, 2))
        assertTrue(CloudVoiceLogic.evictionVictims(entries, 4).isEmpty())
    }

    // === Decyzja ===

    @Test
    fun `tylko darmowe zawsze daje systemowy`() {
        val d = CloudVoiceLogic.decide("gemini", freeOnly = true, geminiKey = "k", openAiKey = "k")
        assertEquals(CloudVoiceProvider.SYSTEM, d.provider)
        assertEquals(CloudVoiceProvider.GEMINI, d.chosen)
        assertEquals("tylko darmowe funkcje", d.reason)
        assertFalse(d.isCloud)
    }

    @Test
    fun `brak klucza wybranej uslugi daje systemowy, klucz drugiej nie pomaga`() {
        assertEquals(
            "brak klucza Gemini",
            CloudVoiceLogic.decide("gemini", false, geminiKey = " ", openAiKey = "o").reason
        )
        assertEquals(
            CloudVoiceProvider.SYSTEM,
            CloudVoiceLogic.decide("openai", false, geminiKey = "g", openAiKey = null).provider
        )
    }

    @Test
    fun `wybor z kluczem obowiazuje, domyslnie systemowy`() {
        assertEquals(CloudVoiceProvider.GEMINI, CloudVoiceLogic.decide("gemini", false, "g", null).provider)
        assertEquals(CloudVoiceProvider.OPENAI, CloudVoiceLogic.decide("openai", false, null, "o").provider)
        val none = CloudVoiceLogic.decide(null, false, "g", "o")
        assertEquals(CloudVoiceProvider.SYSTEM, none.provider)
        assertNull(none.reason)
        assertEquals(CloudVoiceProvider.SYSTEM, CloudVoiceLogic.decide("cos-innego", false, "g", "o").provider)
    }

    @Test
    fun `glos spoza listy uslugi wraca do domyslnego`() {
        assertEquals("Charon", CloudVoiceLogic.voiceFor(CloudVoiceProvider.GEMINI, "Charon"))
        assertEquals(CloudVoiceConfig.GEMINI_DEFAULT_VOICE, CloudVoiceLogic.voiceFor(CloudVoiceProvider.GEMINI, "marin"))
        assertEquals(CloudVoiceConfig.OPENAI_DEFAULT_VOICE, CloudVoiceLogic.voiceFor(CloudVoiceProvider.OPENAI, null))
    }

    @Test
    fun `chmura tylko dla polskiego`() {
        assertTrue(CloudVoiceLogic.isCloudLanguage("pl"))
        assertTrue(CloudVoiceLogic.isCloudLanguage("pl-PL"))
        assertTrue(CloudVoiceLogic.isCloudLanguage("pl_PL"))
        assertFalse(CloudVoiceLogic.isCloudLanguage("en"))
        assertFalse(CloudVoiceLogic.isCloudLanguage("de-DE"))
    }

    @Test
    fun `limity czasu i przerwy po porazce`() {
        assertEquals(2_500L, CloudVoiceLogic.waitLimitMs(true, 0))
        assertEquals(1_500L, CloudVoiceLogic.waitLimitMs(true, 1_000))
        assertEquals(50L, CloudVoiceLogic.waitLimitMs(true, 10_000))
        assertEquals(CloudVoiceConfig.NEXT_SENTENCE_TIMEOUT_MS, CloudVoiceLogic.waitLimitMs(false, 10_000))
        assertEquals(CloudVoiceFallback.AUTH, CloudVoiceLogic.classifyHttp(401))
        assertEquals(CloudVoiceFallback.LIMIT, CloudVoiceLogic.classifyHttp(429))
        assertEquals(CloudVoiceFallback.NOT_FOUND, CloudVoiceLogic.classifyHttp(404))
        assertEquals(CloudVoiceFallback.HTTP, CloudVoiceLogic.classifyHttp(500))
        assertTrue(
            CloudVoiceLogic.cooldownMs(CloudVoiceFallback.AUTH) > CloudVoiceLogic.cooldownMs(CloudVoiceFallback.TIMEOUT)
        )
    }
}
