package pl.victor.app.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kalendarz doklejany do promptu.
 *
 * Najważniejszy jest tu warunek wejścia: kalendarz to dane wrażliwe, więc
 * fałszywe trafienie wysyła listę spotkań do modelu przy pytaniu, które nie ma
 * z planami nic wspólnego. Testy pilnują obu stron tego progu.
 */
class CalendarContextTest {

    private val now = 1_700_000_000_000L  // stały punkt odniesienia

    private fun event(
        title: String,
        minutesFromNow: Long,
        location: String? = null
    ): CalendarEvent {
        val begin = now + minutesFromNow * 60_000L
        return CalendarEvent(
            id = minutesFromNow,
            title = title,
            beginMs = begin,
            endMs = begin + 3_600_000L,
            location = location,
            calendarName = "Praca",
            description = null,
            leaveByMs = begin - 15 * 60_000L
        )
    }

    // === Kiedy sięgamy po kalendarz ===

    @Test
    fun `pytania o plany sa rozpoznawane`() {
        val questions = listOf(
            "co mam dziś w planach",
            "jakie mam dzisiaj spotkania",
            "pokaż mój kalendarz",
            "co mam jutro",
            "jaki mam harmonogram",
            "czy mam coś zaplanowane",
            "what's on my schedule",
            "next meeting"
        )
        for (q in questions) {
            assertTrue("nie rozpoznano pytania o plany: \"$q\"", CalendarContext.isAboutSchedule(q))
        }
    }

    @Test
    fun `pytania niezwiazane z planami nie siegaja po kalendarz`() {
        // Fałszywe trafienie oznacza wysłanie prywatnych danych do modelu.
        val questions = listOf(
            "co widzisz przede mną",
            "ile to 20 euro w złotych",
            "jaka jest pogoda",
            "przetłumacz ten tekst",
            "kto to jest",
            "opisz tę scenę"
        )
        for (q in questions) {
            assertFalse(
                "niepotrzebnie sięgnięto po kalendarz przy: \"$q\"",
                CalendarContext.isAboutSchedule(q)
            )
        }
    }

    @Test
    fun `rozpoznawanie nie zalezy od wielkosci liter`() {
        assertTrue(CalendarContext.isAboutSchedule("Jakie mam SPOTKANIA?"))
    }

    // === Budowanie fragmentu promptu ===

    @Test
    fun `pusta lista nie daje fragmentu`() {
        assertNull(CalendarContext.buildPromptContext(emptyList(), now))
    }

    @Test
    fun `fragment zawiera tytuly i godziny wydarzen`() {
        val context = CalendarContext.buildPromptContext(
            listOf(event("Przegląd sprintu", 90), event("Lunch z Anią", 240)),
            now
        )
        assertNotNull(context)
        assertTrue(context!!.contains("Przegląd sprintu"))
        assertTrue(context.contains("Lunch z Anią"))
    }

    @Test
    fun `miejsce jest doklejane gdy jest podane`() {
        val context = CalendarContext.buildPromptContext(
            listOf(event("Wizyta", 60, location = "Poznańska 12")),
            now
        )!!
        assertTrue(context.contains("Poznańska 12"))
    }

    @Test
    fun `wydarzenie bez tytulu ma zastepczy opis`() {
        val context = CalendarContext.buildPromptContext(listOf(event("", 30)), now)!!
        assertTrue(context.contains("(bez tytułu)"))
    }

    @Test
    fun `fragment mowi modelowi zeby nie zmyslal`() {
        // Bez tego model dopowiada spotkania, których w kalendarzu nie ma.
        val context = CalendarContext.buildPromptContext(listOf(event("Cokolwiek", 60)), now)!!
        assertTrue(context.contains("nie widzisz"))
    }

    // === Opis odstępu czasu ===

    @Test
    fun `odstep ponizej minuty to za chwile`() {
        assertEquals("za chwilę", CalendarContext.describeDelay(30_000L))
    }

    @Test
    fun `wydarzenie w przeszlosci jest oznaczone jako trwajace`() {
        assertEquals("już trwa albo się zaczęło", CalendarContext.describeDelay(-60_000L))
    }

    @Test
    fun `polska odmiana minut jest poprawna`() {
        assertEquals("za 1 minutę", CalendarContext.describeDelay(60_000L))
        assertEquals("za 2 minuty", CalendarContext.describeDelay(2 * 60_000L))
        assertEquals("za 5 minut", CalendarContext.describeDelay(5 * 60_000L))
        assertEquals("za 22 minuty", CalendarContext.describeDelay(22 * 60_000L))
        // 12-14 to wyjątek od reguły końcówek: "12 minut", nie "12 minuty".
        assertEquals("za 12 minut", CalendarContext.describeDelay(12 * 60_000L))
        assertEquals("za 13 minut", CalendarContext.describeDelay(13 * 60_000L))
    }

    @Test
    fun `polska odmiana godzin jest poprawna`() {
        assertEquals("za 1 godzinę", CalendarContext.describeDelay(60 * 60_000L))
        assertEquals("za 2 godziny", CalendarContext.describeDelay(120 * 60_000L))
        assertEquals("za 5 godzin", CalendarContext.describeDelay(300 * 60_000L))
    }

    @Test
    fun `godziny i minuty sa laczone`() {
        assertEquals("za 2 godziny 30 minut", CalendarContext.describeDelay(150 * 60_000L))
        assertEquals("za 1 godzinę 1 minutę", CalendarContext.describeDelay(61 * 60_000L))
    }

    // === Zgłoszenie: "zmyślał, że mam coś w kalendarzu, czego nie mam" ===
    //
    // Przyczyna nie była w modelu, tylko tutaj: gdy pytanie nie zostanie uznane
    // za dotyczące planów, kalendarz nie jest doklejany i model odpowiada z
    // wyobraźni. Na dziesięciu prawdziwych pytaniach przechodziło JEDNO.

    @Test
    fun `pytania o plan dnia bez slowa kalendarz tez licza sie jako plan`() {
        listOf(
            "czy mam coś jutro",
            "czy jestem dziś wolny wieczorem",
            "czy mam coś wieczorem",
            "co robię w piątek",
            "kiedy mam wizytę u lekarza",
            "czy coś mnie dziś czeka",
            "o której mam jutro wyjść",
            "czy jutro jestem zajęty"
        ).forEach {
            assertTrue("pytanie: \"$it\"", CalendarContext.isAboutSchedule(it))
        }
    }

    @Test
    fun `sam czasownik bez czasu to jeszcze nie plan`() {
        // Warunek jest podwójny z rozmysłu: fałszywe trafienie wysyła prywatne
        // dane do modelu, więc "mam" samo w sobie nie może wystarczyć.
        assertFalse(CalendarContext.isAboutSchedule("mam ochotę na kawę"))
        assertFalse(CalendarContext.isAboutSchedule("czy masz rację"))
    }

    @Test
    fun `sam czas bez czasownika to tez nie plan`() {
        // Inaczej każde pytanie o pogodę ciągnęłoby kalendarz.
        assertFalse(CalendarContext.isAboutSchedule("jaka będzie jutro pogoda"))
        assertFalse(CalendarContext.isAboutSchedule("co się stało wczoraj w Warszawie"))
    }

    @Test
    fun `slowo w srodku innego nie liczy sie jako czasownik`() {
        // "mam" w "mamy", "mama", "mamut" - to są dokładnie te rozmowy, w
        // których kalendarz nie ma nic do rzeczy.
        assertFalse(CalendarContext.isAboutSchedule("czy mamy dziś mleko"))
        assertFalse(CalendarContext.isAboutSchedule("co mama robiła wczoraj"))
    }
}
