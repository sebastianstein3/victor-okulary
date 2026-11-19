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
     * Co pokazać na ekranie "Pokaż rozmówcy": ostatni przekład TWOJEGO zdania
     * (duży, w jego języku) i ostatnie zdanie rozmówcy (jego oryginał, małe).
     *
     * ## Po co
     * Rozmówca słyszy przekład z głośnika telefonu, ale w hałasie (ulica,
     * dworzec) to nie wystarcza - telefon odwrócony ekranem do niego działa
     * jak napisy. Jego oryginał pod spodem pozwala mu sprawdzić, czy jego
     * zdanie zostało dobrze usłyszane.
     */
    data class DlaRozmówcy(
        /** Przekład Twojego ostatniego zdania na język rozmówcy albo `null`, gdy jeszcze nic nie mówiłeś. */
        val mójPrzekład: String?,
        /** Twoje zdanie po polsku (bez [PREFIKS]) - mały podpis dla Ciebie. */
        val mojeZdanie: String?,
        /** Ostatnie zdanie rozmówcy w jego języku albo `null`. */
        val jegoOryginał: String?,
        /** Id odcinka z [mójPrzekład] - ekran wie, że przyszedł nowy, a nie ten sam. */
        val mójId: Long?
    )

    fun dlaRozmówcy(t: EarTranscript): DlaRozmówcy {
        val mój = t.segmenty.lastOrNull { czyMoje(it.oryginał) && it.przekład.isNotBlank() }
        val jego = t.segmenty.lastOrNull { !czyMoje(it.oryginał) && it.oryginał.isNotBlank() }
        return DlaRozmówcy(
            mójPrzekład = mój?.przekład?.trim(),
            mojeZdanie = mój?.oryginał?.removePrefix(PREFIKS)?.trim(),
            jegoOryginał = jego?.oryginał?.trim(),
            mójId = mój?.id
        )
    }

    /**
     * Wielkość czcionki przekładu dla rozmówcy (sp): czytelna z metra, a
     * długie zdanie wciąż mieści się na ekranie telefonu bez przewijania.
     * Krótkie "Dziękuję" ma być ogromne, akapit - tylko duży.
     */
    fun czcionkaDlaRozmówcy(tekst: String?): Int {
        val n = tekst?.trim()?.length ?: 0
        return when {
            n <= 30 -> 60
            n <= 70 -> 48
            n <= 130 -> 40
            n <= 220 -> 34
            else -> 28
        }
    }

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
