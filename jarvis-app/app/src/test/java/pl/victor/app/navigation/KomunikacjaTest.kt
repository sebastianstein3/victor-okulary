package pl.victor.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KomunikacjaTest {

    // === Adresy Map ===

    @Test
    fun `plan podrozy komunikacja w Mapach`() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=Dworzec%20G%C5%82%C3%B3wny&travelmode=transit",
            KomunikacjaLinki.planPodróży("Dworzec Główny")
        )
        // Znaki, które rozbiłyby adres, są zakodowane.
        assertTrue(KomunikacjaLinki.planPodróży("A&B?").contains("destination=A%26B%3F&"))
    }

    @Test
    fun `zapytanie o przystanek w Mapach z miastem`() {
        assertEquals("przystanek Rondo Mogilskie, Kraków", KomunikacjaLinki.przystanekDoMap("Rondo Mogilskie", "Kraków"))
        assertEquals("przystanek Lubicz", KomunikacjaLinki.przystanekDoMap("Lubicz", null))
    }

    // === Rozpoznanie pytania o odjazdy ===

    @Test
    fun `pytania o odjazdy`() {
        val tramwaj = OdjazdyPytanie.rozpoznaj("Kiedy najbliższy tramwaj?")!!
        assertEquals(Pojazd.TRAMWAJ, tramwaj.pojazd)
        assertNull(tramwaj.przystanek)

        assertEquals(Pojazd.AUTOBUS, OdjazdyPytanie.rozpoznaj("o której jest następny autobus")!!.pojazd)
        assertEquals(Pojazd.AUTOBUS, OdjazdyPytanie.rozpoznaj("za ile przyjedzie autobus")!!.pojazd)
        assertEquals(Pojazd.TRAMWAJ, OdjazdyPytanie.rozpoznaj("kiedy mam tramwaj stąd")!!.pojazd)
        assertEquals(Pojazd.METRO, OdjazdyPytanie.rozpoznaj("kiedy jest metro")!!.pojazd)
        assertNull(OdjazdyPytanie.rozpoznaj("jakie linie stąd jeżdżą")!!.pojazd)
        assertEquals(Pojazd.TRAMWAJ, OdjazdyPytanie.rozpoznaj("jakie tramwaje tu jeżdżą")!!.pojazd)
        assertNotNull(OdjazdyPytanie.rozpoznaj("rozkład jazdy z tego przystanku"))
        // Przegląd 22.11: własny plan i historia to nie przystanki wokół.
        assertNull(OdjazdyPytanie.rozpoznaj("o której mam pociąg"))
        assertNull(OdjazdyPytanie.rozpoznaj("kiedy mam jutro pociąg"))
        assertNull(OdjazdyPytanie.rozpoznaj("kiedy otwarto metro w Warszawie"))
        assertNull(OdjazdyPytanie.rozpoznaj("kiedy wynaleziono tramwaj"))
    }

    @Test
    fun `nazwa przystanku z pytania`() {
        assertEquals(
            "rondo mogilskie",
            OdjazdyPytanie.rozpoznaj("kiedy najbliższy tramwaj z przystanku Rondo Mogilskie")!!.przystanek
        )
        assertEquals(
            "ronda mogilskiego",
            OdjazdyPytanie.rozpoznaj("kiedy najbliższy tramwaj z Ronda Mogilskiego?")!!.przystanek
        )
        // Kierunek nie jest częścią nazwy.
        assertEquals(
            "teatr bagatela",
            OdjazdyPytanie.rozpoznaj("o której odjeżdża autobus z przystanku Teatr Bagatela w stronę centrum")!!.przystanek
        )
        // "Z tego przystanku" i "stąd" to tutaj.
        assertNull(OdjazdyPytanie.rozpoznaj("jakie autobusy jeżdżą z tego przystanku")!!.przystanek)
        assertNull(OdjazdyPytanie.rozpoznaj("kiedy najbliższy tramwaj stąd")!!.przystanek)
    }

    @Test
    fun `to nie sa pytania o odjazdy`() {
        listOf(
            "kiedy jechałem ostatnio tramwajem",
            "kiedy tramwaje zaczęły jeździć w Krakowie",
            "jak dojadę tramwajem do ratusza",
            "kiedy będę na przystanku",
            "jakie linie lotnicze latają do Rzymu",
            "kiedy jest spotkanie",
            "ile kosztuje bilet",
            "opowiedz mi o historii autobusów"
        ).forEach { assertNull(it, OdjazdyPytanie.rozpoznaj(it)) }
    }

    // === Overpass ===

    @Test
    fun `zapytanie overpass o przystanki w poblizu`() {
        val q = Przystanki.zapytanie(50.0647, 19.9450, null)
        assertTrue(q, q.startsWith("[out:json]"))
        assertTrue(q, q.contains("node(around:450,50.064700,19.945000)[highway=bus_stop][name];"))
        assertTrue(q, q.contains("[railway=tram_stop]"))
        assertTrue(q, q.contains("[public_transport=platform]"))
        // Przystanek i ZARAZ ZA NIM jego trasy - bez członków relacji.
        assertTrue(q, q.contains("foreach.p->.s(.s out body;rel(bn.s)[type=route][route~\"^(bus|tram|trolleybus|subway|light_rail|train)$\"];out tags;);"))
    }

    @Test
    fun `zapytanie o nazwany przystanek odporne na odmiane`() {
        val wzorzec = Przystanki.wzorzecNazwy("ronda mogilskiego")
        assertEquals("[Rr][Oo][Nn].*[Mm][Oo][Gg][Ii][Ll]", wzorzec)
        val r = Regex(wzorzec)
        assertTrue(r.containsMatchIn("Rondo Mogilskie"))
        assertTrue(Regex(Przystanki.wzorzecNazwy("dworca głównego")).containsMatchIn("Dworzec Główny"))
        assertTrue(Regex(Przystanki.wzorzecNazwy("placu grunwaldzkiego")).containsMatchIn("Plac Grunwaldzki"))
        // Polska wielka litera bez flagi wielkości liter.
        assertTrue(Regex(Przystanki.wzorzecNazwy("łagiewnik")).containsMatchIn("Łagiewniki"))
        val q = Przystanki.zapytanie(50.0, 19.0, "ronda mogilskiego")
        assertTrue(q, q.contains("around:6000,"))
        assertTrue(q, q.contains("[name~\"$wzorzec\"]"))
        assertFalse(q, q.contains("[name];"))
    }

    private val ODPOWIEDŹ = """
        {"elements":[
          {"type":"node","id":1,"lat":50.0650,"lon":19.9450,"tags":{"name":"Rondo Mogilskie","railway":"tram_stop"}},
          {"type":"relation","id":10,"tags":{"type":"route","route":"tram","ref":"4"}},
          {"type":"relation","id":11,"tags":{"type":"route","route":"tram","ref":"10"}},
          {"type":"relation","id":12,"tags":{"type":"route","route":"tram","ref":"1"}},
          {"type":"node","id":2,"lat":50.0651,"lon":19.9452,"tags":{"name":"Rondo Mogilskie","highway":"bus_stop"}},
          {"type":"relation","id":13,"tags":{"type":"route","route":"bus","ref":"129"}},
          {"type":"relation","id":14,"tags":{"type":"route","route":"tram","ref":"4"}},
          {"type":"node","id":3,"lat":50.0630,"lon":19.9410,"tags":{"name":"Lubicz","highway":"bus_stop"}},
          {"type":"relation","id":15,"tags":{"type":"route","route":"bus","ref":"152"}},
          {"type":"node","id":4,"lat":50.0640,"lon":19.9440,"tags":{"highway":"bus_stop"}},
          {"type":"relation","id":16,"tags":{"type":"route","route":"bus","ref":"999"}}
        ]}
    """.trimIndent()

    @Test
    fun `rozbior laczy perony i przypisuje linie`() {
        val lista = Przystanki.rozbierz(ODPOWIEDŹ, 50.0647, 19.9450)
        assertEquals(listOf("Rondo Mogilskie", "Lubicz"), lista.map { it.nazwa })
        val rondo = lista[0]
        // Kolejność "po ludzku": 1, 4, 10 - a nie 1, 10, 4.
        assertEquals(listOf("1", "4", "10"), rondo.linie[Pojazd.TRAMWAJ])
        assertEquals(listOf("129"), rondo.linie[Pojazd.AUTOBUS])
        // Linia ze słupka bez nazwy nie przykleja się do poprzedniego przystanku.
        assertEquals(listOf("152"), lista[1].linie[Pojazd.AUTOBUS])
        assertTrue(rondo.odległośćM < 50)
        assertTrue(Przystanki.rozbierz("to nie json", 0.0, 0.0).isEmpty())
    }

    @Test
    fun `zdanie o najblizszym przystanku`() {
        val lista = Przystanki.rozbierz(ODPOWIEDŹ, 50.0647, 19.9450)
        val wszystko = Przystanki.odpowiedź(lista, OdjazdyPytanie.Pytanie(null, null))
        assertTrue(wszystko, wszystko.startsWith("Najbliższy przystanek: Rondo Mogilskie, "))
        assertTrue(wszystko, wszystko.contains("Tramwaje: 1, 4 i 10; autobusy: 129."))
        assertTrue(wszystko, wszystko.contains("Dalej: Lubicz, "))
        assertTrue(wszystko, wszystko.contains(" - autobusy: 152."))

        // Pytanie o tramwaj - tylko tramwaje.
        val tramwaj = Przystanki.odpowiedź(lista, OdjazdyPytanie.Pytanie(Pojazd.TRAMWAJ, null))
        assertTrue(tramwaj, tramwaj.contains("Tramwaje: 1, 4 i 10."))
        assertFalse(tramwaj, tramwaj.contains("129"))

        // Pytanie o autobus - Lubicz ma autobus, ale rondo jest bliżej i też ma.
        val autobus = Przystanki.odpowiedź(lista, OdjazdyPytanie.Pytanie(Pojazd.AUTOBUS, null))
        assertTrue(autobus, autobus.contains("Autobusy: 129."))

        // Trolejbusów nie ma - mówimy to wprost.
        val trolejbus = Przystanki.odpowiedź(lista, OdjazdyPytanie.Pytanie(Pojazd.TROLEJBUS, null))
        assertTrue(trolejbus, trolejbus.startsWith("W pobliżu mapa nie pokazuje linii: trolejbusy."))
    }

    @Test
    fun `pusto i nazwany przystanek`() {
        assertEquals(
            "W promieniu 450 metrów mapa nie pokazuje żadnego przystanku.",
            Przystanki.odpowiedź(emptyList(), OdjazdyPytanie.Pytanie(null, null))
        )
        assertTrue(
            Przystanki.odpowiedź(emptyList(), OdjazdyPytanie.Pytanie(null, "rondo x")).contains("„rondo x”")
        )
        val bezLinii = listOf(Przystanki.Przystanek("Kawiory", 120, emptyMap()))
        assertEquals(
            "Najbliższy przystanek: Kawiory, 120 metrów. Linii, które tu stają, mapa nie podaje.",
            Przystanki.odpowiedź(bezLinii, OdjazdyPytanie.Pytanie(null, null))
        )
        val nazwany = Przystanki.odpowiedź(bezLinii, OdjazdyPytanie.Pytanie(null, "kawiory"))
        assertTrue(nazwany, nazwany.startsWith("Przystanek Kawiory, 120 metrów stąd."))
    }

    @Test
    fun `kontekst dla modelu nie udaje odjazdow na zywo`() {
        val lista = Przystanki.rozbierz(ODPOWIEDŹ, 50.0647, 19.9450)
        val k = Przystanki.kontekstDlaModelu(lista, OdjazdyPytanie.Pytanie(Pojazd.TRAMWAJ, null))
        assertTrue(k, k.contains("Rondo Mogilskie"))
        assertTrue(k, k.contains("tramwaje: 1, 4, 10"))
        assertTrue(k, k.contains("nie odjazdy na żywo"))
        assertTrue(k, k.contains("nie wymyślaj"))
    }
}
