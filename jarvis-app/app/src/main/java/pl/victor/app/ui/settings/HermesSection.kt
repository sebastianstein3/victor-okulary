package pl.victor.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.victor.app.hermes.HermesAdres
import pl.victor.app.hermes.HermesClient

/**
 * Hermes Agent na własnym komputerze jako "mózg" do zadań - adres, klucz,
 * sprawdzenie połączenia.
 *
 * Osobny plik, bo SettingsActivity ma już kilka tysięcy linii.
 */
@Composable
internal fun HermesSection() {
    val app = pl.victor.app.VictorApplication.get()
    val settings = app.settings
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(settings.isHermesPrefOn()) }
    var url by remember { mutableStateOf(settings.getHermesUrl()) }
    // Pole klucza startuje puste - zapisanego klucza nie pokazujemy wcale.
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(settings.getHermesKey() != null) }
    var session by remember { mutableStateOf(settings.getHermesSessionKey()) }
    var auto by remember { mutableStateOf(settings.isHermesAutoRoute()) }
    var filesUrl by remember { mutableStateOf(settings.getHermesFilesUrl()) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val client = remember {
        HermesClient(
            adres = { settings.getHermesUrl() },
            klucz = { settings.getHermesKey() },
            kluczSesji = { settings.getHermesSessionKey() }
        )
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Hermes Agent (komputer)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "„Hermes, znajdź w folderze Dokumenty fakturę z października”, „zapytaj Hermesa…”, " +
                        "„na komputerze zrób…”. Zadania wykonuje agent na Twoim komputerze (pliki, " +
                        "narzędzia, własna pamięć), a Victor czyta odpowiedź na głos. Koszt zależy od " +
                        "modelu skonfigurowanego w Hermesie - przy „Tylko darmowe funkcje” Hermes jest wyłączony.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                settings.setHermesEnabled(it)
            })
        }

        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                settings.setHermesUrl(it)
                status = null
            },
            label = { Text("Adres bramy Hermesa") },
            placeholder = { Text("http://mac-mini.local:8642") },
            singleLine = true,
            isError = url.isNotBlank() && HermesAdres.sprawdź(url) is HermesAdres.Wynik.Zły,
            supportingText = {
                val w = if (url.isBlank()) null else HermesAdres.sprawdź(url)
                Text(
                    (w as? HermesAdres.Wynik.Zły)?.powód
                        ?: "Sieć domowa albo Tailscale (http). Spoza nich: https. Brama: hermes gateway."
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                if (it.isNotBlank()) {
                    settings.setHermesKey(it)
                    hasKey = true
                }
                status = null
            },
            label = { Text(if (hasKey) "Klucz zapisany - wklej nowy, by zmienić" else "Klucz API (API_SERVER_KEY)") },
            placeholder = { Text("Z pliku ~/.hermes/.env na komputerze") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )

        OutlinedTextField(
            value = session,
            onValueChange = {
                session = it
                settings.setHermesSessionKey(it)
            },
            label = { Text("Identyfikator kanału (pamięć Hermesa)") },
            singleLine = true,
            supportingText = { Text("Ten sam identyfikator = ta sama długa pamięć. Zwykle zostaw domyślny.") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )

        OutlinedTextField(
            value = filesUrl,
            onValueChange = {
                filesUrl = it
                settings.setHermesFilesUrl(it)
            },
            label = { Text("Adres mostu plików (opcjonalnie)") },
            placeholder = { Text(pl.victor.app.hermes.HermesPlikiAdres.pochodny(url) ?: "http://mac-mini.local:8643") },
            singleLine = true,
            isError = filesUrl.isNotBlank() && HermesAdres.sprawdź(filesUrl) is HermesAdres.Wynik.Zły,
            supportingText = {
                Text(
                    "Do otwierania w Victorze obrazów i plików od Hermesa (skrypt tools/hermes-pliki na komputerze). " +
                        "Puste = ten sam komputer, port 8643."
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Także zadania na plikach bez wołania Hermesa", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "np. „znajdź plik z fakturą”. Wolniejsze niż zwykły model, więc domyślnie wyłączone.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = auto, onCheckedChange = {
                auto = it
                settings.setHermesAutoRoute(it)
            })
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    status = "Sprawdzam…"
                    scope.launch {
                        val wynik = client.sprawdźPołączenie()
                        runCatching {
                            app.diag.event(
                                pl.victor.app.diagnostics.DiagFormat.Phase.AKCJA,
                                "hermes: sprawdzenie połączenia",
                                mapOf("wynik" to wynik)
                            )
                        }
                        status = wynik
                        checking = false
                    }
                }
            ) { Text("Sprawdź połączenie") }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
