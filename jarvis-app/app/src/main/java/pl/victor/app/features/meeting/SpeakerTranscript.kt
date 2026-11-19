package pl.victor.app.features.meeting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pl.victor.app.audio.WavWriter
import pl.victor.app.conversation.CloudSpeechToText
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Transkrypcja spotkania z podziałem na mówców - czysta logika, bez Androida.
 *
 * ## Skąd mówcy
 * Wypowiedzi przepisywane na bieżąco ([MeetingRecorder]) nie wiedzą, kto mówi:
 * każda to kilka sekund bez kontekstu. Rozpoznanie mówców wymaga dłuższego
 * nagrania, więc wypowiedzi zbieramy do pliku i co [MAKS_FRAGMENT_MS] mowy
 * wysyłamy cały fragment do `gemini-3.5-transcribe` z diaryzacją. Odpowiedź
 * niesie słowa z etykietą mówcy (`spk_1`, `spk_2`...) i czasem w nagraniu.
 *
 * ## Czemu 7 minut, a nie 10
 * Limit zapytania z nagraniem w środku to 20 MB. 10 min PCM 16 kHz to 19,2 MB,
 * ale w JSON-ie nagranie idzie jako base64 - o 1/3 więcej, czyli 25,6 MB i
 * odmowa. 7 min to 13,4 MB surowo i 17,9 MB po zakodowaniu - z zapasem.
 *
 * ## Numeracja osób jest w obrębie fragmentu
 * Usługa nadaje etykiety od nowa w każdym zapytaniu: "spk_1" z drugiego
 * fragmentu nie musi być tą samą osobą co "spk_1" z pierwszego. Nie zgadujemy
 * (bez cech głosu nie ma na czym) - transkrypcja i polecenie dla modelu mówią
 * to wprost.
 */
object SpeakerTranscript {

    const val HZ = 16_000
    const val BAJTÓW_NA_SEKUNDĘ = HZ * 2

    /** Najdłuższy fragment - patrz opis klasy (limit 20 MB po base64). */
    const val MAKS_FRAGMENT_MS = 7 * 60_000L
    const val MAKS_FRAGMENT_BAJTÓW = MAKS_FRAGMENT_MS * BAJTÓW_NA_SEKUNDĘ / 1000

    /**
     * Cisza wstawiana między wypowiedzi w pliku fragmentu. Bez niej koniec
     * jednej osoby zlewa się z początkiem drugiej i rozpoznanie mówców myli się
     * na granicy; z nią usługa ma naturalną pauzę jak w rozmowie.
     */
    const val PRZERWA_MS = 400
    const val PRZERWA_BAJTÓW = PRZERWA_MS * BAJTÓW_NA_SEKUNDĘ / 1000

    /** Gdzie w pliku fragmentu leży jedna wypowiedź i kiedy padła na spotkaniu. */
    data class Ułożenie(val odStartuMs: Long, val wFragmencieMs: Long, val długośćMs: Long)

    /** Jedno słowo z odpowiedzi usługi. */
    data class Słowo(val tekst: String, val mówca: String?, val startS: Double?)

    /** Odpowiedź usługi: cały tekst i słowa z mówcami (puste, gdy usługa ich nie dała). */
    data class Odpowiedź(val tekst: String?, val słowa: List<Słowo>) {
        val mówców: Int get() = słowa.mapNotNull { it.mówca }.distinct().size
        val maMówców: Boolean get() = mówców > 0
    }

    /** Wynik jednego fragmentu: zakres czasu na spotkaniu i linie z mówcami (`null` = nie wyszło). */
    data class WynikFragmentu(val nr: Int, val odMs: Long, val doMs: Long, val linie: List<MeetingLine>?)

    // ---- Zapytanie ----

    /**
     * Treść zapytania do Interactions API z diaryzacją i czasem słów.
     *
     * Pola z dokumentacji (aistudio.google.com/docs/speech-to-text):
     * `transcription_config.mode` z `type: verbatim`, `diarization_mode:
     * speaker` i `timestamp_granularities: [word]`. Tryb "smart" nie łączy się
     * z diaryzacją, stąd wprost `verbatim`. Czas słów jest nam potrzebny, żeby
     * przypisać wypowiedź do miejsca na spotkaniu (fragment to sklejone
     * wypowiedzi, a nie ciągłe nagranie).
     */
    fun ciało(daneBase64: String, languageTag: String): JsonObject = buildJsonObject {
        put("model", "models/${CloudSpeechToText.GEMINI_MODEL}")
        putJsonArray("input") {
            add(
                buildJsonObject {
                    put("type", "audio")
                    put("data", daneBase64)
                    put("mime_type", "audio/wav")
                }
            )
        }
        putJsonObject("generation_config") {
            putJsonObject("transcription_config") {
                putJsonArray("language_codes") { add(CloudSpeechToText.kodGemini(languageTag)) }
                putJsonObject("mode") {
                    put("type", "verbatim")
                    put("diarization_mode", "speaker")
                    putJsonArray("timestamp_granularities") { add("word") }
                }
            }
        }
    }

    private const val ZNACZNIK = "@@NAGRANIE@@"

    /**
     * Zapytanie rozcięte w miejscu nagrania: początek i koniec JSON-a. Nagranie
     * idzie między nimi strumieniem z pliku - 18 MB base64 jako String w
     * pamięci to ok. 36 MB (znaki po 2 bajty), a w kopii zapytania drugie tyle.
     */
    fun częściZapytania(languageTag: String): Pair<String, String> {
        val całe = ciało(ZNACZNIK, languageTag).toString()
        val i = całe.indexOf(ZNACZNIK)
        return całe.substring(0, i) to całe.substring(i + ZNACZNIK.length)
    }

    /** Długość base64 dla [bajtów] bajtów (z dopełnieniem `=`). */
    fun długośćBase64(bajtów: Long): Long = (bajtów + 2) / 3 * 4

    /** Ile bajtów ma całe zapytanie dla [pcmBajtów] bajtów nagrania - nagłówek Content-Length. */
    fun długośćZapytania(languageTag: String, pcmBajtów: Long): Long {
        val (p, k) = częściZapytania(languageTag)
        return p.toByteArray(Charsets.UTF_8).size +
            długośćBase64(WavWriter.HEADER_SIZE + pcmBajtów) +
            k.toByteArray(Charsets.UTF_8).size
    }

    /**
     * Pisze całe zapytanie do [out]: początek JSON-a, WAV (nagłówek + [pcm]) w
     * base64 i koniec. [out] NIE jest zamykany - należy do wołającego (OkHttp).
     */
    fun zapiszZapytanie(out: OutputStream, languageTag: String, pcmBajtów: Long, pcm: InputStream) {
        val (p, k) = częściZapytania(languageTag)
        out.write(p.toByteArray(Charsets.UTF_8))
        // Koder base64 przy zamknięciu dopisuje dopełnienie i zamyka strumień
        // pod spodem - a ten nie jest nasz. Osłona zamyka tylko koder.
        val koder = java.util.Base64.getEncoder().wrap(BezZamykania(out))
        koder.write(WavWriter.header(pcmBajtów.toInt(), HZ))
        pcm.copyTo(koder, 64 * 1024)
        koder.close()
        out.write(k.toByteArray(Charsets.UTF_8))
    }

    private class BezZamykania(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = out.flush()
    }

    // ---- Odpowiedź ----

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.tekst(klucz: String): String? =
        (this[klucz] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement?.tablica(): List<JsonObject> =
        (this as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /**
     * Czas z odpowiedzi w sekundach: "0.200s" (format Google dla Duration),
     * sama liczba albo obiekt `{seconds, nanos}`.
     */
    fun sekundy(e: JsonElement?): Double? = when (e) {
        is JsonPrimitive -> if (e.isString) {
            e.contentOrNull?.trim()?.removeSuffix("s")?.toDoubleOrNull()
        } else {
            e.doubleOrNull
        }
        is JsonObject -> {
            val s = sekundy(e["seconds"]) ?: 0.0
            val n = sekundy(e["nanos"]) ?: 0.0
            if (e["seconds"] == null && e["nanos"] == null) null else s + n / 1e9
        }
        else -> null
    }

    /**
     * Słowa z `steps[].content[].annotations[]` typu `word_info`. Odpowiedź bez
     * adnotacji (np. pole diaryzacji zignorowane) daje sam tekst i pustą listę
     * słów - wołający traktuje to jako "bez mówców".
     *
     * @return `null`, gdy to w ogóle nie jest JSON odpowiedzi
     */
    fun parsuj(odpowiedź: String): Odpowiedź? = runCatching {
        val o = json.parseToJsonElement(odpowiedź).jsonObject
        val treści = o["steps"].tablica().flatMap { it["content"].tablica() }
            .filter { (it.tekst("type") ?: "text") == "text" }
        val słowa = treści.flatMap { it["annotations"].tablica() }
            .filter { (it.tekst("type") ?: "word_info") == "word_info" }
            .mapNotNull { a ->
                val t = a.tekst("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Słowo(t, a.tekst("speaker")?.trim()?.takeIf { it.isNotEmpty() }, sekundy(a["start_offset"]))
            }
        val tekst = o.tekst("output_text")?.trim()?.takeIf { it.isNotEmpty() }
            ?: treści.mapNotNull { it.tekst("text")?.trim()?.takeIf { t -> t.isNotEmpty() } }
                .joinToString(" ").takeIf { it.isNotBlank() }
        Odpowiedź(tekst, słowa)
    }.getOrNull()

    // ---- Składanie transkrypcji ----

    /** "spk_1", "spk_3", "spk_1" -> 1, 2, 1: numer w kolejności pierwszego odezwania się. */
    fun numeracja(słowa: List<Słowo>): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        słowa.forEach { s -> s.mówca?.let { if (it !in m) m[it] = m.size + 1 } }
        return m
    }

    fun etykieta(osoba: Int): String = "Osoba $osoba"

    /** Która wypowiedź z [ułożenie] obejmuje chwilę [ms] fragmentu (w przerwie - poprzednia). */
    fun indeksWypowiedzi(ms: Long, ułożenie: List<Ułożenie>): Int {
        if (ułożenie.isEmpty()) return -1
        val i = ułożenie.indexOfLast { it.wFragmencieMs <= ms }
        return if (i < 0) 0 else i
    }

    /** Chwila fragmentu -> czas od początku spotkania. */
    fun naCzasSpotkania(ms: Long, ułożenie: List<Ułożenie>): Long {
        val i = indeksWypowiedzi(ms, ułożenie)
        if (i < 0) return 0
        val u = ułożenie[i]
        return u.odStartuMs + (ms - u.wFragmencieMs).coerceIn(0, u.długośćMs)
    }

    private val INTERPUNKCJA = Regex("""^[.,!?;:…)\]»”"%]+.*""")

    /**
     * Słowa -> linie transkrypcji: nowa linia, gdy zmienia się mówca albo
     * wypowiedź (pauza w nagraniu). Bez cięcia po wypowiedziach jedna osoba
     * mówiąca trzy minuty dawała jedną ścianę tekstu z jednym znacznikiem czasu.
     *
     * Słowo bez mówcy należy do bieżącej linii, bez czasu - do bieżącej
     * wypowiedzi.
     */
    fun linie(słowa: List<Słowo>, ułożenie: List<Ułożenie>, fragment: Int? = null): List<MeetingLine> {
        val numery = numeracja(słowa)
        val wynik = mutableListOf<MeetingLine>()
        var osoba: Int? = null
        var wypowiedź = -1
        var czas = ułożenie.firstOrNull()?.odStartuMs ?: 0L
        val bieżące = StringBuilder()
        fun zamknij() {
            if (bieżące.isNotBlank()) {
                val t = bieżące.toString().trim().replaceFirstChar { it.uppercase() }
                wynik += MeetingLine(czas, t, osoba?.let { etykieta(it) }, fragment)
            }
            bieżące.setLength(0)
        }
        for (s in słowa) {
            val nowaOsoba = s.mówca?.let { numery[it] } ?: osoba
            val ms = s.startS?.let { (it * 1000).toLong() }
            val nowaWypowiedź = ms?.let { indeksWypowiedzi(it, ułożenie) } ?: wypowiedź
            val zmiana = bieżące.isEmpty() || nowaOsoba != osoba || nowaWypowiedź != wypowiedź
            if (zmiana) {
                zamknij()
                osoba = nowaOsoba
                wypowiedź = nowaWypowiedź
                if (ms != null) czas = naCzasSpotkania(ms, ułożenie)
                else if (wypowiedź >= 0 && wypowiedź < ułożenie.size) czas = ułożenie[wypowiedź].odStartuMs
            }
            if (bieżące.isNotEmpty() && !INTERPUNKCJA.matches(s.tekst)) bieżące.append(' ')
            bieżące.append(s.tekst)
        }
        zamknij()
        return wynik
    }

    /**
     * Linie z mówcami tam, gdzie fragment się udał, a z przepisywania na
     * bieżąco tam, gdzie nie. Wypowiedź na żywo ma ten sam czas początku co w
     * [Ułożenie], więc zakres fragmentu dokładnie wskazuje, co zastąpić.
     */
    fun scal(żywe: List<MeetingLine>, wyniki: List<WynikFragmentu>): List<MeetingLine> {
        val udane = wyniki.filter { it.linie != null }
        if (udane.isEmpty()) return żywe
        val zostają = żywe.filter { l -> udane.none { l.odStartuMs in it.odMs..it.doMs } }
        return (zostają + udane.flatMap { it.linie.orEmpty() }).sortedBy { it.odStartuMs }
    }

    /** Zakres fragmentu na spotkaniu: od początku pierwszej do końca ostatniej wypowiedzi. */
    fun zakres(ułożenie: List<Ułożenie>): Pair<Long, Long> =
        (ułożenie.firstOrNull()?.odStartuMs ?: 0L) to
            (ułożenie.lastOrNull()?.let { it.odStartuMs + it.długośćMs } ?: 0L)

    /**
     * Czemu spotkanie idzie BEZ mówców - do dziennika; `null`, gdy z mówcami.
     * Kolejność jak w ustawieniach: główny przełącznik, przełącznik funkcji, klucz.
     */
    fun powódBezMówców(tylkoDarmowe: Boolean, włączone: Boolean, kluczGemini: String?): String? = when {
        tylkoDarmowe -> "tylko darmowe funkcje"
        !włączone -> "wyłączone w ustawieniach"
        kluczGemini.isNullOrBlank() -> "brak klucza Gemini"
        else -> null
    }
}
