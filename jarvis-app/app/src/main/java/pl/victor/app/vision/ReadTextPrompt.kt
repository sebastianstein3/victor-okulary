package pl.victor.app.vision

/**
 * Polecenie dla przytrzymania przycisku: powiedz, co jest napisane na tym, na
 * co patrzę - a jeśli to obcy język, od razu po naszemu.
 *
 * ## Do czego to służy
 * Do napisów w obcym kraju: nazwa produktu na półce, tabliczka, etykieta,
 * szyld - ale TAKŻE do dłuższego tekstu, na który ktoś świadomie celuje:
 * ulotki, menu, akapitu instrukcji. Stąd trzy rzeczy w poleceniu.
 *
 * Po pierwsze ROZRÓŻNIENIE, CO JEST W KADRZE - i to jest poprawka do
 * poprzedniej wersji tego pliku. Stało tu twarde „przeczytaj TYLKO ten
 * najważniejszy napis" plus „odpowiedz jednym lub dwoma zdaniami", bez żadnego
 * warunku. Reguła była pisana pod półkę w sklepie, gdzie wyliczanka
 * kilkunastu nazw i cen trwałaby minutę - i tam jest słuszna. Tylko że
 * stosowała się też do kadru, w którym jest JEDEN tekst, po prostu długi:
 * wtedy „najważniejszy napis" to był pierwszy nagłówek, a reszta strony
 * przepadała. Zgłoszone wprost: „gdy proszę o tłumaczenie większego tekstu,
 * tłumaczy tylko fragment".
 *
 * Teraz wybór dotyczy WIELU ODDZIELNYCH napisów, a jeden spójny blok tekstu
 * idzie w całości. Tego rozróżnienia model nie musi zgadywać - widzi kadr.
 *
 * Po drugie ROZRÓŻNIENIE marki od opisu. Sama nazwa własna przetłumaczona nic
 * nie daje („Ariel" to dalej „Ariel"), ale to, co przy niej stoi, jest całą
 * informacją: węgierskie „mosópor" obok tej marki znaczy proszek do prania i
 * dopiero to odpowiada na pytanie, po co się w ogóle patrzy na to opakowanie.
 *
 * Po trzecie DŁUGOŚĆ ODPOWIEDZI IDZIE ZA DŁUGOŚCIĄ NAPISU, a nie za stałą
 * regułą. Krótka tabliczka ma dać krótkie zdanie; strona menu ma dać całe
 * menu. Limit na wyjściu modelu i tak istnieje (MAX_OUTPUT_TOKENS), więc
 * drugi limit w poleceniu tylko obcinał to, co człowiek chciał usłyszeć.
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
            "opakowaniu produktu, tabliczce, etykiecie, szyldzie, ulotce, menu. " +
            "Najpierw rozstrzygnij, co jest w kadrze. " +
            "Gdy jest to JEDEN spójny tekst (ulotka, menu, akapit, strona, " +
            "jedna etykieta) - podaj go W CAŁOŚCI, nic nie pomijając i nic " +
            "nie streszczając, choćby był długi. " +
            "Gdy w kadrze jest WIELE OSOBNYCH napisów (półka w sklepie, " +
            "ściana z ogłoszeniami, kilka różnych opakowań) - przeczytaj " +
            "TYLKO ten najważniejszy: największy, najbardziej na środku, ten, " +
            "na który użytkownik wyraźnie celuje, i nie wyliczaj pozostałych. " +
            "Jeśli jest w innym języku niż $mianownik, od razu podaj " +
            "tłumaczenie na $mianownik - nie czytaj oryginału. Jeśli jest już " +
            "po $miejscownik, podaj go bez zmian. " +
            "Nazwy marek i nazwy własne (ulic, miejsc, firm, ludzi) zostaw w " +
            "oryginalnym brzmieniu, ale to, co je OPISUJE, przetłumacz - " +
            "przy obcym produkcie najważniejsze jest, CO TO JEST. " +
            "Cenę, wagę lub pojemność podaj, jeśli widnieje przy tym napisie. " +
            "Długość odpowiedzi dopasuj do długości napisu: krótka tabliczka " +
            "to jedno zdanie, cała strona tekstu to cała strona. Nie komentuj, " +
            "nie streszczaj i nie zapowiadaj, w jakim języku był oryginał. " +
            "Jeśli tekstu nie ma albo jest nieczytelny, powiedz to jednym zdaniem."
    }

    /** Języki, dla których mamy poprawną odmianę nazwy. */
    val SUPPORTED: Set<String> get() = NAMES.keys
}
