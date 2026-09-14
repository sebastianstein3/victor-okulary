package pl.victor.app.stream

import java.io.EOFException
import java.io.InputStream

/**
 * Czyta to, co płynie jednym połączeniem TCP z serwerem RTSP.
 *
 * ## Czemu obraz i sterowanie idą tym samym połączeniem
 * Bo tak wybiera producent: jego odtwarzacz dostaje `--rtsp-tcp`, czyli RTP
 * wplecione w połączenie sterujące zamiast osobnych gniazd UDP. Dla nas to
 * dodatkowo jedyna droga, przy której da się wskazać sieć okularów - gniazdo
 * jest jedno i bierzemy je z [android.net.Network.getSocketFactory].
 *
 * ## Dwie rzeczy w jednym strumieniu
 * Po tym samym połączeniu przychodzą NA PRZEMIAN ramki z obrazem i tekstowe
 * odpowiedzi serwera. Rozróżnia je pierwszy bajt: `$` zaczyna ramkę binarną,
 * cokolwiek innego zaczyna wiersz `RTSP/1.0 ...`. Czytnik, który zakłada tylko
 * jedno z dwojga, rozjeżdża się przy pierwszej odpowiedzi na podtrzymanie
 * sesji i od tego miejsca wszystko, co przeczyta, jest przesunięte.
 */
class InterleavedReader(private val input: InputStream) {

    /** Co przyszło z serwera. */
    sealed interface Frame {

        /** Ramka binarna: numer kanału z SETUP i surowy pakiet RTP. */
        data class Media(val channel: Int, val payload: ByteArray) : Frame {
            // ByteArray w data class porównuje się przez referencję, a wtedy
            // equals kłamie. Dla ramki nie ma to zastosowania, ale zostawiony
            // domyślny equals jest pułapką dla następnego czytającego.
            override fun equals(other: Any?): Boolean =
                this === other ||
                    (other is Media && channel == other.channel && payload.contentEquals(other.payload))

            override fun hashCode(): Int = 31 * channel + payload.contentHashCode()
        }

        /** Tekstowa odpowiedź serwera, w całości - z nagłówkami i treścią. */
        data class Text(val raw: String) : Frame
    }

    /**
     * Czyta jedną rzecz ze strumienia.
     *
     * @return ramka albo `null`, gdy połączenie się skończyło
     */
    fun read(): Frame? {
        val first = input.read()
        if (first < 0) return null
        return if (first.toByte() == RtspProtocol.INTERLEAVED_MARKER) {
            readMedia()
        } else {
            readText(first)
        }
    }

    private fun readMedia(): Frame.Media {
        val channel = readByteOrThrow()
        val high = readByteOrThrow()
        val low = readByteOrThrow()
        val length = (high shl 8) or low
        val payload = ByteArray(length)
        var read = 0
        while (read < length) {
            // `read` na gnieździe oddaje TYLE, ILE AKURAT PRZYSZŁO, nie tyle, ile
            // prosimy. Pojedyncze wywołanie wystarcza przy małych ramkach i
            // zawodzi dokładnie przy dużych - czyli przy klatkach kluczowych,
            // które są najważniejsze.
            val n = input.read(payload, read, length - read)
            if (n < 0) throw EOFException("Połączenie zerwane w środku ramki")
            read += n
        }
        return Frame.Media(channel, payload)
    }

    /**
     * Czyta tekstową odpowiedź: nagłówki do pustego wiersza, potem treść o
     * długości podanej w `Content-Length`.
     *
     * Treść trzeba doczytać co do bajtu, bo zaraz za nią zaczyna się kolejna
     * ramka - zostawienie choćby jednego bajtu przesuwa wszystko, co potem.
     */
    private fun readText(firstByte: Int): Frame.Text {
        val head = StringBuilder()
        head.append(firstByte.toChar())
        while (!head.endsWith("\r\n\r\n") && !head.endsWith("\n\n")) {
            val b = input.read()
            if (b < 0) return Frame.Text(head.toString())
            head.append(b.toChar())
        }
        val headText = head.toString()
        val length = contentLength(headText)
        if (length <= 0) return Frame.Text(headText)

        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Frame.Text(headText + String(body, 0, read, Charsets.US_ASCII))
    }

    private fun readByteOrThrow(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("Połączenie zerwane w nagłówku ramki")
        return b and 0xFF
    }

    private companion object {

        /** Wyciąga `Content-Length` bez rozróżniania wielkości liter. */
        fun contentLength(head: String): Int = head
            .split("\r\n", "\n")
            .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: 0
    }
}
