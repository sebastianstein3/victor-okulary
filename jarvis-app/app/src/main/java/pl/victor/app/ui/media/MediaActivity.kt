package pl.victor.app.ui.media

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.victor.app.VictorApplication
import pl.victor.app.ble.MediaLibrary
import pl.victor.app.ui.theme.VictorTheme
import java.io.File

/**
 * Galeria plików z okularów - zdjęcia, wideo i nagrania zrobione sprzętem.
 *
 * ## Dlaczego to nie jest zwykła lista
 * Pliki leżą w okularach, nie w telefonie. Żeby je zobaczyć, trzeba podnieść
 * grupę Wi-Fi Direct - a póki ona stoi, CAŁY ruch telefonu idzie przez okulary
 * (bez internetu). Dlatego sesja jest jawna: włącza ją przycisk, a wyjście z
 * ekranu ją zamyka. Ukrycie tego pod automatem znaczyłoby telefon bez sieci i
 * użytkownika, który nie wie dlaczego.
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

    private val _files = MutableStateFlow<List<Pair<MediaLibrary.Kind, List<MediaLibrary.Item>>>>(
        emptyList()
    )
    val files: StateFlow<List<Pair<MediaLibrary.Kind, List<MediaLibrary.Item>>>> =
        _files.asStateFlow()

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
    private val thumbnailQueue = ArrayDeque<MediaLibrary.Item>()
    private var thumbnailWorkerRunning = false

    fun load() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                _status.value = "Pytam okulary o listę plików..."
                val overBle = manager.findAlbumOverBle()
                if (overBle.isNotEmpty()) {
                    _files.value = MediaLibrary.group(overBle)
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
                _files.value = MediaLibrary.group(names)
                _status.value = if (names.isEmpty()) {
                    "Okulary nie mają jeszcze żadnych plików."
                } else {
                    "${names.size} plików. Dopóki galeria jest otwarta, telefon nie ma " +
                        "internetu - cały ruch idzie przez okulary."
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
    fun requestThumbnail(item: MediaLibrary.Item) {
        if (item.kind != MediaLibrary.Kind.PHOTO) return
        if (!requestedThumbnails.add(item.name)) return
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
    fun open(item: MediaLibrary.Item) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "Pobieram ${item.name}..."
            try {
                val bytes = manager.downloadFile(item.name)
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
    fun saveAllToPhone() {
        if (_saving.value != null || _busy.value) return
        val all = _files.value.flatMap { it.second }
        if (all.isEmpty()) {
            _status.value = "Nie ma czego zapisywać - najpierw wczytaj listę."
            return
        }
        saveJob = viewModelScope.launch {
            var saved = 0
            var skipped = 0
            var failed = 0
            try {
                all.forEachIndexed { index, item ->
                    _saving.value = SaveProgress(index, all.size, item.name)
                    val already = withContext(Dispatchers.IO) { alreadyInGallery(item.name) }
                    if (already) {
                        skipped++
                        return@forEachIndexed
                    }
                    val ok = runCatching {
                        val bytes = manager.downloadFile(item.name)
                        withContext(Dispatchers.IO) { writeToGallery(item.name, bytes) }
                    }.isSuccess
                    if (ok) saved++ else failed++
                }
                _status.value = buildString {
                    append("Zapisano $saved z ${all.size}")
                    if (skipped > 0) append(", pominięto $skipped już zapisanych")
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

    /** Czy plik o tej nazwie już leży w katalogu VICTOR galerii telefonu. */
    private fun alreadyInGallery(name: String): Boolean {
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
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
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

    /**
     * Czy pokazać pytanie o zwolnienie pamięci okularów.
     *
     * Osobne potwierdzenie, a nie zwykły przycisk, bo tej operacji może się nie
     * dać cofnąć - patrz [pl.victor.app.ble.GlassesProtocol.WORK_RELEASE_STORAGE].
     */
    private val _askRelease = MutableStateFlow(false)
    val askRelease: StateFlow<Boolean> = _askRelease.asStateFlow()

    fun askReleaseStorage() {
        if (_files.value.isEmpty()) {
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
                // Lista na ekranie odnosi się teraz do stanu sprzed operacji.
                if (after != null && before != null && after.total < before.total) {
                    _files.value = emptyList()
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
     * Wołane przy wyjściu z ekranu i to jest tu obowiązkowe: zostawiona sesja
     * to telefon bez internetu, a użytkownik nie ma jak skojarzyć jednego z
     * drugim.
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
    val askRelease by viewModel.askRelease.collectAsState()
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
                                Text("Zapisz wszystko")
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.cancelSaveAll() }) {
                                Text("Przerwij")
                            }
                        }
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
                        onRequestThumbnail = { viewModel.requestThumbnail(item) },
                        onClick = { viewModel.open(item) }
                    )
                }
            }
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
 */
@Composable
private fun MediaTile(
    item: MediaLibrary.Item,
    thumbnail: Bitmap?,
    enabled: Boolean,
    onRequestThumbnail: () -> Unit,
    onClick: () -> Unit
) {
    LaunchedEffect(item.name) { onRequestThumbnail() }

    Column(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
        }
        Text(
            item.name.substringAfterLast('/'),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
