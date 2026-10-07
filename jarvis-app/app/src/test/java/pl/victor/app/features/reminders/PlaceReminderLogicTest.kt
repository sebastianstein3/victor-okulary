package pl.victor.app.features.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceReminderLogicTest {

    @Test
    fun `prosba w obu szykach`() {
        assertEquals(
            PlaceReminderLogic.Prośba("kupić mleko", "Biedronce"),
            PlaceReminderLogic.prośba("Przypomnij mi kupić mleko, gdy będę w Biedronce.")
        )
        assertEquals(
            PlaceReminderLogic.Prośba("lekach dla mamy", "aptece"),
            PlaceReminderLogic.prośba("kiedy będę w aptece, przypomnij mi o lekach dla mamy")
        )
        assertEquals("domu", PlaceReminderLogic.prośba("przypomnij mi wynieść śmieci jak wrócę do domu")?.gdzie)
        assertNull(PlaceReminderLogic.prośba("przypomnij mi jutro o dziesiątej o spotkaniu"))
    }

    @Test
    fun `cel - zapisane, rodzaj, nazwa`() {
        assertEquals(Cel.Zapisane("Dom"), PlaceReminderLogic.cel("domu"))
        assertEquals(Cel.Zapisane("Praca"), PlaceReminderLogic.cel("pracy"))
        assertEquals("apteka", (PlaceReminderLogic.cel("aptece") as Cel.Rodzaj).opis)
        assertTrue("Biedronka".contains((PlaceReminderLogic.cel("Biedronce") as Cel.Nazwa).tekst))
        assertEquals(Cel.Nazwa("Lidl"), PlaceReminderLogic.cel("Lidlu"))
    }

    @Test
    fun `zapis domu i pracy, lista, usuwanie`() {
        assertEquals("Dom", PlaceReminderLogic.zapisDomuLubPracy("Tu jest mój dom"))
        assertEquals("Dom", PlaceReminderLogic.zapisDomuLubPracy("zapamiętaj, że tu jest mój dom"))
        assertEquals("Praca", PlaceReminderLogic.zapisDomuLubPracy("tu pracuję"))
        assertNull(PlaceReminderLogic.zapisDomuLubPracy("tu jest ładnie"))
        assertTrue(PlaceReminderLogic.czyLista("jakie mam przypomnienia?"))
        assertTrue(PlaceReminderLogic.czyUsuńWszystkie("usuń wszystkie przypomnienia"))
    }

    @Test
    fun `overpass - zapytanie i odpowiedz`() {
        val q = PlaceReminderLogic.zapytanieOverpass(Cel.Nazwa("Biedronk"), 53.0, 18.6, 80)!!
        assertTrue(q.contains("around:80,53.0,18.6"))
        assertTrue(q.contains("Biedronk"))
        assertNull(PlaceReminderLogic.zapytanieOverpass(Cel.Zapisane("Dom"), 53.0, 18.6, 80))
        val json = """{"elements":[{"type":"node","lat":53.001,"lon":18.6,"tags":{"name":"Biedronka"}},
            {"type":"way","center":{"lat":53.002,"lon":18.6},"tags":{"name":"Biedronka 2"}}]}"""
        val o = PlaceReminderLogic.parsujOverpass(json)
        assertEquals(2, o.size)
        assertEquals("Biedronka 2", o[1].nazwa)
        assertTrue(PlaceReminderLogic.odległośćM(53.0, 18.6, 53.001, 18.6) in 100.0..120.0)
    }
}
