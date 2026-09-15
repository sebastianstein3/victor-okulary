package pl.victor.app.proactive

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zamiana wydarzeń z kalendarza na fragment promptu dla modelu.
 *
 * Wydzielone z [pl.victor.app.AIOrchestrator] jako czyste funkcje, żeby dało się
 * to przetestować bez Androida - a przy okazji, żeby reguła „kiedy w ogóle
 * sięgamy po kalendarz" była w jednym miejscu i widoczna.
 *
 * ## Dlaczego nie zawsze
 * Kalendarz to dane wrażliwe i kosztują tokeny. Doklejamy go **tylko wtedy**,
 * gdy pytanie faktycznie dotyczy planów - inaczej każde „co to jest?" wysyłałoby
 * do modelu listę spotkań użytkownika.
 */
object CalendarContext {

    /**
     * Czy pytanie dotyczy planów, kalendarza albo spotkań.
     *
     * Dopasowanie jest celowo zachowawcze: fałszywe trafienie wysyła prywatne
     * dane do modelu, a fałszywe pominięcie kosztuje tylko gorszą odpowiedź.
     */
    fun isAboutSchedule(question: String): Boolean {
        val text = question.lowercase(Locale.ROOT)
        if (KEYWORDS.any { it in text }) return true
        // DRUGA DROGA: czasownik posiadania planu RAZEM z określeniem czasu.
        //
        // Sama lista słów nigdy nie będzie pełna - zmierzone na dziesięciu
        // prawdziwych pytaniach, z których przechodziło JEDNO. "Czy mam coś
        // jutro", "czy jestem dziś wolny wieczorem", "o której mam jutro wyjść"
        // - żadne z nich nie zawiera słowa "kalendarz", a wszystkie są o
        // kalendarzu. Model bez tych danych odpowiadał z wyobraźni.
        //
        // Warunek jest PODWÓJNY z rozmysłu: samo "mam" łapałoby połowę rozmów, a
        // samo "jutro" - pytania o pogodę. Dopiero razem znaczą plan.
        return SCHEDULE_VERBS.any { containsWord(text, it) } &&
            TIME_WORDS.any { containsWord(text, it) }
    }

    /**
     * Czy słowo występuje jako OSOBNE słowo, nie jako fragment innego.
     *
     * Bez tego "mam" łapałoby "mamy", "mama" i "mamut", czyli dokładnie te
     * rozmowy, w których kalendarz nie ma nic do rzeczy - a jego doklejenie
     * wysyła prywatne dane do modelu.
     */
    private fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return false
            val before = at == 0 || !text[at - 1].isLetterOrDigit()
            val afterAt = at + word.length
            val after = afterAt == text.length || !text[afterAt].isLetterOrDigit()
            if (before && after) return true
            from = at + 1
        }
    }

    /** Czasowniki, którymi mówi się o zajętości - patrz [isAboutSchedule]. */
    private val SCHEDULE_VERBS = listOf(
        "mam", "masz", "jestem", "jesteś", "jestes",
        "robię", "robie", "robisz", "czeka", "wypada", "planuję", "planuje"
    )

    /** Określenia czasu, przy których pytanie zwykle dotyczy planu dnia. */
    private val TIME_WORDS = listOf(
        "dziś", "dzis", "dzisiaj", "jutro", "pojutrze", "wieczorem", "rano",
        "popołudniu", "weekend", "tydzień", "tygodniu", "kiedy",
        "poniedziałek", "poniedzialek", "wtorek", "środę", "srode", "środa",
        "czwartek", "piątek", "piatek", "sobotę", "sobote", "sobota",
        "niedzielę", "niedziele", "niedziela"
    )

    /**
     * Składa opis wydarzeń dla modelu.
     *
     * @return fragment promptu albo `null`, gdy nie ma czego dokleić
     */
    fun buildPromptContext(
        events: List<CalendarEvent>,
        now: Long = System.currentTimeMillis()
    ): String? {
        if (events.isEmpty()) return null

        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dayFormatter = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

        return buildString {
            append("=== KALENDARZ UŻYTKOWNIKA ===\n")
            append("Teraz jest ").append(formatter.format(Date(now)))
            append(", ").append(dayFormatter.format(Date(now))).append(".\n")
            append("Nadchodzące wydarzenia:\n")
            events.forEach { event ->
                append("- ").append(formatter.format(Date(event.beginMs)))
                append(" ").append(event.title.ifBlank { "(bez tytułu)" })
                event.location?.takeIf { it.isNotBlank() }?.let {
                    append(", miejsce: ").append(it)
                }
                append(" (").append(describeDelay(event.beginMs - now)).append(")")
                append("\n")
            }
            append(
                "Odpowiadaj na podstawie tej listy. Jeśli pytanie dotyczy czegoś, " +
                    "czego na niej nie ma, powiedz wprost, że tego nie widzisz.\n"
            )
        }
    }

    /** „za 25 minut", „za 3 godziny", „trwa" - zamiast surowych znaczników czasu. */
    internal fun describeDelay(deltaMs: Long): String {
        if (deltaMs <= 0) return "już trwa albo się zaczęło"
        val minutes = deltaMs / 60_000L
        return when {
            minutes < 1 -> "za chwilę"
            minutes < 60 -> "za $minutes ${minuteForm(minutes)}"
            else -> {
                val hours = minutes / 60
                val rest = minutes % 60
                val head = "za $hours ${hourForm(hours)}"
                if (rest == 0L) head else "$head $rest ${minuteForm(rest)}"
            }
        }
    }

    /** Polska odmiana - „1 minutę", „2 minuty", „5 minut". */
    private fun minuteForm(n: Long): String = when {
        n == 1L -> "minutę"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "minuty"
        else -> "minut"
    }

    private fun hourForm(n: Long): String = when {
        n == 1L -> "godzinę"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "godziny"
        else -> "godzin"
    }

    private val KEYWORDS = listOf(
        "kalendarz", "kalendarzu", "plan", "plany", "planach", "planie",
        "spotkanie", "spotkania", "spotkań", "spotkaniu",
        "harmonogram", "grafik", "terminarz",
        "co mam dzisiaj", "co mam dziś", "co mam jutro",
        "wydarzenie", "wydarzenia",
        "umówion", "zaplanowan",
        "schedule", "meeting", "calendar", "agenda"
    )
}
