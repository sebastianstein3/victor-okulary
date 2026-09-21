package pl.victor.app.translation

/**
 * Stan jednej sesji trybu "tłumacz ze słuchu".
 *
 * Trzyma to, co musi przetrwać między kolejnymi nasłuchami: ostatnie
 * rozpoznanie (do liczenia ogona), ostatnie zdanie, które sami wypowiedzieliśmy
 * (do rozpoznania echa), i chwilę ostatniego tłumaczenia (do dławienia tempa).
 *
 * Same reguły są w [EarTranslation] - tu jest tylko ich kolejność. Rozdzielenie
 * nie jest ozdobne: dzięki niemu pętla w orkiestratorze nie podejmuje ŻADNEJ
 * decyzji, a wszystkie decyzje da się sprawdzić testem bez okularów.
 */
class EarTranslationSession(
    private val zegar: () -> Long = System::currentTimeMillis
) {
    private var ostatnieRozpoznanie: String = ""
    private var ostatnioPowiedziane: String? = null
    private var ostatnieTłumaczenieMs: Long = 0L

    /** Co zrobić z usłyszanym zdaniem. */
    sealed class Decyzja {
        /** Przetłumacz ten fragment i powiedz wynik. */
        data class Tłumacz(val fragment: String) : Decyzja()

        /** Nic nie rób - i wiadomo dlaczego. */
        data class Pomiń(val powód: String) : Decyzja()

        /** To było polecenie wyjścia z trybu. */
        object Koniec : Decyzja()
    }

    /**
     * Rozstrzyga, co zrobić z jednym rozpoznanym zdaniem.
     *
     * Kolejność sprawdzeń jest istotna:
     *  1. **koniec** - zanim cokolwiek innego, bo polecenie wyjścia ma działać
     *     także wtedy, gdy akurat trwa dławienie;
     *  2. **echo** - zanim ogon, bo własny głos nie może zapisać się jako
     *     "ostatnie rozpoznanie"; zapisany, przesunąłby punkt odniesienia i
     *     ucinał początek następnego prawdziwego zdania;
     *  3. **ogon** - dopiero z tekstu, o którym wiemy, że jest cudzy;
     *  4. **tempo** - na końcu, bo dławić ma się tłumaczenie, nie czytanie.
     */
    fun rozstrzygnij(usłyszane: String): Decyzja {
        if (EarTranslation.toKoniec(usłyszane)) return Decyzja.Koniec
        if (EarTranslation.jestEchem(usłyszane, ostatnioPowiedziane)) {
            return Decyzja.Pomiń("własny głos wrócił z mikrofonu")
        }
        val fragment = EarTranslation.ogon(ostatnieRozpoznanie, usłyszane)
        if (!EarTranslation.wartoTłumaczyć(fragment)) {
            return Decyzja.Pomiń("nic nowego nie przybyło")
        }
        val teraz = zegar()
        if (!EarTranslation.czasNaTłumaczenie(teraz, ostatnieTłumaczenieMs)) {
            // ŚWIADOMIE NIE ZAPISUJEMY TU ROZPOZNANIA.
            //
            // Zdławiony fragment nie przepadł - przy następnym nasłuchu
            // policzy się razem z tym, co dojdzie, i pójdzie jednym kawałkiem.
            // Gdybyśmy zapisali go teraz, zniknąłby bez tłumaczenia.
            return Decyzja.Pomiń("za wcześnie po poprzednim tłumaczeniu")
        }
        ostatnieRozpoznanie = usłyszane.trim()
        ostatnieTłumaczenieMs = teraz
        return Decyzja.Tłumacz(fragment)
    }

    /** Zapamiętuje zdanie, które właśnie wypowiedzieliśmy w okulary. */
    fun zapamiętajWłasnąWypowiedź(tekst: String) {
        ostatnioPowiedziane = tekst
    }

    /** Czyści stan - wołane przy każdym wejściu w tryb. */
    fun wyzeruj() {
        ostatnieRozpoznanie = ""
        ostatnioPowiedziane = null
        ostatnieTłumaczenieMs = 0L
    }
}
