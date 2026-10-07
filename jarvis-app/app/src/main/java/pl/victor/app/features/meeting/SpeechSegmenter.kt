package pl.victor.app.features.meeting

import kotlin.math.sqrt

/**
 * Tnie ciągłe nagranie spotkania na wypowiedzi - po pauzach.
 *
 * ## Po co ciąć
 * Rozpoznawanie mowy na telefonie jest zrobione pod JEDNO zdanie: dostaje
 * nagranie, czeka na ciszę i oddaje wynik. Podane minuty naraz kończy po
 * pierwszej pauzie i resztę gubi. Kawałki po kilka-kilkanaście sekund,
 * cięte tam, gdzie ktoś przestał mówić, przepisuje dobrze - i można je
 * przepisywać W TRAKCIE spotkania, a nie godzinę po nim.
 *
 * ## Próg mowy
 * Szum sali (wentylacja, klimatyzacja) bywa głośniejszy niż cichy mówca
 * w innym pomieszczeniu, więc stały próg nie działa. Śledzimy poziom tła
 * (najcichsze ramki z ostatnich sekund) i za mowę uznajemy wyraźnie
 * głośniejsze - [ILE_RAZY_GŁOŚNIEJ] razy.
 *
 * Czysta logika na próbkach 16-bit: bez Androida, sprawdzana testem.
 */
class SpeechSegmenter(
    private val częstotliwość: Int = 16_000,
    private val ciszaKończyMs: Int = 700,
    private val minimumMs: Int = 1_200,
    private val maksimumMs: Int = 20_000,
    private val przedMs: Int = 300
) {
    private val bieżący = ArrayList<Short>()
    private val przed = ArrayDeque<ShortArray>()
    private var mowaWToku = false
    private var ciszaOdMs = 0
    private var głośneMs = 0
    private var tło = 0.0
    private var ramekTła = 0

    /**
     * @param ramka kilka-kilkadziesiąt milisekund próbek
     * @return gotowa wypowiedź albo `null`
     */
    fun podaj(ramka: ShortArray): ShortArray? {
        val ms = ramka.size * 1000 / częstotliwość
        val rms = rms(ramka)
        if (ramekTła == 0) {
            tło = rms
            ramekTła++
        }
        val mowa = rms > maxOf(tło * ILE_RAZY_GŁOŚNIEJ, MIN_RMS)
        // Tło liczone TYLKO z ramek, które nie są mową - inaczej długie
        // przemówienie samo podniosłoby próg ponad siebie i resztę spotkania
        // uznalibyśmy za ciszę.
        if (!mowa || rms < tło) aktualizujTło(rms)

        if (!mowaWToku) {
            przed.addLast(ramka)
            while (przed.sumOf { it.size } * 1000 / częstotliwość > przedMs) przed.removeFirst()
            if (mowa) {
                mowaWToku = true
                przed.forEach { r -> r.forEach { bieżący.add(it) } }
                przed.clear()
                ciszaOdMs = 0
                głośneMs = ms
            }
            return null
        }

        ramka.forEach { bieżący.add(it) }
        if (mowa) {
            ciszaOdMs = 0
            głośneMs += ms
        } else {
            ciszaOdMs += ms
        }
        val długośćMs = bieżący.size * 1000 / częstotliwość
        val koniec = (ciszaOdMs >= ciszaKończyMs && długośćMs >= minimumMs) || długośćMs >= maksimumMs
        if (ciszaOdMs >= ciszaKończyMs && długośćMs < minimumMs) {
            // Kaszlnięcie, trzaśnięcie drzwiami - za krótkie na zdanie.
            zeruj()
            return null
        }
        return if (koniec) zamknij() else null
    }

    /** Resztka po zatrzymaniu nagrywania. */
    fun domknij(): ShortArray? =
        if (mowaWToku && bieżący.size * 1000 / częstotliwość >= minimumMs) zamknij() else null.also { zeruj() }

    private fun zamknij(): ShortArray? {
        val wynik = if (głośneMs >= MIN_GŁOŚNE_MS) bieżący.toShortArray() else null
        zeruj()
        return wynik
    }

    private fun zeruj() {
        bieżący.clear()
        mowaWToku = false
        ciszaOdMs = 0
        głośneMs = 0
    }

    private fun aktualizujTło(rms: Double) {
        // Tło goni w dół szybko, w górę powoli: mowa nie może go podnieść.
        tło = if (rms < tło) tło * 0.7 + rms * 0.3 else tło * 0.98 + rms * 0.02
        ramekTła++
    }

    companion object {
        const val ILE_RAZY_GŁOŚNIEJ = 2.5
        const val MIN_RMS = 250.0
        private const val MIN_GŁOŚNE_MS = 400

        fun rms(r: ShortArray): Double {
            if (r.isEmpty()) return 0.0
            var s = 0.0
            for (v in r) s += v.toDouble() * v
            return sqrt(s / r.size)
        }

        fun doBajtów(próbki: ShortArray): ByteArray {
            val out = ByteArray(próbki.size * 2)
            for (i in próbki.indices) {
                val v = próbki[i].toInt()
                out[2 * i] = (v and 0xFF).toByte()
                out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
