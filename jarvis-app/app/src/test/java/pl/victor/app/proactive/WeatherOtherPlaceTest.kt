package pl.victor.app.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dziennik z biegu 153: Bodrum dostawało prognozę Torunia i odmowę zamiast wyszukania. */
class WeatherOtherPlaceTest {

    @Test
    fun `pytanie z dziennika to inne miejsce`() {
        assertEquals("bodrum", WeatherContext.innaMiejscowość("jaka będzie pogoda w bodrum w turcji w ten weekend", "Toruń"))
    }

    @Test
    fun `miejscowosc z ustawien w kazdej odmianie to nie inne miejsce`() {
        assertNull(WeatherContext.innaMiejscowość("jaka jest pogoda w Toruniu", "Toruń"))
        assertNull(WeatherContext.innaMiejscowość("pogoda dla torunia na jutro", "Toruń"))
    }

    @Test
    fun `czas i miejsca bez nazwy nie sa miejscowoscia`() {
        listOf(
            "jaka jest dzisiaj pogoda",
            "czy będzie padać w nocy",
            "jaka pogoda na weekend",
            "czy wziąć parasol na spacer",
            "ile stopni na dworze",
            "jaka będzie pogoda w przyszłym tygodniu",
            "czy w sobotę będzie ciepło",
            "pogoda w ciągu dnia w mieście",
            "czy będzie śnieg w grudniu"
        ).forEach { assertNull(it, WeatherContext.innaMiejscowość(it, "Toruń")) }
    }

    @Test
    fun `inne miasta sa rozpoznane, a notatka kaze szukac`() {
        assertEquals("gdańsku", WeatherContext.innaMiejscowość("jaka pogoda w Gdańsku", "Toruń"))
        assertEquals("paryża", WeatherContext.innaMiejscowość("prognoza dla Paryża na jutro", "Toruń"))
        val nota = WeatherContext.otherPlaceNote("bodrum", "Toruń")
        assertTrue(nota.contains("WYSZUKAJ"))
        assertTrue(nota.contains("bodrum"))
    }
}
