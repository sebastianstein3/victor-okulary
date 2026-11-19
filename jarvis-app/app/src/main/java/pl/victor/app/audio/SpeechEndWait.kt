package pl.victor.app.audio

/**
 * Ile ciszy po zdaniu kończy nasłuch - wybór z Ustawień ("Czekanie na koniec
 * zdania").
 *
 * ## Po co
 * Z dzienników: od "Hej Wiktor" do końca nasłuchu mija zwykle 4-7 s, a spora
 * część tego to czekanie na ciszę po zdaniu (`NASŁUCH koniec ms=5389`). Przy
 * nasłuchu przez profil rozmowy o końcu decydował wyłącznie silnik
 * rozpoznawania w telefonie, z własnym, długim oknem ciszy - ustawić mu
 * krótszego się nie da (patrz komentarz w `SpeechToText.intent`: dodatki z
 * oknami ciszy raz już całkiem wyłączyły mu wykrywanie końca).
 *
 * Dlatego koniec mierzymy sami - po ustaniu wyników częściowych i spadku
 * głośności (patrz `conversation.RecognizerEndpoint`) - i tu jest jedno
 * miejsce, które mówi, ile tej ciszy ma być.
 *
 * ## Co się NIE zmienia
 * Czekanie na POCZĄTEK mowy (karencje [SpeechEnd.MIN_LISTEN_MS], limit bez
 * mowy w silniku, dłuższa cisza po bardzo krótkim dźwięku w [EnergyEndpoint]).
 * Człowiek musi zdążyć zacząć mówić - to są zabezpieczenia po konkretnych
 * zgłoszeniach i nie należą do tego wyboru.
 *
 * ## Dlaczego domyślnie [NORMAL]
 * 1,1 s ciszy to wciąż więcej niż zwykła pauza na oddech w środku zdania, a
 * wyraźnie mniej niż okno silnika. Ucięte pytanie kosztuje całą turę (5-10 s
 * plus powtórzenie), więc oszczędzanie kolejnych 0,2 s domyślnie nie jest
 * warte ryzyka; [SHORT] jest dla krótkich poleceń, [LONG] przywraca dokładnie
 * dawne zachowanie dla kogoś, kto mówi z dłuższymi pauzami.
 */
enum class SpeechEndWait(val id: String, val label: String) {
    SHORT("short", "krótkie"),
    NORMAL("normal", "normalne"),
    LONG("long", "długie");

    /**
     * Cisza po ostatnich rozpoznanych słowach, po której aplikacja sama kończy
     * rozpoznawanie w telefonie. `null` - decyduje silnik, jak dawniej.
     */
    val recognizerSilenceMs: Long?
        get() = when (this) {
            SHORT -> SHORT_MS
            NORMAL -> NORMAL_MS
            LONG -> null
        }

    /** Przerwa w pakietach z okularów kończąca wypowiedź - patrz [SpeechEnd.endsOnSilence]. */
    val glassesPacketSilenceMs: Long
        get() = when (this) {
            SHORT -> SHORT_MS
            NORMAL -> NORMAL_MS
            LONG -> SpeechEnd.SILENCE_ENDS_SPEECH_MS
        }

    /** Cisza w GŁOŚNOŚCI strumienia z okularów kończąca wypowiedź - patrz [EnergyEndpoint]. */
    val glassesEnergySilenceMs: Long
        get() = when (this) {
            SHORT -> SHORT_MS
            NORMAL -> NORMAL_MS
            LONG -> EnergyEndpoint.SILENCE_ENDS_SPEECH_MS
        }

    /** Krótko do dziennika i pod przełącznikiem, np. "normalne (1100 ms)". */
    fun describe(): String = when (val ms = recognizerSilenceMs) {
        null -> "$label (jak dawniej)"
        else -> "$label ($ms ms)"
    }

    companion object {
        const val SHORT_MS = 900L
        const val NORMAL_MS = 1_100L

        val DEFAULT = NORMAL

        /** Wartość z ustawień; nieznana albo pusta - [DEFAULT]. */
        fun fromId(id: String?): SpeechEndWait = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}
