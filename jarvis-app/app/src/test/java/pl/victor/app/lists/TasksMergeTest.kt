package pl.victor.app.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.lists.TasksMerge.RemoteTask
import pl.victor.app.lists.UserLists.Item

/**
 * Łączenie listy lokalnej z Google Tasks. Każda reguła z dokumentacji
 * [TasksMerge] ma tu swój test - pomyłka kończy się albo duplikatami na
 * liście, albo pozycją, która wraca po usunięciu.
 */
class TasksMergeTest {

    private var n = 0
    private val ids: () -> String = { "new${++n}" }
    private fun list(vararg items: Item, deleted: List<String> = emptyList()) =
        UserLists.UserList("zakupy", items.toList(), googleListId = "L", deletedGoogleIds = deleted)

    @Test
    fun `nowa pozycja lokalna trafia do utworzenia`() {
        val plan = TasksMerge.merge(list(Item("1", "mleko", updatedMs = 5)), emptyList(), ids)
        assertEquals(listOf("1"), plan.create.map { it.id })
        assertTrue(plan.update.isEmpty() && plan.delete.isEmpty())
        val linked = TasksMerge.withCreatedIds(plan.local, mapOf("1" to "g1"))
        assertEquals("g1", linked.items.single().googleId)
    }

    @Test
    fun `zadanie z Google dopisane lokalnie z liczba`() {
        val plan = TasksMerge.merge(list(), listOf(RemoteTask("g1", "2 mleka", false, 10)), ids)
        val item = plan.local.items.single()
        assertEquals("mleka", item.text)
        assertEquals(2, item.quantity)
        assertEquals("g1", item.googleId)
        assertTrue(plan.isNoop)
    }

    @Test
    fun `ta sama nazwa laczy sie bez duplikatu`() {
        val plan = TasksMerge.merge(
            list(Item("1", "chleb", updatedMs = 5)),
            listOf(RemoteTask("g1", "Chleba", false, 1)),
            ids
        )
        assertEquals(1, plan.local.items.size)
        assertEquals("g1", plan.local.items.single().googleId)
        assertTrue(plan.create.isEmpty())
    }

    @Test
    fun `nowsza zmiana wygrywa`() {
        // Lokalnie odhaczone później niż ostatnia zmiana w Google -> do Google.
        val local = Item("1", "chleb", done = true, googleId = "g1", updatedMs = 20)
        val p1 = TasksMerge.merge(list(local), listOf(RemoteTask("g1", "chleb", false, 10)), ids)
        assertEquals(listOf("g1"), p1.update.map { it.first })
        assertTrue(p1.local.items.single().done)
        // W Google odhaczone później -> lokalnie.
        val p2 = TasksMerge.merge(
            list(local.copy(done = false, updatedMs = 5)),
            listOf(RemoteTask("g1", "chleb", true, 10)), ids
        )
        assertTrue(p2.update.isEmpty())
        assertTrue(p2.local.items.single().done)
    }

    @Test
    fun `usuniete w Google znika lokalnie`() {
        val plan = TasksMerge.merge(
            list(Item("1", "chleb", googleId = "g1"), Item("2", "ser", googleId = "g2")),
            listOf(RemoteTask("g2", "ser", true, 1, hidden = true), RemoteTask("g3", "x", false, 1, deleted = true)),
            ids
        )
        assertTrue(plan.local.items.isEmpty())
        assertTrue(plan.isNoop)
    }

    @Test
    fun `usuniete lokalnie kasowane w Google i nie wraca`() {
        val plan = TasksMerge.merge(
            list(deleted = listOf("g1", "gone")),
            listOf(RemoteTask("g1", "mleko", false, 100)),
            ids
        )
        assertEquals(listOf("g1"), plan.delete)
        assertTrue(plan.local.items.isEmpty())
        assertTrue(plan.local.deletedGoogleIds.isEmpty())
    }

    @Test
    fun `bez zmian bez operacji`() {
        val plan = TasksMerge.merge(
            list(Item("1", "mleka", quantity = 2, googleId = "g1", updatedMs = 1)),
            listOf(RemoteTask("g1", "2 mleka", false, 1)),
            ids
        )
        assertTrue(plan.isNoop)
    }

    @Test
    fun `czas RFC 3339 z Google`() {
        assertEquals(1_763_547_330_000L, TasksMerge.parseRfc3339("2025-11-19T10:15:30.000Z"))
        assertEquals(0L, TasksMerge.parseRfc3339("bzdura"))
        assertEquals(0L, TasksMerge.parseRfc3339(null))
    }
}
