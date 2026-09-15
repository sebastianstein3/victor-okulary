package pl.victor.app.vision

/**
 * Polecenie dla przytrzymania przycisku: przeczytaj tekst ze zdjęcia, a jeśli
 * jest w obcym języku - powiedz go od razu w języku użytkownika.
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
        return "Przeczytaj na głos cały tekst widoczny na zdjęciu. Jeśli tekst " +
            "jest w innym języku niż $mianownik, od razu podaj jego tłumaczenie " +
            "na $mianownik - nie czytaj oryginału. Jeśli jest już po " +
            "$miejscownik, przeczytaj go bez zmian. Nazwy własne (ulic, miejsc, " +
            "firm, ludzi) zostaw w oryginalnym brzmieniu. Nie streszczaj, nie " +
            "komentuj i nie dodawaj nic od siebie - ani zapowiedzi, w jakim " +
            "języku był oryginał. Jeśli tekstu nie ma albo jest nieczytelny, " +
            "powiedz to jednym zdaniem."
    }

    /** Języki, dla których mamy poprawną odmianę nazwy. */
    val SUPPORTED: Set<String> get() = NAMES.keys
}
