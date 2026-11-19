package pl.victor.app.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Liczby potoku openWakeWord sprawdzone na udawanych modelach.
 *
 * Prawdziwe sieci nie są tu potrzebne: pomyłka w porcji, skalowaniu albo
 * oknie nie wywraca niczego, tylko sprawia, że klasyfikator nigdy nie
 * przekracza progu - "fraza nie działa", bez śladu przyczyny. Te testy
 * pilnują liczb, które muszą się zgadzać z openwakeword/utils.py.
 */
class OwwPipelineTest {

    /** Co dostały udawane modele - do sprawdzania kształtów i wartości. */
    private val melWejscia = mutableListOf<FloatArray>()
    private val embedWejscia = mutableListOf<Array<FloatArray>>()
    private val klasWejscia = mutableListOf<Array<FloatArray>>()

    /** Udawany model mel: 8 ramek po 32, każda wartość = podana. */
    private fun melStala(wartosc: Float): (FloatArray) -> Array<FloatArray> = { probki ->
        melWejscia += probki
        Array(8) { FloatArray(32) { wartosc } }
    }

    /** Udawane osadzenie: 96 wartości, pierwsza to numer wywołania. */
    private var numerOsadzenia = 0
    private val embedLiczacy: (Array<FloatArray>) -> FloatArray = { okno ->
        embedWejscia += okno
        numerOsadzenia++
        FloatArray(96) { if (it == 0) numerOsadzenia.toFloat() else 0f }
    }

    private fun potok(
        wyniki: () -> Float = { 0f },
        prog: Float = OwwPipeline.DEFAULT_THRESHOLD,
        ramki: Int = 1,
        wykrycia: MutableList<Float> = mutableListOf(),
        mel: (FloatArray) -> Array<FloatArray> = melStala(0f)
    ) = OwwPipeline(
        melspec = mel,
        embed = embedLiczacy,
        classify = { cechy -> klasWejscia += cechy; wyniki() },
        threshold = prog,
        wymaganeRamki = ramki,
        onDetected = { wykrycia += it }
    )

    private fun porcja(wartosc: Short = 0) = ShortArray(OwwPipeline.CHUNK) { wartosc }

    /** Karmi potok `n` pełnymi porcjami w odstępach 80 ms, zwraca czas po ostatniej. */
    private fun OwwPipeline.porcje(n: Int, odMs: Long = 0L): Long {
        var t = odMs
        repeat(n) { feed(porcja(), t); t += 80 }
        return t
    }

    @Test
    fun `niepelna porcja czeka na nastepne wywolanie`() {
        val p = potok()
        assertNull(p.feed(ShortArray(1000), 0))
        assertEquals(0, melWejscia.size)
        assertEquals(1000, p.pendingSamples)

        // 1000 + 500 = jedna pełna porcja i 220 reszty.
        p.feed(ShortArray(500), 0)
        assertEquals(1, melWejscia.size)
        assertEquals(220, p.pendingSamples)

        // Dwie porcje w jednym wywołaniu - dwa przebiegi mel.
        p.feed(ShortArray(2 * 1280 - 220), 0)
        assertEquals(3, melWejscia.size)
        assertEquals(0, p.pendingSamples)
    }

    @Test
    fun `reszta probek wchodzi do porcji w dobrej kolejnosci`() {
        val p = potok()
        p.feed(ShortArray(1000) { 1 }, 0)
        p.feed(ShortArray(280) { 2 }, 0)
        val wejscie = melWejscia.single()
        assertEquals(1280, wejscie.size)
        assertEquals(1f, wejscie[999])
        assertEquals(2f, wejscie[1000])
        assertEquals(2f, wejscie[1279])
    }

    @Test
    fun `mel dostaje porcje plus trzy przesuniecia z poprzedniej`() {
        val p = potok()
        p.feed(porcja(1), 0)
        // Pierwsza porcja - starszych próbek jeszcze nie ma.
        assertEquals(1280, melWejscia[0].size)

        p.feed(porcja(2), 80)
        val drugie = melWejscia[1]
        assertEquals(1280 + 160 * 3, drugie.size)
        // 480 ostatnich próbek poprzedniej porcji, potem cała nowa.
        assertTrue(drugie.copyOfRange(0, 480).all { it == 1f })
        assertTrue(drugie.copyOfRange(480, 1760).all { it == 2f })

        p.feed(porcja(3), 160)
        val trzecie = melWejscia[2]
        assertEquals(1760, trzecie.size)
        assertTrue(trzecie.copyOfRange(0, 480).all { it == 2f })
        assertTrue(trzecie.copyOfRange(480, 1760).all { it == 3f })
    }

    @Test
    fun `wartosci mel skalowane x przez 10 plus 2`() {
        // Surowe 30 -> 30/10 + 2 = 5. Jedynki z bufora startowego zostają jedynkami.
        val p = potok(mel = melStala(30f))
        p.feed(porcja(), 0)
        val okno = embedWejscia.single()
        assertEquals(76, okno.size)
        assertEquals(32, okno[0].size)
        // 68 wierszy startowych jedynek, potem 8 nowych ramek.
        assertTrue(okno.take(68).all { r -> r.all { it == 1f } })
        assertTrue(okno.drop(68).all { r -> r.all { it == 5f } })
    }

    @Test
    fun `jedno osadzenie z ostatnich 76 ramek na kazda porcje`() {
        val p = potok()
        p.porcje(3)
        assertEquals(3, embedWejscia.size)
        assertTrue(embedWejscia.all { it.size == 76 })
        assertEquals(76 + 3 * 8, p.melFrames)
        assertEquals(3, p.features)
    }

    @Test
    fun `bufor mel ma gorny limit`() {
        val p = potok()
        p.porcje(200)
        assertEquals(OwwPipeline.MEL_MAX_FRAMES, p.melFrames)
        assertEquals(OwwPipeline.FEATURE_MAX, p.features)
    }

    @Test
    fun `klasyfikator dostaje 16 ostatnich osadzen dopiero gdy sa`() {
        val p = potok()
        // 15 porcji - 15 osadzeń, za mało na wynik.
        var t = 0L
        repeat(15) { assertNull(p.feed(porcja(), t)); t += 80 }
        assertEquals(0, klasWejscia.size)

        assertNotNull(p.feed(porcja(), t))
        val cechy = klasWejscia.single()
        assertEquals(16, cechy.size)
        assertEquals(96, cechy[0].size)
        // Kolejność: najstarsze pierwsze, najnowsze ostatnie.
        assertArrayEquals((1..16).map { it.toFloat() }.toFloatArray(), cechy.map { it[0] }.toFloatArray(), 0f)

        p.feed(porcja(), t + 80)
        assertEquals(17f, klasWejscia[1].last()[0])
        assertEquals(2f, klasWejscia[1].first()[0])
    }

    @Test
    fun `pierwsze piec wynikow po starcie jest zerowanych`() {
        val wykrycia = mutableListOf<Float>()
        val p = potok(wyniki = { 0.9f }, wykrycia = wykrycia)
        val t = p.porcje(15)
        val wyniki = (0 until 6).map { p.feed(porcja(), t + it * 80L) }
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0.9f), wyniki)
        // Wykrycie dopiero na pierwszym prawdziwym wyniku.
        assertEquals(1, wykrycia.size)
    }

    @Test
    fun `reset przywraca rozgrzewke`() {
        val wykrycia = mutableListOf<Float>()
        val p = potok(wyniki = { 0.9f }, wykrycia = wykrycia)
        p.porcje(30)
        assertEquals(1, wykrycia.size)
        p.reset()
        assertEquals(76, p.melFrames)
        assertEquals(0, p.features)
        // Po resecie znowu 16 osadzeń + 5 wyzerowanych wyników.
        p.porcje(20, odMs = 100_000)
        assertEquals(0, p.detections)
        p.porcje(1, odMs = 200_000)
        assertEquals(1, p.detections)
    }

    @Test
    fun `wynik ponizej progu nie wykrywa`() {
        val wykrycia = mutableListOf<Float>()
        val p = potok(wyniki = { 0.49f }, wykrycia = wykrycia)
        p.porcje(100)
        assertTrue(wykrycia.isEmpty())
        assertEquals(0.49f, p.lastScore!!, 0f)
    }

    @Test
    fun `prog wlacznie`() {
        val wykrycia = mutableListOf<Float>()
        val p = potok(wyniki = { 0.5f }, wykrycia = wykrycia)
        p.porcje(21)
        assertEquals(listOf(0.5f), wykrycia)
    }

    @Test
    fun `wymagane ramki z rzedu`() {
        // Po rozgrzewce: trafienie, pudło, trafienie, trafienie, trafienie.
        val seria = ArrayDeque(listOf(0.8f, 0.1f, 0.8f, 0.8f, 0.8f))
        val wykrycia = mutableListOf<Float>()
        var rozgrzane = false
        val p = potok(
            wyniki = { if (rozgrzane) seria.removeFirst() else 0f },
            ramki = 3,
            wykrycia = wykrycia
        )
        val t = p.porcje(20) // 15 bez wyniku + 5 wyzerowanych
        rozgrzane = true
        p.porcje(2, odMs = t)
        assertTrue("przerwana seria nie wystarcza", wykrycia.isEmpty())
        p.porcje(2, odMs = t + 160)
        assertTrue("dwa z rzędu to za mało", wykrycia.isEmpty())
        p.porcje(1, odMs = t + 320)
        assertEquals(1, wykrycia.size)
    }

    @Test
    fun `po wykryciu dwie sekundy przerwy a bufory licza sie dalej`() {
        val wykrycia = mutableListOf<Float>()
        val p = potok(wyniki = { 0.9f }, wykrycia = wykrycia)
        val t0 = p.porcje(20)
        p.feed(porcja(), t0)
        assertEquals(1, wykrycia.size)

        // Przez 2 s same trafienia - i żadnego nowego wykrycia.
        val melPrzed = melWejscia.size
        var t = t0 + 80
        while (t < t0 + OwwPipeline.REFRACTORY_MS) {
            p.feed(porcja(), t)
            t += 80
        }
        assertEquals(1, wykrycia.size)
        assertTrue("bufory liczą się w przerwie", melWejscia.size > melPrzed)
        assertEquals(0.9f, p.lastScore!!, 0f)

        // Pierwsza porcja po przerwie znowu wykrywa.
        p.feed(porcja(), t0 + OwwPipeline.REFRACTORY_MS)
        assertEquals(2, wykrycia.size)
    }

    @Test
    fun `seria w przerwie nie liczy sie do wymaganych ramek`() {
        val wykrycia = mutableListOf<Float>()
        val p = potok(wyniki = { 0.9f }, ramki = 2, wykrycia = wykrycia)
        val t0 = p.porcje(22) // wykrycie na 22. porcji (2 trafienia z rzędu)
        assertEquals(1, wykrycia.size)
        val koniec = t0 - 80 + OwwPipeline.REFRACTORY_MS
        // Pierwsza porcja po przerwie: dopiero 1 trafienie z wymaganych 2.
        p.feed(porcja(), koniec)
        assertEquals(1, wykrycia.size)
        p.feed(porcja(), koniec + 80)
        assertEquals(2, wykrycia.size)
    }
}
