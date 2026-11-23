package pl.victor.app.proactive

import pl.victor.app.google.EmailSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zamiana ostatnich maili na fragment promptu dla modelu.
 *
 * Ta sama zasada co [CalendarContext]: skrzynkę doklejamy do promptu
 * TYLKO gdy pytanie faktycznie o pocztę pyta - inaczej każde pytanie
 * wysyłałoby do modelu treść prywatnej skrzynki użytkownika.
 */
object GmailContext {

    /**
     * Czy pytanie dotyczy poczty/maili.
     *
     * Tak samo zachowawcze jak [CalendarContext.isAboutSchedule] - fałszywe
     * trafienie wysyła prywatne dane do modelu, fałszywe pominięcie kosztuje
     * tylko gorszą odpowiedź.
     */
    fun isAboutEmail(question: String): Boolean {
        val text = question.lowercase(Locale.ROOT)
        return KEYWORDS.any { it in text }
    }

    /**
     * Składa opis ostatnich maili dla modelu.
     *
     * @return fragment promptu albo `null`, gdy nie ma czego dokleić
     */
    fun buildPromptContext(
        messages: List<EmailSummary>,
        now: Long = System.currentTimeMillis()
    ): String? {
        if (messages.isEmpty()) return null

        val formatter = SimpleDateFormat("d MMMM, HH:mm", Locale.getDefault())

        return buildString {
            append("=== SKRZYNKA POCZTOWA UŻYTKOWNIKA ===\n")
            append("Teraz jest ").append(formatter.format(Date(now))).append(".\n")
            append("Ostatnie maile (od najnowszego):\n")
            messages.forEach { msg ->
                append("- ")
                if (msg.isUnread) append("[NIEPRZECZYTANY] ")
                append(msg.subject.ifBlank { "(brak tematu)" })
                append(" - od: ").append(msg.from)
                if (msg.receivedMs > 0) {
                    append(", ").append(formatter.format(Date(msg.receivedMs)))
                }
                if (msg.snippet.isNotBlank()) {
                    append("\n  „").append(msg.snippet).append("”")
                }
                append("\n")
            }
            append(
                "Odpowiadaj na podstawie tej listy. Jeśli pytanie dotyczy maila, " +
                    "którego na niej nie ma, powiedz wprost, że tego nie widzisz.\n"
            )
        }
    }

    private val KEYWORDS = listOf(
        "mail", "maila", "maile", "maili", "mailu", "mailem", "mailach",
        "email", "e-mail", "emaila", "emaile",
        "skrzynka", "skrzynce", "skrzynki", "skrzynkę",
        "poczta", "poczcie", "poczty", "pocztę",
        // Bez "wiadomość/wiadomości": "jakie są wiadomości ze świata?" i
        // "przeczytaj wiadomości z WhatsAppa" doklejały do pytania prywatną
        // skrzynkę Gmail (przegląd 22.11).
        "gmail", "inbox"
    )
}
