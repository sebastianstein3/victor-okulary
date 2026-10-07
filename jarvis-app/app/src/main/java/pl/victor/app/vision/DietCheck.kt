package pl.victor.app.vision

/**
 * Czy rozpoznany produkt pasuje do tego, co użytkownik o sobie powiedział.
 *
 * ## Po co
 * Asystent zna produkt z kodu (skład, alergeny z Open Food Facts) i zna fakty
 * o użytkowniku ("nie jem glutenu", "mam alergię na orzechy") - ale nie łączył
 * jednego z drugim. Model MÓGŁBY to zrobić sam, tylko że fakty i skład giną
 * w kilkuset liniach promptu, a pominięcie alergenu to nie jest drobna
 * pomyłka. Tu porównanie jest lokalne, natychmiastowe i nie dokłada ani
 * milisekundy sieci: oba składniki są już w pamięci, gdy model dostaje
 * pytanie.
 *
 * ## Tylko fakty o UNIKANIU
 * "Lubię orzechy" też wymienia orzechy. Fakt liczy się dopiero wtedy, gdy
 * mówi o unikaniu ([UNIKANIE]) - inaczej asystent ostrzegałby przed tym, co
 * człowiek lubi.
 */
object DietCheck {

    /** Zdania dla modelu - puste, gdy nie ma czego zgłaszać. */
    fun ostrzeżenia(info: ProductLookup.Info, fakty: List<String>): List<String> {
        val unikane = fakty.map { it.lowercase() }.filter { f -> UNIKANIE.any { f.contains(it) } }
        if (unikane.isEmpty()) return emptyList()
        val wynik = mutableListOf<String>()
        for ((klucz, reguła) in REGUŁY) {
            val fakt = unikane.firstOrNull { f -> reguła.słowa.any { f.contains(it) } } ?: continue
            when {
                klucz in info.alergeny ->
                    wynik += "ZAWIERA ${reguła.nazwa}, a użytkownik zapisał: \"$fakt\"."
                klucz in info.ślady ->
                    wynik += "MOŻE ZAWIERAĆ ŚLADY: ${reguła.nazwa}, a użytkownik zapisał: \"$fakt\"."
            }
        }
        unikane.firstOrNull { f -> WEGAN.any { f.contains(it) } }?.let { fakt ->
            if ("non-vegan" in info.analiza) wynik += "NIE JEST WEGAŃSKI, a użytkownik zapisał: \"$fakt\"."
        }
        unikane.firstOrNull { f -> WEGETARIAN.any { f.contains(it) } && WEGAN.none { f.contains(it) } }?.let { fakt ->
            if ("non-vegetarian" in info.analiza) wynik += "NIE JEST WEGETARIAŃSKI, a użytkownik zapisał: \"$fakt\"."
        }
        unikane.firstOrNull { f -> CUKIER.any { f.contains(it) } }?.let { fakt ->
            val c = info.cukry100g
            if (c != null && c >= DUŻO_CUKRU_G) {
                wynik += "MA DUŻO CUKRU (${c.toInt()} g na 100 g), a użytkownik zapisał: \"$fakt\"."
            }
        }
        return wynik
    }

    /**
     * Ograniczenia z faktów jako stałe polecenie - także bez kodu produktu.
     *
     * Bieg 160: "daj mi informację na temat Nutelli" przy zapisanej alergii na
     * orzechy - model znał fakt, wyszukał skład i nie połączył jednego z
     * drugim. Jedno zdanie dla modelu, tylko gdy są fakty o unikaniu.
     */
    fun regułaDlaModelu(fakty: List<String>): String? {
        val unikane = fakty.filter { f -> val l = f.lowercase(); UNIKANIE.any { l.contains(it) } }
        if (unikane.isEmpty()) return null
        return "=== DIETA UŻYTKOWNIKA ===\n" + unikane.joinToString("\n") { "- $it" } +
            "\nGdy rozmowa dotyczy jedzenia, produktu spożywczego, przepisu albo lokalu z " +
            "jedzeniem i coś się z tym kłóci (np. produkt zawiera orzechy), powiedz to NA " +
            "POCZĄTKU odpowiedzi jednym zdaniem."
    }

    /** Blok dla modelu - polecenie, żeby ostrzeżenie padło na początku odpowiedzi. */
    fun dlaModelu(ostrzeżenia: List<String>): String? {
        if (ostrzeżenia.isEmpty()) return null
        return "=== UWAGA: DIETA UŻYTKOWNIKA ===\n" +
            ostrzeżenia.joinToString("\n") { "- Ten produkt $it" } +
            "\nPowiedz o tym NA POCZĄTKU odpowiedzi, jednym zdaniem, zanim powiesz cokolwiek innego."
    }

    private data class Reguła(val nazwa: String, val słowa: List<String>)

    /** Klucze Open Food Facts (bez "en:") -> jak o nich mówi człowiek. */
    private val REGUŁY = mapOf(
        "gluten" to Reguła("GLUTEN", listOf("gluten", "celiak", "pszenic")),
        "milk" to Reguła("MLEKO", listOf("laktoz", "mleko", "mleka", "nabiał", "nabial")),
        "eggs" to Reguła("JAJA", listOf("jaj")),
        "nuts" to Reguła("ORZECHY", listOf("orzech")),
        "peanuts" to Reguła("ORZESZKI ZIEMNE", listOf("orzeszk", "arachid", "ziemn")),
        "soybeans" to Reguła("SOJĘ", listOf("soj")),
        "fish" to Reguła("RYBY", listOf("ryb")),
        "crustaceans" to Reguła("SKORUPIAKI", listOf("skorupiak", "krewet", "krab")),
        "molluscs" to Reguła("MIĘCZAKI", listOf("mięczak", "mieczak", "małż", "malz")),
        "celery" to Reguła("SELER", listOf("seler")),
        "mustard" to Reguła("GORCZYCĘ", listOf("gorczyc", "musztard")),
        "sesame-seeds" to Reguła("SEZAM", listOf("sezam")),
        "sulphur-dioxide-and-sulphites" to Reguła("SIARCZYNY", listOf("siarczyn")),
        "lupin" to Reguła("ŁUBIN", listOf("łubin", "lubin"))
    )

    private val UNIKANIE = listOf(
        "nie jem", "nie jadam", "nie mogę", "nie moge", "nie piję", "nie pije", "alergi",
        "uczulon", "uczula", "nietoleranc", "unikam", "bez ", "celiak", "wegan", "wegetaria",
        "cukrzyc", "dieta", "na diecie", "szkodzi"
    )
    private val WEGAN = listOf("wegan")
    private val WEGETARIAN = listOf("wegetaria", "nie jem mięsa", "nie jem miesa")
    private val CUKIER = listOf("cukrzyc", "cukru", "cukier", "słodycz", "slodycz")

    /** Od tylu gramów cukru na 100 g produkt uchodzi za "dużo cukru" (próg UK/EU FoP). */
    private const val DUŻO_CUKRU_G = 22.5
}
