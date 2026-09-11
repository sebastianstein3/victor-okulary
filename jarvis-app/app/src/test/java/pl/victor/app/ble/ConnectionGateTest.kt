package pl.victor.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGateTest {

    @Test
    fun `pierwsze wykrycie uslug jest nowym polaczeniem`() {
        assertTrue(ConnectionGate().onServicesDiscovered(1_000L))
    }

    @Test
    fun `echo SDK zaraz po wykryciu uslug nie jest nowym polaczeniem`() {
        val gate = ConnectionGate()
        gate.onServicesDiscovered(1_000L)
        // Producent rozgłasza service_discovered drugi raz po ~2,5 s.
        assertFalse(gate.onServicesDiscovered(3_500L))
    }

    /**
     * To jest test dla sytuacji z dziennika: zdjęcie o 16:01, POŁĄCZONO o 17:33
     * i ani jednego ROZŁĄCZONO po drodze. Bez tej gałęzi okulary zostawały
     * bez powitania, bez openBT i bez wykrywania frazy do końca sesji.
     */
    @Test
    fun `wykrycie uslug po dlugiej przerwie jest nowym polaczeniem mimo braku ramki rozlaczenia`() {
        val gate = ConnectionGate()
        gate.onServicesDiscovered(godzina(16, 1))
        assertTrue(gate.onServicesDiscovered(godzina(17, 33)))
    }

    @Test
    fun `zapowiedziane laczenie robi nowe polaczenie nawet w oknie echa`() {
        val gate = ConnectionGate()
        gate.onServicesDiscovered(1_000L)
        gate.onConnecting()
        // Krócej niż okno echa, ale SDK zdążyło zapowiedzieć łączenie -
        // to znaczy, że GATT wstał od nowa.
        assertTrue(gate.onServicesDiscovered(2_000L))
    }

    @Test
    fun `po zapowiedzi kolejne echo jest nadal echem`() {
        val gate = ConnectionGate()
        gate.onConnecting()
        assertTrue(gate.onServicesDiscovered(1_000L))
        assertFalse(gate.onServicesDiscovered(3_500L))
    }

    @Test
    fun `rozlaczenie sprawia ze nastepne wykrycie uslug jest nowe`() {
        val gate = ConnectionGate()
        gate.onServicesDiscovered(1_000L)
        gate.onDisconnected()
        assertTrue(gate.onServicesDiscovered(1_500L))
    }

    @Test
    fun `cofniety zegar nie uchodzi za echo`() {
        val gate = ConnectionGate()
        gate.onServicesDiscovered(10_000L)
        assertTrue(gate.onServicesDiscovered(5_000L))
    }

    private fun godzina(h: Int, m: Int): Long = ((h * 60L + m) * 60L) * 1000L
}
