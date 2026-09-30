package pl.victor.app.ble

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ramka 0x0C to "ucisz" TYLKO przy [7] == 1 - jak w aplikacji producenta.
 *
 * Dziennik z 30 września: po każdej wypowiedzi (odpowiedź okularów na nasz
 * sygnał końca mówienia) aplikacja przerywała turę "dotknięciem zauszników" -
 * w tym tłumaczenie ze słuchu tuż po jego zapowiedzi.
 */
class InterruptNotifyTest {

    @Test
    fun `znacznik 1 to prawdziwe uciszenie`() {
        val event = GlassesProtocol.decodeNotify(GlassesProtocol.notifyFrame(GlassesProtocol.NOTIFY_INTERRUPT_SPEECH, 1))
        assertTrue("$event", event is NotifyEvent.SpeechInterrupted)
    }

    @Test
    fun `inna wartosc albo jej brak to tylko potwierdzenie stanu`() {
        listOf(
            GlassesProtocol.notifyFrame(GlassesProtocol.NOTIFY_INTERRUPT_SPEECH, 0),
            GlassesProtocol.notifyFrame(GlassesProtocol.NOTIFY_INTERRUPT_SPEECH, 3),
            GlassesProtocol.notifyFrame(GlassesProtocol.NOTIFY_INTERRUPT_SPEECH)
        ).forEach { frame ->
            val event = GlassesProtocol.decodeNotify(frame)
            assertTrue("$event nie może przerywać tury", event is NotifyEvent.PlaybackStateEcho)
        }
    }

    @Test
    fun `symulator wysyla prawdziwe uciszenie`() {
        assertTrue(GlassesProtocol.decodeNotify(GlassesProtocol.interruptSpeechFrame()) is NotifyEvent.SpeechInterrupted)
    }
}
