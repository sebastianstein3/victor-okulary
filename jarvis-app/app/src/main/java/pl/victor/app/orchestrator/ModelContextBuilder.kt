package pl.victor.app.orchestrator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import pl.victor.app.actions.ContactResolver
import pl.victor.app.data.HistoryRepository
import pl.victor.app.data.SettingsRepository
import pl.victor.app.diagnostics.DiagFormat
import pl.victor.app.vision.OCRResult

/**
 * Konteksty doklejane do pytania dla modelu: czas, notatki, kalendarz,
 * kontakty, położenie (z miejscami i przystankami z OpenStreetMap), pogoda,
 * poczta, pamięć długoterminowa i tłumaczenie odczytanego tekstu.
 *
 * ## Po co osobna klasa
 * Wydzielone z [pl.victor.app.AIOrchestrator] (który miał ponad 9 tysięcy
 * linii) BEZ ZMIANY ZACHOWANIA: te same bramki, te same teksty dla modelu i te
 * same wpisy w dzienniku. Orkiestrator tylko woła te funkcje w turze - zbiera
 * je równolegle i pilnuje wspólnego terminu, patrz `CONTEXT_BUDGET_MS`.
 *
 * Stan "który temat jest otwarty" ([openContextTopics]) należy dalej do
 * orkiestratora - czyści go "nowy temat" i korzystają z niego wiadomości.
 * Tutaj jest tylko pozycja dla Map Google w bieżącej turze
 * ([mapsLocationForTurn]), bo ustawia ją wyłącznie [buildLocationContext].
 */
internal class ModelContextBuilder(
    private val context: Context,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    /** Dziennik leniwie - z tego samego powodu co `AIOrchestrator.diag`. */
    private val dziennik: () -> pl.victor.app.diagnostics.DiagnosticLog,
    private val openContextTopics: pl.victor.app.conversation.ContextTopics,
    private val contactResolver: ContactResolver,
    private val longTermMemory: pl.victor.app.memory.LongTermMemory,
    private val semanticMemory: pl.victor.app.memory.SemanticMemoryService,
    private val translator: pl.victor.app.translation.SimultaneousTranslator
) {
    private val diag: pl.victor.app.diagnostics.DiagnosticLog
        get() = dziennik()

    /**
     * Bieżąca data i godzina jako kontekst dla modelu.
     *
     * Model nie ma zegara, a jego wiedza kończy się na dacie treningu - bez tego
     * "jaki dziś dzień", "ile zostało do piątku" albo "umów spotkanie na jutro"
     * są zgadywaniem, podanym tym samym pewnym tonem co prawdziwa odpowiedź.
     *
     * Format ISO obok zapisu słownego, bo ten sam blok obsługuje dwie różne
     * potrzeby: człowiek pyta "jaki dziś dzień", a znacznik
     * `[[ACTION: type=create_calendar_event start=...]]` potrzebuje daty, którą
     * da się sparsować bez zgadywania (patrz [SmartActionDetector.parseStartTime]).
     *
     * Doklejany ZAWSZE - w odróżnieniu od pogody czy kalendarza nie da się
     * wykryć słowami kluczowymi, kiedy jest potrzebny ("ile mam czasu?",
     * "zdążę?", "jutro" w środku zdania), a kosztuje dwie linijki promptu.
     */
    fun buildTimeContext(): String {
        val now = java.time.ZonedDateTime.now()
        val polish = java.util.Locale("pl", "PL")
        val spoken = now.format(
            java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", polish)
        )
        return buildString {
            append("=== TERAZ ===\n")
            append(spoken).append('\n')
            append("ISO: ").append(
                now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            ).append(" (strefa ").append(now.zone.id).append(")\n")
            // Gotowe kotwice zamiast liczenia. Modele mylą się w arytmetyce
            // kalendarzowej (przełomy miesięcy, lata przestępne) znacznie
            // częściej niż w czytaniu podanej daty - a "co zapisałem wczoraj"
            // rozstrzyga się właśnie na tej jednej dacie.
            val day = java.time.format.DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd", polish)
            append("wczoraj = ").append(now.minusDays(1).format(day)).append('\n')
            append("jutro = ").append(now.plusDays(1).format(day)).append('\n')
        }
    }

    /**
     * Sekcja kontekstu mówiąca, DLACZEGO danych nie ma i co z tym zrobić.
     *
     * ## Dlaczego to nie może być ciche `null`
     * Gdy użytkownik pyta o kalendarz, a my po cichu nie doklejamy danych,
     * model dostaje samo pytanie i odpowiada po swojemu - "nie mam dostępu do
     * Twojego kalendarza". Brzmi jak awaria aplikacji, a jest brakiem jednej
     * zgody, którą da się kliknąć w trzydzieści sekund. Zgłoszono to dwa razy:
     * raz o pogodę, raz o kalendarz.
     *
     * Wersja z powodem zamienia bezradną odpowiedź w instrukcję.
     *
     * @param force gdy prawda, sekcja jest pomijana - briefing zbiera dane
     *   automatycznie i nie ma komu tłumaczyć, czego brakuje
     */
    private fun missingContext(section: String, reason: String, force: Boolean): String? {
        if (force) return null
        return "=== $section ===\nNIE MAM TYCH DANYCH: $reason\n" +
            "Powiedz to użytkownikowi wprost i krótko, jednym zdaniem. " +
            "Nie zmyślaj danych i nie twierdź, że nie masz takiej funkcji."
    }

    /**
     * Notatki jako kontekst dla modelu.
     *
     * Osobno od odczytania na żądanie ([pl.victor.app.notes.Notes.isListRequest]):
     * tam użytkownik chce USŁYSZEĆ listę, tu chce ODPOWIEDZI. "Czy mam coś do
     * kupienia?" albo "co miałem zrobić w piątek?" ma dostać zdanie, a nie
     * wyliczankę wszystkiego po kolei.
     */
    fun buildNotesContext(question: String, force: Boolean = false): String? {
        if (!openContextTopics.dokleić(
                TOPIC_NOTES,
                pytanieOTemat = pl.victor.app.notes.Notes.mentionsNotes(question),
                wymuszone = force
            )
        ) {
            return null
        }
        return pl.victor.app.notes.Notes.buildPromptContext(settings.getNotes())
            ?.also { Log.i(TAG, "Doklejam notatki użytkownika") }
    }

    /**
     * Wydarzenia z kalendarza urządzenia jako kontekst dla modelu.
     *
     * Czyta kalendarz systemowy (dowolny zsynchronizowany, w tym Google), więc
     * nie wymaga logowania OAuth. Wcześniej kalendarz był odczytywany wyłącznie
     * przez alerty pogodowe - V.I.C.T.O.R. nie potrafił odpowiedzieć na „co mam dziś
     * w planach", mimo że dane były na wyciągnięcie ręki.
     *
     * @return fragment promptu albo `null`, gdy pytanie nie dotyczy planów,
     *         brakuje uprawnienia albo nie ma nadchodzących wydarzeń
     */
    suspend fun buildCalendarContext(question: String, force: Boolean = false): String? {
        // `force` obchodzi bramkę słów kluczowych - używa go briefing, który
        // ma zebrać wszystko, o co użytkownik poprosił w ustawieniach, a nie
        // to, co akurat wynika z brzmienia pytania.
        if (!openContextTopics.dokleić(
                TOPIC_CALENDAR,
                pytanieOTemat = pl.victor.app.proactive.CalendarContext.isAboutSchedule(question),
                wymuszone = force
            )
        ) {
            return null
        }

        // DWIE DROGI DO KALENDARZA, BO SĄ DWA RÓŻNE KALENDARZE.
        //
        // Kalendarz URZĄDZENIA czyta się przez dostawcę treści Androida i wymaga
        // uprawnienia READ_CALENDAR. Kalendarz GOOGLE czyta się przez API i wymaga
        // połączonego konta. To są niezależne warunki, a do tej pory odczyt
        // korzystał wyłącznie z pierwszej drogi - podczas gdy TWORZENIE wydarzeń
        // szło drugą.
        //
        // Stąd zgłoszenie: "AI mówi, że nie ma dostępu do kalendarza Google, ale
        // potrafi utworzyć wydarzenie". Obie rzeczy były prawdą naraz.
        val calendar = pl.victor.app.proactive.CalendarService(context)
        val deviceEvents = if (calendar.hasPermission()) {
            runCatching { calendar.getUpcomingEvents(limit = 8, hoursAhead = 48) }
                .onFailure { Log.w(TAG, "Odczyt kalendarza urządzenia nie powiódł się", it) }
                .getOrDefault(emptyList())
        } else {
            Log.d(TAG, "Brak uprawnienia READ_CALENDAR - próbuję kalendarza Google")
            emptyList()
        }

        // Google dopytujemy, gdy urządzenie nic nie dało. Nie zawsze, bo to sieć:
        // przy działającym kalendarzu urządzenia byłby to koszt bez zysku.
        val events = deviceEvents.ifEmpty {
            val google = pl.victor.app.calendar.GoogleCalendarService(context)
            if (!google.isSignedIn()) {
                emptyList()
            } else {
                runCatching {
                    pl.victor.app.calendar.GoogleEventBridge
                        .toDeviceEvents(google.getUpcomingEvents(maxResults = 8))
                }
                    .onFailure { Log.w(TAG, "Odczyt Kalendarza Google nie powiódł się", it) }
                    .getOrDefault(emptyList())
                    .also { if (it.isNotEmpty()) Log.i(TAG, "Kalendarz z konta Google: ${it.size}") }
            }
        }

        if (events.isEmpty()) {
            // Rozróżniamy BRAK DOSTĘPU od PUSTEGO KALENDARZA - to zupełnie inne
            // rady dla użytkownika, a do tej pory obie brzmiały tak samo.
            val googleConnected =
                runCatching { pl.victor.app.calendar.GoogleCalendarService(context).isSignedIn() }
                    .getOrDefault(false)
            if (!calendar.hasPermission() && !googleConnected) {
                return missingContext(
                    section = "KALENDARZ",
                    reason = "nie mam dostępu do żadnego kalendarza. Trzeba albo włączyć " +
                        "zgodę na kalendarz w Ustawieniach aplikacji (kalendarz telefonu), " +
                        "albo podłączyć konto Google w Ustawieniach V.I.C.T.O.R.",
                    force = force
                )
            }
            Log.i(TAG, "Kalendarz dostępny, ale pusty w najbliższych 48 h")
            return missingContext(
                section = "KALENDARZ",
                reason = "kalendarz jest dostępny, ale nie ma w nim nic w najbliższych " +
                    "dwóch dobach. To NIE jest usterka - powiedz po prostu, że nic nie ma.",
                force = force
            )
        }

        return pl.victor.app.proactive.CalendarContext.buildPromptContext(events)
            ?.also { Log.i(TAG, "Doklejam ${events.size} wydarzeń z kalendarza") }
    }

    /**
     * Położenie dla modelu - patrz [pl.victor.app.proactive.NearbyQuestion].
     *
     * Temat zostaje otwarty jak pogoda czy kalendarz: po "gdzie tu zjeść"
     * pytanie "a ile tam kosztuje obiad" dalej dotyczy tego samego miejsca.
     */
    /**
     * Prawdziwe kontakty pasujące do pytania o telefon albo wiadomość.
     *
     * Bieg 183: model nie widział książki adresowej, a twierdził "w kontaktach
     * mam Sebastiana Stalmacha" - nazwisko przekręcone wcześniej przez
     * transkrypcję, którego w telefonie nie było. Prawdziwego, zapisanego przed
     * chwilą "Steinke" nie znał. Teraz dostaje kandydatów po BRZMIENIU (tak
     * łapie się "Sztainke" -> "Steinke") i zakaz wymyślania.
     */
    suspend fun buildContactsContext(question: String): String? {
        if (!KONTAKT_PYTANIE.containsMatchIn(question.lowercase())) return null
        val nazwy = contactResolver.allDisplayNames()
        if (nazwy.isEmpty()) return null
        val pasujące = pl.victor.app.actions.ContactMatch.podobne(question, nazwy)
        runCatching {
            diag.event(DiagFormat.Phase.KONTEKST, "kontakty pasujące do pytania", mapOf("ile" to pasujące.size, "wszystkich" to nazwy.size))
        }
        return buildString {
            append("=== KONTAKTY Z TELEFONU (pasujące do pytania) ===\n")
            if (pasujące.isEmpty()) {
                append("Żaden kontakt nie brzmi jak osoba z pytania. Powiedz, że nie ma takiego ")
                append("kontaktu, i poproś o powtórzenie nazwiska - NIE wymyślaj kontaktu.\n")
            } else {
                pasujące.forEach { append("- ").append(it).append('\n') }
                append("Nazwisko w pytaniu mogło zostać przekręcone przy rozpoznawaniu mowy - wybierz ")
                append("kontakt z tej listy, który BRZMI tak samo, i w znaczniku akcji użyj jego ")
                append("nazwy DOKŁADNIE jak tutaj. Innych kontaktów nie znasz - nie wymyślaj ich ")
                append("i nie opieraj się na nazwiskach z wcześniejszej rozmowy.\n")
            }
        }
    }

    /** Pozycja dla Map Google w bieżącej turze - ustawia [buildLocationContext], czyta [buildStream]. */
    @Volatile
    var mapsLocationForTurn: Pair<Double, Double>? = null
        private set

    suspend fun buildLocationContext(question: String, hasPhoto: Boolean): String? {
        mapsLocationForTurn = null
        // Pytanie o odjazdy dociera tu tylko z Mapami Google (bez nich
        // odpowiada warstwa 0) - potrzebuje położenia jak pytanie o okolicę.
        val odjazdy = pl.victor.app.navigation.OdjazdyPytanie.rozpoznaj(question)
        val oOkolicę = pl.victor.app.proactive.NearbyQuestion.dotyczyOkolicy(question) || odjazdy != null
        val cel = when {
            pl.victor.app.proactive.NearbyQuestion.jestPrzewodnikiem(question) ->
                pl.victor.app.proactive.LocationContext.Cel.PRZEWODNIK
            oOkolicę -> pl.victor.app.proactive.LocationContext.Cel.OKOLICA
            hasPhoto -> pl.victor.app.proactive.LocationContext.Cel.ZDJĘCIE
            else -> null
        }
        val otwarty = openContextTopics.dokleić(TOPIC_LOCATION, pytanieOTemat = oOkolicę)
        val naprawdęCel = cel ?: if (otwarty) pl.victor.app.proactive.LocationContext.Cel.OKOLICA else return null
        val opis = pl.victor.app.proactive.LocationContext.buildPromptContext(context, naprawdęCel)
        if (opis != null) {
            Log.i(TAG, "Doklejam kontekst lokalizacji ($naprawdęCel)")
            // Pytanie o okolicę: model dostaje też Mapy Google (miejsca, opinie,
            // godziny otwarcia) z naszą pozycją.
            if (naprawdęCel != pl.victor.app.proactive.LocationContext.Cel.ZDJĘCIE &&
                settings.isMapsGroundingEnabled()
            ) {
                pl.victor.app.proactive.LocationContext.ostatnia?.let {
                    mapsLocationForTurn = it.lat to it.lon
                }
            }
            // MODEL BEZ MAP GOOGLE dostaje listę miejsc z OpenStreetMap - inaczej
            // o Biedronce za rogiem mógłby tylko zgadywać. Gemini z Mapami jej
            // nie potrzebuje (ma więcej, z opiniami), więc nie płaci czasem.
            val mapyGoogle = settings.getActiveProvider() == "gemini" && settings.isMapsGroundingEnabled()
            // ODJAZDY: model z Mapami Google dostaje też przystanki i linie z
            // OpenStreetMap oraz polecenie, by nie udawał danych na żywo.
            if (odjazdy != null && naprawdęCel == pl.victor.app.proactive.LocationContext.Cel.OKOLICA) {
                val tu = pl.victor.app.proactive.LocationContext.ostatnia
                val od = System.currentTimeMillis()
                val lista = if (tu != null && settings.isOsmPlacesEnabled()) {
                    withTimeoutOrNull(OSM_PLACES_MS) {
                        pl.victor.app.navigation.PrzystankiClient.przystanki(tu.lat, tu.lon, odjazdy.przystanek)
                    }
                } else {
                    null
                }
                runCatching {
                    diag.event(
                        DiagFormat.Phase.KONTEKST, "komunikacja: przystanki dla modelu",
                        mapOf("ms" to (System.currentTimeMillis() - od), "przystanków" to lista?.size, "mapyGoogle" to mapyGoogle)
                    )
                }
                return opis + "\n\n" + pl.victor.app.navigation.Przystanki.kontekstDlaModelu(lista.orEmpty(), odjazdy)
            }
            if (naprawdęCel == pl.victor.app.proactive.LocationContext.Cel.OKOLICA && !mapyGoogle &&
                settings.isOsmPlacesEnabled()
            ) {
                val tu = pl.victor.app.proactive.LocationContext.ostatnia
                val od = System.currentTimeMillis()
                val miejsca = tu?.let {
                    withTimeoutOrNull(OSM_PLACES_MS) {
                        pl.victor.app.proactive.OsmPlacesClient.kontekst(question, it.lat, it.lon)
                    }
                }
                runCatching {
                    diag.event(
                        DiagFormat.Phase.KONTEKST, "miejsca z OpenStreetMap",
                        mapOf(
                            "ms" to (System.currentTimeMillis() - od),
                            "są" to (miejsca != null),
                            "rodzaj" to (pl.victor.app.proactive.OsmPlaces.marka(question)
                                ?: pl.victor.app.proactive.OsmPlaces.rodzaj(question)?.nazwa)
                        )
                    )
                }
                if (miejsca != null) return opis + "\n\n" + miejsca
            }
            return opis
        }
        if (!oOkolicę) return null
        // Pytanie o okolicę, a położenia nie ma - model ma to powiedzieć, a
        // nie zgadywać miasta z ustawień.
        val powód = if (!pl.victor.app.proactive.LocationContext.maZgodę(context)) {
            "aplikacja nie ma zgody na lokalizację (Ustawienia telefonu → Aplikacje → V.I.C.T.O.R. → Uprawnienia)"
        } else {
            "telefon nie złapał teraz położenia"
        }
        runCatching {
            diag.event(
                DiagFormat.Phase.KONTEKST, "pytanie o okolicę bez położenia",
                mapOf("powód" to powód, "szczegół" to pl.victor.app.proactive.LocationContext.ostatniPowód)
            )
        }
        return "=== GDZIE JEST UŻYTKOWNIK ===\nNIE WIADOMO: $powód. Powiedz krótko, że nie " +
            "udało się ustalić położenia, i poproś o nazwę miejsca albo ulicy - nie zgaduj miasta " +
            "i nie twierdź, że GPS jest wyłączony."
    }

    /**
     * Prognoza pogody jako kontekst dla modelu.
     *
     * Do tej pory pogoda żyła wyłącznie w alertach w tle: aplikacja sprawdzała
     * ją co jakiś czas i wysyłała powiadomienie, gdy coś było nie tak. Zapytana
     * wprost - "jaka jest pogoda?" - odpowiadała z pamięci modelu, czyli
     * ZMYŚLAŁA. Teraz pytania o pogodę dostają prawdziwe dane.
     *
     * Wymaga klucza OpenWeatherMap i lokalizacji z ustawień; bez nich po prostu
     * nic nie dokleja, zamiast wywracać odpowiedź.
     */
    suspend fun buildWeatherContext(question: String, force: Boolean = false): String? {
        // `force` obchodzi bramkę słów kluczowych - używa go briefing, który
        // ma zebrać wszystko, o co użytkownik poprosił w ustawieniach, a nie
        // to, co akurat wynika z brzmienia pytania.
        if (!openContextTopics.dokleić(
                TOPIC_WEATHER,
                pytanieOTemat = pl.victor.app.proactive.WeatherContext.isAboutWeather(question),
                wymuszone = force
            )
        ) {
            return null
        }

        val apiKey = settings.getOpenWeatherApiKey()
        if (apiKey.isBlank()) {
            Log.d(TAG, "Pytanie o pogodę, ale brak klucza OpenWeatherMap")
            return missingContext(
                section = "POGODA",
                reason = "brakuje klucza OpenWeatherMap. Trzeba go wpisać w Ustawieniach.",
                force = force
            )
        }
        // POGODA TAM, GDZIE JESTEŚ, a nie tam, gdzie mieszkasz. Miasto z
        // ustawień zostaje na wypadek braku GPS. Tylko ostatnia znana pozycja,
        // bez czekania na pomiar - pogoda nie zmienia się co sto metrów.
        val tutaj = runCatching {
            pl.victor.app.proactive.LocationContext.tutaj(context)
        }.getOrNull()?.takeIf { it.miasto != null }
        val place = tutaj?.miasto ?: settings.getWeatherLocation()
        if (place.isBlank()) {
            Log.d(TAG, "Pytanie o pogodę, ale brak ustawionej lokalizacji")
            return missingContext(
                section = "POGODA",
                reason = "nie ustawiono miejscowości. Trzeba ją wpisać w Ustawieniach.",
                force = force
            )
        }
        // Pytanie o INNE miejsce niż w ustawieniach - prognoza z ustawień tylko
        // by przeszkadzała, patrz WeatherContext.innaMiejscowość.
        // Tylko przy pytaniu WPROST o pogodę. Pytanie dopowiadające ("a jutro?")
        // dostaje prognozę z tematu otwartego wcześniej - i wtedy "na youtubie"
        // albo "na nim" nie jest żadnym miejscem (dziennik z biegu 154).
        if (!force && pl.victor.app.proactive.WeatherContext.isAboutWeather(question)) {
            pl.victor.app.proactive.WeatherContext.innaMiejscowość(question, place)?.let { other ->
                runCatching {
                    diag.event(
                        DiagFormat.Phase.KONTEKST, "pogoda dla innego miejsca - każę szukać",
                        mapOf("miejsce" to other, "zUstawień" to place)
                    )
                }
                return pl.victor.app.proactive.WeatherContext.otherPlaceNote(other, place)
            }
        }
        return try {
            val service = pl.victor.app.proactive.WeatherService(apiKey)
            val (lat, lon) = if (tutaj != null) {
                tutaj.lat to tutaj.lon
            } else {
                val geo = service.geocode(place) ?: return null
                geo.lat to geo.lon
            }
            val forecast = service.getForecast(lat, lon)
            val air = runCatching { service.getAirQuality(lat, lon) }.getOrNull()
            pl.victor.app.proactive.WeatherContext.buildPromptContext(forecast, air)
                ?.also { Log.i(TAG, "Doklejam prognozę pogody dla $place") }
        } catch (e: Exception) {
            // Padnięte API pogodowe nie może wywrócić odpowiedzi na pytanie.
            Log.w(TAG, "Pobranie pogody nie powiodło się", e)
            null
        }
    }

    /**
     * Ostatnie maile jako kontekst dla modelu.
     *
     * Tak jak kalendarz - doklejane tylko gdy pytanie faktycznie dotyczy
     * poczty. Wymaga połączonego konta Google (patrz [pl.victor.app.google.GoogleAccountManager]);
     * bez tego po prostu nic nie dokleja, zamiast pokazywać błąd.
     *
     * @return fragment promptu albo `null`, gdy pytanie nie dotyczy maili,
     *         konto nie jest połączone albo nie ma żadnych wiadomości
     */
    suspend fun buildGmailContext(question: String, force: Boolean = false): String? {
        // `force` obchodzi bramkę słów kluczowych - używa go briefing, który
        // ma zebrać wszystko, o co użytkownik poprosił w ustawieniach, a nie
        // to, co akurat wynika z brzmienia pytania.
        if (!openContextTopics.dokleić(
                TOPIC_MAIL,
                pytanieOTemat = pl.victor.app.proactive.GmailContext.isAboutEmail(question),
                wymuszone = force
            )
        ) {
            return null
        }

        val gmail = pl.victor.app.google.GmailService(context)
        // WYGASŁE logowanie przed "niepołączonym": od chwili wykrycia wygaśnięcia
        // konto liczy się jako niezalogowane, więc bez tej gałęzi użytkownik
        // usłyszałby "podłącz konto" - a on je podłączył i nic nie zrobił źle.
        // Rada jest zresztą inna: nie ma czego konfigurować, trzeba kliknąć jeszcze raz.
        if (pl.victor.app.google.GoogleAccountManager.isLoginExpired()) {
            Log.d(TAG, "Pytanie o maile, ale logowanie Google wygasło")
            return missingContext(
                section = "POCZTA",
                reason = "logowanie Google wygasło i trzeba je odnowić w Ustawieniach. " +
                    "Google unieważnia je co siedem dni, dopóki aplikacja jest w trybie " +
                    "testowym - to normalne, nie usterka.",
                force = force
            )
        }
        if (!gmail.isSignedIn()) {
            Log.d(TAG, "Pytanie o maile, ale brak połączonego konta Google")
            return missingContext(
                section = "POCZTA",
                reason = "konto Google nie jest połączone. Trzeba je podłączyć w Ustawieniach.",
                force = force
            )
        }
        if (!gmail.hasAccess()) {
            // Konto jest połączone, ale poczta wymaga OSOBNEJ zgody. Bez tej gałęzi
            // model dostawał pustą listę i odpowiadał, że nie ma nowych maili albo
            // że nie umie ich czytać - obie odpowiedzi nieprawdziwe.
            Log.d(TAG, "Pytanie o maile, ale konto nie ma zgody na pocztę")
            return missingContext(
                section = "POCZTA",
                reason = "poczta wymaga osobnej zgody Google. Użytkownik włącza ją " +
                    "w Ustawieniach, w karcie konta Google, przyciskiem \"Włącz pocztę\".",
                force = force
            )
        }
        return try {
            val messages = gmail.getRecentMessages(maxResults = 8)
            pl.victor.app.proactive.GmailContext.buildPromptContext(messages)
                ?.also { Log.i(TAG, "Doklejam ${messages.size} maili") }
        } catch (e: Exception) {
            // Brak dostępu do Gmaila nie może wywrócić odpowiedzi na pytanie.
            Log.w(TAG, "Odczyt Gmaila nie powiódł się", e)
            null
        }
    }

    /**
     * Szuka w historii rozmów podobnych do bieżącego pytania i buduje z nich kontekst.
     * Respektuje przełącznik "pamięć długoterminowa" w ustawieniach.
     *
     * @return fragment promptu albo `null` gdy wyłączone lub brak trafień
     */
    suspend fun buildMemoryContext(
        question: String,
        notesInPrompt: Boolean = false,
        audioTurn: Boolean = false
    ): String? {
        if (!settings.isLongTermMemoryEnabled()) return null
        // PO ZNACZENIU, gdy włączone: "gdzie zostawiłem klucze?" znajduje
        // notatkę "położyłem klucze na szafce", czego słowa nie umiały.
        // Szuka w rozmowach, notatkach (w tym podsumowaniach spotkań) i
        // faktach naraz; słowa nadal biorą udział w wyniku. Wektor pytania ma
        // limit czasu, więc tura nie zwalnia - po nim zostają same słowa.
        //
        // Przy pytaniu NAGRANIEM `question` to tylko instrukcja ("odpowiedz na
        // pytanie z nagrania"), bez słów użytkownika - jej wektor trafiałby w
        // przypadkowe wpisy i kosztował zapytanie na nic. Taka tura idzie jak
        // dotąd.
        if (!audioTurn && settings.isSemanticMemoryEnabled()) {
            try {
                // Fakty z polecenia i - gdy pytanie dokleiło je w całości -
                // notatki nie mogą wejść drugi raz.
                val factsInPrompt = settings.getFacts()
                    .take(pl.victor.app.memory.UserFacts.PROMPT_LIMIT)
                    .map { pl.victor.app.memory.SemanticMemory.factDoc(it.text, it.createdAtMs).key }
                    .toSet()
                val outcome = semanticMemory.search(
                    question,
                    excludeKinds = if (notesInPrompt) setOf(pl.victor.app.memory.SemanticMemory.Kind.NOTATKA) else emptySet(),
                    excludeKeys = factsInPrompt
                )
                return pl.victor.app.memory.SemanticMemory.buildPromptContext(outcome.docs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Awaria nowej drogi nie może zabrać starej - schodzimy do słów.
                Log.w(TAG, "Pamięć po znaczeniu niedostępna - szukam słowami", e)
            }
        }
        return try {
            val entries = history.getRecent(MEMORY_SEARCH_POOL)
            if (entries.isEmpty()) return null

            val matches = longTermMemory
                .findSimilar(question, entries, limit = MEMORY_MAX_MATCHES)
                .filter { it.score >= MEMORY_MIN_SCORE }
            // Ślad także dla darmowej drogi - porównanie "ile znalazły słowa,
            // ile znaczenie" wymaga obu wierszy w dzienniku.
            runCatching {
                diag.event(
                    DiagFormat.Phase.KONTEKST, "pamięć: wyszukiwanie słowami",
                    mapOf("rozmów" to entries.size, "zSłów" to matches.size)
                )
            }
            if (matches.isEmpty()) return null

            Log.i(TAG, "Pamięć: ${matches.size} podobnych rozmów (najlepsza ${matches.first().score})")
            buildString {
                append("Wcześniejsze rozmowy z tym użytkownikiem na podobny temat:\n")
                matches.forEach { match ->
                    append("- Pytanie: ").append(match.entry.userQuestion.take(200)).append('\n')
                    append("  Odpowiedź: ").append(match.entry.aiResponse.take(300)).append('\n')
                }
                append("Wykorzystaj to jeśli pomaga, ale nie powtarzaj bez potrzeby.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pamięć długoterminowa niedostępna", e)
            null
        }
    }

    /**
     * Tłumaczy tekst odczytany przez OCR na język docelowy z ustawień,
     * ale tylko gdy użytkownik faktycznie prosi o tłumaczenie.
     */
    suspend fun translateOcrIfRequested(question: String, ocr: OCRResult?): String? {
        if (ocr == null || !ocr.isSuccess || ocr.fullText.isBlank()) return null
        if (!wantsTranslation(question)) return null

        val target = settings.getTranslationTarget()
        return try {
            val source = settings.getResponseLanguage().take(2).lowercase()
            // PRZYCIĘCIE MA BYĆ WIDOCZNE, NIE MILCZĄCE.
            //
            // Stało tu `ocr.fullText.take(1000)` - urwanie w połowie zdania bez
            // żadnego śladu. Tłumaczenie wyglądało na kompletne i po prostu
            // kończyło się w środku. Zgłoszone jako "gdy proszę o tłumaczenie
            // większego tekstu, tłumaczy tylko fragment".
            val cięcie = pl.victor.app.translation.LongTextCut.przytnij(ocr.fullText)
            val translated = translator.translate(cięcie.tekst, source, target)
            if (translated.isBlank() || translated == ocr.fullText) return null
            val targetName = pl.victor.app.translation.SimultaneousTranslator.languageName(target)
            Log.i(TAG, "Przetłumaczono OCR na $target (przycięte=${cięcie.przycięte})")
            val ogon = if (cięcie.przycięte) {
                "\n(To jest POCZĄTEK tekstu - reszta się nie zmieściła. " +
                    "Powiedz to użytkownikowi jednym zdaniem na końcu.)"
            } else {
                ""
            }
            "Tłumaczenie odczytanego tekstu ($targetName):\n$translated$ogon"
        } catch (e: Exception) {
            Log.w(TAG, "Tłumaczenie OCR nie powiodło się", e)
            null
        }
    }

    /** Czy pytanie użytkownika dotyczy tłumaczenia. */
    private fun wantsTranslation(question: String): Boolean {
        val q = question.lowercase()
        return TRANSLATION_KEYWORDS.any { q.contains(it) }
    }

    companion object {
        /** Klucze tematów kontekstu - patrz [openContextTopics]. */
        private const val TOPIC_CALENDAR = "kalendarz"
        private const val TOPIC_WEATHER = "pogoda"
        private const val TOPIC_MAIL = "poczta"
        private const val TOPIC_NOTES = "notatki"
        private const val TOPIC_LOCATION = "położenie"

        /** Najdłużej tyle czeka lista miejsc z OpenStreetMap (wspólny termin kontekstów i tak obowiązuje). */
        private const val OSM_PLACES_MS = 4_000L

        /** Pytanie o telefon albo wiadomość do kogoś - patrz [buildContactsContext]. */
        private val KONTAKT_PYTANIE = Regex(
            """(zadzwo|dzwo[nń]|po[lł][aą]cz (si[eę] )?z|wybierz numer|napisz|wy[sś]lij|sms|esemes|wiadomo[sś][cć] do|odpisz|numer (do|telefonu)|kontakt)"""
        )

        /** Ile ostatnich rozmów przeszukiwać w pamięci długoterminowej. */
        private const val MEMORY_SEARCH_POOL = 50
        private const val MEMORY_MAX_MATCHES = 3
        private const val MEMORY_MIN_SCORE = 0.15f

        private val TRANSLATION_KEYWORDS = listOf(
            "przetłumacz", "tłumacz", "translate", "po angielsku", "po niemiecku",
            "po polsku", "co to znaczy", "what does it mean"
        )

        /** Ten sam znacznik logcata co orkiestrator - filtry logów działają jak dotąd. */
        private const val TAG = "AIOrchestrator"
    }
}
