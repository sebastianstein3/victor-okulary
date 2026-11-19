package pl.victor.app.translation

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import pl.victor.app.translation.LiveTranslationProtocol.Usługa
import pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie
import java.util.concurrent.TimeUnit

/**
 * Jedna sesja tłumaczenia na żywo: WebSocket, mikrofon telefonu, odtwarzacz.
 *
 * Mikrofon i odtwarzacz są tu, a nie w orkiestratorze, bo to jedna całość z
 * jednym cyklem życia: [zamknij] ma posprzątać wszystko naraz, także po
 * wyjątku w połowie startu.
 *
 * Przekład gra z `USAGE_MEDIA` - tą samą drogą co mowa asystenta, czyli do
 * okularów, gdy są połączone jako słuchawki.
 */
class LiveTranslationSession(
    private val context: Context,
    val usługa: Usługa,
    private val klucz: String,
    private val językDocelowy: String
) {
    private val tag = "LiveTranslation"

    /** Zdarzenia z serwera, w kolejności - czyta je pętla w orkiestratorze. */
    val zdarzenia = Channel<Zdarzenie>(Channel.UNLIMITED)

    private val gotowe = CompletableDeferred<Boolean>()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    var powódZamknięcia: String? = null
        private set

    private var mikrofon: AudioRecord? = null
    private var odtwarzacz: AudioTrack? = null

    /** Kiedy skończy grać to, co już wpisaliśmy do odtwarzacza. */
    @Volatile
    private var graDoMs = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * Łączy się i czeka na gotowość sesji.
     *
     * @return `false`, gdy nie udało się w [limitMs] - wtedy tryb przechodzi
     *   na tłumaczenie w telefonie
     */
    suspend fun połącz(limitMs: Long): Boolean {
        val żądanie = Request.Builder()
            .url(LiveTranslationProtocol.adres(usługa, klucz))
            .apply { LiveTranslationProtocol.nagłówki(usługa, klucz).forEach { (k, v) -> addHeader(k, v) } }
            .build()
        socket = client.newWebSocket(żądanie, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Gemini nie powie nic, dopóki nie dostanie `setup`; OpenAI
                // przyjmuje `session.update` od razu po otwarciu.
                webSocket.send(LiveTranslationProtocol.ustawienia(usługa, językDocelowy))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = obsłuż(text)

            // Gemini Live wysyła JSON w ramkach BINARNYCH.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = obsłuż(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                koniec("serwer zamknął: $code ${reason.take(160)}")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                koniec("zamknięte: $code ${reason.take(160)}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val kod = response?.code?.let { "HTTP $it " } ?: ""
                val treść = runCatching { response?.body?.string()?.take(160) }.getOrNull().orEmpty()
                koniec("błąd połączenia: $kod${t.javaClass.simpleName} ${t.message?.take(120) ?: ""} $treść".trim())
            }
        })
        return kotlinx.coroutines.withTimeoutOrNull(limitMs) { gotowe.await() } ?: run {
            if (powódZamknięcia == null) powódZamknięcia = "brak gotowości w ${limitMs / 1000} s"
            false
        }
    }

    private fun obsłuż(wiadomość: String) {
        for (z in LiveTranslationProtocol.rozbierz(usługa, wiadomość)) {
            when (z) {
                Zdarzenie.Gotowe -> gotowe.complete(true)
                is Zdarzenie.Błąd -> if (powódZamknięcia == null) powódZamknięcia = z.opis
                else -> Unit
            }
            zdarzenia.trySend(z)
        }
    }

    private fun koniec(powód: String) {
        if (powódZamknięcia == null) powódZamknięcia = powód
        gotowe.complete(false)
        zdarzenia.trySend(Zdarzenie.Zamknięte)
    }

    /** Wysyła kawałek PCM w próbkowaniu usługi. */
    fun wyślij(pcm: ByteArray): Boolean =
        socket?.send(LiveTranslationProtocol.dźwięk(usługa, pcm)) ?: false

    // ---------- mikrofon ----------

    /** Bajtów w jednym kawałku 100 ms. */
    val bajtówKawałka: Int = usługa.próbkowanieWejścia / 1000 * LiveTranslationProtocol.KAWAŁEK_MS * 2

    @SuppressLint("MissingPermission") // zgodę sprawdza startEarTranslation
    fun otwórzMikrofon(): Boolean {
        if (mikrofon != null) return true
        val rate = usługa.próbkowanieWejścia
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, bajtówKawałka * 4)
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        // MIKROFON TELEFONU, wprost. Gdy okulary są podłączone tylko w trybie
        // rozmowy, Android potrafi oddać nagranie z ICH mikrofonu (daleko od
        // rozmówcy) - a tu słuchamy osoby naprzeciwko.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        runCatching {
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { rec.setPreferredDevice(it) }
        }
        return runCatching { rec.startRecording() }.isSuccess.also { ok ->
            if (ok) mikrofon = rec else rec.release()
        }
    }

    /** Czyta jeden kawałek (blokuje ok. 100 ms). `null` = mikrofon padł. */
    fun czytaj(bufor: ByteArray): ByteArray? {
        val rec = mikrofon ?: return null
        var przeczytane = 0
        while (przeczytane < bufor.size) {
            val n = rec.read(bufor, przeczytane, bufor.size - przeczytane)
            if (n <= 0) return null
            przeczytane += n
        }
        return bufor
    }

    /**
     * Oddaje mikrofon - na czas "mojej kolei" w rozmowie rozpoznawanie
     * telefonu potrzebuje go dla siebie (Android daje dźwięk jednemu).
     */
    fun zamknijMikrofon() {
        mikrofon?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        mikrofon = null
    }

    // ---------- odtwarzacz ----------

    fun graTeraz(terazMs: Long = System.currentTimeMillis()): Boolean = terazMs < graDoMs

    /** Z jakiego urządzenia naprawdę nagrywamy - do dziennika. */
    fun źródłoMikrofonu(): String = runCatching {
        val d = mikrofon?.routedDevice ?: return "nieznane"
        if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) "mikrofon telefonu" else (d.productName?.toString() ?: "typ ${d.type}")
    }.getOrDefault("nieznane")

    /**
     * Gra kawałek przekładu. Wołać z jednego wątku roboczego - zapis do
     * AudioTrack blokuje, dopóki w buforze nie zrobi się miejsca.
     */
    fun graj(pcm: ByteArray) {
        val track = odtwarzacz ?: nowyOdtwarzacz()?.also { odtwarzacz = it } ?: return
        val teraz = System.currentTimeMillis()
        val ms = pcm.size / 2 * 1000L / LiveTranslationProtocol.PRÓBKOWANIE_WYJŚCIA
        // Tylko SŁYSZALNY przekład wycisza mikrofon. Bieg 184: usługa przysyła
        // dźwięk niemal bez przerwy (także ciszę), a każdy kawałek przedłużał
        // wyciszenie - mikrofon telefonu milczał na stałe i rozmówca "nie był
        // słyszany" przez całe minuty.
        if (poziom(pcm) >= SŁYSZALNE) graDoMs = maxOf(graDoMs, teraz) + ms
        wyjścieKawałków++
        runCatching { track.write(pcm, 0, pcm.size) }
    }

    /** Ile kawałków przekładu przyszło - do podsumowania w dzienniku. */
    @Volatile
    var wyjścieKawałków = 0

    /** Średni poziom (RMS) PCM 16-bit, 0..32767. */
    fun poziom(pcm: ByteArray, długość: Int = pcm.size): Int {
        val próbek = długość / 2
        if (próbek == 0) return 0
        var suma = 0.0
        var i = 0
        while (i + 1 < długość) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            suma += v * v
            i += 2
        }
        return Math.sqrt(suma / próbek).toInt()
    }

    private fun nowyOdtwarzacz(): AudioTrack? = runCatching {
        val rate = LiveTranslationProtocol.PRÓBKOWANIE_WYJŚCIA
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, rate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }.onFailure { Log.w(tag, "Nie udało się otworzyć odtwarzacza", it) }.getOrNull()

    /**
     * Czy przekład zagra z głośnika TELEFONU (brak słuchawek Bluetooth) -
     * wtedy mikrofon usłyszy go z powrotem i trzeba go na ten czas wyciszać.
     */
    fun graNaGłośnikuTelefonu(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val słuchawki = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
        return runCatching {
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).none { it.type in słuchawki }
        }.getOrDefault(false)
    }

    // ---------- koniec ----------

    /** Sprząta wszystko. Można wołać wiele razy. */
    fun zamknij() {
        zamknijMikrofon()
        odtwarzacz?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
        odtwarzacz = null
        socket?.let { s ->
            LiveTranslationProtocol.zakończenie(usługa)?.let { runCatching { s.send(it) } }
            runCatching { s.close(1000, "koniec") }
        }
        socket = null
        zdarzenia.close()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        /** Poziom RMS, od którego przekład uznajemy za słyszalny (~ -50 dBFS). */
        const val SŁYSZALNE = 100
    }
}
