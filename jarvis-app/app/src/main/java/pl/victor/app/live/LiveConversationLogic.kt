package pl.victor.app.live

/**
 * Decyzje trybu "rozmowa na żywo" - czysta logika, bez Androida.
 *
 * Pętla w [LiveConversationMode] tylko pyta i wykonuje; wszystko, co da się
 * pomylić (czy wolno zacząć, którą drogą dźwięku, kiedy skończyć po ciszy,
 * czy mikrofon ma iść do serwera w trakcie odpowiedzi), jest tutaj, żeby
 * dało się to sprawdzić testem bez okularów.
 */
object LiveConversationLogic {

    /** Po tylu ms bez słowa z żadnej strony rozmowa kończy się sama. */
    const val CISZA_KOŃCZY_MS = 120_000L

    /** Ile najwyżej czekamy na łącze rozmowy (SCO) okularów, zanim przejdziemy na telefon. */
    const val SCO_LIMIT_MS = 5_000L

    // ---------- czy wolno zacząć ----------

    /** Dlaczego rozmowy na żywo nie da się zacząć - albo [MOŻNA]. */
    enum class Dostępność(val komunikat: String?) {
        MOŻNA(null),
        TYLKO_DARMOWE(
            "Rozmowa na żywo jest płatna, a w ustawieniach włączone są tylko darmowe funkcje. " +
                "Pytaj mnie jak zwykle."
        ),
        WYŁĄCZONA(
            "Rozmowa na żywo jest wyłączona w ustawieniach. Pytaj mnie jak zwykle."
        ),
        BRAK_KLUCZA(
            "Rozmowa na żywo wymaga klucza Gemini. Dodaj go w ustawieniach. Do tego czasu " +
                "pytaj mnie jak zwykle."
        ),
        BRAK_MIKROFONU(
            "Brak zgody na mikrofon. Otwórz aplikację i naciśnij przycisk Powiedz - system " +
                "zapyta o uprawnienie."
        )
    }

    /**
     * Czy wolno zacząć. Kolejność ma znaczenie dla komunikatu: "tylko
     * darmowe" wygrywa z wyłączonym przełącznikiem, bo przełącznik w
     * Ustawieniach jest wtedy wyszarzony i nie da się go "po prostu włączyć".
     *
     * Nie zależy od aktywnego dostawcy - rozmowa idzie zawsze przez Gemini.
     */
    fun dostępność(
        tylkoDarmowe: Boolean,
        włączona: Boolean,
        kluczGemini: String?,
        zgodaNaMikrofon: Boolean
    ): Dostępność = when {
        tylkoDarmowe -> Dostępność.TYLKO_DARMOWE
        !włączona -> Dostępność.WYŁĄCZONA
        kluczGemini.isNullOrBlank() -> Dostępność.BRAK_KLUCZA
        !zgodaNaMikrofon -> Dostępność.BRAK_MIKROFONU
        else -> Dostępność.MOŻNA
    }

    // ---------- droga dźwięku ----------

    enum class TrybDźwięku(val opis: String) {
        /** Mikrofon i głośnik okularów przez profil rozmowy - z systemowym tłumieniem echa. */
        SCO("okulary (SCO)"),

        /** Mikrofon telefonu, odpowiedź jak zwykła mowa asystenta, z bramką echa. */
        TELEFON("telefon")
    }

    data class WybórDźwięku(val tryb: TrybDźwięku, val powód: String)

    /**
     * Którędy idzie dźwięk rozmowy.
     *
     * @param maZestawBluetooth czy telefon widzi podłączony zestaw audio (okulary)
     * @param łączePrzyjęte wynik prośby o łącze rozmowy: `null` = nie
     *   zmieściła się w [SCO_LIMIT_MS], `false` = odmowa (np. mikrofon okularów
     *   wyłączony w Ustawieniach)
     * @param łączeWstało czy system potwierdził, że łącze SCO faktycznie stoi
     */
    fun wybierzDźwięk(
        maZestawBluetooth: Boolean,
        łączePrzyjęte: Boolean?,
        łączeWstało: Boolean
    ): WybórDźwięku = when {
        !maZestawBluetooth -> WybórDźwięku(TrybDźwięku.TELEFON, "brak zestawu Bluetooth")
        łączePrzyjęte == null ->
            WybórDźwięku(TrybDźwięku.TELEFON, "łącze SCO nie odpowiedziało w ${SCO_LIMIT_MS / 1000} s")
        !łączePrzyjęte ->
            WybórDźwięku(TrybDźwięku.TELEFON, "łącze SCO odmówione (mikrofon okularów wyłączony albo brak profilu rozmowy)")
        !łączeWstało ->
            WybórDźwięku(TrybDźwięku.TELEFON, "łącze SCO nie wstało w ${SCO_LIMIT_MS / 1000} s")
        else -> WybórDźwięku(TrybDźwięku.SCO, "łącze SCO stoi")
    }

    // ---------- koniec po ciszy ----------

    /**
     * Czy pora skończyć, bo nikt nic nie mówi. Aktywnością jest słowo
     * użytkownika (z transkrypcji wejścia) i każda odpowiedź asystenta;
     * w trakcie odpowiedzi nigdy nie kończymy.
     */
    fun czasKończyćPoCiszy(
        terazMs: Long,
        ostatniaAktywnośćMs: Long,
        asystentMówi: Boolean,
        limitMs: Long = CISZA_KOŃCZY_MS
    ): Boolean = !asystentMówi && terazMs - ostatniaAktywnośćMs >= limitMs

    // ---------- minuty do dziennika ----------

    /** Minuty dźwięku z liczby kawałków po [kawałekMs] - zaokrąglone do setnych. */
    fun minutyZKawałków(kawałków: Int, kawałekMs: Int): Double =
        Math.round(kawałków.toLong() * kawałekMs / 600.0) / 100.0

    /** Minuty PCM 16-bit mono z liczby bajtów - zaokrąglone do setnych. */
    fun minutyZBajtów(bajtów: Long, próbkowanie: Int): Double =
        Math.round(bajtów / 2.0 / próbkowanie * 100.0 / 60.0) / 100.0
}

/**
 * Bramka echa dla rozmowy przez TELEFON (bez SCO): czy kawałek z mikrofonu
 * ma iść do serwera.
 *
 * ## Po co
 * Przez SCO echo tłumi system (źródło `VOICE_COMMUNICATION`). Na telefonie
 * odpowiedź gra z głośnika albo z okularów przez A2DP, a mikrofon telefonu
 * ją słyszy - serwer brałby własny głos asystenta za wejście w słowo i
 * przerywał sam siebie. Jak w tłumaczeniu na żywo (bieg 183): na czas
 * słyszalnej odpowiedzi serwer dostaje ciszę.
 *
 * ## Czym się różni od tłumaczenia
 * Tu wolno wejść w słowo. Głośny głos tuż przy telefonie (poziom od
 * [prógPrzebicia] przez [ramekPrzebicia] kawałki z rzędu) przechodzi mimo
 * odpowiedzi - i potem przez [trzymajMs], żeby serwer dostał całe zdanie, a
 * nie sam początek. Bezpiecznik jak w tłumaczeniu: wyciszenie dłużej niż
 * [maksWyciszeniaMs] to już błąd, nie echo jednej odpowiedzi.
 */
class BramkaEcha(
    private val prógPrzebicia: Int = PRÓG_PRZEBICIA,
    private val ramekPrzebicia: Int = 2,
    private val trzymajMs: Long = 1_500L,
    private val maksWyciszeniaMs: Long = 30_000L
) {
    private var głośnychZRzędu = 0
    private var otwartaDoMs = 0L
    private var wyciszaOdMs: Long? = null

    /** Ile razy człowiek przebił się przez odpowiedź - do dziennika. */
    var przebicia = 0
        private set

    /**
     * @param asystentMówi czy gra słyszalna odpowiedź (z ogonem echa)
     * @param poziom RMS kawałka z mikrofonu
     * @return `true` - wyślij mikrofon, `false` - wyślij ciszę
     */
    fun przepuść(terazMs: Long, asystentMówi: Boolean, poziom: Int): Boolean {
        if (!asystentMówi) {
            głośnychZRzędu = 0
            wyciszaOdMs = null
            return true
        }
        if (terazMs < otwartaDoMs) return true
        głośnychZRzędu = if (poziom >= prógPrzebicia) głośnychZRzędu + 1 else 0
        if (głośnychZRzędu >= ramekPrzebicia) {
            głośnychZRzędu = 0
            otwartaDoMs = terazMs + trzymajMs
            wyciszaOdMs = null
            przebicia++
            return true
        }
        val od = wyciszaOdMs ?: terazMs.also { wyciszaOdMs = it }
        return terazMs - od > maksWyciszeniaMs
    }

    companion object {
        /**
         * RMS, od którego mowa przy telefonie przebija echo odpowiedzi (~ -22
         * dBFS). Echo z głośnika telefonu w jego własnym mikrofonie bywa
         * głośne, więc próg jest wysoko - wejść w słowo trzeba wyraźnie.
         */
        const val PRÓG_PRZEBICIA = 2_500
    }
}

/**
 * Składa przyrosty transkrypcji jednej wymiany: co powiedział użytkownik i co
 * odpowiedział asystent. Gemini przysyła tekst kawałkami, a "koniec rozmowy"
 * potrafi przyjść jako "Koniec" i " rozmowy." w dwóch wiadomościach.
 */
class LiveExchangeBuffer {
    private val użytkownik = StringBuilder()
    private val asystent = StringBuilder()

    val użytkownikTeraz: String get() = użytkownik.toString().replace(Regex("\\s+"), " ").trim()
    val asystentTeraz: String get() = asystent.toString().replace(Regex("\\s+"), " ").trim()

    /**
     * Dokłada słowa użytkownika. Nowa wypowiedź po odpowiedzi asystenta to
     * nowa wymiana - zwłaszcza po wejściu w słowo, po którym serwer nie
     * zawsze kończy turę znacznikiem.
     *
     * @return poprzednia, właśnie zamknięta wymiana albo `null`
     */
    fun dodajUżytkownika(tekst: String): Pair<String, String>? {
        val zamknięta = if (asystent.isNotEmpty()) zamknij() else null
        użytkownik.append(tekst)
        return zamknięta
    }

    fun dodajAsystenta(tekst: String) {
        asystent.append(tekst)
    }

    /** Zamyka wymianę: para (pytanie, odpowiedź) albo `null`, gdy brak którejś strony. */
    fun zamknij(): Pair<String, String>? {
        val u = użytkownikTeraz
        val a = asystentTeraz
        użytkownik.clear()
        asystent.clear()
        return if (u.isEmpty() || a.isEmpty()) null else u to a
    }
}

/**
 * Rozpoznaje "koniec rozmowy" w transkrypcji wejścia, która przychodzi
 * kawałkami.
 *
 * ## Dlaczego z opóźnieniem
 * Transkrypcja rośnie słowo po słowie, a polecenie ma stać na KOŃCU
 * wypowiedzi. "Koniec rozmowy był dziwny" w chwili, gdy przyszło dopiero
 * "koniec rozmowy", wygląda jak polecenie. Kończymy więc dopiero, gdy przez
 * [potwierdzenieMs] nie dopłynęło nic, co by je zmieniło.
 *
 * ## Dlaczego ogon, a nie jedna wymiana
 * Kolejność transkrypcji względem odpowiedzi nie jest gwarantowana
 * (dokumentacja Live API), więc "Koniec" i " rozmowy" potrafią wpaść po dwóch
 * stronach początku odpowiedzi. Ogon czyścimy dopiero na końcu tury serwera
 * ([koniecTury]) - wtedy cała wypowiedź użytkownika już dotarła.
 */
class WykrywaczKońca(
    private val czyKoniec: (String) -> Boolean,
    private val potwierdzenieMs: Long = POTWIERDZENIE_MS,
    private val długośćOgona: Int = 160
) {
    private val ogon = StringBuilder()
    private var kandydatOdMs: Long? = null

    /** Ostatnie słowa użytkownika - do dziennika. */
    val ogonTeraz: String get() = ogon.toString().replace(Regex("\\s+"), " ").trim()

    fun usłyszane(tekst: String, terazMs: Long) {
        ogon.append(tekst)
        if (ogon.length > długośćOgona) ogon.delete(0, ogon.length - długośćOgona)
        kandydatOdMs = if (czyKoniec(ogonTeraz)) {
            kandydatOdMs ?: terazMs
        } else {
            null
        }
    }

    /**
     * Serwer skończył turę - następna wypowiedź zaczyna się od zera. Kandydat
     * zostaje: po "koniec rozmowy" serwer zwykle od razu odpowiada, i to nie
     * może skasować polecenia.
     */
    fun koniecTury() {
        if (kandydatOdMs == null) ogon.clear()
    }

    fun czasKończyć(terazMs: Long): Boolean {
        val od = kandydatOdMs ?: return false
        return terazMs - od >= potwierdzenieMs
    }

    companion object {
        const val POTWIERDZENIE_MS = 700L
    }
}
