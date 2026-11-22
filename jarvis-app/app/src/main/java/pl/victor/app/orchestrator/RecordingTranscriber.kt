package pl.victor.app.orchestrator

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import pl.victor.app.AIOrchestrator.Companion.PLAIN_TASK_SYSTEM_PROMPT
import pl.victor.app.AIOrchestrator.Companion.RECORDING_SPEECH_PEAK
import pl.victor.app.AIOrchestrator.Companion.SOURCE_CLOUD
import pl.victor.app.AIOrchestrator.Companion.SOURCE_ON_DEVICE
import pl.victor.app.ai.AIProvider
import pl.victor.app.ai.AIProviderFactory
import pl.victor.app.data.SettingsRepository
import pl.victor.app.diagnostics.DiagFormat

/**
 * Przepisywanie NAGRAŃ na tekst: nagranie z okularów, PCM spotkania i - jako
 * ostatnia deska - przepis przez model.
 *
 * ## Po co osobna klasa
 * Wydzielone z [pl.victor.app.AIOrchestrator] BEZ ZMIANY ZACHOWANIA: ta sama
 * kolejność dróg (chmura -> rozpoznawanie na urządzeniu), te same progi i te
 * same wpisy w dzienniku. Korzystają z tego trzy miejsca naraz - tura
 * pytania, tłumaczenie ze słuchu i notatki ze spotkania - więc kolejność dróg
 * żyje w jednym pliku.
 *
 * Która droga przepisała ostatnie pytanie ([lastTranscriptionSource]) należy
 * dalej do orkiestratora - zapisuje ją też nasłuch telefonu w turze. Tu tylko
 * dopisujemy swoje drogi.
 */
internal class RecordingTranscriber(
    private val context: android.content.Context,
    private val settings: SettingsRepository,
    private val speechToText: pl.victor.app.conversation.SpeechToText,
    /** Dziennik leniwie - z tego samego powodu co `AIOrchestrator.diag`. */
    private val dziennik: () -> pl.victor.app.diagnostics.DiagnosticLog,
    /** Dostawca modelu dla przepisu przez model - patrz `AIOrchestrator.getOrCreateProvider`. */
    private val getOrCreateProvider: suspend () -> AIProvider,
    /** Która droga przepisała ostatnie pytanie - patrz `AIOrchestrator.lastTranscriptionSource`. */
    private val lastTranscriptionSource: MutableStateFlow<String?>
) {
    private val diag: pl.victor.app.diagnostics.DiagnosticLog
        get() = dziennik()

    /**
     * Przepisuje nagranie Z OKULARÓW wszystkimi dostępnymi drogami, po kolei.
     *
     * ## Dlaczego to jest jedna funkcja, a nie rozsypane próby
     * Bo kolejność jest tu całą treścią. Mikrofon okularów wisi przy ustach,
     * telefon leży w kieszeni - więc DOWOLNA droga licząca na nagraniu z okularów
     * jest lepsza od najlepszego nasłuchu telefonu. Do tej pory tak nie było:
     * chmura wyprzedzała telefon, ale rozpoznawanie systemowe czekało w
     * gałęzi ciszy, czyli wchodziły dopiero wtedy, gdy telefon nie usłyszał NIC.
     *
     * Kto nie wpisał klucza OpenAI, nie dostawał więc żadnej poprawy: telefon
     * słyszał "coś" i to "coś" wygrywało. Teraz bez klucza wchodzi rozpoznawanie
     * systemowe na tym samym nagraniu.
     *
     * @return tekst albo `null`, gdy żadna droga nie dała rady
     */
    suspend fun transcribeGlassesAudio(pcm: ByteArray, languageTag: String): String? {
        val wynik = transcribeGlassesAudioSteps(pcm, languageTag)
        // PRZERWANA TURA NIE MOŻE DOSTAĆ TEKSTU.
        //
        // Kolejne drogi transkrypcji siedzą w runCatching, a ono połyka też
        // anulowanie - funkcja oddawała wtedy null albo tekst, a przerwana tura
        // szła dalej: zadawała pytanie albo mówiła "nic nie słyszałem", mimo
        // dotknięcia zausznika w trakcie przepisywania.
        currentCoroutineContext().ensureActive()
        return wynik
    }

    private suspend fun transcribeGlassesAudioSteps(pcm: ByteArray, languageTag: String): String? {
        // Opus rozkodowuje się na 48 kHz, a rozpoznawanie mowy pracuje na 16 kHz.
        // Przeliczamy sami - podanie 48 kHz i liczenie na to, że usługa sobie
        // poradzi, byłoby zakładem o całą transkrypcję.
        val speechPcm = pl.victor.app.audio.PcmResampler.resample(
            pcm = pcm,
            sourceRate = pl.victor.app.audio.OpusDecoder.SAMPLE_RATE
        )
        return transcribeSpeechPcm(speechPcm, languageTag)
    }

    /**
     * Czy da się przepisać nagranie JAKĄKOLWIEK drogą (chmura, rozpoznawanie na
     * urządzeniu) - sprawdzane przed startem notatek ze spotkania, żeby nie
     * nagrywać godziny, po której nic się nie przepisze.
     */
    fun canTranscribeRecordings(): Boolean =
        (settings.isCloudTranscriptionEnabled() && cloudSpeechService() != null) ||
            speechToText.isOnDeviceAvailable()

    /** Przepisuje PCM 16 kHz mono: chmura -> rozpoznawanie systemowe na urządzeniu. */
    suspend fun transcribeSpeechPcm(speechPcm: ByteArray, languageTag: String): String? {
        val rate = pl.victor.app.audio.PcmResampler.SPEECH_SAMPLE_RATE

        transcribeInCloud(speechPcm, languageTag)?.let { return it }

        // Rozpoznawanie systemowe NA URZĄDZENIU - ten sam silnik co dyktowanie na
        // klawiaturze bez sieci. Darmowe i dobre, ale wymaga pobranego pakietu
        // języka; patrz SpeechToText.isOnDeviceAvailable i ekran ustawień.
        runCatching {
            speechToText.transcribe(pcm = speechPcm, sampleRate = rate, languageTag = languageTag)
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            Log.i(TAG, "Transkrypcja systemowa z nagrania okularów: \"$it\"")
            lastTranscriptionSource.value = SOURCE_ON_DEVICE
            return it
        }
        return null
    }

    /**
     * Ostatnia deska: przepisanie nagrania przez model, gdy przyjmuje dźwięk.
     *
     * Tylko dla krótkich wypowiedzi z mową (patrz [RECORDING_SPEECH_PEAK]) -
     * cisza wysłana do modelu kończy się zgadywaniem, a nie przepisem.
     *
     * @return tekst albo `null`, gdy model nie przyjmuje dźwięku, nie
     *   usłyszał mowy albo zawiódł
     */
    suspend fun transcribeWithModel(pcm: ByteArray, language: String): String? {
        val providerId = settings.getActiveProvider()
        if (!settings.hasApiKey(providerId) ||
            !AIProviderFactory.getCapabilitiesFor(providerId).supportsAudio
        ) {
            return null
        }
        val rate = pl.victor.app.audio.OpusDecoder.SAMPLE_RATE
        val szczyt = pl.victor.app.audio.VoiceTrim.measure(pcm, rate)?.peak ?: return null
        if (szczyt < RECORDING_SPEECH_PEAK) return null
        val wav = pl.victor.app.audio.WavWriter.wrap(pl.victor.app.audio.VoiceTrim.trim(pcm, rate), rate)
        val język = pl.victor.app.translation.SimultaneousTranslator.languageName(language)
        val prompt = "Przepisz dosłownie, co mówi osoba na załączonym nagraniu (język: $język). " +
            "Odpowiedz wyłącznie przepisanym tekstem, bez komentarza i bez cudzysłowów. " +
            "Jeśli nie słychać wyraźnej mowy, odpowiedz dokładnie: $MODEL_NO_SPEECH"
        val tekst = try {
            getOrCreateProvider().analyze(
                textQuestion = prompt,
                images = emptyList(),
                audioBytes = wav,
                systemPrompt = PLAIN_TASK_SYSTEM_PROMPT
            ).text.trim()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Przepisanie nagrania przez model nie powiodło się", e)
            null
        }
        val wynik = tekst?.takeIf {
            it.isNotBlank() && !it.contains(MODEL_NO_SPEECH) && it.length <= MODEL_TRANSCRIPT_MAX_CHARS
        }
        runCatching {
            diag.event(
                DiagFormat.Phase.TRANSKRYPCJA, "przepisanie nagrania przez model",
                mapOf("wynik" to wynik?.take(60), "odmowa" to (tekst?.contains(MODEL_NO_SPEECH) == true))
            )
        }
        return wynik
    }

    /**
     * Przepisuje nagranie przez usługę w chmurze - albo od razu oddaje `null`.
     *
     * Trzy warunki i wszystkie muszą być spełnione: ustawienie włączone, klucz
     * OpenAI wpisany, nagranie niepuste. Bez któregokolwiek nic nie wychodzi z
     * telefonu, a wołający idzie dalej swoją drogą.
     */
    private suspend fun transcribeInCloud(pcm: ByteArray, languageTag: String): String? {
        if (!settings.isCloudTranscriptionEnabled()) return null
        val (usługa, key) = cloudSpeechService() ?: return null
        val stt = pl.victor.app.conversation.CloudSpeechToText(usługa, key)
        val od = System.currentTimeMillis()
        val heard = runCatching {
            stt.transcribe(
                pcm = pcm,
                sampleRate = pl.victor.app.audio.PcmResampler.SPEECH_SAMPLE_RATE,
                languageTag = languageTag
            )
        }.onFailure { Log.w(TAG, "Transkrypcja w chmurze nie powiodła się", it) }.getOrNull()
        runCatching {
            diag.event(
                DiagFormat.Phase.NASŁUCH, "transkrypcja w chmurze",
                mapOf(
                    "usługa" to usługa.nazwa,
                    "ms" to (System.currentTimeMillis() - od),
                    "znaków" to (heard?.length ?: 0),
                    "błąd" to stt.ostatniBłąd
                )
            )
        }
        if (heard != null) {
            Log.i(TAG, "Transkrypcja z chmury (${usługa.nazwa}): \"$heard\"")
            lastTranscriptionSource.value = SOURCE_CLOUD
        }
        return heard
    }

    /**
     * Kto przepisuje nagranie w chmurze - dostawca wybrany w ustawieniach
     * (Gemini -> Google, OpenAI -> OpenAI), a przy pozostałych ten z dwóch
     * kluczy, który jest. Patrz [pl.victor.app.conversation.CloudSpeechToText.wybierz].
     */
    private fun cloudSpeechService() = pl.victor.app.conversation.CloudSpeechToText.wybierz(
        aktywny = settings.getActiveProvider(),
        kluczGemini = settings.getApiKey("gemini"),
        kluczOpenAi = settings.getApiKey("openai")
    )

    /**
     * Puste przepisania nagrania okularów z rzędu - patrz [GLASSES_TRANSCRIPT_MAX_MISSES].
     *
     * Trwałe, nie w pamięci procesu: licznik zerowany przy każdym starcie
     * aplikacji kosztował trzy daremne przepisania (po 1,5-4 s) na start - a
     * na tym telefonie przepisanie nagrania z okularów nie oddało tekstu ani
     * razu w ponad 250 próbach. Sukces zeruje licznik jak dotąd.
     */
    var glassesTranscriptMisses: Int
        get() = runtimePrefs.getInt(KEY_GLASSES_TRANSCRIPT_MISSES, 0)
        set(value) {
            runtimePrefs.edit().putInt(KEY_GLASSES_TRANSCRIPT_MISSES, value).apply()
        }

    private val runtimePrefs by lazy {
        context.getSharedPreferences(RUNTIME_PREFS, android.content.Context.MODE_PRIVATE)
    }

    /**
     * Czy przepisanie nagrania z okularów milczy od tylu prób, że przy gotowym
     * tekście z telefonu nie warto na nie czekać. Z włączoną transkrypcją w
     * chmurze próbujemy zawsze - to inna, dużo lepsza droga niż ta, która
     * milczała.
     */
    fun glassesTranscriptMissedTooOften(): Boolean =
        !settings.isCloudTranscriptionEnabled() &&
            glassesTranscriptMisses >= GLASSES_TRANSCRIPT_MAX_MISSES

    companion object {
        /** Po tylu pustych przepisaniach nagrania okularów z rzędu bierzemy od razu tekst z telefonu. */
        private const val GLASSES_TRANSCRIPT_MAX_MISSES = 3

        /** Stan, który ma przetrwać restart aplikacji, a nie jest ustawieniem. */
        private const val RUNTIME_PREFS = "victor_runtime"
        private const val KEY_GLASSES_TRANSCRIPT_MISSES = "glasses_transcript_misses"

        /** Umówiona odpowiedź modelu "w nagraniu nie ma mowy" - patrz [transcribeWithModel]. */
        private const val MODEL_NO_SPEECH = "BRAK"

        /** Dłuższy "przepis" krótkiej wypowiedzi to model, który się rozgadał. */
        private const val MODEL_TRANSCRIPT_MAX_CHARS = 400

        /** Ten sam znacznik logcata co orkiestrator - filtry logów działają jak dotąd. */
        private const val TAG = "AIOrchestrator"
    }
}
