package pl.victor.app.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomPoleceniaTest {

    private fun dom(t: String, obszary: List<String> = emptyList()) = DomPolecenia.rozpoznaj(t, obszary)

    @Test
    fun `polecenia dla domu`() {
        listOf(
            "włącz światło w salonie",
            "Zgaś światło",
            "wyłącz lampę w sypialni",
            "zapal lampkę na biurku",
            "podnieś rolety w kuchni",
            "opuść żaluzje",
            "ustaw ogrzewanie na 21 stopni",
            "ustaw temperaturę w salonie na 22",
            "włącz klimatyzację",
            "wyłącz gniazdko z czajnikiem",
            "jaka jest temperatura w sypialni",
            "ile stopni jest w łazience",
            "jaka temperatura w domu",
            "czy światło w kuchni jest włączone",
            "czy drzwi wejściowe są zamknięte",
            "zamknij bramę",
            "włącz odkurzacz"
        ).forEach { t ->
            val p = dom(t)
            assertNotNull("„$t” powinno iść do domu", p)
            assertFalse(t, p!!.jawne)
            assertEquals(t, t.trimEnd('.', '!'), p.tekst)
        }
    }

    @Test
    fun `jawny przedrostek`() {
        val p = dom("Dom, włącz tryb nocny")!!
        assertTrue(p.jawne)
        assertEquals("włącz tryb nocny", p.tekst)
        // "w domu" nie jest już jawnym przedrostkiem (przegląd 22.11) - polecenie
        // nadal idzie do domu przez zwykłe rozpoznanie, ale całym zdaniem.
        assertFalse(dom("w domu zamknij rolety")!!.jawne)
        assertNull(dom("W domu mam jeszcze jakieś leki przeciwbólowe?"))
        assertNull(dom("Dom Chopina - do której otwarty?"))
        assertEquals("Włącz scenę Kino", dom("Home Assistant: Włącz scenę Kino")!!.tekst)
        // "domofon" to nie przedrostek
        assertNull(dom("domofon dzwoni"))
    }

    @Test
    fun `to nie sa polecenia dla domu`() {
        listOf(
            "włącz latarkę",
            "zgaś latarkę",
            "ustaw alarm na siódmą",
            "ustaw alarm na 7:30",
            "jaka jest temperatura",
            "jaka jest temperatura w Krakowie",
            "włącz muzykę",
            "włącz tryb bez kamery",
            "przypomnij mi włączyć światło",
            "zrób zdjęcie",
            "otwórz aplikację Spotify",
            "wyłącz bluetooth",
            "kiedy najbliższy tramwaj",
            "dodaj żarówki do listy zakupów",
            "co to jest lampa naftowa",
            "jak działa termostat",
            // Budzik w telefonie, nie alarm domowy.
            "wyłącz alarm"
        ).forEach { assertNull(it, dom(it)) }
    }

    @Test
    fun `obszary z Home Assistant`() {
        val obszary = listOf("Pokój Zosi", "Gabinet", "Sypialnia")
        assertNotNull(dom("włącz radio w pokoju Zosi", obszary))
        assertNotNull(dom("jaka jest temperatura w gabinecie", obszary))
        // Bez pobranych obszarów "pokoju Zosi" to i tak pomieszczenie ("w pokoju").
        assertTrue(DomPolecenia.maObszar("włącz radio w pokoju zosi", obszary))
        assertFalse(DomPolecenia.maObszar("włącz radio w salonie", listOf("Pokój Zosi")))
    }

    @Test
    fun `potwierdzenie przy zamkach bramach i alarmie`() {
        listOf(
            "otwórz zamek",
            "otwórz drzwi wejściowe",
            "odblokuj drzwi",
            "otwórz bramę",
            "otwórz bramę garażową",
            "otwórz garaż",
            "wyłącz alarm",
            "rozbrój alarm",
            "alarm wyłącz",
            // Przegląd 22.11: te przechodziły bez pytania.
            "podnieś bramę garażową",
            "dom, otwieraj bramę",
            "dom, rozbrój ochronę",
            "uchyl furtkę"
        ).forEach { assertTrue(it, DomPolecenia.wymagaPotwierdzenia(it)) }
        listOf(
            "zamknij drzwi",
            "zamknij bramę",
            "zablokuj zamek",
            "uzbrój alarm",
            "włącz alarm",
            "włącz światło w salonie",
            "otwórz rolety",
            "czy brama jest zamknięta",
            "zgaś światło w garażu",
            "włącz światło w garażu"
        ).forEach { assertFalse(it, DomPolecenia.wymagaPotwierdzenia(it)) }
        assertTrue(dom("otwórz bramę")!!.wymagaPotwierdzenia)
        assertTrue(dom("dom, wyłącz alarm")!!.wymagaPotwierdzenia)
        assertFalse(dom("włącz światło")!!.wymagaPotwierdzenia)
    }
}

class HaAdresTest {

    private fun dobry(a: String) = HaAdres.sprawdź(a) as? HaAdres.Wynik.Dobry

    @Test
    fun `adresy lokalne po http`() {
        assertEquals("http://homeassistant.local:8123", dobry("http://homeassistant.local:8123/")!!.baza)
        assertEquals("http://192.168.1.10:8123", dobry("http://192.168.1.10:8123/api")!!.baza)
        assertEquals("http://10.0.0.5:8123", dobry("10.0.0.5:8123")!!.baza)
        assertEquals("http://homeassistant:8123", dobry("homeassistant:8123")!!.baza)
        assertEquals("http://172.20.1.1", dobry("http://172.20.1.1")!!.baza)
        assertTrue(dobry("http://192.168.1.10:8123")!!.lokalny)
    }

    @Test
    fun `adresy zdalne tylko https`() {
        val nabu = dobry("https://abcdef123.ui.nabu.casa/lovelace/0")!!
        assertEquals("https://abcdef123.ui.nabu.casa", nabu.baza)
        assertFalse(nabu.lokalny)
        // Bez schematu adres zdalny dostaje https.
        assertEquals("https://dom.example.com", dobry("dom.example.com")!!.baza)
        // Jawne http przez internet - odmowa.
        val zły = HaAdres.sprawdź("http://dom.example.com:8123")
        assertTrue(zły is HaAdres.Wynik.Zły)
        assertTrue((zły as HaAdres.Wynik.Zły).powód.contains("HTTPS"))
        assertTrue(HaAdres.sprawdź("http://8.8.8.8") is HaAdres.Wynik.Zły)
        assertTrue(HaAdres.sprawdź("http://172.32.0.1") is HaAdres.Wynik.Zły)
    }

    @Test
    fun `bledne adresy`() {
        assertTrue(HaAdres.sprawdź("") is HaAdres.Wynik.Zły)
        assertTrue(HaAdres.sprawdź("ftp://192.168.1.1") is HaAdres.Wynik.Zły)
        assertTrue(HaAdres.sprawdź("http://") is HaAdres.Wynik.Zły)
    }

    @Test
    fun `co jest lokalne`() {
        listOf("localhost", "127.0.0.1", "192.168.0.2", "10.1.2.3", "172.16.0.1", "172.31.255.255",
            "169.254.1.1", "100.100.1.1", "homeassistant", "ha.lan", "ha.home.arpa", "ha.local", "::1", "fd00::1", "[fe80::1]"
        ).forEach { assertTrue(it, HaAdres.jestLokalny(it)) }
        listOf("8.8.8.8", "172.15.0.1", "192.169.0.1", "abc.ui.nabu.casa", "example.com", "100.128.0.1", "2001:db8::1", "")
            .forEach { assertFalse(it, HaAdres.jestLokalny(it)) }
    }
}

class HaJsonTest {

    @Test
    fun `zadanie conversation API`() {
        val j = Json.parseToJsonElement(HaJson.żądanie("włącz światło w salonie \"duże\"")).jsonObject
        assertEquals("włącz światło w salonie \"duże\"", j["text"]!!.jsonPrimitive.content)
        assertEquals("pl", j["language"]!!.jsonPrimitive.content)
        assertNull(j["conversation_id"])
        val z = Json.parseToJsonElement(HaJson.żądanie("a w kuchni?", rozmowa = "abc123")).jsonObject
        assertEquals("abc123", z["conversation_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `szablon obszarow`() {
        val j = Json.parseToJsonElement(HaJson.żądanieObszarów()).jsonObject
        assertEquals("{{ areas() | map('area_name') | join('\\n') }}", j["template"]!!.jsonPrimitive.content)
        assertEquals(listOf("Salon", "Kuchnia", "Pokój Zosi"), HaJson.obszary("Salon\nKuchnia\n\n Pokój Zosi \nSalon"))
    }

    @Test
    fun `odpowiedz wykonane`() {
        val o = HaJson.rozbierz(
            """
            {"continue_conversation":false,"conversation_id":"01HX",
             "response":{"speech":{"plain":{"speech":"Włączono światło","extra_data":null}},
               "card":{},"language":"pl","response_type":"action_done",
               "data":{"targets":[],"success":[{"name":"Salon","type":"area","id":"salon"}],"failed":[]}}}
            """.trimIndent()
        )!!
        assertEquals("Włączono światło", o.mowa)
        assertEquals("action_done", o.typ)
        assertNull(o.kodBłędu)
        assertEquals("01HX", o.rozmowa)
        assertFalse(o.dalej)
        assertFalse(HaDecyzja.nieZrozumiał(o))
    }

    @Test
    fun `odpowiedz niezrozumiane`() {
        val o = HaJson.rozbierz(
            """{"response":{"speech":{"plain":{"speech":"Przepraszam, nie zrozumiałem"}},"response_type":"error",
               "data":{"code":"no_intent_match"}},"conversation_id":null}"""
        )!!
        assertEquals("error", o.typ)
        assertEquals("no_intent_match", o.kodBłędu)
        assertNull(o.rozmowa)
        assertTrue(HaDecyzja.nieZrozumiał(o))
        // Brak urządzenia to zrozumiany zamiar - mówimy zdanie HA.
        val brak = o.copy(kodBłędu = "no_valid_targets", mowa = "Nie znam obszaru Strych")
        assertFalse(HaDecyzja.nieZrozumiał(brak))
    }

    @Test
    fun `odpowiedz zapytanie i smieci`() {
        val o = HaJson.rozbierz(
            """{"response":{"speech":{"plain":{"speech":"Temperatura w sypialni wynosi 21,5 °C"}},"response_type":"query_answer","data":{}},"continue_conversation":true}"""
        )!!
        assertEquals("query_answer", o.typ)
        assertTrue(o.dalej)
        assertNull(HaJson.rozbierz("<html>401: Unauthorized</html>"))
        assertNull(HaJson.rozbierz("""{"message":"x"}"""))
    }

    @Test
    fun `api dziala`() {
        assertTrue(HaJson.apiDziała("""{"message": "API running."}"""))
        assertFalse(HaJson.apiDziała("""{"message": "Hello"}"""))
        assertFalse(HaJson.apiDziała("401: Unauthorized"))
    }
}

class HaDecyzjaTest {

    private val zrobione = HaJson.Odpowiedź("Włączono światło", "action_done", null, null, false)

    @Test
    fun `powiedz to co HA`() {
        val d = HaDecyzja.z(200, null, zrobione, "http://ha:8123")
        assertEquals(HaDecyzja.Decyzja.Powiedz("Włączono światło", zrozumiał = true), d)
        val bezMowy = HaDecyzja.z(200, null, zrobione.copy(mowa = null), "h") as HaDecyzja.Decyzja.Powiedz
        assertEquals("Zrobione.", bezMowy.mowa)
    }

    @Test
    fun `niezrozumiane idzie do modelu`() {
        val o = HaJson.Odpowiedź("Nie zrozumiałem", "error", "no_intent_match", null, false)
        assertEquals(HaDecyzja.Decyzja.DoModelu("Nie zrozumiałem"), HaDecyzja.z(200, null, o, "h"))
    }

    @Test
    fun `bledy polaczenia maja jasne komunikaty`() {
        fun mowa(d: HaDecyzja.Decyzja) = (d as HaDecyzja.Decyzja.Powiedz).mowa
        assertTrue(mowa(HaDecyzja.z(401, null, null, "h")).contains("token"))
        assertTrue(mowa(HaDecyzja.z(404, null, null, "h")).contains("adres"))
        assertTrue(mowa(HaDecyzja.z(500, null, null, "h")).contains("500"))
        assertTrue(mowa(HaDecyzja.z(null, HaDecyzja.Błąd.BRAK_SIECI, null, "http://ha.local:8123")).contains("sieci domowej"))
        assertTrue(mowa(HaDecyzja.z(null, HaDecyzja.Błąd.CZAS, null, "h")).contains("5 sekund"))
        assertTrue(mowa(HaDecyzja.z(200, null, null, "h")).contains("nie rozumiem"))
        assertFalse((HaDecyzja.z(401, null, null, "h") as HaDecyzja.Decyzja.Powiedz).zrozumiał)
    }

    @Test
    fun `prompt dla modelu bez sekretow`() {
        val p = HaMowa.dlaModelu("włącz tryb kino", "Nie zrozumiałem")
        assertTrue(p.contains("„włącz tryb kino”"))
        assertTrue(p.contains("Nie zrozumiałem"))
        assertFalse(p.contains("Bearer"))
        assertFalse(p.contains("http"))
        assertEquals("Na pewno: otwórz bramę? Powiedz tak albo nie.", HaMowa.pytanieOPotwierdzenie("otwórz bramę."))
    }
}
