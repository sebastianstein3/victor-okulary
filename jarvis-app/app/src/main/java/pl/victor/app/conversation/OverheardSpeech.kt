package pl.victor.app.conversation

/**
 * Rozstrzyga, czy usłyszane zdanie jest skierowane DO ASYSTENTA.
 *
 * ## Skąd to się wzięło
 * Z dziennika z 12 września, 21:10. Czternaście sekund po starcie aplikacji,
 * przy jeszcze niepodłączonych okularach, poszła do modelu tura:
 *
 *     21:10:56.989  SESJA  start sesji
 *     21:11:11.153  SESJA  --- TURA 0b13 ---  źródło=VOICE
 *     21:11:11.158  SESJA  pytanie  tekst=bardzo by chciał
 *
 * „bardzo by chciał" to urywek czyjejś rozmowy w pokoju. Nikt o nic nie pytał;
 * asystent po prostu usłyszał zdanie i odpowiedział na nie. Z zewnątrz wygląda
 * to tak, jak zgłoszono: „pytam, co widzi, a on odpowiada na pytanie z wczoraj".
 *
 * ## Czemu tak było
 * Tryb konwersacyjny wstaje razem z aplikacją, jeśli jest włączony w
 * ustawieniach, i od tej chwili nasłuchuje bez przerwy. A wszystko, co usłyszał,
 * szło do modelu - fraza wybudzenia była OBCINANA, gdy ją znaleziono, ale jej
 * BRAK niczego nie zatrzymywał.
 *
 * ## Reguła
 * Bez frazy wybudzenia zdanie liczy się tylko wtedy, gdy rozmowa już trwa -
 * czyli tuż po tym, jak asystent skończył mówić albo gdy człowiek sam włączył
 * nasłuch. Wtedy „a ile to kosztuje?" jest normalną dopowiedzią i wymaganie
 * frazy byłoby uciążliwe. Poza tym oknem cisza jest bezpieczniejsza niż
 * odpowiadanie na cudzą rozmowę.
 */
object OverheardSpeech {

    /** Co zrobić z usłyszanym zdaniem. */
    enum class Verdict {
        /** Sama fraza wybudzenia - otwórz nasłuch, nie pytaj modelu. */
        OPEN_LISTENING,

        /** Skierowane do asystenta - potraktuj jak pytanie. */
        ASK,

        /** Cudza rozmowa albo szum - zignoruj. */
        IGNORE
    }

    /**
     * @param text co usłyszało rozpoznawanie mowy
     * @param wakePhrases skonfigurowane frazy wybudzenia
     * @param conversationOpen czy rozmowa trwa (patrz opis klasy)
     */
    fun classify(
        text: String,
        wakePhrases: List<String>,
        conversationOpen: Boolean
    ): Verdict {
        if (text.isBlank()) return Verdict.IGNORE
        if (WakePhrase.isOnlyWakePhrase(text, wakePhrases)) return Verdict.OPEN_LISTENING
        // Fraza z pytaniem w jednym zdaniu („okej lens, jaka jest pogoda") -
        // człowiek zawołał asystenta, więc pytanie jest do niego niezależnie od
        // tego, czy rozmowa trwała.
        if (WakePhrase.stripLeadingWakePhrase(text, wakePhrases) != text) return Verdict.ASK
        return if (conversationOpen) Verdict.ASK else Verdict.IGNORE
    }
}
