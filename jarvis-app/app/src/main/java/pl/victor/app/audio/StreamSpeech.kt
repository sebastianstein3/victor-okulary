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
        val marker = buffer.indexOf("[[")
        if (marker >= 0) return marker
        return if (buffer.endsWith("[")) buffer.length - 1 else buffer.length
    }
}
