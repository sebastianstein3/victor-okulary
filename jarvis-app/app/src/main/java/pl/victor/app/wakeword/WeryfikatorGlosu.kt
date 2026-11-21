package pl.victor.app.wakeword

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Weryfikator „Hej Wiktor” nauczony na nagraniach właściciela - bez Androida.
 *
 * ## Po co
 * Klasyfikator frazy (`hej_wiktor.onnx`) uczył się wyłącznie na głosach
 * syntetycznych (tools/wakeword/README.md: polski głos testowy 77% wykryć przy
 * progu 0,5 i dwóch ramkach). Na prawdziwym głosie właściciela wynik bywa
 * niższy, a w hałasie zdarzają się fałszywe wybudzenia. Trenowanie sieci na
 * telefonie odpada (za ciężkie, za mało danych), więc - jak "custom verifier"
 * z openWakeWord (`custom_verifier_model.py`) - na wyjściu modelu osadzeń
 * (okno 16 x 96 = 1536 liczb) uczymy mały, liniowy model: "to jest MOJE
 * Hej Wiktor" kontra "to moja zwykła mowa / moje tło".
 *
 * ## Dlaczego regresja logistyczna, a nie podobieństwo cosinusowe do szablonów
 * Negatywy to ten sam głos i ten sam mikrofon co pozytywy. W podobieństwie
 * cosinusowym wszystkie 1536 wymiarów ważą tyle samo, a większość z nich
 * opisuje właśnie głos, mikrofon i pomieszczenie - wspólne dla obu klas - więc
 * szablon pozytywny i negatywny wychodzą do siebie podobne i próg staje się
 * loterią. Regresja logistyczna sama znajduje wymiary, które odróżniają frazę
 * od reszty mowy TEGO głosu, a jej wynik to prawdopodobieństwo 0..1 z
 * naturalnym progiem 0,5. Do tego jest deterministyczna (start od zer, pełny
 * gradient, stała liczba kroków), na kilkuset oknach uczy się w ułamku
 * sekundy i ma 1537 parametrów - plik ma kilkanaście kB.
 *
 * ## Szczegóły uczenia
 * - Standaryzacja każdej cechy (średnia, odchylenie z danych uczących), jak
 *   `StandardScaler` w openWakeWord, dodatkowo podzielona przez pierwiastek z
 *   liczby wymiarów - wektor ma wtedy długość rzędu 1, co pozwala policzyć
 *   bezpieczny krok uczenia z góry (patrz [naucz]).
 * - Klasy ważone po równo: pozytywów jest kilkadziesiąt okien, negatywów
 *   kilkaset - bez wag model nauczyłby się mówić zawsze "nie".
 * - Regularyzacja L2 ([Parametry.lambda]) rzędu tej z openWakeWord
 *   (`LogisticRegression(C=0.001)` po przeliczeniu na nasze skalowanie) - przy
 *   1536 wymiarach i kilkudziesięciu pozytywach bez niej model "zapamiętałby"
 *   nagrania zamiast nauczyć się frazy.
 * - Metoda: gradient Nesterova, krok 1/L z ograniczenia stałej Lipschitza.
 */
class WeryfikatorGlosu internal constructor(
    /** Średnia każdej cechy w danych uczących. */
    internal val srednie: FloatArray,
    /** 1 / (odchylenie * sqrt(wymiar)) dla każdej cechy. */
    internal val skale: FloatArray,
    internal val wagi: FloatArray,
    internal val wyraz: Float
) {
    init {
        require(srednie.size == skale.size && skale.size == wagi.size) { "niespójne wymiary" }
    }

    /** Liczba cech wejścia - 16 x 96 dla modeli openWakeWord. */
    val wymiar: Int get() = wagi.size

    /** Prawdopodobieństwo 0..1, że to fraza właściciela, dla spłaszczonego okna. */
    fun ocen(cechy: FloatArray): Float {
        require(cechy.size == wymiar) { "okno ma ${cechy.size} cech, weryfikator $wymiar" }
        var z = wyraz.toDouble()
        for (j in 0 until wymiar) z += wagi[j].toDouble() * (cechy[j] - srednie[j]) * skale[j]
        return sigmoida(z).toFloat()
    }

    /** To samo dla okna osadzeń w kształcie, w jakim dostaje je klasyfikator. */
    fun ocen(okno: Array<FloatArray>): Float = ocen(splaszcz(okno))

    /** Zapis binarny - patrz [zBajtow]. */
    fun doBajtow(): ByteArray {
        val bajty = ByteArrayOutputStream(16 + wymiar * 12)
        DataOutputStream(bajty).use { out ->
            out.writeInt(MAGIA)
            out.writeInt(WERSJA)
            out.writeInt(wymiar)
            for (t in arrayOf(srednie, skale, wagi)) t.forEach { out.writeFloat(it) }
            out.writeFloat(wyraz)
        }
        return bajty.toByteArray()
    }

    /** Hiperparametry uczenia - domyślne dobrane do 1536 cech i kilkuset okien. */
    data class Parametry(
        val lambda: Double = 0.002,
        val iteracje: Int = 300,
        /** Dolna granica odchylenia - cecha stała w danych nie może dzielić przez zero. */
        val minOdchylenie: Double = 1e-3
    )

    /**
     * Jeden przykład uczący: spłaszczone okno cech.
     *
     * @property grupa numer nagrania - walidacja krzyżowa odkłada całe nagrania,
     *   bo sąsiednie okna jednego nagrania są prawie identyczne
     * @property glowny dla pozytywów: okno z najwyższym wynikiem klasyfikatora
     *   (sąsiednie to dodatkowe przykłady, przesunięte o 80-160 ms)
     */
    class Przyklad(
        val cechy: FloatArray,
        val pozytywny: Boolean,
        val waga: Float = 1f,
        val grupa: Int = 0,
        val glowny: Boolean = true
    )

    /**
     * Wynik walidacji krzyżowej - "jak dobrze to zadziała" policzone na
     * nagraniach, których model w danej rundzie nie widział.
     *
     * @property trafnoscPozytywow ułamek odłożonych nagrań frazy, które weryfikator przyjął
     * @property falszyweAkceptacje ułamek odłożonych okien tła, które przyjął
     */
    data class Walidacja(
        val rund: Int,
        val trafnoscPozytywow: Float,
        val falszyweAkceptacje: Float
    ) {
        /** Średnia z trafności na obu klasach - jedna liczba do dziennika i ekranu. */
        val trafnosc: Float get() = (trafnoscPozytywow + (1f - falszyweAkceptacje)) / 2f
    }

    companion object {
        private const val MAGIA = 0x56574731 // "VWG1"
        private const val WERSJA = 1

        /** Próg przyjęcia przy ocenie walidacji - ten sam co w [DecyzjaWybudzenia]. */
        const val PROG_AKCEPTACJI = 0.5f

        fun splaszcz(okno: Array<FloatArray>): FloatArray {
            val dl = okno.firstOrNull()?.size ?: 0
            val wynik = FloatArray(okno.size * dl)
            okno.forEachIndexed { i, w -> System.arraycopy(w, 0, wynik, i * dl, dl) }
            return wynik
        }

        /** Odczyt z [doBajtow]; `null` przy uszkodzonym albo obcym pliku. */
        fun zBajtow(bajty: ByteArray): WeryfikatorGlosu? = runCatching {
            DataInputStream(ByteArrayInputStream(bajty)).use { inp ->
                if (inp.readInt() != MAGIA || inp.readInt() != WERSJA) return null
                val d = inp.readInt()
                if (d <= 0 || d > 100_000) return null
                val srednie = FloatArray(d) { inp.readFloat() }
                val skale = FloatArray(d) { inp.readFloat() }
                val wagi = FloatArray(d) { inp.readFloat() }
                val wyraz = inp.readFloat()
                if (!(wagi + skale + srednie + wyraz).all { it.isFinite() }) return null
                WeryfikatorGlosu(srednie, skale, wagi, wyraz)
            }
        }.getOrNull()

        /**
         * Uczy weryfikator. Deterministycznie: te same dane dają te same wagi.
         *
         * @throws IllegalArgumentException gdy brak którejś klasy albo wymiary się różnią
         */
        fun naucz(przyklady: List<Przyklad>, parametry: Parametry = Parametry()): WeryfikatorGlosu {
            require(przyklady.any { it.pozytywny }) { "brak pozytywów" }
            require(przyklady.any { !it.pozytywny }) { "brak negatywów" }
            val d = przyklady.first().cechy.size
            require(d > 0 && przyklady.all { it.cechy.size == d }) { "przykłady mają różne wymiary" }
            val n = przyklady.size

            // Standaryzacja z dodatkowym 1/sqrt(d).
            val srednie = DoubleArray(d)
            for (p in przyklady) for (j in 0 until d) srednie[j] += p.cechy[j].toDouble()
            for (j in 0 until d) srednie[j] = srednie[j] / n
            val wariancje = DoubleArray(d)
            for (p in przyklady) for (j in 0 until d) {
                val r = p.cechy[j] - srednie[j]
                wariancje[j] += r * r
            }
            val pierwD = sqrt(d.toDouble())
            val skale = DoubleArray(d) { j ->
                1.0 / (maxOf(sqrt(wariancje[j] / n), parametry.minOdchylenie) * pierwD)
            }
            val x = Array(n) { i ->
                val c = przyklady[i].cechy
                FloatArray(d) { j -> ((c[j] - srednie[j]) * skale[j]).toFloat() }
            }
            val y = DoubleArray(n) { if (przyklady[it].pozytywny) 1.0 else 0.0 }

            // Wagi przykładów: każda klasa waży łącznie 1/2.
            val sumaPoz = przyklady.filter { it.pozytywny }.sumOf { it.waga.toDouble() }
            val sumaNeg = przyklady.filter { !it.pozytywny }.sumOf { it.waga.toDouble() }
            require(sumaPoz > 0 && sumaNeg > 0) { "wagi klas muszą być dodatnie" }
            val c = DoubleArray(n) { i ->
                val p = przyklady[i]
                p.waga / (2.0 * if (p.pozytywny) sumaPoz else sumaNeg)
            }

            // Hesjan straty <= 0,25 * max ||x||^2 (suma wag = 1) + lambda, więc
            // krok 1/L jest bezpieczny bez strojenia.
            var maksNorma = 0.0
            for (wiersz in x) {
                var s = 0.0
                for (v in wiersz) s += v.toDouble() * v
                if (s > maksNorma) maksNorma = s
            }
            val lipschitz = 0.25 * (maksNorma + 1.0) + parametry.lambda
            val krok = 1.0 / lipschitz

            var w = DoubleArray(d)
            var b = 0.0
            var wPoprz = DoubleArray(d)
            var bPoprz = 0.0
            val v = DoubleArray(d)
            val grad = DoubleArray(d)
            for (k in 1..parametry.iteracje) {
                // Punkt z pędem Nesterova (wyraz wolny traktujemy jak cechę równą 1).
                val beta = (k - 1.0) / (k + 2.0)
                for (j in 0 until d) v[j] = w[j] + beta * (w[j] - wPoprz[j])
                val vb = b + beta * (b - bPoprz)

                java.util.Arrays.fill(grad, 0.0)
                var gradB = 0.0
                for (i in 0 until n) {
                    val wiersz = x[i]
                    var z = vb
                    for (j in 0 until d) z += v[j] * wiersz[j]
                    val e = (sigmoida(z) - y[i]) * c[i]
                    if (e == 0.0) continue
                    for (j in 0 until d) grad[j] += e * wiersz[j]
                    gradB += e
                }
                wPoprz = w
                bPoprz = b
                w = DoubleArray(d) { j -> v[j] - krok * (grad[j] + parametry.lambda * v[j]) }
                b = vb - krok * gradB
            }

            return WeryfikatorGlosu(
                srednie = FloatArray(d) { srednie[it].toFloat() },
                skale = FloatArray(d) { skale[it].toFloat() },
                wagi = FloatArray(d) { w[it].toFloat() },
                wyraz = b.toFloat()
            )
        }

        /**
         * Walidacja krzyżowa "odłóż jedno nagranie frazy".
         *
         * Runda k odkłada k-te nagranie frazy (całą grupę) i k-ty ciągły kawałek
         * tła, uczy na reszcie i sprawdza odłożone: nagranie frazy przyjęte, gdy
         * jego GŁÓWNE okno przejdzie próg; tło - każde okno osobno. Ciągłe
         * kawałki, nie losowanie, bo sąsiednie okna tła nakładają się w 15/16 i
         * losowy podział sprawdzałby model na danych, które prawie widział.
         *
         * @return `null`, gdy nagrań frazy jest mniej niż 2 (nie ma czego odkładać)
         */
        fun walidacjaKrzyzowa(
            przyklady: List<Przyklad>,
            parametry: Parametry = Parametry(),
            postep: (Int, Int) -> Unit = { _, _ -> }
        ): Walidacja? {
            val grupyPoz = przyklady.filter { it.pozytywny }.map { it.grupa }.distinct().sorted()
            val negatywy = przyklady.filter { !it.pozytywny }
            val rundy = grupyPoz.size
            if (rundy < 2 || negatywy.size < rundy) return null

            var przyjetePoz = 0
            var sprawdzonePoz = 0
            var przyjeteNeg = 0
            var sprawdzoneNeg = 0
            grupyPoz.forEachIndexed { k, grupa ->
                postep(k, rundy)
                val odNeg = negatywy.size * k / rundy
                val doNeg = negatywy.size * (k + 1) / rundy
                val testNeg = negatywy.subList(odNeg, doNeg).toSet()
                val uczace = przyklady.filter {
                    if (it.pozytywny) it.grupa != grupa else it !in testNeg
                }
                val model = naucz(uczace, parametry)
                przyklady.filter { it.pozytywny && it.grupa == grupa && it.glowny }.forEach {
                    sprawdzonePoz++
                    if (model.ocen(it.cechy) >= PROG_AKCEPTACJI) przyjetePoz++
                }
                testNeg.forEach {
                    sprawdzoneNeg++
                    if (model.ocen(it.cechy) >= PROG_AKCEPTACJI) przyjeteNeg++
                }
            }
            postep(rundy, rundy)
            return Walidacja(
                rund = rundy,
                trafnoscPozytywow = if (sprawdzonePoz == 0) 0f else przyjetePoz.toFloat() / sprawdzonePoz,
                falszyweAkceptacje = if (sprawdzoneNeg == 0) 0f else przyjeteNeg.toFloat() / sprawdzoneNeg
            )
        }

        private fun sigmoida(z: Double): Double =
            if (z >= 0) 1.0 / (1.0 + exp(-z)) else exp(z).let { it / (1.0 + it) }
    }
}
