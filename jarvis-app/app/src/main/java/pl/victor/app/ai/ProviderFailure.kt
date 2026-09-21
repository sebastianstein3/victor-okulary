package pl.victor.app.ai

/**
 * Zamienia błąd dostawcy AI na jedno zdanie, które da się powiedzieć na głos.
 *
 * ## Po co osobna klasa
 * Bo to jedyna informacja, jaką użytkownik w okularach dostanie o nieudanej
 * turze. Błąd lądował wcześniej wyłącznie na ekranie telefonu - a ktoś z
 * telefonem w kieszeni widział wtedy dokładnie to samo, co przy awarii:
 * ciszę. Zgłoszone jako "często jest brak odpowiedzi", przy opłaconych kontach
 * DeepSeeka i Gemini. Odpowiedź była, tylko nie po polsku i nie na głos.
 *
 * Rozpoznajemy tylko te powody, po których użytkownik może COŚ ZROBIĆ: brak
 * środków, limit zapytań, zły klucz, brak sieci. Reszta dostaje zdanie ogólne,
 * bo czytanie na głos treści odpowiedzi HTTP nikomu nie pomoże.
 *
 * Dopasowanie idzie po fragmentach, nie po całych komunikatach: każdy dostawca
 * pisze je inaczej, a kod stanu i angielskie słowo kluczowe są tym, co się
 * powtarza.
 */
object ProviderFailure {

    /**
     * @param message treść wyjątku dostawcy (bywa `null`)
     * @param retryable czy dostawca sam uznał błąd za przejściowy
     */
    fun describe(message: String?, retryable: Boolean = false): String {
        val text = (message ?: "").lowercase()
        return when {
            NO_FUNDS.any { it in text } ->
                "Dostawca AI odmówił: konto nie ma środków albo przekroczyło limit. " +
                    "Sprawdź to w ustawieniach."
            RATE_LIMITED.any { it in text } ->
                "Za dużo zapytań naraz - dostawca kazał chwilę odczekać. Spróbuj za moment."
            BAD_KEY.any { it in text } ->
                "Klucz API nie został przyjęty. Sprawdź go w ustawieniach."
            NO_NETWORK.any { it in text } ->
                "Nie mam połączenia z siecią, więc nie zapytam modelu."
            // NAJPIERW, przed kluczem: odpowiedź o nieznanym modelu bywa
            // niesiona kodem 404, ale zdarza się i 400 razem ze słowem
            // "permission" - a wtedy komunikat o kluczu wysyłałby człowieka
            // sprawdzać coś, co jest w porządku.
            UNKNOWN_MODEL.any { it in text } ->
                "Wybrany model nie istnieje u tego dostawcy. Zmień go w " +
                    "ustawieniach, w sekcji Model AI."
            TRUNCATED.any { it in text } ->
                "Model zużył cały budżet na rozumowanie i nic nie powiedział. " +
                    "Wybierz w ustawieniach model bez rozumowania."
            retryable -> "Model nie odpowiedział. Spróbuj jeszcze raz."
            else -> "Model odmówił odpowiedzi. Sprawdź ustawienia dostawcy AI."
        }
    }

    /**
     * Czy to odmowa z powodu SAMEJ NAZWY MODELU.
     *
     * ## Po co osobne pytanie, skoro [describe] już to rozpoznaje
     * Bo tamto układa zdanie dla człowieka, a to podejmuje decyzję: model,
     * którego dostawca nie zna, nie zacznie istnieć w tej samej sesji. Dalsze
     * pytanie go co turę to czysty koszt.
     *
     * Z dziennika użytkownika: od 21:11 KAŻDA tura zaczynała się od
     * `gemini-2.5-flash-lite` i kończyła kodem 404, po czym szła na kolejnego
     * dostawcę. Dwadzieścia kilka razy pod rząd, za każdym razem ta sama
     * odpowiedź serwera - i za każdym razem opóźnienie doliczone do czasu
     * odpowiedzi, o który użytkownik się skarżył.
     *
     * Kolejność sprawdzeń jest ta sama co w [describe]: BRAK ŚRODKÓW i LIMIT
     * mają pierwszeństwo, bo niosą czasem ten sam kod 404 co nieznany model,
     * a uznanie pustego konta za "zły model" wysłałoby człowieka nie tam.
     */
    fun isMissingModel(message: String?): Boolean {
        val text = (message ?: "").lowercase()
        if (NO_FUNDS.any { it in text } || RATE_LIMITED.any { it in text }) return false
        return UNKNOWN_MODEL.any { it in text }
    }

    /**
     * Czy ponawianie tego zapytania NIE MA SENSU, dopóki człowiek czegoś nie zmieni.
     *
     * ## Po co to rozróżnienie
     * Bo tryby ciągłe (opis otoczenia, nawigacja) pytają model w pętli. Przy
     * zerwanej sieci ponowienie za chwilę jest właściwym zachowaniem - sieć
     * wraca sama. Przy pustym koncie albo odrzuconym kluczu nie wróci nic:
     * pętla dobija się wtedy do serwera co półtorej sekundy w nieskończoność,
     * a użytkownik słyszy w kółko ten sam komunikat.
     *
     * Limit zapytań celowo NIE jest tu trwały: to jest właśnie ten przypadek,
     * w którym odczekanie pomaga.
     */
    fun isPermanent(message: String?): Boolean {
        val text = (message ?: "").lowercase()
        return NO_FUNDS.any { it in text } || BAD_KEY.any { it in text }
    }

    // Kolejność list ma znaczenie tylko tyle, ile kolejność gałęzi wyżej:
    // "insufficient balance" DeepSeeka niesie czasem i 402, i 429 w tej samej
    // odpowiedzi, a brak środków jest wtedy prawdziwszym powodem niż limit.
    private val NO_FUNDS = listOf(
        "402", "insufficient", "balance", "quota", "billing", "exceeded your current"
    )
    /**
     * Nieistniejąca nazwa modelu.
     *
     * Z dziennika z 15 września: w ustawieniach siedziały `deepseek-flash` i
     * `gemini-3.8-flash`. Obie próby padały po 70 MILISEKUNDACH - tyle trwa
     * odrzucenie przez serwer - aplikacja schodziła na model lokalny, a ten
     * potrzebował 94 sekund na pierwszy token. Z zewnątrz: „AI myśli" i nic.
     *
     * Powód był do odczytania z odpowiedzi HTTP przez cały czas; nikt go tylko
     * nie pokazywał. To jest dokładnie ten rodzaj awarii, z którym człowiek
     * sobie poradzi w dziesięć sekund - pod warunkiem, że wie, co się stało.
     */
    private val UNKNOWN_MODEL = listOf(
        "404", "model not found", "model_not_found", "is not found",
        "does not exist", "unknown model", "invalid model", "unsupported model",
        "no such model"
    )
    private val RATE_LIMITED = listOf("429", "rate limit", "rate_limit", "too many requests")
    private val BAD_KEY = listOf(
        "401", "403", "api key", "api_key", "unauthorized", "invalid_api_key",
        "permission_denied"
    )
    // "length" to koniec generowania na limicie tokenów. U modeli rozumujących
    // potrafi zejść na samo myślenie, a odpowiedź wraca pusta - patrz
    // DeepSeekProvider.parseResponse.
    private val TRUNCATED = listOf("finish_reason=length", "finish_reason\":\"length")
    private val NO_NETWORK = listOf(
        "timeout", "timed out", "unable to resolve host", "failed to connect",
        "network is unreachable", "no address associated", "connection reset"
    )
}
