package pl.victor.app.conversation

/**
 * Komendy sterujące samą rozmową - zmiana persony, reset kontekstu - rozpoznawane
 * PRZED wykryciem akcji i PRZED wysłaniem czegokolwiek do AI.
 *
 * Wydzielone jako czyste funkcje (bez Androida), tak jak [pl.victor.app.proactive.CalendarContext]
 * i [pl.victor.app.ble.GlassesProtocol] - łatwiej to przetestować i nie ma pokusy
 * wsadzenia tego do rozdętego `AIOrchestrator`.
 */
object MetaCommands {

    /**
     * Wykrywa prośbę o zmianę persony - "bądź Sterna", "przełącz się na Profesora",
     * "zmień osobowość na minimalistę".
     *
     * @return ID persony z [pl.victor.app.persona.PersonaRegistry] albo `null`, gdy to
     *         nie jest komenda zmiany persony ALBO nazwa nie pasuje do żadnej znanej -
     *         w drugim przypadku wołający powinien i tak nie przepuszczać frazy dalej
     *         do AI, tylko przeczytać listę dostępnych person (patrz [detectPersonaSwitchAttempt]).
     */
    fun detectPersonaSwitch(text: String): String? {
        val name = extractRequestedName(text) ?: return null
        return resolvePersonaAlias(name)
    }

    /**
     * Jak [detectPersonaSwitch], ale zwraca też przypadek "user chciał zmienić personę,
     * ale nazwy nie rozpoznaliśmy" - odróżnia to od "to w ogóle nie była komenda zmiany
     * persony", żeby dało się odpowiedzieć czymś sensowniejszym niż cisza.
     */
    fun detectPersonaSwitchAttempt(text: String): PersonaSwitchAttempt? {
        val name = extractRequestedName(text) ?: return null
        val personaId = resolvePersonaAlias(name)
        return if (personaId != null) {
            PersonaSwitchAttempt.Recognized(personaId)
        } else {
            PersonaSwitchAttempt.Unrecognized(name)
        }
    }

    private fun extractRequestedName(text: String): String? {
        val match = SWITCH_REGEX.find(text.trim()) ?: return null
        return match.groupValues[1].trim().trimEnd('.', '!', '?', ',')
    }

    private fun resolvePersonaAlias(rawName: String): String? {
        val normalized = rawName.lowercase().trim()
        ALIASES[normalized]?.let { return it }
        // Fraza "asystent niewidomych/niewidomy/niewidoma" odmienia się - dopasuj
        // po rdzeniu zamiast wymieniać każdą formę z osobna.
        if ("niewidom" in normalized) return "asystent_niewidomych"
        // Pojedyncze słowo z odmianą (np. "sternę", "profesora") - spróbuj dopasować
        // po prefiksie do najkrótszego znanego aliasu.
        return ALIASES.entries.firstOrNull { (alias, _) ->
            alias.length >= 4 && normalized.startsWith(alias.take(alias.length - 1))
        }?.value
    }

    private val SWITCH_REGEX = Regex(
        """(?:b[aą]dź|badz|przel[aą]cz(?:\s+si[eę])?\s+na|zmień\s+osobowo[sś][cć]\s+na|""" +
            """zmien\s+osobowosc\s+na|w[lł][aą]cz\s+personę|włącz\s+osobowość)\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    private val ALIASES: Map<String, String> = mapOf(
        "asystent" to "default", "asystenta" to "default",
        "domyślny" to "default", "domyslny" to "default", "domyślną" to "default",
        "sterna" to "sternik", "sternę" to "sternik", "sternie" to "sternik",
        "sternik" to "sternik", "wojskowy" to "sternik", "wojskową" to "sternik",
        "przyjaciel" to "przyjaciel", "przyjaciela" to "przyjaciel", "kumpel" to "przyjaciel",
        "kompan" to "suchar", "kompana" to "suchar", "suchary" to "suchar", "suchara" to "suchar",
        "żarty" to "suchar", "zarty" to "suchar",
        "minimalista" to "minimalista", "minimalistę" to "minimalista", "minimalisty" to "minimalista",
        "profesor" to "profesor", "profesora" to "profesor", "nauczyciel" to "profesor",
        "sarkastyk" to "sarkazm", "sarkastyka" to "sarkazm", "sarkazm" to "sarkazm",
        "opiekun" to "opiekun", "opiekuna" to "opiekun",
        "tłumacz" to "tlumacz", "tlumacz" to "tlumacz", "tłumacza" to "tlumacz"
    )

    /**
     * Wykrywa prośbę o wyczyszczenie kontekstu rozmowy - "nowy temat", "zapomnij co
     * mówiliśmy", "wyczyść kontekst".
     */
    fun detectContextReset(text: String): Boolean = RESET_REGEX.containsMatchIn(text.trim())

    /**
     * Czy to jest prośba o zakończenie trwającego trybu ciągłego.
     *
     * ## Czemu to musi być sprawdzane LOKALNIE
     * Tryby dostępności chodzą w pętli i pytają model kilkadziesiąt razy na
     * minutę. Zatrzymanie ich szło dotąd jedynie przez model, a wzorce zapasowe
     * uruchamiają się wyłącznie wtedy, gdy AI jest niedostępne - czyli gdy sieć
     * padła, pętli nie dało się wyłączyć głosem WCALE.
     *
     * ## Wolno być szerokim, bo bramkuje to stan trybu
     * Wołający sprawdza to DOPIERO wtedy, gdy jakiś tryb faktycznie chodzi.
     * Poza trybem te same słowa idą do modelu jak zwykłe zdanie, więc szeroka
     * lista nie przechwytuje rozmowy.
     *
     * Lista obejmuje oba zdania, których uczy katalog komend ("dziękuję,
     * wystarczy", "przestań czytać") - to one padną najpierw, a wcześniej nie
     * znał ich żaden lokalny wzorzec.
     *
     * `containsMatchIn`, nie `matches`: człowiek, któremu okulary właśnie czytają
     * do ucha, rzadko mówi samo hasło - mówi "dobra, dziękuję, wystarczy".
     */
    fun stopsAccessibility(text: String): Boolean =
        ACCESSIBILITY_STOP_REGEX.containsMatchIn(text.lowercase().trim())

    private val ACCESSIBILITY_STOP_REGEX = Regex(
        """(wystarczy|przesta[nń]|sko[nń]cz|zako[nń]cz|dosy[cć]|dzi[eę]kuj[eę])|""" +
            """(stop|wy[lł][aą]cz|zatrzymaj)\s+(czytani|opis|tryb|nawigacj)"""
    )

    /**
     * Czy to prośba o WŁĄCZENIE tłumaczenia ze słuchu.
     *
     * ## Czemu to jest komenda META, a nie akcja warstwy 0
     * Bo nie uruchamia niczego przez Intent i nie kończy się jedną
     * odpowiedzią - przestawia aplikację w tryb, który trwa. Akcje w warstwie 0
     * mają inny kształt (jedna czynność, jeden wynik) i wciśnięcie tego tam
     * zmusiłoby do przemycania stanu trybu przez `Action`.
     *
     * ## Czemu `matches`, a nie `containsMatchIn`
     * Tu jest odwrotnie niż przy [stopsAccessibility]: tamto bramkuje stan
     * trybu, więc wolno mu być szerokim. To nie ma czego bramkować - działa
     * w zwykłej rozmowie. "Przetłumacz mi to zdanie" ma pójść do modelu jako
     * pytanie, a nie włączać tryb ciągły, więc wzorzec musi objąć CAŁĄ
     * wypowiedź.
     *
     * Osobno od `przetłumacz` z [pl.victor.app.actions.SmartActionDetector.detectGesture]
     * (to tłumaczy NAPIS z kamery) - te dwie rzeczy już raz się pomyliły.
     */
    fun startsEarTranslation(text: String): Boolean {
        val bare = earBare(text)
        return EAR_TRANSLATION_START_REGEX.matches(bare) || earTranslationLanguages(text) != null
    }

    /**
     * Języki podane w samej komendzie: "tłumacz z polskiego na angielski".
     *
     * Dziennik z biegu 153: to zdanie poszło do modelu, który odpowiedział
     * "podaj mi tekst po polsku" - a człowiek chciał włączyć tryb, i to od
     * razu z tymi językami, bez przestawiania ich w Ustawieniach. Wzorzec
     * obejmuje CAŁĄ wypowiedź, więc "przetłumacz 'dzień dobry' na angielski"
     * (jednorazowe tłumaczenie) dalej idzie do modelu.
     *
     * @return para (z, na) w kodach języków albo `null`, gdy to nie ta komenda
     *   albo któregoś języka nie znamy
     */
    fun earTranslationLanguages(text: String): Pair<String, String>? {
        val m = EAR_TRANSLATION_WITH_LANGUAGES.matchEntire(earBare(text)) ?: return null
        val z = languageCode(m.groupValues[1]) ?: return null
        val na = languageCode(m.groupValues[2]) ?: return null
        return if (z == na) null else z to na
    }

    /**
     * Tryb przewodnika: `true` = włącz, `false` = wyłącz, `null` = to nie ta komenda.
     *
     * Cała wypowiedź, jak przy tłumaczeniu - "kto był przewodnikiem tej
     * wycieczki" ma iść do modelu, a nie włączać tryb.
     */
    fun guideCommand(text: String): Boolean? {
        val bare = earBare(text)
        return when {
            GUIDE_STOP.matches(bare) -> false
            GUIDE_START.matches(bare) -> true
            else -> null
        }
    }

    private val GUIDE_START = Regex(
        """^((w[lł][aą]cz(y[cć])?|uruchom(i[cć])?|start(uj)?)\s+)?(tryb\s+)?przewodnik(a)?(\s+po\s+okolicy)?$|""" +
            """^oprowad[zź]\s+mnie(\s+po\s+okolicy)?$|^b[aą]d[zź]\s+moim\s+przewodnikiem$"""
    )
    private val GUIDE_STOP = Regex(
        """^(wy[lł][aą]cz(y[cć])?|zatrzymaj|zako[nń]cz|koniec|stop)\s+(trybu?\s+)?przewodnik(a|iem)?$"""
    )

    /**
     * Rozmowa w dwie strony: kod języka rozmówcy, "" = z ustawień, `null` = to
     * nie ta komenda.
     *
     * Sprawdzane PRZED [startsEarTranslation] - "tłumacz rozmowę" pasuje też
     * do jednostronnego tłumaczenia ze słuchu.
     */
    fun twoWayCommand(text: String): String? {
        val m = TWO_WAY.matchEntire(earBare(text)) ?: return null
        val słowo = m.groups["jezyk"]?.value ?: return ""
        return languageCode(słowo) ?: ""
    }

    private val TWO_WAY = Regex(
        """^((w[lł][aą]cz|uruchom|zacznij)\s+)?(t[lł]umacz(enie)?\s+rozmow[ęeyay]|tryb\s+rozmowy|""" +
            """rozmow[aęy]\s+z\s+t[lł]umaczem|t[lł]umacz(enie)?\s+w\s+dwie\s+strony|""" +
            """pom[oó][zż]\s+mi\s+rozmawia[cć])(\s+(z|ze|po)\s+(?<jezyk>\S+))?$"""
    )

    /**
     * Notatki ze spotkania: `true` = zacznij nagrywać, `false` = zakończ,
     * `null` = to nie ta komenda.
     */
    fun meetingCommand(text: String): Boolean? {
        val bare = earBare(text)
        return when {
            MEETING_STOP.matches(bare) -> false
            MEETING_START.matches(bare) -> true
            else -> null
        }
    }

    private val MEETING_START = Regex(
        """^((nagrywaj|nagraj|zacznij\s+nagrywa[cć]|rozpocznij|w[lł][aą]cz|zacznij|r[oó]b)\s+)?""" +
            """(nagrywanie\s+)?(spotkanie|notatki\s+ze\s+spotkania|protok[oó][lł](\s+ze\s+spotkania)?)$|""" +
            """^nagrywaj\s+(to\s+)?spotkanie$"""
    )
    private val MEETING_STOP = Regex(
        """^(zako[nń]cz|koniec|zatrzymaj|stop|sko[nń]cz|przerwij)\s+(nagrywani[ae]\s+)?""" +
            """(spotkani[ae]|nagrywani[ae]|protok[oó][lł]u?|notatk[ię]\s+ze\s+spotkania)$"""
    )

    /** Kod języka z polskiej nazwy w dowolnym przypadku ("polskiego", "angielski"). */
    fun languageCode(word: String): String? {
        val w = word.lowercase().trim()
        return LANGUAGE_STEMS.firstOrNull { (stem, _) -> w.startsWith(stem) }?.second
    }

    // GRZECZNOŚĆ NIE MOŻE ZMIENIAĆ KOMENDY.
    //
    // Dziennik z 30 września: "a możesz włączyć tłumaczenie na żywo" nie
    // pasowało do żadnego wzorca, poszło do modelu, a model odpowiedział,
    // że takiej funkcji nie ma. Wzorzec zostaje "cała wypowiedź", ale bez
    // wstępu i dopisku, które nic nie zmieniają.
    private fun earBare(text: String): String =
        text.lowercase().replace(',', ' ').replace(Regex("\\s+"), " ")
            .trim().trimEnd('.', '!', '?')
            .replace(EAR_POLITE_PREFIX, "")
            .replace(EAR_POLITE_SUFFIX, "")
            .trim()

    private val EAR_TRANSLATION_WITH_LANGUAGES = Regex(
        """^(?:(?:w[lł][aą]cz(?:y[cć])?|uruchom(?:i[cć])?)\s+)?t[lł]umacz(?:enie|a)?""" +
            """(?:\s+(?:na\s+[zż]ywo|ze\s+s[lł]uchu))?\s+ze?\s+(\S+)\s+na\s+(\S+)""" +
            """(?:\s+(?:na\s+[zż]ywo|ze\s+s[lł]uchu))?$"""
    )

    /** Rdzenie polskich nazw języków - pasują do każdej odmiany. */
    private val LANGUAGE_STEMS = listOf(
        "pols" to "pl", "angiel" to "en", "niemie" to "de", "francu" to "fr",
        "hiszpa" to "es", "włos" to "it", "wlos" to "it", "portugal" to "pt",
        "rosyj" to "ru", "ukrai" to "uk", "japo" to "ja", "korea" to "ko",
        "chiń" to "zh", "chin" to "zh", "arab" to "ar", "czes" to "cs",
        "słowac" to "sk", "slowac" to "sk", "holend" to "nl", "niderl" to "nl",
        "szwedz" to "sv"
    )

    private val EAR_POLITE_PREFIX = Regex(
        """^(a\s+|no\s+|to\s+)?((czy\s+)?(mo[zż]esz|m[oó]g[lł]by[sś]|mo[zż]na)\s+)?(prosz[eę]\s+)?"""
    )
    private val EAR_POLITE_SUFFIX = Regex("""\s+(prosz[eę]|dla\s+mnie)$""")

    private val EAR_TRANSLATION_START_REGEX = Regex(
        """^(w[lł][aą]cz(y[cć])?\s+)?t[lł]umacz(enie|:)?\s*(ze\s+s[lł]uchu|na\s+[zż]ywo|symultaniczn[ey]|""" +
            """co\s+m[oó]wi[aą]|rozmow[eę])$|""" +
            """^(w[lł][aą]cz(y[cć])?|uruchom(i[cć])?)\s+t[lł]umacza(\s+(ze\s+s[lł]uchu|na\s+[zż]ywo))?$|""" +
            """^t[lł]umacz\s+mi\s+(na\s+bie[zż][aą]co|wszystko|co\s+s[lł]ysz[eę])$"""
    )

    private val RESET_REGEX = Regex(
        """(?:nowy\s+temat|zapomnij\s+(?:co\s+)?(?:m[oó]wili[sś]my|rozmawiali[sś]my)|""" +
            """wyczy[sś][cć]\s+(?:kontekst|rozmow[eę]|histori[eę])|""" +
            """zacznij(?:my)?\s+od\s+nowa|nowa\s+rozmowa)""",
        RegexOption.IGNORE_CASE
    )
}

/** Wynik próby rozpoznania komendy zmiany persony. */
sealed class PersonaSwitchAttempt {
    data class Recognized(val personaId: String) : PersonaSwitchAttempt()
    data class Unrecognized(val requestedName: String) : PersonaSwitchAttempt()
}
