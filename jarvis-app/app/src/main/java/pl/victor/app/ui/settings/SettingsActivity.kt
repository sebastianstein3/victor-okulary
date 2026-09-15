package pl.victor.app.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.victor.app.ai.AIProviderFactory
import pl.victor.app.google.GoogleAccountManager
import pl.victor.app.google.GoogleAccountManager.SignInOutcome
import pl.victor.app.ai.ProviderInfo
import pl.victor.app.data.ModelInfo
import pl.victor.app.ui.theme.Motion
import pl.victor.app.ui.theme.VictorTheme

/**
 * Ekran ustawień - wybór providera AI, klucze API, opcje.
 */
class SettingsActivity : ComponentActivity() {

    private val googleSignInLauncher = androidx.activity.result.contract.ActivityResultContracts
        .StartActivityForResult()
        .let { contract ->
            registerForActivityResult(contract) { result ->
                // Wynik czyta GoogleAccountManager - ten sam kod, co na ekranie
                // głównym. Wcześniej każdy ekran miał własną obsługę i ta sama
                // nieudana próba kończyła się raz komunikatem, raz ciszą.
                val app = application as pl.victor.app.VictorApplication
                val manager = GoogleAccountManager(this)
                when (val outcome = manager.handleSignInResult(result.data)) {
                    is SignInOutcome.Success -> {
                        app.settings.setGoogleAccountConnected(true)
                        toast("Połączono konto: ${outcome.account.email ?: "Google"}")
                    }
                    is SignInOutcome.MissingConsent -> {
                        app.settings.setGoogleAccountConnected(false)
                        toast(
                            "Zalogowano, ale bez zgód: " +
                                "${outcome.missing.joinToString(", ")}. Kalendarz " +
                                "nie zadziała - zaloguj się ponownie i zaznacz " +
                                "wszystkie."
                        )
                    }
                    SignInOutcome.Cancelled -> {
                        app.settings.setGoogleAccountConnected(false)
                        // Patrz GoogleAccountManager.CANCELLED_HINT: zablokowany
                        // ekran zgody wraca nieodróżnialnie od rezygnacji.
                        toast(GoogleAccountManager.CANCELLED_HINT)
                    }
                    is SignInOutcome.Failed -> {
                        app.settings.setGoogleAccountConnected(false)
                        toast("Nie udało się połączyć konta Google. ${outcome.message}")
                    }
                }
            }
        }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ekran pokazuje klucze API - blokuj zrzuty ekranu i podgląd w menu zadań.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent {
            VictorTheme {
                SettingsScreen(
                    onBack = { finish() },
                    onRequestGoogleSignIn = {
                        try {
                            val googleAccount = GoogleAccountManager(this@SettingsActivity)
                            googleSignInLauncher.launch(googleAccount.getSignInIntent())
                        } catch (e: Exception) {
                            // Bez tego komunikatu kliknięcie "Połącz konto Google"
                            // na urządzeniu bez Usług Play po prostu nic nie robiło.
                            android.util.Log.e("SettingsActivity", "Google Sign-In failed", e)
                            toast("Nie udało się otworzyć logowania Google: ${e.message}")
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRequestGoogleSignIn: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Komunikaty z ustawień (wynik testu połączenia, zapis klucza, test głosu...) były
    // renderowane WYŁĄCZNIE w karcie na samym dole tego bardzo długiego ekranu, więc
    // praktycznie nikt ich nie widział - po kliknięciu "Testuj połączenie" wyglądało to
    // jak samo odświeżenie ekranu. Pasek na dole pokazuje je niezależnie od przewinięcia.
    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // === USTAWIENIA POGRUPOWANE, NIE JEDEN CIĄG ===
            //
            // Wcześniej było tu dwadzieścia sekcji jedna pod drugą, rozdzielonych
            // wyłącznie kreską - i w kolejności, która wzięła się z historii
            // dopisywania, a nie z sensu. Dwie sekcje o aparacie dzieliło
            // dziesięć pozycji, a dwie o frazie wybudzenia leżały na dwóch
            // końcach ekranu. Zgłoszone wprost: "nie ma oddzielnych sekcji do
            // różnych typów ustawień, tylko wszystko jedno pod drugim".
            //
            // Grupy są zwinięte poza pierwszą: model i klucze to jedyna rzecz,
            // bez której aplikacja nie działa, więc ona jedna jest otwarta od
            // razu. Reszta czeka, aż ktoś jej poszuka - i teraz da się jej
            // szukać po nazwie kategorii zamiast przewijaniem.
            SettingsGroup(
                title = "Model AI i klucze",
                subtitle = "Dostawca, model, klucze API, styl odpowiedzi",
                initiallyExpanded = true
            ) {
                ProviderSection(
                    currentProviderId = state.activeProviderId,
                    providers = AIProviderFactory.supportedProviders(),
                    onProviderSelected = { viewModel.setActiveProvider(it) }
                )
                HorizontalDivider()
                ModelSection(
                    providerId = state.activeProviderId,
                    selectedModelId = state.selectedModelId,
                    onModelSelected = { viewModel.setSelectedModel(it) }
                )
                HorizontalDivider()
                ProviderKeysSection(
                    providers = AIProviderFactory.supportedProviders(),
                    getKey = { viewModel.getApiKey(it) },
                    onKeyChange = { id, key -> viewModel.setApiKey(id, key) },
                    isTestRunning = state.isTestRunning,
                    onTestClick = { viewModel.testConnection() },
                    onTestProvider = { viewModel.testConnection(it) }
                )
                HorizontalDivider()
                LocalModelSection()
                HorizontalDivider()
                AIOptionsSection(
                    webSearchEnabled = state.webSearchEnabled,
                    onWebSearchChange = { viewModel.setWebSearchEnabled(it) },
                    responseLanguage = state.responseLanguage,
                    onLanguageChange = { viewModel.setResponseLanguage(it) }
                )
                HorizontalDivider()
                PersonaSection(
                    selectedPersonaId = state.selectedPersonaId,
                    customPrompt = state.customPersonaPrompt,
                    onPersonaSelected = { viewModel.setPersona(it) },
                    onCustomPromptChange = { viewModel.setCustomPersonaPrompt(it) }
                )
            }

            SettingsGroup(
                title = "Mowa i głos",
                subtitle = "Silnik mowy, głos asystenta, rozpoznawanie mowy"
            ) {
                // Silnik PRZED głosami, bo to on decyduje, jakie głosy są
                // w ogóle dostępne.
                TtsEngineSection()
                HorizontalDivider()
                VoiceSection(
                    voices = state.availableVoices,
                    currentVoice = state.currentVoice,
                    speechRate = state.ttsSpeechRate,
                    pitch = state.ttsPitch,
                    onVoiceSelected = { viewModel.setTtsVoice(it) },
                    onRateChange = { viewModel.setTtsRate(it) },
                    onPitchChange = { viewModel.setTtsPitch(it) },
                    onTestClick = { viewModel.testVoice() }
                )
                VoiceInstallGuideSection()
                HorizontalDivider()
                SpeechSection()
            }

            SettingsGroup(
                title = "Wybudzanie",
                subtitle = "Fraza, która budzi asystenta, i silnik jej wykrywania"
            ) {
                // Te dwie sekcje leżały na dwóch końcach ekranu, choć jedna
                // jest ustawieniem drugiej.
                WakeEngineSection()
                HorizontalDivider()
                WakeWordSection(
                    enabled = state.wakeWordEnabled,
                    selectedId = state.wakeWordId,
                    customPhrase = state.customWakeWord,
                    keywordPath = state.customKeywordPath,
                    modelPath = state.customModelPath,
                    picovoiceAccessKey = state.picovoiceAccessKey,
                    onEnabledChange = { viewModel.setWakeWordEnabled(it) },
                    onWakeWordSelected = { viewModel.setWakeWordId(it) },
                    onCustomPhraseChange = { viewModel.setCustomWakeWord(it) },
                    onPicovoiceKeyChange = { viewModel.setPicovoiceAccessKey(it) },
                    onKeywordPathChange = { viewModel.setCustomKeywordPath(it) },
                    onModelPathChange = { viewModel.setCustomModelPath(it) }
                )
            }

            SettingsGroup(
                title = "Aparat i zdjęcia",
                subtitle = "Ile zdjęć, jak często, w jakiej rozdzielczości"
            ) {
                // Obie sekcje o aparacie razem - dotąd dzieliło je dziesięć
                // innych pozycji.
                CaptureSection(
                    count = state.captureCount,
                    intervalMs = state.captureIntervalMs,
                    onCountChange = { viewModel.setCaptureCount(it) },
                    onIntervalChange = { viewModel.setCaptureInterval(it) }
                )
                HorizontalDivider()
                CaptureModeSection()
            }

            SettingsGroup(
                title = "Funkcje asystenta",
                subtitle = "Komendy, kalendarz i poczta, alerty, dostępność"
            ) {
                ActionsSection()
                HorizontalDivider()
                IntelligenceSection(
                    onManageGoogleAccount = { onRequestGoogleSignIn() }
                )
                HorizontalDivider()
                ProactiveAlertsSection()
                DailyBriefingSection()
                HorizontalDivider()
                AccessibilitySection()
            }

            SettingsGroup(
                title = "Diagnostyka i konfiguracja",
                subtitle = "Dziennik, tryb symulacji, ponowny onboarding"
            ) {
                DiagnosticsLogSection()
                HorizontalDivider()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "🧪 Narzędzia diagnostyczne",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "Test ML Kit Barcode Scanner - wybierz zdjęcie z kodem QR z galerii.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.size(8.dp))
                        // LocalContext.current da się odczytać tylko w kontekście
                        // composable - nie wewnątrz lambdy onClick.
                        val qrTestContext = LocalContext.current
                        TextButton(
                            onClick = {
                                qrTestContext.startActivity(
                                    android.content.Intent(
                                        qrTestContext,
                                        pl.victor.app.vision.QRTestActivity::class.java
                                    )
                                )
                            }
                        ) {
                            Text("🔍 Test skanera QR")
                        }

                        Spacer(Modifier.size(4.dp))
                        Text(
                            "Diagnostyka okularów: stan połączenia, surowe ramki notify i tryb " +
                                "symulacji, który pozwala przejść całą ścieżkę bez sprzętu.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.size(8.dp))
                        TextButton(
                            onClick = {
                                qrTestContext.startActivity(
                                    android.content.Intent(
                                        qrTestContext,
                                        pl.victor.app.ui.diagnostics.DiagnosticsActivity::class.java
                                    )
                                )
                            }
                        ) {
                            Text("🕶️ Diagnostyka okularów")
                        }
                    }
                }
                HorizontalDivider()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "🔄 Konfiguracja",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "Restartuj onboarding (dla siebie lub kogoś nowego)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.size(8.dp))
                        TextButton(
                            onClick = {
                                pl.victor.app.VictorApplication.get().settings.resetOnboarding()
                                val intent = android.content.Intent(
                                    context,
                                    pl.victor.app.ui.onboarding.OnboardingActivity::class.java
                                )
                                context.startActivity(intent)
                                (context as? android.app.Activity)?.finish()
                            }
                        ) {
                            Text("🚀 Restartuj onboarding")
                        }
                    }
                }
            }

            // Status message
            state.statusMessage?.let { msg ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(16.dp),
                        color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.size(24.dp))
            DeveloperOptionsGate()
        }
    }
}

/**
 * Składana grupa ustawień.
 *
 * ## Po co
 * Ekran ustawień miał dwadzieścia sekcji jedna pod drugą, rozdzielonych samą
 * kreską. Żeby dojść do frazy wybudzenia, trzeba było przewinąć obok kluczy
 * API, głosów, person i alertów - a kolejność wzięła się z historii
 * dopisywania, nie z sensu. Zgłoszone wprost: "nie ma oddzielnych sekcji do
 * różnych typów ustawień".
 *
 * ## Dlaczego zwinięte, a nie tylko nagłówki
 * Bo same nagłówki nie skracają przewijania - a to ono jest tu problemem.
 * Zwinięta grupa zajmuje jeden wiersz, więc CAŁA mapa ustawień mieści się na
 * jednym ekranie i wybór jest jednym dotknięciem zamiast szukaniem wzrokiem.
 *
 * Wzór zwijania jest ten sam co w [pl.victor.app.ui.components.CapabilitiesPanel] -
 * jedna konwencja na całą aplikację, nie dwie.
 */
@Composable
private fun SettingsGroup(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Cały wiersz jest celem dotyku, nie sam trójkąt: 48 dp to
                    // minimum, którego wymaga Android, a nagłówek grupy jest
                    // elementem, w który trafia się bez patrzenia.
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    if (expanded) "\u25B2" else "\u25BC",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            AnimatedVisibility(
                visible = expanded,
                // Wejście wolniej i miękko, wyjście szybciej i ostro.
                //
                // Reguły ze skilla "animate" (delphi-ai, na podstawie kursu
                // Emila Kowalskiego): element wchodzący dostaje ease-out i
                // 200-300 ms, wychodzący ease-in i mniej więcej trzy czwarte
                // tego czasu. Powód jest praktyczny: na wyjście nikt nie patrzy,
                // a czekanie na nie jest czystym opóźnieniem.
                //
                // Krzywe są dokładnie te z jego tabeli, przepisane na
                // CubicBezierEasing - w Compose nie ma zmiennych CSS, ale
                // liczby są te same.
                enter = fadeIn(tween(Motion.ENTER_MS, easing = Motion.EaseOutCubic)) +
                    expandVertically(tween(Motion.ENTER_MS, easing = Motion.EaseOutCubic)),
                exit = fadeOut(tween(Motion.EXIT_MS, easing = Motion.EaseInCubic)) +
                    shrinkVertically(tween(Motion.EXIT_MS, easing = Motion.EaseInCubic))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.size(8.dp))
                    content()
                }
            }
        }
    }
}

/**
 * Ukryta furtka do "Opcji programistycznych" - stuknij numer wersji
 * [TAPS_TO_UNLOCK] razy, tak jak w Androidowym "Numer kompilacji".
 *
 * Wolno stukać z przerwami do 1.5 s - dłuższa pauza resetuje licznik, żeby
 * przypadkowe pojedyncze stuknięcia (np. przy scrollowaniu) nic nie odblokowały.
 */
@Composable
private fun DeveloperOptionsGate() {
    val context = LocalContext.current
    var tapCount by remember { mutableStateOf(0) }
    var lastTapAtMs by remember { mutableStateOf(0L) }

    Text(
        "V.I.C.T.O.R. ${pl.victor.app.BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .clickable {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapAtMs > TAP_RESET_WINDOW_MS) 1 else tapCount + 1
                lastTapAtMs = now
                when {
                    tapCount >= TAPS_TO_UNLOCK -> {
                        tapCount = 0
                        context.startActivity(
                            android.content.Intent(
                                context,
                                pl.victor.app.ui.developer.DeveloperOptionsActivity::class.java
                            )
                        )
                    }
                    tapCount >= TAPS_TO_UNLOCK - 3 -> {
                        android.widget.Toast.makeText(
                            context,
                            "Jeszcze ${TAPS_TO_UNLOCK - tapCount} stuknięć do Opcji programistycznych",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
    )
}

private const val TAPS_TO_UNLOCK = 7
private const val TAP_RESET_WINDOW_MS = 1_500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSection(
    providerId: String,
    selectedModelId: String?,
    onModelSelected: (String?) -> Unit
) {
    if (providerId == pl.victor.app.ai.AIProviderFactory.LOCAL_PROVIDER_ID) {
        // Model lokalny ma dziś jeden wpis w katalogu, nie listę wersji do
        // wyboru jak providerzy chmurowi - wybór modelu/pobieranie jest
        // niżej, w LocalModelSection.
        return
    }
    // Lista modeli przychodzi Z API PROVIDERA, nie z naszego katalogu.
    //
    // Wpisana na sztywno starzeje się w tygodniach: nazwy się zmieniają,
    // warianty znikają, nowe dochodzą - a użytkownik dowiaduje się o tym
    // dopiero z błędu przy pierwszym pytaniu. Katalog daje już tylko OPISY
    // (ładna nazwa, możliwości) dla modeli, które znamy; czym da się wybrać,
    // rozstrzyga odpowiedź API. Bez klucza albo bez sieci pokazujemy katalog,
    // bo pusty wybór jest gorszy niż trochę nieaktualny.
    val context = LocalContext.current
    val apiKey = remember(providerId) {
        (context.applicationContext as pl.victor.app.VictorApplication)
            .settings.getApiKey(providerId).orEmpty()
    }
    // SKĄD wzięła się ta lista - razem z nią, nie osobno.
    //
    // Zgłoszenie brzmiało: "pokazuje bardzo mało modeli, jakby się nie
    // aktualizowały". Nie dało się na nie odpowiedzieć, bo brak klucza,
    // nieudane pobranie i szczera odpowiedź API "mam dwa modele" dawały
    // DOKŁADNIE TEN SAM ekran. Teraz każda z tych sytuacji mówi o sobie sama.
    val state by produceState(
        initialValue = pl.victor.app.data.ModelCatalog.forPicker(providerId, emptyList()) to
            (pl.victor.app.data.ModelCatalog.Source.NoApiKey
                as pl.victor.app.data.ModelCatalog.Source),
        providerId,
        apiKey
    ) {
        if (apiKey.isBlank()) return@produceState
        val attempt = runCatching {
            pl.victor.app.data.RemoteModelValidator(apiKey, providerId).fetchAvailableModels()
        }
        val live = attempt.getOrDefault(emptyList())
        val source = when {
            attempt.isFailure ->
                pl.victor.app.data.ModelCatalog.Source.AskFailed(
                    attempt.exceptionOrNull()?.message?.take(60)
                )
            live.isEmpty() -> pl.victor.app.data.ModelCatalog.Source.ApiEmpty
            else -> pl.victor.app.data.ModelCatalog.Source.FromApi(live.size)
        }
        value = pl.victor.app.data.ModelCatalog.forPicker(providerId, live) to source
    }
    val models = state.first
    val modelsSource = state.second

    val selectedInfo = models.firstOrNull { it.id == selectedModelId }
        ?: selectedModelId?.let { pl.victor.app.data.ModelRegistry.findById(it) }
    val defaultInfo = models.firstOrNull()
        ?: pl.victor.app.data.ModelRegistry.defaultFor(providerId)
    val currentInfo = selectedInfo ?: defaultInfo

    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Model AI", style = MaterialTheme.typography.titleMedium)
        Text(
            modelsSource.message(),
            style = MaterialTheme.typography.labelSmall,
            color = if (modelsSource is pl.victor.app.data.ModelCatalog.Source.FromApi) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            }
        )

        // Bieżący model z opisem
        currentInfo?.let { info ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = if (info.deprecated) {
                    androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                } else {
                    androidx.compose.material3.CardDefaults.cardColors()
                }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            info.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (info.deprecated) {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "PRZESTARZAŁY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        info.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    info.releaseDate?.let {
                        Text(
                            "Wydany: $it" + (info.contextWindow?.let { cw -> " · $cw tokenów" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (info.deprecated && info.replacementId != null) {
                        val replacement = pl.victor.app.data.ModelRegistry.findById(info.replacementId)
                        Text(
                            "→ Następca: ${replacement?.displayName ?: info.replacementId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Dropdown
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = currentInfo?.displayName ?: "Domyślny",
                onValueChange = {},
                readOnly = true,
                label = { Text("Wybierz model") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { Text("▼", modifier = Modifier.padding(8.dp)) }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                // Opcja "Domyślny" na górze
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Domyślny", fontWeight = FontWeight.Bold)
                            Text(
                                "Użyj ${defaultInfo?.displayName ?: "—" }",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    onClick = {
                        onModelSelected(null)
                        expanded = false
                    }
                )
                HorizontalDivider()
                models.forEach { model ->
                    ModelDropdownItem(
                        model = model,
                        isSelected = (selectedModelId ?: defaultInfo?.id) == model.id,
                        onClick = {
                            onModelSelected(model.id)
                            expanded = false
                        }
                    )
                }
            }
        }

        Text(
            "Model jest sprawdzany przy każdym użyciu. Jeśli provider wycofa wybrany model, " +
                    "apka automatycznie przejdzie na nowy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModelDropdownItem(
    model: ModelInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.displayName,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    if (model.deprecated) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "deprecated",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (model.releaseDate != null) {
                    Text(
                        model.releaseDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        onClick = onClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSection(
    currentProviderId: String,
    providers: List<ProviderInfo>,
    onProviderSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = providers.find { it.id == currentProviderId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Provider AI", style = MaterialTheme.typography.titleMedium)

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = current?.displayName ?: "Wybierz providera",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = {
                    Text("▼", modifier = Modifier.padding(8.dp))
                }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                providers.forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(provider.displayName)
                                Text(
                                    provider.description,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (!provider.available) {
                                    Text(
                                        "W przygotowaniu",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        onClick = {
                            if (provider.available) {
                                onProviderSelected(provider.id)
                            }
                            expanded = false
                        }
                    )
                }
            }
        }

        current?.let { provider ->
            // LocalContext.current da się odczytać tylko w kontekście composable,
            // nie wewnątrz lambdy onClick.
            val browserContext = LocalContext.current
            TextButton(
                onClick = {
                    // Przycisk był pusty (TODO), więc nie prowadził nigdzie.
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(provider.keyUrl)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { browserContext.startActivity(intent) }
                        .onFailure {
                            // Urządzenie bez przeglądarki - lepiej nic niż wywrotka.
                            android.util.Log.w(
                                "SettingsActivity",
                                "Nie udało się otworzyć ${provider.keyUrl}",
                                it
                            )
                        }
                }
            ) {
                Text("Pobierz klucz API →")
            }
        }
    }
}

@Composable
private fun ProviderKeysSection(
    providers: List<ProviderInfo>,
    getKey: (String) -> String,
    onKeyChange: (String, String) -> Unit,
    isTestRunning: Boolean,
    onTestClick: () -> Unit,
    onTestProvider: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Klucze API", style = MaterialTheme.typography.titleMedium)
        Text(
            "Klucze są szyfrowane lokalnie. Aplikacja nigdy ich nie wysyła na zewnątrz.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Model lokalny nie ma klucza API - jego karta jest niżej, w LocalModelSection.
        providers.filter { it.id != pl.victor.app.ai.AIProviderFactory.LOCAL_PROVIDER_ID }.forEach { provider ->
            var keyValue by remember(provider.id) {
                mutableStateOf(getKey(provider.id))
            }
            var saved by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = keyValue,
                onValueChange = {
                    keyValue = it
                    saved = false
                },
                label = { Text(provider.displayName) },
                placeholder = { Text("Wklej klucz API") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                enabled = provider.available,
                supportingText = {
                    if (!provider.available) {
                        Text("Ten provider nie jest jeszcze dostępny")
                    }
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onKeyChange(provider.id, keyValue)
                        saved = true
                    },
                    enabled = keyValue.isNotBlank()
                ) {
                    Text(if (saved) "Zapisano ✓" else "Zapisz")
                }
                // Sprawdzenie DOKŁADNIE tego klucza, obok pola, w którym się go
                // wkleja. Przycisk niżej testuje aktywnego providera - a klucz
                // wkleja się zwykle temu, którego się dopiero zamierza włączyć,
                // więc tamten test mówił o czymś innym niż to, co się właśnie
                // zrobiło.
                OutlinedButton(
                    onClick = {
                        onKeyChange(provider.id, keyValue)
                        saved = true
                        onTestProvider(provider.id)
                    },
                    enabled = keyValue.isNotBlank() && !isTestRunning && provider.available
                ) {
                    Text("Sprawdź klucz")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onTestClick,
            enabled = !isTestRunning
        ) {
            if (isTestRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp
                )
            }
            Text("Testuj połączenie z aktywnym providerem")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AIOptionsSection(
    webSearchEnabled: Boolean,
    onWebSearchChange: (Boolean) -> Unit,
    responseLanguage: String,
    onLanguageChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Opcje AI", style = MaterialTheme.typography.titleMedium)

        // ZUŻYCIE DZISIAJ - u samej góry, bo to jest odpowiedź na pytanie, z
        // którym się tu wchodzi przy koncie przedpłaconym. Liczba szła dotąd
        // wyłącznie do dziennika diagnostycznego, czyli do pliku, który trzeba
        // najpierw komuś wysłać.
        run {
            val ctx = LocalContext.current
            val meter = remember {
                (ctx.applicationContext as pl.victor.app.VictorApplication).usage
            }
            val day by meter.today.collectAsState()
            Text(
                if (day.requests == 0) {
                    "Dzisiaj: jeszcze żadnego zapytania do modelu."
                } else {
                    "Dzisiaj: ${day.tokens} tokenów w ${day.requests} zapytaniach."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Wyszukiwanie w sieci")
                Text(
                    "AI może szukać aktualnych informacji (Gemini grounding)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = webSearchEnabled, onCheckedChange = onWebSearchChange)
        }

        // OSZCZĘDZANIE NA MYŚLENIU - świadome, nie domyślne.
        //
        // Stan czytamy prosto z ustawień, jak przy wyborze modelu niżej: to
        // przełącznik dotyczący JEDNEGO providera i przeciąganie go przez cały
        // stan ekranu dołożyłoby więcej niż warte.
        run {
            val ctx = LocalContext.current
            val repo = remember {
                (ctx.applicationContext as pl.victor.app.VictorApplication).settings
            }
            var limitThinking by remember { mutableStateOf(repo.isThinkingLimitEnabled()) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Oszczędzaj na myśleniu modelu (Gemini)")
                    Text(
                        "Model rozmyśla przed odpowiedzią i to zużywa cztery do " +
                            "ośmiu razy więcej niż sama odpowiedź - zmierzone na " +
                            "Twoich turach. Wyłączenie myślenia wyraźnie tnie " +
                            "rachunek, ale POGARSZA rozumowanie przy trudniejszych " +
                            "pytaniach. Gdy API nie przyjmie tej prośby, aplikacja " +
                            "sama wróci do zwykłego trybu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = limitThinking,
                    onCheckedChange = {
                        limitThinking = it
                        repo.setThinkingLimitEnabled(it)
                    }
                )
            }
        }

        // PROWADZENIE DO CELU: Z ASYSTENTEM CZY BEZ.
        //
        // Stan czytamy prosto z ustawień, tak samo jak przełącznik wyżej.
        run {
            val ctx = LocalContext.current
            val repo = remember {
                (ctx.applicationContext as pl.victor.app.VictorApplication).settings
            }
            var routeAssist by remember { mutableStateOf(repo.isRouteAssistEnabled()) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ostrzegaj o przeszkodach podczas prowadzenia")
                    Text(
                        "Gdy mówisz \"prowadź do...\", wskazówki trasy liczą i mówią " +
                            "mapy i nic to nie kosztuje. Ta opcja dokłada do tego " +
                            "ostrzeganie o przeszkodach z kamery okularów - pomaga, " +
                            "ale pyta model przez całą drogę i zużywa tokeny. " +
                            "Niezależnie od tego ustawienia możesz powiedzieć " +
                            "\"prowadź do apteki z asystentem\" albo \"bez asystenta\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = routeAssist,
                    onCheckedChange = {
                        routeAssist = it
                        repo.setRouteAssistEnabled(it)
                    }
                )
            }
        }

        // Język - uproszczone (tylko kilka opcji)
        var langExpanded by remember { mutableStateOf(false) }
        val languages = listOf(
            "pl" to "Polski",
            "en" to "English",
            "de" to "Deutsch",
            "fr" to "Français",
            "es" to "Español"
        )
        val current = languages.find { it.first == responseLanguage }?.second ?: "Polski"

        ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = !langExpanded }
        ) {
            OutlinedTextField(
                value = current,
                onValueChange = {},
                readOnly = true,
                label = { Text("Język odpowiedzi") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                languages.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        onLanguageChange(code)
                        langExpanded = false
                    })
                }
            }
        }
    }
}

@Composable
private fun CaptureSection(
    count: Int,
    intervalMs: Long,
    onCountChange: (Int) -> Unit,
    onIntervalChange: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Przechwytywanie", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = count.toString(),
                onValueChange = { onCountChange(it.toIntOrNull() ?: count) },
                label = { Text("Liczba zdjęć") },
                modifier = Modifier.weight(1f),
                supportingText = { Text("Domyślnie: 5") }
            )

            OutlinedTextField(
                value = intervalMs.toString(),
                onValueChange = { onIntervalChange(it.toLongOrNull() ?: intervalMs) },
                label = { Text("Interwał (ms)") },
                modifier = Modifier.weight(1f),
                supportingText = { Text("Domyślnie: 2000") }
            )
        }

        Text(
            "5 zdjęć co 1s = 5s. HeyCyan nie ma live stream, więc to nie jest prawdziwe 'live view', ale AI widzi kontekst.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Wybór silnika wykrywania frazy wybudzenia.
 *
 * ## Dlaczego wybór, a nie jeden silnik
 * Picovoice działa lepiej i taniej energetycznie, ale wymaga konta, a własna
 * fraza - płatnego planu. Vosk nie wymaga niczego (Apache 2.0, bez klucza),
 * kosztem baterii i pobrania modelu na telefon. To jest realny kompromis, więc
 * decyzja należy do użytkownika, a nie do nas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeEngineSection() {
    val context = LocalContext.current
    val app = remember { context.applicationContext as pl.victor.app.VictorApplication }
    val settings = remember { app.settings }
    val vosk = remember { app.voskWakeWord }
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf(settings.getWakeEngine()) }
    var phrase by remember { mutableStateOf(settings.getVoskPhrase()) }
    var url by remember { mutableStateOf(settings.getVoskModelUrl()) }
    var modelReady by remember { mutableStateOf(vosk.isModelReady()) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Wykrywanie frazy wybudzenia", style = MaterialTheme.typography.titleMedium)

        listOf(
            pl.victor.app.data.SettingsRepository.WAKE_ENGINE_PICOVOICE to
                "Picovoice - lżejszy dla baterii, wymaga klucza (własna fraza: plan płatny)",
            pl.victor.app.data.SettingsRepository.WAKE_ENGINE_VOSK to
                "Vosk - bez konta i klucza, dowolna fraza po polsku, więcej baterii"
        ).forEach { (id, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        engine = id
                        settings.setWakeEngine(id)
                        app.refreshVosk()
                    }
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(selected = engine == id, onClick = {
                    engine = id
                    settings.setWakeEngine(id)
                    app.refreshVosk()
                })
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        if (engine == pl.victor.app.data.SettingsRepository.WAKE_ENGINE_VOSK) {
            OutlinedTextField(
                value = phrase,
                onValueChange = {
                    phrase = it
                    settings.setVoskPhrase(it)
                },
                label = { Text("Fraza wybudzenia") },
                supportingText = {
                    Text(
                        "Pisz tak, jak model ją usłyszy - po polsku. \"Hej Wiktor\", " +
                            "nie \"Hey Victor\": model jest polski i angielskiej " +
                            "pisowni nie zna."
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    settings.setVoskModelUrl(it)
                },
                label = { Text("Adres modelu") },
                supportingText = {
                    Text(
                        "Nazwy plików modeli zmieniają się z wersjami. Gdy pobieranie " +
                            "zwróci 404, weź aktualny adres ze strony modeli Voska i " +
                            "wklej tutaj."
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Text(
                if (modelReady) "✅ Model jest na telefonie." else "⚠ Model nie jest pobrany.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        busy = true
                        status = "Pobieram model (kilkadziesiąt MB, najlepiej przez Wi-Fi)..."
                        scope.launch {
                            val error = vosk.downloadModel(url) { progress = it }
                            modelReady = vosk.isModelReady()
                            if (error == null) app.refreshVosk()
                            status = error ?: "Model gotowy. Włącz wybudzanie na ekranie głównym."
                            progress = null
                            busy = false
                        }
                    },
                    enabled = !busy
                ) {
                    Text(if (modelReady) "Pobierz ponownie" else "Pobierz model")
                }
                if (busy) {
                    val p = progress
                    if (p != null) {
                        Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    }
                }
            }

            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * Rozpoznawanie mowy i to, czym nasłuchujemy frazy.
 *
 * Obie rzeczy dotyczą mikrofonu i obie mają koszt, który trzeba nazwać wprost:
 * jedna wysyła nagranie poza telefon, druga zabiera okularom tryb multimediów.
 */
@Composable
private fun SpeechSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as pl.victor.app.VictorApplication).settings }
    var cloud by remember { mutableStateOf(settings.isCloudTranscriptionEnabled()) }
    var glassesMic by remember { mutableStateOf(settings.isWakeWordOverGlassesMic()) }
    val hasOpenAiKey = remember { settings.hasApiKey("openai") }
    val app = context.applicationContext as pl.victor.app.VictorApplication
    val onDeviceReady = remember {
        runCatching { pl.victor.app.conversation.SpeechToText(context).isOnDeviceAvailable() }
            .getOrDefault(false)
    }
    val lastSource by app.orchestrator.lastTranscriptionSource.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("🎤 Rozpoznawanie mowy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("☁️ Transkrypcja w chmurze", fontWeight = FontWeight.Medium)
                    Text(
                        "Znacznie dokładniejsza po polsku niż rozpoznawanie w telefonie " +
                            "i działa przy zablokowanym ekranie. Wysyła nagranie pytania " +
                            "do OpenAI - używa klucza, który masz już w ustawieniach.\n\n" +
                            "Dotyczy pytań zadawanych PRZEZ OKULARY (telefon w kieszeni " +
                            "słyszy gorzej). Pytania zadane do telefonu idą jak dawniej i " +
                            "nic nie kosztują. Rozliczenie jest za czas nagrania, nie za " +
                            "tokeny - kilkusekundowe pytanie to ułamek grosza, znacznie " +
                            "mniej niż odpowiedź modelu, za którą i tak płacisz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!hasOpenAiKey) {
                        Text(
                            "⚠️ Brak klucza OpenAI - nic nie jest wysyłane. Pytanie " +
                                "z okularów i tak jest przepisywane z ICH mikrofonu, " +
                                "rozpoznawaniem offline poniżej - to nadal lepiej niż " +
                                "nasłuch telefonu z kieszeni.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Switch(
                    checked = cloud,
                    onCheckedChange = { cloud = it; settings.setCloudTranscriptionEnabled(it) }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // NAJLEPSZA DROGA LOKALNA JEST JUŻ W TELEFONIE - tylko trzeba ją
            // pobrać. To ten sam silnik, którym dyktuje się na klawiaturze bez
            // sieci: dla polszczyzny nieporównanie lepszy od Voska, darmowy i
            // działa przy zablokowanym ekranie. Aplikacja korzystała z niego od
            // dawna, ale gdy pakietu brakowało, po cichu schodziła niżej - i nikt
            // nie wiedział, że jednym pobraniem można mieć to za darmo.
            Text("📴 Rozpoznawanie bez internetu", fontWeight = FontWeight.Medium)
            if (onDeviceReady) {
                Text(
                    "✅ Dostępne na tym telefonie. To najlepsza darmowa droga: ten sam " +
                        "silnik co dyktowanie na klawiaturze offline, działa też przy " +
                        "zablokowanym ekranie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "❌ Brak pakietu języka polskiego. Bez niego zostaje Vosk, który " +
                        "myli słowa. Pobranie pakietu jest darmowe i jednorazowe - " +
                        "w ustawieniach systemu wejdź w rozpoznawanie mowy Google i " +
                        "pobierz język polski do użytku offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.size(6.dp))
                OutlinedButton(onClick = {
                    // Ekran wprowadzania głosowego. Producenci trzymają go w
                    // różnych miejscach, więc gdy tej intencji nie ma, otwieramy
                    // ogólne ustawienia - lepsze niż nic się nie dzieje.
                    val opened = runCatching {
                        context.startActivity(
                            android.content.Intent("android.settings.VOICE_INPUT_SETTINGS")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        true
                    }.getOrDefault(false)
                    if (!opened) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }) { Text("Otwórz ustawienia głosu") }
            }

            lastSource?.let { source ->
                Spacer(Modifier.size(10.dp))
                Text("Ostatnie pytanie rozpoznała: $source", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Dróg jest pięć i z zewnątrz wyglądają tak samo. Gdy asystent " +
                        "odpowie nie na temat, to pole mówi, gdzie szukać przyczyny.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🎧 Fraza przez mikrofon okularów", fontWeight = FontWeight.Medium)
                    Text(
                        "Wyłączone: fraza łapana mikrofonem telefonu. Włączenie trzyma " +
                            "okulary w trybie ROZMOWY przez cały czas - Android pokazuje " +
                            "je wtedy jako urządzenie do połączeń, nie do multimediów, i " +
                            "nie posłuchasz przez nie muzyki. Wybudzanie samymi okularami " +
                            "działa niezależnie od tego przełącznika.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = glassesMic,
                    onCheckedChange = { glassesMic = it; settings.setWakeWordOverGlassesMic(it) }
                )
            }
        }
    }
}

/**
 * Wybór silnika mowy.
 *
 * ## Dlaczego to jest osobna sekcja, a nie ustawienie systemowe
 * Bo aplikacja brała dotąd silnik DOMYŚLNY systemu, a na telefonach Samsunga
 * jest nim silnik Samsunga: jeden polski głos i ani jednego angielskiego.
 * Zgłoszone jako "nie da się wgrać głosów z Google TTS i mam tylko jeden głos
 * kobiecy". Zmiana domyślnego silnika w ustawieniach Androida bywa schowana
 * albo zablokowana przez producenta - a tutaj wystarczy jedno kliknięcie i
 * dotyczy tylko tej aplikacji.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsEngineSection() {
    val context = LocalContext.current
    val app = remember { context.applicationContext as pl.victor.app.VictorApplication }
    val settings = remember { app.settings }
    val engines = remember { app.audio.availableEngines() }
    var selected by remember { mutableStateOf(settings.getTtsEngine()) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Silnik mowy", style = MaterialTheme.typography.titleMedium)
        Text(
            "To silnik decyduje, jakie głosy są dostępne - i czy w ogóle jest " +
                "jakiś angielski. Bez angielskiego głosu angielskie wtręty będą " +
                "czytane po polsku, tak jak się je pisze.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        if (engines.isEmpty()) {
            Text(
                "Nie widzę żadnego silnika mowy.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        engines.forEach { (packageName, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selected = packageName
                        settings.setTtsEngine(packageName)
                        app.audio.restartTts()
                    }
                    .padding(vertical = 6.dp)
            ) {
                RadioButton(
                    selected = selected == packageName,
                    onClick = {
                        selected = packageName
                        settings.setTtsEngine(packageName)
                        app.audio.restartTts()
                    }
                )
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                // Sklep, a nie instrukcja: Google TTS to zwykła aplikacja i
                // najczęściej wystarczy ją zainstalować.
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("market://details?id=com.google.android.tts")
                        )
                    )
                }.onFailure {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                "https://play.google.com/store/apps/details?id=com.google.android.tts"
                            )
                        )
                    )
                }
            }) {
                Text("Zainstaluj Google TTS")
            }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent("com.android.settings.TTS_SETTINGS")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) {
                Text("Ustawienia mowy")
            }
        }
    }
}

@Composable
private fun VoiceInstallGuideSection() {
    val audio = remember { pl.victor.app.VictorApplication.get().audio }
    var expanded by remember { mutableStateOf(false) }
    val polishOffline = audio.getPolishOfflineVoicesCount()
    val polishTotal = audio.getPolishVoicesCount()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📚 Jak pobrać więcej głosów",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Ukryj" else "Pokaż")
                    }
                }

                // Status
                Text(
                    when {
                        polishOffline >= 2 -> "✅ Masz ${polishOffline} polskie głosy offline - świetnie!"
                        polishOffline == 1 -> "✓ Masz 1 polski głos. Możesz pobrać więcej (żeńskie, inne akcenty)."
                        polishTotal == 0 -> "⚠ Brak polskich głosów. Poniżej instrukcja."
                        else -> "⚠ Polskie głosy wymagają pobrania. Poniżej instrukcja."
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                if (expanded) {
                    Spacer(Modifier.size(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.size(8.dp))

                    Text(
                        "Krok po kroku:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "1. Kliknij „Ustawienia TTS” poniżej",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "2. Wybierz „Google Text-to-speech Engine”",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "3. Kliknij ⚙ obok silnika → „Instaluj dane głosowe”",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "4. Wybierz język Polski (pl-PL)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "5. Pobierz wysokiej jakości głosy (WiFi zalecane)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "6. Wróć do tej apki - nowe głosy pojawią się w dropdown",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.size(12.dp))

                    Text(
                        "💡 Wskazówki:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "• Google TTS ma najlepszą jakość dla polskiego\n" +
                                "• Pobrane offline działają bez internetu\n" +
                                "• Jeden głos żeński + jeden męski to dobry start\n" +
                                "• Głosy „WaveNet\" są premium (wymagają konta Google)",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.size(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { audio.openTtsSettings() }) {
                            Text("⚙ Otwórz Ustawienia TTS")
                        }
                        TextButton(onClick = { audio.openGoogleTtsPlayStore() }) {
                            Text("📥 Google TTS (Play Store)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaSection(
    selectedPersonaId: String,
    customPrompt: String,
    onPersonaSelected: (String) -> Unit,
    onCustomPromptChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val personas = remember { pl.victor.app.persona.PersonaRegistry.all() }
    val currentPersona = personas.find { it.id == selectedPersonaId }
        ?: if (selectedPersonaId == "custom") pl.victor.app.persona.PersonaRegistry.customFromPrompt(customPrompt)
        else pl.victor.app.persona.PersonaRegistry.default()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Styl komunikacji AI", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(currentPersona.emoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        currentPersona.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        currentPersona.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Dropdown z personami
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = currentPersona.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Persona") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { Text("▼", modifier = Modifier.padding(8.dp)) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                personas.forEach { persona ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    persona.emoji,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Column {
                                    Text(
                                        persona.name,
                                        fontWeight = if (persona.id == selectedPersonaId)
                                            androidx.compose.ui.text.font.FontWeight.Bold
                                        else androidx.compose.ui.text.font.FontWeight.Normal
                                    )
                                    Text(
                                        persona.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            onPersonaSelected(persona.id)
                            expanded = false
                        }
                    )
                }
                HorizontalDivider()
                // Custom
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "✏️",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column {
                                Text(
                                    "Własna persona",
                                    fontWeight = if (selectedPersonaId == "custom")
                                        androidx.compose.ui.text.font.FontWeight.Bold
                                    else androidx.compose.ui.text.font.FontWeight.Normal
                                )
                                Text(
                                    "Wpisz własny system prompt",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        onPersonaSelected("custom")
                        expanded = false
                    }
                )
            }
        }

        // Pole custom (widoczne tylko gdy custom)
        if (selectedPersonaId == "custom") {
            Text(
                "Własny system prompt - instrukcja dla AI jak ma się zachowywać:",
                style = MaterialTheme.typography.labelMedium
            )
            OutlinedTextField(
                value = customPrompt,
                onValueChange = onCustomPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                placeholder = { Text("Np. Jesteś moim osobistym kucharzem. Odpowiadaj krótko, w punktach, z proporcjami na 2 osoby. Na końcu dodaj żart o jedzeniu.") },
                supportingText = { Text("Zostaw puste żeby użyć domyślnej persony") }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSection(
    voices: List<pl.victor.app.audio.VoiceInfo>,
    currentVoice: pl.victor.app.audio.VoiceInfo?,
    speechRate: Float,
    pitch: Float,
    onVoiceSelected: (String) -> Unit,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onTestClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val audio = remember { pl.victor.app.VictorApplication.get().audio }

    val polishTotal = voices.count { it.isPolish }
    val polishOffline = voices.count { it.isPolish && it.isInstalledOffline }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Głos syntezy mowy", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onTestClick) {
                Text("🔊 Odsłuchaj")
            }
        }

        // === Karta statusu: ile głosów polskich jest dostępnych ===
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = when {
                    polishOffline > 0 -> MaterialTheme.colorScheme.primaryContainer
                    polishTotal > 0 -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    when {
                        polishOffline > 0 -> "✓ $polishOffline polskich głosów offline"
                        polishTotal > 0 -> "⚠ Polskie głosy wymagają pobrania"
                        else -> "✗ Brak polskich głosów"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    "Łącznie: ${voices.size} głosów w systemie",
                    style = MaterialTheme.typography.bodySmall
                )

                if (polishOffline == 0) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Polski głos offline jest potrzebny żeby działać bez internetu. " +
                                "Google TTS oferuje najlepszą jakość - kliknij poniżej:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { audio.openTtsSettings() }) {
                            Text("⚙ Ustawienia TTS")
                        }
                        TextButton(onClick = { audio.openGoogleTtsPlayStore() }) {
                            Text("📥 Google TTS")
                        }
                    }
                } else {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "💡 Więcej głosów (żeńskie, inne akcenty) → Ustawienia TTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (voices.isEmpty()) {
            Text(
                "Inicjalizuję TTS... Jeśli to nie zniknie, zrestartuj aplikację.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            // Dropdown z głosami
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = currentVoice?.displayName ?: "Wybierz głos",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Głos") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { Text("▼", modifier = Modifier.padding(8.dp)) }
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    voices.forEach { voice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            voice.displayName,
                                            fontWeight = if (voice.name == currentVoice?.name)
                                                androidx.compose.ui.text.font.FontWeight.Bold
                                            else androidx.compose.ui.text.font.FontWeight.Normal
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            voice.statusLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when {
                                                voice.isInstalledOffline && voice.isPolish ->
                                                    MaterialTheme.colorScheme.primary
                                                voice.requiresNetwork ->
                                                    MaterialTheme.colorScheme.error
                                                else ->
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            "${voice.quality} · ${voice.locale}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onVoiceSelected(voice.name)
                                expanded = false
                            }
                        )
                    }
                }
            }

            // Slider: prędkość
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Prędkość mówienia", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        String.format("%.1fx", speechRate),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                Slider(
                    value = speechRate,
                    onValueChange = onRateChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,  // 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Slider: wysokość
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wysokość głosu", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        String.format("%.1fx", pitch),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                Slider(
                    value = pitch,
                    onValueChange = onPitchChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                "Domyślne wartości: prędkość 1.0x, wysokość 1.0x. " +
                        "Polskie głosy Google TTS są najlepsze dla naszego języka.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Codzienny briefing - jedyna funkcja, która odzywa się bez powodu w danych.
 *
 * Dlatego każda sekcja da się wyłączyć osobno, a pole "na co zwracać uwagę"
 * przyjmuje własne słowa użytkownika. Briefing, którego nie da się przyciąć, po
 * tygodniu jest wyłączany na stałe.
 */
@Composable
private fun DailyBriefingSection() {
    val context = LocalContext.current
    val app = pl.victor.app.VictorApplication.get()
    var enabled by remember { mutableStateOf(app.settings.isBriefingEnabled()) }
    var hour by remember { mutableStateOf(app.settings.getBriefingHour()) }
    var minute by remember { mutableStateOf(app.settings.getBriefingMinute()) }
    var prefs by remember { mutableStateOf(app.settings.getBriefingPreferences()) }

    fun update(value: pl.victor.app.proactive.DailyBriefing.Preferences) {
        prefs = value
        app.settings.setBriefingPreferences(value)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("☀️ Codzienny briefing", fontWeight = FontWeight.Bold)
                    Text(
                        "Raz dziennie streszcza, co Cię czeka. Możesz też " +
                            "powiedzieć \"briefing\" o dowolnej porze.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        app.settings.setBriefingEnabled(it)
                        if (it) {
                            pl.victor.app.proactive.DailyBriefingScheduler.enable(context)
                        } else {
                            pl.victor.app.proactive.DailyBriefingScheduler.disable(context)
                        }
                    }
                )
            }

            if (!enabled) return@Column

            Spacer(Modifier.size(8.dp))
            Text("O której:", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hour.toString(),
                    onValueChange = { text ->
                        hour = text.filter { it.isDigit() }.take(2).toIntOrNull()
                            ?.coerceIn(0, 23) ?: 0
                        app.settings.setBriefingTime(hour, minute)
                    },
                    label = { Text("Godzina") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = minute.toString(),
                    onValueChange = { text ->
                        minute = text.filter { it.isDigit() }.take(2).toIntOrNull()
                            ?.coerceIn(0, 59) ?: 0
                        app.settings.setBriefingTime(hour, minute)
                    },
                    label = { Text("Minuta") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.size(8.dp))
            Text("Co ma zawierać:", style = MaterialTheme.typography.labelMedium)
            BriefingToggle("📅 Kalendarz", prefs.includeCalendar) {
                update(prefs.copy(includeCalendar = it))
            }
            BriefingToggle("🌤️ Pogoda", prefs.includeWeather) {
                update(prefs.copy(includeWeather = it))
            }
            BriefingToggle("📧 Poczta", prefs.includeMail) {
                update(prefs.copy(includeMail = it))
            }

            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = prefs.focus,
                onValueChange = { update(prefs.copy(focus = it)) },
                label = { Text("Na co zwracać uwagę") },
                placeholder = { Text("np. mów o korkach na trasie do pracy") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.size(8.dp))
            Text("Długość:", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pl.victor.app.proactive.DailyBriefing.Length.entries.forEach { length ->
                    if (prefs.length == length) {
                        Button(onClick = { update(prefs.copy(length = length)) }) {
                            Text(length.label, fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(onClick = { update(prefs.copy(length = length)) }) {
                            Text(length.label, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { app.orchestrator.runBriefing() }) {
                Text("Posłuchaj teraz")
            }
        }
    }
}

@Composable
private fun BriefingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProactiveAlertsSection() {
    val context = LocalContext.current
    val app = pl.victor.app.VictorApplication.get()
    var enabled by remember { mutableStateOf(app.settings.isProactiveAlertsEnabled()) }
    var alertsSpoken by remember { mutableStateOf(app.settings.isAlertsSpokenEnabled()) }
    var alertsWithoutGlasses by remember {
        mutableStateOf(app.settings.isAlertsSpokenWithoutGlasses())
    }
    var owmKey by remember { mutableStateOf(app.settings.getOpenWeatherApiKey()) }
    var location by remember { mutableStateOf(app.settings.getWeatherLocation()) }
    var hasCalendarPermission by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasCalendarPermission = results[android.Manifest.permission.READ_CALENDAR] == true
        hasNotificationPermission = results[android.Manifest.permission.POST_NOTIFICATIONS] == true
    }

    LaunchedEffect(Unit) {
        hasCalendarPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALENDAR
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasNotificationPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Proaktywne alerty (pogoda + kalendarz)", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (enabled)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🌦️ Alerty przed wyjściem",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            app.settings.setProactiveAlertsEnabled(it)
                            if (it) {
                                pl.victor.app.proactive.ProactiveAlertsScheduler.enable(
                                    context,
                                    app.settings.getProactiveIntervalMinutes()
                                )
                            } else {
                                pl.victor.app.proactive.ProactiveAlertsScheduler.disable(context)
                            }
                        }
                    )
                }

                Spacer(Modifier.size(4.dp))
                Text(
                    "V.I.C.T.O.R. sprawdza kalendarz i pogodę co 15 min. " +
                            "Jak masz spotkanie za 30-60 min i ma padać - dostaniesz " +
                            "powiadomienie: \"Weź parasol\".",
                    style = MaterialTheme.typography.bodySmall
                )

                if (enabled) {
                    Spacer(Modifier.size(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🔊 Mów alerty w okularach", fontWeight = FontWeight.Medium)
                            Text(
                                "Alert, po który trzeba wyjąć telefon, mija się z " +
                                    "sensem okularów. Nie przerywa rozmowy z " +
                                    "asystentem - powiadomienie i tak zostaje.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = alertsSpoken,
                            onCheckedChange = {
                                alertsSpoken = it
                                app.settings.setAlertsSpokenEnabled(it)
                            }
                        )
                    }

                    if (alertsSpoken) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("📱 Mów też bez okularów", fontWeight = FontWeight.Medium)
                                Text(
                                    "Domyślnie wyłączone - nagły głos z telefonu w " +
                                        "kieszeni bardziej zaskakuje, niż pomaga.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = alertsWithoutGlasses,
                                onCheckedChange = {
                                    alertsWithoutGlasses = it
                                    app.settings.setAlertsSpokenWithoutGlasses(it)
                                }
                            )
                        }
                    }
                }
            }
        }

        if (enabled) {
            // Uprawnienia
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Uprawnienia:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    PermissionRow(
                        "📅 READ_CALENDAR",
                        "Czytanie Twojego kalendarza",
                        hasCalendarPermission
                    )
                    PermissionRow(
                        "🔔 POST_NOTIFICATIONS",
                        "Wysyłanie powiadomień",
                        hasNotificationPermission
                    )

                    if (!hasCalendarPermission || !hasNotificationPermission) {
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = {
                                permissionLauncher.launch(arrayOf(
                                    android.Manifest.permission.READ_CALENDAR,
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                ))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🔓 Poproś o uprawnienia")
                        }
                    }
                }
            }

            // OpenWeatherMap key
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "OpenWeatherMap API Key",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Darmowy po rejestracji na https://openweathermap.org/api. " +
                                "Wklej API key - służy do pobierania prognozy pogody.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = owmKey,
                        onValueChange = {
                            owmKey = it
                            app.settings.setOpenWeatherApiKey(it)
                        },
                        label = { Text("API Key") },
                        placeholder = { Text("np. a1b2c3d4e5f6...") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Lokalizacja
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Lokalizacja",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Nazwa miasta lub współrzędne. Np. \"Warszawa,PL\", " +
                                "\"Kraków\", \"52.23,21.01\".",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = location,
                        onValueChange = {
                            location = it
                            app.settings.setWeatherLocation(it)
                        },
                        label = { Text("Miasto / współrzędne") },
                        placeholder = { Text("Warszawa,PL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Przykładowe alerty
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "💡 Przykłady alertów które dostaniesz:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("☂️ \"Będzie padać za 20 min, weź parasol\"", style = MaterialTheme.typography.bodySmall)
                    Text("⛈️ \"Ulewa za 10 min - weź taksówkę\"", style = MaterialTheme.typography.bodySmall)
                    Text("❄️ \"Śnieg, ubierz się ciepło\"", style = MaterialTheme.typography.bodySmall)
                    Text("💨 \"Silny wiatr, uważaj na parasol\"", style = MaterialTheme.typography.bodySmall)
                    Text("🥶 \"Mróz - czapka i rękawiczki obowiązkowe\"", style = MaterialTheme.typography.bodySmall)
                    Text("⏰ \"Spóźnisz się, wychodź natychmiast!\"", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Test - ręczne uruchomienie workera z realnym raportem wyniku.
            // Wcześniej to tylko wrzucało zadanie do kolejki i pokazywało "sprawdź za
            // chwilę" - a worker, jeśli nie znalazł nic wartego alertu, nie pokazywał
            // NICZEGO. Z perspektywy użytkownika test wisiał w nieskończoność.
            Spacer(Modifier.size(8.dp))
            var testStatus by remember { mutableStateOf<String?>(null) }
            val testScope = rememberCoroutineScope()
            TextButton(
                onClick = {
                    val request = androidx.work.OneTimeWorkRequestBuilder<
                        pl.victor.app.proactive.ProactiveAlertsWorker>().build()
                    val workManager = androidx.work.WorkManager.getInstance(context)
                    workManager.enqueue(request)
                    testStatus = "⏳ Sprawdzam pogodę i kalendarz..."
                    testScope.launch {
                        // Świadomie odpytujemy w pętli przez getWorkInfoById (API stabilne
                        // od dawna) zamiast nowszych wariantów z Flow - mniej ryzyka
                        // rozjazdu z wersją WorkManagera w tym projekcie.
                        val info = withContext(Dispatchers.IO) {
                            var current: androidx.work.WorkInfo? = null
                            var attempts = 0
                            while (attempts < 60 && current?.state?.isFinished != true) {
                                current = runCatching {
                                    workManager.getWorkInfoById(request.id).get()
                                }.getOrNull()
                                if (current?.state?.isFinished != true) delay(500)
                                attempts++
                            }
                            current
                        }
                        testStatus = when (info?.state) {
                            androidx.work.WorkInfo.State.SUCCEEDED ->
                                "✓ Sprawdzone. Powiadomienie pojawia się tylko wtedy, gdy " +
                                    "faktycznie jest o czym ostrzegać (deszcz/mróz przed " +
                                    "wyjściem, spóźnienie na spotkanie)."
                            androidx.work.WorkInfo.State.FAILED ->
                                "✗ Sprawdzanie nie powiodło się - najczęściej brak klucza " +
                                    "OpenWeather albo uprawnienia do kalendarza."
                            else -> "✗ Zadanie zakończyło się stanem: ${info?.state}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🧪 Testuj teraz")
            }
            testStatus?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActionsSection() {
    val context = LocalContext.current
    val audio = remember { pl.victor.app.VictorApplication.get().audio }
    val executor = remember { pl.victor.app.actions.ActionExecutor(context) }
    val detector = remember { pl.victor.app.actions.SmartActionDetector() }
    var installedApps by remember { mutableStateOf<List<pl.victor.app.actions.AppInfo>>(emptyList()) }
    val directExecutor = remember { pl.victor.app.actions.DirectActionExecutor(context) }
    var hasSmsPermission by remember { mutableStateOf(false) }
    var hasCallPermission by remember { mutableStateOf(false) }
    var hasContactsPermission by remember { mutableStateOf(false) }
    var actionMode by remember {
        mutableStateOf(pl.victor.app.VictorApplication.get().settings.getActionMode())
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasSmsPermission = results[android.Manifest.permission.SEND_SMS] == true
        hasCallPermission = results[android.Manifest.permission.CALL_PHONE] == true
        hasContactsPermission = results[android.Manifest.permission.READ_CONTACTS] == true
    }

    LaunchedEffect(Unit) {
        installedApps = executor.getInstalledApps()
        hasSmsPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasCallPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasContactsPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Akcje (komendy głosowe)", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "🎯 V.I.C.T.O.R. może wykonywać akcje w Twoim imieniu",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "Powiedz np.: „Wyślij SMS do Ani: cześć\", „Zadzwoń do mamy\", " +
                            "„Włącz muzykę Queen”, „Nawiguj do domu”, „Ustaw alarm na 7 rano”.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Bezpieczeństwo: Apka NIGDY nie robi nic bezpośrednio - otwiera " +
                            "odpowiednią apkę (Spotify, Dialer, Gmail...) i user potwierdza.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // === Tryb akcji: SAFE vs DIRECT ===
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (actionMode == "DIRECT")
                    MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Tryb wykonywania akcji",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = actionMode == "SAFE",
                        onClick = {
                            actionMode = "SAFE"
                            pl.victor.app.VictorApplication.get().settings.setActionMode("SAFE")
                        }
                    )
                    Spacer(Modifier.size(4.dp))
                    Column {
                        Text("🔒 Bezpieczny (Intent)", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            "Otwiera SMS/Dialer/Gmail. Bez uprawnień.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = actionMode == "DIRECT",
                        onClick = {
                            actionMode = "DIRECT"
                            pl.victor.app.VictorApplication.get().settings.setActionMode("DIRECT")
                        }
                    )
                    Spacer(Modifier.size(4.dp))
                    Column {
                        Text("⚡ Szybki (bezpośredni)", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            "Wysyła SMS / dzwoni po potwierdzeniu. Wymaga uprawnień.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Uprawnienia dla DIRECT
                if (actionMode == "DIRECT") {
                    Spacer(Modifier.size(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.size(8.dp))

                    Text(
                        "Uprawnienia dla trybu szybkiego:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))

                    PermissionRow(
                        "📱 SEND_SMS",
                        "Wysyłanie SMS bezpośrednio",
                        hasSmsPermission
                    )
                    PermissionRow(
                        "📞 CALL_PHONE",
                        "Dzwonienie bezpośrednio",
                        hasCallPermission
                    )
                    PermissionRow(
                        "👥 READ_CONTACTS",
                        "Rozpoznawanie imion kontaktów",
                        hasContactsPermission
                    )

                    Spacer(Modifier.size(8.dp))

                    val allGranted = hasSmsPermission && hasCallPermission && hasContactsPermission
                    if (!allGranted) {
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    pl.victor.app.actions.DirectActionExecutor.REQUIRED_PERMISSIONS
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🔓 Poproś o uprawnienia")
                        }
                    } else {
                        Text(
                            "✓ Wszystkie uprawnienia przyznane",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Po wykryciu akcji (np. \"zadzwoń do mamy\") pokaże się dialog: " +
                                "\"Czy zadzwonić?\". Wciśnij OK i dzwoni bezpośrednio.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Lista wspieranych akcji
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Wspierane akcje:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(4.dp))
                ActionListItem("📱", "SMS", "Wyślij SMS do kontaktu")
                ActionListItem("📞", "Telefon", "Zadzwoń do kogoś")
                ActionListItem("📧", "Email", "Wyślij maila (Gmail/inny)")
                ActionListItem("🎵", "Muzyka", "Włącz muzykę w Spotify / YouTube")
                ActionListItem("⏯", "Odtwarzacz", "Pauza / następny / poprzedni")
                ActionListItem("🗺", "Nawigacja", "Jedź do X (Google Maps)")
                ActionListItem("⏰", "Alarm", "Ustaw alarm (Clock app)")
                ActionListItem("⏱", "Timer", "Odliczanie")
                ActionListItem("🔍", "Szukaj", "Google search")
                ActionListItem("🌐", "Tłumacz", "Przetłumacz tekst")
                ActionListItem("📲", "Otwórz apkę", "Spotify, YouTube, Gmail...")
                ActionListItem("💡", "Latarka", "Włącz/wyłącz")
            }
        }

        // Test akcji
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "🧪 Test - wpisz komendę:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(8.dp))
                var testCommand by remember { mutableStateOf("") }
                var testResult by remember { mutableStateOf<String?>(null) }
                var listening by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val speechToText = remember { pl.victor.app.conversation.SpeechToText(context) }

                Text(
                    "Wynik pokazuje, KTÓRA WARSTWA obsłuży zdanie: 0 to odruch " +
                        "(natychmiast, offline), 2 to zapasowe wzorce, a wszystko " +
                        "inne rozumie AI - i to jest normalne, nie błąd.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.size(8.dp))

                OutlinedTextField(
                    value = testCommand,
                    onValueChange = { testCommand = it; testResult = null },
                    label = { Text("Np. \"włącz latarkę\" albo \"zrób zdjęcie\"") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        testResult = describeRouting(detector, testCommand)
                    }) {
                        Text("🔍 Sprawdź")
                    }
                    TextButton(
                        onClick = {
                            val actions = detector.detectCritical(testCommand)
                                .ifEmpty { detector.detect(testCommand) }
                            if (actions.isNotEmpty()) {
                                testResult = actions.joinToString("\n") { action ->
                                    "${action.description}\n${executor.execute(action)}"
                                }
                            } else {
                                testResult = "Żadna warstwa wzorców tego nie łapie - " +
                                    "w aplikacji to zdanie trafiłoby do AI."
                            }
                        },
                        enabled = testCommand.isNotBlank()
                    ) {
                        Text("▶ Wykonaj")
                    }
                }

                // Test MÓWIONY - komendy wydaje się głosem, więc test tylko na
                // wpisywanym tekście pomijał najczęstszą przyczynę problemów:
                // rozpoznawanie mowy, które usłyszy co innego, niż się powiedziało.
                Spacer(Modifier.size(8.dp))
                TextButton(
                    onClick = {
                        listening = true
                        testResult = "🎙 Mów…"
                        scope.launch {
                            val heard = speechToText.listen(
                                languageTag = languageTagOf(
                                    pl.victor.app.VictorApplication.get()
                                        .settings.getResponseLanguage()
                                )
                            )
                            listening = false
                            if (heard.isNullOrBlank()) {
                                testResult = "Nic nie usłyszałem. Sprawdź uprawnienie " +
                                    "do mikrofonu i czy okulary są sparowane jako zestaw audio."
                            } else {
                                testCommand = heard
                                testResult = "Usłyszałem: \"$heard\"\n\n" +
                                    describeRouting(detector, heard)
                            }
                        }
                    },
                    enabled = !listening
                ) {
                    Text(if (listening) "🎙 Słucham…" else "🎙 Powiedz komendę")
                }

                // Test samego znacznika, którym AI zleca akcję. To tędy szła
                // usterka "AI czyta komendy zamiast je wykonywać".
                Spacer(Modifier.size(4.dp))
                TextButton(onClick = {
                    val sample = "Wysyłam SMS do Ani.\n" +
                        "[[ACTION: type=send_sms to=\"Ania\" body=\"Będę później\"]]"
                    val (spoken, actions) = detector.detectAiMarkedActions(sample)
                    testResult = buildString {
                        append("Przykładowa odpowiedź AI ze znacznikiem.\n")
                        append("Do wypowiedzenia: \"").append(spoken).append("\"\n")
                        append(
                            if (actions.isEmpty()) "❌ Znacznik NIE został rozpoznany"
                            else "✓ Akcje: " + actions.joinToString { it.description }
                        )
                    }
                }) {
                    Text("🏷 Sprawdź znacznik AI")
                }

                testResult?.let { result ->
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Zainstalowane apki
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "📲 Zainstalowane popularne apki:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(4.dp))
                if (installedApps.isEmpty()) {
                    Text("Ładowanie...", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        installedApps.forEach { app ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (app.installed) "✓" else "✗",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (app.installed)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    app.appName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (app.installed)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(name: String, description: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (granted) "✓" else "○",
            color = if (granted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionListItem(emoji: String, name: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "$name - ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeWordSection(
    enabled: Boolean,
    selectedId: String,
    customPhrase: String,
    keywordPath: String = "",
    modelPath: String = "",
    picovoiceAccessKey: String = "",
    onEnabledChange: (Boolean) -> Unit,
    onWakeWordSelected: (String) -> Unit,
    onCustomPhraseChange: (String) -> Unit,
    onPicovoiceKeyChange: (String) -> Unit = {},
    onKeywordPathChange: (String) -> Unit = {},
    onModelPathChange: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val wakeWords = remember { pl.victor.app.data.WakeWordRegistry.all() }
    val current = wakeWords.find { it.id == selectedId } ?: pl.victor.app.data.WakeWordRegistry.default()
    val resolvedPhrase = if (selectedId == "custom" && customPhrase.isNotBlank()) customPhrase
        else if (selectedId == "custom") "Wpisz swoją"
        else current.phrase

    val glasses = remember { pl.victor.app.VictorApplication.get().glassesManager }
    val glassesWakeEnabled by glasses.glassesWakeWordEnabled.collectAsState()
    val glassesConnection by glasses.connectionState.collectAsState()

    val app = remember { pl.victor.app.VictorApplication.get() }
    var glassesMicEnabled by remember { mutableStateOf(app.settings.isGlassesMicEnabled()) }
    // Do otwarcia systemowych ustawień Bluetooth - patrz karta niżej.
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Komenda głosowa (v1.1)", style = MaterialTheme.typography.titleMedium)

        // === Wybudzenie po stronie OKULARÓW ===
        // To jest droga, która NIE wymaga konta Picovoice. Okulary mają własne
        // wykrywanie frazy w firmware; aplikacja tylko je włącza i słucha
        // zdarzenia. Karta jest pierwsza, bo dla użytkownika okularów to
        // rozwiązanie domyślne - Picovoice niżej dotyczy nasłuchu telefonu.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🕶️ Wybudzenie z okularów",
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            "Okulary same wykrywają swoją frazę - bez konta " +
                                "Picovoice i bez nasłuchu mikrofonem telefonu. " +
                                "Po wybudzeniu V.I.C.T.O.R. słucha pytania i " +
                                "odpowiada głosem przez okulary.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = glassesWakeEnabled,
                        onCheckedChange = { glasses.setGlassesWakeWord(it) },
                        enabled = glassesConnection == pl.victor.app.ble.ConnectionState.READY
                    )
                }
                if (glassesConnection != pl.victor.app.ble.ConnectionState.READY) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Najpierw połącz okulary - przełącznik działa dopiero " +
                            "wtedy, bo ustawienie zapisuje się w samych okularach.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // === Skąd brać PYTANIE po wybudzeniu ===
        // Osobna sprawa od wybudzenia: SCO/HFP zawiesza odtwarzanie A2DP, więc
        // zestaw, który zgłasza profil rozmowy, ale go nie obsługuje, milknie
        // i jednocześnie nic nie słyszy. Aplikacja wyłącza to sama po trzech
        // cichych turach - ten przełącznik pozwala wrócić.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🎙️ Pytania mikrofonem okularów",
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            if (glassesMicEnabled) {
                                "Pytania zbiera mikrofon okularów (profil rozmowy " +
                                    "SCO/HFP). Mikrofon przy uchu słyszy lepiej niż " +
                                    "telefon w kieszeni."
                            } else {
                                "Pytania zbiera mikrofon TELEFONU. Odpowiedzi i tak " +
                                    "są słyszalne w okularach. Włącz, jeśli mikrofon " +
                                    "okularów zaczął działać."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = glassesMicEnabled,
                        onCheckedChange = {
                            glassesMicEnabled = it
                            app.settings.setGlassesMicEnabled(it)
                            app.audio.setGlassesMicEnabled(it)
                        }
                    )
                }
            }
        }

        // === GDY OKULARY ZMIENIĄ SIĘ W "SAMO POŁĄCZENIE" ===
        //
        // Zgłoszone wprost: "z połączeń i słuchania zmieniają się w ustawieniach
        // Bluetooth na samo połączenie". W dzienniku widać to w KAŻDEJ turze
        // jako "brak A2DP - biorę profil rozmowy": asystent mówi wtedy przez
        // wąskopasmowy kanał rozmów telefonicznych zamiast przez muzyczny, więc
        // jego głos brzmi gorzej, niż powinien.
        //
        // Prosimy o to okulary przy każdym połączeniu (LargeDataHandler.openBT),
        // ale to nie pomaga i pomóc nie może: o tym, czy profil multimediów jest
        // włączony dla danego urządzenia, decyduje TELEFON, a przestawienie tego
        // z aplikacji wymagałoby ukrytego API Androida. Nie idę tą drogą.
        //
        // Zostaje jedno dotknięcie zamiast tłumaczenia słowami, gdzie to jest.
        // Deep-link do KONKRETNEGO urządzenia nie istnieje w publicznym API -
        // każdy producent trzyma tę stronę gdzie indziej - więc otwieramy listę.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "🔊 Okulary milczą albo brzmią jak przez telefon?",
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "W ustawieniach Bluetooth telefonu znajdź okulary, wejdź w " +
                        "ikonę koła zębatego obok nich i włącz \"Dźwięk multimediów\". " +
                        "Bez tego odpowiedzi idą kanałem rozmów telefonicznych - " +
                        "słychać je, ale wyraźnie gorzej. Aplikacja nie może " +
                        "przestawić tego za Ciebie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(6.dp))
                OutlinedButton(onClick = {
                    // Gdyby producent nie wystawił ekranu Bluetootha osobno,
                    // wpadamy w ogólne ustawienia - lepsze niż przycisk, który
                    // nic nie robi.
                    val opened = runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        true
                    }.getOrDefault(false)
                    if (!opened) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }) { Text("Otwórz ustawienia Bluetooth") }
            }
        }

        Text(
            "Poniżej: nasłuch mikrofonem TELEFONU (Picovoice). Przydatny, gdy " +
                "okularów nie masz na sobie - wymaga własnego klucza dostępu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Aktywuj AI głosowo")
                Text(
                    if (picovoiceAccessKey.isBlank()) {
                        "Najpierw wklej klucz Picovoice poniżej - bez niego " +
                            "wykrywanie komendy nie ruszy."
                    } else {
                        "V.I.C.T.O.R. nasłuchuje komendy w tle. Zużywa baterię."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                // Przełącznik był zablokowany na sztywno komentarzem "gdy Picovoice
                // dodane" - biblioteka jest w projekcie od dawna, brakuje tylko
                // klucza dostępu, a ten wpisuje się w karcie poniżej.
                enabled = picovoiceAccessKey.isNotBlank()
            )
        }

        // Karta z aktualną komendą
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(current.emoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "„${resolvedPhrase}" + if (selectedId != "custom") "”" else "”",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        current.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Dropdown
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = current.phrase.ifBlank { "Własna komenda" },
                onValueChange = {},
                readOnly = true,
                label = { Text("Wybierz komendę") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { Text("▼", modifier = Modifier.padding(8.dp)) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                // Podział przebiega tam, gdzie przebiega naprawdę: część fraz
                // Porcupine zna, reszta wymaga wytrenowanego modelu.
                Text(
                    "  Działają od razu (wymowa angielska)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
                wakeWords.filter { it.worksOutOfTheBox }.forEach { ww ->
                    WakeWordItem(ww, selectedId, onWakeWordSelected) { expanded = false }
                }
                HorizontalDivider()
                Text(
                    "  Wymaga własnego modelu .ppn",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
                wakeWords.filterNot { it.worksOutOfTheBox }.forEach { ww ->
                    WakeWordItem(ww, selectedId, onWakeWordSelected) { expanded = false }
                }
            }
        }

        // Pole custom (gdy wybrano custom)
        if (selectedId == "custom") {
            OutlinedTextField(
                value = customPhrase,
                onValueChange = onCustomPhraseChange,
                label = { Text("Twoja komenda") },
                placeholder = { Text("Np. Hej V.I.C.T.O.R., Panie Asystencie") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        "Sama fraza nie wystarczy - Porcupine potrzebuje pliku .ppn " +
                            "wytrenowanego na console.picovoice.ai. Dla frazy polskiej " +
                            "dodatkowo modelu .pv dla języka polskiego."
                    )
                }
            )

            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = keywordPath,
                onValueChange = onKeywordPathChange,
                label = { Text("Ścieżka do pliku .ppn") },
                placeholder = { Text("/sdcard/Download/hey-victor_pl.ppn") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = modelPath,
                onValueChange = onModelPathChange,
                label = { Text("Model .pv (tylko język inny niż angielski)") },
                placeholder = { Text("/sdcard/Download/porcupine_params_pl.pv") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // Info o domyślnej
        Text(
            "Domyślna: „${pl.victor.app.data.WakeWordRegistry.default().phrase}” - " +
                "wbudowana w Porcupine, działa bez dodatkowych plików. " +
                "Wymowa angielska: „dżarwis”.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Picovoice access key (v1.1)
        if (picovoiceAccessKey.isBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "🔑 Aby włączyć wake word, wpisz Picovoice AccessKey",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "1. Zarejestruj się na https://console.picovoice.ai/\n" +
                                "2. Utwórz projekt (darmowy tier: 3 keywords)\n" +
                                "3. Skopiuj AccessKey i wklej poniżej",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = picovoiceAccessKey,
                        onValueChange = onPicovoiceKeyChange,
                        label = { Text("Picovoice AccessKey") },
                        placeholder = { Text("np. /5jV.../kE=") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✓ Picovoice skonfigurowany",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onPicovoiceKeyChange("") }) {
                    Text("Zmień klucz")
                }
            }
        }
    }
}

@Composable
private fun WakeWordItem(
    ww: pl.victor.app.data.WakeWord,
    selectedId: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ww.emoji,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column {
                    Text(
                        ww.phrase.ifBlank { "(własna)" },
                        fontWeight = if (ww.id == selectedId)
                            androidx.compose.ui.text.font.FontWeight.Bold
                        else androidx.compose.ui.text.font.FontWeight.Normal
                    )
                    Text(
                        ww.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        onClick = {
            onSelected(ww.id)
            onDismiss()
        }
    )
}

// === Sekcja: Inteligentne funkcje (v1.2) ===

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntelligenceSection(
    onManageGoogleAccount: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as pl.victor.app.VictorApplication).settings }

    var conversationalOn by remember { mutableStateOf(settings.isConversationalModeEnabled()) }
    var longTermOn by remember { mutableStateOf(settings.isLongTermMemoryEnabled()) }
    var translationTarget by remember { mutableStateOf(settings.getTranslationTarget()) }
    // Stan konta czytamy Z USŁUG GOOGLE, nie z zapamiętanej flagi, i odświeżamy
    // po każdym powrocie na ekran. Wcześniej wartość była brana raz, przy
    // pierwszym złożeniu widoku - więc po udanym logowaniu (osobne Activity!)
    // karta dalej pisała "nie połączono", mimo że konto już było podłączone.
    // Z zewnątrz to wyglądało jak nieudane logowanie.
    var googleConnected by remember { mutableStateOf(settings.isGoogleAccountConnected()) }
    // Poczta jest zgodą OSOBNĄ od logowania - patrz GoogleAccountManager. Karta
    // musi więc pokazywać dwa niezależne stany, a nie jeden.
    var gmailConnected by remember {
        mutableStateOf(runCatching { GoogleAccountManager(context).hasGmailAccess() }.getOrDefault(false))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val manager = GoogleAccountManager(context)
                val signedIn = runCatching { manager.isSignedIn() }.getOrDefault(false)
                settings.setGoogleAccountConnected(signedIn)
                googleConnected = signedIn
                gmailConnected = runCatching { manager.hasGmailAccess() }.getOrDefault(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Zgodę na pocztę Google dokłada do już zalogowanego konta, więc wynik czytamy
    // przez sprawdzenie stanu, a nie kod wyniku - tak samo jak zgodę na Dysk.
    val gmailConsentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        gmailConnected = runCatching {
            GoogleAccountManager(context).hasGmailAccess()
        }.getOrDefault(false)
    }

    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "🧠 Inteligentne funkcje",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(8.dp))

            // Tryb konwersacyjny
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("💬 Tryb konwersacyjny", fontWeight = FontWeight.Medium)
                    Text(
                        "Po odpowiedzi automatycznie słucha kolejnego pytania",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = conversationalOn,
                    onCheckedChange = { newVal ->
                        conversationalOn = newVal
                        settings.setConversationalModeEnabled(newVal)
                        pl.victor.app.VictorApplication.get()
                            .orchestrator
                            .let { orch ->
                                if (newVal) orch.enableConversationalMode()
                                else orch.disableConversationalMode()
                            }
                    }
                )
            }
            Spacer(Modifier.size(8.dp))

            // Pamięć długoterminowa
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🧠 Pamięć długoterminowa", fontWeight = FontWeight.Medium)
                    Text(
                        "Pamięta rozmowy i używa ich jako kontekst",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = longTermOn,
                    onCheckedChange = { newVal ->
                        longTermOn = newVal
                        settings.setLongTermMemoryEnabled(newVal)
                    }
                )
            }
            Spacer(Modifier.size(8.dp))

            // Tłumacz - wybór języka docelowego
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🌍 Tłumacz symultaniczny", fontWeight = FontWeight.Medium)
                    Text(
                        "Docelowy język: ${pl.victor.app.translation.SimultaneousTranslator.languageName(translationTarget)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Dropdown z językami
            var translationExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = translationExpanded,
                onExpandedChange = { translationExpanded = !translationExpanded }
            ) {
                OutlinedTextField(
                    value = pl.victor.app.translation.SimultaneousTranslator.languageName(translationTarget),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Język docelowy") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = translationExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = translationExpanded,
                    onDismissRequest = { translationExpanded = false }
                ) {
                    pl.victor.app.translation.SimultaneousTranslator.SUPPORTED_LANGUAGES.forEach { (code, _) ->
                        DropdownMenuItem(
                            text = { Text(pl.victor.app.translation.SimultaneousTranslator.languageName(code)) },
                            onClick = {
                                translationTarget = code
                                settings.setTranslationTarget(code)
                                translationExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))

            // Konto Google - logowanie daje Kalendarz; poczta to osobna zgoda
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (googleConnected)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (googleConnected) "🔗 Konto Google: połączono" else "🔗 Konto Google: nie połączono",
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "Logowanie odblokowuje 📅 Kalendarz - czyta i tworzy wydarzenia " +
                            "(\"dodaj spotkanie jutro o 10\").",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!googleConnected && GoogleAccountManager.isLoginExpired()) {
                        // Bez tego rozłączenie wygląda jak awaria albo cudze działanie.
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "⏳ Logowanie wygasło. Google unieważnia je co 7 dni, dopóki " +
                                "aplikacja jest w trybie testowym - zaloguj się ponownie, " +
                                "nic nie trzeba zmieniać w ustawieniach.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (googleConnected) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (gmailConnected) {
                                "📧 Poczta: włączona - czytam i wysyłam maile."
                            } else {
                                "📧 Poczta: wyłączona. Google wymaga do niej osobnej, " +
                                    "szerszej zgody, więc pytam o nią tylko wtedy, gdy " +
                                    "naprawdę jest potrzebna."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Row {
                        if (googleConnected && !gmailConnected) {
                            OutlinedButton(
                                onClick = {
                                    gmailConsentLauncher.launch(
                                        GoogleAccountManager(context).getGmailConsentIntent()
                                    )
                                }
                            ) { Text("📧 Włącz pocztę") }
                            Spacer(Modifier.size(8.dp))
                        }
                        if (googleConnected) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            pl.victor.app.google.GoogleAccountManager(context).signOut()
                                            settings.setGoogleAccountConnected(false)
                                            googleConnected = false
                                        } catch (e: Exception) { }
                                    }
                                }
                            ) { Text("Wyloguj") }
                        } else {
                            Button(onClick = onManageGoogleAccount) {
                                Text("🔑 Połącz konto Google")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessibilitySection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as pl.victor.app.VictorApplication).settings }

    var highContrast by remember { mutableStateOf(settings.isHighContrastEnabled()) }
    var largeText by remember { mutableStateOf(settings.isLargeTextEnabled()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "\u267F Dost\u0119pno\u015b\u0107",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wysoki kontrast", fontWeight = FontWeight.Medium)
                    Text(
                        "Czer\u0144 i biel zamiast kolor\u00f3w systemowych",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = highContrast,
                    onCheckedChange = { newVal ->
                        highContrast = newVal
                        settings.setHighContrastEnabled(newVal)
                    }
                )
            }
            Spacer(Modifier.size(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Du\u017ce litery", fontWeight = FontWeight.Medium)
                    Text(
                        "Powi\u0119ksza tekst w ca\u0142ej aplikacji o 30%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = largeText,
                    onCheckedChange = { newVal ->
                        largeText = newVal
                        settings.setLargeTextEnabled(newVal)
                    }
                )
            }
            Spacer(Modifier.size(8.dp))

            Text(
                "Zmiany wida\u0107 po ponownym otwarciu ekranu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CaptureModeSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as pl.victor.app.VictorApplication).settings }
    var preferredMode by remember { mutableStateOf(pl.victor.app.ai.CaptureMode.valueOf(settings.getPreferredCaptureMode())) }
    var autoDegrade by remember { mutableStateOf(settings.isAutoDegradeCaptureEnabled()) }
    var photoSource by remember { mutableStateOf(settings.getPhotoSource()) }
    var photoDivisor by remember { mutableStateOf(settings.getPhotoDivisor()) }
    var providerCaps by remember { mutableStateOf<pl.victor.app.ai.ProviderCapabilities?>(null) }

    LaunchedEffect(Unit) {
        try {
            val app = context.applicationContext as pl.victor.app.VictorApplication
            val providerId = app.settings.getActiveProvider()
            providerCaps = pl.victor.app.ai.AIProviderFactory.getCapabilitiesFor(providerId)
        } catch (e: Exception) { }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("📸 Tryb przechwytywania", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            Text("Jak okulary mają przechwytywać obraz?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(12.dp))

            pl.victor.app.ai.CaptureMode.values().forEach { mode ->
                val caps = providerCaps
                val supported = caps?.supportsMode(mode) ?: true
                val isSelected = preferredMode == mode
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    RadioButton(selected = isSelected, onClick = { preferredMode = mode; settings.setPreferredCaptureMode(mode.name) }, enabled = supported)
                    Spacer(Modifier.size(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.emoji, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.size(4.dp))
                            Text(mode.displayName, fontWeight = FontWeight.Medium, color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!supported && caps != null) {
                            Text("❌ Provider nie obsługuje", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🔄 Auto-degradacja", fontWeight = FontWeight.Medium)
                    Text("Jeśli provider nie obsługuje, użyj prostszego (wideo → zdjęcia).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoDegrade, onCheckedChange = { autoDegrade = it; settings.setAutoDegradeCaptureEnabled(it) })
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("🖼️ Co dostaje AI", fontWeight = FontWeight.Medium)
            Text(
                "Miniatura idzie samym Bluetoothem i jest natychmiast, ale liter z " +
                    "bliska nie da się z niej odczytać. Pełne zdjęcie wymaga Wi-Fi z " +
                    "okularami - wolniej, za to czytelnie.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            listOf(
                pl.victor.app.data.SettingsRepository.PHOTO_FULL to "Pełne zdjęcie (czyta tekst)",
                pl.victor.app.data.SettingsRepository.PHOTO_THUMBNAIL to "Miniatura (najszybsza)"
            ).forEach { (value, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = photoSource == value,
                        onClick = { photoSource = value; settings.setPhotoSource(value) }
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (photoSource == pl.victor.app.data.SettingsRepository.PHOTO_FULL) {
                Spacer(Modifier.size(8.dp))
                Text("Zmniejszenie przed wysłaniem: ${photoDivisor}x", fontWeight = FontWeight.Medium)
                Text(
                    "Mniejszy plik to krótsza droga do modelu. Przy dwukrotnym " +
                        "zmniejszeniu tekst zostaje czytelny; przy czterokrotnym " +
                        "drobny druk może już się nie udać.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // RadioButton, a nie FilterChip: ten pierwszy jest w tym pliku
                // używany wszędzie i na pewno się kompiluje, a wyboru jednej
                // wartości z czterech nie robi to ani trochę gorzej.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(1, 2, 3, 4).forEach { divisor ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            RadioButton(
                                selected = photoDivisor == divisor,
                                onClick = { photoDivisor = divisor; settings.setPhotoDivisor(divisor) }
                            )
                            Text(if (divisor == 1) "bez" else "${divisor}x")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PowerModeSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as pl.victor.app.VictorApplication).settings }
    val app = context.applicationContext as pl.victor.app.VictorApplication
    val scope = rememberCoroutineScope()

    val powerManager = remember { pl.victor.app.power.PowerManager(context, settings) }
    val currentMode by powerManager.currentMode.collectAsState()
    val batteryState by powerManager.batteryState.collectAsState()
    val autoMode by powerManager.autoModeEnabled.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔋", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(8.dp))
                Text("Zasilanie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.size(8.dp))

            // Aktualna bateria
            Row(verticalAlignment = Alignment.CenterVertically) {
                val charging = if (batteryState.charging) "⚡" else "🔋"
                Text("$charging ${batteryState.percent}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                if (batteryState.charging) {
                    Text("ładowanie", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            val remainHours = powerManager.estimateRemainingHours()
            if (remainHours.isFinite() && remainHours > 0) {
                Text(
                    "Pozostało: ${"%.1f".format(remainHours)}h w trybie ${currentMode.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.size(12.dp))

            // Tryby
            Text("Tryb zasilania:", fontWeight = FontWeight.Medium)
            pl.victor.app.power.PowerMode.values().forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = currentMode == mode,
                        onClick = { powerManager.setMode(mode) }
                    )
                    Spacer(Modifier.size(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.emoji, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.size(4.dp))
                            Text(mode.displayName, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.size(4.dp))
                            Text("~${mode.batteryPerHourPercent}%/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.size(8.dp))

            // Auto-mode
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🤖 Tryb automatyczny", fontWeight = FontWeight.Medium)
                    Text("Sam dostosowuje tryb do stanu baterii (ECO przy <15%, NORMAL przy 20-50%)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoMode, onCheckedChange = { powerManager.setAutoMode(it) })
            }

            // Wskazówki oszczędzania
            Spacer(Modifier.size(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("💡 Co zużywa baterię:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("• Wake word (nasłuch mikrofonu w tle) - ~3%/h", style = MaterialTheme.typography.labelSmall)
                    Text("• AI inference - krótkie, intensywne (kilka%)", style = MaterialTheme.typography.labelSmall)
                    Text("• TTS mówienie - ~1-2%/h mówienia", style = MaterialTheme.typography.labelSmall)
                    Text("• HeyCyan wideo 1080p - ~5-10%/h", style = MaterialTheme.typography.labelSmall)
                    Text("• WorkManager proaktywne - minimalnie", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * Opisuje, która warstwa routingu obsłuży dane zdanie.
 *
 * Panel testowy sprawdzał wcześniej wyłącznie [SmartActionDetector.detect],
 * czyli warstwę ZAPASOWĄ. Naturalne polecenie ("daj znać Ani, że się spóźnię")
 * dostawało więc "nie wykryto akcji", chociaż w działającej aplikacji rozumie
 * je AI i wykonuje poprawnie. Test mylił bardziej, niż pomagał.
 */
private fun describeRouting(
    detector: pl.victor.app.actions.SmartActionDetector,
    text: String
): String {
    if (text.isBlank()) return "Wpisz albo powiedz coś najpierw."

    val critical = detector.detectCritical(text)
    if (critical.isNotEmpty()) {
        return "⚡ Warstwa 0 (odruch, offline, natychmiast)\n" +
            critical.joinToString("\n") { "• " + it.description }
    }

    val fallback = detector.detect(text)
    return if (fallback.isNotEmpty()) {
        "🧠 Warstwa 1: to zdanie idzie do AI.\n\n" +
            "Gdyby AI było niedostępne (brak klucza, brak sieci), warstwa 2 " +
            "zrozumiałaby je wzorcami jako:\n" +
            fallback.joinToString("\n") { "• " + it.description }
    } else {
        "🧠 Warstwa 1: to zdanie idzie do AI, które samo zdecyduje, czy " +
            "odpowiedzieć, czy zlecić akcję.\n\n" +
            "Zapasowe wzorce (warstwa 2) go nie rozpoznają - to normalne dla " +
            "zdań wypowiedzianych naturalnie."
    }
}

/** Kod języka z ustawień na tag BCP-47 dla rozpoznawania mowy. */
private fun languageTagOf(languageCode: String): String = when (languageCode) {
    "pl" -> "pl-PL"
    "en" -> "en-US"
    "de" -> "de-DE"
    "es" -> "es-ES"
    "fr" -> "fr-FR"
    "it" -> "it-IT"
    "uk" -> "uk-UA"
    else -> languageCode
}


/**
 * Dziennik diagnostyczny - na czas testów ze sprzętem.
 *
 * ## Po co to w ustawieniach
 * Bo bez tego usterki typu "zawiesiło się" albo "odpowiada nie na to pytanie"
 * są nie do zdiagnozowania: przy okularach na głowie nikt nie patrzy w logcat,
 * a opis po fakcie nie niesie czasów ani tego, która droga transkrypcji
 * zadziałała. Dziennik zapisuje każdy etap tury z czasem od jej początku.
 *
 * ## Token
 * Wystarczy token o zakresie `Contents: Read and write` na jedno repozytorium.
 * Dziennik przechodzi przez zaciemnianie sekretów, zanim trafi do pliku, więc
 * kluczy API w nim nie ma - ale sam token trzymany jest tak jak każdy inny
 * (zaszyfrowane preferencje) i nigdy nie jest wypisywany.
 */
@Composable
private fun DiagnosticsLogSection() {
    val context = LocalContext.current
    val settings = remember { pl.victor.app.data.SettingsRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(settings.isDiagnosticLogEnabled()) }
    var token by remember { mutableStateOf(settings.getGithubToken()) }
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "🩺 Dziennik diagnostyczny",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Zapisuje każdy etap tury z czasem: nasłuch, która droga przepisała " +
                "mowę, zdjęcie, wysłanie do modelu i moment, w którym przyszło " +
                "pierwsze słowo odpowiedzi. Kluczy API w dzienniku nie ma.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Zapisuj dziennik", modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    settings.setDiagnosticLogEnabled(it)
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                settings.setGithubToken(it)
            },
            label = { Text("Token GitHuba (wysyłka dziennika)") },
            placeholder = { Text("github_pat_... albo ghp_...") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Bez tokenu dziennik zostaje na telefonie. Z tokenem trafia po " +
                "każdej turze do gałęzi \"" +
                pl.victor.app.diagnostics.DiagnosticUploader.BRANCH + "\" w repozytorium.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                sending = true
                status = "Wysyłam..."
                scope.launch {
                    val app = context.applicationContext as pl.victor.app.VictorApplication
                    val file = app.diag.currentFile()
                    val content = app.diag.readSession()
                    status = when {
                        token.isBlank() -> "Najpierw wklej token."
                        file == null || content.isBlank() -> "Dziennik jest jeszcze pusty."
                        else -> pl.victor.app.diagnostics.DiagnosticUploader(token)
                            .upload(file.name, content)
                            .fold(
                                onSuccess = { "Wysłano: $it" },
                                onFailure = { it.message ?: "Nie udało się wysłać." }
                            )
                    }
                    sending = false
                }
            },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Wyślij dziennik teraz")
        }
        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
