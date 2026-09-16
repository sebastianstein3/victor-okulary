package pl.victor.app.actions

/**
 * Komendy zdefiniowane przez użytkownika: własna fraza → istniejąca akcja.
 *
 * ## Po co
 * Wzorce w [SmartActionDetector] są nasze, nie Twoje. Ktoś mówi "świeć",
 * ktoś inny "daj światło", a jeszcze ktoś ma psa, do którego mówi "aport" -
 * i żadnej z tych fraz nie da się przewidzieć z góry. Zamiast zgadywać,
 * pozwalamy dopisać własną.
 *
 * ## Dlaczego to działa PRZED wzorcami i przed AI
 * Bo fraza wpisana ręcznie jest jednoznaczną deklaracją intencji - mocniejszą
 * niż jakikolwiek nasz wzorzec i niż domysł modelu. Jeśli ktoś ustawił "dobranoc"
 * na wyłączenie latarki, to ma wyłączyć latarkę, a nie zacząć rozmowę o spaniu.
 */
object CustomCommands {

    /**
     * @param phrase fraza wypowiadana przez użytkownika (bez wielkości liter)
     * @param type którą akcję uruchomić
     * @param argument parametr akcji, gdy jej potrzebuje (numer, miasto, nazwa
     *   aplikacji). Dla akcji bezparametrowych ignorowany.
     */
    data class CustomCommand(
        val phrase: String,
        val type: ActionType,
        val argument: String = ""
    )

    /**
     * Normalizuje frazę do porównywania.
     *
     * Rozpoznawanie mowy zwraca tekst z wielkich liter, z kropką na końcu i z
     * różnymi odmianami spacji. Bez sprowadzenia obu stron do wspólnej postaci
     * własna komenda działałaby "czasami", co jest gorsze niż gdyby nie działała
     * wcale - użytkownik nie wiedziałby, czy to on, czy aplikacja.
     */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(NON_LETTER, " ")
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
            .joinToString(" ")

    /**
     * Szuka komendy pasującej do wypowiedzi.
     *
     * Dopasowanie jest CAŁOŚCIOWE - fraza musi być całą wypowiedzią, nie jej
     * fragmentem. Dopasowanie po fragmencie łapałoby zdania, które komendą nie
     * są ("nie włączaj latarki", "czy mam włączyć latarkę?"), a przy własnych,
     * krótkich frazach ryzyko jest szczególnie duże.
     *
     * Przy kilku pasujących wygrywa PIERWSZA z listy - czyli ta, którą
     * użytkownik dodał wcześniej.
     */
    fun match(spoken: String, commands: List<CustomCommand>): CustomCommand? {
        val normalized = normalize(spoken)
        if (normalized.isBlank()) return null
        return commands.firstOrNull { normalize(it.phrase) == normalized }
    }

    /**
     * Czy frazę wolno dodać.
     *
     * Odrzucamy puste i jednoliterowe: przy tak krótkiej frazie każde
     * przesłyszenie rozpoznawania mowy uruchamiałoby akcję.
     */
    fun isValidPhrase(phrase: String): Boolean = normalize(phrase).length >= MIN_PHRASE_LENGTH

    /**
     * Buduje akcję z komendy użytkownika.
     *
     * Akcje wymagające parametru, którego użytkownik nie podał, zwracają `null` -
     * lepiej nie zrobić nic, niż wysłać SMS-a bez odbiorcy.
     */
    fun toAction(command: CustomCommand): Action? {
        val arg = command.argument.trim()
        return when (command.type) {
            ActionType.TAKE_PHOTO -> Action.TakePhoto
            ActionType.TOGGLE_PLAY -> Action.TogglePlayPause
            ActionType.READ_TEXT -> Action.ReadText
            ActionType.DESCRIBE_SCENE -> Action.DescribeScene
            ActionType.START_NAVIGATION -> Action.StartNavigation
            ActionType.STOP_ACCESSIBILITY -> Action.StopAccessibility

            ActionType.TOGGLE_FLASHLIGHT -> Action.ToggleFlashlight(enabled = !arg.isOff())
            ActionType.TOGGLE_WIFI -> Action.ToggleWifi(enabled = !arg.isOff())
            ActionType.TOGGLE_BLUETOOTH -> Action.ToggleBluetooth(enabled = !arg.isOff())
            ActionType.SKIP_TRACK -> Action.SkipTrack(
                if (arg.startsWith("po")) SkipDirection.PREVIOUS else SkipDirection.NEXT
            )

            ActionType.MAKE_CALL -> arg.ifBlankNull()?.let { Action.MakeCall(it) }
            ActionType.PLAY_MUSIC -> arg.ifBlankNull()?.let { Action.PlayMusic(it) }
            ActionType.NAVIGATE -> arg.ifBlankNull()?.let { Action.Navigate(it) }
            ActionType.SHOW_ON_MAP -> arg.ifBlankNull()?.let { Action.ShowOnMap(it) }
            ActionType.WEB_SEARCH -> arg.ifBlankNull()?.let { Action.WebSearch(it) }
            ActionType.OPEN_URL -> arg.ifBlankNull()?.let { Action.OpenUrl(it) }
            ActionType.OPEN_APP -> arg.ifBlankNull()?.let {
                Action.OpenApp(packageName = "", appName = it)
            }

            // Te wymagają więcej niż jednego parametru (odbiorca I treść, godzina
            // I minuta), więc jedno pole tekstowe im nie wystarczy. Świadomie
            // zostawiamy je modelowi - on wyciągnie je ze zdania.
            ActionType.SEND_SMS,
            ActionType.SEND_WHATSAPP,
            // APP_TASK niesie rodzaj zadania I cel, więc jedno pole tekstowe
            // własnej komendy mu nie wystarczy.
            ActionType.APP_TASK,
            ActionType.SEND_EMAIL,
            ActionType.TRANSLATE,
            ActionType.SET_ALARM,
            ActionType.SET_TIMER,
            ActionType.CREATE_CALENDAR_EVENT -> null
        }
    }

    /** Typy, które da się podpiąć pod własną frazę - reszta wymaga zdania. */
    val ASSIGNABLE_TYPES: List<ActionType> =
        ActionType.entries.filter { type ->
            toAction(CustomCommand("x", type, argument = "test")) != null
        }

    /** Czy dana akcja potrzebuje parametru, żeby dało się ją wykonać. */
    fun needsArgument(type: ActionType): Boolean =
        toAction(CustomCommand("x", type, argument = "")) == null &&
            toAction(CustomCommand("x", type, argument = "test")) != null

    private fun String.ifBlankNull(): String? = ifBlank { null }

    private fun String.isOff(): Boolean =
        this == "off" || startsWith("wy") || startsWith("zga")

    private val NON_LETTER = Regex("""[^\p{L}\p{Nd}]+""")
    private val WHITESPACE = Regex("""\s+""")

    private const val MIN_PHRASE_LENGTH = 2
}
