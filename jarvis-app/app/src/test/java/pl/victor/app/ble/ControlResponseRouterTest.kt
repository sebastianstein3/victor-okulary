package pl.victor.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlResponseRouterTest {

    private var clock = 1_000_000L
    private val router = ControlResponseRouter<String> { clock }
    private val got = mutableListOf<String>()

    private fun expect(type: Int?, name: String) = router.expect(type) { got += "$name<-$it" }

    @Test
    fun `odpowiedz trafia do czekajacego na ten typ a nie do ostatniego`() {
        expect(0x04, "transfer")
        expect(0x14, "podglad")
        assertTrue(router.route(0x04, "A"))
        assertTrue(router.route(0x14, "B"))
        assertEquals(listOf("transfer<-A", "podglad<-B"), got)
        assertEquals(0, router.pending())
    }

    @Test
    fun `odpowiedz na komende bez czekajacego nie jest podawana komu innemu`() {
        expect(0x04, "transfer")
        router.noteSent(0x01)
        assertFalse(router.route(0x01, "ack zdjęcia"))
        assertTrue(got.isEmpty())
        assertTrue(router.route(0x04, "A"))
        assertEquals(listOf("transfer<-A"), got)
    }

    @Test
    fun `gdy firmware nie odsyla typu dostaje najdluzej czekajacy`() {
        expect(0x04, "pierwszy")
        expect(0x04, "drugi")
        assertTrue(router.route(null, "X"))
        assertTrue(router.route(0x7F, "Y"))
        assertEquals(listOf("pierwszy<-X", "drugi<-Y"), got)
    }

    @Test
    fun `dwie komendy tego samego typu dostaja odpowiedzi po kolei`() {
        expect(0x04, "pierwszy")
        expect(0x04, "drugi")
        router.route(0x04, "1")
        router.route(0x04, "2")
        assertEquals(listOf("pierwszy<-1", "drugi<-2"), got)
    }

    @Test
    fun `zapomniany czekajacy wygasa a anulowany znika od razu`() {
        expect(0x04, "stary")
        clock += ControlResponseRouter.WAITER_TTL_MS + 1
        assertFalse(router.route(0x04, "spóźniona"))
        val handle = expect(0x04, "anulowany")
        router.cancel(handle)
        assertFalse(router.route(0x04, "po anulowaniu"))
        assertTrue(got.isEmpty())
    }

    @Test
    fun `stara wysylka nie zaslania odpowiedzi`() {
        router.noteSent(0x01)
        clock += ControlResponseRouter.RECENT_SEND_MS + 1
        expect(0x04, "transfer")
        // Typ 1 poszedł dawno - to nie jest już "jego" odpowiedź.
        assertTrue(router.route(0x01, "Z"))
        assertEquals(listOf("transfer<-Z"), got)
    }

    @Test
    fun `dwie takie same wysylki odsiewaja dwie odpowiedzi`() {
        expect(0x04, "transfer")
        router.noteSent(0x01)
        router.noteSent(0x01)
        assertFalse(router.route(0x01, "ack 1"))
        assertFalse(router.route(0x01, "ack 2"))
        assertTrue(got.isEmpty())
        // Trzecia odpowiedź tego typu nie ma już swojej wysyłki.
        assertTrue(router.route(0x01, "trzecia"))
        assertEquals(listOf("transfer<-trzecia"), got)
    }

    @Test
    fun `bez czekajacych nic sie nie dzieje`() {
        assertFalse(router.route(0x04, "nikt"))
    }
}
