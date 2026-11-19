package pl.victor.app.ui.translation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import pl.victor.app.VictorApplication
import pl.victor.app.translation.EarTranscript
import pl.victor.app.translation.SimultaneousTranslator
import pl.victor.app.translation.TwoWay
import pl.victor.app.ui.theme.VictorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Panel tłumaczenia na żywo - to, co słychać, i przekład, na ekranie telefonu.
 *
 * ## Skąd pomysł i czym się różni od pierwowzoru
 * Aplikacja producenta ma ekran "Simultaneous translation": dwa pola jedno nad
 * drugim (oryginał, przekład), wybór języków nad każdym i jeden duży przycisk
 * Start/Stop. Ten ekran ma ten sam układ, bo on po prostu działa - ale nie
 * powtarza jego braków, które widać w kodzie producenta:
 *  - tekst, który jeszcze się zmienia, wygląda tam tak samo jak pewny; tu jest
 *    szary i pochylony, więc widać, czemu jeszcze nie ufać,
 *  - błędy tłumaczenia są tam niewidoczne (komunikat powstaje i nigdzie nie
 *    trafia); tu każdy powód zakończenia jest na ekranie,
 *  - nie ma zamiany języków jednym ruchem ani kopiowania i wysyłania tekstu.
 *
 * ## Ekran jest tylko widokiem
 * Całą pracę robi tryb tłumaczenia ze słuchu w orkiestratorze - ten sam, który
 * włącza przycisk na okularach i komenda "tłumaczenie na żywo". Panel go
 * uruchamia, pokazuje i zatrzymuje; tryb działa dalej także po jego zamknięciu,
 * bo tak się go używa w rozmowie z telefonem w kieszeni.
 */
class TranslationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VictorTheme {
                TranslationScreen(
                    onBack = { finish() },
                    keepScreenOn = { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    },
                    fullBrightness = { on ->
                        // Napisy dla rozmówcy czyta się często na dworze - pełna
                        // jasność tylko na czas tego widoku, potem wraca systemowa.
                        val lp: WindowManager.LayoutParams = window.attributes
                        lp.screenBrightness = if (on) 1f
                        else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.setAttributes(lp)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationScreen(
    onBack: () -> Unit,
    keepScreenOn: (Boolean) -> Unit,
    fullBrightness: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val app = remember { VictorApplication.get() }
    val orchestrator = remember { app.orchestrator }
    val settings = remember { app.settings }
    val transcript by orchestrator.earTranscript.collectAsState()
    val running by orchestrator.earTranslation.collectAsState()
    val glassesState by app.glassesManager.connectionState.collectAsState()
    var from by remember { mutableStateOf(settings.getEarTranslationFrom()) }
    var to by remember { mutableStateOf(settings.getEarTranslationTo()) }
    var spoken by remember { mutableStateOf(settings.isEarTranslationSpoken()) }
    val twoWayRunning by orchestrator.earTwoWay.collectAsState()
    // Wybór trybu przed startem - w trakcie pokazuje to, co naprawdę działa.
    var twoWayWanted by remember { mutableStateOf(false) }

    // Napisy dla rozmówcy - patrz [PartnerCaptions].
    var showToPartner by rememberSaveable { mutableStateOf(false) }
    val forPartner = remember(transcript) { TwoWay.dlaRozmówcy(transcript) }

    // Ekran gaśnie po kilkudziesięciu sekundach - w środku rozmowy z kimś, kto
    // właśnie mówi. Producent trzyma go zapalonym przez cały czas trwania.
    // Widok dla rozmówcy - zawsze, także po zatrzymaniu: ktoś właśnie z niego czyta.
    DisposableEffect(running, showToPartner) {
        keepScreenOn(running || showToPartner)
        onDispose { keepScreenOn(false) }
    }
    DisposableEffect(showToPartner) {
        fullBrightness(showToPartner)
        if (showToPartner) {
            runCatching {
                app.diag.event(
                    pl.victor.app.diagnostics.DiagFormat.Phase.NASŁUCH, "rozmowa: napisy dla rozmówcy",
                    mapOf("trwa" to running, "dwieStrony" to twoWayRunning, "maPrzekład" to (forPartner.mójPrzekład != null))
                )
            }
        }
        onDispose { fullBrightness(false) }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (twoWayWanted) orchestrator.startTwoWayTranslation(from) else orchestrator.startEarTranslation()
        } else {
            Toast.makeText(context, "Bez mikrofonu nie ma czego tłumaczyć.", Toast.LENGTH_LONG).show()
        }
    }

    fun toggle() {
        if (running) {
            orchestrator.stopEarTranslation("przycisk w panelu")
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (twoWayWanted) orchestrator.startTwoWayTranslation(from) else orchestrator.startEarTranslation()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun setLanguages(newFrom: String, newTo: String) {
        from = newFrom
        to = newTo
        orchestrator.setEarTranslationLanguages(newFrom, newTo)
    }

    val fromName = SimultaneousTranslator.languageName(from)
    val toName = SimultaneousTranslator.languageName(to)

    if (showToPartner) {
        BackHandler { showToPartner = false }
        PartnerCaptions(
            content = forPartner,
            partnerLanguage = transcript.z,
            myTurnAvailable = running && twoWayRunning,
            onMyTurn = { orchestrator.myTurn(zOkularów = false) },
            onClose = { showToPartner = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tłumaczenie na żywo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wróć")
                    }
                },
                actions = {
                    val hasText = transcript.segmenty.isNotEmpty()
                    IconButton(onClick = { copyTranscript(context, transcript) }, enabled = hasText) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Kopiuj")
                    }
                    IconButton(onClick = { shareTranscript(context, transcript) }, enabled = hasText) {
                        Icon(Icons.Default.Share, contentDescription = "Udostępnij")
                    }
                    IconButton(onClick = { orchestrator.clearEarTranscript() }, enabled = hasText) {
                        Icon(Icons.Default.Delete, contentDescription = "Wyczyść")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // === Języki ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LanguagePicker(
                    label = "Słyszę",
                    code = from,
                    modifier = Modifier.weight(1f),
                    onPick = { picked ->
                        // Ten sam język po obu stronach nie ma sensu - wybór
                        // języka docelowego jako źródła to w praktyce zamiana.
                        if (picked == to) setLanguages(picked, from) else setLanguages(picked, to)
                    }
                )
                IconButton(onClick = { setLanguages(to, from) }) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Zamień języki")
                }
                LanguagePicker(
                    label = "Tłumaczę na",
                    code = to,
                    modifier = Modifier.weight(1f),
                    onPick = { picked ->
                        if (picked == from) setLanguages(to, picked) else setLanguages(from, picked)
                    }
                )
            }

            // === Oryginał ===
            TextPane(
                title = "Słyszę · $fromName",
                modifier = Modifier.weight(1f),
                trailing = null
            ) {
                PaneText(
                    committed = transcript.segmenty.map { it.id to it.oryginał },
                    partial = transcript.częściowyOryginał,
                    highlightedId = null,
                    placeholder = if (running) "Mów albo podsuń telefon rozmówcy…"
                    else "Tu pojawi się to, co słychać."
                )
            }

            // === Przekład ===
            TextPane(
                title = "Przekład · $toName",
                modifier = Modifier.weight(1f),
                trailing = {
                    IconButton(onClick = {
                        spoken = !spoken
                        settings.setEarTranslationSpoken(spoken)
                    }) {
                        Icon(
                            if (spoken) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = if (spoken) "Wycisz przekład" else "Czytaj przekład na głos"
                        )
                    }
                }
            ) {
                PaneText(
                    committed = transcript.segmenty.map { it.id to it.przekład },
                    partial = transcript.częściowyPrzekład,
                    highlightedId = transcript.czytanyId,
                    placeholder = "Tu pojawi się przekład."
                )
            }

            // === Stan i komunikaty ===
            val status = when {
                transcript.komunikat != null && !running -> transcript.komunikat
                !running -> null
                transcript.status == EarTranscript.Status.MÓWIĘ -> "Czytam przekład…"
                else -> "Słucham (" + (
                    if (glassesState == pl.victor.app.ble.ConnectionState.READY) "okulary i telefon"
                    else "mikrofon telefonu"
                    ) + ")…"
            }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!running && transcript.komunikat != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!spoken && running) {
                Text(
                    "Głos wyłączony - przekład tylko na ekranie.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // === Rozmowa w dwie strony ===
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🗣️ Rozmowa w dwie strony", fontWeight = FontWeight.Medium)
                    Text(
                        "Ty mówisz po polsku (klik na oprawce albo \"Mówię ja\"), przekład czyta " +
                            "głośnik telefonu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = if (running) twoWayRunning else twoWayWanted,
                    enabled = !running,
                    onCheckedChange = { twoWayWanted = it }
                )
            }
            if (running && twoWayRunning) {
                OutlinedButton(
                    onClick = { orchestrator.myTurn(zOkularów = false) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🎤 Mówię ja (po polsku)") }
            }
            // Działa na samym stanie panelu - tak samo przy tłumaczeniu w
            // chmurze, jak i w telefonie, i zostaje po zatrzymaniu, póki jest przekład.
            if ((running && twoWayRunning) || forPartner.mójPrzekład != null) {
                OutlinedButton(
                    onClick = { showToPartner = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pokaż rozmówcy")
                }
            }

            // === Start / Stop ===
            Button(
                onClick = { toggle() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = if (running) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(if (running) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Zatrzymaj tłumaczenie" else "Zacznij tłumaczyć", fontSize = 16.sp)
            }
        }
    }
}

/**
 * Napisy dla rozmówcy: przekład Twojego ostatniego zdania na cały ekran.
 *
 * Telefon trzymasz ekranem do rozmówcy (albo kładziesz na stole między wami) -
 * w hałasie głośnik nie wystarcza, a tekst czyta się z metra. Stąd biały
 * tekst na czarnym tle, pełna jasność i wielkość dobierana do długości
 * ([TwoWay.czcionkaDlaRozmówcy]). Obrót o 180° jest dla telefonu leżącego
 * na stole: tekst ma być prosto dla niego, a przyciski zostają po Twojej
 * stronie. Widok odświeża się sam przy każdym nowym przekładzie.
 */
@Composable
private fun PartnerCaptions(
    content: TwoWay.DlaRozmówcy,
    partnerLanguage: String,
    myTurnAvailable: Boolean,
    onMyTurn: () -> Unit,
    onClose: () -> Unit
) {
    var rotated by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // Nowy przekład - od początku, a nie tam, gdzie skończył się poprzedni.
    LaunchedEffect(content.mójId) { scroll.scrollTo(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // Box środkuje krótki tekst; długi przewija się w kolumnie w środku.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .rotate(if (rotated) 180f else 0f),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll)) {
                Text(
                    partnerLanguage.substringBefore('-').uppercase(),
                    color = Color(0xFFFFD54F),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    content.mójPrzekład ?: "…",
                    color = Color.White,
                    fontSize = TwoWay.czcionkaDlaRozmówcy(content.mójPrzekład).sp,
                    lineHeight = (TwoWay.czcionkaDlaRozmówcy(content.mójPrzekład) * 1.2f).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                content.jegoOryginał?.let { his ->
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "» $his",
                        color = Color(0xFFBDBDBD),
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        // Mały podpis dla Ciebie - co powiedziałeś, po polsku. Nie obraca się:
        // jest po Twojej stronie ekranu.
        content.mojeZdanie?.let { mine ->
            Text(
                "Ty: $mine",
                color = Color(0xFF9E9E9E),
                fontSize = 14.sp,
                maxLines = 2,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Zamknij", tint = Color.White)
            }
            OutlinedButton(onClick = { rotated = !rotated }) {
                Icon(Icons.Default.ScreenRotation, contentDescription = "Obróć tekst", tint = Color.White)
            }
            if (myTurnAvailable) {
                Button(onClick = onMyTurn, modifier = Modifier.weight(1f)) {
                    Text("🎤 Mówię ja")
                }
            }
        }
    }
}

@Composable
private fun LanguagePicker(
    label: String,
    code: String,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(SimultaneousTranslator.languageName(code), fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SimultaneousTranslator.SUPPORTED_LANGUAGES.keys.forEach { option ->
                DropdownMenuItem(
                    text = { Text(SimultaneousTranslator.languageName(option)) },
                    onClick = {
                        expanded = false
                        onPick(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun TextPane(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (trailing != null) Box(modifier = Modifier.size(40.dp)) { trailing() }
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/**
 * Tekst jednego pola: gotowe odcinki, a po nich ogon, który jeszcze się zmienia.
 *
 * Przewija się sam na koniec przy każdej zmianie - jak u producenta. Czytany
 * właśnie odcinek jest wyróżniony (u producenta też), żeby wzrok trafiał tam,
 * gdzie jest ucho.
 */
@Composable
private fun ColumnScope.PaneText(
    committed: List<Pair<Long, String>>,
    partial: String,
    highlightedId: Long?,
    placeholder: String
) {
    val scroll = rememberScrollState()
    LaunchedEffect(committed.size, partial, highlightedId) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val empty = committed.isEmpty() && partial.isBlank()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(scroll)
    ) {
        if (empty) {
            Text(placeholder, color = muted, fontSize = 16.sp)
        } else {
            Text(
                buildAnnotatedString {
                    committed.forEachIndexed { i, (id, tekst) ->
                        if (i > 0) append("\n")
                        if (id == highlightedId) {
                            withStyle(SpanStyle(color = primary, fontWeight = FontWeight.SemiBold)) {
                                append(tekst)
                            }
                        } else {
                            append(tekst)
                        }
                    }
                    if (partial.isNotBlank()) {
                        if (committed.isNotEmpty()) append("\n")
                        withStyle(SpanStyle(color = muted.copy(alpha = 0.8f), fontStyle = FontStyle.Italic)) {
                            append(partial)
                        }
                    }
                },
                fontSize = 18.sp,
                lineHeight = 26.sp
            )
        }
    }
}

private fun transcriptText(transcript: EarTranscript): String {
    val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return transcript.doUdostępnienia(
        SimultaneousTranslator.languageName(transcript.z),
        SimultaneousTranslator.languageName(transcript.na)
    ) { clock.format(Date(it)) }
}

private fun copyTranscript(context: Context, transcript: EarTranscript) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Tłumaczenie", transcriptText(transcript)))
    Toast.makeText(context, "Skopiowano", Toast.LENGTH_SHORT).show()
}

private fun shareTranscript(context: Context, transcript: EarTranscript) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Tłumaczenie - V.I.C.T.O.R.")
        putExtra(Intent.EXTRA_TEXT, transcriptText(transcript))
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Wyślij tłumaczenie")) }
}
