package pl.victor.app.ui.memory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.victor.app.VictorApplication
import pl.victor.app.memory.UserFacts
import pl.victor.app.ui.theme.VictorTheme

/**
 * Co asystent wie o użytkowniku.
 *
 * ## Dlaczego ten ekran MUSI istnieć
 * Fakty idą do modelu przy każdym pytaniu, więc jeden błędny - przekręcone
 * imię, dawny adres, źle zrozumiane uczulenie - będzie powtarzany pewnym
 * głosem bez końca i nie da się tego cofnąć rozmową. Miejsce, w którym widać
 * całą listę i można ją poprawić albo skasować, jest tu warunkiem sensu, a nie
 * dodatkiem.
 */
class FactsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { FactsScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as VictorApplication).settings }

    var facts by remember { mutableStateOf(settings.getFacts()) }
    var draft by remember { mutableStateOf("") }
    var editingIndex by remember { mutableStateOf(-1) }
    var editDraft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Co o Tobie wiem") },
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
                    "Powiedz „zapamiętaj, że mieszkam w Krakowie” albo „zapamiętaj, " +
                        "że nie jem laktozy”. Te fakty idą do asystenta przy KAŻDYM " +
                        "pytaniu, więc nie musisz ich powtarzać.\n\n" +
                        "„Zapamiętaj” to coś o Tobie, „zapisz” to zadanie na później " +
                        "— tamto trafia do Notatek.\n\n" +
                        "Możesz też powiedzieć „co o mnie wiesz” albo „zapomnij o Krakowie”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
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
                        label = { Text("Dopisz fakt o sobie") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            facts = settings.addFact(draft.trim())
                            draft = ""
                        },
                        enabled = draft.isNotBlank()
                    ) {
                        Text("Dodaj")
                    }
                }
            }

            if (facts.isEmpty()) {
                item {
                    Text(
                        "Nie zapamiętałem jeszcze niczego o Tobie.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }

            itemsIndexed(facts) { index, fact ->
                FactCard(
                    fact = fact,
                    editing = editingIndex == index,
                    editDraft = editDraft,
                    onEditDraftChange = { editDraft = it },
                    onStartEdit = {
                        editingIndex = index
                        editDraft = fact.text
                    },
                    onCancelEdit = { editingIndex = -1 },
                    onSaveEdit = {
                        val text = editDraft.trim()
                        // Patrz NotesActivity: indeks na zestarzałej liście wskazuje inny wpis.
                        val aktualne = settings.getFacts()
                        if (aktualne.getOrNull(index)?.createdAtMs != fact.createdAtMs) {
                            facts = aktualne
                        } else if (text.isNotBlank()) {
                            facts = settings.updateFact(index, text)
                        }
                        editingIndex = -1
                    },
                    onDelete = {
                        val aktualne = settings.getFacts()
                        facts = if (aktualne.getOrNull(index)?.createdAtMs == fact.createdAtMs) {
                            settings.deleteFact(index)
                        } else {
                            aktualne
                        }
                        if (editingIndex == index) editingIndex = -1
                    }
                )
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun FactCard(
    fact: UserFacts.Fact,
    editing: Boolean,
    editDraft: String,
    onEditDraftChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = onEditDraftChange,
                    label = { Text("Treść faktu") },
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
                    Text(
                        fact.text,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onStartEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edytuj fakt")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Usuń fakt")
                    }
                }
            }
        }
    }
}
