package pl.victor.app.stream

/**
 * Rozmowa po RTSP - składanie żądań, czytanie odpowiedzi, rozumienie SDP.
 *
 * ## Czemu piszemy to sami, skoro media3 ma klienta RTSP
 * Bo ten konkretny serwer odbija się od niego na starcie. Dziennik z 14
 * września, dwie próby pod rząd, identycznie:
 *
 *     ParserException: Malformed Attribute line: a=decode_buf=300
 *
 * Okulary mają serwer Hisilicon i wysyłają w SDP wiersz `a=decode_buf=300`.
 * media3 czyta atrybuty jako `a=nazwa:wartość`, z dwukropkiem - ten ma znak
 * równości, więc uznaje CAŁY opis za uszkodzony i przerywa. Nie pomija
 * nieznanego atrybutu, tylko rzuca wyjątkiem.
 *
 * Druga bariera jest w tym samym opisie i sama by wystarczyła: ścieżka wideo
 * nie ma `a=fmtp:96`, czyli nie podaje `sprop-parameter-sets`. Okulary wysyłają
 * parametry obrazu w samym strumieniu, jak większość tanich enkoderów - media3
 * tego nie przyjmuje (`checkArgument(..., "missing sprop parameter")`), a
 * podsunąć mu poprawionego opisu się nie da, bo robi własne DESCRIBE.
 *
 * Stąd własny klient. Nie jest to pełna implementacja RFC 2326 - jest to
 * dokładnie tyle, ile potrzeba, żeby odebrać jeden strumień z tych okularów.
 *
 * ## Zasada nadrzędna: NIE ODRZUCAJ TEGO, CZEGO NIE ROZUMIESZ
 * Każdy wiersz SDP, którego nie znamy, jest POMIJANY, nie traktowany jako
 * błąd. To jest cała różnica między tym kodem a media3 i jedyny powód, dla
 * którego ten kod w ogóle powstaje.
 */
object RtspProtocol {

    /** Rodzaj strumienia - interesuje nas obraz, reszta jest do pominięcia. */
    enum class Kind { VIDEO, AUDIO, OTHER }

    /**
     * Jedna ścieżka z opisu sesji.
     *
     * @param control wartość `a=control:` - adres, pod którym robi się SETUP
     * @param payloadType numer z `m=`, ten sam, który wraca w nagłówkach RTP
     * @param encoding nazwa kodeka z `a=rtpmap:`, wielkimi literami ("H264")
     * @param clockRate zegar z `a=rtpmap:`, do przeliczania znaczników czasu
     */
    data class Track(
        val control: String?,
        val payloadType: Int,
        val encoding: String?,
        val clockRate: Int?,
        val kind: Kind
    )

    /**
     * Czyta opis sesji, pomijając wszystko, czego nie rozumie.
     *
     * Na wejściu bierze CAŁĄ odpowiedź serwera albo samo SDP - nagłówki HTTP-owe
     * i tak nie zaczynają się od `m=` ani `a=`, więc nie przeszkadzają.
     */
    fun parseSdp(sdp: String): List<Track> {
        val tracks = mutableListOf<Track>()
        var kind = Kind.OTHER
        var payloadType = -1
        var control: String? = null
        var encoding: String? = null
        var clockRate: Int? = null
        var open = false

        fun flush() {
            if (open) {
                tracks += Track(control, payloadType, encoding, clockRate, kind)
            }
            open = false
            control = null
            encoding = null
            clockRate = null
        }

        for (raw in sdp.split("\r\n", "\n")) {
            val line = raw.trim()
            when {
                line.startsWith("m=") -> {
                    // Nowy `m=` zamyka poprzednią ścieżkę. Format:
                    // "m=video 0 RTP/AVP 96" - rodzaj, port, profil, typ ładunku.
                    flush()
                    val parts = line.removePrefix("m=").split(' ').filter { it.isNotBlank() }
                    kind = when (parts.getOrNull(0)?.lowercase()) {
                        "video" -> Kind.VIDEO
                        "audio" -> Kind.AUDIO
                        else -> Kind.OTHER
                    }
                    payloadType = parts.getOrNull(3)?.toIntOrNull() ?: -1
                    open = true
                }
                line.startsWith("a=control:") ->
                    if (open) control = line.removePrefix("a=control:").trim()
                line.startsWith("a=rtpmap:") -> if (open) {
                    // "a=rtpmap:96 H264/90000" - typ ładunku, kodek, zegar.
                    val body = line.removePrefix("a=rtpmap:").trim()
                    val codec = body.substringAfter(' ', "").trim()
                    encoding = codec.substringBefore('/').uppercase().takeIf { it.isNotBlank() }
                    clockRate = codec.substringAfter('/', "").substringBefore('/').toIntOrNull()
                }
                // Każdy inny wiersz - w tym `a=decode_buf=300`, o który rozbija
                // się media3 - po prostu nas nie dotyczy.
            }
        }
        flush()
        return tracks
    }

    /**
     * Adres do SETUP dla ścieżki.
     *
     * `a=control:` bywa adresem bezwzględnym ("rtsp://...") albo względnym
     * ("track0"), a bywa go też brak. Okulary podają "track0", czyli ten
     * przypadek, w którym trzeba samemu dokleić bazę.
     */
    fun resolveControl(baseUrl: String, control: String?): String {
        if (control.isNullOrBlank() || control == "*") return baseUrl
        if (control.startsWith("rtsp://", ignoreCase = true)) return control
        return baseUrl.trimEnd('/') + "/" + control.trimStart('/')
    }

    /** Składa żądanie RTSP. Kolejność nagłówków jest bez znaczenia, CSeq nie. */
    fun request(
        method: String,
        url: String,
        cseq: Int,
        headers: Map<String, String> = emptyMap()
    ): String = buildString {
        append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
        append("CSeq: ").append(cseq).append("\r\n")
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("User-Agent: ").append(USER_AGENT).append("\r\n")
        append("\r\n")
    }

    /** Odpowiedź serwera rozłożona na części. */
    data class Reply(val status: Int, val headers: Map<String, String>, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    /**
     * Czyta odpowiedź serwera.
     *
     * Nazwy nagłówków sprowadzamy do małych liter, bo RTSP ich nie rozróżnia, a
     * serwery pisują je po swojemu ("Session" kontra "session").
     */
    fun parseReply(raw: String): Reply? {
        val split = raw.indexOf("\r\n\r\n").takeIf { it >= 0 }
            ?: raw.indexOf("\n\n").takeIf { it >= 0 }
        val head = if (split != null) raw.substring(0, split) else raw
        val body = if (split != null) raw.substring(split).trimStart('\r', '\n') else ""

        val lines = head.split("\r\n", "\n").filter { it.isNotBlank() }
        val statusLine = lines.firstOrNull() ?: return null
        if (!statusLine.startsWith("RTSP/", ignoreCase = true)) return null
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return null

        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        return Reply(status, headers, body)
    }

    /**
     * Wyciąga identyfikator sesji z nagłówka `Session`.
     *
     * Nagłówek bywa postaci "12345678;timeout=60" - część po średniku to
     * parametry, nie identyfikator, a wysłanie jej z powrotem potrafi skończyć
     * się odmową.
     */
    fun sessionId(headers: Map<String, String>): String? =
        headers["session"]?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }

    /** Limit bezczynności sesji w sekundach, gdy serwer go podaje. */
    fun sessionTimeoutSeconds(headers: Map<String, String>): Int? =
        headers["session"]
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("timeout=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.toIntOrNull()

    /** Opis transportu: RTP wplecione w to samo połączenie TCP. */
    fun interleavedTransport(channel: Int): String =
        "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}"

    /** Znacznik ramki wplecionej w strumień sterujący. */
    const val INTERLEAVED_MARKER: Byte = 0x24 // '$'

    /** Nasza nazwa w nagłówku User-Agent. */
    const val USER_AGENT = "VICTOR"

    /** Domyślny port serwera RTSP w okularach. */
    const val DEFAULT_PORT = 8554
}
