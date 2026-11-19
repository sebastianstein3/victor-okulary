package pl.victor.app.conversation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pl.victor.app.audio.WavWriter
import java.util.concurrent.TimeUnit

/**
 * Przepisuje nagranie na tekst przez usługę w chmurze.
 *
 * ## Po co, skoro jest rozpoznawanie na telefonie
 * Bo jest słabe tam, gdzie to najbardziej boli. Rozpoznawanie systemowe wymaga
 * Androida 13+ i pobranego pakietu języka, a przy zablokowanym ekranie na wielu
 * telefonach po prostu nie startuje. Mniejsze modele lokalne myliły słowa - a
 * przekręcone pytanie jest gorsze niż brak pytania, bo model odpowiada wtedy
 * pewnie i nie na temat. Zgłoszone: "na pytanie jaka jest pogoda
 * odpowiada, czym jest rozwód".
 *
 * Ta droga używa klucza, który jest już w ustawieniach - tego od WYBRANEGO
 * dostawcy: przy Gemini nagranie idzie do Google (`gemini-3.5-transcribe`,
 * $0,003/min), przy OpenAI do OpenAI (`gpt-transcribe`, $0,0045/min). Przy
 * innych dostawcach bierzemy ten z dwóch kluczy, który jest. Jakość dla
 * polszczyzny jest nieporównanie lepsza od rozpoznawania w telefonie.
 *
 * `whisper-1` OpenAI wyłącza 26.02.2027 - dlatego `gpt-transcribe`.
 *
 * ## Czego świadomie NIE robi
 * Nie wysyła niczego, gdy klucza nie ma. Nagranie głosu to dane wrażliwe i nie
 * może wychodzić z telefonu przez samo włączenie funkcji o innej nazwie - stąd
 * osobne ustawienie i wyraźny opis przy nim.
 */
class CloudSpeechToText(
    private val usługa: Usługa,
    private val apiKey: String
) {

    /** Kto przepisuje nagranie. */
    enum class Usługa(val nazwa: String) { GEMINI("Gemini"), OPENAI("OpenAI") }

    private val tag = "CloudSpeechToText"

    /**
     * Dlaczego ostatnia próba się nie udała - kod HTTP i początek odpowiedzi.
     * Do dziennika: 400 (zły format), 401 (klucz) i 429 (limit) wymagają
     * zupełnie różnych działań, a z zewnątrz wszystkie wyglądają jak "nie działa".
     */
    @Volatile
    var ostatniBłąd: String? = null
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * @param pcm surowe 16-bitowe PCM mono
     * @param sampleRate częstotliwość próbkowania nagrania
     * @param languageTag język wypowiedzi ("pl-PL")
     * @return rozpoznany tekst albo `null`, gdy się nie udało
     */
    suspend fun transcribe(
        pcm: ByteArray,
        sampleRate: Int,
        languageTag: String
    ): String? = withContext(Dispatchers.IO) {
        ostatniBłąd = null
        if (apiKey.isBlank() || pcm.isEmpty()) return@withContext null
        val wav = runCatching { WavWriter.wrap(pcm, sampleRate) }.getOrNull() ?: return@withContext null
        return@withContext try {
            val request = when (usługa) {
                Usługa.GEMINI -> żądanieGemini(wav, languageTag)
                Usługa.OPENAI -> żądanieOpenAi(wav, languageTag)
            }
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    ostatniBłąd = "HTTP ${response.code}: ${text.take(160)}"
                    Log.w(tag, "Transkrypcja w chmurze (${usługa.nazwa}) odrzucona: ${response.code}")
                    return@use null
                }
                val heard = when (usługa) {
                    Usługa.GEMINI -> tekstGemini(text)
                    Usługa.OPENAI -> tekstOpenAi(text)
                }
                if (heard.isNullOrBlank()) {
                    ostatniBłąd = "pusta odpowiedź"
                    null
                } else {
                    heard
                }
            }
        } catch (e: Exception) {
            ostatniBłąd = e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: "")
            Log.w(tag, "Transkrypcja w chmurze nie powiodła się", e)
            null
        }
    }

    private fun żądanieOpenAi(wav: ByteArray, languageTag: String): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", OPENAI_MODEL)
            // Język podany wprost, a nie zgadywany: przy krótkim nagraniu
            // wykrywanie języka bywa zawodne, a polskie pytanie rozpoznane
            // jako czeskie to dokładnie ten rodzaj błędu, który tu naprawiamy.
            // `gpt-transcribe` bierze TABLICĘ `languages[]` zamiast `language`
            // (obu naraz wysłać nie wolno).
            .addFormDataPart("languages[]", kodOpenAi(languageTag))
            .addFormDataPart("file", "nagranie.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        return Request.Builder()
            .url(OPENAI_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
    }

    private fun żądanieGemini(wav: ByteArray, languageTag: String): Request =
        Request.Builder()
            .url(GEMINI_URL)
            .addHeader("x-goog-api-key", apiKey)
            .post(ciałoGemini(wav, languageTag).toString().toRequestBody("application/json".toMediaType()))
            .build()

    companion object {
        private const val OPENAI_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"

        /** Następca `whisper-1` (wyłączany 26.02.2027), $0,0045/min. */
        const val OPENAI_MODEL = "gpt-transcribe"

        /** Model Google do samej transkrypcji, $0,003/min. */
        const val GEMINI_MODEL = "gemini-3.5-transcribe"

        private const val CONNECT_TIMEOUT_S = 10L

        /** Nagranie to kilka sekund - dłuższe czekanie znaczy, że coś jest nie tak. */
        private const val READ_TIMEOUT_S = 25L

        /** "pl-PL" -> "pl": OpenAI chce samego kodu języka. */
        fun kodOpenAi(languageTag: String): String =
            languageTag.substringBefore('-').lowercase().ifBlank { "pl" }

        /** "pl" -> "pl-PL": Gemini chce pełnego kodu BCP-47 z regionem. */
        fun kodGemini(languageTag: String): String {
            val t = languageTag.trim()
            if (t.contains('-')) return t
            val język = t.lowercase().ifBlank { "pl" }
            val region = REGIONY[język] ?: język.uppercase()
            return "$język-$region"
        }

        private val REGIONY = mapOf(
            "en" to "US", "uk" to "UA", "cs" to "CZ", "da" to "DK", "el" to "GR",
            "ja" to "JP", "ko" to "KR", "sv" to "SE", "zh" to "CN", "he" to "IL",
            "hi" to "IN", "vi" to "VN"
        )

        /**
         * Treść zapytania do Interactions API. Nagranie idzie w samym zapytaniu
         * (base64) - kilka sekund WAV to kilkadziesiąt kB, daleko od limitu 20 MB.
         */
        fun ciałoGemini(wav: ByteArray, languageTag: String): JsonObject = buildJsonObject {
            put("model", "models/$GEMINI_MODEL")
            putJsonArray("input") {
                add(
                    buildJsonObject {
                        put("type", "audio")
                        put("data", java.util.Base64.getEncoder().encodeToString(wav))
                        put("mime_type", "audio/wav")
                    }
                )
            }
            putJsonObject("generation_config") {
                putJsonObject("transcription_config") {
                    putJsonArray("language_codes") { add(kodGemini(languageTag)) }
                }
            }
        }

        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject.tekst(klucz: String): String? =
            (this[klucz] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()

        /** Tekst z odpowiedzi Gemini: `output_text` albo `steps[].content[].text`. */
        fun tekstGemini(odpowiedź: String): String? = runCatching {
            val o = json.parseToJsonElement(odpowiedź).jsonObject
            o.tekst("output_text")?.takeIf { it.isNotEmpty() }?.let { return@runCatching it }
            val części = o["steps"]?.jsonArray.orEmpty()
                .mapNotNull { it as? JsonObject }
                .flatMap { krok -> krok["content"]?.jsonArray.orEmpty() }
                .mapNotNull { it as? JsonObject }
                .filter { (it.tekst("type") ?: "text") == "text" }
                .mapNotNull { it.tekst("text")?.takeIf { t -> t.isNotEmpty() } }
            części.joinToString(" ").takeIf { it.isNotBlank() }
        }.getOrNull()

        fun tekstOpenAi(odpowiedź: String): String? = runCatching {
            json.parseToJsonElement(odpowiedź).jsonObject.tekst("text")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        /**
         * Którą usługą przepisywać: tą od wybranego dostawcy, a przy innych
         * dostawcach (Claude, DeepSeek) - OpenAI, jak dotąd, a bez jego klucza
         * Gemini. `null`, gdy nie ma żadnego z dwóch kluczy.
         */
        fun wybierz(aktywny: String, kluczGemini: String?, kluczOpenAi: String?): Pair<Usługa, String>? {
            val g = kluczGemini?.takeIf { it.isNotBlank() }
            val o = kluczOpenAi?.takeIf { it.isNotBlank() }
            return when {
                aktywny == "gemini" && g != null -> Usługa.GEMINI to g
                aktywny == "openai" && o != null -> Usługa.OPENAI to o
                o != null -> Usługa.OPENAI to o
                g != null -> Usługa.GEMINI to g
                else -> null
            }
        }
    }
}
