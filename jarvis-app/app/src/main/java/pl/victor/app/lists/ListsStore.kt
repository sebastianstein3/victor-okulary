package pl.victor.app.lists

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trwały zapis list - w SharedPreferences, jak notatki i przypomnienia w
 * miejscach. List jest kilka, pozycji dziesiątki - baza danych byłaby tu
 * przerostem, a jeden zapis JSON jest atomowy (`apply` podmienia całość).
 *
 * Jedna instancja na proces ([get]): głos (orkiestrator) i ekran Notatek
 * zmieniają te same listy, a [lists] pozwala ekranowi odświeżyć się po
 * „dodaj mleko” powiedzianym w trakcie, gdy jest otwarty.
 */
class ListsStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _lists = MutableStateFlow(UserLists.decode(prefs.getString(KEY, null)))
    val lists: StateFlow<List<UserLists.UserList>> = _lists.asStateFlow()

    fun all(): List<UserLists.UserList> = _lists.value

    /**
     * Zmienia listy atomowo względem innych zmian w tym procesie i zapisuje.
     * @return to, co zwróciła [block] (np. opis zmiany do odpowiedzi głosem)
     */
    @Synchronized
    fun <T> update(block: (List<UserLists.UserList>) -> Pair<List<UserLists.UserList>, T>): T {
        val (next, result) = block(_lists.value)
        _lists.value = next
        prefs.edit().putString(KEY, UserLists.encode(next)).apply()
        return result
    }

    /** Podmienia jedną listę (po nazwie) albo ją dokłada. */
    fun put(list: UserLists.UserList) {
        update { all -> replace(all, list) to Unit }
    }

    /** Usuwa całą listę (z ekranu). */
    fun deleteList(name: String) {
        update { all -> all.filterNot { it.name == name } to Unit }
    }

    companion object {
        private const val PREFS = "victor_lists"
        private const val KEY = "lists_v1"

        @Volatile
        private var instance: ListsStore? = null

        fun get(context: Context): ListsStore =
            instance ?: synchronized(this) {
                instance ?: ListsStore(context).also { instance = it }
            }

        fun replace(all: List<UserLists.UserList>, list: UserLists.UserList): List<UserLists.UserList> =
            if (all.any { it.name == list.name }) all.map { if (it.name == list.name) list else it }
            else all + list

        /** Identyfikator nowej pozycji - unikalny w obrębie telefonu. */
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
