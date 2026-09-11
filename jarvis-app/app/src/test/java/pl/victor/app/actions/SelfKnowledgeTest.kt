package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfKnowledgeTest {

    /** Dokładnie to pytanie padło w dzienniku z 21:55 (tura b873). */
    @Test
    fun `pytanie o persony z dziennika jest rozpoznane`() {
        assertEquals(SelfKnowledge.Topic.PERSONAS, SelfKnowledge.topicOf("jakie persony mamy dostępne"))
    }

    @Test
    fun `rozne sformulowania o personach`() {
        for (q in listOf(
            "jakie masz persony",
            "wymień persony",
            "lista person",
            "jakie są dostępne persony",
            "jakie masz style odpowiedzi"
        )) {
            assertEquals(q, SelfKnowledge.Topic.PERSONAS, SelfKnowledge.topicOf(q))
        }
    }

    @Test
    fun `pytania o funkcje`() {
        for (q in listOf("co potrafisz", "co umiesz?", "jakie masz funkcje", "jakie komendy")) {
            assertEquals(q, SelfKnowledge.Topic.CAPABILITIES, SelfKnowledge.topicOf(q))
        }
    }

    /**
     * To jest najważniejszy test w tym pliku. „Co widzisz" to najczęstsze
     * pytanie w całej aplikacji i MUSI uruchomić aparat, a nie wyliczankę
     * funkcji - przechwycenie go tutaj byłoby gorsze niż brak tej ścieżki.
     */
    @Test
    fun `pytania o obraz nie trafiaja do wiedzy o sobie`() {
        for (q in listOf("co widzisz", "co widzisz przede mną", "przeczytaj to", "co to jest")) {
            assertNull(q, SelfKnowledge.topicOf(q))
        }
    }

    @Test
    fun `zwykle pytania ida do modelu`() {
        for (q in listOf(
            "jaka jest pogoda",
            "ile to 2 + 2",
            "przejrzyj moje maile",
            "jakie mam spotkania jutro"
        )) {
            assertNull(q, SelfKnowledge.topicOf(q))
        }
    }

    @Test
    fun `pusta wypowiedz nic nie uruchamia`() {
        assertNull(SelfKnowledge.topicOf("   "))
    }
}
