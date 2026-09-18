package pl.victor.app.ble

/**
 * Protokół komunikacji z okularami HeyCyan - czyste kodowanie i dekodowanie.
 *
 * Wydzielone z [VictorManager], żeby dało się to przetestować bez Androida
 * i bez sprzętu. To jest jedyne miejsce, w którym siedzą surowe bajty -
 * reszta aplikacji operuje na typach z [NotifyEvent].
 *
 * Źródła: oficjalny przewodnik SDK producenta oraz aplikacja referencyjna
 * CyanBridge (github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK).
 *
 * ## Komendy
 * Wszystkie sterujące mają postać `0x02 0x01 <tryb>`; wyjątkiem jest
 * zapytanie o liczbę plików (`0x02 0x04`) oraz zdjęcie AI, które dokłada
 * jakość miniatury i bajt domykający.
 *
 * ## Ramki notify
 * Bajt `loadData[6]` niesie typ zdarzenia, dalsze bajty jego dane.
 */
object GlassesProtocol {

    // === Tryby pracy (drugi bajt komendy) ===

    const val WORK_PHOTO = 0x01
    const val WORK_VIDEO_START = 0x02
    const val WORK_VIDEO_STOP = 0x03
    const val WORK_TRANSFER = 0x04

    /** Argument [WORK_TRANSFER]: okulary stawiają grupę Wi-Fi Direct. */
    const val TRANSFER_MODE_P2P = 0x01

    /** Argument [WORK_TRANSFER]: okulary stawiają własny hotspot (AP). */
    const val TRANSFER_MODE_AP = 0x02

    /**
     * Podgląd na żywo z kamery okularów.
     *
     * ## Skąd to wiemy i czemu wcześniej nie działało
     * Podgląd stawia na okularach zwykły serwer RTSP - patrz [rtspUrl]. Adres
     * odgadliśmy już wcześniej (panel Live Stream Lab sprawdzał port 8554 i
     * ścieżkę `ch0`, czyli trafnie), ale strumień nigdy nie ruszał, bo
     * BRAKOWAŁO KOMENDY, która go włącza. Zgadywaliśmy ją wśród bajtów 0x07 i
     * 0x0D; prawdziwa to 0x14, z tym samym argumentem sieci co tryb transferu.
     *
     * To jest dokładnie ten sam błąd, co przy galerii: poprawny adres, brak
     * komendy włączającej.
     */
    const val WORK_LIVE_PREVIEW = 0x14

    /** Zakończenie podglądu na żywo. */
    const val WORK_LIVE_PREVIEW_STOP = 0x15

    /**
     * Włącza podgląd na żywo.
     *
     * @param mode [TRANSFER_MODE_P2P] albo [TRANSFER_MODE_AP] - ta sama para co
     *   przy trybie transferu, bo podgląd potrzebuje takiej samej sieci
     */
    fun startLivePreview(mode: Int = TRANSFER_MODE_AP): ByteArray =
        byteArrayOf(0x02, 0x01, WORK_LIVE_PREVIEW.toByte(), mode.toByte())

    /** Kończy podgląd na żywo. */
    fun stopLivePreview(): ByteArray =
        byteArrayOf(0x02, 0x01, WORK_LIVE_PREVIEW_STOP.toByte(), 0x01)

    /** Port serwera RTSP na okularach. */
    const val RTSP_PORT = 8554

    /** Ścieżka strumienia - okulary mają jeden kanał. */
    const val RTSP_PATH = "ch0"

    /** Adres strumienia podglądu na żywo. */
    fun rtspUrl(ip: String): String = "rtsp://$ip:$RTSP_PORT/$RTSP_PATH"

    /** Ile wierszy opisu mediów zachować z SDP - reszta to szum w dzienniku. */
    const val RTSP_MEDIA_LINES = 6

    /**
     * Wyciąga z odpowiedzi na DESCRIBE to, co mówi, czy jest co oglądać.
     *
     * Zwraca wiersz stanu (`RTSP/1.0 200 OK` albo kod błędu) oraz wiersze `m=`
     * i `a=rtpmap:` z SDP - czyli rodzaj strumienia i kodek. Te dwie rzeczy
     * rozstrzygają, czy przy porażce odtwarzacza winny jest brak strumienia,
     * czy format, którego odtwarzacz nie umie.
     *
     * Wiersz stanu jest `null`, gdy odpowiedź nie zaczyna się od `RTSP/`:
     * otwarty port, na którym siedzi coś innego, to NIE to samo co serwer RTSP
     * i nie wolno tego mylić.
     */
    fun parseRtspDescribe(reply: String): Pair<String?, List<String>> {
        val lines = reply.split("\r\n", "\n").map { it.trim() }
        val status = lines.firstOrNull()?.takeIf { it.startsWith("RTSP/") }?.take(60)
        val media = lines
            .filter { it.startsWith("m=") || it.startsWith("a=rtpmap:") }
            .take(RTSP_MEDIA_LINES)
        return status to media
    }
    const val WORK_OTA = 0x05
    const val WORK_AI_PHOTO = 0x06
    const val WORK_AUDIO_START = 0x08

    /**
     * Zwolnienie pamięci okularów po zakończonym imporcie.
     *
     * ## Na jakiej podstawie
     * Aplikacja producenta wysyła tę komendę DOKŁADNIE RAZ - w
     * `fileDownloadComplete`, po pobraniu kompletu plików, razem z
     * rozłączeniem Wi-Fi. Odpowiedź ignoruje (callback jest pusty).
     *
     * Trzy poszlaki mówią, że to zwalnianie pamięci:
     * - komunikat producenta przy pełnej pamięci brzmi "zaimportuj zdjęcia i
     *   filmy przez aplikację i spróbuj ponownie", czyli import JEST u nich
     *   lekarstwem na zapełnione okulary,
     * - zapytanie o liczniki ([requestMediaCount]) zwraca liczbę plików
     *   NIEZSYNCHRONIZOWANYCH, więc coś musi tę flagę ustawiać,
     * - to jedyna komenda wysyłana po imporcie i nigdzie indziej.
     *
     * ## CO ROBI NAPRAWDĘ - ZMIERZONE
     * Kasuje. Dziennik z 14 września, liczniki wokół jednego użycia:
     *
     *     PRZED  zdjęć=121  wideo=14  nagrań=0
     *     PO     zdjęć=0    wideo=13  nagrań=0
     *
     * Sto dwadzieścia jeden zdjęć zniknęło z pamięci okularów. Hipoteza była
     * trafna, a pomiar zrobił to, po co był: zamienił "prawdopodobnie" w fakt.
     *
     * Wideo przetrwały prawie w komplecie - czternaście na trzynaście. Czemu
     * akurat jedno, NIE WIEM. Nasuwa się, że komenda kasuje tylko pliki
     * pobrane wcześniej (zdjęcia idą szybko, wideo po kilkadziesiąt megabajtów
     * już nie), ale jeden pomiar tego nie dowodzi i nie należy tego zakładać.
     *
     * Dlatego komenda NADAL nie jest wysyłana automatycznie po imporcie.
     * Wiemy już, że kasuje - a to tym bardziej powód, żeby zostawić ją za
     * świadomym kliknięciem.
     */
    const val WORK_RELEASE_STORAGE = 0x09

    /**
     * Koniec sesji AI po stronie okularów.
     *
     * ## Skąd to wiemy
     * Z aplikacji producenta (Prism Pro, `GlassesAzureSpeechRecognizer`). Wysyła
     * ona `0x02 0x01 0x0B` w TRZECH miejscach i tylko w tych trzech:
     * w `exitAi()`, po zadziałaniu własnego limitu czasu (`timeoutTask`) oraz -
     * co najważniejsze - w callbacku "rozpoznano wypowiedź", czyli w chwili,
     * gdy użytkownik przestaje mówić.
     *
     * To jest odpowiedź na zgłoszenie "okulary same nie kończą nasłuchu":
     * odsubskrybowanie strumienia (`removeGptNotify`) NIC nie wysyła do
     * okularów, więc one nadają dalej. Dopiero ta komenda je zatrzymuje.
     */
    const val WORK_AI_SESSION_STOP = 0x0B

    const val WORK_AUDIO_STOP = 0x0C
    const val WORK_RESET_P2P = 0x0F

    // === Komendy eksperymentalne (Opcje programistyczne - Live Stream Lab) ===
    //
    // Znaczenie WORK_EXPERIMENTAL_07 i WORK_EXPERIMENTAL_0D jest NIEPOTWIERDZONE -
    // to jedyne dwa bajty spoza tabeli powyżej, które nie są potwierdzone jako coś
    // niebezpiecznego (w przeciwieństwie do 0x0A, który oficjalna apka potwierdza
    // jako factory reset). Wolno je wysyłać wyłącznie z gated panelu developerskiego,
    // pojedynczo, po potwierdzeniu, na sprzęcie przeznaczonym do testów.
    // WORK_RESTART_DEVICE jest za to potwierdzone (restart urządzenia) - to jedyna
    // bezpieczna komenda odzyskiwania w tym panelu.

    /** Nieznane - kandydat na aktywację trybu 8 (live streaming) wg analizy firmware. */
    const val WORK_EXPERIMENTAL_07 = 0x07

    /** Nieznane - kandydat na aktywację trybu 8 (live streaming) wg analizy firmware. */
    const val WORK_EXPERIMENTAL_0D = 0x0D

    /** Potwierdzone w oficjalnej apce jako restart urządzenia. */
    const val WORK_RESTART_DEVICE = 0x0E

    /**
     * Zakres jakości miniatury akceptowany przez okulary.
     *
     * 0..5, tak jak w aplikacji referencyjnej (od "Instant" do "Detailed").
     * Wcześniej dopuszczaliśmy 6 - wartość spoza tabeli producenta.
     */
    val THUMBNAIL_QUALITY_RANGE = 0..5

    // === Typy ramek notify (loadData[6]) ===

    const val NOTIFY_PHOTO_READY = 0x02
    const val NOTIFY_AI_BUTTON = 0x03
    const val NOTIFY_OTA_PROGRESS = 0x04
    const val NOTIFY_BATTERY = 0x05
    const val NOTIFY_GLASSES_IP = 0x08
    const val NOTIFY_P2P_ERROR = 0x09

    /**
     * Użytkownik przerwał wypowiedź (dotknięcie zauszników w trakcie mówienia).
     * Aplikacja producenta robi tu `stopRealTimeTTS` + `setUserInterruptsAudio`,
     * czyli traktuje to jako "zamilcz", a nie jako pauzę odtwarzacza.
     */
    const val NOTIFY_INTERRUPT_SPEECH = 0x0C
    const val NOTIFY_UNBIND = 0x0D
    const val NOTIFY_LOW_MEMORY = 0x0E

    // === Typy odczytane z aplikacji producenta (Prism Pro) ===
    // Tabela skoków w MainActivity$MyDeviceNotifyListener obsługuje typy 0x02..0x18.
    // Poniższe były u nas nieobsługiwane - okulary je wysyłały, a aplikacja
    // milczała. Najważniejsze są dwa ostatnie: to one uruchamiają rozmowę.

    /** Okulary przerwały rozpoznawanie obrazu (`deviceIdentificationStop`). */
    const val NOTIFY_IDENTIFICATION_STOP = 0x0A

    /** Zmiana głośności zauszniki -> telefon (`setVolumeControl`). */
    const val NOTIFY_VOLUME_CHANGED = 0x12

    /** Kąt kamery (`setGimbalCameraAngle`). */
    const val NOTIFY_CAMERA_ANGLE = 0x16

    /**
     * Okulary proszą o rozpoczęcie rozmowy z AI - wariant pierwszy.
     *
     * Producent w obu wariantach (0x17 i 0x18) robi to samo: sprawdza sieć,
     * gra dźwięk przez `aiVoicePlay`, czyści kolejkę TTS i startuje
     * rozpoznawanie mowy.
     *
     * ## NA NASZYM EGZEMPLARZU TA RAMKA NIE PRZYCHODZI - I NIC SIĘ NIE DZIEJE
     * Sprawdzone na sprzęcie 12 września: po „hej lens" okulary wysyłają
     * ramkę PRZYCISKU (0x03, numer 1), a nie 0x17 ani 0x18. Wybudzenie głosem
     * działa więc tą samą drogą co dotknięcie zausznika i nie potrzebuje tych
     * stałych.
     *
     * Zostają, bo opisuje je SDK producenta, a inny egzemplarz albo inna wersja
     * firmware'u może ich używać - obsługa kosztuje jedną gałąź `when`. Ale
     * gdy ktoś szuka usterki wybudzenia, NIE jest to miejsce, w którym ona
     * siedzi: tam patrz na obsługę przycisku.
     */
    const val NOTIFY_AI_SESSION_A = 0x17

    /** Okulary proszą o rozpoczęcie rozmowy z AI - wariant drugi. */
    const val NOTIFY_AI_SESSION_B = 0x18

    /**
     * Bajt trybu w ramce rozpoczęcia rozmowy. `1` oznacza u producenta tryb
     * tekstu na żywo (tłumaczenie), cokolwiek innego - zwykłe pytanie do AI.
     */
    const val AI_SESSION_MODE_INDEX = 7

    /** Indeks bajtu typu zdarzenia w ramce notify. */
    const val NOTIFY_TYPE_INDEX = 6

    /**
     * Indeks bajtu z długością ładunku (bajty od [NOTIFY_TYPE_INDEX] w górę).
     *
     * Widać go w ramkach ze sprzętu: `BC 73 02 00 C0 80 03 01` to długość 2
     * i ładunek `03 01`, a `BC 73 0E 00 ... ` to długość 14. Używamy go tylko
     * tam, gdzie odróżnia zdarzenie od cudzej ramki o tym samym pierwszym
     * bajcie - patrz [NOTIFY_VOLUME_CHANGED].
     */
    const val NOTIFY_LENGTH_INDEX = 2

    /** Ładunek zdarzenia niosącego jedną wartość: bajt typu i bajt wartości. */
    const val SINGLE_VALUE_PAYLOAD = 2

    /**
     * Pozycje wartości w DŁUGIEJ ramce 0x12 - ustawienia głośności.
     *
     * ## Skąd dokładnie te indeksy
     * Z aplikacji producenta, `MainActivity`, gałąź `case 18` tablicy skoków po
     * `loadData[6]`. Sklada ona napis z bajtów 8, 9, 10, 12, 13, 14, 16, 17, 18
     * i 19, i zapisuje go pod `setVolumeControl`. Pozycje 7, 11 i 15 są
     * POMIJANE - to znaczniki grup (w naszych ramkach mają wartości 1, 2, 3).
     *
     * ## Czemu dotąd była nieznana
     * Bo braliśmy ją za zdarzenie jednowartościowe i czytaliśmy bajt DŁUGOŚCI
     * jako poziom głośności - dziennik zapełniał się wpisami "Głośność: 1".
     * Zostawiliśmy ją wtedy świadomie jako nieznaną, do czasu ustalenia, czym
     * jest. Ustalone.
     *
     * ## Czego wciąż NIE wiemy
     * Co dokładnie znaczy każda z dziesięciu liczb. W dzienniku z 15 września
     * dziewięć z nich stoi w miejscu (0, 16, 10, 0, 15, 0, 0, 16, 10), a
     * zmienia się WYŁĄCZNIE ostatnia - przyjmowała 1, 2 i 3. Producent nie
     * nazywa ich w kodzie, więc zgadywanie nazw byłoby wymyślaniem. Oddajemy
     * je surowo i zapisujemy do dziennika; jeden przejazd po zausznikach w
     * terenie rozstrzygnie, co się z czym rusza.
     */
    val VOLUME_SETTINGS_INDICES = listOf(8, 9, 10, 12, 13, 14, 16, 17, 18, 19)

    /**
     * Bajt trybu w ramce "zdjęcie gotowe" (0x02).
     *
     * Producent czyta tu wartość i tylko przy `2` dokleja do zdjęcia polecenie
     * "opisz, co widzisz" (`setUserVisionText`). Inne wartości to zwykłe zdjęcie
     * do galerii. Ramka bywa krótsza niż 10 bajtów - wtedy trybu po prostu nie
     * ma i traktujemy zdjęcie jako zwykłe.
     */
    const val PHOTO_MODE_INDEX = 9

    /** Wartość [PHOTO_MODE_INDEX] oznaczająca zdjęcie do opisania przez AI. */
    const val PHOTO_MODE_AI_VISION = 2

    /** Ramka była za krótka, żeby nieść tryb - patrz [PHOTO_MODE_INDEX]. */
    const val PHOTO_MODE_ABSENT = -1

    /**
     * Numer przycisku AI w ramce 0x03.
     *
     * Okulary mają dwa przyciski. Producent reaguje wyłącznie na `1` - i tylko
     * ten numer jest potwierdzony sprzętowo. Pozostałe numery dekodujemy, ale
     * ich nie wykonujemy: trafiają do dziennika diagnostycznego, żeby dało się
     * je odczytać z prawdziwych okularów zamiast zgadywać.
     */
    const val AI_BUTTON = 1

    /** Indeks bajtu z numerem przycisku w ramce 0x03. */
    const val BUTTON_INDEX = 7

    /** Klucz nasłuchu ogólnych ramek notify w LargeDataHandler. */
    const val DEVICE_NOTIFY_KEY = 100

    // === Kody dla aiVoicePlay (sterowanie dźwiękiem po stronie okularów) ===
    // Odczytane z Prism Pro; SDK pakuje je jako [0x02, kod] pod nagłówkiem 0x48.
    // Świadomie NIE zgadujemy tu "dźwięku powitalnego" - producent go nie gra,
    // tylko ucisza to, co akurat leci, zanim zacznie nową rozmowę.

    /** Wstrzymaj to, co okulary właśnie odtwarzają. */
    const val TONE_PAUSE_PLAYBACK = 0x02

    /** Zatrzymaj odtwarzanie - producent woła to na starcie nowej rozmowy. */
    const val TONE_STOP_PLAYBACK = 0x03

    /** Komunikat błędu - producent gra go, gdy nie ma sieci. */
    const val TONE_ERROR = 0xF1

    /** `dataType == 4` w odpowiedzi na komendę oznacza liczniki plików. */
    const val DATA_TYPE_MEDIA_COUNT = 4

    // === Kodowanie komend ===

    /** Prosta komenda sterująca: `0x02 0x01 <tryb>`. */
    fun command(workType: Int): ByteArray =
        byteArrayOf(0x02, 0x01, workType.toByte())

    fun takePhoto(): ByteArray = command(WORK_PHOTO)
    fun startVideo(): ByteArray = command(WORK_VIDEO_START)
    fun stopVideo(): ByteArray = command(WORK_VIDEO_STOP)
    fun startAudio(): ByteArray = command(WORK_AUDIO_START)
    fun stopAudio(): ByteArray = command(WORK_AUDIO_STOP)

    /** Każe okularom zakończyć nasłuch - patrz [WORK_AI_SESSION_STOP]. */
    fun stopAiSession(): ByteArray = command(WORK_AI_SESSION_STOP)
    /**
     * Włącza na okularach tryb transferu plików.
     *
     * ## CZWARTY BAJT - to była przyczyna martwej galerii
     * Wysyłaliśmy `0x02 0x01 0x04`, czyli komendę BEZ argumentu, i okulary nie
     * stawiały żadnej sieci: ani grupy Wi-Fi Direct, ani własnego hotspotu.
     * W dziennikach wyglądało to na "okulary nie chcą rozgłaszać grupy", a w
     * rzeczywistości nigdy nie dowiedziały się, KTÓRY tryb mają podnieść.
     *
     * Aplikacja producenta wysyła w obu swoich ścieżkach cztery bajty:
     * `0x02 0x01 0x04 0x01` (Wi-Fi Direct) albo `0x02 0x01 0x04 0x02` (hotspot).
     * Jedyne dwa wywołania tej komendy w całej tamtej aplikacji to
     * `importAlbum()` i `importAlbumAp()` - i każde podaje swój tryb.
     *
     * @param mode [TRANSFER_MODE_P2P] albo [TRANSFER_MODE_AP]
     */
    fun enableTransferMode(mode: Int = TRANSFER_MODE_AP): ByteArray =
        byteArrayOf(0x02, 0x01, WORK_TRANSFER.toByte(), mode.toByte())

    fun resetP2p(): ByteArray = command(WORK_RESET_P2P)

    /** Zwalnia pamięć okularów po imporcie - patrz [WORK_RELEASE_STORAGE]. */
    fun releaseStorage(): ByteArray = command(WORK_RELEASE_STORAGE)

    // === Odpowiedź na komendę trybu transferu ===

    /**
     * `workTypeIng == 666` w odpowiedzi znaczy "sieć stoi, możesz się łączyć".
     *
     * Aplikacja producenta czeka na tę wartość ZANIM spróbuje dołączyć do
     * sieci. My łączyliśmy się od razu po wysłaniu komendy - i w dzienniku z
     * 13 września widać, czym to się kończy: czterdzieści sekund czekania na
     * sieć, której okulary jeszcze (albo już) nie postawiły.
     */
    const val TRANSFER_READY = 666

    /** `workTypeIng == 4`: okulary UTKNĘŁY w trybie transferu z poprzedniej próby. */
    const val TRANSFER_STUCK = 4

    /**
     * Czemu okulary odmówiły wejścia w tryb transferu - zdaniem dla użytkownika.
     *
     * ## Skąd te powody
     * Z aplikacji producenta, która czyta `workTypeIng` z odpowiedzi i pokazuje
     * dla każdej wartości inny komunikat. My tej odpowiedzi nie czytaliśmy w
     * ogóle: wysyłaliśmy komendę i czekaliśmy czterdzieści sekund w ciemno, po
     * czym mówiliśmy "okulary nie wystawiły sieci". A okulary przez cały ten
     * czas MÓWIŁY, czemu nie mogą - tylko nikt nie słuchał.
     *
     * ## KOD BŁĘDU Z ODPOWIEDZI NIE JEST TU MIARĄ NICZEGO
     * Dziennik z 14 września pokazuje trzy udane podniesienia hotspotu i przy
     * każdym z nich `błąd=1`:
     *
     *     Hotspot okularów: odpowiedź na komendę  błąd=1 stan=0
     *     Hotspot okularów: gotowe  ms=10099
     *
     * Gdyby przeszkody czytać z `errorCode`, odrzucalibyśmy działające
     * połączenie za każdym razem. Rozstrzyga wyłącznie `workTypeIng` - i to
     * jest powód, dla którego ta funkcja bierze właśnie jego.
     *
     * @return powód po polsku albo `null`, gdy nie ma przeszkody
     */
    fun transferRefusalReason(workTypeIng: Int): String? = when (workTypeIng) {
        TRANSFER_READY -> null
        1, 6 -> "Okulary robią właśnie zdjęcie. Zaczekaj, aż skończą."
        2 -> "Okulary nagrywają wideo. Zatrzymaj nagrywanie i spróbuj ponownie."
        TRANSFER_STUCK ->
            "Okulary zostały w trybie przesyłania po poprzedniej próbie. " +
                "Próbuję je z niego wyprowadzić."
        5 -> "Okulary aktualizują oprogramowanie. Zaczekaj, aż skończą."
        7 -> "Okulary są w trybie rozmowy z asystentem. Zakończ ją i spróbuj ponownie."
        8 -> "Okulary nagrywają dźwięk. Zatrzymaj nagrywanie i spróbuj ponownie."
        else -> null
    }

    // === Hotspot okularów (tryb AP) ===

    /**
     * Hasło do hotspotu okularów.
     *
     * Stałe w firmware - nie jest nigdzie negocjowane ani konfigurowane.
     */
    const val GLASSES_AP_PASSWORD = "123456789"

    /**
     * Nazwa sieci (SSID), którą okulary rozgłaszają po [TRANSFER_MODE_AP].
     *
     * Reguła jest w całości po stronie telefonu - okulary NIE podają jej
     * żadną komendą. Składa się z fragmentu nazwy BLE i adresu MAC bez
     * dwukropków. Gdy nazwa zawiera podkreślenia, bierzemy jej ostatni
     * człon (albo pierwszy, gdy członów są dokładnie dwa) i ucinamy go do
     * 20 znaków; bez podkreślenia idzie cała nazwa.
     *
     * @param bleName nazwa urządzenia BLE, taka jak w skanie
     * @param bleAddress adres MAC urządzenia BLE (z dwukropkami lub bez)
     */
    /**
     * Czy nazwa BLE wygląda na te okulary.
     *
     * ## Po co, skoro sparowanych urządzeń jest kilka
     * Bo wśród nich jest samochód, klawiatura i głośnik w kuchni. Lista
     * parowania w naszej aplikacji ma pokazywać okulary, a nie wszystko, co
     * użytkownik kiedykolwiek sparował z telefonem.
     *
     * ## Wzorzec wzięty ze sprzętu, nie z katalogu
     * Nasz egzemplarz przedstawia się jako "W610T_04C5" - człon modelu,
     * podkreślnik i cztery znaki szesnastkowe z końca adresu. Ten sam kształt
     * wykorzystuje [glassesApSsid] przy budowaniu nazwy sieci.
     *
     * Do tego garść słów, po których poznaje się okulary innych wersji. Lista
     * jest CELOWO szeroka: przeoczenie własnych okularów kosztuje niemożność
     * połączenia, a nadmiarowa pozycja - jeden wiersz na ekranie.
     */
    fun looksLikeGlassesName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (GLASSES_NAME_SHAPE.matches(trimmed)) return true
        val lower = trimmed.lowercase()
        return GLASSES_NAME_WORDS.any { lower.contains(it) }
    }

    /** Kształt ze sprzętu: "W610T_04C5" - model, podkreślnik, cztery znaki hex. */
    private val GLASSES_NAME_SHAPE = Regex("""^[A-Za-z0-9]{2,20}_[0-9A-Fa-f]{4}$""")

    private val GLASSES_NAME_WORDS =
        listOf("lens", "glass", "okular", "prism", "cyan", "w610")

    fun glassesApSsid(bleName: String, bleAddress: String): String {
        val mac = bleAddress.replace(":", "")
        if (!bleName.contains("_")) return bleName + "_" + mac
        val parts = bleName.split("_")
        val head = if (parts.size > 2) parts.last() else parts[0]
        return head.take(20) + "_" + mac
    }

    /** Eksperymentalna, niepotwierdzona komenda - patrz [WORK_EXPERIMENTAL_07]. */
    fun experimental07(): ByteArray = command(WORK_EXPERIMENTAL_07)

    /** Eksperymentalna, niepotwierdzona komenda - patrz [WORK_EXPERIMENTAL_0D]. */
    fun experimental0D(): ByteArray = command(WORK_EXPERIMENTAL_0D)

    /** Restart urządzenia - potwierdzone, bezpieczne odzyskiwanie. */
    fun restartDevice(): ByteArray = command(WORK_RESTART_DEVICE)

    /**
     * Zdjęcie AI wraz z odesłaniem miniatury.
     *
     * PIĘĆ bajtów, nie sześć: `0x02 0x01 0x06 <jakość> <jakość>`. Jakość idzie
     * dwukrotnie, i na tym komenda się kończy.
     *
     * ## Skąd pewność
     * Wcześniej dokładaliśmy tu szósty bajt `0x02` "domykający ramkę". Działająca
     * aplikacja referencyjna na tym samym SDK (CyanBridge,
     * `GeminiLiveGlassesImageCapture`) wysyła dokładnie pięć bajtów. Okulary
     * zdjęcie i tak robiły - trzy pierwsze bajty wystarczą - ale miniatura po
     * nim nie przychodziła, co zgłoszono jako "robi zdjęcie, ale nie idzie ono
     * do AI".
     *
     * @param quality 0..5; wartości spoza zakresu są przycinane
     */
    fun captureAiPhoto(quality: Int): ByteArray {
        val q = quality.coerceIn(THUMBNAIL_QUALITY_RANGE).toByte()
        return byteArrayOf(0x02, 0x01, WORK_AI_PHOTO.toByte(), q, q)
    }

    /**
     * Czy bajty wyglądają na plik JPEG.
     *
     * Miniatura przychodzi po BLE w kawałkach i nic w SDK nie sprawdza, czy
     * poskładało się z nich zdjęcie. Bez tej kontroli urwany transfer szedł do
     * modelu jako "obraz" i wracał odpowiedzią o niczym - zamiast komunikatu,
     * że zdjęcie się nie udało.
     */
    fun looksLikeJpeg(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size >= JPEG_MAGIC.size &&
            JPEG_MAGIC.indices.all { bytes[it] == JPEG_MAGIC[it] }

    /**
     * Czy plik ma też znacznik KOŃCA, czyli czy transfer doszedł do końca.
     *
     * ## Dlaczego osobno, a nie wewnątrz [looksLikeJpeg]
     * Bo zaostrzenie warunku PRZYJĘCIA byłoby zgadywaniem o firmwarze, którego
     * nie mamy jak sprawdzić - a odrzucenie dobrego zdjęcia jest gorsze niż
     * przyjęcie urwanego. Ta funkcja istnieje po to, żeby dziennik ZAPISAŁ
     * różnicę: sam nagłówek przechodzi przez [looksLikeJpeg], więc transfer
     * urwany w jednej trzeciej wyglądał dotąd jak poprawne zdjęcie i nie
     * zostawiał żadnego śladu. Dopiero mając oba pola w dzienniku da się
     * rozstrzygnąć, czy zdjęcia nie ma, czy przychodzi połowa.
     */
    fun isCompleteJpeg(bytes: ByteArray?): Boolean = endOfJpeg(bytes) >= 0

    /**
     * Gdzie kończy się obraz - indeks bajtu ZA znacznikiem `FFD9` - albo `-1`,
     * gdy znacznika nie ma, czyli transfer naprawdę się urwał.
     *
     * ## Okno 64 bajtów było za małe i to ono „psuło" zdjęcia
     * Dziennik z 12 września, cztery tury pod rząd: „nie udało się zrobić
     * zdjęcia", a wcześniej miniatury o rozmiarach 16384, 32768 i 49152 bajty -
     * co do bajta jeden, dwa i trzy razy po 16 kB. Prawdziwy JPEG nie kończy
     * się trzy razy z rzędu równo na granicy 2^14; okulary DOPYCHAJĄ ostatni
     * blok do pełnego rozmiaru. Znacznik końca leży więc gdzieś w środku tego
     * dopchnięcia, a nie w ostatnich 64 bajtach - i sprawdzenie kompletności
     * zawsze wychodziło na „nie".
     *
     * Skutek był dokładnie odwrotny do zamierzonego: obraz BYŁ kompletny,
     * tylko z ogonem zer, a my odrzucaliśmy go i szliśmy w kolejne próby -
     * stąd trzy migawki pod rząd („słychać nawet 3 zdjęcia") i pusty wynik po
     * wyczerpaniu budżetu czasu.
     *
     * Szukanie po CAŁYM buforze jest bezpieczne, bo w danych obrazu każdy bajt
     * `FF` jest dopchnięty zerem (byte stuffing) - `FFD9` nie pojawia się w
     * strumieniu przypadkiem. Idziemy od końca, więc gdy w EXIF siedzi
     * zagnieżdżona miniatura z własnym `FFD9`, trafiamy w znacznik pliku
     * zewnętrznego, a nie w jej.
     */
    fun endOfJpeg(bytes: ByteArray?): Int {
        if (!looksLikeJpeg(bytes)) return -1
        val b = bytes ?: return -1
        for (i in b.size - 2 downTo JPEG_MAGIC.size) {
            if (b[i] == 0xFF.toByte() && b[i + 1] == 0xD9.toByte()) return i + 2
        }
        return -1
    }

    /**
     * Obcina dopchnięcie za znacznikiem końca. Gdy znacznika nie ma, oddaje
     * wejście bez zmian - urwany obraz i tak jest lepszy niż żaden.
     *
     * Warto obcinać, a nie tylko rozpoznawać: przy miniaturze 16 kB samo
     * dopchnięcie potrafi być jej połową, a każdy zbędny bajt to token
     * zapłacony modelowi za ciąg zer.
     */
    fun trimToJpegEnd(bytes: ByteArray): ByteArray {
        val end = endOfJpeg(bytes)
        return if (end in 1 until bytes.size) bytes.copyOfRange(0, end) else bytes
    }

    /** Początek każdego pliku JPEG: SOI plus znacznik. */
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /**
     * Ustawia jakość miniatury, którą okulary produkują dla AI.
     *
     * ## To jest INNA RODZINA KOMEND niż cała reszta
     * Nie `0x02 0x01 <tryb>`, tylko `0x02 0x0B <jakość> <jakość>` - drugi bajt
     * to `0x0B`, nie `0x01`. Odczytane z aplikacji producenta
     * (`AIHelperActivity.showImageClarity`), gdzie użytkownik wybiera "jakość
     * obrazu dla AI" z listy, a aplikacja zapisuje wybór i wysyła go okularom
     * właśnie tak.
     *
     * ## Dlaczego to jest ważne
     * Wysyłaliśmy zamiast tego `0x02 0x01 0x06 <jakość> <jakość>` (za aplikacją
     * CyanBridge) i okulary NIE ROBIŁY ZDJĘCIA - w dzienniku ramek nie było ani
     * jednej odpowiedzi 0x02. Producent tej komendy nie używa w ogóle: ustawia
     * jakość tą komendą, a zdjęcie robi zwykłym [takePhoto].
     *
     * @param quality 0..5; wartości spoza zakresu są przycinane
     */
    fun setAiPhotoQuality(quality: Int): ByteArray {
        val q = quality.coerceIn(THUMBNAIL_QUALITY_RANGE).toByte()
        return byteArrayOf(0x02, AI_PHOTO_QUALITY.toByte(), q, q)
    }

    /** Drugi bajt komendy jakości miniatury - patrz [setAiPhotoQuality]. */
    const val AI_PHOTO_QUALITY = 0x0B

    fun isAiPhotoQuality(command: ByteArray?): Boolean =
        command != null && command.size >= 2 &&
            command[0].toInt() == 0x02 && command[1].toInt() == AI_PHOTO_QUALITY

    /** Zapytanie o liczbę niezsynchronizowanych plików. */
    fun requestMediaCount(): ByteArray = byteArrayOf(0x02, 0x04)

    // === Dekodowanie komend wychodzących (symulator, diagnostyka) ===

    /**
     * Wyciąga tryb pracy z komendy sterującej `0x02 0x01 <tryb>`.
     * @return tryb albo `null` gdy to nie jest komenda sterująca
     */
    fun workTypeOf(command: ByteArray?): Int? {
        if (command == null || command.size < 3) return null
        if (command[0].toInt() != 0x02 || command[1].toInt() != 0x01) return null
        return command[2].toInt() and 0xFF
    }

    fun isMediaCountRequest(command: ByteArray?): Boolean =
        command != null && command.size >= 2 &&
            command[0].toInt() == 0x02 && command[1].toInt() == 0x04

    /** Opis komendy po polsku - dla ekranu diagnostycznego i logów. */
    fun describeCommand(command: ByteArray?): String {
        if (command == null || command.isEmpty()) return "(pusta komenda)"
        if (isMediaCountRequest(command)) return "Zapytanie o liczbę plików"
        if (isAiPhotoQuality(command)) {
            return "Jakość zdjęcia dla AI: " +
                (if (command.size > 2) command[2].toInt() and 0xFF else 0)
        }
        return when (workTypeOf(command)) {
            WORK_PHOTO -> "Zdjęcie"
            WORK_VIDEO_START -> "Start nagrywania wideo"
            WORK_VIDEO_STOP -> "Stop nagrywania wideo"
            WORK_TRANSFER -> "Tryb transferu plików" + when {
                command.size < 4 -> " (BEZ WYBORU SIECI)"
                command[3].toInt() == TRANSFER_MODE_P2P -> " (Wi-Fi Direct)"
                command[3].toInt() == TRANSFER_MODE_AP -> " (hotspot)"
                else -> " (tryb ${command[3].toInt() and 0xFF})"
            }
            WORK_OTA -> "Aktualizacja firmware (OTA)"
            WORK_AI_PHOTO -> "Zdjęcie AI z miniaturą" +
                if (command.size > 3) " (jakość ${command[3].toInt() and 0xFF})" else ""
            WORK_AUDIO_START -> "Start nagrywania audio"
            WORK_AI_SESSION_STOP -> "Koniec nasłuchu AI"
            WORK_AUDIO_STOP -> "Stop nagrywania audio"
            WORK_RESET_P2P -> "Reset P2P"
            WORK_EXPERIMENTAL_07 -> "[EKSPERYMENT] Nieznana komenda 0x07"
            WORK_EXPERIMENTAL_0D -> "[EKSPERYMENT] Nieznana komenda 0x0D"
            WORK_RESTART_DEVICE -> "Restart urządzenia"
            else -> "Nieznana komenda: ${formatFrame(command)}"
        }
    }

    // === Budowanie ramek notify (symulator, testy) ===

    /**
     * Składa ramkę notify tak, jak przysłałyby ją okulary.
     *
     * Bajty 0..5 to nagłówek vendor SDK, którego nie parsujemy - wypełniamy je
     * markerem `SIM`, żeby na ekranie diagnostycznym od razu było widać, że
     * ramka pochodzi z symulatora, a nie ze sprzętu.
     */
    fun notifyFrame(type: Int, vararg payload: Int): ByteArray {
        val frame = ByteArray(NOTIFY_TYPE_INDEX + 1 + payload.size)
        SIMULATED_HEADER.copyInto(frame)
        frame[NOTIFY_TYPE_INDEX] = type.toByte()
        payload.forEachIndexed { i, value ->
            frame[NOTIFY_TYPE_INDEX + 1 + i] = value.toByte()
        }
        return frame
    }

    /**
     * @param aiVision czy okulary proszą o opisanie zdjęcia - patrz
     *   [PHOTO_MODE_INDEX]
     */
    fun photoReadyFrame(aiVision: Boolean = false): ByteArray =
        notifyFrame(NOTIFY_PHOTO_READY, 0, 0, if (aiVision) PHOTO_MODE_AI_VISION else 0)

    /** Ramka zdjęcia z DOWOLNYM bajtem trybu - do odtworzenia tego, co przyszło ze sprzętu. */
    fun photoReadyFrameWithMode(mode: Int): ByteArray =
        notifyFrame(NOTIFY_PHOTO_READY, 0, 0, mode)

    fun buttonPressedFrame(button: Int = AI_BUTTON): ByteArray =
        notifyFrame(NOTIFY_AI_BUTTON, button)

    fun batteryFrame(level: Int, charging: Boolean): ByteArray =
        notifyFrame(NOTIFY_BATTERY, level.coerceIn(0, 100), if (charging) 1 else 0)

    /**
     * @throws IllegalArgumentException gdy adres nie jest poprawnym IPv4 -
     *         lepiej wysypać się w teście niż wysłać ramkę, której nikt nie zdekoduje
     */
    fun glassesIpFrame(ip: String): ByteArray {
        val octets = ip.split('.')
        require(octets.size == 4) { "Oczekiwano adresu IPv4, dostano: $ip" }
        val values = octets.map { part ->
            val value = part.toIntOrNull()
            require(value != null && value in 0..255) { "Niepoprawny oktet '$part' w adresie $ip" }
            value
        }
        return notifyFrame(NOTIFY_GLASSES_IP, values[0], values[1], values[2], values[3])
    }

    fun otaProgressFrame(download: Int, soc: Int, nor: Int): ByteArray =
        notifyFrame(NOTIFY_OTA_PROGRESS, download, soc, nor)

    fun p2pErrorFrame(code: Int): ByteArray = notifyFrame(NOTIFY_P2P_ERROR, code)

    fun lowMemoryFrame(): ByteArray = notifyFrame(NOTIFY_LOW_MEMORY)

    /**
     * Ramka "okulary proszą o rozmowę" - do symulatora i testów, żeby dało się
     * przejść całą ścieżkę wybudzenia bez sprzętu na głowie.
     */
    fun aiSessionFrame(realtimeText: Boolean = false): ByteArray =
        notifyFrame(NOTIFY_AI_SESSION_A, if (realtimeText) 1 else 0)

    /** Ramka "użytkownik uciszył asystenta". */
    fun interruptSpeechFrame(): ByteArray = notifyFrame(NOTIFY_INTERRUPT_SPEECH)

    /** Ramka zmiany głośności na zausznikach. */
    fun volumeFrame(level: Int): ByteArray =
        notifyFrame(NOTIFY_VOLUME_CHANGED, level.coerceIn(0, 100))

    /** Marker `SIM` w nagłówku - patrz [notifyFrame]. */
    private val SIMULATED_HEADER = byteArrayOf(0x53, 0x49, 0x4D, 0x00, 0x00, 0x00)

    // === Dekodowanie ramek notify ===

    /**
     * Rozkłada ramkę notify na zdarzenie.
     *
     * @return zdarzenie albo [NotifyEvent.Malformed] gdy ramka jest za krótka,
     *         albo [NotifyEvent.Unknown] dla typu, którego nie obsługujemy
     */
    /** Deklarowana długość ładunku albo `-1`, gdy ramka jest za krótka. */
    fun payloadLength(loadData: ByteArray?): Int =
        if (loadData == null || loadData.size <= NOTIFY_LENGTH_INDEX) -1
        else loadData[NOTIFY_LENGTH_INDEX].toIntUnsigned()

    fun decodeNotify(loadData: ByteArray?): NotifyEvent {
        if (loadData == null || loadData.size <= NOTIFY_TYPE_INDEX) {
            return NotifyEvent.Malformed(loadData?.size ?: 0)
        }

        return when (val type = loadData[NOTIFY_TYPE_INDEX].toIntUnsigned()) {
            // Bajt 9 mówi, PO CO zdjęcie powstało. Producent przy wartości 2
            // dokleja do niego polecenie "opisz, co widzisz" - i tylko dzięki
            // temu drugi przycisk okularów robi cokolwiek poza wrzuceniem
            // zdjęcia do galerii.
            NOTIFY_PHOTO_READY -> NotifyEvent.PhotoReady(
                mode = if (loadData.size > PHOTO_MODE_INDEX) {
                    loadData[PHOTO_MODE_INDEX].toIntUnsigned()
                } else {
                    PHOTO_MODE_ABSENT
                }
            )

            // Okulary mają WIĘCEJ NIŻ JEDEN przycisk, a numer wciśniętego siedzi
            // w bajcie 7. Dotąd przepuszczaliśmy wyłącznie wartość 1 i wszystko
            // inne szło do kosza jako "nieznane" - czyli drugi przycisk nie
            // istniał z punktu widzenia aplikacji. Teraz przechodzi każdy numer,
            // a to, co z nim zrobić, decyduje wyżej [NotifyEvent.ButtonPressed.button].
            //
            // Zero zostaje odrzucone celowo: okulary wysyłają je przy ZWOLNIENIU
            // przycisku, a to nie jest osobne wciśnięcie.
            NOTIFY_AI_BUTTON -> {
                val button =
                    if (loadData.size > BUTTON_INDEX) loadData[BUTTON_INDEX].toIntUnsigned() else 0
                if (button > 0) NotifyEvent.ButtonPressed(button) else NotifyEvent.Unknown(type)
            }

            NOTIFY_BATTERY ->
                if (loadData.size > 8) {
                    NotifyEvent.Battery(
                        level = loadData[7].toIntUnsigned(),
                        charging = loadData[8].toIntUnsigned() == 1
                    )
                } else {
                    NotifyEvent.Malformed(loadData.size)
                }

            NOTIFY_GLASSES_IP ->
                if (loadData.size > 10) {
                    NotifyEvent.GlassesIp(
                        buildString {
                            append(loadData[7].toIntUnsigned()).append('.')
                            append(loadData[8].toIntUnsigned()).append('.')
                            append(loadData[9].toIntUnsigned()).append('.')
                            append(loadData[10].toIntUnsigned())
                        }
                    )
                } else {
                    NotifyEvent.Malformed(loadData.size)
                }

            NOTIFY_P2P_ERROR -> NotifyEvent.P2pError(
                code = if (loadData.size > 7) loadData[7].toIntUnsigned() else -1
            )

            NOTIFY_OTA_PROGRESS ->
                if (loadData.size > 9) {
                    NotifyEvent.OtaProgress(
                        download = loadData[7].toIntUnsigned(),
                        soc = loadData[8].toIntUnsigned(),
                        nor = loadData[9].toIntUnsigned()
                    )
                } else {
                    NotifyEvent.Malformed(loadData.size)
                }

            NOTIFY_LOW_MEMORY -> NotifyEvent.LowMemory
            NOTIFY_INTERRUPT_SPEECH -> NotifyEvent.SpeechInterrupted
            NOTIFY_UNBIND -> NotifyEvent.Unbound

            NOTIFY_IDENTIFICATION_STOP -> NotifyEvent.IdentificationStopped

            // Długość ładunku, nie sam bajt typu - i to jest tu sedno.
            //
            // Ze sprzętu przychodzi co sekundę czternastobajtowa ramka, której
            // pierwszy bajt to też 0x12. Braliśmy ją za zmianę głośności i
            // dziennik diagnostyczny zapełniał się setkami wpisów "Głośność: 1"
            // (czytaliśmy w rzeczywistości bajt DŁUGOŚCI, nie poziom). Prawdziwe
            // zdarzenie jednowartościowe ma ładunek długości 2; dłuższe ramki
            // zostają nieznane, dopóki nie wiemy, czym są.
            NOTIFY_VOLUME_CHANGED ->
                if (payloadLength(loadData) == SINGLE_VALUE_PAYLOAD && loadData.size > 7) {
                    NotifyEvent.VolumeChanged(level = loadData[7].toIntUnsigned())
                } else if (loadData.size > VOLUME_SETTINGS_INDICES.last()) {
                    NotifyEvent.VolumeSettings(
                        values = VOLUME_SETTINGS_INDICES.map { loadData[it].toIntUnsigned() }
                    )
                } else {
                    NotifyEvent.Unknown(type)
                }

            NOTIFY_CAMERA_ANGLE -> NotifyEvent.CameraAngle(
                angle = if (loadData.size > 7) loadData[7].toIntUnsigned() else -1
            )

            NOTIFY_AI_SESSION_A, NOTIFY_AI_SESSION_B -> NotifyEvent.AiSessionRequested(
                realtimeText = loadData.size > AI_SESSION_MODE_INDEX &&
                    loadData[AI_SESSION_MODE_INDEX].toIntUnsigned() == 1
            )

            else -> NotifyEvent.Unknown(type)
        }
    }

    /** Bajty w Kotlinie są ze znakiem, a protokół operuje na 0..255. */
    private fun Byte.toIntUnsigned(): Int = this.toInt() and 0xFF

    /** Czytelny podgląd ramki - do logów i ekranu diagnostycznego. */
    fun formatFrame(loadData: ByteArray?): String {
        if (loadData == null || loadData.isEmpty()) return "(pusta ramka)"
        return loadData.joinToString(" ") { "%02X".format(it) }
    }
}

/**
 * Zdarzenie odebrane z okularów.
 *
 * `Unknown` i `Malformed` są celowo osobne: pierwsze oznacza ramkę poprawną,
 * ale nieobsługiwaną (nowy firmware), drugie ramkę uszkodzoną lub za krótką.
 */
sealed class NotifyEvent {
    /**
     * Okulary zrobiły zdjęcie i miniatura jest gotowa do pobrania.
     *
     * Trzymamy SUROWY bajt trybu, a nie samo "tak/nie", bo tylko jego wartość
     * da się porównać z tym, co naprawdę przysyła sprzęt. Ekran diagnostyczny
     * pokazuje go wprost - inaczej nieznany tryb wyglądałby jak zwykłe zdjęcie
     * i nie dałoby się go odróżnić od braku funkcji.
     *
     * @param mode bajt 9 ramki albo [GlassesProtocol.PHOTO_MODE_ABSENT], gdy
     *   ramka była za krótka
     */
    data class PhotoReady(
        val mode: Int = GlassesProtocol.PHOTO_MODE_ABSENT
    ) : NotifyEvent() {
        /** Czy okulary proszą o opisanie tego zdjęcia. */
        val aiVision: Boolean get() = mode == GlassesProtocol.PHOTO_MODE_AI_VISION
    }

    /**
     * Wciśnięto przycisk na okularach.
     *
     * @param button numer przycisku z ramki. `1` to przycisk AI, sprawdzony na
     *   sprzęcie. Wyższe numery to pozostałe przyciski - ich znaczenia nie
     *   potwierdziliśmy, więc trafiają do dziennika zamiast uruchamiać akcję.
     */
    data class ButtonPressed(val button: Int = GlassesProtocol.AI_BUTTON) : NotifyEvent()

    data class Battery(val level: Int, val charging: Boolean) : NotifyEvent()

    /** Adres okularów w grupie Wi-Fi Direct. */
    data class GlassesIp(val ip: String) : NotifyEvent()

    /** Błąd Wi-Fi Direct; kod 255 bywa zgłaszany rutynowo. */
    data class P2pError(val code: Int) : NotifyEvent()

    data class OtaProgress(val download: Int, val soc: Int, val nor: Int) : NotifyEvent()

    object LowMemory : NotifyEvent()

    /** Użytkownik uciszył V.I.C.T.O.R.-a dotknięciem zauszników. */
    object SpeechInterrupted : NotifyEvent()

    object Unbound : NotifyEvent()

    /** Okulary przerwały rozpoznawanie obrazu. */
    object IdentificationStopped : NotifyEvent()

    /** Zmieniono głośność na zausznikach. */
    data class VolumeChanged(val level: Int) : NotifyEvent()

    /**
     * Długa ramka 0x12 - ustawienia głośności w postaci dziesięciu liczb.
     *
     * Surowo, bez nazywania pól: patrz [GlassesProtocol.VOLUME_SETTINGS_INDICES].
     */
    data class VolumeSettings(val values: List<Int>) : NotifyEvent()

    /** Zgłoszony kąt kamery. */
    data class CameraAngle(val angle: Int) : NotifyEvent()

    /**
     * Okulary proszą o rozmowę: użytkownik powiedział słowo wybudzenia albo
     * przytrzymał zausznik.
     *
     * @param realtimeText tryb tekstu na żywo (tłumaczenie) zamiast pytania do AI
     */
    data class AiSessionRequested(val realtimeText: Boolean) : NotifyEvent()

    /** Ramka poprawna, ale typ nieobsługiwany. */
    data class Unknown(val type: Int) : NotifyEvent()

    /** Ramka za krótka albo pusta. */
    data class Malformed(val size: Int) : NotifyEvent()
}
