package pl.victor.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.victor.app.ble.GlassesVolume

/** Głośność mowy w okularach - patrz [pl.victor.app.ble.GlassesVolume] i komendy głosowe głośności. */
@Composable
internal fun GlassesVolumeSection() {
    val manager = pl.victor.app.VictorApplication.get().glassesManager
    val volume by manager.volume.collectAsState()
    // Odczyt stanu połączenia wymusza odświeżenie sekcji przy rozłączeniu.
    val connection by manager.connectionState.collectAsState()
    val current: GlassesVolume? = volume
    // Odczyt funkcji odświeża sekcję, gdy okulary odpowiedzą po połączeniu.
    val features by manager.features.collectAsState()
    val supported = features?.volumeControl == true
    val usable = current != null && supported &&
        connection == pl.victor.app.ble.ConnectionState.READY && manager.isConnected()

    // Zewnętrzna zmiana (przyciski na zauszniku, komenda głosowa) przestawia suwak.
    val reported = current?.percent() ?: 50
    var slider by remember(reported) { mutableStateOf(reported.coerceIn(10, 100).toFloat()) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Głośność mowy w okularach",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${slider.toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = slider,
            onValueChange = { slider = it },
            valueRange = 10f..100f,
            onValueChangeFinished = {
                val target = current?.withSpeechPercent(slider.toInt())
                if (target != null) manager.setGlassesVolume(target)
            },
            enabled = usable,
            modifier = Modifier.fillMaxWidth()
        )
        if (current != null) {
            Text(
                "Muzyka ${current.music.current}/${current.music.max} · " +
                    "Rozmowa ${current.call.current}/${current.call.max} · " +
                    "System ${current.system.current}/${current.system.max}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (!usable) {
            Text(
                if (current != null && !supported) {
                    "Okulary nie zgłosiły obsługi sterowania głośnością z telefonu."
                } else {
                    "Okulary jeszcze nie podały głośności - połącz je."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            "Działa też głosem: „głośniej”, „ciszej”, „głośność na 70 procent”.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
