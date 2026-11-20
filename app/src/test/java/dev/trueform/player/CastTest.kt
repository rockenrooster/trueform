package dev.trueform.player

import dev.trueform.player.cast.CastHttpServer
import dev.trueform.player.cast.CastManager
import dev.trueform.player.dsp.DspParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/plan/07-chromecast.md T2 and T3: the server's parsing and the passthrough decisions. */
class CastTest {
    private val len = 10_000L

    @Test
    fun ranges() {
        val r = CastHttpServer.Range
        assertNull(r.parse(null, len))
        assertEquals(0L..99L, r.parse("bytes=0-99", len))
        assertEquals(1000L..9999L, r.parse("bytes=1000-", len))
        assertEquals(9500L..9999L, r.parse("bytes=-500", len))
        assertEquals(0L..9999L, r.parse("bytes=-20000", len))          // a suffix longer than the file: all of it
        assertEquals(10L..9999L, r.parse("bytes=10-99999", len))       // an end past the file is clamped
        assertEquals(r.INVALID, r.parse("bytes=10000-", len))          // starts at the end
        assertEquals(r.INVALID, r.parse("bytes=50-10", len))
        assertEquals(r.INVALID, r.parse("bytes=-0", len))
        assertEquals(r.INVALID, r.parse("items=0-9", len))
        assertEquals(r.INVALID, r.parse("bytes=abc", len))
        assertEquals(r.INVALID, r.parse("bytes=0-1", 0))
    }

    @Test
    fun routes() {
        val key = "e7934beee9f936e1b2164a5ed6d21f0c"
        val f = CastHttpServer.Route.parse("/s/$key/f/42.flac", key)!!
        assertEquals('f', f.kind); assertEquals(42L, f.trackId)
        val r = CastHttpServer.Route.parse("/s/$key/r/7.wav?b=16&sr=44100&v=3", key)!!
        assertEquals('r', r.kind); assertEquals(mapOf("b" to "16", "sr" to "44100", "v" to "3"), r.query)
        assertNull(CastHttpServer.Route.parse("/s/0000/f/42.flac", key))           // wrong key
        assertNull(CastHttpServer.Route.parse("/s/$key/x/42.flac", key))           // unknown route
        assertNull(CastHttpServer.Route.parse("/s/$key/f/abc.flac", key))
        assertNull(CastHttpServer.Route.parse("/s/$key/f/1/2.flac", key))
        assertNull(CastHttpServer.Route.parse("/$key/f/1.flac", key))
        assertEquals(8, CastHttpServer.keyTag(key).length)
    }

    @Test
    fun requests() {
        val raw = "GET /s/k/f/1.mp3 HTTP/1.1\r\nHost: 192.168.1.5\r\nRange: bytes=0-\r\nIcy-MetaData: 1\r\n\r\n"
        val q = CastHttpServer.Request.parse(raw.byteInputStream())!!
        assertEquals("GET", q.method); assertEquals("/s/k/f/1.mp3", q.path)
        assertEquals("bytes=0-", q.headers["range"]); assertEquals("1", q.headers["icy-metadata"])
        assertNull(CastHttpServer.Request.parse("GET /x HTTP/1.1\r\nHost: a".byteInputStream()))   // never ends
        assertNull(CastHttpServer.Request.parse(("GET /" + "a".repeat(9000) + "\r\n\r\n").byteInputStream()))   // > 8 KB
    }

    @Test
    fun passthrough() {
        val p = CastManager.Companion::passthroughType
        assertEquals("audio/mpeg", p("mp3", "mp3", 44100, 0, 2))
        assertEquals("audio/flac", p("flac", "flac", 96000, 24, 2))
        assertNull(p("flac", "flac", 192000, 24, 2))                   // above 96 kHz
        assertNull(p("flac", "flac", 48000, 16, 6))                    // surround
        assertNull(p("flac", "ogg", 44100, 16, 2))                     // FLAC in Ogg
        assertEquals("audio/mp4", p("aac", "mov,mp4,m4a,3gp,3g2,mj2", 44100, 0, 2))
        assertNull(p("alac", "mov,mp4,m4a,3gp,3g2,mj2", 44100, 16, 2))
        assertEquals("audio/ogg", p("vorbis", "ogg", 44100, 0, 2))
        assertEquals("audio/ogg", p("opus", "ogg", 48000, 0, 2))
        assertEquals("audio/wav", p("pcm_s24le", "wav", 96000, 24, 2))
        assertNull(p("pcm_f32le", "wav", 44100, 32, 2))
        assertNull(p("wmav2", "asf", 44100, 0, 2))
        assertNull(p("wavpack", "wv", 44100, 16, 2))
        assertNull(p("ape", "ape", 44100, 16, 2))
    }

    @Test
    fun renderFormats() {
        assertEquals(44100, CastManager.castRate(44100, 96000))
        assertEquals(96000, CastManager.castRate(192000, 96000))
        assertEquals(88200, CastManager.castRate(176400, 96000))
        assertEquals(88200, CastManager.castRate(352800, 96000))
        assertEquals(44100, CastManager.castRate(88200, 48000))
        assertEquals(48000, CastManager.castRate(50000, 48000))        // no integer ratio above 44.1 kHz
        assertEquals(48000, CastManager.castRate(0, 96000))
        val long = CastManager.renderFormat(24, 96000, 96000, 4 * 3600_000L)   // 4 h at 24/96 would pass 4 GB
        assertEquals(16, long.bits); assertEquals(48000, long.rate)
        val normal = CastManager.renderFormat(24, 96000, 44100, 300_000L)
        assertEquals(24, normal.bits); assertEquals(44100, normal.rate)
    }

    @Test
    fun soundShaping() {
        assertFalse(CastManager.shapesSound(DspParams.NEUTRAL))
        assertTrue(CastManager.shapesSound(DspParams.NEUTRAL.copy(balance = 0.2f)))
        assertTrue(CastManager.shapesSound(DspParams.NEUTRAL.copy(mono = true)))
        assertFalse(CastManager.shapesSound(DspParams.NEUTRAL.copy(eqOn = true)))                   // on but flat
        assertTrue(CastManager.shapesSound(DspParams.NEUTRAL.copy(eqOn = true, autoPreamp = false, manualPreampDb = -3f)))
    }
}
