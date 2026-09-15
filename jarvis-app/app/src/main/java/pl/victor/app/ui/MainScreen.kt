package pl.victor.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.victor.app.OrchestratorState
import pl.victor.app.ui.components.ActionConfirmationDialog
import pl.victor.app.ui.components.CapabilitiesPanel
import pl.victor.app.ui.components.GlassesPanel
import pl.victor.app.ui.components.ModelBadge
import pl.victor.app.ui.components.NewModelsBanner
import pl.victor.app.ui.history.HistoryActivity
import pl.victor.app.ui.pairing.PairingActivity
import pl.victor.app.ui.settings.SettingsActivity

/**
 * Główny ekran apki.
 * - Przycisk "Zadaj pytanie" (capture)
 * - Pole tekstowe (alternatywa)
 * - Status / odpowiedź AI
 * - Top bar: ustawienia, historia
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit = {},
    onRequestGoogleSignIn: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val modelWarning by viewModel.modelWarning.collectAsState()
    val newModels by viewModel.newModels.collectAsState()
    val currentModelId by viewModel.currentModelId.collectAsState()
    val pendingAction by viewModel.pendingActionConfirmation.collectAsState()
    var textInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val glassesManager = pl.victor.app.VictorApplication.get().glassesManager

    // Model mógł zostać zmieniony w ustawieniach - odśwież badge po powrocie na ten ekran
    // (ustawienia to osobne Activity, więc wracamy tu przez ON_RESUME).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshModelBadge()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val connState by glassesManager.connectionState.collectAsState()

    // Po przyznaniu uprawnienia od razu startujemy nasłuch - inaczej użytkownik
    // musiałby kliknąć mikrofon drugi raz i wyglądałoby to na zignorowanie.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onVoiceButtonPressed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        pl.victor.app.ui.brand.VictorMark(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("V.I.C.T.O.R.")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(context, PairingActivity::class.java))
                    }) {
                        Icon(
                            if (connState == pl.victor.app.ble.ConnectionState.READY)
                                Icons.Default.BluetoothConnected
                            else
                                Icons.Default.Bluetooth,
                            contentDescription = "Połącz z okularami"
                        )
                    }
                    IconButton(onClick = {
                        context.startActivity(Intent(context, HistoryActivity::class.java))
                    }) {
                        Icon(Icons.Default.History, contentDescription = "Historia")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ustawienia")
                    }
                    // Badge dla trybu konwersacyjnego
                    val orch = pl.victor.app.VictorApplication.get().orchestrator
                    val convOn by orch.conversationalModeFlow.collectAsState()
                    if (convOn) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Tryb konwersacyjny ON",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {

        // Banner o nowych modelach
        NewModelsBanner(
            newModels = newModels,
            onDismiss = { viewModel.clearNewModels() },
            onCheckAgain = { viewModel.refreshModels() }
        )

        // Animowany badge aktualnego modelu
        ModelBadge(
            modelId = currentModelId,
            showDetails = state is OrchestratorState.Idle
        )

        // Banner ostrzegawczy o modelu
        modelWarning?.let { warning ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Ikona wektorowa, nie emoji: emoji zależy od kroju pisma
                    // na urządzeniu, nie da się go przefarbować motywem, a
                    // czytnik ekranu odczytuje jego nazwę jako słowo.
                    // contentDescription = null, bo treść ostrzeżenia stoi
                    // tuż obok - dublowanie zmusza czytnik do dwóch zdań.
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(24.dp)
                    )
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.clearModelWarning() }) {
                        Text("OK")
                    }
                }
            }
        }

        // verticalScroll, bo ekran główny nie mieści się już na jednym widoku:
        // doszedł panel okularów i spis możliwości. Kolejność modyfikatorów jest
        // istotna - fillMaxSize PRZED przewijaniem zostawia kolumnie minimalną
        // wysokość ekranu, więc krótka treść (np. sam stan "myślę") dalej jest
        // wyśrodkowana, a długa się przewija zamiast urwać.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val currentState = state) {
                is OrchestratorState.Idle -> IdleContent(
                    textInput = textInput,
                    onTextChange = { textInput = it },
                    onCaptureClick = { viewModel.onCaptureButtonPressed() },
                    onExample = { viewModel.onTextSubmit(it) },
                    onVoiceClick = {
                        // RECORD_AUDIO było zadeklarowane w manifeście, ale NIKT
                        // o nie nie prosił - ani onboarding, ani żaden ekran. Bez
                        // niego rozpoznawanie mowy kończyło się błędem
                        // INSUFFICIENT_PERMISSIONS, a użytkownik widział tylko, że
                        // "nic się nie dzieje". Onboarding już o nie prosi, ale kto
                        // przeszedł go wcześniej, nie zostałby zapytany nigdy.
                        if (hasMicPermission(context)) {
                            viewModel.onVoiceButtonPressed()
                        } else {
                            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onTextSubmit = {
                        viewModel.onTextSubmit(textInput)
                        textInput = ""
                    }
                )

                is OrchestratorState.Capturing -> CapturingContent(currentState)

                is OrchestratorState.Listening -> ListeningContent()

                is OrchestratorState.Thinking -> ThinkingContent(
                    onInterrupt = { viewModel.onInterrupt() }
                )

                is OrchestratorState.Streaming -> StreamingContent(
                    text = currentState.text,
                    onInterrupt = { viewModel.onInterrupt() }
                )

                is OrchestratorState.Completed -> CompletedContent(
                    text = currentState.text,
                    onReset = { viewModel.resetState() },
                    onAskAgain = {
                        // Reset MUSI pójść pierwszy: orkiestrator ignoruje nowy
                        // trigger, dopóki stan nie wróci do Idle.
                        viewModel.resetState()
                        if (hasMicPermission(context)) {
                            viewModel.onVoiceButtonPressed()
                        } else {
                            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )

                is OrchestratorState.Error -> ErrorContent(
                    message = currentState.message,
                    onReset = { viewModel.resetState() },
                    onDiagnose = {
                        context.startActivity(
                            Intent(
                                context,
                                pl.victor.app.ui.diagnostics.DiagnosticsActivity::class.java
                            )
                        )
                    }
                )
            }
        }
        }
    }

    // Dialog potwierdzenia akcji (SMS, call w trybie DIRECT)
    pendingAction?.let { pending ->
        ActionConfirmationDialog(
            pending = pending,
            onConfirm = { viewModel.confirmAction() },
            onCancel = { viewModel.cancelAction() }
        )
    }
}

@Composable
private fun IdleContent(
    textInput: String,
    onTextChange: (String) -> Unit,
    onCaptureClick: () -> Unit,
    onExample: (String) -> Unit,
    onVoiceClick: () -> Unit,
    onTextSubmit: () -> Unit
) {
    val orch = pl.victor.app.VictorApplication.get().orchestrator
    val accessibilityMode by orch.accessibility.mode.collectAsState()
    val context = LocalContext.current

    Text(
        text = "Hej, jestem Twoim asystentem AI",
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Mów, pokaż aparatem albo napisz - odpowiem głosem",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // === Okulary: stan, gesty, ostatnie zdarzenie ===
    Spacer(modifier = Modifier.height(16.dp))
    GlassesPanel(
        onTakePhoto = onCaptureClick,
        onOpenPairing = {
            context.startActivity(Intent(context, PairingActivity::class.java))
        },
        onOpenDiagnostics = {
            context.startActivity(
                Intent(context, pl.victor.app.ui.diagnostics.DiagnosticsActivity::class.java)
            )
        },
        onOpenGallery = {
            context.startActivity(
                Intent(context, pl.victor.app.ui.media.MediaActivity::class.java)
            )
        }
    )

    // === Podgląd na żywo ===
    Spacer(modifier = Modifier.height(12.dp))
    androidx.compose.material3.OutlinedButton(
        onClick = {
            context.startActivity(
                Intent(context, pl.victor.app.ui.livepreview.LivePreviewActivity::class.java)
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        androidx.compose.material3.Text("Podgląd na żywo z kamery")
    }

    // === Notatki ===
    Spacer(modifier = Modifier.height(12.dp))
    androidx.compose.material3.OutlinedButton(
        onClick = {
            context.startActivity(
                Intent(context, pl.victor.app.ui.notes.NotesActivity::class.java)
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("📝 Notatki")
    }

    // === Co asystent o Tobie wie ===
    Spacer(modifier = Modifier.height(8.dp))
    androidx.compose.material3.OutlinedButton(
        onClick = {
            context.startActivity(
                Intent(context, pl.victor.app.ui.memory.FactsActivity::class.java)
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("🧠 Co o Tobie wiem")
    }

    // === Co V.I.C.T.O.R. potrafi ===
    Spacer(modifier = Modifier.height(12.dp))
    CapabilitiesPanel(
        onExample = onExample,
        onOpenAll = {
            context.startActivity(
                Intent(context, pl.victor.app.ui.commands.CommandsActivity::class.java)
            )
        }
    )

    // === Panel Accessibility (niewidomi) ===
    Spacer(modifier = Modifier.height(12.dp))
    // PANEL, NIE KARTA. Materialowa karta oddziela treść cieniem i
    // wypełnieniem - wygląda jak kartonik na tle. Panel oddziela ją kreską i
    // zostawia tło prawie takie samo jak dookoła; tak buduje się pola w
    // przyrządach. Patrz [pl.victor.app.ui.theme.VictorPanel].
    pl.victor.app.ui.theme.VictorPanel(
        label = "Asystent niewidomych",
        accent = accessibilityMode != pl.victor.app.accessibility.AccessibilityMode.OFF
    ) {
        Column {
            Text(
                if (accessibilityMode != pl.victor.app.accessibility.AccessibilityMode.OFF)
                    "Aktywny: ${accessibilityMode.emoji} ${accessibilityMode.displayName}"
                else "Komendy: \"czytaj\", \"co przede mną\", \"prowadź\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(8.dp))
            // JEDEN POD DRUGIM, NIE OBOK SIEBIE.
            //
            // Trzy przyciski w rzędzie mieściły się tylko dlatego, że etykiety
            // zjechały do 11 sp - czyli poniżej progu czytelności, i to w
            // panelu przeznaczonym dla osób, które widzą źle albo wcale.
            // Do tego przy powiększonej czcionce systemowej (a kto nie widzi
            // dobrze, ten ją powiększa) tekst i tak się urywał.
            //
            // W kolumnie każdy przycisk ma pełną szerokość, etykietę w
            // domyślnym rozmiarze i wysokość co najmniej 48 dp - tyle wynosi
            // minimalny cel dotyku na Androidzie.
            val modeButtons = listOf(
                Triple(
                    Icons.Default.MenuBook,
                    "Czytaj tekst",
                    { orch.accessibility.enableReadText() }
                ),
                Triple(
                    Icons.Default.Visibility,
                    "Opisuj otoczenie",
                    { orch.accessibility.enableDescribeScene() }
                ),
                Triple(
                    Icons.Default.Explore,
                    "Prowadź",
                    { orch.accessibility.enableNavigate() }
                )
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modeButtons.forEach { (icon, label, action) ->
                    Button(
                        onClick = action,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        enabled = accessibilityMode ==
                            pl.victor.app.accessibility.AccessibilityMode.OFF
                    ) {
                        // Ikona jest ozdobą przy widocznej etykiecie - czytnik
                        // ekranu ma przeczytać samą etykietę, nie nazwę glifu.
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(label)
                    }
                }
            }
            if (accessibilityMode != pl.victor.app.accessibility.AccessibilityMode.OFF) {
                Spacer(modifier = Modifier.size(8.dp))
                Button(
                    onClick = { orch.accessibility.disable() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Zatrzymaj tryb")
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    // Dwa główne wejścia, rozdzielone intencją. Wcześniej był tu SAM aparat:
    // żeby w ogóle coś powiedzieć, trzeba było mieć okulary na głowie i
    // działające wybudzenie - albo pisać z klawiatury. Asystent głosowy, do
    // którego trzeba pisać, mija się z celem.
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = onVoiceClick,
                modifier = Modifier.size(132.dp)
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Zapytaj głosem",
                    modifier = Modifier.size(52.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Powiedz", style = MaterialTheme.typography.labelLarge)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalButton(
                onClick = onCaptureClick,
                modifier = Modifier.size(132.dp)
            ) {
                Icon(
                    Icons.Default.Camera,
                    contentDescription = "Zapytaj o to, co widzę",
                    modifier = Modifier.size(52.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Pokaż", style = MaterialTheme.typography.labelLarge)
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    OutlinedTextField(
        value = textInput,
        onValueChange = onTextChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Lub wpisz pytanie…") },
        singleLine = true,
        trailingIcon = {
            if (textInput.isNotBlank()) {
                Button(onClick = onTextSubmit) {
                    Text("Wyślij")
                }
            }
        }
    )
}

@Composable
private fun CapturingContent(state: OrchestratorState.Capturing) {
    CircularProgressIndicator(
        progress = { state.progress.toFloat() / state.total },
        modifier = Modifier.size(120.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = state.label ?: "Przechwytuję obraz…",
        style = MaterialTheme.typography.titleMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "${state.progress}/${state.total}",
        style = MaterialTheme.typography.headlineLarge
    )
}

@Composable
private fun ListeningContent() {
    // Wyraźnie inny stan niż "AI myśli": tu mikrofon jest OTWARTY i to
    // użytkownik ma coś powiedzieć. Bez tego rozdziału wybudzenie okularami
    // wyglądałoby tak, jakby aplikacja już przetwarzała pytanie.
    val transition = rememberInfiniteTransition(label = "listening")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Icon(
        imageVector = Icons.Default.Mic,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Słucham…",
        style = MaterialTheme.typography.titleMedium
    )
}

@Composable
private fun ThinkingContent(onInterrupt: () -> Unit) {
    CircularProgressIndicator(modifier = Modifier.size(80.dp))

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "AI myśli…",
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Bez tego jedynym wyjściem z długiej odpowiedzi było czekanie do końca
    // albo wyjście z aplikacji - a asystent, którego nie da się uciszyć, jest
    // męczący dokładnie wtedy, gdy się pomylił.
    TextButton(onClick = onInterrupt) { Text("Przerwij") }
}

@Composable
private fun ColumnScope.StreamingContent(text: String, onInterrupt: () -> Unit) {
    // Pulsujące kropki
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha))
        Spacer(Modifier.size(4.dp))
        Text("●", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha * 0.7f))
        Spacer(Modifier.size(4.dp))
        Text("●", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha * 0.4f))
    }

    Spacer(Modifier.height(16.dp))

    Text(
        text = "AI odpowiada (streamuje)…",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(8.dp))

    // Tekst pojawia się z animacją. Przewijalny i ograniczony wagą, bo długa
    // odpowiedź wypychała przycisk przerwania poza ekran.
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    )

    Spacer(Modifier.height(12.dp))

    TextButton(onClick = onInterrupt) { Text("Przerwij") }
}

@Composable
// ColumnScope, bo Modifier.weight() jest jego rozszerzeniem - funkcja
// composable wywołana wewnątrz Column NIE dziedziczy jej odbiornika.
private fun ColumnScope.CompletedContent(
    text: String,
    onReset: () -> Unit,
    onAskAgain: () -> Unit
) {
    // Długa odpowiedź musi się dać przewinąć - inaczej koniec tekstu wypycha
    // przyciski poza ekran i użytkownik nie ma jak wrócić.
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
    )

    Spacer(modifier = Modifier.height(24.dp))

    // "Dopytaj" zamiast samego OK: rozmowa rzadko kończy się na jednym
    // pytaniu, a wcześniej po każdej odpowiedzi trzeba było wrócić do
    // ekranu startowego i zacząć od zera.
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onAskAgain) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text("Dopytaj")
        }
        FilledTonalButton(onClick = onReset) {
            Text("Gotowe")
        }
    }
}

@Composable
private fun ErrorContent(message: String, onReset: () -> Unit, onDiagnose: () -> Unit) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.error
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(onClick = onReset) {
        Text("Spróbuj ponownie")
    }

    // Diagnostyka była schowana w Ustawieniach, pod opisem trybu symulacji -
    // czyli najdalej od miejsca, w którym staje się potrzebna. Skrót jest tutaj,
    // bo błąd to dokładnie ten moment: "Sprawdź wszystko" przechodzi po kolei
    // przez wszystkie ogniwa i mówi, które nie działa.
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = onDiagnose) {
        Text("Sprawdź, co nie działa")
    }
}

/** Czy mamy uprawnienie do mikrofonu - patrz obsługa przycisku "Powiedz". */
private fun hasMicPermission(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
