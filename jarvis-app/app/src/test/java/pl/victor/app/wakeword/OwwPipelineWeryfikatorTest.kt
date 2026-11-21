package pl.victor.app.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.wakeword.DecyzjaWybudzenia.Decyzja

/**
 * Potok z weryfikatorem głosu: niższy próg dla potwierdzonego głosu, weto,
 * zgłaszanie weta raz na przerwę, okna do korekty. Modele udawane.
 */
class OwwPipelineWeryfikatorTest {

    private var numer = 0

    private class Zapis {
        val wykrycia = mutableListOf<Float>()
        val decyzje = mutableListOf<Triple<Decyzja, Float, Float?>>()
        var wywolaniaWer = 0
    }

    private fun potok(
        wynik: () -> Float,
        wer: (() -> Float?)?,
        z: Zapis,
        tylkoMoj: Boolean = false
    ) = OwwPipeline(
        melspec = { Array(8) { FloatArray(32) } },
        embed = { numer++; FloatArray(96) { if (it == 0) numer.toFloat() else 0f } },
        classify = { wynik() },
        wymaganeRamki = 2,
        verify = wer?.let { f -> { _: Array<FloatArray> -> z.wywolaniaWer++; f() } },
        tylkoMojGlos = { tylkoMoj },
        onDecision = { d, w, v -> z.decyzje += Triple(d, w, v) },
        onDetected = { z.wykrycia += it }
    )

    /** Karmi porcjami co 80 ms; zwraca czas po ostatniej. */
    private fun OwwPipeline.porcje(n: Int, odMs: Long = 0L): Long {
        var t = odMs
        repeat(n) { feed(ShortArray(OwwPipeline.CHUNK), t); t += 80 }
        return t
    }

    /** Rozgrzewka: 15 porcji bez wyniku + 5 wyników zerowanych. */
    private val rozgrzewka = OwwPipeline.FEATURE_WINDOW - 1 + OwwPipeline.WARMUP_SCORES

    @Test
    fun `bez weryfikatora - jak dawniej, wynik 0,4 nie budzi`() {
        val z = Zapis()
        val p = potok({ 0.4f }, null, z)
        p.porcje(rozgrzewka + 10)
        assertTrue(z.wykrycia.isEmpty())
        assertNull(p.lastVerifierScore)
    }

    @Test
    fun `weryfikator potwierdza - budzi przy wyniku 0,4`() {
        val z = Zapis()
        val p = potok({ 0.4f }, { 0.8f }, z)
        p.porcje(rozgrzewka + 2)
        assertEquals(1, z.wykrycia.size)
        assertEquals(Decyzja.WLASCICIEL, z.decyzje.single().first)
        assertEquals(0.8f, z.decyzje.single().third!!, 0f)
        assertEquals(0.8f, p.lastVerifierScore!!, 0f)
    }

    @Test
    fun `weryfikator liczony tylko od niskiego progu`() {
        val z = Zapis()
        val p = potok({ 0.2f }, { 0.99f }, z)
        p.porcje(rozgrzewka + 10)
        assertEquals(0, z.wywolaniaWer)
        assertTrue(z.wykrycia.isEmpty())
    }

    @Test
    fun `weto - wysoki wynik, weryfikator mocno odrzuca - bez wykrycia, zgloszone raz na przerwe`() {
        val z = Zapis()
        val p = potok({ 0.9f }, { 0.02f }, z)
        var t = p.porcje(rozgrzewka + 2)
        assertTrue(z.wykrycia.isEmpty())
        assertEquals(1, z.decyzje.size)
        assertEquals(Decyzja.ODRZUCONE, z.decyzje[0].first)
        assertEquals(1, p.vetoes)
        // Ten sam dźwięk trwa - w czasie przerwy nie ma kolejnego zgłoszenia.
        t = p.porcje(10, t)
        assertEquals(1, z.decyzje.size)
        // Po przerwie - kolejne.
        p.porcje((OwwPipeline.REFRACTORY_MS / 80).toInt(), t)
        assertEquals(2, p.vetoes)
    }

    @Test
    fun `wysoki wynik i niepewny weryfikator - budzi jak dawniej`() {
        val z = Zapis()
        val p = potok({ 0.9f }, { 0.3f }, z)
        p.porcje(rozgrzewka + 2)
        assertEquals(1, z.wykrycia.size)
        assertEquals(Decyzja.OGOLNY, z.decyzje.single().first)
    }

    @Test
    fun `tylko moj glos - niepewny weryfikator wetuje`() {
        val z = Zapis()
        val p = potok({ 0.9f }, { 0.3f }, z, tylkoMoj = true)
        p.porcje(rozgrzewka + 2)
        assertTrue(z.wykrycia.isEmpty())
        assertEquals(Decyzja.ODRZUCONE_TYLKO_MOJ, z.decyzje.single().first)
    }

    @Test
    fun `weryfikator zwraca null (brak nagran) - jak dawniej`() {
        val z = Zapis()
        val p = potok({ 0.6f }, { null }, z, tylkoMoj = true)
        p.porcje(rozgrzewka + 2)
        assertEquals(1, z.wykrycia.size)
        assertEquals(Decyzja.OGOLNY, z.decyzje.single().first)
    }

    @Test
    fun `ostatnie okna do korekty - kopie, najnowsze na koncu`() {
        val z = Zapis()
        val p = potok({ 0f }, null, z)
        p.porcje(20)
        val okna = p.recentWindows(3)
        assertEquals(3, okna.size)
        assertEquals(16, okna[0].size)
        // Osadzenie n ma na pozycji 0 numer n; ostatnie okno kończy się na 20.
        assertEquals(20f, okna[2][15][0], 0f)
        assertEquals(19f, okna[1][15][0], 0f)
        assertEquals(5f, okna[2][0][0], 0f)
        okna[2][15][0] = -1f
        assertEquals(20f, p.recentWindows(1)[0][15][0], 0f)
        // Za mało osadzeń na pełne okno.
        val p2 = potok({ 0f }, null, Zapis())
        p2.porcje(5)
        assertTrue(p2.recentWindows(3).isEmpty())
    }
}
