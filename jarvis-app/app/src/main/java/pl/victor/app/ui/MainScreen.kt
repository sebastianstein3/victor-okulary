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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import pl.victor.app.OrchestratorState
import pl.victor.app.ui.components.ActionConfirmationDialog
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
    // TextFieldValue, nie sam String: kafelki "Zadzwoń", "Krok po kroku" itd.
    // wstawiają początek zdania i kursor ma stanąć NA KOŃCU, a przy zwykłym
    // Stringu zostawał tam, gdzie był - czyli na początku pustego pola.
    var textInput by remember { mutableStateOf(TextFieldValue("")) }
    val textFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val glassesManager = pl.victor.app.VictorApplication.get().glassesManager
    val cameraFree by pl.victor.app.VictorApplication.get().settings.cameraFreeFlow.collectAsState()
    val glassesBattery by glassesManager.batteryLevel.collectAsState()

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
                        Text("V.I.C.T.O.R.", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    // Znaczek trybu bez kamery: sama ikona, bo w pasku z czterema
                    // przyciskami i baterią napis by się nie zmieścił na 390 dp.
                    if (cameraFree) {
                        Icon(
                            Icons.Default.VideocamOff,
                            contentDescription = "Tryb bez kamery",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(20.dp)
                        )
                    }
                    // Bateria okularów - tylko gdy są połączone i ją znamy.
                    // Zastępuje pełny panel okularów, który był na ekranie głównym.
                    val battery = glassesBattery
                    if (connState == pl.victor.app.ble.ConnectionState.READY && battery != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.BatteryStd,
                                contentDescription = "Bateria okularów",
                                tint = if (battery <= 20) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "$battery%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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

        // Animowany badge aktualnego modelu - poza stanem bezczynności. Na
        // ekranie głównym zabierał ok. 70 dp, przez które kafelki przestawały
        // się mieścić bez przewijania, a model widać i zmienia się w
        // Ustawieniach. W trakcie odpowiedzi zostaje: wtedy ważne jest, KTO
        // odpowiada.
        if (state !is OrchestratorState.Idle) {
            ModelBadge(
                modelId = currentModelId,
                showDetails = false
            )
        }

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

        // verticalScroll zostaje, choć kafelki mieszczą się na typowym telefonie:
        // przy dużej czcionce systemowej treść ma się przewijać, a nie urwać.
        // Kolejność modyfikatorów jest
        // istotna - fillMaxSize PRZED przewijaniem zostawia kolumnie minimalną
        // wysokość ekranu, więc krótka treść (np. sam stan "myślę") dalej jest
        // wyśrodkowana, a długa się przewija zamiast urwać.
        //
        // Stan bezczynności (kafelki) układa się od góry, z węższym marginesem;
        // pozostałe stany - jak dotąd, wyśrodkowane.
        val idle = state is OrchestratorState.Idle
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (idle) 16.dp else 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = if (idle) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = if (idle) Arrangement.Top else Arrangement.Center
        ) {
            when (val currentState = state) {
                is OrchestratorState.Idle -> IdleContent(
                    textInput = textInput,
                    onTextChange = { textInput = it },
                    onPrefill = { start ->
                        textInput = TextFieldValue(start, selection = TextRange(start.length))
                        // Pole jest zawsze na ekranie w tym stanie; runCatching
                        // na wypadek, gdyby kliknięcie przyszło w trakcie zmiany stanu.
                        runCatching { textFocus.requestFocus() }
                    },
                    focusRequester = textFocus,
                    cameraFree = cameraFree,
                    onCaptureClick = { viewModel.onCaptureButtonPressed() },
                    onSendText = { viewModel.onTextSubmit(it) },
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
                        viewModel.onTextSubmit(textInput.text)
                        textInput = TextFieldValue("")
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

/**
 * Ekran główny w spoczynku: kafelki zamiast kolumny przycisków.
 *
 * ## Dlaczego kafelki
 * Dawna kolumna obrysowanych przycisków z emoji rosła z każdą funkcją i
 * kończyła się daleko poza ekranem - a najważniejsze ("Powiedz") było na
 * samym dole. Teraz kolejność jest odwrotna: najpierw to, co DZIAŁA w tle
 * (z przyciskiem "Zakończ"), potem główne wejście, potem funkcje w grupach
 * po trzy. Całość mieści się na typowym telefonie bez przewijania; przewijanie
 * zostaje tylko na wypadek dużej czcionki systemowej.
 *
 * ## Tryb bez kamery
 * Gdy w Ustawieniach włączony jest "Tryb bez kamery", znikają kafelki, które
 * bez aparatu nie mają sensu (Co widzę, Na żywo, Galeria, Dla niewidomych,
 * Krok po kroku), a w ich miejsce wchodzi telefon: dzwonienie, muzyka, alarm,
 * wiadomości i plan dnia.
 */
@Composable
private fun IdleContent(
    textInput: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    onPrefill: (String) -> Unit,
    focusRequester: FocusRequester,
    cameraFree: Boolean,
    onCaptureClick: () -> Unit,
    onSendText: (String) -> Unit,
    onVoiceClick: () -> Unit,
    onTextSubmit: () -> Unit
) {
    val app = pl.victor.app.VictorApplication.get()
    val orch = app.orchestrator
    val context = LocalContext.current

    // Wszystko, co może działać w tle - stąd pasek "TERAZ DZIAŁA" i
    // podświetlenie kafelków.
    val accessibilityMode by orch.accessibility.mode.collectAsState()
    val guide = remember { orch.guide }
    val guideOn by guide.aktywny.collectAsState()
    val earOn by orch.earTranslation.collectAsState()
    val earTwoWay by orch.earTwoWay.collectAsState()
    val meetingRecorder = remember { orch.meeting }
    val meetingState by meetingRecorder.stan.collectAsState()
    val meetingOn = meetingState is pl.victor.app.features.meeting.MeetingRecorder.Stan.Nagrywa
    val videoOn by app.glassesManager.videoRecording.collectAsState()
    val connState by app.glassesManager.connectionState.collectAsState()
    val blindOn = accessibilityMode != pl.victor.app.accessibility.AccessibilityMode.OFF

    var showBlindDialog by remember { mutableStateOf(false) }

    fun open(activity: Class<*>) {
        context.startActivity(Intent(context, activity))
    }

    // Gdy działa kilka rzeczy naraz, pasek pokazuje pierwszą z tej listy.
    // Kolejność = co najbardziej zajmuje uszy i mikrofon.
    val running: RunningMode? = when {
        earTwoWay -> RunningMode(
            icon = Icons.Default.Forum,
            name = "Rozmowa – tłumaczenie",
            onStop = { orch.stopEarTranslation("przycisk w aplikacji") }
        )
        earOn -> RunningMode(
            icon = Icons.Default.Hearing,
            name = "Tłumaczenie ze słuchu",
            onStop = { orch.stopEarTranslation("przycisk w aplikacji") }
        )
        meetingOn -> RunningMode(
            icon = Icons.Default.Group,
            name = "Notatki ze spotkania",
            onStop = { orch.stopMeeting() }
        )
        guideOn -> RunningMode(
            icon = Icons.Default.Explore,
            name = "Przewodnik po okolicy",
            onStop = { guide.stop("przycisk w aplikacji") }
        )
        blindOn -> RunningMode(
            icon = Icons.Default.Accessibility,
            name = accessibilityMode.displayName,
            onStop = { orch.accessibility.disable() }
        )
        videoOn -> RunningMode(
            icon = Icons.Default.Videocam,
            name = "Film z okularów",
            onStop = { orch.stopVideoFromUi() }
        )
        else -> null
    }

    // === Okulary niepołączone ===
    // Pełny panel okularów (legenda gestów, ostatnie zdarzenie) zajmował pół
    // ekranu także wtedy, gdy wszystko działało. Teraz przy połączonych
    // okularach wystarcza znaczek baterii w pasku górnym, a karta pojawia się
    // tylko wtedy, gdy jest coś do zrobienia: połączyć albo sprawdzić, co nie działa.
    if (connState != pl.victor.app.ble.ConnectionState.READY) {
        GlassesDisconnectedCard(
            state = connState,
            onConnect = { open(PairingActivity::class.java) },
            onDiagnose = { open(pl.victor.app.ui.diagnostics.DiagnosticsActivity::class.java) }
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // === TERAZ DZIAŁA ===
    running?.let { mode ->
        RunningBar(mode)
        Spacer(modifier = Modifier.height(12.dp))
    }

    // === Rząd główny: Powiedz (2 kolumny) + Co widzę / Wiadomości ===
    // Szerokość kolumny liczona jak w siatkach poniżej, żeby krawędzie
    // kafelków wypadały w jednej linii.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val column = (maxWidth - TILE_GAP * 2) / 3
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP)
        ) {
            VoiceTile(
                onClick = onVoiceClick,
                modifier = Modifier
                    .width(column * 2 + TILE_GAP)
                    .fillMaxHeight()
            )
            if (cameraFree) {
                HomeTile(
                    label = "Wiadomości",
                    icon = Icons.Default.Chat,
                    onClick = { orch.readNewMessages() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            } else {
                HomeTile(
                    label = "Co widzę",
                    icon = Icons.Default.Visibility,
                    onClick = onCaptureClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }

    // === Grupy kafelków ===
    val translationActivity = pl.victor.app.ui.translation.TranslationActivity::class.java
    val earTile = HomeTileSpec("Ze słuchu", Icons.Default.Hearing, active = earOn && !earTwoWay) {
        open(translationActivity)
    }
    val twoWayTile = HomeTileSpec("Rozmowa", Icons.Default.Forum, active = earTwoWay) {
        if (!orch.earTwoWay.value) orch.startTwoWayTranslation()
        open(translationActivity)
    }
    val guideTile = HomeTileSpec("Przewodnik", Icons.Default.Explore, active = guideOn) {
        if (guide.aktywny.value) guide.stop("przycisk w aplikacji") else guide.start()
    }
    val factsTile = HomeTileSpec("Co o mnie wiesz", Icons.Default.Face) {
        open(pl.victor.app.ui.memory.FactsActivity::class.java)
    }
    val commandsTile = HomeTileSpec("Wszystkie komendy", Icons.Default.List) {
        open(pl.victor.app.ui.commands.CommandsActivity::class.java)
    }
    val rememberGroup = listOf(
        HomeTileSpec("Notatki", Icons.Default.Edit) {
            open(pl.victor.app.ui.notes.NotesActivity::class.java)
        },
        HomeTileSpec("Spotkanie", Icons.Default.Group, active = meetingOn) {
            open(pl.victor.app.ui.meeting.MeetingActivity::class.java)
        },
        // Przypomnienia w miejscach mają swoją kartę na ekranie komend.
        HomeTileSpec("Przypomnienia", Icons.Default.Notifications) {
            open(pl.victor.app.ui.commands.CommandsActivity::class.java)
        }
    )

    val groups: List<Pair<String, List<HomeTileSpec>>> = if (cameraFree) {
        listOf(
            "Telefon" to listOf(
                HomeTileSpec("Zadzwoń", Icons.Default.Call) { onPrefill("Zadzwoń do ") },
                HomeTileSpec("Muzyka", Icons.Default.MusicNote) { onSendText("Włącz muzykę") },
                HomeTileSpec("Alarm i minutnik", Icons.Default.Alarm) { onPrefill("Ustaw alarm na ") }
            ),
            "Rozmawiaj i tłumacz" to listOf(
                earTile,
                twoWayTile,
                HomeTileSpec("Mój dzień", Icons.Default.WbSunny) { orch.runBriefing() }
            ),
            "Zapamiętaj" to rememberGroup,
            "W drodze i moje" to listOf(guideTile, factsTile, commandsTile)
        )
    } else {
        listOf(
            "Patrz i tłumacz" to listOf(
                HomeTileSpec("Na żywo", Icons.Default.Videocam) {
                    open(pl.victor.app.ui.livepreview.LivePreviewActivity::class.java)
                },
                earTile,
                twoWayTile
            ),
            "Zapamiętaj" to rememberGroup,
            "W drodze" to listOf(
                guideTile,
                HomeTileSpec("Krok po kroku", Icons.Default.FormatListNumbered) {
                    onPrefill("Krok po kroku: ")
                },
                HomeTileSpec("Dla niewidomych", Icons.Default.Accessibility, active = blindOn) {
                    showBlindDialog = true
                }
            ),
            "Moje" to listOf(
                HomeTileSpec("Galeria", Icons.Default.PhotoLibrary) {
                    open(pl.victor.app.ui.media.MediaActivity::class.java)
                },
                factsTile,
                commandsTile
            )
        )
    }

    groups.forEach { (title, tiles) ->
        Spacer(modifier = Modifier.height(14.dp))
        TileGroup(title = title, tiles = tiles)
    }

    // === Pole tekstowe ===
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value = textInput,
        onValueChange = onTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text("Lub wpisz pytanie…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
            if (textInput.text.isNotBlank()) onTextSubmit()
        }),
        trailingIcon = {
            IconButton(onClick = onTextSubmit, enabled = textInput.text.isNotBlank()) {
                Icon(Icons.Default.Send, contentDescription = "Wyślij")
            }
        }
    )

    // === Tryby dla niewidomych ===
    // Zastępuje dawny duży panel "Asystent niewidomych": trzy tryby to rzadki
    // wybór, a panel zajmował stałe miejsce na ekranie każdemu.
    if (showBlindDialog) {
        BlindModesDialog(
            current = accessibilityMode,
            onReadText = { orch.accessibility.enableReadText() },
            onDescribeScene = { orch.accessibility.enableDescribeScene() },
            onNavigate = { orch.accessibility.enableNavigate() },
            onDisable = { orch.accessibility.disable() },
            onDismiss = { showBlindDialog = false }
        )
    }
}

/** Odstęp między kafelkami - wspólny dla siatek i rzędu głównego. */
private val TILE_GAP = 8.dp

/** Co pokazuje pasek "TERAZ DZIAŁA". */
private class RunningMode(
    val icon: ImageVector,
    val name: String,
    val onStop: () -> Unit
)

/** Jeden kafelek w siatce. */
private class HomeTileSpec(
    val label: String,
    val icon: ImageVector,
    val active: Boolean = false,
    val onClick: () -> Unit
)

/** Pasek tego, co działa w tle, z przyciskiem "Zakończ". */
@Composable
private fun RunningBar(mode: RunningMode) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.primaryContainer)
            .border(1.dp, colors.primary, shape)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Ikona jest ozdobą - nazwę trybu czytnik ekranu dostaje z tekstu obok.
        Icon(
            mode.icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "TERAZ DZIAŁA",
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                mode.name,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
            )
        }
        Button(
            onClick = mode.onStop,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("Zakończ")
        }
    }
}

/** Duży kafelek "Powiedz" - główne wejście, na dwie kolumny. */
@Composable
private fun VoiceTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .heightIn(min = 88.dp)
            .clip(shape)
            .background(colors.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Napis "Powiedz" stoi obok - ikona nie potrzebuje opisu.
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            tint = colors.onPrimary,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column {
            Text(
                "Powiedz",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onPrimary
            )
            Text(
                "albo kliknij na oprawce",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onPrimary
            )
        }
    }
}

/** Zwykły kafelek: ikona u góry, podpis pod nią. */
@Composable
private fun HomeTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(if (active) colors.primaryContainer else colors.surface)
            .border(1.dp, if (active) colors.primary else colors.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (active) "$label · działa" else label,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = if (active) colors.onPrimaryContainer else colors.onSurface
        )
    }
}

/** Nagłówek grupy i siatka kafelków po trzy w rzędzie. */
@Composable
private fun TileGroup(title: String, tiles: List<HomeTileSpec>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        tiles.chunked(3).forEachIndexed { index, row ->
            if (index > 0) Spacer(modifier = Modifier.height(TILE_GAP))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP)
            ) {
                row.forEach { tile ->
                    HomeTile(
                        label = tile.label,
                        icon = tile.icon,
                        onClick = tile.onClick,
                        active = tile.active,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
                // Niepełny rząd: puste miejsca trzymają szerokość kolumn.
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** Krótka karta, gdy okulary nie są gotowe: połącz albo sprawdź, co nie działa. */
@Composable
private fun GlassesDisconnectedCard(
    state: pl.victor.app.ble.ConnectionState,
    onConnect: () -> Unit,
    onDiagnose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val description = when (state) {
        pl.victor.app.ble.ConnectionState.CONNECTING -> "Łączę…"
        pl.victor.app.ble.ConnectionState.SCANNING -> "Szukam okularów…"
        pl.victor.app.ble.ConnectionState.CONNECTED -> "Połączone, kończę konfigurację"
        pl.victor.app.ble.ConnectionState.ERROR -> "Błąd połączenia"
        else -> "Możesz pytać telefonem albo połączyć okulary"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.BluetoothDisabled,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Okulary niepołączone",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        TextButton(onClick = onConnect) { Text("Połącz") }
        TextButton(onClick = onDiagnose) { Text("Sprawdź") }
    }
}

/** Wybór trybu dla niewidomych - zastępuje dawny panel na ekranie głównym. */
@Composable
private fun BlindModesDialog(
    current: pl.victor.app.accessibility.AccessibilityMode,
    onReadText: () -> Unit,
    onDescribeScene: () -> Unit,
    onNavigate: () -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit
) {
    val off = current == pl.victor.app.accessibility.AccessibilityMode.OFF
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dla niewidomych") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (off) {
                        "Wybierz tryb. Głosem: „czytaj”, „co przede mną”, „prowadź”."
                    } else {
                        "Teraz działa: ${current.displayName}. Żeby wybrać inny, najpierw go wyłącz."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                // Jeden pod drugim, pełna szerokość i co najmniej 48 dp - ten
                // wybór robią osoby, które widzą źle, często z dużą czcionką.
                listOf(
                    Triple(Icons.Default.MenuBook, "Czytaj tekst", onReadText),
                    Triple(Icons.Default.Visibility, "Opisuj otoczenie", onDescribeScene),
                    Triple(Icons.Default.Explore, "Prowadź", onNavigate)
                ).forEach { (icon, label, action) ->
                    Button(
                        onClick = {
                            action()
                            onDismiss()
                        },
                        enabled = off,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(label)
                    }
                }
                if (!off) {
                    Button(
                        onClick = {
                            onDisable()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Wyłącz tryb")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Anuluj") }
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
