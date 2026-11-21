package pl.victor.app.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.wakeword.DecyzjaWybudzenia.Decyzja

/** Tablica decyzji: wynik ogólny x wynik weryfikatora x "Tylko mój głos". */
class DecyzjaWybudzeniaTest {

    private fun d(wynik: Float, wer: Float?, tylkoMoj: Boolean = false) =
        DecyzjaWybudzenia.ocen(wynik, wer, tylkoMoj)

    @Test
    fun `bez weryfikatora dziala jak dotad - prog 0,5`() {
        assertEquals(Decyzja.BRAK, d(0.49f, null))
        assertEquals(Decyzja.OGOLNY, d(0.5f, null))
        assertEquals(Decyzja.OGOLNY, d(0.99f, null))
        // "Tylko mój głos" bez nagrań nic nie zmienia.
        assertEquals(Decyzja.OGOLNY, d(0.7f, null, tylkoMoj = true))
    }

    @Test
    fun `potwierdzony glos budzi juz od niskiego progu`() {
        assertEquals(Decyzja.WLASCICIEL, d(0.3f, 0.5f))
        assertEquals(Decyzja.WLASCICIEL, d(0.42f, 0.9f))
        assertEquals(Decyzja.WLASCICIEL, d(0.8f, 0.9f))
        assertEquals(Decyzja.BRAK, d(0.29f, 0.99f))
    }

    @Test
    fun `wysoki wynik ogolny budzi, chyba ze weryfikator mocno odrzuca`() {
        assertEquals(Decyzja.OGOLNY, d(0.6f, 0.3f))
        assertEquals(Decyzja.OGOLNY, d(0.6f, 0.1f))
        assertEquals(Decyzja.ODRZUCONE, d(0.6f, 0.09f))
        assertEquals(Decyzja.ODRZUCONE, d(0.99f, 0.0f))
    }

    @Test
    fun `sredni wynik bez potwierdzenia nie budzi`() {
        assertEquals(Decyzja.BRAK, d(0.4f, 0.49f))
        assertEquals(Decyzja.BRAK, d(0.4f, 0.01f))
    }

    @Test
    fun `tylko moj glos - bez potwierdzenia nie budzi nawet przy wysokim wyniku`() {
        assertEquals(Decyzja.ODRZUCONE_TYLKO_MOJ, d(0.9f, 0.3f, tylkoMoj = true))
        assertEquals(Decyzja.WLASCICIEL, d(0.35f, 0.6f, tylkoMoj = true))
        assertEquals(Decyzja.BRAK, d(0.4f, 0.3f, tylkoMoj = true))
    }

    @Test
    fun `flagi decyzji i progi spojne`() {
        assertTrue(Decyzja.OGOLNY.budzi)
        assertTrue(Decyzja.WLASCICIEL.budzi)
        assertFalse(Decyzja.ODRZUCONE.budzi)
        assertFalse(Decyzja.ODRZUCONE_TYLKO_MOJ.budzi)
        assertFalse(Decyzja.BRAK.budzi)
        assertTrue(DecyzjaWybudzenia.PROG_NISKI < OwwPipeline.DEFAULT_THRESHOLD)
        assertTrue(DecyzjaWybudzenia.PROG_MOCNEGO_ODRZUCENIA < DecyzjaWybudzenia.PROG_POTWIERDZENIA)
        assertFalse(DecyzjaWybudzenia.potrzebnyWeryfikator(0.29f))
        assertTrue(DecyzjaWybudzenia.potrzebnyWeryfikator(0.3f))
    }
}
