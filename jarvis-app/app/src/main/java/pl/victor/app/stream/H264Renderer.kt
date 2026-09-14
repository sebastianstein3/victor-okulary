package pl.victor.app.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * Rysuje obraz H.264 na powierzchni ekranu, karmiony jednostkami Annex-B.
 *
 * ## Czemu to wystarcza, skoro media3 odmawiał
 * Bo `MediaCodec` nie potrzebuje parametrów obrazu z opisu sesji. Wystarczy, że
 * SPS i PPS przyjdą W STRUMIENIU jako zwykłe jednostki - a okulary właśnie tak
 * je wysyłają. Dekoder rozpoznaje je sam i sam ustawia rozdzielczość.
 *
 * Dlatego format przy uruchomieniu podajemy bez `csd-0` i `csd-1`: nie mamy ich
 * i nie są potrzebne. Rozmiar też jest wstępny - prawdziwy przyjdzie z SPS i
 * zgłosi się przez zmianę formatu wyjściowego.
 *
 * ## Zasada
 * Karmienie i odbieranie idą w JEDNYM wątku, tym, który woła [feed]. `MediaCodec`
 * nie jest bezpieczny przy dowolnym mieszaniu wątków, a sesja RTSP i tak ma
 * własny wątek - więc obraz płynie prosto z niej do dekodera.
 */
class H264Renderer(
    private val surface: Surface,
    private val onEvent: (String, Map<String, Any?>) -> Unit = { _, _ -> }
) {

    private val tag = "H264Renderer"
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var started = false
    private var fed = 0
    private var rendered = 0

    /** Czy dekoder wypuścił choć jedną klatkę - czyli czy na ekranie COŚ jest. */
    @Volatile
    var sawFrame: Boolean = false
        private set

    /** Uruchamia dekoder. Zwraca `false`, gdy sprzęt go nie dał. */
    fun start(): Boolean = runCatching {
        val format = MediaFormat.createVideoFormat(MIME, INITIAL_WIDTH, INITIAL_HEIGHT)
        val c = MediaCodec.createDecoderByType(MIME)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        started = true
        onEvent("Podgląd: dekoder uruchomiony", mapOf("format" to MIME))
        true
    }.getOrElse {
        Log.e(tag, "Nie udało się uruchomić dekodera", it)
        onEvent(
            "Podgląd: dekoder NIE ruszył",
            mapOf("powód" to "${it.javaClass.simpleName}: ${it.message}")
        )
        false
    }

    /**
     * Wrzuca jedną jednostkę obrazu i oddaje na ekran to, co dekoder ma gotowe.
     *
     * Odbiór idzie w tym samym wywołaniu, bo bufory wyjściowe trzeba zwalniać -
     * inaczej dekoder zapycha się po kilkunastu klatkach i przestaje przyjmować
     * cokolwiek, co z zewnątrz wygląda jak zamrożony obraz.
     */
    fun feed(nal: ByteArray) {
        val c = codec ?: return
        runCatching {
            val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val buffer = c.getInputBuffer(index)
                if (buffer != null) {
                    buffer.clear()
                    buffer.put(nal)
                    c.queueInputBuffer(index, 0, nal.size, presentationTimeUs(), 0)
                    fed++
                }
            }
            drain(c)
        }.onFailure {
            Log.w(tag, "Dekoder odrzucił dane", it)
        }
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val index = c.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    // `render = true` oddaje klatkę na powierzchnię i zwalnia bufor.
                    c.releaseOutputBuffer(index, true)
                    rendered++
                    if (!sawFrame) {
                        sawFrame = true
                        onEvent("Podgląd: PIERWSZA KLATKA NA EKRANIE", mapOf("wkarmionych" to fed))
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Tu przychodzi PRAWDZIWA rozdzielczość, odczytana z SPS w
                    // strumieniu - czyli dokładnie ta informacja, której media3
                    // wymagał w opisie sesji i bez której odmawiał.
                    val f = c.outputFormat
                    onEvent(
                        "Podgląd: rozdzielczość ze strumienia",
                        mapOf(
                            "szerokość" to runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull(),
                            "wysokość" to runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull()
                        )
                    )
                }
                else -> return
            }
        }
    }

    /**
     * Znacznik czasu klatki.
     *
     * Dekoder wypuszczający obraz prosto na powierzchnię nie synchronizuje go z
     * zegarem - kolejność wystarcza. Rosnący licznik jest tu uczciwszy niż
     * udawanie czasu, którego ze strumienia na żywo nie potrzebujemy.
     */
    private fun presentationTimeUs(): Long = fed.toLong() * FRAME_STEP_US

    /** Zatrzymuje dekoder i zwalnia sprzęt. Wolno wołać wielokrotnie. */
    fun release() {
        if (!started) return
        started = false
        onEvent(
            "Podgląd: dekoder zatrzymany",
            mapOf("wkarmionych" to fed, "wyświetlonych" to rendered)
        )
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * Rozmiar wstępny - prawdziwy przychodzi z SPS w strumieniu.
         *
         * `MediaFormat` wymaga jakiegoś przy tworzeniu, ale dla dekodera to
         * tylko podpowiedź: przy pierwszym SPS zgłosi zmianę formatu i wtedy
         * poznamy rozdzielczość, którą okulary naprawdę nadają.
         */
        const val INITIAL_WIDTH = 1280
        const val INITIAL_HEIGHT = 720

        const val INPUT_TIMEOUT_US = 10_000L

        /** Krok znacznika czasu - circa 30 klatek na sekundę. */
        const val FRAME_STEP_US = 33_333L
    }
}
