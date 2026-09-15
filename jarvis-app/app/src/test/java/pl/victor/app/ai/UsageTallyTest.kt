package pl.victor.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Dwa błędy licznika zużycia nie krzyczą, tylko kłamią: licznik, który nie
 * zeruje się o północy, po tygodniu pokazuje tydzień, a tempo liczone od
 * uruchomienia aplikacji zaniża bieżące zużycie tym bardziej, im dłużej
 * aplikacja chodzi. Oba widać tylko wtedy, gdy zegar jest parametrem.
 */
class UsageTallyTest {

    private val warsaw: ZoneId = ZoneId.of("Europe/Warsaw")

    /** Południe danego dnia w strefie użytkownika - bezpiecznie daleko od granicy doby. */
    private fun noon(day: String): Long =
        java.time.LocalDate.parse(day).atTime(12, 0).atZone(warsaw).toInstant().toEpochMilli()

    private fun at(day: String, hour: Int, minute: Int = 0): Long =
        java.time.LocalDate.parse(day).atTime(hour, minute).atZone(warsaw).toInstant().toEpochMilli()

    // --- licznik dobowy ---

    @Test
    fun `zapytania tego samego dnia sie sumuja`() {
        var state = UsageTally.Day()
        state = UsageTally.add(state, 1600, noon("2026-09-15"), warsaw)
        state = UsageTally.add(state, 400, noon("2026-09-15"), warsaw)
        assertEquals(2000L, state.tokens)
        assertEquals(2, state.requests)
    }

    @Test
    fun `nowa doba zeruje licznik`() {
        var state = UsageTally.add(UsageTally.Day(), 5000, noon("2026-09-15"), warsaw)
        state = UsageTally.add(state, 100, noon("2026-09-16"), warsaw)
        assertEquals(100L, state.tokens)
        assertEquals(1, state.requests)
    }

    @Test
    fun `polnoc jest granica doby, nie druga w nocy`() {
        // To jest ten przypadek, dla którego strefa w ogóle jest parametrem:
        // przy liczeniu w UTC granica przy polskiej strefie wypadałaby w środku
        // wieczornego używania.
        val before = at("2026-09-15", 23, 50)
        val after = at("2026-09-16", 0, 10)
        var state = UsageTally.add(UsageTally.Day(), 1000, before, warsaw)
        assertEquals(1000L, state.tokens)
        state = UsageTally.add(state, 1000, after, warsaw)
        assertEquals("po północy licznik ma ruszyć od nowa", 1000L, state.tokens)
    }

    @Test
    fun `wczytany stan z wczoraj pokazuje dzis zero`() {
        val yesterday = UsageTally.add(UsageTally.Day(), 9000, noon("2026-09-15"), warsaw)
        val today = UsageTally.asOf(yesterday, noon("2026-09-16"), warsaw)
        assertEquals(0L, today.tokens)
        assertEquals(0, today.requests)
    }

    @Test
    fun `stan z dzis przechodzi bez zmian`() {
        val state = UsageTally.add(UsageTally.Day(), 9000, at("2026-09-16", 8), warsaw)
        assertEquals(state, UsageTally.asOf(state, at("2026-09-16", 20), warsaw))
    }

    @Test
    fun `ujemna liczba tokenow nie cofa licznika`() {
        // Dostawca, który nie poda zużycia, oddaje zero; gdyby kiedyś oddał
        // liczbę ujemną, licznik miałby się nie cofnąć.
        var state = UsageTally.add(UsageTally.Day(), 1000, noon("2026-09-15"), warsaw)
        state = UsageTally.add(state, -500, noon("2026-09-15"), warsaw)
        assertEquals(1000L, state.tokens)
        assertEquals("zapytanie liczy się nawet bez tokenów", 2, state.requests)
    }

    // --- tempo ---

    private fun events(vararg pairs: Pair<Long, Int>) =
        pairs.map { UsageTally.Event(it.first, it.second) }

    @Test
    fun `pojedyncze zapytanie nie daje tempa`() {
        assertNull(UsageTally.perMinute(events(1_000L to 1600), nowMs = 2_000L))
    }

    @Test
    fun `puste okno nie daje tempa`() {
        assertNull(UsageTally.perMinute(emptyList(), nowMs = 10_000L))
    }

    @Test
    fun `tempo liczy sie z rzeczywistego czasu, nie z pelnej minuty`() {
        // Dwa zapytania po 1600 tokenów w ciągu 30 sekund to 6400 na minutę,
        // a nie 3200. Dzielenie przez pełną minutę zaniżałoby tempo dokładnie
        // wtedy, gdy jest najbardziej potrzebne - zaraz po włączeniu trybu.
        val now = 100_000L
        val rate = UsageTally.perMinute(
            events(now - 30_000L to 1600, now - 15_000L to 1600),
            nowMs = now
        )
        assertEquals(6400, rate)
    }

    @Test
    fun `stare zapytania wypadaja z okna`() {
        val now = 10 * 60_000L
        val rate = UsageTally.perMinute(
            events(
                1_000L to 100_000,          // sprzed dziesięciu minut - poza oknem
                now - 60_000L to 1000,
                now - 30_000L to 1000
            ),
            nowMs = now
        )
        // Gdyby stare zapytanie weszło do wyniku, tempo poszłoby w dziesiątki
        // tysięcy - a tryb w tej chwili pali 2000 na minutę.
        assertTrue("tempo=$rate", rate != null && rate < 5000)
    }

    @Test
    fun `okno obcina liste zdarzen`() {
        val now = 10 * 60_000L
        val kept = UsageTally.within(
            events(1_000L to 1, now - 1_000L to 1),
            nowMs = now,
            windowMs = UsageTally.WINDOW_MS
        )
        assertEquals(1, kept.size)
    }

    @Test
    fun `tempo trybu ciaglego jest rzedu dziesiatek tysiecy`() {
        // Zapytanie o obraz to zmierzone circa 1600 tokenów. Przy odstępie
        // półtorej sekundy daje to circa 64 tysiące na minutę - i to jest
        // dokładnie ta liczba, dla której ten licznik powstał.
        val now = 60_000L
        val list = (0 until 20).map { UsageTally.Event(now - 57_000L + it * 3_000L, 1600) }
        val rate = UsageTally.perMinute(list, nowMs = now)!!
        assertTrue("tempo=$rate", rate > 20_000)
    }
}
