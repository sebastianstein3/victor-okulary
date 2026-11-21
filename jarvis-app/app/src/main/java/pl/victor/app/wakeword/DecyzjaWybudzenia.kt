package pl.victor.app.wakeword

/**
 * Czy jedna porcja (80 ms) liczy się jako „Hej Wiktor” - z wyniku ogólnego
 * klasyfikatora frazy i, jeśli jest, weryfikatora głosu właściciela.
 *
 * ## Zasada
 * - Bez weryfikatora (nikt nie nagrał głosu): jak dotąd, wynik >= próg ogólny (0,5).
 * - Z weryfikatorem:
 *   - wynik >= [PROG_NISKI] (0,3) i weryfikator >= [PROG_POTWIERDZENIA] -
 *     budzimy. To jest zysk czułości: fraza właściciela, którą model ogólny
 *     ocenił nisko (uczył się na głosach syntetycznych), i tak przechodzi.
 *   - wynik >= próg ogólny, weryfikator nie potwierdza, ale też nie odrzuca
 *     mocno (>= [PROG_MOCNEGO_ODRZUCENIA]) - budzimy jak dotąd. Weryfikator
 *     uczy się na kilku nagraniach i nie może zepsuć frazy wypowiedzianej
 *     np. z katarem albo przez kogoś innego z domowników.
 *   - wynik >= próg ogólny, a weryfikator mocno odrzuca - NIE budzimy. To jest
 *     zysk na fałszywych wybudzeniach: dźwięk, który model ogólny wziął za
 *     frazę, wygląda jak zwykła mowa/tło właściciela.
 * - "Tylko mój głos" ([tylkoMojGlos]): bez wyjątku dla modelu ogólnego -
 *   budzi wyłącznie potwierdzenie weryfikatora.
 *
 * Progi weryfikatora to prawdopodobieństwa z regresji logistycznej
 * ([WeryfikatorGlosu]); 0,5 to jej naturalna granica klas.
 */
object DecyzjaWybudzenia {

    /** Od tego wyniku ogólnego pytamy weryfikator (i może on obudzić). */
    const val PROG_NISKI = 0.3f

    /** Weryfikator potwierdza głos właściciela. */
    const val PROG_POTWIERDZENIA = 0.5f

    /** Poniżej tego weryfikator "mocno odrzuca" - wtedy nawet wysoki wynik ogólny nie budzi. */
    const val PROG_MOCNEGO_ODRZUCENIA = 0.1f

    enum class Decyzja(val opis: String, val budzi: Boolean) {
        /** Nic - wynik za niski. */
        BRAK("brak", false),
        /** Wynik ogólny ponad progiem, weryfikatora nie ma albo nie sprzeciwia się mocno. */
        OGOLNY("model ogólny", true),
        /** Weryfikator potwierdził głos właściciela. */
        WLASCICIEL("mój głos", true),
        /** Wynik ogólny ponad progiem, ale weryfikator mocno odrzucił. */
        ODRZUCONE("odrzucone przez weryfikator", false),
        /** Tryb "Tylko mój głos": wynik ogólny ponad progiem bez potwierdzenia weryfikatora. */
        ODRZUCONE_TYLKO_MOJ("odrzucone - tylko mój głos", false)
    }

    /** Czy przy takim wyniku ogólnym warto liczyć weryfikator. */
    fun potrzebnyWeryfikator(wynik: Float): Boolean = wynik >= PROG_NISKI

    /**
     * @param wynik klasyfikator ogólny 0..1
     * @param weryfikator wynik weryfikatora 0..1 albo `null`, gdy go nie ma
     *   (brak nagrań) albo nie był liczony
     * @param progOgolny próg modelu ogólnego - ten, który działał bez weryfikatora
     */
    fun ocen(
        wynik: Float,
        weryfikator: Float?,
        tylkoMojGlos: Boolean = false,
        progOgolny: Float = OwwPipeline.DEFAULT_THRESHOLD
    ): Decyzja {
        if (weryfikator == null) return if (wynik >= progOgolny) Decyzja.OGOLNY else Decyzja.BRAK
        if (wynik < PROG_NISKI) return Decyzja.BRAK
        if (weryfikator >= PROG_POTWIERDZENIA) return Decyzja.WLASCICIEL
        if (wynik < progOgolny) return Decyzja.BRAK
        if (tylkoMojGlos) return Decyzja.ODRZUCONE_TYLKO_MOJ
        return if (weryfikator < PROG_MOCNEGO_ODRZUCENIA) Decyzja.ODRZUCONE else Decyzja.OGOLNY
    }
}
