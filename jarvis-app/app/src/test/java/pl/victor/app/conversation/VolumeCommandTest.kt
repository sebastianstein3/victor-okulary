package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeCommandTest {

    @Test
    fun `glosniej i ciszej w roznych odmianach`() {
        listOf("głośniej", "Głośniej!", "trochę głośniej", "mów głośniej", "podgłośnij",
            "zrób głośniej proszę", "jeszcze głośniej", "zwiększ głośność", "glosniej")
            .forEach { assertEquals(it, VolumeCommand.Up, VolumeCommand.parse(it)) }
        listOf("ciszej", "trochę ciszej", "mów ciszej", "ścisz", "przycisz trochę",
            "zmniejsz głośność", "ścisz okulary")
            .forEach { assertEquals(it, VolumeCommand.Down, VolumeCommand.parse(it)) }
    }

    @Test
    fun `glosnosc na liczbe`() {
        assertEquals(VolumeCommand.SetPercent(70), VolumeCommand.parse("ustaw głośność na 70 procent"))
        assertEquals(VolumeCommand.SetPercent(70), VolumeCommand.parse("głośność 70%"))
        assertEquals(VolumeCommand.SetPercent(100), VolumeCommand.parse("głośność okularów na 100"))
        assertEquals(VolumeCommand.SetPercent(100), VolumeCommand.parse("głośność na maksimum"))
        assertEquals(VolumeCommand.SetPercent(100), VolumeCommand.parse("maksymalna głośność"))
        assertEquals(VolumeCommand.SetPercent(50), VolumeCommand.parse("ustaw głośność na połowę"))
    }

    @Test
    fun `zero nie wycisza asystenta`() {
        assertEquals(
            VolumeCommand.SetPercent(VolumeCommand.MIN_PERCENT),
            VolumeCommand.parse("głośność na 0")
        )
        assertNull(VolumeCommand.parse("głośność na 250"))
    }

    @Test
    fun `zwykle zdania nie sa komenda`() {
        listOf(
            "mów głośniej o tym zamku", "ścisz muzykę", "cicho", "czy możesz mówić ciszej w kinie",
            "jaka jest głośność silnika", "głośniej niż wczoraj było na koncercie", ""
        ).forEach { assertNull(it, VolumeCommand.parse(it)) }
    }
}
