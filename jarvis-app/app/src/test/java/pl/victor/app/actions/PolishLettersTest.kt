package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Polskie litery w regexach - klasa błędu, która w tym projekcie wróciła PIĘĆ RAZY.
 *
 * W Javie, a więc i w Kotlinie, `\w` to dosłownie `[a-zA-Z_0-9]`. Nie ma w tym
 * ani jednej polskiej litery. Z tego wynikają dwie różne usterki:
 *
 * - `\w*` staje PRZED ogonkiem, więc wzorzec urywa się w połowie słowa
 *   ("japoński" -> "japo", "najbliższą" -> bez dopasowania),
 * - `\b` jest granicą między `\w` a resztą, więc PO ogonku nie zachodzi wcale
 *   ("miejską\b" nie pasuje, "dotrę" nie daje granicy).
 *
 * Złapane kolejno na: "taksówkę", "dotrę na", "miejską", "japoński",
 * "najbliższą", "otwórz Żappkę". Za każdym razem objawem była cisza - zdanie
 * szło do modelu i nic się nie działo.
 *
 * Wszystkie przypadki tutaj są ZDANIAMI, jakie ktoś naprawdę powie, a nie
 * sztucznymi łamańcami.
 */
class PolishLettersTest {

    private val detector = SmartActionDetector()

    @Test
    fun `nazwa aplikacji z ogonkiem nie jest ucinana`() {
        val akcja = detector.detect("otwórz Żappkę")
            .filterIsInstance<Action.OpenApp>().singleOrNull()
        assertTrue("„otwórz Żappkę” nie dało akcji", akcja != null)
        assertEquals("żappkę", akcja!!.appName)
    }

    @Test
    fun `aplikacja spoza listy tez sie otwiera`() {
        // Lista znanych pakietów ma kilkanaście pozycji; wszystko spoza niej
        // dawało PUSTKĘ. Wykonawca umie znaleźć aplikację po nazwie z pulpitu,
        // więc wystarczy mu ją podać.
        val akcja = detector.detect("otwórz Jakdojade")
            .filterIsInstance<Action.OpenApp>().singleOrNull()
        assertTrue("„otwórz Jakdojade” nie dało akcji", akcja != null)
        assertEquals("jakdojade", akcja!!.appName)
    }

    @Test
    fun `wlacz nie robi z rzeczownika nazwy aplikacji`() {
        // Warunek poprawności poprzedniego testu. "Włącz" bywa poleceniem
        // systemowym, więc zapas po nazwie działa tylko po "otwórz"/"uruchom".
        val akcje = detector.detect("włącz latarkę")
        assertTrue(
            "„włącz latarkę” nie może szukać aplikacji o nazwie „latarkę”: $akcje",
            akcje.none { it is Action.OpenApp }
        )
        assertTrue("„włącz latarkę” ma zapalić latarkę", akcje.any { it is Action.ToggleFlashlight })
    }

    @Test
    fun `jezyk docelowy nie jest ucinany`() {
        val akcja = detector.detect("przetłumacz dzień dobry na japoński")
            .filterIsInstance<Action.Translate>().singleOrNull()
        assertTrue("brak akcji tłumaczenia", akcja != null)
        assertEquals("japoński", akcja!!.targetLang)
    }

    @Test
    fun `najblizsza z ogonkiem jest wycinana z celu`() {
        val trasa = detector.detect("nawiguj do najbliższą biedronkę")
            .filterIsInstance<Action.Navigate>().singleOrNull()
        assertTrue("brak trasy", trasa != null)
        assertEquals("biedronkę", trasa!!.destination)
    }

    @Test
    fun `komunikacja miejska z ogonkiem jest rozpoznawana`() {
        val trasa = detector.detect("jedź komunikacją miejską do dworca")
            .filterIsInstance<Action.Navigate>().singleOrNull()
        assertTrue("„komunikacją miejską” nie zostało rozpoznane", trasa != null)
        assertTrue("trasa ma iść KOMUNIKACJĄ", trasa!!.byTransit)
        assertEquals("dworca", trasa.destination)
    }

    @Test
    fun `pytanie o dojazd z czasownikiem dotrzec`() {
        val trasa = detector.detect("jak dotrę na polną 140")
            .filterIsInstance<Action.Navigate>().singleOrNull()
        assertTrue("„jak dotrę na X” nie zostało rozpoznane", trasa != null)
        assertTrue(trasa!!.byTransit)
    }
}
