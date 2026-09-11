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
