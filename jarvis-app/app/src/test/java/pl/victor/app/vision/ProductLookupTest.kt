package pl.victor.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Odpowiedzi w kształcie, jaki naprawdę oddaje Open Food Facts.
 *
 * Ta funkcja mówi osobie niewidomej, co trzyma w ręce - więc błąd tutaj nie
 * jest kosmetyczny. Najważniejszy jest przypadek alergenów: przemilczany
 * alergen to nie literówka.
 */
class ProductLookupTest {

    @Test
    fun `obcy produkt z polska nazwa i alergenem`() {
        val json = """
            {"status":1,"product":{
              "product_name":"Zabpehely",
              "product_name_pl":"Płatki owsiane",
              "brands":"Nemzeti",
              "quantity":"500 g",
              "allergens_tags":["en:gluten"]
            }}
        """.trimIndent()
        assertEquals(
            "Płatki owsiane, Nemzeti, 500 g. Zawiera gluten.",
            ProductLookup.describe(json)
        )
    }

    @Test
    fun `polska nazwa ma pierwszenstwo przed oryginalna`() {
        val json = """{"status":1,"product":{"product_name":"Tejföl","product_name_pl":"Śmietana"}}"""
        assertTrue(ProductLookup.describe(json)!!.startsWith("Śmietana"))
    }

    @Test
    fun `bez polskiej nazwy bierzemy oryginalna`() {
        val json = """{"status":1,"product":{"product_name":"Mosópor"}}"""
        assertEquals("Mosópor.", ProductLookup.describe(json))
    }

    @Test
    fun `marka nie powtarza sie po nazwie`() {
        // "Ariel, Ariel, 2 l" brzmi jak usterka aplikacji.
        val json = """{"status":1,"product":{"product_name":"Ariel proszek","brands":"Ariel","quantity":"2 l"}}"""
        assertEquals("Ariel proszek, 2 l.", ProductLookup.describe(json))
    }

    @Test
    fun `kilka alergenow po polsku, bez przedrostka jezyka`() {
        val json = """
            {"status":1,"product":{"product_name":"Ciastka",
             "allergens_tags":["en:gluten","en:milk","en:eggs"]}}
        """.trimIndent()
        assertEquals("Ciastka. Zawiera gluten, mleko, jaja.", ProductLookup.describe(json))
    }

    @Test
    fun `nieznany alergen idzie surowy, a nie znika`() {
        // Lepiej powiedzieć dziwne słowo niż przemilczeć alergen.
        val json = """{"status":1,"product":{"product_name":"X","allergens_tags":["en:sesame-seeds","en:kukurydza"]}}"""
        val out = ProductLookup.describe(json)!!
        assertTrue(out.contains("sezam"))
        assertTrue(out.contains("kukurydza"))
    }

    @Test
    fun `kodu spoza bazy nie udajemy`() {
        assertNull(ProductLookup.describe("""{"status":0,"status_verbose":"product not found"}"""))
    }

    @Test
    fun `produkt bez nazwy to nie jest odpowiedz`() {
        assertNull(ProductLookup.describe("""{"status":1,"product":{"brands":"Cokolwiek"}}"""))
    }

    @Test
    fun `smieci zamiast JSON nie wywalaja aplikacji`() {
        assertNull(ProductLookup.describe("<html>502 Bad Gateway</html>"))
        assertNull(ProductLookup.describe(""))
    }

    @Test
    fun `puste pola sa traktowane jak brak`() {
        val json = """{"status":1,"product":{"product_name_pl":"   ","product_name":"Chleb","brands":""}}"""
        assertEquals("Chleb.", ProductLookup.describe(json))
    }

    @Test
    fun `adres zapytania zawiera kod i nie wymaga klucza`() {
        val url = ProductLookup.urlFor("5900512345678")
        assertTrue(url.contains("5900512345678"))
        assertTrue(url.startsWith("https://"))
        assertTrue("zapytanie nie może wymagać klucza", !url.contains("key="))
    }

    @Test
    fun `makro na 100 g w kolejnosci, ktora ludzie licza`() {
        val json = """
            {"status":1,"product":{"product_name":"Płatki owsiane","nutriments":{
              "energy-kcal_100g":379,"proteins_100g":13.5,
              "carbohydrates_100g":60,"fat_100g":7,"fiber_100g":10,"salt_100g":0.02
            }}}
        """.trimIndent()
        assertEquals(
            "Płatki owsiane. 100 g: 379 kcal, 13,5 g białka, 60 g węglowodanów, 7 g tłuszczu.",
            ProductLookup.describe(json)
        )
    }

    @Test
    fun `kilodzule przeliczane na kalorie`() {
        // Baza podaje jedno albo drugie, zależnie od tego, co było na
        // opakowaniu. "1585 kilodżuli" nikomu w Polsce nic nie mówi.
        val json = """{"status":1,"product":{"product_name":"X","nutriments":{"energy_100g":1585}}}"""
        val out = ProductLookup.describe(json)!!
        assertTrue(out, out.contains("379 kcal"))
    }

    @Test
    fun `kilokalorie maja pierwszenstwo przed dzulami`() {
        val json = """
            {"status":1,"product":{"product_name":"X","nutriments":{
              "energy-kcal_100g":400,"energy_100g":1585}}}
        """.trimIndent()
        assertTrue(ProductLookup.describe(json)!!.contains("400 kcal"))
    }

    @Test
    fun `bez zbednego zera po przecinku`() {
        val json = """{"status":1,"product":{"product_name":"X","nutriments":{"proteins_100g":13.0}}}"""
        val out = ProductLookup.describe(json)!!
        assertTrue(out, out.contains("13 g białka"))
        assertTrue("nie chcemy 13,0: $out", !out.contains("13,0"))
    }

    @Test
    fun `liczba jako napis tez sie liczy`() {
        // Baza jest społecznościowa i pola bywają tekstem zamiast liczbą.
        val json = """{"status":1,"product":{"product_name":"X","nutriments":{"energy-kcal_100g":"250"}}}"""
        assertTrue(ProductLookup.describe(json)!!.contains("250 kcal"))
    }

    @Test
    fun `produkt bez tabeli odzywczej nie dostaje pustego zdania`() {
        val json = """{"status":1,"product":{"product_name":"Woda","nutriments":{}}}"""
        assertEquals("Woda.", ProductLookup.describe(json))
        assertEquals("Woda.", ProductLookup.describe("""{"status":1,"product":{"product_name":"Woda"}}"""))
    }

    @Test
    fun `makro nie wypiera alergenow`() {
        // Alergen jest ważniejszy niż kalorie i musi zostać, gdy dochodzi makro.
        val json = """
            {"status":1,"product":{"product_name":"Ciastka","allergens_tags":["en:gluten"],
             "nutriments":{"energy-kcal_100g":450}}}
        """.trimIndent()
        val out = ProductLookup.describe(json)!!
        assertTrue(out, out.contains("Zawiera gluten"))
        assertTrue(out, out.contains("450 kcal"))
    }

    @Test
    fun `zapytanie prosi o tabele odzywcza`() {
        // Bez tego pola serwis jej nie odda - i dokładnie tak było na początku.
        assertTrue(ProductLookup.urlFor("123").contains("nutriments"))
    }
}
