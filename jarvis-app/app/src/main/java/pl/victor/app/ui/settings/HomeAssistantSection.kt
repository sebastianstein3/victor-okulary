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
import pl.victor.app.home.HaAdres
import pl.victor.app.home.HomeAssistantClient

/**
 * Sterowanie domem przez Home Assistant - adres, token, sprawdzenie.
 *
 * Osobny plik, bo SettingsActivity ma już kilka tysięcy linii.
 */
@Composable
internal fun HomeAssistantSection() {
    val app = pl.victor.app.VictorApplication.get()
    val settings = app.settings
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(settings.isHomeControlEnabled()) }
    var url by remember { mutableStateOf(settings.getHomeAssistantUrl()) }
    // Pole tokenu startuje puste - zapisanego tokenu nie pokazujemy wcale.
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(settings.getHomeAssistantToken() != null) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val client = remember {
        HomeAssistantClient(adres = { settings.getHomeAssistantUrl() }, token = { settings.getHomeAssistantToken() })
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Sterowanie domem (Home Assistant)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "„Włącz światło w salonie”, „jaka jest temperatura w sypialni”, „dom, zamknij " +
                        "rolety”. Polecenia idą do Twojego serwera Home Assistant - bez kosztów. " +
                        "Otwarcie zamka, bramy i wyłączenie alarmu wymaga potwierdzenia głosem.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                settings.setHomeControlEnabled(it)
            })
        }

        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                settings.setHomeAssistantUrl(it)
                status = null
            },
            label = { Text("Adres serwera") },
            placeholder = { Text("http://homeassistant.local:8123") },
            singleLine = true,
            isError = url.isNotBlank() && HaAdres.sprawdź(url) is HaAdres.Wynik.Zły,
            supportingText = {
                val w = if (url.isBlank()) null else HaAdres.sprawdź(url)
                Text(
                    (w as? HaAdres.Wynik.Zły)?.powód
                        ?: "W domu: adres lokalny (http). Spoza domu: https, np. Nabu Casa."
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                if (it.isNotBlank()) {
                    settings.setHomeAssistantToken(it)
                    hasToken = true
                }
                status = null
            },
            label = { Text(if (hasToken) "Token zapisany - wklej nowy, by zmienić" else "Token długoterminowy") },
            placeholder = { Text("Profil w HA → Bezpieczeństwo → Tokeny") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    status = "Sprawdzam…"
                    scope.launch {
                        val wynik = client.sprawdźPołączenie()
                        // Pokoje pobieramy przy okazji - pomagają rozpoznać
                        // "w pokoju Zosi" jako polecenie dla domu.
                        val pokoje = if (wynik.startsWith("Połączono")) client.obszary() else null
                        if (pokoje != null) settings.setHomeAssistantAreas(pokoje)
                        runCatching {
                            app.diag.event(
                                pl.victor.app.diagnostics.DiagFormat.Phase.AKCJA,
                                "dom: sprawdzenie połączenia",
                                mapOf("wynik" to wynik, "pokoi" to pokoje?.size)
                            )
                        }
                        status = wynik + (pokoje?.takeIf { it.isNotEmpty() }?.let { " Pokoje: ${it.joinToString(", ")}." } ?: "")
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
