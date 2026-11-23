package pl.victor.app.memory

/**
 * Fakty o użytkowniku - to, co asystent ma o nim WIEDZIEĆ, a nie pamiętać z rozmowy.
 *
 * ## Czym to się różni od notatek i od historii
 * - **Notatka** to zadanie albo myśl na później: "kupić mleko". Ma datę, bywa
 *   odhaczana, przestaje być aktualna.
 * - **Historia rozmów** ([LongTermMemory]) to zapis tego, co padło - szukany
 *   dopiero wtedy, gdy pytanie do niego pasuje.
 * - **Fakt** jest trwały i dotyczy CIEBIE: jak masz na imię, gdzie mieszkasz,
 *   czego nie jesz, jak masz na imię do żony. Idzie do modelu przy KAŻDYM
 *   pytaniu, bo bez tego asystent za każdym razem zaczyna od zera.
 *
 * ## Dlaczego to jest osobny, testowalny plik
 * Bo fałszywy fakt jest gorszy niż jego brak. Model, który "wie", że mieszkasz
 * w mieście, w którym nie mieszkasz, będzie tę nieprawdę powtarzał pewnym
 * głosem w każdej kolejnej odpowiedzi - i nie da się tego cofnąć rozmową.
 * Dlatego rozpoznawanie, zastępowanie i usuwanie są tu czystymi funkcjami z
 * testami, a nie warunkami rozsianymi po orkiestratorze.
 */
object UserFacts {

    /** Jeden fakt o użytkowniku. */
    data class Fact(val text: String, val createdAtMs: Long)

    /**
     * Zwroty otwierające fakt.
     *
     * Świadomie ROZŁĄCZNE z notatkami: tam jest "zapisz", "zanotuj", "notatka",
     * "dodaj"; tutaj "zapamiętaj", "pamiętaj", "wiedz". Granica jest prosta i
     * daje się wytłumaczyć jednym zdaniem - "zapisz" to zadanie, "zapamiętaj"
     * to coś o mnie - a bez niej jedno wpadałoby w drugie.
     */
    private val PREFIXES = listOf(
        "zapamiętaj o mnie że",
        "zapamiętaj o mnie ze",
        "zapamiętaj sobie że",
        "zapamiętaj sobie ze",
        "zapamiętaj że",
        "zapamiętaj ze",
        "zapamiętaj",
        "zapamietaj że",
        "zapamietaj ze",
        "zapamietaj",
        "pamiętaj że",
        "pamiętaj ze",
        "pamietaj że",
        "wiedz że",
        "wiedz ze",
        "o mnie"
    ).sortedByDescending { it.length }

    /** Zwroty, po których fakt ma zniknąć. */
    private val FORGET_PREFIXES = listOf(
        "zapomnij o mnie że",
        "zapomnij że",
        "zapomnij ze",
        "zapomnij o",
        "zapomnij",
        "usuń fakt",
        "usun fakt"
    ).sortedByDescending { it.length }

    private const val SEPARATORS = " :,-–—\t"

    private fun startsWithPrefix(lower: String, prefix: String): Boolean {
        if (!lower.startsWith(prefix)) return false
        if (lower.length == prefix.length) return true
        return lower[prefix.length] in SEPARATORS
    }

    private fun body(text: String, prefixes: List<String>): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        val prefix = prefixes.firstOrNull { startsWithPrefix(lower, it) } ?: return null
        val rest = stripConjunction(
            trimmed.substring(prefix.length).trimStart { it in SEPARATORS }.trim()
        )
        if (rest.length < MIN_BODY) return null
        return rest
    }

    /** Spójniki, które zostają po przecinku: "zapamiętaj, ŻE mieszkam...". */
    private val CONJUNCTIONS = listOf("że", "ze", "iż", "iz")

    /**
     * Obcina spójnik z początku treści.
     *
     * Wzorce zawierają "zapamiętaj że", ale ludzie mówią "zapamiętaj, że" - z
     * przecinkiem. Wtedy pasuje dopiero krótszy wzorzec "zapamiętaj", a w
     * treści zostaje sierociarne "że mieszkam w Krakowie". Zapisany fakt
     * zaczynałby się od spójnika i tak też trafiałby do modelu.
     */
    private fun stripConjunction(text: String): String {
        val lower = text.lowercase()
        val hit = CONJUNCTIONS.firstOrNull {
            lower.startsWith(it) && (lower.length == it.length || lower[it.length] in SEPARATORS)
        } ?: return text
        return text.substring(hit.length).trimStart { it in SEPARATORS }.trim()
    }

    /** Krótsza treść to przesłyszenie, nie fakt. */
    private const val MIN_BODY = 3

    /**
     * Wyciąga fakt z wypowiedzi.
     *
     * @return treść faktu albo `null`, gdy to nie jest prośba o zapamiętanie
     */
    fun extract(text: String): String? =
        body(text, PREFIXES)?.replaceFirstChar { it.uppercase() }

    /** Wyciąga treść, którą użytkownik chce, żeby asystent zapomniał. */
    fun extractForget(text: String): String? = body(text, FORGET_PREFIXES)

    /** Czy wypowiedź to prośba o wyliczenie tego, co asystent wie. */
    fun isListRequest(text: String): Boolean {
        val normalized = text.lowercase().trim().trimEnd('.', '!', '?').trim()
        return normalized in LIST_PHRASES
    }

    private val LIST_PHRASES = setOf(
        "co o mnie wiesz",
        "co o mnie pamiętasz",
        "co o mnie pamietasz",
        "co wiesz o mnie",
        "co zapamiętałeś",
        "co zapamietales",
        "wypisz fakty",
        "moje fakty"
    )

    /**
     * Tematy, których może być tylko JEDEN.
     *
     * Sedno całego zastępowania. "Mieszkam w Krakowie" po "Mieszkam w
     * Warszawie" ma STARY fakt zastąpić - inaczej model dostaje dwa sprzeczne
     * zdania i wybiera losowo. Ale "Nie lubię kawy" po "Nie lubię herbaty" ma
     * się DOŁOŻYĆ, bo to lista, nie jedna wartość. Dlatego zastępujemy tylko
     * po tej wąskiej, wypisanej liście, a wszystko inne się kumuluje.
     */
    private val SINGULAR_TOPICS = listOf(
        "mam na imię", "mam na imie", "nazywam się", "nazywam sie",
        "mieszkam", "pracuję", "pracuje", "mam lat",
        "urodziłem się", "urodziłam się", "urodziny mam",
        "moja żona", "mój mąż", "moja partnerka", "mój partner",
        "mój numer", "mój adres", "moje auto", "jeżdżę"
    )

    /**
     * Temat faktu - klucz, po którym nowy fakt zastępuje stary.
     *
     * Dla faktów spoza [SINGULAR_TOPICS] kluczem jest cała treść, więc
     * powtórzenie tego samego zdania niczego nie duplikuje, a nowe zdanie
     * dokłada się do listy.
     */
    fun topicOf(fact: String): String {
        val lower = fact.lowercase().trim()
        return SINGULAR_TOPICS.firstOrNull { lower.startsWith(it) } ?: lower
    }

    /**
     * Dokłada fakt, zastępując poprzedni o tym samym temacie.
     *
     * Nowe idą na początek: przy wypisywaniu i w poleceniu dla modelu to, co
     * świeże, ma być pierwsze.
     */
    fun merge(existing: List<Fact>, fresh: Fact): List<Fact> {
        val topic = topicOf(fresh.text)
        return listOf(fresh) + existing.filterNot { topicOf(it.text) == topic }
    }

    /**
     * Usuwa fakty pasujące do opisu.
     *
     * Dopasowanie jest luźne (zawieranie, bez wielkości liter), bo użytkownik
     * mówi "zapomnij o Krakowie", a nie cytuje zapisanego zdania.
     */
    fun forget(existing: List<Fact>, what: String): List<Fact> {
        val needle = what.lowercase().trim().trimEnd('.', '!', '?')
        if (needle.isEmpty()) return existing
        // "Zapomnij o tym" to zwykła odpowiedź w rozmowie, nie polecenie
        // skasowania wszystkich faktów z "tym" w środku (przegląd 22.11).
        if (needle in ZAIMKI) return existing
        // Od początku SŁOWA, nie gdziekolwiek: "zapomnij o kocie" nie kasuje
        // "mam skocie...". Końcówki dalej łapie ("krakow" -> "Krakowie").
        val wzór = Regex("(^|[^\\p{L}])" + Regex.escape(needle))
        return existing.filterNot { wzór.containsMatchIn(it.text.lowercase()) }
    }

    private val ZAIMKI = setOf(
        "tym", "tamtym", "tym wszystkim", "wszystkim", "nim", "niej", "nich", "niego",
        "tego", "to", "tamto", "tamtej", "tej", "czymś", "czyms", "mnie", "sobie"
    )

    /**
     * Fakty jako sekcja polecenia dla modelu.
     *
     * Bez dat - w przeciwieństwie do notatek. Fakt "mieszkam w Krakowie" nie
     * staje się mniej prawdziwy dlatego, że zapisano go w marcu, a data tylko
     * kusiłaby model do rozważań, czy to jeszcze aktualne.
     */
    fun buildPromptContext(facts: List<Fact>): String? {
        if (facts.isEmpty()) return null
        return buildString {
            append("=== CO WIESZ O UŻYTKOWNIKU ===\n")
            facts.take(PROMPT_LIMIT).forEach { append("- ").append(it.text).append('\n') }
            append("To są fakty, które użytkownik sam kazał zapamiętać. ")
            append("Korzystaj z nich swobodnie i nie pytaj o to, co już tu jest. ")
            append("Nie wymyślaj faktów, których tu nie ma, i nie zakładaj niczego ")
            append("ponad to, co napisano.")
        }
    }

    /** Ile faktów trafia do polecenia - reszta tylko zabierałaby miejsce. */
    const val PROMPT_LIMIT = 40

    /** Fakty do wypowiedzenia na głos. */
    fun speak(facts: List<Fact>): String {
        if (facts.isEmpty()) {
            return "Nie zapamiętałem jeszcze niczego o Tobie. Powiedz " +
                "\"zapamiętaj, że...\", a zapiszę."
        }
        val body = facts.take(SPOKEN_LIMIT)
            .mapIndexed { index, fact -> "${index + 1}. ${fact.text}" }
            .joinToString(" ")
        val header = if (facts.size == 1) "Wiem o Tobie jedno." else "Wiem o Tobie ${facts.size} rzeczy."
        val tail = if (facts.size > SPOKEN_LIMIT) " Resztę zobaczysz w aplikacji." else ""
        return "$header $body$tail"
    }

    private const val SPOKEN_LIMIT = 10
}
