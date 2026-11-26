package pl.victor.app.hermes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesMozgTest {

    private fun cel(zdanie: String) = HermesMózg.rozpoznaj(zdanie)?.tekst

    @Test
    fun `fakt o uzytkowniku idzie do pamieci Hermesa`() {
        val t = cel("zapamiętaj, że mieszkam w Krakowie")
        assertNotNull(t)
        assertTrue(t!!, t.contains("Mieszkam w Krakowie") && t.contains("pamięci o użytkowniku"))
    }

    @Test
    fun `zapominanie i pytanie o pamiec`() {
        assertTrue(cel("zapomnij o Krakowie")!!.contains("Usuń ze swojej pamięci"))
        assertTrue(cel("co o mnie wiesz")!!.contains("co pamiętasz o użytkowniku"))
    }

    @Test
    fun `notatka ma trafic do pliku w folderze roboczym`() {
        val t = cel("zapisz, że mam kupić mleko")
        assertNotNull(t)
        assertTrue(t!!, t.contains("Notatki.md") && t.contains("kupić mleko"))
    }

    @Test
    fun `kalendarz poczta i dysk idą do Hermesa z zastrzezeniem`() {
        for (z in listOf(
            "co mam jutro w kalendarzu",
            "dodaj do kalendarza spotkanie z Anią w piątek o dziesiątej",
            "sprawdź maile od księgowej",
            "znajdź na dysku google fakturę z października",
            "co jest w notebook lm o projekcie"
        )) {
            val t = cel(z)
            assertNotNull(z, t)
            assertTrue(z, t!!.contains(HermesMózg.DOPISEK_GOOGLE))
        }
    }

    @Test
    fun `wysylanie i telefon zostaja w Victorze`() {
        assertNull(cel("wyślij maila do Ani, że spóźnię się dziesięć minut"))
        assertNull(cel("odpisz na maila od szefa"))
        assertNull(cel("zadzwoń do Janusza"))
        assertNull(cel("napisz do mamy sms, że będę wieczorem"))
    }

    @Test
    fun `zwykle zdania i komendy urzadzenia zostaja`() {
        assertNull(cel("jaka jest pogoda"))
        assertNull(cel("ustaw głośność na pięćdziesiąt procent"))
        assertNull(cel("gdzie jest najbliższa apteka"))
        assertNull(cel(""))
        assertFalse(HermesMózg.rozpoznaj("jaka jest pogoda") != null)
    }

    @Test
    fun `poczta jako urzad nie jest skrzynka mailowa`() {
        assertNull(cel("gdzie jest najbliższa poczta"))
    }
}
