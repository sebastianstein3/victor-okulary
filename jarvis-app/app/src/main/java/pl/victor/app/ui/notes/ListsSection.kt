package pl.victor.app.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import pl.victor.app.VictorApplication
import pl.victor.app.lists.ListCommands
import pl.victor.app.lists.ListsStore
import pl.victor.app.lists.UserLists

/**
 * Listy (zakupy, zadania, nazwane) - podgląd i proste poprawki.
 *
 * Listy powstają GŁOSEM („dodaj mleko do listy zakupów”); tu się je
 * przegląda, odhacza, kasuje pozycje i dopisuje ręcznie. Zmiany idą do tego
 * samego zapisu co polecenia głosowe ([ListsStore]) i - gdy włączona - do
 * synchronizacji z Google Tasks.
 */
@Composable
fun ListsSection() {
    val context = LocalContext.current
    val store = remember { ListsStore.get(context) }
    val lists by store.lists.collectAsState()
    var newListName by remember { mutableStateOf("") }

    fun changed(name: String) {
        runCatching { VictorApplication.get().orchestrator.lists.syncInBackground(name) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Listy", fontWeight = FontWeight.Medium)
            Text(
                "Powiedz „dodaj 2 mleka do listy zakupów”, „co mam na liście zakupów”, " +
                    "„odhacz chleb”. Synchronizację z Google Tasks włączysz w Ustawieniach, " +
                    "w karcie konta Google.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            lists.forEach { list ->
                ListCard(
                    list = list,
                    onToggle = { item, done ->
                        store.update { all ->
                            // Świeży stan z magazynu, nie migawka z ostatniego rysowania -
                            // inaczej klik nadpisywał zmianę z synchronizacji albo głosu.
                            val current = UserLists.find(all, list.name) ?: return@update all to Unit
                            ListsStore.replace(all, UserLists.setDone(current, item.id, done, System.currentTimeMillis())) to Unit
                        }
                        changed(list.name)
                    },
                    onDelete = { item ->
                        store.update { all ->
                            val current = UserLists.find(all, list.name) ?: return@update all to Unit
                            ListsStore.replace(all, UserLists.removeById(current, item.id)) to Unit
                        }
                        changed(list.name)
                    },
                    onAdd = { text ->
                        store.update { all ->
                            val current = UserLists.find(all, list.name) ?: list
                            val c = UserLists.add(
                                current, ListCommands.splitItems(text), System.currentTimeMillis(), ListsStore::newId
                            )
                            ListsStore.replace(all, c.list) to Unit
                        }
                        changed(list.name)
                    },
                    onClear = {
                        store.update { all ->
                            val current = UserLists.find(all, list.name) ?: return@update all to Unit
                            ListsStore.replace(all, UserLists.clear(current)) to Unit
                        }
                        changed(list.name)
                    }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    label = { Text("Nowa lista (np. na wyjazd)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        val name = ListCommands.canonicalListName(newListName)
                        store.update { all ->
                            (if (UserLists.find(all, name) != null) all else all + UserLists.UserList(name)) to Unit
                        }
                        newListName = ""
                    },
                    enabled = newListName.isNotBlank()
                ) { Text("Utwórz") }
            }
        }
    }
}

@Composable
private fun ListCard(
    list: UserLists.UserList,
    onToggle: (UserLists.Item, Boolean) -> Unit,
    onDelete: (UserLists.Item) -> Unit,
    onAdd: (String) -> Unit,
    onClear: () -> Unit
) {
    var draft by remember(list.name) { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${list.title} (${list.open.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (list.items.isNotEmpty()) TextButton(onClick = onClear) { Text("Wyczyść") }
        }
        list.items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = item.done, onCheckedChange = { onToggle(item, it) })
                Text(
                    item.display,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onDelete(item) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Usuń ${item.display}")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Dopisz") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onAdd(draft.trim())
                    draft = ""
                },
                enabled = draft.isNotBlank()
            ) { Text("Dodaj") }
        }
    }
}
