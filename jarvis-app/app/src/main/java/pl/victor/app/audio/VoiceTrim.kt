package pl.victor.app.audio

import kotlin.math.sqrt

/**
 * Przycina nagranie do fragmentu, w którym naprawdę ktoś mówi.
 *
 * ## Skąd to się wzięło
 * Z jednego zdania użytkownika: „AI mówi na każde pytanie, że słyszy tylko
 * kroki, a nikt nawet nie chodzi". Dziennik z 12 września, 19:39, pokazuje
 * mechanizm bez reszty. Gdy rozpoznawanie mowy na telefonie nie wykryje końca
 * wypowiedzi - a zdarza się to w trzech turach na czternaście - nasłuch dobija
 * do twardego sufitu i nagranie ma 9,11 s przy pytaniu trwającym ze dwie:
 *
 *     19:43:52.351  NASŁUCH koniec  ms=8671            (bez rozpoznanego tekstu)
 *     19:43:53.517  nagranie z okularów  sekund=9.1135
 *     19:43:58.289  pytanie  nagranie=874940 B
 *
 * Model dostaje więc nagranie, w którym mowa jest MNIEJSZOŚCIĄ, i opisuje to,
 * co w nim słychać najwyraźniej - czyli otoczenie. Transkrypcja na urządzeniu
 * wykłada się na tym samym (uruchomiła się, 1,7 s i 4,8 s, wróciła pusta).
 *
 * ## Co robi ta klasa, a czego nie robi
 * Liczy głośność w krótkich oknach, znajduje pierwsze i ostatnie okno wyraźnie
 * głośniejsze od TŁA TEGO NAGRANIA i oddaje wycinek między nimi z zapasem.
 * Nie rozpoznaje mowy, nie odsiewa szumu, nie zmienia próbek - tylko odcina
 * końce, w których nie dzieje się nic.
 *
 * Próg liczy się od szumu własnego nagrania, a nie od stałej wartości, bo
 * poziom zależy od tego, jak ciasno mikrofon przylega do skroni i jak głośno
 * jest wokół. Stała działałaby w jednym pomieszczeniu.
 *
 * ## Zasada bezpieczeństwa: w razie wątpliwości nie tniemy
 * Każda droga wyjścia, która nie jest pewna, oddaje nagranie BEZ ZMIAN. Ucięcie
 * pytania jest gorsze niż zostawienie ciszy: cisza kosztuje tokeny i trochę
 * celności, ucięte pytanie kosztuje całą turę.
 */
object VoiceTrim {

    /**
     * Przycina próbki 16-bit mono do fragmentu z mową.
     *
     * @param pcm surowe próbki, 16 bitów ze znakiem, little endian, mono
     * @param sampleRate częstotliwość próbkowania tych próbek
     * @return wycinek albo `pcm` bez zmian, gdy nie ma czego albo nie warto ciąć
     */
    fun trim(pcm: ByteArray, sampleRate: Int): ByteArray {
        if (sampleRate <= 0) return pcm
        val windowBytes = bytesFor(WINDOW_MS, sampleRate)
        if (windowBytes <= 0) return pcm
        // Za krótkie, żeby cokolwiek oceniać - a i tak nie ma tu czego zyskać.
        if (pcm.size < windowBytes * MIN_WINDOWS) return pcm

        val loudness = windowLoudness(pcm, windowBytes)
        if (loudness.isEmpty()) return pcm

        // Tło bierzemy z mediany, nie ze średniej ani z minimum: średnią zawyża
        // sama mowa, a minimum to zwykle pojedyncze okno ciszy między słowami.
        val floor = median(loudness)
        val peak = loudness.max()
        // Nagranie jednolicie głośne albo jednolicie ciche - nie ma w nim
        // rozróżnialnej mowy, więc nie mamy czym się kierować.
        if (peak < floor * PEAK_OVER_FLOOR) return pcm

        val threshold = floor + (peak - floor) * THRESHOLD_FRACTION
        val first = loudness.indexOfFirst { it >= threshold }
        val last = loudness.indexOfLast { it >= threshold }
        if (first < 0 || last < first) return pcm

        val marginWindows = (MARGIN_MS + WINDOW_MS - 1) / WINDOW_MS
        val fromWindow = (first - marginWindows).coerceAtLeast(0)
        val toWindow = (last + marginWindows).coerceAtMost(loudness.size - 1)

        val from = fromWindow * windowBytes
        val to = ((toWindow + 1) * windowBytes).coerceAtMost(pcm.size)
        if (to <= from) return pcm

        val kept = to - from
        // Nie tniemy dla kilku procent - każde cięcie to ryzyko, a zysk musi je
        // uzasadniać. Nie zostawiamy też wycinka krótszego, niż trwa pytanie:
        // gdyby próg trafił w jedno głośne stuknięcie, wyszedłby z tego ułamek
        // sekundy zamiast zdania.
        if (kept >= pcm.size - bytesFor(MIN_GAIN_MS, sampleRate)) return pcm
        if (kept < bytesFor(MIN_KEPT_MS, sampleRate)) return pcm

        return pcm.copyOfRange(from, to)
    }

    /** Skuteczna wartość próbek w kolejnych oknach (RMS). */
    private fun windowLoudness(pcm: ByteArray, windowBytes: Int): DoubleArray {
        val windows = pcm.size / windowBytes
        if (windows <= 0) return DoubleArray(0)
        val out = DoubleArray(windows)
        for (w in 0 until windows) {
            var sum = 0.0
            var i = w * windowBytes
            val end = i + windowBytes
            while (i + 1 < end) {
                // Little endian, 16 bitów ze znakiem.
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
                val v = sample.toDouble()
                sum += v * v
                i += 2
            }
            out[w] = sqrt(sum / (windowBytes / 2))
        }
        return out
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        return sorted[sorted.size / 2]
    }

    private fun bytesFor(ms: Int, sampleRate: Int): Int {
        val samples = sampleRate.toLong() * ms / 1000L
        // Parzyście - próbka ma dwa bajty i nie wolno jej przeciąć w połowie.
        return ((samples * 2).toInt() / 2) * 2
    }

    /** Okno pomiaru głośności. Krótsze niż sylaba, dłuższe niż pojedynczy trzask. */
    private const val WINDOW_MS = 20

    /** Ile okien musi być, żeby w ogóle warto było liczyć. */
    private const val MIN_WINDOWS = 25

    /** Ile zostawiamy przed pierwszym i za ostatnim głośnym oknem. */
    private const val MARGIN_MS = 400

    /** O ile szczyt musi przewyższać tło, żeby uznać, że jest w nagraniu mowa. */
    private const val PEAK_OVER_FLOOR = 3.0

    /** Gdzie między tłem a szczytem stawiamy próg „tu ktoś mówi". */
    private const val THRESHOLD_FRACTION = 0.15

    /** Poniżej tego zysku nie tniemy - ryzyko bez nagrody. */
    private const val MIN_GAIN_MS = 500

    /** Krótszego wycinka nie oddajemy - to już nie byłoby pytanie. */
    private const val MIN_KEPT_MS = 700
}
