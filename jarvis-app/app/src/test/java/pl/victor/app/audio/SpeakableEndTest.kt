package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Granica, do której wolno mówić z bufora strumienia.
 *
 * Wcześniej wstrzymywał ją KAŻDY nawias kwadratowy, więc odpowiedź z linkiem
 * albo przypisem milczała przez cały czas generowania i szła jednym ciągiem
 * dopiero na końcu - czyli czytanie zdanie po zdaniu nie działało.
 */
class SpeakableEndTest {

    private fun speakable(buffer: String) =
        buffer.substring(0, StreamSpeech.speakableEnd(buffer))

    @Test
    fun `znacznik akcji zostaje wstrzymany`() {
        assertEquals(
            "Jasne, wysyłam. ",
            speakable("Jasne, wysyłam. [[ACTION: type=send_sms to=\"Ania\"]]")
        )
    }

    @Test
    fun `pojedynczy nawias na koncu bufora czeka na drugi`() {
        // Następny fragment strumienia może dopisać drugi nawias.
        assertEquals("Już patrzę. ", speakable("Już patrzę. ["))
    }

    @Test
    fun `link markdown nie zatrzymuje mowy`() {
        val text = "Znalazłem to w [instrukcji](https://example.com) na stronie 12."
        assertEquals(text, speakable(text))
    }

    @Test
    fun `przypis z wyszukiwania nie zatrzymuje mowy`() {
        val text = "Jutro ma być 18 stopni [1] i bezchmurnie."
        assertEquals(text, speakable(text))
    }

    @Test
    fun `zwykly nawias w zdaniu nie zatrzymuje mowy`() {
        val text = "Spotkanie [sala 4] zaczyna się o dziesiątej."
        assertEquals(text, speakable(text))
    }

    @Test
    fun `tekst bez nawiasow idzie w calosci`() {
        val text = "Dzisiaj jest wtorek, a jutro środa."
        assertEquals(text, speakable(text))
    }

    @Test
    fun `znacznik na samym poczatku nie przepuszcza nic`() {
        assertEquals("", speakable("[[ACTION: type=take_photo]]"))
    }
}
