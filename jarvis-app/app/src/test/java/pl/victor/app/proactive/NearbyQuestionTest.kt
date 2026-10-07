package pl.victor.app.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dziennik z biegu 154: pytanie o jedzenie w okolicy dostało miasto z ustawień. */
class NearbyQuestionTest {

    @Test
    fun `pytania o okolice`() {
        listOf(
            "gdzie w okolicy można zjeść coś taniego i dobrego",
            "gdzie mogę tu zjeść",
            "gdzie można kupić baterie",
            "gdzie jest najbliższa apteka",
            "w jakim mieście jestem",
            "jak stąd dojść do dworca",
            "czy niedaleko jest bankomat"
        ).forEach { assertTrue(it, NearbyQuestion.dotyczyOkolicy(it)) }
    }

    @Test
    fun `przewodnik`() {
        assertTrue(NearbyQuestion.jestPrzewodnikiem("co ciekawego jest w okolicy"))
        assertTrue(NearbyQuestion.jestPrzewodnikiem("jakie są tu zabytki"))
        assertTrue(NearbyQuestion.dotyczyOkolicy("co warto zobaczyć"))
        assertFalse(NearbyQuestion.jestPrzewodnikiem("gdzie zjeść"))
    }

    @Test
    fun `zwykle pytania nie dostaja polozenia`() {
        listOf(
            "ile to jest siedem razy osiem",
            "opowiedz mi kawał",
            "co mam zjeść na obiad",
            "jaka jest stolica Francji",
            "przypomnij mi jutro o spotkaniu"
        ).forEach { assertFalse(it, NearbyQuestion.dotyczyOkolicy(it)) }
    }

    @Test
    fun `opis dla modelu mowi co zrobic z polozeniem`() {
        val tu = LocationContext.Tutaj(53.01, 18.6, "Toruń", "Polska, Toruń, Szeroka 1", 30_000L, 12f)
        val okolica = LocationContext.opisDlaModelu(tu, LocationContext.Cel.OKOLICA)
        assertTrue(okolica.contains("Szeroka 1"))
        assertTrue(okolica.contains("53.01000, 18.60000"))
        assertTrue(okolica.contains("Nie pytaj"))
        assertTrue(LocationContext.opisDlaModelu(tu, LocationContext.Cel.PRZEWODNIK).contains("przewodnik"))
    }
}
