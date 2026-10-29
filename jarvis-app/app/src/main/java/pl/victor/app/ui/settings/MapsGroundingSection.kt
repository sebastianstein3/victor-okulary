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

/** Przełącznik Map Google dla pytań o okolicę - patrz [pl.victor.app.ai.GeminiProvider.mapsLocation]. */
@Composable
internal fun MapsGroundingSection() {
    val settings = pl.victor.app.VictorApplication.get().settings
    var enabled by remember { mutableStateOf(settings.isMapsGroundingEnabled()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Mapy Google przy pytaniach o okolicę", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Gdy pytasz „gdzie tu zjem?”, model dostaje Mapy Google: prawdziwe miejsca, " +
                        "oceny i godziny otwarcia, z Twoją pozycją. Działa z modelami Gemini 3 " +
                        "i nowszymi; po przekroczeniu darmowego limitu Google nalicza opłatę.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                settings.setMapsGroundingEnabled(it)
            })
        }
    }
}
