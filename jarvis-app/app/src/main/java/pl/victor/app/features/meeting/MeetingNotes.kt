package pl.victor.app.features.meeting

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Jedna wypowiedź ze spotkania - przesunięcie od początku i tekst. */
data class MeetingLine(val odStartuMs: Long, val tekst: String)

/** Zapisane spotkanie. */
data class Meeting(
    val id: String,
    val startMs: Long,
    val koniecMs: Long,
    val linie: List<MeetingLine>,
    val podsumowanie: String? = null,
    val nieprzepisanych: Int = 0
)

/** Teksty dla modelu, na głos i do udostępnienia - bez Androida. */
object MeetingNotes {

    fun transkrypcja(linie: List<MeetingLine>): String =
        linie.joinToString("\n") { "[${czas(it.odStartuMs)}] ${it.tekst}" }

    /**
     * Polecenie dla modelu. Krótkie i stałe w formie: wynik idzie do notatek
     * i na głos, więc musi się dać przeczytać bez formatowania.
     */
    fun polecenie(linie: List<MeetingLine>, czasTrwaniaMs: Long): String = buildString {
        append("Poniżej transkrypcja spotkania (").append(czasTrwaniaMs / 60_000).append(" min), ")
        append("przepisana automatycznie z mikrofonu - mogą w niej być pomyłki rozpoznawania mowy, ")
        append("popraw je po sensie, ale nie wymyślaj niczego, czego nie ma.\n\n")
        append("Przygotuj notatkę po polsku, BEZ markdown, dokładnie w tym układzie:\n")
        append("TEMAT: jedno zdanie\n")
        append("USTALENIA:\n- ...\n")
        append("ZADANIA:\n- kto: co (termin, jeśli padł)\n")
        append("OTWARTE PYTANIA:\n- ...\n")
        append("Puste sekcje oznacz \"- brak\".\n\n")
        append("TRANSKRYPCJA:\n").append(transkrypcja(linie))
    }

    /** Pierwsza linia "TEMAT: ..." - tytuł na liście spotkań. */
    fun temat(podsumowanie: String?): String? =
        podsumowanie?.lineSequence()?.firstOrNull { it.trim().uppercase().startsWith("TEMAT:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    /** Co powiedzieć na głos po zakończeniu - temat i liczba zadań, nie cała notatka. */
    fun naGłos(podsumowanie: String?, linii: Int): String {
        if (linii == 0) return "Nagranie zakończone, ale nie udało się nic przepisać - było za cicho albo za daleko."
        if (podsumowanie == null) {
            return "Spotkanie zapisane: $linii wypowiedzi. Podsumowania nie udało się zrobić - " +
                "transkrypcja jest w aplikacji."
        }
        val zadania = sekcja(podsumowanie, "ZADANIA").count { !it.contains("brak", ignoreCase = true) }
        val t = temat(podsumowanie)
        return buildString {
            append("Zapisałem notatki ze spotkania")
            if (t != null) append(": ").append(t.trimEnd('.')).append('.') else append('.')
            when (zadania) {
                0 -> append(" Bez zadań.")
                1 -> append(" Jedno zadanie: ").append(sekcja(podsumowanie, "ZADANIA").first().removePrefix("-").trim()).append('.')
                else -> append(" Zadań: ").append(zadania).append(". Szczegóły są w notatkach.")
            }
        }
    }

    fun sekcja(tekst: String, nazwa: String): List<String> {
        val linie = tekst.lines()
        val start = linie.indexOfFirst { it.trim().uppercase().startsWith("$nazwa:") }
        if (start < 0) return emptyList()
        return linie.drop(start + 1)
            .takeWhile { !NAGŁÓWEK.matches(it.trim()) }
            .map { it.trim() }
            .filter { it.startsWith("-") }
    }

    fun doUdostępnienia(m: Meeting): String = buildString {
        val data = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("pl", "PL")).format(Date(m.startMs))
        append("Spotkanie - ").append(data).append(" (").append((m.koniecMs - m.startMs) / 60_000).append(" min)\n\n")
        m.podsumowanie?.let { append(it.trim()).append("\n\n") }
        append("TRANSKRYPCJA\n").append(transkrypcja(m.linie))
    }

    private fun czas(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private val NAGŁÓWEK = Regex("""^[A-ZĄĆĘŁŃÓŚŹŻ ]{4,}:.*$""")
}
