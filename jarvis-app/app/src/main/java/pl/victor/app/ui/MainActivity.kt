package pl.victor.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
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

        // Tryb konwersacyjny (nasłuch frazy prowadzi aplikacja, nie ekran)
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
     * Aktywuje tryb konwersacyjny, jeśli jest włączony.
     *
     * Nasłuch "Hej Wiktor" NIE startuje tutaj: trzyma mikrofon, więc jego cyklem
     * życia zarządza VictorApplication - uruchomiony z ekranu przeżywał jego
     * zamknięcie i blokował rozpoznawanie mowy. Patrz VictorApplication.refreshWake().
     */
    private fun initWakeWordAndConversationalMode() {
        val app = application as VictorApplication
        val settings = app.settings
        val orchestrator = app.orchestrator

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
