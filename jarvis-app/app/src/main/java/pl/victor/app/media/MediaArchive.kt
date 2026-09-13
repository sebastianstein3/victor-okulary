package pl.victor.app.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.victor.app.ble.MediaLibrary
import pl.victor.app.data.GlassesMediaEntity
import pl.victor.app.data.MediaDatabase
import java.io.File

/**
 * Archiwum plików z okularów po stronie telefonu.
 *
 * ## Po co to istnieje
 * Okulary mają skończoną pamięć i są buforem; telefon jest archiwum. Dotąd
 * galeria żyła tylko tak długo, jak otwarty był ekran - po wyjściu ginęła lista
 * i wszystkie miniatury, a kolejne wejście oznaczało ponowne podniesienie sieci
 * i pobranie tych samych megabajtów.
 *
 * Archiwum przeżywa nie tylko zamknięcie ekranu, ale i zniknięcie pliku z
 * okularów: zdjęcie zapisane w telefonie i usunięte ze sprzętu ma nadal być
 * widoczne, z miniaturą i datą.
 */
class MediaArchive(context: Context) {

    private val appContext = context.applicationContext
    private val dao = MediaDatabase.getInstance(appContext).glassesMediaDao()

    /** Katalog miniatur - w cache, bo da się je odtworzyć z plików źródłowych. */
    private val thumbnailDir: File
        get() = File(appContext.cacheDir, THUMBNAIL_DIR).apply { mkdirs() }

    /** Wszystko, co kiedykolwiek widzieliśmy - do pokazania na ekranie. */
    suspend fun all(): List<MediaItem> = withContext(Dispatchers.IO) {
        dao.all().map { it.toItem() }
    }

    /**
     * Zapisuje listę świeżo odczytaną z okularów.
     *
     * Nowe pliki dopisuje, znanym nie rusza (miniatura i data zapisu w telefonie
     * są cenniejsze niż to, co niesie sama lista nazw), a te, których na liście
     * NIE MA, oznacza jako nieobecne na okularach - bez kasowania wiersza.
     */
    suspend fun rememberListing(names: List<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val fresh = names.map { name ->
            GlassesMediaEntity(
                name = name,
                kind = MediaLibrary.kindOf(name).name,
                firstSeenAtMs = now
            )
        }
        dao.insertIfNew(fresh)
        if (names.isEmpty()) dao.markAllMissing() else dao.markMissing(names)
    }

    /** Oznacza, że pamięć okularów została zwolniona - wszystko z nich znikło. */
    suspend fun rememberGlassesEmptied() = withContext(Dispatchers.IO) {
        dao.markAllMissing()
    }

    /**
     * Zapisuje miniaturę na dysk i zapamiętuje jej ścieżkę.
     *
     * Na dysk, nie do bazy: sto kilkadziesiąt obrazków wewnątrz wierszy
     * zamieniłoby odczyt listy w odczyt kilkunastu megabajtów przy każdym
     * otwarciu ekranu.
     */
    suspend fun saveThumbnail(name: String, bitmap: Bitmap): File? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(thumbnailDir, thumbnailFileName(name))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            dao.setThumbnail(name, file.absolutePath)
            file
        }.onFailure { Log.w(TAG, "Nie udało się zapisać miniatury $name", it) }.getOrNull()
    }

    suspend fun markSavedToPhone(name: String) = withContext(Dispatchers.IO) {
        dao.markSavedToPhone(name, System.currentTimeMillis())
    }

    suspend fun savedCount(): Int = withContext(Dispatchers.IO) { dao.savedCount() }

    /**
     * Nazwa pliku miniatury.
     *
     * Nazwy z okularów bywają ścieżkami (spis w formacie JSON podaje `f` jako
     * pełną ścieżkę na karcie), więc ukośniki trzeba zamienić - inaczej zapis
     * trafiłby w nieistniejący podkatalog.
     */
    private fun thumbnailFileName(name: String): String =
        name.replace('/', '_').replace('\\', '_') + ".thumb.jpg"

    private fun GlassesMediaEntity.toItem() = MediaItem(
        name = name,
        kind = runCatching { MediaLibrary.Kind.valueOf(kind) }
            .getOrDefault(MediaLibrary.Kind.OTHER),
        thumbnailPath = thumbnailPath?.takeIf { File(it).exists() },
        savedToPhone = savedToPhoneAtMs != null,
        stillOnGlasses = stillOnGlasses,
        firstSeenAtMs = firstSeenAtMs
    )


    private companion object {
        const val TAG = "MediaArchive"
        const val THUMBNAIL_DIR = "glasses_thumbs"
    }
}
