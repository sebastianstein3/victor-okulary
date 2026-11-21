package pl.victor.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.conversation.MetaCommands
import pl.victor.app.live.LiveConversationLogic.Dostępność
import pl.victor.app.live.LiveConversationLogic.TrybDźwięku

class LiveConversationLogicTest {

    @Test
    fun `dostepnosc - tylko darmowe wygrywa, potem przelacznik, klucz, mikrofon`() {
        fun d(free: Boolean = false, on: Boolean = true, key: String? = "k", mic: Boolean = true) =
            LiveConversationLogic.dostępność(free, on, key, mic)
        assertEquals(Dostępność.MOŻNA, d())
        assertEquals(Dostępność.TYLKO_DARMOWE, d(free = true, on = false, key = null))
        assertEquals(Dostępność.WYŁĄCZONA, d(on = false, key = null))
        assertEquals(Dostępność.BRAK_KLUCZA, d(key = null))
        assertEquals(Dostępność.BRAK_KLUCZA, d(key = "  "))
        assertEquals(Dostępność.BRAK_MIKROFONU, d(mic = false))
        // Każda odmowa ma komunikat dla człowieka, "można" - nie.
        Dostępność.values().filter { it != Dostępność.MOŻNA }.forEach { assertTrue(it.komunikat!!.isNotBlank()) }
        assertNull(Dostępność.MOŻNA.komunikat)
        assertTrue(Dostępność.TYLKO_DARMOWE.komunikat!!.contains("jak zwykle"))
    }

    @Test
    fun `droga dzwieku - SCO tylko gdy lacze naprawde stoi`() {
        assertEquals(TrybDźwięku.SCO, LiveConversationLogic.wybierzDźwięk(true, true, true).tryb)
        val bezZestawu = LiveConversationLogic.wybierzDźwięk(false, false, false)
        assertEquals(TrybDźwięku.TELEFON, bezZestawu.tryb)
        assertTrue(bezZestawu.powód.contains("brak zestawu"))
        assertTrue(LiveConversationLogic.wybierzDźwięk(true, null, false).powód.contains("nie odpowiedziało"))
        assertTrue(LiveConversationLogic.wybierzDźwięk(true, false, false).powód.contains("odmówione"))
        val niewstało = LiveConversationLogic.wybierzDźwięk(true, true, false)
        assertEquals(TrybDźwięku.TELEFON, niewstało.tryb)
        assertTrue(niewstało.powód.contains("nie wstało"))
    }

    @Test
    fun `cisza - konczy po dwoch minutach, nigdy w trakcie odpowiedzi`() {
        val limit = LiveConversationLogic.CISZA_KOŃCZY_MS
        assertEquals(120_000L, limit)
        assertFalse(LiveConversationLogic.czasKończyćPoCiszy(1_000 + limit - 1, 1_000, asystentMówi = false))
        assertTrue(LiveConversationLogic.czasKończyćPoCiszy(1_000 + limit, 1_000, asystentMówi = false))
        assertFalse(LiveConversationLogic.czasKończyćPoCiszy(1_000 + limit * 3, 1_000, asystentMówi = true))
    }

    @Test
    fun `minuty do dziennika`() {
        // 600 kawałków po 100 ms = 1 minuta
        assertEquals(1.0, LiveConversationLogic.minutyZKawałków(600, 100), 0.0001)
        assertEquals(0.5, LiveConversationLogic.minutyZKawałków(300, 100), 0.0001)
        // 24 kHz, 16 bit: 2 880 000 B = 60 s
        assertEquals(1.0, LiveConversationLogic.minutyZBajtów(2_880_000L, 24_000), 0.0001)
        assertEquals(0.0, LiveConversationLogic.minutyZBajtów(0L, 24_000), 0.0001)
    }

    @Test
    fun `bramka echa - cisza w trakcie odpowiedzi, glosne wejscie w slowo przechodzi`() {
        val b = BramkaEcha(prógPrzebicia = 2_000, ramekPrzebicia = 2, trzymajMs = 1_000, maksWyciszeniaMs = 30_000)
        // Asystent milczy - wszystko idzie.
        assertTrue(b.przepuść(0, asystentMówi = false, poziom = 50))
        // Odpowiedź gra, echo jest ciche - cisza do serwera.
        assertFalse(b.przepuść(100, asystentMówi = true, poziom = 800))
        // Jedna głośna ramka to jeszcze nie wejście w słowo (trzask, stuknięcie).
        assertFalse(b.przepuść(200, asystentMówi = true, poziom = 3_000))
        assertFalse(b.przepuść(300, asystentMówi = true, poziom = 500))
        // Dwie z rzędu - przechodzi i trzyma bramkę otwartą.
        assertFalse(b.przepuść(400, asystentMówi = true, poziom = 3_000))
        assertTrue(b.przepuść(500, asystentMówi = true, poziom = 3_000))
        assertTrue(b.przepuść(900, asystentMówi = true, poziom = 300))
        assertEquals(1, b.przebicia)
        assertFalse(b.przepuść(1_600, asystentMówi = true, poziom = 300))
    }

    @Test
    fun `bramka echa - bezpiecznik po dlugim wyciszeniu`() {
        val b = BramkaEcha(maksWyciszeniaMs = 10_000)
        assertFalse(b.przepuść(0, asystentMówi = true, poziom = 100))
        assertFalse(b.przepuść(10_000, asystentMówi = true, poziom = 100))
        assertTrue(b.przepuść(10_001, asystentMówi = true, poziom = 100))
        // Koniec odpowiedzi zeruje licznik.
        assertTrue(b.przepuść(11_000, asystentMówi = false, poziom = 100))
        assertFalse(b.przepuść(11_100, asystentMówi = true, poziom = 100))
    }

    @Test
    fun `wykrywacz konca - polecenie z kawalkow, potwierdzone po chwili`() {
        val w = WykrywaczKońca(MetaCommands::endsLiveConversation, potwierdzenieMs = 700)
        w.usłyszane("Dobra, dzięki. Koniec", 0)
        assertFalse(w.czasKończyć(5_000))
        w.usłyszane(" rozmowy.", 1_000)
        assertFalse(w.czasKończyć(1_500))
        assertTrue(w.czasKończyć(1_700))
        // Odpowiedź serwera po poleceniu nie kasuje kandydata.
        w.koniecTury()
        assertTrue(w.czasKończyć(2_000))
    }

    @Test
    fun `wykrywacz konca - dalsze slowa zmieniaja zdanie`() {
        val w = WykrywaczKońca(MetaCommands::endsLiveConversation, potwierdzenieMs = 700)
        w.usłyszane("Ten koniec rozmowy", 0)
        w.usłyszane(" był dziwny", 300)
        assertFalse(w.czasKończyć(5_000))
        // Koniec tury czyści ogon - nowa wypowiedź od zera.
        w.koniecTury()
        assertEquals("", w.ogonTeraz)
        w.usłyszane("Do widzenia", 6_000)
        assertTrue(w.czasKończyć(6_700))
    }

    @Test
    fun `wymiana - pytanie i odpowiedz, nowa po odpowiedzi`() {
        val b = LiveExchangeBuffer()
        assertNull(b.dodajUżytkownika("Jak  się "))
        assertNull(b.dodajUżytkownika("masz?"))
        b.dodajAsystenta("Dobrze, ")
        b.dodajAsystenta("dziękuję.")
        // Wejście w słowo: nowa wypowiedź zamyka poprzednią wymianę.
        assertEquals("Jak się masz?" to "Dobrze, dziękuję.", b.dodajUżytkownika("A pogoda?"))
        assertEquals("A pogoda?", b.użytkownikTeraz)
        // Bez odpowiedzi nie ma wymiany.
        assertNull(b.zamknij())
        assertEquals("", b.użytkownikTeraz)
    }
}
