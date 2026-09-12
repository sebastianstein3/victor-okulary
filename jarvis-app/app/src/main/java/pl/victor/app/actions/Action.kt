package pl.victor.app.actions

/**
 * Akcja którą V.I.C.T.O.R. może wykonać w imieniu użytkownika.
 *
 * Akcje są wykonywane przez Android Intents - apka NIGDY nie robi nic
 * bezpośrednio (nie wysyła SMS, nie dzwoni, nie czyta maili). Zamiast tego
 * otwiera odpowiednią apkę (Spotify, Dialer, Gmail, ...) z przygotowanymi
 * parametrami. User ostatni krok robi sam (wciśnięcie "wyślij" w SMS, "zadzwoń" w dialer).
 *
 * To jest bezpieczniejsze i nie wymaga dangerous permissions.
 */
sealed class Action {
    abstract val type: ActionType
    abstract val description: String

    // === Komunikacja ===
    /** Wyślij SMS do kogoś. Otwiera aplikację SMS z przygotowanym tekstem. */
    data class SendSms(
        val to: String,
        val body: String,
        /**
         * Nazwa kontaktu Z KSIĄŻKI ADRESOWEJ, gdy [to] zostało już zamienione
         * na numer - albo `null`, gdy nic nie rozwiązywaliśmy.
         *
         * Domyślne `null` jest tu celowe: pole dochodzi do istniejącej klasy i
         * żadne miejsce tworzące akcję nie musi o nim wiedzieć. Bez niego
         * komunikat po wysłaniu SMS-a mógł powtórzyć wyłącznie imię, KTÓRE
         * PADŁO - a to nie jest to samo co adresat, do którego SMS poszedł.
         */
        val resolvedName: String? = null
    ) : Action() {
        override val type = ActionType.SEND_SMS
        override val description = "Wyślij SMS do ${resolvedName ?: to}: \"$body\""
    }

    /** Zadzwoń do kogoś. Otwiera dialer z numerem. */
    data class MakeCall(
        val to: String,
        /** Patrz [SendSms.resolvedName]. */
        val resolvedName: String? = null
    ) : Action() {
        override val type = ActionType.MAKE_CALL
        override val description = "Zadzwoń do ${resolvedName ?: to}"
    }

    /** Wyślij email. Otwiera Gmail z przygotowanym tematem i treścią. */
    data class SendEmail(
        val to: String,
        val subject: String,
        val body: String
    ) : Action() {
        override val type = ActionType.SEND_EMAIL
        override val description = "Wyślij email do $to: $subject"
    }

    // === Muzyka / Media ===
    /** Włącz muzykę. Otwiera Spotify (lub YouTube Music) z zapytaniem. */
    data class PlayMusic(val query: String) : Action() {
        override val type = ActionType.PLAY_MUSIC
        override val description = "Włącz muzykę: \"$query\""
    }

    /** Pauza/wznów muzykę. Kontroluje MediaSession. */
    object TogglePlayPause : Action() {
        override val type = ActionType.TOGGLE_PLAY
        override val description = "Pauza/wznów muzykę"
    }

    /** Następna/poprzednia piosenka. */
    data class SkipTrack(val direction: SkipDirection) : Action() {
        override val type = ActionType.SKIP_TRACK
        override val description = if (direction == SkipDirection.NEXT)
            "Następna piosenka" else "Poprzednia piosenka"
    }

    // === Accessibility (niewidomi) ===

    /** Czytaj tekst (tryb OCR + TTS). */
    object ReadText : Action() {
        override val type = ActionType.READ_TEXT
        override val description = "Czytaj tekst z otoczenia"
    }

    /** Opisz scenę (co przede mną). */
    /**
     * AI samo prosi o zdjęcie, bo bez obrazu nie odpowie na pytanie użytkownika.
     *
     * Nie jest wykonywana przez [ActionExecutor] jak pozostałe akcje - obsługuje ją
     * bezpośrednio [pl.victor.app.AIOrchestrator], robiąc zdjęcie i zadając to samo
     * pytanie jeszcze raz, już z obrazem. Dzięki temu nie musimy robić serii zdjęć
     * "na wszelki wypadek" przy każdym pytaniu.
     */
    object TakePhoto : Action() {
        override val type = ActionType.TAKE_PHOTO
        override val description = "Zrób zdjęcie, żeby odpowiedzieć na pytanie"
    }

    object DescribeScene : Action() {
        override val type = ActionType.DESCRIBE_SCENE
        override val description = "Opisz co przede mną"
    }

    /** Tryb nawigacji (ciągłe sprawdzanie otoczenia). */
    object StartNavigation : Action() {
        override val type = ActionType.START_NAVIGATION
        override val description = "Włącz tryb nawigacji"
    }

    /** Zatrzymaj aktywny tryb accessibility. */
    object StopAccessibility : Action() {
        override val type = ActionType.STOP_ACCESSIBILITY
        override val description = "Zatrzymaj tryb"
    }

    // === Nawigacja ===
    /** Nawiguj do adresu/ miejsca. Otwiera Google Maps. */
    data class Navigate(val destination: String) : Action() {
        override val type = ActionType.NAVIGATE
        override val description = "Nawiguj do: $destination"
    }

    // === Kalendarz ===
    /**
     * Utwórz wydarzenie w kalendarzu. SAFE otwiera apkę Kalendarza (dowolną
     * zsynchronizowaną, nie tylko Google) z gotowym formularzem - user zapisuje sam.
     * DIRECT tworzy je od razu przez Google Calendar API (wymaga zalogowania).
     */
    data class CreateCalendarEvent(
        val title: String,
        val startTimeMillis: Long,
        val durationMinutes: Int = 60
    ) : Action() {
        override val type = ActionType.CREATE_CALENDAR_EVENT
        override val description = "Dodaj do kalendarza: $title"
    }

    // === Narzędzia ===
    /** Ustaw alarm. Otwiera Clock app. */
    data class SetAlarm(val hour: Int, val minute: Int, val label: String = "") : Action() {
        override val type = ActionType.SET_ALARM
        override val description = "Ustaw alarm na ${hour}:${minute.toString().padStart(2, '0')}" +
                if (label.isNotBlank()) " ($label)" else ""
    }

    /** Ustaw timer. Otwiera Clock app. */
    data class SetTimer(val minutes: Int, val seconds: Int = 0) : Action() {
        override val type = ActionType.SET_TIMER
        override val description = "Timer na ${minutes}m ${seconds}s"
    }

    /** Wyszukaj w internecie. Otwiera przeglądarkę. */
    data class WebSearch(val query: String) : Action() {
        override val type = ActionType.WEB_SEARCH
        override val description = "Szukaj: $query"
    }

    /** Otwórz URL. */
    data class OpenUrl(val url: String) : Action() {
        override val type = ActionType.OPEN_URL
        override val description = "Otwórz: $url"
    }

    /** Otwórz aplikację po nazwie. */
    data class OpenApp(val packageName: String, val appName: String = "") : Action() {
        override val type = ActionType.OPEN_APP
        override val description = "Otwórz ${appName.ifBlank { packageName }}"
    }

    /** Przetłumacz tekst. Android 12+ Translate. */
    data class Translate(val text: String, val targetLang: String) : Action() {
        override val type = ActionType.TRANSLATE
        override val description = "Przetłumacz na $targetLang: $text"
    }

    /** Pokaż coś na mapie. */
    data class ShowOnMap(val query: String) : Action() {
        override val type = ActionType.SHOW_ON_MAP
        override val description = "Pokaż na mapie: $query"
    }

    // === System ===
    /** Włącz/wyłącz WiFi. */
    data class ToggleWifi(val enabled: Boolean) : Action() {
        override val type = ActionType.TOGGLE_WIFI
        override val description = if (enabled) "Włącz WiFi" else "Wyłącz WiFi"
    }

    /** Włącz/wyłącz Bluetooth. */
    data class ToggleBluetooth(val enabled: Boolean) : Action() {
        override val type = ActionType.TOGGLE_BLUETOOTH
        override val description = if (enabled) "Włącz Bluetooth" else "Wyłącz Bluetooth"
    }

    /** Włącz/wyłącz latarkę. */
    data class ToggleFlashlight(val enabled: Boolean) : Action() {
        override val type = ActionType.TOGGLE_FLASHLIGHT
        override val description = if (enabled) "Włącz latarkę" else "Wyłącz latarkę"
    }
}

enum class ActionType {
    SEND_SMS, MAKE_CALL, SEND_EMAIL,
    PLAY_MUSIC, TOGGLE_PLAY, SKIP_TRACK,
    NAVIGATE, CREATE_CALENDAR_EVENT,
    SET_ALARM, SET_TIMER,
    WEB_SEARCH, OPEN_URL, OPEN_APP,
    TRANSLATE, SHOW_ON_MAP,
    TOGGLE_WIFI, TOGGLE_BLUETOOTH, TOGGLE_FLASHLIGHT,
    READ_TEXT, DESCRIBE_SCENE, START_NAVIGATION, STOP_ACCESSIBILITY,
    TAKE_PHOTO
}

enum class SkipDirection { NEXT, PREVIOUS }

/**
 * Rezultat wykonania akcji - czy się udało, czy nie.
 */
sealed class ActionResult {
    data class Success(val message: String) : ActionResult()
    data class Failed(val reason: String) : ActionResult()
    data class NeedsConfirmation(val question: String) : ActionResult()
}
