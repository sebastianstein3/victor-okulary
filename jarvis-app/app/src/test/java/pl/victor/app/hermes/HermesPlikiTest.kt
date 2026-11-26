package pl.victor.app.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesPlikiTest {

    @Test
    fun `znacznik PLIK z odstepami w sciezce`() {
        val t = "Gotowe.\nPLIK: /Users/seb/Hermes/Raport za październik.pdf\nMiłego dnia"
        val p = HermesPliki.znajdź(t).single()
        assertEquals("/Users/seb/Hermes/Raport za październik.pdf", p.odnośnik)
        assertEquals("Raport za październik.pdf", p.nazwa)
        assertEquals(RodzajPliku.DOKUMENT, p.rodzaj)
        assertEquals("application/pdf", p.mime)
        assertFalse(p.zdalny)
    }

    @Test
    fun `sciezka w zdaniu bez znacznika`() {
        val p = HermesPliki.znajdź("Zapisałem wykres w /Users/seb/Hermes/wykres.png i jestem gotowy.").single()
        assertEquals("/Users/seb/Hermes/wykres.png", p.odnośnik)
        assertEquals(RodzajPliku.OBRAZ, p.rodzaj)
    }

    @Test
    fun `sciezka z tylda i kropka na koncu zdania`() {
        val p = HermesPliki.znajdź("Plik jest tu: ~/Hermes/dane.csv.").single()
        assertEquals("~/Hermes/dane.csv", p.odnośnik)
        assertEquals(RodzajPliku.DANE, p.rodzaj)
    }

    @Test
    fun `adres url z rozszerzeniem jest zdalny`() {
        val p = HermesPliki.znajdź("Obraz: https://cdn.example.com/a/b/kot.webp?sig=abc123").single()
        assertTrue(p.zdalny)
        assertEquals("kot.webp", p.nazwa)
        assertEquals("image/webp", p.mime)
    }

    @Test
    fun `adres url bez rozszerzenia to nie plik, chyba ze obrazek markdown`() {
        assertTrue(HermesPliki.znajdź("Zajrzyj na https://example.com/strona").isEmpty())
        val p = HermesPliki.znajdź("![kot](https://img.example.com/gen/8f3a2c)").single()
        assertEquals(RodzajPliku.OBRAZ, p.rodzaj)
        assertEquals("image/*", p.mime)
        assertTrue(p.zdalny)
    }

    @Test
    fun `sciezka wewnatrz adresu nie jest liczona drugi raz`() {
        val pliki = HermesPliki.znajdź("Pobierz https://example.com/dir/plik.pdf")
        assertEquals(1, pliki.size)
        assertTrue(pliki.single().zdalny)
    }

    @Test
    fun `kod zrodlowy i logi to nie pliki do otwarcia`() {
        assertTrue(HermesPliki.znajdź("Zmieniłem /Users/seb/proj/main.py oraz /var/log/system.log").isEmpty())
    }

    @Test
    fun `wyjscie poza folder jest odrzucane juz u nas`() {
        assertTrue(HermesPliki.znajdź("PLIK: /Users/seb/Hermes/../.ssh/klucz.txt").isEmpty())
        assertTrue(HermesPliki.podejrzana("/a/../b"))
        assertFalse(HermesPliki.podejrzana("/a/b/c.pdf"))
    }

    @Test
    fun `znacznik MEDIA i powtorzenia`() {
        val t = "MEDIA: /Users/seb/Hermes/a.png\nPLIK: /Users/seb/Hermes/a.png\nPLIK: /Users/seb/Hermes/b.mp3"
        val pliki = HermesPliki.znajdź(t)
        assertEquals(listOf("/Users/seb/Hermes/a.png", "/Users/seb/Hermes/b.mp3"), pliki.map { it.odnośnik })
        assertEquals(RodzajPliku.AUDIO, pliki[1].rodzaj)
    }

    @Test
    fun `znacznik z nieznanym rozszerzeniem to dokument do otwarcia`() {
        val p = HermesPliki.znajdź("PLIK: /Users/seb/Hermes/notatki.pages").single()
        assertEquals(RodzajPliku.DOKUMENT, p.rodzaj)
        assertEquals("application/octet-stream", p.mime)
    }

    @Test
    fun `limit plikow z jednej odpowiedzi`() {
        val t = (1..40).joinToString("\n") { "PLIK: /Users/seb/Hermes/p$it.png" }
        assertEquals(HermesPliki.LIMIT_PLIKÓW, HermesPliki.znajdź(t).size)
    }

    @Test
    fun `tekst bez plikow i pusty`() {
        assertTrue(HermesPliki.znajdź("").isEmpty())
        assertTrue(HermesPliki.znajdź("Dziś jest ładnie, a 5/6 zadań gotowe.").isEmpty())
    }

    @Test
    fun `dymek nie pokazuje linii ze znacznikami`() {
        val t = "Zrobione.\n\nPLIK: /Users/seb/Hermes/a.png\n\n\nDo zobaczenia."
        assertEquals("Zrobione.\n\nDo zobaczenia.", HermesPliki.bezZnaczników(t))
        assertEquals("Samo zdanie.", HermesPliki.bezZnaczników("Samo zdanie."))
    }

    @Test
    fun `typ po nazwie`() {
        assertEquals("application/pdf", HermesPliki.typPoNazwie("X.PDF"))
        assertNull(HermesPliki.typPoNazwie("bez-rozszerzenia"))
    }

    @Test
    fun `wskazowka glosowa mowi o znaczniku PLIK`() {
        assertTrue(HermesPliki.WSKAZÓWKA.contains("PLIK:"))
    }
}

class FiltrMowyTest {

    private fun przepuść(vararg kawałki: String): String {
        val f = FiltrMowy()
        return kawałki.joinToString("") { f.przepuść(it) } + f.koniec()
    }

    @Test
    fun `zwykly tekst przechodzi bez zmian`() {
        assertEquals("Cześć, mam wynik.\nDruga linia.", przepuść("Cześć, mam wynik.\nDruga linia."))
    }

    @Test
    fun `linia ze znacznikiem znika w calosci`() {
        assertEquals("Gotowe.\nMiłego dnia", przepuść("Gotowe.\nPLIK: /Users/seb/a b.pdf\nMiłego dnia"))
    }

    @Test
    fun `znacznik rozcięty miedzy kawalki`() {
        assertEquals("Gotowe.\nKoniec", przepuść("Gotowe.\nPL", "IK: /Users/seb/", "a.pdf\nKon", "iec"))
    }

    @Test
    fun `male i duze litery oraz odstep przed znacznikiem`() {
        assertEquals("A\nB", przepuść("A\n  media: /x/a.png\nB"))
    }

    @Test
    fun `slowo podobne do znacznika nie jest wycinane`() {
        assertEquals("Plik gotowy.\nMedia są wolne.", przepuść("Plik gotowy.\nMedia są wolne."))
        assertEquals("Pliki: dwa", przepuść("Pli", "ki: dwa"))
    }

    @Test
    fun `urwany poczatek linii wraca przy koncu odpowiedzi`() {
        assertEquals("Zrobione.\nPLI", przepuść("Zrobione.\nPLI"))
    }

    @Test
    fun `znacznik w srodku zdania nie jest wycinany`() {
        assertEquals("To jest PLIK: ważny", przepuść("To jest PLIK: ważny"))
    }
}

class HermesPlikiAdresTest {
    @Test
    fun `adres mostu z adresu bramy`() {
        assertEquals("http://192.168.1.10:8643", HermesPlikiAdres.pochodny("http://192.168.1.10:8642"))
        assertEquals("https://mac.tail1234.ts.net:8643", HermesPlikiAdres.pochodny("https://mac.tail1234.ts.net"))
        assertEquals("http://mac-mini.local:8643", HermesPlikiAdres.pochodny("mac-mini.local"))
    }

    @Test
    fun `zly adres bramy nie daje adresu mostu`() {
        assertNull(HermesPlikiAdres.pochodny(""))
        assertNull(HermesPlikiAdres.pochodny("http://example.com:8642"))
    }

    @Test
    fun `nazwa pliku w pamieci podrecznej jest bezpieczna`() {
        assertEquals("passwd", HermesPlikiAdres.bezpiecznaNazwa("../../etc/passwd"))
        assertEquals("a_b.pdf", HermesPlikiAdres.bezpiecznaNazwa("a|b.pdf"))
        assertEquals("ukryty", HermesPlikiAdres.bezpiecznaNazwa(".ukryty"))
        assertEquals("plik", HermesPlikiAdres.bezpiecznaNazwa("///"))
        assertTrue(HermesPlikiAdres.bezpiecznaNazwa("x".repeat(300)).length <= 80)
        assertEquals("Raport październik.pdf", HermesPlikiAdres.bezpiecznaNazwa("Raport październik.pdf"))
    }

    @Test
    fun `rozszerzenie po typie`() {
        assertEquals("png", HermesPlikiAdres.rozszerzeniePoTypie("image/png; charset=binary"))
        assertNull(HermesPlikiAdres.rozszerzeniePoTypie("application/x-nieznany"))
    }
}
