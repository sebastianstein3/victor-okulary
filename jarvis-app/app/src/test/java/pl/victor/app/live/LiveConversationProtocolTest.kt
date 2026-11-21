package pl.victor.app.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie

class LiveConversationProtocolTest {

    @Test
    fun `setup - model glos instrukcja i transkrypcje`() {
        val o = Json.parseToJsonElement(LiveConversationProtocol.ustawienia("Jesteś V.I.C.T.O.R.")).jsonObject
        val setup = o["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        val gen = setup["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", gen["responseModalities"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(
            LiveConversationProtocol.GŁOS,
            gen["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!
                .jsonObject["voiceName"]!!.jsonPrimitive.content
        )
        // Polski wymuszamy instrukcją - pola languageCode modele z natywnym
        // dźwiękiem nie przyjmują (nieznane pole = błąd sesji).
        assertFalse(gen["speechConfig"]!!.jsonObject.containsKey("languageCode"))
        // Tłumaczeniowe ustawienie nie może przeciec do rozmowy.
        assertFalse(gen.containsKey("translationConfig"))
        val tekst = setup["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single()
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Jesteś V.I.C.T.O.R.", tekst)
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
        assertTrue(setup["contextWindowCompression"]!!.jsonObject.containsKey("slidingWindow"))
        assertFalse(setup.containsKey("tools"))
    }

    @Test
    fun `adres i ramka dzwieku jak w tlumaczeniu przez Gemini`() {
        assertTrue(LiveConversationProtocol.adres("K").endsWith("BidiGenerateContent?key=K"))
        val audio = Json.parseToJsonElement(LiveConversationProtocol.dźwięk(byteArrayOf(1, 2, 3))).jsonObject
            .getValue("realtimeInput").jsonObject.getValue("audio").jsonObject
        assertEquals("AQID", audio["data"]!!.jsonPrimitive.content)
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals(16_000, LiveConversationProtocol.PRÓBKOWANIE_WEJŚCIA)
    }

    @Test
    fun `rozbior - wejscie w slowo przed reszta`() {
        val z = LiveConversationProtocol.rozbierz("""{"serverContent":{"interrupted":true}}""")
        assertEquals(listOf(Zdarzenie.Przerwane), z)
        val dwa = LiveConversationProtocol.rozbierz(
            """{"serverContent":{"interrupted":true,"inputTranscription":{"text":"czekaj"}}}"""
        )
        assertTrue(dwa.first() === Zdarzenie.Przerwane)
        assertEquals(Zdarzenie.Oryginał("czekaj"), dwa[1])
        assertTrue(LiveConversationProtocol.rozbierz("""{"serverContent":{"interrupted":false}}""").isEmpty())
    }

    @Test
    fun `rozbior - odpowiedz transkrypcje i koniec tury`() {
        val w = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQID"}}]},
            "outputTranscription":{"text":"Cześć"},"inputTranscription":{"text":"Hej"},"turnComplete":true}}"""
        val z = LiveConversationProtocol.rozbierz(w)
        assertEquals(Zdarzenie.Oryginał("Hej"), z.filterIsInstance<Zdarzenie.Oryginał>().single())
        assertEquals(Zdarzenie.Przekład("Cześć"), z.filterIsInstance<Zdarzenie.Przekład>().single())
        assertArrayEquals(byteArrayOf(1, 2, 3), z.filterIsInstance<Zdarzenie.Dźwięk>().single().pcm)
        assertTrue(z.last() === Zdarzenie.KoniecTury)
        assertFalse(z.any { it === Zdarzenie.Przerwane })
    }

    @Test
    fun `rozbior - gotowosc koniec i bledy`() {
        assertEquals(listOf(Zdarzenie.Gotowe), LiveConversationProtocol.rozbierz("""{"setupComplete":{}}"""))
        assertEquals(listOf(Zdarzenie.Zamknięte), LiveConversationProtocol.rozbierz("""{"goAway":{"timeLeft":"10s"}}"""))
        assertEquals(
            Zdarzenie.Błąd("model not found"),
            LiveConversationProtocol.rozbierz("""{"error":{"message":"model not found"}}""").single()
        )
        assertTrue(LiveConversationProtocol.rozbierz("nie json").isEmpty())
        assertTrue(LiveConversationProtocol.rozbierz("""{"usageMetadata":{"totalTokenCount":5}}""").isEmpty())
    }

    @Test
    fun `instrukcja - persona zasady czas i miejsce`() {
        val i = LiveConversationProtocol.instrukcja("PERSONA", "=== TERAZ ===\nśroda", "=== GDZIE ===\nToruń")
        assertTrue(i.startsWith("PERSONA"))
        assertTrue(i.contains(LiveConversationProtocol.ZASADY_ROZMOWY))
        assertTrue(i.contains("po polsku"))
        assertTrue(i.contains("=== TERAZ ===\nśroda"))
        assertTrue(i.contains("Toruń"))
        // Bez znaczników akcji - w tym trybie nikt ich nie wykonuje.
        assertFalse(i.contains("[[ACTION"))
        val bezMiejsca = LiveConversationProtocol.instrukcja("P", "czas", null)
        assertFalse(bezMiejsca.contains("GDZIE"))
    }

    @Test
    fun `miejsce - nazwa bez wspolrzednych`() {
        assertEquals(
            "=== GDZIE JEST UŻYTKOWNIK (GPS telefonu) ===\nul. Szeroka, Toruń",
            LiveConversationProtocol.miejsce("ul. Szeroka, Toruń", "Toruń")
        )
        assertTrue(LiveConversationProtocol.miejsce(" ", "Toruń")!!.endsWith("Toruń"))
        assertNull(LiveConversationProtocol.miejsce(null, null))
        assertNull(LiveConversationProtocol.miejsce("", " "))
    }
}
