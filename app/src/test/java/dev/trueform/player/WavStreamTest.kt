package dev.trueform.player

import dev.trueform.player.audio.PcmEncoding
import dev.trueform.player.audio.PcmSource
import dev.trueform.player.audio.WavHeader
import dev.trueform.player.audio.WavStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

class WavStreamTest {

    /** Frame i is (i, -i) / 2^20: every frame distinct. */
    class FakeSource(private val frames: Int, override val exact: Boolean = true, private val stated: Long = frames.toLong()) : PcmSource {
        var pos = 0L
        var seeks = 0
        override val outRate = 48000
        override val totalFrames get() = stated
        override fun read(dst: FloatArray, offset: Int, maxFrames: Int): Int {
            val n = minOf(maxFrames.toLong(), frames - pos).toInt().coerceAtLeast(0)
            for (k in 0 until n) {
                val v = (pos + k).toFloat() / (1 shl 20)
                dst[offset + k * 2] = v; dst[offset + k * 2 + 1] = -v
            }
            pos += n
            return n
        }
        override fun seek(frame: Long) { pos = frame; seeks++ }
        override fun scanFrames() = frames.toLong()
        override fun close() {}
    }

    private fun readAll(s: WavStream, chunk: Int = 7777): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val b = ByteArray(chunk)
        while (true) { val n = s.read(b, 0, b.size); if (n < 0) break; out.write(b, 0, n) }
        return out.toByteArray()
    }

    @Test
    fun headerFields() {
        for (enc in PcmEncoding.entries) {
            val h = ByteBuffer.wrap(WavHeader.build(enc, 44100, 1000)).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(44, h.capacity())
            assertEquals("RIFF", String(ByteArray(4) { h.get(it) }))
            assertEquals(36 + 1000 * 2 * enc.bytes, h.getInt(4))
            assertEquals(if (enc == PcmEncoding.FLOAT32) 3 else 1, h.getShort(20).toInt())
            assertEquals(2, h.getShort(22).toInt())
            assertEquals(44100, h.getInt(24))
            assertEquals(44100 * 2 * enc.bytes, h.getInt(28))
            assertEquals(2 * enc.bytes, h.getShort(32).toInt())
            assertEquals(8 * enc.bytes, h.getShort(34).toInt())
            assertEquals(1000 * 2 * enc.bytes, h.getInt(40))
        }
    }

    @Test
    fun rf64PastFourGigabytes() {
        val frames = 600_000_000L   // 4.8 GB of float stereo
        val h = ByteBuffer.wrap(WavHeader.build(PcmEncoding.FLOAT32, 192000, frames)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(80, h.capacity())
        assertEquals("RF64", String(ByteArray(4) { h.get(it) }))
        assertEquals(-1, h.getInt(4))
        assertEquals("ds64", String(ByteArray(4) { h.get(12 + it) }))
        assertEquals(frames * 8, h.getLong(28))
        assertEquals(frames, h.getLong(36))
        assertEquals("data", String(ByteArray(4) { h.get(72 + it) }))
        assertEquals(-1, h.getInt(76))
        // just below the threshold stays RIFF
        assertEquals(44, WavHeader.build(PcmEncoding.INT16, 48000, 0xFFFFFFF0L / 4 - 20).size)
    }

    @Test
    fun streamHasExactlyTheDeclaredLength() {
        val s = WavStream(FakeSource(1000), PcmEncoding.FLOAT32)
        assertEquals(44 + 1000 * 8L, s.length)
        val all = readAll(s)
        assertEquals(s.length, all.size.toLong())
        val f = ByteBuffer.wrap(all, 44, 8000).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (i in 0 until 1000) assertEquals(i.toFloat() / (1 shl 20), f.get(i * 2), 0f)
    }

    @Test
    fun padsWithSilenceAndCutsToKnownLength() {
        val longer = readAll(WavStream(FakeSource(1000), PcmEncoding.FLOAT32, knownFrames = 1500))
        assertEquals(44 + 1500 * 8, longer.size)
        val f = ByteBuffer.wrap(longer, 44, 1500 * 8).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertEquals(999f / (1 shl 20), f.get(999 * 2), 0f)
        for (i in 1000 until 1500) assertEquals(0f, f.get(i * 2), 0f)
        val shorter = readAll(WavStream(FakeSource(1000), PcmEncoding.FLOAT32, knownFrames = 600))
        assertEquals(44 + 600 * 8, shorter.size)
    }

    @Test
    fun inexactSourcesAreScanned() {
        val s = WavStream(FakeSource(1000, exact = false, stated = 1234), PcmEncoding.FLOAT32)
        assertEquals(1000, s.totalFrames)
    }

    @Test
    fun seeksAnywhereGiveTheSameBytes() {
        for (enc in PcmEncoding.entries) {
            if (enc == PcmEncoding.INT16) continue   // dither: not repeatable across seeks
            val whole = readAll(WavStream(FakeSource(20000), enc))
            for (off in listOf(0L, 3L, 43L, 44L, 45L, 44L + 7 * 2 * enc.bytes + 1, 44L + 10000 * 2 * enc.bytes, whole.size - 5L)) {
                val s = WavStream(FakeSource(20000), enc)
                s.seek(off)
                val got = readAll(s, 1000)
                assertArrayEquals("$enc at $off", whole.copyOfRange(off.toInt(), whole.size), got)
            }
        }
    }

    @Test
    fun int24RoundsAndInt16DithersWithinOneStep() {
        val b24 = readAll(WavStream(FakeSource(3000), PcmEncoding.INT24))
        for (i in 0 until 3000) {
            val o = 44 + i * 6
            val v = (b24[o].toInt() and 0xff) or ((b24[o + 1].toInt() and 0xff) shl 8) or (b24[o + 2].toInt() shl 16)
            assertEquals((i.toFloat() / (1 shl 20) * 8388607f).roundToInt(), v)
        }
        val b16 = readAll(WavStream(FakeSource(3000), PcmEncoding.INT16))
        for (i in 0 until 3000) {
            val o = 44 + i * 4
            val v = ((b16[o].toInt() and 0xff) or (b16[o + 1].toInt() shl 8)).toShort().toInt()
            assertTrue("frame $i: $v", abs(v - i.toFloat() / (1 shl 20) * 32767f) <= 1.5f)
        }
    }
}
