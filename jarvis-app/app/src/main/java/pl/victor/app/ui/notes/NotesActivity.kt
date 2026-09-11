package pl.victor.app.ui.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pl.victor.app.VictorApplication
import pl.victor.app.google.NotesDocSync
import pl.victor.app.notes.Notes
import pl.victor.app.ui.theme.VictorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notatki - to, co użytkownik podyktował asystentowi.
 *
 * ## Co ten ekran robi, a czego nie
 * Notatki powstają GŁOSEM, w biegu. Tu się je przegląda, poprawia i kasuje -
 * bo dyktowanie zawsze coś przekręci, a notatka z literówką w nazwisku albo
 * w kwocie jest gorsza niż brak notatki. Nie ma folderów, tagów ani
 * formatowania: to jest lista zdań z datami, a nie notatnik do pisania.
 */
class NotesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { NotesScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as VictorApplication }
    val settings = remember { app.settings }
    val uiScope = rememberCoroutineScope()

    var notes by remember { mutableStateOf(settings.getNotes()) }
    var draft by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(settings.getNoteStyle()) }

    // Edycja: trzymamy INDEKS, bo tylko on jednoznacznie wskazuje notatkę -
    // dwie notatki mogą mieć identyczną treść.
    var editingIndex by remember { mutableStateOf(-1) }
    var editDraft by remember { mutableStateOf("") }

    // Streszczenia kluczujemy treścią i datą, a nie indeksem: po skasowaniu
    // notatki indeksy się przesuwają i streszczenie trafiłoby pod cudzą.
    var summaries by remember { mutableStateOf(mapOf<String, String>()) }
    var summarizing by remember { mutableStateOf<String?>(null) }

    // Eksport na Dysk Google (źródło dla NotebookLM).
    val docSync = remember { NotesDocSync(context) }
    var syncEnabled by remember { mutableStateOf(settings.isNotesDocSyncEnabled()) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var docId by remember { mutableStateOf(settings.getNotesDocId()) }

    fun runSync() {
        if (syncing) return
        syncing = true
        syncStatus = "Wysyłam notatki na Dysk..."
        uiScope.launch {
            when (val result = docSync.sync(settings.getNotes(), settings.getNotesDocId())) {
                is NotesDocSync.Result.Success -> {
                    settings.setNotesDocId(result.fileId)
                    docId = result.fileId
                    syncStatus = "Wysłano ${result.noteCount} notatek."
                }
                is NotesDocSync.Result.NoAccess ->
                    syncStatus = "Brak zgody na Dysk - włącz przełącznik jeszcze raz."
                is NotesDocSync.Result.Failed ->
                    syncStatus = "Nie udało się wysłać: ${result.reason}"
            }
            syncing = false
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Wynik czytamy przez samo sprawdzenie zgody: Google dokłada ją do już
        // zalogowanego konta, więc interesuje nas stan, a nie kod wyniku.
        if (docSync.hasAccess()) {
            syncEnabled = true
            settings.setNotesDocSyncEnabled(true)
            runSync()
        } else {
            syncStatus = "Bez zgody na Dysk nie mogę tam nic zapisać."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notatki") },
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
                Text(
                    "Powiedz „Notatka: kupić mleko”, „zapisz, że mam oddać książkę” " +
                        "albo „dodaj kupić mleko”. Zapytaj „sprawdź w notatkach” lub " +
                        "„co mam do zrobienia”, a V.I.C.T.O.R. odpowie na ich podstawie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                StylePicker(
                    style = style,
                    onChange = {
                        style = it
                        settings.setNoteStyle(it)
                    }
                )
            }

            item {
                DriveSyncCard(
                    enabled = syncEnabled,
                    busy = syncing,
                    status = syncStatus,
                    documentUrl = docId?.let { docSync.documentUrl(it) },
                    onToggle = { on ->
                        if (!on) {
                            syncEnabled = false
                            settings.setNotesDocSyncEnabled(false)
                            syncStatus = null
                        } else if (docSync.hasAccess()) {
                            syncEnabled = true
                            settings.setNotesDocSyncEnabled(true)
                            runSync()
                        } else {
                            consentLauncher.launch(
                                pl.victor.app.google.GoogleAccountManager(context)
                                    .getDriveConsentIntent()
                            )
                        }
                    },
                    onSyncNow = { runSync() },
                    onOpen = {
                        docId?.let { id ->
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(docSync.documentUrl(id))
                                )
                            )
                        }
                    }
                )
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Dopisz notatkę") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            notes = settings.addNote(draft.trim())
                            draft = ""
                        },
                        enabled = draft.isNotBlank()
                    ) {
                        Text("Dodaj")
                    }
                }
            }

            if (notes.isEmpty()) {
                item {
                    Text(
                        "Nie masz jeszcze żadnych notatek.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }

            itemsIndexed(notes) { index, note ->
                val key = noteKey(note)
                NoteCard(
                    note = note,
                    editing = editingIndex == index,
                    editDraft = editDraft,
                    summary = summaries[key],
                    summarizing = summarizing == key,
                    onEditDraftChange = { editDraft = it },
                    onStartEdit = {
                        editingIndex = index
                        editDraft = note.text
                    },
                    onCancelEdit = { editingIndex = -1 },
                    onSaveEdit = {
                        val text = editDraft.trim()
                        if (text.isNotBlank()) notes = settings.updateNote(index, text)
                        editingIndex = -1
                    },
                    onDelete = {
                        notes = settings.deleteNote(index)
                        if (editingIndex == index) editingIndex = -1
                    },
                    onSummarize = {
                        summarizing = key
                        uiScope.launch {
                            val result = app.orchestrator
                                .askModelPlain(Notes.summaryPrompt(note.text))
                            summaries = summaries + (
                                key to (
                                    result?.trim()
                                        ?: "Nie udało się streścić - sprawdź klucz API " +
                                        "i połączenie z siecią."
                                    )
                                )
                            summarizing = null
                        }
                    }
                )
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

/** Notatki nie mają identyfikatorów - para treść+data jest wystarczająco unikalna. */
private fun noteKey(note: Notes.Note): String = "${note.createdAtMs}|${note.text}"

/**
 * Wybór sposobu zapisywania dyktowanych notatek.
 *
 * Domyślnie dosłownie, bo to jedyny wariant, w którym na pewno nic nie ginie.
 * Porządkowanie przez model jest wygodne, ale zmienia słowa - i to musi być
 * świadoma decyzja użytkownika, a nie zachowanie, które sam odkryje.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StylePicker(style: Notes.Style, onChange: (Notes.Style) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Jak zapisywać dyktowane notatki",
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            FilterChip(
                selected = style == Notes.Style.VERBATIM,
                onClick = { onChange(Notes.Style.VERBATIM) },
                label = { Text("Słowo w słowo") }
            )
            FilterChip(
                selected = style == Notes.Style.AI,
                onClick = { onChange(Notes.Style.AI) },
                label = { Text("Uporządkuj przez AI") }
            )
        }
        Text(
            if (style == Notes.Style.VERBATIM) {
                "Zapisuję dokładnie to, co padło - razem z potknięciami."
            } else {
                "Zapisuję dosłownie, a chwilę później podmieniam na wersję " +
                    "poprawioną przez model. Sens zostaje bez zmian."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Eksport notatek do Dokumentu Google.
 *
 * ## Dlaczego akurat tak, a nie wprost do NotebookLM
 * NotebookLM nie ma publicznego API dla zwykłych kont Google - jest tylko
 * wersja Enterprise, wymagająca Google Cloud i konta firmowego. Ma za to
 * obsługę dokumentów z Dysku jako źródeł, razem z odświeżaniem. Dokument
 * dodaje się w NotebookLM RAZ, a potem aktualizuje się sam.
 */
@Composable
private fun DriveSyncCard(
    enabled: Boolean,
    busy: Boolean,
    status: String?,
    documentUrl: String?,
    onToggle: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onOpen: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Kopia na Dysku Google", fontWeight = FontWeight.Medium)
                    Text(
                        "Notatki lądują w jednym Dokumencie. Dodaj go raz jako " +
                            "źródło w NotebookLM - potem będzie się odświeżał sam.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onSyncNow, enabled = !busy) {
                        Text("Synchronizuj teraz")
                    }
                    if (documentUrl != null) {
                        TextButton(onClick = onOpen, enabled = !busy) { Text("Otwórz dokument") }
                    }
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                }
            }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: Notes.Note,
    editing: Boolean,
    editDraft: String,
    summary: String?,
    summarizing: Boolean,
    onEditDraftChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDelete: () -> Unit,
    onSummarize: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = onEditDraftChange,
                    label = { Text("Treść notatki") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSaveEdit, enabled = editDraft.isNotBlank()) {
                        Text("Zapisz")
                    }
                    TextButton(onClick = onCancelEdit) { Text("Anuluj") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(note.text, fontWeight = FontWeight.Medium)
                        if (note.createdAtMs > 0L) {
                            Text(
                                DATE_FORMAT.format(Date(note.createdAtMs)),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onStartEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edytuj notatkę")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Usuń notatkę")
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onSummarize, enabled = !summarizing) {
                        Text("Podsumuj")
                    }
                    if (summarizing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    }
                }

                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
