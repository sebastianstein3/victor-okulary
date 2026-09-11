package pl.victor.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagFormatTest {

    @Test
    fun `wiersz ma zegar etap i tresc`() {
        val line = DiagFormat.line(
            wallClock = "10:23:45.123",
            sinceTurnMs = 0,
            phase = DiagFormat.Phase.WAKE,
            message = "wybudzenie z okularów"
        )
        assertTrue(line, "10:23:45.123" in line)
        assertTrue(line, "WAKE" in line)
        assertTrue(line, "wybudzenie z okularów" in line)
    }

    @Test
    fun `pola ida na koniec wiersza`() {
        val line = DiagFormat.line(
            "10:23:45.123", 1200, DiagFormat.Phase.TRANSKRYPCJA, "rozpoznano",
            mapOf("silnik" to "systemowy", "ms" to 340)
        )
        assertTrue(line, line.indexOf("silnik=systemowy") > line.indexOf("rozpoznano"))
        assertTrue(line, "ms=340" in line)
    }

    @Test
    fun `puste pola nie zasmiecaja wiersza`() {
        val line = DiagFormat.line(
            "10:23:45.123", 0, DiagFormat.Phase.MODEL, "start",
            mapOf("model" to null, "dostawca" to "deepseek")
        )
        assertTrue(line, "model=" !in line)
        assertTrue(line, "dostawca=deepseek" in line)
    }

    @Test
    fun `czas od startu tury jest widoczny`() {
        val line = DiagFormat.line("10:23:45.123", 2480, DiagFormat.Phase.MOWA, "start")
        assertTrue(line, "2480" in line)
    }

    @Test
    fun `poza tura nie ma licznika`() {
        val line = DiagFormat.line("10:23:45.123", null, DiagFormat.Phase.BLE, "rozłączono")
        assertTrue(line, "rozłączono" in line)
    }

    // === Zaciemnianie - to jest część, która chroni przed wyciekiem ===

    @Test
    fun `klucz OpenAI nie trafia do dziennika`() {
        val out = DiagFormat.redact("Authorization: Bearer sk-proj-AbCdEf0123456789GhIjKlMnOp")
        assertTrue(out, "AbCdEf0123456789" !in out)
        assertTrue(out, "ukryte" in out)
    }

    @Test
    fun `klucz Google nie trafia do dziennika`() {
        val out = DiagFormat.redact("klucz=AIzaSyD1234567890abcdefghijklmnopqrs")
        assertTrue(out, "SyD1234567890" !in out)
    }

    @Test
    fun `token GitHuba nie trafia do dziennika`() {
        val out = DiagFormat.redact("token ghp_ABCDEFGHIJ1234567890abcdefghij")
        assertTrue(out, "ABCDEFGHIJ1234567890" !in out)
    }

    @Test
    fun `fine-grained token GitHuba nie trafia do dziennika`() {
        val out = DiagFormat.redact("github_pat_11ABCDEFG0abcdefghij_KLMNOPQRSTUVWXYZ0123456789")
        assertTrue(out, "KLMNOPQRSTUVWXYZ" !in out)
    }

    @Test
    fun `klucz Anthropic nie trafia do dziennika`() {
        val out = DiagFormat.redact("sk-ant-api03-AbCdEf0123456789GhIjKl")
        assertTrue(out, "AbCdEf0123456789" !in out)
    }

    @Test
    fun `zostaje poczatek zeby dalo sie poznac ktory to klucz`() {
        val out = DiagFormat.redact("sk-proj-AbCdEf0123456789GhIjKlMnOp")
        assertTrue(out, out.startsWith("sk-pro"))
    }

    @Test
    fun `zwykly tekst zostaje nietkniety`() {
        val text = "rozpoznano: jaka jest pogoda w Warszawie"
        assertEquals(text, DiagFormat.redact(text))
    }

    @Test
    fun `polskie zdanie z liczbami nie jest brane za sekret`() {
        val text = "nasłuch trwał 2480 ms, okulary nadały 15 pakietów"
        assertEquals(text, DiagFormat.redact(text))
    }

    @Test
    fun `dlugi ciag literowo-cyfrowy jest zaciemniany na wszelki wypadek`() {
        val out = DiagFormat.redact("wartosc=Xq7Lm2Pz9Rt4Vb6Nc8Jd1Kf3Hg5Ws0Ya2Ue4Ii")
        assertTrue(out, "Nc8Jd1Kf3Hg5Ws0" !in out)
    }

    @Test
    fun `zaciemnianie dziala takze w polach wiersza`() {
        val line = DiagFormat.line(
            "10:23:45.123", 0, DiagFormat.Phase.MODEL, "start",
            mapOf("klucz" to "sk-proj-AbCdEf0123456789GhIjKlMnOp")
        )
        assertTrue(line, "AbCdEf0123456789" !in line)
    }

    @Test
    fun `zaciemnianie dziala takze w tresci wiersza`() {
        val line = DiagFormat.line(
            "10:23:45.123", 0, DiagFormat.Phase.BŁĄD,
            "odmowa: AIzaSyD1234567890abcdefghijklmnopqrs"
        )
        assertTrue(line, "SyD1234567890" !in line)
    }

    // Klucz Picovoice to jedyny sekret w aplikacji bez rozpoznawalnego prefiksu.
    // Zapisany w base64 rozpadał się na "/" i "+" na fragmenty krótsze niż próg
    // 32 i przechodził przez siatkę w całości - do publicznego repozytorium.

    @Test
    fun `klucz w base64 nie trafia do dziennika`() {
        val out = DiagFormat.redact("klucz=Hs9OqA0sVx7Lm2P/YbNc8Jd1Kf3Hg+Ws0Ya2Ue4IiQq7Lm2Pz9Rt4Vb6N==")
        assertTrue(out, "YbNc8Jd1Kf3Hg" !in out)
        assertTrue(out, "Ws0Ya2Ue4IiQq7" !in out)
        assertTrue(out, "ukryte" in out)
    }

    @Test
    fun `base64 zaciemnia sie w calosci a nie we fragmentach`() {
        val out = DiagFormat.redact("aB3d/efGh1Jk+Lm2No4Pq6Rs8Tu0Vw2Xy4Za6Bc8De==")
        assertTrue(out, "Rs8Tu0Vw2Xy4" !in out)
        assertTrue(out, "efGh1Jk" !in out)
    }

    @Test
    fun `komunikat Porcupine z kluczem w srodku jest bezpieczny`() {
        val out = DiagFormat.redact(
            "PorcupineInvalidArgumentException: AccessKey " +
                "'Hs9OqA0sVx7Lm2P/YbNc8Jd1Kf3Hg+Ws0Ya2Ue4IiQq7Lm2Pz9Rt4Vb6N==' is invalid"
        )
        assertTrue(out, "YbNc8Jd1Kf3Hg" !in out)
    }

    // Przeciwwaga: po adresie poznaje się, KTÓRE wywołanie zawiodło. Gdyby
    // siatka na base64 zjadała ścieżki URL-i, dziennik straciłby tę informację.

    @Test
    fun `sciezka adresu zostaje czytelna`() {
        val text = "błąd HTTP z https://generativelanguage.googleapis.com/v1beta/models/gemini-flash"
        assertEquals(text, DiagFormat.redact(text))
    }

    @Test
    fun `nazwa modelu zostaje czytelna`() {
        val text = "model=claude-sonnet-4-20250514 dostawca=anthropic"
        assertEquals(text, DiagFormat.redact(text))
    }
}
