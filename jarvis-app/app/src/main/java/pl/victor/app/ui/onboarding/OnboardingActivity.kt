package pl.victor.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.victor.app.ui.MainActivity
import pl.victor.app.ui.brand.VictorBrandMark
import pl.victor.app.ui.theme.VictorTheme

/**
 * Onboarding - prowadzenie nowego użytkownika przez setup.
 * 8 kroków: welcome → provider → api key → weather → picovoice → location → permissions → done
 */
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ekran pokazuje klucze API - blokuj zrzuty ekranu i podgląd w menu zadań.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent {
            VictorTheme {
                OnboardingScreen(onFinished = {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Wyjątek optymalizacji baterii to intencja do Ustawień systemowych, nie
    // runtime permission - nie ma callbacku powrotu, więc sprawdzamy stan
    // ponownie za każdym razem, gdy ekran wraca na pierwszy plan.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBatteryExemption()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results[android.Manifest.permission.RECORD_AUDIO]?.let {
            viewModel.setMicPermission(it)
        }
        results[android.Manifest.permission.READ_CALENDAR]?.let {
            viewModel.setCalendarPermission(it)
        }
        results[android.Manifest.permission.POST_NOTIFICATIONS]?.let {
            viewModel.setNotificationPermission(it)
        }
        results[android.Manifest.permission.BLUETOOTH_SCAN]?.let {
            viewModel.setBluetoothPermission(it)
        }
        results[android.Manifest.permission.BLUETOOTH_CONNECT]?.let {
            viewModel.setBluetoothPermission(it)
        }
        results[android.Manifest.permission.ACCESS_FINE_LOCATION]?.let {
            viewModel.setLocationPermission(it)
        }
    }

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Konfiguracja V.I.C.T.O.R.") },
                actions = {
                    if (state.currentStep > 0 && state.currentStep < state.totalSteps - 1) {
                        TextButton(onClick = { viewModel.skipStep() }) {
                            Text("Pomiń")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Pasek postępu
            LinearProgressIndicator(
                progress = (state.currentStep + 1).toFloat() / state.totalSteps.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )

            // Zawartość - animowane przejście
            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    (slideInHorizontally { it } + fadeIn())
                        .togetherWith(slideOutHorizontally { -it } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "step"
            ) { step ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (OnboardingStep.values().getOrNull(step)) {
                        OnboardingStep.WELCOME -> StepWelcome(viewModel)
                        OnboardingStep.FEATURE_AI -> FeatureSlide(
                            emoji = "🧠",
                            accent = MaterialTheme.colorScheme.primary,
                            title = "AI, które naprawdę działa",
                            tagline = "Gemini, OpenAI, Claude albo MiniMax - Ty wybierasz",
                            points = listOf(
                                "Przełączaj się między personami głosem, np. \"włącz tryb szefa kuchni\"",
                                "AI może samo wysłać SMS, zadzwonić, odpalić muzykę albo nawigację - wystarczy poprosić",
                                "Pamięta kontekst rozmowy, więc nie musisz się powtarzać"
                            )
                        )
                        OnboardingStep.FEATURE_VOICE -> FeatureSlide(
                            emoji = "🎙️",
                            accent = MaterialTheme.colorScheme.tertiary,
                            title = "Steruj głosem",
                            tagline = "Nasłuch przez telefon, odpowiedzi strumieniowo",
                            points = listOf(
                                "Aktywacja słowem kluczowym - wybierz swoje w Ustawieniach",
                                "Odpowiedzi TTS zdanie po zdaniu, bez czekania na całość",
                                "Tryb konwersacyjny - dopytuj dalej bez powtarzania słowa aktywującego"
                            )
                        )
                        OnboardingStep.FEATURE_GLASSES -> FeatureSlide(
                            emoji = "👓",
                            accent = MaterialTheme.colorScheme.secondary,
                            title = "Przycisk i panel dotykowy",
                            tagline = "Fizyczne sterowanie na samych okularach",
                            // GESTY MUSZĄ ZGADZAĆ SIĘ Z [ButtonActionDetector].
                            //
                            // Dwa z czterech były tu na odwrót: samouczek uczył,
                            // że potrójne kliknięcie czyta kod QR, a
                            // przytrzymanie zaczyna nową rozmowę. Kod robi
                            // dokładnie odwrotnie, odkąd przytrzymanie dostało
                            // czytanie tekstu - bo to jest funkcja, dla której
                            // nosi się te okulary, a reset zszedł do
                            // najrzadziej potrzebnego gestu.
                            //
                            // Pierwsze zdanie, jakie nowa osoba czyta o
                            // sterowaniu, nie może być nieprawdą: uczy się jej
                            // gestu, który zrobi co innego, niż zapowiedziano.
                            points = listOf(
                                "1 kliknięcie = zapytaj głosem, 2 = zdjęcie i opis widoku",
                                "Przytrzymanie = co tu jest napisane, obce od razu po polsku",
                                "3 kliknięcia = nowa rozmowa (kasuje historię)"
                            )
                        )
                        OnboardingStep.FEATURE_SMART -> FeatureSlide(
                            emoji = "📅",
                            accent = MaterialTheme.colorScheme.primary,
                            title = "Kalendarz, mail i pogoda",
                            tagline = "V.I.C.T.O.R. pilnuje Twojego dnia",
                            points = listOf(
                                "Dodawaj wydarzenia głosem - dostaniesz przypomnienie, zanim wyjdziesz",
                                "Wyślij maila z Gmaila samym głosem",
                                "Proaktywne alerty pogodowe - \"weź parasol, za 20 minut pada\""
                            )
                        )
                        OnboardingStep.PROVIDER -> StepProvider(viewModel)
                        OnboardingStep.API_KEY -> StepApiKey(viewModel)
                        OnboardingStep.WEATHER -> StepWeather(viewModel)
                        OnboardingStep.PICOVOICE -> StepPicovoice(viewModel)
                        OnboardingStep.LOCATION -> StepLocation(viewModel)
                        OnboardingStep.PERMISSIONS -> StepPermissions(
                            viewModel = viewModel,
                            onRequest = {
                                val perms = mutableListOf(
                                    // RECORD_AUDIO było zadeklarowane w manifeście,
                                    // ale NIKT o nie nie prosił. Bez niego nie działa
                                    // ani komenda głosowa, ani tryb konwersacyjny,
                                    // ani rozpoznawanie mowy - czyli wszystko, co w
                                    // asystencie głosowym jest najważniejsze.
                                    android.Manifest.permission.RECORD_AUDIO,
                                    android.Manifest.permission.READ_CALENDAR,
                                    android.Manifest.permission.BLUETOOTH_SCAN,
                                    android.Manifest.permission.BLUETOOTH_CONNECT,
                                    android.Manifest.permission.ACCESS_FINE_LOCATION
                                )
                                // Uprawnienia dostępne dopiero od Androida 13.
                                if (android.os.Build.VERSION.SDK_INT >=
                                    android.os.Build.VERSION_CODES.TIRAMISU
                                ) {
                                    perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                                    perms.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                                }
                                permissionLauncher.launch(perms.toTypedArray())
                            },
                            onOpenBatterySettings = {
                                pl.victor.app.power.BatteryOptimizationHelper.launchExemptionFlow(context)
                            }
                        )
                        OnboardingStep.GLASSES -> StepGlasses(
                            viewModel = viewModel,
                            onOpenPairing = {
                                context.startActivity(Intent(
                                    context,
                                    pl.victor.app.ui.pairing.PairingActivity::class.java
                                ))
                            }
                        )
                        OnboardingStep.DONE -> StepDone(viewModel)
                        else -> {}
                    }
                }
            }

            // Nawigacja dolna
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Wstecz
                if (state.currentStep > 0) {
                    TextButton(onClick = { viewModel.previousStep() }) {
                        Text("← Wstecz")
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                // Progress text
                Text(
                    "${state.currentStep + 1} / ${state.totalSteps}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Dalej / Zakończ
                val isLast = state.currentStep == state.totalSteps - 1
                val step = OnboardingStep.values().getOrNull(state.currentStep)
                val canContinue = viewModel.isStepReady(step ?: OnboardingStep.WELCOME)

                Button(
                    onClick = {
                        if (isLast) viewModel.finish()
                        else viewModel.nextStep()
                    },
                    enabled = canContinue
                ) {
                    Text(if (isLast) "Zakończ" else "Dalej")
                    Spacer(Modifier.size(4.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StepHeader(emoji: String, title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StepWelcome(viewModel: OnboardingViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        VictorBrandMark(markSize = 88.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            "Twoje inteligentne okulary sterowane głosem",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))
        Text(
            "Na kolejnych ekranach krótko pokażemy, co V.I.C.T.O.R. potrafi - " +
                "potem konfiguracja zajmie ~5 minut",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Jeden ekran z krótkiej "co potrafi V.I.C.T.O.R." na start onboardingu -
 * odpowiednik karuzeli funkcji z HeyCyan, ale z realnymi funkcjami tej apki.
 */
@Composable
private fun FeatureSlide(
    emoji: String,
    accent: Color,
    title: String,
    tagline: String,
    points: List<String>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(accent.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, style = MaterialTheme.typography.displayMedium)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            tagline,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                points.forEachIndexed { index, point ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Text("●", color = accent, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        Text(point, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepProvider(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsState()
    val providers = remember { pl.victor.app.ai.AIProviderFactory.supportedProviders() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        StepHeader(
            "🧠",
            "Wybierz AI",
            "Który model AI ma odpowiadać? Możesz zmienić później w Ustawieniach."
        )

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                providers.forEach { provider ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        RadioButton(
                            selected = state.providerId == provider.id,
                            onClick = { viewModel.setProvider(provider.id) }
                        )
                        Spacer(Modifier.size(8.dp))
                        Column {
                            Text(provider.displayName, fontWeight = FontWeight.Bold)
                            Text(
                                provider.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepApiKey(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val providerName = pl.victor.app.ai.AIProviderFactory.supportedProviders()
        .find { it.id == state.providerId }?.displayName ?: "Gemini"

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        StepHeader(
            "🔑",
            "Klucz API - $providerName",
            "Wklej swój klucz API. Będzie zaszyfrowany i przechowywany tylko lokalnie."
        )

        Spacer(Modifier.height(16.dp))

        // Instrukcja
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Jak uzyskać klucz:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text("1. Otwórz stronę z kluczami", style = MaterialTheme.typography.bodySmall)
                Text("2. Zaloguj się kontem Google", style = MaterialTheme.typography.bodySmall)
                Text("3. Kliknij \"Create API key\"", style = MaterialTheme.typography.bodySmall)
                Text("4. Skopiuj klucz (zaczyna się od AIza... lub AQ...)", style = MaterialTheme.typography.bodySmall)
                Text("5. Wklej poniżej", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        val keyUrl = pl.victor.app.ai.AIProviderFactory.supportedProviders()
                            .find { it.id == state.providerId }?.keyUrl
                        if (keyUrl != null) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl)))
                        }
                    }
                ) {
                    Text("🌐 Otwórz stronę z kluczami")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = { viewModel.setApiKey(it) },
            label = { Text("$providerName API Key") },
            placeholder = { Text("AIza... lub AQ.Ab...") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StepWeather(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        StepHeader(
            "🌦️",
            "Alerty pogodowe (opcjonalne)",
            "V.I.C.T.O.R. może ostrzegać: \"Weź parasol, bo za 20 min pada\". Wymaga darmowego klucza OpenWeatherMap."
        )

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Darmowy klucz OWM:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text("1. https://openweathermap.org/api", style = MaterialTheme.typography.bodySmall)
                Text("2. Sign Up (darmowe)", style = MaterialTheme.typography.bodySmall)
                Text("3. Skopiuj API key", style = MaterialTheme.typography.bodySmall)
                Text("4. ⚠️ Nowy klucz aktywuje się do 2h", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(4.dp))
                Text("Możesz pominąć ten krok i dodać klucz później w Ustawieniach.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.openWeatherKey,
            onValueChange = { viewModel.setOpenWeatherKey(it) },
            label = { Text("OpenWeatherMap API Key (opcjonalnie)") },
            placeholder = { Text("a1b2c3d4...") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StepPicovoice(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        StepHeader(
            "🎙️",
            "Wake word (opcjonalne)",
            "Aktywuj AI głosem, np. mówiąc \"Computer\". Docelowa fraza \"Hey Victor\" wymaga " +
                "później własnego modelu (patrz Ustawienia) - na start działa od razu wbudowana " +
                "komenda. Wymaga darmowego konta Picovoice."
        )

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Darmowy Picovoice:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text("1. https://console.picovoice.ai/", style = MaterialTheme.typography.bodySmall)
                Text("2. Sign Up", style = MaterialTheme.typography.bodySmall)
                Text("3. Utwórz projekt", style = MaterialTheme.typography.bodySmall)
                Text("4. Skopiuj AccessKey", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(4.dp))
                Text("Darmowy tier: 3 wake words / urządzenie", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.picovoiceKey,
            onValueChange = { viewModel.setPicovoiceKey(it) },
            label = { Text("Picovoice AccessKey (opcjonalnie)") },
            placeholder = { Text("/5jV.../kE=") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StepLocation(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        StepHeader(
            "📍",
            "Lokalizacja (opcjonalne)",
            "Miasto dla prognozy pogody. Bez tego alerty pogodowe nie zadziałają."
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.city,
            onValueChange = { viewModel.setCity(it) },
            label = { Text("Miasto / współrzędne") },
            placeholder = { Text("Warszawa,PL") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "Przykłady: \"Warszawa,PL\", \"Kraków\", \"52.23,21.01\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepPermissions(
    viewModel: OnboardingViewModel,
    onRequest: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        StepHeader(
            "🔐",
            "Uprawnienia",
            "V.I.C.T.O.R. prosi o kilka uprawnień. Bez nich pewne funkcje nie zadziałają."
        )

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                PermissionRowItem(
                    "🎙 Mikrofon",
                    "Rozmowa głosem, komenda wybudzenia, dyktowanie",
                    state.hasMicPermission
                )
                HorizontalDivider()
                PermissionRowItem(
                    "📅 Kalendarz",
                    "Alerty przed wyjściem na spotkanie",
                    state.hasCalendarPermission
                )
                HorizontalDivider()
                PermissionRowItem(
                    "🔔 Powiadomienia",
                    "Alert: \"Weź parasol\", \"Spóźnisz się\"",
                    state.hasNotificationPermission
                )
                HorizontalDivider()
                PermissionRowItem(
                    "📡 Bluetooth",
                    "Połączenie z okularami",
                    state.hasBluetoothPermission
                )
                HorizontalDivider()
                PermissionRowItem(
                    "📍 Lokalizacja",
                    "Dokładna pogoda dla Twojego miejsca",
                    state.hasLocationPermission
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔓 Poproś o uprawnienia")
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Możesz też ustawić uprawnienia później w Ustawieniach telefonu.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                PermissionRowItem(
                    "🔋 Optymalizacja baterii",
                    "Bez tego Android usypia V.I.C.T.O.R. kilka minut po zgaszeniu ekranu - " +
                        "wake word i połączenie z okularami przestają działać po cichu.",
                    state.hasBatteryExemption
                )
                Spacer(Modifier.height(8.dp))
                if (!state.hasBatteryExemption) {
                    Button(
                        onClick = onOpenBatterySettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔋 Wyłącz optymalizację baterii dla V.I.C.T.O.R.")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRowItem(name: String, description: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Icon(Icons.Default.Check, contentDescription = "OK", tint = MaterialTheme.colorScheme.primary)
        } else {
            Text("○", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun StepGlasses(
    viewModel: OnboardingViewModel,
    onOpenPairing: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        StepHeader(
            "👓",
            "Połącz okulary",
            "Włącz okulary (przytrzymaj przycisk 3s) i sparuj przez Bluetooth."
        )

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Instrukcja:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text("1. Na okularach przytrzymaj przycisk 3 sekundy", style = MaterialTheme.typography.bodySmall)
                Text("2. Dioda LED zacznie migać na niebiesko", style = MaterialTheme.typography.bodySmall)
                Text("3. Kliknij przycisk poniżej", style = MaterialTheme.typography.bodySmall)
                Text("4. Wybierz urządzenie z listy", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Możesz pominąć ten krok - sparujesz później klikając ikonę Bluetooth w głównym ekranie.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                viewModel.setGlassesPaired(true)
                onOpenPairing()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📡 Otwórz parowanie")
        }
    }
}

@Composable
private fun StepDone(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎉", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            "Gotowe!",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Twoja konfiguracja:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                ConfigSummary("🧠 AI", state.providerId, state.apiKey.isNotBlank())
                ConfigSummary("🌦️ Pogoda", state.openWeatherKey.take(8) + "...".takeIf { state.openWeatherKey.isNotBlank() } ?: "pominięto", state.openWeatherKey.isNotBlank())
                ConfigSummary("🎙️ Wake word", state.picovoiceKey.take(8) + "...".takeIf { state.picovoiceKey.isNotBlank() } ?: "pominięto", state.picovoiceKey.isNotBlank())
                ConfigSummary("📍 Lokalizacja", state.city.ifBlank { "pominięto" }, state.city.isNotBlank())
                ConfigSummary("🔐 Uprawnienia", "${countGranted(state)}/4 przyznane", countGranted(state) > 0)
                ConfigSummary(
                    "🔋 Bateria",
                    if (state.hasBatteryExemption) "wyjątek przyznany" else "brak wyjątku - ryzyko usypiania",
                    state.hasBatteryExemption
                )
                ConfigSummary("👓 Okulary", if (state.glassesPaired) "sparowane" else "później", state.glassesPaired)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Kliknij \"Zakończ\" żeby zacząć korzystać z V.I.C.T.O.R.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ConfigSummary(label: String, value: String, configured: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (configured) "✓" else "○",
            color = if (configured) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun countGranted(state: OnboardingState): Int = listOf(
    state.hasCalendarPermission,
    state.hasNotificationPermission,
    state.hasBluetoothPermission,
    state.hasLocationPermission
).count { it }

@Composable
private fun HorizontalDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}
