package pl.victor.app.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.actions.ActionType
import pl.victor.app.actions.CommandCatalog

/**
 * Hamulec trybów ciągłych - jedyna komenda, która MUSI działać bez sieci.
 *
 * Tryby dostępności chodzą w pętli i pytają model kilkadziesiąt razy na minutę
 * po circa 1600 tokenów. Dotąd dawały się zatrzymać wyłącznie przez model, a
 * wzorce zapasowe uruchamiają się tylko wtedy, gdy AI jest niedostępne - czyli
 * gdy sieć padła, pętli nie dało się wyłączyć głosem WCALE.
 *
 * Do tego katalog komend uczył mówić "Dziękuję, wystarczy" i "Przestań czytać",
 * a lista wzorców znała "stop czytanie" i "wyłącz tryb". Człowiek mówił zdanie
 * z instrukcji i nic się nie działo.
 */
class StopAccessibilityTest {

    @Test
    fun `kazde zdanie z katalogu zatrzymuje tryb lokalnie`() {
        val przyklady = CommandCatalog.ALL
            .filter { it.type == ActionType.STOP_ACCESSIBILITY }
            .flatMap { it.examples }
        assertTrue("katalog nie ma przykładów dla wyłączenia trybu", przyklady.isNotEmpty())

        val nieznane = przyklady.filterNot { MetaCommands.stopsAccessibility(it) }
        assertTrue(
            "katalog uczy zdań, których hamulec nie rozpoznaje: $nieznane",
            nieznane.isEmpty()
        )
    }

    @Test
    fun `dziala tez w zdaniu, nie tylko jako samo haslo`() {
        // Człowiek, któremu okulary czytają do ucha, rzadko mówi samo hasło.
        listOf(
            "dobra, dziękuję, wystarczy",
            "okej przestań czytać",
            "już wystarczy",
            "zatrzymaj tryb czytania"
        ).forEach {
            assertTrue("„$it” powinno zatrzymać tryb", MetaCommands.stopsAccessibility(it))
        }
    }

    @Test
    fun `nie lapie zwyklej rozmowy`() {
        // Ten warunek jest słabszy, niż się wydaje, i to jest ZAMIERZONE:
        // wołający sprawdza hamulec DOPIERO gdy tryb ciągły chodzi. Poza trybem
        // te same słowa idą do modelu. Pilnujemy tu tylko zdań, które nawet w
        // trybie nie mogą go gasić.
        listOf(
            "jaka jutro pogoda",
            "co to za budynek",
            "przeczytaj to jeszcze raz"
        ).forEach {
            assertFalse("„$it” nie może gasić trybu", MetaCommands.stopsAccessibility(it))
        }
    }
}
