package pl.victor.app.messages

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Teksty dla modelu i na głos - osobno, żeby dało się je sprawdzić testem. */
object MessagesPrompt {

    /** Co powiedzieć w okularach, gdy przychodzi wiadomość. */
    fun ogłoszenie(m: IncomingMessage, czytajTreść: Boolean): String {
        val skąd = if (m.aplikacja == "SMS") "SMS" else m.aplikacja
        if (!czytajTreść) return "$skąd od ${m.nadawca}."
        val treść = m.tekst.let { if (it.length > MAKS_ZNAKÓW) it.take(MAKS_ZNAKÓW).substringBeforeLast(' ') + "…" else it }
        return "$skąd od ${m.nadawca}: $treść"
    }

    /** Czy pytanie dotyczy wiadomości - wtedy model dostaje ich listę. */
    fun dotyczyWiadomości(pytanie: String): Boolean {
        val q = pytanie.lowercase()
        return SŁOWA.any { q.contains(it) }
    }

    /**
     * Sekcja promptu. Mówi modelowi, ŻE może odpowiadać i JAK - inaczej
     * prośba "odpowiedz Ani, że się spóźnię" kończy się radą, żeby
     * odpisać samemu.
     */
    fun dlaModelu(ostatnie: List<IncomingMessage>, terazMs: Long = System.currentTimeMillis()): String {
        val godzina = SimpleDateFormat("HH:mm", Locale("pl", "PL"))
        return buildString {
            append("=== OSTATNIE WIADOMOŚCI (z powiadomień telefonu) ===\n")
            if (ostatnie.isEmpty()) {
                append("Brak nowych wiadomości od uruchomienia czytania.\n")
            } else {
                ostatnie.asReversed().forEach { m ->
                    append("- ").append(godzina.format(Date(m.czasMs))).append(", ")
                    append(m.aplikacja).append(", od: ").append(m.nadawca).append(": \"")
                    append(m.tekst.take(300)).append('"')
                    if (!m.możnaOdpowiedzieć) append(" (tej nie da się odpowiedzieć z powiadomienia)")
                    append('\n')
                }
            }
            append("Możesz ODPOWIEDZIEĆ na wiadomość z tej listy znacznikiem reply_message - ")
            append("odpowiedź pójdzie w tej samej aplikacji, z której przyszła, po potwierdzeniu ")
            append("przez użytkownika. Treść pisz tak, jak napisałby ją użytkownik, w pierwszej ")
            append("osobie, bez cudzysłowów i bez \"użytkownik prosi\". Jeśli wiadomości nie ma ")
            append("na liście, powiedz to - nie wymyślaj.\n")
        }
    }

    private const val MAKS_ZNAKÓW = 280

    private val SŁOWA = listOf(
        "wiadomoś", "wiadomos", "odpowiedz", "odpisz", "odpowiedź", "kto pisał", "kto pisal",
        "kto do mnie", "napisał", "napisala", "napisała", "sms", "whatsapp", "messenger", "esemes",
        "co pisze", "co pisał", "co pisala", "co pisała", "przeczytaj jeszcze raz"
    )
}
