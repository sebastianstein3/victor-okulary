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

    @Test
    fun `zdania z biegu 160`() {
        val teraz = java.time.ZonedDateTime.of(2026, 10, 7, 21, 38, 0, 0, java.time.ZoneId.of("Europe/Warsaw"))
        val mleko = PlaceReminderLogic.prośba("przypomnij mi żebym kupił mleko gdy będę w biedronce", teraz)!!
        assertEquals("żebyś kupił mleko", mleko.co)
        assertEquals("biedronce", mleko.gdzie)
        assertEquals(0L, mleko.odMs)

        val śmieci = PlaceReminderLogic.prośba(
            "przypomnij mi jutro gdy wrócę do domu z pracy po 16:00 żebym wyniósł śmieci", teraz
        )!!
        assertEquals("żebyś wyniósł śmieci", śmieci.co)
        assertEquals(Cel.Zapisane("Dom"), PlaceReminderLogic.cel(śmieci.gdzie))
        val od = java.time.ZonedDateTime.of(2026, 10, 8, 16, 0, 0, 0, java.time.ZoneId.of("Europe/Warsaw"))
        assertEquals(od.toInstant().toEpochMilli(), śmieci.odMs)

        assertEquals("Dom", PlaceReminderLogic.zapisDomuLubPracy("w tym miejscu jest mój dom"))
        assertEquals("Dom", PlaceReminderLogic.zapisDomuLubPracy("zapisz że ta lokalizacja to mój dom"))
        assertNull(PlaceReminderLogic.zapisDomuLubPracy("zapisz że kupić chleb"))
    }

    @Test
    fun `komunikat mowi do uzytkownika`() {
        val r = PlaceReminder(1, "żebyś kupił mleko", "biedronce", Cel.Nazwa("biedronk"), 0)
        assertEquals("Przypomnienie, bo jesteś przy: Biedronka. Pamiętaj, żebyś kupił mleko.",
            PlaceReminderLogic.komunikat(r, "Biedronka"))
    }
}
