package pl.victor.app.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tłumaczenie mowy na żywo w chmurze - sam protokół, bez sieci i dźwięku.
 *
 * ## Po co
 * Dotychczasowe tłumaczenie ze słuchu to trzy kroki po kolei: rozpoznanie
 * zdania w telefonie, przekład ML Kitem, synteza mowy. Każdy czeka na
 * poprzedni, więc przekład przychodzi dopiero po CAŁYM zdaniu, a ML Kit
 * tłumaczy słowo w słowo. Modele "speech-to-speech" słuchają strumienia i
 * mówią przekład w trakcie - jak tłumacz symultaniczny.
 *
 * ## Dwie usługi, jedna umowa
 * - Gemini (`gemini-3.5-live-translate-preview`, ok. $0,037/min): Live API
 *   przez WebSocket, wejście PCM 16 kHz, wyjście PCM 24 kHz. Język źródłowy
 *   wykrywa sam; mowy już w języku docelowym NIE powtarza
 *   (`echoTargetLanguage = false`) - więc nie tłumaczy Twojego polskiego ani
 *   własnego przekładu, gdy telefon go usłyszy.
 * - OpenAI (`gpt-realtime-translate`, $0,034/min): osobny adres
 *   `/v1/realtime/translations`, wejście i wyjście PCM 24 kHz.
 *
 * Obie usługi dają JEDEN kierunek na sesję (dokumentacja: "one translation
 * session per direction").
 *
 * Wszystko tutaj to czyste funkcje - da się je sprawdzić testem bez telefonu.
 */
object LiveTranslationProtocol {

    enum class Usługa(val nazwa: String, val model: String, val próbkowanieWejścia: Int) {
        GEMINI("Gemini", "gemini-3.5-live-translate-preview", 16_000),
        OPENAI("OpenAI", "gpt-realtime-translate", 24_000)
    }

    /** Obie usługi oddają przekład jako PCM 16-bit mono 24 kHz. */
    const val PRÓBKOWANIE_WYJŚCIA = 24_000

    /** Kawałek dźwięku wysyłany naraz - 100 ms, jak w przykładach obu usług. */
    const val KAWAŁEK_MS = 100

    /** Co przyszło z serwera. */
    sealed class Zdarzenie {
        /** Sesja gotowa - można słać dźwięk. */
        object Gotowe : Zdarzenie()
        /** Kawałek przekładu do odtworzenia (PCM 24 kHz). */
        class Dźwięk(val pcm: ByteArray) : Zdarzenie()
        /** Przyrost tekstu tego, co powiedziano (oryginał). */
        data class Oryginał(val tekst: String) : Zdarzenie()
        /** Przyrost tekstu przekładu. */
        data class Przekład(val tekst: String) : Zdarzenie()
        /** Koniec wypowiedzi - pora zamknąć zdanie w panelu. */
        object KoniecTury : Zdarzenie()
        /** Serwer kończy sesję. */
        object Zamknięte : Zdarzenie()
        data class Błąd(val opis: String) : Zdarzenie()
    }

    /**
     * Którą usługą tłumaczyć: tą od dostawcy wybranego w ustawieniach, a przy
     * innych (Claude, DeepSeek) tą, do której jest klucz - najpierw Gemini.
     */
    fun wybierz(aktywny: String, kluczGemini: String?, kluczOpenAi: String?): Pair<Usługa, String>? {
        val g = kluczGemini?.takeIf { it.isNotBlank() }
        val o = kluczOpenAi?.takeIf { it.isNotBlank() }
        return when {
            aktywny == "gemini" && g != null -> Usługa.GEMINI to g
            aktywny == "openai" && o != null -> Usługa.OPENAI to o
            g != null -> Usługa.GEMINI to g
            o != null -> Usługa.OPENAI to o
            else -> null
        }
    }

    /** Adres WebSocketu. Klucz Gemini idzie w adresie, OpenAI - w nagłówku. */
    fun adres(usługa: Usługa, klucz: String): String = when (usługa) {
        Usługa.GEMINI ->
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$klucz"
        Usługa.OPENAI ->
            "wss://api.openai.com/v1/realtime/translations?model=${usługa.model}"
    }

    /** Nagłówki połączenia (poza adresem). */
    fun nagłówki(usługa: Usługa, klucz: String): Map<String, String> = when (usługa) {
        Usługa.GEMINI -> emptyMap()
        Usługa.OPENAI -> mapOf("Authorization" to "Bearer $klucz")
    }

    /** "pl-PL" -> "pl": obie usługi chcą samego kodu języka. */
    fun kodJęzyka(język: String): String = język.substringBefore('-').lowercase().ifBlank { "pl" }

    /**
     * Pierwsza wiadomość: model i język docelowy.
     *
     * Gemini czyta ją jako `setup` - zanim dostanie cokolwiek innego. OpenAI
     * przyjmuje `session.update` po otwarciu połączenia.
     */
    fun ustawienia(usługa: Usługa, językDocelowy: String): String = when (usługa) {
        Usługa.GEMINI -> buildJsonObject {
            putJsonObject("setup") {
                put("model", "models/${usługa.model}")
                putJsonObject("inputAudioTranscription") {}
                putJsonObject("outputAudioTranscription") {}
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                    putJsonObject("translationConfig") {
                        put("targetLanguageCode", kodJęzyka(językDocelowy))
                        // Mowa już w języku docelowym zostaje bez echa: Twoje
                        // polskie zdania i przekład, który telefon usłyszy z
                        // głośnika, nie wracają do ucha drugi raz.
                        put("echoTargetLanguage", false)
                    }
                }
            }
        }
        Usługa.OPENAI -> buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                putJsonObject("audio") {
                    putJsonObject("output") { put("language", kodJęzyka(językDocelowy)) }
                }
            }
        }
    }.toString()

    /** Kawałek dźwięku z mikrofonu (PCM 16-bit mono w próbkowaniu usługi). */
    fun dźwięk(usługa: Usługa, pcm: ByteArray): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(pcm)
        return when (usługa) {
            Usługa.GEMINI -> buildJsonObject {
                putJsonObject("realtimeInput") {
                    putJsonObject("audio") {
                        put("data", b64)
                        put("mimeType", "audio/pcm;rate=${usługa.próbkowanieWejścia}")
                    }
                }
            }
            Usługa.OPENAI -> buildJsonObject {
                put("type", "session.input_audio_buffer.append")
                put("audio", b64)
            }
        }.toString()
    }

    /**
     * Grzeczne zakończenie - OpenAI dosyła wtedy resztę przekładu i
     * `session.closed`. Gemini nie ma takiej wiadomości.
     */
    fun zakończenie(usługa: Usługa): String? = when (usługa) {
        Usługa.GEMINI -> null
        Usługa.OPENAI -> """{"type":"session.close"}"""
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun base64(s: String?): ByteArray? =
        s?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }

    /** Rozbiera wiadomość serwera na zdarzenia (jedna wiadomość Gemini niesie kilka naraz). */
    fun rozbierz(usługa: Usługa, wiadomość: String): List<Zdarzenie> {
        val o = runCatching { json.parseToJsonElement(wiadomość).jsonObject }.getOrNull()
            ?: return emptyList()
        return when (usługa) {
            Usługa.GEMINI -> rozbierzGemini(o)
            Usługa.OPENAI -> rozbierzOpenAi(o)
        }
    }

    private fun rozbierzGemini(o: JsonObject): List<Zdarzenie> {
        val wynik = mutableListOf<Zdarzenie>()
        if (o.containsKey("setupComplete")) wynik += Zdarzenie.Gotowe
        o["error"].obj()?.let { wynik += Zdarzenie.Błąd(it["message"].str() ?: it.toString().take(160)) }
        o["goAway"].obj()?.let { wynik += Zdarzenie.Zamknięte }
        val sc = o["serverContent"].obj() ?: return wynik
        sc["inputTranscription"].obj()?.get("text").str()
            ?.takeIf { it.isNotEmpty() }?.let { wynik += Zdarzenie.Oryginał(it) }
        sc["outputTranscription"].obj()?.get("text").str()
            ?.takeIf { it.isNotEmpty() }?.let { wynik += Zdarzenie.Przekład(it) }
        sc["modelTurn"].obj()?.get("parts")?.let { parts ->
            runCatching { parts.jsonArray }.getOrNull()?.forEach { part ->
                base64(part.obj()?.get("inlineData").obj()?.get("data").str())
                    ?.takeIf { it.isNotEmpty() }?.let { wynik += Zdarzenie.Dźwięk(it) }
            }
        }
        if ((sc["turnComplete"] as? JsonPrimitive)?.booleanOrNull == true) wynik += Zdarzenie.KoniecTury
        return wynik
    }

    private fun rozbierzOpenAi(o: JsonObject): List<Zdarzenie> = when (o["type"].str()) {
        "session.created", "session.updated" -> listOf(Zdarzenie.Gotowe)
        "session.output_audio.delta" ->
            listOfNotNull(base64(o["delta"].str())?.takeIf { it.isNotEmpty() }?.let { Zdarzenie.Dźwięk(it) })
        "session.output_transcript.delta" ->
            listOfNotNull(o["delta"].str()?.takeIf { it.isNotEmpty() }?.let { Zdarzenie.Przekład(it) })
        "session.input_transcript.delta" ->
            listOfNotNull(o["delta"].str()?.takeIf { it.isNotEmpty() }?.let { Zdarzenie.Oryginał(it) })
        "session.closed" -> listOf(Zdarzenie.Zamknięte)
        "error" -> {
            val e = o["error"].obj()
            listOf(Zdarzenie.Błąd(listOfNotNull(e?.get("code").str(), e?.get("message").str())
                .joinToString(": ").ifBlank { o.toString().take(160) }))
        }
        else -> emptyList()
    }
}

/**
 * Składa przyrosty tekstu w zdania do panelu tłumaczenia.
 *
 * Gemini zamyka wypowiedź znacznikiem `turnComplete`, OpenAI - nie ma tur
 * wcale. Zdanie zamykamy więc też po chwili ciszy w tekście
 * ([CISZA_ZAMYKA_MS]). Czysta logika - bez zegara systemowego, czas podaje
 * wołający.
 */
class LiveTranscriptBuffer {
    private val oryginał = StringBuilder()
    private val przekład = StringBuilder()
    private var ostatnioMs = 0L

    val oryginałTeraz: String get() = oryginał.toString().trim()
    val przekładTeraz: String get() = przekład.toString().trim()

    fun dodajOryginał(tekst: String, terazMs: Long) {
        oryginał.append(tekst)
        ostatnioMs = terazMs
    }

    fun dodajPrzekład(tekst: String, terazMs: Long) {
        przekład.append(tekst)
        ostatnioMs = terazMs
    }

    /**
     * Czy pora zamknąć zdanie, bo tekst stoi od [CISZA_ZAMYKA_MS]. Sam
     * oryginał bez przekładu (np. Twoje polskie zdanie, którego Gemini nie
     * powtarza) czyścimy po dłuższej chwili, żeby nie doklejał się do
     * następnego przekładu.
     */
    fun czasZamknąć(terazMs: Long): Boolean {
        val cisza = terazMs - ostatnioMs
        return (przekładTeraz.isNotEmpty() && cisza >= CISZA_ZAMYKA_MS) ||
            (oryginałTeraz.isNotEmpty() && cisza >= CISZA_SAM_ORYGINAŁ_MS)
    }

    /** Zamyka zdanie: oddaje parę (oryginał, przekład) albo `null`, gdy przekładu brak. */
    fun zamknij(): Pair<String, String>? {
        val o = oryginałTeraz
        val p = przekładTeraz
        oryginał.clear()
        przekład.clear()
        return if (p.isEmpty()) null else o to p
    }

    companion object {
        const val CISZA_ZAMYKA_MS = 1_500L
        const val CISZA_SAM_ORYGINAŁ_MS = 4_000L
    }
}
