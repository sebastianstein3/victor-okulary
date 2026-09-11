package pl.victor.app.actions

import java.text.Normalizer

/**
 * Pytania, na które asystent odpowiada Z SIEBIE, bez pytania modelu.
 *
 * ## Po co
 * Zgłoszone wprost: „AI nie wie, jakie ma dostępne persony - powinien móc
 * przeszukiwać jakieś info, gdy go zapytam o jego funkcje, i tam miałby je
 * zapisane - nie w głównym prompcie, żeby nie przedłużać działania AI".
 *
 * Obie połowy tego zdania są ważne i obie są tu spełnione:
 *
 * 1. **Model nie wiedział**, bo lista person i komend nigdy do niego nie
 *    docierała. Na „jakie persony mamy dostępne" (dziennik z 21:55, tura b873)
 *    odpowiadał więc tym, co mu się wydawało - a wydawało mu się nie to.
 * 2. **Nie w prompcie.** Doklejenie katalogu do każdego zapytania kosztowałoby
 *    kilka tysięcy znaków w KAŻDEJ turze, także wtedy, gdy pytanie dotyczy
 *    pogody. W dzienniku prompt i tak już sięga 11 tysięcy znaków, a czas do
 *    pierwszego fragmentu odpowiedzi rośnie razem z nim.
 *
 * Rozwiązanie jest trzecie: pytanie o samego siebie rozpoznajemy na miejscu i
 * odpowiadamy z katalogu, który aplikacja już ma. Model nie jest w to w ogóle
 * angażowany, więc odpowiedź pada natychmiast, a prompt zostaje nietknięty.
 *
 * Ta klasa jest CZYSTA - rozpoznaje temat i nic więcej. Treść składa
 * [pl.victor.app.AIOrchestrator] z żywych katalogów, żeby nie dublować tu
 * listy, która i tak zmienia się w dwóch innych plikach.
 */
object SelfKnowledge {

    /** O co pyta użytkownik, gdy pyta o asystenta. */
    enum class Topic {
        /** „jakie masz persony", „jakie persony są dostępne" */
        PERSONAS,

        /** „co potrafisz", „jakie masz funkcje" */
        CAPABILITIES
    }

    // Rdzenie, nie pełne formy: rozpoznawanie mowy odda raz „persony", raz
    // „persona", raz „style odpowiedzi", raz „styl odpowiedzi". Dopasowanie do
    // pełnej formy trafiłoby jeden zapis z czterech.
    private val PERSONA_HINTS = listOf("person", "postac", "styl", "tryb rozmowy")

    private val CAPABILITY_PHRASES = listOf(
        "co potrafisz", "co umiesz", "co mozesz", "co ty potrafisz",
        "jakie masz funkcje", "jakie sa funkcje", "twoje funkcje",
        "jakie masz mozliwosci", "co masz w ofercie", "do czego sluzysz",
        "jakie komendy", "jakie masz komendy", "lista komend"
    )

    /**
     * Pytania o OBRAZ, które nie mogą trafić do tej ścieżki, choćby przypadkiem
     * zawierały podobne słowo. „Co widzisz" ma uruchomić aparat, a nie
     * wyliczankę funkcji - i jest to najczęstsze pytanie w całej aplikacji.
     */
    private val VISION_PHRASES = listOf("co widzisz", "co przede mna", "co to jest", "przeczytaj")

    /** @return temat albo `null`, gdy to zwykłe pytanie do modelu */
    fun topicOf(question: String): Topic? {
        val q = normalize(question)
        if (q.isEmpty()) return null
        if (VISION_PHRASES.any { q.contains(it) }) return null

        val asksAboutSelf = q.contains("jakie") || q.contains("jaka") || q.contains("ktore") ||
            q.contains("wymien") || q.contains("lista") || q.contains("dostepne") ||
            q.contains("masz") || q.contains("mamy")
        if (asksAboutSelf && PERSONA_HINTS.any { q.contains(it) }) return Topic.PERSONAS

        if (CAPABILITY_PHRASES.any { q.contains(it) }) return Topic.CAPABILITIES
        return null
    }

    /** Małe litery, bez ogonków i interpunkcji - rozpoznawanie mowy bywa różne. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
