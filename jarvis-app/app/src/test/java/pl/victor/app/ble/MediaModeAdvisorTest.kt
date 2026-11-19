package pl.victor.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaModeAdvisorTest {

    private val sink = "0000110B-0000-1000-8000-00805F9B34FB"
    private val a2dp = "0000110D-0000-1000-8000-00805F9B34FB"
    private val handsFree = "0000111E-0000-1000-8000-00805F9B34FB"

    private fun diagnosis(
        declares: Boolean? = true,
        state: A2dpLinkState = A2dpLinkState.DISCONNECTED,
        name: String? = "W610T_A6BA",
        permission: Boolean = true
    ) = MediaModeDiagnosis(
        permission = permission,
        deviceName = name,
        bonded = name != null,
        classic = true,
        declaresA2dpSink = declares,
        declaresHandsFree = true,
        uuidCount = 3,
        a2dpState = state
    )

    @Test
    fun `AudioSink albo A2DP w profilach to tryb multimediow - bez wzgledu na wielkosc liter`() {
        assertEquals(true, MediaModeAdvisor.declaresA2dpSink(listOf(handsFree, sink)))
        assertEquals(true, MediaModeAdvisor.declaresA2dpSink(listOf(a2dp.lowercase())))
        assertEquals(false, MediaModeAdvisor.declaresA2dpSink(listOf(handsFree)))
        assertEquals(true, MediaModeAdvisor.declaresHandsFree(listOf(handsFree)))
    }

    @Test
    fun `brak listy profili to nie wiadomo a nie brak profilu`() {
        assertNull(MediaModeAdvisor.declaresA2dpSink(null))
        assertEquals(false, MediaModeAdvisor.declaresA2dpSink(emptyList()))
    }

    @Test
    fun `wybiera klasyczna twarz okularow po nazwie urzadzenia audio`() {
        val car = BondedCandidate("Samochód", "AA:00:00:00:00:01", classic = true, uuids = listOf(sink))
        val ble = BondedCandidate("W610T_A6BA", "AA:00:00:00:A6:BA", classic = false, uuids = null)
        val classic = BondedCandidate("W610T_A6BA", "BB:00:00:00:A6:BA", classic = true, uuids = listOf(handsFree))
        val chosen = MediaModeAdvisor.pickGlassesDevice(
            listOf(car, ble, classic),
            audioName = "W610T_A6BA",
            bleName = "W610T_A6BA",
            bleAddress = "AA:00:00:00:A6:BA"
        )
        assertEquals(classic, chosen)
    }

    @Test
    fun `cudze urzadzenie z profilem multimediow nie jest brane za okulary`() {
        val speaker = BondedCandidate("Głośnik kuchnia", "AA:00:00:00:00:02", classic = true, uuids = listOf(sink))
        assertNull(MediaModeAdvisor.pickGlassesDevice(listOf(speaker), null, "W610T_A6BA", null))
    }

    @Test
    fun `sam ksztalt nazwy wystarcza gdy nie znamy nazwy audio ani BLE`() {
        val glasses = BondedCandidate("W610T_04C5", "CC:00:00:00:04:C5", classic = true, uuids = null)
        assertEquals(glasses, MediaModeAdvisor.pickGlassesDevice(listOf(glasses), null, null, null))
    }

    @Test
    fun `zadeklarowany i rozlaczony - instrukcja wlaczenia w ustawieniach`() {
        val hint = MediaModeAdvisor.hintFor(diagnosis(declares = true))!!
        assertEquals(MediaModeHintKind.ENABLE_IN_SETTINGS, hint.kind)
        assertEquals(MediaModeHintAction.BLUETOOTH_SETTINGS, hint.action)
        assertTrue(hint.body.contains("Dźwięk multimediów"))
        assertTrue(hint.body.contains("zębatki"))
        assertTrue("nazwa okularów pomaga je znaleźć", hint.body.contains("W610T_A6BA"))
    }

    @Test
    fun `niezadeklarowany - uczciwie ze okulary nie oferuja trybu multimediow`() {
        val hint = MediaModeAdvisor.hintFor(diagnosis(declares = false))!!
        assertEquals(MediaModeHintKind.NOT_OFFERED, hint.kind)
        assertTrue(hint.title.contains("nie oferują"))
        assertFalse("nie każemy szukać przełącznika, którego nie ma", hint.body.contains("włącz „Dźwięk"))
    }

    @Test
    fun `nieznane profile - instrukcja z zastrzezeniem`() {
        val hint = MediaModeAdvisor.hintFor(diagnosis(declares = null))!!
        assertEquals(MediaModeHintKind.UNCERTAIN, hint.kind)
        assertTrue(hint.body.contains("Jeśli takiego przełącznika nie ma"))
    }

    @Test
    fun `profil polaczony wedlug systemu - podpowiedz nic nie da`() {
        assertNull(MediaModeAdvisor.hintFor(diagnosis(state = A2dpLinkState.CONNECTED)))
    }

    @Test
    fun `brak zgody i brak urzadzenia maja wlasne podpowiedzi`() {
        assertEquals(MediaModeHintKind.NO_PERMISSION, MediaModeAdvisor.hintKind(MediaModeDiagnosis.NO_PERMISSION))
        assertEquals(
            MediaModeHintAction.APP_SETTINGS,
            MediaModeAdvisor.hint(MediaModeHintKind.NO_PERMISSION, null).action
        )
        assertEquals(MediaModeHintKind.NOT_PAIRED, MediaModeAdvisor.hintKind(MediaModeDiagnosis.NOT_FOUND))
        assertEquals(MediaModeHintKind.UNCERTAIN, MediaModeAdvisor.hintKind(MediaModeDiagnosis.PROBE_FAILED))
    }

    @Test
    fun `wiersz dziennika ma wniosek`() {
        val fields = diagnosis(declares = true).fields()
        assertEquals("true", fields["deklarujeA2DP"])
        assertEquals("rozłączony", fields["profilA2DP"])
        assertEquals(MediaModeHintKind.ENABLE_IN_SETTINGS.label, fields["wniosek"])
    }

    // === Sesja: ograniczanie próśb i jednorazowa podpowiedź ===

    @Test
    fun `po trzech porazkach prosba raz na dziesiec minut`() {
        val s = MediaModeSession()
        var t = 1_000L
        repeat(2) {
            assertTrue(s.mayRequest(t)); s.noteRequest(t)
            assertEquals(MediaModeSession.Transition.NONE, s.noteResult(false))
            t += 30_000L
        }
        assertTrue(s.mayRequest(t)); s.noteRequest(t)
        assertEquals("trzecia porażka - raz do dziennika", MediaModeSession.Transition.ENTERED_BACKOFF, s.noteResult(false))
        val last = t
        assertFalse("następna tura nie prosi", s.mayRequest(last + 30_000L))
        assertFalse(s.mayRequest(last + 9 * 60_000L))
        assertTrue(s.mayRequest(last + 10 * 60_000L))
        s.noteRequest(last + 10 * 60_000L)
        assertEquals("kolejna porażka nie ogłasza się drugi raz", MediaModeSession.Transition.NONE, s.noteResult(false))
        assertFalse(s.mayRequest(last + 11 * 60_000L))
    }

    @Test
    fun `sukces wraca do zwyklego trybu`() {
        val s = MediaModeSession()
        repeat(3) { s.noteRequest(1_000L); s.noteResult(false) }
        assertTrue(s.inBackoff())
        assertEquals(MediaModeSession.Transition.RECOVERED, s.noteResult(true))
        assertTrue(s.mayRequest(2_000L))
        assertEquals(0, s.consecutiveFailures)
        assertEquals("sukces bez porażek nic nie ogłasza", MediaModeSession.Transition.NONE, s.noteResult(true))
    }

    @Test
    fun `podpowiedz po dwoch porazkach z rzedu raz na sesje`() {
        val s = MediaModeSession()
        s.noteResult(false)
        assertFalse(s.hintDue(suppressed = false))
        s.noteResult(false)
        assertTrue(s.hintDue(suppressed = false))
        assertFalse("nie pokazuj więcej", s.hintDue(suppressed = true))
        s.markHintShown()
        s.noteResult(false)
        assertFalse("raz na sesję", s.hintDue(suppressed = false))
        s.reset()
        s.noteResult(false); s.noteResult(false)
        assertTrue("nowe połączenie - nowa sesja", s.hintDue(suppressed = false))
    }

    @Test
    fun `sukces miedzy porazkami zeruje serie`() {
        val s = MediaModeSession()
        s.noteResult(false)
        s.noteResult(true)
        s.noteResult(false)
        assertFalse(s.hintDue(suppressed = false))
    }
}
