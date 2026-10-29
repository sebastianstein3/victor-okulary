package pl.victor.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiBadRequestTest {

    private val ogólna = """{"error":{"code":400,"message":"Request contains an invalid argument.","status":"INVALID_ARGUMENT"}}"""

    @Test
    fun `ogolna odmowa bez slowa thinking najpierw zdejmuje ograniczenie myslenia`() {
        assertEquals(
            GeminiBadRequest.Naprawa.BEZ_OGRANICZENIA_MYŚLENIA,
            GeminiBadRequest.naprawa(400, ogólna, wysłanoOgraniczenieMyślenia = true, wysłanoWyszukiwarkę = true)
        )
    }

    @Test
    fun `bez ograniczenia myslenia zdejmuje wyszukiwarke`() {
        assertEquals(
            GeminiBadRequest.Naprawa.BEZ_WYSZUKIWARKI,
            GeminiBadRequest.naprawa(400, ogólna, wysłanoOgraniczenieMyślenia = false, wysłanoWyszukiwarkę = true)
        )
    }

    @Test
    fun `gdy nie ma czego zdjac - nie ponawia`() {
        assertNull(GeminiBadRequest.naprawa(400, ogólna, wysłanoOgraniczenieMyślenia = false, wysłanoWyszukiwarkę = false))
    }

    @Test
    fun `zly klucz i inne kody nie sa naprawiane zmiana zapytania`() {
        assertNull(GeminiBadRequest.naprawa(400, "API key not valid. Please pass a valid API key.", true, true))
        assertNull(GeminiBadRequest.naprawa(400, "\"reason\": \"API_KEY_INVALID\"", true, true))
        assertNull(GeminiBadRequest.naprawa(429, ogólna, true, true))
        assertNull(GeminiBadRequest.naprawa(500, ogólna, true, true))
    }

    @Test
    fun `odpowiedz serwera trafia do dziennika w jednej linii`() {
        val wieloliniowa = "{\n  \"error\": {\n    \"code\": 400,\n    \"details\": [ \"tools\" ]\n  }\n}"
        assertEquals("{ \"error\": { \"code\": 400, \"details\": [ \"tools\" ] } }", GeminiBadRequest.jednaLinia(wieloliniowa))
        assertEquals(600, GeminiBadRequest.jednaLinia("x".repeat(5000)).length)
    }

    @Test
    fun `odmowa wskazujaca tresc nie wyzwala zdejmowania dodatkow`() {
        val body = """{"error":{"code":400,"message":"Invalid value at 'contents[0].parts[1].inline_data'","status":"INVALID_ARGUMENT"}}"""
        assertNull(GeminiBadRequest.naprawa(400, body, true, true))
        assertNull(GeminiBadRequest.naprawa(400, "Request payload size exceeds the limit", true, true))
    }

    @Test
    fun `mapy zdejmowane pierwsze, potem myslenie, potem wyszukiwarka`() {
        assertEquals(
            GeminiBadRequest.Naprawa.BEZ_MAP,
            GeminiBadRequest.naprawa(400, ogólna, wysłanoOgraniczenieMyślenia = true, wysłanoWyszukiwarkę = true, wysłanoMapy = true)
        )
        assertEquals(
            GeminiBadRequest.Naprawa.BEZ_OGRANICZENIA_MYŚLENIA,
            GeminiBadRequest.naprawa(400, ogólna, wysłanoOgraniczenieMyślenia = true, wysłanoWyszukiwarkę = true, wysłanoMapy = false)
        )
    }
}
