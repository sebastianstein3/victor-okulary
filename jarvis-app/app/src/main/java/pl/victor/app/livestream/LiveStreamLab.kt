package pl.victor.app.livestream

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.victor.app.ble.VictorManager
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Laboratoryjny, gated moduł do prób wykrycia i podejrzenia trybu 8 (live
 * streaming RTSP) w okularach HeyCyan.
 *
 * ## Kontekst i bezpieczeństwo - PRZECZYTAJ PRZED ZMIANĄ TEGO PLIKU
 * Analiza firmware (CyanBridge `docs/LIVE_PREVIEW_TEST_MODE.md`) wskazuje na
 * ukryty tryb 8 = `ai_glass_livestream`, serwer RTSP na porcie 554. Komenda BLE,
 * która ten tryb aktywuje, NIE jest znana - nawet twórcy CyanBridge świadomie
 * NIE wysyłają żadnej komendy z automatu, tylko biernie nasłuchują. Mają nawet
 * test w kodzie (`LivePreviewSourceSafetyTest`), który wysadza im build, jeśli
 * ktoś doda wysyłanie komend do ich odpowiednika tej klasy.
 *
 * Ten moduł idzie dalej - na wyraźną prośbę użytkownika, który kupił dodatkowy
 * sprzęt przeznaczony do tych testów - i pozwala wysłać DWIE komendy, których
 * znaczenie jest niepotwierdzone (0x07, 0x0D - patrz [GlassesProtocol]).
 * Zasady, których nie wolno złamać przy edycji tego pliku:
 * - NIGDY nie wysyłaj 0x0A (potwierdzony factory reset) ani żadnego bajtu
 *   spoza udokumentowanej tabeli komend.
 * - Wysyłka komendy zawsze pojedyncza, inicjowana ręcznie z UI po potwierdzeniu
 *   - nigdy automatyczna pętla próbująca kolejnych bajtów.
 * - Każdy krok (komenda, odpowiedź, stan P2P, próba RTSP, stan odtwarzacza)
 *   idzie do [LiveStreamLabLog] - to jedyny ślad po tym, co się stało ze sprzętem.
 *
 * Dostępny wyłącznie z gated ekranu "Opcje programistyczne" - patrz
 * `pl.victor.app.ui.developer.DeveloperOptionsActivity`.
 */
class LiveStreamLab(context: Context) {

    private val appContext = context.applicationContext
    private val victor = VictorManager.getInstance(appContext)

    /** Log tej sesji - eksportowalny, patrz [LiveStreamLabLog]. */
    val log = LiveStreamLabLog(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var probeJob: Job? = null

    private val _state = MutableStateFlow<LabState>(LabState.Idle)
    val state: StateFlow<LabState> = _state.asStateFlow()

    @Volatile
    private var exoPlayer: ExoPlayer? = null

    /** Odtwarzacz aktywnego strumienia - `null` dopóki [LabState.Playing]. */
    fun getPlayer(): ExoPlayer? = exoPlayer

    // === Komendy - każda wymaga jawnego wywołania z UI po potwierdzeniu ===

    /** Wysyła niepotwierdzoną komendę 0x07 (kandydat na aktywację trybu 8). */
    fun sendCommand07() {
        log.append("COMMAND", "Wysyłam 0x07 (nieznana, kandydat na mode 8)")
        victor.sendExperimentalCommand07 { error ->
            log.append("COMMAND_RESULT", "0x07 -> errorCode=$error")
        }
    }

    /** Wysyła niepotwierdzoną komendę 0x0D (kandydat na aktywację trybu 8). */
    fun sendCommand0D() {
        log.append("COMMAND", "Wysyłam 0x0D (nieznana, kandydat na mode 8)")
        victor.sendExperimentalCommand0D { error ->
            log.append("COMMAND_RESULT", "0x0D -> errorCode=$error")
        }
    }

    /** Restartuje okulary - potwierdzona, bezpieczna komenda odzyskiwania. */
    fun sendRestartDevice() {
        log.append("COMMAND", "Wysyłam 0x0E (restart urządzenia - odzyskiwanie)")
        victor.restartDeviceExperimental { error ->
            log.append("COMMAND_RESULT", "0x0E -> errorCode=$error")
        }
    }

    // === Próba RTSP - bezpieczna sama w sobie, nic nie wysyła do okularów ===

    /**
     * Łączy się pasywnie (bez wysłanej komendy) i szuka serwera RTSP na
     * okularach. Wołaj po aktywacji trybu 8 - komendą powyżej albo zewnętrznie.
     * Bez skutku, jeśli tryb 8 nie jest aktywny - po prostu nic nie znajdzie.
     */
    fun startProbe() {
        if (probeJob?.isActive == true) return
        log.startSession()
        _state.value = LabState.ConnectingP2p
        probeJob = scope.launch { runProbe() }
    }

    /** Zatrzymuje próbę/odtwarzanie i zwalnia sieć P2P. */
    fun stopProbe() {
        probeJob?.cancel()
        probeJob = null
        releasePlayer()
        // Komendą, nie samym rozłączeniem sieci: okulary zostawione w trybie
        // podglądu odmówią następnym razem - tak samo jak przy transferze.
        victor.stopLivePreview()
        _state.value = LabState.Idle
        log.append("INFO", "Zatrzymano - wyłączyłem podgląd i zwolniłem sieć")
    }

    private suspend fun runProbe() {
        // KONIEC ZGADYWANIA - ZNAMY KOMENDĘ.
        //
        // Ta klasa powstała, gdy nie wiedzieliśmy, czym się włącza strumień:
        // łączyła się biernie i sprawdzała czternaście kombinacji portu i
        // ścieżki, licząc, że trafi. Adres trafiała od początku (port 8554,
        // ścieżka `ch0`), ale serwer nigdy nie odpowiadał, bo nikt go nie
        // uruchomił.
        //
        // Komenda to `0x02 0x01 0x14 <rodzaj sieci>` - ten sam kształt co przy
        // trybie transferu plików. Zgadywaliśmy ją wśród bajtów 0x07 i 0x0D.
        log.append("INFO", "Start: włączam podgląd na żywo komendą 0x14")
        _state.value = LabState.ConnectingP2p

        val url = victor.startLivePreview()
        if (url == null) {
            val reason = victor.lastTransferFailure ?: "nieznany powód"
            log.append("ERROR", "Nie udało się podnieść podglądu: $reason")
            _state.value = LabState.Error(reason)
            return
        }

        log.append("INFO", "Strumień pod $url - uruchamiam odtwarzacz")
        _state.value = LabState.ProbingRtsp(url)

        if (!tryPlayUrl(url)) {
            log.append(
                "ERROR",
                "Sieć stoi i adres jest znany, ale odtwarzacz nie odebrał obrazu"
            )
            _state.value = LabState.Error("Strumień nie ruszył pod $url")
            return
        }

        log.append("SUCCESS", "Obraz leci: $url")
        _state.value = LabState.Playing(url)
    }

    private suspend fun tryPlayUrl(url: String): Boolean = withContext(Dispatchers.Main) {
        val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val player = ExoPlayer.Builder(appContext).build()
                val mediaSource = RtspMediaSource.Factory()
                    .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
                var resolved = false

                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (resolved) return
                        when (playbackState) {
                            Player.STATE_READY -> {
                                resolved = true
                                exoPlayer = player
                                if (cont.isActive) cont.resume(true) {}
                            }
                            Player.STATE_ENDED -> {
                                resolved = true
                                player.release()
                                if (cont.isActive) cont.resume(false) {}
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (resolved) return
                        resolved = true
                        log.append("RTSP_PROBE", "Błąd dla $url: ${error.errorCodeName}")
                        player.release()
                        if (cont.isActive) cont.resume(false) {}
                    }
                }

                player.addListener(listener)
                cont.invokeOnCancellation {
                    player.removeListener(listener)
                    player.release()
                }
                player.setMediaSource(mediaSource)
                player.playWhenReady = true
                player.prepare()
            }
        }
        result ?: false
    }


    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 3_000L
        private const val PORT_CHECK_TIMEOUT_MS = 2_000
    }
}

/** Stan sesji Live Stream Lab. */
sealed class LabState {
    object Idle : LabState()
    object ConnectingP2p : LabState()
    /** Sieć stoi, adres strumienia znany - czekamy na pierwszą klatkę. */
    data class ProbingRtsp(val url: String) : LabState()
    data class Playing(val url: String) : LabState()
    data class Error(val message: String) : LabState()
}
