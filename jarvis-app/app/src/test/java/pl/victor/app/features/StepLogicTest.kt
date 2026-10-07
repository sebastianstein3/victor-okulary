package pl.victor.app.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepLogicTest {

    @Test
    fun `prosba i komendy`() {
        assertEquals("jak upiec chleb", StepLogic.prośba("Krok po kroku: jak upiec chleb"))
        // Bieg 160 - tak to naprawdę zostało powiedziane.
        assertEquals("jak ugotować jajko na miękko", StepLogic.prośba("powiedz mi krok po kroku jak ugotować jajko na miękko"))
        assertEquals("jak zmienić koło", StepLogic.prośba("jak zmienić koło krok po kroku"))
        assertNull(StepLogic.prośba("krok po kroku"))
        assertTrue(StepLogic.samoHasło("Krok po kroku."))
        assertEquals("składanie szafki z Ikei", StepLogic.prośba("prowadź mnie krok po kroku przez składanie szafki z Ikei"))
        assertNull(StepLogic.prośba("opowiedz mi o krokach milowych"))
        assertEquals(StepLogic.Komenda.DALEJ, StepLogic.komenda("Dalej."))
        assertEquals(StepLogic.Komenda.POWTÓRZ, StepLogic.komenda("powtórz"))
        assertEquals(StepLogic.Komenda.KONIEC, StepLogic.komenda("koniec instrukcji"))
        assertNull(StepLogic.komenda("dalej nie wiem co robić z tą śrubą"))
    }

    @Test
    fun `kroki z odpowiedzi modelu`() {
        val k = StepLogic.kroki("Oto kroki:\n1. Rozgrzej piekarnik.\n2) Wymieszaj mąkę z wodą.\n\n3. Piecz 40 minut.")
        assertEquals(listOf("Rozgrzej piekarnik.", "Wymieszaj mąkę z wodą.", "Piecz 40 minut."), k)
    }

    @Test
    fun `nawigacja po krokach`() {
        val m = StepMode()
        m.start("chleb", listOf("a", "b"))
        assertEquals("Krok 1 z 2. a", m.bieżący())
        assertEquals("Krok 2 z 2. b", m.dalej())
        assertTrue(m.dalej()!!.contains("ostatni"))
        assertTrue(!m.aktywny)
        m.start("x", listOf("a", "b"))
        m.dalej()
        assertEquals("Krok 1 z 2. a", m.wstecz())
        assertTrue(StepLogic.kontekst(m.stan.value!!).contains("1. a   <- użytkownik jest TU"))
    }
}
