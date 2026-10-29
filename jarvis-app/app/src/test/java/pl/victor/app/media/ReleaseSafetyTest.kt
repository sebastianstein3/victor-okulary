package pl.victor.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.ble.MediaCount
import pl.victor.app.ble.MediaLibrary

class ReleaseSafetyTest {

    private fun item(name: String, saved: Boolean, onGlasses: Boolean = true) = MediaItem(
        name = name,
        kind = MediaLibrary.Kind.PHOTO,
        thumbnailPath = null,
        savedToPhone = saved,
        stillOnGlasses = onGlasses,
        firstSeenAtMs = 0
    )

    @Test
    fun `pusta lista nie pozwala kasowac`() {
        assertTrue(ReleaseSafety.checkArchive(emptyList()) is ReleaseSafety.Verdict.Refused)
        assertTrue(
            ReleaseSafety.checkArchive(listOf(item("a.jpg", true, onGlasses = false)))
                is ReleaseSafety.Verdict.Refused
        )
    }

    @Test
    fun `niezapisany plik blokuje`() {
        val v = ReleaseSafety.checkArchive(listOf(item("a.jpg", true), item("b.jpg", false)))
        assertTrue(v is ReleaseSafety.Verdict.Refused)
        assertTrue((v as ReleaseSafety.Verdict.Refused).reason.contains("1 z 2"))
    }

    @Test
    fun `wszystko zapisane pozwala`() {
        val v = ReleaseSafety.checkArchive(listOf(item("a.jpg", true), item("b.jpg", true)))
        assertEquals(ReleaseSafety.Verdict.Allowed(2), v)
    }

    @Test
    fun `nowe pliki na okularach blokuja`() {
        assertTrue(
            ReleaseSafety.checkCounters(2, MediaCount(2, 1, 0)) is ReleaseSafety.Verdict.Refused
        )
        assertTrue(ReleaseSafety.checkCounters(2, null) is ReleaseSafety.Verdict.Refused)
        assertEquals(
            ReleaseSafety.Verdict.Allowed(2),
            ReleaseSafety.checkCounters(2, MediaCount(2, 0, 0))
        )
    }
}
