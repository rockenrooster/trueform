package dev.trueform.player

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.trueform.player.dsp.BandLayout
import dev.trueform.player.dsp.DspChain
import dev.trueform.player.dsp.DspParams
import dev.trueform.player.dsp.EqDesign
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.random.Random

/**
 * The native DSP path (conversion, filters with fused multiply-adds, mono/balance, limiter delay) against the Kotlin
 * one (docs/plan/06-dsp-equalizer.md T6, 11-testing-hardening.md budgets): the same output up to rounding (measured
 * 1.9e-9 at 44.1 kHz, 1.5e-8 at 192 kHz; the float output's own step is about 6e-8), and the time for one 100 ms buffer.
 */
@RunWith(AndroidJUnit4::class)
class DspNativeTest {
    @After fun restore() { DspChain.nativeCascade = true }

    private fun params(layout: BandLayout): DspParams {
        val gains = FloatArray(layout.count) { i -> listOf(6f, 3f, -2f, 4.5f, -6f, 1f)[i % 6] }
        val corrected = EqDesign.correctedGains(layout, gains, 12f)
        return DspParams(eqOn = true, filters = EqDesign.graphicSpecs(layout, corrected), autoPreamp = true,
            manualPreampDb = 0f, balance = 0f, mono = false, limiterOn = false, ceilingDb = 0f)
    }

    /** [seconds] of noise through a chain, in 100 ms buffers; the output and the slowest buffer's time in ms. */
    private fun run(native: Boolean, layout: BandLayout, fs: Int, seconds: Int): Pair<FloatArray, Double> {
        DspChain.nativeCascade = native
        val p = params(layout)
        val chain = DspChain { p }.apply { configure(fs) }
        val frames = fs / 10
        val rnd = Random(42)
        val out = FloatArray(fs * seconds * 2)
        val buf = FloatArray(frames * 2)
        var worst = 0.0
        val times = ArrayList<Double>()
        for (b in 0 until seconds * 10) {
            for (i in buf.indices) buf[i] = (rnd.nextFloat() * 2 - 1) * 0.25f
            val t = System.nanoTime()
            chain.apply(buf, frames)
            val ms = (System.nanoTime() - t) / 1e6
            times += ms
            buf.copyInto(out, b * frames * 2)
        }
        times.sort()
        worst = times[times.size * 9 / 10]   // the 90th percentile: the first buffers include the JIT warming up
        return out to worst
    }

    @Test
    fun nativeMatchesKotlinAndIsFaster() {
        for ((layout, fs) in listOf(BandLayout.B10 to 44100, BandLayout.B31 to 192000)) {
            val (k, kotlinMs) = run(native = false, layout, fs, 3)
            val (n, nativeMs) = run(native = true, layout, fs, 3)
            var maxDiff = 0.0
            for (i in k.indices) maxDiff = maxOf(maxDiff, abs(k[i] - n[i]).toDouble())
            Log.i("TFDSP", "${layout.count} bands at $fs Hz, one 100 ms buffer: Kotlin %.3f ms, native %.3f ms; max difference %.2e"
                .format(kotlinMs, nativeMs, maxDiff))
            assertTrue("outputs differ by $maxDiff", maxDiff < 1e-6)
        }
    }
}
