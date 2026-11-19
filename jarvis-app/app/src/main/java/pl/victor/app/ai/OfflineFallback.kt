package pl.victor.app.ai

/**
 * Co robi asystent, gdy WSZYSCY dostawcy AI padli z braku sieci.
 *
 * ## Skąd to się wzięło
 * Do tej pory ostatnią deską był model lokalny (llama.cpp + Qwen 0.8B). Z
 * dziennika na Galaxy A23, bez sieci: wczytywał się 13,8 s, generował 19 s i
 * nie oddał ani jednego tokena ("model lokalny: koniec generowania ms=19317
 * byłPierwszyToken=false"). Człowiek czekał pół minuty na ciszę - a płacił za
 * to pobraniem circa 560 MB i bibliotekami natywnymi w APK. Model usunięto.
 *
 * W zamian, bez sieci:
 * 1. Uruchamiamy ten sam awaryjny detektor poleceń, który działa przy braku
 *    klucza API (warstwa 2 w `AIOrchestrator.handleUserTrigger`,
 *    `SmartActionDetector.detect`). Warstwa 0 łapie tylko ścisłe komendy
 *    ("włącz latarkę", "zanotuj ..."), a timer, budzik i telefon do kontaktu
 *    rozpoznawała dotąd wyłącznie warstwa 2 - czyli przy braku klucza, nie
 *    przy braku sieci. Bez tego "zadzwoń do mamy" w tunelu kończyło się
 *    komunikatem o braku internetu, choć do zadzwonienia internet nie jest
 *    potrzebny.
 * 2. Gdy wzorce niczego nie znalazły - krótko mówimy, że nie ma internetu,
 *    i podajemy kilka poleceń, które bez niego działają.
 */
object OfflineFallback {

    /**
     * Polecenia, które działają bez sieci - podawane na głos jako przykłady.
     *
     * Każde jest sprawdzone testem (OfflineFallbackTest) na tych wzorcach,
     * które je naprawdę obsługują: latarka i notatka w warstwie 0, timer,
     * budzik i telefon w warstwie 2. Przykład, który nie działa, byłby gorszy
     * niż żaden - człowiek powtórzyłby go dosłownie i usłyszał ten sam
     * komunikat.
     */
    val EXAMPLES: List<String> = listOf(
        "włącz latarkę",
        "timer na 5 minut",
        "ustaw alarm na 7",
        "zadzwoń do mamy",
        "zanotuj kupić mleko"
    )

    /**
     * Zdanie na głos. Krótkie, bo słychać je w okularach, często w ruchu:
     * najpierw powód, potem przykłady do powtórzenia.
     */
    val SPEECH: String =
        "Nie mam internetu, więc nie zapytam modelu. Bez sieci działają polecenia takie jak: " +
            EXAMPLES.dropLast(1).joinToString(", ") + " albo " + EXAMPLES.last() + "."

    /**
     * Czy tura padła WYŁĄCZNIE z braku sieci: była co najmniej jedna próba i
     * każda skończyła się błędem sieci (patrz [ProviderFailure.isNoNetwork]).
     *
     * Wystarczy jeden dostawca, który odpowiedział czymkolwiek innym - pustym
     * kontem, złym kluczem, przekroczonym czasem - i to nie jest brak sieci:
     * internet był, zawiodło coś innego, a podpowiedź o poleceniach offline
     * wysłałaby człowieka szukać zasięgu, którego nie brakuje.
     *
     * @param failures treści wyjątków kolejnych prób, w kolejności prób
     */
    fun allFailedOffline(failures: List<String?>): Boolean =
        failures.isNotEmpty() && failures.all { ProviderFailure.isNoNetwork(it) }
}
