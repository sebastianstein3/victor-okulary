package pl.victor.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import pl.victor.app.VictorApplication
import pl.victor.app.google.GoogleAccountManager
import pl.victor.app.ui.onboarding.OnboardingActivity
import pl.victor.app.ui.settings.SettingsActivity
import pl.victor.app.ui.theme.VictorTheme

/**
 * Główna aktywność - host dla Compose UI.
 *
 * Obsługuje:
 * - Auto-onboarding (pierwszy raz)
 * - Google Sign-In dla Calendar
 */
class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"
    private val viewModel: MainViewModel by viewModels()

    // Google Sign-In launcher
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Sprawdzanie samego resultCode było tu błędem, przez który logowanie
        // "raz działa, raz nie": Google Sign-In sygnalizuje błąd WEWNĄTRZ
        // intencji, więc trwała usterka konfiguracji (DEVELOPER_ERROR) wracała
        // nieodróżnialnie od cofnięcia się z ekranu wyboru konta - i w obu
        // przypadkach aplikacja milczała. Wynik czyta teraz GoogleAccountManager,
        // ten sam, którego używają Ustawienia.
        val settings = (application as VictorApplication).settings
        when (val outcome = GoogleAccountManager(this).handleSignInResult(result.data)) {
            is GoogleAccountManager.SignInOutcome.Success -> {
                settings.setGoogleAccountConnected(true)
                toast("Połączono konto: ${outcome.account.email ?: "Google"}")
            }
            is GoogleAccountManager.SignInOutcome.MissingConsent -> {
                // Zalogowany bez części zgód jest z punktu widzenia aplikacji
                // niepołączony - ale musi wiedzieć DLACZEGO, inaczej kliknie
                // "Połącz" jeszcze raz i podejmie tę samą decyzję.
                settings.setGoogleAccountConnected(false)
                toast(
                    "Brakuje zgód: ${outcome.missing.joinToString(", ")}. " +
                        "Zaloguj się ponownie i zaznacz wszystkie."
                )
            }
            GoogleAccountManager.SignInOutcome.Cancelled -> {
                settings.setGoogleAccountConnected(false)
                Log.i(tag, "Logowanie Google przerwane")
                // Nie milczymy: blokada ekranu zgody po stronie Google wraca tak
                // samo jak rezygnacja - patrz GoogleAccountManager.CANCELLED_HINT.
                toast(GoogleAccountManager.CANCELLED_HINT)
            }
            is GoogleAccountManager.SignInOutcome.Failed -> {
                settings.setGoogleAccountConnected(false)
                toast(outcome.message)
            }
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sprawdź czy onboarding był ukończony
        val settings = (application as VictorApplication).settings
        if (!settings.isOnboardingCompleted()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // Init wake word + conversational mode
        initWakeWordAndConversationalMode()

        setContent {
            VictorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        onOpenSettings = {
                            startActivity(Intent(this, SettingsActivity::class.java))
                        },
                        onRequestGoogleSignIn = {
                            launchGoogleSignIn()
                        }
                    )
                }
            }
        }
    }

    /**
     * Subskrybuje wake word eventów i aktywuje tryb konwersacyjny.
     */
    private fun initWakeWordAndConversationalMode() {
        val app = application as VictorApplication
        val settings = app.settings
        val wakeWord = app.wakeWordDetector
        val orchestrator = app.orchestrator

        // Subskrybuj wake word eventy
        lifecycleScope.launch {
            wakeWord.detectionEvent.collect { keyword ->
                Log.i(tag, "Wake word wykryty: $keyword")
                if (settings.isConversationalModeEnabled()) {
                    orchestrator.enableConversationalMode()
                } else {
                    // startVoiceQuestion(), NIE handleUserTrigger(). To drugie
                    // szło do modelu z PUSTYM pytaniem: fraza wybudzenia to
                    // sygnał "zaraz o coś zapytam", a nie samo pytanie. Model
                    // dostawał więc sam kontekst i odpowiadał na nic, zamiast
                    // poczekać, aż użytkownik powie, o co mu chodzi.
                    orchestrator.startVoiceQuestion()
                }
            }
        }

        // Vosk NIE startuje tutaj: trzyma mikrofon, więc jego cyklem życia
        // zarządza VictorApplication - inaczej nasłuch przeżywał zamknięcie
        // ekranu i blokował rozpoznawanie mowy. Patrz refreshVosk().

        // Init wake word jeśli włączony + jest klucz
        if (settings.isWakeWordEnabled() &&
            settings.getWakeEngine() != pl.victor.app.data.SettingsRepository.WAKE_ENGINE_VOSK &&
            settings.getPicovoiceAccessKey().isNotBlank()
        ) {
            lifecycleScope.launch {
                try {
                    val entry = settings.getSelectedWakeWordEntry()
                    val result = wakeWord.initialize(
                        accessKey = settings.getPicovoiceAccessKey(),
                        // Porcupine rozpoznaje nazwę wbudowanej komendy, nie
                        // wyświetlaną frazę - dla własnej frazy liczy się plik .ppn.
                        keyword = entry.porcupineKeyword ?: settings.getSelectedWakeWord(),
                        keywordPath = settings.getCustomKeywordPath(),
                        modelPath = settings.getCustomModelPath()
                    )
                    if (result.isSuccess) {
                        wakeWord.startListening()
                        Log.i(tag, "Wake word listening")
                    } else {
                        // Cicha porażka zostawiała użytkownika z włączonym
                        // przełącznikiem i martwym wykrywaniem.
                        Log.w(tag, "Wake word nie wystartował: ${result.message()}")
                        audio.speak(result.message(), language = "pl")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to init wake word", e)
                }
            }
        }

        // Aktywuj tryb konwersacyjny jeśli włączony w settings.
        //
        // afterUserAction = false: uruchomienie aplikacji NIE JEST poproszeniem
        // asystenta o rozmowę. Bez tego rozróżnienia nasłuch wstawał razem z
        // aplikacją i odpowiadał na pierwsze zdanie, jakie usłyszał w pokoju -
        // w dzienniku z 12 września poszło tak do modelu „bardzo by chciał",
        // czternaście sekund po starcie.
        if (settings.isConversationalModeEnabled()) {
            orchestrator.enableConversationalMode(afterUserAction = false)
        }
    }

    private val audio by lazy { pl.victor.app.audio.AudioManager.getInstance(this) }

    /**
     * Uruchamia Google Sign-In flow.
     */
    private fun launchGoogleSignIn() {
        try {
            val googleAccount = GoogleAccountManager(this)
            val signInIntent = googleAccount.getSignInIntent()
            googleSignInLauncher.launch(signInIntent)
        } catch (e: Exception) {
            // Cicha porażka tutaj znaczyła "kliknąłem Połącz i nic się nie
            // stało" - najgorszy możliwy komunikat, bo żaden.
            Log.e(tag, "Nie udało się uruchomić logowania Google", e)
            toast("Nie udało się otworzyć logowania Google: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // Po powrocie z Google Sign-In - sprawdź status
        val settings = (application as VictorApplication).settings
        try {
            val googleAccount = GoogleAccountManager(this)
            if (settings.isGoogleAccountConnected() && !googleAccount.isSignedIn()) {
                // User wylogował się z Google - reset flagi
                settings.setGoogleAccountConnected(false)
            } else if (!settings.isGoogleAccountConnected() && googleAccount.isSignedIn()) {
                // Auto-login (silent) zadziałał - ustaw flagę
                settings.setGoogleAccountConnected(true)
            }
        } catch (_: Exception) {}
    }
}
