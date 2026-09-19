package pl.victor.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.victor.app.VictorApplication
import pl.victor.app.ble.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Panel okularów na ekranie głównym.
 *
 * ## Po co on jest
 * Zgłoszono to jednym zdaniem: "okulary same w sobie niezbyt wpływają na
 * aplikację i odwrotnie". I tak było - jedynym śladem okularów w całej
 * aplikacji była ikonka Bluetootha w pasku. Nie dało się zobaczyć ani tego, że
 * są połączone, ani ile mają baterii, ani co robią ich gesty, ani czy
 * cokolwiek z nich w ogóle dochodzi.
 *
 * Ten panel odpowiada na wszystkie cztery pytania naraz, a przycisk „Zrób
 * zdjęcie" domyka to w drugą stronę: aplikacja wywołuje okulary, nie tylko je
 * obserwuje.
 */
@Composable
fun GlassesPanel(
    onTakePhoto: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glasses = VictorApplication.get().glassesManager
    val state by glasses.connectionState.collectAsState()
    val battery by glasses.batteryLevel.collectAsState()
    val charging by glasses.isCharging.collectAsState()
    val notifyLog by glasses.notifyLog.collectAsState()
    val connected = state == ConnectionState.READY

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (connected) "🕶️" else "⚫", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Okulary",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        describeState(state, battery, charging),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (connected) {
                Spacer(Modifier.size(10.dp))
                // Legenda gestów. Bez niej użytkownik nie ma skąd wiedzieć, że
                // okulary potrafią cokolwiek poza noszeniem - a to one są tu
                // głównym urządzeniem wejściowym.
                GESTURES.forEach { (gesture, meaning) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            gesture,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(0.42f)
                        )
                        Text(
                            meaning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.58f)
                        )
                    }
                }

                // Ostatnie zdarzenie Z OKULARÓW. To jest dowód, że urządzenie
                // żyje i że aplikacja je słyszy - przy diagnozowaniu "nic się
                // nie dzieje" pierwsza rzecz, jaką trzeba wiedzieć.
                notifyLog.firstOrNull()?.let { last ->
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Ostatnio z okularów: ${last.meaning} (${formatTime(last.timestampMs)})",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.size(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (connected) {
                    Button(onClick = onTakePhoto, modifier = Modifier.weight(1f)) {
                        Text("📷 Zrób zdjęcie", fontSize = 12.sp)
                    }
                } else {
                    Button(onClick = onOpenPairing, modifier = Modifier.weight(1f)) {
                        Text("🔗 Połącz", fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = onOpenGallery, modifier = Modifier.weight(1f)) {
                    Text("🖼️ Galeria", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.weight(1f)) {
                    Text("🩺 Sprawdź", fontSize = 12.sp)
                }
            }
        }
    }
}

/** Jedno zdanie o stanie okularów - z baterią, gdy ją znamy. */
private fun describeState(state: ConnectionState, battery: Int?, charging: Boolean): String {
    val base = when (state) {
        ConnectionState.READY -> "Połączone i gotowe"
        ConnectionState.CONNECTED -> "Połączone, kończę konfigurację"
        ConnectionState.CONNECTING -> "Łączę…"
        ConnectionState.SCANNING -> "Szukam okularów…"
        ConnectionState.ERROR -> "Błąd połączenia"
        ConnectionState.DISCONNECTED -> "Niepołączone"
    }
    if (state != ConnectionState.READY || battery == null) return base
    val power = if (charging) "ładują się, $battery%" else "bateria $battery%"
    return "$base · $power"
}

private fun formatTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

/**
 * Mapa gestów - MUSI zgadzać się z [pl.victor.app.ble.ButtonActionDetector]
 * i z obsługą w [pl.victor.app.AIOrchestrator.handleButtonAction].
 */
private val GESTURES = listOf(
    "1× klik" to "zapytaj głosem",
    "2× klik" to "zdjęcie i opis widoku",
    // Przy trzech i czterech kliknięciach podajemy komendę głosową, bo z terenu
    // zgłoszono, że same gesty nie dochodzą ("3 i 4 kliknięcia nie działają"),
    // a jedno i dwa działają. Uczenie gestu, który bywa gubiony, bez podania
    // drogi pewnej, zostawia człowieka z funkcją, której nie umie uruchomić.
    "3× klik lub „przetłumacz to”" to "przeczytaj i przetłumacz napis",
    "4× klik lub „nowy temat”" to "nowa rozmowa"
)
