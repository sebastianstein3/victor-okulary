package pl.victor.app.translation

/** Teksty trybu rozmowy w dwie strony - patrz AIOrchestrator.startTwoWayTranslation. */
object TwoWay {

    /** Zapowiedź po polsku - mówi, jak oddać sobie głos. */
    fun zapowiedź(nazwaJęzykaRozmówcy: String): String =
        "Tłumaczę rozmowę. Rozmówcę słyszysz po polsku w okularach. Gdy chcesz coś " +
            "powiedzieć, kliknij raz przycisk na oprawce albo dotknij zausznika i mów po polsku - przekład " +
            "na $nazwaJęzykaRozmówcy przeczyta głośnik telefonu. Dwa kliknięcia kończą."

    /** Twoje zdanie w historii panelu - odróżnione od słów rozmówcy. */
    fun mojeZdanie(tekst: String): String = "$PREFIKS$tekst"

    fun czyMoje(oryginał: String): Boolean = oryginał.startsWith(PREFIKS)

    const val PREFIKS = "Ty: "

    /**
     * Polecenie dla modelu: przekład TWOJEGO zdania na język rozmówcy.
     *
     * Model, a nie ML Kit, bo ML Kit tłumaczy słowo w słowo, a rozmówca słyszy
     * to z głośnika - zdanie ma brzmieć jak powiedziane przez człowieka.
     */
    fun polecenieTłumaczenia(tekst: String, z: String, na: String): String =
        "Przetłumacz poniższą wypowiedź z języka: ${nazwaJęzyka(z)} na język: ${nazwaJęzyka(na)}. " +
            "To mowa w rozmowie twarzą w twarz - przetłumacz naturalnie, jak powiedziałby " +
            "to rodzimy użytkownik, zachowaj sens i ton, bez dodawania czegokolwiek. " +
            "Odpowiedz wyłącznie przekładem.\n\nWypowiedź: $tekst"

    /** Przekład z modelu bez cudzysłowów i etykiet typu "Przekład:". */
    fun oczyśćPrzekład(odpowiedź: String?): String? {
        var t = odpowiedź?.trim() ?: return null
        t = t.replace(Regex("""^(przekład|tłumaczenie|translation)\s*:\s*""", RegexOption.IGNORE_CASE), "")
        t = t.trim().trim('"', '\'', '„', '”', '«', '»', '“').trim()
        return t.takeIf { it.isNotBlank() }
    }

    /** Polska nazwa języka do polecenia; nieznany kod zostaje kodem. */
    fun nazwaJęzyka(kod: String): String = when (kod.substringBefore('-').lowercase()) {
        "pl" -> "polski"
        "en" -> "angielski"
        "de" -> "niemiecki"
        "fr" -> "francuski"
        "es" -> "hiszpański"
        "it" -> "włoski"
        "pt" -> "portugalski"
        "ru" -> "rosyjski"
        "uk" -> "ukraiński"
        "cs" -> "czeski"
        "sk" -> "słowacki"
        "nl" -> "niderlandzki"
        "sv" -> "szwedzki"
        "ja" -> "japoński"
        "ko" -> "koreański"
        "zh" -> "chiński"
        "ar" -> "arabski"
        "tr" -> "turecki"
        "hu" -> "węgierski"
        "ro" -> "rumuński"
        "lt" -> "litewski"
        "vi" -> "wietnamski"
        "hi" -> "hindi"
        else -> "o kodzie $kod"
    }
}

/**
 * Jakim językiem NAPRAWDĘ mówi rozmówca - z tego, co rozpoznaje usługa na żywo.
 *
 * W ustawieniach jest "z angielskiego", ale tłumaczenie na żywo samo wykrywa
 * język, więc rozmawiać można z Niemcem bez zmiany ustawień. Twoja odpowiedź
 * ma wtedy pójść po niemiecku, nie po angielsku. Jedno pomylone wykrycie nie
 * może przestawić rozmowy - zmieniamy dopiero po [PRÓG] zgodnych z rzędu.
 */
class JęzykRozmówcy(mójJęzyk: String) {
    private val mój = mójJęzyk.substringBefore('-').lowercase()
    private var kandydat: String? = null
    private var ile = 0

    /** Pewnie wykryty język rozmówcy albo `null`, gdy jeszcze nie wiadomo. */
    @Volatile
    var pewny: String? = null
        private set

    fun usłyszany(kod: String?) {
        val k = kod?.substringBefore('-')?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        if (k == mój) return
        if (k == kandydat) ile++ else { kandydat = k; ile = 1 }
        if (ile >= PRÓG) pewny = k
    }

    companion object {
        const val PRÓG = 3
    }
}
