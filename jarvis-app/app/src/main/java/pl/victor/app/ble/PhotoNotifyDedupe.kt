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
}
