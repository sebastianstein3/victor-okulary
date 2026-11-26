package pl.victor.app.hermes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * Hermes Agent (Nous Research) jako "mózg" Victora - czysta logika, bez
 * Androida.
 *
 * ## Co wiemy, a czego nie
 * Z dokumentacji Hermesa (serwer API, `hermes gateway`): HTTP z nagłówkiem
 * `Authorization: Bearer <API_SERVER_KEY>`, punkt `POST /v1/chat/completions`
 * w formacie OpenAI ze strumieniem SSE, nagłówki `X-Hermes-Session-Key`
 * (trwała pamięć kanału) i `GET /health`. Ramka `event: hermes.tool.progress`
 * zapowiada start narzędzia.
 *
 * NIE WIEMY (i nie zgadujemy): jak wygląda ramka z prośbą o zgodę na
 * niebezpieczną operację w strumieniu chat/completions. Dlatego każda
 * nieznana ramka idzie do dziennika w całości (początek), a parser rozpoznaje
 * ją tylko po słowie "approv" - patrz [HermesProtocol.interpretuj].
 */

/** Adres serwera Hermesa wpisany w ustawieniach. */
object HermesAdres {

    const val DOMYŚLNY_PORT = 8642

    sealed class Wynik {
        /** Adres bazowy bez ukośnika, np. `http://192.168.1.10:8642`. */
        data class Dobry(val baza: String, val lokalny: Boolean) : Wynik()
        data class Zły(val powód: String) : Wynik()
    }

    /**
     * Sprawdza i porządkuje adres.
     *
     * ## Czemu HTTP tylko lokalnie
     * Klucz API Hermesa daje dostęp do narzędzi agenta, w tym terminala.
     * Przez zwykłe HTTP idzie jawnym tekstem: w sieci domowej albo w Tailscale
     * (szyfruje sam) to akceptowalne, przez internet nie. Adres zdalny musi
     * być HTTPS.
     */
    fun sprawdź(wpisany: String): Wynik {
        var a = wpisany.trim()
        if (a.isEmpty()) return Wynik.Zły("Wpisz adres komputera z Hermesem.")
        if (!a.contains("://")) {
            val host = a.substringBefore('/').substringBefore(':')
            a = (if (pl.victor.app.home.HaAdres.jestLokalny(host)) "http://" else "https://") + a
        }
        val uri = runCatching { java.net.URI(a) }.getOrNull()
            ?: return Wynik.Zły("To nie wygląda na adres - przykład: http://mac-mini.local:$DOMYŚLNY_PORT")
        val schemat = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
            ?: return Wynik.Zły("W adresie brakuje nazwy komputera - przykład: http://mac-mini.local:$DOMYŚLNY_PORT")
        if (schemat != "http" && schemat != "https") {
            return Wynik.Zły("Adres musi zaczynać się od http:// albo https://")
        }
        val lokalny = pl.victor.app.home.HaAdres.jestLokalny(host)
        if (schemat == "http" && !lokalny) {
            return Wynik.Zły(
                "Przez internet tylko HTTPS - klucz szedłby jawnym tekstem. Użyj adresu z sieci domowej, " +
                    "z Tailscale albo https://."
            )
        }
        // Bez portu w adresie http: domyślny port bramy Hermesa.
        val port = when {
            uri.port > 0 -> ":${uri.port}"
            schemat == "http" -> ":$DOMYŚLNY_PORT"
            else -> ""
        }
        val hostWAdresie = if (host.contains(':')) "[$host]" else host
        return Wynik.Dobry("$schemat://$hostWAdresie$port", lokalny)
    }
}

/** Czy Hermes może teraz obsłużyć zdanie - i jeśli nie, dlaczego. */
enum class HermesDostępność(val komunikat: String?) {
    MOŻNA(null),
    WYŁĄCZONY("Hermes jest wyłączony w ustawieniach."),
    TYLKO_DARMOWE(
        "Hermes jest wyłączony, bo w ustawieniach włączone są tylko darmowe funkcje. " +
            "Pytaj mnie jak zwykle."
    ),
    BRAK_KONFIGURACJI("Hermes nie jest skonfigurowany. Wpisz w ustawieniach adres komputera i klucz.");

    companion object {
        /**
         * @param włączony przełącznik funkcji
         * @param tylkoDarmowe główny przełącznik "Tylko darmowe funkcje" - koszt
         *   Hermesa zależy od modelu, który on sam ma skonfigurowany, więc
         *   aplikacja nie może go ocenić i go pomija
         */
        fun z(włączony: Boolean, tylkoDarmowe: Boolean, adres: String, klucz: String?): HermesDostępność = when {
            !włączony -> WYŁĄCZONY
            tylkoDarmowe -> TYLKO_DARMOWE
            HermesAdres.sprawdź(adres) !is HermesAdres.Wynik.Dobry || klucz.isNullOrBlank() -> BRAK_KONFIGURACJI
            else -> MOŻNA
        }
    }
}

/** Zdanie rozpoznane jako przeznaczone dla Hermesa. */
data class HermesPolecenie(
    /** Treść dla Hermesa - bez słowa "Hermes" i przedrostków. */
    val tekst: String,
    /** Czy użytkownik wprost wskazał Hermesa (a nie rozpoznaliśmy zadanie sami). */
    val jawne: Boolean
)

/**
 * Które zdania idą do Hermesa.
 *
 * Domyślnie TYLKO te, w których użytkownik wprost woła Hermesa ("Hermes,
 * znajdź…", "zapytaj Hermesa…") albo zaczyna od "na komputerze" z czasownikiem
 * zadania. Tryb automatyczny (opcjonalny) łapie też zadania na plikach - jest
 * wyłączony, bo wolne zadanie agenta nie może przypadkiem zabrać pytania, które
 * szybki model załatwiłby w sekundę.
 */
object HermesRouting {

    // Normalizacja znak w znak - indeksy w znormalizowanym tekście są tymi
    // samymi indeksami w oryginale, więc treść dla Hermesa bierzemy z oryginału.
    private val OGONKI = mapOf(
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l',
        'ń' to 'n', 'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z'
    )

    private fun normalizuj(t: String): String =
        t.lowercase().map { OGONKI[it] ?: it }.joinToString("")

    // Imię w odmianie i w tym, co potrafi z niego zrobić rozpoznawanie mowy.
    private const val IMIĘ = """herm(?:e|ie|i|y)[sz]\w*"""

    private val JAWNE = Regex(
        """^(?:(?:hej|ok|halo)\s+)?(?:(?:zapytaj|spytaj|poprosze|popros|niech|zlec|zleć)\s+)?$IMIĘ\s*[,:.\-]?\s*""" +
            """(?:(?:zeby|aby)\s+)?(?<reszta>.+)$"""
    )

    private val NA_KOMPUTERZE = Regex(
        """^na\s+(?:moim\s+)?(?:komputerze|macu|mac\s+mini|laptopie|pulpicie)\s*[,:]?\s+(?<reszta>.+)$"""
    )

    /** Czasownik zadania na początku (rozkaźnik). */
    private val CZASOWNIK_ZADANIA = Regex(
        """^(?:znajdz|przejrzyj|przeszukaj|otworz|utworz|stworz|zapisz|usun|przenies|skopiuj|uruchom|""" +
            """zainstaluj|zbuduj|zrob|napisz|sprawdz|policz|porownaj|zrefaktoryzuj|przeanalizuj|""" +
            """podsumuj|wyszukaj|pobierz|wyslij|zaktualizuj|przygotuj|wygeneruj|stworz)\b"""
    )

    private val RZECZ_NA_KOMPUTERZE = Regex(
        """\b(?:plik\w*|folder\w*|katalog\w*|repozytori\w*|skrypt\w*|commit\w*|komit\w*|""" +
            """pulpit\w*|dysk\w*|terminal\w*|konsol\w*|na\s+komputerze|na\s+macu)\b"""
    )

    fun rozpoznaj(wypowiedź: String, trybAuto: Boolean = false): HermesPolecenie? {
        val surowa = wypowiedź.trim().trimEnd('.', '!', '?').replace(Regex("""\s+"""), " ")
        if (surowa.isEmpty()) return null
        val t = normalizuj(surowa)
        val zgodneDługości = t.length == surowa.length

        fun reszta(m: MatchResult): String {
            val r = m.groups["reszta"]!!
            return (if (zgodneDługości) surowa.substring(r.range.first) else r.value).trim()
        }

        JAWNE.find(t)?.let { m ->
            val r = reszta(m)
            if (r.isNotBlank()) return HermesPolecenie(r, jawne = true)
        }
        NA_KOMPUTERZE.find(t)?.let { m ->
            val r = reszta(m)
            // "Na komputerze mam problem z…" to zwykłe zdanie, nie zadanie.
            if (r.isNotBlank() && CZASOWNIK_ZADANIA.containsMatchIn(normalizuj(r))) {
                return HermesPolecenie(r, jawne = true)
            }
        }
        if (trybAuto && CZASOWNIK_ZADANIA.containsMatchIn(t) && RZECZ_NA_KOMPUTERZE.containsMatchIn(t)) {
            return HermesPolecenie(surowa, jawne = false)
        }
        return null
    }
}

/** Ramka ze strumienia Hermesa. */
sealed class HermesRamka {
    /** Kawałek odpowiedzi do przeczytania. */
    data class Tekst(val kawałek: String) : HermesRamka()

    /** Hermes zaczyna narzędzie (np. czyta plik) - [opis] może być pusty. */
    data class Narzędzie(val opis: String?) : HermesRamka()

    /** Prośba o zgodę na operację - format nieznany, [początek] to surowe dane. */
    data class Zgoda(val początek: String) : HermesRamka()

    data class Koniec(val powód: String?) : HermesRamka()
    object Gotowe : HermesRamka()
    data class Błąd(val komunikat: String) : HermesRamka()

    /** Ramka, której nie rozumiemy - do dziennika. */
    data class Nieznana(val zdarzenie: String?, val początek: String) : HermesRamka()
}

/** Zdarzenie SSE (po złożeniu linii). */
data class SseZdarzenie(val zdarzenie: String?, val dane: String)

/** Składa linie strumienia SSE w zdarzenia. Komentarze (`: keepalive`) pomija. */
class SseParser {
    private var zdarzenie: String? = null
    private val dane = StringBuilder()
    private var maDane = false

    /** @return zdarzenie, gdy ta linia je zamyka (pusta linia), inaczej `null` */
    fun linia(l: String): SseZdarzenie? {
        if (l.isEmpty()) {
            if (!maDane && zdarzenie == null) return null
            val z = SseZdarzenie(zdarzenie, dane.toString())
            zdarzenie = null
            dane.setLength(0)
            maDane = false
            return z
        }
        if (l.startsWith(":")) return null
        val dwukropek = l.indexOf(':')
        val pole = if (dwukropek < 0) l else l.substring(0, dwukropek)
        var wartość = if (dwukropek < 0) "" else l.substring(dwukropek + 1)
        if (wartość.startsWith(" ")) wartość = wartość.substring(1)
        when (pole) {
            "event" -> zdarzenie = wartość
            "data" -> {
                if (maDane) dane.append('\n')
                dane.append(wartość)
                maDane = true
            }
        }
        return null
    }

    /** Zdarzenie niedomknięte pustą linią przy końcu połączenia. */
    fun reszta(): SseZdarzenie? = linia("")
}

object HermesProtocol {

    const val MODEL = "hermes-agent"
    const val NAGŁÓWEK_KLUCZ_SESJI = "X-Hermes-Session-Key"
    const val DOMYŚLNY_KLUCZ_SESJI = "victor:okulary"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Wskazówka kanału: odpowiedź będzie czytana na głos w okularach. Jako
     * wiadomość systemowa, żeby nie mieszać jej z pytaniem użytkownika.
     */
    const val WSKAZÓWKA_GŁOSOWA =
        "Rozmawiasz z użytkownikiem głosowo, przez jego okulary: Twoja odpowiedź zostanie przeczytana na głos. " +
            "Odpowiadaj po polsku, krótko i naturalnie, bez markdownu, list, tabel i bloków kodu. " +
            "Przy dłuższym zadaniu na początku powiedz jednym zdaniem, co robisz, a na końcu podsumuj w 1-3 zdaniach. " +
            "Nie dyktuj długich wyników ani ścieżek - powiedz, gdzie je zapisałeś."

    /** Jedna wiadomość rozmowy (`user` albo `assistant`). */
    data class Wiadomość(val rola: String, val treść: String)

    fun żądanie(tekst: String, historia: List<Wiadomość> = emptyList(), model: String = MODEL): String =
        buildJsonObject {
            put("model", model)
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", WSKAZÓWKA_GŁOSOWA)
                })
                historia.forEach { w ->
                    add(buildJsonObject {
                        put("role", w.rola)
                        put("content", w.treść)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", tekst)
                })
            })
        }.toString()

    /** Nagłówki zapytania (bez `Content-Type`, które ustawia ciało). */
    fun nagłówki(klucz: String, kluczSesji: String?): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${klucz.filterNot { it.isWhitespace() }}")
        put("Accept", "text/event-stream")
        kluczSesji?.trim()?.takeIf { it.isNotEmpty() }?.let { put(NAGŁÓWEK_KLUCZ_SESJI, it.take(256)) }
    }

    private val KLUCZE_OPISU = listOf("tool", "name", "tool_name", "label", "title", "message", "text")

    /** Początek surowych danych do dziennika - bez znaków nowej linii. */
    fun początek(s: String, max: Int = 400): String = s.replace(Regex("""\s+"""), " ").trim().take(max)

    /** Zamienia zdarzenie SSE na ramkę. */
    fun interpretuj(z: SseZdarzenie): HermesRamka {
        val dane = z.dane.trim()
        if (dane == "[DONE]") return HermesRamka.Gotowe
        val nazwa = z.zdarzenie?.lowercase()
        val element = runCatching { json.parseToJsonElement(dane) }.getOrNull()

        val toZgoda = (nazwa != null && nazwa.contains("approv")) ||
            (nazwa == null && dane.lowercase().contains("approval_request"))
        if (toZgoda) {
            return HermesRamka.Zgoda(początek(dane))
        }
        if (nazwa == "hermes.tool.progress" || nazwa?.startsWith("hermes.tool") == true) {
            return HermesRamka.Narzędzie(opisNarzędzia(element))
        }
        if (element is JsonObject) {
            (element["error"] as? JsonObject)?.let { e ->
                val m = (e["message"] as? JsonPrimitive)?.contentOrNull
                return HermesRamka.Błąd(m ?: "błąd serwera Hermesa")
            }
            val wybór = (element["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            if (wybór != null) {
                val delta = wybór["delta"] as? JsonObject
                val treść = (delta?.get("content") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
                if (!treść.isNullOrEmpty()) return HermesRamka.Tekst(treść)
                val powód = (wybór["finish_reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
                if (powód != null) return HermesRamka.Koniec(powód)
                // Pusta delta (np. sama rola) - nic do powiedzenia, ale to nie jest nieznana ramka.
                return HermesRamka.Tekst("")
            }
        }
        return HermesRamka.Nieznana(z.zdarzenie, początek(dane))
    }

    private fun opisNarzędzia(e: JsonElement?): String? {
        val o = e as? JsonObject ?: return null
        for (k in KLUCZE_OPISU) {
            val v = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            if (!v.isNullOrBlank()) return v.take(80)
        }
        return null
    }

    /**
     * Odpowiedź bez strumienia (serwer zignorował `stream: true`) -
     * `choices[0].message.content`.
     */
    fun odpowiedźBezStrumienia(ciało: String): String? {
        val o = runCatching { json.parseToJsonElement(ciało) }.getOrNull() as? JsonObject ?: return null
        val wybór = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val msg = wybór["message"] as? JsonObject ?: return null
        return (msg["content"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    }

    /** Komunikat błędu z ciała odpowiedzi HTTP (`{"error":{"message":…}}`), jeśli jest. */
    fun komunikatBłędu(ciało: String?): String? {
        val o = runCatching { json.parseToJsonElement(ciało.orEmpty()) }.getOrNull() as? JsonObject ?: return null
        val e = o["error"]
        return when (e) {
            is JsonObject -> (e["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> e.contentOrNull
            else -> null
        }?.take(200)
    }
}

/** Rodzaj porażki zapytania do Hermesa. */
enum class HermesBłąd(val mowa: String) {
    BRAK_SIECI("Nie mogę połączyć się z komputerem z Hermesem. Jest włączony i w tej samej sieci?"),
    CZAS("Hermes nie odpowiedział na czas."),
    KLUCZ("Hermes odrzucił klucz. Sprawdź klucz w ustawieniach."),
    NIE_ZNALEZIONO("Hermes nie odpowiada pod tym adresem. Uruchomiłeś bramę komendą hermes gateway?"),
    SERWER("Hermes zgłosił błąd po swojej stronie."),
    PUSTA("Hermes skończył, ale nic nie odpowiedział."),
    ZERWANE("Połączenie z Hermesem zerwało się w trakcie odpowiedzi.");

    companion object {
        fun zKoduHttp(kod: Int): HermesBłąd = when (kod) {
            401, 403 -> KLUCZ
            404 -> NIE_ZNALEZIONO
            else -> SERWER
        }
    }
}

/**
 * Krótka pamięć rozmowy z Hermesem po stronie aplikacji.
 *
 * Punkt `chat/completions` jest bezstanowy (według dokumentacji), więc
 * dopowiedzenie ("a teraz to samo dla drugiego folderu") musi nieść poprzednie
 * wymiany. Trwała pamięć jest po stronie Hermesa (klucz sesji w nagłówku).
 */
class HermesHistoria(
    private val ważnośćMs: Long = 10 * 60_000L,
    private val maxWiadomości: Int = 8,
    private val maxZnaków: Int = 6_000
) {
    private data class Wpis(val w: HermesProtocol.Wiadomość, val czasMs: Long)

    private val wpisy = ArrayDeque<Wpis>()

    @Synchronized
    fun dodaj(pytanie: String, odpowiedź: String, terazMs: Long) {
        if (odpowiedź.isBlank()) return
        wpisy.addLast(Wpis(HermesProtocol.Wiadomość("user", pytanie), terazMs))
        wpisy.addLast(Wpis(HermesProtocol.Wiadomość("assistant", odpowiedź), terazMs))
        while (wpisy.size > maxWiadomości) wpisy.removeFirst()
    }

    @Synchronized
    fun wiadomości(terazMs: Long): List<HermesProtocol.Wiadomość> {
        while (wpisy.isNotEmpty() && terazMs - wpisy.first().czasMs > ważnośćMs) wpisy.removeFirst()
        // Od końca, dopóki mieszczą się w limicie znaków; para nie może zostać
        // rozdarta (odpowiedź bez pytania mówiłaby modelowi bzdury).
        var suma = 0
        val wynik = ArrayDeque<HermesProtocol.Wiadomość>()
        val lista = wpisy.toList()
        var i = lista.size - 1
        while (i >= 1) {
            val a = lista[i].w
            val u = lista[i - 1].w
            val d = a.treść.length + u.treść.length
            if (suma + d > maxZnaków) break
            suma += d
            wynik.addFirst(a)
            wynik.addFirst(u)
            i -= 2
        }
        return wynik.toList()
    }

    @Synchronized
    fun wyczyść() = wpisy.clear()
}

/**
 * Co mówić, gdy Hermes długo milczy (np. przegląda pliki). Czas podaje wołający.
 */
class HermesCierpliwość(
    private val pierwszyPoMs: Long = 6_000L,
    private val kolejnePoMs: Long = 30_000L,
    private val maxKolejnych: Int = 3
) {
    private var ostatniDźwiękMs = 0L
    private var wypowiedziane = 0
    private var zaczęte = false

    fun start(terazMs: Long) {
        ostatniDźwiękMs = terazMs
        wypowiedziane = 0
        zaczęte = true
    }

    /** Hermes powiedział coś sensownego - licznik ciszy od nowa. */
    fun usłyszano(terazMs: Long) {
        ostatniDźwiękMs = terazMs
        wypowiedziane = 0
    }

    /** @return zdanie do powiedzenia albo `null` */
    fun zapytaj(terazMs: Long): String? {
        if (!zaczęte) return null
        val cisza = terazMs - ostatniDźwiękMs
        val próg = if (wypowiedziane == 0) pierwszyPoMs else kolejnePoMs
        if (cisza < próg) return null
        if (wypowiedziane > maxKolejnych) return null
        val zdanie = if (wypowiedziane == 0) "Chwilę, pracuję nad tym. " else "Nadal pracuję. "
        wypowiedziane++
        ostatniDźwiękMs = terazMs
        return zdanie
    }
}
