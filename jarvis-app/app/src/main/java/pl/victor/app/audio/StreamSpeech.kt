package pl.victor.app.audio

/**
 * Decyzje o tym, co z bufora strumienia wolno już wypowiedzieć.
 *
 * ## Po co osobny plik
 * Bo [AudioManager] ciągnie za sobą TTS, Bluetooth, ustawienia i pół Androida -
 * nie da się go skompilować do testu jednostkowego. Ta logika jest czysta i
 * jednocześnie łatwa do popsucia, więc mieszka osobno i ma testy. Tak samo
 * zrobiono z [pl.victor.app.ble.GlassesProtocol] i
 * [pl.victor.app.diagnostics.DiagFormat].
 */
object StreamSpeech {

    /**
     * Dokąd z bufora wolno mówić - reszta może być znacznikiem akcji.
     *
     * Wstrzymujemy PODWÓJNY nawias, bo tak zaczyna się `[[ACTION: ...]]`, oraz
     * pojedynczy nawias na samym KOŃCU bufora, bo drugi może dopiero nadejść
     * następnym fragmentem strumienia.
     *
     * Wcześniej stało tu `indexOf('[')`, więc mowę zatrzymywał KAŻDY nawias
     * kwadratowy: link markdown `[tekst](url)`, przypis `[1]` z wyszukiwania w
     * sieci, nawias w zwykłym zdaniu. Przy włączonym wyszukiwaniu odpowiedź
     * milczała przez cały czas generowania i szła jednym ciągiem dopiero przy
     * domknięciu strumienia - czyli czytanie zdanie po zdaniu, o które prosił
     * użytkownik, w praktyce nie działało.
     */
    fun speakableEnd(buffer: String): Int {
        // Znacznik z POJEDYNCZYM nawiasem też jest znacznikiem: rozpoznawanie
        // akcji przyjmuje `[ACTION: ...]`, a tu wstrzymywaliśmy tylko `[[`.
        // "Wysyłam. [ACTION: type=send_sms body="Będę za 10 min."]" szło więc
        // na głos aż do kropki WEWNĄTRZ znacznika.
        val double = buffer.indexOf("[[")
        val single = SINGLE_MARKER.find(buffer)?.range?.first ?: -1
        val marker = listOf(double, single).filter { it >= 0 }.minOrNull()
        if (marker != null) return marker
        // Początek znacznika może jeszcze nie dojść w całości: "[", "[AC",
        // "[ ACTI". Wstrzymujemy tylko taki ogon - zwykły nawias ("[1]",
        // link markdown) idzie na głos jak dotąd.
        val last = buffer.lastIndexOf('[')
        if (last >= 0 && buffer.length - last <= MAX_MARKER_PREFIX) {
            val tail = buffer.substring(last + 1).replace(" ", "").uppercase()
            if ("ACTION".startsWith(tail)) return last
        }
        return buffer.length
    }

    private val SINGLE_MARKER = Regex("\\[\\s*ACTION", RegexOption.IGNORE_CASE)

    /** Tyle znaków od `[` może jeszcze być niedokończonym początkiem znacznika. */
    private const val MAX_MARKER_PREFIX = 10
}
