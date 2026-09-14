package pl.victor.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Punktem odniesienia jest PRAWDZIWA odpowiedź okularów z dziennika z 14
 * września - ta sama, o którą rozbił się odtwarzacz media3.
 */
class RtspProtocolTest {

    /** Dosłowna odpowiedź serwera Hisilicon z okularów. */
    private val realReply = listOf(
        "RTSP/1.0 200 OK",
        "CSeq: 1",
        "Content-Type: application/sdp",
        "Cache-Control: no-cache",
        "Server: Hisilicon RTSP Streaming Media Server/1.0.0",
        "Content-Length: 345",
        "",
        "v=0",
        "o=- 1 1 IN IP4 127.0.0.1",
        "s=Test",
        "a=type:broadcast",
        "t=0 0",
        "c=IN IP4 0.0.0.0",
        "m=video 0 RTP/AVP 96",
        "a=rtpmap:96 H264/90000",
        "a=decode_buf=300",
        "a=control:track0",
        "m=audio 0 RTP/AVP 97",
        "a=rtpmap:97 mpeg4-generic/8000/1",
        "a=fmtp:97 profile-level-id=1; mode=AAC-hbr; config=1588; sizeLength=13",
        "a=control:track1"
    ).joinToString("\r\n")

    @Test
    fun `wiersz o ktory rozbil sie media3 jest po prostu pomijany`() {
        // "Malformed Attribute line: a=decode_buf=300" - media3 uznaje cały opis
        // za uszkodzony. Dla nas to wiersz, który nas nie dotyczy.
        val tracks = RtspProtocol.parseSdp(realReply)
        assertEquals(2, tracks.size)
    }

    @Test
    fun `sciezka wideo jest rozpoznana mimo braku fmtp`() {
        // Druga bariera media3: brak `a=fmtp:96`, czyli brak sprop-parameter-sets.
        // Parametry obrazu przyjdą w strumieniu i to jest w porządku.
        val video = RtspProtocol.parseSdp(realReply).first { it.kind == RtspProtocol.Kind.VIDEO }
        assertEquals(96, video.payloadType)
        assertEquals("H264", video.encoding)
        assertEquals(90_000, video.clockRate)
        assertEquals("track0", video.control)
    }

    @Test
    fun `sciezka dzwieku tez sie czyta`() {
        val audio = RtspProtocol.parseSdp(realReply).first { it.kind == RtspProtocol.Kind.AUDIO }
        assertEquals(97, audio.payloadType)
        assertEquals("MPEG4-GENERIC", audio.encoding)
        assertEquals(8_000, audio.clockRate)
        assertEquals("track1", audio.control)
    }

    @Test
    fun `atrybuty nie wyciekaja miedzy sciezkami`() {
        // Gdyby `a=control:` z pierwszej ścieżki został przy drugiej, SETUP
        // poszedłby dwa razy pod ten sam adres i obraz nigdy by nie ruszył.
        val tracks = RtspProtocol.parseSdp(realReply)
        assertEquals("track0", tracks[0].control)
        assertEquals("track1", tracks[1].control)
    }

    @Test
    fun `opis bez zadnej sciezki daje pusta liste a nie wyjatek`() {
        assertTrue(RtspProtocol.parseSdp("v=0\r\no=- 1 1 IN IP4 127.0.0.1").isEmpty())
        assertTrue(RtspProtocol.parseSdp("").isEmpty())
    }

    @Test
    fun `adres wzgledny sie dokleja a bezwzgledny zostaje`() {
        val base = "rtsp://192.168.31.1:8554/ch0"
        assertEquals("$base/track0", RtspProtocol.resolveControl(base, "track0"))
        assertEquals(
            "rtsp://inny:8554/x",
            RtspProtocol.resolveControl(base, "rtsp://inny:8554/x")
        )
        assertEquals(base, RtspProtocol.resolveControl(base, "*"))
        assertEquals(base, RtspProtocol.resolveControl(base, null))
    }

    @Test
    fun `doklejanie nie robi podwojnego ukosnika`() {
        assertEquals(
            "rtsp://a:8554/ch0/track0",
            RtspProtocol.resolveControl("rtsp://a:8554/ch0/", "/track0")
        )
    }

    @Test
    fun `zadanie ma CSeq i konczy sie pusta linia`() {
        val req = RtspProtocol.request("DESCRIBE", "rtsp://a/ch0", 3, mapOf("Accept" to "application/sdp"))
        assertTrue(req, req.startsWith("DESCRIBE rtsp://a/ch0 RTSP/1.0\r\n"))
        assertTrue(req, req.contains("CSeq: 3\r\n"))
        assertTrue(req, req.contains("Accept: application/sdp\r\n"))
        assertTrue(req, req.endsWith("\r\n\r\n"))
    }

    @Test
    fun `odpowiedz rozklada sie na stan naglowki i tresc`() {
        val reply = RtspProtocol.parseReply(realReply)!!
        assertEquals(200, reply.status)
        assertTrue(reply.ok)
        assertEquals("application/sdp", reply.headers["content-type"])
        assertTrue(reply.body, reply.body.startsWith("v=0"))
    }

    @Test
    fun `wielkosc liter w naglowkach nie ma znaczenia`() {
        // RTSP ich nie rozróżnia, a serwery pisują je po swojemu.
        val reply = RtspProtocol.parseReply("RTSP/1.0 200 OK\r\nSESSION: abc;timeout=60\r\n\r\n")!!
        assertEquals("abc", RtspProtocol.sessionId(reply.headers))
    }

    @Test
    fun `identyfikator sesji nie niesie parametrow`() {
        // Odesłanie "12345678;timeout=60" jako identyfikatora bywa odrzucane.
        assertEquals("12345678", RtspProtocol.sessionId(mapOf("session" to "12345678;timeout=60")))
        assertEquals("12345678", RtspProtocol.sessionId(mapOf("session" to "12345678")))
        assertNull(RtspProtocol.sessionId(emptyMap()))
    }

    @Test
    fun `limit bezczynnosci sie odczytuje gdy jest`() {
        assertEquals(60, RtspProtocol.sessionTimeoutSeconds(mapOf("session" to "abc;timeout=60")))
        assertNull(RtspProtocol.sessionTimeoutSeconds(mapOf("session" to "abc")))
    }

    @Test
    fun `odpowiedz niebedaca RTSP jest odrzucana`() {
        // Otwarty port, na którym siedzi HTTP, to nie jest serwer RTSP.
        assertNull(RtspProtocol.parseReply("HTTP/1.1 400 Bad Request\r\n\r\n"))
        assertNull(RtspProtocol.parseReply(""))
    }

    @Test
    fun `blad serwera nie jest brany za sukces`() {
        val reply = RtspProtocol.parseReply("RTSP/1.0 454 Session Not Found\r\nCSeq: 5\r\n\r\n")!!
        assertEquals(454, reply.status)
        assertTrue(!reply.ok)
    }

    @Test
    fun `transport zada RTP wplecionego w TCP`() {
        // To jest ten wybór, który producent robi przez `--rtsp-tcp`.
        val t = RtspProtocol.interleavedTransport(0)
        assertTrue(t, t.contains("RTP/AVP/TCP"))
        assertTrue(t, t.contains("interleaved=0-1"))
        assertEquals("RTP/AVP/TCP;unicast;interleaved=2-3", RtspProtocol.interleavedTransport(2))
    }

    @Test
    fun `odpowiedz rozdzielona pojedynczymi nowymi liniami tez sie czyta`() {
        val reply = RtspProtocol.parseReply("RTSP/1.0 200 OK\nCSeq: 1\n\nv=0\n")
        assertNotNull(reply)
        assertEquals(200, reply!!.status)
        assertTrue(reply.body, reply.body.startsWith("v=0"))
    }
}
