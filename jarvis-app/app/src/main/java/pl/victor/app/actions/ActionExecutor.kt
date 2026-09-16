package pl.victor.app.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.util.Log
import pl.victor.app.R

/**
 * Wykonuje akcje przez Android Intents.
 *
 * UWAGA: Apka NIGDY nie robi nic bezpośrednio (nie wysyła SMS, nie dzwoni).
 * Zamiast tego otwiera odpowiednią apkę z przygotowanymi parametrami.
 * User musi potwierdzić ostatni krok (wciśnięcie "wyślij" w SMS, "zadzwoń" w dialer).
 *
 * Dlaczego to bezpieczniejsze:
 * - Nie potrzeba dangerous permissions (CALL_PHONE, SEND_SMS, READ_CONTACTS)
 * - User widzi co się dzieje
 * - Może anulować w ostatniej chwili
 * - Kontakty/numery są w systemie, nie w naszej apce
 */
class ActionExecutor(private val context: Context) {

    private val tag = "ActionExecutor"

    /**
     * Dziennik diagnostyczny - ten sam, który użytkownik wysyła jako plik.
     *
     * Logcat wystarcza, gdy telefon wisi na kablu. Wyniki prób z [appTask]
     * powstają w sklepie albo na przystanku, a nie przy biurku, więc muszą
     * trafić do pliku, inaczej pomiar, dla którego ta lista prób w ogóle
     * istnieje, nie dojdzie do nikogo.
     *
     * `as?`, bo ActionExecutor bywa tworzony też w podglądzie ustawień.
     */
    private val diag: pl.victor.app.diagnostics.DiagnosticLog?
        get() = (context.applicationContext as? pl.victor.app.VictorApplication)?.diag

    /**
     * Wykonuje akcję. Zwraca rezultat.
     */
    fun execute(action: Action): ActionResult {
        Log.i(tag, "Executing: ${action.type} - ${action.description}")
        return try {
            when (action) {
                is Action.SendSms -> sendSms(action)
                is Action.SendWhatsApp -> sendWhatsApp(action)
                is Action.AppTask -> appTask(action)
                is Action.MakeCall -> makeCall(action)
                is Action.SendEmail -> sendEmail(action)
                is Action.PlayMusic -> playMusic(action)
                is Action.TogglePlayPause -> togglePlayPause()
                is Action.SkipTrack -> skipTrack(action)
                is Action.Navigate -> navigate(action)
                is Action.CreateCalendarEvent -> createCalendarEvent(action)
                is Action.SetAlarm -> setAlarm(action)
                is Action.SetTimer -> setTimer(action)
                is Action.WebSearch -> webSearch(action)
                is Action.OpenUrl -> openUrl(action)
                is Action.OpenApp -> openApp(action)
                is Action.Translate -> translate(action)
                is Action.ShowOnMap -> showOnMap(action)
                is Action.ToggleWifi -> toggleWifi(action)
                is Action.ToggleBluetooth -> toggleBluetooth(action)
                is Action.ToggleFlashlight -> toggleFlashlight(action)
                // Tryby dostępności obsługuje AIOrchestrator przez AccessibilityService -
                // odfiltrowuje je zanim trafią tutaj. Gałąź istnieje, bo Kotlin
                // wymaga wyczerpania when po typie Action.
                is Action.ReadText,
                is Action.DescribeScene,
                is Action.StartNavigation,
                is Action.StopAccessibility ->
                    ActionResult.Failed("Tryb dostępności obsługiwany poza ActionExecutor")

                // Prośba AI o zdjęcie (warstwa 1 zlecająca warstwie 0) - to nie jest
                // akcja do wykonania Intentem. AIOrchestrator odfiltrowuje ją zanim
                // lista tu trafi; gałąź istnieje, bo Kotlin wymaga wyczerpania when.
                is Action.TakePhoto ->
                    ActionResult.Failed("Zdjęcie obsługiwane poza ActionExecutor")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to execute ${action.type}", e)
            ActionResult.Failed(e.message ?: "Nieznany błąd")
        }
    }

    // === Komunikacja ===

    private fun sendSms(action: Action.SendSms): ActionResult {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${action.to}")
            putExtra("sms_body", action.body)
        }
        return launchIntent(intent, "Klient SMS nie jest zainstalowany")
    }

    /**
     * Otwiera rozmowę na WhatsAppie z wpisaną wiadomością.
     *
     * Wysyłki tu nie ma i nie da się jej dopisać: WhatsApp nie udostępnia
     * żadnej drogi, którą obca aplikacja wysłałaby wiadomość w czyimś imieniu.
     * Komunikat mówi to wprost, żeby nikt nie wyszedł z domu przekonany, że
     * uprzedził o spóźnieniu.
     */
    private fun sendWhatsApp(action: Action.SendWhatsApp): ActionResult {
        val url = WhatsAppLink.forNumber(action.to, action.body)
            ?: return ActionResult.Failed(
                "To nie wygląda na numer telefonu, więc nie otwieram WhatsAppa."
            )
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(intent, "WhatsApp nie jest zainstalowany")
    }

    /**
     * Próbuje kolejnych sposobów otwarcia cudzej aplikacji z zadaniem.
     *
     * ## Czemu pętla, a nie jeden Intent
     * Bo adresów głębokich tych aplikacji nie da się sprawdzić z góry i
     * zmieniają się między wersjami. Jeden wpisany na sztywno dałby funkcję,
     * która CICHO NIE DZIAŁA. Tu idziemy od najbardziej szczegółowej próby do
     * zwykłego uruchomienia aplikacji, a mówimy to, co SIĘ UDAŁO.
     *
     * Który kandydat zadziałał, trafia do dziennika - po jednym teście w
     * terenie da się listę skrócić na podstawie pomiaru, a nie domysłu.
     */
    private fun appTask(action: Action.AppTask): ActionResult {
        val attempts = when (action.kind) {
            pl.victor.app.actions.AppTaskKind.TRANSIT_PLAN ->
                pl.victor.app.external.AppLinks.jakdojade(action.argument)
            pl.victor.app.actions.AppTaskKind.RECOGNIZE_SONG ->
                pl.victor.app.external.AppLinks.shazam()
            pl.victor.app.actions.AppTaskKind.ORDER_RIDE ->
                pl.victor.app.external.AppLinks.ride(action.argument)
            pl.victor.app.actions.AppTaskKind.ROAD_ASSIST ->
                pl.victor.app.external.AppLinks.yanosik()
        }

        for ((index, attempt) in attempts.withIndex()) {
            val intent = when {
                attempt.action != null -> Intent(attempt.action)
                attempt.uri != null -> Intent(Intent.ACTION_VIEW, Uri.parse(attempt.uri))
                attempt.packageName != null ->
                    context.packageManager.getLaunchIntentForPackage(attempt.packageName)
                else -> null
            } ?: continue
            attempt.packageName?.let { if (attempt.uri != null || attempt.action != null) intent.setPackage(it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
            if (ok) {
                diag?.event(
                    pl.victor.app.diagnostics.DiagFormat.Phase.AKCJA,
                    "Zadanie w cudzej aplikacji: udane",
                    mapOf(
                        "zadanie" to action.kind.name,
                        "proba" to "${index + 1}/${attempts.size}",
                        "aplikacja" to attempt.packageName,
                        "adres" to (attempt.uri ?: attempt.action)
                    )
                )
                Log.i(tag, "AppTask ${action.kind}: zadziałała próba ${index + 1}/${attempts.size}")
                return ActionResult.Success(attempt.describe)
            }
        }
        diag?.event(
            pl.victor.app.diagnostics.DiagFormat.Phase.AKCJA,
            "Zadanie w cudzej aplikacji: żadna próba nie weszła",
            mapOf("zadanie" to action.kind.name, "prob" to attempts.size)
        )
        return ActionResult.Failed(
            "Nie mam na tym telefonie aplikacji, którą dałoby się to zrobić."
        )
    }

    private fun makeCall(action: Action.MakeCall): ActionResult {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${action.to}")
        }
        // ACTION_DIAL nie wymaga CALL_PHONE - otwiera dialer z numerem, user wciska "zadzwoń"
        return launchIntent(intent, "Dialer nie jest dostępny")
    }

    private fun sendEmail(action: Action.SendEmail): ActionResult {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${action.to}")
            putExtra(Intent.EXTRA_SUBJECT, action.subject)
            putExtra(Intent.EXTRA_TEXT, action.body)
        }
        return launchIntent(intent, "Brak klienta email")
    }

    // === Muzyka ===

    private fun playMusic(action: Action.PlayMusic): ActionResult {
        // Próba 1: Spotify
        val spotifyUri = Uri.parse("spotify:search:${Uri.encode(action.query)}")
        val spotifyIntent = Intent(Intent.ACTION_VIEW, spotifyUri).apply {
            setPackage("com.spotify.music")
        }
        if (spotifyIntent.resolveActivity(context.packageManager) != null) {
            return launchIntent(spotifyIntent, "Nie udało się otworzyć Spotify")
        }

        // Próba 2: YouTube Music
        val ytUri = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(action.query)}")
        val ytIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
            setPackage("com.google.android.apps.youtube.music")
        }
        if (ytIntent.resolveActivity(context.packageManager) != null) {
            return launchIntent(ytIntent, "Nie udało się otworzyć YT Music")
        }

        // Próba 3: System media search
        val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.CONTENT_TYPE)
            putExtra("query", action.query)
        }
        if (mediaIntent.resolveActivity(context.packageManager) != null) {
            return launchIntent(mediaIntent, "Nie udało się wyszukać muzyki")
        }

        // Fallback: YouTube (regularny)
        val youtubeIntent = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(action.query)}"))
        return launchIntent(youtubeIntent, "Brak aplikacji muzycznej")
    }

    private fun togglePlayPause(): ActionResult {
        // MediaSession przez intent
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            ))
        }
        return launchIntent(intent, "Brak aktywnego odtwarzacza muzyki")
    }

    private fun skipTrack(action: Action.SkipTrack): ActionResult {
        val keyCode = if (action.direction == SkipDirection.NEXT)
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        else android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS

        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN, keyCode
            ))
        }
        return launchIntent(intent, "Brak aktywnego odtwarzacza")
    }

    // === Nawigacja ===

    /**
     * Uruchamia PROWADZENIE do celu - takie, przy którym mapy same mówią.
     *
     * ## Czemu nie samo `geo:`
     * Bo `geo:0,0?q=...` tylko POKAZUJE pinezkę. Dla osoby widzącej to połowa
     * roboty, dla niewidomej - dokładnie nic, tyle że wygląda na sukces:
     * asystent melduje "otwieram mapę", telefon wyświetla punkt, którego nikt
     * nie zobaczy, i zapada cisza. Dopiero `google.navigation:` włącza
     * prowadzenie krok po kroku, a wskazówki ("za pięćdziesiąt metrów skręć w
     * prawo") mówi już aplikacja map, nie my.
     *
     * ## Czemu `geo:` mimo to zostaje
     * Jako zapas i z tego samego powodu, dla którego wybrano je wcześniej:
     * `google.navigation:` rozumie Google Maps, a `geo:` KAŻDA aplikacja map.
     * Gdy Map Google nie ma, lepiej pokazać miejsce w czymkolwiek niż nie
     * zrobić nic - ale wtedy komunikat mówi wprost, że wskazówek nie będzie.
     */
    private fun navigate(action: Action.Navigate): ActionResult {
        val where = Uri.encode(action.destination)

        // KOMUNIKACJA MIEJSKA IDZIE INNĄ DROGĄ.
        //
        // `google.navigation:` zna samochód, pieszo, rower i jednoślad - ale
        // NIE komunikację miejską. Dla niej jest adres Map z
        // `travelmode=transit`, który otwiera PLAN PODRÓŻY: linie, przesiadki,
        // godziny odjazdu. To nie jest prowadzenie krok po kroku i komunikat
        // musi to powiedzieć wprost, bo ktoś, kto nie patrzy na ekran,
        // usłyszałby "prowadzę" i czekał na wskazówki, które nie przyjdą.
        if (action.byTransit) {
            val plan = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://www.google.com/maps/dir/?api=1" +
                        "&destination=$where&travelmode=transit"
                )
            )
            return launchIntent(
                plan,
                errorIfNotFound = "Brak aplikacji map - zainstaluj np. Google Maps",
                successMessage = "Pokazuję połączenia do „${action.destination}”. " +
                    "To plan podróży z liniami i przesiadkami, a nie prowadzenie " +
                    "krok po kroku."
            )
        }

        val mode = if (action.byCar) TRAVEL_CAR else TRAVEL_WALK
        val guided = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=$where&mode=$mode")
        )
        val how = if (action.byCar) "samochodem" else "pieszo"
        // Mówimy, czy asystent patrzy. Dla kogoś, kto nie widzi ekranu, to
        // jedyny sposób, żeby się dowiedzieć - a różnica jest i w pomocy, i w
        // rachunku.
        val assist = if (action.assist == RouteAssist.ON) {
            " Ostrzeganie o przeszkodach włączone."
        } else {
            ""
        }
        val started = launchIntent(
            guided,
            errorIfNotFound = "",
            successMessage = "Prowadzę $how do „${action.destination}”. " +
                "Wskazówek słuchaj z map.$assist"
        )
        if (started is ActionResult.Success) return started

        // Zapas: pinezka w dowolnej aplikacji map. Mówimy, CZEGO NIE BĘDZIE -
        // "otworzyłem mapę" brzmi jak sukces, a bez wskazówek głosowych nie jest
        // nim dla kogoś, kto na tę mapę nie patrzy.
        val pin = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$where"))
        return launchIntent(
            pin,
            errorIfNotFound = "Brak aplikacji map - zainstaluj np. Google Maps",
            successMessage = "Nie mam czym poprowadzić, więc tylko pokazałem " +
                "„${action.destination}” na mapie. Wskazówek głosowych nie będzie - " +
                "do prowadzenia potrzebne są Mapy Google."
        )
    }

    /**
     * Otwiera dowolną zainstalowaną apkę kalendarza (nie tylko Google) z gotowym
     * formularzem nowego wydarzenia - user zapisuje ostatnim krokiem sam, więc nie
     * trzeba tu żadnego OAuth. Wersja DIRECT (przez Google Calendar API) jest w
     * [DirectActionExecutor].
     */
    /**
     * Otwiera formularz nowego wydarzenia - NIE zapisuje go.
     *
     * Zapis robi dopiero użytkownik, klikając w aplikacji kalendarza. Komunikat
     * musi to mówić wprost, bo zgłoszenie brzmiało: "mówi, że dodaje coś do
     * kalendarza, a finalnie nie dodaje". Nic się nie psuło - asystent po prostu
     * meldował sukces w chwili otwarcia formularza, a formularz czekał na
     * telefonie, którego użytkownik w okularach nie widzi.
     *
     * Prawdziwy zapis idzie przez konto Google - patrz
     * [DirectActionExecutor.createCalendarEventDirect]. Tędy chodzą tylko ci,
     * którzy konta nie podłączyli.
     */
    private fun createCalendarEvent(action: Action.CreateCalendarEvent): ActionResult {
        val endMillis = action.startTimeMillis + action.durationMinutes * 60_000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, action.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, action.startTimeMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
        }
        return launchIntent(
            intent,
            "Brak aplikacji kalendarza",
            successMessage = "Otworzyłem kalendarz z wydarzeniem „${action.title}”. " +
                "Zapisz je na telefonie - albo podłącz konto Google w ustawieniach, " +
                "to będę dodawał sam."
        )
    }

    private fun showOnMap(action: Action.ShowOnMap): ActionResult {
        val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(action.query)}")
        val intent = Intent(Intent.ACTION_VIEW, geoUri)
        return launchIntent(intent, "Brak aplikacji map")
    }

    // === Narzędzia ===

    private fun setAlarm(action: Action.SetAlarm): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, action.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
            if (action.label.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return launchIntent(intent, "Brak aplikacji zegara")
    }

    private fun setTimer(action: Action.SetTimer): ActionResult {
        val totalSeconds = action.minutes * 60 + action.seconds
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return launchIntent(intent, "Brak aplikacji zegara")
    }

    private fun webSearch(action: Action.WebSearch): ActionResult {
        val intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=${Uri.encode(action.query)}"))
        return launchIntent(intent, "Brak przeglądarki")
    }

    private fun openUrl(action: Action.OpenUrl): ActionResult {
        val uri = if (action.url.startsWith("http")) Uri.parse(action.url)
                  else Uri.parse("https://${action.url}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        return launchIntent(intent, "Nie udało się otworzyć URL")
    }

    /**
     * Otwiera aplikację po nazwie pakietu albo po nazwie, jaką mówi człowiek.
     *
     * Model zna "Spotify", nie "com.spotify.music" - kazanie mu zgadywać nazwę
     * pakietu kończy się wymyśloną nazwą i komunikatem "nie jest
     * zainstalowana" przy zainstalowanej aplikacji. Zamiana nazwy na pakiet
     * należy do tej klasy, bo tylko ona wie, co faktycznie jest na telefonie.
     */
    private fun openApp(action: Action.OpenApp): ActionResult {
        val packageName = action.packageName.ifBlank { resolvePackage(action.appName) }
            ?: return ActionResult.Failed(
                "Nie znalazłem aplikacji \"${action.appName}\" na tym telefonie."
            )
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return ActionResult.Success("Otwieram ${action.appName.ifBlank { packageName }}")
        }
        return ActionResult.Failed(
            "Aplikacja ${action.appName.ifBlank { packageName }} nie jest zainstalowana"
        )
    }

    /** Szuka zainstalowanej aplikacji po nazwie widocznej dla użytkownika. */
    private fun resolvePackage(appName: String): String? {
        if (appName.isBlank()) return null
        val wanted = appName.trim().lowercase()
        // Najpierw lista własna, bo ma nazwy w formie, w jakiej ludzie je mówią,
        // i jest darmowa. Dopiero gdy nic nie pasuje, przeglądamy CAŁY pulpit -
        // inaczej "otwórz X" działałoby tylko dla kilkunastu aplikacji, które
        // ktoś kiedyś wpisał tu ręcznie.
        //
        // Kolejność ma znaczenie dla PŁYNNOŚCI, nie tylko dla trafności: akcje
        // idą po Dispatchers.Main, a odczytanie nazw stu kilkudziesięciu
        // aplikacji to setki milisekund z dysku. Typowe "otwórz Spotify" nie ma
        // za co płacić tym czasem.
        val known = getInstalledApps().filter { it.installed }
        // Trafienie CO DO ZNAKU na własnej liście kończy sprawę - nic lepszego
        // już nie będzie, a to pokrywa prawie każde "otwórz X", jakie pada.
        known.firstOrNull { it.appName.lowercase() == wanted }?.let { return it.packageName }
        // Dalej już z całym pulpitem i w JEDNYM rankingu, nie dwóch po kolei.
        // Osobne przebiegi dawałyby byle dopasowanie z własnej listy przed
        // trafieniem co do znaku z pulpitu: "otwórz uber eats" otwierałoby
        // Ubera, bo "uber eats" zawiera "uber".
        val all = known + launchableApps()
        return all.firstOrNull { it.appName.lowercase() == wanted }?.packageName
            ?: all.firstOrNull { it.appName.lowercase().contains(wanted) }?.packageName
            ?: all.firstOrNull { wanted.contains(it.appName.lowercase()) }?.packageName
    }

    /**
     * Wszystko, co ma ikonę na pulpicie, z nazwą widoczną dla użytkownika.
     *
     * Wymaga wpisu `<intent>` z MAIN/LAUNCHER w `<queries>` manifestu - bez
     * niego Android 11+ oddaje pustą listę.
     */
    private fun launchableApps(): List<AppInfo> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(query, 0).mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                AppInfo(pkg, info.loadLabel(pm).toString(), true)
            }
        }.getOrDefault(emptyList())
    }

    private fun translate(action: Action.Translate): ActionResult {
        // Android 12+ ma systemowy intent do tłumaczenia
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                putExtra(Intent.EXTRA_PROCESS_TEXT, action.text)
                type = "text/plain"
            }
            // Sprawdź czy jest jakaś apka do tłumaczenia
            val activities = context.packageManager.queryIntentActivities(intent, 0)
            val translateActivity = activities.find { info ->
                info.activityInfo.packageName.contains("translate", ignoreCase = true)
            }
            if (translateActivity != null) {
                intent.setPackage(translateActivity.activityInfo.packageName)
                intent.setClassName(
                    translateActivity.activityInfo.packageName,
                    translateActivity.activityInfo.name
                )
                context.startActivity(intent)
                return ActionResult.Success("Tłumaczę")
            }
        }
        return ActionResult.Failed("Zainstaluj Google Translate lub inną apkę do tłumaczenia")
    }

    // === System ===

    private fun toggleWifi(action: Action.ToggleWifi): ActionResult {
        // Android 10+ nie pozwala programowo włączać WiFi
        // Otwieramy ustawienia
        val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ActionResult.Success("Otwieram ustawienia WiFi")
    }

    private fun toggleBluetooth(action: Action.ToggleBluetooth): ActionResult {
        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ActionResult.Success("Otwieram ustawienia Bluetooth")
    }

    private fun toggleFlashlight(action: Action.ToggleFlashlight): ActionResult {
        // Próba: CameraManager (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
            try {
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, action.enabled)
                    return ActionResult.Success(if (action.enabled) "Latarka włączona" else "Latarka wyłączona")
                }
            } catch (e: Exception) {
                Log.w(tag, "Flashlight toggle failed", e)
            }
        }
        return ActionResult.Failed("Nie udało się sterować latarką")
    }

    // === Helpers ===

    /**
     * Uruchamia intencję i zwraca czytelny błąd, gdy nie ma jej czym obsłużyć.
     *
     * ## Dlaczego NIE ma tu resolveActivity()
     * Od Androida 11 (a aplikacja celuje w 34) obowiązuje **widoczność
     * pakietów**: `resolveActivity()` zwraca `null` dla każdej aplikacji, której
     * nie wymieniono w `<queries>` w manifeście - nawet jeśli jest zainstalowana
     * i intencję obsługuje. Sprawdzenie z góry dawało więc FAŁSZYWE odmowy:
     * "Brak aplikacji kalendarza" przy zainstalowanym kalendarzu, bo formularz
     * nowego wydarzenia (`ACTION_INSERT`) nie był w `<queries>`. To dokładnie ta
     * sama klasa usterki, co zgłoszone wcześniej "brak permission" przy budziku:
     * akcja była rozpoznana i zlecona, po czym odbijała się o deklarację w
     * manifeście.
     *
     * Ograniczenie dotyczy WYŁĄCZNIE odpytywania - `startActivity()` z intencją
     * niejawną system rozwiązuje normalnie. Odpowiedzią na "czy da się to
     * otworzyć" jest więc próba otwarcia, a nie pytanie o pozwolenie na pytanie.
     */
    /**
     * @param successMessage co powiedzieć, gdy samo "Otwarto" wprowadza w błąd -
     *   czyli wszędzie tam, gdzie otwarcie okna to dopiero POŁOWA roboty, a
     *   resztę musi zrobić użytkownik.
     */
    private fun launchIntent(
        intent: Intent,
        errorIfNotFound: String,
        successMessage: String = GENERIC_SUCCESS
    ): ActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult.Success(successMessage)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(tag, "Nie ma czym obsłużyć ${intent.action}", e)
            ActionResult.Failed(errorIfNotFound)
        }
    }

    /**
     * Lista zainstalowanych popularnych apek (do UI "Otwórz apkę").
     */
    fun getInstalledApps(): List<AppInfo> {
        val pm = context.packageManager
        val popularPackages = listOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.youtube" to "YouTube",
            "com.google.android.apps.maps" to "Google Maps",
            "com.google.android.apps.photos" to "Google Photos",
            "com.google.android.gm" to "Gmail",
            "com.whatsapp" to "WhatsApp",
            "com.facebook.katana" to "Facebook",
            "com.instagram.android" to "Instagram",
            "com.twitter.android" to "Twitter",
            "org.telegram.messenger" to "Telegram",
            "com.slack" to "Slack",
            "com.netflix.mediaclient" to "Netflix",
            "com.amazon.mShop.android.shopping" to "Amazon",
            "com.ubercab" to "Uber",
            "ee.mtakso.client" to "Bolt",
            "pl.jakdojade" to "Jakdojade",
            "com.shazam.android" to "Shazam",
            "pl.neptis.yanosik.mobi.android" to "Yanosik",
            "pl.victor.app" to "V.I.C.T.O.R. (ta apka)"
        )

        return popularPackages.mapNotNull { (pkg, name) ->
            try {
                pm.getPackageInfo(pkg, 0)
                AppInfo(pkg, name, true)
            } catch (e: PackageManager.NameNotFoundException) {
                AppInfo(pkg, name, false)
            }
        }
    }

    companion object {
        /**
         * Domyślny komunikat powodzenia dla akcji, które tylko otwierają cudze
         * okno. Publiczny, bo orkiestrator musi ODRÓŻNIĆ go od komunikatu
         * konkretnego - inaczej mówiłby "OK, otwarto" zamiast opisu akcji.
         * Jedna stała po obu stronach, żeby jej zmiana nie rozjechała ich po cichu.
         */
        const val GENERIC_SUCCESS = "Otwarto"

        /** Tryby podróży schematu `google.navigation:` - `w` pieszo, `d` samochodem. */
        const val TRAVEL_WALK = "w"
        const val TRAVEL_CAR = "d"
    }

}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val installed: Boolean
)
