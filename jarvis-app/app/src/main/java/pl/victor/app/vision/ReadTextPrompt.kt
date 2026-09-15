package pl.victor.app.vision

/**
 * Polecenie dla przytrzymania przycisku: powiedz, co jest napisane na tym, na
 * co patrzę - a jeśli to obcy język, od razu po naszemu.
 *
 * ## Do czego to służy naprawdę
 * Nie do czytania stron tekstu, tylko do KRÓTKICH napisów w obcym kraju:
 * nazwa produktu na półce, tabliczka informacyjna, etykieta, szyld. Stąd dwie
 * rzeczy w poleceniu, które inaczej byłyby zbędne.
 *
 * Po pierwsze WYBÓR. Zdjęcie półki w sklepie zawiera kilkanaście nazw, cen i
 * naklejek promocyjnych; „przeczytaj cały tekst ze zdjęcia" znaczyłoby tu
 * minutę wyliczanki. Model ma więc czytać napis dominujący - największy,
 * centralny, ten, na który widać, że użytkownik celuje.
 *
 * Po drugie ROZRÓŻNIENIE marki od opisu. Sama nazwa własna przetłumaczona nic
 * nie daje („Ariel" to dalej „Ariel"), ale to, co przy niej stoi, jest całą
 * informacją: węgierskie „mosópor" obok tej marki znaczy proszek do prania i
 * dopiero to odpowiada na pytanie, po co się w ogóle patrzy na to opakowanie.
 *
 * Czytania długiego tekstu to NIE wyłącza - do tego jest tryb ciągły (OCR na
 * telefonie, darmowy) albo zwykłe pytanie głosem.
 *
 * ## Czemu to w ogóle tłumaczy
 * Bo czytanie obcego tekstu polskim głosem TTS nie dawało nic użytecznego:
 * angielskie słowa wychodziły przekręcone, a osoba słuchająca i tak nie
 * wiedziała, co znaczą. Przytrzymanie przycisku wysyła zdjęcie do modelu, który
 * ten tekst i tak CZYTA - więc przetłumaczenie go to zmiana polecenia, a nie
 * druga runda: bez dodatkowego zapytania, bez OCR-a na telefonie i bez
 * pobierania modeli tłumaczących.
 *
 * ## Czemu osobny plik, a nie prywatna metoda w AIOrchestrator
 * Bo to jest sześć wariantów językowych z odmianą, a literówka w którymkolwiek
 * poleci prosto do płatnego modelu i nikt jej nie zauważy. Tutaj da się to
 * uruchomić w teście; w prywatnej metodzie klasy z kilkunastoma zależnościami
 * w konstruktorze - nie.
 */
object ReadTextPrompt {

    /**
     * Nazwa języka w dwóch formach: mianownik („na polski") i miejscownik
     * („po polsku").
     *
     * Dwie formy, a nie jedna z doklejoną końcówką, bo „po" wymaga
     * miejscownika: sklejanie dawało „po polskiu" - i to w KAŻDYM języku z tej
     * listy, bo wszystkie kończą się tak samo.
     */
    private val NAMES: Map<String, Pair<String, String>> = mapOf(
        "pl" to ("polski" to "polsku"),
        "en" to ("angielski" to "angielsku"),
        "de" to ("niemiecki" to "niemiecku"),
        "fr" to ("francuski" to "francusku"),
        "es" to ("hiszpański" to "hiszpańsku"),
        "it" to ("włoski" to "włosku"),
        "uk" to ("ukraiński" to "ukraińsku")
    )

    /** Język, na który tłumaczymy, gdy ustawienie mówi coś nieznanego. */
    private const val FALLBACK = "pl"

    /**
     * @param languageCode wartość z `SettingsRepository.getResponseLanguage()`
     *   - to samo ustawienie, które decyduje o języku TTS i rozpoznawania mowy.
     *   Gdyby czytanie tłumaczyło zawsze na polski, osoba z aplikacją ustawioną
     *   na angielski dostawałaby polskiego lektora przy angielskiej tabliczce.
     *
     * UWAGA: prompty systemowe samych dostawców (GeminiProvider i pozostałe)
     * mają „odpowiadaj po polsku" WPISANE NA SZTYWNO i tego ustawienia nie
     * czytają. Nie naprawiam tego tutaj - to osobna zmiana w siedmiu plikach -
     * ale przy innym języku te dwie instrukcje mówią co innego. Polecenie
     * zadania jest bliżej, więc powinno wygrać.
     */
    fun forLanguage(languageCode: String): String {
        val (mianownik, miejscownik) = NAMES[languageCode] ?: NAMES.getValue(FALLBACK)
        return "Powiedz, co jest napisane na tym, na co patrzy użytkownik - " +
            "opakowaniu produktu, tabliczce, etykiecie, szyldzie. " +
            "Jeśli w kadrze jest WIELE osobnych napisów (półka w sklepie, " +
            "ściana z ogłoszeniami), przeczytaj TYLKO ten najważniejszy: " +
            "największy, najbardziej na środku, ten, na który użytkownik " +
            "wyraźnie celuje. Nie wyliczaj pozostałych. Gdy napis jest krótki " +
            "i jedyny, podaj go w całości. " +
            "Jeśli jest w innym języku niż $mianownik, od razu podaj " +
            "tłumaczenie na $mianownik - nie czytaj oryginału. Jeśli jest już " +
            "po $miejscownik, podaj go bez zmian. " +
            "Nazwy marek i nazwy własne (ulic, miejsc, firm, ludzi) zostaw w " +
            "oryginalnym brzmieniu, ale to, co je OPISUJE, przetłumacz - " +
            "przy obcym produkcie najważniejsze jest, CO TO JEST. " +
            "Cenę, wagę lub pojemność podaj, jeśli widnieje przy tym napisie. " +
            "Odpowiedz krótko, jednym lub dwoma zdaniami. Nie komentuj, nie " +
            "streszczaj i nie zapowiadaj, w jakim języku był oryginał. " +
            "Jeśli tekstu nie ma albo jest nieczytelny, powiedz to jednym zdaniem."
    }

    /** Języki, dla których mamy poprawną odmianę nazwy. */
    val SUPPORTED: Set<String> get() = NAMES.keys
}
