package pl.victor.app.conversation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * "Miałem język pobrany, choć aplikacja twierdziła inaczej."
 *
 * Silnik na urządzeniu ma WŁASNY magazyn pakietów. Nasłuch sięgał wyłącznie po
 * niego i nie miał drogi zapasowej - teraz przy braku języka przechodzi na
 * rozpoznawanie przez sieć.
 */
class LanguagePackFallbackTest {

    @Test
    fun `stale zgadzaja sie z Androidem 13`() {
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE.
        assertEquals(12, LanguagePackFallback.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertEquals(13, LanguagePackFallback.ERROR_LANGUAGE_UNAVAILABLE)
    }

    @Test
    fun `tylko bledy jezyka przelaczaja silnik`() {
        assertTrue(LanguagePackFallback.isLanguageError(12))
        assertTrue(LanguagePackFallback.isLanguageError(13))
        // Cisza (6) i brak dopasowania (7) to normalny koniec nasłuchu - przejście
        // na sieć dałoby tylko drugi, równie pusty nasłuch.
        assertFalse(LanguagePackFallback.isLanguageError(6))
        assertFalse(LanguagePackFallback.isLanguageError(7))
        assertFalse(LanguagePackFallback.isLanguageError(null))
    }

    private fun zrodlo(sciezka: String): String? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "jarvis-app/app/$sciezka"), File(dir, sciezka))
                .firstOrNull { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `nasluch ma droge zapasowa przez siec`() {
        val stt = zrodlo("src/main/java/pl/victor/app/conversation/SpeechToText.kt")
        assumeTrue("Nie znalazłem SpeechToText.kt", stt != null)
        val listen = stt!!.indexOf("suspend fun listen(")
        val koniec = stt.indexOf("private val onDeviceMissingLanguages", listen)
        val ciało = stt.substring(listen, koniec)
        assertTrue("po błędzie języka ma być druga próba", ciało.contains("LanguagePackFallback.isLanguageError(lastErrorCode)"))
        assertTrue("druga próba ma iść przez sieć", ciało.contains("preferOnDevice = false"))
    }
}
