package pl.victor.app.ui.pairing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.victor.app.ble.DiscoveredDevice
import pl.victor.app.ui.theme.VictorTheme

/**
 * Ekran parowania z okularami HeyCyan.
 * - Skan BLE
 * - Lista wykrytych urządzeń
 * - Klik = połącz
 * - Pokazuje status połączenia
 */
class PairingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VictorTheme {
                PairingScreen(onBack = { finish() })
            }
        }
    }
}

/** Uprawnienia do samego skanu BLE - zależne od wersji Androida. */
private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

/**
 * Uprawnienia do Wi-Fi Direct (pełne pliki) - dopraszane przy okazji skanu BLE,
 * żeby nie przerywać użytkownikowi drugim dialogiem później, ale ich brak NIE
 * blokuje samego wyszukiwania okularów (patrz [hasBlePermissions]).
 */
private fun wifiDirectPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasBlePermissions(context: Context): Boolean =
    blePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onBack: () -> Unit,
    viewModel: PairingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasBlePermissions(context)) {
            viewModel.startScan()
        } else {
            viewModel.onPermissionsDenied()
        }
    }

    // Skanuj od razu po wejściu na ekran - wcześniej trzeba było wiedzieć, że
    // trzeba wcisnąć malutką ikonę odświeżania, a dialog systemowy o uprawnienia
    // w ogóle nie miał podpiętego dalszego działania po zgodzie użytkownika.
    // To był realny powód, dla którego panel "nic nie znajdował" przy pierwszym
    // uruchomieniu na nowym telefonie.
    LaunchedEffect(Unit) {
        if (hasBlePermissions(context)) {
            viewModel.startScan()
        } else {
            permissionLauncher.launch(blePermissions() + wifiDirectPermissions())
        }
    }

    // Czy telefon widzi okulary także jako ZESTAW AUDIO. Sprawdzane po każdej
    // zmianie stanu połączenia, bo część klasyczna zgłasza się z opóźnieniem
    // po openBT() - a użytkownik ma zobaczyć podpowiedź dokładnie wtedy, gdy
    // jest potrzebna, i przestać ją widzieć, gdy sparuje.
    var audioReady by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        audioReady = pl.victor.app.audio.BluetoothAudioRouter
            .getInstance(context)
            .hasConnectedBluetoothAudioDevice()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Połącz z okularami") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (hasBlePermissions(context)) {
                            viewModel.startScan()
                        } else {
                            permissionLauncher.launch(blePermissions() + wifiDirectPermissions())
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Skanuj ponownie")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status card
            StatusCard(
                state = state,
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                    )
                },
                onDone = onBack,
                audioReady = audioReady,
                onOpenBluetoothSettings = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            )

            HorizontalDivider()

            // Info text
            Text(
                text = "1. Włącz okulary (przytrzymaj przycisk 3 sekundy)\n" +
                        "2. Poczekaj aż dioda LED zacznie migać\n" +
                        "3. Wybierz urządzenie z listy poniżej",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Lista urządzeń
            if (devices.isEmpty() && state == PairingState.SCANNING) {
                Text("Szukam urządzeń...", style = MaterialTheme.typography.bodyMedium)
            } else if (devices.isEmpty()) {
                Text(
                    "Brak urządzeń. Wciśnij przycisk odświeżania.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices, key = { it.address }) { device ->
                        DeviceCard(
                            device = device,
                            onClick = { viewModel.connect(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: PairingState,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
    audioReady: Boolean,
    onOpenBluetoothSettings: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                PairingState.IDLE -> {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(Modifier.size(12.dp))
                    Text("Gotowy do skanowania")
                }
                PairingState.SCANNING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("Skanowanie Bluetooth...", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                PairingState.CONNECTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Łączenie...")
                }
                PairingState.CONNECTED -> {
                    Icon(
                        Icons.Default.BluetoothConnected,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(
                            "Połączono!",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Okulary gotowe do użycia",
                            style = MaterialTheme.typography.bodySmall
                        )

                        // NAJCZĘSTSZA PUŁAPKA. BLE (zdjęcia, przycisk, sterowanie)
                        // i dźwięk to DWA OSOBNE połączenia. Po sparowaniu BLE
                        // okulary są "połączone", ale milczą - bo część audio
                        // trzeba sparować raz, w ustawieniach Bluetooth telefonu.
                        // Bez tej podpowiedzi wygląda to na awarię aplikacji.
                        if (!audioReady) {
                            Spacer(Modifier.height(12.dp))
                            Card(
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "Jeszcze jeden krok: dźwięk",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        "Żeby V.I.C.T.O.R. mówił i słyszał przez okulary, " +
                                            "sparuj je jeszcze raz - jako zestaw słuchawkowy " +
                                            "w ustawieniach Bluetooth telefonu. To osobne " +
                                            "połączenie niż to powyżej.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = onOpenBluetoothSettings) {
                                        Text("Otwórz ustawienia Bluetooth")
                                    }
                                }
                            }
                        }

                        // Po udanym połączeniu ekran nie dawał żadnego wyjścia poza
                        // przyciskiem "wstecz" - użytkownik zostawał na liście urządzeń
                        // i nie wiedział, czy ma coś jeszcze zrobić.
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onDone) {
                            Text("Gotowe - wróć do aplikacji")
                        }
                    }
                }
                PairingState.ERROR -> {
                    Icon(
                        Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Błąd połączenia", color = MaterialTheme.colorScheme.error)
                }
                PairingState.PERMISSIONS_DENIED -> {
                    Icon(
                        Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(
                            "Brak uprawnień do Bluetooth",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Bez tego telefon nie może wyszukiwać okularów.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onOpenSettings) {
                            Text("Otwórz ustawienia aplikacji")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Nazwa bywa niedostępna zanim urządzenie ją rozgłosi -
                    // wtedy pokazujemy adres MAC.
                    device.name ?: device.address,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    // Skan pokazuje WSZYSTKO, co nadaje w pobliżu - telewizor,
                    // słuchawki, cudzy telefon. Zgłoszone jako „znalazł okulary
                    // (LG), tylko że to nie są okulary, których używam".
                    // Nie ukrywamy niczego (nie wiemy, jak przedstawia się
                    // każdy egzemplarz), ale mówimy wprost, które urządzenie
                    // już raz działało.
                    if (device.known) {
                        "${device.address} - łączyłeś się już z tym urządzeniem"
                    } else {
                        device.address
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.known) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            TextButton(onClick = onClick) {
                Text("Połącz")
            }
        }
    }
}
