package pl.victor.app.proactive

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pogoda jako kontekst dla modelu - dokładnie tą samą drogą, co kalendarz
 * ([CalendarContext]) i poczta ([GmailContext]).
 *
 * ## Dlaczego kontekst, a nie osobna "komenda pogodowa"
 * Aplikacja miała pogodę wyłącznie w alertach: sprawdzała ją w tle i wysyłała
 * powiadomienie, gdy coś było nie tak. Zapytana wprost - "jaka jest pogoda?" -
 * odpowiadała z pamięci modelu, czyli zmyślała. Dorobienie osobnej komendy
 * dałoby jedną sztywną formułkę; doklejenie prognozy do promptu pozwala
 * odpowiedzieć na wszystko naraz: "czy brać kurtkę", "czy zdążę przed
 * deszczem", "czy da się dziś biegać" - bo model ma dane i sam wyciąga wnioski.
 *
 * Prognoza jest doklejana TYLKO wtedy, gdy pytanie faktycznie jej dotyczy -
 * inaczej każde pytanie ciągnęłoby zapytanie do API pogodowego.
 */
object WeatherContext {

    /**
     * Czy pytanie dotyczy pogody.
     *
     * Świadomie szeroko - lepiej dokleić kilka linijek prognozy niepotrzebnie
     * niż odpowiedzieć zmyśloną temperaturą. Wzorce łapią też pytania zadane
     * nie wprost ("brać kurtkę?", "czy zmoknę").
     */
    fun isAboutWeather(question: String): Boolean {
        val q = question.lowercase()
        return WEATHER_KEYWORDS.any { q.contains(it) }
    }

    /**
     * Buduje fragment promptu z prognozą.
     *
     * @return `null`, gdy nie ma prognozy - wtedy prompt zostaje bez zmian,
     *         a model odpowie, że nie zna aktualnej pogody
     */
    /**
     * Do jakiego miejsca odnosi się prognoza i co zrobić z pytaniem o inne.
     *
     * Nie zakłada, że model ma wyszukiwarkę: z Gemini ją ma, z modelem
     * zapasowym czy lokalnym - nie. Dlatego dwa wyjścia zamiast jednego, bo
     * polecenie "sprawdź w internecie" wydane modelowi bez internetu kończy się
     * zmyśloną prognozą.
     */
    /**
     * Miejsce INNE niż miejscowość z ustawień, o które pyta człowiek - albo `null`.
     *
     * ## Po co, skoro jest [scopeNote]
     * Dziennik z biegu 153: "jaka będzie pogoda w Bodrum w Turcji w ten
     * weekend" - Gemini z działającą wyszukiwarką (o iPhone'a w następnym
     * pytaniu szukał) odpowiedział "mam prognozę tylko dla Torunia". Zdanie
     * "jeśli pytanie dotyczy innego miejsca, wyszukaj" przegrywało z
     * kilkunastoma linijkami gotowych danych dla Torunia tuż obok. Skoro
     * wiemy, że pytanie jest o inne miejsce, prognozy z ustawień w ogóle nie
     * doklejamy - zamiast niej idzie polecenie, żeby szukać.
     *
     * Rozpoznanie jest proste: słowo po "w", "we", "dla", "nad" albo "na",
     * które nie jest określeniem czasu ani miejsca bez nazwy ("w domu", "na
     * dworze") i nie zaczyna się tak jak miejscowość z ustawień ("w Toruniu").
     */
    fun innaMiejscowość(question: String, homeCity: String): String? {
        val home = homeCity.lowercase().trim().take(HOME_STEM)
        for (m in PLACE_AFTER_PREPOSITION.findAll(question.lowercase())) {
            val word = m.groupValues[1]
            if (word.length < 3 || word in NOT_A_PLACE) continue
            if (NOT_A_PLACE_PREFIXES.any { word.startsWith(it) }) continue
            if (home.isNotEmpty() && word.startsWith(home)) continue
            return word
        }
        return null
    }

    /** Zamiast prognozy z ustawień - gdy pytanie jest o [place], patrz [innaMiejscowość]. */
    fun otherPlaceNote(place: String, homeCity: String): String =
        "=== POGODA ===\n" +
            "Pytanie dotyczy miejsca \"$place\", a aplikacja ma prognozę tylko dla " +
            "$homeCity - dlatego jej tu nie ma. WYSZUKAJ w internecie aktualną " +
            "prognozę dla \"$place\" i podaj ją konkretnie: temperatury, opady, wiatr. " +
            "Nie mów o prognozie dla $homeCity. Jeśli nie masz wyszukiwarki, powiedz " +
            "wprost, że pogody dla tego miejsca teraz nie sprawdzisz."

    private const val HOME_STEM = 4

    private val PLACE_AFTER_PREPOSITION =
        Regex("""(?:^|\s)(?:w|we|dla|nad|na)\s+([a-ząćęłńóśźż]+)""")

    /** Słowa po przyimku, które NIE są nazwą miejsca. */
    private val NOT_A_PLACE = setOf(
        "ten", "tym", "tę", "te", "ta", "tej", "tych", "ciągu", "nocy", "dzień", "dzien",
        "dni", "domu", "pracy", "mieście", "miescie", "okolicy", "okolicach", "pobliżu",
        "poblizu", "dworze", "zewnątrz", "zewnatrz", "polu", "jutro", "dziś", "dzis",
        "dzisiaj", "teraz", "rano", "wieczór", "wieczor", "noc", "południe", "poludnie",
        "weekend", "weekendzie", "tydzień", "tydzien", "tygodniu", "przyszłym", "przyszlym",
        "przyszły", "przyszly", "najbliższy", "najblizszy", "najbliższym", "najblizszym",
        "najbliższe", "najblizsze", "sobotę", "sobote", "niedzielę", "niedziele",
        "poniedziałek", "poniedzialek", "wtorek", "środę", "srode", "czwartek", "piątek",
        "piatek", "spacer", "rower", "basen", "plażę", "plaze", "zakupy", "trening",
        "godzinę", "godzine", "godzinach", "chwilę", "chwile", "moim", "mojej", "naszym",
        "naszej", "twoim", "twojej", "całym", "calym", "całej", "calej", "miasto",
        "nim", "niej", "nich", "niego", "nią", "nia", "tobie", "mnie", "sobie",
        "telefonie", "zdjęciu", "zdjeciu", "youtubie", "spotify", "internecie", "zewnątrz"
    )

    /** Początki słów, które nie są nazwą miejsca (miesiące, pory). */
    private val NOT_A_PLACE_PREFIXES = listOf(
        "stycz", "lut", "marc", "kwiet", "maj", "czerw", "lip", "sierp", "wrze",
        "październ", "pazdziern", "listopad", "grud", "godzin", "minut", "następn", "nastepn"
    )

    fun scopeNote(city: String): String =
        "Ta prognoza dotyczy WYŁĄCZNIE miejscowości $city, ustawionej w aplikacji. " +
            "Jeśli pytanie dotyczy innego miejsca (innego miasta albo kraju), NIE " +
            "odpowiadaj tymi danymi: sprawdź pogodę dla tamtego miejsca w " +
            "wyszukiwarce internetowej, a jeśli nie masz do niej dostępu, powiedz " +
            "wprost, że masz prognozę tylko dla $city. Nie zgaduj pogody z pamięci."

    fun buildPromptContext(
        forecast: WeatherForecast?,
        airQuality: AirQuality? = null,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        if (forecast == null || forecast.entries.isEmpty()) return null

        val timeFormat = SimpleDateFormat("EEEE HH:mm", Locale("pl", "PL"))
        val upcoming = forecast.entries
            .filter { it.timestampMs >= nowMs - HOUR_MS }
            .take(ENTRIES_IN_PROMPT)

        if (upcoming.isEmpty()) return null

        return buildString {
            append("=== PROGNOZA POGODY (").append(forecast.city).append(") ===\n")
            append("Dane z serwisu pogodowego, pobrane przed chwilą. ")
            append("Opieraj się na nich, nie na własnej pamięci.\n")
            // PROGNOZA JEST DLA JEDNEGO MIEJSCA - I MODEL MUSI TO WIEDZIEĆ.
            //
            // Zgłoszone: "mówi, że nie ma informacji o pogodzie w Bodrum, bo ma
            // pogodę tylko dla Torunia". Ten blok jest doklejany do KAŻDEGO
            // pytania o pogodę, dla miejscowości z ustawień - a zdanie wyżej
            // każe się na nim opierać. Model zrobił dokładnie to, co mu
            // kazano: nie miał Bodrum w danych, więc odmówił, zamiast sprawdzić.
            append(scopeNote(forecast.city)).append('\n')

            upcoming.forEach { entry ->
                append("- ").append(timeFormat.format(Date(entry.timestampMs)))
                append(": ").append("%.0f".format(entry.tempCelsius)).append("°C")
                if (kotlin.math.abs(entry.feelsLike - entry.tempCelsius) >= 2.0) {
                    append(" (odczuwalna ").append("%.0f".format(entry.feelsLike)).append("°C)")
                }
                append(", ").append(entry.description)
                append(", wiatr ").append("%.0f".format(entry.windSpeed * MPS_TO_KMH)).append(" km/h")
                if (entry.rainMm3h > 0.0) {
                    append(", deszcz ").append("%.1f".format(entry.rainMm3h)).append(" mm")
                }
                if (entry.snowMm3h > 0.0) {
                    append(", śnieg ").append("%.1f".format(entry.snowMm3h)).append(" mm")
                }
                append('\n')
            }

            forecast.minutesToSunset(nowMs)?.let { minutes ->
                append("Do zachodu słońca: ").append(minutes).append(" min.\n")
            }

            airQuality?.let { append(it.summary()).append('\n') }

            append("Odpowiadaj krótko i konkretnie, tak jak się mówi na głos - ")
            append("bez tabelek i wyliczanek godzina po godzinie.\n")
        }
    }

    private const val HOUR_MS = 3_600_000L

    /** Ile wpisów prognozy (co 3 h) dokleić - 8 to doba do przodu. */
    private const val ENTRIES_IN_PROMPT = 8

    private const val MPS_TO_KMH = 3.6

    private val WEATHER_KEYWORDS = listOf(
        "pogod", "prognoz", "temperatur", "stopni", "ciepło", "cieplo",
        "zimno", "mróz", "mroz", "upał", "upal",
        "deszcz", "pada", "padać", "padac", "zmokn", "parasol",
        "śnieg", "snieg", "gołoledź", "gololedz", "ślisko", "slisko",
        "wiatr", "wietrzn", "burz", "mgła", "mgla",
        "słonecznie", "slonecznie", "zachmurzen", "chmur",
        "kurtk", "czapk", "ubrać się", "ubrac sie", "jak się ubrać", "jak sie ubrac",
        "smog", "powietrz", "pylenie",
        "zachód słońca", "zachod slonca", "wschód słońca", "wschod slonca",
        "weather", "forecast", "rain", "temperature"
    )
}
