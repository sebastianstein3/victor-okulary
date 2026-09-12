package pl.victor.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/**
 * Testy przycinania nagrania do fragmentu z mową.
 *
 * Przypadek, dla którego to powstało, jest pierwszy: dwie sekundy pytania w
 * dziewięciosekundowym nagraniu. Reszta pilnuje, żeby ostrożność nie zamieniła
 * się w ucinanie pytań - bo ucięte pytanie kosztuje całą turę, a zostawiona
 * cisza tylko trochę celności.
 */
class VoiceTrimTest {

    private val rate = 16_000

    /** Cicho jak w pokoju - ale nie idealna cisza, bo taka nie istnieje. */
    private fun ambient(ms: Int, level: Int = 120, seed: Int = 1): ByteArray {
        val rnd = Random(seed)
        return samples(ms) { rnd.nextInt(-level, level + 1) }
    }

    /** Mowa: głośny, zmienny sygnał. */
    private fun speech(ms: Int, level: Int = 9_000): ByteArray =
        samples(ms) { i -> (sin(i / 7.0) * level).toInt() }

    private fun samples(ms: Int, value: (Int) -> Int): ByteArray {
        val count = rate * ms / 1000
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = value(i).coerceIn(-32_768, 32_767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun ms(bytes: Int) = bytes * 1000 / (rate * 2)

    // === Przypadek z dziennika ===

    @Test
    fun `dwie sekundy pytania w dziewieciu sekundach nagrania`() {
        val pcm = ambient(500) + speech(2_000) + ambient(6_600)
        val trimmed = VoiceTrim.trim(pcm, rate)
        assertTrue("nic nie przycięto", trimmed.size < pcm.size)
        // Mowa plus marginesy: jakieś 2,8 s. Z zapasem na zaokrąglenia okien.
        assertTrue("za długo: ${ms(trimmed.size)} ms", ms(trimmed.size) < 3_600)
        assertTrue("za krótko: ${ms(trimmed.size)} ms", ms(trimmed.size) > 2_000)
    }

    @Test
    fun `zostaje zapas przed pierwszym slowem`() {
        // Pytanie zaczyna się od razu - margines ma nie dać uciąć pierwszej głoski.
        val pcm = speech(1_500) + ambient(7_000)
        val trimmed = VoiceTrim.trim(pcm, rate)
        assertTrue(ms(trimmed.size) >= 1_500)
    }

    // === Ostrożność: kiedy NIE tniemy ===

    @Test
    fun `samo otoczenie zostaje bez zmian`() {
        // Nie ma czego szukać - oddajemy wszystko, niech model sam powie, że nic
        // nie słyszy.
        val pcm = ambient(9_000)
        assertArrayEquals(pcm, VoiceTrim.trim(pcm, rate))
    }

    @Test
    fun `nagranie w calosci glosne zostaje bez zmian`() {
        val pcm = speech(5_000)
        assertArrayEquals(pcm, VoiceTrim.trim(pcm, rate))
    }

    @Test
    fun `krotkie nagranie zostaje bez zmian`() {
        val pcm = speech(200)
        assertArrayEquals(pcm, VoiceTrim.trim(pcm, rate))
    }

    @Test
    fun `nie tniemy dla kilku procent`() {
        // 200 ms ciszy na końcu to nie jest powód, żeby ryzykować cięcie.
        val pcm = speech(4_000) + ambient(200)
        assertArrayEquals(pcm, VoiceTrim.trim(pcm, rate))
    }

    @Test
    fun `pojedynczy trzask nie zostawia ulamka sekundy`() {
        // Gdyby próg trafił w jedno stuknięcie, wycinek byłby mikroskopijny -
        // wtedy wolimy oddać całość niż udawać, że mamy pytanie.
        val pcm = ambient(4_000) + speech(30) + ambient(4_000)
        val trimmed = VoiceTrim.trim(pcm, rate)
        assertTrue(
            "oddano ułamek sekundy: ${ms(trimmed.size)} ms",
            trimmed.size == pcm.size || ms(trimmed.size) >= 700
        )
    }

    @Test
    fun `pusty bufor i bledna czestotliwosc nie wywracaja niczego`() {
        assertEquals(0, VoiceTrim.trim(ByteArray(0), rate).size)
        val pcm = ambient(500) + speech(2_000) + ambient(6_600)
        assertArrayEquals(pcm, VoiceTrim.trim(pcm, 0))
    }
}
