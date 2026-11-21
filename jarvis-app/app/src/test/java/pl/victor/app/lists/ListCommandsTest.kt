package pl.victor.app.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.victor.app.lists.ListCommands.Command
import pl.victor.app.lists.UserLists.Entry

/**
 * Polecenia list w warstwie 0 - polska odmiana i liczby. Fałszywe trafienie
 * zabiera zdanie modelowi, więc pilnujemy też tego, czego łapać NIE wolno.
 */
class ListCommandsTest {

    private fun parse(t: String) = ListCommands.parse(t)

    @Test
    fun `dodawanie do listy zakupow w roznych odmianach`() {
        val expected = Command.Add(UserLists.SHOPPING, listOf(Entry("mleko")))
        assertEquals(expected, parse("dodaj mleko do listy zakupów"))
        assertEquals(expected, parse("Dodaj mleko na listę zakupów."))
        assertEquals(expected, parse("dopisz mleko do zakupów"))
        assertEquals(expected, parse("dodaj mleko do listy"))
        assertEquals(expected, parse("dodaj do listy zakupów mleko"))
        assertEquals(expected, parse("lista zakupów: mleko"))
        assertEquals(expected, parse("dodaj mleko do listy zakupow"))
    }

    @Test
    fun `kilka pozycji i liczby`() {
        assertEquals(
            Command.Add(UserLists.SHOPPING, listOf(Entry("mleka", 2), Entry("chleb"), Entry("masło"))),
            parse("dodaj 2 mleka, chleb i masło do listy zakupów")
        )
        assertEquals(
            Command.Add(UserLists.SHOPPING, listOf(Entry("chleby", 2), Entry("bułek", 5))),
            parse("dodaj dwa chleby oraz pięć bułek do listy zakupów")
        )
        assertEquals(Entry("jajek", 10), ListCommands.parseEntry("10 jajek"))
        assertEquals(Entry("mleko", null), ListCommands.parseEntry("mleko"))
        assertEquals(Entry("dwa", null), ListCommands.parseEntry("dwa"))
    }

    @Test
    fun `nazwane listy`() {
        assertEquals(
            Command.Add("na wyjazd", listOf(Entry("krem do opalania"))),
            parse("dodaj krem do opalania do listy na wyjazd")
        )
        assertEquals(Command.Show("na wyjazd"), parse("co mam na liście na wyjazd?"))
        assertEquals(Command.Add(UserLists.TASKS, listOf(Entry("zadzwonić do banku"))), parse("dodaj zadzwonić do banku do listy zadań"))
        assertEquals(Command.Create("na wyjazd"), parse("utwórz listę na wyjazd"))
        assertEquals(Command.Create("prezenty"), parse("nowa lista prezenty"))
    }

    @Test
    fun `odczyt listy`() {
        assertEquals(Command.Show(UserLists.SHOPPING), parse("co mam na liście zakupów?"))
        assertEquals(Command.Show(UserLists.SHOPPING), parse("przeczytaj mi listę zakupów"))
        assertEquals(Command.Show(UserLists.SHOPPING), parse("co mam kupić"))
        assertEquals(Command.Show(UserLists.SHOPPING), parse("lista zakupów"))
        assertEquals(Command.Show(UserLists.TASKS), parse("co jest na liście zadań"))
        assertEquals(Command.ShowAll, parse("jakie mam listy"))
    }

    @Test
    fun `usuwanie odhaczanie czyszczenie`() {
        assertEquals(Command.Remove(UserLists.SHOPPING, listOf("chleb")), parse("usuń chleb z listy zakupów"))
        assertEquals(Command.Remove(null, listOf("chleb")), parse("usuń chleb z listy"))
        assertEquals(Command.CheckOff(null, listOf("chleb")), parse("odhacz chleb"))
        assertEquals(Command.CheckOff(UserLists.SHOPPING, listOf("chleb", "mleko")), parse("odhacz chleb i mleko z listy zakupów"))
        assertEquals(Command.CheckOff(null, listOf("chleb")), parse("zaznacz chleb jako kupiony"))
        assertEquals(Command.CheckOff(null, listOf("chleb"), onlyIfPresent = true), parse("kupiłem chleb"))
        assertEquals(Command.Clear(UserLists.SHOPPING), parse("wyczyść listę zakupów"))
        assertEquals(Command.Clear("na wyjazd"), parse("usuń listę na wyjazd"))
    }

    @Test
    fun `nie lapie zdan ktore nie sa poleceniami list`() {
        listOf(
            "dodaj spotkanie jutro o 10",
            "zapisz, że mam oddać książkę",
            "usuń notatkę",
            "co mam do zrobienia",
            "kupiłem wczoraj nowy telefon i nie wiem jak go ustawić",
            "jaka jest pogoda",
            "dodaj kupić mleko",
            "odpowiedz na ostatni mail od Ani"
        ).forEach { assertNull(it, parse(it)) }
    }

    @Test
    fun `nazwy list sprowadzone do kanonicznych`() {
        assertEquals(UserLists.SHOPPING, ListCommands.canonicalListName("listy zakupów"))
        assertEquals(UserLists.SHOPPING, ListCommands.canonicalListName("liście zakupowej"))
        assertEquals(UserLists.SHOPPING, ListCommands.canonicalListName("zakupy"))
        assertEquals(UserLists.TASKS, ListCommands.canonicalListName("liście zadań"))
        assertEquals(UserLists.TASKS, ListCommands.canonicalListName("listy rzeczy do zrobienia"))
        assertEquals("na wyjazd", ListCommands.canonicalListName("listy na wyjazd"))
        assertEquals(UserLists.SHOPPING, ListCommands.canonicalListName("listy"))
    }
}
