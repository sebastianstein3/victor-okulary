package pl.victor.app.proactive

/**
 * Czy pytanie dotyczy miejsca, w którym użytkownik JEST - i czy chce
 * opowieści przewodnika.
 *
 * ## Po co
 * Położenie szło do modelu tylko przy zdjęciu. "Gdzie w okolicy można zjeść"
 * dostawało odpowiedź dla miasta z ustawień pogody (dziennik z biegu 154).
 * Doklejanie położenia do KAŻDEGO pytania kosztowałoby GPS i geokodowanie przy
 * "ile to siedem razy osiem", więc rozpoznajemy pytania, którym ono pomaga.
 *
 * Wzorce są szerokie, bo pomyłka w tę stronę kosztuje jedną linijkę promptu,
 * a w drugą - odpowiedź o złym mieście.
 */
object NearbyQuestion {

    fun dotyczyOkolicy(pytanie: String): Boolean {
        val q = pytanie.lowercase()
        return OKOLICA.any { q.contains(it) } || GDZIE_CZYNNOŚĆ.containsMatchIn(q) ||
            jestPrzewodnikiem(pytanie)
    }

    /** "Co ciekawego w okolicy", "opowiedz o tym miejscu", "co tu warto zobaczyć". */
    fun jestPrzewodnikiem(pytanie: String): Boolean {
        val q = pytanie.lowercase()
        return PRZEWODNIK.any { q.contains(it) }
    }

    private val OKOLICA = listOf(
        "w okolic", "w pobliżu", "w poblizu", "niedaleko", "blisko mnie", "blisko stąd",
        "blisko stad", "najbliższ", "najblizsz", "gdzie jestem", "gdzie teraz jestem",
        "w jakim mieście jestem", "w jakim miescie jestem", "jaka to ulica", "co to za ulica",
        "na jakiej ulicy", "co jest obok", "co tu jest", "tutaj w ", "tu w pobliżu",
        "w tej okolicy", "ta okolica", "jak stąd", "jak stad", "stąd do", "stad do",
        "near me", "nearby"
    )

    /** "gdzie (mogę|można|się da) zjeść / kupić / zatankować ..." */
    private val GDZIE_CZYNNOŚĆ = Regex(
        """gdzie\s+(?:tu\s+|tutaj\s+)?(?:mog[eę]|mo[zż]na|si[eę]\s+da|by|zjem|kupi[eę])\s*""" +
            """(?:\w+\s+)?(?:zje[sś][cć]|zjem|kupi[cć]|kupi[eę]|zatankowa[cć]|zaparkowa[cć]|""" +
            """wypi[cć]|napi[cć]|wymieni[cć]|wyp[lł]aci[cć]|naprawi[cć]|zrobi[cć]\s+zakupy|""" +
            """przenocowa[cć]|spa[cć]|posiedzie[cć]|p[oó]j[sś][cć])"""
    )

    private val PRZEWODNIK = listOf(
        "co ciekawego", "co warto zobaczyć", "co warto zobaczyc", "co tu warto",
        "zabytk", "atrakcj", "zwiedz", "opowiedz o tym miejscu", "opowiedz o okolicy",
        "co to za miejsce", "historia tego miejsca", "przewodnik"
    )
}
