package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Odpowiedź ucięta na limicie tokenów nie ma prawa pokazać znacznika.
 *
 * ## Zrzut ekranu z 21 września
 * Na ekranie stało dosłownie to, co niżej w teście: zdanie o trasie, a pod nim
 * `[[ACTION: type=app_task kind="TRANSIT_` - bez nawiasów zamykających, bo
 * model przestał pisać w środku znacznika.
 *
 * Filtr znaczników wymagał nawiasów ZAMYKAJĄCYCH, więc takiego ogona nie
 * ruszał. Szedł on prosto na ekran i do syntezatora.
 *
 * Skutek jest podwójny: człowiek słyszy techniczny bełkot, a akcja i tak się
 * nie wykonuje, bo niedokończonego znacznika nie da się sparsować. Asystent
 * obiecuje trasę i nie robi nic - zgłoszone jako "zamiast pokazać trasę
 * wyświetla się dziwny komunikat".
 */
class TruncatedMarkerTest {

    private val detector = SmartActionDetector()

    @Test
    fun `uciety znacznik ze zrzutu nie trafia do uzytkownika`() {
        val zEkranu = "Już szukam połączenia komunikacją miejską na ulicę Polną " +
            "sto czterdzieści be. Zaraz wyświetlę Ci trasę dojazdu.\n\n" +
            "[[ACTION: type=app_task kind=\"TRANSIT_"

        val (tekst, akcje) = detector.detectAiMarkedActions(zEkranu)

        assertFalse("na ekran poszedł ogon znacznika: \"$tekst\"", tekst.contains("[["))
        assertFalse("na ekran poszło słowo ACTION: \"$tekst\"", tekst.contains("ACTION"))
        assertFalse("na ekran poszła nazwa akcji: \"$tekst\"", tekst.contains("app_task"))
        assertTrue(
            "zdanie dla człowieka ma zostać nietknięte",
            tekst.startsWith("Już szukam połączenia")
        )
        // Akcji NIE MA i to jest poprawne: z niedokończonego znacznika nie da
        // się odczytać celu. Test utrwala, że nie zgadujemy - lepiej nie zrobić
        // nic, niż pojechać w przypadkowe miejsce.
        assertEquals("z uciętego znacznika nie wolno zgadywać akcji", 0, akcje.size)
    }

    @Test
    fun `kompletny znacznik dalej dziala`() {
        // Strażnik przeciw naprawie, która psuje działającą drogę.
        val pelny = "Włączam nawigację.\n[[ACTION: type=app_task kind=\"TRANSIT_PLAN\"]]"
        val (tekst, akcje) = detector.detectAiMarkedActions(pelny)
        assertFalse(tekst.contains("ACTION"))
        assertEquals("kompletny znacznik ma dalej dawać akcję", 1, akcje.size)
    }

    @Test
    fun `nawias kwadratowy w srodku zdania zostaje`() {
        // Wzorzec celuje w ogon na KOŃCU tekstu. Zwykły nawias w treści
        // odpowiedzi nie ma prawa zniknąć.
        val zdanie = "Cena to 20 zł [promocja do piątku] za sztukę."
        val (tekst, _) = detector.detectAiMarkedActions(zdanie)
        assertTrue(
            "nawias w środku zdania zniknął: \"$tekst\"",
            tekst.contains("[promocja do piątku]")
        )
    }

    @Test
    fun `uciety znacznik bez podwojnego nawiasu tez znika`() {
        // Modele piszą znacznik różnie - jednym nawiasem też się zdarza.
        val zEkranu = "Robi się.\n[ACTION: type=navigate destination=\"Poz"
        val (tekst, _) = detector.detectAiMarkedActions(zEkranu)
        assertFalse("ogon z jednym nawiasem został: \"$tekst\"", tekst.contains("ACTION"))
    }
}
