package pl.victor.app.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tryb krok po kroku: przepis, składanie mebli, konfiguracja routera.
 *
 * Model rozpisuje zadanie na krótkie kroki, a asystent podaje JEDEN naraz i
 * czeka - ręce są zajęte, więc przechodzi się dalej przyciskiem:
 * - 1 klik (albo "Hey Lens") - następny krok,
 * - 2 kliki - pytanie o bieżący krok (model zna wszystkie kroki),
 * - 3 kliki - powtórz,
 * - 4 kliki - koniec.
 * Głosem działa to samo: "dalej", "powtórz", "wróć", "koniec instrukcji".
 */
class StepMode {

    data class Stan(val zadanie: String, val kroki: List<String>, val bieżący: Int)

    private val _stan = MutableStateFlow<Stan?>(null)
    val stan: StateFlow<Stan?> = _stan.asStateFlow()

    val aktywny: Boolean get() = _stan.value != null

    fun start(zadanie: String, kroki: List<String>) {
        _stan.value = Stan(zadanie, kroki, 0)
    }

    fun stop() {
        _stan.value = null
    }

    /** Tekst bieżącego kroku do powiedzenia. */
    fun bieżący(): String? = _stan.value?.let { StepLogic.naGłos(it.kroki, it.bieżący) }

    /** @return tekst następnego kroku albo komunikat końca (tryb się wyłącza) */
    fun dalej(): String? {
        val s = _stan.value ?: return null
        if (s.bieżący + 1 >= s.kroki.size) {
            stop()
            return "To był ostatni krok. Gotowe!"
        }
        _stan.value = s.copy(bieżący = s.bieżący + 1)
        return bieżący()
    }

    fun wstecz(): String? {
        val s = _stan.value ?: return null
        _stan.value = s.copy(bieżący = (s.bieżący - 1).coerceAtLeast(0))
        return bieżący()
    }
}

/** Czysta logika trybu krok po kroku. */
object StepLogic {

    enum class Komenda { DALEJ, POWTÓRZ, WSTECZ, KONIEC }

    /** "Krok po kroku: jak upiec chleb" -> "jak upiec chleb". */
    fun prośba(tekst: String): String? {
        val t = tekst.trim().trimEnd('.', '?', '!')
        return START.matchEntire(t)?.groups?.get("co")?.value?.trim()?.takeIf { it.length >= 3 }
    }

    fun komenda(tekst: String): Komenda? {
        val t = tekst.lowercase().trim().trimEnd('.', '!', '?')
        return when {
            DALEJ.matches(t) -> Komenda.DALEJ
            POWTÓRZ.matches(t) -> Komenda.POWTÓRZ
            WSTECZ.matches(t) -> Komenda.WSTECZ
            KONIEC.matches(t) -> Komenda.KONIEC
            else -> null
        }
    }

    fun polecenie(zadanie: String): String =
        "Rozpisz na kroki: $zadanie.\n" +
            "Każdy krok w osobnej linii, zaczynając od numeru i kropki (\"1. ...\"). " +
            "Jeden krok = jedna czynność, jedno-dwa krótkie zdania do przeczytania na głos. " +
            "Najwyżej $MAKS_KROKÓW kroków. Bez wstępu, bez podsumowania, bez markdown. " +
            "Jeśli potrzebne są składniki albo narzędzia, pierwszy krok niech je wymienia."

    /** Wyciąga kroki z odpowiedzi modelu - linie "1. ...", "2) ...", "- ...". */
    fun kroki(odpowiedź: String): List<String> =
        odpowiedź.lines()
            .map { it.trim() }
            .mapNotNull { linia -> NUMER.matchEntire(linia)?.groups?.get("tresc")?.value?.trim() }
            .filter { it.isNotEmpty() }
            .take(MAKS_KROKÓW)

    fun naGłos(kroki: List<String>, i: Int): String = "Krok ${i + 1} z ${kroki.size}. ${kroki[i]}"

    /** Kontekst dla modelu przy pytaniu w trakcie ("a jaki śrubokręt?"). */
    fun kontekst(s: StepMode.Stan): String = buildString {
        append("=== INSTRUKCJA KROK PO KROKU (trwa) ===\n")
        append("Zadanie: ").append(s.zadanie).append('\n')
        s.kroki.forEachIndexed { i, k ->
            append(i + 1).append(". ").append(k)
            if (i == s.bieżący) append("   <- użytkownik jest TU")
            append('\n')
        }
        append("Odpowiedz krótko na pytanie o bieżący krok. Nie czytaj wszystkich kroków.\n")
    }

    const val MAKS_KROKÓW = 15

    private val START = Regex(
        """^(?:(?:prowad[zź]\s+mnie|poprowad[zź]\s+mnie|tryb|instrukcja|w[lł][aą]cz\s+tryb)\s+)?""" +
            """krok\s+po\s+kroku[:,]?\s+(?:przez\s+|jak\s+)?(?<co>.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val NUMER = Regex("""^(?:\d{1,2}[.)]|[-•])\s*(?<tresc>.+)$""")
    private val DALEJ = Regex("""^(dalej|nast[eę]pny(\s+krok)?|kolejny(\s+krok)?|zrobione|gotowe|ok\s+dalej)$""")
    private val POWTÓRZ = Regex("""^(powt[oó]rz(\s+krok)?|jeszcze\s+raz|co\s+teraz)$""")
    private val WSTECZ = Regex("""^(wr[oó][cć]|wstecz|poprzedni(\s+krok)?|cofnij)$""")
    private val KONIEC = Regex("""^(koniec(\s+instrukcji)?|zako[nń]cz(\s+instrukcj[eę])?|wy[lł][aą]cz\s+tryb\s+krok\s+po\s+kroku|stop\s+instrukcja)$""")
}
