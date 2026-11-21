package pl.victor.app.google

import android.content.Context
import android.util.Log
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.tasks.Tasks
import com.google.api.services.tasks.model.Task
import com.google.api.services.tasks.model.TaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.victor.app.lists.ListsStore
import pl.victor.app.lists.TasksMerge
import pl.victor.app.lists.UserLists

/**
 * Synchronizacja list z Google Tasks - lista lokalna ↔ lista zadań Google o
 * tej samej nazwie („zakupy” ↔ „Zakupy”, także istniejąca „Lista zakupów”).
 *
 * API jest darmowe, ale wymaga osobnej zgody (zakres `tasks`) - pytanej
 * dopiero przy włączeniu przełącznika w Ustawieniach. Bez zgody albo bez
 * sieci listy działają lokalnie, a ta klasa zwraca [Result.NoAccess] /
 * [Result.Failed] i niczego nie psuje.
 *
 * Google Keep nie ma publicznego API dla kont prywatnych (tylko Workspace),
 * dlatego synchronizacja idzie do Tasks - te listy widać też w Gmailu,
 * Kalendarzu i aplikacji Google Tasks.
 *
 * Decyzje „co wygrywa” są w [TasksMerge] (czysta logika z testami); tutaj jest
 * wyłącznie sieć.
 */
class TasksSync(context: Context) {

    private val tag = "TasksSync"
    private val accountManager = GoogleAccountManager(context)

    fun hasAccess(): Boolean = accountManager.hasTasksAccess()

    sealed class Result {
        data class Success(
            val list: UserLists.UserList,
            val created: Int,
            val updated: Int,
            val deleted: Int
        ) : Result()

        object NoAccess : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Łączy jedną listę z jej odpowiednikiem w Google (tworzy go, gdy go nie ma). */
    suspend fun sync(list: UserLists.UserList): Result = withContext(Dispatchers.IO) {
        val credential = accountManager.getTasksCredential() ?: return@withContext Result.NoAccess
        try {
            val service = Tasks.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("V.I.C.T.O.R.")
                .build()
            val listId = remoteListId(service, list)
            val remote = mutableListOf<TasksMerge.RemoteTask>()
            var pageToken: String? = null
            do {
                val page = service.tasks().list(listId)
                    .setShowCompleted(true)
                    .setShowHidden(true)
                    .setMaxResults(100)
                    .setPageToken(pageToken)
                    .execute()
                page.items.orEmpty().forEach { t ->
                    remote += TasksMerge.RemoteTask(
                        id = t.id,
                        title = t.title.orEmpty(),
                        done = t.status == STATUS_DONE,
                        updatedMs = TasksMerge.parseRfc3339(t.updated),
                        deleted = t.deleted == true,
                        hidden = t.hidden == true
                    )
                }
                pageToken = page.nextPageToken
            } while (pageToken != null)

            val plan = TasksMerge.merge(list.copy(googleListId = listId), remote, ListsStore::newId)
            plan.delete.forEach { id ->
                runCatching { service.tasks().delete(listId, id).execute() }
                    .onFailure { Log.w(tag, "Nie udało się usunąć zadania $id", it) }
            }
            plan.update.forEach { (id, item) ->
                service.tasks().patch(listId, id, toTask(item)).execute()
            }
            val created = plan.create.associate { item ->
                item.id to service.tasks().insert(listId, toTask(item)).execute().id
            }
            Log.i(
                tag,
                "Lista ${list.name}: +${created.size} ~${plan.update.size} -${plan.delete.size}, " +
                    "w Google ${remote.size}"
            )
            Result.Success(
                TasksMerge.withCreatedIds(plan.local, created),
                created = created.size, updated = plan.update.size, deleted = plan.delete.size
            )
        } catch (e: Exception) {
            if (GoogleAccountManager.noteApiFailure(e)) {
                Log.w(tag, "Google Tasks: logowanie wygasło", e)
                Result.Failed("logowanie Google wygasło")
            } else {
                Log.w(tag, "Synchronizacja z Google Tasks nie powiodła się", e)
                Result.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Identyfikator listy zadań: zapamiętany, a gdy zniknął - po nazwie;
     * gdy i po nazwie nie ma - nowa lista.
     */
    private fun remoteListId(service: Tasks, list: UserLists.UserList): String {
        list.googleListId?.let { id ->
            val alive = runCatching { service.tasklists().get(id).execute() }.getOrNull()
            if (alive != null) return id
        }
        val existing = service.tasklists().list().setMaxResults(100).execute().items.orEmpty()
            .firstOrNull { UserLists.sameName(it.title.orEmpty(), list.name) }
        if (existing != null) return existing.id
        return service.tasklists().insert(TaskList().setTitle(list.title)).execute().id
    }

    private fun toTask(item: UserLists.Item): Task =
        Task().setTitle(item.display).setStatus(if (item.done) STATUS_DONE else STATUS_OPEN)

    private companion object {
        const val STATUS_DONE = "completed"
        const val STATUS_OPEN = "needsAction"
    }
}
