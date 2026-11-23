package pl.victor.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mówi głosem z chmury (Gemini albo OpenAI) - zdanie po zdaniu, z wyprzedzeniem.
 *
 * ## Jak to brzmi bez opóźnienia
 * Tekst przychodzi już podzielony na zdania ([CloudVoiceLogic.splitSentences]).
 * Pierwsze zdanie idzie do syntezy natychmiast i gra, gdy tylko wróci; kolejne
 * ([CloudVoiceConfig.LOOKAHEAD]) pobieramy RÓWNOLEGLE, gdy bieżące jeszcze gra -
 * więc między zdaniami nie ma przerwy na sieć.
 *
 * ## Kiedy oddajemy głos syntezatorowi systemowemu
 * Gdy pierwszy dźwięk nie przyszedł w [CloudVoiceConfig.FIRST_SOUND_TIMEOUT_MS]
 * albo usługa zwróciła błąd: RESZTA (to, co jeszcze nie zagrało) idzie przez
 * [Listener.onFallback] do syntezatora Androida, w tej samej kolejności, a
 * chmura wyłącza się na chwilę ([CloudVoiceLogic.cooldownMs]) - bez tego przy
 * braku sieci każde zdanie strumienia czekałoby 2,5 s na pewną porażkę.
 *
 * ## Trasa dźwięku
 * Ta sama co syntezatora: atrybuty bierzemy z [BluetoothAudioRouter.ttsAudioAttributes]
 * przy każdym zdaniu (USAGE_VOICE_COMMUNICATION, gdy rozmowa idzie przez SCO
 * okularów, inaczej USAGE_ASSISTANT - A2DP albo głośnik), więc okulary słyszą
 * głos z chmury tam, gdzie słyszały systemowy.
 */
class CloudVoiceEngine(
    cacheDir: File,
    private val audioAttributes: () -> AudioAttributes,
    private val listener: Listener,
    private val diagEvent: (String, Map<String, Any?>) -> Unit
) {
    interface Listener {
        /** Zaczęło grać / skończyło grać (cała seria zdań). */
        fun onSpeakingChanged(speaking: Boolean)

        /** Koniec wypowiedzi - jak `UtteranceProgressListener.onDone/onStop` systemowego syntezatora. */
        fun onUtteranceDone(utteranceId: String, success: Boolean)

        /**
         * To, czego chmura nie powiedziała - do syntezatora systemowego, w kolejności.
         * Ostatni kawałek każdej wypowiedzi niesie jej identyfikator (na nim
         * wisi powiadomienie o końcu), pozostałe mają `null`.
         *
         * Wołane POD blokadą kolejki - żeby nowa wypowiedź (już systemowa) nie
         * wyprzedziła reszty starej.
         */
        fun onFallback(pieces: List<Pair<String, String?>>, reason: CloudVoiceFallback)
    }

    /** Czym mówimy - zamrożone na czas wypowiedzi. */
    data class Config(
        val provider: CloudVoiceProvider,
        val voice: String,
        val apiKey: String
    ) {
        val model: String get() = CloudVoiceLogic.modelFor(provider)
        val style: String
            get() = when (provider) {
                CloudVoiceProvider.GEMINI -> CloudVoiceConfig.GEMINI_STYLE
                CloudVoiceProvider.OPENAI -> CloudVoiceConfig.OPENAI_INSTRUCTIONS
                CloudVoiceProvider.SYSTEM -> ""
            }
    }

    private class Utterance(val id: String, val config: Config, val sentences: Int) {
        val enqueuedAt: Long = SystemClock.elapsedRealtime()
        @Volatile var firstSoundMs: Long? = null
        val fromCache = AtomicInteger(0)
        val played = AtomicInteger(0)
    }

    private class Item(
        val text: String,
        val utterance: Utterance,
        val isLast: Boolean,
        val generation: Long
    ) {
        val enqueuedAt: Long = SystemClock.elapsedRealtime()
        var synth: Deferred<Synth>? = null
    }

    private class Synth(
        val pcm: ByteArray?,
        val fromCache: Boolean,
        val failure: CloudVoiceFallback? = null,
        val detail: String? = null
    )

    private val tag = "CloudVoiceEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = CloudVoiceCache(File(cacheDir, CACHE_DIR))

    private val client = OkHttpClient.Builder()
        .connectTimeout(CloudVoiceConfig.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(CloudVoiceConfig.READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CloudVoiceConfig.CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private val queue = ArrayDeque<Item>()

    @Volatile private var generation = 0L
    @Volatile private var worker: Job? = null

    /**
     * Ostatnie zdanie wypowiedzi zdjęte już z kolejki, które jeszcze wybrzmiewa.
     * stop() w tym oknie nie widział go w kolejce i zdarzenie końca nie
     * padało nigdy - tura czekała do 150 s (przegląd 22.11).
     */
    private var draining: Utterance? = null
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var cooldownUntil = 0L

    /** Czy teraz gra (albo czeka na następne zdanie tej samej serii). */
    @Volatile
    var isPlaying: Boolean = false
        private set

    /** Powód ostatniego przejścia na systemowy - do dziennika i Ustawień. */
    @Volatile
    var lastFallback: CloudVoiceFallback? = null
        private set

    /** Czy chmura jest chwilowo wyłączona po porażce - patrz [CloudVoiceLogic.cooldownMs]. */
    fun isCoolingDown(): Boolean = SystemClock.elapsedRealtime() < cooldownUntil

    /**
     * Zleca wypowiedź.
     *
     * @param flush `true` = wypiera to, co gra (jak QUEUE_FLUSH), `false` = dopisuje (QUEUE_ADD)
     * @return `false`, gdy chmura jest chwilowo wyłączona - wtedy mówi systemowy
     */
    fun enqueue(sentences: List<String>, utteranceId: String, flush: Boolean, config: Config): Boolean {
        if (sentences.isEmpty()) return false
        val dropped: List<String>
        synchronized(lock) {
            if (isCoolingDown()) return false
            dropped = if (flush) stopLocked() else emptyList()
            val u = Utterance(utteranceId, config, sentences.size)
            sentences.forEachIndexed { i, s ->
                queue.addLast(Item(s, u, i == sentences.lastIndex, generation))
            }
            prefetchLocked()
            startWorkerLocked()
        }
        dropped.forEach { listener.onUtteranceDone(it, false) }
        return true
    }

    /**
     * Przerywa wszystko natychmiast - dotknięcie zausznika, "cicho", nowe pytanie.
     * Każda niedokończona wypowiedź dostaje "koniec z porażką", jak przy
     * `TextToSpeech.stop()`.
     */
    fun stop() {
        val dropped: List<String>
        val grało: Boolean
        synchronized(lock) {
            // Także "czekało na syntezę" - wypowiedź podmieniona przez nową
            // mogła zostawić stan mówienia, którego nikt by już nie zgasił.
            grało = isPlaying || worker != null || queue.isNotEmpty()
            dropped = stopLocked()
        }
        if (dropped.isNotEmpty()) {
            diagEvent("głos z chmury przerwany", mapOf("wypowiedzi" to dropped.size))
        }
        if (grało) listener.onSpeakingChanged(false)
        dropped.forEach { listener.onUtteranceDone(it, false) }
    }

    fun shutdown() {
        stop()
        scope.coroutineContext[Job]?.cancel()
    }

    /** Czyści kolejkę i zatrzymuje odtwarzacz; zwraca identyfikatory porzuconych wypowiedzi. */
    private fun stopLocked(): List<String> {
        generation++
        val ids = (queue.map { it.utterance.id } + listOfNotNull(draining?.id)).distinct()
        draining = null
        queue.forEach { it.synth?.cancel() }
        queue.clear()
        worker?.cancel()
        worker = null
        isPlaying = false
        currentTrack?.let { t ->
            // pause + flush: blokujący write() w wątku odtwarzacza od razu wraca.
            runCatching { t.pause() }
            runCatching { t.flush() }
        }
        return ids
    }

    private fun prefetchLocked() {
        queue.take(1 + CloudVoiceConfig.LOOKAHEAD).forEach { item ->
            if (item.synth == null) {
                item.synth = scope.async { synthesize(item.text, item.utterance.config) }
            }
        }
    }

    private fun startWorkerLocked() {
        if (worker?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) { runWorker() }
        worker = job
        job.start()
    }

    // === Odtwarzanie ===

    private suspend fun runWorker() {
        val me = currentCoroutineContext()[Job]
        var track: AudioTrack? = null
        var trackUsage = -1
        var framesWritten = 0L
        var firstOfBurst = true
        var announced = false
        fun current(item: Item) = item.generation == generation && worker === me

        try {
            while (currentCoroutineContext().isActive) {
                val item = synchronized(lock) {
                    if (worker !== me) return
                    prefetchLocked()
                    queue.firstOrNull().also {
                        // Decyzja "koniec" i zwolnienie miejsca na nowego
                        // odtwarzacza muszą być jednym krokiem - inaczej zdanie
                        // dopisane w tej chwili czekałoby na nieistniejący wątek.
                        if (it == null) {
                            worker = null
                            isPlaying = false
                        }
                    }
                } ?: break

                val limit = CloudVoiceLogic.waitLimitMs(
                    firstOfBurst, SystemClock.elapsedRealtime() - item.enqueuedAt
                )
                val result = withTimeoutOrNull(limit) { item.synth?.await() }
                if (!current(item)) continue
                if (result == null || result.pcm == null) {
                    fallbackFrom(item, result?.failure ?: CloudVoiceFallback.TIMEOUT, result?.detail)
                    firstOfBurst = true
                    continue
                }
                val pcm: ByteArray = result.pcm

                // Trasa mogła się zmienić między zdaniami (np. podniesione SCO) -
                // wtedy nowy odtwarzacz z nowymi atrybutami, jak systemowy
                // syntezator, który dostaje atrybuty przed każdą wypowiedzią.
                val attrs = audioAttributes()
                if (track != null && attrs.usage != trackUsage) {
                    awaitDrain(track, framesWritten) { current(item) }
                    releaseTrack(track)
                    track = null
                }
                if (track == null) {
                    track = createTrack(attrs)
                    if (track == null) {
                        fallbackFrom(item, CloudVoiceFallback.PLAYBACK, "AudioTrack")
                        continue
                    }
                    trackUsage = attrs.usage
                    framesWritten = 0L
                    currentTrack = track
                    if (!current(item)) continue
                }

                if (!announced) {
                    announced = true
                    isPlaying = true
                    listener.onSpeakingChanged(true)
                }
                val u = item.utterance
                if (u.firstSoundMs == null) u.firstSoundMs = SystemClock.elapsedRealtime() - u.enqueuedAt
                if (result.fromCache) u.fromCache.incrementAndGet()

                val written = writeAll(track, pcm) { current(item) }
                framesWritten += written / 2
                if (!current(item)) continue
                if (written < pcm.size) {
                    fallbackFrom(item, CloudVoiceFallback.PLAYBACK, "write")
                    continue
                }
                u.played.incrementAndGet()
                firstOfBurst = false

                val next = synchronized(lock) {
                    if (queue.firstOrNull() === item) queue.removeFirst()
                    if (item.isLast) draining = u
                    queue.firstOrNull()
                }
                if (item.isLast) {
                    // Koniec wypowiedzi zgłaszamy, gdy NAPRAWDĘ ucichła - na tym
                    // czeka tryb rozmowy, zanim otworzy mikrofon. Gdy następne
                    // zdanie jest już gotowe, nie czekamy (bez dziury w mowie);
                    // bufor jest krótki, więc zgłoszenie wyprzedza ciszę o
                    // najwyżej ~0,2 s, a i tak zaraz gra dalej.
                    val nextReady = next?.synth?.isCompleted == true
                    if (!nextReady) awaitDrain(track, framesWritten) { current(item) }
                    // Zatrzymane w trakcie wybrzmiewania: stopLocked() zabrał już
                    // tę wypowiedź do porzuconych i sam zgłosi jej koniec.
                    val nasza = synchronized(lock) {
                        (draining === u && current(item)).also { if (it) draining = null }
                    }
                    if (!nasza) continue
                    logUtterance(u, null, null)
                    listener.onUtteranceDone(u.id, true)
                }
            }
        } finally {
            track?.let { releaseTrack(it) }
            var report = false
            synchronized(lock) {
                if (worker === me) worker = null
                if (currentTrack === track) currentTrack = null
                if (announced && worker == null && queue.isEmpty()) {
                    isPlaying = false
                    report = true
                }
            }
            if (report) listener.onSpeakingChanged(false)
        }
    }

    /** Zapis w kawałkach po 0,1 s - przerwanie słychać od razu, nie po całym zdaniu. */
    private fun writeAll(track: AudioTrack, pcm: ByteArray, stillCurrent: () -> Boolean): Int {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { track.play() }
        var off = 0
        while (off < pcm.size) {
            if (!stillCurrent()) return off
            val len = minOf(CHUNK_BYTES, pcm.size - off)
            val n = runCatching { track.write(pcm, off, len) }.getOrDefault(-1)
            if (n <= 0) return off
            off += n
        }
        return off
    }

    /** Czeka, aż odtwarzacz zagra wszystko, co dostał (z limitem). */
    private suspend fun awaitDrain(track: AudioTrack, framesWritten: Long, stillCurrent: () -> Boolean) {
        val bufferMs = BUFFER_BYTES * 1000L / (CloudVoiceConfig.SAMPLE_RATE * 2)
        val deadline = SystemClock.elapsedRealtime() + bufferMs + DRAIN_MARGIN_MS
        while (SystemClock.elapsedRealtime() < deadline && stillCurrent()) {
            val head = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(framesWritten)
            if (head >= framesWritten) return
            delay(DRAIN_POLL_MS)
        }
    }

    private fun createTrack(attrs: AudioAttributes): AudioTrack? = runCatching {
        val rate = CloudVoiceConfig.SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { t.release() }
            return null
        }
        t.play()
        t
    }.onFailure { Log.w(tag, "Nie udało się utworzyć odtwarzacza", it) }.getOrNull()

    private fun releaseTrack(t: AudioTrack) {
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    /**
     * Oddaje resztę syntezatorowi systemowemu i na chwilę wyłącza chmurę.
     * Reszta = bieżące zdanie i wszystko za nim, także kolejne wypowiedzi.
     */
    private fun fallbackFrom(item: Item, reason: CloudVoiceFallback, detail: String?) {
        val utterances: List<Utterance>
        synchronized(lock) {
            if (item.generation != generation) return
            cooldownUntil = SystemClock.elapsedRealtime() + CloudVoiceLogic.cooldownMs(reason)
            lastFallback = reason
            val pieces = queue.map { it.text to (if (it.isLast) it.utterance.id else null) }
            utterances = queue.map { it.utterance }.distinct()
            queue.forEach { it.synth?.cancel() }
            queue.clear()
            Log.w(tag, "Głos z chmury -> systemowy (${reason.opis}${detail?.let { ": $it" } ?: ""})")
            runCatching { listener.onFallback(pieces, reason) }
                .onFailure { Log.w(tag, "Przekazanie reszty syntezatorowi nie wyszło", it) }
        }
        utterances.forEach { logUtterance(it, reason, detail) }
    }

    private fun logUtterance(u: Utterance, fallback: CloudVoiceFallback?, detail: String?) {
        runCatching {
            diagEvent(
                if (fallback == null) "głos z chmury" else "głos z chmury -> systemowy",
                mapOf(
                    "dostawca" to u.config.provider.nazwa,
                    "model" to u.config.model,
                    "głos" to u.config.voice,
                    "zdania" to u.sentences,
                    "zagrane" to (if (fallback != null) u.played.get() else null),
                    "msDoDźwięku" to u.firstSoundMs,
                    "zPamięci" to "${u.fromCache.get()}/${u.sentences}",
                    "przejście" to fallback?.opis,
                    "szczegóły" to detail?.take(160),
                    "przerwaChmuryS" to (if (fallback != null) CloudVoiceLogic.cooldownMs(fallback) / 1000 else null)
                )
            )
        }
    }

    // === Synteza ===

    private suspend fun synthesize(text: String, config: Config): Synth {
        val key = if (CloudVoiceLogic.isCacheable(text)) {
            CloudVoiceLogic.cacheKey(config.provider, config.model, config.voice, config.style, text)
        } else null
        key?.let { cache.get(it) }?.let { return Synth(it, fromCache = true) }

        val request = try { when (config.provider) {
            CloudVoiceProvider.GEMINI -> Request.Builder()
                .url(CloudVoiceConfig.GEMINI_URL)
                .addHeader("x-goog-api-key", config.apiKey)
                .post(
                    CloudVoiceLogic.geminiRequest(text, config.voice).toString()
                        .toRequestBody(JSON)
                )
                .build()
            CloudVoiceProvider.OPENAI -> Request.Builder()
                .url(CloudVoiceConfig.OPENAI_URL)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(
                    CloudVoiceLogic.openAiRequest(text, config.voice).toString()
                        .toRequestBody(JSON)
                )
                .build()
            CloudVoiceProvider.SYSTEM -> return Synth(null, false, CloudVoiceFallback.HTTP, "brak usługi")
        } } catch (e: IllegalArgumentException) {
            // Klucz z niedozwolonym znakiem (np. wklejony z "\n") - OkHttp rzuca
            // przy nagłówku. Bez tego wyjątek z Deferred wywracał aplikację.
            return Synth(null, false, CloudVoiceFallback.HTTP, "zły znak w kluczu API")
        }

        val (code, body) = try {
            execute(request)
        } catch (e: IOException) {
            return Synth(null, false, CloudVoiceFallback.NETWORK, e.javaClass.simpleName + ": " + e.message?.take(100))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Synth(null, false, CloudVoiceFallback.HTTP, e.javaClass.simpleName + ": " + e.message?.take(100))
        }
        if (code !in 200..299) {
            val msg = CloudVoiceLogic.errorMessage(String(body, Charsets.UTF_8))
            return Synth(null, false, CloudVoiceLogic.classifyHttp(code), "HTTP $code" + (msg?.let { ": $it" } ?: ""))
        }
        val pcm = when (config.provider) {
            CloudVoiceProvider.GEMINI -> CloudVoiceLogic.parseGeminiAudio(String(body, Charsets.UTF_8))
            else -> CloudVoiceLogic.evenLength(CloudVoiceLogic.stripWavHeader(body)).takeIf { it.isNotEmpty() }
        } ?: return Synth(null, false, CloudVoiceFallback.EMPTY, null)

        if (key != null) cache.put(key, pcm)
        return Synth(pcm, fromCache = false)
    }

    /** Zapytanie OkHttp, które da się przerwać razem z korutyną (stop, nowe pytanie). */
    private suspend fun execute(request: Request): Pair<Int, ByteArray> =
        suspendCancellableCoroutine { cont: CancellableContinuation<Pair<Int, ByteArray>> ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
                    }
                    if (cont.isActive) {
                        cont.resumeWith(
                            result.fold(
                                { Result.success(it) },
                                { Result.failure(if (it is IOException) it else IOException(it)) }
                            )
                        )
                    }
                }
            })
        }

    /**
     * Krótka próbka do Ustawień - przez ten sam silnik, ale bez kolejki
     * rozmowy: wypiera to, co gra.
     */
    fun sample(text: String, config: Config, utteranceId: String): Boolean {
        // Próbka ma zabrzmieć nawet tuż po porażce - użytkownik właśnie
        // sprawdza, czy działa (np. po wpisaniu poprawnego klucza).
        cooldownUntil = 0L
        return enqueue(CloudVoiceLogic.splitSentences(text), utteranceId, flush = true, config = config)
    }

    private companion object {
        const val CACHE_DIR = "cloud_tts"
        val JSON = "application/json".toMediaType()

        /** 0,1 s mowy - porcja zapisu, po której sprawdzamy przerwanie. */
        const val CHUNK_BYTES = CloudVoiceConfig.SAMPLE_RATE * 2 / 10

        /** 0,2 s - krótko, żeby zgłoszenie końca nie wyprzedzało ciszy. */
        const val BUFFER_BYTES = CloudVoiceConfig.SAMPLE_RATE * 2 / 5

        const val DRAIN_POLL_MS = 20L
        const val DRAIN_MARGIN_MS = 1_500L
    }
}

/**
 * Pamięć podręczna nagrań krótkich zdań - pliki PCM w `cacheDir`.
 *
 * "Słucham", "Już sprawdzam", "Gotowe" padają dziesiątki razy dziennie; z
 * pamięci grają od razu i nic nie kosztują. Najwyżej
 * [CloudVoiceConfig.CACHE_MAX_ENTRIES] plików, wyrzucamy najdawniej użyte
 * (czas użycia = data modyfikacji pliku, odświeżana przy odczycie).
 */
class CloudVoiceCache(private val dir: File) {

    @Synchronized
    fun get(key: String): ByteArray? {
        val f = File(dir, "$key.pcm")
        if (!f.isFile) return null
        return runCatching {
            f.setLastModified(System.currentTimeMillis())
            f.readBytes()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun put(key: String, pcm: ByteArray) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            tmp.writeBytes(pcm)
            tmp.renameTo(File(dir, "$key.pcm"))
            val entries = dir.listFiles { f -> f.name.endsWith(".pcm") }.orEmpty()
                .map { it.name to it.lastModified() }
            CloudVoiceLogic.evictionVictims(entries, CloudVoiceConfig.CACHE_MAX_ENTRIES)
                .forEach { File(dir, it).delete() }
        }
    }
}
