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
    fun startsEarTranslation(text: String): Boolean =
        EAR_TRANSLATION_START_REGEX.matches(text.lowercase().trim().trimEnd('.', '!', '?'))

    private val EAR_TRANSLATION_START_REGEX = Regex(
        """^(w[lł][aą]cz\s+)?t[lł]umacz(enie|:)?\s*(ze\s+s[lł]uchu|na\s+[zż]ywo|symultaniczn[ey]|""" +
            """co\s+m[oó]wi[aą]|rozmow[eę])$|""" +
            """^(w[lł][aą]cz|uruchom)\s+t[lł]umacza(\s+(ze\s+s[lł]uchu|na\s+[zż]ywo))?$|""" +
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
