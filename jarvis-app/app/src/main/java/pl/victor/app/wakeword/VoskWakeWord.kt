package pl.victor.app.wakeword

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Wykrywanie frazy wybudzenia przez Vosk - offline, bez konta i bez klucza.
 *
 * ## Po co, skoro jest Porcupine
 * Bo Picovoice wymaga konta, a własna fraza - płatnego planu. Zgłoszone wprost:
 * "Picovoice wymaga konta firmowego, nie da się użyć innego narzędzia?". Vosk
 * jest na licencji Apache 2.0: żadnego klucza, żadnej rejestracji, model
 * ściąga się raz i zostaje na telefonie.
 *
 * ## Czym to NIE jest
 * Vosk to pełne rozpoznawanie mowy, nie wyspecjalizowany detektor słowa
 * kluczowego. Zawężamy je GRAMATYKĄ do jednej frazy plus "[unk]", więc silnik
 * ma tylko dwie możliwe odpowiedzi i nie próbuje transkrybować całego
 * otoczenia. Mimo to kosztuje więcej procesora i baterii niż Porcupine, który
 * jest projektowany pod ciągły nasłuch. To jest realny kompromis, nie
 * zamiennik jeden do jednego - i dlatego wybór silnika należy do użytkownika.
 *
 * ## Model
 * Nie leży w APK: ma kilkadziesiąt megabajtów i nie każdy go chce. Pobierany
 * jest na żądanie pod [modelDir]. Adres jest ustawieniem, a nie stałą, bo
 * nazwy plików modeli zmieniają się z wersjami - a wtedy lepiej wkleić nowy
 * adres niż czekać na nową wersję aplikacji.
 */
class VoskWakeWord(private val context: Context) {

    private val tag = "VoskWakeWord"

    private var model: Model? = null
    private var speechService: SpeechService? = null

    /** Katalog rozpakowanego modelu. */
    val modelDir: File get() = File(context.filesDir, MODEL_DIR_NAME)

    /** Czy model jest już na telefonie i da się od razu nasłuchiwać. */
    fun isModelReady(): Boolean =
        modelDir.isDirectory && (modelDir.listFiles()?.isNotEmpty() == true)

    /**
     * Pobiera i rozpakowuje model.
     *
     * @param onProgress ułamek 0.0-1.0 albo `null`, gdy serwer nie podał rozmiaru
     * @return opis błędu albo `null`, gdy się udało
     */
    suspend fun downloadModel(
        url: String,
        onProgress: (Float?) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val target = modelDir
        val zip = File(context.cacheDir, "vosk-model.zip")
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // Kod HTTP wprost w komunikacie: 404 znaczy "zmieniła się nazwa
                // pliku modelu" i wtedy wystarczy wkleić nowy adres, a nie
                // szukać usterki w aplikacji.
                return@withContext "Serwer odpowiedział kodem $code. Sprawdź adres modelu."
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            connection.inputStream.use { input ->
                zip.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(if (total > 0) downloaded.toFloat() / total else null)
                    }
                }
            }

            // Rozpakowujemy do katalogu tymczasowego i dopiero na końcu
            // podmieniamy - przerwane rozpakowanie zostawiłoby model, który
            // wygląda na gotowy, a nie działa.
            val staging = File(context.filesDir, "$MODEL_DIR_NAME.tmp")
            staging.deleteRecursively()
            staging.mkdirs()
            unzip(zip, staging)
            target.deleteRecursively()
            // Archiwa Voska mają jeden katalog na wierzchu - wchodzimy do niego,
            // żeby ścieżka wskazywała na sam model, a nie na jego opakowanie.
            val root = staging.listFiles()?.singleOrNull { it.isDirectory } ?: staging
            if (!root.renameTo(target)) {
                root.copyRecursively(target, overwrite = true)
            }
            staging.deleteRecursively()
            Log.i(tag, "Model Voska gotowy: ${target.absolutePath}")
            null
        } catch (e: Exception) {
            Log.e(tag, "Pobranie modelu nie powiodło się", e)
            "Nie udało się pobrać modelu: ${e.message}"
        } finally {
            zip.delete()
        }
    }

    private fun unzip(zip: File, destination: File) {
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = File(destination, entry.name)
                // Zip Slip: wpis może mieć w nazwie "../" i wyjść poza katalog.
                if (!out.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                    throw SecurityException("Wpis archiwum wychodzi poza katalog: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
                input.closeEntry()
            }
        }
    }

    /**
     * Zaczyna nasłuchiwać frazy.
     *
     * @param phrase fraza wybudzenia, małymi literami, bez znaków diakrytycznych
     *   tam, gdzie model ich nie zna
     * @return opis błędu albo `null`, gdy ruszyło
     */
    fun start(phrase: String, onDetected: () -> Unit): String? {
        if (!isModelReady()) return "Model nie jest pobrany."
        stop()
        return try {
            val loaded = model ?: Model(modelDir.absolutePath).also { model = it }
            // Gramatyka zawęża silnik do JEDNEJ frazy plus "[unk]" - bez niej
            // Vosk transkrybowałby wszystko, co słyszy, i kosztował wielokrotnie
            // więcej procesora.
            val grammar = "[${JSONObject.quote(phrase)}, ${JSONObject.quote("[unk]")}]"
            val recognizer = Recognizer(loaded, SAMPLE_RATE, grammar)
            activeRecognizer = recognizer
            val service = SpeechService(recognizer, SAMPLE_RATE)
            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (matches(hypothesis, phrase)) onDetected()
                }

                override fun onResult(hypothesis: String?) {
                    if (matches(hypothesis, phrase)) onDetected()
                }

                override fun onFinalResult(hypothesis: String?) = Unit

                override fun onError(exception: Exception?) {
                    Log.w(tag, "Vosk zgłosił błąd", exception)
                }

                override fun onTimeout() = Unit
            })
            speechService = service
            Log.i(tag, "Nasłuch Voska wystartował (fraza: \"$phrase\")")
            null
        } catch (e: Exception) {
            Log.e(tag, "Nie udało się wystartować nasłuchu Voska", e)
            "Nasłuch nie wystartował: ${e.message}"
        }
    }

    /**
     * Czy w odpowiedzi silnika jest nasza fraza.
     *
     * Vosk oddaje JSON - z pola "text" przy wyniku i "partial" przy częściowym.
     * Sprawdzamy oba, bo reakcja na wynik częściowy jest zauważalnie szybsza, a
     * przy gramatyce z jedną frazą ryzyko pomyłki jest małe.
     */
    private fun matches(hypothesis: String?, phrase: String): Boolean {
        val json = hypothesis ?: return false
        val heard = runCatching {
            val obj = JSONObject(json)
            obj.optString("text").ifBlank { obj.optString("partial") }
        }.getOrDefault("")
        return heard.contains(phrase, ignoreCase = true)
    }

    /**
     * Przepisuje NAGRANIE na tekst - bez mikrofonu, bez gramatyki.
     *
     * ## Po co, skoro jest rozpoznawanie systemowe
     * Bo systemowe działa tylko na Androidzie 13+ i tylko wtedy, gdy użytkownik ma
     * pobrany pakiet języka na urządzenie. Gdy go nie ma, pytanie leciało do modelu
     * jako DŹWIĘK, a wtedy żadna bramka kontekstu nie miała czego dopasować - model
     * dostawał nagranie bez kalendarza, pogody i notatek. Zgłoszone: "model nie
     * dostaje transkrypcji, tylko plik z głosem".
     *
     * Vosk liczy lokalnie i offline, więc jest ostatnią deską przed oddaniem
     * samego dźwięku.
     *
     * ## Czemu bez gramatyki
     * [start] zawęża silnik do jednej frazy, bo nasłuchuje bez przerwy i liczy się
     * koszt. Tutaj jest odwrotnie: przepisujemy jedno krótkie nagranie i chcemy
     * WSZYSTKICH słów, więc rozpoznawanie idzie na pełnym słowniku.
     *
     * Nie rusza mikrofonu - pracuje na gotowym buforze, więc nie wchodzi w drogę
     * ani nasłuchowi frazy, ani rozpoznawaniu systemowemu.
     *
     * @param pcm surowe 16-bitowe PCM, mono
     * @param sampleRate częstotliwość próbkowania nagrania
     * @return rozpoznany tekst albo `null`, gdy się nie da
     */
    suspend fun transcribe(
        pcm: ByteArray,
        sampleRate: Int = SAMPLE_RATE.toInt()
    ): String? = withContext(Dispatchers.Default) {
        if (pcm.isEmpty() || !isModelReady()) return@withContext null
        var recognizer: Recognizer? = null
        try {
            // Wczytanie modelu jest w tym samym `try`, bo rzuca tym samym
            // wyjątkiem co reszta i ma tę samą odpowiedź: nie da się, wracamy null.
            val loaded = model ?: Model(modelDir.absolutePath).also { model = it }
            recognizer = Recognizer(loaded, sampleRate.toFloat())
            // Porcjami, nie całością: silnik przyjmuje bufor po buforze, a przy
            // dłuższym nagraniu jedna wielka tablica to niepotrzebny skok pamięci.
            var offset = 0
            while (offset < pcm.size) {
                val length = minOf(CHUNK_BYTES, pcm.size - offset)
                recognizer.acceptWaveForm(pcm.copyOfRange(offset, offset + length), length)
                offset += length
            }
            val text = runCatching {
                JSONObject(recognizer.finalResult).optString("text").trim()
            }.getOrDefault("")
            if (text.isBlank()) {
                Log.i(tag, "Vosk nie rozpoznał w nagraniu żadnych słów")
                null
            } else {
                Log.i(tag, "Vosk przepisał nagranie: \"$text\"")
                text
            }
        } catch (e: Exception) {
            Log.w(tag, "Transkrypcja Voskiem nie powiodła się", e)
            null
        } finally {
            runCatching { recognizer?.close() }
        }
    }

    /** Kończy nasłuch i zwalnia mikrofon. */
    fun stop() {
        runCatching {
            speechService?.stop()
            speechService?.shutdown()
        }
        speechService = null
        // SpeechService nie zamyka Recognizera, a pauza/wznowienie nasłuchu
        // (każda tura) tworzy nowy - natywna pamięć rosła z każdą rozmową.
        // stop() dołączył wątek rozpoznawania, więc zamknięcie jest bezpieczne.
        runCatching { activeRecognizer?.close() }
        activeRecognizer = null
    }

    private var activeRecognizer: Recognizer? = null

    /** Zwalnia też model - przy wyłączaniu funkcji na dobre. */
    fun release() {
        stop()
        runCatching { model?.close() }
        model = null
    }

    companion object {
        /** Domyślny adres małego modelu polskiego. Do nadpisania w ustawieniach. */
        const val DEFAULT_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip"

        private const val MODEL_DIR_NAME = "vosk-model-pl"
        private const val SAMPLE_RATE = 16000.0f

        /** Ile bajtów naraz podajemy silnikowi przy transkrypcji. */
        private const val CHUNK_BYTES = 8192
        private const val BUFFER_BYTES = 32 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
