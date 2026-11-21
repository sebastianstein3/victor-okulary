package pl.victor.app.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
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
import pl.victor.app.translation.LiveTranslationProtocol
import pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie
import java.util.concurrent.TimeUnit

/**
 * Wspólny szkielet sesji "mowa na żywo": WebSocket, mikrofon, odtwarzacz.
 *
 * ## Po co osobna klasa
 * Tłumaczenie na żywo ([pl.victor.app.translation.LiveTranslationSession]) i
 * rozmowa na żywo z asystentem ([LiveConversationSession]) różnią się tylko
 * tym, CO mówią serwerowi (pierwsza wiadomość, adres) i SKĄD biorą / DOKĄD
 * puszczają dźwięk. Cała reszta - zestawienie połączenia, czekanie na
 * gotowość, czytanie mikrofonu kawałkami po 100 ms, odtwarzacz PCM 24 kHz,
 * sprzątanie po wyjątku w połowie startu - jest ta sama. Druga kopia tego
 * kodu rozjechałaby się przy pierwszej poprawce (bieg 184 dotyczył właśnie
 * odtwarzacza i wyciszania).
 *
 * Mikrofon i odtwarzacz są tu, a nie w orkiestratorze, bo to jedna całość z
 * jednym cyklem życia: [zamknij] ma posprzątać wszystko naraz.
 *
 * @param próbkowanieWejścia próbkowanie PCM wysyłanego do usługi
 * @param źródłoNagrania `MediaRecorder.AudioSource.*` - tłumaczenie słucha
 *   rozmówcy (`VOICE_RECOGNITION`, bez obróbki), rozmowa z asystentem chce
 *   systemowego tłumienia echa (`VOICE_COMMUNICATION`)
 * @param preferowaneWejście typ `AudioDeviceInfo` mikrofonu, który wskazujemy
 *   wprost (np. wbudowany w telefon albo SCO okularów); `null` = jak system
 *   ustawi
 * @param użycieOdtwarzania `AudioAttributes.USAGE_*` odtwarzacza - przez SCO
 *   do okularów idzie WYŁĄCZNIE `USAGE_VOICE_COMMUNICATION`
 */
abstract class LiveAudioSession(
    protected val context: Context,
    val próbkowanieWejścia: Int,
    private val źródłoNagrania: Int,
    private val preferowaneWejście: Int?,
    private val użycieOdtwarzania: Int
) {
    protected abstract val tag: String

    /** Adres WebSocketu (z kluczem, jeśli usługa chce go w adresie). */
    protected abstract fun adres(): String

    /** Nagłówki połączenia poza adresem. */
    protected open fun nagłówki(): Map<String, String> = emptyMap()

    /** Pierwsza wiadomość po otwarciu (`setup` w Gemini). */
    protected abstract fun ustawienia(): String

    /** Wiadomość serwera -> zdarzenia. */
    protected abstract fun rozbierz(wiadomość: String): List<Zdarzenie>

    /** Kawałek PCM z mikrofonu -> wiadomość dla serwera. */
    protected abstract fun ramkaDźwięku(pcm: ByteArray): String

    /** Grzeczne pożegnanie przed zamknięciem, jeśli usługa je zna. */
    protected open fun zakończenie(): String? = null

    /** Zdarzenia z serwera, w kolejności - czyta je pętla w orkiestratorze. */
    val zdarzenia = Channel<Zdarzenie>(Channel.UNLIMITED)

    private val gotowe = CompletableDeferred<Boolean>()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    var powódZamknięcia: String? = null
        private set

    private var mikrofon: AudioRecord? = null
    private var tłumikEcha: AcousticEchoCanceler? = null
    private var odtwarzacz: AudioTrack? = null

    /** Kiedy skończy grać to, co już wpisaliśmy do odtwarzacza. */
    @Volatile
    private var graDoMs = 0L

    /**
     * Numer "wypowiedzi" odtwarzacza. [wyczyśćOdtwarzanie] go podbija, a
     * [graj] przerywa zapis kawałka z poprzedniego numeru - inaczej reszta
     * kawałka, który akurat czekał na miejsce w buforze, dograłaby się już
     * PO przerwaniu.
     */
    @Volatile
    private var pokolenie = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * Łączy się i czeka na gotowość sesji.
     *
     * @return `false`, gdy nie udało się w [limitMs]
     */
    suspend fun połącz(limitMs: Long): Boolean {
        val żądanie = Request.Builder()
            .url(adres())
            .apply { nagłówki().forEach { (k, v) -> addHeader(k, v) } }
            .build()
        socket = client.newWebSocket(żądanie, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Gemini nie powie nic, dopóki nie dostanie `setup`; OpenAI
                // przyjmuje `session.update` od razu po otwarciu.
                webSocket.send(ustawienia())
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
        for (z in rozbierz(wiadomość)) {
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
    fun wyślij(pcm: ByteArray): Boolean = socket?.send(ramkaDźwięku(pcm)) ?: false

    // ---------- mikrofon ----------

    /** Bajtów w jednym kawałku 100 ms. */
    val bajtówKawałka: Int = próbkowanieWejścia / 1000 * LiveTranslationProtocol.KAWAŁEK_MS * 2

    @SuppressLint("MissingPermission") // zgodę sprawdza wołający (orkiestrator)
    fun otwórzMikrofon(): Boolean {
        if (mikrofon != null) return true
        val rate = próbkowanieWejścia
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = runCatching {
            AudioRecord(
                źródłoNagrania, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, bajtówKawałka * 4)
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        preferowaneWejście?.let { typ ->
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            runCatching {
                am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == typ }
                    ?.let { rec.setPreferredDevice(it) }
            }
        }
        // Rozmowa: systemowe źródło VOICE_COMMUNICATION zwykle samo włącza
        // tłumienie echa, ale nie na każdym telefonie - prosimy o nie wprost.
        if (źródłoNagrania == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            tłumikEcha = runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
                } else {
                    null
                }
            }.getOrNull()
        }
        return runCatching { rec.startRecording() }.isSuccess.also { ok ->
            if (ok) {
                mikrofon = rec
            } else {
                zwolnijTłumikEcha()
                rec.release()
            }
        }
    }

    /** Czy działa tłumik echa założony przy otwarciu mikrofonu - do dziennika. */
    fun maTłumikEcha(): Boolean = runCatching { tłumikEcha?.enabled == true }.getOrDefault(false)

    private fun zwolnijTłumikEcha() {
        tłumikEcha?.let { runCatching { it.release() } }
        tłumikEcha = null
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
        zwolnijTłumikEcha()
    }

    /** Z jakiego urządzenia naprawdę nagrywamy - do dziennika. */
    fun źródłoMikrofonu(): String = runCatching {
        val d = mikrofon?.routedDevice ?: return "nieznane"
        when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "mikrofon telefonu"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "SCO: " + (d.productName?.toString() ?: "Bluetooth")
            else -> d.productName?.toString() ?: "typ ${d.type}"
        }
    }.getOrDefault("nieznane")

    // ---------- odtwarzacz ----------

    fun graTeraz(terazMs: Long = System.currentTimeMillis()): Boolean = terazMs < graDoMs

    /** Ile kawałków dźwięku z serwera przyszło - do podsumowania w dzienniku. */
    @Volatile
    var wyjścieKawałków = 0

    /** Ile bajtów PCM z serwera zagraliśmy (albo próbowaliśmy) - do minut w dzienniku. */
    @Volatile
    var wyjścieBajtów = 0L

    /**
     * Gra kawałek dźwięku z serwera. Wołać z jednego wątku roboczego - zapis
     * do AudioTrack blokuje, dopóki w buforze nie zrobi się miejsca.
     *
     * Zapis idzie po kawałeczkach ([PLASTER_MS]), żeby [wyczyśćOdtwarzanie]
     * mogło go przerwać w połowie - dźwięk z serwera przychodzi w kawałkach
     * po kilkaset ms.
     */
    fun graj(pcm: ByteArray) {
        val mojePokolenie = pokolenie
        val track = odtwarzacz ?: nowyOdtwarzacz()?.also { odtwarzacz = it } ?: return
        val teraz = System.currentTimeMillis()
        val ms = pcm.size / 2 * 1000L / LiveTranslationProtocol.PRÓBKOWANIE_WYJŚCIA
        // Tylko SŁYSZALNY dźwięk przedłuża "gra teraz". Bieg 184: usługa
        // przysyła dźwięk niemal bez przerwy (także ciszę), a każdy kawałek
        // przedłużał wyciszenie - mikrofon telefonu milczał na stałe.
        if (poziom(pcm) >= SŁYSZALNE) graDoMs = maxOf(graDoMs, teraz) + ms
        wyjścieKawałków++
        wyjścieBajtów += pcm.size
        val plaster = LiveTranslationProtocol.PRÓBKOWANIE_WYJŚCIA / 1000 * PLASTER_MS * 2
        var od = 0
        while (od < pcm.size) {
            if (pokolenie != mojePokolenie) return
            val ile = minOf(plaster, pcm.size - od)
            val n = runCatching { track.write(pcm, od, ile) }.getOrDefault(-1)
            if (n <= 0) return
            od += n
        }
    }

    /**
     * Wycina natychmiast wszystko, co czeka w odtwarzaczu - wejście w słowo.
     *
     * Serwer, który usłyszał, że użytkownik zaczął mówić, przestaje
     * generować (`interrupted`), ale to, co już przysłał, siedzi w buforze
     * AudioTracka (do sekundy) - bez tego asystent "dokańczałby" zdanie,
     * mówiąc człowiekowi w słowo.
     */
    fun wyczyśćOdtwarzanie() {
        pokolenie++
        graDoMs = 0L
        odtwarzacz?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.play() }
        }
    }

    /** Średni poziom (RMS) PCM 16-bit, 0..32767. */
    fun poziom(pcm: ByteArray, długość: Int = pcm.size): Int = poziomRms(pcm, długość)

    private fun nowyOdtwarzacz(): AudioTrack? = runCatching {
        val rate = LiveTranslationProtocol.PRÓBKOWANIE_WYJŚCIA
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(użycieOdtwarzania)
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
     * Czy dźwięk zagra z głośnika TELEFONU (brak słuchawek) - wtedy mikrofon
     * usłyszy go z powrotem i trzeba go na ten czas wyciszać.
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
        pokolenie++
        odtwarzacz?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
        odtwarzacz = null
        socket?.let { s ->
            zakończenie()?.let { runCatching { s.send(it) } }
            runCatching { s.close(1000, "koniec") }
        }
        socket = null
        zdarzenia.close()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        /** Poziom RMS, od którego dźwięk uznajemy za słyszalny (~ -50 dBFS). */
        const val SŁYSZALNE = 100

        /** Najdłuższy kawałek zapisywany do odtwarzacza naraz. */
        const val PLASTER_MS = 40

        /** Średni poziom (RMS) PCM 16-bit little-endian, 0..32767 - czysta funkcja. */
        fun poziomRms(pcm: ByteArray, długość: Int = pcm.size): Int {
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
    }
}
