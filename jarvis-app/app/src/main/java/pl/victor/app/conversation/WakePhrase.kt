package pl.victor.app.conversation

import java.text.Normalizer

/**
 * Odsiewa samo słowo wybudzenia od pytania, które ma po nim iść.
 *
 * ## Co się działo
 * Z dziennika z 11 września, tura `e117`:
 *
 * ```
 * 18:17:07.453  SESJA  pytanie  tekst=okej lens  wymuszonyObraz=false
 * 18:17:09.112  MODEL  wysyłam pytanie  znakówPromptu=15896
 * ```
 *
 * Użytkownik powiedział do okularów frazę wybudzenia, a aplikacja potraktowała
 * ją jako CAŁE pytanie: zbudowała kontekst, wysłała do modelu i zaczęła mówić.
 * Właściwe pytanie, zadane sekundę później, trafiało już w trwającą turę.
 *
 * Z zewnątrz wygląda to dokładnie tak, jak zgłoszono: „wywołuję AI głosowo,
 * okulary reagują, ale aplikacja nic nie robi, jakby nie słyszała".
 *
 * ## Co robimy zamiast tego
 * Wypowiedź złożona z samej frazy nie jest pytaniem - jest prośbą o nasłuch.
 * Gdy po frazie coś jeszcze padło („okej lens jaka jest pogoda"), pytaniem
 * jest reszta, bo fraza jest adresem, nie treścią.
 *
 * ## Dlaczego porównujemy po uproszczeniu
 * Bo tekst przychodzi z rozpoznawania mowy: raz „OK Lens", raz „okej lens",
 * raz z przecinkiem. Porównanie wprost trafiłoby jeden zapis z wielu.
 */
object WakePhrase {

    /**
     * Frazy wybudzenia po stronie OKULARÓW, w zapisach, jakie zwraca
     * rozpoznawanie mowy. „okej lens" jest tu, bo dokładnie tak zapisał je
     * telefon w dzienniku - to nie jest zgadywanie, tylko obserwacja.
     */
    val GLASSES_PHRASES: List<String> = listOf(
        "ok lens", "okej lens", "okay lens", "o kej lens", "okey lens", "hej lens", "hey lens"
    )

    /**
     * Do PORÓWNAŃ: małe litery, bez ogonków, bez interpunkcji.
     *
     * `ł` i `Ł` trzeba podmienić ręcznie, zanim ruszy `Normalizer`: w Unicode
     * nie są literą z dodanym znakiem diakrytycznym, tylko osobnymi literami,
     * więc rozkład ich nie rusza i filtr znaków zamieniłby je na spację.
     * Pierwsza wersja tego kodu robiła ze „światło" - „swiat o", co widać było
     * dopiero w teście.
     */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    /**
     * Czy ta wypowiedź to SAMA fraza wybudzenia, bez pytania.
     *
     * @param extraPhrases dodatkowe frazy (np. komenda wybrana na telefonie)
     */
    fun isOnlyWakePhrase(text: String, extraPhrases: Collection<String> = emptyList()): Boolean {
        val spoken = meaningfulTokens(text)
        if (spoken.isEmpty()) return false
        return allPhrases(extraPhrases).any { it == spoken }
    }

    /**
     * Zwraca pytanie bez wiodącej frazy wybudzenia - w ORYGINALNYM zapisie.
     *
     * Uproszczenie służy wyłącznie dopasowaniu; tekst, który idzie dalej, musi
     * zachować ogonki i interpunkcję, bo trafia do modelu i do historii.
     *
     * Gdy frazy nie ma albo nic po niej nie zostaje, oddaje tekst bez zmian -
     * decyzję „to była sama fraza" podejmuje [isOnlyWakePhrase], żeby jedno
     * wywołanie nie musiało znaczyć dwóch różnych rzeczy.
     */
    fun stripLeadingWakePhrase(
        text: String,
        extraPhrases: Collection<String> = emptyList()
    ): String {
        val spokenWords = text.split(WHITESPACE).filter { it.isNotBlank() }
        // Najdłuższe dopasowanie wygrywa: gdyby ktoś miał i „lens", i
        // „okej lens", krótsza fraza zostawiłaby w pytaniu słowo „lens".
        val consumed = allPhrases(extraPhrases)
            .mapNotNull { leadingWordsCovering(spokenWords, it) }
            .maxOrNull()
            ?: return text
        val rest = spokenWords.drop(consumed).joinToString(" ")
        return rest.ifBlank { text }
    }

    /**
     * Ile PIERWSZYCH słów wypowiedzi pokrywa ta fraza, albo `null` gdy nie
     * zaczyna się od niej. Liczymy na słowach oryginału, nie na złączonym
     * tekście - tylko tak wiadomo, gdzie odciąć bez ruszania reszty.
     */
    private fun leadingWordsCovering(spokenWords: List<String>, phrase: String): Int? {
        var matched = StringBuilder()
        for ((index, word) in spokenWords.withIndex()) {
            val normalized = normalize(word)
            // Słowo z samej interpunkcji („—") nic nie wnosi, ale zajmuje
            // miejsce w oryginale, więc musi zostać policzone do odcięcia.
            if (normalized.isNotEmpty()) {
                if (matched.isNotEmpty()) matched.append(' ')
                matched.append(normalized)
            }
            val soFar = matched.toString()
            if (soFar == phrase) return index + 1
            if (soFar.length >= phrase.length || !phrase.startsWith(soFar)) return null
        }
        return null
    }

    /** Wypowiedź sprowadzona do porównywalnej postaci, bez pustych słów. */
    private fun meaningfulTokens(text: String): String =
        text.split(WHITESPACE)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun allPhrases(extra: Collection<String>): List<String> =
        (GLASSES_PHRASES + extra).map { normalize(it) }.filter { it.isNotEmpty() }.distinct()

    private val WHITESPACE = Regex("\\s+")
}
