package pl.victor.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hałas wokół - żeby odpowiedź na ulicy nie ginęła, a w cichym pokoju nie
 * krzyczała.
 *
 * ## Czemu mikrofon TELEFONU, nie okularów
 * Okulary same wycinają tło: w dzienniku tło ich nagrań to 0-11 przy
 * szczytach mowy w tysiącach, niezależnie od miejsca. Z takiego sygnału nie da
 * się odczytać, czy wokół jest gwar. Telefon nagrywa surowo - nawet z kieszeni
 * słychać różnicę między pokojem a tramwajem.
 *
 * Pomiar trwa ułamek sekundy i robimy go wtedy, gdy mikrofon na pewno jest
 * wolny: zaraz po zakończonym nasłuchu, gdy model jeszcze myśli. Kosztuje więc
 * zero czasu odpowiedzi. Mikrofon wybieramy wprost (wbudowany), bo przy
 * łączu SCO zwykłe źródło poszłoby do okularów.
 */
class AmbientNoise(private val context: Context) {

    @Volatile
    private var ostatniDb: Double? = null

    @Volatile
    private var kiedy = 0L

    /** Ostatni pomiar, o ile świeży. */
    fun aktualnyDb(): Double? =
        ostatniDb?.takeIf { System.currentTimeMillis() - kiedy < NoiseLogic.WAŻNY_MS }

    /** Mierzy tło przez [NoiseLogic.POMIAR_MS] ms; `null` gdy się nie da. */
    suspend fun zmierz(): Double? = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return@withContext null
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return@withContext null
        val samples = ShortArray(rate * NoiseLogic.POMIAR_MS / 1000)
        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, samples.size * 2)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Pomiar hałasu niemożliwy", e)
            return@withContext null
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return@withContext null
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { rec.setPreferredDevice(it) }
            rec.startRecording()
            var n = 0
            while (n < samples.size) {
                val r = rec.read(samples, n, samples.size - n)
                if (r <= 0) break
                n += r
            }
            val db = NoiseLogic.tłoDb(samples, n, rate)
            if (db != null) {
                ostatniDb = db
                kiedy = System.currentTimeMillis()
            }
            db
        } catch (e: Exception) {
            Log.w(TAG, "Pomiar hałasu przerwany", e)
            null
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private companion object {
        const val TAG = "AmbientNoise"
    }
}
