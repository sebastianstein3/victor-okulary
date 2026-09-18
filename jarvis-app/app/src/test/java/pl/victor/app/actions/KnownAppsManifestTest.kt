package pl.victor.app.actions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Spina trzy rzeczy, które muszą się zgadzać, a mieszkają osobno: listę znanych
 * aplikacji, manifest i nazwy mówione.
 *
 * ## Czemu to nie jest przesada
 * Nazwy pakietów mają dwie niemiłe własności naraz: nie da się ich wywnioskować
 * z nazwy aplikacji ani sprawdzić na oczy, a brak wpisu w manifeście nie daje
 * błędu - Android po prostu UDAJE, że aplikacji nie ma.
 *
 * Obie własności uderzyły już w tym projekcie:
 * - "pl.jakdojade" było nazwą wymyśloną, wyglądało sensownie i trzeba je było
 *   poprawić w trzech miejscach naraz,
 * - Uber siedział w dwóch kopiach listy jako "com.ubercab" i "com.uber";
 *   poprawka jednej kopii nie ruszyła drugiej,
 * - dziewięć aplikacji (Netflix, Instagram, kalendarz, notatki...) nie miało
 *   wpisu w <queries>, więc "otwórz Netflixa" odpowiadało "nie jest
 *   zainstalowana", a ekran ustawień stawiał krzyżyk przy obecnej aplikacji.
 *
 * Każdy z tych trzech przypadków objawiał się CICHĄ ODMOWĄ, czyli najgorszym
 * możliwym sposobem.
 */
class KnownAppsManifestTest {

    private fun manifest(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(
                File(dir, "jarvis-app/app/src/main/AndroidManifest.xml"),
                File(dir, "src/main/AndroidManifest.xml")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `kazda znana aplikacja jest widoczna w manifescie`() {
        val plik = manifest()
        assumeTrue("Nie znalazłem manifestu", plik != null)
        val xml = plik!!.readText()
        val brakujace = KnownApps.ALL
            .map { it.packageName }
            // Siebie nie musimy deklarować - własny pakiet widzimy zawsze.
            .filter { it != "pl.victor.app" }
            .filterNot { xml.contains("<package android:name=\"$it\" />") }
        assertTrue(
            "brak wpisu <package> w <queries> dla: $brakujace - Android będzie " +
                "udawał, że te aplikacje nie są zainstalowane",
            brakujace.isEmpty()
        )
    }

    @Test
    fun `nazwa pakietu wystepuje w repozytorium dokladnie raz`() {
        // Warunek, którego złamanie dało usterkę Ubera: dwie kopie listy,
        // poprawiona jedna.
        val duplikaty = KnownApps.ALL.groupBy { it.packageName }.filter { it.value.size > 1 }
        assertTrue("pakiet powtórzony w KnownApps: ${duplikaty.keys}", duplikaty.isEmpty())
    }

    @Test
    fun `nazwy mowione nie koliduja`() {
        val wszystkie = KnownApps.ALL.flatMap { it.spoken }
        assertEquals(
            "ta sama nazwa mówiona wskazuje dwie aplikacje: " +
                wszystkie.groupBy { it }.filter { it.value.size > 1 }.keys,
            wszystkie.size,
            wszystkie.distinct().size
        )
        assertTrue(
            "nazwy mówione mają być małymi literami - po nich szuka się w mapie",
            wszystkie.all { it == it.lowercase() }
        )
    }

    @Test
    fun `mapa nazw mowionych zgadza sie z lista`() {
        KnownApps.ALL.forEach { app ->
            app.spoken.forEach { nazwa ->
                assertEquals(
                    "„$nazwa” ma wskazywać ${app.display}",
                    app.packageName,
                    KnownApps.bySpokenName[nazwa]
                )
            }
        }
    }
}
