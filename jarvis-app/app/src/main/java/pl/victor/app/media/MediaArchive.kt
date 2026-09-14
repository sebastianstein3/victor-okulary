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
        val hidden = hidden()
        val now = System.currentTimeMillis()
        val fresh = names.filterNot { it in hidden }.map { name ->
            GlassesMediaEntity(
                name = name,
                kind = MediaLibrary.kindOf(name).name,
                firstSeenAtMs = now
            )
        }
        dao.insertIfNew(fresh)
        if (names.isEmpty()) dao.markAllMissing() else dao.markMissing(names)
        pruneHidden(names)
    }

    /** Oznacza, że pamięć okularów została zwolniona - wszystko z nich znikło. */
    suspend fun rememberGlassesEmptied() = withContext(Dispatchers.IO) {
        dao.markAllMissing()
        // Nagrobki dotyczą plików LEŻĄCYCH NA SPRZĘCIE. Po wyczyszczeniu
        // pamięci nie ma już czego ukrywać, a zostawione mogłyby zasłonić
        // ZUPEŁNIE NOWE zdjęcie - patrz [pruneHidden].
        pruneHidden(emptyList())
    }

    /**
     * Zapomina pliki na życzenie użytkownika: wpis, miniatura i nagrobek.
     *
     * ## Czemu nagrobek, a nie samo skasowanie wiersza
     * Bo okulary NIE MAJĄ komendy kasowania pojedynczego pliku - ma tylko
     * `WORK_RELEASE_STORAGE`, które czyści wszystko naraz (zmierzone: 121
     * zdjęć przed, 0 po). Aplikacja producenta zresztą robi dokładnie to samo,
     * co my: jej "usuń" kasuje wiersz we własnej bazie i plik w telefonie, a do
     * okularów nie wysyła nic.
     *
     * Skoro plik zostaje na sprzęcie, samo skasowanie wiersza byłoby udawaniem:
     * przy najbliższym "Połącz i wczytaj" nazwa wróciłaby na listę razem z
     * całym spisem. Usunięcie, po którym rzecz wraca, jest gorsze niż brak
     * usuwania - bo drugi raz nikt już aplikacji nie uwierzy.
     */
    suspend fun forget(names: List<String>) = withContext(Dispatchers.IO) {
        if (names.isEmpty()) return@withContext
        names.forEach { name ->
            runCatching { File(thumbnailDir, thumbnailFileName(name)).delete() }
        }
        dao.deleteByNames(names)
        setHidden(hidden() + names)
    }

    // === Nagrobki ===
    //
    // W zwykłych ustawieniach, nie w bazie - i to jest decyzja, nie
    // niedopatrzenie. Dołożenie kolumny znaczy podbicie wersji bazy i
    // napisanie migracji, a komentarz w [pl.victor.app.data.MediaDatabase]
    // opisuje, czemu tego tu unikamy: Room jest w gałęzi przechodzącej na
    // sterowniki KMP i nie da się offline potwierdzić, który wariant
    // `Migration.migrate` obowiązuje. Zbiór kilkudziesięciu nazw to nie są
    // dane relacyjne - nie ma czego łączyć ani po czym sortować - więc cena
    // jest żadna, a ryzyko zerowe.
    //
    // Całość siedzi ZA tą klasą: reszta aplikacji nie wie, że archiwum ma dwa
    // miejsca zapisu, i nie ma jak się o nie potknąć.

    private val hiddenPrefs =
        appContext.getSharedPreferences(HIDDEN_PREFS, Context.MODE_PRIVATE)

    private fun hidden(): Set<String> =
        hiddenPrefs.getStringSet(HIDDEN_KEY, emptySet()) ?: emptySet()

    private fun setHidden(names: Set<String>) {
        // Kopia, bo zbioru oddanego przez `getStringSet` nie wolno zmieniać, a
        // ten sam obiekt wstawiony z powrotem bywa ignorowany.
        hiddenPrefs.edit().putStringSet(HIDDEN_KEY, names.toSet()).apply()
    }

    /**
     * Zdejmuje nagrobki z plików, których na okularach już nie ma.
     *
     * ## Czemu to jest konieczne, a nie tylko porządkowe
     * Bo nazwy się POWTARZAJĄ. Po wyczyszczeniu pamięci licznik zdjęć rusza od
     * początku i następne zdjęcie znów nazywa się `IMG_0001.JPG`. Nagrobek
     * trzymany w nieskończoność zasłoniłby wtedy zupełnie nowy plik - a to
     * wygląda jak gubienie zdjęć przez aplikację i nie da się tego z ekranu
     * zrozumieć.
     *
     * Nagrobek ma więc dokładnie jedno zadanie: nie wpuścić nazwy z powrotem,
     * DOPÓKI ten sam plik leży na sprzęcie. Gdy zniknie ze spisu, zadanie się
     * kończy.
     */
    private fun pruneHidden(present: List<String>) {
        val hidden = hidden()
        if (hidden.isEmpty()) return
        val stillThere = hidden.intersect(present.toSet())
        if (stillThere.size != hidden.size) setHidden(stillThere)
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
        const val HIDDEN_PREFS = "victor_media_hidden"
        const val HIDDEN_KEY = "hidden_names"
    }
}
