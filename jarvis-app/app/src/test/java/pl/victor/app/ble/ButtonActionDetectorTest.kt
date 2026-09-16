package pl.victor.app.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gesty na okularach to jedyny sposób sterowania V.I.C.T.O.R.-em bez telefonu,
 * a każdy z nich znaczy co innego. Te testy pilnują mapy gestów - przede
 * wszystkim tego, że podwójne kliknięcie ROBI ZDJĘCIE.
 *
 * To nie jest test dla samego testu: gdy pojedyncze kliknięcie przestało robić
 * zdjęcie, a zaczęło słuchać, aparatu nie wywoływał już żaden gest - i wyglądało
 * to dokładnie tak, jak zgłoszono: "zdjęć jakby w ogóle nie robi".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ButtonActionDetectorTest {

    /** Okno zliczania kliknięć w detektorze to 500 ms - czekamy z zapasem. */
    private val afterClickWindowMs = 900L

    private fun actionFor(clicks: Int): ButtonAction = runBlocking {
        val detector = ButtonActionDetector()
        val awaited = async { detector.action.first() }
        // Subskrypcja flow musi zdążyć wystartować, zanim polecą kliknięcia -
        // SharedFlow bez replay nie odda zdarzenia sprzed subskrypcji.
        delay(50)
        repeat(clicks) { detector.processEvent(ButtonEvent.ShortClick) }
        delay(afterClickWindowMs)
        awaited.await()
    }

    @Test
    fun `jedno klikniecie pyta glosem`() {
        assertEquals(ButtonAction.QUICK_QUESTION, actionFor(1))
    }

    @Test
    fun `dwa klikniecia robia zdjecie i opisuja widok`() {
        assertEquals(ButtonAction.LOOK_AND_DESCRIBE, actionFor(2))
    }

    @Test
    fun `trzy klikniecia czytaja tekst`() {
        // Czytanie siedzi najniżej, jak się da NA TYM SPRZĘCIE - patrz test
        // "kazda akcja da sie wywolac samymi klikami" niżej.
        assertEquals(ButtonAction.READ_TEXT, actionFor(3))
    }

    @Test
    fun `cztery klikniecia zaczynaja nowa rozmowe`() {
        // Reset zszedł najniżej, bo jest najrzadziej potrzebny - i tak da się
        // go zrobić głosem ("nowy temat").
        assertEquals(ButtonAction.NEW_CONVERSATION, actionFor(4))
    }

    @Test
    fun `kazda akcja da sie wywolac samymi klikami`() {
        // TEN TEST JEST TU PO COŚ KONKRETNEGO.
        //
        // Okulary zgłaszają przez BLE wyłącznie ShortClick - ani
        // przytrzymania, ani puszczenia, ani czasu trzymania (ramka 0x03 niesie
        // sam numer przycisku). Akcja podpięta pod inne zdarzenie jest więc
        // MARTWA, choć kod wygląda poprawnie i ma zielony test.
        //
        // Tak właśnie zginęło czytanie tekstu: wisiało pod LongPress i nie
        // odpaliło się ani razu. Zgłoszone dwa razy ("tłumaczenie nie działa,
        // kompletnie nic nie mówi", "w trybie czytania niczego nie czyta"),
        // a w dzienniku z terenu jest szesnaście wciśnięć i wszystkie to
        // pojedyncze kliknięcia.
        // SCAN_QR świadomie NIE ma gestu - zszedł do komend głosowych i do
        // spisu komend, bo jest niszowy. Reszta musi być osiągalna klikami.
        val wymagane = listOf(
            ButtonAction.QUICK_QUESTION,
            ButtonAction.LOOK_AND_DESCRIBE,
            ButtonAction.READ_TEXT,
            ButtonAction.NEW_CONVERSATION
        )
        val osiagalne = (1..6).map { actionFor(it) }.toSet()
        val brakujace = wymagane - osiagalne
        assertTrue(
            "tych akcji nie da się wywołać żadną liczbą kliknięć: $brakujace",
            brakujace.isEmpty()
        )
    }

    @Test
    fun `gotowe zdarzenie podwojnego klikniecia tez robi zdjecie`() = runBlocking {
        val detector = ButtonActionDetector()
        val awaited = async { detector.action.first() }
        delay(50)
        detector.processEvent(ButtonEvent.DoubleClick)
        assertEquals(ButtonAction.LOOK_AND_DESCRIBE, awaited.await())
    }

    @Test
    fun `przytrzymanie tez czyta tekst, gdyby sprzet je przysylal`() = runBlocking {
        // Gałąź zostaje, bo nic nie kosztuje, a inny egzemplarz może ją
        // wysyłać. Ale NIE WOLNO na niej niczego opierać - na tym sprzęcie
        // nie przychodzi ani razu.
        val detector = ButtonActionDetector()
        val awaited = async { detector.action.first() }
        delay(50)
        detector.processEvent(ButtonEvent.LongPress)
        assertEquals(ButtonAction.READ_TEXT, awaited.await())
    }

    @Test
    fun `gotowe zdarzenie potrojnego klikniecia tez czyta tekst`() = runBlocking {
        val detector = ButtonActionDetector()
        val awaited = async { detector.action.first() }
        delay(50)
        detector.processEvent(ButtonEvent.TripleClick)
        assertEquals(ButtonAction.READ_TEXT, awaited.await())
    }
}
