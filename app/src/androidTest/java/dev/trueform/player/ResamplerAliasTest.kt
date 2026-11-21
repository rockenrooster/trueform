package dev.trueform.player

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.trueform.player.audio.NativeDecoder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * docs/plan/01-native-decoder.md T6 item 7: the resampler's alias level. One-second tones made at 96 kHz are played at
 * 44.1 kHz; a tone above the output's Nyquist frequency (22.05 kHz) must vanish, so whatever comes out is alias. The
 * levels are logged per quality for PROGRESS.md (the plan builds with libsoxr if aliasing shows above -100 dB); the
 * test fails only on a sample beyond full scale or a passband tone that is not kept.
 */
@RunWith(AndroidJUnit4::class)
class ResamplerAliasTest {
    private val amp = 0.891   // -1 dBFS

    private fun tone96(hz: Int, file: File) {
        val rate = 96_000
        val data = ByteBuffer.allocate(rate * 8).order(ByteOrder.LITTLE_ENDIAN)
        val fade = rate / 20   // 50 ms raised-cosine fades: an abrupt start would make any filter ring past the tone's level
        for (i in 0 until rate) {
            val env = when { i < fade -> 0.5 - 0.5 * kotlin.math.cos(PI * i / fade); i >= rate - fade -> 0.5 - 0.5 * kotlin.math.cos(PI * (rate - 1 - i) / fade); else -> 1.0 }
            val v = (amp * env * sin(2 * PI * hz * i / rate)).toFloat()
            data.putFloat(v).putFloat(v)
        }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + rate * 8).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(3).putShort(2).putInt(rate).putInt(rate * 8).putShort(8).putShort(32)   // float, stereo
            .put("data".toByteArray()).putInt(rate * 8)
        file.outputStream().use { it.write(h.array()); it.write(data.array()) }
    }

    /** Level of the left channel's middle half against the input tone, in dB, and the output's peak. */
    private fun level(path: String, quality: Int): Pair<Double, Float> {
        NativeDecoder.open(path, outRate = 44_100, quality = quality).use { d ->
            val left = ArrayList<Float>(44_100)
            var peak = 0f
            while (true) {
                val f = d.read()
                if (f == 0) break
                for (i in 0 until f) { val v = d.floats.get(i * 2); left.add(v); peak = maxOf(peak, abs(v)) }
            }
            val mid = left.subList(left.size / 4, left.size * 3 / 4)
            val rms = sqrt(mid.sumOf { it.toDouble() * it } / mid.size)
            return 20 * log10(rms / (amp / sqrt(2.0)) + 1e-12) to peak
        }
    }

    @Test
    fun aliasLevels() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "alias").apply { mkdirs() }
        val passband = listOf(1_000, 10_000, 20_000)
        val above = listOf(22_600, 23_000, 24_000, 26_000, 30_000, 40_000, 47_000)
        for (q in listOf(NativeDecoder.QUALITY_NORMAL, NativeDecoder.QUALITY_HIGH, NativeDecoder.QUALITY_VERY_HIGH)) {
            val line = StringBuilder("quality $q:")
            var worstFrom24 = -300.0
            for (hz in passband + above) {
                val file = File(dir, "tone$hz.wav").also { if (!it.exists()) tone96(hz, it) }
                val (db, peak) = level(file.absolutePath, q)
                line.append(" ${hz / 1000.0} kHz %.1f dB;".format(db))
                assertTrue("quality $q, $hz Hz: peak $peak beyond full scale", peak <= 1f)
                if (hz in passband && hz <= 10_000) assertTrue("quality $q, $hz Hz not kept: $db dB", abs(db) < 0.1)
                if (hz >= 24_000) worstFrom24 = maxOf(worstFrom24, db)
            }
            Log.i(TAG, "$line worst alias from 24 kHz up: %.1f dB".format(worstFrom24))
        }
    }

    private companion object { const val TAG = "TFTEST" }
}
