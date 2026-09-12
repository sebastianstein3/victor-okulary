package pl.victor.app.ble

/**
 * Krótki bufor pakietów z mikrofonu okularów, odkładanych ZANIM zacznie się tura.
 *
 * ## Skąd się bierze problem
 * Okulary zaczynają nadawać dźwięk w chwili wciśnięcia przycisku, a nasłuch po
 * naszej stronie rusza dopiero po jakiejś sekundzie: 500 ms zajmuje okno na
 * rozpoznanie podwójnego kliknięcia (jedno = słuchaj, dwa = zdjęcie), reszta to
 * rozruch. W dzienniku z 12 września widać dokładnie ten odstęp:
 *
 *     09:25:17.118  PRZYCISK  wciśnięto
 *     09:25:17.246  WAKE      okulary nadają dźwięk, choć żadna tura nie trwa  pakietów=1
 *     09:25:18.076  SESJA     --- TURA 05ca ---
 *
 * Pakiety z tego odstępu wyrzucaliśmy, więc początek zdania nie trafiał ani do
 * modelu, ani do rozpoznawania mowy.
 *
 * ## Czemu to jest osobna klasa, a nie pole w [VictorManager]
 * Bo to jedyna część tej naprawy, którą da się sprawdzić bez okularów. Reszta
 * (subskrypcja BLE, dekoder Opusa) wymaga sprzętu; tutaj siedzi cała logika, w
 * której da się pomylić kolejność albo okno czasu - czyli dokładnie to, co
 * kończy się objawem „to nagranie jest niewyraźne".
 *
 * ## Dwa ograniczenia, każde z innego powodu
 * - OKNO CZASU chroni przed sklejeniem pytania z cudzym dźwiękiem. Okulary
 *   nadają także MIĘDZY turami (ten sam wiersz „nadają dźwięk, choć żadna tura
 *   nie trwa" pojawia się po zakończonym nasłuchu), więc bufor bez okna
 *   doklejałby do nowego pytania ogon poprzedniego.
 * - LICZBA PAKIETÓW chroni pamięć, gdy okulary nadają bez przerwy, a nikt
 *   przez godzinę nie zadaje pytania. Okno czasu samo tego nie załatwia, bo
 *   przycinamy je dopiero przy odbiorze.
 *
 * Klasa NIE jest bezpieczna wątkowo - woła ją [VictorManager] pod swoją blokadą.
 */
class MicBacklog(
    private val windowMs: Long = WINDOW_MS,
    private val maxPackets: Int = MAX_PACKETS
) {

    /** Pakiet razem z chwilą, w której NAPRAWDĘ przyszedł. */
    class Packet(val atMs: Long, val payload: ByteArray)

    private val packets = ArrayDeque<Packet>()

    /**
     * Odkłada pakiet i wyrzuca to, co się już nie mieści.
     *
     * Wołane tylko wtedy, gdy nikt nie słucha strumienia - inaczej ten sam
     * pakiet trafiłby i do bufora, i do odbiorcy, czyli dwa razy do nagrania.
     */
    fun add(atMs: Long, payload: ByteArray) {
        packets.addLast(Packet(atMs, payload))
        while (packets.size > maxPackets) packets.removeFirst()
    }

    /**
     * Oddaje pakiety od [sinceMs] wzwyż i CZYŚCI bufor.
     *
     * Czyści cały, nie tylko oddaną część: to, co zostało, jest starsze od
     * pytania, więc nie przyda się nigdy, a zostawione czekałoby na następną
     * turę i tam już wyglądałoby jak świeże.
     *
     * @param sinceMs najwcześniejsza chwila, która należy jeszcze do tego
     *   pytania - zwykle moment wciśnięcia przycisku
     * @param nowMs bieżący czas; starsze niż [windowMs] odpada niezależnie od
     *   [sinceMs], bo wciśnięcie przycisku sprzed minuty nie czyni dźwięku
     *   sprzed minuty częścią pytania
     */
    fun drainSince(sinceMs: Long, nowMs: Long): List<Packet> {
        val cutoff = maxOf(sinceMs, nowMs - windowMs)
        // Kolejność zachowana: ArrayDeque oddaje w kolejności dodawania, a
        // dekoder Opusa składa ramki po kolei. Odwrócenie albo przetasowanie
        // daje szum nie do odróżnienia od zerwanego połączenia.
        val taken = packets.filter { it.atMs >= cutoff }
        packets.clear()
        return taken
    }

    /** Zapomina wszystko - przy rozłączeniu i przy starcie nowego połączenia. */
    fun clear() = packets.clear()

    /** Ile pakietów czeka w buforze. Do dziennika i testów. */
    val size: Int get() = packets.size

    companion object {
        /**
         * Jak stary może być pakiet, żeby jeszcze należał do pytania.
         *
         * Półtorej sekundy, bo odstęp do wyrównania wynosi ~960 ms (500 ms okna
         * na podwójne kliknięcie plus rozruch), a zapas ma pokryć wolniejszy
         * start, nie wpuścić poprzednią wypowiedź.
         */
        const val WINDOW_MS = 1_500L

        /**
         * Górna granica bufora. Przy ~50 pakietach na sekundę półtorej sekundy
         * to około 75 sztuk; dwieście daje zapas na gęstszy strumień i nadal
         * jest pomijalne w pamięci (pakiet ma kilkadziesiąt bajtów).
         */
        const val MAX_PACKETS = 200
    }
}
