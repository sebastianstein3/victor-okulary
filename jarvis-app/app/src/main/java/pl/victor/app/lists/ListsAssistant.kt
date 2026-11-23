package pl.victor.app.lists

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import pl.victor.app.data.SettingsRepository
import pl.victor.app.google.TasksSync

/**
 * Wykonanie poleceń list (warstwa 0 orkiestratora) i synchronizacja z Google
 * Tasks w tle. Rozpoznanie zdań jest w [ListCommands], operacje w [UserLists],
 * odpowiedzi w [ListReplies] - tu tylko klej z zapisem i siecią.
 *
 * Synchronizacja NIGDY nie opóźnia zmiany: zapis lokalny idzie od razu, a
 * Google dostaje ją po fakcie. Wyjątek to odczyt listy - wtedy najpierw (z
 * krótkim limitem) dociągamy zmiany z Google, bo „co mam na liście” po
 * dopisaniu czegoś w Google Tasks na komputerze ma to pokazać.
 */
class ListsAssistant(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val dziennik: (String, Map<String, Any?>) -> Unit
) {
    private val store = ListsStore.get(context)
    private val tasks = TasksSync(context)

    /**
     * Czy to polecenie wykonujemy. „Kupiłem chleb” tylko wtedy, gdy chleb
     * jest na którejś liście - inaczej to zwykłe zdanie i idzie do modelu.
     */
    fun accepts(cmd: ListCommands.Command): Boolean =
        cmd !is ListCommands.Command.CheckOff || !cmd.onlyIfPresent ||
            cmd.items.any { UserLists.findListWith(store.all(), it) != null }

    /** Wykonuje polecenie i zwraca odpowiedź do powiedzenia. */
    suspend fun handle(cmd: ListCommands.Command): String {
        val now = System.currentTimeMillis()
        val speech: String
        var touched: String? = null
        when (cmd) {
            is ListCommands.Command.Add -> {
                val change = store.update { all ->
                    val list = UserLists.find(all, cmd.list) ?: UserLists.UserList(cmd.list)
                    val c = UserLists.add(list, cmd.entries, now, ListsStore::newId)
                    ListsStore.replace(all, c.list) to c
                }
                touched = change.list.name
                speech = ListReplies.added(change.list.name, change)
                log("lista: dodano", change.list.name, mapOf("pozycje" to cmd.entries.size, "nowe" to change.added.size))
            }
            is ListCommands.Command.Remove -> {
                val name = cmd.list ?: UserLists.findListWith(store.all(), cmd.items.first(), openOnly = false)?.name
                    ?: UserLists.SHOPPING
                val change = store.update { all ->
                    val list = UserLists.find(all, name) ?: UserLists.UserList(name)
                    val c = UserLists.remove(list, cmd.items)
                    (if (UserLists.find(all, name) == null) all else ListsStore.replace(all, c.list)) to c
                }
                touched = change.list.name
                speech = ListReplies.removed(change.list.name, change)
                log("lista: usunięto", change.list.name, mapOf("usunięte" to change.changed.size, "brak" to change.missing.size))
            }
            is ListCommands.Command.CheckOff -> {
                val name = cmd.list ?: UserLists.findListWith(store.all(), cmd.items.first())?.name
                    ?: UserLists.SHOPPING
                val change = store.update { all ->
                    val list = UserLists.find(all, name) ?: UserLists.UserList(name)
                    val c = UserLists.checkOff(list, cmd.items, now)
                    (if (UserLists.find(all, name) == null) all else ListsStore.replace(all, c.list)) to c
                }
                touched = change.list.name
                speech = ListReplies.checkedOff(change.list.name, change)
                log("lista: odhaczono", change.list.name, mapOf("odhaczone" to change.changed.size, "brak" to change.missing.size))
            }
            is ListCommands.Command.Clear -> {
                val had = store.update { all ->
                    val list = UserLists.find(all, cmd.list)
                    if (list == null) all to false
                    else ListsStore.replace(all, UserLists.clear(list)) to list.items.isNotEmpty()
                }
                touched = UserLists.find(store.all(), cmd.list)?.name
                speech = ListReplies.cleared(cmd.list, had)
                log("lista: wyczyszczono", cmd.list, mapOf("było" to had))
            }
            is ListCommands.Command.Create -> {
                val existed = store.update { all ->
                    if (UserLists.find(all, cmd.list) != null) all to true
                    else (all + UserLists.UserList(cmd.list)) to false
                }
                touched = cmd.list
                speech = ListReplies.created(cmd.list, existed)
                log("lista: utworzona", cmd.list, mapOf("była" to existed))
            }
            is ListCommands.Command.Show -> {
                val name = UserLists.find(store.all(), cmd.list)?.name ?: cmd.list
                pullBeforeRead(name)
                val list = UserLists.find(store.all(), name)
                log("lista: odczyt", name, mapOf("otwarte" to (list?.open?.size ?: 0)))
                return UserLists.speak(list, name)
            }
            ListCommands.Command.ShowAll -> {
                log("lista: wszystkie", "-", mapOf("list" to store.all().size))
                return ListReplies.all(store.all())
            }
        }
        touched?.let { syncInBackground(it) }
        return speech
    }

    private fun log(co: String, lista: String, pola: Map<String, Any?>) {
        runCatching { dziennik(co, mapOf("lista" to lista) + pola) }
    }

    private fun syncEnabled(): Boolean = settings.isListsTasksSyncEnabled() && tasks.hasAccess()

    /** Dociąga zmiany z Google przed odczytem - najwyżej [PULL_TIMEOUT_MS]. */
    private suspend fun pullBeforeRead(name: String) {
        if (!syncEnabled()) return
        // Synchronizacja idzie w osobnej korutynie: limit przerywa CZEKANIE, nie
        // ją samą. Blokujące wywołania Google i tak nie dają się anulować, a
        // anulowanie gubiło ich wynik, choć zmiany w Google już zaszły.
        val sync = scope.async { syncNow(name) }
        val done = withTimeoutOrNull(PULL_TIMEOUT_MS) { sync.await() }
        if (done == null) log("lista: Google Tasks za wolno - czytam lokalną", name, emptyMap())
    }

    /** Wysyła zmianę do Google w tle; bez zgody/sieci nic się nie dzieje. */
    fun syncInBackground(name: String) {
        if (!syncEnabled()) return
        scope.launch { syncNow(name) }
    }

    /**
     * Jedna synchronizacja listy. Gdy w trakcie lista zmieniła się lokalnie
     * (ktoś dopisał coś głosem), nie nadpisujemy tej zmiany wynikiem: dopisujemy
     * tylko identyfikatory utworzonych zadań i synchronizujemy jeszcze raz.
     */
    suspend fun syncNow(name: String): TasksSync.Result = syncLock.withLock { syncLocked(name) }

    /**
     * Jedna synchronizacja naraz. Dwie równoległe ("dodaj mleko", zaraz potem
     * "dodaj chleb") obie widziały w Google stan bez mleka i obie je tworzyły -
     * duplikaty w Google i na liście (przegląd 22.11).
     */
    private val syncLock = Mutex()

    private suspend fun syncLocked(name: String): TasksSync.Result {
        val before = UserLists.find(store.all(), name) ?: return TasksSync.Result.Failed("brak listy")
        val result = tasks.sync(before)
        when (result) {
            is TasksSync.Result.Success -> {
                val changedMeanwhile = store.update { all ->
                    val current = UserLists.find(all, name)
                    when {
                        current == null -> all to false
                        current == before -> ListsStore.replace(all, result.list) to false
                        else -> {
                            val ids = result.list.items.mapNotNull { i -> i.googleId?.let { i.id to it } }.toMap()
                            ListsStore.replace(
                                all,
                                TasksMerge.withCreatedIds(current, ids).copy(googleListId = result.list.googleListId)
                            ) to true
                        }
                    }
                }
                log(
                    "lista: Google Tasks zsynchronizowane", name,
                    mapOf(
                        "utworzone" to result.created, "zmienione" to result.updated,
                        "usunięte" to result.deleted, "zmianaWTrakcie" to changedMeanwhile
                    )
                )
                if (changedMeanwhile) scope.launch { syncNow(name) }
            }
            TasksSync.Result.NoAccess -> log("lista: Google Tasks bez zgody - tylko lokalnie", name, emptyMap())
            is TasksSync.Result.Failed -> log("lista: Google Tasks błąd - tylko lokalnie", name, mapOf("powód" to result.reason))
        }
        return result
    }

    /** Wszystkie listy naraz - po włączeniu przełącznika i z ekranu. */
    suspend fun syncAll(): Int {
        var ok = 0
        store.all().map { it.name }.forEach { if (syncNow(it) is TasksSync.Result.Success) ok++ }
        return ok
    }

    private companion object {
        const val PULL_TIMEOUT_MS = 4_000L
    }
}
