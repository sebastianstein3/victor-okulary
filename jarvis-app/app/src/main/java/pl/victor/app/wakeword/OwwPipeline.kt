package pl.victor.app.wakeword

/**
 * Strumieniowa logika openWakeWord - bez Androida i bez ONNX.
 *
 * ## Po co osobno
 * Bo to tutaj są wszystkie liczby, które muszą się zgadzać z oryginałem
 * (`openwakeword/utils.py`, klasa `AudioFeatures`, i `Model.predict`): rozmiar
 * porcji, skalowanie mel, okna 76 ramek i 16 osadzeń, pominięcie pierwszych
 * wyników. Pomyłka w którejkolwiek z nich nie wywraca aplikacji - klasyfikator
 * po prostu nigdy nie przekracza progu, a z zewnątrz wygląda to jak "fraza nie
 * działa". Trzy modele wchodzą jako funkcje, więc test sprawdza te liczby na
 * zwykłej JVM, bez telefonu i bez plików modeli. Część z ONNX i mikrofonem
 * jest w [OpenWakeWord].
 *
 * ## Przepływ (jak w openWakeWord)
 * 1. Dźwięk 16 kHz, int16, w porcjach po [CHUNK] próbek (80 ms). Reszta, która
 *    nie wypełnia porcji, czeka na następne wywołanie [feed].
 * 2. Na każdą porcję: [melspec] z ostatnich `CHUNK + 160*3` surowych próbek,
 *    każda wartość przez `x/10 + 2`, ramki (zwykle 8) na koniec bufora mel.
 *    Bufor startuje z [MEL_WINDOW] wierszami jedynek i ma górny limit.
 * 3. JEDNO osadzenie ([embed]) z ostatnich [MEL_WINDOW] ramek mel na koniec
 *    bufora cech.
 * 4. [classify] z ostatnich [featureWindow] osadzeń daje wynik 0..1.
 *
 * Różnica wobec oryginału, świadoma: openWakeWord wypełnia bufor cech
 * osadzeniami losowego szumu, żeby mieć wynik od pierwszej porcji. Tutaj bufor
 * startuje pusty, a wynik pojawia się, gdy zbierze się [featureWindow] osadzeń
 * (ok. 1,3 s). Za to pierwsze [WARMUP_SCORES] wyniki po starcie są zerowane - tak
 * jak robi to `Model.predict`, bo bufory są wtedy jeszcze w połowie sztuczne.
 *
 * ## Wykrycie
 * Wynik >= [threshold] przez [wymaganeRamki] porcji z rzędu. Potem przez
 * [refractoryMs] nic nowego nie odpala (jedna fraza to kilkanaście porcji nad
 * progiem - bez tej przerwy jedno "Hej Wiktor" otwierałoby kilka tur), a
 * bufory liczą się dalej, żeby po przerwie nie zaczynać od zera.
 *
 * Klasa nie jest bezpieczna wątkowo - karmi ją jeden wątek nasłuchu.
 */
class OwwPipeline(
    /** Model mel: próbki (wartości int16 jako Float) -> ramki x 32, surowe wyjście modelu. */
    private val melspec: (FloatArray) -> Array<FloatArray>,
    /** Model osadzeń: [MEL_WINDOW] x 32 -> 96. */
    private val embed: (Array<FloatArray>) -> FloatArray,
    /** Klasyfikator frazy: [featureWindow] x 96 -> wynik 0..1. */
    private val classify: (Array<FloatArray>) -> Float,
    /** Próg wyniku, od którego porcja liczy się jako trafienie. */
    private val threshold: Float = DEFAULT_THRESHOLD,
    /** Ile porcji z rzędu musi przekroczyć próg, żeby uznać wykrycie. */
    private val wymaganeRamki: Int = 1,
    /** Przerwa po wykryciu, w której kolejne wykrycie nie odpala. */
    private val refractoryMs: Long = REFRACTORY_MS,
    /** Ile osadzeń bierze klasyfikator - w modelach openWakeWord zwykle 16. */
    val featureWindow: Int = FEATURE_WINDOW,
    /** Wywoływane w wątku [feed] przy wykryciu, z wynikiem, który je dał. */
    private val onDetected: (Float) -> Unit = {}
) {
    init {
        require(threshold > 0f) { "próg musi być dodatni" }
        require(wymaganeRamki >= 1) { "wymaganeRamki >= 1" }
        require(featureWindow >= 1) { "featureWindow >= 1" }
    }

    /** Surowe próbki - trzymamy tylko ogon potrzebny modelowi mel. */
    private val raw = FloatArray(MEL_INPUT_SAMPLES)
    private var rawCount = 0

    /** Próbki, które nie wypełniły jeszcze porcji. */
    private val remainder = ShortArray(CHUNK)
    private var remainderCount = 0

    private val melBuffer = ArrayDeque<FloatArray>()
    private val featureBuffer = ArrayDeque<FloatArray>()

    /** Ile wyników policzono od startu - do pominięcia rozgrzewki. */
    private var scoresSinceStart = 0
    private var consecutiveHits = 0

    /** Koniec przerwy po ostatnim wykryciu; `Long.MIN_VALUE` = brak przerwy. */
    private var refractoryUntilMs = Long.MIN_VALUE

    /** Ostatni wynik (po rozgrzewce - 0 w jej trakcie) albo `null`, gdy jeszcze nie było. */
    var lastScore: Float? = null
        private set

    /** Ile razy fraza została wykryta od startu. */
    var detections: Int = 0
        private set

    init {
        reset()
    }

    /** Czyści wszystko jak przy nowym starcie nasłuchu. */
    fun reset() {
        rawCount = 0
        remainderCount = 0
        melBuffer.clear()
        repeat(MEL_WINDOW) { melBuffer.addLast(FloatArray(MEL_BINS) { 1f }) }
        featureBuffer.clear()
        scoresSinceStart = 0
        consecutiveHits = 0
        refractoryUntilMs = Long.MIN_VALUE
        lastScore = null
        detections = 0
    }

    /** Liczba ramek mel w buforze - dla testów. */
    internal val melFrames: Int get() = melBuffer.size

    /** Liczba osadzeń w buforze - dla testów. */
    internal val features: Int get() = featureBuffer.size

    /** Ile próbek czeka na dopełnienie porcji - dla testów. */
    internal val pendingSamples: Int get() = remainderCount

    /**
     * Dokarmia potok próbkami z mikrofonu.
     *
     * @param samples PCM 16 kHz mono, dowolnej długości
     * @param nowMs czas wywołania (do przerwy po wykryciu)
     * @return wynik ostatniej porcji przetworzonej w tym wywołaniu (0 w czasie
     *   rozgrzewki) albo `null`, gdy żadna porcja nie dała wyniku
     */
    fun feed(samples: ShortArray, nowMs: Long): Float? {
        var result: Float? = null
        var offset = 0
        while (offset < samples.size) {
            val take = minOf(CHUNK - remainderCount, samples.size - offset)
            System.arraycopy(samples, offset, remainder, remainderCount, take)
            remainderCount += take
            offset += take
            if (remainderCount == CHUNK) {
                remainderCount = 0
                processChunk(remainder, nowMs)?.let { result = it }
            }
        }
        return result
    }

    private fun processChunk(chunk: ShortArray, nowMs: Long): Float? {
        appendRaw(chunk)

        // Mel z ogona: CHUNK nowych próbek plus 3 przesunięcia okna (160) z
        // poprzedniej porcji - dokładnie tyle bierze openWakeWord.
        val melInput = raw.copyOfRange(MEL_INPUT_SAMPLES - rawCount, MEL_INPUT_SAMPLES)
        val frames = melspec(melInput)
        for (frame in frames) {
            melBuffer.addLast(FloatArray(frame.size) { frame[it] / 10f + 2f })
        }
        while (melBuffer.size > MEL_MAX_FRAMES) melBuffer.removeFirst()

        if (melBuffer.size >= MEL_WINDOW) {
            val window = Array(MEL_WINDOW) { melBuffer[melBuffer.size - MEL_WINDOW + it] }
            featureBuffer.addLast(embed(window))
            while (featureBuffer.size > FEATURE_MAX) featureBuffer.removeFirst()
        }

        if (featureBuffer.size < featureWindow) return null

        val features = Array(featureWindow) { featureBuffer[featureBuffer.size - featureWindow + it] }
        var score = classify(features)
        // openWakeWord zeruje pierwsze wyniki - bufory są wtedy jeszcze w
        // dużej części wypełnieniem, a nie dźwiękiem.
        if (scoresSinceStart < WARMUP_SCORES) score = 0f
        scoresSinceStart++
        lastScore = score

        if (nowMs < refractoryUntilMs) {
            consecutiveHits = 0
            return score
        }
        if (score >= threshold) {
            consecutiveHits++
            if (consecutiveHits >= wymaganeRamki) {
                consecutiveHits = 0
                refractoryUntilMs = nowMs + refractoryMs
                detections++
                onDetected(score)
            }
        } else {
            consecutiveHits = 0
        }
        return score
    }

    /** Dopisuje porcję na koniec ogona surowych próbek, przesuwając go w lewo. */
    private fun appendRaw(chunk: ShortArray) {
        val keep = minOf(rawCount, MEL_INPUT_SAMPLES - chunk.size)
        System.arraycopy(raw, MEL_INPUT_SAMPLES - keep, raw, MEL_INPUT_SAMPLES - chunk.size - keep, keep)
        for (i in chunk.indices) raw[MEL_INPUT_SAMPLES - chunk.size + i] = chunk[i].toFloat()
        rawCount = keep + chunk.size
    }

    companion object {
        /** Częstotliwość próbkowania, jakiej oczekują modele. */
        const val SAMPLE_RATE = 16_000

        /** Porcja: 80 ms przy 16 kHz. */
        const val CHUNK = 1280

        /** Ile próbek wchodzi do modelu mel: porcja plus 3 przesunięcia po 160. */
        const val MEL_INPUT_SAMPLES = CHUNK + 160 * 3

        /** Liczba pasm mel na ramkę. */
        const val MEL_BINS = 32

        /** Okno ramek mel na jedno osadzenie. */
        const val MEL_WINDOW = 76

        /** Górny limit bufora mel (10 s, jak w openWakeWord). */
        const val MEL_MAX_FRAMES = 970

        /** Długość osadzenia. */
        const val EMBEDDING_SIZE = 96

        /** Domyślne okno klasyfikatora w osadzeniach. */
        const val FEATURE_WINDOW = 16

        /** Górny limit bufora osadzeń (jak w openWakeWord). */
        const val FEATURE_MAX = 120

        /** Ile pierwszych wyników po starcie jest zerowanych. */
        const val WARMUP_SCORES = 5

        /** Próg domyślny - taki sam, jakiego używa openWakeWord. */
        const val DEFAULT_THRESHOLD = 0.5f

        /** Przerwa po wykryciu. */
        const val REFRACTORY_MS = 2_000L
    }
}
