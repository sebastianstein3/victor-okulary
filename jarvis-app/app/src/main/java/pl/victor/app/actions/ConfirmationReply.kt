package pl.victor.app.actions

/**
 * Rozpoznaje "tak" albo "nie" w odpowiedzi na pytanie o potwierdzenie akcji.
 *
 * ## Po co czysta funkcja z testami
 * Bo obie pomyłki są kosztowne i nieodwracalne w różny sposób: fałszywe "tak"
 * wysyła maila albo dodaje wydarzenie, którego nikt nie chciał, a fałszywe "nie"
 * kasuje pracę, którą użytkownik właśnie zlecił. Przy potwierdzaniu głosem nie ma
 * drugiego ekranu, na którym dałoby się to złapać.
 *
 * ## Dlaczego pierwsze rozstrzygające słowo, a nie "czy zawiera tak"
 * Bo ludzie mówią "nie, dziękuję" i "tak, dodaj" - a także "tak, ale nie teraz".
 * Liczy się to, co padło NAJPIERW: reszta zdania jest zwykle uzasadnieniem
 * decyzji, a nie jej zmianą.
 *
 * Wątpliwość NIE jest zgodą. Gdy nic nie pasuje, wynik jest [Reply.UNCLEAR] i
 * decyzja wraca do użytkownika.
 */
object ConfirmationReply {

    enum class Reply { YES, NO, UNCLEAR }

    /**
     * @param text to, co usłyszał asystent
     * @return decyzja albo [Reply.UNCLEAR], gdy nie da się jej odczytać
     */
    fun parse(text: String?): Reply {
        val words = normalize(text ?: return Reply.UNCLEAR)
        if (words.isEmpty()) return Reply.UNCLEAR
        // Wielowyrazowe zwroty przed pojedynczymi słowami: "nie trzeba" ma być
        // odmową, a nie utknąć na słowie "trzeba".
        val joined = words.joinToString(" ")
        // WAHANIE ROZSTRZYGA SIĘ PIERWSZE I ZAWSZE NA NIE-WIEM.
        //
        // "Chyba tak by było dobrze" zawiera "tak" i bez tego szło jako zgoda -
        // czyli wahanie wysyłałoby maila. Złapane testem, nie przeglądem kodu.
        // Kto się waha, nie podjął decyzji, więc oddajemy mu ją z powrotem.
        HEDGES.forEach { if (joined.contains(it)) return Reply.UNCLEAR }
        NO_PHRASES.forEach { if (joined.contains(it)) return Reply.NO }
        for (word in words) {
            if (word in NO_WORDS) return Reply.NO
            if (word in YES_WORDS) return Reply.YES
        }
        return Reply.UNCLEAR
    }

    /**
     * Jak [parse], ale TYLKO gdy cała wypowiedź to słowa zgody/odmowy i
     * zwykłe dopowiedzenia ("tak, proszę", "dobra, dzięki"). "Zrób zdjęcie" i
     * "dodaj mleko" zawierają słowa zgody, ale są poleceniami - trafiają do
     * [Reply.UNCLEAR], czyli idą dalej jako nowa tura.
     */
    fun parseStrict(text: String?): Reply {
        val words = normalize(text ?: return Reply.UNCLEAR)
        if (words.isEmpty()) return Reply.UNCLEAR
        val znane = YES_WORDS + NO_WORDS + FILLER +
            NO_PHRASES.flatMap { it.split(' ') } + HEDGES.flatMap { it.split(' ') }
        if (!words.all { it in znane }) return Reply.UNCLEAR
        // Samo "zrób"/"dodaj"/"wyślij" bez dopowiedzenia to początek polecenia.
        if (words.size == 1 && words[0] in COMMAND_VERBS) return Reply.UNCLEAR
        return parse(text)
    }

    private val FILLER = setOf(
        "to", "prosze", "dzieki", "dziekuje", "bardzo", "na", "pewno", "juz", "teraz",
        "wszystko", "tak", "sobie", "mi", "wlasnie", "oczywiscie", "wyslij", "jasne"
    )

    private val COMMAND_VERBS = setOf("rob", "zrob", "dodaj", "wykonaj")

    /**
     * Rozbija na słowa bez znaków przestankowych i bez polskich ogonków.
     *
     * Ogonki lecą, bo rozpoznawanie mowy bywa w tym niekonsekwentne i zwraca raz
     * "wyslij", raz "wyślij" - a to ma być ta sama decyzja.
     */
    private fun normalize(text: String): List<String> =
        text.lowercase()
            .map { ch -> DIACRITICS[ch] ?: ch }
            .joinToString("")
            .split(' ', ',', '.', '!', '?', ';', ':', '\n', '\t')
            .filter { it.isNotBlank() }

    private val DIACRITICS = mapOf(
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l',
        'ń' to 'n', 'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z'
    )

    /**
     * Odmowy złożone z kilku słów.
     *
     * Sprawdzane PRZED pojedynczymi, bo inaczej "nie trzeba" i "daj spokoj"
     * przepadłyby albo trafiły w złą stronę.
     */
    /**
     * Wahanie - ani zgoda, ani odmowa.
     *
     * Sprawdzane PRZED wszystkim innym, bo te zwroty zwykle stoją obok słowa
     * "tak" albo "nie" i to one niosą prawdziwe znaczenie zdania.
     */
    private val HEDGES = listOf("chyba", "moze", "raczej", "nie wiem", "sam nie wiem")

    private val NO_PHRASES = listOf(
        "nie trzeba", "nie teraz", "daj spokoj", "jednak nie", "nie chce"
    )

    /**
     * Odmowy jednowyrazowe.
     *
     * Świadomie NIE ma tu "stop": to jest komenda uciszenia syntezatora i znaczy
     * "przestań mówić", a nie "nie wykonuj". Wpuszczenie jej tutaj kasowałoby
     * akcję każdemu, kto chciał tylko przerwać czytanie pytania.
     */
    private val NO_WORDS = setOf(
        "nie", "anuluj", "anulowac", "rezygnuje", "zostaw", "odrzuc", "odrzucam"
    )

    /**
     * Zgody.
     *
     * "Ok" jest tu świadomie, mimo że bywa potakiwaniem bez decyzji - przy pytaniu
     * wprost o wykonanie akcji jest jednak zgodą.
     *
     * NIE ma tu "pewnie" i nie będzie: "pewnie!" to zgoda, ale "pewnie tak" to
     * przypuszczenie, a różnicy nie słychać w transkrypcji. Przy akcji, której
     * nie da się cofnąć, dwuznaczność rozstrzygamy na niekorzyść wykonania.
     */
    private val YES_WORDS = setOf(
        "tak", "potwierdzam", "zgoda", "dobra", "dobrze", "jasne",
        "ok", "okej", "dawaj", "rob", "zrob", "wyslij", "dodaj", "wykonaj"
    )
}
