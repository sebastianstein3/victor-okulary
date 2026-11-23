package pl.victor.app.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmPlacesTest {

    @Test
    fun `pytania o rodzaj miejsca dostaja polozenie`() {
        listOf(
            "gdzie jest fryzjer",
            "czy jest tu jakaś Biedronka",
            "gdzie jest Żabka",
            "gdzie mogę wypłacić pieniądze",
            "czy jest tu paczkomat",
            "o której zamykają najbliższego Lidla",
            "czy Rossmann jest jeszcze otwarty",
            "jak dojść do apteki"
        ).forEach { assertTrue(it, NearbyQuestion.dotyczyOkolicy(it)) }
    }

    @Test
    fun `wzmianka bez pytania o miejsce nie dostaje polozenia`() {
        listOf(
            "kupiłem dziś chleb w Biedronce",
            "jaki hotel polecasz w Paryżu",
            "jakie piwo jest najlepsze",
            "ile to jest netto i brutto",
            "co mam zjeść na obiad"
        ).forEach { assertFalse(it, NearbyQuestion.dotyczyOkolicy(it)) }
    }

    @Test
    fun `zapytanie overpass wedlug rodzaju i marki`() {
        val marka = OsmPlaces.zapytanie(53.0, 18.6, "gdzie jest Biedronka")
        assertTrue(marka, marka.contains("""[~"^(name|brand)$"~"Biedronka",i]"""))
        assertTrue(marka.contains("around:2000,53.000000,18.600000"))
        val rodzaj = OsmPlaces.zapytanie(53.0, 18.6, "gdzie jest fryzjer")
        assertTrue(rodzaj.contains("hairdresser"))
        val ogólne = OsmPlaces.zapytanie(53.0, 18.6, "co jest w pobliżu")
        assertTrue(ogólne.contains("around:500,") && ogólne.contains("[name]"))
        assertTrue(ogólne.startsWith("[out:json]") && ogólne.endsWith("out center tags 300;"))
    }

    @Test
    fun `rozbior odpowiedzi - najblizsze najpierw z adresem i godzinami`() {
        val odp = """{"elements":[
            {"type":"node","id":1,"lat":53.0018,"lon":18.6,"tags":{"name":"Biedronka","shop":"supermarket","addr:street":"Długa","addr:housenumber":"5","opening_hours":"Mo-Sa 06:00-22:00"}},
            {"type":"way","id":2,"center":{"lat":53.0005,"lon":18.6},"tags":{"name":"Pizzeria Roma","amenity":"restaurant","cuisine":"pizza;italian"}},
            {"type":"node","id":3,"lat":53.0005,"lon":18.6,"tags":{"amenity":"atm"}},
            {"type":"node","id":4,"lat":53.0,"lon":18.6}
        ]}"""
        val m = OsmPlaces.rozbierz(odp, 53.0, 18.6)
        assertEquals(3, m.size)
        assertEquals("Pizzeria Roma", m[0].nazwa)
        assertEquals("pizza, italian", m[0].kuchnia)
        assertEquals("bankomat", m[1].nazwa)
        val b = m.last()
        assertEquals("supermarket", b.rodzaj)
        assertEquals("Długa 5", b.adres)
        assertEquals(200, b.odległośćM / 10 * 10)
        val opis = OsmPlaces.opisDlaModelu(m, "gdzie jest Biedronka")
        assertTrue(opis.contains("Biedronka (supermarket) - 200 m") || opis.contains("Biedronka (supermarket) - 19"))
        assertTrue(opis.contains("NIE MA"))
        assertTrue(OsmPlaces.opisDlaModelu(emptyList(), "gdzie jest fryzjer").contains("nie ma fryzjer"))
        assertTrue(OsmPlaces.rozbierz("zepsute", 0.0, 0.0).isEmpty())
    }

    @Test
    fun `marki i rodzaje`() {
        assertEquals("Żabka", OsmPlaces.marka("czy jest tu Żabka"))
        assertEquals("Biedronka", OsmPlaces.marka("w biedronce"))
        assertNull(OsmPlaces.marka("gdzie jest fryzjer"))
        assertEquals("apteka", OsmPlaces.rodzaj("najbliższa apteka")?.nazwa)
    }
}
