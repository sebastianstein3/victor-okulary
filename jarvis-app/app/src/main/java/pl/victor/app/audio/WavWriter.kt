package pl.victor.app.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pakuje surowe PCM w plik WAV.
 *
 * Modele multimodalne przyjmują audio jako `audio/wav` - gołe PCM bez nagłówka
 * nie niesie informacji o częstotliwości próbkowania ani liczbie kanałów, więc
 * po drugiej stronie brzmi jak szum albo jak nagranie puszczone w złym tempie.
 * Nagłówek WAV to 44 bajty i rozwiązuje to raz na zawsze.
 */
object WavWriter {

    /**
     * @param pcm próbki 16 bit little-endian
     * @param sampleRate częstotliwość próbkowania PCM (nie ta z pytania!)
     * @param channels liczba kanałów
     */
    fun wrap(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream(HEADER_SIZE + pcm.size)
        out.write(header(pcm.size, sampleRate, channels))
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * Sam nagłówek WAV dla [pcmBytes] bajtów próbek - do wysyłania długiego
     * nagrania strumieniem z pliku (notatki ze spotkania), bez trzymania
     * kilkunastu MB w pamięci.
     */
    fun header(pcmBytes: Int, sampleRate: Int, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream(HEADER_SIZE)
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
        val blockAlign = channels * BYTES_PER_SAMPLE

        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        // Pole RIFF liczy WSZYSTKO po sobie: "WAVE" (4) + nagłówek i treść
        // sekcji "fmt " (8 + 16) + nagłówek sekcji "data" (8) + same próbki.
        out.write(le32(RIFF_OVERHEAD + pcmBytes))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))

        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(le32(SUBCHUNK1_SIZE))
        out.write(le16(PCM_FORMAT))
        out.write(le16(channels))
        out.write(le32(sampleRate))
        out.write(le32(byteRate))
        out.write(le16(blockAlign))
        out.write(le16(BITS_PER_SAMPLE))

        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(le32(pcmBytes))
        return out.toByteArray()
    }

    /** Ile sekund trwa takie nagranie - do pokazania w diagnostyce. */
    fun durationSeconds(pcmBytes: Int, sampleRate: Int, channels: Int = 1): Double =
        pcmBytes.toDouble() / (sampleRate * channels * BYTES_PER_SAMPLE)

    private fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun le16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    const val HEADER_SIZE = 44

    /** Wszystko w nagłówku poza polem RIFF i samymi próbkami: 44 - 8. */
    private const val RIFF_OVERHEAD = 36
    private const val SUBCHUNK1_SIZE = 16
    private const val PCM_FORMAT = 1
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2
}
