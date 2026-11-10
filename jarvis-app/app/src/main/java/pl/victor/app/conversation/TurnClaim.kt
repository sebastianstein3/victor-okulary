package pl.victor.app.conversation

/**
 * Czy nowe wywołanie wolno wpuścić, gdy stan to `Idle`.
 *
 * `Idle` nie znaczy, że nic nie trwa: tura głosowa stoi w nim, zanim zestawi
 * łącze audio, i jeszcze raz między końcem nasłuchu a przekazaniem pytania
 * dalej. Wydzielone z orkiestratora, bo to czysta decyzja - a pomyłka w niej
 * daje albo dwie tury naraz, albo asystenta głuchego na własne przekazanie.
 */
object TurnClaim {

    enum class Verdict {
        /** Nic nie trwa albo to dalszy ciąg bieżącej tury - wchodzimy. */
        ENTER,
        /** Tura w przygotowaniu, a wywołanie nie ma prawa jej przerwać. */
        REFUSE,
        /** Przerwij turę w przygotowaniu i wejdź. */
        TAKE_OVER
    }

    /**
     * @param turnAlive czy korutyna tury żyje i wciąż jest turą (nie sprząta)
     * @param insideTurn czy wywołanie pochodzi z wnętrza tej tury
     * @param takeOver czy wywołanie jest świadomym działaniem użytkownika
     * @param ageMs ile czasu minęło od startu tury albo ostatniej zmiany stanu
     */
    fun whenIdle(
        turnAlive: Boolean,
        insideTurn: Boolean,
        takeOver: Boolean,
        ageMs: Long,
        graceMs: Long,
        stuckMs: Long
    ): Verdict = when {
        !turnAlive || insideTurn -> Verdict.ENTER
        // Karencja: podwójne wykrycie TEJ SAMEJ frazy nie może ubić tury,
        // którą samo przed chwilą zaczęło.
        takeOver && ageMs > graceMs -> Verdict.TAKE_OVER
        ageMs > stuckMs -> Verdict.TAKE_OVER
        else -> Verdict.REFUSE
    }
}
