package pl.victor.app.ui.developer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.ui.PlayerView
import pl.victor.app.livestream.LabState
import pl.victor.app.livestream.LiveStreamLab
import pl.victor.app.livestream.LiveStreamLabLog
import pl.victor.app.ui.theme.VictorTheme
import android.widget.Toast

/**
 * "Opcje programistyczne" - ukryty ekran, odsłaniany stuknięciem numeru wersji
 * w Ustawieniach (patrz `DeveloperOptionsGate` w [pl.victor.app.ui.settings.SettingsActivity]).
 *
 * Na razie zawiera wyłącznie Live Stream Lab - patrz [LiveStreamLab] po pełny
 * opis kontekstu i zasad bezpieczeństwa. Ten ekran istnieje TYLKO po to, żeby
 * ta funkcja nie była widoczna dla zwykłego użytkownika przez przypadek.
 */
class DeveloperOptionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VictorTheme {
                DeveloperOptionsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperOptionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lab = remember { LiveStreamLab(context) }
    val state by lab.state.collectAsState()

    // Zwolnij odtwarzacz i sieć P2P, gdy user wyjdzie z ekranu - inaczej
    // zostają zajęte w tle.
    DisposableEffect(Unit) {
        onDispose { lab.stopProbe() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🧪 Opcje programistyczne") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            WarningBanner()
            LiveStreamLabSection(lab = lab, state = state)
            LogSection(lab = lab)
        }
    }
}

@Composable
private fun WarningBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "⚠️ Tylko na sprzęt testowy",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "Ten panel wysyła do okularów komendy, których znaczenie NIE jest " +
                    "potwierdzone, żeby znaleźć aktywację ukrytego trybu live streamingu. " +
                    "Może nie zrobić nic, albo coś nieoczekiwanego. Używaj wyłącznie na " +
                    "okularach przeznaczonych do testów, nie na jedynej parze, z której " +
                    "korzystasz na co dzień.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun LiveStreamLabSection(lab: LiveStreamLab, state: LabState) {
    var pendingCommand by remember { mutableStateOf<PendingCommand?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "📡 Live Stream Lab",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text("Stan: ${stateLabel(state)}", style = MaterialTheme.typography.bodyMedium)

            Text(
                "1. Wyślij jedną z eksperymentalnych komend. 2. Szukaj strumienia RTSP. " +
                    "Jeśli nic się nie znajdzie - tryb 8 najpewniej się nie aktywował.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pendingCommand = PendingCommand.Cmd07 }) {
                    Text("Wyślij 0x07")
                }
                OutlinedButton(onClick = { pendingCommand = PendingCommand.Cmd0D }) {
                    Text("Wyślij 0x0D")
                }
            }
            OutlinedButton(onClick = { pendingCommand = PendingCommand.Restart }) {
                Text("🔄 Restart okularów (odzyskiwanie)")
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { lab.startProbe() },
                    enabled = state !is LabState.ConnectingP2p && state !is LabState.ProbingRtsp
                ) {
                    Text("▶️ Szukaj strumienia RTSP")
                }
                OutlinedButton(onClick = { lab.stopProbe() }) {
                    Text("⏹️ Zatrzymaj")
                }
            }

            if (state is LabState.Playing) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx) },
                    update = { view -> view.player = lab.getPlayer() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
            }
        }
    }

    pendingCommand?.let { command ->
        CommandConfirmDialog(
            command = command,
            onConfirm = {
                when (command) {
                    PendingCommand.Cmd07 -> lab.sendCommand07()
                    PendingCommand.Cmd0D -> lab.sendCommand0D()
                    PendingCommand.Restart -> lab.sendRestartDevice()
                }
                pendingCommand = null
            },
            onDismiss = { pendingCommand = null }
        )
    }
}

private enum class PendingCommand { Cmd07, Cmd0D, Restart }

@Composable
private fun CommandConfirmDialog(
    command: PendingCommand,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val (title, message) = when (command) {
        PendingCommand.Cmd07 -> "Wysłać komendę 0x07?" to
            ("Znaczenie tej komendy jest NIEPOTWIERDZONE. To jeden z dwóch kandydatów " +
                "na aktywację trybu live streamingu, ale równie dobrze może nie zrobić " +
                "nic. Wysyłaj tylko na sprzęt przeznaczony do testów.")
        PendingCommand.Cmd0D -> "Wysłać komendę 0x0D?" to
            ("Znaczenie tej komendy jest NIEPOTWIERDZONE. To jeden z dwóch kandydatów " +
                "na aktywację trybu live streamingu, ale równie dobrze może nie zrobić " +
                "nic. Wysyłaj tylko na sprzęt przeznaczony do testów.")
        PendingCommand.Restart -> "Zrestartować okulary?" to
            ("To potwierdzona, bezpieczna komenda restartu urządzenia - użyj, jeśli " +
                "okulary przestały reagować po eksperymencie.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Wyślij") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

private fun stateLabel(state: LabState): String = when (state) {
    is LabState.Idle -> "Bezczynny"
    is LabState.ConnectingP2p -> "Łączenie Wi-Fi Direct..."
    is LabState.ProbingRtsp -> "Czekam na obraz: ${state.url}"
    is LabState.Playing -> "Odtwarzam: ${state.url}"
    is LabState.Error -> "Błąd: ${state.message}"
}

@Composable
private fun LogSection(lab: LiveStreamLab) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "📋 Log sesji",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Każda wysłana komenda, odpowiedź, próba RTSP i stan odtwarzacza " +
                    "zapisują się do pliku, który możesz wyeksportować i przesłać dalej.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { exportLog(context, lab.log) }) {
                Text("📤 Eksportuj log")
            }
        }
    }
}

private fun exportLog(context: Context, log: LiveStreamLabLog) {
    val file = log.currentSessionFile() ?: log.listSessions().firstOrNull()
    if (file == null || !file.exists()) {
        Toast.makeText(context, "Brak logu do wysłania - najpierw coś zrób w Live Stream Lab", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Wyślij log Live Stream Lab"))
}
