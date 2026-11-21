package pl.victor.app.lists

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Listy użytkownika (zakupy, zadania, „na wyjazd”) - model danych i operacje.
 *
 * ## Czemu czysta logika, bez Androida
 * Bo tu są wszystkie decyzje, które da się zepsuć po cichu: czy „mleka” to to
 * samo co „mleko” (ma być), czy dwa razy „dodaj mleko” daje dwa wiersze (nie
 * ma dawać), co zostaje po wyczyszczeniu listy połączonej z Google Tasks (ślad,
 * żeby usunąć też tam). Zapis i synchronizacja to tylko obudowa - patrz
 * [ListsStore] i [pl.victor.app.google.TasksSync].
 *
 * Kodowanie do JSON ręczne (kotlinx.serialization.json bez wtyczki), bo te same
 * funkcje mają działać w testach JVM.
 */
object UserLists {

    /** Nazwa listy zakupów - tej, o którą chodzi, gdy nazwa nie pada („kupiłem chleb”). */
    const val SHOPPING = "zakupy"

    /** Nazwa listy zadań („do zrobienia”). */
    const val TASKS = "zadania"

    data class Item(
        /** Stały identyfikator lokalny - łączy wiersz z zadaniem Google po jego utworzeniu. */
        val id: String,
        /** Co - bez liczby: „mleka”, „chleb”, „krem do opalania”. */
        val text: String,
        /** Ile - `null`, gdy nie padło. */
        val quantity: Int? = null,
        val done: Boolean = false,
        /** Identyfikator zadania w Google Tasks albo `null`, gdy jeszcze go tam nie ma. */
        val googleId: String? = null,
        /** Ostatnia zmiana lokalna - rozstrzyga konflikt z Google ([TasksMerge]). */
        val updatedMs: Long = 0L
    ) {
        /** „2 mleka”, „chleb” - tak, jak to się mówi i jak idzie do Google Tasks. */
        val display: String get() = if (quantity != null && quantity != 1) "$quantity $text" else text
    }

    data class UserList(
        /** Nazwa kanoniczna, małymi literami: „zakupy”, „na wyjazd”. */
        val name: String,
        val items: List<Item> = emptyList(),
        /** Identyfikator listy zadań w Google Tasks, gdy połączona. */
        val googleListId: String? = null,
        /**
         * Zadania Google usunięte LOKALNIE, a jeszcze nie w Google. Bez tego
         * następna synchronizacja uznałaby je za nowe i przywróciła na listę.
         */
        val deletedGoogleIds: List<String> = emptyList()
    ) {
        val open: List<Item> get() = items.filter { !it.done }

        /** Nazwa do pokazania i dla Google Tasks: „Zakupy”, „Na wyjazd”. */
        val title: String get() = name.replaceFirstChar { it.uppercase() }
    }

    /** Pozycja z polecenia: tekst i opcjonalna liczba. */
    data class Entry(val text: String, val quantity: Int? = null)

    /** Wynik operacji: nowa lista i co się z pozycjami stało (do odpowiedzi głosem). */
    data class Change(
        val list: UserList,
        val added: List<String> = emptyList(),
        val alreadyThere: List<String> = emptyList(),
        val changed: List<String> = emptyList(),
        val missing: List<String> = emptyList()
    )

    // === Operacje ===

    /**
     * Dodaje pozycje. Ta sama rzecz drugi raz nie robi drugiego wiersza: liczba
     * podana wprost nadpisuje poprzednią, a odhaczona wraca na listę.
     */
    fun add(list: UserList, entries: List<Entry>, now: Long, newId: () -> String): Change {
        val items = list.items.toMutableList()
        val added = mutableListOf<String>()
        val already = mutableListOf<String>()
        val changed = mutableListOf<String>()
        for (e in entries) {
            val text = e.text.trim()
            if (text.isEmpty()) continue
            val i = items.indexOfFirst { sameItem(it.text, text) }
            if (i < 0) {
                items += Item(newId(), text, e.quantity, done = false, updatedMs = now)
                added += Item("", text, e.quantity).display
                continue
            }
            val old = items[i]
            val qty = e.quantity ?: old.quantity
            if (old.done || qty != old.quantity) {
                // Z nową liczbą bierzemy też nową formę słowa: „2 mleka”, nie „2 mleko”.
                val form = if (e.quantity != null) text else old.text
                items[i] = old.copy(text = form, quantity = qty, done = false, updatedMs = now)
                changed += items[i].display
            } else {
                already += old.display
            }
        }
        return Change(list.copy(items = items), added = added, alreadyThere = already, changed = changed)
    }

    /** Usuwa pozycje. Usunięte połączone z Google trafiają do [UserList.deletedGoogleIds]. */
    fun remove(list: UserList, texts: List<String>): Change {
        val removed = mutableListOf<Item>()
        val missing = mutableListOf<String>()
        var items = list.items
        for (t in texts) {
            val hit = items.firstOrNull { sameItem(it.text, t) }
            if (hit == null) missing += t else {
                removed += hit
                items = items - hit
            }
        }
        val tomb = list.deletedGoogleIds + removed.mapNotNull { it.googleId }
        return Change(
            list.copy(items = items, deletedGoogleIds = tomb.distinct()),
            changed = removed.map { it.display },
            missing = missing
        )
    }

    /** Odhacza (kupione/zrobione) - pozycja zostaje, ale jako zrobiona. */
    fun checkOff(list: UserList, texts: List<String>, now: Long): Change {
        val items = list.items.toMutableList()
        val changed = mutableListOf<String>()
        val already = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (t in texts) {
            val i = items.indexOfFirst { sameItem(it.text, t) }
            when {
                i < 0 -> missing += t
                items[i].done -> already += items[i].display
                else -> {
                    items[i] = items[i].copy(done = true, updatedMs = now)
                    changed += items[i].display
                }
            }
        }
        return Change(list.copy(items = items), changed = changed, alreadyThere = already, missing = missing)
    }

    /** Zaznaczenie/odznaczenie pozycji z ekranu (po identyfikatorze, nie nazwie). */
    fun setDone(list: UserList, itemId: String, done: Boolean, now: Long): UserList =
        list.copy(items = list.items.map { if (it.id == itemId) it.copy(done = done, updatedMs = now) else it })

    /** Usunięcie pozycji z ekranu - z tym samym śladem dla Google co [remove]. */
    fun removeById(list: UserList, itemId: String): UserList {
        val hit = list.items.firstOrNull { it.id == itemId } ?: return list
        return list.copy(
            items = list.items - hit,
            deletedGoogleIds = (list.deletedGoogleIds + listOfNotNull(hit.googleId)).distinct()
        )
    }

    /** Usuwa wszystko z listy - lista zostaje, pusta. */
    fun clear(list: UserList): UserList =
        list.copy(
            items = emptyList(),
            deletedGoogleIds = (list.deletedGoogleIds + list.items.mapNotNull { it.googleId }).distinct()
        )

    /** Lista, w której jest dana pozycja - do „odhacz chleb” bez nazwy listy. Zakupy najpierw. */
    fun findListWith(lists: List<UserList>, text: String, openOnly: Boolean = true): UserList? =
        lists.sortedBy { if (it.name == SHOPPING) 0 else 1 }
            .firstOrNull { l -> l.items.any { (!openOnly || !it.done) && sameItem(it.text, text) } }

    /** Lista o tej nazwie (z odmianą: „zakupów” = „zakupy”) albo `null`. */
    fun find(lists: List<UserList>, name: String): UserList? {
        val canon = ListCommands.canonicalListName(name)
        return lists.firstOrNull { it.name == canon } ?: lists.firstOrNull { sameName(it.name, canon) }
    }

    // === Mowa ===

    /** Odczytanie listy na głos: tylko to, co zostało do zrobienia. */
    fun speak(list: UserList?, askedName: String): String {
        val label = listLabel(list?.name ?: ListCommands.canonicalListName(askedName))
        val open = list?.open.orEmpty()
        if (open.isEmpty()) {
            val done = list?.items?.count { it.done } ?: 0
            return if (done > 0) "Na $label wszystko odhaczone." else "Na $label nic nie ma."
        }
        return "Na $label: " + open.joinToString(", ") { it.display } + "."
    }

    /** „liście zakupów”, „liście zadań”, „liście na wyjazd”. */
    fun listLabel(name: String): String = when (name) {
        SHOPPING -> "liście zakupów"
        TASKS -> "liście zadań"
        else -> "liście $name"
    }

    /** Biernik: „listę zakupów”, „listę na wyjazd”. */
    fun listLabelAccusative(name: String): String = "listę " + listLabelGenitive(name).removePrefix("listy ")

    /** Mianownik: „Lista zakupów”, „Lista na wyjazd”. */
    fun listLabelNominative(name: String): String = "Lista " + listLabelGenitive(name).removePrefix("listy ")

    /** Jak [listLabel], ale w dopełniaczu: „z listy zakupów”, „do listy zakupów”. */
    fun listLabelGenitive(name: String): String = when (name) {
        SHOPPING -> "listy zakupów"
        TASKS -> "listy zadań"
        else -> "listy $name"
    }

    // === Dopasowanie ===

    /**
     * Czy dwie nazwy pozycji to ta sama rzecz - mimo odmiany.
     *
     * „mleko”/„mleka”, „chleb”/„chleba”, „jajka”/„jajek”, „bułki”/„bułek”: słowa
     * mają tyle samo wyrazów, a każde para ma wspólny początek długi na tyle,
     * że różnią się co najwyżej końcówką (2 litery, 3 przy dłuższych). Bez
     * ogonków, bo rozpoznawanie mowy raz je stawia, raz nie.
     */
    fun sameItem(a: String, b: String): Boolean {
        val ta = words(a)
        val tb = words(b)
        if (ta.isEmpty() || ta.size != tb.size) return false
        return ta.zip(tb).all { (x, y) -> sameWord(x, y) }
    }

    /** Nazwy list: to samo co pozycje, plus „lista zakupów” = „zakupy”. */
    fun sameName(a: String, b: String): Boolean =
        sameItem(ListCommands.canonicalListName(a), ListCommands.canonicalListName(b))

    private fun sameWord(x: String, y: String): Boolean {
        if (x == y) return true
        val common = x.zip(y).takeWhile { (c1, c2) -> c1 == c2 }.size
        val shorter = minOf(x.length, y.length)
        if (shorter < 3) return false
        val allowed = if (shorter >= 7) 3 else 2
        return common >= 3 && x.length - common <= allowed && y.length - common <= allowed
    }

    fun words(s: String): List<String> =
        normalize(s).split(' ').filter { it.isNotBlank() }

    fun normalize(s: String): String = s.lowercase()
        .replace("ą", "a").replace("ć", "c").replace("ę", "e")
        .replace("ł", "l").replace("ń", "n").replace("ó", "o")
        .replace("ś", "s").replace("ź", "z").replace("ż", "z")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    // === Zapis ===

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(lists: List<UserList>): String = buildJsonArray {
        lists.forEach { l ->
            add(buildJsonObject {
                put("name", l.name)
                l.googleListId?.let { put("googleListId", it) }
                put("deleted", JsonArray(l.deletedGoogleIds.map { JsonPrimitive(it) }))
                put("items", JsonArray(l.items.map { i ->
                    buildJsonObject {
                        put("id", i.id)
                        put("text", i.text)
                        i.quantity?.let { put("qty", it) }
                        put("done", i.done)
                        i.googleId?.let { put("gid", it) }
                        put("upd", i.updatedMs)
                    }
                }))
            })
        }
    }.toString()

    /** Odczyt; uszkodzony zapis daje pustą listę zamiast wywrócić aplikację. */
    fun decode(raw: String?): List<UserList> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                UserList(
                    name = name,
                    googleListId = o["googleListId"]?.jsonPrimitive?.contentOrNull,
                    deletedGoogleIds = (o["deleted"] as? JsonArray).orEmpty()
                        .mapNotNull { it.jsonPrimitive.contentOrNull },
                    items = (o["items"] as? JsonArray).orEmpty().mapNotNull { ie ->
                        val io = ie.jsonObject
                        Item(
                            id = io["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                            text = io["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                            quantity = io["qty"]?.jsonPrimitive?.intOrNull,
                            done = io["done"]?.jsonPrimitive?.booleanOrNull ?: false,
                            googleId = io["gid"]?.jsonPrimitive?.contentOrNull,
                            updatedMs = io["upd"]?.jsonPrimitive?.longOrNull ?: 0L
                        )
                    }
                )
            }
        }.getOrDefault(emptyList())
    }
}
