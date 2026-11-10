package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesCommandTest {

    @Test
    fun `nagrywanie filmu`() {
        listOf("nagraj film", "Nagrywaj wideo.", "zacznij nagrywać film", "włącz nagrywanie wideo",
            "nagraj krótki filmik", "nagraj film okularami", "proszę nagraj wideo")
            .forEach { assertEquals(it, GlassesCommand.VideoStart, GlassesCommand.parse(it)) }
        listOf("zatrzymaj nagrywanie", "zakończ nagrywanie filmu", "stop wideo", "skończ film",
            "wyłącz nagrywanie", "koniec nagrywania")
            .forEach { assertEquals(it, GlassesCommand.VideoStop, GlassesCommand.parse(it)) }
    }

    @Test
    fun `samo stop i spotkanie to nie film`() {
        listOf("stop", "koniec", "zakończ spotkanie", "nagrywaj spotkanie", "nagraj notatkę",
            "jaki film nagrał Wajda", "nagraj film o tym, jak gotuję i wyślij go Ani jutro rano")
            .forEach { assertNull(it, GlassesCommand.parse(it)) }
    }

    @Test
    fun `bateria okularow`() {
        listOf("ile baterii mają okulary", "stan baterii", "bateria", "ile mam baterii",
            "jak naładowane są okulary", "ile procent baterii")
            .forEach { assertEquals(it, GlassesCommand.Battery, GlassesCommand.parse(it)) }
        listOf("ile baterii ma telefon", "jak wymienić baterię w zegarku",
            "ile kosztuje bateria do samochodu",
            "opowiedz mi jak działają baterie litowo jonowe i dlaczego się zużywają")
            .forEach { assertNull(it, GlassesCommand.parse(it)) }
    }

    @Test
    fun `liczba plikow`() {
        listOf("ile zdjęć jest w okularach", "ile filmów mam w okularach",
            "ile plików jest w pamięci okularów", "ile zdjęć i filmów jest w okularach")
            .forEach { assertEquals(it, GlassesCommand.MediaCount, GlassesCommand.parse(it)) }
        assertNull(GlassesCommand.parse("ile zdjęć zrobiłem wczoraj"))
    }

    @Test
    fun `slaba bateria - dwa progi i kazdy raz`() {
        val w = LowBatteryWatch()
        assertNull(w.check(60, false))
        assertNull(w.check(null, false))
        assertEquals(20, w.check(20, false))
        // Niewypowiedziany komunikat wraca przy następnym odczycie.
        assertEquals(20, w.check(19, false))
        w.announced(20)
        assertNull(w.check(18, false))
        assertEquals(10, w.check(10, false))
        w.announced(10)
        assertNull(w.check(5, false))
    }

    @Test
    fun `ladowanie i powrot powyzej progu zeruja pamiec`() {
        val w = LowBatteryWatch()
        w.announced(20)
        assertNull(w.check(15, true))
        assertEquals(20, w.check(15, false))
        w.announced(20)
        assertNull(w.check(30, false))
        assertEquals(20, w.check(20, false))
    }

    @Test
    fun `od razu bardzo nisko mowimy o dziesieciu`() {
        val w = LowBatteryWatch()
        assertEquals(10, w.check(8, false))
    }
}
