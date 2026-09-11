package pl.victor.app.ui.media

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.victor.app.VictorApplication
import pl.victor.app.ble.MediaLibrary
import pl.victor.app.ui.theme.VictorTheme
import androidx.compose.runtime.collectAsState

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

    private val _preview = MutableStateFlow<ByteArray?>(null)
    val preview: StateFlow<ByteArray?> = _preview.asStateFlow()

    private val _sessionOpen = MutableStateFlow(false)
    val sessionOpen: StateFlow<Boolean> = _sessionOpen.asStateFlow()

    fun load() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "Podnoszę połączenie Wi-Fi z okularami..."
            try {
                if (!manager.openMediaSession()) {
                    // Nieudane otwarcie TEŻ potrafi zostawić podniesioną grupę
                    // P2P, a wtedy telefon zostaje bez internetu - tyle że bez
                    // ostrzeżenia, które pokazujemy przy otwarciu udanym.
                    // closeSession() jest bramkowane flagą _sessionOpen, która w
                    // tym miejscu jeszcze nie jest ustawiona, więc sprzątamy
                    // wprost. Inaczej po nieudanej próbie przestają działać
                    // także pytania do modelu - bez widocznego związku z galerią.
                    manager.endTransferSession()
                    // Przyczyna z VictorManagera, nie jedno zdanie na wszystko:
                    // brak Wi-Fi, brak zgody, nieodnaleziona sieć i brak adresu
                    // to cztery różne awarie i cztery różne rzeczy do zrobienia.
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
                    "Uwaga: dopóki galeria jest otwarta, telefon nie ma internetu - " +
                        "cały ruch idzie przez okulary."
                }
            } catch (e: Exception) {
                _status.value = "Nie udało się wczytać plików: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun open(item: MediaLibrary.Item) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "Pobieram ${item.name}..."
            try {
                val bytes = manager.downloadFile(item.name)
                if (MediaLibrary.isViewable(item)) _preview.value = bytes
                _status.value = "Pobrano ${item.name} (${bytes.size / 1024} kB)."
                lastDownload = item.name to bytes
            } catch (e: Exception) {
                _status.value = "Nie udało się pobrać ${item.name}: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    private var lastDownload: Pair<String, ByteArray>? = null

    /**
     * Zapisuje ostatnio pobrany plik w pamięci telefonu.
     *
     * Przez MediaStore, a nie do katalogu aplikacji: plik ma się pojawić w
     * galerii telefonu, bo po to się go pobiera. Katalog prywatny aplikacji
     * znika razem z jej odinstalowaniem i nie widzi go żadna inna aplikacja.
     */
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

    private fun writeToGallery(name: String, bytes: ByteArray) {
        val resolver = getApplication<android.app.Application>().contentResolver
        val kind = MediaLibrary.kindOf(name)
        val collection = when (kind) {
            MediaLibrary.Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaLibrary.Kind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val folder = when (kind) {
            MediaLibrary.Kind.VIDEO -> Environment.DIRECTORY_MOVIES
            MediaLibrary.Kind.AUDIO -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_PICTURES
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/VICTOR")
            }
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("system nie dał miejsca na plik")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("nie udało się otworzyć pliku do zapisu")
    }

    fun closePreview() {
        _preview.value = null
    }

    /**
     * Zamyka grupę Wi-Fi Direct.
     *
     * Wołane przy wyjściu z ekranu i to jest tu obowiązkowe: zostawiona sesja
     * to telefon bez internetu, a użytkownik nie ma jak skojarzyć jednego z
     * drugim.
     */
    fun closeSession() {
        if (!_sessionOpen.value) return
        manager.endTransferSession()
        _sessionOpen.value = false
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

    DisposableEffect(Unit) {
        onDispose { viewModel.closeSession() }
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Button(onClick = { viewModel.load() }, enabled = !busy) {
                        Text(if (sessionOpen) "Odśwież" else "Połącz i wczytaj")
                    }
                    OutlinedButton(onClick = { viewModel.saveLastToPhone() }, enabled = !busy) {
                        Text("Zapisz w telefonie")
                    }
                }
            }

            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            status?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            preview?.let { bytes ->
                item {
                    val bitmap = remember(bytes) {
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                            .getOrNull()
                    }
                    if (bitmap != null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Podgląd zdjęcia",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedButton(
                                    onClick = { viewModel.closePreview() },
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    Text("Zamknij podgląd")
                                }
                            }
                        }
                    }
                }
            }

            files.forEach { (kind, items) ->
                item {
                    Text(
                        "${kind.emoji} ${kind.title} (${items.size})",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                items(items) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { viewModel.open(item) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(item.name)
                            Text(
                                if (MediaLibrary.isViewable(item)) {
                                    "Dotknij, żeby pobrać i zobaczyć"
                                } else {
                                    "Dotknij, żeby pobrać"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
