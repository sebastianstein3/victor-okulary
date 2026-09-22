package pl.victor.app.translation

/**
 * Przycięcie długiego tekstu do tłumaczenia - tak, żeby było WIDAĆ, że przycięto.
 *
 * ## Skąd to się wzięło
 * W drodze tłumaczenia tekstu z OCR stało `ocr.fullText.take(1000)`. Gołe
 * `take` jest tu najgorszym możliwym zachowaniem: urywa w połowie zdania, a
 * czasem w połowie słowa, i nie zostawia po sobie ŻADNEGO śladu. Tłumaczenie
 * wygląda wtedy na kompletne i po prostu kończy się w środku - a z zewnątrz
 * jest nie do odróżnienia od modelu, który się poddał.
 *
 * Zgłoszone jako „gdy proszę o tłumaczenie większego tekstu, tłumaczy tylko
 * fragment". Sam limit nie był tam jedyną przyczyną (druga siedziała w
 * poleceniu dla modelu, patrz [pl.victor.app.vision.ReadTextPrompt]), ale
 * milcząca była tylko ta.
 *
 * ## Co robimy zamiast
 * Trzy rzeczy, każda po to, żeby wynik dało się ocenić bez zaglądania w kod:
 *  1. limit jest WYŻSZY - tyle, ile i tak wchodzi do promptu z OCR-a;
 *  2. cięcie idzie po granicy ZDANIA (a gdy jej nie ma - słowa), więc
 *     tłumacz nie dostaje urwanego wyrazu;
 *  3. zostaje ślad - [Wynik.przycięte] mówi wołającemu, że ma powiedzieć
 *     człowiekowi „to jest początek", zamiast udawać całość.
 */
object LongTextCut {

    /**
     * Ile znaków wchodzi do tłumaczenia.
     *
     * Stało tu 1000, czyli circa 150 słów - mniej niż strona menu i mniej niż
     * połowa tego, co ten sam tekst dostaje w prompcie z OCR-a
     * (`OCRResult.describe` bierze 3000). Dwa różne limity na ten sam tekst w
     * jednej turze były po prostu pomyłką.
     */
    const val MAX_ZNAKÓW = 3_000

    data class Wynik(
        val tekst: String,
        /** Czy coś odcięto - wołający ma to POWIEDZIEĆ, nie przemilczeć. */
        val przycięte: Boolean
    )

    fun przytnij(text: String, limit: Int = MAX_ZNAKÓW): Wynik {
        if (limit <= 0) return Wynik("", text.isNotEmpty())
        if (text.length <= limit) return Wynik(text, false)

        val okno = text.substring(0, limit)
        // Granica zdania ma pierwszeństwo, bo tłumacz maszynowy pracuje na
        // zdaniach - urwane zdanie tłumaczy się gorzej niż o jedno krótsze.
        val koniecZdania = okno.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
        val cięcie = when {
            // Próg połowy okna: inaczej tekst bez interpunkcji na początku
            // (lista, menu, tabela) obcinałby się do kilku znaków przez jedną
            // wczesną kropkę.
            koniecZdania >= limit / 2 -> koniecZdania + 1
            else -> okno.lastIndexOf(' ').takeIf { it >= limit / 2 } ?: limit
        }
        return Wynik(okno.substring(0, cięcie).trim(), true)
    }
}
