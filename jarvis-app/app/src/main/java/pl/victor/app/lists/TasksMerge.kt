package pl.victor.app.lists

/**
 * Łączenie listy lokalnej z listą zadań Google o tej samej nazwie.
 *
 * ## Zasady (każda to osobny test)
 * - Pozycja połączona (ma `googleId`), zadanie istnieje: różnią się - wygrywa
 *   NOWSZA zmiana (lokalny `updatedMs` wobec `updated` z Google).
 * - Pozycja połączona, zadania już nie ma (usunięte albo ukryte po
 *   „wyczyść ukończone” w Google): znika też lokalnie.
 * - Pozycja bez `googleId`: gdy w Google jest niepołączone zadanie o tej samej
 *   nazwie - łączymy (bez duplikatu); inaczej tworzymy zadanie.
 * - Zadanie z Google, którego lokalnie nie ma: gdy jest na liście usuniętych
 *   lokalnie ([UserLists.UserList.deletedGoogleIds]) - kasujemy w Google;
 *   inaczej dopisujemy lokalnie (dodane na innym urządzeniu).
 *
 * Wynik to nowy stan lokalny i lista operacji do wykonania w Google - samo
 * wykonanie (sieć) jest w [pl.victor.app.google.TasksSync].
 */
object TasksMerge {

    /** Zadanie Google sprowadzone do tego, czego potrzebuje łączenie. */
    data class RemoteTask(
        val id: String,
        val title: String,
        val done: Boolean,
        val updatedMs: Long,
        val deleted: Boolean = false,
        val hidden: Boolean = false
    )

    data class Plan(
        /** Lista lokalna po połączeniu (bez `googleId` dla pozycji z [create]). */
        val local: UserLists.UserList,
        /** Pozycje do utworzenia w Google - po utworzeniu dopisz im `googleId`. */
        val create: List<UserLists.Item>,
        /** Zadania do nadpisania stanem lokalnym: (id zadania, pozycja). */
        val update: List<Pair<String, UserLists.Item>>,
        /** Zadania do usunięcia w Google. */
        val delete: List<String>
    ) {
        val isNoop: Boolean get() = create.isEmpty() && update.isEmpty() && delete.isEmpty()
    }

    fun merge(local: UserLists.UserList, remote: List<RemoteTask>, newId: () -> String): Plan {
        val alive = remote.filter { !it.deleted && !it.hidden && it.title.isNotBlank() }
        val byId = alive.associateBy { it.id }
        val linked = mutableSetOf<String>()
        val items = mutableListOf<UserLists.Item>()
        val create = mutableListOf<UserLists.Item>()
        val update = mutableListOf<Pair<String, UserLists.Item>>()
        val tomb = local.deletedGoogleIds.toSet()

        // Najpierw pozycje już połączone - żeby „wolne” zadania do łączenia po
        // nazwie były policzone dopiero po nich.
        val (withId, withoutId) = local.items.partition { it.googleId != null }
        for (item in withId) {
            val r = byId[item.googleId] ?: continue // usunięte w Google - znika lokalnie
            linked += r.id
            val remoteEntry = ListCommands.parseEntry(r.title)
            val same = r.done == item.done && UserLists.normalize(r.title) == UserLists.normalize(item.display)
            when {
                same -> items += item
                item.updatedMs > r.updatedMs -> {
                    items += item
                    update += r.id to item
                }
                else -> items += item.copy(
                    text = remoteEntry.text,
                    quantity = remoteEntry.quantity,
                    done = r.done,
                    updatedMs = r.updatedMs
                )
            }
        }
        for (item in withoutId) {
            val match = alive.firstOrNull {
                it.id !in linked && it.id !in tomb &&
                    UserLists.sameItem(ListCommands.parseEntry(it.title).text, item.text)
            }
            if (match == null) {
                items += item
                create += item
                continue
            }
            linked += match.id
            val joined = item.copy(googleId = match.id)
            if (match.done != item.done && item.updatedMs > match.updatedMs) {
                update += match.id to joined
                items += joined
            } else {
                items += joined.copy(done = match.done)
            }
        }
        val delete = mutableListOf<String>()
        for (r in alive) {
            if (r.id in linked) continue
            if (r.id in tomb) {
                delete += r.id
                continue
            }
            val e = ListCommands.parseEntry(r.title)
            items += UserLists.Item(
                id = newId(), text = e.text, quantity = e.quantity,
                done = r.done, googleId = r.id, updatedMs = r.updatedMs
            )
        }
        // Ślady usunięć są zużyte: te z Google idą teraz do skasowania, a tych,
        // których w Google już nie ma, nie ma po co pamiętać.
        return Plan(
            local = local.copy(items = items, deletedGoogleIds = emptyList()),
            create = create,
            update = update,
            delete = delete
        )
    }

    /** Po utworzeniu zadań w Google: dopisuje pozycjom ich identyfikatory. */
    fun withCreatedIds(list: UserLists.UserList, created: Map<String, String>): UserLists.UserList =
        list.copy(items = list.items.map { i -> created[i.id]?.let { i.copy(googleId = it) } ?: i })

    /**
     * Czas z Google (RFC 3339, np. „2026-11-19T10:15:30.000Z”) w ms; 0, gdy
     * nieczytelny - wtedy wygrywa strona lokalna.
     */
    fun parseRfc3339(value: String?): Long =
        value?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            ?: 0L
}
