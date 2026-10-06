package pl.victor.app.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kontekst ma iść za rozmową, a nie ciągnąć się przez cały dzień - [ContextTopics]. */
class ContextTopicsTest {

    private var teraz = 1_000_000L
    private val tematy = ContextTopics(ttlMs = 60_000L, clock = { teraz })

    @Test
    fun `pytanie dopytujace dostaje dane tematu`() {
        assertTrue(tematy.dokleić("pogoda", pytanieOTemat = true))
        teraz += 20_000
        assertTrue("\"a jutro?\" ma dostać prognozę", tematy.dokleić("pogoda", pytanieOTemat = false))
    }

    @Test
    fun `temat wygasa po terminie`() {
        tematy.dokleić("poczta", pytanieOTemat = true)
        teraz += 61_000
        assertFalse(tematy.dokleić("poczta", pytanieOTemat = false))
    }

    @Test
    fun `dopytanie nie przedluza terminu, wzmianka tak`() {
        tematy.dokleić("pogoda", pytanieOTemat = true)
        teraz += 50_000
        tematy.dokleić("pogoda", pytanieOTemat = false)
        teraz += 20_000
        assertFalse("samo dopytywanie nie może trzymać tematu w nieskończoność",
            tematy.dokleić("pogoda", pytanieOTemat = false))
    }

    @Test
    fun `wymuszenie daje dane, ale nie otwiera tematu`() {
        assertTrue(tematy.dokleić("kalendarz", pytanieOTemat = false, wymuszone = true))
        teraz += 1_000
        assertFalse("po turze z nagraniem kalendarz nie może zostać otwarty na stałe",
            tematy.dokleić("kalendarz", pytanieOTemat = false))
    }

    @Test
    fun `wymuszenie nie otwiera tematu nawet gdy tekst pasuje do wzorca`() {
        // Polecenie dla nagrania wymienia "pogodę" - dziennik z biegu 154.
        assertTrue(tematy.dokleić("pogoda", pytanieOTemat = true, wymuszone = true))
        teraz += 1_000
        assertFalse("muzyka po turze z nagraniem nie może dostać pogody",
            tematy.dokleić("pogoda", pytanieOTemat = false))
    }

    @Test
    fun `nowy temat czysci wszystko`() {
        tematy.dokleić("notatki", pytanieOTemat = true)
        tematy.clear()
        assertFalse(tematy.dokleić("notatki", pytanieOTemat = false))
    }
}
