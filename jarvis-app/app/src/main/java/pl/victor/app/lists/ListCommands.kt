package pl.victor.app.lists

/**
 * Polecenia list rozpoznawane w warstwie 0 - bez modelu, bez sieci.
 *
 * ## Czemu wzorce, a nie model
 * Bo „dodaj mleko do listy zakupów” ma się dopisać w ułamku sekundy i za darmo,
 * także bez internetu w sklepie. Model potrafiłby odpowiedzieć „dodałem” i nie
 * dodać niczego - a to gorsze niż odmowa. Wzorce są ścisłe: każde polecenie
 * zawiera słowo „lista” (w którejś odmianie) albo „zakupy”, poza „odhacz” i
 * „kupiłem”, które orkiestrator wykonuje tylko wtedy, gdy pozycja NA LIŚCIE JEST.
 *
 * Polska odmiana: „do listy zakupów”, „na listę zakupów”, „z listy”, „na
 * liście”; liczby cyframi i słowami („2 mleka”, „dwa chleby”).
 */
object ListCommands {

    sealed class Command {
        data class Add(val list: String, val entries: List<UserLists.Entry>) : Command()

        /** `list == null` - lista, na której ta pozycja jest. */
        data class Remove(val list: String?, val items: List<String>) : Command()

        /**
         * Odhaczenie. [onlyIfPresent] - „kupiłem chleb” to też zwykłe zdanie w
         * rozmowie; wykonujemy je tylko, gdy chleb jest na którejś liście.
         */
        data class CheckOff(
            val list: String?,
            val items: List<String>,
            val onlyIfPresent: Boolean = false
        ) : Command()

        data class Show(val list: String) : Command()
        data class Clear(val list: String) : Command()
        data class Create(val list: String) : Command()
        object ShowAll : Command()
    }

    /** Krótka nazwa polecenia do dziennika: „Add”, „Show”. */
    fun describe(cmd: Command): String = cmd::class.simpleName ?: "?"

    private const val L = "\\p{L}"

    /** „lista”, „listy”, „listę”, „liście”… - samo słowo. */
    private const val LIST_WORD = "(?:list[$L]*|li[sś]ci[$L]*)"

    /** Odwołanie do listy: „listy zakupów”, „liście na wyjazd”, „listy”, „zakupów”. */
    private const val LIST_REF = "(?:$LIST_WORD(?:\\s+.+)?|zakup[$L]*)"

    private val ADD_ITEMS_FIRST = Regex(
        "^(?:dodaj|dopisz|wpisz|zapisz|wrzu[cć])\\s+(?<items>.+?)\\s+(?:do|na)\\s+(?<list>$LIST_REF)$"
    )
    private val ADD_LIST_FIRST = Regex(
        "^(?:dodaj|dopisz|wpisz|zapisz|wrzu[cć])\\s+(?:do|na)\\s+" +
            "(?<list>$LIST_WORD(?:\\s+zakup[$L]*)?|zakup[$L]*)\\s*[:,]?\\s+(?<items>.+)$"
    )
    private val ADD_BARE_SHOPPING = Regex(
        "^(?:lista\\s+zakup[$L]*|na\\s+list[eę]\\s+zakup[$L]*)\\s*[:,]?\\s+(?<items>.+)$"
    )
    private val SHOW = Regex(
        "^(?:co\\s+(?:mam|jest|zosta[lł]o|zosta[lł]o\\s+mi)\\s+(?:na|w)\\s+|co\\s+na\\s+|" +
            "(?:przeczytaj|poka[zż]|odczytaj|wyrecytuj)(?:\\s+mi)?\\s+|jaka\\s+jest\\s+|sprawd[zź]\\s+)" +
            "(?<list>$LIST_WORD(?:\\s+.+)?)$"
    )
    private val SHOW_SHOPPING = Regex(
        "^(?:co\\s+(?:mam|trzeba|muszę|musze)\\s+(?:jeszcze\\s+)?kupi[cć]|" +
            "(?:moja\\s+)?lista\\s+zakup[$L]*)$"
    )
    private val SHOW_ALL = Regex(
        "^(?:jakie\\s+(?:mam\\s+)?listy(?:\\s+mam)?|moje\\s+listy|jakie\\s+s[aą]\\s+(?:moje\\s+)?listy|poka[zż]\\s+(?:mi\\s+)?(?:moje\\s+)?listy)$"
    )
    private val REMOVE = Regex(
        "^(?:usu[nń]|skre[sś]l|wykre[sś]l|wyrzu[cć]|skasuj)\\s+(?<items>.+?)\\s+z\\s+(?<list>$LIST_REF)$"
    )
    private val CHECK_OFF = Regex(
        "^(?:odhacz|odznacz|zaznacz)\\s+(?<items>.+?)" +
            "(?:\\s+(?:z|na)\\s+(?<list>$LIST_REF))?$"
    )
    private val BOUGHT = Regex("^(?:kupi[lł]em|kupi[lł]am|mam\\s+ju[zż])\\s+(?<items>.+)$")
    private val CLEAR = Regex(
        "^(?:wyczy[sś][cć]|opr[oó][zż]nij|skasuj|usu[nń])\\s+(?:ca[lł][aąe]\\s+)?" +
            "(?<list>list[$L]*(?:\\s+.+)?)$"
    )
    private val CREATE = Regex(
        "^(?:(?:utw[oó]rz|za[lł][oó][zż]|zr[oó]b|stw[oó]rz)\\s+(?:now[aąe]\\s+)?list[eę]\\s+|nowa\\s+lista\\s+)(?<list>.+)$"
    )

    /** @return polecenie albo `null`, gdy to nie jest polecenie list */
    fun parse(text: String): Command? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?').trim()
            .replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null

        SHOW_ALL.matchEntire(t)?.let { return Command.ShowAll }
        SHOW_SHOPPING.matchEntire(t)?.let { return Command.Show(UserLists.SHOPPING) }
        CREATE.matchEntire(t)?.let { m ->
            return Command.Create(canonicalListName(m.groups["list"]!!.value))
        }
        // Usuwanie pozycji („usuń chleb z listy”) przed czyszczeniem listy
        // („usuń listę zakupów”) - oba zaczynają się tak samo.
        REMOVE.matchEntire(t)?.let { m ->
            val items = splitItems(m.groups["items"]!!.value).map { it.text }
            if (items.isEmpty()) return null
            return Command.Remove(listOrNullIfBare(m.groups["list"]!!.value), items)
        }
        CLEAR.matchEntire(t)?.let { m ->
            return Command.Clear(canonicalListName(m.groups["list"]!!.value))
        }
        SHOW.matchEntire(t)?.let { m ->
            return Command.Show(canonicalListName(m.groups["list"]!!.value))
        }
        for (re in listOf(ADD_ITEMS_FIRST, ADD_LIST_FIRST)) {
            re.matchEntire(t)?.let { m ->
                val entries = splitItems(m.groups["items"]!!.value)
                if (entries.isEmpty()) return null
                return Command.Add(canonicalListName(m.groups["list"]!!.value), entries)
            }
        }
        ADD_BARE_SHOPPING.matchEntire(t)?.let { m ->
            val entries = splitItems(m.groups["items"]!!.value)
            if (entries.isEmpty()) return null
            return Command.Add(UserLists.SHOPPING, entries)
        }
        CHECK_OFF.matchEntire(t)?.let { m ->
            val items = splitItems(stripDoneSuffix(m.groups["items"]!!.value)).map { it.text }
            if (items.isEmpty()) return null
            return Command.CheckOff(m.groups["list"]?.value?.let(::listOrNullIfBare), items)
        }
        BOUGHT.matchEntire(t)?.let { m ->
            // „Kupiłem wczoraj nowy telefon i nie wiem, jak go ustawić” to
            // rozmowa. Krótkie zdanie to meldunek z zakupów.
            val raw = m.groups["items"]!!.value
            if (raw.split(' ').size > MAX_BOUGHT_WORDS) return null
            val items = splitItems(raw).map { it.text }
            if (items.isEmpty()) return null
            return Command.CheckOff(null, items, onlyIfPresent = true)
        }
        return null
    }

    private const val MAX_BOUGHT_WORDS = 6

    /** „z listy” bez nazwy - lista, na której ta pozycja jest (`null`). */
    private fun listOrNullIfBare(ref: String): String? =
        if (BARE_LIST.matches(ref.trim())) null else canonicalListName(ref)

    private val BARE_LIST = Regex("^$LIST_WORD$")

    private fun stripDoneSuffix(items: String): String =
        items.replace(Regex("\\s+jako\\s+(?:kupion|zrobion|gotow)[$L]*$"), "")

    /**
     * Nazwa listy z tego, jak padła: „listy zakupów” → „zakupy”, „liście zadań”
     * → „zadania”, „listy na wyjazd” → „na wyjazd”. Samo „lista” → zakupy, bo o
     * nią chodzi w zdecydowanej większości.
     */
    fun canonicalListName(phrase: String): String {
        var p = phrase.lowercase().trim().trimEnd('.', '!', '?', ',').trim()
            .replace(Regex("\\s+"), " ")
        p = p.replace(Regex("^$LIST_WORD\\s*"), "").trim()
        if (p.isEmpty()) return UserLists.SHOPPING
        val n = UserLists.normalize(p)
        val words = n.split(' ')
        if (words.size <= 2 && words.any { it.startsWith("zakup") }) return UserLists.SHOPPING
        if (words.size <= 2 && words.any { it.startsWith("zadan") || it == "todo" }) return UserLists.TASKS
        if (n == "do zrobienia" || n == "rzeczy do zrobienia") return UserLists.TASKS
        return p
    }

    /**
     * „mleko, 2 chleby i masło” → trzy pozycje, z liczbami.
     * Przecinek, „i”, „oraz”, „a także” - tak wylicza się po polsku.
     */
    fun splitItems(text: String): List<UserLists.Entry> =
        text.lowercase()
            .split(Regex("\\s*,\\s*|\\s+(?:i|oraz|a\\s+tak[zż]e)\\s+"))
            .map { it.trim().removePrefix("jeszcze ").removePrefix("też ").removeSuffix(" też").trim() }
            .filter { it.isNotBlank() }
            .map(::parseEntry)
            .filter { it.text.isNotBlank() }

    /** „2 mleka” → (mleka, 2); „dwa chleby” → (chleby, 2); „mleko” → (mleko, null). */
    fun parseEntry(text: String): UserLists.Entry {
        val t = text.trim()
        Regex("^(\\d{1,3})\\s*(?:x|×|szt\\.?|sztuki|sztuk)?\\s+(.+)$").matchEntire(t)?.let { m ->
            return UserLists.Entry(m.groupValues[2].trim(), m.groupValues[1].toInt())
        }
        val first = t.substringBefore(' ')
        val rest = t.substringAfter(' ', "")
        val n = NUMBER_WORDS[UserLists.normalize(first)]
        if (n != null && rest.isNotBlank()) return UserLists.Entry(rest.trim(), n)
        return UserLists.Entry(t, null)
    }

    private val NUMBER_WORDS = mapOf(
        "jeden" to 1, "jedna" to 1, "jedno" to 1,
        "dwa" to 2, "dwie" to 2, "dwoch" to 2, "dwoje" to 2,
        "trzy" to 3, "trzech" to 3, "troje" to 3,
        "cztery" to 4, "czterech" to 4,
        "piec" to 5, "szesc" to 6, "siedem" to 7, "osiem" to 8,
        "dziewiec" to 9, "dziesiec" to 10, "dwanascie" to 12, "tuzin" to 12
    )
}
