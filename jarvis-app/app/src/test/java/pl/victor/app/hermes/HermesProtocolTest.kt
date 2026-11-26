package pl.victor.app.hermes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesAdresTest {

    private fun baza(a: String) = (HermesAdres.sprawdź(a) as HermesAdres.Wynik.Dobry).baza

    @Test
    fun `adres lokalny po http dostaje domyslny port`() {
        assertEquals("http://mac-mini.local:8642", baza("mac-mini.local"))
        assertEquals("http://192.168.1.20:8642", baza("http://192.168.1.20"))
        assertEquals("http://192.168.1.20:9000", baza("http://192.168.1.20:9000/v1/models"))
    }

    @Test
    fun `tailscale po http jest dozwolony`() {
        assertEquals("http://100.101.102.103:8642", baza("100.101.102.103"))
        assertEquals("http://mac.tail1234.ts.net:8642", baza("mac.tail1234.ts.net"))
    }

    @Test
    fun `adres zdalny tylko po https`() {
        assertTrue(HermesAdres.sprawdź("http://example.com:8642") is HermesAdres.Wynik.Zły)
        assertEquals("https://hermes.example.com", baza("https://hermes.example.com"))
        // Bez schematu adres zdalny dostaje https, nie http.
        assertEquals("https://hermes.example.com", baza("hermes.example.com"))
    }

    @Test
    fun `bledne adresy`() {
        assertTrue(HermesAdres.sprawdź("") is HermesAdres.Wynik.Zły)
        assertTrue(HermesAdres.sprawdź("ftp://mac.local") is HermesAdres.Wynik.Zły)
        assertTrue(HermesAdres.sprawdź("http://") is HermesAdres.Wynik.Zły)
    }

    @Test
    fun `dostepnosc zalezy od przelacznika darmowych i konfiguracji`() {
        val ok = "http://mac.local:8642"
        assertEquals(HermesDostępność.MOŻNA, HermesDostępność.z(true, false, ok, "klucz"))
        assertEquals(HermesDostępność.WYŁĄCZONY, HermesDostępność.z(false, false, ok, "klucz"))
        assertEquals(HermesDostępność.TYLKO_DARMOWE, HermesDostępność.z(true, true, ok, "klucz"))
        assertEquals(HermesDostępność.BRAK_KONFIGURACJI, HermesDostępność.z(true, false, "", "klucz"))
        assertEquals(HermesDostępność.BRAK_KONFIGURACJI, HermesDostępność.z(true, false, ok, null))
        assertEquals(HermesDostępność.BRAK_KONFIGURACJI, HermesDostępność.z(true, false, ok, "  "))
        // Wyłączony wygrywa z brakiem konfiguracji: pierwsza przyczyna jest właściwa.
        assertEquals(HermesDostępność.WYŁĄCZONY, HermesDostępność.z(false, true, "", null))
    }
}

class HermesRoutingTest {

    private fun r(t: String, auto: Boolean = false) = HermesRouting.rozpoznaj(t, auto)

    @Test
    fun `jawne wolanie po imieniu`() {
        assertEquals("znajdź plik z fakturą", r("Hermes, znajdź plik z fakturą")!!.tekst)
        assertEquals("sprawdź, czy kopia się zrobiła", r("hej Hermes sprawdź, czy kopia się zrobiła")!!.tekst)
        assertEquals("czy Mac jest włączony", r("zapytaj Hermesa czy Mac jest włączony")!!.tekst)
        assertEquals("zrobił kopię projektu", r("poproś Hermesa żeby zrobił kopię projektu")!!.tekst)
        assertTrue(r("Hermes: policz pliki w folderze Pobrane")!!.jawne)
    }

    @Test
    fun `tresc dla Hermesa zachowuje wielkosc liter i polskie znaki`() {
        assertEquals("znajdź w Dokumentach FAKTURĘ z Października", r("Hermes, znajdź w Dokumentach FAKTURĘ z Października")!!.tekst)
    }

    @Test
    fun `rozpoznawanie mowy pisze imie roznie`() {
        assertEquals("zrób kopię", r("Hermez zrób kopię")!!.tekst)
        assertEquals("zrób kopię", r("zapytaj hermesie zrób kopię")!!.tekst)
    }

    @Test
    fun `na komputerze tylko z czasownikiem zadania`() {
        assertEquals("znajdź plik z fakturą", r("na komputerze znajdź plik z fakturą")!!.tekst)
        assertEquals("zrób kopię", r("Na Macu: zrób kopię")!!.tekst)
        assertNull(r("na komputerze mam problem z internetem"))
    }

    @Test
    fun `zwykle zdania nie ida do Hermesa`() {
        listOf(
            "jaka jest pogoda",
            "zrób zdjęcie",
            "dodaj mleko do listy zakupów",
            "włącz światło w salonie",
            "Hermes",
            "",
            "   "
        ).forEach { assertNull(it, r(it)) }
    }

    @Test
    fun `tryb automatyczny lapie zadania na plikach`() {
        assertNull(r("znajdź plik z fakturą"))
        val p = r("znajdź plik z fakturą", auto = true)!!
        assertFalse(p.jawne)
        assertEquals("znajdź plik z fakturą", p.tekst)
        // Bez rzeczy z komputera albo bez czasownika zadania - nie.
        assertNull(r("zapisz notatkę o zakupach", auto = true))
        assertNull(r("jaki jest największy plik na świecie", auto = true))
    }
}

class SseParserTest {

    private fun zdarzenia(vararg linie: String): List<SseZdarzenie> {
        val p = SseParser()
        return linie.mapNotNull { p.linia(it) }
    }

    @Test
    fun `sklada zdarzenie z linii`() {
        val z = zdarzenia("data: {\"a\":1}", "")
        assertEquals(1, z.size)
        assertNull(z[0].zdarzenie)
        assertEquals("{\"a\":1}", z[0].dane)
    }

    @Test
    fun `nazwa zdarzenia i wiele linii danych`() {
        val z = zdarzenia("event: hermes.tool.progress", "data: linia1", "data: linia2", "")
        assertEquals("hermes.tool.progress", z.single().zdarzenie)
        assertEquals("linia1\nlinia2", z.single().dane)
    }

    @Test
    fun `komentarze i puste linie bez danych sa pomijane`() {
        assertTrue(zdarzenia(": keepalive", "", ": keepalive", "").isEmpty())
    }

    @Test
    fun `brak spacji po dwukropku i niedomkniete zdarzenie na koncu`() {
        val p = SseParser()
        assertNull(p.linia("data:{\"x\":2}"))
        assertEquals("{\"x\":2}", p.reszta()!!.dane)
        assertNull(p.reszta())
    }
}

class HermesInterpretacjaTest {

    private fun ramka(dane: String, zdarzenie: String? = null) =
        HermesProtocol.interpretuj(SseZdarzenie(zdarzenie, dane))

    @Test
    fun `kawalek tekstu`() {
        val r = ramka("""{"choices":[{"index":0,"delta":{"content":"Cześć"},"finish_reason":null}]}""")
        assertEquals(HermesRamka.Tekst("Cześć"), r)
    }

    @Test
    fun `koniec strumienia`() {
        assertEquals(HermesRamka.Gotowe, ramka("[DONE]"))
        assertEquals(
            HermesRamka.Koniec("stop"),
            ramka("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
        )
    }

    @Test
    fun `pusta delta z rola nie jest nieznana`() {
        val r = ramka("""{"choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""")
        assertEquals(HermesRamka.Tekst(""), r)
    }

    @Test
    fun `postep narzedzia z opisem`() {
        val r = ramka("""{"tool":"read_file","status":"started"}""", "hermes.tool.progress")
        assertEquals(HermesRamka.Narzędzie("read_file"), r)
        assertEquals(HermesRamka.Narzędzie(null), ramka("""{"status":"started"}""", "hermes.tool.progress"))
        assertEquals(HermesRamka.Narzędzie(null), ramka("nie json", "hermes.tool.progress"))
    }

    @Test
    fun `blad serwera w strumieniu`() {
        val r = ramka("""{"error":{"message":"model niedostępny","type":"x"}}""")
        assertEquals(HermesRamka.Błąd("model niedostępny"), r)
    }

    @Test
    fun `prosba o zgode jest rozpoznana po slowie approval`() {
        val r = ramka("""{"command":"rm -r build","run_id":"r1"}""", "hermes.approval.request")
        assertTrue(r is HermesRamka.Zgoda)
        assertTrue((r as HermesRamka.Zgoda).początek.contains("rm -r build"))
    }

    @Test
    fun `nieznana ramka trafia do dziennika z poczatkiem`() {
        val r = ramka("""{"cos":"innego"}""", "hermes.cos")
        assertTrue(r is HermesRamka.Nieznana)
        assertEquals("hermes.cos", (r as HermesRamka.Nieznana).zdarzenie)
        assertTrue(r.początek.contains("innego"))
    }

    @Test
    fun `poczatek do dziennika jest krotki i bez nowych linii`() {
        val p = HermesProtocol.początek("a\n\n  b\t" + "x".repeat(1000), 50)
        assertEquals(50, p.length)
        assertFalse(p.contains('\n'))
    }

    @Test
    fun `odpowiedz bez strumienia i komunikat bledu`() {
        assertEquals(
            "Gotowe.",
            HermesProtocol.odpowiedźBezStrumienia("""{"choices":[{"message":{"role":"assistant","content":"Gotowe."}}]}""")
        )
        assertNull(HermesProtocol.odpowiedźBezStrumienia("""{"choices":[]}"""))
        assertNull(HermesProtocol.odpowiedźBezStrumienia("to nie json"))
        assertEquals("zły klucz", HermesProtocol.komunikatBłędu("""{"error":{"message":"zły klucz"}}"""))
        assertEquals("zły klucz", HermesProtocol.komunikatBłędu("""{"error":"zły klucz"}"""))
        assertNull(HermesProtocol.komunikatBłędu("<html>"))
        assertNull(HermesProtocol.komunikatBłędu(null))
    }

    @Test
    fun `kody http na bledy`() {
        assertEquals(HermesBłąd.KLUCZ, HermesBłąd.zKoduHttp(401))
        assertEquals(HermesBłąd.KLUCZ, HermesBłąd.zKoduHttp(403))
        assertEquals(HermesBłąd.NIE_ZNALEZIONO, HermesBłąd.zKoduHttp(404))
        assertEquals(HermesBłąd.SERWER, HermesBłąd.zKoduHttp(500))
        assertEquals(HermesBłąd.SERWER, HermesBłąd.zKoduHttp(429))
    }
}

class HermesZapytanieTest {

    @Test
    fun `zadanie ma strumien wskazowke glosowa historie i pytanie na koncu`() {
        val json = HermesProtocol.żądanie(
            "znajdź fakturę",
            listOf(
                HermesProtocol.Wiadomość("user", "cześć"),
                HermesProtocol.Wiadomość("assistant", "Cześć, w czym pomóc?")
            )
        )
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals("true", o["stream"]!!.jsonPrimitive.content)
        assertEquals(HermesProtocol.MODEL, o["model"]!!.jsonPrimitive.content)
        val m = o["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user"), m.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(HermesProtocol.WSKAZÓWKA_GŁOSOWA, m[0]["content"]!!.jsonPrimitive.content)
        assertEquals("znajdź fakturę", m.last()["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cudzyslowy i nowe linie w pytaniu sa poprawnie zakodowane`() {
        val tekst = "powiedz \"cześć\"\ni zakończ"
        val o = Json.parseToJsonElement(HermesProtocol.żądanie(tekst)).jsonObject
        assertEquals(tekst, o["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `naglowki - klucz bez bialych znakow i klucz sesji`() {
        val n = HermesProtocol.nagłówki(" abc \n", "victor:okulary ")
        assertEquals("Bearer abc", n["Authorization"])
        assertEquals("text/event-stream", n["Accept"])
        assertEquals("victor:okulary", n[HermesProtocol.NAGŁÓWEK_KLUCZ_SESJI])
        assertFalse(HermesProtocol.nagłówki("k", "  ").containsKey(HermesProtocol.NAGŁÓWEK_KLUCZ_SESJI))
        assertFalse(HermesProtocol.nagłówki("k", null).containsKey(HermesProtocol.NAGŁÓWEK_KLUCZ_SESJI))
        assertEquals(256, HermesProtocol.nagłówki("k", "x".repeat(400))[HermesProtocol.NAGŁÓWEK_KLUCZ_SESJI]!!.length)
    }

    @Test
    fun `klucz nie trafia do zadania`() {
        val json = HermesProtocol.żądanie("test")
        assertFalse(json.contains("Bearer"))
        assertNotNull(json)
    }
}

class HermesHistoriaTest {

    @Test
    fun `pamieta pary i wygasa po czasie`() {
        val h = HermesHistoria(ważnośćMs = 1_000)
        h.dodaj("a", "A", terazMs = 0)
        assertEquals(2, h.wiadomości(500).size)
        assertTrue(h.wiadomości(2_000).isEmpty())
    }

    @Test
    fun `limit wiadomosci zostawia najnowsze`() {
        val h = HermesHistoria(maxWiadomości = 4)
        for (i in 1..5) h.dodaj("p$i", "o$i", terazMs = 0)
        assertEquals(listOf("p4", "o4", "p5", "o5"), h.wiadomości(0).map { it.treść })
    }

    @Test
    fun `limit znakow nie rozdziera pary`() {
        val h = HermesHistoria(maxZnaków = 20)
        h.dodaj("x".repeat(15), "y".repeat(15), terazMs = 0)   // 30 znaków - nie mieści się
        h.dodaj("ab", "cd", terazMs = 0)
        val w = h.wiadomości(0)
        assertEquals(listOf("user", "assistant"), w.map { it.rola })
        assertEquals(listOf("ab", "cd"), w.map { it.treść })
    }

    @Test
    fun `pusta odpowiedz nie wchodzi do historii`() {
        val h = HermesHistoria()
        h.dodaj("pytanie", "  ", terazMs = 0)
        assertTrue(h.wiadomości(0).isEmpty())
    }
}

class HermesCierpliwoscTest {

    @Test
    fun `po szesciu sekundach ciszy pada pierwsze zdanie`() {
        val c = HermesCierpliwość()
        c.start(0)
        assertNull(c.zapytaj(5_000))
        assertEquals("Chwilę, pracuję nad tym. ", c.zapytaj(6_000))
        assertNull(c.zapytaj(7_000))
    }

    @Test
    fun `kolejne co trzydziesci sekund i nie w nieskonczonosc`() {
        val c = HermesCierpliwość()
        c.start(0)
        assertNotNull(c.zapytaj(6_000))
        assertNull(c.zapytaj(35_000))
        assertEquals("Nadal pracuję. ", c.zapytaj(36_000))
        assertEquals("Nadal pracuję. ", c.zapytaj(66_000))
        assertEquals("Nadal pracuję. ", c.zapytaj(96_000))
        assertNull(c.zapytaj(200_000))
    }

    @Test
    fun `gdy Hermes mowi cisza liczy sie od nowa`() {
        val c = HermesCierpliwość()
        c.start(0)
        c.usłyszano(5_000)
        assertNull(c.zapytaj(10_000))
        assertNotNull(c.zapytaj(11_000))
    }

    @Test
    fun `przed startem milczy`() {
        assertNull(HermesCierpliwość().zapytaj(100_000))
    }
}

class HermesRunsTest {

    private fun ramka(zdarzenie: String?, dane: String) = HermesRuns.interpretuj(SseZdarzenie(zdarzenie, dane))

    @Test
    fun `start zadania niesie pytanie wskazowke i historie`() {
        val o = Json.parseToJsonElement(
            HermesRuns.żądanieStartu(
                "znajdź fakturę",
                listOf(HermesProtocol.Wiadomość("user", "cześć"), HermesProtocol.Wiadomość("assistant", "Cześć!"))
            )
        ).jsonObject
        assertEquals("znajdź fakturę", o["input"]!!.jsonPrimitive.content)
        assertEquals(HermesProtocol.WSKAZÓWKA_GŁOSOWA, o["instructions"]!!.jsonPrimitive.content)
        val h = o["conversation_history"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant"), h.map { it["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun `bez historii nie ma pola conversation_history`() {
        val o = Json.parseToJsonElement(HermesRuns.żądanieStartu("test")).jsonObject
        assertFalse(o.containsKey("conversation_history"))
    }

    @Test
    fun `identyfikator zadania z odpowiedzi startu`() {
        assertEquals("run_abc123", HermesRuns.runId("""{"run_id":"run_abc123","status":"started"}"""))
        assertNull(HermesRuns.runId("""{"status":"started"}"""))
        assertNull(HermesRuns.runId("<html>"))
        assertNull(HermesRuns.runId(null))
    }

    @Test
    fun `zgoda to once albo deny z identyfikatorem i generacja`() {
        val tak = Json.parseToJsonElement(HermesRuns.żądanieZgody("p1", 3, true)).jsonObject
        assertEquals("p1", tak["request_id"]!!.jsonPrimitive.content)
        assertEquals("3", tak["execution_generation"]!!.jsonPrimitive.content)
        assertEquals("once", tak["choice"]!!.jsonPrimitive.content)
        val nie = Json.parseToJsonElement(HermesRuns.żądanieZgody("p1", null, false)).jsonObject
        assertEquals("deny", nie["choice"]!!.jsonPrimitive.content)
        assertFalse(nie.containsKey("execution_generation"))
    }

    @Test
    fun `zgoda glosem nigdy nie jest na stale`() {
        // "session" i "always" nie mogą paść z jednego "tak" w okularach.
        val json = HermesRuns.żądanieZgody("p1", 1, true)
        assertFalse(json.contains("session"))
        assertFalse(json.contains("always"))
    }

    @Test
    fun `tekst z delt i pomijanie pustych`() {
        assertEquals(HermesRamka.Tekst("Cześć"), ramka("message.delta", """{"delta":"Cześć"}"""))
        assertEquals(HermesRamka.Tekst("Hej"), ramka("message.delta", """{"text":"Hej"}"""))
        assertEquals(HermesRamka.Tekst("Zagnieżdżone"), ramka("message.delta", """{"delta":{"content":"Zagnieżdżone"}}"""))
        assertEquals(HermesRamka.Tekst("zwykły tekst"), ramka("message.delta", "zwykły tekst"))
    }

    @Test
    fun `nazwa zdarzenia moze byc w json zamiast w linii event`() {
        assertEquals(HermesRamka.Tekst("A"), ramka(null, """{"type":"message.delta","delta":"A"}"""))
    }

    @Test
    fun `komentarz posredni tylko gdy nie poszedl deltami`() {
        assertEquals(HermesRamka.Tekst("Sprawdzę pliki. "), ramka("message.interim", """{"text":"Sprawdzę pliki.","already_streamed":false}"""))
        assertEquals(HermesRamka.Tekst(""), ramka("message.interim", """{"text":"Sprawdzę pliki.","already_streamed":true}"""))
    }

    @Test
    fun `narzedzia start i koniec`() {
        assertEquals(
            HermesRamka.Narzędzie("read_file: /tmp/a", false),
            ramka("tool.started", """{"tool":"read_file","preview":"/tmp/a"}""")
        )
        val k = ramka("tool.completed", """{"tool":"read_file","duration":0.2,"error":false}""")
        assertTrue(k is HermesRamka.Narzędzie && k.zakończone)
    }

    @Test
    fun `koniec zadania z tekstem i bez`() {
        assertEquals(HermesRamka.Koniec("completed", "Gotowe."), ramka("run.completed", """{"output":"Gotowe.","usage":{}}"""))
        assertEquals(HermesRamka.Koniec("completed", null), ramka("run.completed", """{"completed":true}"""))
        assertEquals(HermesRamka.Koniec("cancelled"), ramka("run.cancelled", "{}"))
        assertEquals(HermesRamka.Koniec("interrupted"), ramka("run.interrupted", "{}"))
        assertEquals(HermesRamka.Błąd("brak miejsca"), ramka("run.failed", """{"error":"brak miejsca"}"""))
        assertTrue(ramka("run.failed", "{}") is HermesRamka.Błąd)
    }

    @Test
    fun `prosba o zgode - pola z kilku mozliwych nazw`() {
        val z = ramka(
            "approval.request",
            """{"prompt_id":"pr-9","execution_generation":3,"command":"rm -r build","description":"usuwa katalog"}"""
        ) as HermesRamka.Zgoda
        assertEquals("pr-9", z.requestId)
        assertEquals(3, z.generacja)
        assertEquals("rm -r build", z.polecenie)
        assertEquals("usuwa katalog", z.opis)
    }

    @Test
    fun `prosba o zgode - pola w zagniezdzonym obiekcie`() {
        val z = ramka(
            "approval.request",
            """{"control":{"request_id":"r7","execution_generation":"2","command":"git push"}}"""
        ) as HermesRamka.Zgoda
        assertEquals("r7", z.requestId)
        assertEquals(2, z.generacja)
        assertEquals("git push", z.polecenie)
    }

    @Test
    fun `prosba o zgode bez znanych pol nadal jest zgoda z poczatkiem do dziennika`() {
        val z = ramka("approval.request", """{"cos":"innego"}""") as HermesRamka.Zgoda
        assertNull(z.requestId)
        assertNull(z.polecenie)
        assertTrue(z.początek.contains("innego"))
    }

    @Test
    fun `oczekujace zgody z pending_controls`() {
        val z = HermesRuns.oczekująceZgody(
            """{"status":"waiting_for_approval","pending_controls":[{"prompt_id":"pr-1","execution_generation":4,"command":"sudo ls"}]}"""
        )
        assertEquals(1, z.size)
        assertEquals("pr-1", z[0].requestId)
        assertEquals(4, z[0].generacja)
        assertTrue(HermesRuns.oczekująceZgody("""{"status":"running"}""").isEmpty())
        assertTrue(HermesRuns.oczekująceZgody("nie json").isEmpty())
    }

    @Test
    fun `stan zadania - status i output`() {
        val (s, o) = HermesRuns.stanRuna("""{"object":"hermes.run","run_id":"r","status":"completed","output":"Done."}""")
        assertEquals("completed", s)
        assertEquals("Done.", o)
        assertEquals(null to null, HermesRuns.stanRuna(null))
    }

    @Test
    fun `nieznane zdarzenie idzie do dziennika a znane ciche nie`() {
        assertTrue(ramka("hermes.cos", """{"a":1}""") is HermesRamka.Nieznana)
        assertEquals(HermesRamka.Tekst(""), ramka("subagent.start", "{}"))
        assertEquals(HermesRamka.Tekst(""), ramka("run.steered", "{}"))
    }
}

class HermesMowaTest {

    @Test
    fun `pytanie o zgode czyta polecenie`() {
        val z = HermesRamka.Zgoda("x", polecenie = "rm -r build")
        assertEquals(
            "Hermes prosi o zgodę na wykonanie polecenia: rm -r build. Zezwolić? Powiedz tak albo nie.",
            HermesMowa.pytanieOZgodę(z)
        )
        assertTrue(HermesMowa.pytanieOZgodę(z, powtórka = true).startsWith("Czekam na decyzję"))
    }

    @Test
    fun `bez polecenia opis a bez opisu uczciwie ze nie wiadomo`() {
        assertEquals("usuwa katalog", HermesMowa.opisZgody(HermesRamka.Zgoda("x", opis = "usuwa katalog")))
        assertEquals("operację, której opisu nie podał", HermesMowa.opisZgody(HermesRamka.Zgoda("x")))
    }

    @Test
    fun `dlugie polecenie jest skracane do jednej linii`() {
        val z = HermesRamka.Zgoda("x", polecenie = "a\n\nb " + "c".repeat(400))
        val opis = HermesMowa.opisZgody(z)
        assertFalse(opis.contains('\n'))
        assertTrue(opis.endsWith("i dalej"))
        assertTrue(opis.length < 200)
    }

    @Test
    fun `kody http na wynik zgody`() {
        assertEquals(WynikZgody.OK, WynikZgody.zKoduHttp(200))
        assertEquals(WynikZgody.OK, WynikZgody.zKoduHttp(204))
        assertEquals(WynikZgody.WYGASŁA, WynikZgody.zKoduHttp(409))
        assertEquals(WynikZgody.BRAK_ZADANIA, WynikZgody.zKoduHttp(404))
        assertEquals(WynikZgody.KLUCZ, WynikZgody.zKoduHttp(401))
        assertEquals(WynikZgody.SIEĆ, WynikZgody.zKoduHttp(null))
        assertEquals(WynikZgody.INNE, WynikZgody.zKoduHttp(500))
    }
}
