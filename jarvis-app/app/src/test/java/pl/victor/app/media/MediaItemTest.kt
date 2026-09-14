package pl.victor.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.ble.MediaLibrary

class MediaItemTest {

    private fun item(
        name: String,
        seen: Long = 0L,
        saved: Boolean = false,
        onGlasses: Boolean = true
    ) = MediaItem(
        name = name,
        kind = MediaLibrary.kindOf(name),
        thumbnailPath = null,
        savedToPhone = saved,
        stillOnGlasses = onGlasses,
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

    // --- zawężanie widoku ---

    private val mixed = listOf(
        item("nowe.jpg", saved = false, onGlasses = true),
        item("zapisane.jpg", saved = true, onGlasses = true),
        item("tylko_w_telefonie.jpg", saved = true, onGlasses = false),
        item("przepadle.jpg", saved = false, onGlasses = false)
    )

    @Test
    fun `wszystko pokazuje cale archiwum`() {
        assertEquals(4, applyFilter(mixed, MediaFilter.ALL).size)
    }

    @Test
    fun `na okularach pomija to czego na sprzecie juz nie ma`() {
        assertEquals(
            listOf("nowe.jpg", "zapisane.jpg"),
            applyFilter(mixed, MediaFilter.ON_GLASSES).map { it.name }
        )
    }

    @Test
    fun `do zapisania to tylko rzeczy WYKONALNE`() {
        // Kluczowy przypadek: "przepadle.jpg" też nie jest zapisany, ale nie ma
        // go już na okularach, więc zapisać się go NIE DA. Lista rzeczy do
        // zrobienia z niewykonalnymi pozycjami jest gorsza niż jej brak.
        assertEquals(
            listOf("nowe.jpg"),
            applyFilter(mixed, MediaFilter.NOT_SAVED).map { it.name }
        )
    }

    @Test
    fun `w telefonie obejmuje takze te skasowane z okularow`() {
        assertEquals(
            listOf("zapisane.jpg", "tylko_w_telefonie.jpg"),
            applyFilter(mixed, MediaFilter.SAVED).map { it.name }
        )
    }

    @Test
    fun `zawezenie pustego archiwum nie wywraca sie`() {
        MediaFilter.entries.forEach { filter ->
            assertTrue(applyFilter(emptyList(), filter).isEmpty())
        }
    }

    @Test
    fun `zawezenie zachowuje kolejnosc wejscia`() {
        // Porządkowaniem zajmuje się groupForDisplay - gdyby zawężanie
        // przestawiało elementy, te dwie reguły biłyby się o wynik.
        val order = applyFilter(mixed, MediaFilter.ALL).map { it.name }
        assertEquals(mixed.map { it.name }, order)
    }
}
