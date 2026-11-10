package pl.victor.app.ui.diagnostics

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.victor.app.ble.ConnectionState
import pl.victor.app.ble.NotifyLogEntry
import pl.victor.app.power.BatteryOptimizationHelper
import pl.victor.app.ui.theme.VictorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ekran diagnostyczny okularów.
 *
 * Pokazuje stan połączenia, baterię, IP, liczniki plików i **surowe ramki notify**
 * razem z tym, jak je zrozumiał [pl.victor.app.ble.GlassesProtocol]. Gdy coś nie
 * działa ze sprzętem, to jest pierwsze miejsce do sprawdzenia: widać, czy okulary
 * w ogóle coś przysłały, czy problem jest po naszej stronie.
 *
 * Przełącznik symulacji pozwala przejść całą ścieżkę bez okularów.
 */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VictorTheme {
                DiagnosticsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val connection by viewModel.connectionState.collectAsState()
    val battery by viewModel.batteryLevel.collectAsState()
    val charging by viewModel.isCharging.collectAsState()
    val ip by viewModel.glassesIp.collectAsState()
    val media by viewModel.mediaCount.collectAsState()
    val log by viewModel.notifyLog.collectAsState()
    val lastCommand by viewModel.lastCommand.collectAsState()
    val commandsAnswered = viewModel.commandsAnswered()
    val simulated by viewModel.simulationEnabled.collectAsState()
    val result by viewModel.result.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val fullCheck by viewModel.fullCheck.collectAsState()
    val recordingFileType by viewModel.recordingFileType.collectAsState()
    val recordingProgress by viewModel.recordingProgress.collectAsState()
    val batteryExempt by viewModel.batteryExemptionGranted.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val features by viewModel.features.collectAsState()
    val audioName by viewModel.classicAudioName.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Wyjątek baterii to ekran Ustawień systemowych bez callbacku powrotu -
    // sprawdzamy stan ponownie za każdym razem, gdy ten ekran wraca na pierwszy plan.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBatteryExemption()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostyka okularów") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    // Kopiowanie całego dziennika: ramki ze sprzętu trzeba dać
                    // się WYNIEŚĆ z telefonu. Przepisywanie szesnastkowych
                    // bajtów z ekranu jest tak żmudne, że w praktyce nikt tego
                    // nie robi - a bez surowych ramek nie da się potwierdzić,
                    // co naprawdę wysyłają okulary.
                    IconButton(onClick = {
                        val text = viewModel.notifyLogAsText()
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(
                            context,
                            "Skopiowano dziennik (${log.size} ramek)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Kopiuj dziennik")
                    }
                    IconButton(onClick = { viewModel.clearLog() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Wyczyść dziennik")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    connection = connection,
                    battery = battery,
                    charging = charging,
                    ip = ip,
                    mediaSummary = media?.let {
                        "${it.images} zdjęć, ${it.videos} wideo, ${it.records} nagrań"
                    },
                    lastCommand = lastCommand,
                    commandsAnswered = commandsAnswered,
                    simulated = simulated
                )
            }

            item {
                AboutGlassesCard(info = deviceInfo, features = features, audioName = audioName)
            }

            item {
                BatteryReadinessCard(
                    exempt = batteryExempt,
                    onOpenSettings = { BatteryOptimizationHelper.launchExemptionFlow(context) }
                )
            }

            item {
                SectionCard("Sprawdź całą ścieżkę") {
                    Text(
                        "Od wybudzenia do odpowiedzi jest siedem ogniw: BLE, zgoda " +
                            "na mikrofon, rozpoznawanie mowy, profil audio, syntezator, " +
                            "klucz do modelu i aparat. Awaria KAŻDEGO wygląda tak samo - " +
                            "jak cisza. Ten przycisk sprawdza je po kolei i mówi, które " +
                            "nie działa.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::runFullCheck,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (busy) "Sprawdzam..." else "▶ Sprawdź wszystko")
                    }
                    fullCheck?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                SimulationCard(
                    enabled = simulated,
                    onToggle = viewModel::setSimulation,
                    onConnect = viewModel::connectSimulated,
                    onDisconnect = viewModel::disconnect,
                    onButton = viewModel::injectButtonPress,
                    onPhotoButton = viewModel::injectPhotoButton,
                    onLowBattery = viewModel::injectLowBattery,
                    onLowMemory = viewModel::injectLowMemory,
                    onWakeWord = viewModel::injectWakeWord,
                    onInterrupt = viewModel::injectInterrupt,
                    onVolume = viewModel::injectVolume
                )
            }

            item {
                SectionCard("Dźwięk przez okulary") {
                    Text(
                        "BLE i dźwięk to DWA OSOBNE połączenia. Okulary mogą być " +
                            "połączone (zdjęcia i przycisk działają), a mimo to milczeć - " +
                            "bo część audio trzeba sparować osobno, w ustawieniach " +
                            "Bluetooth telefonu.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = viewModel::testGlassesAudio) {
                        Text("🔊 Powiedz coś przez okulary")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Mikrofon okularów ma jeszcze DRUGĄ drogę: aplikacja " +
                            "producenta bierze dźwięk po BLE jako strumień Opus, " +
                            "a nie przez zestaw słuchawkowy. Ten test mierzy, czy " +
                            "okulary nim nadają - bez pomiaru \"nie nadaje\" i " +
                            "\"nadaje, ale nie umiemy odczytać\" wyglądają tak samo.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = viewModel::testGlassesMicStream) {
                        Text("🎙 Zmierz strumień z mikrofonu (15 s)")
                    }
                    // Wynik MUSI być tutaj. Wcześniej pokazywał go wyłącznie
                    // sąsiedni panel "Testy funkcji", więc przyciski z tej karty
                    // wyglądały, jakby nic nie robiły - komunikat powstawał, tylko
                    // nie miał gdzie się wyświetlić.
                    result?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                SectionCard("Testy funkcji") {
                    Text(
                        "Te same przyciski działają na symulatorze i na prawdziwych okularach - " +
                            "po podłączeniu sprzętu przejdź je po kolei.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    if (busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = viewModel::testBattery) { Text("Bateria") }
                        FilledTonalButton(onClick = viewModel::testMediaCount) { Text("Liczba plików") }
                        FilledTonalButton(
                            onClick = viewModel::testPhoto,
                            enabled = !busy
                        ) { Text("Zdjęcie") }
                        FilledTonalButton(onClick = viewModel::testVideo) { Text("Start wideo") }
                        FilledTonalButton(onClick = viewModel::stopVideo) { Text("Stop wideo") }
                        FilledTonalButton(onClick = viewModel::testAudio) { Text("Start audio") }
                        FilledTonalButton(onClick = viewModel::stopAudio) { Text("Stop audio") }
                        FilledTonalButton(onClick = viewModel::testTransferMode) { Text("Tryb transferu") }
                        FilledTonalButton(
                            onClick = viewModel::testFileList,
                            enabled = !busy
                        ) { Text("Lista plików") }
                        FilledTonalButton(
                            onClick = viewModel::testDownloadPhoto,
                            enabled = !busy
                        ) { Text("Pobierz zdjęcie") }
                        OutlinedButton(onClick = viewModel::resetP2p) { Text("Reset P2P") }
                    }
                    result?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                RecordingsCard(
                    fileType = recordingFileType,
                    onFileTypeChange = viewModel::setRecordingFileType,
                    progress = recordingProgress,
                    busy = busy,
                    onList = viewModel::testListRecordings,
                    onDownload = viewModel::testDownloadRecording
                )
            }

            item {
                Text(
                    "Dziennik ramek notify (${log.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (log.isEmpty()) {
                item {
                    Text(
                        "Pusto. Okulary nie przysłały jeszcze żadnej ramki - " +
                            "albo nie są połączone, albo nasłuch nie wystartował.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                items(log) { entry -> NotifyRow(entry) }
            }
        }
    }
}

@Composable
private fun StatusCard(
    connection: ConnectionState,
    battery: Int?,
    charging: Boolean,
    ip: String?,
    mediaSummary: String?,
    lastCommand: String?,
    commandsAnswered: Boolean,
    simulated: Boolean
) {
    SectionCard(if (simulated) "Stan (SYMULACJA)" else "Stan") {
        StatusRow("Połączenie", connection.polish())
        StatusRow(
            "Bateria",
            battery?.let { "$it%" + if (charging) " (ładowanie)" else "" } ?: "nieznana"
        )
        StatusRow("IP okularów", ip ?: "brak (tryb transferu wyłączony)")
        StatusRow("Pliki", mediaSummary ?: "nie sprawdzono")
        StatusRow("Ostatnia komenda", lastCommand ?: "żadna")
        // Najważniejszy wiersz przy szukaniu awarii: okulary mogą przysyłać
        // zdarzenia (przycisk, stan) i JEDNOCZEŚNIE nie odpowiadać na komendy.
        // Bez tego rozróżnienia obie awarie wyglądają tak samo - cisza.
        StatusRow(
            "Odpowiadają na komendy",
            if (commandsAnswered) "tak" else "jeszcze nie"
        )
    }
}

@Composable
private fun AboutGlassesCard(
    info: pl.victor.app.ble.GlassesDeviceInfo?,
    features: pl.victor.app.ble.GlassesFeatures?,
    audioName: String?
) {
    SectionCard("O okularach") {
        if (info == null && features == null && audioName == null) {
            Text(
                "Okulary jeszcze nie odpowiedziały.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            StatusRow("Firmware", info?.firmware ?: "brak danych")
            StatusRow("Sprzęt", info?.hardware ?: "brak danych")
            StatusRow("Wi-Fi firmware", info?.wifiFirmware ?: "brak danych")
            StatusRow("Wi-Fi sprzęt", info?.wifiHardware ?: "brak danych")
            StatusRow("Model", features?.model?.toString() ?: "brak danych")
            StatusRow("Tłumaczenie", features?.let { takNie(it.translation) } ?: "brak danych")
            StatusRow("Wykrywanie założenia", features?.let { takNie(it.wearCheck) } ?: "brak danych")
            StatusRow("Sterowanie głośnością", features?.let { takNie(it.volumeControl) } ?: "brak danych")
            StatusRow("Część audio", audioName ?: "brak danych")
        }
    }
}

private fun takNie(value: Boolean): String = if (value) "tak" else "nie"

@Composable
private fun BatteryReadinessCard(
    exempt: Boolean,
    onOpenSettings: () -> Unit
) {
    SectionCard(if (exempt) "Bateria: gotowe" else "Bateria: RYZYKO") {
        Text(
            if (exempt) {
                "Optymalizacja baterii jest wyłączona dla V.I.C.T.O.R. Wake word i połączenie " +
                    "z okularami mogą działać w tle bez przerwy."
            } else {
                "Android może usypiać V.I.C.T.O.R. kilka minut po zgaszeniu ekranu. Jeśli wake " +
                    "word albo połączenie z okularami \"samo się rozłącza\" po jakimś czasie - " +
                    "to jest najczęstsza przyczyna, nie błąd w kodzie."
            },
            style = MaterialTheme.typography.bodySmall
        )
        if (!exempt) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenSettings) { Text("🔋 Wyłącz optymalizację baterii") }
        }
    }
}

@Composable
private fun RecordingsCard(
    fileType: Int,
    onFileTypeChange: (Int) -> Unit,
    progress: Float?,
    busy: Boolean,
    onList: () -> Unit,
    onDownload: () -> Unit
) {
    SectionCard("Nagrania głosowe przez BLE") {
        Text(
            "Osobny kanał vendor SDK - działa bez Wi-Fi Direct, więc to droga " +
                "awaryjna, gdy grupa P2P nie chce się podnieść.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Producent nie podaje numeru typu pliku, a SDK startuje z zerem. " +
                "Jeśli lista wraca pusta mimo nagrań w pamięci, przejdź kolejne typy.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Typ pliku: $fileType", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { onFileTypeChange(fileType - 1) },
                enabled = fileType > 0,
                modifier = Modifier.padding(start = 12.dp)
            ) { Text("−") }
            OutlinedButton(
                onClick = { onFileTypeChange(fileType + 1) },
                enabled = fileType < 7,
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("+") }
        }

        Spacer(Modifier.height(8.dp))
        progress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        FlowRowCompat {
            FilledTonalButton(onClick = onList, enabled = !busy) { Text("Lista nagrań") }
            FilledTonalButton(onClick = onDownload, enabled = !busy) { Text("Pobierz nagranie") }
        }
    }
}

@Composable
private fun SimulationCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onButton: () -> Unit,
    onPhotoButton: () -> Unit,
    onLowBattery: () -> Unit,
    onLowMemory: () -> Unit,
    onWakeWord: () -> Unit,
    onInterrupt: () -> Unit,
    onVolume: () -> Unit
) {
    SectionCard("Symulowane okulary") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Tryb symulacji", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Udawany jest tylko transport BLE - dekodowanie ramek, stan i cała " +
                        "warstwa AI działają na prawdziwym kodzie.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        if (enabled) {
            Spacer(Modifier.height(12.dp))
            FlowRowCompat {
                Button(onClick = onConnect) { Text("Połącz") }
                OutlinedButton(onClick = onDisconnect) { Text("Rozłącz") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Wstrzyknij zdarzenie:", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            FlowRowCompat {
                OutlinedButton(onClick = onButton) { Text("Przycisk AI") }
                OutlinedButton(onClick = onPhotoButton) { Text("Przycisk zdjęcia") }
                OutlinedButton(onClick = onLowBattery) { Text("Bateria 9%") }
                OutlinedButton(onClick = onLowMemory) { Text("Brak pamięci") }
                // Ramki odczytane z aplikacji producenta - bez nich nie dało się
                // przetestować wybudzenia inaczej niż z okularami na głowie.
                OutlinedButton(onClick = onWakeWord) { Text("Wybudzenie AI") }
                OutlinedButton(onClick = onInterrupt) { Text("Cicho (zauszniki)") }
                OutlinedButton(onClick = onVolume) { Text("Głośność") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NotifyRow(entry: NotifyLogEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(entry.meaning, style = MaterialTheme.typography.bodyMedium)
            Text(
                TIME_FORMAT.format(Date(entry.timestampMs)),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            entry.hex,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

private fun ConnectionState.polish(): String = when (this) {
    ConnectionState.DISCONNECTED -> "rozłączone"
    ConnectionState.SCANNING -> "skanowanie"
    ConnectionState.CONNECTING -> "łączenie"
    ConnectionState.CONNECTED -> "połączone (trwa wykrywanie usług)"
    ConnectionState.READY -> "gotowe"
    ConnectionState.ERROR -> "błąd"
}
