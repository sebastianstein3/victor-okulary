package pl.victor.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trzy pułapki, przez które ta funkcja byłaby gorsza niż jej brak.
 *
 * Zgłoszenie, które ją wywołało: "okulary przestały reagować na hej lens i na
 * kliknięcie (...) okazało się, że w ustawieniach Bluetooth się rozłączyły".
 * Trzeci raz to samo - a asystent, który gada bez sensu, zostaje wyłączony po
 * drugim dniu i wtedy nie powie już nic także wtedy, gdy będzie miał rację.
 */
class ConnectionAnnouncerTest {

    @Test
    fun `nie oglasza utraty przy starcie aplikacji`() {
        // Stan początkowy to DISCONNECTED. Naiwny warunek wita tu użytkownika
        // informacją o rozłączeniu urządzenia, którego nigdy nie miał.
        val a = ConnectionAnnouncer()
        assertFalse(
            "nic nie zostało utracone - okulary nigdy nie były połączone",
            a.czyOgłosićUtratę()
        )
    }

    @Test
    fun `nie oglasza powrotu po pierwszym polaczeniu`() {
        val a = ConnectionAnnouncer()
        assertFalse(
            "\"znów połączone\" po pierwszym w życiu połączeniu nie ma sensu",
            a.czyOgłosićPowrót()
        )
    }

    @Test
    fun `oglasza utrate dopiero po prawdziwym polaczeniu`() {
        val a = ConnectionAnnouncer()
        a.czyOgłosićPowrót() // pierwsze połączenie
        assertTrue("to jest prawdziwa utrata - było co stracić", a.czyOgłosićUtratę())
    }

    @Test
    fun `nie powtarza sie przy migotaniu lacza`() {
        val a = ConnectionAnnouncer()
        a.czyOgłosićPowrót()
        assertTrue(a.czyOgłosićUtratę())
        assertFalse("drugie ogłoszenie tej samej utraty to gadanie w kółko", a.czyOgłosićUtratę())
        assertFalse("i trzecie też", a.czyOgłosićUtratę())
    }

    @Test
    fun `powrot oglasza sie tylko po oglosozonej utracie`() {
        val a = ConnectionAnnouncer()
        a.czyOgłosićPowrót()
        a.czyOgłosićUtratę()
        assertTrue("o tej utracie człowiek wie, więc powrót go interesuje", a.czyOgłosićPowrót())
        assertFalse("drugi powrót bez utraty pomiędzy - cisza", a.czyOgłosićPowrót())
    }

    @Test
    fun `krotkie migniecie bez ogloszonej utraty nie daje powrotu`() {
        // Karencja po stronie wołającego sprawia, że przy chwilowym zerwaniu
        // czyOgłosićUtratę() w ogóle nie zostanie zawołane. Wtedy powrót też ma
        // być cichy - inaczej z niczego robi się komunikat.
        val a = ConnectionAnnouncer()
        a.czyOgłosićPowrót()
        assertFalse("nie ogłoszono utraty, więc nie ma czego odwoływać", a.czyOgłosićPowrót())
    }

    @Test
    fun `swiadome rozlaczenie nie zostawia dlugu`() {
        val a = ConnectionAnnouncer()
        a.czyOgłosićPowrót()
        a.czyOgłosićUtratę()
        a.zapomnij()
        assertFalse("po ręcznym rozłączeniu powrót nie jest niespodzianką", a.czyOgłosićPowrót())
    }
}
