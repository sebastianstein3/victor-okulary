package pl.victor.app.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideLogicTest {

    private val json = """{"batchcomplete":"","query":{"geosearch":[
        {"pageid":1,"title":"Ratusz Staromiejski w Toruniu","lat":53.01,"lon":18.6,"dist":120.4},
        {"pageid":2,"title":"Pomnik Mikołaja Kopernika w Toruniu","lat":53.01,"lon":18.6,"dist":35.0},
        {"pageid":3,"title":"Powiat toruński","lat":53.0,"lon":18.6,"dist":10.0}]}}"""

    @Test
    fun `najblizsze pierwsze, obszary administracyjne odpadaja`() {
        val m = GuideLogic.parsujGeosearch(json)
        assertEquals(listOf("Pomnik Mikołaja Kopernika w Toruniu", "Ratusz Staromiejski w Toruniu"), m.map { it.tytuł })
    }

    @Test
    fun `nie powtarza tego, co juz powiedzial`() {
        val m = GuideLogic.parsujGeosearch(json)
        assertEquals("Ratusz Staromiejski w Toruniu",
            GuideLogic.następne(m, setOf("Pomnik Mikołaja Kopernika w Toruniu"))?.tytuł)
        assertNull(GuideLogic.następne(m, m.map { it.tytuł }.toSet()))
    }

    @Test
    fun `opis to dwa zdania bez wymowy w nawiasie`() {
        val opis = GuideLogic.przytnij(
            "Ratusz Staromiejski (niem. Altstädter Rathaus) – gotycki ratusz w Toruniu. Zbudowany w XIV wieku. " +
                "Mieści muzeum. Ma wieżę."
        )
        assertEquals("Ratusz Staromiejski – gotycki ratusz w Toruniu. Zbudowany w XIV wieku.", opis)
    }

    @Test
    fun `zapowiedz i odleglosc`() {
        val z = GuideLogic.zapowiedź(GuideLogic.Miejsce("Ratusz", 118), "Gotycki ratusz.")
        assertEquals("Około 100 metrów stąd: Ratusz. Gotycki ratusz.", z)
        assertTrue(GuideLogic.zapowiedź(GuideLogic.Miejsce("Pomnik", 20), null).startsWith("Jesteś przy"))
        val d = GuideLogic.odległośćM(53.0100, 18.6000, 53.0110, 18.6000)
        assertTrue(d in 105.0..118.0)
    }
}
