package pl.victor.app.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

/**
 * Kto mówi głosem asystenta.
 *
 * Systemowy syntezator Androida jest darmowy i działa bez sieci, ale brzmi
 * mechanicznie - zgłoszone wprost: "głos jak z automatu na dworcu". Głosy z
 * chmury (Gemini, OpenAI) brzmią jak człowiek, za to kosztują i potrzebują
 * internetu - stąd wybór w Ustawieniach, domyślnie systemowy.
 */
enum class CloudVoiceProvider(val id: String, val nazwa: String) {
    SYSTEM("system", "Systemowy"),
    GEMINI("gemini", "Gemini"),
    OPENAI("openai", "OpenAI");

    companion object {
        fun fromId(id: String?): CloudVoiceProvider =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: SYSTEM
    }
}

/** Głos do wyboru w Ustawieniach: nazwa dla API i krótki opis dla człowieka. */
data class CloudVoiceOption(val name: String, val opis: String)

/**
 * Stałe głosu z chmury - modele, głosy, adresy, limity - w JEDNYM miejscu.
 *
 * Identyfikatory modeli sprawdzone w dokumentacji (listopad 2026):
 * - Gemini: `gemini-3.8-flash-lite-tts` przez Interactions API - dokumentacja
 *   poleca go wprost do "conversational voice agent cascades"; obsługuje polski.
 *   Odpowiedź unarna domyślnie to WAV, więc prosimy jawnie o `audio/l16`
 *   (surowe PCM 24 kHz) - i tak na wszelki wypadek obcinamy nagłówek RIFF.
 * - OpenAI: `gpt-4o-mini-tts` przez `/v1/audio/speech`, `response_format=pcm`
 *   (24 kHz, 16 bit, mono). UWAGA: OpenAI zapowiedziało wyłączenie tego modelu
 *   6.01.2027 (następca jest tylko w Realtime API) - po tej dacie zapytania
 *   wrócą z błędem, a mowa sama przejdzie na syntezator systemowy (z wpisem
 *   w dzienniku).
 */
object CloudVoiceConfig {
    /** Oba modele oddają PCM 16 bit mono 24 kHz. */
    const val SAMPLE_RATE = 24_000

    const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
    const val GEMINI_MODEL = "gemini-3.8-flash-lite-tts"

    /**
     * Styl wypowiedzi dla Gemini - krótki i STAŁY, jak radzi dokumentacja
     * (długie opisy postaci w każdym zapytaniu pogarszają spójność głosu).
     */
    const val GEMINI_STYLE = "calm, natural and warm, native Polish pronunciation, conversational pace"

    val GEMINI_VOICES = listOf(
        CloudVoiceOption("Kore", "kobiecy, zdecydowany"),
        CloudVoiceOption("Sulafat", "kobiecy, ciepły"),
        CloudVoiceOption("Aoede", "kobiecy, lekki"),
        CloudVoiceOption("Charon", "męski, rzeczowy"),
        CloudVoiceOption("Achird", "męski, przyjazny"),
        CloudVoiceOption("Algieba", "męski, gładki")
    )
    const val GEMINI_DEFAULT_VOICE = "Kore"

    const val OPENAI_URL = "https://api.openai.com/v1/audio/speech"
    const val OPENAI_MODEL = "gpt-4o-mini-tts"

    /** Pole `instructions` - model mówi wtedy jak Polak, a nie z akcentem. */
    const val OPENAI_INSTRUCTIONS =
        "Mów po polsku jak rodzimy użytkownik języka: spokojnie, naturalnie i ciepło, " +
            "w umiarkowanym tempie, jak asystent w rozmowie, a nie lektor."

    val OPENAI_VOICES = listOf(
        CloudVoiceOption("marin", "kobiecy, naturalny (polecany)"),
        CloudVoiceOption("cedar", "męski, naturalny (polecany)"),
        CloudVoiceOption("coral", "kobiecy, pogodny"),
        CloudVoiceOption("sage", "kobiecy, spokojny"),
        CloudVoiceOption("ash", "męski, wyraźny"),
        CloudVoiceOption("verse", "męski, ekspresyjny")
    )
    const val OPENAI_DEFAULT_VOICE = "marin"

    /**
     * Orientacyjny koszt do opisu w Ustawieniach.
     *
     * Gemini 3.8 Flash-Lite TTS: 0,003 $ za 10 s dźwięku od 1.01.2027 (do końca
     * 2026 połowa tego), tekst wejściowy pomijalny. Zdanie odpowiedzi to ok.
     * 3 s mowy, więc 100 zdań ≈ 5 min ≈ 0,09 $ ≈ 0,35 zł. Klucz z darmowym
     * limitem Gemini nie płaci nic (do wyczerpania limitu).
     * OpenAI gpt-4o-mini-tts: ok. 0,015 $ za minutę mowy - 100 zdań ≈ 0,30 zł.
     */
    const val GEMINI_COST = "ok. 0,35 zł za 100 zdań (do końca 2026 połowa); w darmowym limicie klucza Gemini - 0 zł"
    const val OPENAI_COST = "ok. 0,30 zł za 100 zdań (ok. 0,015 \$ za minutę mowy)"

    /**
     * Ile najdłużej czekamy na PIERWSZY dźwięk (od zlecenia zdania). Dłużej
     * niż systemowy syntezator z rozruchem - użytkownik zauważa już ~2 s
     * ciszy po pytaniu, więc po 2,5 s lepiej mechaniczny głos niż żaden.
     */
    const val FIRST_SOUND_TIMEOUT_MS = 2_500L

    /**
     * Ile najdłużej czekamy na KOLEJNE zdanie, gdy poprzednie już wybrzmiało.
     * Zwykle jest gotowe wcześniej (pobierane w tle), więc to tylko bezpiecznik.
     */
    const val NEXT_SENTENCE_TIMEOUT_MS = 6_000L

    /** Ile zdań naprzód pobieramy, gdy bieżące jeszcze gra. */
    const val LOOKAHEAD = 2

    /** Pamięć podręczna: tyle plików najwyżej. */
    const val CACHE_MAX_ENTRIES = 50

    /** Pamiętamy tylko krótkie zdania ("Słucham", "Już sprawdzam") - one się powtarzają. */
    const val CACHE_MAX_CHARS = 60

    /** Zdanie dłuższe od tego dzielimy dalej - długie zdanie to długa cisza przed dźwiękiem. */
    const val MAX_SENTENCE_CHARS = 220

    /** Krótszy kawałek ("Tak.") doklejamy do następnego - osobne zapytanie nie ma sensu. */
    const val MIN_SENTENCE_CHARS = 5

    const val CONNECT_TIMEOUT_S = 5L
    const val READ_TIMEOUT_S = 15L
    const val CALL_TIMEOUT_S = 25L
}

/** Dlaczego mowa przeszła z chmury na systemowy syntezator. */
enum class CloudVoiceFallback(val opis: String) {
    TIMEOUT("za długo na dźwięk"),
    AUTH("klucz odrzucony (401/403)"),
    LIMIT("limit zapytań (429)"),
    NOT_FOUND("model niedostępny (404)"),
    HTTP("błąd usługi"),
    NETWORK("brak sieci"),
    EMPTY("pusta odpowiedź"),
    PLAYBACK("odtwarzanie nie wyszło")
}

/**
 * Czysta logika głosu z chmury - bez Androida, testowana na JVM.
 */
object CloudVoiceLogic {

    /**
     * Efektywny wybór.
     *
     * @param provider kto NAPRAWDĘ mówi
     * @param chosen co wybrał użytkownik
     * @param reason dlaczego mówi ktoś inny niż wybrany (`null`, gdy wybór obowiązuje)
     */
    data class Decision(
        val provider: CloudVoiceProvider,
        val chosen: CloudVoiceProvider,
        val reason: String?
    ) {
        val isCloud: Boolean get() = provider != CloudVoiceProvider.SYSTEM
    }

    /**
     * Kto mówi: wybór użytkownika, ale "Tylko darmowe funkcje" zawsze wygrywa,
     * a bez klucza danej usługi zostaje syntezator systemowy. Klucz jest
     * sprawdzany NIEZALEŻNIE od aktywnego dostawcy modelu - można rozmawiać z
     * Claude'em i mówić głosem Gemini.
     */
    fun decide(
        choiceId: String?,
        freeOnly: Boolean,
        geminiKey: String?,
        openAiKey: String?
    ): Decision {
        val chosen = CloudVoiceProvider.fromId(choiceId)
        val system = CloudVoiceProvider.SYSTEM
        return when {
            chosen == system -> Decision(system, chosen, null)
            freeOnly -> Decision(system, chosen, "tylko darmowe funkcje")
            chosen == CloudVoiceProvider.GEMINI && geminiKey.isNullOrBlank() ->
                Decision(system, chosen, "brak klucza Gemini")
            chosen == CloudVoiceProvider.OPENAI && openAiKey.isNullOrBlank() ->
                Decision(system, chosen, "brak klucza OpenAI")
            else -> Decision(chosen, chosen, null)
        }
    }

    /** Głos zapisany w ustawieniach albo domyślny, gdy zapisany nie należy do tej usługi. */
    fun voiceFor(provider: CloudVoiceProvider, saved: String?): String = when (provider) {
        CloudVoiceProvider.GEMINI ->
            CloudVoiceConfig.GEMINI_VOICES.firstOrNull { it.name == saved }?.name
                ?: CloudVoiceConfig.GEMINI_DEFAULT_VOICE
        CloudVoiceProvider.OPENAI ->
            CloudVoiceConfig.OPENAI_VOICES.firstOrNull { it.name == saved }?.name
                ?: CloudVoiceConfig.OPENAI_DEFAULT_VOICE
        CloudVoiceProvider.SYSTEM -> ""
    }

    fun modelFor(provider: CloudVoiceProvider): String = when (provider) {
        CloudVoiceProvider.GEMINI -> CloudVoiceConfig.GEMINI_MODEL
        CloudVoiceProvider.OPENAI -> CloudVoiceConfig.OPENAI_MODEL
        CloudVoiceProvider.SYSTEM -> ""
    }

    /**
     * Czy ten język mówimy chmurą.
     *
     * Tylko polski. Inne języki to przekłady (tłumacz, rozmowa w dwie strony) -
     * tam liczy się opóźnienie i przewidywalność, a systemowy silnik ma już
     * dobrane głosy obcojęzyczne. Instrukcje stylu też są pisane pod polski.
     */
    fun isCloudLanguage(language: String): Boolean {
        val code = language.trim().replace('_', '-').substringBefore('-').lowercase()
        return code == "pl" || code == "pol"
    }

    // === Dzielenie na zdania ===

    /**
     * Skróty, po których kropka NIE kończy zdania. Lepiej nie podzielić tam,
     * gdzie zdanie naprawdę się kończy (dłuższy kawałek - nic złego), niż
     * podzielić "ul. | Marszałkowska" (dwa zapytania i dziwna pauza w środku).
     */
    private val ABBREVIATIONS = setOf(
        "ul", "al", "pl", "os", "np", "itd", "itp", "tzn", "tj", "m.in", "dr", "prof",
        "inż", "mgr", "hab", "godz", "tel", "nr", "ok", "ds", "wg", "zob", "por", "r",
        "tys", "mln", "mld", "zł", "gr", "min", "sek", "pkt", "str", "ust", "art", "im",
        "płk", "gen", "ks", "św", "pn", "wt", "śr", "czw", "pt", "sob", "niedz", "jw",
        "ww", "tzw", "dot", "red", "woj", "pow", "gm", "proc", "wyd", "e.g", "i.e",
        "mr", "mrs", "ms", "st", "vs", "etc"
    )

    private val CLOSERS = setOf('"', '\'', '”', '»', ')', ']', '’')
    private val OPENERS = setOf('"', '„', '«', '(', '\'', '“', '-', '–')

    /**
     * Dzieli tekst na zdania do osobnej syntezy.
     *
     * Po co: pierwszy dźwięk słychać po syntezie PIERWSZEGO zdania, nie całej
     * odpowiedzi - a kolejne pobieramy, gdy pierwsze już gra.
     *
     * Kropka kończy zdanie tylko przed wielką literą albo cyfrą (po spacji), i
     * nie po skrócie ("ul.", "np.", "m.in.") ani inicjale ("J. Kowalski");
     * "3.5" i "12.30" nie mają spacji, więc w ogóle nie są końcem. Wykrzyknik,
     * pytajnik i wielokropek kończą zawsze, nowa linia też.
     */
    fun splitSentences(text: String): List<String> {
        val raw = mutableListOf<String>()
        text.split('\n').forEach { line ->
            val l = line.replace(Regex("\\s+"), " ").trim()
            if (l.isNotEmpty()) raw += splitLine(l)
        }
        return limitLength(mergeShort(raw))
    }

    private fun splitLine(l: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < l.length) {
            val c = l[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var j = i + 1
                while (j < l.length && (l[j] == '.' || l[j] == '!' || l[j] == '?' || l[j] == '…' || l[j] in CLOSERS)) j++
                if (j >= l.length) break
                if (l[j] == ' ') {
                    val k = j + 1
                    if (k < l.length && isBoundary(l, i, j, k)) {
                        out += l.substring(start, j).trim()
                        start = k
                    }
                }
                i = j
                continue
            }
            i++
        }
        val tail = l.substring(start).trim()
        if (tail.isNotEmpty()) out += tail
        return out.filter { it.isNotBlank() }
    }

    /**
     * @param i pozycja znaku końca (pierwszego z serii)
     * @param j pierwsza pozycja po serii znaków końca i zamknięć
     * @param k pierwszy znak następnego zdania
     */
    private fun isBoundary(l: String, i: Int, j: Int, k: Int): Boolean {
        val run = l.substring(i, j)
        val next = l[k]
        // "!", "?", "…" i "..." kończą zawsze.
        if (run.any { it == '!' || it == '?' || it == '…' } || run.startsWith("...")) return true
        if (!(next.isUpperCase() || next.isDigit() || next in OPENERS)) return false
        // Słowo przed kropką - razem z kropkami w środku ("m.in").
        var b = i
        while (b > 0 && l[b - 1] != ' ') b--
        val word = l.substring(b, i).trimStart(*OPENERS.toCharArray()).lowercase()
        if (word.isEmpty()) return true
        if (word in ABBREVIATIONS) return false
        // Inicjał: "J. Kowalski".
        if (word.length == 1 && word[0].isLetter()) return false
        return true
    }

    private fun mergeShort(parts: List<String>): List<String> {
        val out = mutableListOf<String>()
        var carry = ""
        for (p in parts) {
            val joined = if (carry.isEmpty()) p else "$carry $p"
            if (joined.length < CloudVoiceConfig.MIN_SENTENCE_CHARS) {
                carry = joined
            } else {
                out += joined
                carry = ""
            }
        }
        if (carry.isNotEmpty()) {
            if (out.isEmpty()) out += carry else out[out.lastIndex] = out.last() + " " + carry
        }
        return out
    }

    /** Za długie zdanie tniemy po przecinku/średniku/myślniku, a w ostateczności po spacji. */
    private fun limitLength(parts: List<String>): List<String> {
        val max = CloudVoiceConfig.MAX_SENTENCE_CHARS
        val out = mutableListOf<String>()
        for (p in parts) {
            var rest = p
            while (rest.length > max) {
                val window = rest.substring(0, max)
                val cut = listOf(", ", "; ", " – ", " - ", ": ")
                    .map { sep -> window.lastIndexOf(sep).let { if (it >= 40) it + sep.length - 1 else -1 } }
                    .maxOrNull()?.takeIf { it > 0 }
                    ?: window.lastIndexOf(' ').takeIf { it > 0 }
                    ?: max
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
        }
        return out
    }

    // === Pamięć podręczna ===

    /** Czy zdanie warto pamiętać - tylko krótkie, bo tylko one się powtarzają. */
    fun isCacheable(text: String): Boolean =
        normalizeForCache(text).length in 1..CloudVoiceConfig.CACHE_MAX_CHARS

    fun normalizeForCache(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    /**
     * Klucz pliku w pamięci podręcznej. Obejmuje WSZYSTKO, co zmienia dźwięk -
     * usługę, model, głos i styl - żeby po zmianie głosu nie grać starych nagrań.
     * Wielkość liter zostaje: "Tak." i "tak." intonuje się różnie.
     */
    fun cacheKey(provider: CloudVoiceProvider, model: String, voice: String, style: String, text: String): String {
        val src = listOf(provider.id, model, voice, style, normalizeForCache(text)).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(src.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Które pozycje usunąć, żeby zostało najwyżej [max] - najdawniej użyte.
     *
     * @param entries nazwa -> czas ostatniego użycia
     */
    fun evictionVictims(entries: List<Pair<String, Long>>, max: Int): List<String> {
        if (entries.size <= max) return emptyList()
        return entries.sortedBy { it.second }.take(entries.size - max).map { it.first }
    }

    // === Zapytania ===

    /** Treść zapytania do Interactions API Gemini (TTS). */
    fun geminiRequest(
        text: String,
        voice: String,
        model: String = CloudVoiceConfig.GEMINI_MODEL,
        style: String = CloudVoiceConfig.GEMINI_STYLE
    ): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("input") {
            addJsonObject {
                put("type", "user_input")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                        if (style.isNotBlank()) {
                            putJsonArray("annotations") {
                                addJsonObject {
                                    put("type", "speech_metadata")
                                    put("style", style)
                                }
                            }
                        }
                    }
                }
            }
        }
        putJsonObject("response_format") {
            put("type", "audio")
            put("mime_type", "audio/l16")
            put("sample_rate", CloudVoiceConfig.SAMPLE_RATE)
        }
        putJsonObject("generation_config") {
            putJsonArray("speech_config") {
                addJsonObject { put("voice", voice) }
            }
        }
    }

    /** Treść zapytania do `/v1/audio/speech` OpenAI. */
    fun openAiRequest(
        text: String,
        voice: String,
        model: String = CloudVoiceConfig.OPENAI_MODEL,
        instructions: String = CloudVoiceConfig.OPENAI_INSTRUCTIONS
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("input", text)
        put("voice", voice)
        if (instructions.isNotBlank()) put("instructions", instructions)
        put("response_format", "pcm")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Wyciąga dźwięk z odpowiedzi Gemini: `steps[].content[]` typu `audio`,
     * ostatni blok (tak robi przykład z dokumentacji). Na wypadek starszego
     * kształtu odpowiedzi sprawdza też `outputs[]`.
     *
     * @return surowe PCM 16 bit (nagłówek WAV obcięty) albo `null`
     */
    fun parseGeminiAudio(body: String): ByteArray? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val blocks = mutableListOf<JsonObject>()
        (root["steps"] as? JsonArray)?.forEach { step ->
            val s = step as? JsonObject ?: return@forEach
            val type = (s["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            if (type != null && type != "model_output") return@forEach
            (s["content"] as? JsonArray)?.forEach { c -> (c as? JsonObject)?.let { blocks += it } }
        }
        (root["outputs"] as? JsonArray)?.forEach { c -> (c as? JsonObject)?.let { blocks += it } }
        val audio = blocks.lastOrNull {
            (it["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "audio" && it["data"] != null
        } ?: return null
        val data = audio["data"]!!.jsonPrimitive.content
        val bytes = java.util.Base64.getDecoder().decode(data)
        evenLength(stripWavHeader(bytes)).takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Komunikat błędu z odpowiedzi JSON ("error.message") - do dziennika. */
    fun errorMessage(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val err = root["error"] as? JsonObject ?: return null
        (err["message"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /** Jeśli to plik WAV - zwraca same próbki z kawałka `data`; inaczej bez zmian. */
    fun stripWavHeader(bytes: ByteArray): ByteArray {
        if (bytes.size < 12 || !bytes.ascii(0, "RIFF") || !bytes.ascii(8, "WAVE")) return bytes
        var off = 12
        while (off + 8 <= bytes.size) {
            val size = bytes.le32(off + 4)
            if (bytes.ascii(off, "data")) {
                val start = off + 8
                val end = if (size < 0 || start + size > bytes.size) bytes.size else start + size
                return bytes.copyOfRange(start, end)
            }
            if (size < 0) break
            off += 8 + size + (size and 1)
        }
        // RIFF bez kawałka "data" - typowy nagłówek ma 44 bajty.
        return if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else ByteArray(0)
    }

    /** PCM 16 bit musi mieć parzystą długość - nieparzysty bajt przesunąłby wszystkie próbki. */
    fun evenLength(pcm: ByteArray): ByteArray =
        if (pcm.size % 2 == 0) pcm else pcm.copyOf(pcm.size - 1)

    private fun ByteArray.ascii(at: Int, s: String): Boolean =
        at + s.length <= size && s.indices.all { this[at + it] == s[it].code.toByte() }

    private fun ByteArray.le32(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)

    // === Błędy i limity czasu ===

    fun classifyHttp(code: Int): CloudVoiceFallback = when (code) {
        401, 403 -> CloudVoiceFallback.AUTH
        404 -> CloudVoiceFallback.NOT_FOUND
        429 -> CloudVoiceFallback.LIMIT
        else -> CloudVoiceFallback.HTTP
    }

    /**
     * Na ile wyłączyć chmurę po przejściu na systemowy. Bez tego przy braku
     * sieci KAŻDE zdanie strumienia czekałoby 2,5 s na pewną porażkę. Zły klucz
     * czy wyłączony model nie naprawią się same - te dłużej.
     */
    fun cooldownMs(reason: CloudVoiceFallback): Long = when (reason) {
        CloudVoiceFallback.TIMEOUT -> 30_000L
        CloudVoiceFallback.NETWORK, CloudVoiceFallback.HTTP,
        CloudVoiceFallback.EMPTY, CloudVoiceFallback.PLAYBACK -> 60_000L
        CloudVoiceFallback.LIMIT -> 5 * 60_000L
        CloudVoiceFallback.AUTH, CloudVoiceFallback.NOT_FOUND -> 10 * 60_000L
    }

    /**
     * Ile jeszcze czekać na dźwięk zdania.
     *
     * Pierwsze zdanie serii (nic jeszcze nie gra): limit liczony od ZLECENIA,
     * nie od chwili, w której odtwarzacz po nie sięgnął - to jest czas, który
     * słyszy użytkownik. Kolejne: osobny, hojniejszy limit od końca poprzedniego.
     */
    fun waitLimitMs(firstOfBurst: Boolean, msSinceEnqueue: Long): Long =
        if (firstOfBurst) {
            (CloudVoiceConfig.FIRST_SOUND_TIMEOUT_MS - msSinceEnqueue).coerceAtLeast(MIN_WAIT_MS)
        } else {
            CloudVoiceConfig.NEXT_SENTENCE_TIMEOUT_MS
        }

    /** Nawet po przekroczeniu - chwila na gotowe już dane, zanim się poddamy. */
    private const val MIN_WAIT_MS = 50L
}
