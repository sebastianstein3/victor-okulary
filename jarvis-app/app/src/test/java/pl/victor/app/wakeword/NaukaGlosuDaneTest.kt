package pl.victor.app.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Wybór okien z nagrań, wstęp, profil i pliki - bez Androida. */
class NaukaGlosuDaneTest {

    private fun okno(nr: Int, wynik: Float, d: Int = 8) = OknoCech(FloatArray(d) { nr.toFloat() }, wynik)

    @Test
    fun `wstep konczy sie odbiciem poczatku nagrania - bez skoku na laczeniu`() {
        val probki = ShortArray(5 * OwwPipeline.CHUNK) { (it % 1000).toShort() }
        val z = NaukaGlosuDane.zWstepem(probki)
        val wstep = NaukaGlosuDane.WSTEP_PORCJI * OwwPipeline.CHUNK
        assertEquals(wstep + probki.size, z.size)
        // Ostatnie próbki wstępu to początek nagrania w odwrotnej kolejności.
        assertEquals(probki[0], z[wstep - 1])
        assertEquals(probki[1], z[wstep - 2])
        assertEquals(probki[0], z[wstep])
        // Wstęp bierze tylko z pierwszych porcji (cisza przed "Mów teraz").
        val wzor = NaukaGlosuDane.WZOR_WSTEPU_PORCJI * OwwPipeline.CHUNK
        val dozwolone = probki.copyOf(wzor).toSet()
        assertTrue((0 until wstep).all { z[it] in dozwolone })
    }

    @Test
    fun `wstep krotkiego nagrania nie wywraca`() {
        val z = NaukaGlosuDane.zWstepem(ShortArray(10) { it.toShort() })
        assertEquals(NaukaGlosuDane.WSTEP_PORCJI * OwwPipeline.CHUNK + 10, z.size)
        assertEquals(0, NaukaGlosuDane.zWstepem(ShortArray(0)).size)
    }

    @Test
    fun `pozytyw - okno z najwyzszym wynikiem i sasiedzi, bez okien poczatkowych`() {
        // Najwyższy wynik w oknie 3 (sztuczne początkowe) - ma być pominięty.
        val okna = List(30) { i ->
            okno(i, when (i) { 3 -> 0.99f; 20 -> 0.8f; 19, 21 -> 0.6f; else -> 0.01f })
        }
        val w = NaukaGlosuDane.wybierzPozytywne(okna, koniecMowyMs = 1500)!!
        assertEquals(20, w.glowne)
        assertEquals(listOf(18, 19, 20, 21, 22), w.indeksy)
        assertTrue(w.wedlugWyniku)
        assertEquals(0.8f, w.maksWynik, 0f)
    }

    @Test
    fun `pozytyw przy krawedzi - sasiedzi przycieci`() {
        val okna = List(15) { i -> okno(i, if (i == 14) 0.9f else 0f) }
        val w = NaukaGlosuDane.wybierzPozytywne(okna, 1000)!!
        assertEquals(listOf(12, 13, 14), w.indeksy)
    }

    @Test
    fun `model ogolny nie slyszy glosu - okno wyrownane do konca mowy`() {
        val okna = List(44) { i -> okno(i, 0.001f) }
        val w = NaukaGlosuDane.wybierzPozytywne(okna, koniecMowyMs = 1600)!!
        assertFalse(w.wedlugWyniku)
        // Koniec mowy 1,6 s + 0,2 s = porcja 16 + 22 = 38 -> okno 38 - 15 = 23.
        assertEquals(23, w.glowne)
    }

    @Test
    fun `za krotkie nagranie - brak pozytywu`() {
        assertNull(NaukaGlosuDane.wybierzPozytywne(List(10) { okno(it, 1f) }, 500))
    }

    @Test
    fun `negatywy co drugie okno po pominieciu poczatkowych`() {
        val okna = List(20) { okno(it, 0f) }
        val neg = NaukaGlosuDane.wybierzNegatywne(okna)
        assertEquals(listOf(10f, 12f, 14f, 16f, 18f), neg.map { it[0] })
    }

    @Test
    fun `zbior - grupy, okna glowne, pominiete nagrania`() {
        val ocena = OcenaNagrania.ocenFraze(ShortArray(0))
        val frazy = listOf(
            NaukaGlosuDane.NagranieFrazy(List(30) { okno(it, if (it == 20) 0.9f else 0.1f) }, ocena),
            NaukaGlosuDane.NagranieFrazy(List(5) { okno(it, 0.9f) }, ocena),
            NaukaGlosuDane.NagranieFrazy(List(30) { okno(it, if (it == 25) 0.7f else 0.1f) }, ocena)
        )
        val z = NaukaGlosuDane.zbuduj(frazy, List(30) { okno(100 + it, 0f) })
        assertEquals(1, z.pominiete)
        assertEquals(2, z.nagranFrazy)
        assertEquals(10, z.pozytywow)
        assertEquals(10, z.negatywow)
        assertEquals(setOf(0, 2), z.przyklady.filter { it.pozytywny }.map { it.grupa }.toSet())
        assertEquals(2, z.przyklady.count { it.pozytywny && it.glowny })
        assertTrue(z.przyklady.filter { !it.pozytywny }.all { it.grupa == NaukaGlosuDane.GRUPA_TLA })
    }

    @Test
    fun `prog jakosci walidacji`() {
        assertFalse(NaukaGlosuDane.czyWystarczajaca(null))
        assertTrue(NaukaGlosuDane.czyWystarczajaca(WeryfikatorGlosu.Walidacja(8, 0.9f, 0.02f)))
        assertFalse(NaukaGlosuDane.czyWystarczajaca(WeryfikatorGlosu.Walidacja(8, 0.4f, 0.0f)))
        assertFalse(NaukaGlosuDane.czyWystarczajaca(WeryfikatorGlosu.Walidacja(8, 0.6f, 0.5f)))
    }

    private fun profil(neg: Int = 5, d: Int = 8): ProfilGlosu {
        val p = List(4) { WeryfikatorGlosu.Przyklad(FloatArray(d) { 1f }, true, grupa = it, glowny = it % 2 == 0) } +
            List(neg) { n -> WeryfikatorGlosu.Przyklad(FloatArray(d) { -n.toFloat() }, false, grupa = NaukaGlosuDane.GRUPA_TLA) }
        return ProfilGlosu(p, 4, 1, 25_000, "telefon", 123L, 0, WeryfikatorGlosu.Walidacja(4, 0.75f, 0.01f))
    }

    @Test
    fun `profil - zapis i odczyt`() {
        val p = profil()
        val o = ProfilGlosu.zBajtow(p.doBajtow())!!
        assertEquals(p.przyklady.size, o.przyklady.size)
        assertEquals(4, o.pozytywow)
        assertEquals(5, o.negatywow)
        assertEquals("telefon", o.mikrofon)
        assertEquals(25_000, o.tloMs)
        assertEquals(1, o.odrzuconychNagran)
        assertEquals(0.75f, o.walidacja!!.trafnoscPozytywow, 0f)
        for (i in p.przyklady.indices) {
            assertTrue(p.przyklady[i].cechy.contentEquals(o.przyklady[i].cechy))
            assertEquals(p.przyklady[i].glowny, o.przyklady[i].glowny)
            assertEquals(p.przyklady[i].grupa, o.przyklady[i].grupa)
        }
        assertNull(ProfilGlosu.zBajtow(ByteArray(3)))
    }

    @Test
    fun `korekta dopisuje negatywy z waga i pilnuje limitu`() {
        val p = profil(neg = ProfilGlosu.MAX_NEGATYWOW)
        val k = p.zKorekta(List(3) { FloatArray(8) { 9f } })
        assertEquals(1, k.korekt)
        assertEquals(ProfilGlosu.MAX_NEGATYWOW, k.negatywow)
        assertEquals(4, k.pozytywow)
        val korekty = k.przyklady.filter { it.grupa == NaukaGlosuDane.GRUPA_KOREKT }
        assertEquals(3, korekty.size)
        assertTrue(korekty.all { it.waga == ProfilGlosu.WAGA_KOREKTY && !it.pozytywny })
        // Odpadły najstarsze okna tła (cechy -0, -1, -2).
        assertEquals(-3f, k.przyklady.first { !it.pozytywny }.cechy[0], 0f)
    }

    @Test
    fun `magazyn - zapis, odczyt, usuniecie`() {
        val katalog = File(Files.createTempDirectory("glos").toFile(), MagazynGlosu.KATALOG)
        val m = MagazynGlosu(katalog)
        assertFalse(m.istnieje())
        assertNull(m.wczytajWeryfikator())
        assertNull(m.wczytajProfil())

        val p = profil(neg = 20)
        val model = WeryfikatorGlosu.naucz(p.przyklady)
        m.zapisz(p, model)
        assertTrue(m.istnieje())
        val w = m.wczytajWeryfikator()
        assertNotNull(w)
        assertEquals(model.ocen(p.przyklady[0].cechy), w!!.ocen(p.przyklady[0].cechy), 0f)
        assertEquals(24, m.wczytajProfil()!!.przyklady.size)
        assertFalse(File(katalog, MagazynGlosu.PLIK_WERYFIKATORA + ".tmp").exists())

        // Nadpisanie działa (zmiana nazwy na istniejący plik).
        m.zapisz(p.zKorekta(listOf(FloatArray(8))), model)
        assertEquals(1, m.wczytajProfil()!!.korekt)

        assertTrue(m.usun())
        assertFalse(katalog.exists())
        assertFalse(m.istnieje())
        assertTrue(m.usun())
    }
}
