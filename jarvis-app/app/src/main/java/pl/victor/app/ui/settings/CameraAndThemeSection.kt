package pl.victor.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.victor.app.data.SettingsRepository

/**
 * Tryb bez kamery i motyw aplikacji.
 *
 * Oba ustawienia działają od razu, bez restartu: ekran główny i lista komend
 * słuchają [SettingsRepository.cameraFreeFlow], a motyw
 * [SettingsRepository.themeModeFlow] (czyta go [pl.victor.app.ui.theme.VictorTheme]).
 * Dlatego stan bierzemy z przepływów, a nie z jednorazowego odczytu - przełącznik
 * zmieniony z innego miejsca (np. głosem) od razu się tu zgadza.
 */
@Composable
internal fun CameraAndThemeSection() {
    val settings = pl.victor.app.VictorApplication.get().settings
    val cameraFree by settings.cameraFreeFlow.collectAsState()
    val themeMode by settings.themeModeFlow.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Tryb bez kamery", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Asystent głosowy, rozmowa i telefon. Aplikacja nie robi zdjęć ani nie " +
                        "włącza podglądu; przycisk aparatu na okularach dalej robi zdjęcie do " +
                        "ich pamięci, ale V.I.C.T.O.R. go nie pobiera.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = cameraFree, onCheckedChange = { settings.setCameraFree(it) })
        }

        Spacer(Modifier.height(16.dp))
        Text("Motyw", style = MaterialTheme.typography.titleSmall)
        // FilterChip, nie SegmentedButton: FilterChip jest już używany w
        // aplikacji (notatki, galeria), więc na pewno jest w tej wersji Material3.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            THEME_OPTIONS.forEach { (mode, label) ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { settings.setThemeMode(mode) },
                    label = { Text(label) }
                )
            }
        }
    }
}

/** Opcje motywu w kolejności na ekranie. */
private val THEME_OPTIONS = listOf(
    SettingsRepository.THEME_SYSTEM to "Jak telefon",
    SettingsRepository.THEME_LIGHT to "Jasny",
    SettingsRepository.THEME_DARK to "Ciemny"
)
