package pl.victor.app.ui.meeting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import pl.victor.app.VictorApplication
import pl.victor.app.features.meeting.Meeting
import pl.victor.app.features.meeting.MeetingNotes
import pl.victor.app.features.meeting.MeetingRecorder
import pl.victor.app.ui.theme.VictorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notatki ze spotkania: start/stop, transkrypcja na żywo i zapisane spotkania.
 *
 * To samo, co głosem ("nagrywaj spotkanie" / "zakończ spotkanie") - ekran
 * jest dla tych, którzy wolą kliknąć, i do przeczytania notatki potem.
 */
class MeetingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { MeetingScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val orchestrator = remember { VictorApplication.get().orchestrator }
    val recorder = remember { orchestrator.meeting }
    val stan by recorder.stan.collectAsState()
    val linie by recorder.linie.collectAsState()
    var zapisane by remember { mutableStateOf(recorder.store.wszystkie()) }
    var rozwinięte by remember { mutableStateOf<String?>(null) }
    var teraz by remember { mutableStateOf(System.currentTimeMillis()) }

    // Lista odświeża się, gdy podsumowanie się skończy.
    LaunchedEffect(stan) {
        if (stan is MeetingRecorder.Stan.Bezczynny) zapisane = recorder.store.wszystkie()
        while (stan is MeetingRecorder.Stan.Nagrywa) {
            teraz = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val zgoda = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) orchestrator.startMeeting()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notatki ze spotkania") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz") }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Połóż telefon na stole i zacznij. Asystent przepisuje rozmowę na bieżąco, a po " +
                    "zakończeniu robi notatkę: temat, ustalenia, zadania. Głosem: \"nagrywaj spotkanie\" " +
                    "i \"zakończ spotkanie\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (val s = stan) {
                is MeetingRecorder.Stan.Nagrywa -> {
                    val sek = (teraz - s.startMs) / 1000
                    Text("🔴 Nagrywam - %d:%02d".format(sek / 60, sek % 60), fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { orchestrator.stopMeeting() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Zakończ i zrób notatkę") }
                }
                is MeetingRecorder.Stan.Podsumowuje -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text("Robię podsumowanie…")
                }
                is MeetingRecorder.Stan.Bezczynny -> Button(
                    onClick = {
                        val ma = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (ma) orchestrator.startMeeting() else zgoda.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🎙️ Zacznij nagrywać spotkanie") }
            }

            if (stan !is MeetingRecorder.Stan.Bezczynny && linie.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        MeetingNotes.transkrypcja(linie.takeLast(30)),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (zapisane.isNotEmpty()) {
                Text("Zapisane spotkania", style = MaterialTheme.typography.titleMedium)
            }
            zapisane.forEach { m ->
                SpotkanieKarta(
                    m = m,
                    rozwinięte = rozwinięte == m.id,
                    onKlik = { rozwinięte = if (rozwinięte == m.id) null else m.id },
                    onUdostępnij = {
                        val tekst = MeetingNotes.doUdostępnienia(m)
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tekst),
                                "Udostępnij notatkę"
                            )
                        )
                    },
                    onUsuń = {
                        recorder.store.usuń(m.id)
                        zapisane = recorder.store.wszystkie()
                    }
                )
            }
        }
    }
}

@Composable
private fun SpotkanieKarta(
    m: Meeting,
    rozwinięte: Boolean,
    onKlik: () -> Unit,
    onUdostępnij: () -> Unit,
    onUsuń: () -> Unit
) {
    val data = SimpleDateFormat("d MMM, HH:mm", Locale("pl", "PL")).format(Date(m.startMs))
    Card(modifier = Modifier.fillMaxWidth().clickable { onKlik() }) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(MeetingNotes.temat(m.podsumowanie) ?: "Spotkanie", fontWeight = FontWeight.Bold)
                    Text(
                        "$data · ${(m.koniecMs - m.startMs) / 60_000} min · ${m.linie.size} wypowiedzi",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = onUdostępnij) { Icon(Icons.Default.Share, contentDescription = "Udostępnij") }
                IconButton(onClick = onUsuń) { Icon(Icons.Default.Delete, contentDescription = "Usuń") }
            }
            if (rozwinięte) {
                Spacer(Modifier.height(8.dp))
                Text(m.podsumowanie ?: "Bez podsumowania - model nie odpowiedział.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("Transkrypcja", fontWeight = FontWeight.Bold)
                Text(MeetingNotes.transkrypcja(m.linie), style = MaterialTheme.typography.bodySmall)
                if (m.nieprzepisanych > 0) {
                    Text(
                        "Nie udało się przepisać ${m.nieprzepisanych} fragmentów.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
