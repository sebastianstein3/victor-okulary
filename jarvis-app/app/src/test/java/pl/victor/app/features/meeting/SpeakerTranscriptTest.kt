package pl.victor.app.features.meeting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class SpeakerTranscriptTest {

    // ---- Zapytanie ----

    @Test
    fun `zapytanie ma diaryzacje, czas slow i jezyk`() {
        val o = SpeakerTranscript.ciało("QUJD", "pl")
        assertEquals("models/gemini-3.5-transcribe", o["model"]!!.jsonPrimitive.content)
        val wejście = o["input"]!!.jsonArray[0].jsonObject
        assertEquals("audio", wejście["type"]!!.jsonPrimitive.content)
        assertEquals("audio/wav", wejście["mime_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", wejście["data"]!!.jsonPrimitive.content)
        val tc = o["generation_config"]!!.jsonObject["transcription_config"]!!.jsonObject
        assertEquals("pl-PL", tc["language_codes"]!!.jsonArray[0].jsonPrimitive.content)
        val mode = tc["mode"]!!.jsonObject
        assertEquals("verbatim", mode["type"]!!.jsonPrimitive.content)
        assertEquals("speaker", mode["diarization_mode"]!!.jsonPrimitive.content)
        assertEquals("word", mode["timestamp_granularities"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `zapytanie strumieniem jest identyczne z JSON-em i ma zapowiedziana dlugosc`() {
        val pcm = ByteArray(1001) { (it * 7).toByte() }
        val out = ByteArrayOutputStream()
        SpeakerTranscript.zapiszZapytanie(out, "pl", pcm.size.toLong(), ByteArrayInputStream(pcm))
        val wav = pl.victor.app.audio.WavWriter.wrap(pcm, 16_000)
        val oczekiwane = SpeakerTranscript.ciało(java.util.Base64.getEncoder().encodeToString(wav), "pl").toString()
        assertEquals(oczekiwane, out.toString("UTF-8"))
        assertEquals(out.size().toLong(), SpeakerTranscript.długośćZapytania("pl", pcm.size.toLong()))
        // Poprawny JSON z nagraniem do odkodowania.
        val dane = Json.parseToJsonElement(out.toString("UTF-8")).jsonObject["input"]!!
            .jsonArray[0].jsonObject["data"]!!.jsonPrimitive.content
        assertTrue(java.util.Base64.getDecoder().decode(dane).contentEquals(wav))
    }

    @Test
    fun `fragment 7 min miesci sie w limicie 20 MB po base64`() {
        val dł = SpeakerTranscript.długośćZapytania("pl", SpeakerTranscript.MAKS_FRAGMENT_BAJTÓW)
        assertTrue("$dł", dł < 20L * 1024 * 1024)
    }

    // ---- Odpowiedź ----

    private val odpowiedźZMówcami = """
        {"id":"interactions/x","status":"completed","steps":[{"id":"s1","type":"model_output","content":[
          {"type":"text","text":"Dzień dobry. Cześć, zaczynamy?","annotations":[
            {"type":"word_info","text":"Dzień","speaker":"spk_2","start_offset":"0.200s","end_offset":"0.450s"},
            {"type":"word_info","text":"dobry.","speaker":"spk_2","start_offset":"0.500s","end_offset":"0.950s"},
            {"type":"word_info","text":"Cześć,","speaker":"spk_1","start_offset":"1.100s","end_offset":"1.400s"},
            {"type":"word_info","text":"zaczynamy","speaker":"spk_1","start_offset":"1.500s"},
            {"type":"word_info","text":"?","speaker":"spk_1","start_offset":"2.000s"}
          ]}]}]}
    """.trimIndent()

    @Test
    fun `parsuje slowa z mowcami i czasem`() {
        val o = SpeakerTranscript.parsuj(odpowiedźZMówcami)!!
        assertEquals(5, o.słowa.size)
        assertEquals(2, o.mówców)
        assertEquals("spk_2", o.słowa[0].mówca)
        assertEquals(0.2, o.słowa[0].startS!!, 1e-9)
        assertEquals("Dzień dobry. Cześć, zaczynamy?", o.tekst)
    }

    @Test
    fun `odpowiedz bez adnotacji - tekst bez mowcow`() {
        val o = SpeakerTranscript.parsuj(
            """{"steps":[{"content":[{"type":"text","text":"Sam tekst."}]}]}"""
        )!!
        assertFalse(o.maMówców)
        assertEquals("Sam tekst.", o.tekst)
        assertTrue(o.słowa.isEmpty())
    }

    @Test
    fun `smieci i blad to null`() {
        assertNull(SpeakerTranscript.parsuj("<html>502</html>"))
    }

    @Test
    fun `czas w roznych formatach`() {
        assertEquals(1.5, SpeakerTranscript.sekundy(JsonPrimitive("1.5s"))!!, 1e-9)
        assertEquals(2.0, SpeakerTranscript.sekundy(JsonPrimitive(2))!!, 1e-9)
        val obj = Json.parseToJsonElement("""{"seconds":"3","nanos":500000000}""")
        assertEquals(3.5, SpeakerTranscript.sekundy(obj)!!, 1e-9)
        assertNull(SpeakerTranscript.sekundy(null))
        assertNull(SpeakerTranscript.sekundy(JsonObject(emptyMap())))
        assertNull(SpeakerTranscript.sekundy(JsonArray(emptyList())))
    }

    // ---- Składanie ----

    @Test
    fun `numeracja wedlug pierwszego odezwania sie`() {
        val s = listOf("spk_3", "spk_1", "spk_3", "spk_2").map { SpeakerTranscript.Słowo("x", it, null) }
        assertEquals(mapOf("spk_3" to 1, "spk_1" to 2, "spk_2" to 3), SpeakerTranscript.numeracja(s))
    }

    @Test
    fun `linie z etykietami osob, interpunkcja bez spacji, czas spotkania`() {
        // Jedna wypowiedź na spotkaniu od 65 s, długa 3 s, na początku fragmentu.
        val ułożenie = listOf(SpeakerTranscript.Ułożenie(odStartuMs = 65_000, wFragmencieMs = 0, długośćMs = 3_000))
        val o = SpeakerTranscript.parsuj(odpowiedźZMówcami)!!
        val linie = SpeakerTranscript.linie(o.słowa, ułożenie, fragment = 2)
        assertEquals(2, linie.size)
        assertEquals(MeetingLine(65_200, "Dzień dobry.", "Osoba 1", 2), linie[0])
        assertEquals(MeetingLine(66_100, "Cześć, zaczynamy?", "Osoba 2", 2), linie[1])
    }

    @Test
    fun `ta sama osoba w dwoch wypowiedziach to dwie linie`() {
        val ułożenie = listOf(
            SpeakerTranscript.Ułożenie(10_000, 0, 2_000),
            SpeakerTranscript.Ułożenie(50_000, 2_400, 2_000)
        )
        val słowa = listOf(
            SpeakerTranscript.Słowo("raz", "spk_1", 0.5),
            SpeakerTranscript.Słowo("dwa", "spk_1", 1.0),
            SpeakerTranscript.Słowo("trzy", "spk_1", 2.5),
            SpeakerTranscript.Słowo("cztery", null, null)
        )
        val linie = SpeakerTranscript.linie(słowa, ułożenie)
        assertEquals(listOf("Raz dwa", "Trzy cztery"), linie.map { it.tekst })
        assertEquals(listOf(10_500L, 50_100L), linie.map { it.odStartuMs })
        assertTrue(linie.all { it.mówca == "Osoba 1" })
    }

    @Test
    fun `czas w przerwie miedzy wypowiedziami nie wychodzi poza poprzednia`() {
        val ułożenie = listOf(
            SpeakerTranscript.Ułożenie(10_000, 0, 2_000),
            SpeakerTranscript.Ułożenie(50_000, 2_400, 2_000)
        )
        assertEquals(12_000L, SpeakerTranscript.naCzasSpotkania(2_200, ułożenie))
        assertEquals(50_000L, SpeakerTranscript.naCzasSpotkania(2_400, ułożenie))
        assertEquals(10_000L, SpeakerTranscript.naCzasSpotkania(0, ułożenie))
    }

    @Test
    fun `scalanie - udany fragment zastepuje linie na zywo ze swojego zakresu`() {
        val żywe = listOf(
            MeetingLine(1_000, "a"), MeetingLine(5_000, "b"), MeetingLine(9_000, "c"), MeetingLine(20_000, "d")
        )
        val wyniki = listOf(
            SpeakerTranscript.WynikFragmentu(1, 1_000, 6_000, listOf(MeetingLine(1_000, "A", "Osoba 1", 1), MeetingLine(5_200, "B", "Osoba 2", 1))),
            SpeakerTranscript.WynikFragmentu(2, 9_000, 25_000, null)
        )
        val s = SpeakerTranscript.scal(żywe, wyniki)
        assertEquals(listOf("A", "B", "c", "d"), s.map { it.tekst })
        // Bez udanych - nic się nie zmienia.
        assertEquals(żywe, SpeakerTranscript.scal(żywe, listOf(wyniki[1])))
    }

    @Test
    fun `powod bez mowcow - tylko darmowe ma pierwszenstwo`() {
        assertEquals("tylko darmowe funkcje", SpeakerTranscript.powódBezMówców(true, true, "k"))
        assertEquals("wyłączone w ustawieniach", SpeakerTranscript.powódBezMówców(false, false, "k"))
        assertEquals("brak klucza Gemini", SpeakerTranscript.powódBezMówców(false, true, " "))
        assertNull(SpeakerTranscript.powódBezMówców(false, true, "klucz"))
    }

    // ---- Fragmenty ----

    @Test
    fun `podzial na fragmenty po wypowiedziach, z cisza miedzy nimi`() {
        // Limit 100 bajtów, przerwa 10: 40 + 10 + 40 = 90 mieści się, kolejne 40 już nie.
        val f = FragmentyNagrania.podziel(
            listOf(0L to 40, 1_000L to 40, 2_000L to 40, 3_000L to 120),
            maksBajtów = 100, przerwaBajtów = 10
        )
        assertEquals(listOf(2, 1, 1), f.map { it.size })
        assertEquals(listOf(0L, 1_000L), f[0].map { it.odStartuMs })
        // Za długa wypowiedź dostaje własny fragment, nie jest gubiona.
        assertEquals(3_000L, f[2][0].odStartuMs)
    }

    @Test
    fun `ulozenie liczy cisze w czasie fragmentu`() {
        val b = SpeakerTranscript.BAJTÓW_NA_SEKUNDĘ
        val f = FragmentyNagrania.podziel(listOf(0L to b, 30_000L to b))
        assertEquals(1, f.size)
        assertEquals(0L, f[0][0].wFragmencieMs)
        assertEquals(1_000L + SpeakerTranscript.PRZERWA_MS, f[0][1].wFragmencieMs)
        assertEquals(1_000L, f[0][1].długośćMs)
    }

    @Test
    fun `zapis fragmentow do plikow`() {
        val katalog = Files.createTempDirectory("mowcy").toFile()
        try {
            val gotowe = mutableListOf<ZapisFragmentów.Fragment>()
            val z = ZapisFragmentów(
                plikDla = { nr -> java.io.File(katalog, "s-$nr.pcm") },
                gotowy = { gotowe += it },
                plan = FragmentyNagrania(maksBajtów = 100, przerwaBajtów = 10)
            )
            z.dopisz(0, ByteArray(40) { 1 })
            z.dopisz(1_000, ByteArray(40) { 2 })
            assertTrue(gotowe.isEmpty())
            z.dopisz(2_000, ByteArray(40) { 3 })
            assertEquals(1, gotowe.size)
            assertEquals(90L, gotowe[0].plik.length())
            val treść = gotowe[0].plik.readBytes()
            assertEquals(1.toByte(), treść[0])
            assertEquals(0.toByte(), treść[45])
            assertEquals(2.toByte(), treść[89])
            z.domknij()
            assertEquals(2, gotowe.size)
            assertEquals(2, gotowe[1].nr)
            assertEquals(40L, gotowe[1].plik.length())
            assertNotNull(gotowe[1].ułożenie.singleOrNull())
        } finally {
            katalog.deleteRecursively()
        }
    }

    // ---- Transkrypcja z mówcami w notatkach ----

    @Test
    fun `transkrypcja z mowcami i znacznikiem nowej numeracji`() {
        val t = MeetingNotes.transkrypcja(
            listOf(
                MeetingLine(5_000, "Zaczynamy.", "Osoba 1", 1),
                MeetingLine(500_000, "Kontynuujemy.", "Osoba 1", 2)
            )
        )
        val linie = t.lines()
        assertEquals("[00:05] Osoba 1: Zaczynamy.", linie[0])
        assertTrue(linie[1], linie[1].startsWith("---"))
        assertEquals("[08:20] Osoba 1: Kontynuujemy.", linie[2])
    }

    @Test
    fun `polecenie mowi modelowi o numeracji tylko przy mowcach`() {
        val z = MeetingNotes.polecenie(listOf(MeetingLine(0, "Hej.", "Osoba 1", 1)), 60_000)
        assertTrue(z.contains("Osoba 1: Hej."))
        assertTrue(z.contains("od nowa"))
        val bez = MeetingNotes.polecenie(listOf(MeetingLine(0, "Hej.")), 60_000)
        assertFalse(bez.contains("od nowa"))
        assertTrue(bez.contains("[00:00] Hej."))
    }
}
