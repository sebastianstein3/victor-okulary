package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Katalog jest jedynym miejscem, z którego użytkownik dowiaduje się, o co może
 * poprosić. Akcja bez wpisu to funkcja, o której nikt się nie dowie - a takich
 * w tej aplikacji było już kilka i za każdym razem kończyło się to zgłoszeniem
 * "mało funkcji widocznych".
 */
class CommandCatalogTest {

    @Test
    fun `kazda akcja ma wpis w katalogu`() {
        val missing = ActionType.entries.filter { CommandCatalog.byType(it) == null }
        assertTrue("Brak opisu dla: $missing", missing.isEmpty())
    }

    @Test
    fun `zaden typ nie jest opisany dwa razy`() {
        val duplicates = CommandCatalog.ALL.groupBy { it.type }.filter { it.value.size > 1 }
        assertTrue("Zdublowane: ${duplicates.keys}", duplicates.isEmpty())
    }

    @Test
    fun `kazda komenda ma nazwe opis i przyklad`() {
        CommandCatalog.ALL.forEach { info ->
            assertTrue("${info.type}: pusta nazwa", info.name.isNotBlank())
            assertTrue("${info.type}: pusty opis", info.whatItDoes.isNotBlank())
            assertTrue("${info.type}: brak przykładów", info.examples.isNotEmpty())
            info.examples.forEach {
                assertTrue("${info.type}: pusty przykład", it.isNotBlank())
            }
        }
    }

    @Test
    fun `grupowanie nie gubi zadnej komendy`() {
        val grouped = CommandCatalog.grouped().flatMap { it.second }
        assertEquals(CommandCatalog.ALL.size, grouped.size)
    }

    @Test
    fun `komendy wymagajace okularow to te od patrzenia`() {
        val needing = CommandCatalog.ALL.filter { it.needsGlasses }.map { it.type }.toSet()
        assertTrue(ActionType.TAKE_PHOTO in needing)
        assertTrue(ActionType.READ_TEXT in needing)
        assertTrue(ActionType.DESCRIBE_SCENE in needing)
        // Latarka jest w telefonie - okulary nie są do niej potrzebne.
        assertTrue(ActionType.TOGGLE_FLASHLIGHT !in needing)
    }

    @Test
    fun `w katalogu sa komendy z kamera i bez niej`() {
        // Tryb bez kamery chowa pozycje po fladze - musi więc istnieć jedno i drugie.
        assertTrue(CommandCatalog.byType(ActionType.DESCRIBE_SCENE)!!.needsCamera)
        assertTrue(CommandCatalog.byType(ActionType.TAKE_PHOTO)!!.needsCamera)
        assertFalse(CommandCatalog.byType(ActionType.SEND_SMS)!!.needsCamera)
        assertFalse(CommandCatalog.byType(ActionType.MAKE_CALL)!!.needsCamera)
    }

    @Test
    fun `tryb bez kamery chowa tylko komendy z kamera`() {
        val bezKamery = CommandCatalog.grouped(cameraFree = true).flatMap { it.second }
        assertTrue(bezKamery.none { it.needsCamera })
        assertEquals(CommandCatalog.ALL.count { !it.needsCamera }, bezKamery.size)
        // Żadna grupa nie zostaje pusta - puste znikają.
        assertTrue(CommandCatalog.grouped(cameraFree = true).all { it.second.isNotEmpty() })
    }

    @Test
    fun `tryb bez kamery chowa tryby z kamera`() {
        assertTrue(CommandCatalog.MODES.any { it.needsCamera })
        assertTrue(CommandCatalog.MODES.any { !it.needsCamera })
        val bezKamery = CommandCatalog.modes(cameraFree = true)
        assertTrue(bezKamery.none { it.needsCamera })
        assertEquals(CommandCatalog.MODES.size, CommandCatalog.modes().size)
    }

    @Test
    fun `zdjecie da sie znalezc po typie`() {
        assertNotNull(CommandCatalog.byType(ActionType.TAKE_PHOTO))
    }
}
