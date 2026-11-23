package pl.victor.app.orchestrator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.AIOrchestrator.Companion.CAMERA_OFF_SPEECH
import pl.victor.app.OrchestratorState
import pl.victor.app.audio.AudioManager
import pl.victor.app.ble.VictorManager
import pl.victor.app.conversation.ConversationalMode
import pl.victor.app.data.SettingsRepository
import pl.victor.app.diagnostics.DiagFormat

/**
 * Komendy samych okularów na głos: film (start, stop, automatyczny koniec po
 * pięciu minutach), bateria, liczba plików i głośność.
 *
 * ## Po co osobna klasa
 * Wydzielone z [pl.victor.app.AIOrchestrator] BEZ ZMIANY ZACHOWANIA: te same
 * zdania i wpisy w dzienniku. Orkiestrator rozpoznaje komendę w warstwie
 * komend meta i oddaje ją tutaj; stan filmu nagrywanego na komendę należy
 * do tej klasy.
 */
internal class GlassesCommandHandler(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val audio: AudioManager,
    private val glassesManager: VictorManager,
    private val conversationalMode: ConversationalMode,
    /** Stan orkiestratora - komenda kończy się w nim jak tura (Completed). */
    private val state: MutableStateFlow<OrchestratorState>,
    /** Dziennik leniwie - z tego samego powodu co `AIOrchestrator.diag`. */
    private val dziennik: () -> pl.victor.app.diagnostics.DiagnosticLog,
    /** Czy wolno teraz mówić z tła - patrz `AIOrchestrator.ciszaWTle`. */
    private val ciszaWTle: () -> Boolean
) {
    private val diag: pl.victor.app.diagnostics.DiagnosticLog
        get() = dziennik()

    /** Stan filmu nagrywanego na komendę - patrz [handleGlassesCommand]. */
    private var videoAutoStopJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var videosBeforeRecording: Int? = null

    /**
     * Film, bateria i liczba plików - na głos.
     *
     * Okulary nagrywały film tylko z przycisku na oprawce albo z ekranu
     * diagnostyki, a stanu baterii nie dało się usłyszeć wcale.
     *
     * @return `false`, gdy komenda nie dotyczy tego, co teraz trwa (np.
     *   "zakończ nagrywanie" bez filmu - to może być spotkanie)
     */
    fun handleGlassesCommand(komenda: pl.victor.app.conversation.GlassesCommand): Boolean {
        val język = settings.getResponseLanguage()
        fun powiedz(tekst: String) {
            audio.speak(tekst, language = język)
            state.value = OrchestratorState.Completed(tekst)
        }
        if (komenda is pl.victor.app.conversation.GlassesCommand.VideoStop &&
            !glassesManager.videoRecording.value
        ) {
            return false
        }
        if (!glassesManager.isConnected()) {
            powiedz("Okulary nie są połączone.")
            return true
        }
        when (komenda) {
            is pl.victor.app.conversation.GlassesCommand.VideoStart -> {
                if (settings.isCameraFree()) {
                    powiedz(CAMERA_OFF_SPEECH)
                    return true
                }
                if (glassesManager.videoRecording.value) {
                    powiedz("Już nagrywam. Powiedz: zatrzymaj nagrywanie.")
                    return true
                }
                videosBeforeRecording = glassesManager.mediaCount.value?.videos
                diag.event(
                    DiagFormat.Phase.AKCJA, "film na komendę: start",
                    mapOf("filmówPrzed" to videosBeforeRecording)
                )
                // Zapowiedź PRZED komendą: inaczej nagrałaby się na filmie.
                scope.launch {
                    conversationalMode.onAiStartedSpeaking()
                    runCatching {
                        audio.speakAndAwait("Nagrywam. Powiedz: zatrzymaj nagrywanie.", language = język)
                    }
                    conversationalMode.onAiFinishedSpeaking()
                    glassesManager.startVideoRecording()
                }
                state.value = OrchestratorState.Completed("Nagrywam film.")
                // Zapomniany film zapełnia pamięć okularów i wyczerpuje baterię.
                videoAutoStopJob?.cancel()
                videoAutoStopJob = scope.launch {
                    delay(VIDEO_MAX_MS)
                    if (glassesManager.videoRecording.value) stopVideoOnCommand(automatycznie = true)
                }
            }
            is pl.victor.app.conversation.GlassesCommand.VideoStop -> stopVideoOnCommand(automatycznie = false)
            is pl.victor.app.conversation.GlassesCommand.Battery -> {
                val poziom = glassesManager.batteryLevel.value
                if (poziom == null) {
                    runCatching { glassesManager.requestBatteryLevel() }
                    powiedz("Okulary nie podały jeszcze stanu baterii. Zapytaj za chwilę.")
                } else {
                    powiedz(
                        "Okulary mają $poziom procent baterii" +
                            if (glassesManager.isCharging.value) " i się ładują." else "."
                    )
                }
            }
            is pl.victor.app.conversation.GlassesCommand.MediaCount -> {
                state.value = OrchestratorState.Completed("Sprawdzam pamięć okularów.")
                scope.launch {
                    val licznik = glassesManager.mediaCountNow() ?: glassesManager.mediaCount.value
                    powiedz(
                        if (licznik == null) {
                            "Okulary nie podały liczby plików."
                        } else {
                            "W okularach: zdjęć ${licznik.images}, filmów ${licznik.videos}, " +
                                "nagrań ${licznik.records}."
                        }
                    )
                }
            }
        }
        return true
    }

    /** Kafelek "Zakończ" przy filmie na ekranie głównym - ta sama droga co głos. */
    fun stopVideoFromUi() {
        if (glassesManager.videoRecording.value) stopVideoOnCommand(automatycznie = false)
    }

    /**
     * Kończy film i SPRAWDZA, czy okulary go zapisały.
     *
     * Komenda nagrywania nie była dotąd potwierdzona na sprzęcie z tej drogi,
     * więc nie mówimy "zapisane" na wiarę: porównujemy liczbę filmów przed i po.
     */
    private fun stopVideoOnCommand(automatycznie: Boolean) {
        videoAutoStopJob?.cancel()
        videoAutoStopJob = null
        glassesManager.stopVideoRecording()
        val przed = videosBeforeRecording
        videosBeforeRecording = null
        state.value = OrchestratorState.Completed("Zatrzymuję nagrywanie.")
        scope.launch {
            delay(VIDEO_SAVE_SETTLE_MS)
            val po = glassesManager.mediaCountNow()?.videos
            runCatching {
                diag.event(
                    DiagFormat.Phase.AKCJA, "film na komendę: stop",
                    mapOf("filmówPrzed" to przed, "filmówPo" to po, "automatycznie" to automatycznie)
                )
            }
            val wstęp = if (automatycznie) "Zatrzymałem nagrywanie po pięciu minutach. " else ""
            val speech = wstęp + when {
                przed != null && po != null && po > przed ->
                    "Film zapisany w okularach. Znajdziesz go w galerii aplikacji."
                przed != null && po != null ->
                    "Okulary nie zgłosiły nowego filmu - możliwe, że się nie nagrał."
                else -> "Nagrywanie zatrzymane."
            }
            if (automatycznie && !ciszaWTle()) return@launch
            audio.speak(speech, language = settings.getResponseLanguage())
            state.value = OrchestratorState.Completed(speech)
        }
    }

    /**
     * "Głośniej" / "ciszej" / "głośność na 70 procent".
     *
     * Zmienia DWIE rzeczy naraz, bo na głośność mowy w okularach składają się
     * obie: poziom strumienia w telefonie i własna głośność okularów (z
     * dziennika: muzyka stała na 10 z 16, czyli nigdy na pełnej). Własną
     * głośność okularów ustawiamy komendą SDK - patrz
     * [pl.victor.app.ble.GlassesVolume].
     */
    fun applyVolumeCommand(komenda: pl.victor.app.conversation.VolumeCommand) {
        val kroki = when (komenda) {
            is pl.victor.app.conversation.VolumeCommand.Up -> 1
            is pl.victor.app.conversation.VolumeCommand.Down -> -1
            is pl.victor.app.conversation.VolumeCommand.SetPercent -> 0
        }
        val procent = (komenda as? pl.victor.app.conversation.VolumeCommand.SetPercent)?.percent
        val telefon = audio.changeSpeechVolume(kroki, procent)
        val cel = glassesManager.volume.value
            ?.takeIf { it.writable && glassesManager.canSetVolume() }
            ?.let { if (procent != null) it.withSpeechPercent(procent) else it.withSpeechStepped(kroki) }
        val okulary = cel != null && glassesManager.setGlassesVolume(cel)
        runCatching {
            diag.event(
                DiagFormat.Phase.AKCJA, "głośność na komendę",
                mapOf(
                    "kroki" to kroki, "procent" to procent,
                    "telefonProcent" to telefon,
                    "okularyProcent" to cel?.percent(), "okularyWysłane" to okulary
                )
            )
        }
        val wynik = if (okulary) cel?.percent() else telefon
        val speech = when {
            wynik == null -> "Nie mogę teraz zmienić głośności."
            wynik >= 100 -> "Głośność maksymalna."
            else -> "Głośność $wynik procent."
        }
        audio.speak(speech, language = settings.getResponseLanguage())
        state.value = OrchestratorState.Completed(speech)
    }

    companion object {
        /** Film nagrywany na komendę kończymy sami po tylu minutach. */
        private const val VIDEO_MAX_MS = 5 * 60 * 1000L

        /** Ile dać okularom na zapisanie filmu, zanim zapytamy o liczniki. */
        private const val VIDEO_SAVE_SETTLE_MS = 2_500L
    }
}
