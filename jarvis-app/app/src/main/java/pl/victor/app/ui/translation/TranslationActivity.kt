package pl.victor.app.ui.translation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import pl.victor.app.VictorApplication
import pl.victor.app.translation.EarTranscript
import pl.victor.app.translation.SimultaneousTranslator
import pl.victor.app.ui.theme.VictorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Panel tłumaczenia na żywo - to, co słychać, i przekład, na ekranie telefonu.
 *
 * ## Skąd pomysł i czym się różni od pierwowzoru
 * Aplikacja producenta ma ekran "Simultaneous translation": dwa pola jedno nad
 * drugim (oryginał, przekład), wybór języków nad każdym i jeden duży przycisk
 * Start/Stop. Ten ekran ma ten sam układ, bo on po prostu działa - ale nie
 * powtarza jego braków, które widać w kodzie producenta:
 *  - tekst, który jeszcze się zmienia, wygląda tam tak samo jak pewny; tu jest
 *    szary i pochylony, więc widać, czemu jeszcze nie ufać,
 *  - błędy tłumaczenia są tam niewidoczne (komunikat powstaje i nigdzie nie
 *    trafia); tu każdy powód zakończenia jest na ekranie,
 *  - nie ma zamiany języków jednym ruchem ani kopiowania i wysyłania tekstu.
 *
 * ## Ekran jest tylko widokiem
 * Całą pracę robi tryb tłumaczenia ze słuchu w orkiestratorze - ten sam, który
 * włącza przycisk na okularach i komenda "tłumaczenie na żywo". Panel go
 * uruchamia, pokazuje i zatrzymuje; tryb działa dalej także po jego zamknięciu,
 * bo tak się go używa w rozmowie z telefonem w kieszeni.
 */
class TranslationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VictorTheme {
                TranslationScreen(
                    onBack = { finish() },
                    keepScreenOn = { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationScreen(onBack: () -> Unit, keepScreenOn: (Boolean) -> Unit) {
    val context = LocalContext.current
    val app = remember { VictorApplication.get() }
    val orchestrator = remember { app.orchestrator }
    val settings = remember { app.settings }
    val transcript by orchestrator.earTranscript.collectAsState()
    val running by orchestrator.earTranslation.collectAsState()
    val glassesState by app.glassesManager.connectionState.collectAsState()
    var from by remember { mutableStateOf(settings.getEarTranslationFrom()) }
    var to by remember { mutableStateOf(settings.getEarTranslationTo()) }
    var spoken by remember { mutableStateOf(settings.isEarTranslationSpoken()) }

    // Ekran gaśnie po kilkudziesięciu sekundach - w środku rozmowy z kimś, kto
    // właśnie mówi. Producent trzyma go zapalonym przez cały czas trwania.
    DisposableEffect(running) {
        keepScreenOn(running)
        onDispose { keepScreenOn(false) }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) orchestrator.startEarTranslation()
        else Toast.makeText(context, "Bez mikrofonu nie ma czego tłumaczyć.", Toast.LENGTH_LONG).show()
    }

    fun toggle() {
        if (running) {
            orchestrator.stopEarTranslation("przycisk w panelu")
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) orchestrator.startEarTranslation()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun setLanguages(newFrom: String, newTo: String) {
        from = newFrom
        to = newTo
        orchestrator.setEarTranslationLanguages(newFrom, newTo)
    }

    val fromName = SimultaneousTranslator.languageName(from)
    val toName = SimultaneousTranslator.languageName(to)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tłumaczenie na żywo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wróć")
                    }
                },
                actions = {
                    val hasText = transcript.segmenty.isNotEmpty()
                    IconButton(onClick = { copyTranscript(context, transcript) }, enabled = hasText) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Kopiuj")
                    }
                    IconButton(onClick = { shareTranscript(context, transcript) }, enabled = hasText) {
                        Icon(Icons.Default.Share, contentDescription = "Udostępnij")
                    }
                    IconButton(onClick = { orchestrator.clearEarTranscript() }, enabled = hasText) {
                        Icon(Icons.Default.Delete, contentDescription = "Wyczyść")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // === Języki ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LanguagePicker(
                    label = "Słyszę",
                    code = from,
                    modifier = Modifier.weight(1f),
                    onPick = { picked ->
                        // Ten sam język po obu stronach nie ma sensu - wybór
                        // języka docelowego jako źródła to w praktyce zamiana.
                        if (picked == to) setLanguages(picked, from) else setLanguages(picked, to)
                    }
                )
                IconButton(onClick = { setLanguages(to, from) }) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Zamień języki")
                }
                LanguagePicker(
                    label = "Tłumaczę na",
                    code = to,
                    modifier = Modifier.weight(1f),
                    onPick = { picked ->
                        if (picked == from) setLanguages(to, picked) else setLanguages(from, picked)
                    }
                )
            }

            // === Oryginał ===
            TextPane(
                title = "Słyszę · $fromName",
                modifier = Modifier.weight(1f),
                trailing = null
            ) {
                PaneText(
                    committed = transcript.segmenty.map { it.id to it.oryginał },
                    partial = transcript.częściowyOryginał,
                    highlightedId = null,
                    placeholder = if (running) "Mów albo podsuń telefon rozmówcy…"
                    else "Tu pojawi się to, co słychać."
                )
            }

            // === Przekład ===
            TextPane(
                title = "Przekład · $toName",
                modifier = Modifier.weight(1f),
                trailing = {
                    IconButton(onClick = {
                        spoken = !spoken
                        settings.setEarTranslationSpoken(spoken)
                    }) {
                        Icon(
                            if (spoken) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = if (spoken) "Wycisz przekład" else "Czytaj przekład na głos"
                        )
                    }
                }
            ) {
                PaneText(
                    committed = transcript.segmenty.map { it.id to it.przekład },
                    partial = transcript.częściowyPrzekład,
                    highlightedId = transcript.czytanyId,
                    placeholder = "Tu pojawi się przekład."
                )
            }

            // === Stan i komunikaty ===
            val status = when {
                transcript.komunikat != null && !running -> transcript.komunikat
                !running -> null
                transcript.status == EarTranscript.Status.MÓWIĘ -> "Czytam przekład…"
                else -> "Słucham (" + (
                    if (glassesState == pl.victor.app.ble.ConnectionState.READY) "okulary i telefon"
                    else "mikrofon telefonu"
                    ) + ")…"
            }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!running && transcript.komunikat != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!spoken && running) {
                Text(
                    "Głos wyłączony - przekład tylko na ekranie.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // === Start / Stop ===
            Button(
                onClick = { toggle() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = if (running) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(if (running) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Zatrzymaj tłumaczenie" else "Zacznij tłumaczyć", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun LanguagePicker(
    label: String,
    code: String,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(SimultaneousTranslator.languageName(code), fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SimultaneousTranslator.SUPPORTED_LANGUAGES.keys.forEach { option ->
                DropdownMenuItem(
                    text = { Text(SimultaneousTranslator.languageName(option)) },
                    onClick = {
                        expanded = false
                        onPick(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun TextPane(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (trailing != null) Box(modifier = Modifier.size(40.dp)) { trailing() }
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/**
 * Tekst jednego pola: gotowe odcinki, a po nich ogon, który jeszcze się zmienia.
 *
 * Przewija się sam na koniec przy każdej zmianie - jak u producenta. Czytany
 * właśnie odcinek jest wyróżniony (u producenta też), żeby wzrok trafiał tam,
 * gdzie jest ucho.
 */
@Composable
private fun ColumnScope.PaneText(
    committed: List<Pair<Long, String>>,
    partial: String,
    highlightedId: Long?,
    placeholder: String
) {
    val scroll = rememberScrollState()
    LaunchedEffect(committed.size, partial, highlightedId) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val empty = committed.isEmpty() && partial.isBlank()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(scroll)
    ) {
        if (empty) {
            Text(placeholder, color = muted, fontSize = 16.sp)
        } else {
            Text(
                buildAnnotatedString {
                    committed.forEachIndexed { i, (id, tekst) ->
                        if (i > 0) append("\n")
                        if (id == highlightedId) {
                            withStyle(SpanStyle(color = primary, fontWeight = FontWeight.SemiBold)) {
                                append(tekst)
                            }
                        } else {
                            append(tekst)
                        }
                    }
                    if (partial.isNotBlank()) {
                        if (committed.isNotEmpty()) append("\n")
                        withStyle(SpanStyle(color = muted.copy(alpha = 0.8f), fontStyle = FontStyle.Italic)) {
                            append(partial)
                        }
                    }
                },
                fontSize = 18.sp,
                lineHeight = 26.sp
            )
        }
    }
}

private fun transcriptText(transcript: EarTranscript): String {
    val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return transcript.doUdostępnienia(
        SimultaneousTranslator.languageName(transcript.z),
        SimultaneousTranslator.languageName(transcript.na)
    ) { clock.format(Date(it)) }
}

private fun copyTranscript(context: Context, transcript: EarTranscript) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Tłumaczenie", transcriptText(transcript)))
    Toast.makeText(context, "Skopiowano", Toast.LENGTH_SHORT).show()
}

private fun shareTranscript(context: Context, transcript: EarTranscript) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Tłumaczenie - V.I.C.T.O.R.")
        putExtra(Intent.EXTRA_TEXT, transcriptText(transcript))
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Wyślij tłumaczenie")) }
}
