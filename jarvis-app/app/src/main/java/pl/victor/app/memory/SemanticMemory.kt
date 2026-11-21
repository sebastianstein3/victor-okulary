package pl.victor.app.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sqrt

/**
 * Pamięć po znaczeniu - czysta logika (bez Androida i bez sieci).
 *
 * ## Po co
 * Dotychczasowe wyszukiwanie ([LongTermMemory], TF-IDF) dopasowuje SŁOWA.
 * "Gdzie zostawiłem klucze?" nie znajdzie notatki "położyłem klucze na
 * szafce" (zostawiłem ≠ położyłem), a "co mówił Tomek o urlopie" - notatki
 * ze słowem "wakacje". Embedding zamienia tekst na wektor, w którym bliskie
 * ZNACZENIA leżą blisko siebie, więc takie pary się odnajdują.
 *
 * ## Jak to jest złożone
 * - wektory liczy usługa (Gemini albo OpenAI) - przy ZAPISIE wpisu, w tle,
 *   a nie przy pytaniu; przy pytaniu liczy się tylko jeden krótki wektor;
 * - indeks leży w telefonie (plik, patrz [SemanticIndexCodec]), wyszukiwanie
 *   to zwykły iloczyn skalarny po kilkuset wpisach - bez bibliotek;
 * - wynik to połączenie starego dopasowania słów i nowego po znaczeniu
 *   ([fuse], reciprocal rank fusion), więc nic, co działało dotąd, nie
 *   przestaje działać.
 *
 * Stałe usług (modele, adresy, wymiary, ceny) są w JEDNYM miejscu - w
 * [Provider] i niżej.
 */
object SemanticMemory {

    /**
     * Usługa embeddingów.
     *
     * Sprawdzone w dokumentacji (listopad 2026):
     * - Gemini: `gemini-embedding-2` - polecany przez Google, wejście do
     *   8192 tokenów, wymiar 128-3072 (`outputDimensionality`), skrócone wektory
     *   normalizuje sam. NIE przyjmuje pola `taskType` - rodzaj tekstu idzie
     *   przedrostkiem w treści (patrz [documentText] / [queryText]).
     *   Cena: 0,20 $ za 1 mln tokenów (darmowy poziom: bez opłat).
     * - OpenAI: `text-embedding-3-small`, wymiar przez `dimensions`.
     *   Cena: 0,02 $ za 1 mln tokenów.
     *
     * 768 wymiarów to jedna z wartości polecanych przez Google; przy kilkuset
     * wpisach to ok. 3 KB na wpis i ułamek milisekundy na przeszukanie.
     *
     * [minSimilarity] jest RÓŻNY dla usług, bo ich podobieństwa leżą w innych
     * przedziałach: u OpenAI teksty niepowiązane mają ok. 0,1-0,3, u Gemini
     * zwykle ok. 0,4-0,55. Wartości są punktem wyjścia - w dzienniku przy
     * każdym wyszukiwaniu jest `najlepsze=`, z którego da się je dostroić.
     */
    enum class Provider(
        val id: String,
        val model: String,
        val dims: Int,
        val usdPerMillionTokens: Double,
        val minSimilarity: Float
    ) {
        GEMINI("gemini", "gemini-embedding-2", 768, 0.20, 0.60f),
        OPENAI("openai", "text-embedding-3-small", 768, 0.02, 0.35f);

        /**
         * Znacznik modelu zapisywany w indeksie. Wektory z różnych modeli (albo
         * o różnym wymiarze) są nieporównywalne - zmiana znacznika = przeliczenie.
         */
        val modelTag: String get() = "$id/$model/$dims"
    }

    const val GEMINI_URL_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:batchEmbedContents"
    const val OPENAI_URL = "https://api.openai.com/v1/embeddings"

    /** Adres zapytania dla danej usługi. */
    fun url(provider: Provider): String = when (provider) {
        Provider.GEMINI -> GEMINI_URL_TEMPLATE.format(provider.model)
        Provider.OPENAI -> OPENAI_URL
    }

    /**
     * Ile najwyżej czekamy na wektor pytania. Po tym czasie tura idzie samym
     * dopasowaniem słów - pamięć po znaczeniu nie może opóźniać odpowiedzi.
     */
    const val QUERY_TIMEOUT_MS = 800L

    /** Ile wpisów idzie w jednym zapytaniu przy indeksowaniu. */
    const val BATCH_SIZE = 50

    /**
     * Ile wpisów dziennie wolno najwyżej przeliczyć. Chroni przed niespodzianką
     * przy pierwszym indeksowaniu albo zmianie modelu - reszta dojdzie jutro.
     */
    const val DAILY_LIMIT = 1_000

    /** Dłuższy tekst przycinamy: sedno wpisu i tak jest na początku. */
    const val MAX_DOC_CHARS = 2_000

    /** Ile wpisów trafia do kontekstu modelu. */
    const val TOP_K = 4

    /** Ilu kandydatów z każdej listy bierze udział w łączeniu. */
    const val CANDIDATES_PER_LIST = 10

    /** Stała k w reciprocal rank fusion - standardowe 60. */
    const val RRF_K = 60

    // === Decyzje ===

    /**
     * Którą usługą liczyć wektory: aktywny dostawca, jeśli ma klucz; inaczej
     * ten, którego klucz jest (Gemini przed OpenAI).
     */
    fun chooseProvider(activeProvider: String, hasGeminiKey: Boolean, hasOpenAiKey: Boolean): Provider? =
        when {
            activeProvider == Provider.GEMINI.id && hasGeminiKey -> Provider.GEMINI
            activeProvider == Provider.OPENAI.id && hasOpenAiKey -> Provider.OPENAI
            hasGeminiKey -> Provider.GEMINI
            hasOpenAiKey -> Provider.OPENAI
            else -> null
        }

    /**
     * Czy pamięć po znaczeniu działa.
     *
     * @param pref zapisany wybór użytkownika albo `null`, gdy nigdy nie
     *   przełączał - wtedy domyślnie WŁĄCZONA, ale tylko przy kluczu
     * @param freeOnly główny przełącznik "Tylko darmowe funkcje" - wygrywa zawsze
     * @param hasKey czy jest klucz Gemini albo OpenAI
     */
    fun isEnabled(pref: Boolean?, freeOnly: Boolean, hasKey: Boolean): Boolean =
        (pref ?: hasKey) && hasKey && !freeOnly

    // === Wektory ===

    /** Wektor o długości 1 albo `null`, gdy zerowy lub z NaN (do wyrzucenia). */
    fun normalize(v: FloatArray): FloatArray? {
        if (v.isEmpty()) return null
        var sum = 0.0
        for (x in v) {
            if (x.isNaN() || x.isInfinite()) return null
            sum += x.toDouble() * x
        }
        if (sum <= 0.0) return null
        val inv = (1.0 / sqrt(sum)).toFloat()
        return FloatArray(v.size) { v[it] * inv }
    }

    /** Iloczyn skalarny - dla wektorów znormalizowanych to cosinus. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Cosinus dla dowolnych wektorów (w testach i na wszelki wypadek). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val na = normalize(a) ?: return 0f
        val nb = normalize(b) ?: return 0f
        return dot(na, nb)
    }

    /** Trafienie wyszukiwania po znaczeniu. */
    data class Hit(val key: String, val similarity: Float)

    /**
     * Najbliższe wpisy powyżej progu, najlepsze pierwsze.
     *
     * @param query znormalizowany wektor pytania
     * @param vectors znormalizowane wektory wpisów
     */
    fun nearest(
        query: FloatArray,
        vectors: Map<String, FloatArray>,
        minSimilarity: Float,
        limit: Int = CANDIDATES_PER_LIST
    ): List<Hit> = vectors.entries
        .asSequence()
        .filter { it.value.size == query.size }
        .map { Hit(it.key, dot(query, it.value)) }
        .filter { it.similarity >= minSimilarity }
        .sortedByDescending { it.similarity }
        .take(limit)
        .toList()

    /** Najwyższe podobieństwo w ogóle (bez progu) - do dziennika i strojenia progu. */
    fun bestSimilarity(query: FloatArray, vectors: Map<String, FloatArray>): Float? =
        vectors.values.filter { it.size == query.size }.maxOfOrNull { dot(query, it) }

    /**
     * Reciprocal rank fusion: każdy wpis dostaje sumę 1/(k + miejsce) z list,
     * na których się znalazł.
     *
     * Czemu nie średnia wyników: TF-IDF i cosinus mają zupełnie inne skale, a
     * miejsca na liście są porównywalne zawsze. Wpis wysoko na OBU listach
     * wygrywa z wpisem pierwszym tylko na jednej - i o to chodzi.
     *
     * @return klucze od najlepszego, najwyżej [limit]
     */
    fun fuse(rankings: List<List<String>>, limit: Int = TOP_K, k: Int = RRF_K): List<String> {
        val scores = LinkedHashMap<String, Double>()
        rankings.forEach { list ->
            list.distinct().forEachIndexed { index, key ->
                scores[key] = (scores[key] ?: 0.0) + 1.0 / (k + index + 1)
            }
        }
        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    // === Wpisy ===

    /** Rodzaj wpisu - pierwsza litera klucza. */
    enum class Kind(val prefix: String, val label: String) {
        ROZMOWA("r", "rozmowa"),
        NOTATKA("n", "notatka"),
        FAKT("f", "fakt");

        companion object {
            fun ofKey(key: String): Kind? = entries.firstOrNull { key.startsWith(it.prefix + ":") }
        }
    }

    /**
     * Jeden wpis pamięci.
     *
     * Klucz notatki i faktu to skrót TREŚCI, nie numer na liście: po edycji
     * powstaje nowy klucz (stary wektor znika przy porządkowaniu), a dopisanie
     * notatki na początek listy nie przesuwa kluczy pozostałych.
     */
    data class Doc(val key: String, val kind: Kind, val text: String, val createdAtMs: Long)

    fun conversationDoc(id: Long, question: String, answer: String, timestampMs: Long): Doc =
        Doc("${Kind.ROZMOWA.prefix}:$id", Kind.ROZMOWA, "Pytanie: $question\nOdpowiedź: $answer", timestampMs)

    fun noteDoc(text: String, createdAtMs: Long): Doc =
        Doc("${Kind.NOTATKA.prefix}:${contentHash(text)}", Kind.NOTATKA, text, createdAtMs)

    fun factDoc(text: String, createdAtMs: Long): Doc =
        Doc("${Kind.FAKT.prefix}:${contentHash(text)}", Kind.FAKT, text, createdAtMs)

    /** Krótki skrót treści (SHA-1, 16 znaków) - dość, by nie było kolizji. */
    fun contentHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Wpisy bez wektora, najnowsze pierwsze - świeże wspomnienie jest ważniejsze
     * niż dociąganie starych, gdy dzienny limit się kończy.
     */
    fun missing(docs: List<Doc>, indexed: Set<String>): List<Doc> =
        docs.filter { it.key !in indexed }
            .distinctBy { it.key }
            .sortedByDescending { it.createdAtMs }

    /** Klucze w indeksie, których wpisów już nie ma (usunięte, zmienione). */
    fun stale(indexed: Set<String>, docs: List<Doc>): Set<String> =
        indexed - docs.map { it.key }.toSet()

    /** Ile wpisów wolno dziś jeszcze przeliczyć. */
    fun allowance(budgetDay: Long, usedThatDay: Int, today: Long, limit: Int = DAILY_LIMIT): Int =
        if (budgetDay != today) limit else (limit - usedThatDay).coerceAtLeast(0)

    /**
     * Odstęp przed kolejną próbą po nieudanym indeksowaniu (brak sieci, 429):
     * 1, 2, 4... minuty, najwyżej pół godziny.
     */
    fun retryDelayMs(failures: Int): Long {
        if (failures <= 0) return 0L
        val shift = (failures - 1).coerceAtMost(5)
        return (60_000L shl shift).coerceAtMost(30 * 60_000L)
    }

    // === Teksty do usługi ===

    /**
     * Tekst wpisu w postaci, jakiej chce model.
     *
     * `gemini-embedding-2` nie przyjmuje `taskType` - dokumentacja każe
     * oznaczać dokument przedrostkiem "title: ... | text: ...", a pytanie
     * przedrostkiem "task: search result | query: ...". Bez tego wektory
     * pytań i dokumentów leżą w różnych miejscach przestrzeni i trafienia
     * słabną. OpenAI nie ma takiego rozróżnienia.
     */
    fun documentText(provider: Provider, text: String): String {
        val body = text.trim().take(MAX_DOC_CHARS)
        return when (provider) {
            Provider.GEMINI -> "title: none | text: $body"
            Provider.OPENAI -> body
        }
    }

    fun queryText(provider: Provider, text: String): String {
        val body = text.trim().take(MAX_DOC_CHARS)
        return when (provider) {
            Provider.GEMINI -> "task: search result | query: $body"
            Provider.OPENAI -> body
        }
    }

    // === JSON zapytań i odpowiedzi ===

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Zapytanie do usługi dla gotowych tekstów (już po [documentText] /
     * [queryText]).
     */
    fun requestBody(provider: Provider, texts: List<String>): String = when (provider) {
        Provider.GEMINI -> buildJsonObject {
            putJsonArray("requests") {
                texts.forEach { text ->
                    addJsonObject {
                        put("model", "models/${provider.model}")
                        putJsonObject("content") {
                            putJsonArray("parts") {
                                addJsonObject { put("text", text) }
                            }
                        }
                        put("outputDimensionality", provider.dims)
                    }
                }
            }
        }
        Provider.OPENAI -> buildJsonObject {
            put("model", provider.model)
            putJsonArray("input") { texts.forEach { add(it) } }
            put("dimensions", provider.dims)
            put("encoding_format", "float")
        }
    }.toString()

    /**
     * Wektory z odpowiedzi, znormalizowane, w kolejności zapytania.
     *
     * @return `null`, gdy odpowiedź jest nieczytelna, liczba wektorów nie
     *   zgadza się z [expected] albo któryś wektor ma zły wymiar - wtedy całą
     *   partię uznajemy za nieudaną, bo przypisanie wektorów do wpisów byłoby
     *   zgadywaniem
     */
    fun parseResponse(provider: Provider, body: String, expected: Int): List<FloatArray>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val raw: List<FloatArray?> = when (provider) {
            Provider.GEMINI -> {
                // batchEmbedContents: {"embeddings":[{"values":[...]}]};
                // embedContent (pojedynczy): {"embedding":{"values":[...]}}.
                val many = root["embeddings"] as? JsonArray
                val one = root["embedding"] as? JsonObject
                when {
                    many != null -> many.map { (it as? JsonObject)?.let { e -> floats(e["values"]) } }
                    one != null -> listOf(floats(one["values"]))
                    else -> return null
                }
            }
            Provider.OPENAI -> {
                // {"data":[{"index":0,"embedding":[...]}]} - kolejność wg "index".
                val data = root["data"] as? JsonArray ?: return null
                data.mapNotNull { it as? JsonObject }
                    .sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
                    .map { floats(it["embedding"]) }
            }
        }
        if (raw.size != expected) return null
        return raw.map { v ->
            if (v == null || v.size != provider.dims) return null
            normalize(v) ?: return null
        }
    }

    private fun floats(element: kotlinx.serialization.json.JsonElement?): FloatArray? {
        val array = element as? JsonArray ?: return null
        val out = FloatArray(array.size)
        for (i in 0 until array.size) {
            out[i] = runCatching { array[i].jsonPrimitive.floatOrNull }.getOrNull() ?: return null
        }
        return out
    }

    /** Krótki opis błędu z odpowiedzi usługi - do dziennika, bez treści wpisów. */
    fun errorSummary(code: Int, body: String): String {
        val message = runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject
            ((root?.get("error") as? JsonObject)?.get("message"))?.jsonPrimitive?.content
        }.getOrNull()
        return "HTTP $code" + (message?.let { ": " + it.take(160) } ?: "")
    }

    // === Kontekst dla modelu ===

    /**
     * Wybrane wpisy jako sekcja polecenia.
     *
     * Z datą i rodzajem: na "gdzie zostawiłem klucze?" liczy się NAJŚWIEŻSZA
     * notatka, a model bez daty nie wie, która nią jest.
     */
    fun buildPromptContext(docs: List<Doc>, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (docs.isEmpty()) return null
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
        return buildString {
            append("Wcześniejsze rozmowy i zapiski użytkownika, które mogą dotyczyć pytania ")
            append("(wybrane po znaczeniu, nie tylko po słowach):\n")
            docs.forEach { doc ->
                append("- [").append(doc.kind.label)
                if (doc.createdAtMs > 0L) {
                    append(", ").append(format.format(Instant.ofEpochMilli(doc.createdAtMs).atZone(zone)))
                }
                append("] ")
                append(doc.text.take(PROMPT_DOC_CHARS).replace("\n", " / "))
                append('\n')
            }
            append("Wykorzystaj to, jeśli pomaga. Gdy wpisy sobie przeczą, ważniejszy jest ")
            append("nowszy. Nie powtarzaj ich bez potrzeby i nie wymyślaj niczego ponad nie.")
        }
    }

    /** Ile znaków jednego wpisu trafia do polecenia. */
    private const val PROMPT_DOC_CHARS = 500

    // === Koszt ===

    /** Kurs orientacyjny - do napisu w Ustawieniach, nie do rozliczeń. */
    const val USD_TO_PLN = 4.0

    /** Średnio tokenów na wpis rozmowy (pytanie + odpowiedź po polsku). */
    private const val DOC_TOKENS = 300

    /** Średnio tokenów na pytanie. */
    private const val QUERY_TOKENS = 30

    /**
     * Orientacyjny koszt miesięczny w złotych: każda tura to jedno pytanie
     * (wektor pytania) i jeden nowy wpis rozmowy (wektor wpisu). Notatki i
     * fakty to przy tym margines.
     */
    fun monthlyCostPln(provider: Provider, turnsPerDay: Int = 30): Double {
        val tokens = turnsPerDay.toDouble() * (DOC_TOKENS + QUERY_TOKENS) * 30
        return tokens / 1_000_000.0 * provider.usdPerMillionTokens * USD_TO_PLN
    }

    /** "0,32" - z przecinkiem, dwa miejsca, najmniej "0,01". */
    fun formatPln(value: Double): String =
        String.format(Locale.ROOT, "%.2f", value.coerceAtLeast(0.01)).replace('.', ',')
}

/**
 * Zapis indeksu wektorów do pliku - binarnie, bo w JSON-ie (base64 albo
 * liczby tekstem) ten sam indeks byłby 1,5-4 razy większy i wolniej się
 * czytał przy pierwszym pytaniu.
 *
 * Układ: znacznik "VSM1", znacznik modelu, wymiar, dzień i zużycie dziennego
 * limitu, liczba wpisów, a dla każdego klucz i [dims] liczb float32.
 * Wektor o innym wymiarze niż nagłówek oznacza uszkodzony plik.
 */
object SemanticIndexCodec {

    data class Index(
        val modelTag: String,
        val dims: Int,
        val vectors: Map<String, FloatArray>,
        /** Dzień (epoch day), którego dotyczy [usedThatDay]. */
        val budgetDay: Long = 0L,
        val usedThatDay: Int = 0
    )

    private const val MAGIC = 0x56534D31 // "VSM1"

    fun encode(index: Index): ByteArray {
        val bytes = ByteArrayOutputStream(64 + index.vectors.size * (index.dims * 4 + 24))
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(index.modelTag)
            out.writeInt(index.dims)
            out.writeLong(index.budgetDay)
            out.writeInt(index.usedThatDay)
            val valid = index.vectors.filter { it.value.size == index.dims }
            out.writeInt(valid.size)
            valid.forEach { (key, vector) ->
                out.writeUTF(key)
                vector.forEach { out.writeFloat(it) }
            }
        }
        return bytes.toByteArray()
    }

    /** @return indeks albo `null`, gdy plik jest uszkodzony lub w innym formacie */
    fun decode(data: ByteArray): Index? = runCatching {
        DataInputStream(ByteArrayInputStream(data)).use { input ->
            if (input.readInt() != MAGIC) return null
            val tag = input.readUTF()
            val dims = input.readInt()
            if (dims <= 0 || dims > 10_000) return null
            val day = input.readLong()
            val used = input.readInt()
            val count = input.readInt()
            if (count < 0) return null
            val vectors = LinkedHashMap<String, FloatArray>(count * 2)
            repeat(count) {
                val key = input.readUTF()
                vectors[key] = FloatArray(dims) { input.readFloat() }
            }
            Index(tag, dims, vectors, day, used)
        }
    }.getOrNull()
}
