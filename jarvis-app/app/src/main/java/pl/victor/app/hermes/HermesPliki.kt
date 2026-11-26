package pl.victor.app.hermes

/** Rodzaj pliku od Hermesa - decyduje o ikonie i o tym, czy pokazać podgląd. */
enum class RodzajPliku(val etykieta: String) {
    OBRAZ("Obraz"),
    DOKUMENT("Dokument"),
    DANE("Dane"),
    AUDIO("Dźwięk"),
    WIDEO("Wideo"),
    ARCHIWUM("Archiwum")
}

/**
 * Plik, o którym Hermes wspomniał w odpowiedzi.
 *
 * @param odnośnik adres `http(s)://…` ([zdalny]) albo ścieżka na KOMPUTERZE
 *   z Hermesem (`/Users/…`, `~/…`) - tej Victor sam nie otworzy, prosi o nią
 *   most plików ([HermesPlikiKlient])
 * @param mime typ pliku; `*` w rodzaju typu oznacza "jeszcze nie wiadomo"
 *   (adres obrazka bez rozszerzenia) - dopowie go serwer przy pobraniu
 */
data class HermesPlik(
    val odnośnik: String,
    val nazwa: String,
    val rodzaj: RodzajPliku,
    val mime: String,
    val zdalny: Boolean
)

/**
 * Wyławianie plików i obrazów z odpowiedzi Hermesa - czysta logika.
 *
 * ## Skąd wiemy, że Hermes coś wygenerował
 * Dokumentacja API Hermesa nie opisuje przekazywania plików klientom: nie ma
 * ani zdarzenia, ani punktu pobierania. Bramy komunikatorów (Slack, Telegram…)
 * wyławiają pliki z TEKSTU odpowiedzi - po ścieżkach i znaczniku `MEDIA:`. My
 * robimy to samo, a dodatkowo prosimy Hermesa ([WSKAZÓWKA]), żeby pliki wymieniał
 * w osobnych liniach `PLIK: …` - taki znacznik zadziała nawet przy spacjach w
 * ścieżce i jest jednoznaczny.
 *
 * ❓ Niesprawdzone na żywo: czy Hermes w ogóle wpisuje ścieżki do tekstu
 * odpowiedzi przez API. Jeśli nie, panel pokaże tylko to, co jest w tekście.
 */
object HermesPliki {

    /** Dopisek do polecenia głosowego dla Hermesa - patrz `HermesProtocol.WSKAZÓWKA_GŁOSOWA`. */
    const val WSKAZÓWKA =
        "Każdy plik albo obraz, który utworzysz lub znajdziesz dla użytkownika, wypisz w osobnej linii " +
            "w postaci `PLIK: /pełna/ścieżka/do/pliku` (albo `PLIK: https://adres`) - ta linia nie zostanie " +
            "przeczytana na głos, tylko pokazana w aplikacji. Pliki zapisuj w folderze roboczym."

    private val TYPY: Map<String, Pair<RodzajPliku, String>> = mapOf(
        "png" to (RodzajPliku.OBRAZ to "image/png"),
        "jpg" to (RodzajPliku.OBRAZ to "image/jpeg"),
        "jpeg" to (RodzajPliku.OBRAZ to "image/jpeg"),
        "gif" to (RodzajPliku.OBRAZ to "image/gif"),
        "webp" to (RodzajPliku.OBRAZ to "image/webp"),
        "bmp" to (RodzajPliku.OBRAZ to "image/bmp"),
        "svg" to (RodzajPliku.OBRAZ to "image/svg+xml"),
        "tiff" to (RodzajPliku.OBRAZ to "image/tiff"),
        "pdf" to (RodzajPliku.DOKUMENT to "application/pdf"),
        "docx" to (RodzajPliku.DOKUMENT to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        "doc" to (RodzajPliku.DOKUMENT to "application/msword"),
        "odt" to (RodzajPliku.DOKUMENT to "application/vnd.oasis.opendocument.text"),
        "rtf" to (RodzajPliku.DOKUMENT to "application/rtf"),
        "txt" to (RodzajPliku.DOKUMENT to "text/plain"),
        "md" to (RodzajPliku.DOKUMENT to "text/markdown"),
        "html" to (RodzajPliku.DOKUMENT to "text/html"),
        "epub" to (RodzajPliku.DOKUMENT to "application/epub+zip"),
        "pptx" to (RodzajPliku.DOKUMENT to "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        "xlsx" to (RodzajPliku.DANE to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        "xls" to (RodzajPliku.DANE to "application/vnd.ms-excel"),
        "ods" to (RodzajPliku.DANE to "application/vnd.oasis.opendocument.spreadsheet"),
        "csv" to (RodzajPliku.DANE to "text/csv"),
        "tsv" to (RodzajPliku.DANE to "text/tab-separated-values"),
        "json" to (RodzajPliku.DANE to "application/json"),
        "xml" to (RodzajPliku.DANE to "application/xml"),
        "yaml" to (RodzajPliku.DANE to "text/plain"),
        "yml" to (RodzajPliku.DANE to "text/plain"),
        "mp3" to (RodzajPliku.AUDIO to "audio/mpeg"),
        "wav" to (RodzajPliku.AUDIO to "audio/wav"),
        "m4a" to (RodzajPliku.AUDIO to "audio/mp4"),
        "ogg" to (RodzajPliku.AUDIO to "audio/ogg"),
        "mp4" to (RodzajPliku.WIDEO to "video/mp4"),
        "mov" to (RodzajPliku.WIDEO to "video/quicktime"),
        "webm" to (RodzajPliku.WIDEO to "video/webm"),
        "zip" to (RodzajPliku.ARCHIWUM to "application/zip")
    )

    /** Rozszerzenia, które uznajemy za plik do otwarcia (kod źródłowy i logi - nie). */
    val ROZSZERZENIA: Set<String> get() = TYPY.keys

    /** Najwyżej tyle plików z jednej odpowiedzi - agent potrafi wypisać setki ścieżek. */
    const val LIMIT_PLIKÓW = 12

    private val EXT = TYPY.keys.joinToString("|")

    /** `PLIK: …` / `MEDIA: …` / `FILE: …` w osobnej linii. */
    private val ZNACZNIK = Regex("""(?im)^[ \t]*(?:PLIK|MEDIA|FILE)[ \t]*:[ \t]*(\S.*?)[ \t]*$""")

    /** Ścieżka bezwzględna albo od `~/` ze znanym rozszerzeniem, bez spacji. */
    private val ŚCIEŻKA = Regex(
        """(?<![\w/.~:-])(~?/(?:[^\s"'`<>()\[\]|*?:]+/)*[^\s"'`<>()\[\]|*?:/]+\.(?:$EXT))(?![\w])""",
        RegexOption.IGNORE_CASE
    )

    private val ADRES = Regex("""https?://[^\s"'`<>()\[\]]+""")

    /** `![opis](https://…)` - obrazek w markdownie, nawet bez rozszerzenia (adresy z podpisem). */
    private val OBRAZEK_MD = Regex("""!\[[^\]]*]\((https?://[^)\s]+)\)""")

    private class Trafienie(val pozycja: Int, val plik: HermesPlik)

    /** Wszystkie pliki z odpowiedzi - w kolejności wystąpienia, bez powtórzeń. */
    fun znajdź(tekst: String): List<HermesPlik> {
        if (tekst.isBlank()) return emptyList()
        val trafienia = mutableListOf<Trafienie>()

        ZNACZNIK.findAll(tekst).forEach { m ->
            val wartość = oczyść(m.groupValues[1])
            zOdnośnika(wartość, wymagajRozszerzenia = false)?.let { trafienia += Trafienie(m.range.first, it) }
        }
        OBRAZEK_MD.findAll(tekst).forEach { m ->
            zOdnośnika(oczyść(m.groupValues[1]), wymagajRozszerzenia = false, domyślnyObraz = true)
                ?.let { trafienia += Trafienie(m.range.first, it) }
        }
        ADRES.findAll(tekst).forEach { m ->
            zOdnośnika(oczyść(m.value), wymagajRozszerzenia = true)?.let { trafienia += Trafienie(m.range.first, it) }
        }
        ŚCIEŻKA.findAll(tekst).forEach { m ->
            zOdnośnika(oczyść(m.groupValues[1]), wymagajRozszerzenia = true)?.let { trafienia += Trafienie(m.range.first, it) }
        }

        return trafienia.sortedBy { it.pozycja }
            .map { it.plik }
            .distinctBy { it.odnośnik }
            .take(LIMIT_PLIKÓW)
    }

    /** Odpowiedź bez linii ze znacznikami plików - to, co pokazujemy w dymku. */
    fun bezZnaczników(tekst: String): String =
        tekst.replace(ZNACZNIK, "").replace(Regex("\n{3,}"), "\n\n").trim()

    /** Ścieżka niebezpieczna dla mostu (wyjście poza folder) - odrzucamy już u nas. */
    fun podejrzana(ścieżka: String): Boolean =
        ścieżka.split('/', '\\').any { it == ".." } || ścieżka.contains('\u0000')

    private fun oczyść(s: String): String =
        s.trim().trim('`', '"', '\'', '<', '>').trimEnd('.', ',', ';', ')', ']')

    private fun zOdnośnika(
        wartość: String,
        wymagajRozszerzenia: Boolean,
        domyślnyObraz: Boolean = false
    ): HermesPlik? {
        if (wartość.isEmpty()) return null
        val zdalny = wartość.startsWith("http://", true) || wartość.startsWith("https://", true)
        if (!zdalny && !(wartość.startsWith("/") || wartość.startsWith("~/"))) return null
        if (!zdalny && podejrzana(wartość)) return null
        val bezZapytania = wartość.substringBefore('#').substringBefore('?')
        val nazwa = bezZapytania.substringAfterLast('/').let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }
        val ext = nazwa.substringAfterLast('.', "").lowercase()
        val typ = TYPY[ext]
        return when {
            typ != null -> HermesPlik(wartość, nazwa.ifBlank { "plik.$ext" }, typ.first, typ.second, zdalny)
            wymagajRozszerzenia -> null
            // Znacznik `PLIK:` albo obrazek markdown bez znanego rozszerzenia.
            domyślnyObraz -> HermesPlik(wartość, nazwa.ifBlank { "obraz" }, RodzajPliku.OBRAZ, "image/*", zdalny)
            else -> HermesPlik(wartość, nazwa.ifBlank { "plik" }, RodzajPliku.DOKUMENT, "application/octet-stream", zdalny)
        }
    }

    fun typPoNazwie(nazwa: String): String? =
        TYPY[nazwa.substringAfterLast('.', "").lowercase()]?.second
}

/**
 * Wycina z mowy linie `PLIK: …` / `MEDIA: …` - ścieżki mają trafić do panelu,
 * a nie być czytane na głos.
 *
 * Strumień przychodzi kawałkami, więc początek linii jest wstrzymywany do
 * chwili, gdy wiadomo, czy to znacznik (kilka znaków). Reszta tekstu przechodzi
 * bez zmian i bez opóźnienia.
 */
class FiltrMowy {
    private val znaczniki = listOf("PLIK:", "MEDIA:", "FILE:")
    private val bufor = StringBuilder()
    private var początekLinii = true
    private var pomijanie = false

    /** @return to, co wolno już wypowiedzieć z [kawałek] (i z wstrzymanego początku linii) */
    fun przepuść(kawałek: String): String {
        val wyjście = StringBuilder()
        for (c in kawałek) {
            if (pomijanie) {
                if (c == '\n') { pomijanie = false; początekLinii = true }
                continue
            }
            if (!początekLinii) {
                wyjście.append(c)
                if (c == '\n') początekLinii = true
                continue
            }
            // Początek linii: zbieramy znaki, dopóki da się rozstrzygnąć.
            if (c == '\n') {
                wyjście.append(bufor).append(c)
                bufor.clear()
                continue
            }
            bufor.append(c)
            val głowa = bufor.toString().trimStart(' ', '\t').uppercase()
            when {
                głowa.isEmpty() -> Unit // same odstępy - czekamy dalej
                znaczniki.any { głowa.startsWith(it) } -> {
                    bufor.clear(); pomijanie = true; początekLinii = false
                }
                znaczniki.any { it.startsWith(głowa) } -> Unit // może być znacznikiem
                else -> {
                    wyjście.append(bufor); bufor.clear(); początekLinii = false
                }
            }
        }
        return wyjście.toString()
    }

    /** Koniec odpowiedzi: wypuszcza wstrzymany początek, który nie okazał się znacznikiem. */
    fun koniec(): String {
        val reszta = bufor.toString()
        bufor.clear()
        początekLinii = true
        pomijanie = false
        return reszta
    }
}
