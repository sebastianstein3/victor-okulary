package pl.victor.app.vision

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Kod kreskowy -> co to za produkt, po polsku.
 *
 * ## Po co, skoro mamy model widzący
 * Bo to są dwie różne rzeczy. Model patrzy na opakowanie i CZYTA, co na nim
 * napisano - a skład i alergeny bywają drobnym drukiem z boku, którego nikt nie
 * kadruje. Kod kreskowy identyfikuje produkt JEDNOZNACZNIE i oddaje dane, o
 * których na froncie opakowania nie ma ani słowa.
 *
 * Dla osoby niewidomej w sklepie to jest różnica między „chyba płatki" a
 * „płatki owsiane, 500 g, zawiera gluten".
 *
 * ## Czemu Open Food Facts
 * Bo nie wymaga klucza ani konta, jest darmowe i otwarte, a baza jest
 * społecznościowa i ma polskie produkty. Nie kosztuje też ANI JEDNEGO tokenu
 * modelu - a przy skanowaniu półki w sklepie to jest realna różnica na koncie.
 *
 * ## Podział na czystą i brudną część
 * [describe] dostaje gotowy napis JSON i nie dotyka sieci, więc da się ją
 * uruchomić w teście na prawdziwych odpowiedziach serwisu. Pobieranie stoi
 * osobno, w [ProductLookupClient].
 */
object ProductLookup {

    /** Adres zapytania dla danego kodu. */
    fun urlFor(barcode: String): String =
        "https://world.openfoodfacts.org/api/v2/product/$barcode.json" +
            "?fields=product_name,product_name_pl,brands,quantity,allergens_tags,ingredients_text_pl"

    /**
     * Zdanie do wypowiedzenia albo `null`, gdy produktu nie ma w bazie.
     *
     * Nazwę bierzemy polską, gdy jest - to jest cały sens przy obcym produkcie.
     */
    fun describe(json: String): String? {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return null
        // status 0 znaczy "nie znam takiego kodu". To NIE jest błąd - po prostu
        // bazy społecznościowe nie mają wszystkiego i trzeba to powiedzieć
        // wprost, zamiast milczeć.
        if (root.get("status")?.asIntOrNull() != 1) return null
        val product = root.getAsJsonObject("product") ?: return null

        val name = product.stringOrNull("product_name_pl")
            ?: product.stringOrNull("product_name")
            ?: return null

        return buildString {
            append(name)
            product.stringOrNull("brands")?.let { brands ->
                val first = brands.split(",").first().trim()
                // Marka tylko wtedy, gdy nie powtarza tego, co już w nazwie -
                // "Ariel Ariel proszek" brzmi jak usterka.
                if (first.isNotEmpty() && !name.contains(first, ignoreCase = true)) {
                    append(", ").append(first)
                }
            }
            product.stringOrNull("quantity")?.let { append(", ").append(it) }
            allergensOf(product)?.let { append(". Zawiera ").append(it) }
            append('.')
        }
    }

    /**
     * Alergeny po polsku albo `null`, gdy serwis żadnych nie podaje.
     *
     * Open Food Facts oddaje je jako znaczniki z przedrostkiem języka
     * ("en:gluten"). Tłumaczymy tylko te, które w Polsce są na liście
     * obowiązkowych - reszta idzie surowym znacznikiem bez przedrostka, bo
     * lepiej powiedzieć dziwne słowo niż przemilczeć alergen.
     */
    private fun allergensOf(product: JsonObject): String? {
        val tags = product.getAsJsonArray("allergens_tags") ?: return null
        if (tags.size() == 0) return null
        val names = tags.mapNotNull { element ->
            val tag = runCatching { element.asString }.getOrNull() ?: return@mapNotNull null
            val bare = tag.substringAfter(':', tag).trim()
            if (bare.isEmpty()) null else (ALLERGENS[bare] ?: bare.replace('-', ' '))
        }.distinct()
        return if (names.isEmpty()) null else names.joinToString(", ")
    }

    private val ALLERGENS = mapOf(
        "gluten" to "gluten",
        "milk" to "mleko",
        "eggs" to "jaja",
        "nuts" to "orzechy",
        "peanuts" to "orzeszki ziemne",
        "soybeans" to "soję",
        "fish" to "ryby",
        "crustaceans" to "skorupiaki",
        "molluscs" to "mięczaki",
        "celery" to "seler",
        "mustard" to "gorczycę",
        "sesame-seeds" to "sezam",
        "sulphur-dioxide-and-sulphites" to "siarczyny",
        "lupin" to "łubin"
    )

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        val text = runCatching { element.asString }.getOrNull()?.trim()
        return if (text.isNullOrEmpty()) null else text
    }

    private fun com.google.gson.JsonElement.asIntOrNull(): Int? =
        runCatching { asInt }.getOrNull()
}
