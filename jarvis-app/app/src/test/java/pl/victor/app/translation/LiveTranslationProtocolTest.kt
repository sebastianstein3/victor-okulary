package pl.victor.app.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.translation.LiveTranslationProtocol.Usługa
import pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie

class LiveTranslationProtocolTest {

    @Test
    fun `usluga idzie za wybranym dostawca`() {
        assertEquals(Usługa.GEMINI, LiveTranslationProtocol.wybierz("gemini", "g", "o")?.first)
        assertEquals(Usługa.OPENAI, LiveTranslationProtocol.wybierz("openai", "g", "o")?.first)
        assertEquals(Usługa.GEMINI, LiveTranslationProtocol.wybierz("claude", "g", "o")?.first)
        assertEquals(Usługa.OPENAI, LiveTranslationProtocol.wybierz("gemini", null, "o")?.first)
        assertNull(LiveTranslationProtocol.wybierz("gemini", null, null))
    }

    @Test
    fun `adresy i naglowki`() {
        assertTrue(LiveTranslationProtocol.adres(Usługa.GEMINI, "K").endsWith("BidiGenerateContent?key=K"))
        assertEquals(
            "wss://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate",
            LiveTranslationProtocol.adres(Usługa.OPENAI, "K")
        )
        assertEquals("Bearer K", LiveTranslationProtocol.nagłówki(Usługa.OPENAI, "K")["Authorization"])
        assertTrue(LiveTranslationProtocol.nagłówki(Usługa.GEMINI, "K").isEmpty())
    }

    @Test
    fun `ustawienia gemini - jezyk docelowy bez echa`() {
        val o = Json.parseToJsonElement(LiveTranslationProtocol.ustawienia(Usługa.GEMINI, "pl-PL")).jsonObject
        val setup = o["setup"]!!.jsonObject
        assertEquals("models/gemini-3.5-live-translate-preview", setup["model"]!!.jsonPrimitive.content)
        val tc = setup["generationConfig"]!!.jsonObject["translationConfig"]!!.jsonObject
        assertEquals("pl", tc["targetLanguageCode"]!!.jsonPrimitive.content)
        assertEquals("false", tc["echoTargetLanguage"]!!.jsonPrimitive.content)
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
    }

    @Test
    fun `ustawienia openai - jezyk wyjscia`() {
        val o = Json.parseToJsonElement(LiveTranslationProtocol.ustawienia(Usługa.OPENAI, "pl")).jsonObject
        assertEquals("session.update", o["type"]!!.jsonPrimitive.content)
        assertEquals(
            "pl",
            o["session"]!!.jsonObject["audio"]!!.jsonObject["output"]!!.jsonObject["language"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `dzwiek w obu formatach`() {
        val g = Json.parseToJsonElement(LiveTranslationProtocol.dźwięk(Usługa.GEMINI, byteArrayOf(1, 2, 3))).jsonObject
        val audio = g["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("AQID", audio["data"]!!.jsonPrimitive.content)
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        val o = Json.parseToJsonElement(LiveTranslationProtocol.dźwięk(Usługa.OPENAI, byteArrayOf(1, 2, 3))).jsonObject
        assertEquals("session.input_audio_buffer.append", o["type"]!!.jsonPrimitive.content)
        assertEquals("AQID", o["audio"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini - jedna wiadomosc kilka zdarzen`() {
        val w = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQID"}}]},
            "outputTranscription":{"text":"Dzień dobry"},"inputTranscription":{"text":"Good morning"},"turnComplete":true}}"""
        val z = LiveTranslationProtocol.rozbierz(Usługa.GEMINI, w)
        assertEquals(Zdarzenie.Oryginał("Good morning"), z.filterIsInstance<Zdarzenie.Oryginał>().single())
        assertEquals(Zdarzenie.Przekład("Dzień dobry"), z.filterIsInstance<Zdarzenie.Przekład>().single())
        assertArrayEquals(byteArrayOf(1, 2, 3), z.filterIsInstance<Zdarzenie.Dźwięk>().single().pcm)
        assertTrue(z.last() === Zdarzenie.KoniecTury)
        assertEquals(listOf(Zdarzenie.Gotowe), LiveTranslationProtocol.rozbierz(Usługa.GEMINI, """{"setupComplete":{}}"""))
    }

    @Test
    fun `openai - zdarzenia sesji`() {
        fun r(s: String) = LiveTranslationProtocol.rozbierz(Usługa.OPENAI, s)
        assertEquals(listOf(Zdarzenie.Gotowe), r("""{"type":"session.created"}"""))
        assertEquals(listOf(Zdarzenie.Przekład("Hola")), r("""{"type":"session.output_transcript.delta","delta":"Hola"}"""))
        assertEquals(listOf(Zdarzenie.Oryginał("Hello")), r("""{"type":"session.input_transcript.delta","delta":"Hello"}"""))
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            (r("""{"type":"session.output_audio.delta","delta":"AQID"}""").single() as Zdarzenie.Dźwięk).pcm
        )
        assertEquals(listOf(Zdarzenie.Zamknięte), r("""{"type":"session.closed"}"""))
        assertEquals(
            Zdarzenie.Błąd("invalid_key: zły klucz"),
            r("""{"type":"error","error":{"code":"invalid_key","message":"zły klucz"}}""").single()
        )
        assertTrue(r("nie json").isEmpty())
    }

    @Test
    fun `bufor zamyka zdanie po ciszy`() {
        val b = LiveTranscriptBuffer()
        b.dodajOryginał("Good ", 0)
        b.dodajOryginał("morning", 100)
        b.dodajPrzekład("Dzień dobry", 200)
        assertFalse(b.czasZamknąć(1_000))
        assertTrue(b.czasZamknąć(200 + LiveTranscriptBuffer.CISZA_ZAMYKA_MS))
        assertEquals("Good morning" to "Dzień dobry", b.zamknij())
        assertEquals("", b.oryginałTeraz)
        // Sam oryginał (np. Twoje polskie zdanie) - czyszczony, bez segmentu.
        b.dodajOryginał("To po polsku", 10_000)
        assertFalse(b.czasZamknąć(10_000 + LiveTranscriptBuffer.CISZA_ZAMYKA_MS))
        assertTrue(b.czasZamknąć(10_000 + LiveTranscriptBuffer.CISZA_SAM_ORYGINAŁ_MS))
        assertNull(b.zamknij())
    }
}
