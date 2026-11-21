package pl.victor.app.wakeword

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Ocena nagrania do nauki głosu - czy nadaje się na przykład dla weryfikatora.
 * Bez Androida.
 *
 * ## Po co
 * Weryfikator uczy się na 6-10 nagraniach. Jedno złe (cisza, przester, fraza
 * ucięta, "Hej Wiktor" powiedziane przed napisem "Mów teraz") to 10-15% danych
 * pozytywnych - wystarczy, żeby nauczył się czegoś innego niż fraza. Taniej
 * odrzucić je od razu i poprosić o powtórkę, z konkretnym powodem.
 *
 * ## Jak
 * Ramki po 20 ms, poziom każdej w dBFS. Tło = 10. percentyl poziomów ramek
 * (w nagraniu frazy większość czasu to cisza przed i po). Mowa = ramki
 * głośniejsze od tła o [PRZEWAGA_MOWY_DB] i od absolutnego minimum
 * [MIN_MOWA_DBFS]; początek i koniec mowy to pierwsza i ostatnia seria co
 * najmniej [MIN_SERIA] takich ramek (pojedyncze stuknięcie nie jest mową).
 */
object OcenaNagrania {

    /** Ramka analizy: 20 ms przy 16 kHz. */
    const val RAMKA = 320
    private const val MS_RAMKI = 20

    /** Próbka uznana za przesterowaną. */
    const val PRZESTER = 32_000

    const val PRZEWAGA_MOWY_DB = 10f
    const val MIN_MOWA_DBFS = -55f

    /** Ile ramek z rzędu (60 ms) to już początek mowy. */
    const val MIN_SERIA = 3

    /** Mowa w nagraniu frazy: poniżej tego poziomu (średnio) - "za cicho". */
    const val ZA_CICHO_MOWA_DBFS = -42f

    /** Szczyt poniżej tego - "za cicho" niezależnie od reszty. */
    const val ZA_CICHO_SZCZYT_DBFS = -32f

    /** Najkrótsza mowa, którą uznajemy za frazę. */
    const val MIN_MOWA_FRAZY_MS = 250

    /** Najdłuższa - dłuższa to raczej zdanie niż "Hej Wiktor". */
    const val MAX_MOWA_FRAZY_MS = 2_200

    /**
     * Ile ms na początku nagrania ma być ciszą - to z niego potok dostaje
     * "tło przed frazą" ([NaukaGlosuDane.zWstepem]); UI pokazuje "Mów teraz" dopiero później.
     */
    const val CISZA_NA_POCZATKU_MS = 300

    /** Mowa kończąca się bliżej końca nagrania niż tyle - prawdopodobnie ucięta. */
    const val MARGINES_KONCA_MS = 150

    /** Najkrótsze sensowne nagranie tła. */
    const val MIN_TLO_MS = 10_000

    /** Ile mowy/dźwięku musi być w nagraniu tła. */
    const val MIN_AKTYWNOSC_TLA_MS = 3_000

    enum class Werdykt(val komunikat: String) {
        DOBRE("Dobre nagranie."),
        ZA_CICHO("Za cicho - mów głośniej albo bliżej mikrofonu."),
        ZA_GLOSNO("Za głośno - dźwięk przesterowany, odsuń telefon albo mów ciszej."),
        BRAK_MOWY("Nie słychać mowy - spróbuj jeszcze raz."),
        ZA_DLUGO("Za długo jak na „Hej Wiktor” - powiedz samą frazę."),
        ZA_WCZESNIE("Za wcześnie - zacznij mówić dopiero po napisie „Mów teraz”."),
        UCIETE("Fraza ucięta na końcu - mów od razu po napisie."),
        ZA_KROTKO("Nagranie za krótkie - potrzeba co najmniej 10 s.")
    }

    data class Ocena(
        val werdykt: Werdykt,
        val dlugoscMs: Int,
        val szczytDbfs: Float,
        val tloDbfs: Float,
        /** Średni poziom ramek mowy; `null`, gdy mowy nie było. */
        val mowaDbfs: Float?,
        val mowaMs: Int,
        val poczatekMowyMs: Int?,
        val koniecMowyMs: Int?,
        val przesterowanych: Int
    ) {
        val dobre: Boolean get() = werdykt == Werdykt.DOBRE

        /** Pola do dziennika diagnostycznego. */
        fun doDziennika(): Map<String, Any?> = mapOf(
            "ocena" to werdykt.name,
            "długośćMs" to dlugoscMs,
            "szczytDb" to "%.1f".format(szczytDbfs),
            "tłoDb" to "%.1f".format(tloDbfs),
            "mowaDb" to mowaDbfs?.let { "%.1f".format(it) },
            "mowaMs" to mowaMs,
            "początekMs" to poczatekMowyMs,
            "koniecMs" to koniecMowyMs,
            "przester" to przesterowanych.takeIf { it > 0 }
        )
    }

    /** Ocena jednego nagrania frazy „Hej Wiktor”. */
    fun ocenFraze(probki: ShortArray, sampleRate: Int = OwwPipeline.SAMPLE_RATE): Ocena {
        val a = analizuj(probki, sampleRate)
        val werdykt = when {
            a.przesterowanych > maxOf(20, probki.size / 500) -> Werdykt.ZA_GLOSNO
            a.szczytDbfs < ZA_CICHO_SZCZYT_DBFS -> Werdykt.ZA_CICHO
            a.mowaMs < MIN_MOWA_FRAZY_MS -> Werdykt.BRAK_MOWY
            (a.mowaDbfs ?: -120f) < ZA_CICHO_MOWA_DBFS -> Werdykt.ZA_CICHO
            (a.poczatekMowyMs ?: 0) < CISZA_NA_POCZATKU_MS -> Werdykt.ZA_WCZESNIE
            (a.koniecMowyMs ?: 0) > a.dlugoscMs - MARGINES_KONCA_MS -> Werdykt.UCIETE
            a.mowaMs > MAX_MOWA_FRAZY_MS -> Werdykt.ZA_DLUGO
            else -> Werdykt.DOBRE
        }
        return a.copy(werdykt = werdykt)
    }

    /**
     * Ocena nagrania tła (zwykła mowa użytkownika, dźwięki domu). Łagodniejsza:
     * liczy się, żeby coś było słychać i nie było przesteru.
     */
    fun ocenTlo(probki: ShortArray, sampleRate: Int = OwwPipeline.SAMPLE_RATE): Ocena {
        val a = analizuj(probki, sampleRate)
        val werdykt = when {
            a.dlugoscMs < MIN_TLO_MS -> Werdykt.ZA_KROTKO
            a.przesterowanych > maxOf(50, probki.size / 200) -> Werdykt.ZA_GLOSNO
            a.szczytDbfs < ZA_CICHO_SZCZYT_DBFS - 15f -> Werdykt.ZA_CICHO
            a.mowaMs < MIN_AKTYWNOSC_TLA_MS -> Werdykt.BRAK_MOWY
            else -> Werdykt.DOBRE
        }
        return a.copy(werdykt = werdykt)
    }

    private fun analizuj(probki: ShortArray, sampleRate: Int): Ocena {
        val ramka = RAMKA * sampleRate / OwwPipeline.SAMPLE_RATE
        val nRamek = probki.size / ramka
        val poziomy = FloatArray(nRamek)
        var szczyt = 0
        var przester = 0
        for (s in probki) {
            val a = kotlin.math.abs(s.toInt())
            if (a > szczyt) szczyt = a
            if (a >= PRZESTER) przester++
        }
        for (r in 0 until nRamek) {
            var suma = 0.0
            for (i in r * ramka until (r + 1) * ramka) {
                val v = probki[i].toDouble()
                suma += v * v
            }
            poziomy[r] = dbfs(sqrt(suma / ramka))
        }
        val dlugoscMs = (probki.size.toLong() * 1000 / sampleRate).toInt()
        val szczytDb = dbfs(szczyt.toDouble())
        if (nRamek == 0) {
            return Ocena(Werdykt.BRAK_MOWY, dlugoscMs, szczytDb, szczytDb, null, 0, null, null, przester)
        }
        val tlo = poziomy.sorted()[(nRamek - 1) / 10]
        val progMowy = maxOf(tlo + PRZEWAGA_MOWY_DB, MIN_MOWA_DBFS)
        val mowa = BooleanArray(nRamek) { poziomy[it] >= progMowy }

        // Początek: pierwsza seria MIN_SERIA ramek mowy; koniec: ostatnia.
        var poczatek: Int? = null
        var koniec: Int? = null
        var seria = 0
        for (r in 0 until nRamek) {
            seria = if (mowa[r]) seria + 1 else 0
            if (seria >= MIN_SERIA) {
                if (poczatek == null) poczatek = r - MIN_SERIA + 1
                koniec = r
            }
        }
        var liczba = 0
        var sumaMocy = 0.0
        if (poczatek != null && koniec != null) {
            for (r in poczatek..koniec) if (mowa[r]) {
                liczba++
                sumaMocy += Math.pow(10.0, poziomy[r] / 10.0)
            }
        }
        val mowaDb = if (liczba == 0) null else (10 * log10(sumaMocy / liczba)).toFloat()
        return Ocena(
            werdykt = Werdykt.DOBRE,
            dlugoscMs = dlugoscMs,
            szczytDbfs = szczytDb,
            tloDbfs = tlo,
            mowaDbfs = mowaDb,
            mowaMs = liczba * MS_RAMKI,
            poczatekMowyMs = poczatek?.let { it * MS_RAMKI },
            koniecMowyMs = koniec?.let { (it + 1) * MS_RAMKI },
            przesterowanych = przester
        )
    }

    private fun dbfs(amplituda: Double): Float =
        if (amplituda < 1.0) -100f else (20 * log10(amplituda / 32768.0)).toFloat()
}
