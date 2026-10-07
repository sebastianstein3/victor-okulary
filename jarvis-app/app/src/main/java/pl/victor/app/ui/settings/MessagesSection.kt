package pl.victor.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pl.victor.app.messages.MessagingApps

/**
 * Ustawienia czytania wiadomości i odpowiadania głosem.
 *
 * Osobny plik, a nie kolejna sekcja w SettingsActivity: ten ma już ponad
 * trzy tysiące linii.
 */
@Composable
internal fun MessagesSection() {
    val context = LocalContext.current
    val settings = pl.victor.app.VictorApplication.get().settings
    var enabled by remember { mutableStateOf(settings.isMessageReadingEnabled()) }
    var content by remember { mutableStateOf(settings.isMessageContentRead()) }
    var confirm by remember { mutableStateOf(settings.isMessageReplyConfirmed()) }
    var apps by remember { mutableStateOf(settings.getMessageApps()) }

    fun maDostęp(): Boolean = androidx.core.app.NotificationManagerCompat
        .getEnabledListenerPackages(context).contains(context.packageName)

    // Zgodę daje się w ustawieniach systemu - po powrocie stan ma się odświeżyć.
    var dostęp by remember { mutableStateOf(maDostęp()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) dostęp = maDostęp()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("💬 Wiadomości w okularach", fontWeight = FontWeight.Bold)
                    Text(
                        "Czyta wiadomości z WhatsAppa, SMS-ów i Messengera, gdy masz okulary. " +
                            "Odpowiadasz głosem: \"odpowiedz jej, że będę za 10 minut\". Odpowiedź " +
                            "idzie w tej samej rozmowie.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        settings.setMessageReadingEnabled(it)
                    }
                )
            }
            if (!enabled) return@Column

            Spacer(Modifier.size(8.dp))
            if (!dostęp) {
                Text(
                    "Potrzebna zgoda \"Dostęp do powiadomień\" - bez niej telefon nie pokaże " +
                        "aplikacji żadnej wiadomości. Na liście wybierz V.I.C.T.O.R. i włącz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Daj dostęp do powiadomień") }
            } else {
                Text("✅ Dostęp do powiadomień jest", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.size(8.dp))
            Toggle("Czytaj treść (wyłączone: tylko \"SMS od Ani\")", content) {
                content = it
                settings.setMessageContentRead(it)
            }
            Toggle("Pytaj \"Wysłać?\" przed odpowiedzią", confirm) {
                confirm = it
                settings.setMessageReplyConfirmed(it)
            }

            Spacer(Modifier.size(8.dp))
            Text("Z których aplikacji:", style = MaterialTheme.typography.labelMedium)
            MessagingApps.ZNANE.distinctBy { it.pakiet }.forEach { app ->
                val nazwa = if (app.nazwa == "SMS") "SMS (${app.pakiet.substringAfterLast('.')})" else app.nazwa
                Toggle(nazwa, app.pakiet in apps) { włącz ->
                    apps = if (włącz) apps + app.pakiet else apps - app.pakiet
                    settings.setMessageApps(apps)
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
