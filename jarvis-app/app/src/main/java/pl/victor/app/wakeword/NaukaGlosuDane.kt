package pl.victor.app.wakeword

/**
 * Okno cech z nagrania (spłaszczone 16 x 96) i wynik ogólnego klasyfikatora frazy dla niego.
 *
 * Okno o indeksie `i` w liście z jednego nagrania kończy się na porcji
 * `featureWindow - 1 + i` (liczonej od zera, z wstępem - patrz [NaukaGlosuDane.zWstepem]).
 */
class OknoCech(val cechy: FloatArray, val wynik: Float)

/**
 * Z nagrań do przykładów uczących weryfikatora - bez Androida.
 *
 * ## Które okno nagrania frazy jest "pozytywem"
 * Nagranie frazy trwa ~3,5 s, a okno klasyfikatora to 1,28 s - większość okien
 * nagrania NIE zawiera całej frazy. Bierzemy okno, w którym ogólny klasyfikator
 * dał najwyższy wynik (tak wybiera je też custom verifier openWakeWord), plus
 * [SASIEDZI] okna przed i po nim: na żywo wykrycie pada na pierwszych porcjach
 * nad progiem, czyli trochę przed szczytem, i weryfikator ma je też znać.
 * Gdy ogólny model daje na tym głosie wszędzie prawie zero, szczyt jest
 * przypadkowy - wtedy wyrównujemy okno do końca mowy z [OcenaNagrania]
 * (okno kończy się ~0,2 s po frazie, jak w treningu modelu ogólnego).
 *
 * ## Negatywy
 * Każde [KROK_NEGATYWOW]-gie okno nagrania tła (sąsiednie różnią się o 80 ms,
 * co drugie wystarcza i o połowę skraca naukę).
 *
 * ## Pierwsze okna nagrania
 * Potok startuje z buforem mel wypełnionym jedynkami - pierwsze ~10 osadzeń
 * jest częściowo sztucznych. Okna z nimi ([POMIN_POCZATKOWE_OKNA]) pomijamy.
 */
object NaukaGlosuDane {

    const val POMIN_POCZATKOWE_OKNA = 10
    const val SASIEDZI = 2
    const val KROK_NEGATYWOW = 2

    /** Wstęp doklejany przed nagranie frazy: 16 porcji (1,28 s). */
    const val WSTEP_PORCJI = 16

    /** Z ilu pierwszych porcji nagrania (240 ms - przed napisem "Mów teraz") robimy wstęp. */
    const val WZOR_WSTEPU_PORCJI = 3

    /** Poniżej tego maksimum ogólnego wyniku wybór okna "po wyniku" jest przypadkowy. */
    const val MIN_WYNIK_DO_WYBORU = 0.02f

    /** O ile ms po końcu mowy ma kończyć się okno wyrównane do końca frazy. */
    const val OKNO_PO_KONCU_MOWY_MS = 200

    /**
     * Dokleja przed nagranie [WSTEP_PORCJI] porcji jego własnego początku (cisza
     * przed frazą), odbijanego tam i z powrotem - bez skoków na łączeniach.
     *
     * Po co: na żywo przed frazą bufor potoku jest pełen prawdziwego tła. W
     * nagraniu frazy byłby pełen jedynek startowych, a pierwsze okna z frazą
     * trafiłyby w [POMIN_POCZATKOWE_OKNA]. Wstęp z tła tego samego nagrania
     * sprawia, że okno z frazą wygląda tak jak w nasłuchu.
     */
    fun zWstepem(
        probki: ShortArray,
        wstepPorcji: Int = WSTEP_PORCJI,
        wzorPorcji: Int = WZOR_WSTEPU_PORCJI
    ): ShortArray {
        val wzor = minOf(wzorPorcji * OwwPipeline.CHUNK, probki.size)
        val wstep = wstepPorcji * OwwPipeline.CHUNK
        if (wzor == 0 || wstep == 0) return probki.copyOf()
        val wynik = ShortArray(wstep + probki.size)
        // Odbicie: 0,1,...,wzor-1,wzor-1,...,0,0,1,... - kończy się tak, żeby
        // ostatnia próbka wstępu sąsiadowała z pierwszą próbką nagrania.
        for (k in 0 until wstep) {
            val odKonca = wstep - 1 - k
            val okres = 2 * wzor
            val f = odKonca % okres
            val idx = if (f < wzor) f else okres - 1 - f
            wynik[k] = probki[idx]
        }
        System.arraycopy(probki, 0, wynik, wstep, probki.size)
        return wynik
    }

    /**
     * Wybór pozytywów z okien jednego nagrania frazy (z wstępem).
     *
     * @property glowne indeks okna głównego
     * @property indeksy wszystkie wybrane (główne i sąsiednie), rosnąco
     * @property wedlugWyniku `true` - wybrane po wyniku modelu ogólnego,
     *   `false` - wyrównane do końca mowy
     * @property maksWynik najwyższy wynik ogólny w nagraniu (do dziennika)
     */
    data class WyborPozytywow(
        val glowne: Int,
        val indeksy: List<Int>,
        val wedlugWyniku: Boolean,
        val maksWynik: Float
    )

    /**
     * @param koniecMowyMs koniec mowy w nagraniu (bez wstępu) z [OcenaNagrania]
     * @return `null`, gdy nagranie jest za krótkie, żeby mieć czyste okno
     */
    fun wybierzPozytywne(
        okna: List<OknoCech>,
        koniecMowyMs: Int?,
        featureWindow: Int = OwwPipeline.FEATURE_WINDOW,
        wstepPorcji: Int = WSTEP_PORCJI
    ): WyborPozytywow? {
        val od = POMIN_POCZATKOWE_OKNA
        if (okna.size <= od) return null
        var maks = od
        for (i in od until okna.size) if (okna[i].wynik > okna[maks].wynik) maks = i
        val maksWynik = okna[maks].wynik
        val wedlugWyniku = maksWynik >= MIN_WYNIK_DO_WYBORU || koniecMowyMs == null
        val glowne = if (wedlugWyniku) {
            maks
        } else {
            val porcjaKonca = wstepPorcji + (koniecMowyMs!! + OKNO_PO_KONCU_MOWY_MS) / 80
            (porcjaKonca - (featureWindow - 1)).coerceIn(od, okna.size - 1)
        }
        val indeksy = ((glowne - SASIEDZI)..(glowne + SASIEDZI)).filter { it >= od && it < okna.size }
        return WyborPozytywow(glowne, indeksy, wedlugWyniku, maksWynik)
    }

    /** Negatywy z okien nagrania tła. */
    fun wybierzNegatywne(okna: List<OknoCech>, krok: Int = KROK_NEGATYWOW): List<FloatArray> =
        (POMIN_POCZATKOWE_OKNA until okna.size step krok).map { okna[it].cechy }

    /** Nagranie frazy po analizie: okna potoku i ocena jakości. */
    class NagranieFrazy(val okna: List<OknoCech>, val ocena: OcenaNagrania.Ocena)

    /**
     * Zbiór uczący z przyjętych nagrań.
     *
     * @property pominiete nagrania frazy, z których nie dało się wybrać okna
     * @property maksWyniki najwyższy wynik ogólny w każdym nagraniu frazy -
     *   pokazuje, jak model ogólny radzi sobie z tym głosem
     * @property wyrownaneDoMowy ile nagrań wyrównano do końca mowy, bo model
     *   ogólny ich "nie słyszał"
     */
    class Zbior(
        val przyklady: List<WeryfikatorGlosu.Przyklad>,
        val pominiete: Int,
        val maksWyniki: List<Float>,
        val wyrownaneDoMowy: Int
    ) {
        val pozytywow: Int get() = przyklady.count { it.pozytywny }
        val negatywow: Int get() = przyklady.count { !it.pozytywny }
        val nagranFrazy: Int get() = maksWyniki.size
    }

    fun zbuduj(frazy: List<NagranieFrazy>, tlo: List<OknoCech>): Zbior {
        val przyklady = mutableListOf<WeryfikatorGlosu.Przyklad>()
        val maksWyniki = mutableListOf<Float>()
        var pominiete = 0
        var wyrownane = 0
        frazy.forEachIndexed { nr, nagranie ->
            val wybor = wybierzPozytywne(nagranie.okna, nagranie.ocena.koniecMowyMs)
            if (wybor == null) {
                pominiete++
                return@forEachIndexed
            }
            maksWyniki += wybor.maksWynik
            if (!wybor.wedlugWyniku) wyrownane++
            wybor.indeksy.forEach { i ->
                przyklady += WeryfikatorGlosu.Przyklad(
                    cechy = nagranie.okna[i].cechy,
                    pozytywny = true,
                    grupa = nr,
                    glowny = i == wybor.glowne
                )
            }
        }
        wybierzNegatywne(tlo).forEach {
            przyklady += WeryfikatorGlosu.Przyklad(it, pozytywny = false, grupa = GRUPA_TLA)
        }
        return Zbior(przyklady, pominiete, maksWyniki, wyrownane)
    }

    /** Ile nagrań frazy trzeba, żeby uczyć - i ile najwyżej zbieramy. */
    const val MIN_NAGRAN = 6
    const val MAX_NAGRAN = 10

    /** Długość jednego nagrania frazy. */
    const val CZAS_FRAZY_MS = 3_500L

    /** Po ilu ms od startu nagrania pokazać "Mów teraz" - patrz [OcenaNagrania.CISZA_NA_POCZATKU_MS]. */
    const val MOW_PO_MS = 700L

    /** Najdłuższe nagranie tła (zalecane 20-30 s). */
    const val CZAS_TLA_MS = 25_000L

    /**
     * Najsłabszy wynik walidacji krzyżowej, z którym zapisujemy weryfikator.
     * Słabszy przy decyzji "mocno odrzuca" mógłby wetować prawdziwe „Hej Wiktor” -
     * lepiej poprosić o nagrania jeszcze raz niż popsuć działającą frazę.
     */
    const val MIN_TRAFNOSC_POZYTYWOW = 0.5f
    const val MIN_TRAFNOSC = 0.65f

    /** Czy weryfikator z taką walidacją można zapisać. `null` (nie było jak sprawdzić) - nie. */
    fun czyWystarczajaca(w: WeryfikatorGlosu.Walidacja?): Boolean =
        w != null && w.trafnoscPozytywow >= MIN_TRAFNOSC_POZYTYWOW && w.trafnosc >= MIN_TRAFNOSC

    /** Grupa negatywów z nagrania tła. */
    const val GRUPA_TLA = -1

    /** Grupa negatywów z oznaczonych fałszywych wybudzeń. */
    const val GRUPA_KOREKT = -2
}
