package pl.victor.app.ui.hermes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.victor.app.hermes.HermesPlik
import pl.victor.app.hermes.HermesPliki
import pl.victor.app.hermes.HermesPlikiKlient
import pl.victor.app.hermes.RodzajPliku
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import pl.victor.app.VictorApplication
import pl.victor.app.hermes.HermesDostępność
import pl.victor.app.hermes.HermesWpis
import pl.victor.app.hermes.StatusZgody
import pl.victor.app.ui.settings.SettingsActivity
import pl.victor.app.ui.theme.VictorTheme

/**
 * Panel Hermesa: widać rozmowę z agentem na komputerze (także polecenia
 * głosowe "Hermes, …" z okularów), można do niego pisać, zatwierdzać jego
 * prośby o zgodę przyciskami i zatrzymać zadanie.
 *
 * Rozmowa żyje tylko w pamięci aplikacji - patrz [pl.victor.app.hermes.HermesPanelStore].
 */
class HermesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { HermesScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as VictorApplication }
    val orch = app.orchestrator
    val settings = app.settings
    val clipboard = LocalClipboardManager.current

    val wpisy by orch.hermesPanel.wpisy.collectAsState()
    val trwa by orch.hermesPanel.trwa.collectAsState()
    val oczekująca by orch.pendingActionConfirmation.collectAsState()

    val scope = rememberCoroutineScope()
    val klientPlików = remember {
        HermesPlikiKlient(
            adresMostu = { settings.hermesFilesUrlEffective() },
            klucz = { settings.getHermesKey() },
            katalog = File(context.cacheDir, "hermes_pliki")
        )
    }
    val stanyPlików = remember { mutableStateMapOf<String, StanPliku>() }

    fun pobierz(p: HermesPlik, otwórzPo: Boolean) {
        if (stanyPlików[p.odnośnik] is StanPliku.Pobieranie) return
        stanyPlików[p.odnośnik] = StanPliku.Pobieranie
        scope.launch {
            when (val w = klientPlików.pobierz(p)) {
                is HermesPlikiKlient.Wynik.Gotowy -> {
                    val podgląd = if (w.mime.startsWith("image/") && w.mime != "image/svg+xml") {
                        withContext(Dispatchers.IO) { wczytajPodgląd(w.plik) }
                    } else null
                    stanyPlików[p.odnośnik] = StanPliku.Gotowy(w.plik, w.mime, podgląd)
                    // Obraz pokazujemy w karcie (dotknięcie otwiera go w galerii); resztę od razu otwieramy.
                    if (otwórzPo && podgląd == null) otwórzPlik(context, w.plik, w.mime, udostępnij = false)
                }
                is HermesPlikiKlient.Wynik.Błąd -> stanyPlików[p.odnośnik] = StanPliku.Błąd(w.tekst)
            }
        }
    }

    var draft by remember { mutableStateOf("") }
    // Domyślnie cicho: panel służy do czytania i pisania, a głos mają okulary.
    var czytajNaGłos by remember { mutableStateOf(false) }
    // Dostępność czytamy przy każdym wejściu na ekran - ustawienia mogły się zmienić.
    val dostępność = remember(wpisy.size, trwa) { settings.hermesAvailability() }

    val listState = rememberLazyListState()
    var podążaj by remember { mutableStateOf(true) }
    // Użytkownik przewinął w górę = przestajemy go ciągnąć na dół.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { wRuchu ->
            if (!wRuchu) podążaj = !listState.canScrollForward
        }
    }
    val klucz = wpisy.lastOrNull().let { w ->
        when (w) {
            is HermesWpis.Hermes -> w.id to w.tekst.length
            null -> 0L to 0
            else -> w.id to 0
        }
    }
    LaunchedEffect(klucz) {
        if (podążaj && wpisy.isNotEmpty()) listState.scrollToItem(wpisy.lastIndex)
    }

    fun wyślij() {
        val t = draft.trim()
        if (t.isEmpty() || trwa) return
        draft = ""
        podążaj = true
        orch.wyślijDoHermesa(t, czytajNaGłos)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Hermes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(orch.hermesPanel.jakoTekst())) },
                        enabled = wpisy.isNotEmpty()
                    ) { Icon(Icons.Default.ContentCopy, contentDescription = "Kopiuj rozmowę") }
                    IconButton(
                        onClick = { orch.hermesPanel.wyczyść() },
                        enabled = wpisy.isNotEmpty()
                    ) { Icon(Icons.Default.DeleteSweep, contentDescription = "Wyczyść rozmowę") }
                    IconButton(onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }) { Icon(Icons.Default.Settings, contentDescription = "Ustawienia Hermesa") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Switch(checked = czytajNaGłos, onCheckedChange = { czytajNaGłos = it })
                        Text(
                            "  Czytaj odpowiedź na głos",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = { Text("Napisz do Hermesa…") },
                            enabled = dostępność == HermesDostępność.MOŻNA,
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { wyślij() }),
                            modifier = Modifier.weight(1f)
                        )
                        if (trwa) {
                            FilledIconButton(
                                onClick = { orch.zatrzymajHermesa() },
                                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) { Icon(Icons.Default.Stop, contentDescription = "Zatrzymaj zadanie") }
                        } else {
                            FilledIconButton(
                                onClick = { wyślij() },
                                enabled = draft.isNotBlank() && dostępność == HermesDostępność.MOŻNA
                            ) { Icon(Icons.Default.Send, contentDescription = "Wyślij") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (dostępność != HermesDostępność.MOŻNA) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            dostępność.komunikat ?: "Hermes jest niedostępny.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }) { Text("Otwórz ustawienia") }
                    }
                }
            }
            if (wpisy.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Tu zobaczysz rozmowę z Hermesem - także polecenia głosowe, które " +
                            "zaczynasz od „Hermes, …” w okularach. Możesz też pisać do niego " +
                            "stąd, a jego prośby o zgodę zatwierdzasz przyciskami.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(wpisy, key = { it.id }) { w ->
                        when (w) {
                            is HermesWpis.Użytkownik -> Dymek(
                                tekst = (if (w.głosem) "🎙 " else "") + w.tekst,
                                zPrawej = true
                            )
                            is HermesWpis.Hermes -> {
                                // Linie `PLIK: …` pokazujemy jako karty plików, nie jako tekst.
                                val widoczny = HermesPliki.bezZnaczników(w.tekst)
                                if (widoczny.isNotEmpty() || w.trwa) {
                                    Dymek(tekst = widoczny + if (w.trwa) " ▍" else "", zPrawej = false)
                                }
                            }
                            is HermesWpis.Pliki -> KartaPlików(
                                pliki = w.pliki,
                                stany = stanyPlików,
                                onOtwórz = { pobierz(it, otwórzPo = true) },
                                onPodgląd = { p ->
                                    (stanyPlików[p.odnośnik] as? StanPliku.Gotowy)?.let {
                                        otwórzPlik(context, it.plik, it.mime, udostępnij = false)
                                    }
                                },
                                onUdostępnij = { p ->
                                    (stanyPlików[p.odnośnik] as? StanPliku.Gotowy)?.let {
                                        otwórzPlik(context, it.plik, it.mime, udostępnij = true)
                                    }
                                }
                            )
                            is HermesWpis.Narzędzie -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                if (!w.zakończone) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(0.dp).widthIn(max = 14.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                                Text(
                                    (if (w.zakończone) "✓ " else "") + w.opis,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            is HermesWpis.Zgoda -> KartaZgody(
                                w = w,
                                aktywna = w.status == StatusZgody.CZEKA &&
                                    oczekująca?.hermesZgoda != null,
                                onZezwól = { orch.confirmAction() },
                                onOdmów = { orch.cancelAction() }
                            )
                            is HermesWpis.Informacja -> Text(
                                w.tekst,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (w.błąd) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Dymek(tekst: String, zPrawej: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (zPrawej) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (zPrawej) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            // Zaznaczanie i kopiowanie fragmentów odpowiedzi (np. polecenia, ścieżki).
            SelectionContainer {
                Text(tekst, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun KartaZgody(
    w: HermesWpis.Zgoda,
    aktywna: Boolean,
    onZezwól: () -> Unit,
    onOdmów: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hermes prosi o zgodę", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(w.opis, style = MaterialTheme.typography.bodyMedium)
            }
            when {
                aktywna -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onZezwól) { Text("✅ Zezwól") }
                    Button(
                        onClick = onOdmów,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("⛔ Odmów") }
                }
                else -> Text(
                    when (w.status) {
                        StatusZgody.CZEKA -> "Czeka na decyzję w oknie na ekranie głównym."
                        StatusZgody.ZEZWOLONO -> "✅ Zezwolono"
                        StatusZgody.ODMÓWIONO -> "⛔ Odmówiono"
                        StatusZgody.WYGASŁA -> "Zgoda wygasła - zadanie już się skończyło."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Stan jednego pliku od Hermesa w panelu. */
private sealed interface StanPliku {
    object Pobieranie : StanPliku
    class Gotowy(val plik: File, val mime: String, val podgląd: ImageBitmap?) : StanPliku
    class Błąd(val tekst: String) : StanPliku
}

/** Miniatura do karty - dekodowana z ograniczeniem rozmiaru, żeby duży obraz nie zapchał pamięci. */
private fun wczytajPodgląd(plik: File): ImageBitmap? = runCatching {
    val granice = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(plik.absolutePath, granice)
    var próbkowanie = 1
    while (granice.outWidth / próbkowanie > 1280 || granice.outHeight / próbkowanie > 1280) próbkowanie *= 2
    val opcje = BitmapFactory.Options().apply { inSampleSize = próbkowanie }
    BitmapFactory.decodeFile(plik.absolutePath, opcje)?.asImageBitmap()
}.getOrNull()

private fun otwórzPlik(context: Context, plik: File, mime: String, udostępnij: Boolean) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", plik)
    }.getOrNull() ?: return
    val intent = if (udostępnij) {
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Udostępnij plik"
        )
    } else {
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Brak aplikacji, która otworzy ten plik.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun KartaPlików(
    pliki: List<HermesPlik>,
    stany: Map<String, StanPliku>,
    onOtwórz: (HermesPlik) -> Unit,
    onPodgląd: (HermesPlik) -> Unit,
    onUdostępnij: (HermesPlik) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pliki od Hermesa", style = MaterialTheme.typography.titleSmall)
            pliki.forEach { p ->
                val stan = stany[p.odnośnik]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            when (p.rodzaj) {
                                RodzajPliku.OBRAZ -> Icons.Default.ImageIcon
                                RodzajPliku.DOKUMENT -> Icons.Default.Description
                                RodzajPliku.DANE -> Icons.Default.TableChart
                                RodzajPliku.AUDIO -> Icons.Default.MusicNote
                                RodzajPliku.WIDEO -> Icons.Default.Videocam
                                RodzajPliku.ARCHIWUM -> Icons.Default.Archive
                            },
                            contentDescription = p.rodzaj.etykieta
                        )
                        Column(Modifier.weight(1f)) {
                            Text(p.nazwa, style = MaterialTheme.typography.bodyMedium)
                            // Skąd plik pochodzi - adres z odpowiedzi agenta bywa podsunięty
                            // przez czytaną przez niego stronę, więc widać go przed pobraniem.
                            Text(
                                if (p.zdalny) "z internetu: " + (runCatching { java.net.URI(p.odnośnik).host }.getOrNull() ?: "?")
                                else "z komputera",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        when (stan) {
                            is StanPliku.Pobieranie -> CircularProgressIndicator(
                                modifier = Modifier.widthIn(max = 24.dp), strokeWidth = 2.dp
                            )
                            is StanPliku.Gotowy -> {
                                IconButton(onClick = { onUdostępnij(p) }) {
                                    Icon(Icons.Default.Share, contentDescription = "Udostępnij")
                                }
                                OutlinedButton(onClick = { onPodgląd(p) }) { Text("Otwórz") }
                            }
                            else -> OutlinedButton(onClick = { onOtwórz(p) }) {
                                Text(if (stan is StanPliku.Błąd) "Ponów" else "Pobierz")
                            }
                        }
                    }
                    if (stan is StanPliku.Błąd) {
                        Text(stan.tekst, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (stan is StanPliku.Gotowy && stan.podgląd != null) {
                        Image(
                            bitmap = stan.podgląd,
                            contentDescription = p.nazwa,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().clickable { onPodgląd(p) }
                        )
                    }
                }
            }
        }
    }
}
