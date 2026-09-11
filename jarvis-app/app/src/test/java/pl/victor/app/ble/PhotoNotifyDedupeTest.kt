package pl.victor.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoNotifyDedupeTest {

    @Test
    fun `pierwsze zgloszenie nigdy nie jest powtorka`() {
        assertFalse(PhotoNotifyDedupe.isEcho(lastNotifyAtMs = 0L, nowMs = 5_000L))
    }

    /** Dokładnie ten odstęp, który jest w dzienniku: 17:42:51.047 i .049. */
    @Test
    fun `dwie milisekundy odstepu to powtorka`() {
        assertTrue(PhotoNotifyDedupe.isEcho(lastNotifyAtMs = 51_047L, nowMs = 51_049L))
    }

    @Test
    fun `po uplywie okna to juz osobne zdjecie`() {
        val later = PhotoNotifyDedupe.WINDOW_MS + 1
        assertFalse(PhotoNotifyDedupe.isEcho(lastNotifyAtMs = 1_000L, nowMs = 1_000L + later))
    }

    @Test
    fun `granica okna nalezy do osobnego zdjecia`() {
        assertFalse(
            PhotoNotifyDedupe.isEcho(
                lastNotifyAtMs = 1_000L,
                nowMs = 1_000L + PhotoNotifyDedupe.WINDOW_MS
            )
        )
    }

    @Test
    fun `cofniety zegar nie uchodzi za powtorke`() {
        assertFalse(PhotoNotifyDedupe.isEcho(lastNotifyAtMs = 10_000L, nowMs = 9_000L))
    }
}

class OwnShutterTest {

    @Test
    fun `bez wlasnej komendy zgloszenie nalezy do uzytkownika`() {
        assertFalse(PhotoNotifyDedupe.isOwnShutter(lastShutterAtMs = 0L, nowMs = 10_000L))
    }

    /**
     * Ten przypadek zrobił pętlę: nasza migawka o 22:15:17, a ramka 0x02
     * w tej samej sekundzie - i bez tego sprawdzenia wyglądała jak wciśnięcie
     * przycisku przez człowieka.
     */
    @Test
    fun `zgloszenie tuz po naszej komendzie jest nasze`() {
        assertTrue(PhotoNotifyDedupe.isOwnShutter(lastShutterAtMs = 1_000L, nowMs = 2_500L))
    }

    @Test
    fun `okno pokrywa caly budzet przechwytywania`() {
        // Budżet to 22 s - zgłoszenie na jego końcu musi się jeszcze zmieścić.
        assertTrue(PhotoNotifyDedupe.isOwnShutter(lastShutterAtMs = 0L + 1, nowMs = 22_001L))
    }

    @Test
    fun `po uplywie okna zgloszenie znow nalezy do uzytkownika`() {
        val after = PhotoNotifyDedupe.OWN_SHUTTER_WINDOW_MS + 1
        assertFalse(PhotoNotifyDedupe.isOwnShutter(lastShutterAtMs = 1_000L, nowMs = 1_000L + after))
    }

    @Test
    fun `cofniety zegar nie robi z cudzego zdjecia naszego`() {
        assertFalse(PhotoNotifyDedupe.isOwnShutter(lastShutterAtMs = 10_000L, nowMs = 9_000L))
    }
}
