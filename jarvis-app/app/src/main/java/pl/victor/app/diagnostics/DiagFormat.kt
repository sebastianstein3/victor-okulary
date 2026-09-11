package pl.victor.app.diagnostics

/**
 * Formatowanie wierszy dziennika diagnostycznego - czyste funkcje, bez Androida.
 *
 * ## Po co osobny plik
 * Bo to jedyna część dziennika, którą da się sprawdzić testem, a jednocześnie
 * jedyna, która może zrobić realną szkodę: wiersz trafia na GitHuba. Gdyby
 * wyciekł tam klucz API albo token, byłby publiczny w sekundę i trzeba by go
 * unieważnić. Dlatego zaciemnianie jest tutaj, ma testy i działa na KAŻDYM
 * wierszu, a nie w miejscu, w którym ktoś pamiętał, żeby je wywołać.
 */
object DiagFormat {

    /** Etapy tury - jedno słowo, żeby dało się filtrować grep-em. */
    enum class Phase {
        SESJA, WAKE, PRZYCISK, NASŁUCH, TRANSKRYPCJA, ZDJĘCIE, KONTEKST,
        MODEL, MOWA, AKCJA, BLE, AUDIO, BŁĄD
    }

    /**
     * Składa jeden wiersz dziennika.
     *
     * Kolejność kolumn jest stała, bo wiersze czyta się wzrokiem w pionie:
     * zegar bezwzględny, czas od początku tury, etap, treść, pola.
     *
     * @param sinceTurnMs ile ms od startu bieżącej tury; `null` gdy tura nie trwa
     */
    fun line(
        wallClock: String,
        sinceTurnMs: Long?,
        phase: Phase,
        message: String,
        fields: Map<String, Any?> = emptyMap()
    ): String {
        val elapsed = if (sinceTurnMs == null) "        " else "%+7d".format(sinceTurnMs)
        val rendered = fields.entries
            .filter { it.value != null }
            .joinToString(" ") { (k, v) -> "$k=${redact(v.toString())}" }
        val head = "$wallClock $elapsed ms  ${phase.name.padEnd(12)} ${redact(message)}"
        return if (rendered.isBlank()) head else "$head  $rendered"
    }

    /**
     * Wycina z tekstu wszystko, co wygląda na sekret.
     *
     * Celowo agresywne. Fałszywe trafienie kosztuje jeden nieczytelny wiersz
     * dziennika; przeoczenie kosztuje unieważnienie klucza i wpis w historii
     * repozytorium, którego nie da się cofnąć zwykłym commitem.
     */
    fun redact(text: String): String {
        var out = text
        for (pattern in SECRET_PATTERNS) {
            out = pattern.replace(out) { match ->
                val hit = match.value
                // Zostawiamy początek, żeby dało się poznać, O KTÓRY klucz chodzi
                // ("ten od OpenAI czy ten od Gemini") bez ujawniania go.
                val keep = hit.take(SECRET_PREFIX_KEPT)
                "$keep…[ukryte ${hit.length} znaków]"
            }
        }
        return out
    }

    private const val SECRET_PREFIX_KEPT = 6

    /**
     * Wzorce znanych formatów kluczy. Lista jest z natury niepełna, dlatego na
     * końcu stoi wzorzec ogólny: każdy dostatecznie długi ciąg bez spacji,
     * mieszający litery i cyfry, jest traktowany jak sekret.
     */
    private val SECRET_PATTERNS = listOf(
        // OpenAI / DeepSeek / wiele zgodnych: sk-..., sk-proj-...
        Regex("""\bsk-[A-Za-z0-9_\-]{16,}"""),
        // Google / Gemini
        Regex("""\bAIza[A-Za-z0-9_\-]{20,}"""),
        Regex("""\bAQ\.[A-Za-z0-9_\-.]{20,}"""),
        // Anthropic
        Regex("""\bsk-ant-[A-Za-z0-9_\-]{16,}"""),
        // GitHub (klasyczny i fine-grained)
        Regex("""\bgh[pousr]_[A-Za-z0-9]{16,}"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
        // Nagłówek Authorization w dowolnej postaci
        Regex("""(?<=Bearer )[A-Za-z0-9_\-.]{16,}"""),
        // Sekrety zapisane w base64 - taki kształt ma klucz Picovoice, jedyny
        // w aplikacji bez rozpoznawalnego prefiksu. Siatka niżej ich NIE łapie,
        // bo jej klasa znaków nie zna "+", "/" ani "=": klucz rozpada się na
        // fragmenty krótsze niż próg 32 i przechodzi jawnie do publicznego repo.
        // Wymóg trzech rodzajów znaków (mała, WIELKA, cyfra) trzyma z dala
        // ścieżki URL-i - te są zwykle samymi małymi literami i mają zostać
        // czytelne, bo po nich poznaje się, które wywołanie zawiodło.
        Regex(
            """(?<![A-Za-z0-9+/=_\-])""" +
                """(?=[A-Za-z0-9+/=_\-]*[a-z])""" +
                """(?=[A-Za-z0-9+/=_\-]*[A-Z])""" +
                """(?=[A-Za-z0-9+/=_\-]*[0-9])""" +
                """[A-Za-z0-9+/=_\-]{32,}"""
        ),
        // Siatka bezpieczeństwa: długi ciąg mieszający litery i cyfry.
        Regex("""\b(?=[A-Za-z0-9_\-]{32,}\b)(?=[^\s]*[A-Za-z])(?=[^\s]*[0-9])[A-Za-z0-9_\-]{32,}\b""")
    )
}
