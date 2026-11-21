package pl.victor.app.lists

/**
 * Odpowiedzi głosowe po operacjach na listach - czysta logika.
 *
 * Krótkie i z nazwą listy: w okularach nie widać ekranu, więc odpowiedź musi
 * powiedzieć, CO i GDZIE się zmieniło - „dodane” bez listy nie mówi, czy mleko
 * trafiło na zakupy, czy na listę na wyjazd.
 */
object ListReplies {

    fun added(listName: String, change: UserLists.Change): String {
        val parts = mutableListOf<String>()
        val label = UserLists.listLabelGenitive(listName)
        if (change.added.isNotEmpty()) parts += "Dodane do $label: ${change.added.joinToString(", ")}."
        if (change.changed.isNotEmpty()) parts += "Zmienione na liście: ${change.changed.joinToString(", ")}."
        if (change.alreadyThere.isNotEmpty()) {
            parts += "Już jest na liście: ${change.alreadyThere.joinToString(", ")}."
        }
        return parts.joinToString(" ").ifEmpty { "Nic nie dodałem - nie usłyszałem, co dopisać." }
    }

    fun removed(listName: String, change: UserLists.Change): String {
        val parts = mutableListOf<String>()
        if (change.changed.isNotEmpty()) {
            parts += "Usunięte z ${UserLists.listLabelGenitive(listName)}: ${change.changed.joinToString(", ")}."
        }
        if (change.missing.isNotEmpty()) parts += notThere(listName, change.missing)
        return parts.joinToString(" ")
    }

    fun checkedOff(listName: String, change: UserLists.Change): String {
        val parts = mutableListOf<String>()
        if (change.changed.isNotEmpty()) parts += "Odhaczone: ${change.changed.joinToString(", ")}."
        if (change.alreadyThere.isNotEmpty()) {
            parts += "Już było odhaczone: ${change.alreadyThere.joinToString(", ")}."
        }
        val open = change.list.open.size
        if (change.changed.isNotEmpty()) {
            parts += if (open == 0) "Na ${UserLists.listLabel(listName)} wszystko załatwione."
            else "Zostało: $open."
        }
        if (change.missing.isNotEmpty()) parts += notThere(listName, change.missing)
        return parts.joinToString(" ")
    }

    fun notThere(listName: String, items: List<String>): String =
        "Nie ma na ${UserLists.listLabel(listName)}: ${items.joinToString(", ")}."

    fun cleared(listName: String, hadItems: Boolean): String =
        if (hadItems) "Wyczyściłem ${UserLists.listLabelAccusative(listName)}."
        else "${UserLists.listLabelNominative(listName)} już jest pusta."

    fun created(listName: String, existed: Boolean): String {
        val l = UserLists.listLabelAccusative(listName)
        return if (existed) "Masz już $l." else "Utworzyłem $l."
    }

    fun all(lists: List<UserLists.UserList>): String {
        if (lists.isEmpty()) return "Nie masz jeszcze żadnych list. Powiedz na przykład: dodaj mleko do listy zakupów."
        return "Twoje listy: " + lists.joinToString(", ") { l ->
            val n = l.open.size
            "${l.name} ($n)"
        } + "."
    }
}
