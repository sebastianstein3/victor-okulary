package pl.victor.app.conversation

/**
 * Koniec wypowiedzi przy rozpoznawaniu mowy w TELEFONIE - mierzony przez nas,
 * a nie przez silnik.
 *
 * ## Po co
 * Przy nasłuchu przez profil rozmowy (SCO) jedynym sędzią końca był silnik
 * rozpoznawania z własnym, długim oknem ciszy. Skrócić mu go się nie da: trzy
 * dodatki `EXTRA_SPEECH_INPUT_*` raz już wyłączyły temu silnikowi wykrywanie
 * końca zupełnie (dziennik z 12 września - patrz `SpeechToText.intent`).
 *
 * Zamiast tego patrzymy na to, co silnik i tak podaje: wyniki częściowe i
 * poziom sygnału. Gdy od [silenceMs] tekst się nie zmienia I mikrofon jest
 * cicho, wołamy `stopListening()` - silnik oddaje wynik od razu, zamiast
 * czekać na swoją ciszę.
 *
 * ## Zasada bezpieczeństwa (jak w `audio.EnergyEndpoint`)
 * Każda wątpliwość to "jeszcze nie koniec" - wtedy kończy silnik, jak dawniej:
 * - zanim padną [MIN_WORDS] słowa, nie kończymy wcale. Czekanie na POCZĄTEK
 *   mowy zostaje nietknięte, a pojedyncze słowo to często ogon frazy
 *   wybudzenia ("Wiktor") albo "eee" przed pytaniem;
 * - w hałasie, gdzie poziom nie spada pod próg, nie kończymy nigdy;
 * - nowy wynik częściowy albo głośniejsza chwila zeruje odliczanie - pauza w
 *   środku zdania to nie koniec.
 *
 * Bez Androida - wołający podaje czas i zdarzenia, więc całość sprawdza test.
 */
class RecognizerEndpoint(private val silenceMs: Long) {

    private var text = ""
    private var lastTextChangeAtMs = 0L
    private var lastLoudAtMs = 0L
    private var peakDb: Float? = null

    /** Czy to MY zakończyliśmy nasłuch - do dziennika i do [pickResult]. */
    var stopped = false
        private set

    /** Ostatni niepusty wynik częściowy. */
    val lastPartial: String? get() = text.takeIf { it.isNotBlank() }

    fun onPartial(partial: String, nowMs: Long) {
        val norm = partial.trim()
        if (norm.isEmpty()) return
        if (norm != text) {
            text = norm
            lastTextChangeAtMs = nowMs
        }
    }

    /** Poziom z `onRmsChanged`. Skala nie jest znormalizowana - stąd próg względem szczytu. */
    fun onLevel(db: Float, nowMs: Long) {
        if (db.isNaN() || db.isInfinite()) return
        val peak = peakDb
        if (peak == null || db > peak) peakDb = db
        if (db >= loudThresholdDb()) lastLoudAtMs = nowMs
    }

    /**
     * Próg "jeszcze mówi": [DROP_FROM_PEAK_DB] pod szczytem, ale nie niżej niż
     * [MIN_LOUD_DB]. Silniki podają mniej więcej -2 dB dla ciszy i ~10 dB dla
     * mowy; tło, które nie spada pod próg, po prostu nie pozwala skończyć.
     */
    fun loudThresholdDb(): Float =
        maxOf(MIN_LOUD_DB, (peakDb ?: MIN_LOUD_DB) - DROP_FROM_PEAK_DB)

    /** Czy pora zakończyć nasłuch. Nie zmienia stanu - patrz [markStopped]. */
    fun shouldStop(nowMs: Long): Boolean {
        if (stopped) return false
        if (wordCount(text) < MIN_WORDS) return false
        if (nowMs - lastTextChangeAtMs < silenceMs) return false
        if (lastLoudAtMs > 0L && nowMs - lastLoudAtMs < silenceMs) return false
        return true
    }

    fun markStopped() {
        stopped = true
    }

    /**
     * Wynik do oddania: końcowy z silnika, a gdy po NASZYM zatrzymaniu silnik
     * nic nie oddał (bywa, że odpowiada wtedy "brak dopasowania") - ostatni
     * wynik częściowy. Bez tego skrócenie ciszy mogłoby zgubić całe pytanie.
     */
    fun pickResult(final: String?): String? =
        final?.takeIf { it.isNotBlank() } ?: if (stopped) lastPartial else null

    companion object {
        /** Ile słów musi paść, zanim cisza cokolwiek znaczy - patrz opis klasy. */
        const val MIN_WORDS = 2

        const val MIN_LOUD_DB = 1f
        const val DROP_FROM_PEAK_DB = 6f

        fun wordCount(text: String): Int =
            text.split(Regex("\\s+")).count { word -> word.any { it.isLetterOrDigit() } }
    }
}
