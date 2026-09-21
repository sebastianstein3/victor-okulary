package pl.victor.app.translation

/**
 * Decyzje trybu "tłumacz ze słuchu" - bez Androida, bez sieci, bez mikrofonu.
 *
 * ## Po co osobno
 * Sam tryb to pętla: słuchaj -> przepisz -> przetłumacz -> powiedz. Pętli nie
 * da się sprawdzić bez okularów, ale WSZYSTKIE trudne decyzje w niej są czystą
 * funkcją tekstu i czasu - i te da się sprawdzić tutaj, na sucho.
 *
 * ## Skąd te reguły
 * Z aplikacji producenta (Prism, `GlassesAzureSpeechRecognizer` +
 * `TranslatorUtil`). Ich tryb tłumaczenia robi dokładnie trzy rzeczy, których
 * naiwna pętla nie robi:
 *
 *  1. **tłumaczy tylko OGON.** Rozpoznawanie ciągłe powtarza całe zdanie przy
 *     każdym uściśleniu, więc tłumaczenie całości znaczyłoby mówić w kółko to
 *     samo, coraz dłużej. Producent liczy różnicę względem poprzedniego
 *     rozpoznania (`text.startsWith(lastRecognized)` -> `substring`);
 *  2. **dławi tempo.** Nie częściej niż raz na sekundę
 *     (`currentTimeMillis - lastTranslateTime >= 1000`);
 *  3. **resetuje się, gdy rozpoznawanie zaczyna od nowa** - wtedy nowy tekst
 *     nie jest przedłużeniem starego i całość jest ogonem.
 *
 * ## Co dokładam od siebie i dlaczego
 * Producent pokazuje tłumaczenie NA EKRANIE telefonu. My mówimy je w okulary -
 * tym samym głośnikiem, obok tego samego mikrofonu, którym słuchamy. Bez
 * czwartej reguły tryb tłumaczyłby własny głos i sam się nakręcał: to jest
 * sprzężenie zwrotne, nie usterka do wyłapania na sprzęcie. Stąd [jestEchem].
 */
object EarTranslation {

    /**
     * Ile z [teraz] jest nowego względem [poprzednio].
     *
     * @return sam ogon, gdy rozpoznawanie dopisuje do poprzedniego zdania;
     *   całość, gdy zaczęło od nowa; pusty łańcuch, gdy nic nie przybyło
     */
    fun ogon(poprzednio: String, teraz: String): String {
        val a = poprzednio.trim()
        val b = teraz.trim()
        if (b.isEmpty()) return ""
        if (a.isEmpty()) return b
        // Porównanie bez wielkości liter: rozpoznawanie potrafi oddać to samo
        // zdanie raz z wielkiej, raz z małej litery, a wtedy `startsWith` na
        // surowym tekście uznałby je za zupełnie nowe i kazał powtórzyć
        // tłumaczenie całości.
        if (!b.lowercase().startsWith(a.lowercase())) return b
        return b.substring(a.length).trim()
    }

    /**
     * Czy ten fragment w ogóle warto oddawać tłumaczowi.
     *
     * Odrzuca puste i takie, w których nie ma ani jednej litery - rozpoznawanie
     * mowy oddaje czasem samą interpunkcję albo "..." i tłumaczenie tego
     * kosztuje tyle samo, co tłumaczenie zdania.
     */
    fun wartoTłumaczyć(fragment: String): Boolean =
        fragment.isNotBlank() && fragment.any { it.isLetter() }

    /**
     * Czy usłyszane zdanie to nasz własny głos, który wrócił z mikrofonu.
     *
     * Porównujemy po samych literach i cyfrach, bez wielkości i bez
     * interpunkcji: rozpoznawanie prawie nigdy nie odda syntezy znak w znak, a
     * różnica jednego przecinka nie może decydować o tym, czy wpadamy w
     * sprzężenie.
     *
     * @param ostatnioPowiedziane ostatnie zdanie, które sami wypowiedzieliśmy;
     *   `null` znaczy "jeszcze nic nie mówiliśmy"
     */
    fun jestEchem(usłyszane: String, ostatnioPowiedziane: String?): Boolean {
        val swoje = ostatnioPowiedziane?.let { rdzeń(it) } ?: return false
        if (swoje.isEmpty()) return false
        val cudze = rdzeń(usłyszane)
        if (cudze.isEmpty()) return false
        if (cudze == swoje) return true
        // ZAWIERANIE TYLKO DLA DOSTATECZNIE DŁUGICH KAWAŁKÓW.
        //
        // Mikrofon łapie zwykle KAWAŁEK naszej wypowiedzi (nasłuch zaczyna się
        // w jej połowie), więc samo porównanie na równość by echa nie złapało.
        // Ale zawieranie bez progu jest groźne w drugą stronę: gdybyśmy
        // powiedzieli krótkie "tak", każde cudze zdanie ze słowem "tak"
        // zostałoby uznane za nasze i przepadło bez tłumaczenia. Przy tym
        // progu krótka synteza jest chroniona tylko równością.
        val krótszy = minOf(cudze.length, swoje.length)
        if (krótszy < MIN_ZAWIERANIE) return false
        return swoje.contains(cudze) || cudze.contains(swoje)
    }

    /**
     * Czy minęło dość czasu od poprzedniego tłumaczenia.
     *
     * Próg z aplikacji producenta - [MIN_ODSTĘP_MS].
     */
    fun czasNaTłumaczenie(teraz: Long, ostatnieTłumaczenie: Long): Boolean =
        teraz - ostatnieTłumaczenie >= MIN_ODSTĘP_MS

    /** Czy to polecenie wyjścia z trybu, a nie zdanie do przetłumaczenia. */
    fun toKoniec(usłyszane: String): Boolean {
        val t = rdzeń(usłyszane)
        if (t.isEmpty()) return false
        // Dopasowanie do CAŁEJ wypowiedzi, nie do fragmentu. "Koniec" w zdaniu
        // "to już koniec zebrania" ma zostać przetłumaczone, a nie wyłączyć
        // tłumacza - a to jest tryb, w którym z założenia słychać cudzą mowę.
        return t in FRAZY_KOŃCA
    }

    /** Sam rdzeń tekstu: małe litery i cyfry, bez reszty. */
    private fun rdzeń(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Od ilu znaków wspólnego rdzenia wolno uznać zawieranie za echo.
     *
     * Krócej to pojedyncze słowa, które w cudzej mowie padają bez związku
     * z tym, co powiedzieliśmy.
     */
    private const val MIN_ZAWIERANIE = 8

    /** Najkrótszy odstęp między dwoma tłumaczeniami - tyle bierze producent. */
    const val MIN_ODSTĘP_MS = 1_000L

    /**
     * Wypowiedzi kończące tryb.
     *
     * Bez odmian przez przypadki, bo porównujemy CAŁĄ wypowiedź - a człowiek,
     * który chce wyjść, mówi krótko.
     */
    private val FRAZY_KOŃCA = setOf(
        "koniec tłumaczenia",
        "koniec tlumaczenia",
        "przestań tłumaczyć",
        "przestan tlumaczyc",
        "zakończ tłumaczenie",
        "zakoncz tlumaczenie",
        "wyłącz tłumacza",
        "wylacz tlumacza",
        "wyłącz tłumaczenie",
        "wylacz tlumaczenie",
        "stop translating",
        "stop translation"
    )
}
