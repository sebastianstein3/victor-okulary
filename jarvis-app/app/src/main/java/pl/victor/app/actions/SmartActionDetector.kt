package pl.victor.app.actions

import android.util.Log
import java.util.Calendar

/**
 * Rozpoznawanie akcji w tym, co powiedział użytkownik - i w tym, co odpowiedziało AI.
 *
 * Klasa wystawia trzy WYRAŹNIE różne wejścia, po jednym na każdą warstwę
 * routingu (opis całości: [pl.victor.app.AIOrchestrator.handleUserTrigger]):
 *
 * - [detectCritical] - warstwa 0. Garść komend, które muszą zadziałać
 *   natychmiast i offline. Dopasowanie ścisłe, do całego zdania.
 * - [detectAiMarkedActions] - warstwa 1. Skanuje ODPOWIEDŹ modelu w
 *   poszukiwaniu znacznika `[[ACTION: ...]]`, którym prosi o wykonanie
 *   czynności. To jest główna droga: model rozumie intencję, wzorce tylko
 *   dopasowują tekst.
 * - [detect] - warstwa 2. Pełna detekcja wzorcami, używana już WYŁĄCZNIE
 *   wtedy, gdy AI jest niedostępne (brak klucza, brak sieci).
 *
 * Kolejność nie jest przypadkowa: wcześniej [detect] szło pierwsze i
 * przechwytywało zdania, zanim model w ogóle je zobaczył - przez co pudłowało
 * na naturalnej mowie, a przy okazji łapało zdania, które poleceniem nie były.
 */
class SmartActionDetector {

    private val tag = "SmartActionDetector"

    /**
     * WARSTWA 0 - wąski zestaw komend rozpoznawanych natychmiast i bez internetu.
     *
     * Celowo NIE jest to skrót do [detect]. Tamten dopasowuje dziesiątki luźnych fraz
     * i przechwytywał wszystko ZANIM pytanie w ogóle trafiło do AI - przez co z jednej
     * strony pudłował na naturalnej mowie ("daj znać Ani, że będę później"), a z drugiej
     * czasem łapał zdania, które wcale nie były poleceniem. Tutaj są wyłącznie komendy
     * spełniające trzy warunki naraz: jednoznaczne, częste i takie, przy których czekanie
     * na odpowiedź sieci byłoby absurdem. Reszta idzie do AI (warstwa 1), które rozumie
     * intencję zamiast dopasowywać wzorzec.
     *
     * Dopasowanie jest celowo ścisłe (całe zdanie, nie fragment), żeby "zrób zdjęcie
     * jak będziemy na miejscu" nie wyzwoliło aparatu w środku rozmowy.
     */
    /**
     * Czy pytanie dotyczy tego, na co użytkownik PATRZY - czyli czy trzeba
     * zrobić zdjęcie, zanim się na nie odpowie.
     *
     * ## Dlaczego to nie może zależeć od modelu
     * Projekt zakładał, że model sam poprosi o obraz znacznikiem
     * `[[ACTION: type=take_photo]]`. W praktyce robi to niesystematycznie: na
     * "co właśnie widzę" równie chętnie odpowiada, że nie ma dostępu do kamery,
     * albo zgaduje. Zgłoszono to wprost - takie pytania nie uruchamiały aparatu.
     * Prośba modelu zostaje jako uzupełnienie, ale te wzorce działają PEWNIE i
     * bez dodatkowej tury (czyli też szybciej).
     *
     * ## Dlaczego dwie listy, a nie jedna
     * "Spójrz", "co widzisz", "przede mną" są jednoznaczne w każdym zdaniu.
     * Ale "co to jest" znaczy co innego w "co to jest?" (trzymam coś w ręku) niż
     * w "co to jest fotosynteza" (pytanie o wiedzę) - dlatego wzorce wskazujące
     * liczą się dopiero, gdy zdanie jest krótkie i nie ma w nim tematu.
     */
    fun needsVision(text: String): Boolean {
        val lower = text.lowercase().trim().trimEnd('.', '!', '?')
        if (lower.isBlank()) return false

        if (SIGHT_PHRASES.any { lower.contains(it) }) return true

        // Wzorce wskazujące ("to", "tu") tylko w krótkim zdaniu - inaczej
        // "co to jest fotosynteza" kazałoby robić zdjęcie.
        val words = lower.split(Regex("""\s+""")).size
        return words <= DEICTIC_MAX_WORDS && DEICTIC_PHRASES.any { lower.contains(it) }
    }

    fun detectCritical(text: String): List<Action> {
        val lower = text.lowercase().trim().trimEnd('.', '!', '?')

        return when {
            lower.matches(Regex("""^(zr[oó]b|pstryknij)\s+(zdj[eę]cie|f(ot|ot)k[eę])$""")) ->
                listOf(Action.TakePhoto)

            // "stop"/"cicho" NIE jest tutaj celowo - to komenda uciszenia
            // syntezatora, nie sterowanie odtwarzaczem (obsługuje ją
            // AIOrchestrator.handleMetaCommand). TogglePlayPause przełącza stan,
            // więc na zapauzowanej muzyce "stop" by ją... uruchomił.
            lower.matches(Regex("""^(pauza|pauzuj|wstrzymaj|wzn[oó]w)$""")) ->
                listOf(Action.TogglePlayPause)

            lower.matches(Regex("""^(w[lł][aą]cz|zapal)\s+latark[eę]$""")) ->
                listOf(Action.ToggleFlashlight(enabled = true))

            lower.matches(Regex("""^(wy[lł][aą]cz|zga[sś])\s+latark[eę]$""")) ->
                listOf(Action.ToggleFlashlight(enabled = false))

            lower.matches(Regex("""^(nast[eę]pna|nast[eę]pny utw[oó]r|dalej)$""")) ->
                listOf(Action.SkipTrack(SkipDirection.NEXT))

            lower.matches(Regex("""^(poprzednia|poprzedni utw[oó]r|cofnij utw[oó]r)$""")) ->
                listOf(Action.SkipTrack(SkipDirection.PREVIOUS))

            // ROZPOZNANIE PIOSENKI NIE ZNOSI OBIEGU PRZEZ SIEĆ.
            //
            // Zanim model odpowie, mija kilka sekund - a piosenka przez ten
            // czas leci dalej albo się kończy. Shazam musi zacząć słuchać
            // WTEDY, kiedy pada prośba, nie kilka sekund później.
            //
            // To ten sam wniosek co przy trasie (patrz [detectNavigation]):
            // model zapytany o czynność ODPOWIADA NA NIĄ SŁOWAMI. Z dziennika
            // z 16 września: "otwórz shazam" i "włącz shazam" poszły do modelu
            // i wróciły zapowiedzią, że może otworzyć - bez otwierania.
            //
            // Wzorzec obejmuje CAŁE zdanie, więc "lubię tę piosenkę" i
            // "opowiedz mi o tej piosence" tu nie wchodzą - Shazam włączony
            // w środku rozmowy zagłusza asystenta i wygląda na awarię.
            lower.matches(
                Regex(
                    """^(co\s+to\s+(jest\s+)?za\s+(piosenka|utw[oó]r|kawa[lł]ek)|""" +
                        """jaka\s+to\s+piosenka|""" +
                        """rozpoznaj\s+(t[eę]\s+)?(piosenk[eę]|utw[oó]r|muzyk[eę])|""" +
                        """(w[lł][aą]cz|odpal|otw[oó]rz|uruchom)\s+shazam\S*)$"""
                )
            ) -> listOf(Action.AppTask(AppTaskKind.RECOGNIZE_SONG))

            lower.matches(
                Regex("""^(w[lł][aą]cz|odpal|otw[oó]rz|uruchom)\s+yanosik\S*$""")
            ) -> listOf(Action.AppTask(AppTaskKind.ROAD_ASSIST))

            else -> emptyList()
        }
    }

    /**
     * Trasa z wypowiedzi: "nawiguj do X", "prowadź do X", "jedź do X".
     *
     * ## Czemu to jest osobna, publiczna funkcja
     * Bo prośba o trasę należy do warstwy 0, a nie do zapasowej [detect].
     * Model, który dostaje takie zdanie, ODPOWIADA na nie słowami - w dzienniku
     * z 15 września, 20:52:01, na "nawiguj do najbliższej biedronki" zapowiedział
     * włączenie nawigacji i nie uruchomił niczego. Zapowiedź bez Intentu jest
     * gorsza niż odmowa, bo użytkownik idzie dalej przekonany, że trasa leci.
     *
     * Wzorzec jest ścisły (czasownik ruchu + "do" + cel), więc do warstwy 0
     * pasuje: dopasowanie znaczy prośbę o trasę, a nie rozmowę o drodze.
     *
     * ## Tryb podróży niesie CZASOWNIK
     * Trasa samochodowa poprowadzona pieszemu każe iść obwodnicą, a piesza
     * kierowcy - przez park.
     *
     * - "jedź do" -> samochód, bo mówi to wprost;
     * - "prowadź do" -> pieszo, bo tego zwrotu używa osoba, dla której ta
     *   aplikacja powstała, i idzie ona na własnych nogach;
     * - "nawiguj do" -> SAMOCHÓD. Ten czasownik jest neutralny i dotąd szedł
     *   pieszo razem z "prowadź", co zgłoszono z terenu: "ma nawigować jakby
     *   pieszo a nie autem". W potocznym użyciu "nawiguj" znaczy jazdę.
     */
    fun detectNavigation(text: String): Action.Navigate? {
        val lower = text.lowercase().trim()
        // Prośbę o asystenta wycinamy PRZED dopasowaniem trasy, bo po polsku
        // wchodzi ona także MIĘDZY czasownik a cel: "prowadź bez asystenta do
        // apteki". Przy takim szyku wzorzec wymagający "do" zaraz za
        // czasownikiem nie łapał nic - i zamiast trasy ruszał tryb ostrzegania,
        // czyli dokładnie to, o czym użytkownik powiedział "bez".
        // Nazwa pojazdu wchodzi MIĘDZY czasownik a cel: "jedź AUTOBUSEM do
        // dworca". Wzorzec wymaga "do" zaraz za czasownikiem, więc bez wycięcia
        // nie łapałby nic - dokładnie tak, jak wcześniej przy "prowadź bez
        // asystenta do apteki". Wycinamy PO zapamiętaniu, że padła, bo to z
        // niej bierze się tryb podróży.
        val navText = lower
            .replace(ASSIST_ON_REGEX, " ")
            .replace(ASSIST_OFF_REGEX, " ")
            .replace(TRANSIT_REGEX, " ")
        val match = NAV_REGEX.find(navText) ?: return null
        val dest = cleanDestination(match.groupValues[2])
        if (dest.isBlank()) return null
        val verb = match.groupValues[1].lowercase()
        // Komunikacja miejska rozpoznawana ze SŁOWA, nie z czasownika: mówi się
        // "jak dojadę autobusem do dworca", a czasownik jest tam ten sam co
        // przy samochodzie.
        val transit = TRANSIT_WORDS.any { lower.contains(it) }
        return Action.Navigate(
            destination = dest,
            byCar = !transit && (verb.startsWith("jed") || verb.startsWith("nawiguj")),
            byTransit = transit,
            // Wprost powiedziane wygrywa z ustawieniem - tak samo jak przy
            // każdej innej komendzie głosowej.
            assist = routeAssistIn(lower)
        )
    }

    /**
     * Wzorzec "wyślij <kanałem> do <kogoś> <treść>" dla jednego kanału.
     *
     * ## Czemu wspólny, a nie dwa osobne
     * Bo różnią się JEDNYM słowem, a myliły się w obu tak samo. Wzorzec SMS-a
     * nie łapał dwóch własnych przykładów z katalogu komend:
     *
     *     "wyślij SMS do Ani, ŻE się spóźnię"     -> nic
     *     "wyślij WIADOMOŚĆ do mamy, że już jadę" -> nic
     *
     * Dwie przyczyny, obie drobne i obie zabójcze. Po pierwsze przecinek nie
     * był separatorem - działał tylko dwukropek i myślnik, a po polsku pisze
     * się i mówi przecinkiem. Po drugie spójnik był wpisany BEZ OGONKÓW ("ze"),
     * a rozpoznawanie mowy oddaje "że" - więc wpadało wyłącznie zdanie wpisane
     * z klawiatury bez polskich znaków.
     *
     * Skutek widać było w dzienniku jako ciszę: zdanie szło do modelu i model
     * mówił, co by zrobił, zamiast wysłać.
     *
     * @param channel alternatywa nazw kanału, np. `sms|wiadomo[sś][cć]`
     */
    private fun messageRegex(channel: String): Regex = Regex(
        // czasownik + (opcjonalne "na") + kanał + "do" + odbiorca
        """(?:napisz|wy[sś]lij|wyslij|nadaj|wy[lł]ij)\s+(?:na\s+)?(?:$channel)\s+do\s+""" +
            // Odbiorca: bez przecinka w środku, bo przecinek JEST separatorem.
            """([^\s,:;-]+)""" +
            // Separator: znak interpunkcyjny albo spójnik - a po znaku
            // interpunkcyjnym spójnik może jeszcze wystąpić ("do Ani, że ...").
            """(?:\s*[,:;-]\s*(?:[zż]e|i[zż])?|\s+(?:o\s+tre[sś]ci|tre[sś][cć]|tekst|[zż]e|i[zż]))\s*""" +
            // Treść.
            """["']?(.+?)["']?$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Słowa, po których wiadomo, że chodzi o komunikację miejską.
     *
     * "Komunikacją" i "transportem publicznym" nikt na co dzień nie mówi -
     * mówi się nazwą pojazdu. Stąd autobus, tramwaj, metro i pociąg.
     */
    // Wzorce zadań w cudzych aplikacjach - patrz detect().
    //
    // Jakdojade wymaga NAZWY aplikacji, żeby nie kłóciło się z "jedź autobusem
    // do X", które obsługują Mapy (plan podróży bez instalowania czegokolwiek).
    // GRUPA NIEPRZECHWYTUJĄCA wokół alternatywy jest tu warunkiem działania, a
    // nie kosmetyką: bez niej `|` rozdziela CAŁY wzorzec, więc pierwsza gałąź
    // to samo słowo "jakdojade", a cel nigdy się nie łapie. Wzorzec pasował
    // wtedy do zdania i oddawał pusty cel.
    // "do" NIE WYSTARCZY - i to nie jest drobiazg.
    //
    // Z terenu przyszło "sprawdź w jakdojade jak dotrę NA polną 140 w toruniu"
    // i wzorzec nie złapał nic. Dwa powody naraz: cel stał po "na", a nie po
    // "do", a samo "dotrę" zaczyna się od liter "do" BEZ spacji, więc `\bdo\s`
    // przechodziło obok. Po polsku mówi się do celu i na cel wymiennie
    // ("na dworzec", "na Polną", "do apteki"), więc jedno z nich to połowa
    // zdań, jakie ktoś naprawdę powie.
    private val JAKDOJADE_REGEX = Regex(
        """(?:jak\s?doja[dz]\S*|jakdojade)\b.*?\b(?:do|na)\s+["']?(.+?)["']?$""",
        RegexOption.IGNORE_CASE
    )

    private val SONG_REGEX = Regex(
        """(co\s+to\s+(jest\s+)?za\s+(piosenk|utw[oó]r|kawa[lł]ek)|""" +
            """rozpoznaj\s+(t[eę]\s+)?(piosenk|utw[oó]r|muzyk)|""" +
            """\bshazam\S*|jaka\s+to\s+piosenka)""",
        RegexOption.IGNORE_CASE
    )

    private val RIDE_REGEX = Regex(
        """(?:zam[oó]w|wezw[ij]?|weź)\s+(?:mi\s+)?""" +
            // \S, nie \w: w Javie (a więc i w Kotlinie) `\w` to
            // [a-zA-Z_0-9] BEZ polskich liter, więc "taksówkę" urywało się na
            // "ę" i całe zdanie przepadało.
            """(?:ubera|uber|bolta|bolt|taks[oó]wk\S*|przejazd\S*|kurs\S*)\s+do\s+["']?(.+?)["']?$""",
        RegexOption.IGNORE_CASE
    )

    private val YANOSIK_REGEX = Regex(
        """(w[lł][aą]cz|odpal|otw[oó]rz|uruchom)\s+yanosik\S*""",
        RegexOption.IGNORE_CASE
    )

    /** Nazwy pojazdów do WYCIĘCIA z tekstu przed dopasowaniem trasy. */
    private val TRANSIT_REGEX = Regex(
        """\b(autobusem|autobusu|tramwajem|tramwaju|metrem|metra|""" +
            """poci[aą]giem|poci[aą]gu|kolejk[aą]|komunikacj[aą]|""" +
            """transportem\s+publicznym|miejsk[aą])\b""",
        RegexOption.IGNORE_CASE
    )

    private val TRANSIT_WORDS = listOf(
        // Rdzenie, które bezpiecznie łapią odmianę: "autobus" bierze
        // "autobusem" i "autobusu", "tramwaj" bierze "tramwajem".
        "autobus", "tramwaj", "pociąg", "pociag", "kolejk", "szynobus",
        // METRO wymaga form jawnych. Skrót do "metr" łapałby "sto metrów" i
        // zamieniał odległość w środek transportu.
        "metro", "metrem", "metra",
        "komunikacją", "komunikacja miejsk", "transportem publicznym",
        "jakdojade", "jak dojade", "jak dojadę"
    )

    private val NAV_REGEX = Regex(
        """(nawiguj|prowadz|prowadź|jedz|jedź|poprowadz|poprowadź)\s+do\s+["']?(.+?)["']?$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Pełna detekcja wzorcami. Po wprowadzeniu warstw używana już tylko jako ZAPASOWA
     * ścieżka, gdy AI jest niedostępne (brak klucza, brak sieci) - patrz
     * [pl.victor.app.AIOrchestrator]. Gdy AI jest dostępne, routing robi model.
     *
     * Zwraca listę akcji (może być wiele) lub pustą listę jeśli nic nie wykryto.
     */
    fun detect(text: String): List<Action> {
        val lower = text.lowercase().trim()
        val actions = mutableListOf<Action>()

        // === SMS ===
        // "wyślij SMS do Ani o treści cześć" / "wyślij SMS do Ani: cześć"
        val smsRegex = messageRegex("""sms|wiadomo[sś][cć]|wiadomo[sś]ci""")
        smsRegex.find(lower)?.let { match ->
            val to = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (to.isNotBlank() && body.isNotBlank()) {
                actions.add(Action.SendSms(to = to, body = body))
            }
        }

        // === ZADANIA W CUDZYCH APLIKACJACH ===
        //
        // Wzorce są WĄSKIE z rozmysłu: każdy wymaga słowa, które jednoznacznie
        // nazywa czynność albo aplikację. "Co to za piosenka" nie może porwać
        // zwykłego pytania, a "zamów" bez celu nie ma czego zamawiać.
        JAKDOJADE_REGEX.find(lower)?.let { m ->
            val dest = cleanDestination(m.groupValues[1])
            if (dest.isNotBlank()) {
                actions.add(Action.AppTask(AppTaskKind.TRANSIT_PLAN, dest))
            }
        }
        if (SONG_REGEX.containsMatchIn(lower)) {
            actions.add(Action.AppTask(AppTaskKind.RECOGNIZE_SONG))
        }
        RIDE_REGEX.find(lower)?.let { m ->
            val dest = cleanDestination(m.groupValues[1])
            if (dest.isNotBlank()) {
                actions.add(Action.AppTask(AppTaskKind.ORDER_RIDE, dest))
            }
        }
        if (YANOSIK_REGEX.containsMatchIn(lower)) {
            actions.add(Action.AppTask(AppTaskKind.ROAD_ASSIST))
        }

        // === WHATSAPP ===
        // Osobny wzorzec przed SMS-em nie jest potrzebny - kolejność tu nie gra,
        // bo oba wymagają SWOJEGO słowa kluczowego. Ważne jest co innego: bez
        // tego wzorca "napisz na whatsappie" wpadało w SMS-a i wiadomość szła
        // kanałem, którego adresat może nie sprawdzać.
        val whatsAppRegex = messageRegex("""whatsapp(?:ie|em|a|zie)?|wa""")
        whatsAppRegex.find(lower)?.let { match ->
            val to = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (to.isNotBlank() && body.isNotBlank()) {
                actions.add(Action.SendWhatsApp(to = to, body = body))
            }
        }

        // === CALL ===
        // "zadzwoń do mamy" (nazwa - jedno słowo) albo
        // "zadzwoń pod 123 456 789" (numer - może mieć spacje/myślniki).
        // Wcześniej jeden wzorzec z \S+ łapał z numeru tylko pierwszy fragment
        // przed spacją ("123" z "123 456 789").
        val callNameRegex = Regex(
            """(?:zadzwon|zadzwoń|dzwon|zadzwoń)\s+do\s+(\S+)""",
            RegexOption.IGNORE_CASE
        )
        val callNumberRegex = Regex(
            """(?:zadzwon|zadzwoń|dzwon|zadzwoń)\s+(?:pod|na)\s+([\d\s+\-()]+\d)""",
            RegexOption.IGNORE_CASE
        )
        (callNumberRegex.find(lower) ?: callNameRegex.find(lower))?.let { match ->
            val to = match.groupValues[1].trim()
            if (to.isNotBlank()) {
                actions.add(Action.MakeCall(to = to))
            }
        }

        // === EMAIL ===
        // "wyślij maila do kowalski@x.com o temacie X z treścią Y"
        val emailRegex = Regex(
            """(?:wy[lś]lij|wyslij)\s+(?:mail|email|e-mail)\s+do\s+(\S+?)(?:\s+(?:o\s+)?temat[ie]?\s+["']?(.+?)["']?)?(?:\s+(?:tresci|treści|tekst|o\s+tresci|o\s+treści)\s+["']?(.+?))?$""",
            RegexOption.IGNORE_CASE
        )
        emailRegex.find(lower)?.let { match ->
            val to = match.groupValues[1].trim()
            val subject = match.groupValues[2].trim().ifBlank { "Temat" }
            val body = match.groupValues[3].trim()
            if (to.isNotBlank()) {
                actions.add(Action.SendEmail(to = to, subject = subject, body = body))
            }
        }

        // === MUZYKA ===
        // "włącz muzykę" / "puść piosenkę X" / "odtwórz X"
        //
        // "w[lł]acz" (bez "ą") nie łapało poprawnie napisanego "włącz" -
        // ten sam błąd, co wcześniej w przełącznikach WiFi/Bluetooth/latarki.
        val playMusicRegex = Regex(
            """(?:w[lł][aą]cz|pusc|pu[sś]c|odtworz|odtwórz|zagraj)\s+""" +
                """(?:muzyke|muzykę|piosenk[ęe]|utwor|song|album)\s*["']?(.+?)?["']?$""",
            RegexOption.IGNORE_CASE
        )
        playMusicRegex.find(lower)?.let { match ->
            val query = match.groupValues[1].trim().ifBlank { "playlist" }
            actions.add(Action.PlayMusic(query = query))
        }

        // "pauza" / "wstrzymaj" / "zatrzymaj muzykę" / "wznów"
        if (lower.matches(Regex(""".*(pauz|stop|wstrzym|zatrzymaj.*muzyk|pauzuj).*"""))) {
            actions.add(Action.TogglePlayPause)
        }
        if (lower.matches(Regex(""".*(wzn[oó]w|kontynuuj|play).*"""))) {
            actions.add(Action.TogglePlayPause)
        }

        // "następna piosenka" / "skip" / "dalej"
        if (lower.matches(Regex(""".*(nast[eę]pna|nastepna|skip|dalej|przeskocz|next).*"""))) {
            actions.add(Action.SkipTrack(SkipDirection.NEXT))
        }
        if (lower.matches(Regex(""".*(poprzednia|cofn|wstecz|previous|prev).*"""))) {
            actions.add(Action.SkipTrack(SkipDirection.PREVIOUS))
        }

        // === NAWIGACJA ===
        // "nawiguj do X" / "prowadź do X" / "jedź do X"
        //
        // Czasownik niesie TRYB PODRÓŻY i to nie jest szczegół: trasa
        // samochodowa poprowadzona pieszemu każe iść obwodnicą, a piesza
        // kierowcy - przez park.
        detectNavigation(lower)?.let { actions.add(it) }

        // === KALENDARZ ===
        // "dodaj do kalendarza spotkanie z Anną jutro o 15" / "umów spotkanie z
        // szefem w piątek o 10:30". Backend (GoogleCalendarService.createEvent)
        // istniał już wcześniej, ale nic po stronie głosu go nie wywoływało - patrz
        // CalendarEventParser dla parsowania dnia/godziny.
        pl.victor.app.actions.CalendarEventParser.parse(lower)?.let { parsed ->
            actions.add(
                Action.CreateCalendarEvent(
                    title = parsed.title,
                    startTimeMillis = parsed.startTimeMillis
                )
            )
        }

        // === ALARM ===
        // "ustaw alarm na 7 rano" / "alarm na 7:30"
        //
        // Grupa pory dnia dopasowuje zarówno wersję z polskimi znakami, jak
        // i bez nich (rozpoznawanie mowy nie zawsze je zwraca): "południe"
        // albo "poludnie", "północ" albo "polnoc". Wcześniejszy wzorzec
        // `p[oó]lnoc` (bez `ł`) nie pasował do poprawnie zapisanego
        // "północ" wcale - "północ" nigdy się nie dopasowywała.
        val alarmRegex = Regex(
            """(?:ustaw|postaw|nastaw)\s+alarm\s+na\s+(\d{1,2})(?::(\d{2}))?\s*""" +
                """(rano|wiecz[oó]r|po[lł]udnie|p[oó][lł]noc)?""",
            RegexOption.IGNORE_CASE
        )
        alarmRegex.find(lower)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            // Dopasowanie po dokładnej wartości grupy, nie po prefiksie:
            // "południe" i "północ" mają wspólny prefiks "poł", więc łańcuch
            // startsWith() myli jedno z drugim. Do tego && wiąże mocniej niż ||,
            // więc poprzedni warunek "startsWith("po") || startsWith("polu") && ..."
            // uruchamiał gałąź południa dla KAŻDEJ pory zaczynającej się na "po" -
            // w tym dla północy - bez względu na strażnika hour < 12.
            val period = match.groupValues[3].lowercase()
                .replace("ł", "l").replace("ó", "o")
            val adjustedHour = when {
                period == "wieczor" && hour < 12 -> hour + 12
                period == "poludnie" && hour < 12 -> hour + 12
                period == "polnoc" -> if (hour == 12) 0 else hour
                else -> hour
            }
            actions.add(Action.SetAlarm(hour = adjustedHour, minute = minute))
        }

        // === TIMER ===
        // "timer na 5 minut" / "odliczaj 10 sekund"
        val timerRegex = Regex(
            """(?:timer|odlicz[auj]|odliczaj)\s+(?:na\s+)?(\d+)\s*(minut|minut[yey]|sekund|sekundy|sek|godzin)?""",
            RegexOption.IGNORE_CASE
        )
        timerRegex.find(lower)?.let { match ->
            val value = match.groupValues[1].toIntOrNull() ?: return@let
            val unit = match.groupValues[2].lowercase()
            val (minutes, seconds) = when {
                unit.startsWith("min") -> value to 0
                unit.startsWith("sek") -> 0 to value
                unit.startsWith("godz") -> value * 60 to 0
                else -> value to 0  // domyślnie minuty
            }
            actions.add(Action.SetTimer(minutes = minutes, seconds = seconds))
        }

        // === POKAŻ NA MAPIE ===
        // "pokaż na mapie X" / "gdzie jest X" / "znajdź na mapie X"
        // Odrębne od nawigacji: użytkownik chce zobaczyć miejsce, nie jechać.
        // Action.ShowOnMap była zaimplementowana w ActionExecutor, ale nic
        // jej nie tworzyło - komenda nie miała jak zadziałać.
        val mapRegex = Regex(
            """(?:pokaz|pokaż|znajdz|znajdź|wyswietl|wyświetl)\s+(?:mi\s+)?""" +
                """(?:na\s+mapie\s+|gdzie\s+jest\s+)?["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE
        )
        val whereRegex = Regex(
            """(?:gdzie\s+(?:jest|znajduje\s+sie|znajduje\s+się))\s+["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE
        )
        // "gdzie jest X" samo w sobie NIE otwiera map. W trybie dostępności
        // niewidomy użytkownik pyta "gdzie jest wyjście?" o to, co przed nim -
        // przekierowanie go wtedy do Google Maps byłoby wprost szkodliwe.
        // Mapy wchodzą w grę tylko przy jawnej wzmiance o mapie albo przy
        // "najbliższy", które nie ma sensu w pytaniu o widok.
        val explicitMap = lower.contains("mapie") || lower.contains("mapa")
        val looksLikePlaceSearch = Regex(
            """najbli(?:z|ż)sz""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lower)

        if (explicitMap || (whereRegex.containsMatchIn(lower) && looksLikePlaceSearch)) {
            val place = (whereRegex.find(lower) ?: mapRegex.find(lower))
                ?.groupValues?.get(1)
                ?.replace(Regex("""(?:^|\s)na\s+mapie(?:\s|$)""", RegexOption.IGNORE_CASE), " ")
                ?.trim()
            if (!place.isNullOrBlank() && actions.none { it is Action.Navigate }) {
                actions.add(Action.ShowOnMap(query = place))
            }
        }

        // === OTWÓRZ STRONĘ ===
        // "otwórz stronę X" / "wejdź na X" / albo sam adres w wypowiedzi.
        // Action.OpenUrl też była martwa - wykonawca ją obsługiwał, detektor nie.
        val urlInText = Regex(
            """\b((?:https?://|www\.)[^\s<>"']+|[a-z0-9-]+\.(?:pl|com|org|net|eu|io|dev)""" +
                """(?:/[^\s<>"']*)?)""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1)

        // Sam czasownik wystarczy - "wejdź na example.com" jest równie naturalne
        // jak "wejdź na stronę example.com".
        //
        // Bez \b na końcu: w Javie granica słowa opiera się na [a-zA-Z0-9_],
        // więc po polskim „ź" żadnej granicy nie ma i „wejdź " nigdy by się
        // nie dopasowało. To cicha pułapka - regex wygląda poprawnie i milczy.
        val opensPage = Regex(
            """(?:^|\s)(?:otworz|otwórz|wejdz|wejdź|odwiedz|odwiedź)(?:\s|$)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lower)

        val isBareUrl = lower.trim() == urlInText?.lowercase()

        if (urlInText != null && (opensPage || isBareUrl) &&
            actions.none { it is Action.ShowOnMap }
        ) {
            val url = if (urlInText.startsWith("http", ignoreCase = true)) urlInText
                else "https://$urlInText"
            actions.add(Action.OpenUrl(url = url))
        }

        // === WYSZUKIWANIE ===
        // "wyszukaj X" / "szukaj X" / "google X"
        val searchRegex = Regex(
            """(?:wyszukaj|szukaj|google|search)\s+["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE
        )
        searchRegex.find(lower)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.isNotBlank()) {
                actions.add(Action.WebSearch(query = query))
            }
        }

        // === OTWÓRZ APKĘ ===
        // "otwórz Spotify" / "uruchom Gmail" / "włącz Spotify"
        val openAppRegex = Regex(
            """(?:otworz|otwórz|uruchom|w[lł][aą]cz)\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )
        openAppRegex.find(lower)?.let { match ->
            val appName = match.groupValues[1].trim().lowercase()
            val appMap = mapOf(
                "spotify" to "com.spotify.music",
                "youtube" to "com.google.android.youtube",
                "mapy" to "com.google.android.apps.maps",
                "google maps" to "com.google.android.apps.maps",
                "gmail" to "com.google.android.gm",
                "mail" to "com.google.android.gm",
                "whatsapp" to "com.whatsapp",
                "telegram" to "org.telegram.messenger",
                "instagram" to "com.instagram.android",
                "facebook" to "com.facebook.katana",
                "netflix" to "com.netflix.mediaclient",
                "uber" to "com.uber",
                "amazon" to "com.amazon.mShop.android.shopping",
                "kalendarz" to "com.google.android.calendar",
                "calendar" to "com.google.android.calendar",
                "notatki" to "com.google.android.keep",
                "keep" to "com.google.android.keep"
            )
            appMap[appName]?.let { pkg ->
                actions.add(Action.OpenApp(packageName = pkg, appName = appName))
            }
        }

        // === TŁUMACZENIE ===
        // "przetłumacz X na angielski"
        val translateRegex = Regex(
            """(?:przet[lł]umacz|translate|przetlumacz)\s+["']?(.+?)["']?\s+na\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )
        translateRegex.find(lower)?.let { match ->
            val text = match.groupValues[1].trim()
            val lang = match.groupValues[2].trim()
            actions.add(Action.Translate(text = text, targetLang = lang))
        }

        // === SYSTEM ===
        // "Wyłącz" (z nosowym ą) to jedyna poprawna polska pisownia, a wzorzec
        // "wy[lł]acz" (literalne "a", nie "ą") jej nie łapał - łapał tylko
        // "wyłacz"/"wylacz" bez diakrytyków. Poprawione na "wy[lł][aą]cz"
        // wszędzie niżej.
        if (lower.matches(Regex(""".*(wlacz|włącz).*wifi.*"""))) {
            actions.add(Action.ToggleWifi(enabled = true))
        }
        if (lower.matches(Regex(""".*(wylacz|wy[lł][aą]cz).*wifi.*"""))) {
            actions.add(Action.ToggleWifi(enabled = false))
        }
        if (lower.matches(Regex(""".*(wlacz|włącz).*bluetooth.*"""))) {
            actions.add(Action.ToggleBluetooth(enabled = true))
        }
        // Brakowało tej gałęzi - "wyłącz bluetooth" nigdy nie było wykrywane,
        // mimo że WiFi i latarka mają obie strony (włącz/wyłącz).
        if (lower.matches(Regex(""".*(wylacz|wy[lł][aą]cz).*bluetooth.*"""))) {
            actions.add(Action.ToggleBluetooth(enabled = false))
        }
        // `|` w regexie ma najniższy priorytet - wiąże CAŁE wyrażenie po obu
        // stronach, nie tylko "latarke". Wzorzec
        // ".*(wlacz|włącz).*latarke|latark[ęe].*" to w praktyce DWIE osobne
        // alternatywy: ".*(wlacz|włącz).*latarke" (bez "ę") ORAZ
        // "latark[ęe].*" (musi zaczynać się od "latark..." - matches()
        // wymaga dopasowania całego tekstu). "Włącz latarkę" z poprawnym "ę"
        // nie trafiało w żadną z nich. Grupa musi obejmować obie pisownie
        // w jednym miejscu.
        if (lower.matches(Regex(""".*(wlacz|włącz).*latark[ęe].*"""))) {
            actions.add(Action.ToggleFlashlight(enabled = true))
        }
        if (lower.matches(Regex(""".*(wylacz|wy[lł][aą]cz).*latark[ęe].*"""))) {
            actions.add(Action.ToggleFlashlight(enabled = false))
        }

        // === Specjalny marker z AI: [[ACTION: type=send_sms to="Ania" body="cześć"]] ===
        val aiActionRegex = Regex("""\[\[ACTION:\s*type=(\w+)(?:\s+(\w+)=["']?(.+?)["']?)*\]\]""")
        aiActionRegex.findAll(text).forEach { match ->
            val type = match.groupValues[1]
            val params = mutableMapOf<String, String>()
            for (i in 2 until match.groupValues.size - 1 step 2) {
                val key = match.groupValues[i]
                val value = match.groupValues[i + 1]
                if (key.isNotBlank() && value.isNotBlank()) {
                    params[key] = value
                }
            }
            parseAiAction(type, params)?.let { actions.add(it) }
        }

        // === Accessibility (niewidomi) ===
        if (matchesAny(lower, "przeczytaj", "czytaj to", "co tu pisze", "odczytaj")) {
            actions.add(Action.ReadText)
        }
        if (matchesAny(lower, "co przede mną", "co widzisz", "opisz", "co jest przed", "co tam")) {
            actions.add(Action.DescribeScene)
        }
        // "prowadź" WŁĄCZA ostrzeganie o przeszkodach, ale "prowadź DO
        // BIEDRONKI" to prośba o trasę - i dotąd odpalały się OBIE naraz.
        // Skutek był podwójnie zły: otwierała się mapa, a w tle ruszała pętla
        // pytająca model o obraz kilkadziesiąt razy na minutę, o co nikt nie
        // prosił. Ten sam wzorzec pierwszeństwa co przy [Action.ShowOnMap] wyżej.
        if (matchesAny(lower, "prowadź", "nawiguj", "idź ze mną", "idziemy") &&
            actions.none { it is Action.Navigate }
        ) {
            actions.add(Action.StartNavigation)
        }
        // Zwroty MUSZĄ obejmować to, czego uczy katalog komend - inaczej
        // człowiek mówi zdanie z instrukcji i nic się nie dzieje. Tak było z
        // "dziękuję, wystarczy" i "przestań czytać": obu tu brakowało, a oba
        // stoją w katalogu jako przykłady. Pilnuje tego test.
        if (matchesAny(
                lower, "stop czytanie", "stop opis", "zatrzymaj tryb", "wyłącz tryb",
                "wystarczy", "przestań czytać", "przestan czytać", "przestań opisywać",
                "skończ czytać", "koniec czytania"
            )
        ) {
            actions.add(Action.StopAccessibility)
        }

        if (actions.isNotEmpty()) {
            Log.d(tag, "Detected ${actions.size} action(s): ${actions.map { it.type }}")
        }
        return actions
    }

    private fun matchesAny(text: String, vararg phrases: String): Boolean {
        return phrases.any { text.contains(it) }
    }

    /**
     * Sprząta cel trasy z tego, co wnosi mowa, a nie miejsce.
     *
     * "Najbliższa" jest tu najczęstsza i akurat zbędna: mapy szukają od
     * bieżącego położenia i same podają najbliższy wynik, a zostawione w
     * zapytaniu bywa dopasowywane do nazwy miejsca. Odmiany nie prostujemy -
     * "biedronki" mapy znajdą tak samo jak "biedronka", a prostowanie polskiej
     * odmiany regułą kończy się gorzej niż jej zostawienie.
     */
    private fun cleanDestination(raw: String): String = raw
        .trim()
        .replace(NEAREST_REGEX, "")
        .replace(ASSIST_ON_REGEX, " ")
        .replace(ASSIST_OFF_REGEX, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim(',', '.', '!', '?')
        .trim()

    /**
     * Czy w komendzie padło "z asystentem" albo "bez asystenta".
     *
     * Szukamy w CAŁEJ wypowiedzi, nie tylko za nazwą miejsca: po polsku równie
     * naturalne jest "prowadź do apteki bez asystenta" i "prowadź bez asystenta
     * do apteki". Wycinaniem tego z nazwy celu zajmuje się [cleanDestination] -
     * inaczej mapy szukałyby miejsca o nazwie "apteki bez asystenta".
     *
     * "Bez" ma pierwszeństwo, bo jest tańszą pomyłką: nieoczekiwanie nieczynny
     * asystent da się włączyć jednym zdaniem, nieoczekiwanie czynny pyta model
     * kilkadziesiąt razy na minutę.
     */
    private fun routeAssistIn(text: String): RouteAssist = when {
        ASSIST_OFF_REGEX.containsMatchIn(text) -> RouteAssist.OFF
        ASSIST_ON_REGEX.containsMatchIn(text) -> RouteAssist.ON
        else -> RouteAssist.FROM_SETTINGS
    }

    /**
     * Wykrywa akcje oznaczone przez AI znacznikiem `[[ACTION: ...]]` w JEGO
     * własnej odpowiedzi - patrz [AI_ACTION_CAPABILITIES_PROMPT], który tłumaczy
     * modelowi ten format.
     *
     * Celowo OSOBNA od [detect]: [detect] dopasowuje luźne frazy ("włącz wifi")
     * w dowolnym tekście, co ma sens dla tego, co powiedział user, ale odpalone
     * na swobodnej prozie AI ryzykowałoby przypadkowe trafienie (AI wspominające
     * "włączyłbym WiFi" w wyjaśnieniu, nie jako instrukcję). Sam znacznik
     * `[[ACTION: ...]]` jest jednoznaczny - bezpiecznie skanować nim całą
     * odpowiedź AI.
     *
     * @return oczyszczony tekst (bez znaczników - user go nie widzi/słyszy)
     *         razem z wykrytymi akcjami
     */
    fun detectAiMarkedActions(text: String): Pair<String, List<Action>> {
        val actions = mutableListOf<Action>()

        val cleaned = AI_ACTION_TAG_REGEX.replace(text) { match ->
            val body = match.groupValues[1].ifBlank { match.groupValues[2] }
            val params = parseTagParams(body)
            val type = params["type"]
                ?: FIRST_TOKEN_REGEX.find(body)?.groupValues?.get(1)
                ?: return@replace ""
            parseAiAction(normalizeActionType(type), params)?.let { actions.add(it) }
            ""
        }

        // Siatka bezpieczeństwa. Model potrafi napisać znacznik inaczej, niż go
        // prosiliśmy (przecinki zamiast spacji, inna nazwa akcji, literówka) -
        // i wtedy zostaje w tekście, a TTS go po prostu CZYTA na głos. Cokolwiek
        // wygląda jak znacznik, nie ma prawa trafić do użytkownika.
        val withoutStrayTags = STRAY_TAG_REGEX.replace(cleaned, "")

        return tidySpokenText(withoutStrayTags) to actions
    }

    /**
     * Wyciąga pary `klucz=wartość` z wnętrza znacznika.
     *
     * Osobna funkcja, bo poprzednia wersja robiła to jedną grupą powtarzalną
     * `(?:\s+(\w+)=...)*` w regexie znacznika - a powtarzalna grupa w Javie
     * pamięta WYŁĄCZNIE ostatnie dopasowanie. Przy
     * `[[ACTION: type=send_sms to="Ania" body="cześć"]]` zostawało samo
     * `body`, `to` przepadało, `parseAiAction` zwracało null i SMS nigdy nie
     * powstawał. Tutaj każda para jest znajdowana osobno.
     *
     * Akceptuje separatory spacją, przecinkiem i średnikiem oraz `=` i `:`,
     * bo modele mieszają te konwencje.
     */
    private fun parseTagParams(body: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        for (m in AI_ACTION_PARAM_REGEX.findAll(body)) {
            val key = m.groupValues[1].lowercase()
            val value = listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4])
                .firstOrNull { it.isNotEmpty() }
                ?.trim()
                ?.trim(',', ';')
                ?.trim()
                ?: continue
            if (value.isNotBlank()) {
                params[PARAM_ALIASES[key] ?: key] = value
            }
        }
        return params
    }

    /**
     * "true"/"on"/"włącz"/"1" -> true, "false"/"off"/"wyłącz"/"0" -> false.
     * Modele mieszają te zapisy, a brak wartości zwykle znaczy "włącz".
     */
    private fun String?.toBooleanFlag(default: Boolean): Boolean {
        val v = this?.trim()?.lowercase() ?: return default
        return when {
            v in listOf("false", "off", "0", "no", "nie", "wyłącz", "wylacz", "zgaś", "zgas") -> false
            v in listOf("true", "on", "1", "yes", "tak", "włącz", "wlacz", "zapal") -> true
            else -> default
        }
    }

    /** Sprowadza `Send-SMS`, `sendSms`, `SEND_SMS` do jednej postaci. */
    private fun normalizeActionType(raw: String): String {
        val snake = raw.trim()
            .replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
            .lowercase()
            .replace('-', '_')
            .replace(' ', '_')
        return TYPE_ALIASES[snake] ?: snake
    }

    /**
     * Sprząta tekst po wycięciu znaczników: osierocone puste linie, wiszące
     * gwiazdki markdownu i podwójne spacje, które zostają po wyjęciu tagu ze
     * środka zdania.
     */
    private fun tidySpokenText(text: String): String =
        text.replace(Regex("""\*\*\s*\*\*"""), "")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()

    /**
     * Zamienia moment podany przez model na czas w milisekundach.
     *
     * ## Dlaczego nie same milisekundy
     * Poprzednia wersja przyjmowała WYŁĄCZNIE `toLongOrNull()`, czyli kazała
     * modelowi policzyć epokę uniksową w pamięci. Modele robią to źle i robią to
     * niechętnie - a jeden błąd oznacza wydarzenie w 1970 albo w 2255 roku,
     * bez żadnego sygnału, że coś poszło nie tak. ISO 8601 jest formatem, który
     * modele piszą poprawnie i który da się przeczytać w logu.
     *
     * Milisekundy zostają obsługiwane, bo są jednoznaczne i mogą przyjść ze
     * starszego promptu albo z innej ścieżki.
     */
    internal fun parseStartTime(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // Czysta liczba to epoka w milisekundach. Sekundy odrzucamy świadomie:
        // nie da się ich odróżnić od milisekund inaczej niż zgadywaniem rzędu
        // wielkości, a cicha pomyłka o trzy zera jest gorsza niż odmowa.
        value.toLongOrNull()?.let { return it }

        // "2026-09-06 15:00" to ta sama data co "2026-09-06T15:00" - modele
        // piszą raz tak, raz tak.
        val normalized = value.replaceFirst(' ', 'T')
        return runCatching {
            java.time.LocalDateTime.parse(normalized)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.recoverCatching {
            // Wariant ze strefą albo przesunięciem ("...T15:00+02:00", "...Z").
            java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun parseAiAction(type: String, params: Map<String, String>): Action? {
        return when (type) {
            "send_sms" -> params["to"]?.let { to ->
                Action.SendSms(to = to, body = params["body"] ?: "")
            }
            "send_whatsapp" -> params["to"]?.let { to ->
                Action.SendWhatsApp(to = to, body = params["body"] ?: "")
            }
            "make_call" -> params["to"]?.let { Action.MakeCall(to = it) }
            "send_email" -> params["to"]?.let { to ->
                Action.SendEmail(
                    to = to,
                    subject = params["subject"] ?: "",
                    body = params["body"] ?: ""
                )
            }
            "play_music" -> Action.PlayMusic(query = params["query"] ?: "")
            "navigate" -> params["destination"]?.let {
                val dest = cleanDestination(it)
                dest.ifBlank { null }?.let { where ->
                    Action.Navigate(
                        destination = where,
                        // Domyślnie pieszo, tak jak przy komendzie głosowej;
                        // samochód tylko gdy model wyraźnie o niego poprosi.
                        byCar = params["mode"]?.lowercase()?.startsWith("car") == true ||
                            params["mode"]?.lowercase()?.startsWith("sam") == true
                    )
                }
            }
            "set_alarm" -> {
                val hour = params["hour"]?.toIntOrNull() ?: return null
                val minute = params["minute"]?.toIntOrNull() ?: 0
                Action.SetAlarm(hour = hour, minute = minute, label = params["label"] ?: "")
            }
            "set_timer" -> {
                val minutes = params["minutes"]?.toIntOrNull() ?: return null
                Action.SetTimer(minutes = minutes, seconds = params["seconds"]?.toIntOrNull() ?: 0)
            }
            "web_search" -> params["query"]?.let { Action.WebSearch(query = it) }
            "translate" -> params["text"]?.let {
                Action.Translate(text = it, targetLang = params["target"] ?: "en")
            }
            // Nazwa aplikacji, nie nazwa pakietu: model zna "Spotify", a
            // "com.spotify.music" musiałby zgadnąć - i zgaduje źle. Zamianę
            // nazwy na pakiet robi ActionExecutor, który jako jedyny wie, co
            // faktycznie jest na tym telefonie.
            // Zadanie W aplikacji, nie samo jej otwarcie. Rozdzielone od
            // open_app, bo "otwórz Shazama" i "co to za piosenka" kończą się
            // gdzie indziej: pierwsze na ekranie startowym, drugie na
            // słuchającym mikrofonie.
            "app_task" -> params["kind"]?.let { given ->
                val kind = runCatching {
                    AppTaskKind.valueOf(given.trim().uppercase())
                }.getOrNull()
                kind?.let { Action.AppTask(kind = it, argument = params["argument"] ?: "") }
            }
            "open_app" -> (params["package"] ?: params["name"])?.let { given ->
                Action.OpenApp(
                    packageName = params["package"] ?: "",
                    appName = params["name"] ?: given
                )
            }
            "open_url" -> params["url"]?.let { Action.OpenUrl(url = it) }
            "take_photo" -> Action.TakePhoto

            // Akcje, których model wcześniej NIE MÓGŁ zlecić, mimo że aplikacja
            // umie je wykonać. "Włącz latarkę" łapała warstwa 0, ale już
            // "zapal światło, bo nic nie widzę" szło do AI, a ono nie miało
            // czym o to poprosić - odpowiadało więc słowami zamiast działać.
            "toggle_flashlight" -> Action.ToggleFlashlight(
                enabled = params["enabled"].toBooleanFlag(default = true)
            )
            "toggle_wifi" -> Action.ToggleWifi(
                enabled = params["enabled"].toBooleanFlag(default = true)
            )
            "toggle_bluetooth" -> Action.ToggleBluetooth(
                enabled = params["enabled"].toBooleanFlag(default = true)
            )
            "toggle_play" -> Action.TogglePlayPause
            "skip_track" -> Action.SkipTrack(
                direction = if (params["direction"]?.lowercase()?.startsWith("prev") == true ||
                    params["direction"]?.lowercase()?.startsWith("poprz") == true
                ) SkipDirection.PREVIOUS else SkipDirection.NEXT
            )
            "show_on_map" -> params["query"]?.let { Action.ShowOnMap(query = it) }
            "create_calendar_event" -> {
                val title = params["title"] ?: return null
                val startMs = parseStartTime(params["start"]) ?: return null
                Action.CreateCalendarEvent(
                    title = title,
                    startTimeMillis = startMs,
                    durationMinutes = params["duration"]?.toIntOrNull() ?: 60
                )
            }
            "describe_scene" -> Action.DescribeScene
            "read_text" -> Action.ReadText
            "start_navigation" -> Action.StartNavigation
            // Bez tego model mógł tryby dostępności WŁĄCZYĆ, ale nie miał czym
            // ich wyłączyć - a "dziękuję, wystarczy" to najnaturalniejsza rzecz,
            // jaką użytkownik powie, gdy skończy czytać etykietę.
            "stop_accessibility" -> Action.StopAccessibility

            else -> null
        }
    }

    companion object {
        /**
         * "najbliższa/najbliższego/najblizszy..." na początku celu trasy.
         *
         * Z ogonkami i bez, bo rozpoznawanie mowy zwraca jedno i drugie.
         */
        private val NEAREST_REGEX = Regex(
            """^(?:najbli[żz]sz\w*)\s+""",
            RegexOption.IGNORE_CASE
        )

        /**
         * "z asystentem" i pokrewne - prośba o ostrzeganie o przeszkodach w drodze.
         *
         * Wariantów kilka, bo nikt nie pamięta jednej formułki, a odmiany
         * zostawiamy tolerancyjne (`\w*`) - rozpoznawanie mowy i tak zwraca raz
         * "asystentem", raz "asystenta".
         */
        private val ASSIST_ON_REGEX = Regex(
            """\bz\s+(?:asystent\w*|ostrze[żz]eni\w*|ostrzeganiem|opisem|pomoc\w*|ai)\b""",
            RegexOption.IGNORE_CASE
        )

        /** "bez asystenta" i pokrewne - sama trasa, bez patrzenia i bez kosztu. */
        private val ASSIST_OFF_REGEX = Regex(
            """\bbez\s+(?:asystent\w*|ostrze[żz]e\w*|ostrzegania|opisu|pomocy|ai)\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Znacznik akcji, patrz [detectAiMarkedActions] i [AI_ACTION_CAPABILITIES_PROMPT].
         *
         * Celowo tolerancyjny: podwójne LUB pojedyncze nawiasy, `:` lub `=` po
         * słowie ACTION, dowolna wielkość liter. Modele nie trzymają się formatu
         * co do znaku, a każde niedopasowanie kończyło się tym, że użytkownik
         * SŁYSZAŁ surowy znacznik zamiast dostać wykonaną akcję.
         *
         * Wariant z `[[ ]]` jest pierwszą alternatywą, żeby domknięcie zjadało
         * OBA nawiasy - inaczej wersja jednonawiasowa dopasowałaby się pierwsza
         * i zostawiła w tekście osierocony `]`.
         */
        private val AI_ACTION_TAG_REGEX = Regex(
            """\[\[\s*ACTION\s*[:=]?\s*(.+?)\s*\]\]|\[\s*ACTION\s*[:=]?\s*(.+?)\s*\]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        /**
         * Pojedyncza para `klucz=wartość` wewnątrz znacznika, patrz `parseTagParams`.
         *
         * Wartość bez cudzysłowów kończy się dopiero tam, gdzie zaczyna się
         * kolejny `klucz=`, przecinek/średnik albo koniec znacznika - inaczej
         * `destination=Plac Zamkowy` urwałoby się na spacji, a `type=send_sms
         * to=Ania` połknęłoby całą resztę jako nazwę akcji.
         */
        private val AI_ACTION_PARAM_REGEX = Regex(
            """(\w+)\s*[:=]\s*(?:"([^"]*)"|'([^']*)'|(.+?))(?=\s+\w+\s*[:=]|\s*[,;]|\s*$)""",
            RegexOption.DOT_MATCHES_ALL
        )

        /** Nazwa akcji bez `type=`, np. `[[ACTION: take_photo]]`. */
        private val FIRST_TOKEN_REGEX = Regex("""^\s*([A-Za-z][\w-]*)""")

        /**
         * Cokolwiek, co przetrwało parsowanie, a wygląda jak znacznik.
         * Bez tego TTS czyta na głos `[[ACTION: type=cos_czego_nie_znamy]]`.
         */
        private val STRAY_TAG_REGEX = Regex(
            """\[\[[^\[\]]*\]\]|\[\s*ACTION\s*[:=][^\[\]]*\]""",
            RegexOption.IGNORE_CASE
        )

        /** Nazwy akcji, którymi modele zastępują te z promptu. */
        /**
         * Zwroty, po których widać, że chodzi o otoczenie - niezależnie od
         * długości zdania. "Widzieć", "patrzeć", "przede mną" nie mają
         * sensownego odczytania bez obrazu.
         */
        private val SIGHT_PHRASES = listOf(
            "co widzisz", "widzisz to", "widzisz tu", "co ty widzisz",
            "co widzę", "co ja widzę", "co teraz widzę", "co własnie widzę",
            "co właśnie widzę", "na co patrzę", "co mam przed",
            "przede mną", "przed sobą", "co jest przede",
            "spójrz", "spojrz", "popatrz", "zobacz to", "zerknij",
            "opisz to", "opisz co", "opisz otoczenie", "co tu widać",
            "co widać", "rozpoznaj to", "co to za budynek", "co to za miejsce"
        )

        /**
         * Zwroty wskazujące - liczą się dopiero w krótkim zdaniu, patrz
         * [needsVision].
         */
        private val DEICTIC_PHRASES = listOf(
            "co to jest", "co to za", "co to", "co tu jest", "co tu pisze",
            "przeczytaj to", "przeczytaj mi to", "co tu napisane",
            "ile to kosztuje", "jaka to cena", "czy to jest", "czy to są",
            "czy to swieże", "czy to świeże", "co jest napisane"
        )

        /**
         * Powyżej tylu słów zdanie ma już własny temat i nie jest o tym, co widać.
         *
         * Trzy, nie cztery: "co to jest fotosynteza" ma dokładnie cztery słowa i
         * jest pytaniem o wiedzę, a "co to jest?" - trzy i jest pytaniem o to, co
         * użytkownik trzyma w ręku. Dłuższe zdania łapie jeszcze prośba modelu
         * o zdjęcie, więc ostrożny próg nic nie traci na stałe.
         */
        private const val DEICTIC_MAX_WORDS = 3

        private val TYPE_ALIASES = mapOf(
            "sms" to "send_sms",
            "whatsapp" to "send_whatsapp",
            "wa" to "send_whatsapp",
            "text" to "send_sms",
            "message" to "send_sms",
            "call" to "make_call",
            "phone" to "make_call",
            "dial" to "make_call",
            "email" to "send_email",
            "mail" to "send_email",
            "music" to "play_music",
            "play" to "play_music",
            "search" to "web_search",
            "google" to "web_search",
            "timer" to "set_timer",
            "alarm" to "set_alarm",
            "photo" to "take_photo",
            "take_picture" to "take_photo",
            "camera" to "take_photo",
            "capture" to "take_photo",
            "maps" to "navigate",
            "navigation" to "navigate"
        )

        /** Nazwy parametrów, którymi modele zastępują te z promptu. */
        private val PARAM_ALIASES = mapOf(
            "recipient" to "to",
            "number" to "to",
            "contact" to "to",
            "do" to "to",
            "message" to "body",
            "content" to "body",
            "tresc" to "body",
            "treść" to "body",
            "text_body" to "body",
            "subject_line" to "subject",
            "temat" to "subject",
            "destination_address" to "destination",
            "address" to "destination",
            "where" to "destination",
            "place" to "destination",
            "song" to "query",
            "track" to "query",
            "search_query" to "query",
            "q" to "query",
            "target_lang" to "target",
            "target_language" to "target",
            "lang" to "target",
            "package_name" to "package",
            "app" to "package",
            "hours" to "hour",
            "minutes_value" to "minutes"
        )

        /**
         * Dopisywane do system promptu AI (patrz [pl.victor.app.AIOrchestrator]), żeby
         * model wiedział o formacie tagu z [detectAiMarkedActions]. `open_app` celowo
         * pominięty - nazwa pakietu Androida to coś, co model łatwo zgaduje błędnie,
         * zostaje dostępne tylko przez wzorce w [detect].
         */
        const val AI_ACTION_CAPABILITIES_PROMPT = """
=== WYKONYWANIE AKCJI ===
Jeśli user prosi Cię o wykonanie jednej z poniższych czynności, zakończ swoją
odpowiedź (po naturalnej, mówionej części) znacznikiem w DOKŁADNIE tym
formacie, w nowej linii:
[[ACTION: type=TYP klucz1="wartość1" klucz2="wartość2"]]

Dostępne typy i klucze:
- send_sms: to (numer lub imię), body (treść)
- send_whatsapp: to (numer lub imię), body (treść). UWAGA: to tylko OTWIERA
  rozmowę z wpisaną wiadomością - wysłanie zatwierdza człowiek. Nie mów, że
  wiadomość została wysłana.
- make_call: to (numer lub imię)
- send_email: to (adres), subject (temat), body (treść)
- play_music: query (czego szukać)
- navigate: destination (adres lub miejsce), mode (opcjonalnie: "car" gdy
  samochodem; domyślnie pieszo)
- set_alarm: hour, minute (opcjonalnie), label (opcjonalnie)
- set_timer: minutes, seconds (opcjonalnie)
- web_search: query
- translate: text, target (kod języka, np. "en")
- take_photo: (bez kluczy) - poproś o zdjęcie z kamery okularów, gdy do
  odpowiedzi potrzebujesz zobaczyć to, na co user patrzy, a nie masz obrazu
- toggle_flashlight: enabled ("true" albo "false")
- toggle_wifi: enabled
- toggle_bluetooth: enabled
- toggle_play: (bez kluczy) - pauza albo wznowienie muzyki
- skip_track: direction ("next" albo "prev")
- show_on_map: query (co pokazać na mapie)
- open_app: name (nazwa aplikacji tak, jak mówi ją człowiek - "Spotify",
  "Mapy". NIE zgaduj nazwy pakietu)
- app_task: kind, argument (opcjonalnie). ZADANIE w cudzej aplikacji, a nie
  samo jej otwarcie - użyj, gdy człowiek chce WYNIK, nie ekran startowy.
  Dostępne kind:
  * TRANSIT_PLAN - dojazd komunikacją miejską; argument to cel podróży
    ("sprawdź w jakdojade, jak dotrę na Polną 140", "jak dojadę na dworzec")
  * RECOGNIZE_SONG - rozpoznanie granej muzyki, bez argumentu
    ("co to za piosenka", "włącz shazama")
  * ORDER_RIDE - zamówienie kursu; argument to cel ("zamów ubera na lotnisko")
  * ROAD_ASSIST - ostrzeżenia drogowe, bez argumentu ("włącz yanosika")
  Gdy człowiek prosi tylko o OTWARCIE aplikacji ("otwórz jakdojade"), użyj
  open_app. Gdy prosi o wynik - app_task.
- open_url: url (pełny adres ze schematem, np. "https://...")
- describe_scene: (bez kluczy) - opisz otoczenie osobie niewidomej
- read_text: (bez kluczy) - czytaj tekst z otoczenia na głos
- start_navigation: (bez kluczy) - prowadź osobę niewidomą, opisując drogę
- stop_accessibility: (bez kluczy) - wyłącz włączony tryb dostępności
  ("dziękuję, wystarczy", "przestań czytać")
- create_calendar_event: title (nazwa), start (data i godzina w formacie
  ISO 8601, np. "2026-09-06T15:00"), duration (minuty, opcjonalnie - domyślnie
  60). Bieżącą datę masz wyżej, w sekcji "TERAZ" - policz z niej "jutro",
  "w piątek" i podobne.

Kiedy używać take_photo:
- Pytanie dotyczy czegoś w otoczeniu użytkownika ("co to jest?", "czy to
  jest świeże?", "przeczytaj mi to", "ile to kosztuje?") i NIE dostałeś
  żadnego zdjęcia w tej wiadomości.
- Powiedz wtedy krótko, że patrzysz (np. "Chwila, spojrzę."), i dodaj
  znacznik. Zdjęcie zostanie zrobione, a Twoje pytanie wróci do Ciebie
  jeszcze raz - już z obrazem.
- NIE używaj, gdy pytanie da się rozstrzygnąć bez patrzenia (wiedza ogólna,
  przeliczenia, rozmowa) ani gdy zdjęcie już masz.

Zasady:
- Znacznika użyj TYLKO gdy user faktycznie prosi o wykonanie czynności - nie
  przy zwykłych pytaniach ani gdy tylko o czymś wspominasz.
- Znacznik nie jest widoczny ani słyszalny dla użytkownika - to co mówisz
  PRZED nim powinno brzmieć naturalnie i kompletnie (np. "Wysyłam maila do
  Jana z informacją o spóźnieniu.").
- Nigdy nie wymyślaj wartości takich jak numer telefonu czy adres email,
  jeśli user ich nie podał - dopytaj, zamiast zgadywać.
- NIGDY nie mów, że coś zrobiłeś, jeśli nie dodałeś znacznika. Sam nie masz
  dostępu do kalendarza, poczty ani telefonu - jedyne, co je uruchamia, to
  znacznik. Zdanie "dodałem wydarzenie" bez znacznika jest nieprawdą i tak
  właśnie zostanie odebrane: user usłyszy potwierdzenie, a w kalendarzu nic
  nie znajdzie.
- Aplikacja przed wykonaniem akcji dopyta użytkownika - głosem albo oknem na
  telefonie. Twoja odpowiedź to propozycja, nie ostateczna decyzja, więc nie
  obiecuj skutku, tylko powiedz, co proponujesz zrobić.
"""
    }
}
