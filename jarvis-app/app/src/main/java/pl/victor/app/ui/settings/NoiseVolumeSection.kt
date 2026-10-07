package pl.victor.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Przełącznik głośności dopasowanej do hałasu - patrz [pl.victor.app.audio.AmbientNoise]. */
@Composable
internal fun NoiseVolumeSection() {
    val app = pl.victor.app.VictorApplication.get()
    var enabled by remember { mutableStateOf(app.settings.isNoiseAdaptiveVolume()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Głośniej w hałasie", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Po pytaniu telefon na pół sekundy mierzy gwar wokół. Na ulicy czy w " +
                        "tramwaju odpowiedź będzie głośniejsza, potem głośność wraca. " +
                        "W cichym miejscu nic się nie zmienia.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                app.settings.setNoiseAdaptiveVolume(it)
                app.audio.noiseAdaptiveVolume = it
            })
        }
    }
}
