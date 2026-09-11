package pl.victor.app.ui.commands

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import pl.victor.app.VictorApplication
import pl.victor.app.actions.CommandCatalog
import pl.victor.app.actions.CustomCommands
import pl.victor.app.ui.theme.VictorTheme

/**
 * Ekran "Komendy": co V.I.C.T.O.R. rozumie i jak dołożyć własne frazy.
 *
 * ## Po co osobny ekran
 * Panel na ekranie głównym pokazuje przykłady do kliknięcia - dobre na start,
 * za małe na spis. Tutaj jest pełna lista z opisem DZIAŁANIA każdej komendy, a
 * nie tylko z przykładem, plus jedyne miejsce, w którym da się dopisać własną
 * frazę.
 */
class CommandsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { CommandsScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember {
        (context.applicationContext as VictorApplication).settings
    }
    var custom by remember { mutableStateOf(settings.getCustomCommands()) }

    fun save(list: List<CustomCommands.CustomCommand>) {
        settings.setCustomCommands(list)
        custom = list
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Komendy") },
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
                    "Wszystko poniżej działa głosem - przez okulary albo przyciskiem " +
                        "mikrofonu - i po wpisaniu z klawiatury.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                CustomCommandEditor(
                    commands = custom,
                    onAdd = { save(custom + it) },
                    onRemove = { index -> save(custom.filterIndexed { i, _ -> i != index }) }
                )
            }

            CommandCatalog.grouped().forEach { (group, commands) ->
                item {
                    Text(
                        group.emoji + " " + group.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                items(commands) { info -> CommandCard(info) }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandCard(info: CommandCatalog.CommandInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (info.needsGlasses) {
                    Text("Wymaga okularów", fontSize = 12.sp)
                }
            }
            Text(
                info.whatItDoes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                info.examples.forEach { example ->
                    SuggestionChip(
                        onClick = {},
                        label = { Text(example, fontSize = 12.sp) }
                    )
                }
            }
        }
    }
}

/**
 * Dodawanie własnych fraz.
 *
 * Lista akcji do wyboru pochodzi z [CustomCommands.ASSIGNABLE_TYPES], a nie z
 * całego `ActionType`. Akcje wymagające kilku pól naraz (SMS ma odbiorcę I
 * treść) świadomie się tu nie pojawiają - jedno pole tekstowe by im nie
 * wystarczyło, a pokazanie ich obiecywałoby coś, co nie zadziała.
 */
@Composable
private fun CustomCommandEditor(
    commands: List<CustomCommands.CustomCommand>,
    onAdd: (CustomCommands.CustomCommand) -> Unit,
    onRemove: (Int) -> Unit
) {
    var phrase by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(CustomCommands.ASSIGNABLE_TYPES.first()) }
    var argument by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val needsArgument = CustomCommands.needsArgument(selectedType)
    val canAdd = CustomCommands.isValidPhrase(phrase) &&
        (!needsArgument || argument.isNotBlank())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("➕ Własne komendy", fontWeight = FontWeight.Bold)
            Text(
                "Powiedz to swoimi słowami. Własna fraza ma pierwszeństwo przed " +
                    "wszystkim innym - nie trafia nawet do modelu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            commands.forEachIndexed { index, command ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(command.phrase, fontWeight = FontWeight.Medium)
                        Text(
                            CommandCatalog.byType(command.type)?.name ?: command.type.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Usuń")
                    }
                }
            }

            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it },
                label = { Text("Co powiesz") },
                placeholder = { Text("np. dobranoc") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(CommandCatalog.byType(selectedType)?.name ?: selectedType.name)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    CustomCommands.ASSIGNABLE_TYPES.forEach { type ->
                        DropdownMenuItem(
                            text = {
                                Text(CommandCatalog.byType(type)?.name ?: type.name)
                            },
                            onClick = {
                                selectedType = type
                                argument = ""
                                menuOpen = false
                            }
                        )
                    }
                }
            }

            if (needsArgument) {
                Spacer(Modifier.size(6.dp))
                OutlinedTextField(
                    value = argument,
                    onValueChange = { argument = it },
                    label = { Text("Czego dotyczy") },
                    placeholder = { Text("np. numer, miasto, nazwa aplikacji") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.size(8.dp))
            Button(
                onClick = {
                    onAdd(
                        CustomCommands.CustomCommand(
                            phrase = phrase.trim(),
                            type = selectedType,
                            argument = argument.trim()
                        )
                    )
                    phrase = ""
                    argument = ""
                },
                enabled = canAdd
            ) {
                Text("Dodaj komendę")
            }
        }
    }
}
