package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy rozpoznawania komend głosowych.
 *
 * Wzorce w detektorze były zapisane jako zwykłe napisy przekazywane do
 * `String.matches()`, co w Kotlinie w ogóle się nie kompiluje - musiały
 * zostać opakowane w `Regex`. Te testy pilnują, żeby po tej zmianie
 * dopasowania nadal działały, łącznie z polskimi znakami.
 */
class SmartActionDetectorTest {

    private val detector = SmartActionDetector()

    private inline fun <reified T : Action> assertDetects(text: String) {
        val actions = detector.detect(text)
        assertTrue(
            "Nie rozpoznano ${T::class.simpleName} w \"$text\", wykryto: " +
                actions.map { it::class.simpleName },
            actions.any { it is T }
        )
    }

    // === Sterowanie muzyką ===

    @Test
    fun `rozpoznaje pauze`() {
        assertDetects<Action.TogglePlayPause>("pauza")
        assertDetects<Action.TogglePlayPause>("zatrzymaj muzykę")
        assertDetects<Action.TogglePlayPause>("wstrzymaj")
    }

    @Test
    fun `rozpoznaje wznowienie`() {
        assertDetects<Action.TogglePlayPause>("wznów")
        assertDetects<Action.TogglePlayPause>("kontynuuj")
    }

    @Test
    fun `wznow dziala tez bez polskiego ogonka`() {
        // Transkrypcja mowy gubi diakrytyki - wzorzec musi łapać oba warianty.
        assertDetects<Action.TogglePlayPause>("wznow")
    }

    @Test
    fun `rozpoznaje nastepny utwor`() {
        val actions = detector.detect("następna")
        val skip = actions.filterIsInstance<Action.SkipTrack>().firstOrNull()
        assertTrue("Nie wykryto SkipTrack", skip != null)
        assertEquals(SkipDirection.NEXT, skip!!.direction)
    }

    @Test
    fun `nastepna dziala bez ogonkow`() {
        val skip = detector.detect("nastepna").filterIsInstance<Action.SkipTrack>().firstOrNull()
        assertEquals(SkipDirection.NEXT, skip?.direction)
    }

    @Test
    fun `rozpoznaje poprzedni utwor`() {
        val skip = detector.detect("poprzednia").filterIsInstance<Action.SkipTrack>().firstOrNull()
        assertEquals(SkipDirection.PREVIOUS, skip?.direction)
    }

    @Test
    fun `rozpoznaje angielskie warianty`() {
        assertDetects<Action.TogglePlayPause>("stop")
        val skip = detector.detect("skip").filterIsInstance<Action.SkipTrack>().firstOrNull()
        assertEquals(SkipDirection.NEXT, skip?.direction)
    }

    @Test
    fun `AI moze zlecic latarke i sterowanie muzyka`() {
        val (_, light) = detector.detectAiMarkedActions(
            "Zapalam.\n[[ACTION: type=toggle_flashlight enabled=true]]"
        )
        assertEquals(true, light.filterIsInstance<Action.ToggleFlashlight>().firstOrNull()?.enabled)

        val (_, off) = detector.detectAiMarkedActions(
            "Gaszę.\n[[ACTION: type=toggle_flashlight enabled=wyłącz]]"
        )
        assertEquals(false, off.filterIsInstance<Action.ToggleFlashlight>().firstOrNull()?.enabled)

        val (_, skip) = detector.detectAiMarkedActions(
            "Przewijam.\n[[ACTION: type=skip_track direction=prev]]"
        )
        assertEquals(
            SkipDirection.PREVIOUS,
            skip.filterIsInstance<Action.SkipTrack>().firstOrNull()?.direction
        )

        val (_, play) = detector.detectAiMarkedActions("[[ACTION: type=toggle_play]]")
        assertTrue(play.any { it is Action.TogglePlayPause })
    }

    @Test
    fun `brak wartosci enabled znaczy wlacz`() {
        val (_, actions) = detector.detectAiMarkedActions("[[ACTION: type=toggle_flashlight]]")
        assertEquals(true, actions.filterIsInstance<Action.ToggleFlashlight>().firstOrNull()?.enabled)
    }

    // === Warstwa 0: odruch (detectCritical) ===

    @Test
    fun `warstwa 0 lapie zdjecie na komende`() {
        assertTrue(detector.detectCritical("Zrób zdjęcie").any { it is Action.TakePhoto })
        assertTrue(detector.detectCritical("pstryknij fotkę").any { it is Action.TakePhoto })
    }

    @Test
    fun `warstwa 0 lapie latarke w obie strony`() {
        val on = detector.detectCritical("włącz latarkę").filterIsInstance<Action.ToggleFlashlight>()
        assertEquals(true, on.firstOrNull()?.enabled)
        val off = detector.detectCritical("zgaś latarkę").filterIsInstance<Action.ToggleFlashlight>()
        assertEquals(false, off.firstOrNull()?.enabled)
    }

    @Test
    fun `warstwa 0 nie lapie zdania z komenda w srodku`() {
        // Cały sens ścisłego dopasowania: to jest rozmowa, nie polecenie.
        assertTrue(detector.detectCritical("zrób zdjęcie jak będziemy na miejscu").isEmpty())
        assertTrue(detector.detectCritical("czy mam włączyć latarkę?").isEmpty())
    }

    @Test
    fun `warstwa 0 przepuszcza zwykle pytania do AI`() {
        assertTrue(detector.detectCritical("jaka jest stolica Francji").isEmpty())
        assertTrue(detector.detectCritical("daj znać Ani, że się spóźnię").isEmpty())
        assertTrue(detector.detectCritical("").isEmpty())
    }

    @Test
    fun `warstwa 0 nie steruje muzyka na slowo stop`() {
        // "stop" ucisza syntezator (AIOrchestrator.handleMetaCommand), a nie
        // przełącza odtwarzacz - przy zapauzowanej muzyce by ją uruchomiło.
        assertTrue(detector.detectCritical("stop").isEmpty())
        assertTrue(detector.detectCritical("pauza").any { it is Action.TogglePlayPause })
    }

    // === Znacznik AI: take_photo ===

    /**
     * Regresja na najgorszy z możliwych objawów: użytkownik SŁYSZY znacznik
     * zamiast dostać wykonaną akcję. Stary regex miał jedną powtarzalną grupę
     * na parametry, a taka w Javie pamięta tylko OSTATNIE dopasowanie - więc
     * przy dwóch parametrach `to` przepadało i SMS nigdy nie powstawał.
     */
    @Test
    fun `znacznik SMS zachowuje oba parametry`() {
        val (text, actions) = detector.detectAiMarkedActions(
            "Wysyłam SMS do Ani.\n[[ACTION: type=send_sms to=\"Ania\" body=\"Będę później\"]]"
        )
        assertEquals("Wysyłam SMS do Ani.", text)
        val sms = actions.filterIsInstance<Action.SendSms>().firstOrNull()
        assertEquals("Ania", sms?.to)
        assertEquals("Będę później", sms?.body)
    }

    @Test
    fun `znacznik z przecinkami tez dziala`() {
        val (_, actions) = detector.detectAiMarkedActions(
            "Wysyłam.\n[[ACTION: type=send_sms, to=\"Ania\", body=\"test\"]]"
        )
        assertEquals("Ania", actions.filterIsInstance<Action.SendSms>().firstOrNull()?.to)
    }

    @Test
    fun `znacznik w pojedynczych nawiasach tez dziala`() {
        val (text, actions) = detector.detectAiMarkedActions(
            "Szukam.\n[ACTION: type=web_search query=\"pogoda Kraków\"]"
        )
        assertEquals("Szukam.", text)
        assertEquals("pogoda Kraków", actions.filterIsInstance<Action.WebSearch>().firstOrNull()?.query)
    }

    @Test
    fun `nazwa akcji w camelCase i wielkimi literami`() {
        val (_, camel) = detector.detectAiMarkedActions("[[ACTION: type=sendSms to=\"Jan\" body=\"x\"]]")
        assertTrue(camel.any { it is Action.SendSms })
        val (_, upper) = detector.detectAiMarkedActions("[[action: TYPE=WEB_SEARCH QUERY=\"kot\"]]")
        assertTrue(upper.any { it is Action.WebSearch })
    }

    @Test
    fun `wartosc bez cudzyslowow moze miec spacje`() {
        val (_, actions) = detector.detectAiMarkedActions(
            "Nawiguję.\n[[ACTION: type=navigate destination=Plac Zamkowy Warszawa]]"
        )
        assertEquals(
            "Plac Zamkowy Warszawa",
            actions.filterIsInstance<Action.Navigate>().firstOrNull()?.destination
        )
    }

    @Test
    fun `nieznany znacznik nie jest czytany na glos`() {
        val (text, actions) = detector.detectAiMarkedActions(
            "Nie wiem co to.\n[[ACTION: type=cos_czego_nie_znamy x=1]]"
        )
        assertEquals("Nie wiem co to.", text)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `zwykla odpowiedz przechodzi bez zmian`() {
        val (text, actions) = detector.detectAiMarkedActions("To jest zwykła odpowiedź.")
        assertEquals("To jest zwykła odpowiedź.", text)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `znacznik alarmu z trzema parametrami`() {
        val (_, actions) = detector.detectAiMarkedActions(
            "Ustawiam.\n[[ACTION: type=set_alarm hour=7 minute=30 label=\"pobudka\"]]"
        )
        val alarm = actions.filterIsInstance<Action.SetAlarm>().firstOrNull()
        assertEquals(7, alarm?.hour)
        assertEquals(30, alarm?.minute)
        assertEquals("pobudka", alarm?.label)
    }

    @Test
    fun `znacznik maila zachowuje wszystkie trzy pola`() {
        val (_, actions) = detector.detectAiMarkedActions(
            "Mail.\n[[ACTION: type=send_email to=\"a@b.pl\" subject=\"Temat\" body=\"Treść wiadomości\"]]"
        )
        val mail = actions.filterIsInstance<Action.SendEmail>().firstOrNull()
        assertEquals("a@b.pl", mail?.to)
        assertEquals("Temat", mail?.subject)
        assertEquals("Treść wiadomości", mail?.body)
    }

    @Test
    fun `parsuje znacznik take_photo i wycina go z odpowiedzi`() {
        val (text, actions) = detector.detectAiMarkedActions(
            "Chwila, spojrzę.\n[[ACTION: type=take_photo]]"
        )
        assertEquals("Chwila, spojrzę.", text.trim())
        assertTrue(actions.any { it is Action.TakePhoto })
    }

    // === Brak fałszywych trafień ===

    @Test
    fun `zwykle pytanie nie jest komenda`() {
        // "co widzisz przede mną" celowo NIE jest tu użyte - to prawidłowa
        // komenda opisu sceny w trybie dostępności.
        val actions = detector.detect("jaka jest stolica Francji")
        assertTrue(
            "Zwykłe pytanie zostało wzięte za komendę: ${actions.map { it::class.simpleName }}",
            actions.isEmpty()
        )
    }

    @Test
    fun `pytanie o otoczenie jest komenda opisu sceny`() {
        assertDetects<Action.DescribeScene>("co widzisz przede mną")
    }

    @Test
    fun `pusty tekst nie generuje akcji`() {
        assertTrue(detector.detect("").isEmpty())
        assertTrue(detector.detect("   ").isEmpty())
    }

    @Test
    fun `wielkosc liter nie ma znaczenia`() {
        assertDetects<Action.TogglePlayPause>("PAUZA")
        assertDetects<Action.TogglePlayPause>("Pauza")
    }

    @Test
    fun `komenda w srodku zdania jest rozpoznawana`() {
        // Wzorce mają .* po obu stronach, więc muszą łapać także w środku.
        assertDetects<Action.TogglePlayPause>("słuchaj, zatrzymaj muzykę na chwilę")
    }

    // === Akcje, ktore byly zaimplementowane, ale nieosiagalne ===

    @Test
    fun `pokaz na mapie jest rozpoznawane`() {
        assertDetects<Action.ShowOnMap>("pokaż na mapie Rynek Główny")
        assertDetects<Action.ShowOnMap>("gdzie jest najbliższa apteka")
        assertDetects<Action.ShowOnMap>("znajdź na mapie dworzec")
    }

    @Test
    fun `pytanie o otoczenie nie otwiera map`() {
        // To jest ważne dla trybu dostępności: niewidomy użytkownik pytający
        // "gdzie jest wyjście?" pyta o to, co przed nim. Przekierowanie go
        // wtedy do Google Maps byłoby wprost szkodliwe.
        for (question in listOf(
            "gdzie jest wyjście",
            "gdzie jest klamka",
            "gdzie są schody",
            "gdzie jest przycisk"
        )) {
            val actions = detector.detect(question)
            assertTrue(
                "\"$question\" nie może otwierać map, wykryto: $actions",
                actions.none { it is Action.ShowOnMap }
            )
        }
    }

    @Test
    fun `pokaz na mapie niesie nazwe miejsca`() {
        val action = detector.detect("gdzie jest najbliższa apteka")
            .filterIsInstance<Action.ShowOnMap>().first()
        assertTrue(
            "zapytanie ma zawierać nazwę miejsca, było: \"${action.query}\"",
            action.query.contains("apteka")
        )
    }

    @Test
    fun `nawigacja ma pierwszenstwo przed pokazaniem na mapie`() {
        // "nawiguj do X" to prośba o prowadzenie, nie o podgląd - obie naraz
        // otworzyłyby dwie aplikacje.
        val actions = detector.detect("nawiguj do Rynku Głównego")
        assertTrue(actions.any { it is Action.Navigate })
        assertTrue(
            "nie powinno być jednocześnie ShowOnMap",
            actions.none { it is Action.ShowOnMap }
        )
    }

    @Test
    fun `prowadz do miejsca NIE wlacza trybu ostrzegania`() {
        // To jest ten przypadek, który robił dwie złe rzeczy naraz: otwierał
        // mapę I uruchamiał w tle pętlę pytającą model o obraz kilkadziesiąt
        // razy na minutę, o co nikt nie prosił.
        val actions = detector.detect("prowadź do najbliższej biedronki")
        assertTrue("miała powstać trasa", actions.any { it is Action.Navigate })
        assertTrue(
            "tryb ostrzegania o przeszkodach nie miał się włączyć, było: " +
                actions.map { it.type },
            actions.none { it is Action.StartNavigation }
        )
    }

    @Test
    fun `samo prowadz dalej wlacza tryb ostrzegania`() {
        // Druga strona tej samej reguły: bez celu "prowadź" znaczy to co zawsze.
        val actions = detector.detect("prowadź")
        assertTrue(actions.any { it is Action.StartNavigation })
        assertTrue(actions.none { it is Action.Navigate })
    }

    @Test
    fun `najblizsza znika z celu trasy`() {
        // Mapy i tak szukają od bieżącego położenia, a słowo zostawione w
        // zapytaniu bywa dopasowywane do nazwy miejsca.
        val dest = detector.detect("prowadź do najbliższej biedronki")
            .filterIsInstance<Action.Navigate>().first().destination
        assertEquals("biedronki", dest)
    }

    @Test
    fun `najblizsza bez ogonkow tez znika`() {
        // Rozpoznawanie mowy zwraca jedno i drugie.
        val dest = detector.detect("nawiguj do najblizszej apteki")
            .filterIsInstance<Action.Navigate>().first().destination
        assertEquals("apteki", dest)
    }

    @Test
    fun `jedz do znaczy samochodem, prowadz do znaczy pieszo`() {
        // Trasa samochodowa poprowadzona pieszemu każe iść obwodnicą, a piesza
        // kierowcy - przez park.
        val byCar = detector.detect("jedź do Krakowa")
            .filterIsInstance<Action.Navigate>().first()
        assertTrue("jedź = samochodem", byCar.byCar)

        val onFoot = detector.detect("prowadź do apteki")
            .filterIsInstance<Action.Navigate>().first()
        assertFalse("prowadź = pieszo", onFoot.byCar)
    }

    // --- z asystentem czy bez ---
    //
    // Wskazówki trasy liczą i mówią mapy, więc nic nie kosztują. Ostrzeganie o
    // przeszkodach to nasza pętla pytająca model o obraz - circa 1600 tokenów
    // za zapytanie przez całą drogę. Dlatego to musi być wybór, a nie domyślne
    // zachowanie, którego nikt nie widzi.

    @Test
    fun `bez wyraznej prosby decyduje ustawienie`() {
        val nav = detector.detect("prowadź do apteki")
            .filterIsInstance<Action.Navigate>().first()
        assertEquals(RouteAssist.FROM_SETTINGS, nav.assist)
    }

    @Test
    fun `z asystentem wlacza ostrzeganie`() {
        val nav = detector.detect("prowadź do apteki z asystentem")
            .filterIsInstance<Action.Navigate>().first()
        assertEquals(RouteAssist.ON, nav.assist)
    }

    @Test
    fun `bez asystenta wylacza ostrzeganie`() {
        val nav = detector.detect("prowadź do apteki bez asystenta")
            .filterIsInstance<Action.Navigate>().first()
        assertEquals(RouteAssist.OFF, nav.assist)
    }

    @Test
    fun `prosba o asystenta NIE zostaje w nazwie celu`() {
        // Bez tego mapy szukałyby miejsca o nazwie "apteki z asystentem".
        listOf(
            "prowadź do apteki z asystentem",
            "prowadź do apteki bez asystenta",
            "prowadź bez asystenta do apteki"
        ).forEach { command ->
            val dest = detector.detect(command)
                .filterIsInstance<Action.Navigate>().first().destination
            assertEquals("komenda: \"$command\"", "apteki", dest)
        }
    }

    @Test
    fun `bez wygrywa z z gdy padly oba`() {
        // Tańsza pomyłka: nieoczekiwanie nieczynny asystent włącza się jednym
        // zdaniem, nieoczekiwanie czynny pyta model przez całą drogę.
        val nav = detector.detect("prowadź do apteki z asystentem bez asystenta")
            .filterIsInstance<Action.Navigate>().first()
        assertEquals(RouteAssist.OFF, nav.assist)
    }

    @Test
    fun `inne slowa tez znacza asystenta`() {
        assertEquals(
            RouteAssist.ON,
            detector.detect("prowadź do apteki z ostrzeżeniami")
                .filterIsInstance<Action.Navigate>().first().assist
        )
        assertEquals(
            RouteAssist.OFF,
            detector.detect("nawiguj do apteki bez opisu")
                .filterIsInstance<Action.Navigate>().first().assist
        )
    }

    @Test
    fun `otworz strone jest rozpoznawane`() {
        assertDetects<Action.OpenUrl>("otwórz stronę wikipedia.pl")
        assertDetects<Action.OpenUrl>("wejdź na https://example.com")
    }

    @Test
    fun `adres bez protokolu dostaje https`() {
        val action = detector.detect("otwórz stronę wikipedia.pl")
            .filterIsInstance<Action.OpenUrl>().first()
        assertTrue(
            "adres ma mieć protokół, było: ${action.url}",
            action.url.startsWith("https://")
        )
    }

    @Test
    fun `sam adres w wypowiedzi otwiera strone`() {
        assertDetects<Action.OpenUrl>("https://example.com")
    }

    @Test
    fun `zwykle zdanie z kropka nie jest adresem`() {
        // "Idę do domu." nie może zostać uznane za adres.
        val actions = detector.detect("idę do domu")
        assertTrue(actions.none { it is Action.OpenUrl })
    }


    // === Polaczenia i SMS: numer telefonu vs nazwa kontaktu ===
    //
    // Wykryta wartosc "to" idzie pozniej do AIOrchestrator.resolveContactIfNeeded,
    // ktore rozstrzyga miedzy numerem a nazwa kontaktu z ksiazki adresowej. Zanim
    // to sie stanie, sam detektor musi wyciagnac CALY numer, a nie jego fragment.

    @Test
    fun `zadzwon do nazwy wyciaga cale slowo jako cel`() {
        val action = detector.detect("zadzwoń do mamy")
            .filterIsInstance<Action.MakeCall>().first()
        assertEquals("mamy", action.to)
    }

    @Test
    fun `zadzwon pod numer z odstepami wyciaga caly numer`() {
        // Wczesniej \S+ lapal tylko "123" z "123 456 789" - reszta numeru
        // ginela, a polaczenie szlo pod bledny, obciety numer.
        val action = detector.detect("zadzwoń pod 123 456 789")
            .filterIsInstance<Action.MakeCall>().first()
        assertTrue(
            "numer ma zawierac wszystkie cyfry, bylo: \"${action.to}\"",
            action.to.filter { it.isDigit() } == "123456789"
        )
    }

    @Test
    fun `zadzwon na numer z myslnikami dziala`() {
        val action = detector.detect("zadzwoń na 500-100-200")
            .filterIsInstance<Action.MakeCall>().first()
        assertTrue(action.to.filter { it.isDigit() } == "500100200")
    }

    @Test
    fun `wyslij sms do nazwy wyciaga nazwe i tresc osobno`() {
        val action = detector.detect("wyślij sms do Ani: cześć jak się masz")
            .filterIsInstance<Action.SendSms>().first()
        assertEquals("ani", action.to)
        assertTrue(action.body.contains("cześć"))
    }


    // === Alarm: pory dnia ===
    //
    // "poludnie" i "polnoc" maja wspolny prefiks "pol" - dopasowanie przez
    // startsWith() myli jedno z drugim (bylo tak wczesniej). Te testy pilnuja
    // dokladnego rozroznienia.

    @Test
    fun `alarm rano nie przesuwa godziny`() {
        val action = detector.detect("ustaw alarm na 7 rano")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(7, action.hour)
    }

    @Test
    fun `alarm wieczorem przesuwa godzine o 12`() {
        val action = detector.detect("ustaw alarm na 9 wieczór")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(21, action.hour)
    }

    @Test
    fun `alarm w poludnie przesuwa godzine o 12 gdy ponizej 12`() {
        val action = detector.detect("ustaw alarm na 1 południe")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(13, action.hour)
    }

    @Test
    fun `alarm o polnocy to godzina zero`() {
        // To byl martwy przypadek: zla precedencja || i && sprawiala, ze
        // KAZDA pora zaczynajaca sie na "po" (w tym polnoc) dostawala +12
        // bez wzgledu na strategnika, a wzorzec bez "l" nie lapal "polnoc" wcale.
        val action = detector.detect("ustaw alarm na 12 północ")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(0, action.hour)
    }

    @Test
    fun `alarm o polnocy dziala tez bez polskich znakow`() {
        val action = detector.detect("ustaw alarm na 12 polnoc")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(0, action.hour)
    }

    @Test
    fun `alarm z minutami zachowuje minuty`() {
        val action = detector.detect("ustaw alarm na 6:45 rano")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(6, action.hour)
        assertEquals(45, action.minute)
    }


    // === Latarka i Bluetooth: przelaczniki systemowe ===

    @Test
    fun `wlacz latarke z poprawnym e ogonkowym jest wykrywane`() {
        // Bylo martwe: regex mial "|" poza grupa, wiec ta pisownia (najbardziej
        // naturalna dla polskiego uzytkownika) nigdy sie nie dopasowywala.
        val action = detector.detect("włącz latarkę")
            .filterIsInstance<Action.ToggleFlashlight>().first()
        assertTrue(action.enabled)
    }

    @Test
    fun `wlacz latarke bez polskich znakow tez dziala`() {
        val action = detector.detect("włącz latarke")
            .filterIsInstance<Action.ToggleFlashlight>().first()
        assertTrue(action.enabled)
    }

    @Test
    fun `wylacz latarke z poprawnym e ogonkowym jest wykrywane`() {
        val action = detector.detect("wyłącz latarkę")
            .filterIsInstance<Action.ToggleFlashlight>().first()
        assertFalse(action.enabled)
    }

    @Test
    fun `wylacz wifi z poprawnym a ogonkowym jest wykrywane`() {
        // Trzeci wariant tego samego bledu: wzorzec "wy[lł]acz" mial literalne
        // "a" zamiast "ą" po [lł] - "wyłącz" (jedyna poprawna polska pisownia
        // z nosowym "ą") nigdy sie nie dopasowywal, tylko "wyłacz"/"wylacz"
        // bez diakrytykow. Ten blad byl w kodzie od poczatku, nie moj.
        val action = detector.detect("wyłącz wifi")
            .filterIsInstance<Action.ToggleWifi>().first()
        assertFalse(action.enabled)
    }

    @Test
    fun `wylacz bluetooth jest wykrywane`() {
        // Wczesniej byla tylko galaz "wlacz bluetooth" - wylaczenie
        // nigdy nie mialo jak zadzialac.
        val action = detector.detect("wyłącz bluetooth")
            .filterIsInstance<Action.ToggleBluetooth>().first()
        assertFalse(action.enabled)
    }

    @Test
    fun `wlacz bluetooth nadal dziala`() {
        val action = detector.detect("włącz bluetooth")
            .filterIsInstance<Action.ToggleBluetooth>().first()
        assertTrue(action.enabled)
    }


    // === Muzyka i otwieranie aplikacji: ten sam blad "brakujace a-ogonkowe" ===

    @Test
    fun `wlacz muzyke z poprawna polska pisownia jest wykrywane`() {
        val actions = detector.detect("włącz muzykę")
        assertTrue(
            "\"włącz muzykę\" nie zostalo rozpoznane, wykryto: $actions",
            actions.any { it is Action.PlayMusic }
        )
    }

    @Test
    fun `wlacz spotify z poprawna polska pisownia otwiera aplikacje`() {
        val actions = detector.detect("włącz spotify")
        assertTrue(
            "\"włącz spotify\" nie zostalo rozpoznane, wykryto: $actions",
            actions.any { it is Action.OpenApp }
        )
    }


    // === Moment wydarzenia w kalendarzu ===

    @Test
    fun `start w ISO 8601 zamienia sie na czas lokalny`() {
        val expected = java.time.LocalDateTime.of(2026, 9, 6, 15, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, detector.parseStartTime("2026-09-06T15:00"))
    }

    @Test
    fun `spacja zamiast T tez dziala`() {
        assertEquals(
            detector.parseStartTime("2026-09-06T15:00"),
            detector.parseStartTime("2026-09-06 15:00")
        )
    }

    @Test
    fun `milisekundy epoki nadal przechodza`() {
        assertEquals(1_757_170_800_000L, detector.parseStartTime("1757170800000"))
    }

    @Test
    fun `przesuniecie strefy jest respektowane`() {
        val utc = detector.parseStartTime("2026-09-06T15:00Z")
        val plusTwo = detector.parseStartTime("2026-09-06T17:00+02:00")
        assertEquals(utc, plusTwo)
    }

    @Test
    fun `smiec zamiast daty nie tworzy wydarzenia w 1970 roku`() {
        assertNull(detector.parseStartTime("jutro o 15"))
        assertNull(detector.parseStartTime(""))
        assertNull(detector.parseStartTime(null as String?))
    }

    @Test
    fun `znacznik kalendarza z data ISO tworzy wydarzenie`() {
        val (spoken, actions) = detector.detectAiMarkedActions(
            "Dodaję do kalendarza.\n" +
                "[[ACTION: type=create_calendar_event title=\"Dentysta\" " +
                "start=\"2026-09-06T15:00\" duration=\"30\"]]"
        )
        assertEquals("Dodaję do kalendarza.", spoken)
        assertEquals(1, actions.size)
        val event = actions.first() as Action.CreateCalendarEvent
        assertEquals("Dentysta", event.title)
        assertEquals(30, event.durationMinutes)
    }


    @Test
    fun `model moze wylaczyc tryb dostepnosci, nie tylko go wlaczyc`() {
        val (_, on) = detector.detectAiMarkedActions("[[ACTION: type=read_text]]")
        assertEquals(Action.ReadText, on.single())

        val (_, off) = detector.detectAiMarkedActions("Jasne.\n[[ACTION: type=stop_accessibility]]")
        assertEquals(Action.StopAccessibility, off.single())

        val (_, nav) = detector.detectAiMarkedActions("[[ACTION: type=start_navigation]]")
        assertEquals(Action.StartNavigation, nav.single())
    }


    @Test
    fun `open_app przyjmuje nazwe aplikacji, nie tylko nazwe pakietu`() {
        val (_, byName) = detector.detectAiMarkedActions(
            "[[ACTION: type=open_app name=\"Spotify\"]]"
        )
        val app = byName.single() as Action.OpenApp
        assertEquals("Spotify", app.appName)
        assertEquals("", app.packageName)

        val (_, byPackage) = detector.detectAiMarkedActions(
            "[[ACTION: type=open_app package=\"com.spotify.music\"]]"
        )
        assertEquals(
            "com.spotify.music",
            (byPackage.single() as Action.OpenApp).packageName
        )
    }

    @Test
    fun `open_url tworzy akcje otwarcia adresu`() {
        val (_, actions) = detector.detectAiMarkedActions(
            "Otwieram.\n[[ACTION: type=open_url url=\"https://example.com/a\"]]"
        )
        assertEquals("https://example.com/a", (actions.single() as Action.OpenUrl).url)
    }


    // === Kiedy pytanie wymaga zdjęcia ===

    @Test
    fun `pytania o to co widac uruchamiaja aparat`() {
        listOf(
            "co właśnie widzę",
            "co widzisz",
            "co ja widzę?",
            "spójrz i powiedz co to",
            "popatrz na to",
            "co mam przed sobą",
            "co jest przede mną",
            "opisz to, na co patrzę",
            "co to za budynek",
            "przeczytaj to",
            "co to jest?",
            "ile to kosztuje"
        ).forEach { question ->
            assertTrue("powinno wymagać obrazu: \"$question\"", detector.needsVision(question))
        }
    }

    @Test
    fun `pytania o wiedze NIE uruchamiaja aparatu`() {
        listOf(
            "co to jest fotosynteza",
            "co to jest sztuczna inteligencja i jak działa",
            "ile to kosztuje wynajem mieszkania w Warszawie",
            "jaka jest stolica Francji",
            "przelicz dwadzieścia euro na złotówki",
            "ustaw budzik na siódmą",
            "wyślij SMS do Ani, że się spóźnię",
            "jaka jutro pogoda"
        ).forEach { question ->
            assertFalse("NIE powinno wymagać obrazu: \"$question\"", detector.needsVision(question))
        }
    }

    @Test
    fun `puste pytanie nie wymaga obrazu`() {
        assertFalse(detector.needsVision(""))
        assertFalse(detector.needsVision("   "))
    }

}
