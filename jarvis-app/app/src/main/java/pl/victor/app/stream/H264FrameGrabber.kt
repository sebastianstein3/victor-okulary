package pl.victor.app.stream

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Wyjmuje ze strumienia pojedyncze klatki jako JPEG - bez pokazywania czegokolwiek.
 *
 * ## Po co osobno od [H264Renderer]
 * Bo to są dwa różne zadania i różnią się w jednym, rozstrzygającym miejscu:
 * `MediaCodec` skonfigurowany z POWIERZCHNIĄ rysuje obraz prosto na ekran i
 * nie oddaje pikseli, a skonfigurowany BEZ niej oddaje piksele i nic nie
 * rysuje. Jeden dekoder nie zrobi obu rzeczy naraz.
 *
 * Dla trybu dla niewidomych ekran nie jest do niczego potrzebny - potrzebne są
 * bajty. Stąd ten wariant.
 *
 * ## Co to zmienia
 * Tryb opisu otoczenia bierze dziś obraz przez migawkę i MINIATURĘ 9 KB -
 * pełne zdjęcie kosztuje kilkanaście sekund przez Wi-Fi Direct, więc używa go
 * tylko czytanie tekstu. Klatka ze strumienia ma 1600x1200 (zmierzone 14
 * września) i jest dostępna od ręki, bo obraz i tak już płynie.
 *
 * Ale tylko GDY PŁYNIE: samo podniesienie strumienia kosztuje circa 8,4 s,
 * czyli więcej niż zdjęcie (circa 3,3 s). Dlatego to jest źródło dla trybu
 * ciągłego, a nie zamiennik dla pojedynczego pytania - wołający ma o tym
 * wiedzieć i wybierać.
 */
class H264FrameGrabber(
    maxSide: Int = SCENE_MAX_SIDE,
    private val quality: Int = DEFAULT_QUALITY,
    private val onEvent: (String, Map<String, Any?>, Boolean) -> Unit = { _, _, _ -> }
) {

    /**
     * Dłuższy bok klatki oddawanej dalej - ZMIENNY W TRAKCIE.
     *
     * ## Czemu nie jedna stała
     * Bo to są dwie różne potrzeby i jedna liczba nie obsłuży obu. Do "przed
     * tobą schody" osiemset pikseli wystarcza z zapasem, a każdy piksel ponad
     * potrzebę to koszt zapytania i czas wysyłki. Ale do przeczytania napisu
     * osiemset z oryginalnych tysiąca sześciuset to za mało - litery z
     * odległości robią się nieczytelne i model zaczyna zgadywać.
     *
     * Tę samą prawdę zapisał już wcześniej kod zdjęć: "miniatura po BLE nie
     * niesie liter z bliska". Tyle że tam sięgnięcie po szczegół kosztuje
     * kilkanaście sekund przez Wi-Fi Direct, a tutaj - jedną klatkę, czyli
     * circa 33 ms, bo obraz i tak płynie.
     *
     * Zmiana działa od NASTĘPNEJ klatki; bieżąca jest już przepisana.
     */
    @Volatile
    var maxSide: Int = maxSide

    private val tag = "H264FrameGrabber"
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var started = false
    private var fed = 0

    /**
     * Najświeższa klatka jako JPEG - albo `null`, dopóki żadna nie doszła.
     *
     * Trzymamy JEDNĄ, zawsze najnowszą. Kolejka klatek byłaby tu szkodliwa:
     * niewidomy pyta "co przede mną", a nie "co było przede mną trzy sekundy
     * temu".
     */
    @Volatile
    var latestJpeg: ByteArray? = null
        private set

    /** Wymiary ostatniej klatki - do dziennika. */
    @Volatile
    var latestSize: Pair<Int, Int>? = null
        private set

    /** Ile klatek przepisaliśmy. */
    @Volatile
    var grabbed: Int = 0
        private set

    /** Ile klatek trzeba było pominąć jako niespójne. */
    @Volatile
    var skipped: Int = 0
        private set

    /** Uruchamia dekoder BEZ powierzchni. Zwraca `false`, gdy sprzęt go nie dał. */
    fun start(): Boolean = runCatching {
        val format = MediaFormat.createVideoFormat(MIME, INITIAL_WIDTH, INITIAL_HEIGHT)
        // Bez tego sprzęt może oddać własny, zamknięty układ bajtów, którego
        // nie da się odczytać z procesora.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_FLEXIBLE)
        val c = MediaCodec.createDecoderByType(MIME)
        // `surface = null` - to jest cała różnica wobec [H264Renderer].
        c.configure(format, null, null, 0)
        c.start()
        codec = c
        started = true
        onEvent("Klatki: dekoder uruchomiony", mapOf("maxBok" to maxSide), false)
        true
    }.getOrElse {
        Log.e(tag, "Nie udało się uruchomić dekodera klatek", it)
        onEvent(
            "Klatki: dekoder NIE ruszył",
            mapOf("powód" to "${it.javaClass.simpleName}: ${it.message}"),
            true
        )
        false
    }

    /** Wrzuca jednostkę obrazu i przepisuje to, co dekoder ma gotowe. */
    fun feed(nal: ByteArray) {
        val c = codec ?: return
        runCatching {
            val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                c.getInputBuffer(index)?.let { buffer ->
                    buffer.clear()
                    buffer.put(nal)
                    c.queueInputBuffer(index, 0, nal.size, fed.toLong() * FRAME_STEP_US, 0)
                    fed++
                }
            }
            drain(c)
        }.onFailure { Log.w(tag, "Dekoder klatek odrzucił dane", it) }
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val index = c.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            runCatching { convert(c, index) }
                .onFailure { Log.w(tag, "Przepisanie klatki nie powiodło się", it) }
            // Bufor MUSI wrócić do dekodera niezależnie od tego, czy
            // przepisanie się udało - inaczej dekoder zapycha się po
            // kilkunastu klatkach i przestaje przyjmować cokolwiek.
            runCatching { c.releaseOutputBuffer(index, false) }
        }
    }

    private fun convert(c: MediaCodec, index: Int) {
        val image = c.getOutputImage(index) ?: return
        image.use { img ->
            val planes = img.planes
            if (planes.size < PLANE_COUNT) {
                skipped++
                return
            }
            val step = YuvFrame.sampleFor(img.width, img.height, maxSide)
            val nv21 = YuvFrame.toNv21(
                y = planes[0].buffer.toByteArray(),
                yRowStride = planes[0].rowStride,
                yPixelStride = planes[0].pixelStride,
                u = planes[1].buffer.toByteArray(),
                uRowStride = planes[1].rowStride,
                uPixelStride = planes[1].pixelStride,
                v = planes[2].buffer.toByteArray(),
                vRowStride = planes[2].rowStride,
                vPixelStride = planes[2].pixelStride,
                width = img.width,
                height = img.height,
                sample = step
            )
            if (nv21 == null) {
                skipped++
                return
            }
            val out = ByteArrayOutputStream()
            val yuv = YuvImage(nv21.bytes, ImageFormat.NV21, nv21.width, nv21.height, null)
            yuv.compressToJpeg(Rect(0, 0, nv21.width, nv21.height), quality, out)
            latestJpeg = out.toByteArray()
            latestSize = nv21.width to nv21.height
            val first = grabbed == 0
            grabbed++
            if (first) {
                onEvent(
                    "Klatki: PIERWSZA KLATKA WYJĘTA",
                    mapOf(
                        "zeStrumienia" to "${img.width}x${img.height}",
                        "oddana" to "${nv21.width}x${nv21.height}",
                        "bajtów" to latestJpeg?.size,
                        "krok" to step
                    ),
                    false
                )
            }
        }
    }

    /** Zatrzymuje dekoder. Wolno wołać wielokrotnie. */
    fun release() {
        if (!started) return
        started = false
        onEvent(
            "Klatki: dekoder zatrzymany",
            mapOf("wyjętych" to grabbed, "pominiętych" to skipped),
            false
        )
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        latestJpeg = null
    }

    private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
        val bytes = ByteArray(remaining())
        get(bytes)
        return bytes
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * Układ bajtów, który da się odczytać z procesora.
         *
         * `COLOR_FormatYUV420Flexible` - stała z `MediaCodecInfo.CodecCapabilities`,
         * wpisana wprost, żeby nie ciągnąć całej klasy dla jednej liczby.
         */
        const val COLOR_FORMAT_FLEXIBLE = 0x7F420888

        const val INITIAL_WIDTH = 1280
        const val INITIAL_HEIGHT = 720
        const val INPUT_TIMEOUT_US = 10_000L
        const val FRAME_STEP_US = 33_333L
        const val PLANE_COUNT = 3

        /**
         * Do opisu otoczenia i nawigacji.
         *
         * Osiemset pikseli to przy 1600x1200 dokładnie co drugi piksel - czyli
         * najtańsze możliwe pomniejszenie, a wciąż czterokrotnie więcej treści
         * niż dzisiejsza miniatura 9 KB.
         */
        const val SCENE_MAX_SIDE = 800

        /**
         * Do czytania tekstu i kodów - pełna klatka, bez pomniejszania.
         *
         * Zgłoszone wprost: "nie widać detali i napisów". Przy literach z
         * odległości zmniejszenie o połowę rozstrzyga między przeczytaniem a
         * zgadywaniem, a zapytanie z większym obrazem kosztuje ułamek tego, co
         * druga tura z powodu błędnego odczytu.
         */
        const val TEXT_MAX_SIDE = 1600

        /** Kompromis: opis sceny nie potrzebuje jakości archiwalnej. */
        const val DEFAULT_QUALITY = 80
    }
}
