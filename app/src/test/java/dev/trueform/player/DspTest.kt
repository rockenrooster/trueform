package dev.trueform.player

import dev.trueform.player.dsp.BandLayout
import dev.trueform.player.dsp.Biquad
import dev.trueform.player.dsp.Coeffs
import dev.trueform.player.dsp.DspChain
import dev.trueform.player.dsp.DspParams
import dev.trueform.player.dsp.EqDesign
import dev.trueform.player.dsp.EqPreset
import dev.trueform.player.dsp.FilterSpec
import dev.trueform.player.dsp.FilterType
import dev.trueform.player.dsp.Limiter
import dev.trueform.player.dsp.ToneTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** docs/plan/06-dsp-equalizer.md, Tests (JVM). */
class DspTest {
    private val rates = listOf(44100.0, 48000.0, 96000.0, 192000.0)

    private fun impulseResponse(k: Coeffs, n: Int): DoubleArray {
        val y = DoubleArray(n)
        var z1 = 0.0; var z2 = 0.0
        for (i in 0 until n) {
            val x = if (i == 0) 1.0 else 0.0
            val out = k.b0 * x + z1
            z1 = k.b1 * x - k.a1 * out + z2
            z2 = k.b2 * x - k.a2 * out
            y[i] = out
        }
        return y
    }

    /** |DFT| at frequency f of a signal, in dB. */
    private fun goertzelDb(x: DoubleArray, f: Double, fs: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in x.indices) { val w = 2 * PI * f * i / fs; re += x[i] * cos(w); im -= x[i] * sin(w) }
        return 20 * log10(sqrt(re * re + im * im))
    }

    @Test
    fun magnitudeFormulaMatchesTheImpulseResponse() {
        for (fs in rates) for (spec in listOf(
            FilterSpec(FilterType.PEAKING, 1000.0, 6.0, 1.4), FilterSpec(FilterType.PEAKING, 60.0, -9.0, 4.3),
            FilterSpec(FilterType.LOW_SHELF, 200.0, 5.0, 0.7), FilterSpec(FilterType.HIGH_SHELF, 6500.0, -4.0, 0.7))) {
            val k = Biquad.design(spec, fs)
            val h = impulseResponse(k, 65536)
            for (f in listOf(50.0, 300.0, 1000.0, 5000.0, 15000.0)) {
                assertEquals("$spec at $f / $fs", goertzelDb(h, f, fs), Biquad.magnitudeDb(k, f, fs), 0.01)
            }
        }
    }

    @Test
    fun designHitsTheRequestedGain() {
        for (fs in rates) {
            for (g in listOf(-12.0, -3.0, 3.0, 12.0)) {
                val p = Biquad.design(FilterSpec(FilterType.PEAKING, 1000.0, g, 1.4), fs)
                assertEquals(g, Biquad.magnitudeDb(p, 1000.0, fs), 0.01)
                val ls = Biquad.design(FilterSpec(FilterType.LOW_SHELF, 300.0, g, 0.7), fs)
                assertEquals(g, Biquad.magnitudeDb(ls, 20.0, fs), 0.15)
                val hs = Biquad.design(FilterSpec(FilterType.HIGH_SHELF, 3000.0, g, 0.7), fs)
                assertEquals(g, Biquad.magnitudeDb(hs, 0.45 * fs, fs), 0.3)
            }
        }
    }

    private fun maxCentreError(layout: BandLayout, sliders: FloatArray): Double {
        val gains = EqDesign.correctedGains(layout, sliders, 12f)
        val filters = EqDesign.designAll(EqDesign.graphicSpecs(layout, gains), 48000.0)
        var worst = 0.0
        for (i in 0 until layout.count) worst = max(worst, abs(EqDesign.responseDb(filters, 48000.0, layout.centres[i]) - sliders[i]))
        return worst
    }

    @Test
    fun correctedBandsFollowTheSliders() {
        for (p in EqPreset.BUILT_IN) {
            for (layout in listOf(BandLayout.B10, BandLayout.B31)) {
                val sliders = EqDesign.convert(p.layout, p.gains, layout)
                val err = maxCentreError(layout, sliders)
                assertTrue("${p.name} on ${layout.count} bands: $err dB", err <= 0.3)
            }
        }
        val alternating = FloatArray(31) { if (it % 2 == 0) 6f else -6f }
        val err = maxCentreError(BandLayout.B31, alternating)
        assertTrue("31 bands alternating: $err dB", err <= 1.0)
    }

    @Test
    fun autoPreampKeepsTheCurveAtOrBelowZero() {
        for (p in EqPreset.BUILT_IN) {
            val gains = EqDesign.correctedGains(BandLayout.B10, p.gains, 12f)
            for (fs in rates) {
                val filters = EqDesign.designAll(EqDesign.graphicSpecs(BandLayout.B10, gains), fs)
                val pre = EqDesign.autoPreampDb(filters, fs, BandLayout.B10.centres)
                var peak = Double.NEGATIVE_INFINITY
                for (f in EqDesign.grid(fs, BandLayout.B10.centres)) peak = max(peak, EqDesign.responseDb(filters, fs, f) + pre)
                assertTrue("${p.name} at $fs: peak after preamp $peak", peak <= 0.05)
            }
        }
        val cutOnly = EqDesign.designAll(EqDesign.graphicSpecs(BandLayout.B10, DoubleArray(10) { -3.0 * (it % 3) }), 48000.0)
        assertEquals(0.0, EqDesign.autoPreampDb(cutOnly, 48000.0), 0.0)
    }

    @Test
    fun limiterNeverExceedsTheCeiling() {
        val rnd = Random(3)
        for (ceilingDb in listOf(0f, -0.5f, -1f)) {
            val ceiling = Math.pow(10.0, ceilingDb / 20.0)
            val lim = Limiter(48000, ceilingDb)
            val n = 48000
            val signals = listOf(
                DoubleArray(n * 2) { (rnd.nextDouble() * 2 - 1) * 4.0 },                                  // +12 dBFS noise
                DoubleArray(n * 2) { val t = it / 2; if ((t / 2400) % 2 == 0) 2.0 * sin(2 * PI * 440 * t / 48000) else 0.1 * sin(2 * PI * 440 * t / 48000) },
                DoubleArray(n * 2) { if (it % 9001 == 0) 8.0 else 0.01 },                               // isolated spikes
                DoubleArray(n * 2) { val t = it / 2; if ((t / 100) % 2 == 0) 1.6 else -1.6 },               // square
            )
            for (s in signals) {
                lim.reset()
                val buf = s.copyOf()
                for (off in 0 until n step 4800) {   // in buffers, as the sink feeds it
                    val part = buf.copyOfRange(off * 2, (off + 4800) * 2)
                    lim.process(part, 4800)
                    System.arraycopy(part, 0, buf, off * 2, part.size)
                }
                for (v in buf) if (abs(v) > ceiling + 1e-6) fail("ceiling $ceilingDb dB: $v")
            }
        }
    }

    @Test
    fun neutralChainIsTransparentApartFromTheLimiterDelay() {
        val chain = DspChain { DspParams.NEUTRAL }
        chain.configure(48000)
        val rnd = Random(9)
        val input = FloatArray(48000 * 2) { (rnd.nextFloat() * 2 - 1) * 0.99f }
        val out = input.copyOf()
        for (off in 0 until 48000 step 4800) {
            val part = out.copyOfRange(off * 2, (off + 4800) * 2)
            chain.apply(part, 4800)
            System.arraycopy(part, 0, out, off * 2, part.size)
        }
        val d = Limiter(48000, 0f).delay
        for (i in d until 48000) {
            if (out[i * 2] != input[(i - d) * 2] || out[i * 2 + 1] != input[(i - d) * 2 + 1]) fail("frame $i differs")
        }
        // limiter off as well: untouched
        val off = DspChain { DspParams.NEUTRAL.copy(limiterOn = false) }
        off.configure(48000)
        assertTrue(!off.apply(input.copyOf(), 48000))
    }

    @Test
    fun everyFilterIsStableAndNothingBlowsUp() {
        val rnd = Random(5)
        for (layout in BandLayout.entries) for (g in listOf(-15.0, 15.0)) for (fs in rates) {
            val specs = EqDesign.graphicSpecs(layout, DoubleArray(layout.count) { g })
            for (k in EqDesign.designAll(specs, fs)) assertTrue("$layout $g dB $fs", Biquad.isStable(k))
            val chain = DspChain { DspParams(true, specs, true, 0f, 0f, false, true, 0f) }
            chain.configure(fs.toInt())
            val buf = FloatArray(4800 * 2)
            repeat((fs * 10 / 4800 / (if (fs > 48000) 4 else 1)).toInt()) {
                for (i in buf.indices) buf[i] = rnd.nextFloat() * 2 - 1
                chain.apply(buf, 4800)
                for (v in buf) if (v.isNaN() || v.isInfinite()) fail("$layout $g $fs: $v")
            }
        }
        for (g in listOf(-12f, 12f)) for (fs in rates) {
            for (k in EqDesign.designAll(ToneTuning.specs(FloatArray(4) { g }), fs)) assertTrue(Biquad.isStable(k))
        }
    }

    @Test
    fun changesAreCrossfadedWithoutAClick() {
        val fs = 48000
        val flat = DspParams(true, emptyList(), false, 0f, 0f, false, false, 0f)
        val boost = flat.copy(filters = EqDesign.graphicSpecs(BandLayout.B10, DoubleArray(10) { if (it == 2) 12.0 else 0.0 }))
        var current = flat
        val chain = DspChain { current }
        chain.configure(fs)
        val n = 4800
        val sine = { start: Int -> FloatArray(n * 2) { val t = start + it / 2; (0.25 * sin(2 * PI * 100 * t / fs)).toFloat() } }
        var t = 0
        repeat(5) { chain.apply(sine(t), n); t += n }
        current = boost
        val during = sine(t); chain.apply(during, n); t += n
        repeat(10) { chain.apply(sine(t), n); t += n }
        val steady = sine(t); chain.apply(steady, n)
        var maxSteady = 0f
        for (i in 1 until n) maxSteady = max(maxSteady, abs(steady[i * 2] - steady[(i - 1) * 2]))
        var maxDuring = 0f
        for (i in 1 until n) maxDuring = max(maxDuring, abs(during[i * 2] - during[(i - 1) * 2]))
        assertTrue("step during the change $maxDuring vs steady $maxSteady", maxDuring <= 2 * maxSteady)
    }

    @Test
    fun silenceSettlesToExactZero() {
        val specs = EqDesign.graphicSpecs(BandLayout.B31, DoubleArray(31) { if (it % 2 == 0) 9.0 else -9.0 })
        val chain = DspChain { DspParams(true, specs, true, 0f, 0f, false, false, 0f) }
        chain.configure(48000)
        val rnd = Random(2)
        val buf = FloatArray(4800 * 2)
        repeat(10) { for (i in buf.indices) buf[i] = rnd.nextFloat() - 0.5f; chain.apply(buf, 4800) }
        repeat(600) { buf.fill(0f); chain.apply(buf, 4800) }   // 60 s
        for (v in buf) assertEquals(0f, v, 0f)
    }

    @Test
    fun bandCountChangesKeepTheCurve() {
        for (p in EqPreset.BUILT_IN) {
            val back = EqDesign.convert(BandLayout.B31, EqDesign.convert(BandLayout.B10, p.gains, BandLayout.B31), BandLayout.B10)
            for (i in 0 until 10) assertEquals(p.name, p.gains[i], back[i], 0.5f)
        }
    }

    @Test
    fun balanceAndMono() {
        val left = DspChain { DspParams.NEUTRAL.copy(limiterOn = false, balance = -1f) }
        left.configure(48000)
        val b = floatArrayOf(0.5f, 0.5f, 0.2f, -0.2f)
        left.apply(b, 2)
        assertEquals(0.5f, b[0], 0f); assertEquals(0f, b[1], 0f)
        val mono = DspChain { DspParams.NEUTRAL.copy(limiterOn = false, mono = true) }
        mono.configure(48000)
        val m = floatArrayOf(1f, 0f, 0.2f, -0.2f)
        repeat(1) { mono.apply(m, 2) }
        // the first buffer after a change is crossfaded from the neutral stage; run a second buffer to check
        val m2 = floatArrayOf(1f, 0f, 0.2f, -0.2f)
        repeat(3000) { mono.apply(m2.copyOf(), 2) }
        val m3 = floatArrayOf(1f, 0f, 0.2f, -0.2f)
        mono.apply(m3, 2)
        assertEquals(0.5f, m3[0], 1e-6f); assertEquals(0.5f, m3[1], 1e-6f); assertEquals(0f, m3[2], 1e-6f)
    }

    @Test
    fun limiterShortcutIsBitIdentical() {   // quiet, a loud burst with overs, the release back to 1, quiet again
        val fs = 44100
        val n = fs * 3
        val sig = DoubleArray(n * 2) { i ->
            val f = i / 2
            val amp = if (f in fs..(fs + fs / 4)) 1.6 else 0.5
            amp * kotlin.math.sin(2 * Math.PI * 440.0 * f / fs + (i % 2) * 0.3)
        }
        val a = Limiter(fs, 0f)
        val b = Limiter(fs, 0f).apply { fastPath = false }
        val outA = sig.copyOf(); val outB = sig.copyOf()
        var pos = 0
        val chunk = 1024
        while (pos < n) {
            val frames = minOf(chunk, n - pos)
            val ca = outA.copyOfRange(pos * 2, (pos + frames) * 2); a.process(ca, frames); ca.copyInto(outA, pos * 2)
            val cb = outB.copyOfRange(pos * 2, (pos + frames) * 2); b.process(cb, frames); cb.copyInto(outB, pos * 2)
            pos += frames
        }
        for (i in outA.indices) if (outA[i] != outB[i]) fail("sample $i differs: ${outA[i]} vs ${outB[i]}")
    }
}