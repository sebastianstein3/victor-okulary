package pl.victor.app.features.meeting

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Rozpoznanie mówców we fragmencie spotkania przez `gemini-3.5-transcribe`
 * (Interactions API, diaryzacja) - patrz [SpeakerTranscript].
 *
 * Nagranie idzie w samym zapytaniu jako base64, ale STRUMIENIEM z pliku:
 * 7 min to 18 MB tekstu, którego nie budujemy w pamięci (patrz
 * [SpeakerTranscript.częściZapytania]).
 *
 * Koszt jak zwykłej transkrypcji Gemini: ok. $0,003 za minutę nagrania.
 */
class MeetingDiarizer(private val apiKey: String) {

    /**
     * @param odpowiedź sparsowana odpowiedź albo `null`
     * @param błąd kod HTTP i początek odpowiedzi / wyjątek / "bez mówców" - do dziennika
     */
    data class Wynik(val odpowiedź: SpeakerTranscript.Odpowiedź?, val błąd: String?, val ms: Long, val prób: Int)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        // Siedem minut nagrania usługa przepisuje dłużej niż kilkusekundowe pytanie.
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun rozpoznaj(pcm: File, languageTag: String): Wynik = withContext(Dispatchers.IO) {
        val od = System.currentTimeMillis()
        var błąd: String? = null
        var próba = 0
        while (próba < PRÓB) {
            próba++
            try {
                val request = Request.Builder()
                    .url(URL)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(ciało(pcm, languageTag))
                    .build()
                client.newCall(request).execute().use { r ->
                    val tekst = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        błąd = "HTTP ${r.code}: ${tekst.take(200)}"
                        Log.w(TAG, "Rozpoznanie mówców odrzucone: ${r.code}")
                        // 429 i 5xx bywają chwilowe; 400/401/403 nie miną same.
                        if (r.code != 429 && r.code < 500) {
                            return@withContext Wynik(null, błąd, System.currentTimeMillis() - od, próba)
                        }
                    } else {
                        val o = SpeakerTranscript.parsuj(tekst)
                        val b = when {
                            o == null -> "nieczytelna odpowiedź: ${tekst.take(200)}"
                            !o.maMówców -> "bez mówców w odpowiedzi: ${tekst.take(200)}"
                            else -> null
                        }
                        return@withContext Wynik(o, b, System.currentTimeMillis() - od, próba)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                błąd = e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: "")
                Log.w(TAG, "Rozpoznanie mówców nie powiodło się", e)
            }
            if (próba < PRÓB) delay(PRZERWA_MS)
        }
        Wynik(null, błąd, System.currentTimeMillis() - od, próba)
    }

    /** JSON z nagraniem WAV w base64, pisany strumieniem z pliku przy wysyłaniu. */
    private fun ciało(pcm: File, languageTag: String): RequestBody {
        val pcmBajtów = pcm.length()
        val długość = SpeakerTranscript.długośćZapytania(languageTag, pcmBajtów)
        return object : RequestBody() {
            override fun contentType(): MediaType = "application/json".toMediaType()
            override fun contentLength(): Long = długość
            override fun writeTo(sink: BufferedSink) {
                pcm.inputStream().use {
                    SpeakerTranscript.zapiszZapytanie(sink.outputStream(), languageTag, pcmBajtów, it)
                }
                sink.flush()
            }
        }
    }

    companion object {
        private const val TAG = "MeetingDiarizer"
        private const val URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val PRÓB = 2
        private const val PRZERWA_MS = 5_000L
    }
}
