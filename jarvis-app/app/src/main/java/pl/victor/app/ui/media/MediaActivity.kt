package pl.victor.app.ui.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.victor.app.VictorApplication
import pl.victor.app.ble.MediaLibrary
import pl.victor.app.media.MediaArchive
import pl.victor.app.media.MediaFilter
import pl.victor.app.media.MediaItem
import pl.victor.app.media.applyFilter
import pl.victor.app.media.groupForDisplay
import pl.victor.app.ui.theme.VictorTheme
import java.io.File

/**
 * Galeria plików z okularów - zdjęcia, wideo i nagrania zrobione sprzętem.
 *
 * ## Dlaczego to nie jest zwykła lista
 * Pliki leżą w okularach, nie w telefonie. Żeby je zobaczyć, trzeba dołączyć
 * do ich sieci Wi-Fi - dlatego sesja jest jawna: włącza ją przycisk, a wyjście
 * z ekranu ją zamyka.
 *
 * Samo dołączenie NIE odcina już telefonu od internetu: pobieranie plików
 * wskazuje sieć okularów dla swoich własnych połączeń, nie dla całej
 * aplikacji. Dzięki temu przy otwartej galerii model AI odpowiada normalnie.
 */
class MediaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNearbyDevicesPermission()
        setContent { VictorTheme { MediaScreen(onBack = { finish() }) } }
    }

    /**
     * Prosi o zgodę potrzebną do Wi-Fi Direct.
     *
     * ## Dlaczego akurat tutaj
     * Bez niej telefon nie dołączy do sieci okularów, a galeria kończyła się
     * komunikatem "okulary nie zgłosiły adresu Wi-Fi" - który sugeruje usterkę
     * okularów, choć brakowało zwykłej zgody. Pytano o nią tylko w parowaniu i
     * w samouczku, więc każdy, kto je pominął albo odmówił, miał galerię
     * trwale pustą bez żadnej wskazówki.
     */
    private fun requestNearbyDevicesPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(permission), 1)
        }
    }
}

class MediaViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val manager = (app as VictorApplication).glassesManager
    private val archive = MediaArchive(app)

    /**
     * Całe archiwum, płasko - źródło prawdy dla zawężenia i zaznaczenia.
     *
     * Ekran dostaje z tego [files], czyli to samo po zawężeniu i pogrupowaniu.
     * Trzymanie gotowych grup jako stanu znaczyłoby przeliczanie ich przy
     * każdej zmianie filtra w drugim miejscu - a zaznaczenie i tak operuje na
     * nazwach, nie na grupach.
     */
    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())

    private val _filter = MutableStateFlow(MediaFilter.ALL)
    val filter: StateFlow<MediaFilter> = _filter.asStateFlow()

    /** To, co widać na ekranie: archiwum po zawężeniu, pogrupowane. */
    val files: StateFlow<List<Pair<MediaLibrary.Kind, List<MediaItem>>>> =
        combine(_items, _filter) { items, chosen ->
            groupForDisplay(applyFilter(items, chosen))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Nazwy zaznaczonych plików.
     *
     * Nazwy, nie obiekty: po odświeżeniu archiwum obiekty są nowe, a
     * zaznaczenie ma przetrwać - inaczej zapisanie pliku gubiłoby zaznaczenie
     * całej reszty. Niepusty zbiór JEST trybem zaznaczania; osobna flaga
     * mogłaby się z nim rozjechać.
     */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _sessionOpen = MutableStateFlow(false)
    val sessionOpen: StateFlow<Boolean> = _sessionOpen.asStateFlow()

    /**
     * Miniatury gotowe do pokazania, po nazwie pliku.
     *
     * Mapa, nie lista: siatka pyta o miniaturę per kafelek i musi dostać
     * odpowiedź natychmiast (albo `null`), bez szukania po całości.
     */
    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    /** Postęp zapisywania wszystkiego do telefonu, albo `null` gdy nic nie trwa. */
    data class SaveProgress(val done: Int, val total: Int, val current: String)

    private val _saving = MutableStateFlow<SaveProgress?>(null)
    val saving: StateFlow<SaveProgress?> = _saving.asStateFlow()

    init {
        // ARCHIWUM WCZYTUJE SIĘ OD RAZU, BEZ OKULARÓW.
        //
        // To jest cała różnica między galerią a spisem, który trzeba odtwarzać:
        // po wejściu na ekran widać wszystko, co kiedykolwiek było - z
        // miniaturami - i dopiero jeśli ktoś chce NOWYCH plików, podnosimy sieć.
        //
        // Blok stoi PO wszystkich polach stanu i to jest tu warunek poprawności,
        // nie porządek: `viewModelScope.launch` potrafi ruszyć natychmiast na
        // wątku głównym, a `refreshFromArchive` czyta `_status`. Wyżej czytałby
        // pole jeszcze niezainicjalizowane.
        viewModelScope.launch { refreshFromArchive() }
    }

    private suspend fun refreshFromArchive() {
        val items = archive.all()
        _items.value = items
        // Zaznaczenie pliku, którego już nie ma, zostawiłoby licznik "zaznaczono
        // 3" nad dwoma kafelkami i przyciski działające na duchy.
        val present = items.mapTo(mutableSetOf()) { it.name }
        _selection.update { chosen -> chosen.intersect(present) }
        if (items.isEmpty() && _status.value == null) {
            _status.value = "Galeria jest pusta. Połącz się z okularami, żeby wczytać pliki."
        }
    }

    /**
     * Nazwy, o których miniaturę już poproszono.
     *
     * Bez tego przewijanie siatki w tę i z powrotem zamawiałoby ten sam plik
     * po kilka razy - a każde zamówienie to pobranie kilku megabajtów przez
     * łącze, które jest jedyną drogą także dla wszystkiego innego.
     */
    private val requestedThumbnails = mutableSetOf<String>()

    /**
     * Kolejka miniatur, przerabiana PO JEDNEJ.
     *
     * Siatka pokazuje kilkanaście kafelków naraz, więc bez kolejki poszłoby
     * kilkanaście równoległych pobrań pełnych plików przez jedno łącze Wi-Fi
     * okularów. Skutek byłby odwrotny do zamierzonego: wszystkie miniatury
     * pojawiłyby się na końcu, zamiast po kolei od góry.
     */
    private val thumbnailQueue = ArrayDeque<MediaItem>()
    private var thumbnailWorkerRunning = false

    fun load() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                _status.value = "Pytam okulary o listę plików..."
                val overBle = manager.findAlbumOverBle()
                if (overBle.isNotEmpty()) {
                    archive.rememberListing(overBle)
                    refreshFromArchive()
                    _sessionOpen.value = false
                    _status.value = "Lista pobrana przez Bluetooth - internet w telefonie działa " +
                        "normalnie. Podgląd pliku może wymagać Wi-Fi."
                    return@launch
                }
                _status.value = "Bluetooth nie podał listy - podnoszę połączenie Wi-Fi z okularami..."
                if (!manager.openMediaSession()) {
                    manager.endTransferSession()
                    _status.value = manager.lastTransferFailure
                        ?: "Okulary nie zgłosiły adresu Wi-Fi. Sprawdź, czy są " +
                        "połączone, i spróbuj ponownie."
                    return@launch
                }
                _sessionOpen.value = true
                _status.value = "Wczytuję listę plików..."
                val names = manager.getMediaFileList()
                archive.rememberListing(names)
                refreshFromArchive()
                _status.value = if (names.isEmpty()) {
                    "Okulary nie mają jeszcze żadnych plików."
                } else {
                    "${names.size} plików na okularach."
                }
            } catch (e: Exception) {
                _status.value = "Nie udało się wczytać plików: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    // === Miniatury ===

    /**
     * Zamawia miniaturę kafelka, który właśnie wjechał na ekran.
     *
     * Wołane przez siatkę przy każdym złożeniu kafelka, więc musi być tanie i
     * odporne na powtórzenia - stąd [requestedThumbnails].
     */
    fun requestThumbnail(item: MediaItem) {
        if (item.kind != MediaLibrary.Kind.PHOTO) return
        if (!requestedThumbnails.add(item.name)) return
        // Miniatura z dysku jest darmowa - pobranie kosztuje kilka megabajtów
        // przez łącze okularów, więc sięgamy po nie dopiero, gdy na dysku nic
        // nie ma. To jest główny zysk z archiwum przy drugim wejściu na ekran.
        val cached = item.thumbnailPath
        if (cached != null) {
            viewModelScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(cached) }.getOrNull()
                }
                if (bitmap != null) _thumbnails.update { it + (item.name to bitmap) }
            }
            return
        }
        // Pliku, którego nie ma już na okularach, nie da się pobrać - a próba
        // skończyłaby się czterdziestoma sekundami czekania na każdy taki kafelek.
        if (!item.stillOnGlasses) return
        thumbnailQueue.addLast(item)
        startThumbnailWorker()
    }

    private fun startThumbnailWorker() {
        if (thumbnailWorkerRunning) return
        thumbnailWorkerRunning = true
        viewModelScope.launch {
            try {
                while (true) {
                    val item = thumbnailQueue.removeFirstOrNull() ?: break
                    val bitmap = runCatching {
                        val bytes = manager.downloadFile(item.name)
                        withContext(Dispatchers.Default) {
                            MediaThumbnails.decode(bytes, THUMBNAIL_PX)
                        }
                    }.getOrNull()
                    if (bitmap != null) {
                        _thumbnails.update { it + (item.name to bitmap) }
                        // Na dysk, żeby następne wejście na ekran nie musiało
                        // pobierać tych samych megabajtów jeszcze raz.
                        archive.saveThumbnail(item.name, bitmap)
                    } else {
                        // Nieudana miniatura zostaje "zamówiona": ponawianie w
                        // pętli przewijania zajęłoby łącze na okrągło, a plik
                        // i tak da się otworzyć dotknięciem.
                        Log.w(TAG, "Nie udało się zrobić miniatury: ${item.name}")
                    }
                }
            } finally {
                thumbnailWorkerRunning = false
            }
        }
    }

    // === Otwieranie ===

    /**
     * Pobiera plik i pokazuje go: zdjęcie w podglądzie, resztę w odtwarzaczu
     * systemowym.
     *
     * Wideo i nagrania idą do odtwarzacza systemowego, a nie do własnego:
     * przewijanie, głośność, słuchawki i kodeki są tam gotowe i przetestowane,
     * a napisanie tego od nowa dałoby gorszy odtwarzacz mniejszym kosztem
     * tylko pozornie.
     */
    fun open(item: MediaItem) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            // Plik zniknął ze sprzętu? Wtedy sięgamy po kopię w telefonie -
            // patrz [bytesFor]. Wcześniej galeria w tym miejscu odmawiała i
            // odsyłała do galerii telefonu, a po "Zwolnij pamięć okularów"
            // dotyczyło to WSZYSTKIEGO, czyli całej zawartości tego ekranu.
            _status.value = if (item.stillOnGlasses) {
                "Pobieram ${item.name}..."
            } else {
                "Otwieram kopię z telefonu..."
            }
            try {
                val bytes = bytesFor(item)
                if (bytes == null) {
                    _status.value = "${item.displayName} nie ma już na okularach, " +
                        "a nie został zapisany w telefonie."
                    return@launch
                }
                lastDownload = item.name to bytes
                if (item.kind == MediaLibrary.Kind.PHOTO) {
                    val full = withContext(Dispatchers.Default) {
                        MediaThumbnails.decode(bytes, PREVIEW_PX)
                    }
                    if (full != null) {
                        _preview.value = full
                        _status.value = "${item.name} (${bytes.size / 1024} kB)"
                    } else {
                        _status.value = "${item.name} pobrany, ale nie wygląda na obraz."
                    }
                } else {
                    val uri = withContext(Dispatchers.IO) { cacheForPlayback(item.name, bytes) }
                    _pendingPlayback.value = uri to MediaThumbnails.mimeTypeOf(item.name)
                    _status.value = "Otwieram ${item.name}..."
                }
            } catch (e: Exception) {
                _status.value = "Nie udało się pobrać ${item.name}: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Plik czekający na odtwarzacz - adres i typ.
     *
     * ViewModel nie startuje aktywności sam: nie ma do tego kontekstu, w którym
     * wolno to robić, a poza tym po obrocie ekranu odtwarzacz odpaliłby się
     * drugi raz. Ekran odbiera to zdarzenie i kasuje przez [playbackHandled].
     */
    private val _pendingPlayback = MutableStateFlow<Pair<Uri, String>?>(null)
    val pendingPlayback: StateFlow<Pair<Uri, String>?> = _pendingPlayback.asStateFlow()

    fun playbackHandled() {
        _pendingPlayback.value = null
    }

    /**
     * Zapisuje bajty w prywatnym katalogu cache i oddaje adres dla odtwarzacza.
     *
     * Cache, nie MediaStore: to kopia robocza do obejrzenia. Do galerii
     * telefonu plik trafia dopiero, gdy użytkownik o to poprosi - inaczej samo
     * przejrzenie nagrań zaśmieciłoby mu telefon.
     */
    private fun cacheForPlayback(name: String, bytes: ByteArray): Uri {
        val context = getApplication<android.app.Application>()
        val dir = File(context.cacheDir, PLAYBACK_DIR).apply { mkdirs() }
        val file = File(dir, name.substringAfterLast('/'))
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private var lastDownload: Pair<String, ByteArray>? = null

    // === Zapis do galerii telefonu ===

    fun saveLastToPhone() {
        val (name, bytes) = lastDownload ?: run {
            _status.value = "Najpierw otwórz plik, który chcesz zapisać."
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) { writeToGallery(name, bytes) }
                archive.markSavedToPhone(name)
                refreshFromArchive()
                _status.value = "Zapisano $name w telefonie."
            } catch (e: Exception) {
                _status.value = "Nie udało się zapisać: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Zapisuje do galerii telefonu WSZYSTKIE pliki z okularów.
     *
     * Idzie po jednym i po każdym melduje postęp, bo przy stu kilkudziesięciu
     * plikach to są minuty - a pasek, który stoi bez słowa, jest nie do
     * odróżnienia od zawieszonej aplikacji.
     *
     * Pliki już zapisane pomijamy. Bez tego drugie uruchomienie zrobiłoby
     * komplet duplikatów: MediaStore nie odmawia zapisu, tylko dokleja do
     * nazwy „(1)".
     */
    fun saveAllToPhone() = saveToPhone(visibleItems(), whole = true)

    /** Zapisuje w telefonie tylko to, co użytkownik zaznaczył. */
    fun saveSelectedToPhone() = saveToPhone(selectedItems(), whole = false)

    /**
     * @param whole czy to jest "zapisz wszystko" - zmienia tylko komunikat o
     *   pustej liście, bo "nie ma czego zapisywać" i "nic nie zaznaczyłeś" to
     *   dwie różne sytuacje i dwie różne rady.
     */
    private fun saveToPhone(all: List<MediaItem>, whole: Boolean) {
        if (_saving.value != null || _busy.value) return
        if (all.isEmpty()) {
            _status.value = if (whole) {
                "Nie ma czego zapisywać - najpierw wczytaj listę."
            } else {
                "Nic nie jest zaznaczone."
            }
            return
        }
        saveJob = viewModelScope.launch {
            var saved = 0
            var skipped = 0
            var failed = 0
            var gone = 0
            try {
                all.forEachIndexed { index, item ->
                    _saving.value = SaveProgress(index, all.size, item.name)
                    // Pliku, którego na okularach nie ma, nie da się pobrać, a
                    // próba kosztuje czterdzieści sekund czekania na sztukę.
                    // Przy zaznaczeniu obejmującym stare zdjęcia to różnica
                    // między chwilą a kwadransem.
                    if (!item.stillOnGlasses) {
                        gone++
                        return@forEachIndexed
                    }
                    val already = withContext(Dispatchers.IO) { galleryUriOf(item.name) != null }
                    if (already) {
                        skipped++
                        archive.markSavedToPhone(item.name)
                        return@forEachIndexed
                    }
                    val ok = runCatching {
                        val bytes = manager.downloadFile(item.name)
                        withContext(Dispatchers.IO) { writeToGallery(item.name, bytes) }
                    }.isSuccess
                    if (ok) {
                        saved++
                        archive.markSavedToPhone(item.name)
                    } else {
                        failed++
                    }
                }
                refreshFromArchive()
                _status.value = buildString {
                    append("Zapisano $saved z ${all.size}")
                    if (skipped > 0) append(", pominięto $skipped już zapisanych")
                    if (gone > 0) append(", $gone nie ma już na okularach")
                    if (failed > 0) append(", nie udało się $failed")
                    append(".")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Przerwanie to decyzja użytkownika, nie awaria - ale musi
                // zobaczyć, ile zdążyło się zapisać, zanim przerwał.
                _status.value = "Przerwano. Zapisano $saved z ${all.size}."
                throw e
            } catch (e: Exception) {
                _status.value = "Zapisywanie przerwał błąd po $saved plikach: ${e.message}"
            } finally {
                _saving.value = null
            }
        }
    }

    private var saveJob: kotlinx.coroutines.Job? = null

    fun cancelSaveAll() {
        saveJob?.cancel()
        saveJob = null
    }

    /**
     * Adres pliku w galerii telefonu albo `null`, gdy go tam nie ma.
     *
     * Adres, a nie samo "jest/nie ma": ten sam odczyt odpowiada na oba pytania,
     * które galeria zadaje - "czy pomijać przy zapisywaniu" i "co skasować albo
     * udostępnić, gdy pliku nie ma już na okularach".
     */
    private fun galleryUriOf(name: String): Uri? {
        val shortName = name.substringAfterLast('/')
        val resolver = getApplication<android.app.Application>().contentResolver
        val collection = collectionFor(MediaLibrary.kindOf(shortName))
        return runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(shortName),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun collectionFor(kind: MediaLibrary.Kind): Uri = when (kind) {
        MediaLibrary.Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaLibrary.Kind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    /**
     * Zapisuje plik w pamięci telefonu przez MediaStore.
     *
     * Przez MediaStore, a nie do katalogu aplikacji: plik ma się pojawić w
     * galerii telefonu, bo po to się go pobiera. Katalog prywatny aplikacji
     * znika razem z jej odinstalowaniem i nie widzi go żadna inna aplikacja.
     */
    private fun writeToGallery(name: String, bytes: ByteArray) {
        val shortName = name.substringAfterLast('/')
        val resolver = getApplication<android.app.Application>().contentResolver
        val kind = MediaLibrary.kindOf(shortName)
        val collection = collectionFor(kind)
        val folder = when (kind) {
            MediaLibrary.Kind.VIDEO -> Environment.DIRECTORY_MOVIES
            MediaLibrary.Kind.AUDIO -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_PICTURES
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, shortName)
            put(MediaStore.MediaColumns.MIME_TYPE, MediaThumbnails.mimeTypeOf(shortName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/VICTOR")
            }
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("system nie dał miejsca na plik")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("nie udało się otworzyć pliku do zapisu")
    }

    // === Zaznaczanie ===
    //
    // Przytrzymanie kafelka zaczyna zaznaczanie, dotknięcie w tym trybie
    // dokłada i zdejmuje, a zdjęcie ostatniego zaznaczenia tryb kończy. Bez
    // osobnego przycisku "wybierz pliki": jeden gest mniej do odkrycia, a stan
    // widać po samych kafelkach.

    /** To, co w tej chwili widać na ekranie - czyli archiwum po zawężeniu. */
    private fun visibleItems(): List<MediaItem> = applyFilter(_items.value, _filter.value)

    private fun selectedItems(): List<MediaItem> {
        val chosen = _selection.value
        return _items.value.filter { it.name in chosen }
    }

    fun setFilter(chosen: MediaFilter) {
        _filter.value = chosen
        // Zaznaczenie zostaje: schowany plik nadal jest zaznaczony i nadal się
        // go dotyczy. Dlatego licznik nad siatką mówi "zaznaczono N", a nie
        // "N z widocznych" - inaczej ktoś skasowałby więcej, niż widzi.
    }

    fun toggleSelection(item: MediaItem) {
        _selection.update { chosen ->
            if (item.name in chosen) chosen - item.name else chosen + item.name
        }
    }

    fun selectAllVisible() {
        _selection.value = visibleItems().mapTo(mutableSetOf()) { it.name }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    // === Usuwanie ===

    /**
     * Co dokładnie zniknie - do pokazania w pytaniu.
     *
     * Liczby, nie słowo "gotowe": usuwanie w tej galerii znaczy co innego dla
     * pliku leżącego na okularach niż dla takiego, który jest już tylko w
     * telefonie, a użytkownik ma to wiedzieć PRZED potwierdzeniem, nie z
     * komunikatu po fakcie.
     */
    data class DeletePlan(
        val count: Int,
        val stillOnGlasses: Int,
        val inPhoneGallery: Int,

        /**
         * Ile z nich nie istnieje już NIGDZIE INDZIEJ.
         *
         * Ani na okularach, ani w galerii telefonu - czyli po tym usunięciu
         * zostanie po nich tylko tyle, ile zostaje po skasowanym pliku: nic.
         * To jedyny przypadek, w którym usuwanie z tej galerii jest naprawdę
         * nieodwracalne, więc musi mieć własne ostrzeżenie.
         */
        val lastTrace: Int
    )

    private val _askDelete = MutableStateFlow<DeletePlan?>(null)
    val askDelete: StateFlow<DeletePlan?> = _askDelete.asStateFlow()

    fun askDeleteSelected() {
        val chosen = selectedItems()
        if (chosen.isEmpty()) {
            _status.value = "Nic nie jest zaznaczone."
            return
        }
        _askDelete.value = DeletePlan(
            count = chosen.size,
            stillOnGlasses = chosen.count { it.stillOnGlasses },
            inPhoneGallery = chosen.count { it.savedToPhone },
            lastTrace = chosen.count { !it.stillOnGlasses && !it.savedToPhone }
        )
    }

    fun dismissDelete() {
        _askDelete.value = null
    }

    /**
     * Usuwa zaznaczone pliki z galerii aplikacji - i opcjonalnie z telefonu.
     *
     * ## Czego ta funkcja NIE robi
     * Nie kasuje niczego w okularach, bo nie ma czym: sprzęt zna tylko
     * `WORK_RELEASE_STORAGE`, czyli wyczyszczenie całej pamięci naraz.
     * Aplikacja producenta robi dokładnie to samo co my - jej "usuń" rusza
     * własną bazę i plik w telefonie, do okularów nie wysyła nic. Komunikat
     * mówi to wprost, zamiast pozwalać wierzyć, że zdjęcie zniknęło ze sprzętu.
     *
     * @param alsoFromPhone czy skasować też kopie z galerii telefonu
     */
    fun deleteSelected(alsoFromPhone: Boolean) {
        _askDelete.value = null
        if (_busy.value || _saving.value != null) return
        val chosen = selectedItems()
        if (chosen.isEmpty()) return
        val names = chosen.map { it.name }
        val onGlasses = chosen.count { it.stillOnGlasses }
        viewModelScope.launch {
            _busy.value = true
            try {
                val removedFromPhone = if (alsoFromPhone) {
                    withContext(Dispatchers.IO) { names.count { deleteFromGallery(it) } }
                } else {
                    0
                }
                archive.forget(names)
                // Miniatura skasowanego pliku nie może zostać w pamięci: gdyby
                // nazwa kiedyś wróciła (po wyczyszczeniu pamięci licznik zdjęć
                // rusza od nowa), pokazalibyśmy cudzy obrazek pod nowym plikiem.
                val removed = names.toSet()
                requestedThumbnails.removeAll(removed)
                _thumbnails.update { it - removed }
                // Kolejka mogła już mieć te pliki w planie. Pobieranie miniatury
                // dla czegoś usuniętego to kilka megabajtów przez łącze okularów
                // za obrazek, którego nikt nie zobaczy.
                thumbnailQueue.removeAll { it.name in removed }
                _selection.value = emptySet()
                refreshFromArchive()
                _status.value = buildString {
                    append("Usunięto ${names.size} z galerii aplikacji")
                    if (alsoFromPhone) append(", w tym $removedFromPhone kopii z telefonu")
                    append(".")
                    if (onGlasses > 0) {
                        append(
                            " Pliki ($onGlasses) zostają w pamięci okularów - sprzęt nie ma " +
                                "komendy kasowania pojedynczego pliku, umie tylko wyczyścić " +
                                "wszystko naraz. Z listy nie wrócą."
                        )
                    }
                }
            } catch (e: Exception) {
                _status.value = "Nie udało się usunąć: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Kasuje kopię pliku z galerii telefonu.
     *
     * Udaje się dla plików, które zapisała ta aplikacja - system uznaje ją za
     * właściciela. Po przeinstalowaniu aplikacji właściciel przepada i system
     * odmawia; wtedy zwracamy `false`, a licznik w komunikacie pokaże mniej,
     * niż było zaznaczone. To jest uczciwsze niż zgłoszenie sukcesu.
     */
    private fun deleteFromGallery(name: String): Boolean {
        val uri = galleryUriOf(name) ?: return false
        val resolver = getApplication<android.app.Application>().contentResolver
        return runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    // === Udostępnianie ===

    private val _pendingShare = MutableStateFlow<Pair<Uri, String>?>(null)
    val pendingShare: StateFlow<Pair<Uri, String>?> = _pendingShare.asStateFlow()

    fun shareHandled() {
        _pendingShare.value = null
    }

    /**
     * Wysyła zaznaczony plik do innej aplikacji.
     *
     * Jeden plik, nie wiele: wysyłka wielu wymaga innego zamiaru systemowego i
     * innego zestawu adresów, a przy plikach z okularów każdy z nich to osobne
     * pobranie przez Wi-Fi. Lepiej mieć jedną rzecz, która działa pewnie.
     */
    fun shareSelected() {
        if (_busy.value) return
        val item = selectedItems().singleOrNull() ?: run {
            _status.value = "Udostępnianie działa dla jednego zaznaczonego pliku."
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _status.value = "Przygotowuję ${item.displayName}..."
            try {
                val bytes = bytesFor(item)
                if (bytes == null) {
                    _status.value = "${item.displayName} nie ma ani na okularach, ani w telefonie."
                    return@launch
                }
                val uri = withContext(Dispatchers.IO) { cacheForPlayback(item.name, bytes) }
                _pendingShare.value = uri to MediaThumbnails.mimeTypeOf(item.name)
                _status.value = null
            } catch (e: Exception) {
                _status.value = "Nie udało się przygotować pliku: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Bajty pliku - z okularów, a gdy go tam nie ma, z kopii w telefonie.
     *
     * Drugie źródło jest tu istotne, nie zapasowe: po "Zwolnij pamięć okularów"
     * WSZYSTKIE zdjęcia są już tylko w telefonie, a galeria ma nadal umieć je
     * pokazać i wysłać dalej.
     */
    private suspend fun bytesFor(item: MediaItem): ByteArray? {
        if (item.stillOnGlasses) return manager.downloadFile(item.name)
        val uri = withContext(Dispatchers.IO) { galleryUriOf(item.name) } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                getApplication<android.app.Application>().contentResolver
                    .openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
        }
    }

    /**
     * Czy pokazać pytanie o zwolnienie pamięci okularów.
     *
     * Osobne potwierdzenie, a nie zwykły przycisk, bo tej operacji może się nie
     * dać cofnąć - patrz [pl.victor.app.ble.GlassesProtocol.WORK_RELEASE_STORAGE].
     */
    private val _askRelease = MutableStateFlow(false)
    val askRelease: StateFlow<Boolean> = _askRelease.asStateFlow()

    fun askReleaseStorage() {
        if (_items.value.isEmpty()) {
            _status.value = "Najpierw wczytaj listę plików."
            return
        }
        _askRelease.value = true
    }

    fun dismissRelease() {
        _askRelease.value = false
    }

    /**
     * Prosi okulary o zwolnienie pamięci i pokazuje, co się stało z licznikami.
     *
     * Wynik opisujemy liczbami, a nie słowem "gotowe": dopóki nie wiadomo, czy
     * ta komenda kasuje pliki, czy tylko oznacza je jako przeniesione, jedyną
     * uczciwą odpowiedzią jest pokazanie, ile było i ile jest.
     */
    fun releaseStorage() {
        _askRelease.value = false
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "Proszę okulary o zwolnienie pamięci..."
            try {
                val (before, after) = manager.releaseGlassesStorage()
                _status.value = when {
                    before == null || after == null ->
                        "Okulary nie podały liczników - nie wiem, czy coś się zmieniło."
                    after.total < before.total ->
                        "Pamięć zwolniona: było ${before.total} plików, jest ${after.total}."
                    else ->
                        "Liczniki się nie zmieniły (${before.total} plików). " +
                            "Ta komenda nie zwalnia pamięci na tym egzemplarzu."
                }
                // Pliki znikły z okularów, ale ZOSTAJĄ w galerii telefonu -
                // o to w tej funkcji chodziło. Oznaczamy je tylko jako nieobecne
                // na sprzęcie.
                if (after != null && before != null && after.total < before.total) {
                    archive.rememberGlassesEmptied()
                    refreshFromArchive()
                }
            } catch (e: Exception) {
                _status.value = "Nie udało się: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun closePreview() {
        _preview.value = null
    }

    /**
     * Zamyka połączenie Wi-Fi z okularami.
     *
     * Wołane przy wyjściu z ekranu. Sesja nie odcina już telefonu od internetu,
     * ale zostawiona trzyma okulary w trybie przesyłania - a te odmawiają
     * wejścia w niego ponownie, dopóki ktoś ich z niego nie wyprowadzi.
     */
    fun closeSession() {
        cancelSaveAll()
        if (!_sessionOpen.value) return
        manager.endTransferSession()
        _sessionOpen.value = false
    }

    private companion object {
        const val TAG = "MediaViewModel"

        /** Dłuższy bok miniatury w kafelku siatki. */
        const val THUMBNAIL_PX = 320

        /**
         * Dłuższy bok zdjęcia w podglądzie.
         *
         * Też pomniejszamy: pełne 4000x3000 to 48 MB po rozkodowaniu, a ekran
         * telefonu i tak pokaże najwyżej tysiąc kilkaset pikseli.
         */
        const val PREVIEW_PX = 1600

        const val PLAYBACK_DIR = "glasses_media"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(onBack: () -> Unit) {
    val viewModel: MediaViewModel = viewModel()
    val files by viewModel.files.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val status by viewModel.status.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val sessionOpen by viewModel.sessionOpen.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val pendingPlayback by viewModel.pendingPlayback.collectAsState()
    val pendingShare by viewModel.pendingShare.collectAsState()
    val askRelease by viewModel.askRelease.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val deletePlan by viewModel.askDelete.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose { viewModel.closeSession() }
    }

    // Odtwarzacz startuje TUTAJ, nie w ViewModelu: uruchamianie aktywności
    // wymaga kontekstu ekranu, a zdarzenie skasowane po obsłużeniu nie odpali
    // się drugi raz po obrocie telefonu.
    LaunchedEffect(pendingPlayback) {
        val (uri, mime) = pendingPlayback ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
        viewModel.playbackHandled()
    }

    // Wysyłka idzie tą samą drogą co odtwarzacz i z tego samego powodu:
    // uruchamianie cudzej aplikacji wymaga kontekstu ekranu, a zdarzenie
    // skasowane po obsłużeniu nie odpali się drugi raz po obrocie telefonu.
    LaunchedEffect(pendingShare) {
        val (uri, mime) = pendingShare ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Udostępnij")) }
        viewModel.shareHandled()
    }

    deletePlan?.let { plan ->
        // Zaznaczenie przycisku pamiętamy PER PYTANIE (`remember(plan)`), żeby
        // zgoda na skasowanie kopii z telefonu nie przeniosła się cicho na
        // następne usuwanie.
        var alsoPhone by remember(plan) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { viewModel.dismissDelete() },
            title = {
                Text(
                    if (plan.count == 1) "Usunąć plik?"
                    else "Usunąć ${plan.count} plików?"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Mówimy dokładnie, co zniknie, a co zostanie. Okulary nie
                    // mają komendy kasowania pojedynczego pliku - ma tylko
                    // czyszczenie całej pamięci naraz - więc obiecanie "usunięto
                    // ze sprzętu" byłoby nieprawdą.
                    Text(
                        if (plan.stillOnGlasses > 0) {
                            "Zniknie z tej galerii razem z miniaturą. " +
                                "${plan.stillOnGlasses} z tych plików wciąż leży w pamięci " +
                                "okularów i TAM ZOSTANIE: sprzęt umie tylko wyczyścić całą " +
                                "pamięć naraz. Na tę listę już nie wrócą."
                        } else {
                            "Zniknie z tej galerii razem z miniaturą. " +
                                "Tych plików nie ma już na okularach."
                        }
                    )
                    if (plan.lastTrace > 0) {
                        Text(
                            "Uwaga: ${plan.lastTrace} z nich nie ma ani na okularach, ani w " +
                                "galerii telefonu. Po usunięciu nie zostanie po nich nic.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (plan.inPhoneGallery > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { alsoPhone = !alsoPhone }
                        ) {
                            Checkbox(checked = alsoPhone, onCheckedChange = { alsoPhone = it })
                            Text("Skasuj też kopie w galerii telefonu (${plan.inPhoneGallery})")
                        }
                        if (alsoPhone) {
                            Text(
                                "Skasowanych kopii z telefonu NIE DA SIĘ odzyskać.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSelected(alsoPhone) }) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDelete() }) { Text("Anuluj") }
            }
        )
    }

    if (askRelease) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRelease() },
            title = { Text("Zwolnić pamięć okularów?") },
            text = {
                // Mówimy wprost, czego NIE wiemy. Komenda pochodzi z aplikacji
                // producenta, który wysyła ją po każdym imporcie, ale nie da się
                // z kodu rozstrzygnąć, czy kasuje pliki, czy tylko oznacza je
                // jako przeniesione. Pierwsze użycie to rozstrzygnie - i lepiej,
                // żeby stało się to świadomie.
                Text(
                    "Ta komenda pochodzi z aplikacji producenta, która wysyła ją " +
                        "po każdym imporcie. Prawdopodobnie kasuje pliki z okularów " +
                        "i wtedy NIE DA SIĘ ich odzyskać.\n\n" +
                        "Najpierw zapisz wszystko w telefonie przyciskiem " +
                        "\"Zapisz wszystko\".\n\n" +
                        "Po operacji pokażę liczniki przed i po - to rozstrzygnie, " +
                        "co ta komenda naprawdę robi."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.releaseStorage() }) {
                    Text("Zwolnij")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRelease() }) { Text("Anuluj") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Galeria okularów") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            // Adaptive, nie Fixed: na wąskim ekranie wejdą trzy kafelki, na
            // szerokim więcej, a nam nie zależy na konkretnej liczbie kolumn,
            // tylko na tym, żeby miniatura była rozpoznawalna.
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Button(onClick = { viewModel.load() }, enabled = !busy && saving == null) {
                            Text(if (sessionOpen) "Odśwież" else "Połącz i wczytaj")
                        }
                        if (saving == null) {
                            OutlinedButton(
                                onClick = { viewModel.saveAllToPhone() },
                                enabled = !busy && files.isNotEmpty()
                            ) {
                                // Przy włączonym zawężeniu przycisk działa na
                                // to, co widać - i musi to powiedzieć, bo
                                // "wszystko" znaczyłoby wtedy co innego, niż
                                // robi.
                                Text(
                                    if (filter == MediaFilter.ALL) "Zapisz wszystko"
                                    else "Zapisz widoczne"
                                )
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.cancelSaveAll() }) {
                                Text("Przerwij")
                            }
                        }
                    }

                    // Zawężenia - przy stu dwudziestu jeden plikach na sprzęcie
                    // to jedyny sposób, żeby odpowiedzieć na "czego jeszcze nie
                    // mam w telefonie" inaczej niż przewijaniem całej siatki.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        MediaFilter.entries.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { viewModel.setFilter(option) },
                                label = { Text(option.label) }
                            )
                        }
                    }

                    if (selection.isNotEmpty()) {
                        SelectionBar(
                            count = selection.size,
                            enabled = !busy && saving == null,
                            onSave = { viewModel.saveSelectedToPhone() },
                            onShare = { viewModel.shareSelected() },
                            onDelete = { viewModel.askDeleteSelected() },
                            onSelectAll = { viewModel.selectAllVisible() },
                            onCancel = { viewModel.clearSelection() }
                        )
                    }

                    if (saving == null && files.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { viewModel.askReleaseStorage() },
                            enabled = !busy,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text("Zwolnij pamięć okularów")
                        }
                    }
                    saving?.let { progress ->
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            LinearProgressIndicator(
                                progress = {
                                    if (progress.total == 0) 0f
                                    else progress.done.toFloat() / progress.total
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Zapisuję ${progress.done + 1} z ${progress.total}: " +
                                    progress.current.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    if (busy && saving == null) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .height(20.dp)
                        )
                    }
                    status?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            preview?.let { bitmap ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Podgląd zdjęcia",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(8.dp)
                            ) {
                                OutlinedButton(onClick = { viewModel.saveLastToPhone() }) {
                                    Text("Zapisz w telefonie")
                                }
                                OutlinedButton(onClick = { viewModel.closePreview() }) {
                                    Text("Zamknij")
                                }
                            }
                        }
                    }
                }
            }

            // JAK SIĘ DOSTAĆ DO USUWANIA I UDOSTĘPNIANIA.
            //
            // Obie funkcje były tu od dawna, ale pokazują się dopiero po
            // PRZYTRZYMANIU kafelka - a przytrzymanie niczego o sobie nie mówi.
            // Zgłoszone z terenu jako brak funkcji: "w galerii nie widzę
            // niczego w stylu udostępnij, usuń". Niewidoczna funkcja jest z
            // punktu widzenia użytkownika nieistniejąca.
            //
            // Podpowiedź znika, gdy zaznaczanie już trwa - wtedy przyciski
            // widać na górze i powtarzanie instrukcji jest tylko hałasem.
            if (files.isNotEmpty() && selection.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Przytrzymaj zdjęcie, żeby je zaznaczyć - wtedy " +
                            "pojawi się usuwanie i udostępnianie.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }
            }

            files.forEach { (kind, items) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "${kind.emoji} ${kind.title} (${items.size})",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                items(items, key = { it.name }) { item ->
                    MediaTile(
                        item = item,
                        thumbnail = thumbnails[item.name],
                        enabled = !busy && saving == null,
                        selected = item.name in selection,
                        selecting = selection.isNotEmpty(),
                        onRequestThumbnail = { viewModel.requestThumbnail(item) },
                        // W trybie zaznaczania dotknięcie DOKŁADA, a nie
                        // otwiera: inaczej pierwszy odruch po przytrzymaniu
                        // kafelka kończyłby się pobieraniem pliku przez Wi-Fi.
                        onClick = {
                            if (selection.isEmpty()) viewModel.open(item)
                            else viewModel.toggleSelection(item)
                        },
                        onLongClick = { viewModel.toggleSelection(item) }
                    )
                }
            }
        }
    }
}

/**
 * Pasek działań na zaznaczeniu.
 *
 * Przewijany w poziomie, bo pięć przycisków nie mieści się w jednym wierszu na
 * wąskim telefonie, a zwinięcie ich do menu schowałoby "Usuń" - czyli to,
 * po co użytkownik w ogóle wszedł w zaznaczanie.
 */
@Composable
private fun SelectionBar(
    count: Int,
    enabled: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("Zaznaczono $count", fontWeight = FontWeight.Bold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            OutlinedButton(onClick = onSave, enabled = enabled) { Text("Zapisz") }
            // Wysyłka tylko dla jednego pliku - patrz MediaViewModel.shareSelected.
            OutlinedButton(onClick = onShare, enabled = enabled && count == 1) {
                Text("Udostępnij")
            }
            OutlinedButton(onClick = onDelete, enabled = enabled) { Text("Usuń") }
            OutlinedButton(onClick = onSelectAll, enabled = enabled) { Text("Zaznacz widoczne") }
            OutlinedButton(onClick = onCancel) { Text("Odznacz") }
        }
    }
}

/**
 * Jeden kafelek siatki.
 *
 * Miniatura zamawiana jest dopiero przy złożeniu kafelka, czyli gdy naprawdę
 * wjeżdża na ekran. Przy stu kilkudziesięciu plikach zamówienie wszystkiego z
 * góry znaczyłoby pobranie całej zawartości okularów, zanim pokaże się
 * pierwszy obrazek.
 *
 * Przytrzymanie zaczyna zaznaczanie. `combinedClickable` zamiast `clickable`
 * jest tu jedyną drogą: Compose nie daje długiego dotknięcia bez niego, a
 * osobny przycisk "wybierz pliki" byłby stanem do odkrycia i do pomylenia.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: MediaItem,
    thumbnail: Bitmap?,
    enabled: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onRequestThumbnail: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    LaunchedEffect(item.name) { onRequestThumbnail() }

    Column(
        modifier = Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    // Sama ptaszka w rogu nie wystarcza: przy ciemnym zdjęciu
                    // gubi się w obrazie. Obwódka zmienia kształt kafelka, więc
                    // widać ją kątem oka przy przewijaniu.
                    if (selected) {
                        Modifier.border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Emoji rodzaju zamiast pustego prostokąta: przy wideo i
                // nagraniach miniatury nie ma i nie będzie (zrobienie jej
                // znaczyłoby pobranie całego pliku), a przy zdjęciu mówi
                // "to się jeszcze ładuje", a nie "tu nic nie ma".
                Text(item.kind.emoji, fontSize = 28.sp)
            }
            if (selecting) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Zaznaczony",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        Text(
            item.displayName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        // Dwie rzeczy, które użytkownik musi widzieć bez otwierania pliku:
        // czy jest już w telefonie i czy da się go jeszcze pobrać z okularów.
        // Bez tego "Zapisz wszystko" wygląda jak ruletka, a kafelek pliku,
        // którego na sprzęcie nie ma, niczym się nie różni od reszty.
        val badge = when {
            item.savedToPhone && !item.stillOnGlasses -> "w telefonie"
            item.savedToPhone -> "zapisany"
            !item.stillOnGlasses -> "tylko podgląd"
            else -> null
        }
        if (badge != null) {
            Text(
                badge,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
