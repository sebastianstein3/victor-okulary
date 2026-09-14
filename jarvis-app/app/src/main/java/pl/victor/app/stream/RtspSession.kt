package pl.victor.app.stream

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Cała rozmowa z serwerem RTSP w okularach: DESCRIBE, SETUP, PLAY i odbiór obrazu.
 *
 * ## Czemu piszemy to sami
 * Bo media3 odrzuca ten strumień na starcie - raz przez wiersz
 * `a=decode_buf=300`, którego nie umie pominąć, drugi raz przez brak
 * `sprop-parameter-sets` w opisie sesji. Powody i pomiary siedzą w
 * [RtspProtocol] i [H264Depacketizer]; tutaj jest sama mechanika połączenia.
 *
 * ## Kształt
 * Jedno gniazdo TCP, wzięte z sieci okularów, niesie i sterowanie, i obraz.
 * [run] blokuje aż do zerwania połączenia albo do [stop] - wołający ma je
 * trzymać na własnym wątku.
 *
 * Klasa jest jednorazowa: po [stop] trzeba zrobić nową. Gniazdo raz zamknięte
 * nie wraca, a stan składania klatek po zerwaniu jest bezwartościowy.
 */
class RtspSession(
    private val url: String,
    private val socketFactory: SocketFactory?,
    /** Dokąd idą gotowe jednostki obrazu w postaci Annex-B. */
    private val onVideo: (ByteArray) -> Unit,
    /** Wołane raz, gdy serwer przyjął PLAY - ekran może przestać mówić "łączę". */
    private val onPlaying: () -> Unit = {},
    /** Wołane przy każdym kroku, do dziennika. */
    private val onEvent: (String, Map<String, Any?>) -> Unit = { _, _ -> }
) {

    private val tag = "RtspSession"
    private var socket: Socket? = null
    private var cseq = 1
    private var sessionId: String? = null
    private val depacketizer = H264Depacketizer()

    @Volatile
    private var running = false

    /** Ostatni powód niepowodzenia - do pokazania użytkownikowi. */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * Zestawia sesję i odbiera obraz aż do zerwania albo do [stop].
     *
     * @return `true` gdy strumień w ogóle ruszył
     */
    fun run(): Boolean {
        running = true
        try {
            val target = parseTarget() ?: return fail("Adres strumienia jest nie do odczytania.")
            val sock = (socketFactory ?: SocketFactory.getDefault()).createSocket()
            socket = sock
            sock.connect(InetSocketAddress(target.first, target.second), CONNECT_TIMEOUT_MS)
            sock.soTimeout = READ_TIMEOUT_MS
            val out = sock.getOutputStream()
            val reader = InterleavedReader(BufferedInputStream(sock.getInputStream(), BUFFER_BYTES))

            val describe = exchange(out, reader, "DESCRIBE", url, mapOf("Accept" to "application/sdp"))
                ?: return fail("Serwer nie odpowiedział na DESCRIBE.")
            if (!describe.ok) return fail("Serwer odrzucił DESCRIBE (${describe.status}).")

            val tracks = RtspProtocol.parseSdp(describe.body)
            onEvent(
                "Podgląd: opis sesji przeczytany",
                mapOf("ścieżek" to tracks.size, "kodeki" to tracks.mapNotNull { it.encoding })
            )
            val video = tracks.firstOrNull {
                it.kind == RtspProtocol.Kind.VIDEO && it.encoding == "H264"
            } ?: return fail("Ten strumień nie ma ścieżki H.264.")

            // TYLKO OBRAZ. Dźwięk z okularów jest w AAC i dałoby się go wziąć,
            // ale każda ustawiona ścieżka to drugi kanał do obsłużenia, a
            // producent swój strumień też odbiera z `--no-audio`. Jedna rzecz
            // naraz; dźwięk wejdzie, gdy obraz będzie pewny.
            val setup = exchange(
                out, reader, "SETUP",
                RtspProtocol.resolveControl(url, video.control),
                mapOf("Transport" to RtspProtocol.interleavedTransport(VIDEO_CHANNEL))
            ) ?: return fail("Serwer nie odpowiedział na SETUP.")
            if (!setup.ok) return fail("Serwer odrzucił SETUP (${setup.status}).")
            sessionId = RtspProtocol.sessionId(setup.headers)

            val play = exchange(out, reader, "PLAY", url, emptyMap())
                ?: return fail("Serwer nie odpowiedział na PLAY.")
            if (!play.ok) return fail("Serwer odrzucił PLAY (${play.status}).")

            onEvent(
                "Podgląd: strumień ruszył",
                mapOf("sesja" to sessionId, "typŁadunku" to video.payloadType)
            )
            onPlaying()
            pump(reader, video.payloadType)
            return true
        } catch (e: Exception) {
            if (!running) return true // zamknięte przez nas, nie awaria
            Log.w(tag, "Sesja RTSP przerwana", e)
            return fail("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            closeSocket()
        }
    }

    /** Zamyka sesję. Wolno wołać z innego wątku niż [run]. */
    fun stop() {
        running = false
        // TEARDOWN wysyłamy najlepszym staraniem: gniazdo i tak zaraz padnie, a
        // serwer, który go nie dostanie, zwolni sesję po swoim limicie czasu.
        runCatching {
            val sock = socket ?: return@runCatching
            val headers = sessionId?.let { mapOf("Session" to it) } ?: emptyMap()
            sock.getOutputStream().write(
                RtspProtocol.request("TEARDOWN", url, cseq++, headers).toByteArray(Charsets.US_ASCII)
            )
        }
        closeSocket()
    }

    /**
     * Pętla odbioru: ramki obrazu idą do dekodera, odpowiedzi tekstowe są
     * pomijane.
     *
     * Odpowiedzi trzeba CZYTAĆ, nie ignorować: po tym samym połączeniu wracają
     * potwierdzenia podtrzymania sesji, a nieodebrane zapchałyby bufor.
     */
    private fun pump(reader: InterleavedReader, payloadType: Int) {
        var frames = 0
        var lastKeepAliveMs = System.currentTimeMillis()
        while (running) {
            val frame = reader.read() ?: break
            when (frame) {
                is InterleavedReader.Frame.Text -> Unit
                is InterleavedReader.Frame.Media -> {
                    if (frame.channel == VIDEO_CHANNEL) {
                        val rtp = RtpPacket.payload(frame.payload)
                        val type = RtpPacket.payloadType(frame.payload)
                        if (rtp != null && (type == null || type == payloadType)) {
                            depacketizer.push(rtp).forEach(onVideo)
                            frames++
                            if (frames == 1 || frames % REPORT_EVERY == 0) {
                                onEvent(
                                    "Podgląd: obraz płynie",
                                    mapOf(
                                        "pakietów" to frames,
                                        "jednostek" to depacketizer.produced,
                                        "odrzuconych" to depacketizer.dropped,
                                        "parametryObrazu" to depacketizer.sawParameterSets
                                    )
                                )
                            }
                        }
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastKeepAliveMs > KEEP_ALIVE_MS) {
                lastKeepAliveMs = now
                sendKeepAlive()
            }
        }
    }

    /**
     * Podtrzymuje sesję, żeby serwer jej nie zwolnił.
     *
     * Odpowiedzi NIE czekamy tutaj - przyjdzie własną drogą, do pętli odbioru,
     * bo to jedno i to samo połączenie. Czekanie w tym miejscu wstrzymywałoby
     * obraz na czas odpowiedzi.
     */
    private fun sendKeepAlive() {
        runCatching {
            val out = socket?.getOutputStream() ?: return
            val headers = sessionId?.let { mapOf("Session" to it) } ?: emptyMap()
            out.write(
                RtspProtocol.request("OPTIONS", url, cseq++, headers).toByteArray(Charsets.US_ASCII)
            )
            out.flush()
        }.onFailure { Log.w(tag, "Podtrzymanie sesji nie poszło", it) }
    }

    /**
     * Wysyła żądanie i czeka na TEKSTOWĄ odpowiedź.
     *
     * Ramki binarne, które trafią się po drodze, są pomijane: serwer potrafi
     * zacząć nadawać, zanim odpowie na PLAY, a potraktowanie obrazu jako
     * odpowiedzi zerwałoby zestawianie sesji tuż przed metą.
     */
    private fun exchange(
        out: OutputStream,
        reader: InterleavedReader,
        method: String,
        target: String,
        headers: Map<String, String>
    ): RtspProtocol.Reply? {
        val all = headers + (sessionId?.let { mapOf("Session" to it) } ?: emptyMap())
        out.write(RtspProtocol.request(method, target, cseq++, all).toByteArray(Charsets.US_ASCII))
        out.flush()

        repeat(MAX_FRAMES_BEFORE_REPLY) {
            when (val frame = reader.read() ?: return null) {
                is InterleavedReader.Frame.Text ->
                    return RtspProtocol.parseReply(frame.raw)
                is InterleavedReader.Frame.Media -> Unit
            }
        }
        return null
    }

    private fun parseTarget(): Pair<String, Int>? {
        val withoutScheme = url.removePrefix("rtsp://").removePrefix("RTSP://")
        val authority = withoutScheme.substringBefore('/')
        val host = authority.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
        val port = authority.substringAfter(':', "").toIntOrNull() ?: RtspProtocol.DEFAULT_PORT
        return host to port
    }

    private fun fail(reason: String): Boolean {
        lastFailure = reason
        onEvent("Podgląd: sesja RTSP nie ruszyła", mapOf("powód" to reason))
        return false
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        depacketizer.reset()
    }

    private companion object {
        /** Kanał obrazu uzgadniany w SETUP - parzysty dla RTP, nieparzysty dla RTCP. */
        const val VIDEO_CHANNEL = 0

        const val CONNECT_TIMEOUT_MS = 5_000

        /**
         * Cisza, po której uznajemy połączenie za martwe.
         *
         * Hojnie względem podtrzymania sesji: przy strumieniu na żywo pakiety
         * idą kilkadziesiąt razy na sekundę, więc dziesięć sekund ciszy znaczy,
         * że naprawdę nic nie przychodzi.
         */
        const val READ_TIMEOUT_MS = 10_000

        /** Co ile podtrzymywać sesję - poniżej typowego limitu serwerów (60 s). */
        const val KEEP_ALIVE_MS = 25_000L

        /** Ile ramek obrazu wolno pominąć, czekając na odpowiedź tekstową. */
        const val MAX_FRAMES_BEFORE_REPLY = 200

        /** Co ile pakietów meldować w dzienniku, żeby go nie zalać. */
        const val REPORT_EVERY = 300

        const val BUFFER_BYTES = 64 * 1024
    }
}
