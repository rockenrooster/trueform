package dev.trueform.player.dsp

import dev.trueform.player.audio.FrameProcessor
import kotlin.math.min
import kotlin.math.pow

/** Immutable DSP parameters, published by DspController (docs/plan/06-dsp-equalizer.md T6). */
data class DspParams(
    val eqOn: Boolean,
    /** Gains already corrected (T3). Empty: flat. */
    val filters: List<FilterSpec>,
    val autoPreamp: Boolean,
    val manualPreampDb: Float,
    val balance: Float,
    val mono: Boolean,
    val limiterOn: Boolean,
    val ceilingDb: Float,
) {
    companion object {
        val NEUTRAL = DspParams(false, emptyList(), true, 0f, 0f, false, true, 0f)
    }
}

/**
 * One audio consumer's DSP: preamp, EQ filters, balance/mono, limiter, in 64-bit (T6). Local playback has one,
 * each cast render its own. A parameter change is picked up at the start of the next buffer and crossfaded over
 * 2048 frames: no clicks, no zipper noise. Neutral stages are skipped; with everything neutral and the limiter off
 * the audio is untouched. Not thread-safe: one audio thread.
 */
class DspChain(private val params: () -> DspParams) : FrameProcessor {
    private var fs = 0
    private var curParams: DspParams? = null
    private var cur: Stage? = null
    private var old: Stage? = null
    private var fadeDone = 0
    private var work = DoubleArray(0)
    private var oldWork = DoubleArray(0)
    private var limiterCeiling = Float.NaN
    @Volatile var limiter: Limiter? = null
        private set
    /** The preamp in use, for the UI ("Auto -4.5 dB"). */
    @Volatile var preampDb = 0.0
        private set

    fun configure(sampleRate: Int) {
        if (sampleRate == fs) return
        fs = sampleRate
        curParams = null
        cur = null
        old = null
        limiter = null
        limiterCeiling = Float.NaN
    }

    fun reset() {
        cur?.clear()
        old = null
        limiter?.reset()
    }

    override fun process(buf: FloatArray, frames: Int) {
        apply(buf, frames)
    }

    /** Processes [frames] interleaved stereo frames of [buf] in place. False when it left them untouched. */
    fun apply(buf: FloatArray, frames: Int): Boolean {
        if (fs <= 0 || frames <= 0) return false
        val p = params()
        if (p !== curParams) switchTo(p)
        val st = cur!!
        if (!p.limiterOn) limiter = null
        else if (limiter == null || limiterCeiling != p.ceilingDb) {
            limiter = Limiter(fs, p.ceilingDb); limiterCeiling = p.ceilingDb
        }
        val lim = limiter
        val fading = old
        if (st.neutral && fading == null && lim == null) return false

        val n = frames * 2
        if (work.size < n) work = DoubleArray(n)
        if (fading == null && nativeCascade) {
            // The common case in native code: one pass in (conversion, preamp, filters, mono, balance), the limiter,
            // one pass out. Measured on the Moto, these Kotlin loops were about a sixth of the playback thread's time.
            val pre = if (st.preampDb != 0.0) st.preamp else 1.0
            val peak = dev.trueform.player.audio.Native.stageIn(buf, frames, work, pre, st.flat, st.z, st.mono, st.gl, st.gr)
            lim?.process(work, frames, peak)
            dev.trueform.player.audio.Native.stageOut(work, frames, buf, outPeaks)
            if (outPeaks[0] > peakL) peakL = outPeaks[0]
            if (outPeaks[1] > peakR) peakR = outPeaks[1]
            return true
        }
        for (i in 0 until n) work[i] = buf[i].toDouble()
        if (fading != null) {
            val fadeFrames = min(FADE - fadeDone, frames)
            if (oldWork.size < fadeFrames * 2) oldWork = DoubleArray(fadeFrames * 2)
            System.arraycopy(work, 0, oldWork, 0, fadeFrames * 2)
            fading.run(oldWork, fadeFrames)
            st.run(work, frames)
            for (i in 0 until fadeFrames) {
                val w = (fadeDone + i + 1).toDouble() / FADE
                work[i * 2] = oldWork[i * 2] * (1 - w) + work[i * 2] * w
                work[i * 2 + 1] = oldWork[i * 2 + 1] * (1 - w) + work[i * 2 + 1] * w
            }
            fadeDone += fadeFrames
            if (fadeDone >= FADE) old = null
        } else {
            st.run(work, frames)
        }
        lim?.process(work, frames)
        var pl = peakL
        var pr = peakR
        var i = 0
        while (i < n) {
            val l = work[i].toFloat(); val r = work[i + 1].toFloat()
            buf[i] = l; buf[i + 1] = r
            val al = if (l < 0) -l else l; val ar = if (r < 0) -r else r
            if (al > pl) pl = al
            if (ar > pr) pr = ar
            i += 2
        }
        peakL = pl
        peakR = pr
        return true
    }

    /** Output peaks since the last [takePeaks] (the EQ screen's meters); only while the chain processes. */
    @Volatile private var peakL = 0f
    @Volatile private var peakR = 0f
    private val outPeaks = FloatArray(2)
    fun takePeaks(): Pair<Float, Float> = (peakL to peakR).also { peakL = 0f; peakR = 0f }

    private fun switchTo(p: DspParams) {
        val next = Stage.build(p, fs)
        val prev = cur
        if (prev != null) {
            next.adoptState(prev)
            if (!(prev.neutral && next.neutral)) {
                old = prev
                fadeDone = 0
            }
        }
        cur = next
        curParams = p
        preampDb = next.preampDb
    }

    /** Preamp, filters, balance and mono for one parameter set at one sample rate. */
    private class Stage(
        val coeffs: Array<Coeffs>,
        val preampDb: Double,
        val gl: Double,
        val gr: Double,
        val mono: Boolean,
    ) {
        val z = DoubleArray(coeffs.size * 4)
        val preamp = 10.0.pow(preampDb / 20)
        /** b0 b1 b2 a1 a2 per filter, for the native cascade (several times cheaper than the Kotlin loop on a phone). */
        val flat = DoubleArray(coeffs.size * 5).also { a ->
            coeffs.forEachIndexed { f, k -> a[f * 5] = k.b0; a[f * 5 + 1] = k.b1; a[f * 5 + 2] = k.b2; a[f * 5 + 3] = k.a1; a[f * 5 + 4] = k.a2 }
        }
        val neutral = coeffs.isEmpty() && preampDb == 0.0 && gl == 1.0 && gr == 1.0 && !mono

        fun clear() = z.fill(0.0)

        fun adoptState(other: Stage) {
            if (other.coeffs.size == coeffs.size) System.arraycopy(other.z, 0, z, 0, z.size)
        }

        fun run(b: DoubleArray, frames: Int) {
            val n = frames * 2
            if (preampDb != 0.0) for (i in 0 until n) b[i] *= preamp
            if (coeffs.isNotEmpty() && nativeCascade) dev.trueform.player.audio.Native.cascade(b, frames, flat, z)
            else for (f in coeffs.indices) {
                val k = coeffs[f]
                val b0 = k.b0; val b1 = k.b1; val b2 = k.b2; val a1 = k.a1; val a2 = k.a2
                var z1l = z[f * 4]; var z2l = z[f * 4 + 1]; var z1r = z[f * 4 + 2]; var z2r = z[f * 4 + 3]
                var i = 0
                while (i < n) {
                    val xl = b[i]
                    val yl = b0 * xl + z1l
                    z1l = b1 * xl - a1 * yl + z2l
                    z2l = b2 * xl - a2 * yl
                    b[i] = yl
                    val xr = b[i + 1]
                    val yr = b0 * xr + z1r
                    z1r = b1 * xr - a1 * yr + z2r
                    z2r = b2 * xr - a2 * yr
                    b[i + 1] = yr
                    i += 2
                }
                // flush denormal-range states (long silences)
                z[f * 4] = if (kotlin.math.abs(z1l) < 1e-30) 0.0 else z1l
                z[f * 4 + 1] = if (kotlin.math.abs(z2l) < 1e-30) 0.0 else z2l
                z[f * 4 + 2] = if (kotlin.math.abs(z1r) < 1e-30) 0.0 else z1r
                z[f * 4 + 3] = if (kotlin.math.abs(z2r) < 1e-30) 0.0 else z2r
            }
            if (mono) {
                var i = 0
                while (i < n) { val m = (b[i] + b[i + 1]) * 0.5; b[i] = m; b[i + 1] = m; i += 2 }
            }
            if (gl != 1.0 || gr != 1.0) {
                var i = 0
                while (i < n) { b[i] *= gl; b[i + 1] *= gr; i += 2 }
            }
        }

        companion object {
            fun build(p: DspParams, fs: Int): Stage {
                val rate = fs.toDouble()
                // Gain filters within 0.02 dB of flat are left out: the band correction gives bands left at 0 dB gains
                // like -0.011 or -0.0002 dB, inaudible, yet each cost as much as a real band (half the filters of a
                // typical preset). Their effect on the curve is at most 0.02 dB at their own frequency.
                val used = p.filters.filter { it.type == FilterType.LOW_PASS || it.type == FilterType.HIGH_PASS || kotlin.math.abs(it.gainDb) >= NEGLIGIBLE_DB }
                val all = if (p.eqOn) EqDesign.designAll(used, rate) else emptyList()
                val active = all.filter { !it.isIdentity }
                val preamp = when {
                    !p.eqOn -> 0.0
                    p.autoPreamp -> EqDesign.autoPreampDb(active, rate, p.filters.map { it.f0 }.toDoubleArray())
                    else -> p.manualPreampDb.toDouble()
                }
                val bal = p.balance.toDouble().coerceIn(-1.0, 1.0)
                return Stage(active.toTypedArray(), preamp, min(1.0, 1 - bal), min(1.0, 1 + bal), p.mono)
            }
        }
    }

    companion object {
        private const val FADE = 2048
        /** A gain filter closer to flat than this is not run (see Stage.build). */
        const val NEGLIGIBLE_DB = 0.02
        /** The EQ's filters in native code where the library loads; a device test switches it off to compare. */
        @Volatile var nativeCascade: Boolean = dev.trueform.player.audio.NativeCheck.loaded
    }
}
