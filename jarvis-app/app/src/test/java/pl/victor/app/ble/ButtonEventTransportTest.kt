package pl.victor.app.ble

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Wciśnięcie przycisku jest ZDARZENIEM, a nie stanem - i na tym poległo
 * podwójne oraz potrójne kliknięcie.
 *
 * ## Co się działo
 * `_buttonEvent` był `MutableStateFlow`, a [ButtonEvent.ShortClick] jest
 * obiektem. StateFlow POMIJA ustawienie tej samej wartości, więc drugie
 * kliknięcie ustawiało dokładnie ten sam egzemplarz co pierwsze i - dopóki
 * kolektor nie zdążył go odebrać i wyzerować - przepadało bez śladu, także
 * w dzienniku.
 *
 * Pojedyncze kliknięcia dzielone sekundami działały zawsze (18 na 18 w
 * dziennikach z terenu). Ginęły dokładnie szybkie serie, czyli jedyny sposób
 * wywołania gestów wieloklikowych. Zgłoszone: "3 kliknięcia nie działają, nic
 * się nie dzieje" - a w dzienniku po JEDNYM wciśnięciu na próbę.
 *
 * Testy detektora tego nie widziały, bo podają mu zdarzenia BEZPOŚREDNIO,
 * z pominięciem transportu. Usterka leżała POMIĘDZY okularami a detektorem.
 */
class ButtonEventTransportTest {

    @Test
    fun `StateFlow gubi powtorzone zdarzenie - dowod usterki`() = runBlocking {
        // Nie testuje kodu aplikacji, tylko UTRWALA powód, dla którego ten typ
        // jest tu zakazany. Bez tego następna osoba zobaczy w StateFlow wygodne
        // "ostatnie znane zdarzenie" i wróci do tego samego błędu.
        val stan = MutableStateFlow<ButtonEvent?>(null)
        val odebrane = mutableListOf<ButtonEvent?>()
        val praca = async { stan.take(2).toList(odebrane) }
        delay(50)
        stan.value = ButtonEvent.ShortClick
        stan.value = ButtonEvent.ShortClick // ten sam obiekt - StateFlow go POMIJA
        delay(50)
        praca.cancel()
        assertEquals(
            "StateFlow miał oddać jedno zdarzenie zamiast dwóch - na tym polegała usterka",
            listOf<ButtonEvent?>(null, ButtonEvent.ShortClick),
            odebrane
        )
    }

    @Test
    fun `SharedFlow oddaje kazde wcisniecie osobno`() = runBlocking {
        val zdarzenia = MutableSharedFlow<ButtonEvent>(replay = 0, extraBufferCapacity = 8)
        val odebrane = mutableListOf<ButtonEvent>()
        val praca = async { zdarzenia.take(3).toList(odebrane) }
        delay(50)
        repeat(3) { zdarzenia.tryEmit(ButtonEvent.ShortClick) }
        delay(50)
        praca.cancel()
        assertEquals("trzy wciśnięcia mają dać trzy zdarzenia", 3, odebrane.size)
    }

    @Test
    fun `wcisniecia przycisku nie jada StateFlow`() {
        // Właściwy strażnik: pilnuje TYPU w kodzie aplikacji. Semantyki
        // VictorManagera nie da się sprawdzić bez Androida, ale wybór typu -
        // owszem, i to on był usterką.
        var dir: File? = File("").absoluteFile
        var plik: File? = null
        while (dir != null && plik == null) {
            plik = listOf(
                File(dir, "jarvis-app/app/src/main/java/pl/victor/app/ble/VictorManager.kt"),
                File(dir, "src/main/java/pl/victor/app/ble/VictorManager.kt")
            ).firstOrNull { it.isFile }
            dir = dir.parentFile
        }
        assumeTrue("Nie znalazłem VictorManager.kt", plik != null)

        val linia = plik!!.readLines().firstOrNull { it.contains("private val _buttonEvent") }
        assertTrue("Nie znalazłem deklaracji _buttonEvent", linia != null)
        assertTrue(
            "wciśnięcia przycisku muszą iść SharedFlow - StateFlow gubi powtórzenia: $linia",
            linia!!.contains("MutableSharedFlow")
        )
    }
}
