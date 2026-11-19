package pl.victor.app.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.victor.app.conversation.CloudSpeechToText.Usługa

class CloudSpeechToTextTest {

    @Test
    fun `usluga idzie za wybranym dostawca`() {
        assertEquals(Usługa.GEMINI, CloudSpeechToText.wybierz("gemini", "g", "o")?.first)
        assertEquals(Usługa.OPENAI, CloudSpeechToText.wybierz("openai", "g", "o")?.first)
        // Inny dostawca: OpenAI jak dotąd, bez jego klucza - Gemini.
        assertEquals(Usługa.OPENAI, CloudSpeechToText.wybierz("claude", "g", "o")?.first)
        assertEquals(Usługa.GEMINI, CloudSpeechToText.wybierz("claude", "g", null)?.first)
        // Wybrany Gemini bez klucza Gemini - zostaje OpenAI.
        assertEquals(Usługa.OPENAI, CloudSpeechToText.wybierz("gemini", " ", "o")?.first)
        assertNull(CloudSpeechToText.wybierz("gemini", null, ""))
    }

    @Test
    fun `kody jezykow dla obu uslug`() {
        assertEquals("pl", CloudSpeechToText.kodOpenAi("pl-PL"))
        assertEquals("pl-PL", CloudSpeechToText.kodGemini("pl-PL"))
        assertEquals("pl-PL", CloudSpeechToText.kodGemini("pl"))
        assertEquals("en-US", CloudSpeechToText.kodGemini("en"))
    }

    @Test
    fun `zapytanie gemini ma nagranie i jezyk`() {
        val body = CloudSpeechToText.ciałoGemini(byteArrayOf(1, 2, 3), "pl-PL")
        val o = Json.parseToJsonElement(body.toString()).jsonObject
        assertEquals("models/gemini-3.5-transcribe", o["model"]!!.jsonPrimitive.content)
        val audio = o["input"]!!.jsonArray[0].jsonObject
        assertEquals("audio", audio["type"]!!.jsonPrimitive.content)
        assertEquals("audio/wav", audio["mime_type"]!!.jsonPrimitive.content)
        assertEquals("AQID", audio["data"]!!.jsonPrimitive.content)
        val kody = o["generation_config"]!!.jsonObject["transcription_config"]!!
            .jsonObject["language_codes"]!!.jsonArray
        assertEquals("pl-PL", kody[0].jsonPrimitive.content)
    }

    @Test
    fun `tekst z odpowiedzi gemini`() {
        val odp = """{"id":"interactions/abc","status":"completed","steps":[{"type":"model_output",
            "content":[{"type":"text","text":"Jaka jest pogoda?"}]}]}"""
        assertEquals("Jaka jest pogoda?", CloudSpeechToText.tekstGemini(odp))
        assertEquals("Hej", CloudSpeechToText.tekstGemini("""{"output_text":" Hej "}"""))
        assertNull(CloudSpeechToText.tekstGemini("""{"steps":[]}"""))
        assertNull(CloudSpeechToText.tekstGemini("nie json"))
    }

    @Test
    fun `tekst z odpowiedzi openai`() {
        assertEquals("Dzień dobry", CloudSpeechToText.tekstOpenAi("""{"text":"Dzień dobry","languages":[{"code":"pl"}]}"""))
        assertNull(CloudSpeechToText.tekstOpenAi("""{"text":""}"""))
    }
}
