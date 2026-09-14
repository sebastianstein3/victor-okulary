package pl.victor.app.ui.livepreview

import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.victor.app.VictorApplication
import pl.victor.app.diagnostics.DiagFormat
import pl.victor.app.stream.H264Renderer
import pl.victor.app.stream.RtspSession
import pl.victor.app.ui.theme.VictorTheme

/**
 * Podgląd na żywo z kamery okularów.
 *
 * ## Jak to działa
 * Okulary stawiają serwer RTSP na własnej sieci Wi-Fi - tej samej, której
 * używa galeria. Odbieramy go WŁASNYM klientem, nie media3.
 *
 * ## Czemu własnym
 * Bo media3 odrzucał ten strumień na starcie, 44 milisekundy po uruchomieniu:
 *
 *     ParserException: Malformed Attribute line: a=decode_buf=300
 *
 * Serwer w okularach (Hisilicon) wysyła w opisie sesji wiersz, którego media3
 * nie umie pominąć, a do tego nie podaje parametrów obrazu tam, gdzie media3
 * ich wymaga - przysyła je w strumieniu. Szczegóły i pomiary siedzą w
 * [pl.victor.app.stream.RtspProtocol].
 *
 * Telefon zachowuje przy tym internet: strumień idzie gniazdem wskazanym na
 * sieć okularów, a nie przez przypięcie całego procesu.
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

    sealed class State {
        object Idle : State()
        object Starting : State()
        object Playing : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Powierzchnia do rysowania - pojawia się, gdy system ją utworzy.
     *
     * Dekoder potrzebuje jej PRZED startem, więc widok jest na ekranie od
     * początku, a nie dopiero w stanie "gra". Wcześniejsza wersja pokazywała
     * odtwarzacz dopiero po sukcesie i przy własnym dekoderze byłoby to
     * zaklęcie: powierzchnia powstałaby dopiero wtedy, gdy jest już za późno.
     */
    @Volatile
    private var surface: Surface? = null

    private var session: RtspSession? = null
    private var renderer: H264Renderer? = null
    private var sessionJob: Job? = null

    fun onSurfaceReady(s: Surface) {
        surface = s
    }

    fun onSurfaceLost() {
        surface = null
    }

    fun start() {
        if (_state.value is State.Starting || _state.value is State.Playing) return
        _state.value = State.Starting
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            val url = victor.startLivePreview()
            if (url == null) {
                _state.value = State.Failed(
                    victor.lastTransferFailure ?: "Nie udało się włączyć podglądu."
                )
                return@launch
            }
            val target = surface
            if (target == null || !target.isValid) {
                _state.value = State.Failed("Ekran nie jest jeszcze gotowy - spróbuj ponownie.")
                victor.stopLivePreview()
                return@launch
            }

            val decoder = H264Renderer(target, ::report)
            if (!decoder.start()) {
                _state.value = State.Failed("Telefon nie dał dekodera H.264.")
                victor.stopLivePreview()
                return@launch
            }
            renderer = decoder

            // Sesja RTSP BLOKUJE aż do zerwania, więc idzie na własny wątek.
            // Dekoder karmimy z tego samego wątku - MediaCodec nie lubi
            // mieszania, a obraz i tak płynie prosto stamtąd.
            val rtsp = RtspSession(
                url = url,
                socketFactory = victor.glassesSocketFactory,
                onVideo = { nal -> decoder.feed(nal) },
                onPlaying = { _state.value = State.Playing },
                onEvent = ::report
            )
            session = rtsp
            if (victor.glassesSocketFactory == null) {
                // Bez wskazania sieci gniazdo poszłoby komórką i nie doszło do
                // okularów. Zapisujemy, bo inaczej ta awaria wygląda identycznie
                // jak milczący serwer.
                report("Podgląd: brak sieci okularów dla strumienia", emptyMap(), true)
            }

            val ok = withContext(Dispatchers.IO) { rtsp.run() }
            if (!ok && _state.value !is State.Idle) {
                _state.value = State.Failed(
                    rtsp.lastFailure ?: "Strumień z okularów się nie zestawił."
                )
            } else if (_state.value is State.Playing) {
                // Sesja skończyła się normalnie - strumień padł po drodze.
                _state.value = State.Failed("Strumień z okularów się urwał.")
            }
            decoder.release()
        }
    }

    /**
     * Zapisuje zdarzenie strumienia, z wagą podaną przez NADAWCĘ.
     *
     * Pierwsza wersja zgadywała wagę z treści komunikatu i wyszło z tego
     * dokładnie to, czego w dzienniku nie wolno: wiersz "PIERWSZA KLATKA NA
     * EKRANIE" - najlepsza wiadomość w całym przebiegu - trafił do dziennika
     * jako BŁĄD, bo "EKRANIE" zawiera "NIE". Kto zdarzenie wysyła, ten wie,
     * czy jest awarią; tekst tego nie wie.
     */
    private fun report(message: String, fields: Map<String, Any?>, problem: Boolean) {
        runCatching {
            diag.event(
                if (problem) DiagFormat.Phase.BŁĄD else DiagFormat.Phase.BLE,
                message,
                fields
            )
        }
    }

    fun stop() {
        _state.value = State.Idle
        session?.stop()
        session = null
        sessionJob?.cancel()
        sessionJob = null
        renderer?.release()
        renderer = null
        victor.stopLivePreview()
    }

    override fun onCleared() {
        super.onCleared()
        // Bez tego okulary zostają w trybie podglądu po zamknięciu ekranu i
        // odmawiają przy następnej próbie - tak samo jak przy trybie transferu.
        stop()
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
                // Powierzchnia jest na ekranie ZAWSZE, nie tylko gdy obraz gra.
                // Dekoder potrzebuje jej przed startem, więc pokazywanie jej
                // dopiero po sukcesie byłoby zaklęciem: powstałaby wtedy, gdy
                // jest już za późno, żeby jej użyć.
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) =
                                    viewModel.onSurfaceReady(h.surface)

                                override fun surfaceChanged(
                                    h: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) = viewModel.onSurfaceReady(h.surface)

                                override fun surfaceDestroyed(h: SurfaceHolder) =
                                    viewModel.onSurfaceLost()
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                when (state) {
                    is LivePreviewViewModel.State.Starting -> CircularProgressIndicator()
                    is LivePreviewViewModel.State.Playing -> Unit
                    else -> Text(
                        "Podgląd wyłączony",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (val current = state) {
                is LivePreviewViewModel.State.Idle ->
                    Button(onClick = { viewModel.start() }) { Text("Włącz podgląd") }
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
