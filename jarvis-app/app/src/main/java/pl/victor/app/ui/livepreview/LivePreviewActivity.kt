package pl.victor.app.ui.livepreview

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.VictorApplication
import pl.victor.app.ui.theme.VictorTheme

/**
 * Podgląd na żywo z kamery okularów.
 *
 * ## Jak to działa
 * Okulary stawiają serwer RTSP na własnej sieci Wi-Fi - dokładnie tej samej,
 * której używa galeria. Różnica jest jedna: zamiast pobierać pliki po HTTP,
 * odtwarzamy strumień.
 *
 * ## Czego użytkownik musi być świadomy
 * Póki podgląd trwa, telefon jest w sieci okularów i NIE MA INTERNETU. Dlatego
 * ekran mówi to wprost, a wyjście z niego kończy podgląd komendą - okulary
 * zostawione w tym trybie odmówiłyby następnym razem.
 */
class LivePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { LivePreviewScreen(onBack = { finish() }) } }
    }
}

class LivePreviewViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val victor = (app as VictorApplication).glassesManager
    private val diag = (app as VictorApplication).diag

    /**
     * Kontekst wzięty raz, jawnie.
     *
     * `getApplication()` jest generyczne (`<T : Application>`), więc bez typu
     * docelowego kompilator nie wie, co dostaje - i całe wyrażenie z nim w
     * środku przestaje się rozwiązywać.
     */
    private val appContext: android.content.Context = app.applicationContext

    sealed class State {
        object Idle : State()
        object Starting : State()
        object Playing : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var exoPlayer: ExoPlayer? = null

    /** Nazwa inna niż pola: `player` byłoby i polem, i funkcją naraz. */
    fun player(): ExoPlayer? = exoPlayer

    fun start() {
        if (_state.value is State.Starting || _state.value is State.Playing) return
        _state.value = State.Starting
        viewModelScope.launch {
            val url = victor.startLivePreview()
            if (url == null) {
                _state.value = State.Failed(
                    victor.lastTransferFailure ?: "Nie udało się włączyć podglądu."
                )
                return@launch
            }
            openPlayer(url)

            // LIMIT CZASU NA PIERWSZĄ KLATKĘ.
            //
            // Odtwarzacz zgłasza się sam tylko wtedy, gdy obraz ruszy albo gdy
            // padnie z błędem. Serwer, który przyjmuje połączenie i milczy,
            // zostawia go w buforowaniu BEZ KOŃCA - a ekran w "Podnoszę..."
            // równie długo. W dzienniku z 14 września widać skutek: trzy próby
            // pod rząd, bo nie było czym odróżnić czekania od zawieszenia.
            kotlinx.coroutines.delay(FIRST_FRAME_TIMEOUT_MS)
            if (_state.value is State.Starting) {
                diag.event(
                    pl.victor.app.diagnostics.DiagFormat.Phase.BŁĄD,
                    "Podgląd: brak obrazu w limicie czasu",
                    mapOf("ms" to FIRST_FRAME_TIMEOUT_MS, "adres" to url)
                )
                _state.value = State.Failed(
                    "Połączenie z okularami stoi, ale obraz nie ruszył przez " +
                        "${FIRST_FRAME_TIMEOUT_MS / 1000} sekund."
                )
                releasePlayer()
            }
        }
    }

    private fun openPlayer(url: String) {
        releasePlayer()
        val exo: ExoPlayer = ExoPlayer.Builder(appContext).build()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Każdy stan do dziennika, nie tylko sukces. "Buforuje" i
                // "skończył" to dwie różne odpowiedzi na pytanie, czemu nie ma
                // obrazu, a bez nich obie wyglądają jak cisza.
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "bezczynny"
                    Player.STATE_BUFFERING -> "buforuje"
                    Player.STATE_READY -> "OBRAZ LECI"
                    Player.STATE_ENDED -> "strumień się skończył"
                    else -> "stan $playbackState"
                }
                runCatching {
                    diag.event(
                        pl.victor.app.diagnostics.DiagFormat.Phase.BLE,
                        "Podgląd: odtwarzacz - $name"
                    )
                }
                if (playbackState == Player.STATE_READY) _state.value = State.Playing
            }

            override fun onPlayerError(error: PlaybackException) {
                // Błąd odtwarzacza to INNA awaria niż brak sieci i musi mieć
                // inny komunikat: sieć stoi, adres jest znany, ale okulary nie
                // wysyłają obrazu. To jedyny stan, który mówi, że sam serwer
                // RTSP nie działa - i tego właśnie nie wiedzieliśmy do tej pory.
                Log.w(TAG, "Odtwarzacz nie odebrał obrazu", error)
                runCatching {
                    diag.event(
                        pl.victor.app.diagnostics.DiagFormat.Phase.BŁĄD,
                        "Podgląd: błąd odtwarzacza",
                        mapOf(
                            "kod" to error.errorCodeName,
                            "treść" to error.message?.take(80),
                            "przyczyna" to causeChain(error)
                        )
                    )
                }
                _state.value = State.Failed(
                    "Sieć okularów stoi, ale nie przysyłają obrazu (${error.errorCodeName})."
                )
            }
        }
        exo.addListener(listener)
        exo.setMediaSource(buildSource(url))
        exo.prepare()
        exo.playWhenReady = true
        exoPlayer = exo
    }

    /**
     * Rozwija łańcuch przyczyn wyjątku do jednego wiersza.
     *
     * ## Czemu samo `message` nie wystarczyło
     * Bo media3 pakuje KAŻDY błąd RTSP w `RtspPlaybackException`, a `Player`
     * pokazuje potem własny, ogólny komunikat. W dzienniku z 14 września
     * widać, co to daje:
     *
     *     Podgląd: błąd odtwarzacza  kod=ERROR_CODE_IO_UNSPECIFIED treść=Source error
     *
     * "Source error" pasuje do wszystkiego - do zerwanej sieci tak samo jak do
     * opisu strumienia, którego ten odtwarzacz nie przyjmuje. Prawdziwe zdanie
     * (np. "missing sprop parameter" albo "missing attribute control") siedzi
     * dopiero w przyczynie i bez tego rozwinięcia nigdy do nas nie docierało.
     */
    private fun causeChain(error: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = error.cause
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            parts += current.javaClass.simpleName + ": " + (current.message ?: "brak treści")
            current = current.cause
            depth++
        }
        return parts.joinToString(" <- ").ifEmpty { "brak przyczyny" }.take(MAX_CAUSE_CHARS)
    }

    /**
     * Składa źródło RTSP tak, jak robią to okulary - a nie tak, jak media3 woli.
     *
     * ## Dlaczego TCP, a nie domyślne UDP
     * Bo tak strumień odbiera oryginalna aplikacja producenta: jej odtwarzacz
     * dostaje `--rtsp-tcp` i `:rtsp-tcp`, czyli RTP wpleciony w to samo
     * połączenie TCP, którym idzie sterowanie. media3 domyślnie próbuje UDP i
     * osobnych gniazd - jeśli serwer w okularach umie tylko TCP, negocjacja
     * kończy się błędem, mimo że sieć i adres są w porządku. To najlepsze
     * wyjaśnienie "sieć stoi, obrazu nie ma", jakie mamy.
     *
     * ## Dlaczego fabryka gniazd
     * Żeby podgląd nie odcinał telefonu od internetu. Gniazda z sieci okularów
     * bierze tylko ten jeden strumień; reszta aplikacji, w tym rozmowa z AI,
     * zostaje przy zwykłym połączeniu. Działa to wyłącznie w parze z TCP -
     * przy UDP media3 otwiera własne gniazda z pominięciem fabryki.
     */
    private fun buildSource(url: String): RtspMediaSource {
        val factory = RtspMediaSource.Factory().setForceUseRtpTcp(true)
        val sockets = victor.glassesSocketFactory
        if (sockets != null) {
            factory.setSocketFactory(sockets)
        } else {
            // Bez fabryki strumień poleci domyślną siecią i nie dojdzie do
            // okularów. Zapisujemy to, bo inaczej awaria wygląda identycznie
            // jak brak serwera RTSP.
            runCatching {
                diag.event(
                    pl.victor.app.diagnostics.DiagFormat.Phase.BŁĄD,
                    "Podgląd: brak sieci okularów dla odtwarzacza"
                )
            }
        }
        return factory.createMediaSource(MediaItem.fromUri(Uri.parse(url)))
    }

    fun stop() {
        releasePlayer()
        victor.stopLivePreview()
        _state.value = State.Idle
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        // Bez tego okulary zostają w trybie podglądu po zamknięciu ekranu i
        // odmawiają przy następnej próbie - tak samo jak przy trybie transferu.
        stop()
    }

    private companion object {
        const val TAG = "LivePreview"

        /**
         * Ile czekać na pierwszą klatkę, zanim uznamy, że nie będzie.
         *
         * Hojnie: sieć okularów właśnie wstała, a RTSP negocjuje sesję. Ale
         * skończenie: ekran bez limitu to ekran, na którym nie da się odróżnić
         * czekania od zawieszenia.
         */
        const val FIRST_FRAME_TIMEOUT_MS = 15_000L

        /** Ile poziomów przyczyn rozwijać - głębiej to już ślad stosu, nie diagnoza. */
        const val MAX_CAUSE_DEPTH = 5

        /** Ile znaków łańcucha przyczyn zapisać. */
        const val MAX_CAUSE_CHARS = 300
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LivePreviewScreen(onBack: () -> Unit) {
    val viewModel: LivePreviewViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Podgląd na żywo") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    is LivePreviewViewModel.State.Playing -> AndroidView(
                        factory = { ctx -> PlayerView(ctx) },
                        update = { view -> view.player = viewModel.player() },
                        modifier = Modifier.fillMaxSize()
                    )
                    is LivePreviewViewModel.State.Starting -> CircularProgressIndicator()
                    else -> Text(
                        "Podgląd wyłączony",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (val current = state) {
                is LivePreviewViewModel.State.Idle -> {
                    Button(onClick = { viewModel.start() }) { Text("Włącz podgląd") }
                    Text(
                        "Uwaga: w czasie podglądu telefon jest w sieci okularów " +
                            "i nie ma internetu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is LivePreviewViewModel.State.Starting -> Text(
                    "Podnoszę sieć okularów...",
                    style = MaterialTheme.typography.bodySmall
                )
                is LivePreviewViewModel.State.Playing ->
                    Button(onClick = { viewModel.stop() }) { Text("Zakończ podgląd") }
                is LivePreviewViewModel.State.Failed -> {
                    Text(current.reason, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { viewModel.start() }) { Text("Spróbuj ponownie") }
                }
            }
        }
    }
}
