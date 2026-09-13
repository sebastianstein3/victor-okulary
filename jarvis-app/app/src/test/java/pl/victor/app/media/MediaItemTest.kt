package pl.victor.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.ble.MediaLibrary

class MediaItemTest {

    private fun item(name: String, seen: Long = 0L) = MediaItem(
        name = name,
        kind = MediaLibrary.kindOf(name),
        thumbnailPath = null,
        savedToPhone = false,
        stillOnGlasses = true,
        firstSeenAtMs = seen
    )

    @Test
    fun `nazwa ze sciezki pokazuje sie bez katalogow`() {
        assertEquals("IMG_0007.JPG", item("storage/sd0/C/DCIM/1/IMG_0007.JPG").displayName)
        assertEquals("IMG_0007.JPG", item("IMG_0007.JPG").displayName)
    }

    @Test
    fun `grupy ida w kolejnosci zdjecia wideo nagrania`() {
        val grouped = groupForDisplay(listOf(item("a.wav"), item("b.mp4"), item("c.jpg")))
        assertEquals(
            listOf(MediaLibrary.Kind.PHOTO, MediaLibrary.Kind.VIDEO, MediaLibrary.Kind.AUDIO),
            grouped.map { it.first }
        )
    }

    @Test
    fun `najnowsze na gorze`() {
        val grouped = groupForDisplay(
            listOf(item("IMG_0001.jpg"), item("IMG_0003.jpg"), item("IMG_0002.jpg"))
        )
        assertEquals(
            listOf("IMG_0003.jpg", "IMG_0002.jpg", "IMG_0001.jpg"),
            grouped.first().second.map { it.name }
        )
    }

    @Test
    fun `puste grupy nie trafiaja do wyniku`() {
        val grouped = groupForDisplay(listOf(item("c.jpg")))
        assertEquals(1, grouped.size)
        assertTrue(grouped.all { it.second.isNotEmpty() })
    }

    @Test
    fun `puste archiwum daje pusta liste`() {
        assertTrue(groupForDisplay(emptyList()).isEmpty())
    }
}
