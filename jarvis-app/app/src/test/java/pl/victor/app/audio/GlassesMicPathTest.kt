package pl.victor.app.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Mikrofon okularów idzie strumieniem BLE, nie profilem rozmowy.
 *
 * ## Skąd ta decyzja
 * Aplikacja producenta (Prism, `GlassesAzureSpeechRecognizer`) nie zestawia SCO
 * ani razu i nie tyka mikrofonu telefonu: bierze dźwięk z okularów po BLE i
 * pcha go do rozpoznawania.
 *
 * My braliśmy SCO - a SCO każe okularom oddać mikrofon do HFP, przez co
 * PRZESTAJĄ nadawać ten strumień (w dzienniku: pakiety spadały z 456 do 48).
 *
 * ## Czego ten test pilnuje, a czego już NIE
 * Stał tu czwarty warunek: "dekoder stoi na 16 kHz, tak jak u producenta".
 * Był błędny i pilnował mojego błędu. Ich `setSampleRate(16000)` opisuje ICH
 * dekoder; systemowy `c2.android.opus.decoder` tej liczby z `OpusHead` do
 * ustawienia wyjścia nie używa i oddaje 48 kHz - co widać w dzienniku:
 * zadeklarowany czas nagrania wychodził stale ~3,07 raza dłuższy od
 * rzeczywistego (27,58 s wobec 9,02 s), a 460 pakietów po 20 ms daje 9,20 s.
 *
 * Teraz test pilnuje tego, co z tego pomiaru wynika: dekoder oddaje 48 kHz, a
 * przepróbkowanie do 16 kHz przed rozpoznawaniem musi NAPRAWDĘ się wykonywać.
 *
 * Reszty zależności nie da się sprawdzić bez okularów, więc test pilnuje
 * DECYZJI w kodzie - liczby i warunku, na których wszystko stoi.
 */
class GlassesMicPathTest {

    private fun zrodlo(sciezka: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "jarvis-app/app/$sciezka"), File(dir, sciezka))
                .firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `dekoder oddaje 48 kHz - tyle wyszlo z pomiaru`() {
        val plik = zrodlo("src/main/java/pl/victor/app/audio/OpusDecoder.kt")
        assumeTrue("Nie znalazłem OpusDecoder.kt", plik != null)
        val wartosc = Regex("""const val SAMPLE_RATE = ([0-9_]+)""")
            .find(plik!!.readText())?.groupValues?.get(1)?.replace("_", "")
        assertEquals(
            "dziennik z pięciu tur pokazał zadeklarowany czas ~3,07 raza " +
                "dłuższy od rzeczywistego, czyli dokładnie 48000/16000 - " +
                "wpisanie tu 16 000 zabija przepróbkowanie i podaje " +
                "rozpoznawaniu dźwięk trzy razy za wolny",
            "48000",
            wartosc
        )
    }

    @Test
    fun `przy zywym strumieniu BLE nie zestawiamy profilu rozmowy`() {
        val plik = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", plik != null)
        val tekst = plik!!.readText()

        val warunek = Regex(
            """var held = if \(micStreamLive\) \{\s*(?://[^\n]*\n\s*)*false""",
            RegexOption.DOT_MATCHES_ALL
        )
        assertTrue(
            "gdy strumień BLE żyje, SCO ma NIE być brane - inaczej okulary " +
                "oddadzą mikrofon do HFP i przestaną nadawać ten strumień",
            warunek.containsMatchIn(tekst)
        )
    }

    @Test
    fun `nagranie z okularow jest zrodlem pierwszym, nie zapasem`() {
        val plik = zrodlo("src/main/java/pl/victor/app/AIOrchestrator.kt")
        assumeTrue("Nie znalazłem AIOrchestrator.kt", plik != null)
        assertTrue(
            "tekst z telefonu nie może już blokować przepisania nagrania z " +
                "okularów, gdy człowiek wybrał ich mikrofon",
            plik!!.readText().contains("!wantsGlassesMic && phoneFallback != null -> null")
        )
    }

    @Test
    fun `przeprobkowanie do mowy musi sie naprawde wykonywac`() {
        // PcmResampler oddaje wejście BEZ ZMIAN, gdy częstotliwości są równe.
        // Dokładnie to zrobiłem, zrównując je "bo producent bierze 16 kHz":
        // krok 48 -> 16 przestał się wykonywać PO CICHU, a rozpoznawanie
        // dostawało 48 kHz opisane jako 16 kHz. Ten warunek nie pozwala
        // powtórzyć tego tą samą drogą.
        assertTrue(
            "dekoder musi stać WYŻEJ niż wejście rozpoznawania - przy " +
                "równych wartościach przepróbkowanie robi się pustym krokiem",
            OpusDecoder.SAMPLE_RATE > PcmResampler.SPEECH_SAMPLE_RATE
        )
        val krotnosc = OpusDecoder.SAMPLE_RATE.toDouble() / PcmResampler.SPEECH_SAMPLE_RATE
        assertEquals(
            "48 kHz na 16 kHz to całkowita krotność 3 - decymacja co trzecią " +
                "próbkę, bez interpolacji",
            3.0,
            krotnosc,
            0.0001
        )
    }
}
