package pl.victor.app.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** Czysta logika: poziom tła i ile głośniej mówić. */
object NoiseLogic {

    const val POMIAR_MS = 600

    /** Starszy pomiar już nic nie mówi - człowiek mógł wyjść z tramwaju. */
    const val WAŻNY_MS = 3 * 60_000L

    /**
     * Tło w dBFS: 20. percentyl głośności okien po 50 ms. Nie średnia - jedno
     * trzaśnięcie drzwiami nie robi z pokoju ulicy. `null` dla ciszy cyfrowej
     * (mikrofon zajęty przez inną aplikację oddaje same zera).
     */
    fun tłoDb(samples: ShortArray, n: Int, sampleRate: Int): Double? {
        val okno = sampleRate / 20
        if (okno <= 0 || n < okno * 4) return null
        val poziomy = (0 until n / okno).map { w ->
            var sum = 0.0
            for (i in w * okno until (w + 1) * okno) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            sqrt(sum / okno)
        }.sorted()
        val p20 = poziomy[poziomy.size / 5]
        if (poziomy.last() < 1.0) return null
        return 20 * log10(maxOf(p20, 1.0) / 32768.0)
    }

    /**
     * Ułamek pełnej skali głośności, o który podnieść mowę. Progi z dołu
     * celowo ostrożne: w zwykłym pokoju nie zmieniamy NIC - głośność, którą
     * ustawił użytkownik, jest święta. Dopiero wyraźny gwar ją podnosi.
     */
    fun dodatek(db: Double?): Float = when {
        db == null -> 0f
        db < -50 -> 0f
        db < -42 -> 0.15f
        db < -34 -> 0.3f
        else -> 0.45f
    }

    /** Docelowy poziom strumienia - nigdy ciszej niż był, nigdy ponad maksimum. */
    fun cel(obecna: Int, max: Int, dodatek: Float): Int {
        if (dodatek <= 0f || max <= 0) return obecna
        val kroki = kotlin.math.ceil(max * dodatek).toInt().coerceAtLeast(1)
        return (obecna + kroki).coerceAtMost(max).coerceAtLeast(obecna)
    }
}
