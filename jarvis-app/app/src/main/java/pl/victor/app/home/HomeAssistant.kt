package pl.victor.app.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Sterowanie domem przez Home Assistant - czysta logika, bez Androida.
 *
 * ## Po co
 * Home Assistant to serwer użytkownika (u niego w domu albo przez Nabu Casa),
 * więc sterowanie nie kosztuje nic. HA ma własne rozumienie poleceń po polsku
 * (`POST /api/conversation/process`): "włącz światło w salonie", "jaka jest
 * temperatura w sypialni" - sam znajduje urządzenie i sam odpowiada zdaniem.
 * Nie musimy więc znać listy urządzeń ani uczyć modelu znaczników - wystarczy
 * rozpoznać, że zdanie jest DLA DOMU, oddać je HA i powiedzieć jego odpowiedź.
 *
 * Sieć jest w [HomeAssistantClient], decyzje i teksty tutaj (z testami).
 */
object DomPolecenia {

    data class Polecenie(
        /** Tekst dla HA - bez przedrostka "dom,". */
        val tekst: String,
        /** Czy padł jawny przedrostek ("dom, …", "w domu …"). */
        val jawne: Boolean,
        /** Zamek, brama, alarm - najpierw "na pewno?". */
        val wymagaPotwierdzenia: Boolean
    )

    private val PL = Locale("pl")

    /** "dom, włącz…", "w domu zgaś…", "home assistant: …". */
    private val PRZEDROSTEK = Regex(
        """^(?:hej\s+|ok\s+)?(?:dom|w\s+domu|home\s+assistant|asystent\s+domowy|inteligentny\s+dom)\s*[,:.\-–]?\s+(.+)$"""
    )

    /** Czasowniki sterowania - z bezokolicznikiem i trybem rozkazującym. */
    private val CZASOWNIK = Regex(
        """(^|\s)(w[lł][aą]cz|wy[lł][aą]cz|zga[sś]|zgasi|zapal|podnie[sś]|podnio|opu[sś][cć]|opu[sś]ci|zas[lł]o[nń]|ods[lł]o[nń]|""" +
            """otw[oó]rz|otworzy|zamknij|zamkn|zablokuj|odblokuj|ustaw|podkr[eę][cć]|skr[eę][cć]|przyciemnij|""" +
            """rozja[sś]nij|zmniejsz|zwi[eę]ksz|podgrzej|sch[lł]od[zź]|uzbr[oó]j|rozbr[oó]j|przyciemni|rozja[sś]ni)\S*"""
    )

    /** Urządzenia domowe - rdzenie, żeby łapały odmianę. */
    private val URZĄDZENIE = Regex(
        """(^|\s)(?:[sś]wiat[lł]|[sś]wiec|lamp|o[sś]wietleni|[zż]ar[oó]wk|led(y|ów|ach)?(\s|$)|ta[sś]m\S*\s+led|""" +
            """ogrzewani|grzejnik|kaloryfer|termostat|klimatyzac|klimatyzator|klim(a|ę)(\s|$)|wentylator|rekuperac|""" +
            """rolet|[zż]aluzj|zas[lł]on|markiz|gniazd|wtyczk|odkurzacz|oczyszczacz|nawil[zż]acz|bojler|""" +
            """bram|furtk|zamek|zamk[aiu]|drzwi|alarm|podlewani|zraszacz)"""
    )

    /** Pomieszczenia, o które się pyta - "w salonie", "na tarasie". */
    private val POMIESZCZENIE = Regex(
        """(^|\s)(?:w\s+(?:salonie|kuchni|sypialni|[lł]azience|przedpokoju|korytarzu|gara[zż]u|pokoju|gabinecie|""" +
            """jadalni|piwnicy|biurze|pralni|spi[zż]arni|garderobie|[lł]azienkach)|""" +
            """na\s+(?:tarasie|balkonie|werandzie|podw[oó]rku|poddaszu|strychu|ganku|piętrze|pi[eę]trze|parterze))(\s|$)"""
    )

    /** "jaka jest temperatura w salonie", "ile stopni w sypialni". */
    private val STAN_POMIARU = Regex(
        """(temperatur|stopni|wilgotno|ciep[lł]o\s+jest|zimno\s+jest)"""
    )

    /** "w domu", "w mieszkaniu" na końcu albo w środku zdania. */
    private val W_DOMU = Regex("""(^|\s)w\s+(domu|mieszkaniu)(\s|$)""")

    /** "czy światło w kuchni jest włączone", "czy drzwi są zamknięte". */
    private val CZY_STAN = Regex(
        """(^|\s)czy\s.*(w[lł][aą]czon|wy[lł][aą]czon|zapalon|zgaszon|otwart|zamkni[eę]t|zablokowan|odblokowan|uzbrojon|rozbrojon|zasuni[eę]t|podniesion|opuszczon)"""
    )

    /**
     * Rzeczy, które WYGLĄDAJĄ na dom, a nim nie są: latarka w telefonie,
     * budzik ("ustaw alarm na siódmą"), ustawienia aplikacji, nawigacja.
     */
    private val NIE_DOM = Regex(
        """(latark|budzik|minutnik|alarm\s+na\s+\d|alarm\s+na\s+(sz[oó]st|si[oó]dm|[oó]sm|dziewi[aą]t|dziesi[aą]t|godzin)|""" +
            """ustaw\s+alarm\s+na|bluetooth|wi-?fi|tryb\S*\s|nawigacj|t[lł]umacz|aparat|zdj[eę]ci|muzyk|piosenk|""" +
            """przypomnij|przypomnienie|notatk|list[eęy]\s|kalendarz|wiadomo[sś]|sms|mail|aplikacj)"""
    )

    /**
     * Zamek, drzwi, brama, alarm - otwarcie albo rozbrojenie to coś, czego
     * nie wolno zrobić z przesłyszenia. Zamknięcie i uzbrojenie są bezpieczne.
     */
    private val POTWIERDZENIE = listOf(
        Regex("""(otw[oó]rz|otworzy|odblokuj|odbezpiecz|odryglu)\S*\s+.*?(zam(ek|ki|ka|ku|kn)|drzwi|bram\S*|furtk\S*|gara[zż]\S*|wej[sś]ci\S*|sejf)"""),
        Regex("""(wy[lł][aą]cz|rozbr[oó]j|dezaktywuj|deaktywuj)\S*\s+.*?alarm"""),
        Regex("""alarm\S*\s+.*?(wy[lł][aą]cz|rozbr[oó]j|dezaktywuj)""")
    )

    /**
     * @param obszary nazwy obszarów z HA ("Salon", "Pokój Zosi") - łapią
     *   zdania z pomieszczeniem spoza naszej listy
     * @return polecenie dla HA albo `null`, gdy zdanie nie dotyczy domu
     */
    fun rozpoznaj(wypowiedź: String, obszary: List<String> = emptyList()): Polecenie? {
        val surowa = wypowiedź.trim().trimEnd('.', '!').replace(Regex("""\s+"""), " ")
        val q = surowa.lowercase(PL)
        if (q.isEmpty()) return null
        PRZEDROSTEK.find(q)?.let { m ->
            // Ta sama długość po zmianie wielkości liter - tekst dla HA bierzemy
            // z oryginału, żeby nazwy ("Zosi") zostały, jak padły.
            val reszta = if (q.length == surowa.length) surowa.substring(m.groups[1]!!.range.first).trim()
            else m.groupValues[1].trim()
            if (reszta.isNotBlank()) {
                return Polecenie(reszta, jawne = true, wymagaPotwierdzenia = wymagaPotwierdzenia(reszta))
            }
        }
        if (NIE_DOM.containsMatchIn(q)) return null
        val miejsce = POMIESZCZENIE.containsMatchIn(q) || maObszar(q, obszary)
        val urządzenie = URZĄDZENIE.containsMatchIn(q)
        val sterowanie = CZASOWNIK.containsMatchIn(q) && (urządzenie || miejsce)
        val pomiar = STAN_POMIARU.containsMatchIn(q) && (miejsce || W_DOMU.containsMatchIn(q))
        val czyStan = CZY_STAN.containsMatchIn(q) && urządzenie
        if (!sterowanie && !pomiar && !czyStan) return null
        return Polecenie(surowa, jawne = false, wymagaPotwierdzenia = wymagaPotwierdzenia(surowa))
    }

    fun wymagaPotwierdzenia(tekst: String): Boolean {
        val q = tekst.lowercase(PL)
        if (POTWIERDZENIE.any { it.containsMatchIn(q) }) return true
        // Odwrotnie niż lista czasowników powyżej: urządzenie wrażliwe i NIE
        // wyraźnie bezpieczny czasownik. Lista "otwórz/odblokuj" przepuszczała
        // "podnieś bramę garażową", "otwieraj bramę", "rozbrój ochronę" -
        // a HA po polsku rozumie je jako otwarcie (przegląd 22.11).
        return WRAŻLIWE.containsMatchIn(q) &&
            !BEZPIECZNE.containsMatchIn(q) &&
            !PYTANIE_O_STAN.containsMatchIn(q) &&
            !ŚWIATŁO.containsMatchIn(q)
    }

    private val WRAŻLIWE = Regex("""zam(ek|ki|ka|ku|kn)|drzwi|bram|furtk|gara[zż]|sejf|alarm|ochron""")

    /** Zamknięcie, zablokowanie, uzbrojenie - bezpieczne bez pytania. */
    private val BEZPIECZNE = Regex("""\b(zamknij|zamkn[aą][cć]|zablokuj|zarygluj|uzbr[oó]j|w[lł][aą]cz\s+(alarm|ochron))""")

    /** "Czy brama jest zamknięta?" - pytanie, nie polecenie. */
    private val PYTANIE_O_STAN = Regex("""^\s*(czy|jaki|jaka|jakie|sprawd[zź])\b|\bstan\b|\bstatus""")

    /** "Zgaś światło w garażu" to światło, nie brama. */
    private val ŚWIATŁO = Regex("""[sś]wiat[lł]|lamp|o[sś]wietl""")

    /**
     * Czy w zdaniu pada obszar z HA. Porównujemy rdzenie słów, bo obszar
     * nazywa się "Sypialnia", a mówi się "w sypialni".
     */
    fun maObszar(q: String, obszary: List<String>): Boolean {
        // "Pokój" i "w pokoju" - ó wymienia się z o w odmianie.
        val zdanie = q.lowercase(PL).replace('ó', 'o')
        return obszary.any { obszar ->
            val rdzenie = obszar.lowercase(PL).replace('ó', 'o').split(Regex("""\s+"""))
                .filter { it.length >= 3 }
                .map { it.take(maxOf(4, it.length - 2)) }
            rdzenie.isNotEmpty() && rdzenie.all { r -> Regex("""(^|\s)${Regex.escape(r)}""").containsMatchIn(zdanie) }
        }
    }
}

/** Adres serwera HA: poprawność, a HTTP tylko w sieci lokalnej. */
object HaAdres {

    sealed class Wynik {
        /** Adres bazowy bez ukośnika na końcu, np. `http://192.168.1.10:8123`. */
        data class Dobry(val baza: String, val lokalny: Boolean) : Wynik()
        data class Zły(val powód: String) : Wynik()
    }

    /**
     * Sprawdza i porządkuje adres wpisany przez użytkownika.
     *
     * ## Czemu HTTP tylko lokalnie
     * Token HA daje pełną władzę nad domem (zamki, alarm). Przez zwykłe HTTP
     * idzie jawnym tekstem - w domowej sieci to akceptowalne (tak łączy się
     * sama aplikacja HA), ale przez internet już nie. Adres zdalny musi być
     * HTTPS (Nabu Casa daje go od ręki).
     */
    fun sprawdź(wpisany: String): Wynik {
        var a = wpisany.trim()
        if (a.isEmpty()) return Wynik.Zły("Wpisz adres serwera Home Assistant.")
        if (!a.contains("://")) {
            val host = a.substringBefore('/').substringBefore(':')
            a = (if (jestLokalny(host)) "http://" else "https://") + a
        }
        val uri = runCatching { java.net.URI(a) }.getOrNull()
            ?: return Wynik.Zły("To nie wygląda na adres - przykład: http://homeassistant.local:8123")
        val schemat = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
            ?: return Wynik.Zły("W adresie brakuje nazwy serwera - przykład: http://homeassistant.local:8123")
        if (schemat != "http" && schemat != "https") {
            return Wynik.Zły("Adres musi zaczynać się od http:// albo https://")
        }
        val lokalny = jestLokalny(host)
        if (schemat == "http" && !lokalny) {
            return Wynik.Zły(
                "Przez internet tylko HTTPS - token szedłby jawnym tekstem. Użyj adresu https:// " +
                    "(np. Nabu Casa) albo adresu z sieci domowej."
            )
        }
        // Wklejony adres z "/api" albo ze ścieżką strony ("/lovelace/0") - bazą
        // jest sam serwer.
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val hostWAdresie = if (host.contains(':')) "[$host]" else host
        return Wynik.Dobry("$schemat://$hostWAdresie$port", lokalny)
    }

    /**
     * Adres w sieci lokalnej: zakresy prywatne IPv4/IPv6, localhost, nazwy
     * .local/.lan/.home.arpa i nazwy bez kropki ("homeassistant").
     */
    fun jestLokalny(hostSurowy: String): Boolean {
        val host = hostSurowy.lowercase().trim().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        val ipv4 = host.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: -1 }
        if (ipv4 != null && ipv4.all { it in 0..255 }) {
            val (a, b) = ipv4
            return a == 10 || a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                // 100.64.0.0/10 - sieć Tailscale/CGNAT; Tailscale szyfruje sam.
                (a == 100 && b in 64..127)
        }
        if (host.contains(':')) {
            return host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
        }
        if (!host.contains('.')) return true
        return LOKALNE_DOMENY.any { host.endsWith(it) }
    }

    private val LOKALNE_DOMENY = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".fritz.box")
}

/** JSON dla API HA - przez kotlinx.serialization, bez org.json. */
object HaJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Ciało `POST /api/conversation/process`. */
    fun żądanie(tekst: String, język: String = "pl", rozmowa: String? = null): String =
        buildJsonObject {
            put("text", tekst)
            put("language", język)
            if (rozmowa != null) put("conversation_id", rozmowa)
        }.toString()

    /** Ciało `POST /api/template` - nazwy obszarów, każda w osobnej linii. */
    fun żądanieObszarów(): String =
        buildJsonObject { put("template", "{{ areas() | map('area_name') | join('\\n') }}") }.toString()

    data class Odpowiedź(
        /** Zdanie od HA do powiedzenia albo `null`. */
        val mowa: String?,
        /** `action_done`, `query_answer` albo `error`. */
        val typ: String?,
        /** Kod błędu HA (`no_intent_match`, `no_valid_targets`, …) albo `null`. */
        val kodBłędu: String?,
        val rozmowa: String?,
        val dalej: Boolean
    )

    private fun JsonObject.s(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject

    /** Rozbiór odpowiedzi conversation API; `null` przy nie-JSON-ie. */
    fun rozbierz(tekst: String): Odpowiedź? {
        val root = runCatching { json.parseToJsonElement(tekst).jsonObject }.getOrNull() ?: return null
        val r = root.o("response") ?: return null
        val mowa = r.o("speech")?.o("plain")?.s("speech")
            ?: r.o("speech")?.o("ssml")?.s("speech")
        return Odpowiedź(
            mowa = mowa,
            typ = r.s("response_type"),
            kodBłędu = r.o("data")?.s("code"),
            rozmowa = root.s("conversation_id"),
            dalej = (root["continue_conversation"] as? JsonPrimitive)?.booleanOrNull ?: false
        )
    }

    /** `GET /api/` odpowiada `{"message": "API running."}`. */
    fun apiDziała(tekst: String): Boolean =
        runCatching { json.parseToJsonElement(tekst).jsonObject.s("message") }.getOrNull()
            ?.equals("API running.", ignoreCase = true) == true

    /** Nazwy obszarów z wyniku szablonu - po jednej w linii. */
    fun obszary(tekst: String): List<String> =
        tekst.lines().map { it.trim() }.filter { it.isNotEmpty() && it.length <= 40 }.distinct().take(60)
}

/** Co zrobić z wynikiem połączenia - czysta decyzja, z testami. */
object HaDecyzja {

    enum class Błąd { BRAK_SIECI, CZAS, INNY }

    sealed class Decyzja {
        /** Powiedz to użytkownikowi i koniec. */
        data class Powiedz(val mowa: String, val zrozumiał: Boolean) : Decyzja()

        /** HA nie zrozumiał - pytanie idzie do zwykłego modelu. */
        data class DoModelu(val odpowiedźHa: String?) : Decyzja()
    }

    /**
     * @param kodHttp kod odpowiedzi albo `null`, gdy połączenia nie było
     * @param błąd rodzaj błędu połączenia, gdy `kodHttp == null`
     * @param odpowiedź rozebrane ciało przy kodzie 200
     * @param host sam serwer (bez tokenu) - do komunikatu "nie łączę się z…"
     */
    fun z(kodHttp: Int?, błąd: Błąd?, odpowiedź: HaJson.Odpowiedź?, host: String): Decyzja = when {
        kodHttp == null -> Decyzja.Powiedz(
            when (błąd) {
                Błąd.CZAS -> "Serwer domu nie odpowiedział w 5 sekund. Sprawdź, czy Home Assistant działa."
                else -> "Nie łączę się z serwerem domu ($host). Jesteś w sieci domowej? Spoza domu " +
                    "potrzebny jest adres zdalny, np. Nabu Casa."
            },
            zrozumiał = false
        )
        kodHttp == 401 || kodHttp == 403 -> Decyzja.Powiedz(
            "Serwer domu odrzucił token. Utwórz nowy token długoterminowy w profilu Home Assistant " +
                "i wklej go w ustawieniach.",
            zrozumiał = false
        )
        kodHttp == 404 -> Decyzja.Powiedz(
            "Serwer pod tym adresem nie ma interfejsu poleceń Home Assistant. Sprawdź adres w ustawieniach.",
            zrozumiał = false
        )
        kodHttp !in 200..299 -> Decyzja.Powiedz("Serwer domu zgłosił błąd $kodHttp.", zrozumiał = false)
        odpowiedź == null -> Decyzja.Powiedz("Serwer domu odpowiedział czymś, czego nie rozumiem.", zrozumiał = false)
        nieZrozumiał(odpowiedź) -> Decyzja.DoModelu(odpowiedź.mowa)
        else -> Decyzja.Powiedz(
            odpowiedź.mowa ?: if (odpowiedź.typ == "error") "Dom nie wykonał polecenia." else "Zrobione.",
            zrozumiał = odpowiedź.typ != "error"
        )
    }

    /**
     * HA nie rozpoznał zamiaru. "Brak takiego urządzenia" (`no_valid_targets`)
     * to co innego - zamiar zrozumiał i jego zdanie mówi, czego nie znalazł,
     * więc je przekazujemy, a nie pytamy modelu.
     */
    fun nieZrozumiał(o: HaJson.Odpowiedź): Boolean =
        o.typ == "error" && (o.kodBłędu == null || o.kodBłędu == "no_intent_match")
}

/** Teksty dla użytkownika i modelu. */
object HaMowa {

    const val WYŁĄCZONE = "Sterowanie domem jest wyłączone. Włączysz je w ustawieniach, w sekcji Dom."

    const val BRAK_KONFIGURACJI = "Sterowanie domem jest włączone, ale brakuje adresu serwera albo tokenu " +
        "Home Assistant. Uzupełnij je w ustawieniach, w sekcji Dom."

    fun pytanieOPotwierdzenie(polecenie: String): String =
        "Na pewno: ${polecenie.trim().trimEnd('.', '!', '?')}? Powiedz tak albo nie."

    /**
     * Polecenie dla modelu, gdy HA nie zrozumiał. Bez tokenu i adresu -
     * tylko zdanie użytkownika i to, co odpowiedział dom.
     */
    fun dlaModelu(pytanie: String, odpowiedźHa: String?): String = buildString {
        append("Użytkownik powiedział: „").append(pytanie.trim()).append("”.\n")
        append("Wyglądało to na polecenie dla inteligentnego domu, ale Home Assistant go nie rozpoznał")
        odpowiedźHa?.let { append(" (odpowiedział: „").append(it).append("”)") }
        append(". Odpowiedz na to zdanie jak na zwykłe pytanie. Jeśli to jednak było polecenie dla ")
        append("domu, powiedz krótko, że dom go nie zrozumiał, i podpowiedz prostsze sformułowanie z ")
        append("nazwą pokoju, np. „włącz światło w salonie”. Nie twierdź, że cokolwiek włączyłeś.")
    }
}
