package pl.victor.app.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Każda komenda, którą UMIEMY WYSŁAĆ, ma dać się nazwać w dzienniku.
 *
 * ## Skąd to
 * W dzienniku ramek z 19 września stoi trzy razy:
 *
 *     16:08:50.849  Okulary odpowiedziały na: Nieznana komenda: 02 01 15 01
 *
 * `0x15` to `WORK_LIVE_PREVIEW_STOP` - nasza własna komenda. Aplikacja wysyłała
 * ją i sama nie potrafiła powiedzieć, co wysłała. Brakowało też `0x14` i `0x09`.
 *
 * Dziennik jest tu jedynym oknem na sprzęt, którego nie da się podejrzeć
 * debuggerem. Wiersz "nieznana komenda" zmusza do siedzenia z tabelą stałych
 * przy każdym czytaniu - a przy trzech takich wierszach obok siebie łatwo
 * uznać, że to sprzęt odmawia, zamiast że to my nie nazwaliśmy swojego.
 */
class GlassesCommandNamesTest {

    @Test
    fun `kazdy typ pracy ma nazwe w dzienniku`() {
        val bezNazwy = mutableListOf<String>()
        for ((nazwa, kod) in workTypes()) {
            // Ramka komendy: 0x02, licznik, typ pracy, argument.
            val opis = GlassesProtocol.describeCommand(
                byteArrayOf(0x02, 0x01, kod.toByte(), 0x01)
            )
            if (opis.startsWith("Nieznana komenda")) bezNazwy += "$nazwa (0x%02X)".format(kod)
        }
        assertEquals(
            "te komendy wysyłamy, a dziennik nie umie ich nazwać",
            emptyList<String>(),
            bezNazwy
        )
    }

    @Test
    fun `stop podgladu na zywo nie jest juz nieznany`() {
        // Dokładnie ta ramka z dziennika z 19 września.
        val opis = GlassesProtocol.describeCommand(byteArrayOf(0x02, 0x01, 0x15, 0x01))
        assertEquals("Stop podglądu na żywo", opis)
    }

    /** Czyta stałe WORK_* wprost ze źródła - nowa stała ma się tu pojawić sama. */
    private fun workTypes(): List<Pair<String, Int>> {
        var dir: File? = File("").absoluteFile
        var plik: File? = null
        while (dir != null && plik == null) {
            plik = listOf(
                File(dir, "jarvis-app/app/src/main/java/pl/victor/app/ble/GlassesProtocol.kt"),
                File(dir, "src/main/java/pl/victor/app/ble/GlassesProtocol.kt")
            ).firstOrNull { it.isFile }
            dir = dir.parentFile
        }
        assumeTrue("Nie znalazłem GlassesProtocol.kt", plik != null)
        return Regex("""const val (WORK_[A-Z0-9_]+) = 0x([0-9A-Fa-f]+)""")
            .findAll(plik!!.readText())
            .map { it.groupValues[1] to it.groupValues[2].toInt(16) }
            .toList()
    }
}
