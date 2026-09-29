package pl.victor.app.vision

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "AI dostaje zdjęcie sprzed kilku dni zamiast aktualnego."
 *
 * Pobrany z okularów "najnowszy plik" bywa starym zdjęciem. Porównanie z
 * miniaturą zrobioną sekundę wcześniej ma to wyłapać.
 */
class PhotoMatchTest {

    // JEDEN generator na scenę, nie nowy na każdy piksel.
    //
    // Pierwsza wersja tworzyła Random(seed * 1000 + i) dla każdego piksela - a
    // pierwsze wartości generatorów o sąsiednich ziarnach są ze sobą
    // skorelowane. "Sto różnych scen" było w rzeczywistości podobnych i test
    // pokazał pięć fałszywych dopasowań, choć porównanie było w porządku.
    private fun scena(seed: Int): IntArray {
        val r = Random(seed)
        return IntArray(72) { r.nextInt(256) }
    }

    @Test
    fun `to samo zdjecie to roznica zero`() {
        val s = scena(1)
        assertEquals(0, PhotoMatch.różnica(PhotoMatch.odcisk(s)!!, PhotoMatch.odcisk(s)!!))
    }

    @Test
    fun `ta sama scena jasniej i z szumem kompresji nadal pasuje`() {
        val s = scena(2)
        val szum = Random(77)
        // Pełne zdjęcie ma inną ekspozycję i inne artefakty JPEG niż miniatura.
        val jaśniej = IntArray(72) { (s[it] * 1.15 + 12 + szum.nextInt(-6, 7)).toInt().coerceIn(0, 255) }
        assertTrue(PhotoMatch.toSamo(PhotoMatch.odcisk(s)!!, PhotoMatch.odcisk(jaśniej)!!))
    }

    @Test
    fun `rozne sceny nie pasuja - na stu parach ani jednej pomylki`() {
        // Pomyłka w tę stronę to dokładnie zgłoszony błąd: model dostaje obraz
        // z innego dnia. Sto par losowych scen - żadna nie może przejść.
        var pomyłki = 0
        for (i in 0 until 100) {
            val a = PhotoMatch.odcisk(scena(100 + i))!!
            val b = PhotoMatch.odcisk(scena(500 + i))!!
            if (PhotoMatch.toSamo(a, b)) pomyłki++
        }
        assertEquals(0, pomyłki)
    }

    @Test
    fun `odcisk nie zalezy od ogolnej jasnosci`() {
        val s = scena(3)
        val ciemniej = IntArray(72) { s[it] / 2 }
        assertTrue(PhotoMatch.różnica(PhotoMatch.odcisk(s)!!, PhotoMatch.odcisk(ciemniej)!!) <= 4)
    }

    @Test
    fun `zly rozmiar siatki nie udaje odcisku`() {
        assertNull(PhotoMatch.odcisk(IntArray(10)))
        assertFalse(PhotoMatch.MAX_RÓŻNICA >= 32)
    }
}
