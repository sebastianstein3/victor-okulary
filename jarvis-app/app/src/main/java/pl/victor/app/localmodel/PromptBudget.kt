package pl.victor.app.localmodel

/**
 * Docina prompt do okna kontekstu małego modelu.
 *
 * ## Skąd to się wzięło
 * Ze zgłoszenia „lokalny model AI zupełnie nie odpowiada" i z jednej liczby w
 * dzienniku: `znakówPromptu=8943`.
 *
 * Katalog daje Qwenowi 0.8B okno **2048 tokenów**. Polski tekst to grubo licząc
 * 3-4 znaki na token, więc 8943 znaki to jakieś 2500-3000 tokenów - WIĘCEJ NIŻ
 * CAŁE OKNO, zanim model wygeneruje choć jeden token odpowiedzi. Nie ma z czego
 * odpowiedzieć.
 *
 * ## Czemu to zaczęło się psuć dopiero teraz
 * Bo prompt urósł. We wcześniejszych dziennikach miał 1709-3646 znaków; po
 * podłączeniu kalendarza i poczty skoczył do 7117-8943. Model lokalny mógł więc
 * kiedyś działać i przestać bez żadnej zmiany w jego własnym kodzie.
 *
 * ## Co obcinamy, a czego nie
 * Kontekst, nie pytanie. Pytanie użytkownika jest jedyną rzeczą, bez której tura
 * nie ma sensu - kalendarz, poczta i pamięć są dodatkami. Gdy wszystko się nie
 * mieści, oddajemy tyle kontekstu, ile wejdzie, i CAŁE pytanie.
 *
 * Obcinamy po granicy wiersza, nie w połowie zdania: kontekst jest złożony z
 * bloków po kilka linii i ucięcie w środku zdania zostawiłoby modelowi urwaną
 * informację, która wygląda na pełną.
 */
object PromptBudget {

    /**
     * @param context materiał pomocniczy (persona, kalendarz, poczta, pamięć)
     * @param question pytanie użytkownika - nigdy nie skracane
     * @param limitChars ile znaków w sumie wolno oddać
     * @return kontekst przycięty tak, żeby całość zmieściła się w limicie
     */
    fun fitContext(context: String, question: String, limitChars: Int): String {
        if (limitChars <= 0) return ""
        val forContext = limitChars - question.length - NOTICE.length
        // Samo pytanie nie mieści się w oknie - wtedy kontekst nie ma prawa
        // zabrać ani znaku. Pytanie i tak idzie w całości: lepiej, żeby model
        // urwał odpowiedź, niż żeby odpowiadał na połowę pytania.
        if (forContext <= 0) return ""
        if (context.length <= forContext) return context

        val cut = context.take(forContext)
        // Po granicy wiersza, nie w połowie zdania.
        val lastBreak = cut.lastIndexOf('\n')
        val kept = if (lastBreak > forContext / 2) cut.take(lastBreak) else cut
        return kept + NOTICE
    }

    /**
     * Ile znaków promptu mieści się w oknie o podanej liczbie tokenów.
     *
     * Dzielimy okno na pół: druga połowa musi zostać na ODPOWIEDŹ, bo model
     * generuje ją w tym samym kontekście. Przelicznik trzy znaki na token jest
     * ostrożny - polski bywa gęstszy niż angielski, a pomyłka w tę stronę
     * kosztuje krótszy kontekst, nie brak odpowiedzi.
     */
    fun charsFor(contextTokens: Int): Int = (contextTokens / 2) * CHARS_PER_TOKEN

    /** Dopisek, żeby model wiedział, że materiał jest niepełny. */
    private const val NOTICE = "\n[kontekst skrócony]"

    private const val CHARS_PER_TOKEN = 3
}
