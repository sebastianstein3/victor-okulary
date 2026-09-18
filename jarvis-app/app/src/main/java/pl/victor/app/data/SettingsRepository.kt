package pl.victor.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repozytorium ustawień - klucze API, preferencje użytkownika.
 *
 * Wszystkie klucze API są szyfrowane przez EncryptedSharedPreferences (AES-256-GCM).
 * NIGDY nie loguj kluczy, NIGDY nie wysyłaj ich do analityki.
 */
class SettingsRepository private constructor(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "victor_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // === Provider AI ===

    fun getActiveProvider(): String =
        prefs.getString(KEY_ACTIVE_PROVIDER, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER

    fun setActiveProvider(providerId: String) {
        prefs.edit().putString(KEY_ACTIVE_PROVIDER, providerId).apply()
    }

    /**
     * Wybrany model dla danego providera (null = użyj domyślnego).
     */
    fun getSelectedModel(providerId: String): String? =
        prefs.getString("$KEY_MODEL_PREFIX$providerId", null)

    fun setSelectedModel(providerId: String, modelId: String) {
        prefs.edit().putString("$KEY_MODEL_PREFIX$providerId", modelId).apply()
    }

    fun clearSelectedModel(providerId: String) {
        prefs.edit().remove("$KEY_MODEL_PREFIX$providerId").apply()
    }

    /**
     * Model lokalny nie ma klucza API - reszta apki (fallback, ekran testu
     * połączenia) jest napisana pod założenie "pusty klucz = provider
     * niedostępny", więc zwracamy stały placeholder zamiast rozsiewać
     * specjalne przypadki po całym kodzie. Prawdziwym warunkiem gotowości
     * jest pobrany plik modelu - patrz [hasApiKey].
     */
    fun getApiKey(providerId: String): String? =
        if (providerId == LOCAL_PROVIDER_ID) LOCAL_PROVIDER_PLACEHOLDER_KEY
        else prefs.getString("$KEY_API_PREFIX$providerId", null)

    fun setApiKey(providerId: String, key: String) {
        require(key.isNotBlank()) { "API key cannot be blank" }
        prefs.edit().putString("$KEY_API_PREFIX$providerId", key).apply()
    }

    fun hasApiKey(providerId: String): Boolean =
        if (providerId == LOCAL_PROVIDER_ID) {
            pl.victor.app.localmodel.LocalModelStorage.isDownloaded(context, pl.victor.app.localmodel.LocalModelCatalog.QWEN_0_8B)
        } else {
            !getApiKey(providerId).isNullOrBlank()
        }

    /**
     * Kiedy ostatnio sprawdzono modele u providera (ms since epoch).
     */
    fun getLastModelValidation(providerId: String): Long =
        prefs.getLong("$KEY_VALIDATION_PREFIX$providerId", 0L)

    fun setLastModelValidation(providerId: String, timestamp: Long) {
        prefs.edit().putLong("$KEY_VALIDATION_PREFIX$providerId", timestamp).apply()
    }

    // === Funkcje AI ===

    fun isWebSearchEnabled(): Boolean =
        prefs.getBoolean(KEY_WEB_SEARCH, true)  // domyślnie włączone

    fun setWebSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEB_SEARCH, enabled).apply()
    }

    fun getResponseLanguage(): String =
        prefs.getString(KEY_RESPONSE_LANG, "pl") ?: "pl"

    fun setResponseLanguage(lang: String) {
        prefs.edit().putString(KEY_RESPONSE_LANG, lang).apply()
    }

    // === Wake word (v1.1) ===

    /**
     * Reaktywne odbicie [KEY_WAKE_WORD_ENABLED]. [VictorApplication] nasłuchuje tego
     * flow razem ze stanem połączenia BLE, żeby wiedzieć, kiedy uruchomić/zatrzymać
     * [pl.victor.app.ble.VictorForegroundService] - bez tego trzeba by pamiętać o
     * wywołaniu usługi z każdego miejsca, które przełącza wake word (onboarding,
     * ustawienia, automatyczny PowerManager), co łatwo pominąć.
     */
    private val _wakeWordEnabledFlow = MutableStateFlow(
        prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
    )
    val wakeWordEnabledFlow: StateFlow<Boolean> = _wakeWordEnabledFlow.asStateFlow()

    /**
     * Co dostaje model: miniaturę czy zdjęcie w pełnej rozdzielczości.
     *
     * Wartości: [PHOTO_THUMBNAIL] albo [PHOTO_FULL].
     *
     * Miniatura idzie samym BLE i jest natychmiast, ale liter z bliska nie da się
     * z niej odczytać. Pełne wymaga Wi-Fi Direct z okularami - jest wolniejsze,
     * za to nadaje się do czytania tekstu.
     */
    fun getPhotoSource(): String = prefs.getString(KEY_PHOTO_SOURCE, PHOTO_FULL) ?: PHOTO_FULL

    fun setPhotoSource(value: String) {
        prefs.edit().putString(KEY_PHOTO_SOURCE, value).apply()
    }

    /**
     * Ile razy zmniejszyć zdjęcie pełnowymiarowe przed wysłaniem do modelu.
     *
     * Domyślnie dwa. Litery zostają czytelne, a plik jest wyraźnie lżejszy - a to
     * on decyduje o tym, jak długo trwa tura. Jeden oznacza brak zmniejszania.
     */
    fun getPhotoDivisor(): Int = prefs.getInt(KEY_PHOTO_DIVISOR, 2).coerceIn(1, 4)

    fun setPhotoDivisor(value: Int) {
        prefs.edit().putInt(KEY_PHOTO_DIVISOR, value.coerceIn(1, 4)).apply()
    }

    /**
     * Czy zapisywać dziennik diagnostyczny i wysyłać go na GitHuba.
     *
     * Domyślnie WŁĄCZONY na czas testów ze sprzętem: bez dziennika zgłoszenie
     * "zawiesiło się" nie niesie żadnej informacji, a przy okularach na głowie
     * nikt nie patrzy w logcat. Sam zapis jest tani - wysyłka idzie dopiero
     * wtedy, gdy token jest wpisany.
     */
    fun isDiagnosticLogEnabled(): Boolean = prefs.getBoolean(KEY_DIAG_LOG, true)

    fun setDiagnosticLogEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIAG_LOG, enabled).apply()
    }

    /**
     * Token GitHuba do wysyłki dziennika. Pusty = dziennik zostaje na telefonie.
     *
     * Wystarczy token o zakresie `Contents: Read and write` na jedno
     * repozytorium - patrz [pl.victor.app.diagnostics.DiagnosticUploader].
     */
    fun getGithubToken(): String = prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""

    fun setGithubToken(token: String) {
        prefs.edit().putString(KEY_GITHUB_TOKEN, token.trim()).apply()
    }

    fun hasGithubToken(): Boolean = getGithubToken().isNotBlank()

    /**
     * Czy pytanie wolno przepisywać na tekst przez usługę w chmurze.
     *
     * ## Domyślnie WŁĄCZONE, ale bez klucza i tak nic nie wysyła
     * Rozpoznawanie lokalne myli słowa na tyle, że model odpowiada pewnie i nie na
     * temat - a to jest gorsze niż brak odpowiedzi. Dlatego droga przez chmurę
     * jest domyślna, gdy klucz OpenAI jest już w ustawieniach.
     *
     * Kto nie chce wysyłać nagrań poza telefon, wyłącza to i zostaje przy
     * rozpoznawaniu systemowym oraz Vosku. Nagranie głosu to dane wrażliwe, więc
     * ta decyzja ma być widoczna, a nie schowana w innej funkcji.
     */
    fun isCloudTranscriptionEnabled(): Boolean =
        prefs.getBoolean(KEY_CLOUD_TRANSCRIPTION, true)

    fun setCloudTranscriptionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_TRANSCRIPTION, enabled).apply()
    }

    /**
     * Czy nasłuch frazy ma iść przez MIKROFON OKULARÓW zamiast telefonu.
     *
     * ## Domyślnie WYŁĄCZONE i to jest ważne
     * Mikrofon zestawu Bluetooth działa wyłącznie przez profil rozmowy (SCO/HFP).
     * Nasłuch frazy trwa bez przerwy, więc włączenie tego trzyma okulary w trybie
     * ROZMOWY przez cały czas - a wtedy Android pokazuje je jako urządzenie "do
     * połączeń", nie "do multimediów", i nie da się przez nie słuchać muzyki.
     * Przełącznik multimediów w ustawieniach systemu wraca wtedy sam do wyłączenia,
     * bo aplikacja natychmiast zajmuje profil rozmowy z powrotem.
     *
     * Zgłoszone dokładnie tak: "okulary łączą się jako używane do połączeń, a nie
     * do odtwarzania, po kliknięciu tej opcji sama się wyłącza".
     *
     * Wybudzanie okularami i tak działa bez tego - okulary wysyłają je własną
     * drogą po BLE. To ustawienie dotyczy wyłącznie frazy wypowiadanej do telefonu.
     */
    fun isWakeWordOverGlassesMic(): Boolean =
        prefs.getBoolean(KEY_WAKE_WORD_GLASSES_MIC, false)

    fun setWakeWordOverGlassesMic(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_WORD_GLASSES_MIC, enabled).apply()
    }

    fun isWakeWordEnabled(): Boolean = _wakeWordEnabledFlow.value

    fun setWakeWordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
        _wakeWordEnabledFlow.value = enabled
    }

    // === Conversational mode (v1.2) ===

    fun isConversationalModeEnabled(): Boolean =
        prefs.getBoolean(KEY_CONVERSATIONAL_MODE, false)

    fun setConversationalModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONVERSATIONAL_MODE, enabled).apply()
    }

    // === Long-term memory (v1.2) ===

    fun isLongTermMemoryEnabled(): Boolean =
        prefs.getBoolean(KEY_LONG_TERM_MEMORY, true)  // domyślnie ON

    fun setLongTermMemoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LONG_TERM_MEMORY, enabled).apply()
    }

    // === Dostępność ===

    /** Wysoki kontrast - czerń/biel zamiast dynamic color. */
    fun isHighContrastEnabled(): Boolean =
        prefs.getBoolean(KEY_HIGH_CONTRAST, false)

    fun setHighContrastEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGH_CONTRAST, enabled).apply()
    }

    /** Powiększony tekst w całym interfejsie. */
    fun isLargeTextEnabled(): Boolean =
        prefs.getBoolean(KEY_LARGE_TEXT, false)

    fun setLargeTextEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LARGE_TEXT, enabled).apply()
    }

    // === Translation (v1.2) ===

    fun getTranslationTarget(): String =
        prefs.getString(KEY_TRANSLATION_TARGET, "en") ?: "en"  // domyślnie angielski

    fun setTranslationTarget(lang: String) {
        prefs.edit().putString(KEY_TRANSLATION_TARGET, lang).apply()
    }

    // === Konto Google - Calendar + Gmail, jedno logowanie (v1.2, rozszerzone) ===

    /** Nazwa klucza zostaje z czasów gdy dotyczyła tylko kalendarza - flaga już nie. */
    fun isGoogleAccountConnected(): Boolean =
        prefs.getBoolean(KEY_GCAL_CONNECTED, false)

    fun setGoogleAccountConnected(connected: Boolean) {
        prefs.edit().putBoolean(KEY_GCAL_CONNECTED, connected).apply()
    }

    // === Capture mode (v1.3) ===

    /**
     * Preferowany tryb capture (BURST_PHOTO / HIGH_QUALITY_SINGLE / VIDEO_SHORT itd).
     * Domyślnie: HIGH_QUALITY_SINGLE (jedno zdjęcie).
     *
     * Było BURST_PHOTO, czyli PIĘĆ zdjęć. Każde to osobny cykl komendy i
     * osobny transfer miniatury po BLE - kanał wąski, więc czas rośnie
     * liniowo. Użytkownik widział "przechwytywanie 1/5 ... 5/5" i czekał,
     * zanim pytanie w ogóle poszło do modelu. Wielokadrowy kontekst przydaje
     * się przy gestach i ruchu; przy "co to jest?" jedno ostre zdjęcie daje
     * tę samą odpowiedź kilka razy szybciej. Burst został w ustawieniach dla
     * tych, którzy go potrzebują.
     */
    fun getPreferredCaptureMode(): String =
        prefs.getString(KEY_CAPTURE_MODE, pl.victor.app.ai.CaptureMode.HIGH_QUALITY_SINGLE.name)
            ?: pl.victor.app.ai.CaptureMode.HIGH_QUALITY_SINGLE.name

    fun setPreferredCaptureMode(mode: String) {
        prefs.edit().putString(KEY_CAPTURE_MODE, mode).apply()
    }

    /**
     * Czy auto-degradacja z wideo na zdjęcia jest włączona.
     */
    fun isAutoDegradeCaptureEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_DEGRADE_CAPTURE, true)

    fun setAutoDegradeCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DEGRADE_CAPTURE, enabled).apply()
    }

    // === Power management (v1.4) ===

    fun getPowerMode(): String = prefs.getString(KEY_POWER_MODE, "NORMAL") ?: "NORMAL"
    fun setPowerMode(mode: String) {
        prefs.edit().putString(KEY_POWER_MODE, mode).apply()
    }

    fun isAutoPowerModeEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_POWER, true)
    fun setAutoPowerModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_POWER, enabled).apply()
    }

    /**
     * Czy próbować kolejnego providera AI, gdy aktywny zawiedzie zanim padnie
     * pierwszy fragment odpowiedzi (patrz [pl.victor.app.AIOrchestrator]). Domyślnie
     * włączone - próbuje tylko providerów, dla których user już wpisał klucz API,
     * więc nic nowego nie wysyła się donikąd.
     */
    fun isAutoProviderFallbackEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_PROVIDER_FALLBACK, true)
    fun setAutoProviderFallbackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PROVIDER_FALLBACK, enabled).apply()
    }

    /**
     * Czy prosić model, żeby nie rozmyślał przed odpowiedzią.
     *
     * ## Co to daje i co kosztuje
     * Pomiar z 14 września, dziesięć tur pod rząd:
     *
     *     wejście=1288  odpowiedź=50  myślenie=273  razem=1611
     *     wejście=1288  odpowiedź=42  myślenie=441  razem=1771
     *
     * Myślenie zjada cztery do ośmiu razy więcej niż sama odpowiedź, a tokeny
     * wyjściowe są najdroższe - więc jego ograniczenie realnie tnie rachunek.
     *
     * Kosztuje jednak JAKOŚĆ ROZUMOWANIA, a nie tylko pieniądze. Dlatego
     * domyślnie WYŁĄCZONE: asystent ma działać najlepiej jak umie, a oszczędza
     * dopiero wtedy, gdy człowiek świadomie o to poprosi.
     */
    fun isThinkingLimitEnabled(): Boolean = prefs.getBoolean(KEY_THINKING_LIMIT, false)
    fun setThinkingLimitEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_THINKING_LIMIT, enabled).apply()
    }

    /**
     * Czy przy prowadzeniu do celu ma też działać ostrzeganie o przeszkodach.
     *
     * ## Czemu domyślnie WYŁĄCZONE
     * Bo wskazówki trasy liczą i mówią mapy - nas nie kosztują nic - a
     * ostrzeganie o przeszkodach to nasza pętla pytająca model o obraz, circa
     * 1600 tokenów za zapytanie. Przy trasie na drugi koniec miasta robi się z
     * tego pozycja na rachunku, której nikt nie zamawiał.
     *
     * Kto chce obu naraz, włącza to tutaj albo mówi "prowadź do X z asystentem"
     * - i wtedy jest to jego decyzja, a nie domyślne ustawienie, którego nie widać.
     */
    fun isRouteAssistEnabled(): Boolean = prefs.getBoolean(KEY_ROUTE_ASSIST, false)
    fun setRouteAssistEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROUTE_ASSIST, enabled).apply()
    }

    fun setProactiveIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_PROACTIVE_INTERVAL, minutes).apply()
    }
    fun getProactiveIntervalMinutes(): Int = prefs.getInt(KEY_PROACTIVE_INTERVAL, 15)

    fun setHistoryLimit(limit: Int) {
        prefs.edit().putInt(KEY_HISTORY_LIMIT, limit).apply()
    }
    fun getHistoryLimit(): Int = prefs.getInt(KEY_HISTORY_LIMIT, 20)

    /**
     * Tryb symulowanych okularów - pozwala przejść całą ścieżkę aplikacji
     * bez sprzętu. Domyślnie wyłączony.
     */
    fun isGlassesSimulationEnabled(): Boolean =
        prefs.getBoolean(KEY_GLASSES_SIMULATION, false)

    fun setGlassesSimulationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLASSES_SIMULATION, enabled).apply()
    }

    /**
     * ID wybranej komendy głosowej. Domyślnie "computer" - działa od razu,
     * bez wgrywania własnego modelu. "Hey Victor" (id `hey_victor`) to fraza
     * docelowa, ale wymaga wytrenowania - patrz [WakeWordRegistry].
     *
     * Wcześniejsze wersje zapisywały tu identyfikatory fraz, których Porcupine
     * nie obsługuje (np. "jarvis_start", "hej_cyan"). Takie zapisy nie istnieją
     * już w katalogu, więc [getSelectedWakeWordEntry] schodzi wtedy na domyślną.
     */
    fun getSelectedWakeWordId(): String =
        prefs.getString(KEY_WAKE_WORD, WakeWordRegistry.default().id)
            ?: WakeWordRegistry.default().id

    fun setSelectedWakeWordId(id: String) {
        prefs.edit().putString(KEY_WAKE_WORD, id).apply()
    }

/**
     * Wybrana komenda jako wpis katalogu - z niego wiadomo nie tylko jaka fraza,
     * ale też czy Porcupine ją zna, czy potrzebny jest własny model.
     */
    fun getSelectedWakeWordEntry(): WakeWord {
        val id = getSelectedWakeWordId()
        return WakeWordRegistry.findById(id) ?: WakeWordRegistry.default()
    }

    /**
     * Pełna fraza komendy (rozwiązana z ID + custom jeśli potrzeba).
     * Do wyświetlania; do inicjalizacji detektora służy [getSelectedWakeWordEntry].
     */
    fun getSelectedWakeWord(): String {
        val entry = getSelectedWakeWordEntry()
        return if (entry.id == "custom") getCustomWakeWord() else entry.phrase
    }

    /**
     * Ścieżka do własnego pliku `.ppn` z konsoli Picovoice.
     * Bez niego fraza spoza wbudowanej listy nie zadziała.
     */
    fun getCustomKeywordPath(): String = prefs.getString(KEY_KEYWORD_PATH, "") ?: ""

    fun setCustomKeywordPath(path: String) {
        prefs.edit().putString(KEY_KEYWORD_PATH, path).apply()
    }

    /**
     * Ścieżka do modelu językowego `.pv` - potrzebna tylko dla fraz
     * w językach innych niż angielski (np. polskich).
     */
    fun getCustomModelPath(): String = prefs.getString(KEY_MODEL_PATH, "") ?: ""

    fun setCustomModelPath(path: String) {
        prefs.edit().putString(KEY_MODEL_PATH, path).apply()
    }

    fun setSelectedWakeWord(phrase: String) {
        // Stara metoda - zapisuje jako custom
        // Backward compatibility
        setCustomWakeWord(phrase)
    }

    /**
     * Własna komenda (dla "custom").
     */
    fun getCustomWakeWord(): String =
        prefs.getString(KEY_CUSTOM_WAKE_WORD, "") ?: ""

    fun setCustomWakeWord(phrase: String) {
        prefs.edit().putString(KEY_CUSTOM_WAKE_WORD, phrase).apply()
    }

    // === Picovoice (wake word) ===

    /**
     * Picovoice AccessKey z konsoli https://console.picovoice.ai/
     * Darmowy tier: 3 wake words / urządzenie.
     */
    fun getPicovoiceAccessKey(): String =
        prefs.getString(KEY_PICOVOICE_KEY, "") ?: ""

    fun setPicovoiceAccessKey(key: String) {
        prefs.edit().putString(KEY_PICOVOICE_KEY, key).apply()
    }

    // === Action mode ===

    /**
     * Tryb wykonywania akcji: SAFE (Intent) lub DIRECT (bezpośrednio).
     */
    fun getActionMode(): String =
        prefs.getString(KEY_ACTION_MODE, "SAFE") ?: "SAFE"

    fun setActionMode(mode: String) {
        prefs.edit().putString(KEY_ACTION_MODE, mode).apply()
    }

    // === Codzienny briefing ===

    fun isBriefingEnabled(): Boolean = prefs.getBoolean(KEY_BRIEFING_ENABLED, false)

    fun setBriefingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BRIEFING_ENABLED, enabled).apply()
    }

    /** Godzina briefingu (0-23). */
    fun getBriefingHour(): Int = prefs.getInt(KEY_BRIEFING_HOUR, DEFAULT_BRIEFING_HOUR)

    /** Minuta briefingu (0-59). */
    fun getBriefingMinute(): Int = prefs.getInt(KEY_BRIEFING_MINUTE, 0)

    fun setBriefingTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt(KEY_BRIEFING_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_BRIEFING_MINUTE, minute.coerceIn(0, 59))
            .apply()
    }

    /**
     * Preferencje briefingu - co ma w nim być i jak długi ma być.
     *
     * Domyślnie tylko kalendarz i pogoda: poczta bywa długa i osobista, a
     * jakość powietrza interesuje nielicznych. Lepiej, żeby użytkownik dołożył
     * to, czego chce, niż żeby pierwszego ranka wyłączył całość, bo za dużo gada.
     */
    fun getBriefingPreferences(): pl.victor.app.proactive.DailyBriefing.Preferences =
        pl.victor.app.proactive.DailyBriefing.Preferences(
            includeCalendar = prefs.getBoolean(KEY_BRIEFING_CALENDAR, true),
            includeWeather = prefs.getBoolean(KEY_BRIEFING_WEATHER, true),
            includeAirQuality = prefs.getBoolean(KEY_BRIEFING_AIR, false),
            includeMail = prefs.getBoolean(KEY_BRIEFING_MAIL, false),
            focus = prefs.getString(KEY_BRIEFING_FOCUS, "").orEmpty(),
            length = pl.victor.app.proactive.DailyBriefing.Length.fromId(
                prefs.getString(KEY_BRIEFING_LENGTH, null)
            )
        )

    fun setBriefingPreferences(value: pl.victor.app.proactive.DailyBriefing.Preferences) {
        prefs.edit()
            .putBoolean(KEY_BRIEFING_CALENDAR, value.includeCalendar)
            .putBoolean(KEY_BRIEFING_WEATHER, value.includeWeather)
            .putBoolean(KEY_BRIEFING_AIR, value.includeAirQuality)
            .putBoolean(KEY_BRIEFING_MAIL, value.includeMail)
            .putString(KEY_BRIEFING_FOCUS, value.focus)
            .putString(KEY_BRIEFING_LENGTH, value.length.id)
            .apply()
    }

    // === Własne komendy użytkownika ===

    /**
     * Komendy zdefiniowane przez użytkownika: własna fraza → istniejąca akcja.
     *
     * Zapisywane jako zwykły tekst, jedna komenda na linię, pola rozdzielone
     * `|`. Świadomie NIE jest to JSON: pola są trzy, nigdy się nie zagnieżdżają,
     * a czytelny zapis pozwala obejrzeć i naprawić plik ustawień bez narzędzi.
     * Fraza ma znaki `|` i nowe linie usunięte przy zapisie, więc rozdzielenie
     * jest jednoznaczne.
     */
    fun getCustomCommands(): List<pl.victor.app.actions.CustomCommands.CustomCommand> {
        val raw = prefs.getString(KEY_CUSTOM_COMMANDS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size < 2) return@mapNotNull null
            val type = runCatching {
                pl.victor.app.actions.ActionType.valueOf(parts[1])
            }.getOrNull() ?: return@mapNotNull null
            val phrase = parts[0]
            if (phrase.isBlank()) return@mapNotNull null
            pl.victor.app.actions.CustomCommands.CustomCommand(
                phrase = phrase,
                type = type,
                argument = parts.getOrNull(2).orEmpty()
            )
        }
    }

    fun setCustomCommands(
        commands: List<pl.victor.app.actions.CustomCommands.CustomCommand>
    ) {
        val raw = commands.joinToString("\n") { command ->
            listOf(command.phrase, command.type.name, command.argument)
                .joinToString(FIELD_SEPARATOR) { it.sanitizeField() }
        }
        prefs.edit().putString(KEY_CUSTOM_COMMANDS, raw).apply()
    }

    // === Notatki dyktowane głosem ===

    /**
     * Notatki, najnowsze pierwsze.
     *
     * Zapis linia-po-linii, tak samo jak własne komendy: notatek są dziesiątki,
     * nie tysiące, a osobna baza kosztowałaby migracje i zależność, których ta
     * funkcja nie potrzebuje.
     */
    // === Zapamiętane miejsca ===
    //
    // W preferencjach, nie w bazie: to są dwa-trzy wpisy o czterech polach,
    // a migracja Room kosztowałaby więcej niż cała funkcja. Jeden klucz na
    // miejsce, wartość rozdzielana średnikiem - bez biblioteki, bo przy takim
    // kształcie danych parser mieści się w jednym wierszu.

    /** Zapisuje miejsce pod nazwą; nadpisuje poprzednie o tej samej nazwie. */
    fun savePlace(
        name: String,
        latitude: Double,
        longitude: Double,
        atMs: Long,
        signText: String? = null
    ) {
        prefs.edit()
            .putString(placeKey(name), "$latitude;$longitude;$atMs")
            // Napis pod OSOBNYM kluczem, nie w tym samym napisie: tekst z
            // tablicy może zawierać średnik, a wtedy rozdzielanie po nim
            // rozjechałoby współrzędne. Tani sposób na uniknięcie własnego
            // formatu z ucieczkami.
            .putString(signKey(name), signText)
            .apply()
    }

    /** Zapamiętane miejsce albo `null`, gdy nic pod tą nazwą nie stoi. */
    fun getPlace(name: String): pl.victor.app.memory.PlaceMemory.Place? {
        val raw = prefs.getString(placeKey(name), null) ?: return null
        val parts = raw.split(';')
        if (parts.size != PLACE_FIELDS) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val at = parts[2].toLongOrNull() ?: return null
        return pl.victor.app.memory.PlaceMemory.Place(
            name = name,
            latitude = lat,
            longitude = lon,
            savedAtMs = at,
            signText = prefs.getString(signKey(name), null)
        )
    }

    private fun placeKey(name: String) = "$KEY_PLACE_PREFIX${name.lowercase()}"
    private fun signKey(name: String) = "$KEY_PLACE_PREFIX${name.lowercase()}_sign"

    fun getNotes(): List<pl.victor.app.notes.Notes.Note> {
        val raw = prefs.getString(KEY_NOTES, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split(FIELD_SEPARATOR)
            val text = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            pl.victor.app.notes.Notes.Note(
                text = text,
                createdAtMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            )
        }
    }

    fun setNotes(notes: List<pl.victor.app.notes.Notes.Note>) {
        val raw = notes.joinToString("\n") { note ->
            listOf(note.text.sanitizeField(), note.createdAtMs.toString())
                .joinToString(FIELD_SEPARATOR)
        }
        prefs.edit().putString(KEY_NOTES, raw).apply()
    }

    /** Dokłada notatkę na początek listy i zwraca nową listę. */
    fun addNote(text: String): List<pl.victor.app.notes.Notes.Note> {
        val note = pl.victor.app.notes.Notes.Note(text, System.currentTimeMillis())
        val updated = listOf(note) + getNotes()
        setNotes(updated)
        return updated
    }

    /** Podmienia treść notatki o podanym indeksie i zwraca nową listę. */
    fun updateNote(index: Int, text: String): List<pl.victor.app.notes.Notes.Note> {
        val notes = getNotes().toMutableList()
        if (index !in notes.indices) return notes
        // Data zostaje ta sama. Poprawka literówki nie może przesuwać notatki
        // na "dziś" - inaczej pytanie "co zapisałem wczoraj" przestaje działać
        // po każdej edycji.
        notes[index] = notes[index].copy(text = text.trim())
        setNotes(notes)
        return notes
    }

    /** Usuwa notatkę o podanym indeksie i zwraca nową listę. */
    fun deleteNote(index: Int): List<pl.victor.app.notes.Notes.Note> {
        val notes = getNotes().toMutableList()
        if (index !in notes.indices) return notes
        notes.removeAt(index)
        setNotes(notes)
        return notes
    }

    /**
     * Jak zapisywać podyktowane notatki: dosłownie czy po uporządkowaniu przez
     * model. Domyślnie dosłownie - patrz [pl.victor.app.notes.Notes.Style].
     */
    fun getNoteStyle(): pl.victor.app.notes.Notes.Style =
        pl.victor.app.notes.Notes.Style.fromName(prefs.getString(KEY_NOTE_STYLE, null))

    fun setNoteStyle(style: pl.victor.app.notes.Notes.Style) {
        prefs.edit().putString(KEY_NOTE_STYLE, style.name).apply()
    }

    /**
     * Czy do pytań o tekst pobierać zdjęcie w pełnej rozdzielczości.
     *
     * Miniatura po BLE przychodzi w sekundę, ale liter z bliska na niej nie
     * widać. Pełny plik idzie przez Wi-Fi Direct i kosztuje kilkanaście
     * sekund, więc włącza się tylko dla pytań, które tego wymagają
     * ([pl.victor.app.ai.VisionDetail]). Domyślnie włączone - bez tego
     * "przeczytaj, co tu pisze" nie ma prawa zadziałać.
     */
    fun isFullResolutionVisionEnabled(): Boolean =
        prefs.getBoolean(KEY_FULL_RES_VISION, true)

    fun setFullResolutionVisionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FULL_RES_VISION, enabled).apply()
    }

    /**
     * Czy notatki mają iść na Dysk Google (dokument do NotebookLM).
     * Domyślnie wyłączone - to wysyłanie danych poza telefon.
     */
    fun isNotesDocSyncEnabled(): Boolean = prefs.getBoolean(KEY_NOTES_DOC_SYNC, false)

    fun setNotesDocSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTES_DOC_SYNC, enabled).apply()
    }

    /**
     * Identyfikator dokumentu na Dysku.
     *
     * Bez zapamiętania każda synchronizacja tworzyłaby NOWY plik, a w
     * NotebookLM trzeba by dodawać źródło od nowa - czyli dokładnie to, czego
     * ta funkcja ma oszczędzić.
     */
    fun getNotesDocId(): String? = prefs.getString(KEY_NOTES_DOC_ID, null)

    fun setNotesDocId(id: String?) {
        prefs.edit().putString(KEY_NOTES_DOC_ID, id).apply()
    }

    // === Fakty o użytkowniku (pamięć asystenta) ===

    /**
     * Fakty, najnowsze pierwsze. Zapis linia-po-linii, tak samo jak notatki.
     */
    fun getFacts(): List<pl.victor.app.memory.UserFacts.Fact> {
        val raw = prefs.getString(KEY_FACTS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split(FIELD_SEPARATOR)
            val text = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            pl.victor.app.memory.UserFacts.Fact(
                text = text,
                createdAtMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            )
        }
    }

    fun setFacts(facts: List<pl.victor.app.memory.UserFacts.Fact>) {
        val raw = facts.joinToString("\n") { fact ->
            listOf(fact.text.sanitizeField(), fact.createdAtMs.toString())
                .joinToString(FIELD_SEPARATOR)
        }
        prefs.edit().putString(KEY_FACTS, raw).apply()
    }

    /**
     * Dokłada fakt, ZASTĘPUJĄC poprzedni o tym samym temacie.
     *
     * Zastępowanie jest tu istotniejsze niż dokładanie: dwa sprzeczne zdania o
     * miejscu zamieszkania to gorszy stan niż brak obu, bo model wybiera z nich
     * losowo i mówi nieprawdę pewnym głosem.
     */
    fun addFact(text: String): List<pl.victor.app.memory.UserFacts.Fact> {
        val fresh = pl.victor.app.memory.UserFacts.Fact(text.trim(), System.currentTimeMillis())
        val updated = pl.victor.app.memory.UserFacts.merge(getFacts(), fresh)
        setFacts(updated)
        return updated
    }

    /** Usuwa fakty pasujące do opisu ("zapomnij o Krakowie"). */
    fun forgetFacts(what: String): List<pl.victor.app.memory.UserFacts.Fact> {
        val updated = pl.victor.app.memory.UserFacts.forget(getFacts(), what)
        setFacts(updated)
        return updated
    }

    fun updateFact(index: Int, text: String): List<pl.victor.app.memory.UserFacts.Fact> {
        val facts = getFacts().toMutableList()
        if (index !in facts.indices) return facts
        facts[index] = facts[index].copy(text = text.trim())
        setFacts(facts)
        return facts
    }

    fun deleteFact(index: Int): List<pl.victor.app.memory.UserFacts.Fact> {
        val facts = getFacts().toMutableList()
        if (index !in facts.indices) return facts
        facts.removeAt(index)
        setFacts(facts)
        return facts
    }

    /**
     * Pakiet silnika mowy wybrany przez użytkownika. Pusty = systemowy domyślny.
     *
     * Ma znaczenie, bo domyślny silnik Samsunga ma jeden polski głos i ani
     * jednego angielskiego - a bez angielskiego nie da się poprawnie
     * przeczytać angielskiego wtrętu.
     */
    fun getTtsEngine(): String = prefs.getString(KEY_TTS_ENGINE, "").orEmpty()

    fun setTtsEngine(packageName: String) {
        prefs.edit().putString(KEY_TTS_ENGINE, packageName).apply()
    }

    // === Silnik wykrywania frazy wybudzenia ===

    /**
     * "picovoice" albo "vosk".
     *
     * Domyślnie Picovoice - działa lepiej i taniej energetycznie, ale wymaga
     * klucza. Vosk nie wymaga niczego, kosztem baterii i pobrania modelu.
     */
    fun getWakeEngine(): String = prefs.getString(KEY_WAKE_ENGINE, WAKE_ENGINE_PICOVOICE)
        ?: WAKE_ENGINE_PICOVOICE

    fun setWakeEngine(engine: String) {
        prefs.edit().putString(KEY_WAKE_ENGINE, engine).apply()
    }

    /** Fraza rozpoznawana przez Voska - zapisana tak, jak model ją usłyszy. */
    fun getVoskPhrase(): String = prefs.getString(KEY_VOSK_PHRASE, DEFAULT_VOSK_PHRASE)
        ?: DEFAULT_VOSK_PHRASE

    fun setVoskPhrase(phrase: String) {
        prefs.edit().putString(KEY_VOSK_PHRASE, phrase.trim().lowercase()).apply()
    }

    /**
     * Adres modelu Voska.
     *
     * Ustawienie, a nie stała: nazwy plików modeli zmieniają się z wersjami, a
     * wtedy lepiej wkleić nowy adres niż czekać na nową wersję aplikacji.
     */
    fun getVoskModelUrl(): String =
        prefs.getString(KEY_VOSK_MODEL_URL, null)?.takeIf { it.isNotBlank() }
            ?: pl.victor.app.wakeword.VoskWakeWord.DEFAULT_MODEL_URL

    fun setVoskModelUrl(url: String) {
        prefs.edit().putString(KEY_VOSK_MODEL_URL, url.trim()).apply()
    }

    /** Usuwa z pola znaki, które rozwaliłyby zapis linia-po-linii. */
    private fun String.sanitizeField(): String =
        replace(FIELD_SEPARATOR, " ").replace("\n", " ").replace("\r", " ").trim()

    // === Proactive alerts (pogoda + kalendarz) ===

    /**
     * Czy proaktywne alerty są włączone.
     */
    fun isProactiveAlertsEnabled(): Boolean =
        prefs.getBoolean(KEY_PROACTIVE_ENABLED, true)  // domyślnie włączone

    /**
     * Czy alerty mają być WYPOWIADANE, a nie tylko pokazywane jako powiadomienie.
     *
     * Zgłoszone wprost: "alerty pogodowe wyświetlają się tylko jako
     * powiadomienia, a nie głosowo na okularach". Sens okularów polega na tym,
     * że nie trzeba sięgać po telefon - alert, który wymaga wyjęcia telefonu,
     * mija się z celem. Domyślnie włączone.
     */
    fun isAlertsSpokenEnabled(): Boolean =
        prefs.getBoolean(KEY_ALERTS_SPOKEN, true)

    fun setAlertsSpokenEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALERTS_SPOKEN, enabled).apply()
    }

    /**
     * Czy alerty wolno wypowiadać także wtedy, gdy okulary NIE są połączone.
     *
     * Domyślnie nie: nagłe mówienie z głośnika telefonu w kieszeni jest
     * zaskakujące, a przy okularach na głowie - naturalne.
     */
    fun isAlertsSpokenWithoutGlasses(): Boolean =
        prefs.getBoolean(KEY_ALERTS_SPOKEN_NO_GLASSES, false)

    fun setAlertsSpokenWithoutGlasses(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALERTS_SPOKEN_NO_GLASSES, enabled).apply()
    }

    fun setProactiveAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROACTIVE_ENABLED, enabled).apply()
    }

    /**
     * OpenWeatherMap API key (darmowy z https://openweathermap.org/api).
     */
    fun getOpenWeatherApiKey(): String =
        prefs.getString(KEY_OWM_KEY, "") ?: ""

    fun setOpenWeatherApiKey(key: String) {
        prefs.edit().putString(KEY_OWM_KEY, key).apply()
    }

    /**
     * Lokalizacja dla pogody - "Warszawa,PL" / "Kraków" / "52.23,21.01"
     */
    fun getWeatherLocation(): String =
        prefs.getString(KEY_WEATHER_LOCATION, "") ?: ""

    fun setWeatherLocation(location: String) {
        prefs.edit().putString(KEY_WEATHER_LOCATION, location).apply()
    }

    /**
     * Adres MAC ostatnio sparowanych okularów - potrzebny do automatycznego łączenia
     * ponownego, także po restarcie aplikacji.
     */
    fun getLastGlassesAddress(): String? =
        prefs.getString(KEY_LAST_GLASSES_ADDRESS, null)?.takeIf { it.isNotBlank() }

    fun setLastGlassesAddress(address: String?) {
        prefs.edit().putString(KEY_LAST_GLASSES_ADDRESS, address ?: "").apply()
    }

    /**
     * Czy okulary mają same wykrywać frazę wybudzenia.
     *
     * Domyślnie tak - to jedyna droga do komendy głosowej, która nie wymaga
     * konta Picovoice. Ustawienie musi być trwałe, bo po każdym połączeniu
     * aplikacja przesyła je do okularów; bez zapamiętania wyłączenie wracałoby
     * przy pierwszym auto-reconnect.
     */
    fun isGlassesWakeWordEnabled(): Boolean =
        prefs.getBoolean(KEY_GLASSES_WAKE_WORD, true)

    fun setGlassesWakeWordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLASSES_WAKE_WORD, enabled).apply()
    }

    /**
     * Czy zbierać pytania mikrofonem okularów przez profil rozmowy (SCO/HFP).
     *
     * Domyślnie tak - mikrofon przy uchu słyszy lepiej niż telefon w kieszeni.
     * Wyłączenie jest jednak potrzebne, i to nie teoretycznie: zestawienie SCO
     * ZAWIESZA odtwarzanie A2DP. Zestaw, który zgłasza profil rozmowy, ale go
     * porządnie nie obsługuje, daje wtedy najgorszy możliwy wynik - okulary
     * milkną (bo A2DP stoi) i nic nie słyszą (bo SCO nie niesie dźwięku).
     * Z zewnątrz wygląda to jak "asystent przestał działać".
     *
     * Aplikacja wyłącza to sama po kilku takich turach z rzędu - patrz
     * [pl.victor.app.AIOrchestrator].
     *
     * ## Czemu to jest strumień, a nie samo `getBoolean`
     * Wartość zmienia nie tylko człowiek w Ustawieniach - zmienia ją także sama
     * aplikacja, w środku tury, po trzech cichych nasłuchach. Ekran Ustawień
     * odczytany raz przy wejściu pokazywał więc stan SPRZED tego przełączenia i
     * nie miał jak się dowiedzieć, że coś się stało. Zgłoszone dokładnie tak:
     * "w aplikacji mikrofon był włączony" - przy dzienniku, który mówił coś
     * przeciwnego. Przełącznik ma pokazywać prawdę, także wtedy, gdy to nie
     * człowiek ją zmienił.
     */
    private val _glassesMicEnabledFlow = MutableStateFlow(
        prefs.getBoolean(KEY_GLASSES_MIC, true)
    )
    val glassesMicEnabledFlow: StateFlow<Boolean> = _glassesMicEnabledFlow.asStateFlow()

    fun isGlassesMicEnabled(): Boolean = _glassesMicEnabledFlow.value

    fun setGlassesMicEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLASSES_MIC, enabled).apply()
        _glassesMicEnabledFlow.value = enabled
    }

    /**
     * Cache "alert już wysłany" - żeby nie spamować.
     * Klucz: "{type}-{eventId}-{beginMs/30min}"
     */
    fun isAlertAlreadyShown(key: String): Boolean =
        prefs.getBoolean("${KEY_ALERT_SHOWN_PREFIX}$key", false)

    fun markAlertShown(key: String) {
        prefs.edit().putBoolean("${KEY_ALERT_SHOWN_PREFIX}$key", true).apply()
    }

    /**
     * Czyści cache alertów (np. po restarcie apki).
     */
    fun clearAlertCache() {
        val allKeys = prefs.all.keys.filter { it.startsWith(KEY_ALERT_SHOWN_PREFIX) }
        val editor = prefs.edit()
        allKeys.forEach { editor.remove(it) }
        editor.apply()
    }

    // === Onboarding ===

    /**
     * Czy onboarding został ukończony. False = pokaż onboarding przy starcie.
     */
    fun isOnboardingCompleted(): Boolean =
        prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, completed).apply()
    }

    /**
     * Reset onboardingu - przyda się do testów.
     */
    fun resetOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, false).apply()
    }

    // === Capture ===

    fun getCaptureCount(): Int =
        prefs.getInt(KEY_CAPTURE_COUNT, DEFAULT_CAPTURE_COUNT)

    fun setCaptureCount(count: Int) {
        prefs.edit().putInt(KEY_CAPTURE_COUNT, count).apply()
    }

    fun getCaptureIntervalMs(): Long =
        prefs.getLong(KEY_CAPTURE_INTERVAL, DEFAULT_CAPTURE_INTERVAL_MS)

    fun setCaptureIntervalMs(ms: Long) {
        prefs.edit().putLong(KEY_CAPTURE_INTERVAL, ms).apply()
    }

    // === TTS Voice ===

    fun getTtsVoiceName(): String? =
        prefs.getString(KEY_TTS_VOICE, null)

    fun setTtsVoiceName(name: String?) {
        prefs.edit().putString(KEY_TTS_VOICE, name).apply()
    }

    fun getTtsSpeechRate(): Float =
        prefs.getFloat(KEY_TTS_RATE, 1.0f)

    fun setTtsSpeechRate(rate: Float) {
        prefs.edit().putFloat(KEY_TTS_RATE, rate.coerceIn(0.5f, 2.0f)).apply()
    }

    fun getTtsPitch(): Float =
        prefs.getFloat(KEY_TTS_PITCH, 1.0f)

    fun setTtsPitch(pitch: Float) {
        prefs.edit().putFloat(KEY_TTS_PITCH, pitch.coerceIn(0.5f, 2.0f)).apply()
    }

    // === Persona ===

    /**
     * ID wybranej persony ("default", "sternik", "przyjaciel", ..., "custom").
     */
    fun getSelectedPersonaId(): String =
        prefs.getString(KEY_PERSONA_ID, "default") ?: "default"

    fun setSelectedPersonaId(id: String) {
        prefs.edit().putString(KEY_PERSONA_ID, id).apply()
    }

    /**
     * Własny system prompt (dla persony "custom").
     * Pusty = brak własnego, użyj domyślnej persony.
     */
    fun getCustomPersonaPrompt(): String =
        prefs.getString(KEY_CUSTOM_PERSONA, "") ?: ""

    fun setCustomPersonaPrompt(prompt: String) {
        prefs.edit().putString(KEY_CUSTOM_PERSONA, prompt).apply()
    }

    companion object {

        /**
         * Jedna instancja na proces.
         *
         * ## Dlaczego to ma znaczenie dla szybkości
         * Konstruktor buduje klucz w Android Keystore i otwiera
         * EncryptedSharedPreferences - operacje kryptograficzne rzędu
         * dziesiątek do setek milisekund. Repozytorium powstawało dotąd w
         * czterech miejscach, w tym w motywie aplikacji, czyli przy KAŻDYM
         * otwarciu ekranu - na wątku głównym, w trakcie komponowania. Każde
         * wejście w ustawienia, notatki czy galerię płaciło ten koszt od nowa.
         *
         * Instancja jest bezstanowa poza samymi preferencjami, więc
         * współdzielenie jej niczego nie psuje - a Context bierzemy
         * aplikacyjny, żeby nie przetrzymywać Activity.
         */
        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }

        private const val DEFAULT_PROVIDER = "gemini"
        private const val LOCAL_PROVIDER_ID = "local"
        private const val LOCAL_PROVIDER_PLACEHOLDER_KEY = "local-model-no-key-needed"
        const val DEFAULT_CAPTURE_COUNT = 5
        const val DEFAULT_CAPTURE_INTERVAL_MS = 1000L

        private const val KEY_ACTIVE_PROVIDER = "active_provider"
        private const val KEY_API_PREFIX = "api_key_"
        private const val KEY_MODEL_PREFIX = "selected_model_"
        private const val KEY_VALIDATION_PREFIX = "last_validation_"
        private const val KEY_WEB_SEARCH = "web_search_enabled"
        private const val KEY_RESPONSE_LANG = "response_lang"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_WAKE_WORD_GLASSES_MIC = "wake_word_glasses_mic"
        private const val KEY_CLOUD_TRANSCRIPTION = "cloud_transcription"
        private const val KEY_PHOTO_SOURCE = "photo_source"
        private const val KEY_PHOTO_DIVISOR = "photo_divisor"
        private const val KEY_DIAG_LOG = "diagnostic_log_enabled"
        private const val KEY_GITHUB_TOKEN = "github_diagnostics_token"

        /** Zdjęcie samym BLE - natychmiast, ale liter z bliska nie widać. */
        const val PHOTO_THUMBNAIL = "thumbnail"

        /** Pełna rozdzielczość przez Wi-Fi Direct - wolniej, ale czytelnie. */
        const val PHOTO_FULL = "full"
        private const val KEY_CONVERSATIONAL_MODE = "conversational_mode"
        private const val KEY_LONG_TERM_MEMORY = "long_term_memory"
        private const val KEY_TRANSLATION_TARGET = "translation_target"
        private const val KEY_GCAL_CONNECTED = "gcal_connected"
        private const val KEY_CAPTURE_MODE = "capture_mode"
        private const val KEY_AUTO_DEGRADE_CAPTURE = "auto_degrade_capture"
        private const val KEY_POWER_MODE = "power_mode"
        private const val KEY_AUTO_POWER = "auto_power_mode"
        private const val KEY_AUTO_PROVIDER_FALLBACK = "auto_provider_fallback"
        private const val KEY_THINKING_LIMIT = "thinking_limit"
        private const val KEY_ROUTE_ASSIST = "route_assist"
        private const val KEY_PROACTIVE_INTERVAL = "proactive_interval_min"
        private const val KEY_HISTORY_LIMIT = "history_limit"
        private const val KEY_WAKE_WORD = "wake_word"
        private const val KEY_CUSTOM_WAKE_WORD = "custom_wake_word"
        private const val KEY_PICOVOICE_KEY = "picovoice_access_key"
        private const val KEY_ACTION_MODE = "action_mode"
        private const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
        private const val KEY_OWM_KEY = "owm_api_key"
        private const val KEY_WEATHER_LOCATION = "weather_location"
        private const val KEY_LAST_GLASSES_ADDRESS = "last_glasses_address"
        private const val KEY_GLASSES_WAKE_WORD = "glasses_wake_word_enabled"
        private const val KEY_GLASSES_MIC = "glasses_mic_sco_enabled"
        private const val KEY_ALERT_SHOWN_PREFIX = "alert_shown_"
        private const val KEY_ALERTS_SPOKEN = "alerts_spoken"
        private const val KEY_CUSTOM_COMMANDS = "custom_commands"
        private const val KEY_NOTES = "notes"

        /** Przedrostek klucza zapamiętanego miejsca - patrz [savePlace]. */
        private const val KEY_PLACE_PREFIX = "place_"

        /** Szerokość, długość, czas zapisu. */
        private const val PLACE_FIELDS = 3
        private const val KEY_FACTS = "user_facts"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_WAKE_ENGINE = "wake_engine"
        private const val KEY_VOSK_PHRASE = "vosk_phrase"
        private const val KEY_VOSK_MODEL_URL = "vosk_model_url"

        const val WAKE_ENGINE_PICOVOICE = "picovoice"
        const val WAKE_ENGINE_VOSK = "vosk"

        /**
         * Domyślna fraza dla Voska.
         *
         * "wiktor", nie "victor": model jest polski i zna polską pisownię.
         * Zapisane "victor" model odczytałby jako coś innego niż to, co
         * użytkownik powie - i fraza nie działałaby nigdy.
         */
        const val DEFAULT_VOSK_PHRASE = "hej wiktor"
        private const val KEY_NOTE_STYLE = "note_style"
        private const val KEY_NOTES_DOC_SYNC = "notes_doc_sync"
        private const val KEY_NOTES_DOC_ID = "notes_doc_id"
        private const val KEY_FULL_RES_VISION = "full_res_vision"
        private const val KEY_BRIEFING_ENABLED = "briefing_enabled"
        private const val KEY_BRIEFING_HOUR = "briefing_hour"
        private const val KEY_BRIEFING_MINUTE = "briefing_minute"
        private const val KEY_BRIEFING_CALENDAR = "briefing_calendar"
        private const val KEY_BRIEFING_WEATHER = "briefing_weather"
        private const val KEY_BRIEFING_AIR = "briefing_air"
        private const val KEY_BRIEFING_MAIL = "briefing_mail"
        private const val KEY_BRIEFING_FOCUS = "briefing_focus"
        private const val KEY_BRIEFING_LENGTH = "briefing_length"

        /** Domyślna pora briefingu - przed typowym wyjściem z domu. */
        private const val DEFAULT_BRIEFING_HOUR = 7
        private const val FIELD_SEPARATOR = "|"
        private const val KEY_ALERTS_SPOKEN_NO_GLASSES = "alerts_spoken_no_glasses"
        private const val KEY_ONBOARDING_DONE = "onboarding_completed"
        private const val KEY_CAPTURE_COUNT = "capture_count"
        private const val KEY_CAPTURE_INTERVAL = "capture_interval_ms"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_HIGH_CONTRAST = "high_contrast"
        private const val KEY_LARGE_TEXT = "large_text"
        private const val KEY_PERSONA_ID = "persona_id"
        private const val KEY_CUSTOM_PERSONA = "custom_persona"
        private const val KEY_GLASSES_SIMULATION = "glasses_simulation"
        private const val KEY_KEYWORD_PATH = "wake_word_keyword_path"
        private const val KEY_MODEL_PATH = "wake_word_model_path"
    }
}
