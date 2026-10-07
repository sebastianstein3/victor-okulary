package pl.victor.app.ai

/**
 * Mówi modelowi wprost, czy w tej rozmowie ma dostęp do internetu.
 *
 * ## Po co
 * Dziennik z 18:29-18:32: "znajdź najtańszą ofertę na cytrynówkę" - Gemini ma
 * włączone wyszukiwanie Google, a mimo to trzy razy odpowiedział "nie mam
 * możliwości sprawdzania cen", za każdym razem ze znacznikiem `web_search`.
 * Ten znacznik otwiera jedynie wyniki w przeglądarce telefonu, który leży w
 * kieszeni - model nie dostaje z niego NIC z powrotem. Tak samo pogoda w
 * Bodrum: "mam tylko prognozę dla Torunia".
 *
 * Model nie wiedział, że wyszukiwarkę ma u siebie, a znał za to znacznik o
 * nazwie, która brzmi jak właściwa droga. Stąd dwie wersje tego zdania:
 * jedna każe szukać samemu, druga - dla dostawcy bez wyszukiwania - każe
 * się przyznać, zamiast udawać, że szukanie trwa.
 */
object WebSearchPrompt {

    fun dlaModelu(maWyszukiwarkę: Boolean): String = if (maWyszukiwarkę) {
        "\n\nINTERNET: masz w tej rozmowie wbudowaną wyszukiwarkę Google. Gdy " +
            "odpowiedź zależy od aktualnych danych - ceny, oferty sklepów, " +
            "pogoda w innym miejscu niż podana wyżej prognoza, wiadomości, " +
            "wyniki, godziny otwarcia, rozkłady, opinie i oceny miejsc - WYSZUKAJ i odpowiedz z " +
            "wyników, podając konkrety (kwoty, nazwy sklepów, liczby). Nie " +
            "mów, że nie masz dostępu do internetu ani do aktualnych danych, " +
            "i nie używaj do tego znacznika web_search - on tylko otwiera " +
            "przeglądarkę na telefonie i nie daje Ci żadnych wyników."
    } else {
        "\n\nINTERNET: w tej rozmowie NIE masz dostępu do wyszukiwarki. Gdy " +
            "odpowiedź wymaga aktualnych danych z internetu (ceny, pogoda w " +
            "innym miejscu niż podana wyżej prognoza, wiadomości), powiedz " +
            "wprost, że teraz tego nie sprawdzisz. Nie mów, że właśnie " +
            "szukasz - znacznik web_search tylko otwiera przeglądarkę na " +
            "telefonie, więc proponuj go jedynie wtedy, gdy człowiek chce " +
            "sam przejrzeć wyniki."
    }

    /**
     * Pytanie, na które odpowiedź z pamięci jest zmyśleniem - opinie, oceny,
     * "sprawdź w Google". Bieg 160: "sprawdź opinie tych restauracji w googlu"
     * - model nie szukał ani razu i odpowiedział "wszystkie mają dobre opinie".
     */
    fun wymagaSzukania(pytanie: String): Boolean = SZUKAJ.containsMatchIn(pytanie.lowercase())

    /** Dopisek do polecenia, gdy [wymagaSzukania]. */
    const val WYMUSZENIE: String = "\n\nTO PYTANIE WYMAGA WYSZUKANIA: zanim odpowiesz, " +
        "wyszukaj w internecie. Nie odpowiadaj z pamięci. Jeśli wyszukiwanie nic nie " +
        "da, powiedz wprost, że nie znalazłeś - nie wymyślaj ocen ani opinii."

    // Od początku słowa: samo "ocen" siedzi też w "procent".
    private val SZUKAJ = Regex(
        """\b(opini|ocen|recenzj|gwiazdk|wygoogluj|wyszukaj)|w\s+googl|""" +
            """(poszukaj|sprawd[zź]|znajd[zź])\s+w\s+internecie"""
    )
}
