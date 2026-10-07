package pl.victor.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DietCheckTest {

    private fun info(
        alergeny: Set<String> = emptySet(),
        ślady: Set<String> = emptySet(),
        analiza: Set<String> = emptySet(),
        cukry: Double? = null
    ) = ProductLookup.Info("Produkt.", alergeny, ślady, analiza, cukry)

    @Test
    fun `alergen z faktu o unikaniu`() {
        val w = DietCheck.ostrzeżenia(info(alergeny = setOf("gluten", "soybeans")), listOf("Nie jem glutenu"))
        assertEquals(1, w.size)
        assertTrue(w[0].contains("GLUTEN"))
        assertTrue(DietCheck.dlaModelu(w)!!.contains("NA POCZĄTKU"))
    }

    @Test
    fun `slady i weganizm`() {
        val w = DietCheck.ostrzeżenia(
            info(ślady = setOf("nuts"), analiza = setOf("non-vegan")),
            listOf("mam alergię na orzechy", "jestem weganinem")
        )
        assertTrue(w.any { it.contains("ŚLADY") && it.contains("ORZECHY") })
        assertTrue(w.any { it.contains("WEGAŃSKI") })
    }

    @Test
    fun `lubie orzechy to nie zakaz, a bez faktow nie ma ostrzezen`() {
        assertTrue(DietCheck.ostrzeżenia(info(alergeny = setOf("nuts")), listOf("lubię orzechy")).isEmpty())
        assertTrue(DietCheck.ostrzeżenia(info(alergeny = setOf("nuts")), emptyList()).isEmpty())
        assertNull(DietCheck.dlaModelu(emptyList()))
    }

    @Test
    fun `cukrzyca i duzo cukru`() {
        assertTrue(DietCheck.ostrzeżenia(info(cukry = 48.0), listOf("mam cukrzycę")).single().contains("48 g"))
        assertTrue(DietCheck.ostrzeżenia(info(cukry = 3.0), listOf("mam cukrzycę")).isEmpty())
    }

    @Test
    fun `info z odpowiedzi bazy`() {
        val json = """{"status":1,"product":{"product_name":"Natur","brands":"alpro","quantity":"400g",
            "allergens_tags":["en:soybeans"],"traces_tags":["en:nuts"],"ingredients_analysis_tags":["en:vegan"],
            "nutriments":{"sugars_100g":2.3}}}"""
        val i = ProductLookup.info(json)!!
        assertEquals(setOf("soybeans"), i.alergeny)
        assertEquals(setOf("nuts"), i.ślady)
        assertEquals(2.3, i.cukry100g!!, 0.01)
    }
}
