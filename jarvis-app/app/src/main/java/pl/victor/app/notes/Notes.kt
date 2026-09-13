package pl.victor.app.notes

/**
 * Notatki dyktowane głosem.
 *
 * ## Po co osobny plik na rozpoznawanie frazy
 * Bo to jest jedyna część, która musi być PEWNA. Reszta (zapis, lista, ekran)
 * jest odwracalna jednym kliknięciem, a błędne rozpoznanie nie: albo notatka
 * ginie w rozmowie z modelem, albo - gorzej - zwykłe pytanie ląduje w
 * notatniku zamiast dostać odpowiedź. Dlatego dopasowanie jest tu czystą
 * funkcją z testami, a nie warunkiem schowanym w orkiestratorze.
 */
object Notes {

    /**
     * Zwroty otwierające notatkę. Liczy się PRZEDROSTEK wypowiedzi, nie
     * fragment: "zapisz, że mam oddać książkę" to notatka, ale "co zapisałeś
     * wczoraj" jest pytaniem i musi nim zostać.
     *
     * Bez spacji na końcu, bo po zwrocie równie często pada dwukropek co
     * spacja - zgłoszone jako "powiedziałem «Notatka: kupić XYZ» i nic się nie
     * zapisało". Granicę sprawdza [startsWithPrefix], a nie sam tekst wzorca.
     */
    private val PREFIXES = listOf(
        "zapisz że",
        "zapisz ze",
        "zapisz sobie że",
        "zapisz sobie ze",
        "zapisz",
        "zanotuj że",
        "zanotuj ze",
        "zanotuj",
        "dodaj do notatek",
        "dodaj notatkę",
        "dodaj notatke",
        "nowa notatka",
        "zrób notatkę",
        "zrob notatke",
        // Najprostsza forma, jakiej ktokolwiek użyje, a jej dotąd nie było.
        "notatka",
        "notatki do zapisania",
        "notka",
        "przypomnij mi że",
        "przypomnij mi ze",
        "przypomnij mi o",
        "przypomnij mi",
        "dodaj do listy zakupów",
        "dodaj do listy zakupow",
        "dopisz do listy",
        "dodaj"
    ).sortedByDescending { it.length }

    /**
     * Znaki, które mogą stać między zwrotem otwierającym a treścią notatki.
     * Dwukropek jest tu najważniejszy - tak dyktuje się notatki najczęściej.
     */
    private const val SEPARATORS = " :,-\u2013\u2014\t"

    /**
     * Czy wypowiedź zaczyna się od danego zwrotu ZAKOŃCZONEGO granicą słowa.
     *
     * Bez sprawdzania granicy "notatka" łapałoby "notatki" (czyli prośbę o
     * odczytanie), a "dodaj" - "dodajmy".
     */
    private fun startsWithPrefix(lower: String, prefix: String): Boolean {
        if (!lower.startsWith(prefix)) return false
        if (lower.length == prefix.length) return true
        return lower[prefix.length] in SEPARATORS
    }

    /**
     * Wyciąga treść notatki z wypowiedzi.
     *
     * @return treść albo `null`, gdy to nie jest prośba o notatkę
     */
    fun extract(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()

        // Kalendarz ma pierwszeństwo przed notatnikiem. "Zapisz mi spotkanie na
        // piątek" to prośba o wydarzenie, nie o notatkę - a przechwycenie jej
        // tutaj kończyłoby się notatką zamiast wpisu w kalendarzu i cichym
        // brakiem przypomnienia.
        if (CALENDAR_WORDS.any { lower.contains(it) }) return null

        val prefix = PREFIXES.firstOrNull { startsWithPrefix(lower, it) } ?: return null
        val body = stripConjunction(
            trimmed.substring(prefix.length).trimStart { it in SEPARATORS }.trim()
        )
        // Sam czasownik bez treści to nie notatka, tylko urwane zdanie -
        // zapisanie pustki byłoby gorsze niż przyznanie, że nie zrozumiałem.
        if (body.length < MIN_BODY) return null
        return body.replaceFirstChar { it.uppercase() }
    }

    /** Krótsza treść to najpewniej przesłyszenie, a nie notatka. */
    private const val MIN_BODY = 3

    /** Spójniki, które zostają po przecinku: "zapisz, ŻE mam kupić mleko". */
    private val CONJUNCTIONS = listOf("że", "ze", "iż", "iz")

    /**
     * Obcina spójnik z początku treści.
     *
     * Wzorce zawierają "zapisz że", ale ludzie mówią "zapisz, że" - z
     * przecinkiem. Wtedy pasuje dopiero krótszy wzorzec "zapisz", a w treści
     * zostaje sierociarne "że mam kupić mleko". Tekst podpowiedzi w aplikacji
     * podaje właśnie formę z przecinkiem, więc dokładnie ta droga była
     * najczęstsza - i zapisywała notatkę zaczynającą się od spójnika.
     */
    private fun stripConjunction(text: String): String {
        val lower = text.lowercase()
        val hit = CONJUNCTIONS.firstOrNull {
            lower.startsWith(it) && (lower.length == it.length || lower[it.length] in SEPARATORS)
        } ?: return text
        return text.substring(hit.length).trimStart { it in SEPARATORS }.trim()
    }

    /** Słowa, po których wypowiedź należy do kalendarza, nie do notatnika. */
    private val CALENDAR_WORDS = listOf(
        "kalendarz", "spotkanie", "spotkania", "wydarzenie", "w kalendarzu"
    )

    /** Czy wypowiedź prosi o odczytanie notatek. */
    fun isListRequest(text: String): Boolean {
        val normalized = text.lowercase().trim().trimEnd('.', '!', '?').trim()
        return normalized in LIST_PHRASES
    }

    private val LIST_PHRASES = setOf(
        "notatki",
        "moje notatki",
        "przeczytaj notatki",
        "przeczytaj moje notatki",
        "przejrzyj notatki",
        "sprawdź notatki",
        "sprawdz notatki",
        "sprawdź w notatkach",
        "sprawdz w notatkach",
        "co mam w notatkach",
        "co mam zapisane",
        "lista zakupów",
        "lista zakupow",
        "co mam do zrobienia"
    )

    /**
     * Czy wypowiedź w ogóle DOTYCZY notatek.
     *
     * Luźniejsze niż [isListRequest] i służy do czegoś innego: [isListRequest]
     * decyduje, czy odczytać notatki od razu, a to - czy doklejać je jako
     * kontekst dla modelu. Dzięki temu "czy mam coś do kupienia?" albo "co
     * miałem zrobić w piątek?" trafia do modelu RAZEM z notatkami i dostaje
     * prawdziwą odpowiedź, zamiast wyliczanki wszystkiego po kolei.
     */
    fun mentionsNotes(text: String): Boolean {
        val lower = text.lowercase()
        return KEYWORDS.any { lower.contains(it) }
    }

    private val KEYWORDS = listOf(
        // "zapisa" łapie zapisane, zapisałem, zapisał - a to jest dokładnie ta
        // forma, w której pada pytanie "co zapisałem wczoraj?".
        "notatk", "notatek", "zapisa", "zanotow",
        "lista zakup", "do zrobienia", "do kupienia"
    )

    /**
     * Notatki jako sekcja kontekstu dla modelu.
     *
     * ## Dlaczego każda notatka niesie datę
     * Bez niej "co zapisałem wczoraj?" jest pytaniem bez odpowiedzi - model
     * widzi listę zdań bez osi czasu i albo zgaduje, albo mówi, że nie wie.
     * Data idzie w dwóch postaciach naraz: dokładnej (do liczenia) i słownej
     * ("wczoraj", "dziś"), bo modele mylą się w arytmetyce kalendarzowej
     * znacznie częściej niż w czytaniu gotowej etykiety.
     *
     * @param nowMs "teraz" podawane z zewnątrz, żeby dało się to sprawdzić
     *   testem - zegar systemowy w czystej funkcji znaczy test, który psuje
     *   się o północy
     * @return sekcja albo `null`, gdy nie ma ani jednej notatki - pusta sekcja
     *   tylko zajmowałaby miejsce w poleceniu
     */
    fun buildPromptContext(notes: List<Note>, nowMs: Long = System.currentTimeMillis()): String? {
        if (notes.isEmpty()) return null
        return buildString {
            append("=== NOTATKI UŻYTKOWNIKA ===\n")
            notes.forEach { note ->
                append("- ")
                if (note.createdAtMs > 0L) {
                    append('[').append(stamp(note.createdAtMs, nowMs)).append("] ")
                }
                append(note.text).append('\n')
            }
            append("To są notatki zapisane przez użytkownika, najnowsze pierwsze. ")
            append("W nawiasie kwadratowym jest data zapisania notatki. ")
            append("Odpowiadaj na ich podstawie, gdy pyta, co ma zapisane, do zrobienia ")
            append("albo do kupienia, i korzystaj z dat, gdy pyta o konkretny dzień. ")
            append("Nie wymyślaj notatek, których tu nie ma.")
        }
    }

    /** Data notatki: dokładna do liczenia i słowna do czytania. */
    private fun stamp(createdAtMs: Long, nowMs: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochMilli(createdAtMs).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(date.toLocalDate(), today)
        val label = when (days) {
            0L -> "dziś"
            1L -> "wczoraj"
            2L -> "przedwczoraj"
            in 3L..6L -> "$days dni temu"
            else -> null
        }
        val exact = date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return if (label != null) "$exact, $label" else exact
    }

    /** Jedna notatka: treść i kiedy powstała. */
    data class Note(val text: String, val createdAtMs: Long)

    /**
     * Składa notatki w zdanie do wypowiedzenia.
     *
     * Numerowane, bo bez numerów kilka notatek pod rząd zlewa się w jedno
     * zdanie i nie da się ich rozdzielić ze słuchu.
     */
    fun speak(notes: List<Note>): String {
        if (notes.isEmpty()) return "Nie masz jeszcze żadnych notatek."
        val body = notes.take(SPOKEN_LIMIT)
            .mapIndexed { index, note -> "${index + 1}. ${note.text}" }
            .joinToString(" ")
        val header = if (notes.size == 1) "Masz jedną notatkę." else "Masz ${notes.size} notatek."
        val tail = if (notes.size > SPOKEN_LIMIT) {
            " Resztę zobaczysz w aplikacji."
        } else {
            ""
        }
        return "$header $body$tail"
    }

    /** Ile notatek czytamy na głos, zanim odeślemy do aplikacji. */
    private const val SPOKEN_LIMIT = 10

    /**
     * Jak zapisywać to, co użytkownik podyktował.
     *
     * Wybór jest realny, a nie kosmetyczny: model potrafi zrobić z "kup mleko
     * to co zawsze i chleb" czytelne "Kupić mleko i chleb", ale potrafi też
     * zgubić szczegół, który dla piszącego był najważniejszy. Dlatego
     * domyślnie zapisujemy DOSŁOWNIE, a porządkowanie jest do włączenia.
     */
    enum class Style {
        /** Zapisz dokładnie to, co padło. Domyślne. */
        VERBATIM,

        /** Pozwól modelowi uporządkować sformułowanie przed zapisem. */
        AI;

        companion object {
            fun fromName(name: String?): Style =
                entries.firstOrNull { it.name == name } ?: VERBATIM
        }
    }

    /**
     * Polecenie porządkujące notatkę.
     *
     * Zakaz dopisywania jest tu najważniejszy: notatka, w której model dodał
     * coś od siebie, jest gorsza niż notatka niezgrabna - bo użytkownik
     * przeczyta ją później jako własną decyzję.
     */
    fun tidyPrompt(raw: String): String =
        "Uporządkuj poniższą notatkę podyktowaną głosem. Popraw interpunkcję i " +
            "oczywiste błędy rozpoznawania mowy, skróć powtórzenia. NIE dodawaj " +
            "niczego od siebie, nie interpretuj i nie zmieniaj sensu. Zachowaj " +
            "wszystkie liczby, nazwy i daty dokładnie tak, jak padły. Odpowiedz " +
            "SAMĄ treścią notatki, jednym zdaniem, bez cudzysłowów i bez wstępu.\n\n" +
            raw

    // === Notatka NAPISANA przez model, a nie podyktowana ===

    /**
     * Skąd wziąć materiał na notatkę, której użytkownik nie podyktował.
     *
     * ## Po co to w ogóle jest
     * Bo "zrób notatkę o tym zamku" nie jest notatką o treści "o tym zamku".
     * [extract] traktuje wszystko po zwrocie otwierającym jako treść i zapisuje
     * ją dosłownie - przy dyktowaniu ("zapisz, że mam kupić mleko") jest to
     * dokładnie właściwe, a tutaj daje zapis bez sensu. Różnica jest w tym, że
     * ta wypowiedź nie NIESIE treści, tylko ODSYŁA do czegoś poza sobą:
     * do zamku, na który użytkownik patrzy, albo do tego, co przed chwilą
     * usłyszał.
     */
    enum class Source {
        /** Materiałem jest ostatnia odpowiedź asystenta - "zrób z tego notatkę". */
        LAST_ANSWER,

        /** Materiałem jest to, co widzą okulary - "zrób notatkę o tym zamku". */
        SIGHT
    }

    /**
     * @param source skąd wziąć materiał
     * @param topic fraza, którą podał użytkownik ("o tym zamku") albo `null`;
     *   idzie do modelu jako wskazówka, o czym ma być notatka
     */
    data class Described(val source: Source, val topic: String?)

    /**
     * Zwroty odsyłające do TEGO, CO ASYSTENT WŁAŚNIE POWIEDZIAŁ.
     *
     * Kolejność od najdłuższego, bo "z tego co mówiłeś" zawiera "z tego".
     */
    private val LAST_ANSWER_REFERENCES = listOf(
        "z tego co powiedziałeś",
        "z tego co powiedziales",
        "z tego co mówiłeś",
        "z tego co mowiles",
        "z tej odpowiedzi",
        "z ostatniej odpowiedzi",
        "z tej rozmowy",
        "z tego wszystkiego",
        "z tego"
    ).sortedByDescending { it.length }

    /**
     * Zwroty odsyłające do TEGO, NA CO UŻYTKOWNIK PATRZY.
     *
     * "o tym" i "o tej" są tu z rozmysłem bez dalszego ciągu: po nich prawie
     * zawsze idzie rzeczownik ("o tym zamku", "o tej tablicy"), a tego nie ma
     * po co wyliczać - wystarczy, że wypowiedź zaczyna się od wskazania.
     */
    private val SIGHT_REFERENCES = listOf(
        "co widzisz",
        "co tu widać",
        "co tu widac",
        "z tego co widzisz",
        "z tego widoku",
        "o tym",
        "o tej",
        "o tych",
        "z tego zdjęcia",
        "z tego zdjecia"
    ).sortedByDescending { it.length }

    /**
     * Rozpoznaje prośbę o notatkę, której treść ma NAPISAĆ model.
     *
     * Sprawdzana PRZED [extract] i tylko dlatego działa: obie funkcje łapią te
     * same zwroty otwierające, więc ta bardziej szczegółowa musi mieć
     * pierwszeństwo. Gdy zwróci `null`, nic się nie zmienia i wypowiedź idzie
     * dawną drogą.
     *
     * @return skąd wziąć materiał, albo `null` gdy to zwykła notatka lub nie
     *   notatka w ogóle
     */
    fun describeRequest(text: String): Described? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()

        // Kalendarz ma pierwszeństwo tak samo jak w [extract] - inaczej
        // "zapisz spotkanie o tym projekcie" trafiłoby tutaj.
        if (CALENDAR_WORDS.any { lower.contains(it) }) return null

        // SZYK PIERWSZY: "zrób notatkę O TYM ZAMKU" - odsyłacz idzie po całym
        // zwrocie otwierającym.
        PREFIXES.firstOrNull { startsWithPrefix(lower, it) }?.let { prefix ->
            val rest = trimmed.substring(prefix.length).trimStart { it in SEPARATORS }.trim()
            sourceOf(rest)?.let { return Described(it, rest) }
        }

        // SZYK DRUGI: "zrób Z TEGO notatkę" - odsyłacz stoi MIĘDZY czasownikiem
        // a słowem "notatka".
        //
        // Tego szyku nie złapie żadna lista zwrotów otwierających, bo one
        // zakładają, że po czasowniku od razu idzie rzeczownik. A jest to szyk
        // całkiem naturalny i to właśnie nim padła prośba, od której ta funkcja
        // powstała.
        NOTE_VERBS.firstOrNull { startsWithPrefix(lower, it) }?.let { verb ->
            val afterVerb = trimmed.substring(verb.length).trimStart { it in SEPARATORS }.trim()
            val reference = ALL_REFERENCES.firstOrNull {
                startsWithReference(afterVerb.lowercase(), it)
            } ?: return@let
            // Po odsyłaczu ma zostać SAMO słowo "notatkę" i nic więcej.
            // Bez tego warunku "zrób z tego zdjęcie" byłoby notatką.
            val tail = afterVerb.substring(reference.length)
                .trim { it in SEPARATORS || it in ".!?" }
                .lowercase()
            if (tail !in NOTE_NOUNS) return@let
            val source =
                if (reference in LAST_ANSWER_REFERENCES) Source.LAST_ANSWER else Source.SIGHT
            return Described(source, afterVerb)
        }

        return null
    }

    /** Do którego źródła odsyła treść, albo `null` gdy do żadnego. */
    private fun sourceOf(rest: String): Source? {
        if (rest.isEmpty()) return null
        val lower = rest.lowercase()
        if (LAST_ANSWER_REFERENCES.any { startsWithReference(lower, it) }) {
            return Source.LAST_ANSWER
        }
        if (SIGHT_REFERENCES.any { startsWithReference(lower, it) }) return Source.SIGHT
        return null
    }

    /** Czasowniki, po których może stać odsyłacz, a dopiero potem "notatkę". */
    private val NOTE_VERBS = listOf(
        "zrób", "zrob", "zapisz", "zanotuj", "sporządź", "sporzadz", "stwórz", "stworz"
    ).sortedByDescending { it.length }

    /** Rzeczowniki zamykające szyk drugi - patrz [describeRequest]. */
    private val NOTE_NOUNS = setOf(
        "notatkę", "notatke", "notatka", "notatki", "notkę", "notke", "notka"
    )

    private val ALL_REFERENCES =
        (LAST_ANSWER_REFERENCES + SIGHT_REFERENCES).sortedByDescending { it.length }

    /**
     * Czy treść zaczyna się od zwrotu odsyłającego, zakończonego granicą słowa.
     *
     * Granica jest tu konieczna z tego samego powodu co w [startsWithPrefix]:
     * bez niej "o tym" łapałoby "o tymczasowym rozwiązaniu", czyli zwykłą
     * notatkę, i zamiast ją zapisać - poszlibyśmy po zdjęcie.
     */
    private fun startsWithReference(lower: String, reference: String): Boolean {
        if (!lower.startsWith(reference)) return false
        if (lower.length == reference.length) return true
        return lower[reference.length] in SEPARATORS
    }

    /**
     * Polecenie: napisz notatkę na podstawie gotowego tekstu.
     *
     * Inaczej niż [tidyPrompt], tutaj model MA pisać od siebie - to jest sens
     * tej funkcji. Ale ma pisać NOTATKĘ: rzecz do przeczytania za tydzień,
     * bez wstępu i bez zwracania się do czytelnika.
     */
    fun noteFromTextPrompt(material: String, topic: String?): String = buildString {
        append("Napisz zwięzłą notatkę na podstawie poniższego tekstu. ")
        append("Zapisz konkrety: nazwy, liczby, daty, fakty warte zapamiętania. ")
        if (!topic.isNullOrBlank()) {
            append("Użytkownik poprosił o notatkę tak: \"")
            append(topic)
            append("\" - trzymaj się tego tematu. ")
        }
        append("Najwyżej trzy zdania. Nie zaczynaj od wstępu w rodzaju ")
        append("\"Oto notatka\" ani \"Na podstawie tekstu\". ")
        append("Nie zwracaj się do czytelnika. Odpowiedz samą treścią notatki.\n\n")
        append(material)
    }

    /**
     * Polecenie: napisz notatkę o tym, co widać na zdjęciu.
     *
     * Zakaz opisywania zdjęcia jako zdjęcia jest tu istotny: "na zdjęciu widzę
     * zamek z czerwonej cegły" jest opisem obrazka, a notatka ma być o ZAMKU.
     * Za tydzień nikt nie będzie pamiętał, że powstała ze zdjęcia.
     */
    fun noteFromSightPrompt(topic: String?): String = buildString {
        append("Napisz zwięzłą notatkę o tym, co widać na zdjęciu. ")
        if (!topic.isNullOrBlank()) {
            append("Użytkownik poprosił o notatkę tak: \"")
            append(topic)
            append("\" - trzymaj się tego tematu. ")
        }
        append("Zapisz konkrety: co to jest, nazwy własne, napisy, liczby, ")
        append("cechy warte zapamiętania. Jeśli rozpoznajesz konkretne miejsce ")
        append("lub obiekt, nazwij go. Najwyżej trzy zdania. ")
        append("NIE pisz \"na zdjęciu widać\" ani \"widzę\" - notatka ma być o rzeczy, ")
        append("nie o zdjęciu. Nie zaczynaj od wstępu. Odpowiedz samą treścią notatki.")
    }

    /**
     * Czy to, co model napisał, nadaje się na notatkę.
     *
     * Kryteria są inne niż w [acceptTidied] i muszą być: tam model miał NIE
     * dopisywać, a tu dopisywanie jest całym zadaniem. Odrzucamy więc tylko to,
     * co nie jest notatką - pustkę, odmowę i wypracowanie.
     *
     * @return treść gotowa do zapisania albo `null`, gdy model nie dał się użyć
     */
    fun acceptWritten(written: String?): String? {
        val candidate = written?.trim()?.trim('"', '\u201e', '\u201d')?.trim().orEmpty()
        if (candidate.length < MIN_WRITTEN) return null
        if (candidate.length > MAX_WRITTEN) return null
        // Model, który nie umiał odpowiedzieć, mówi to zdaniem zaczynającym się
        // od przeprosin albo od "nie". Taka "notatka" jest gorsza niż jej brak,
        // bo wygląda w spisie jak zapisana myśl.
        val lower = candidate.lowercase()
        if (REFUSALS.any { lower.startsWith(it) }) return null
        return candidate.replaceFirstChar { it.uppercase() }
    }

    private const val MIN_WRITTEN = 10

    /**
     * Notatka dłuższa niż to jest wypracowaniem, nie notatką. Prosiliśmy o trzy
     * zdania; tyle miejsca wystarcza na cztery długie.
     */
    private const val MAX_WRITTEN = 600

    private val REFUSALS = listOf(
        "nie mogę", "nie moge", "nie jestem w stanie", "przepraszam",
        "niestety", "nie widzę", "nie widze", "brak "
    )

    /** Polecenie streszczające jedną notatkę. */
    fun summaryPrompt(text: String): String =
        "Streść poniższą notatkę w jednym, najwyżej dwóch zdaniach. Wypisz " +
            "konkret: co jest do zrobienia, do kiedy i czego dotyczy. Jeśli " +
            "notatka jest już krótka, powiedz to wprost zamiast ją przepisywać. " +
            "Odpowiedz samym streszczeniem, bez wstępu.\n\n" + text

    /**
     * Czy wynik porządkowania nadaje się do zapisania zamiast oryginału.
     *
     * Model zapytany o jedno zdanie potrafi oddać akapit z komentarzem albo
     * puste zdanie - i jedno, i drugie jest gorsze niż surowa notatka.
     * Odrzucamy też wynik podejrzanie długi względem oryginału, bo to znak, że
     * model zaczął dopisywać.
     */
    fun acceptTidied(original: String, tidied: String?): String {
        val candidate = tidied?.trim()?.trim('"', '\u201e', '\u201d')?.trim().orEmpty()
        if (candidate.isEmpty()) return original
        if (candidate.contains('\n')) return original
        if (candidate.length > original.length * 2 + 40) return original
        return candidate
    }
}
