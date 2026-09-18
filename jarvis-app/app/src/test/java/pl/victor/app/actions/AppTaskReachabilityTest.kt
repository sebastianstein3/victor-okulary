package pl.victor.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Przypadki WPROST z dziennika z 16 września, godziny 18:41-18:44.
 *
 * Każde z tych zdań zostało naprawdę powiedziane do okularów i każde poszło do
 * modelu zamiast do aplikacji. W całym dzienniku nie ma ani jednego zdarzenia
 * AKCJA - żadna nie została rozpoznana ani wykonana.
 *
 * ## Czemu testy tego nie złapały wcześniej
 * Bo każdy sprawdzał SWOJĄ warstwę osobno i każdy przechodził. [detect] (czyli
 * warstwa 2) rozpoznawała te zdania bezbłędnie - tylko że warstwa 2 uruchamia
 * się WYŁĄCZNIE wtedy, gdy AI jest niedostępne. Przy działającym modelu droga
 * była jedna: warstwa 0 albo znacznik od modelu. W warstwie 0 nie było
 * APP_TASK, a model nie wiedział, że taka akcja istnieje, bo nie dopisałem jej
 * do swojego słownika.
 *
 * Funkcja miała więc wzorce, katalog i testy - i nie miała jak zadziałać.
 * Te testy sprawdzają DROGĘ, nie sam wzorzec.
 */
class AppTaskReachabilityTest {

    private val detector = SmartActionDetector()

    @Test
    fun `model zna app_task, bo inaczej nie ma jak o nie poprosic`() {
        val slownik = SmartActionDetector.AI_ACTION_CAPABILITIES_PROMPT
        assertTrue("brak app_task w słowniku modelu", slownik.contains("app_task"))
        // Każdy rodzaj z osobna: model wybiera po nazwie, więc nazwa musi paść.
        AppTaskKind.entries.forEach { kind ->
            assertTrue("brak ${kind.name} w słowniku modelu", slownik.contains(kind.name))
        }
    }

    @Test
    fun `znacznik app_task od modelu daje akcje`() {
        val (_, akcje) = detector.detectAiMarkedActions(
            """Sprawdzam dojazd.
            [[ACTION: type=app_task kind="TRANSIT_PLAN" argument="Polna 140"]]"""
        )
        val zadanie = akcje.filterIsInstance<Action.AppTask>().singleOrNull()
        assertTrue("znacznik app_task nie dał akcji: $akcje", zadanie != null)
        assertEquals(AppTaskKind.TRANSIT_PLAN, zadanie!!.kind)
        assertEquals("Polna 140", zadanie.argument)
    }

    @Test
    fun `nieznany rodzaj nie wywraca parsowania`() {
        val (_, akcje) = detector.detectAiMarkedActions(
            """[[ACTION: type=app_task kind="CZEGOS_TAKIEGO_NIE_MA"]]"""
        )
        assertTrue("wymyślony rodzaj ma być pominięty, nie ma wywracać tury", akcje.isEmpty())
    }

    @Test
    fun `rozpoznanie piosenki jest w warstwie 0`() {
        // Zanim model odpowie, mija kilka sekund - piosenka przez ten czas się
        // kończy. Ta jedna komenda nie znosi obiegu przez sieć.
        listOf("co to za piosenka", "otwórz shazam", "włącz shazam").forEach { zdanie ->
            val akcje = detector.detectCritical(zdanie)
            val zadanie = akcje.filterIsInstance<Action.AppTask>().singleOrNull()
            assertTrue("„$zdanie” nie trafiło do warstwy 0: $akcje", zadanie != null)
            assertEquals(AppTaskKind.RECOGNIZE_SONG, zadanie!!.kind)
        }
    }

    @Test
    fun `warstwa 0 nie lapie rozmowy o piosence`() {
        // Warunek poprawności poprzedniego testu: Shazam włączony w środku
        // rozmowy zagłusza asystenta i wygląda na awarię.
        listOf("lubię tę piosenkę", "opowiedz mi o tej piosence").forEach { zdanie ->
            assertTrue("„$zdanie” nie może nic odpalać", detector.detectCritical(zdanie).isEmpty())
        }
    }

    @Test
    fun `pytanie o dojazd idzie do Map, bo tam cel wchodzi`() {
        // POMIAR, NIE PREFERENCJA. Dziennik z 17 września:
        //     Zadanie w cudzej aplikacji: udane  zadanie=TRANSIT_PLAN proba=3/3
        // Trzecia próba z trzech to zwykłe otwarcie - Jakdojade nie przyjmuje
        // celu z zewnątrz. Mapy z travelmode=transit przyjmują, więc pytanie o
        // dojazd ma iść tam.
        listOf(
            "jak dojadę na uniwersytet",
            "jak dotrę na polną 140 w toruniu",
            "jak dostanę się do dworca"
        ).forEach { zdanie ->
            val trasa = detector.detect(zdanie).filterIsInstance<Action.Navigate>().singleOrNull()
            assertTrue("„$zdanie” miało dać trasę komunikacją: ${detector.detect(zdanie)}", trasa != null)
            assertTrue("„$zdanie” ma iść KOMUNIKACJĄ, nie autem", trasa!!.byTransit)
        }
    }

    @Test
    fun `nazwanie Jakdojade z nazwy dalej otwiera Jakdojade`() {
        // Kto prosi o konkretną aplikację, ma ją dostać - nawet jeśli celu nie
        // przyjmie. Komunikat mówi wtedy wprost, że trzeba go wpisać.
        val akcje = detector.detect("sprawdź w jakdojade jak dotrę na polną 140")
        val zadanie = akcje.filterIsInstance<Action.AppTask>().singleOrNull()
        assertTrue("wymienienie Jakdojade z nazwy ma je otwierać: $akcje", zadanie != null)
        assertEquals(AppTaskKind.TRANSIT_PLAN, zadanie!!.kind)
    }

    @Test
    fun `cel po na, nie tylko po do`() {
        // 18:41:39 telefonUsłyszał=sprawdź w jakdojade jak dotrę na polną 140 w toruniu
        //
        // Dwie pułapki polskich liter w jednym zdaniu: cel stoi po "na", a nie
        // po "do", a granica słowa `\b` opiera się na [a-zA-Z_0-9], więc po
        // "dotrę" nie zachodzi wcale.
        val akcje = detector.detect("sprawdź w jakdojade jak dotrę na polną 140 w toruniu")
        val zadanie = akcje.filterIsInstance<Action.AppTask>().singleOrNull()
        assertTrue("zdanie z terenu nie zostało rozpoznane: $akcje", zadanie != null)
        assertEquals(AppTaskKind.TRANSIT_PLAN, zadanie!!.kind)
        assertEquals("polną 140 w toruniu", zadanie.argument)
    }
}
