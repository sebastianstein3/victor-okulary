package pl.victor.app.ble

/**
 * Odsiewa powtórzone zgłoszenie „zdjęcie gotowe" z okularów.
 *
 * ## Skąd wiadomo, że powtórki są
 * Z dziennika z 11 września:
 *
 * ```
 * 17:42:51.047  ZDJĘCIE  okulary zgłosiły gotowe zdjęcie
 * 17:42:51.049  ZDJĘCIE  okulary zgłosiły gotowe zdjęcie
 * ```
 *
 * Dwie milisekundy odstępu to nie są dwa zdjęcia - to jedna ramka doręczona
 * dwa razy.
 *
 * ## Czym to groziło
 * Zdjęcie zrobione przyciskiem na okularach uruchamia pobranie miniatury.
 * Powtórka uruchamiała DRUGIE pobranie, równolegle z pierwszym, tym samym
 * kanałem vendor SDK - a `getPictureThumbnails` trzyma jeden nasłuch i jeden
 * licznik kawałków. Dwie prośby naraz nie dają dwóch zdjęć, tylko dwa limity
 * czasu. Z zewnątrz wygląda to dokładnie tak, jak zgłoszono: „jak zrobię
 * zdjęcie ręcznie, to nie trafia do aplikacji".
 *
 * Okno jest celowo długie: dwa świadome wciśnięcia przycisku w dwie sekundy
 * to i tak jedna scena, a stracenie drugiego z nich kosztuje jedną turę.
 * Nieodsianie powtórki kosztuje obie.
 */
object PhotoNotifyDedupe {

    const val WINDOW_MS = 2_000L

    /**
     * @param lastNotifyAtMs kiedy przyszło poprzednie zgłoszenie (0 = nie było)
     * @param nowMs czas bieżącego zgłoszenia
     */
    fun isEcho(lastNotifyAtMs: Long, nowMs: Long): Boolean {
        if (lastNotifyAtMs <= 0L) return false
        val elapsed = nowMs - lastNotifyAtMs
        // Ujemny odstęp = zegar skoczył w tył. To nie jest dowód powtórki.
        return elapsed in 0 until WINDOW_MS
    }

    /**
     * Ile po NASZEJ komendzie migawki uznajemy zgłoszenie „zdjęcie gotowe" za
     * odpowiedź na nią, a nie za zdjęcie zrobione przez użytkownika.
     *
     * ## Skąd to musiało powstać
     * Z dziennika z 21:55, fragment 22:14:37-22:15:39. Aplikacja wysyła
     * migawkę, okulary ją wykonują i meldują ramką 0x02. Jeśli w tej jednej
     * chwili znacznik „trwa nasze zdjęcie" akurat nie stoi - bo próba właśnie
     * się skończyła, bo budżet czasu wygasł, bo trwa pobieranie POPRZEDNIEGO
     * zdjęcia - ramka wygląda identycznie jak wciśnięcie przycisku przez
     * człowieka. Aplikacja startuje wtedy turę „opisz to zdjęcie", ta robi
     * własną migawkę, okulary meldują ją znowu i całość zaczyna się od nowa.
     *
     * Użytkownik słyszy to wprost: „na «co widzisz» okulary zaczęły robić
     * zdjęcia przez cały czas".
     *
     * Okno jest długie, bo ma pokryć CAŁE przechwytywanie z jego budżetem 22 s
     * i wszystkimi próbami. Koszt pomyłki jest niesymetryczny: zignorowane
     * zdjęcie z przycisku to jedna stracona tura, a nieodsiane echo własnej
     * komendy to pętla bez końca.
     */
    const val OWN_SHUTTER_WINDOW_MS = 25_000L

    /**
     * Czy to zgłoszenie jest odpowiedzią na NASZĄ komendę migawki.
     *
     * @param lastShutterAtMs kiedy sami ostatnio kazaliśmy zrobić zdjęcie
     *   (0 = nigdy w tej sesji)
     */
    fun isOwnShutter(lastShutterAtMs: Long, nowMs: Long): Boolean {
        if (lastShutterAtMs <= 0L) return false
        val elapsed = nowMs - lastShutterAtMs
        return elapsed in 0 until OWN_SHUTTER_WINDOW_MS
    }
}
