package pl.victor.app.actions

/**
 * Spis WSZYSTKIEGO, o co da się poprosić V.I.C.T.O.R.-a - po ludzku.
 *
 * ## Po co osobny katalog, skoro jest [ActionType]
 * Bo enum to nazwy dla kodu, nie dla człowieka. "TOGGLE_FLASHLIGHT" nic nie
 * mówi, a "zapal latarkę" mówi wszystko. Do tej pory jedynym miejscem, gdzie
 * lista możliwości istniała w ludzkiej postaci, był prompt wysyłany do modelu -
 * użytkownik nie miał jej gdzie zobaczyć.
 *
 * ## Zasada, której trzeba tu pilnować
 * Każdy [ActionType] MUSI mieć tu wpis. Nowa akcja bez wpisu to funkcja, o
 * której nikt się nie dowie - a takich w tej aplikacji było już kilka. Pilnuje
 * tego test.
 */
object CommandCatalog {

    /** Jedna komenda, opisana tak, żeby dało się ją pokazać na ekranie. */
    data class CommandInfo(
        val type: ActionType,
        val group: CommandGroup,
        val name: String,
        /** Co się stanie - jednym zdaniem, bez żargonu. */
        val whatItDoes: String,
        /** Zdania, które faktycznie zadziałają, gdy się je powie lub kliknie. */
        val examples: List<String>,
        /** Czy bez połączonych okularów ta komenda nie ma sensu. */
        val needsGlasses: Boolean = false
    )

    enum class CommandGroup(val emoji: String, val title: String) {
        VISION("👁️", "Patrzenie przez okulary"),
        COMMUNICATION("📞", "Kontakt"),
        TIME("📅", "Czas i kalendarz"),
        NAVIGATION("🗺️", "W drodze"),
        MEDIA("🎵", "Muzyka i telefon"),
        KNOWLEDGE("🌤️", "Wiedza i tłumaczenia"),
        ACCESSIBILITY("🦯", "Dla niewidomych")
    }

    val ALL: List<CommandInfo> = listOf(
        CommandInfo(
            ActionType.TAKE_PHOTO, CommandGroup.VISION, "Zrób zdjęcie",
            "Robi zdjęcie okularami i pokazuje je modelowi.",
            listOf("Zrób zdjęcie", "Co właśnie widzę?", "Spójrz na to"),
            needsGlasses = true
        ),
        CommandInfo(
            ActionType.SEND_SMS, CommandGroup.COMMUNICATION, "Wyślij SMS",
            "Otwiera SMS-a z wpisaną treścią albo wysyła go po potwierdzeniu.",
            listOf("Wyślij SMS do Ani, że się spóźnię", "Napisz do mamy, że już jadę")
        ),
        CommandInfo(
            ActionType.MAKE_CALL, CommandGroup.COMMUNICATION, "Zadzwoń",
            "Dzwoni pod numer albo do kontaktu z książki adresowej.",
            listOf("Zadzwoń do domu", "Wybierz numer do Piotra")
        ),
        CommandInfo(
            ActionType.SEND_EMAIL, CommandGroup.COMMUNICATION, "Wyślij maila",
            "Przygotowuje wiadomość z tematem i treścią.",
            listOf("Wyślij maila do szefa o temacie urlop")
        ),
        CommandInfo(
            ActionType.CREATE_CALENDAR_EVENT, CommandGroup.TIME, "Umów spotkanie",
            "Dodaje wydarzenie do kalendarza; rozumie \"jutro\" i \"w piątek\".",
            listOf("Umów spotkanie jutro na 15", "Dodaj wizytę u lekarza w piątek o 9")
        ),
        CommandInfo(
            ActionType.SET_ALARM, CommandGroup.TIME, "Ustaw budzik",
            "Nastawia budzik na podaną godzinę.",
            listOf("Ustaw budzik na 7:00", "Obudź mnie o wpół do siódmej")
        ),
        CommandInfo(
            ActionType.SET_TIMER, CommandGroup.TIME, "Minutnik",
            "Odlicza podany czas.",
            listOf("Minutnik na 10 minut", "Odliczaj 90 sekund")
        ),
        CommandInfo(
            ActionType.NAVIGATE, CommandGroup.NAVIGATION, "Prowadź",
            "Uruchamia prowadzenie do celu - wskazówki mówią mapy. Dodaj " +
                "\"z asystentem\", żeby po drodze ostrzegał też o przeszkodach " +
                "(to zużywa tokeny), albo \"bez asystenta\", żeby tego nie robił.",
            listOf(
                "Prowadź do najbliższej Biedronki",
                "Prowadź do apteki z asystentem",
                "Jedź do Krakowa"
            )
        ),
        CommandInfo(
            ActionType.SHOW_ON_MAP, CommandGroup.NAVIGATION, "Pokaż na mapie",
            "Otwiera mapę z wyszukanym miejscem.",
            listOf("Pokaż na mapie najbliższą aptekę", "Gdzie jest poczta?")
        ),
        CommandInfo(
            ActionType.PLAY_MUSIC, CommandGroup.MEDIA, "Włącz muzykę",
            "Szuka i odtwarza to, o co poprosisz.",
            listOf("Włącz muzykę", "Puść coś spokojnego")
        ),
        CommandInfo(
            ActionType.TOGGLE_PLAY, CommandGroup.MEDIA, "Pauza i wznowienie",
            "Zatrzymuje albo wznawia to, co gra.",
            listOf("Pauza", "Wznów")
        ),
        CommandInfo(
            ActionType.SKIP_TRACK, CommandGroup.MEDIA, "Zmień utwór",
            "Przeskakuje do następnego albo poprzedniego utworu.",
            listOf("Następna", "Poprzedni utwór")
        ),
        CommandInfo(
            ActionType.TOGGLE_FLASHLIGHT, CommandGroup.MEDIA, "Latarka",
            "Zapala albo gasi latarkę w telefonie.",
            listOf("Zapal latarkę", "Zgaś latarkę")
        ),
        CommandInfo(
            ActionType.TOGGLE_WIFI, CommandGroup.MEDIA, "Wi-Fi",
            "Otwiera ustawienia Wi-Fi (Android nie pozwala przełączać go z aplikacji).",
            listOf("Włącz wifi", "Wyłącz wifi")
        ),
        CommandInfo(
            ActionType.TOGGLE_BLUETOOTH, CommandGroup.MEDIA, "Bluetooth",
            "Otwiera ustawienia Bluetooth.",
            listOf("Włącz bluetooth", "Wyłącz bluetooth")
        ),
        CommandInfo(
            ActionType.OPEN_APP, CommandGroup.MEDIA, "Otwórz aplikację",
            "Uruchamia aplikację po nazwie, jaką znasz.",
            listOf("Włącz Spotify", "Otwórz Mapy")
        ),
        CommandInfo(
            ActionType.OPEN_URL, CommandGroup.KNOWLEDGE, "Otwórz stronę",
            "Otwiera podany adres w przeglądarce.",
            listOf("Otwórz onet.pl")
        ),
        CommandInfo(
            ActionType.WEB_SEARCH, CommandGroup.KNOWLEDGE, "Poszukaj w sieci",
            "Wyszukuje to, o co pytasz.",
            listOf("Poszukaj w sieci przepisu na żurek", "Wyszukaj godziny otwarcia")
        ),
        CommandInfo(
            ActionType.TRANSLATE, CommandGroup.KNOWLEDGE, "Przetłumacz",
            "Tłumaczy zdanie na wskazany język.",
            listOf("Jak powiedzieć po angielsku dziękuję?", "Przetłumacz to na niemiecki")
        ),
        CommandInfo(
            ActionType.READ_TEXT, CommandGroup.ACCESSIBILITY, "Czytaj tekst",
            "Czyta na głos napisy, na które patrzysz - w pętli, aż powiesz stop.",
            listOf("Czytaj tekst na głos", "Przeczytaj mi to"),
            needsGlasses = true
        ),
        CommandInfo(
            ActionType.DESCRIBE_SCENE, CommandGroup.ACCESSIBILITY, "Opisuj otoczenie",
            "Co jakiś czas opisuje, co widać dookoła.",
            listOf("Opisuj mi otoczenie", "Co przede mną?"),
            needsGlasses = true
        ),
        CommandInfo(
            ActionType.START_NAVIGATION, CommandGroup.ACCESSIBILITY, "Prowadź (dla niewidomych)",
            "Ostrzega o schodach, krawężnikach i przeszkodach na drodze.",
            listOf("Prowadź mnie", "Uważaj na przeszkody"),
            needsGlasses = true
        ),
        CommandInfo(
            ActionType.STOP_ACCESSIBILITY, CommandGroup.ACCESSIBILITY, "Wyłącz tryb",
            "Kończy czytanie, opisywanie albo prowadzenie.",
            listOf("Dziękuję, wystarczy", "Przestań czytać")
        )
    )

    /** Komendy pogrupowane tematycznie, w kolejności grup. */
    fun grouped(): List<Pair<CommandGroup, List<CommandInfo>>> =
        CommandGroup.entries.map { group -> group to ALL.filter { it.group == group } }
            .filter { it.second.isNotEmpty() }

    fun byType(type: ActionType): CommandInfo? = ALL.firstOrNull { it.type == type }
}
