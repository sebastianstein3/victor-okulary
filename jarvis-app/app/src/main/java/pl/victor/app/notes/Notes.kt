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
        // PRZED samym "zapisz", bo pierwszy pasujący zwrot wygrywa. Bez tego
        // "zapisz notatkę notatkę testową" (dziennik, 20:47:54) zapisywało się
        // jako "Notatkę notatkę testową" - słowo "notatkę" zostawało w treści,
        // choć należało do polecenia, nie do notatki.
        "zapisz notatkę",
        "zapisz notatke",
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
        // SAMA nazwa listy, gdy zaraz po niej idzie treść: "lista zakupów
        // mleko, bułki, chleb". Tak to zostało powiedziane w teście z
        // 15 września i poszło w całości do modelu, bo żaden zwrot otwierający
        // tego nie łapał.
        //
        // Nie kłóci się z odczytaniem listy, choć wygląda podobnie: samo "lista
        // zakupów" zostawia pustą treść, a [extract] odrzuca ją na warunku
        // [MIN_BODY] i wypowiedź leci dalej, do [isListRequest]. Kolejność w
        // AIOrchestrator (najpierw extract, potem isListRequest) jest tu
        // warunkiem poprawności.
        "lista zakupów",
        "lista zakupow",
        "na listę zakupów",
        "na liste zakupow",
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
    fun buildPromptContext(
        notes: List<Note>,
        nowMs: Long = System.currentTimeMillis(),
        noteLimit: Int = PROMPT_NOTE_LIMIT,
        charLimit: Int = PROMPT_CHAR_LIMIT
    ): String? {
        if (notes.isEmpty()) return null
        val lines = renderLines(notes, nowMs, noteLimit, charLimit)
        val shown = lines.size
        val hidden = notes.size - shown
        return buildString {
            append("=== NOTATKI UŻYTKOWNIKA ===\n")
            lines.forEach { append(it).append('\n') }
            append("To są notatki zapisane przez użytkownika, najnowsze pierwsze. ")
            append("W nawiasie kwadratowym jest data zapisania notatki. ")
            append("Odpowiadaj na ich podstawie, gdy pyta, co ma zapisane, do zrobienia ")
            append("albo do kupienia, i korzystaj z dat, gdy pyta o konkretny dzień. ")
            append("Nie wymyślaj notatek, których tu nie ma.")
            if (hidden > 0) {
                // BEZ TEGO ZDANIA OBCIĘCIE JEST GORSZE NIŻ KOSZT, KTÓRY OSZCZĘDZA.
                //
                // Model, który dostaje sześćdziesiąt notatek z dwustu i nie wie o
                // tym, odpowiada na pytanie o starszą z pełnym przekonaniem:
                // "nie masz nic takiego zapisanego". Notatka istnieje, a
                // użytkownik dostaje zaprzeczenie - i nie ma jak się domyślić,
                // że pyta o coś, czego asystent po prostu nie widzi.
                append(" WIDZISZ $shown NAJNOWSZYCH Z ${notes.size} NOTATEK. ")
                append("Gdy pytanie dotyczy czegoś, czego tu nie ma, NIE twierdź, ")
                append("że użytkownik tego nie zapisał - powiedz, że widzisz tylko ")
                append("najnowsze notatki i poproś o doprecyzowanie albo o ")
                append("sprawdzenie listy w aplikacji.")
            }
        }
    }

    /**
     * Wybiera notatki, które zmieszczą się w poleceniu, i składa je w wiersze.
     *
     * ## Czemu w ogóle obcinamy
     * Bo lista notatek nie miała ŻADNEGO ograniczenia: każda notatka szła do
     * modelu przy każdym pytaniu, które ich dotknęło. Przy kilku to nic nie
     * kosztuje i tak jest dziś. Przy trzystu koszt rośnie liniowo, ale gorsze
     * jest co innego - jakość siada wcześniej niż cena, bo model ma znaleźć tę
     * jedną właściwą notatkę wśród trzystu. Model lokalny obrywa jeszcze
     * wcześniej: ma własny budżet znaków i po cichu przycina kontekst.
     *
     * ## Dwa limity, nie jeden
     * Liczba notatek nie mówi nic o ich długości - jedna notatka na trzy tysiące
     * znaków przepełniłaby polecenie mimo limitu "sześćdziesiąt sztuk". Dlatego
     * kończymy na tym z dwóch progów, który wypadnie pierwszy.
     *
     * Jedna notatka wchodzi ZAWSZE, nawet gdy sama przekracza limit znaków:
     * pusty wybór przy niepustej liście notatek byłby gorszy od przekroczenia
     * budżetu o kilkaset znaków.
     */
    private fun renderLines(
        notes: List<Note>,
        nowMs: Long,
        noteLimit: Int,
        charLimit: Int
    ): List<String> {
        val lines = mutableListOf<String>()
        var used = 0
        for (note in notes.take(noteLimit.coerceAtLeast(1))) {
            val line = buildString {
                append("- ")
                if (note.createdAtMs > 0L) {
                    append('[').append(stamp(note.createdAtMs, nowMs)).append("] ")
                }
                append(note.text)
            }
            if (lines.isNotEmpty() && used + line.length > charLimit) break
            lines += line
            used += line.length + 1
        }
        return lines
    }

    /**
     * Ile notatek najwyżej trafia do polecenia.
     *
     * Hojnie: dziś użytkownik ma ich kilka, więc ten próg nigdy nie zadziała, a
     * przy kilkuset uchroni przed cichym rozdęciem polecenia. Gdy notatek
     * będzie regularnie więcej, właściwą odpowiedzią jest WYBÓR trafnych
     * (TF-IDF, ten sam co w [pl.victor.app.memory.LongTermMemory]), a nie
     * podnoszenie tej liczby.
     */
    const val PROMPT_NOTE_LIMIT = 60

    /** Ile znaków najwyżej zajmą same notatki - patrz [renderLines]. */
    const val PROMPT_CHAR_LIMIT = 6_000

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

        /** Materiałem jest to, co widzą okulary - "zrób notatkę z tego co widzisz". */
        SIGHT,

        /**
         * Wskazanie, którego SAMA WYPOWIEDŹ NIE ROZSTRZYGA - "zrób notatkę o tym
         * zamku", "zanotuj to".
         *
         * ## Czemu to jest osobna wartość, a nie domyślne zgadnięcie
         * Bo "o tym" znaczy co innego w zależności od tego, co się przed chwilą
         * działo, a tej wiedzy nie ma w zdaniu. Wcześniej wszystkie takie zwroty
         * szły na sztywno do [SIGHT] - czyli po zdjęcie. Skutek widać było na
         * zgłoszeniu: asystent opowiadał o zamku w Bodrum, użytkownik prosił
         * "zrób notatkę o tym zamku", a aplikacja szła fotografować pokój.
         *
         * Rozstrzyga wołający, bo tylko on wie, czy jest o czym pisać: gdy
         * przed chwilą padła odpowiedź, materiałem jest ONA, a dopiero gdy jej
         * nie ma - obraz z okularów.
         */
        RECENT
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
     * Zwroty odsyłające do TEGO, NA CO UŻYTKOWNIK PATRZY - i tylko takie.
     *
     * Zostały tu wyłącznie te, które mówią o PATRZENIU wprost. "o tym" i "o
     * tej" stały tu wcześniej i to był błąd: po nich idzie rzeczownik ("o tym
     * zamku"), ale nic w tym zdaniu nie mówi, czy zamek stoi przed
     * użytkownikiem, czy asystent właśnie o nim opowiadał. Przeniesione do
     * [RECENT_REFERENCES].
     */
    private val SIGHT_REFERENCES = listOf(
        "co widzisz",
        "co tu widać",
        "co tu widac",
        "z tego co widzisz",
        "z tego widoku",
        "z tego zdjęcia",
        "z tego zdjecia"
    ).sortedByDescending { it.length }

    /**
     * Wskazania, po których MOŻE iść treść - "o tym zamku", "o tej tablicy".
     *
     * Rozstrzyga je dopiero kontekst rozmowy, patrz [Source.RECENT].
     */
    private val RECENT_REFERENCES = listOf(
        "o tym",
        "o tej",
        "o tych"
    ).sortedByDescending { it.length }

    /**
     * Wskazania GOŁE - takie, po których nie ma już nic.
     *
     * "zanotuj to", "zapisz to w notatkach", "dodaj to do notatek". Wszystkie
     * trzy nie działały: samo "to" nie było nigdzie odsyłaczem, więc do
     * notatnika trafiał dosłowny tekst "To w notatkach" albo nie działo się nic.
     *
     * Sprawdzane inaczej niż [RECENT_REFERENCES] i to jest tu warunek
     * poprawności: po gołym wskazaniu NIE MOŻE nic stać. Inaczej "zapisz to
     * mleko" przestałoby być notatką o mleku, a stało się prośbą o wymyślenie
     * treści.
     */
    private val BARE_REFERENCES = setOf("to", "tego", "tym", "tamto")

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
        val trimmed = dropFillerPronoun(text.trim())
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
            // SAMO "zrób notatkę", bez tematu.
            //
            // Dotąd nie działo się NIC: treści brak, więc zwykła ścieżka też
            // odpadała na warunku minimalnej długości. Prośba wyraźnie nazywa
            // notatkę, więc odmowa jest tu ostatnią rzeczą, jakiej się
            // spodziewać - a "o czym" wynika z tego, co się przed chwilą działo.
            if (rest.isEmpty() && prefix.namesANote()) {
                return Described(Source.RECENT, null)
            }
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
            //
            // Dopuszczamy jeden łącznik przed rzeczownikiem, bo "wpisz to JAK
            // notatkę" i "zapisz to JAKO notatkę" to ten sam szyk z jednym
            // słowem więcej - a bez tego wypowiedź szła do modelu. Warunek
            // pozostaje ścisły: po łączniku dalej musi stać samo słowo
            // notatkowe, więc "zrób z tego jak zdjęcie" nadal nie przejdzie.
            val tail = stripNoteJoiner(
                afterVerb.substring(reference.length)
                    .trim { it in SEPARATORS || it in ".!?" }
                    .lowercase()
            )
            if (tail !in NOTE_NOUNS) return@let
            val source = when (reference) {
                in LAST_ANSWER_REFERENCES -> Source.LAST_ANSWER
                in SIGHT_REFERENCES -> Source.SIGHT
                else -> Source.RECENT
            }
            return Described(source, afterVerb)
        }

        // SZYK TRZECI: "wpisz informacje O TYM ZAMKU w notatkach" - słowo
        // notatkowe zamyka zdanie, a odsyłacz siedzi w środku treści.
        //
        // Tutaj, inaczej niż wyżej, odsyłacza szukamy W CAŁEJ treści, nie tylko
        // na jej początku. Wolno na to pozwolić właśnie dlatego, że zdanie musi
        // kończyć się słowem notatkowym - bez tego warunku "zapisz, że o tym
        // zapomniałem" stałoby się prośbą o zdjęcie.
        NOTE_ENDINGS.firstOrNull { lower.endsWith(it) }?.let { ending ->
            val core = trimmed.dropLast(ending.length).trim().trimEnd(',', '-')
            val coreLower = core.lowercase()
            val verb = NOTE_VERBS.firstOrNull { startsWithPrefix(coreLower, it) }
                ?: PREFIXES.firstOrNull { startsWithPrefix(coreLower, it) }
                ?: return@let
            val body = core.substring(verb.length).trimStart { it in SEPARATORS }.trim()
            if (body.isEmpty()) return@let
            sourceAnywhereIn(body)?.let { return Described(it, body) }
        }

        return null
    }

    /**
     * Do którego źródła odsyła treść - odsyłacz może stać GDZIEKOLWIEK.
     *
     * Tylko dla szyku trzeciego, gdzie zdanie kończy się słowem notatkowym.
     * W pozostałych szykach odsyłacz musi otwierać treść, bo bez tego warunku
     * zwykłe notatki zaczęłyby trafiać do modelu.
     */
    private fun sourceAnywhereIn(text: String): Source? {
        val lower = text.lowercase()
        REFERENCE_SOURCES.firstOrNull { containsReference(lower, it.first) }
            ?.let { return it.second }
        // Gołe wskazanie liczy się tylko wtedy, gdy JEST CAŁĄ treścią - patrz
        // [BARE_REFERENCES]. To jest ta różnica między "zapisz to" (wskazanie) a
        // "zapisz to mleko" (zwykła notatka o mleku).
        if (lower.trim() in BARE_REFERENCES) return Source.RECENT
        return null
    }

    /** Czy odsyłacz występuje w tekście jako osobne słowa, nie jako fragment. */
    private fun containsReference(lower: String, reference: String): Boolean {
        var from = 0
        while (true) {
            val at = lower.indexOf(reference, from)
            if (at < 0) return false
            val beforeOk = at == 0 || lower[at - 1] in SEPARATORS
            val afterAt = at + reference.length
            val afterOk = afterAt == lower.length || lower[afterAt] in SEPARATORS
            if (beforeOk && afterOk) return true
            from = at + 1
        }
    }

    /** Do którego źródła odsyła treść, albo `null` gdy do żadnego. */
    private fun sourceOf(rest: String): Source? {
        if (rest.isEmpty()) return null
        val lower = rest.lowercase()
        REFERENCE_SOURCES.firstOrNull { startsWithReference(lower, it.first) }
            ?.let { return it.second }
        if (lower.trim() in BARE_REFERENCES) return Source.RECENT
        return null
    }

    /**
     * Wszystkie zwroty odsyłające razem, OD NAJDŁUŻSZEGO, każdy ze swoim źródłem.
     *
     * ## Czemu razem, a nie lista po liście
     * Bo sprawdzanie kolejno "najpierw rozmowa, potem wzrok" gubi dłuższe
     * dopasowanie: "z tego co widzisz" zaczyna się od "z tego", więc wygrywała
     * rozmowa i prośba o notatkę Z OBRAZU szła po ostatnią odpowiedź. Znalazł to
     * test dopisany przy okazji zupełnie innej poprawki.
     *
     * Przy jednej liście posortowanej po długości wygrywa zwrot BARDZIEJ
     * SZCZEGÓŁOWY, niezależnie od tego, do którego źródła należy.
     */
    private val REFERENCE_SOURCES: List<Pair<String, Source>> = (
        LAST_ANSWER_REFERENCES.map { it to Source.LAST_ANSWER } +
            SIGHT_REFERENCES.map { it to Source.SIGHT } +
            RECENT_REFERENCES.map { it to Source.RECENT }
        ).sortedByDescending { it.first.length }

    /**
     * Wyłuskuje PYTANIE z wypowiedzi, która kończy się prośbą o notatkę.
     *
     * ## Czego nie łapał żaden z trzech szyków
     * Wszystkie zakładają, że prośba o notatkę OTWIERA wypowiedź. Tymczasem
     * najbardziej naturalne jest powiedzieć jedno i drugie naraz:
     *
     *     "opowiedz mi o zamku w Bodrum i zrób z tego notatkę"
     *
     * Dotąd nie działo się nic: prośba szła w całości do modelu, model - zgodnie
     * z tym, co ma napisane w poleceniu - tłumaczył, że tej notatki nie zapisał,
     * i podawał formułę "Notatka: ...". Wyglądało to na upór aplikacji, a było
     * brakiem jednego wzorca.
     *
     * ## Czemu to zwraca PYTANIE, a nie źródło materiału
     * Bo materiału jeszcze NIE MA - powstanie dopiero z odpowiedzi na to samo
     * pytanie. Wołający ma więc zadać pytanie normalnie i zapisać odpowiedź;
     * służy do tego istniejące `saveAsNote`. [describeRequest] odpowiada na inne
     * pytanie: "z czego zrobić notatkę, skoro materiał już jest".
     *
     * Bierzemy tylko takie zakończenia, które ODSYŁAJĄ ("zrób z tego notatkę",
     * "zanotuj to"). Zakończenie z własną treścią ("...i zapisz, że mam kupić
     * mleko") to dwie osobne prośby i tego tu nie rozstrzygamy - lepiej nie
     * ruszyć, niż zrobić połowę.
     *
     * @return pytanie bez końcówki o notatce, albo `null`
     */
    fun trailingNoteRequest(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < MIN_HEAD) return null
        // Kalendarz ma pierwszeństwo tak samo jak w [extract] i [describeRequest].
        if (CALENDAR_WORDS.any { trimmed.lowercase().contains(it) }) return null
        val lower = trimmed.lowercase()

        // Wszystkie miejsca podziału naraz, OD KOŃCA: prośba o notatkę stoi na
        // końcu, ale przed nią samo pytanie może mieć jeszcze kilka spójników
        // ("opowiedz o zamku i o mieście i zrób z tego notatkę"). Branie tylko
        // ostatniego wystąpienia jednego spójnika gubiłoby te przypadki.
        val splits = TRAILING_JOINERS
            .flatMap { joiner -> occurrencesOf(lower, joiner).map { it to joiner.length } }
            .sortedByDescending { it.first }

        splits.forEach { (at, length) ->
            val head = stripDanglingJoiner(trimmed.substring(0, at).trim().trimEnd(',', '-', ';'))
            val tail = trimmed.substring(at + length).trim()
            if (head.length < MIN_HEAD || tail.isEmpty()) return@forEach
            // Sam początek też nie może być prośbą o notatkę - inaczej
            // "zapisz X i zanotuj to" rozjechałoby się na dwie notatki.
            if (describeRequest(head) != null || extract(head) != null) return@forEach
            if (describeRequest(tail) != null) return head
        }
        return null
    }

    /**
     * Zdejmuje spójnik zwisający na końcu pytania.
     *
     * Bierze się stąd, że podziałów szukamy OD KOŃCA, a "a potem" zawiera
     * "potem": krótszy spójnik stoi dalej, więc wygrywa, i w pytaniu zostaje
     * samotne "a" ("jak zrobić pizzę a"). Do modelu poszłoby wtedy pytanie
     * urwane w pół słowa.
     */
    private fun stripDanglingJoiner(head: String): String {
        val lower = head.lowercase()
        DANGLING_JOINERS.forEach { word ->
            if (lower.endsWith(" $word")) {
                return head.dropLast(word.length + 1).trim().trimEnd(',', '-', ';')
            }
        }
        return head
    }

    private val DANGLING_JOINERS = listOf("a", "i", "oraz")

    private fun occurrencesOf(lower: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = lower.indexOf(needle, from)
            if (at < 0) return out
            out.add(at)
            from = at + 1
        }
    }

    /**
     * Spójniki, po których może iść dołożona prośba o notatkę.
     *
     * Ze spacjami po obu stronach, żeby "i" nie łapało się w środku wyrazu.
     * Szukamy OSTATNIEGO wystąpienia, bo prośba o notatkę stoi na końcu, a
     * przed nią może być jeszcze kilka spójników w samym pytaniu.
     */
    private val TRAILING_JOINERS = listOf(
        " a potem ", " a następnie ", " a nastepnie ",
        " i potem ", " i następnie ", " i nastepnie ",
        " następnie ", " nastepnie ", " potem ",
        " oraz ", " i "
    ).sortedByDescending { it.length }

    /** Krótszy początek to nie pytanie, tylko urwane słowo. */
    private const val MIN_HEAD = 6

    /**
     * Czy ten zwrot otwierający NAZYWA notatkę wprost.
     *
     * "zrób notatkę" i "notatka" tak, "zapisz" i "dodaj" nie - te drugie same z
     * siebie nie mówią, o co chodzi, i bez treści są po prostu urwanym zdaniem.
     */
    private fun String.namesANote(): Boolean = contains("notat") || contains("notk")

    /**
     * Wyrzuca zaimek wstawiony między czasownik a resztę prośby.
     *
     * "Zrób MI notatkę o tym zamku" nie działało w ogóle - ani jako prośba o
     * notatkę pisaną przez model, ani jako zwykła notatka - bo wszystkie wzorce
     * zakładają, że po czasowniku idzie od razu rzeczownik. A "mi" i "sobie"
     * wchodzą tam w mowie bez przerwy i nie wnoszą nic poza uprzejmością.
     */
    private fun dropFillerPronoun(text: String): String =
        FILLER_PRONOUN_REGEX.replace(text) { match -> match.groupValues[1] + " " }

    private val FILLER_PRONOUN_REGEX = Regex(
        """^(zr[oó]b|zapisz|zanotuj|dodaj|dopisz|wpisz|stw[oó]rz|sporz[aą]d[źz])\s+""" +
            """(?:mi|sobie|nam|mu|jej)\s+""",
        RegexOption.IGNORE_CASE
    )

    /** Czasowniki, po których może stać odsyłacz, a dopiero potem "notatkę". */
    private val NOTE_VERBS = listOf(
        "zrób", "zrob", "zapisz", "zanotuj", "sporządź", "sporzadz", "stwórz", "stworz",
        "wpisz", "dopisz", "dodaj"
    ).sortedByDescending { it.length }

    /**
     * Zakończenia, po których wiadomo, że chodziło o notatkę.
     *
     * Trzeci naturalny szyk: "wpisz informacje o tym zamku W NOTATKACH". Słowo
     * notatkowe stoi na KOŃCU, a nie na początku ani w środku - i tego nie
     * łapał żaden z dwóch poprzednich wzorców. Zgłoszone dokładnie tym zdaniem.
     */
    private val NOTE_ENDINGS = listOf(
        "w notatkach", "do notatek", "w notatniku", "w notatce",
        "jako notatkę", "jako notatke", "jako notatka"
    ).sortedByDescending { it.length }

    /**
     * Łączniki, które po polsku wchodzą między odsyłacz a słowo "notatka":
     * "wpisz to JAK notatkę", "zapisz to JAKO notatkę", "zrób z tego W FORMIE
     * notatki".
     */
    private val NOTE_JOINERS = listOf("jako", "jak", "w formie", "w postaci")
        .sortedByDescending { it.length }

    /** Zdejmuje jeden łącznik z początku, jeśli tam stoi. */
    private fun stripNoteJoiner(tail: String): String {
        val joiner = NOTE_JOINERS.firstOrNull {
            tail == it || tail.startsWith("$it ")
        } ?: return tail
        return tail.substring(joiner.length).trimStart()
    }

    /** Rzeczowniki zamykające szyk drugi - patrz [describeRequest]. */
    private val NOTE_NOUNS = setOf(
        "notatkę", "notatke", "notatka", "notatki", "notkę", "notke", "notka"
    )

    private val ALL_REFERENCES =
        (LAST_ANSWER_REFERENCES + SIGHT_REFERENCES + RECENT_REFERENCES + BARE_REFERENCES)
            .sortedByDescending { it.length }

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
