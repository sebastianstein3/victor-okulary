package pl.victor.app.ble

/**
 * Porządkowanie listy plików z okularów.
 *
 * Okulary oddają płaską listę nazw (`getMediaFileList`), bez typów, dat ani
 * rozmiarów - tylko nazwy. Cała wiedza o tym, co jest czym, siedzi więc w
 * rozszerzeniu pliku i w kolejności nazw. Wydzielone z UI, żeby dało się to
 * sprawdzić testem: pomyłka tutaj pokazuje wideo w zakładce ze zdjęciami, a
 * tego nie widać w kodzie ekranu.
 */
object MediaLibrary {

    enum class Kind(val title: String, val emoji: String) {
        PHOTO("Zdjęcia", "📷"),
        VIDEO("Wideo", "🎬"),
        AUDIO("Nagrania", "🎙️"),
        OTHER("Inne pliki", "📄")
    }

    private val PHOTO_EXT = listOf(".jpg", ".jpeg", ".png")
    private val VIDEO_EXT = listOf(".mp4", ".avi", ".mov")
    private val AUDIO_EXT = listOf(".wav", ".mp3", ".opus", ".pcm", ".amr")

    fun kindOf(name: String): Kind {
        val lower = name.lowercase()
        return when {
            PHOTO_EXT.any { lower.endsWith(it) } -> Kind.PHOTO
            VIDEO_EXT.any { lower.endsWith(it) } -> Kind.VIDEO
            AUDIO_EXT.any { lower.endsWith(it) } -> Kind.AUDIO
            else -> Kind.OTHER
        }
    }


}
