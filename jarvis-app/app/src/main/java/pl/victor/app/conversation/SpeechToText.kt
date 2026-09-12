package pl.victor.app.conversation

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Rozpoznawanie mowy przez systemowy [SpeechRecognizer].
 *
 * Bez tego [ConversationalMode] nie miał skąd wziąć tekstu: `deliverSpeech()`
 * nie było wołane z żadnego miejsca w aplikacji, więc tryb konwersacyjny
 * zawsze kończył się timeoutem, nie usłyszawszy ani słowa.
 *
 * ## Dwie pułapki, które to API ma
 * 1. `SpeechRecognizer` **musi** być tworzony i wołany z wątku głównego -
 *    z innego rzuca wyjątkiem. Stąd `withContext(Dispatchers.Main)`.
 * 2. Instancja jest jednorazowa w praktyce: po `onError`/`onResults` bywa
 *    w stanie, z którego kolejne `startListening()` nie wraca. Dlatego każde
 *    nasłuchiwanie dostaje świeżą instancję, zwalnianą w `finally`.
 *
 * Mikrofon jest wyłączny - jeśli trzyma go wykrywanie słowa kluczowego
 * (Porcupine), rozpoznawanie nie dostanie dźwięku. Zatrzymanie go na czas
 * słuchania należy do wołającego.
 */
class SpeechToText(private val context: Context) {

    private val tag = TAG
    private val bluetoothRouter = pl.victor.app.audio.BluetoothAudioRouter.getInstance(context)

    /** Wątek, z którego sypiemy dźwięk do potoku - patrz [transcribe]. */
    private val writerScope = CoroutineScope(Dispatchers.IO)

    /**
     * Kod błędu z ostatniego nasłuchu - albo `null`, gdy poszło dobrze.
     *
     * `listen()` zwraca `null` przy KAŻDYM niepowodzeniu: przy ciszy, przy
     * zajętym mikrofonie, przy braku sieci i przy braku uprawnienia. Dla
     * użytkownika wyglądało to zawsze tak samo - "nic nie usłyszałem" - i to
     * była dokładnie zgłoszona sytuacja "aplikacja nie reaguje, jakby nic do
     * niej nie docierało". Kod trzymamy, żeby dało się powiedzieć, CO się stało.
     */
    @Volatile
    private var lastErrorCode: Int? = null

    /**
     * Opis ostatniego niepowodzenia albo `null`, gdy nasłuch skończył się
     * normalnie - czyli tekstem albo zwykłą ciszą.
     *
     * Cisza i brak dopasowania celowo NIE są tu raportowane: to normalny koniec
     * nasłuchu, a nie awaria, o której warto meldować użytkownikowi.
     */
    fun lastFailureReason(): String? {
        val code = lastErrorCode ?: return null
        if (code == SpeechRecognizer.ERROR_NO_MATCH ||
            code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        ) {
            return null
        }
        return describeError(code)
    }

    /** Czy urządzenie w ogóle ma rozpoznawanie mowy (emulator bywa go pozbawiony). */
    /**
     * Czy telefon umie rozpoznawać mowę BEZ SIECI.
     *
     * ## Dlaczego to jest najważniejsza droga lokalna
     * Bo to ten sam silnik, którym dyktuje się na klawiaturze w trybie offline -
     * dla polszczyzny jest nieporównanie lepszy od modelu Voska, jest darmowy i
     * działa przy zablokowanym ekranie. Wymaga tylko POBRANEGO PAKIETU JĘZYKA, a
     * tego nie ma na telefonie domyślnie.
     *
     * Aplikacja korzystała z niego od dawna, ale gdy pakietu brakowało, po cichu
     * schodziła niżej - i nikt nie wiedział, że jednym pobraniem można mieć
     * znacznie lepsze rozpoznawanie za darmo. Stąd ta metoda: żeby dało się to
     * pokazać w ustawieniach zamiast milczeć.
     */
    fun isOnDeviceAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
            .getOrDefault(false)
    }

    fun isAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * Słucha jednej wypowiedzi i zwraca rozpoznany tekst.
     *
     * Próbuje najpierw urządzenia audio Bluetooth (patrz [pl.victor.app.audio.BluetoothAudioRouter]) -
     * bez podłączonego urządzenia albo gdy się nie uda, wraca na mikrofon
     * telefonu bez dodatkowego kroku. Negocjacja Bluetooth idzie POZA
     * [timeoutMs], żeby nie zjadała budżetu czasu na samo słuchanie.
     *
     * @param languageTag język w formacie BCP-47 (np. `pl-PL`); domyślnie z ustawień systemu
     * @param timeoutMs twardy limit - `SpeechRecognizer` potrafi nie oddać sterowania
     * @return rozpoznany tekst albo `null` przy ciszy, błędzie lub przekroczeniu czasu
     */
    suspend fun listen(
        languageTag: String = Locale.getDefault().toLanguageTag(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        useBluetoothMic: Boolean = true
    ): String? {
        if (!isAvailable()) {
            Log.w(tag, "Rozpoznawanie mowy niedostępne na tym urządzeniu")
            return null
        }
        // Router jest zliczany: gdy orkiestrator trzyma łącze na całą rozmowę,
        // to wywołanie tylko dokłada odwołanie i nie ma żadnej przerwy w dźwięku.
        lastErrorCode = null
        // TO WYWOŁANIE ZAJMOWAŁO PROFIL ROZMOWY ZAWSZE - I TO BYŁ BŁĄD.
        //
        // Mikrofon zestawu Bluetooth działa wyłącznie przez SCO/HFP, a negocjacja
        // trwa do czterech sekund. Gdy okulary nadają dźwięk WŁASNĄ drogą po BLE,
        // ten profil nie jest do niczego potrzebny: telefon ma słuchać swoim
        // mikrofonem jako zapas, a nie przejmować okulary.
        //
        // Skutki były dwa i oba zgłoszone jako nienaprawione. Po pierwsze, cztery
        // sekundy ciszy przed każdym rozpoznaniem - poprzednia próba zdjęcia tego
        // ze ścieżki krytycznej nic nie dała, bo zajęcie profilu siedziało TUTAJ,
        // nie w orkiestratorze. Po drugie, okulary zostawały urządzeniem "do
        // połączeń": każda tura brała profil, a router trzyma go jeszcze osiem
        // sekund po zwolnieniu.
        val usedBluetooth = useBluetoothMic && bluetoothRouter.acquire()
        try {
            return withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Main) { listenOnMainThread(languageTag) }
            }
        } finally {
            if (usedBluetooth) bluetoothRouter.release()
        }
    }

    private suspend fun listenOnMainThread(languageTag: String): String? =
        suspendCancellableCoroutine { continuation ->
            val recognizer = createRecognizer()
            if (recognizer == null) {
                Log.w(tag, "Nie udało się utworzyć SpeechRecognizer")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            // onError i onResults potrafią przyjść oba - wznawiamy dokładnie raz.
            val resumed = AtomicBoolean(false)
            fun finish(result: String?) {
                if (resumed.compareAndSet(false, true)) {
                    runCatching { recognizer.destroy() }
                    continuation.resume(result)
                }
            }

            recognizer.setRecognitionListener(listener(::finish))
            continuation.invokeOnCancellation {
                // Anulowanie (np. z withTimeoutOrNull) przychodzi z dowolnego wątku,
                // a SpeechRecognizer wolno ruszać tylko z głównego - stąd post().
                if (resumed.compareAndSet(false, true)) {
                    Handler(Looper.getMainLooper()).post {
                        runCatching { recognizer.cancel() }
                        runCatching { recognizer.destroy() }
                    }
                }
            }

            runCatching { recognizer.startListening(intent(languageTag)) }
                .onFailure {
                    Log.w(tag, "startListening nie powiodło się", it)
                    finish(null)
                }
        }

    /**
     * Przepisuje na tekst GOTOWE nagranie - dźwięk, którego nie nagrał telefon.
     *
     * ## Po co to w ogóle jest
     * Mowa z okularów przychodzi po BLE jako Opus i nigdy nie przechodzi przez
     * mikrofon telefonu, więc [listen] jej nie widzi. Dotąd trafiała do modelu
     * jako załącznik WAV i to był dokładnie zgłoszony objaw: "AI dostaje
     * nagranie zamiast transkrypcji i nie wie, co mówię". Nagranie ma jeszcze
     * dwie wady poza kosztem: nie da się na nim odpalić wykrywania komend ani
     * rozpoznać, że pytanie brzmi "co właśnie widzę" - a więc że trzeba zrobić
     * zdjęcie. Z tekstem cała reszta aplikacji działa tak samo jak przy
     * pisaniu.
     *
     * ## Dlaczego akurat rozpoznawanie na urządzeniu
     * `EXTRA_AUDIO_SOURCE` (Android 13+) pozwala podać rozpoznawaniu własne
     * źródło dźwięku zamiast mikrofonu - przekazujemy deskryptor potoku i
     * wsypujemy do niego PCM. Liczy się lokalnie, więc działa przy zgaszonym
     * ekranie i nie wchodzi w drogę mikrofonowi, który w tym czasie może
     * trzymać wykrywanie słowa kluczowego.
     *
     * Gdy tej drogi nie ma (starszy Android, brak pobranego modelu języka),
     * zwracamy `null` - wołający wraca wtedy do wysyłki nagrania, czyli do
     * zachowania sprzed tej zmiany.
     *
     * @param pcm surowe próbki 16-bit little-endian, mono
     * @param sampleRate częstotliwość próbkowania [pcm]
     * @return rozpoznany tekst albo `null`, gdy się nie da
     */
    suspend fun transcribe(
        pcm: ByteArray,
        sampleRate: Int,
        languageTag: String = Locale.getDefault().toLanguageTag()
    ): String? {
        if (pcm.isEmpty()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.d(tag, "Transkrypcja nagrania wymaga Androida 13+")
            return null
        }
        val available = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
            .getOrDefault(false)
        if (!available) {
            Log.i(tag, "Brak rozpoznawania na urządzeniu - nagranie pójdzie do modelu jako dźwięk")
            return null
        }
        // Limit liczymy z długości nagrania: rozpoznawanie lokalne jest szybsze
        // niż czas rzeczywisty, ale krótka stała potrafiłaby uciąć dłuższe zdanie.
        val audioMs = pcm.size.toLong() * 1000L / (sampleRate.toLong() * BYTES_PER_SAMPLE)
        val budget = (audioMs * TRANSCRIBE_TIME_FACTOR).coerceIn(
            TRANSCRIBE_MIN_TIMEOUT_MS,
            TRANSCRIBE_MAX_TIMEOUT_MS
        )
        return withTimeoutOrNull(budget) {
            withContext(Dispatchers.Main) { transcribeOnMainThread(pcm, sampleRate, languageTag) }
        }
    }

    private suspend fun transcribeOnMainThread(
        pcm: ByteArray,
        sampleRate: Int,
        languageTag: String
    ): String? = suspendCancellableCoroutine { continuation ->
        val recognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }.getOrNull()
        if (recognizer == null) {
            Log.w(tag, "Nie udało się utworzyć rozpoznawania na urządzeniu")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
        if (pipe == null) {
            Log.w(tag, "Nie udało się otworzyć potoku na dźwięk")
            runCatching { recognizer.destroy() }
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val (readEnd, writeEnd) = pipe

        val resumed = AtomicBoolean(false)
        fun finish(result: String?) {
            if (resumed.compareAndSet(false, true)) {
                runCatching { recognizer.destroy() }
                runCatching { readEnd.close() }
                runCatching { writeEnd.close() }
                continuation.resume(result)
            }
        }

        recognizer.setRecognitionListener(listener(::finish))
        continuation.invokeOnCancellation {
            if (resumed.compareAndSet(false, true)) {
                runCatching { writeEnd.close() }
                runCatching { readEnd.close() }
                Handler(Looper.getMainLooper()).post {
                    runCatching { recognizer.cancel() }
                    runCatching { recognizer.destroy() }
                }
            }
        }

        val started = runCatching {
            recognizer.startListening(transcribeIntent(languageTag, readEnd, sampleRate))
        }.onFailure { Log.w(tag, "startListening dla nagrania nie powiodło się", it) }

        if (started.isFailure) {
            finish(null)
            return@suspendCancellableCoroutine
        }

        // Dosypujemy dźwięk z osobnego wątku: potok ma kilkadziesiąt kilobajtów
        // bufora, więc zapis CZEKA, aż rozpoznawanie odbierze poprzednią porcję.
        // Zamknięcie końca zapisu to dla rozpoznawania sygnał "koniec mowy" -
        // bez niego czekałoby, aż skończy się nasz limit czasu.
        writerScope.launch {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    var offset = 0
                    while (offset < pcm.size) {
                        val chunk = minOf(PIPE_CHUNK_BYTES, pcm.size - offset)
                        out.write(pcm, offset, chunk)
                        offset += chunk
                    }
                    out.flush()
                }
            }.onFailure { Log.w(tag, "Nie udało się przekazać dźwięku do rozpoznawania", it) }
        }
    }

    private fun transcribeIntent(
        languageTag: String,
        source: ParcelFileDescriptor,
        sampleRate: Int
    ): Intent = intent(languageTag).apply {
        // Nagranie ma znany koniec (zamknięty potok), więc czekanie na mówcę
        // jest tu bez sensu - te trzy dodatki tylko opóźniłyby wynik.
        removeExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
        removeExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
        removeExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(
            RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
            AudioFormat.ENCODING_PCM_16BIT
        )
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
    }

    /**
     * Tworzy rozpoznawanie mowy - w miarę możliwości TO NA URZĄDZENIU.
     *
     * ## Dlaczego to ma znaczenie przy zablokowanym telefonie
     * Zwykłe [SpeechRecognizer] wiąże się z usługą rozpoznawania Google, która
     * przy zgaszonym ekranie i aplikacji w tle potrafi nie oddać ani jednego
     * wyniku. Zgłoszono to wprost: przez aplikację działa, po zablokowaniu
     * telefonu przestaje. Rozpoznawanie na urządzeniu (Android 13+) nie ma tego
     * ograniczenia - liczy lokalnie, bez sieci i bez cudzej usługi na wierzchu.
     *
     * Gdy go nie ma (starszy Android, brak pobranego modelu języka), wracamy do
     * zwykłego - czyli do zachowania sprzed tej zmiany, nie do gorszego.
     */
    private fun createRecognizer(): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    Log.d(tag, "Rozpoznawanie NA URZĄDZENIU - działa też przy zgaszonym ekranie")
                    return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }
            }.onFailure { Log.w(tag, "Rozpoznawanie na urządzeniu niedostępne", it) }
        }
        return runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .onFailure { Log.w(tag, "Nie udało się utworzyć SpeechRecognizer", it) }
            .getOrNull()
    }

    private fun listener(finish: (String?) -> Unit) = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }
            Log.d(TAG, "Rozpoznano: ${text ?: "(nic)"}")
            finish(text)
        }

        override fun onError(error: Int) {
            lastErrorCode = error
            // Cisza i brak dopasowania to normalny koniec nasłuchiwania, nie awaria.
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (quiet) {
                Log.d(TAG, "Cisza albo brak dopasowania (kod $error)")
            } else {
                Log.w(TAG, "Błąd rozpoznawania: ${describeError(error)}")
            }
            finish(null)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun intent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // TE TRZY DODATKI NIGDY NIE DZIAŁAŁY - I TO NIE Z WINY SILNIKA.
            //
            // W dokumentacji Androida wszystkie trzy są dodatkami typu INT. U nas
            // były stałymi typu `Long`, więc `putExtra` wybierało przeciążenie
            // `putExtra(String, Long)` i zapisywało je jako long. Rozpoznawanie
            // czyta je przez `getIntExtra`, ten przy niezgodnym typie oddaje
            // wartość domyślną - i tyle. Żadnego błędu, żadnego ostrzeżenia,
            // po prostu cisza.
            //
            // Widać to w pomiarze: przy MIN_UTTERANCE_MS = 4000 nasłuch nie
            // miałby prawa skończyć się przed czterema sekundami, a w dzienniku
            // z 12 września kończy się po 2,57 s. Nasza wartość nigdy tam nie
            // dotarła.
            //
            // Dodatki są w API opisane jako niegwarantowane, więc silnik nadal
            // może je zignorować - ale teraz przynajmniej ma co ignorować.
            // Wartości dobrane tak, żeby WŁĄCZENIE ich niczego nie wydłużyło
            // względem dzisiejszego zachowania; patrz opisy stałych.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                MIN_UTTERANCE_MS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                END_OF_SPEECH_SILENCE_MS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                END_OF_SPEECH_SILENCE_MS
            )
        }

    /** Opis kodu błędu - inaczej w logu zostaje sama liczba. */
    internal fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "problem z nagrywaniem dźwięku"
        SpeechRecognizer.ERROR_CLIENT -> "błąd po stronie klienta"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "brak uprawnienia RECORD_AUDIO"
        SpeechRecognizer.ERROR_NETWORK -> "błąd sieci"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "przekroczony czas odpowiedzi sieci"
        SpeechRecognizer.ERROR_NO_MATCH -> "nic nie rozpoznano"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "rozpoznawanie zajęte (mikrofon zajęty?)"
        SpeechRecognizer.ERROR_SERVER -> "błąd serwera rozpoznawania"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "cisza"
        // Poniższe zna dopiero Android 13 - i to one najczęściej tłumaczą,
        // czemu rozpoznawanie NA URZĄDZENIU odmawia: brak pobranego języka.
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "język nieobsługiwany lokalnie"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "model języka niepobrany"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "nie da się sprawdzić obsługi języka"
        else -> "nieznany błąd ($error)"
    }

    companion object {
        private const val TAG = "SpeechToText"

        /**
         * Jedna wypowiedź rzadko trwa dłużej. Limit jest twardy, bo
         * `SpeechRecognizer` potrafi nie oddać sterowania po zajęciu mikrofonu.
         */
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /**
         * Tyle czasu na rozpoczęcie mówienia, zanim rozpoznawanie się podda.
         *
         * INT, NIE LONG - patrz [intent]. Na tym polegał błąd: te wartości
         * były typu `Long`, więc `putExtra` zapisywało je jako long, a
         * rozpoznawanie czyta je przez `getIntExtra` i dostawało swoją wartość
         * domyślną. Wszystkie trzy dodatki były więc po cichu ignorowane od
         * początku.
         *
         * Półtorej sekundy, nie cztery. Cztery były wpisane pod objaw „kończy
         * się, zanim użytkownik zacznie mówić", ale ten dodatek NIE MÓWI „czekaj
         * na początek mowy" - mówi „nie przestawaj nagrywać przed upływem tego
         * czasu". Włączenie go na czterech sekundach zrobiłoby z każdej tury
         * czterosekundowy nasłuch, także po pytaniu „co widzisz". Dzisiejszy
         * zmierzony spód to ~2,6 s, więc półtorej sekundy niczego nie wydłuża,
         * a nadal daje chwilę na zebranie myśli.
         */
        private const val MIN_UTTERANCE_MS = 1_500

        /**
         * Tyle ciszy po wypowiedzi kończy nasłuch - krótsza ucina zdanie w pół.
         *
         * Sekunda, nie półtorej. W dzienniku z 12 września „co widzisz" (jakieś
         * 0,8 s mowy) kończyło nasłuch po 2,57-2,94 s; po odjęciu mowy i obróbki
         * zostaje okno ciszy rzędu 1,5-1,7 s, czyli wartość WŁASNA silnika - bo
         * nasza nigdy do niego nie dotarła. Sekunda skraca każdą turę o jakieś
         * pół sekundy i nadal jest dłuższa niż przerwa na oddech w środku zdania.
         */
        private const val END_OF_SPEECH_SILENCE_MS = 1_000

        /** 16 bitów na próbkę, mono - tak dekodujemy dźwięk z okularów. */
        private const val BYTES_PER_SAMPLE = 2

        /** Ile naraz wsypujemy do potoku - tyle, ile typowo mieści się w buforze. */
        private const val PIPE_CHUNK_BYTES = 8 * 1024

        /** Ile czasu na transkrypcję w stosunku do długości nagrania. */
        private const val TRANSCRIBE_TIME_FACTOR = 2

        private const val TRANSCRIBE_MIN_TIMEOUT_MS = 5_000L
        private const val TRANSCRIBE_MAX_TIMEOUT_MS = 30_000L
    }
}
