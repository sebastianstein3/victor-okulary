package pl.victor.app.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.lists.UserLists.Entry

class UserListsTest {

    private var n = 0
    private val ids: () -> String = { "id${++n}" }
    private val empty = UserLists.UserList(UserLists.SHOPPING)

    @Test
    fun `odmiana to ta sama pozycja`() {
        assertTrue(UserLists.sameItem("mleko", "mleka"))
        assertTrue(UserLists.sameItem("chleb", "chleba"))
        assertTrue(UserLists.sameItem("bułki", "bułek"))
        assertTrue(UserLists.sameItem("Masło", "maslo"))
        assertTrue(UserLists.sameItem("krem do opalania", "kremu do opalania"))
        assertFalse(UserLists.sameItem("mleko", "masło"))
        assertFalse(UserLists.sameItem("ser", "sok"))
        assertFalse(UserLists.sameItem("krem", "krem do opalania"))
    }

    @Test
    fun `dodanie tego samego nie dubluje a liczba nadpisuje`() {
        val a = UserLists.add(empty, listOf(Entry("mleko")), 1, ids)
        assertEquals(listOf("mleko"), a.added)
        val b = UserLists.add(a.list, listOf(Entry("mleka")), 2, ids)
        assertEquals(1, b.list.items.size)
        assertEquals(listOf("mleko"), b.alreadyThere)
        val c = UserLists.add(b.list, listOf(Entry("mleka", 2)), 3, ids)
        assertEquals(1, c.list.items.size)
        assertEquals("2 mleka", c.list.items.single().display)
        assertEquals(2, c.list.items.single().quantity)
        assertEquals(3L, c.list.items.single().updatedMs)
    }

    @Test
    fun `odhaczona pozycja wraca po ponownym dodaniu`() {
        val a = UserLists.add(empty, listOf(Entry("chleb")), 1, ids).list
        val b = UserLists.checkOff(a, listOf("chleba"), 2)
        assertEquals(listOf("chleb"), b.changed)
        assertTrue(b.list.items.single().done)
        assertTrue(b.list.open.isEmpty())
        val c = UserLists.add(b.list, listOf(Entry("chleb")), 3, ids)
        assertFalse(c.list.items.single().done)
        assertEquals(listOf("chleb"), c.changed)
    }

    @Test
    fun `usuniecie zostawia slad dla Google`() {
        val list = empty.copy(
            items = listOf(
                UserLists.Item("1", "mleko", googleId = "g1"),
                UserLists.Item("2", "chleb")
            )
        )
        val r = UserLists.remove(list, listOf("mleka", "ser"))
        assertEquals(listOf("mleko"), r.changed)
        assertEquals(listOf("ser"), r.missing)
        assertEquals(listOf("g1"), r.list.deletedGoogleIds)
        val cleared = UserLists.clear(list)
        assertTrue(cleared.items.isEmpty())
        assertEquals(listOf("g1"), cleared.deletedGoogleIds)
        assertEquals(listOf("g1"), UserLists.removeById(list, "1").deletedGoogleIds)
        assertTrue(UserLists.setDone(list, "2", true, 5).items.first { it.id == "2" }.done)
    }

    @Test
    fun `szuka listy z pozycja i po nazwie z odmiana`() {
        val zakupy = empty.copy(items = listOf(UserLists.Item("1", "chleb")))
        val wyjazd = UserLists.UserList("na wyjazd", items = listOf(UserLists.Item("2", "krem")))
        val all = listOf(wyjazd, zakupy)
        assertEquals("zakupy", UserLists.findListWith(all, "chleba")?.name)
        assertEquals("na wyjazd", UserLists.findListWith(all, "krem")?.name)
        assertNull(UserLists.findListWith(all, "ser"))
        assertEquals("zakupy", UserLists.find(all, "listy zakupów")?.name)
        assertEquals("na wyjazd", UserLists.find(all, "listy na wyjazd")?.name)
    }

    @Test
    fun `odczyt na glos`() {
        val list = empty.copy(
            items = listOf(
                UserLists.Item("1", "mleka", quantity = 2),
                UserLists.Item("2", "chleb", done = true),
                UserLists.Item("3", "masło")
            )
        )
        assertEquals("Na liście zakupów: 2 mleka, masło.", UserLists.speak(list, "zakupy"))
        assertEquals("Na liście zakupów nic nie ma.", UserLists.speak(null, "zakupów"))
        assertEquals(
            "Na liście na wyjazd wszystko odhaczone.",
            UserLists.speak(UserLists.UserList("na wyjazd", listOf(UserLists.Item("1", "krem", done = true))), "na wyjazd")
        )
    }

    @Test
    fun `zapis i odczyt JSON bez strat`() {
        val lists = listOf(
            UserLists.UserList(
                "zakupy",
                items = listOf(
                    UserLists.Item("1", "mleka", 2, done = false, googleId = "g1", updatedMs = 10),
                    UserLists.Item("2", "chleb \"razowy\"", null, done = true, googleId = null, updatedMs = 20)
                ),
                googleListId = "L1",
                deletedGoogleIds = listOf("g9")
            ),
            UserLists.UserList("na wyjazd")
        )
        assertEquals(lists, UserLists.decode(UserLists.encode(lists)))
        assertTrue(UserLists.decode("to nie jest json").isEmpty())
        assertTrue(UserLists.decode(null).isEmpty())
    }

    @Test
    fun `odpowiedzi glosowe`() {
        val c = UserLists.add(empty, listOf(Entry("mleka", 2), Entry("chleb")), 1, ids)
        assertEquals("Dodane do listy zakupów: 2 mleka, chleb.", ListReplies.added("zakupy", c))
        assertEquals("Wyczyściłem listę zakupów.", ListReplies.cleared("zakupy", true))
        assertEquals("Lista na wyjazd już jest pusta.", ListReplies.cleared("na wyjazd", false))
        val off = UserLists.checkOff(c.list, listOf("chleb"), 2)
        assertEquals("Odhaczone: chleb. Zostało: 1.", ListReplies.checkedOff("zakupy", off))
    }
}
