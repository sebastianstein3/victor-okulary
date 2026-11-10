package pl.victor.app.audio

import kotlin.math.sqrt

/**
 * Koniec wypowiedzi rozpoznawany po GŁOŚNOŚCI dźwięku z okularów.
 *
 * ## Po co, skoro jest [SpeechEnd]
 * [SpeechEnd] czeka na przerwę w PAKIETACH - a okulary nadają bez przerwy,
 * także ciszę. W dziennikach sprzętu co piąta tura (44 z 218) słuchała przez
 * to do twardego limitu dziewięciu sekund: telefon w kieszeni nic nie usłyszał,
 * a strumień z okularów nie milkł nigdy. Sam dźwięk rozstrzyga to bez trudu:
 * mikrofon okularów ma bramkę szumów, więc półsekundowe okna ciszy mają
 * wartość 0-42, a mowy 800-3400.
 *
 * ## Zasada bezpieczeństwa
 * Pomyłka w jedną stronę ucina pytanie w pół słowa, w drugą - zostawia stare
 * zachowanie (limit czasu albo rozpoznawanie telefonu). Dlatego wszystkie
 * wątpliwości rozstrzygamy na "jeszcze nie koniec": w hałasie, w którym cisza
 * nie spada poniżej progu, ten miernik po prostu nigdy nie zgłasza końca.
 */
class EnergyEndpoint(
    private val minVoicedMs: Long = MIN_VOICED_MS,
    private val silenceMs: Long = SILENCE_ENDS_SPEECH_MS,
    private val graceMs: Long = SpeechEnd.MIN_LISTEN_MS
) {
    /** Ile mowy (ramek powyżej progu) było do tej pory. */
    var voicedMs = 0L
        private set

    /** Ile nieprzerwanej ciszy leży na końcu. */
    var trailingSilenceMs = 0L
        private set

    /** Najgłośniejsza ramka do tej pory. */
    var peak = 0.0
        private set

    /** Ile dźwięku ocenił miernik. */
    var measuredMs = 0L
        private set

    /** Dokłada jedną rozkodowaną ramkę o głośności [rms] trwającą [frameMs]. */
    fun add(rms: Double, frameMs: Long) {
        if (frameMs <= 0) return
        measuredMs += frameMs
        if (rms > peak) peak = rms
        if (rms >= threshold()) {
            voicedMs += frameMs
            trailingSilenceMs = 0L
        } else {
            trailingSilenceMs += frameMs
        }
    }

    /**
     * Próg mowy: ułamek szczytu, ale w sztywnych granicach.
     *
     * Górna granica jest tu ważniejsza niż dolna: pojedynczy trzask (drzwi,
     * stuknięcie w oprawkę) podbiłby szczyt tak, że zwykła mowa spadłaby pod
     * próg i wyglądała na ciszę - czyli ucięlibyśmy pytanie.
     */
    fun threshold(): Double = (peak * PEAK_FRACTION).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    /**
     * Czy użytkownik skończył mówić.
     *
     * @param sinceStartMs czas od początku nasłuchu - przed karencją cisza jest
     *   przerwą PRZED pytaniem, nie po nim
     */
    fun ended(sinceStartMs: Long): Boolean =
        sinceStartMs >= graceMs && voicedMs >= minVoicedMs &&
            trailingSilenceMs >= requiredSilenceMs()

    /**
     * Po krótkim dźwięku czekamy dłużej.
     *
     * Sprawdzone na 78 nagraniach z dzienników: jedyne przypadki, w których
     * miernik uciąłby pytanie, to cichy dźwięk na starcie (stuknięcie, oddech,
     * ogon sygnału), półtorej sekundy ciszy i dopiero potem właściwe pytanie.
     * Krótka "mowa" jest więc słabym dowodem, że pytanie już padło.
     */
    fun requiredSilenceMs(): Long =
        if (voicedMs < SHORT_SPEECH_MS) maxOf(silenceMs, SILENCE_AFTER_SHORT_SPEECH_MS) else silenceMs

    fun describe(): String =
        "mowa=${voicedMs}ms cisza=${trailingSilenceMs}ms szczyt=${peak.toInt()} zmierzone=${measuredMs}ms"

    companion object {
        /**
         * Krócej niż w [SpeechEnd], bo tu "mowa" to naprawdę głośne ramki, a
         * nie same odstępy między pakietami. "Która godzina" ma około sekundy,
         * "stop" - trzysta milisekund.
         */
        const val MIN_VOICED_MS = 400L

        /** Tyle ciszy po mowie kończy wypowiedź - z zapasem na oddech. */
        const val SILENCE_ENDS_SPEECH_MS = 1_300L

        /** Poniżej tylu milisekund mowy cisza musi trwać dłużej. */
        const val SHORT_SPEECH_MS = 1_000L
        const val SILENCE_AFTER_SHORT_SPEECH_MS = 3_500L

        /**
         * Granice progu. W dziennikach 95% okien ciszy ma głośność do 37, a
         * okna mowy zaczynają się w okolicach 400; słabsze dźwięki (150-250)
         * to tło, które brane za mowę kończyło nasłuch przed pytaniem.
         */
        const val PEAK_FRACTION = 0.1
        const val MIN_THRESHOLD = 250.0
        const val MAX_THRESHOLD = 500.0

        /** Głośność (RMS) jednej ramki PCM 16-bit, little endian. */
        fun rms(pcm: ByteArray): Double {
            val samples = pcm.size / 2
            if (samples == 0) return 0.0
            var sum = 0.0
            var i = 0
            while (i + 1 < pcm.size) {
                val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
                sum += v * v
                i += 2
            }
            return sqrt(sum / samples)
        }
    }
}
