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
 * ani razu i nie tyka mikrofonu telefonu: bierze dźwięk z okularów po BLE,
 * rozkodowuje go jako 16 kHz mono i pcha do rozpoznawania w chmurze.
 *
 * My robiliśmy dwie rzeczy inaczej i obie się nawzajem podtrzymywały:
 *
 *  1. dekodowaliśmy ten sam strumień jako 48 kHz, więc nie wychodziła z niego
 *     mowa (pakiety rozkodowane co do sztuki, tekstu ani razu na circa 40 prób);
 *  2. skoro "strumień nie działa", braliśmy SCO - a SCO każe okularom oddać
 *     mikrofon do HFP, przez co PRZESTAJĄ nadawać ten strumień.
 *
 * Czyli droga, którą uznałem za ślepą, była psuta przez obejście, które
 * wprowadziłem, bo uznałem ją za ślepą.
 *
 * Tych zależności nie da się sprawdzić bez okularów, więc test pilnuje
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
    fun `dekoder stoi na 16 kHz, tak jak u producenta`() {
        val plik = zrodlo("src/main/java/pl/victor/app/audio/OpusDecoder.kt")
        assumeTrue("Nie znalazłem OpusDecoder.kt", plik != null)
        val wartosc = Regex("""const val SAMPLE_RATE = ([0-9_]+)""")
            .find(plik!!.readText())?.groupValues?.get(1)?.replace("_", "")
        assertEquals(
            "producent bierze ten strumień jako 16 kHz - przy innej wartości " +
                "z dekodera nie wychodzi mowa",
            "16000",
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
    fun `przeprobkowanie do mowy staje sie pustym krokiem`() {
        // Skoro dekoder oddaje 16 kHz, a rozpoznawanie chce 16 kHz, to
        // PcmResampler ma oddać wejście BEZ ZMIAN. Gdyby te dwie stałe kiedyś
        // się rozjechały, wracamy do stratnego kroku po cichu.
        assertEquals(
            "dekoder i wejście rozpoznawania mają stać na tej samej częstotliwości",
            PcmResampler.SPEECH_SAMPLE_RATE,
            OpusDecoder.SAMPLE_RATE
        )
    }
}
