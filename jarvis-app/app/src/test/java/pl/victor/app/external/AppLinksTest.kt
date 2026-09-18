package pl.victor.app.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.external.AppLinks.isPlainLaunch

/**
 * Adresy głębokie cudzych aplikacji są NIEPEWNE - i ten test pilnuje tego, co
 * z tej niepewności wynika, a nie samych adresów.
 *
 * Nie da się sprawdzić bez telefonu, czy "shazam://autoshazam" cokolwiek robi.
 * Da się sprawdzić, że lista prób nigdy nie kończy się ślepo: na końcu zawsze
 * stoi zwykłe uruchomienie aplikacji, a każda próba ma zdanie, które mówi
 * PRAWDĘ o tym, co się udało.
 */
class AppLinksTest {

    @Test
    fun `kazde zadanie konczy sie zwyklym uruchomieniem`() {
        // To jest warunek "cicha porażka niemożliwa": gdyby ostatnia próba też
        // była adresem, nieudane wszystkie dałyby milczenie.
        for (attempts in listOf(
            AppLinks.jakdojade("dworzec"),
            AppLinks.shazam(),
            AppLinks.ride("Kraków"),
            AppLinks.yanosik()
        )) {
            assertTrue("ostatnia próba musi być zwykłym uruchomieniem",
                attempts.last().isPlainLaunch())
        }
    }

    @Test
    fun `kazda proba ma zdanie do wypowiedzenia`() {
        for (attempts in listOf(
            AppLinks.jakdojade("dworzec"), AppLinks.shazam(),
            AppLinks.ride("Kraków"), AppLinks.yanosik()
        )) {
            for (a in attempts) {
                assertTrue("pusty opis próby: $a", a.describe.isNotBlank())
            }
        }
    }

    @Test
    fun `zwykle uruchomienie NIE obiecuje wykonania zadania`() {
        // Zdanie po otwarciu samej aplikacji musi różnić się od zdania po
        // udanym adresie - inaczej użytkownik czeka na coś, co się nie stało.
        val plan = AppLinks.jakdojade("dworzec")
        val deep = plan.first().describe
        val plain = plan.last().describe
        assertTrue("adres głęboki ma nieść cel: $deep", deep.contains("dworzec"))
        // Sprawdzamy INTENCJĘ, nie jedno słowo. Poprzednia wersja wymagała
        // dosłownego "sam" i wywróciła się, gdy komunikat powiedział to samo
        // dokładniej ("celu nie wpiszę za Ciebie"). Test pilnujący sformułowania
        // zamiast znaczenia każe poprawiać siebie przy każdym lepszym zdaniu.
        assertTrue(
            "otwarcie aplikacji ma mówić, że celu NIE wpisujemy: $plain",
            listOf("sam", "nie wpiszę", "wpisz").any { plain.contains(it, ignoreCase = true) }
        )
        assertFalse(
            "otwarcie aplikacji nie może brzmieć jak wykonane zadanie: $plain",
            plain.contains("z celem")
        )
    }

    @Test
    fun `geo jest pierwsze, bo to standard Androida`() {
        val first = AppLinks.jakdojade("plac zamkowy").first()
        assertTrue(first.uri!!.startsWith("geo:"))
        // Wartość SPRAWDZONA w Google Play, nie zgadnięta. Poprzednio stało tu
        // "pl.jakdojade" - nazwa, którą wymyśliłem, bo brzmiała sensownie. Test
        // przechodził i utrwalał błąd, przez który aplikacja meldowała brak
        // Jakdojade, stojąc obok jego ikony.
        assertEquals("com.citynav.jakdojade.pl.android", first.packageName)
    }

    @Test
    fun `kazda proba wskazujaca aplikacje niesie tez jej nazwe`() {
        // Nazwa z pulpitu jest jedynym zapasem, gdy nazwa pakietu nie trafia -
        // a raz już nie trafiła. Próba bez niej nie miałaby jak się podnieść.
        val wszystkie = AppLinks.jakdojade("dworzec") + AppLinks.shazam() +
            AppLinks.ride("lotnisko") + AppLinks.yanosik()
        val bezNazwy = wszystkie.filter { it.packageName != null && it.label == null }
        assertTrue("próby z pakietem, ale bez nazwy zapasowej: $bezNazwy", bezNazwy.isEmpty())
    }

    @Test
    fun `cel jest zakodowany do adresu`() {
        val uris = AppLinks.jakdojade("plac zamkowy").mapNotNull { it.uri }
        assertTrue("spacja musi być zakodowana: $uris",
            uris.all { !it.contains(" ") })
        assertTrue(uris.any { it.contains("plac%20zamkowy") || it.contains("plac+zamkowy") })
    }

    @Test
    fun `polskie znaki w celu przezywaja`() {
        val uris = AppLinks.ride("Świętokrzyska").mapNotNull { it.uri }
        assertTrue(uris.all { !it.contains("Ś") })
    }

    @Test
    fun `kurs probuje dwoch przewoznikow`() {
        val describes = AppLinks.ride("Kraków").map { it.describe }
        assertTrue(describes.any { it.contains("Uber") })
        assertTrue(describes.any { it.contains("Bolt") })
    }

    @Test
    fun `shazam probuje najpierw jawnej akcji`() {
        val first = AppLinks.shazam().first()
        assertEquals("com.shazam.android", first.packageName)
        assertTrue(first.action!!.contains("TAGGING"))
    }

    @Test
    fun `yanosik nie udaje, ze zna cel`() {
        // Nie znam dla niego adresu z celem i lista ma to odzwierciedlać,
        // zamiast zawierać zgadywany adres, który po cichu nic nie zrobi.
        val attempts = AppLinks.yanosik()
        assertEquals(1, attempts.size)
        assertTrue(attempts.single().isPlainLaunch())
    }
}
