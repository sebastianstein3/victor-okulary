package pl.victor.app.audio

import kotlin.math.sqrt

/**
 * Głośność nagrania w czasie - jedna liczba na każde pół sekundy.
 *
 * ## Po co
 * Z dziennika z 23 września: w wielu turach telefon nic nie usłyszał, nagranie
 * z okularów poszło do modelu, a model odpowiadał "w nagraniu nie słychać
 * żadnego pytania". Dziennik podaje dla tych nagrań tło 0 i szczyt circa 8000
 * - i to NIE rozstrzyga, co się stało. Tło równe zeru to cyfrowa cisza, a nie
 * cichy pokój: prawdziwy mikrofon zawsze ma szum.
 *
 * Są dwie możliwości i wymagają zupełnie innych napraw:
 *  1. okulary nadają mowę, tylko z przerwami na ciszę (kodek wycina pauzy) -
 *     wtedy mowa jest w nagraniu i problem leży w rozpoznawaniu;
 *  2. okulary nadają dźwięk tylko przez pierwszą chwilę (sygnał wybudzenia,
 *     bufor sprzed startu), a potem same zera - wtedy pytania w nagraniu po
 *     prostu NIE MA i żadne rozpoznawanie go nie wyciągnie.
 *
 * Dwie liczby (tło, szczyt) wyglądają w obu przypadkach tak samo. Obwiednia -
 * nie: w pierwszym ma kilka wzniesień rozrzuconych po całym nagraniu, w drugim
 * jedno na początku i zera do końca. Jeden wiersz dziennika rozstrzyga, gdzie
 * szukać dalej, zamiast zgadywać.
 */
object AudioEnvelope {

    /**
     * Średnia głośność (RMS) kolejnych okien.
     *
     * @param pcm 16 bit little-endian, mono
     * @param oknoMs długość jednego okna
     * @param maxOkien ile okien najwyżej - wpis do dziennika ma zostać krótki
     */
    fun rms(pcm: ByteArray, sampleRate: Int, oknoMs: Int = 500, maxOkien: Int = 30): List<Int> {
        if (pcm.size < 2 || sampleRate <= 0 || oknoMs <= 0) return emptyList()
        val próbekNaOkno = (sampleRate.toLong() * oknoMs / 1000).toInt().coerceAtLeast(1)
        val próbek = pcm.size / 2
        val wynik = mutableListOf<Int>()
        var start = 0
        while (start < próbek && wynik.size < maxOkien) {
            val koniec = minOf(start + próbekNaOkno, próbek)
            var suma = 0.0
            for (i in start until koniec) {
                val lo = pcm[2 * i].toInt() and 0xFF
                val hi = pcm[2 * i + 1].toInt()
                val v = (hi shl 8) or lo
                suma += v.toDouble() * v
            }
            wynik.add(sqrt(suma / (koniec - start)).toInt())
            start = koniec
        }
        return wynik
    }

    /** Postać do dziennika: "0 0 812 1540 0 0" - czytelna gołym okiem. */
    fun doDziennika(obwiednia: List<Int>): String = obwiednia.joinToString(" ")

    /**
     * Ile okien od KOŃCA nagrania jest cyfrową ciszą (dokładne zero).
     *
     * Duża liczba przy małej długości mowy to podpis przypadku 2 z opisu klasy -
     * i jest to jedna liczba, którą da się wypisać obok obwiedni.
     */
    fun ciszaNaKońcu(obwiednia: List<Int>): Int = obwiednia.takeLastWhile { it == 0 }.size
}
