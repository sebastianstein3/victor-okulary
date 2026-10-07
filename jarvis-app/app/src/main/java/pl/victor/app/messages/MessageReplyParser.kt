package pl.victor.app.messages

/**
 * "Odpowiedz jej, że będę za dziesięć minut" -> (adresat: null, treść: "Będę za dziesięć minut").
 *
 * ## Po co lokalnie, skoro model też to umie
 * Bo to najczęstsza odpowiedź i ma być pewna: bez sieci, bez sekund czekania
 * i bez ryzyka, że model "ulepszy" treść. Przez model idą tylko odpowiedzi,
 * których nie da się wziąć dosłownie ("odpowiedz jej grzecznie, że nie dam
 * rady", "odpisz po angielsku...") - tam trzeba treść UŁOŻYĆ, a nie przepisać.
 */
object MessageReplyParser {

    data class Odpowiedź(val adresat: String?, val treść: String)

    fun parse(tekst: String): Odpowiedź? {
        val t = tekst.trim().trimEnd('.')
        // "odpisz na wiadomość od Ani" to prośba BEZ treści - inaczej poszłoby
        // do Ani "Wiadomość od Ani".
        if (prośbaBezTreści(t) != null) return null
        val m = WZORZEC.matchEntire(t) ?: return null
        val kto = m.groups["kto"]?.value?.trim()
        var treść = m.groups["tresc"]?.value?.trim().orEmpty()
        if (treść.isEmpty()) return null
        // Sposób ("grzecznie", "po angielsku") znaczy: ułóż treść - to robota
        // dla modelu, nie dla przepisywania.
        if (SPOSÓB.containsMatchIn(treść.lowercase())) return null
        // "odpisz po angielsku, że..." - "po" wzięte za imię adresata.
        if (kto != null && SPOSÓB.containsMatchIn("$kto $treść".lowercase())) return null
        treść = treść.removePrefix("że ").removePrefix("ze ").trim()
        if (treść.isEmpty()) return null
        val adresat = kto?.takeUnless { it.lowercase() in ZAIMKI }
        return Odpowiedź(adresat, treść.replaceFirstChar { it.uppercase() })
    }

    /**
     * "Odpisz na wiadomość od Ani" - BEZ treści. Bieg 160 ("podpisz na
     * wiadomość od inpostu" - tak rozpoznawanie usłyszało "odpisz"): poszło do
     * modelu, który zaczął szukać w poczcie. Zwraca adresata (może być pusty),
     * `null` gdy to nie ta prośba.
     */
    fun prośbaBezTreści(tekst: String): String? {
        val m = BEZ_TREŚCI.matchEntire(tekst.trim().trimEnd('.', '?', '!')) ?: return null
        return m.groups["kto"]?.value?.trim().orEmpty()
    }

    private val BEZ_TREŚCI = Regex(
        """^(?:p?odpisz|odpowiedz)(?:\s+(?:jej|mu))?\s+na\s+(?:t[eę]\s+|ostatni[aą]\s+)?""" +
            """(?:wiadomo[sś][cć]|sms(?:a)?|esemes(?:a)?)(?:\s+od\s+(?<kto>.+))?$""",
        RegexOption.IGNORE_CASE
    )

    private val WZORZEC = Regex(
        """^(?:(?:a\s+)?(?:teraz\s+)?)?(?:odpowiedz|odpisz|napisz\s+w\s+odpowiedzi|odpowiedź)""" +
            """(?:\s+(?<kto>[A-Za-zĄĆĘŁŃÓŚŹŻąćęłńóśźż]+))?\s*[,:]?\s+(?<tresc>.+)$""",
        RegexOption.IGNORE_CASE
    )

    private val ZAIMKI = setOf("jej", "mu", "im", "temu", "tej", "na", "do", "to", "tak", "ze", "że")

    private val SPOSÓB = Regex(
        """^(grzecznie|uprzejmie|kr[oó]tko|elegancko|[sś]miesznie|zabawnie|formalnie|po\s+angielsku|""" +
            """po\s+niemiecku|po\s+polsku|co[sś]\s+mi[lł]ego|jako[sś]|co[sś]|za\s+mnie|w\s+moim\s+imieniu)\b"""
    )
}
