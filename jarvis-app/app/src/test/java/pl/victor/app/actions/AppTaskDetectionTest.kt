package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rozpoznanie zadań zlecanych cudzym aplikacjom.
 *
 * Wzorce są WĄSKIE z rozmysłu: przechwycenie zwykłego pytania byłoby tu gorsze
 * niż nierozpoznanie polecenia. Połowa tych testów pilnuje właśnie tego, czego
 * łapać NIE WOLNO.
 */
class AppTaskDetectionTest {

    private val detector = SmartActionDetector()

    private fun task(text: String) =
        detector.detect(text).filterIsInstance<Action.AppTask>().firstOrNull()

    @Test
    fun `jakdojade z celem`() {
        val a = task("sprawdź w jakdojade do dworca głównego")
        assertEquals(AppTaskKind.TRANSIT_PLAN, a?.kind)
        assertEquals("dworca głównego", a?.argument)
    }

    @Test
    fun `piosenka na kilka sposobow`() {
        for (t in listOf(
            "co to za piosenka", "co to jest za utwór", "jaka to piosenka",
            "rozpoznaj tę piosenkę", "shazam"
        )) {
            assertEquals("nie rozpoznane: $t", AppTaskKind.RECOGNIZE_SONG, task(t)?.kind)
        }
    }

    @Test
    fun `kurs do celu`() {
        assertEquals(AppTaskKind.ORDER_RIDE, task("zamów ubera do domu")?.kind)
        assertEquals("krakowa", task("zamów taksówkę do krakowa")?.argument)
        assertEquals(AppTaskKind.ORDER_RIDE, task("zamów bolta do dworca")?.kind)
    }

    @Test
    fun `yanosik`() {
        assertEquals(AppTaskKind.ROAD_ASSIST, task("włącz yanosika")?.kind)
        assertEquals(AppTaskKind.ROAD_ASSIST, task("odpal yanosik")?.kind)
    }

    @Test
    fun `zwykla rozmowa o muzyce nie odpala Shazama`() {
        // To jest najważniejszy test w tym pliku: Shazam odpalony w środku
        // rozmowy zagłusza asystenta i wygląda na awarię.
        assertNull(task("lubię tę piosenkę"))
        assertNull(task("włącz muzykę"))
        assertNull(task("opowiedz mi o tej piosence"))
    }

    @Test
    fun `zamowienie bez celu nie zamawia niczego`() {
        assertNull(task("zamów ubera"))
        assertNull(task("zamów taksówkę"))
    }

    @Test
    fun `jakdojade bez celu nie ustawia trasy`() {
        assertNull(task("otwórz jakdojade"))
    }

    @Test
    fun `trasa Mapami nie koliduje z Jakdojade`() {
        // "Jedź autobusem do dworca" ma iść do Map (plan podróży bez
        // instalowania czegokolwiek), a nie do Jakdojade.
        val nav = detector.detectNavigation("jedź autobusem do dworca")
        assertTrue(nav != null && nav.byTransit)
        assertNull(task("jedź autobusem do dworca"))
    }
}
