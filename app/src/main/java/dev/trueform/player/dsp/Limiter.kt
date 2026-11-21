package dev.trueform.player.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stereo-linked look-ahead peak limiter (docs/plan/06-dsp-equalizer.md T5). With window L, a linear attack averaged
 * over L and a delay of L - 1, no output sample exceeds the ceiling, whatever the input. When nothing exceeds it the
 * gain is exactly 1 and the output is the input delayed by [delay] frames, bit for bit.
 */
class Limiter(fs: Int, ceilingDb: Float) {
    val window = max(8, (0.005 * fs).roundToInt())
    val delay = window - 1
    private val ceiling = 10.0.pow20(ceilingDb)
    private val k = 1 - exp(-1.0 / (0.150 * fs))       // 150 ms release

    // Delay line (interleaved stereo), ring of [delay] frames: frame t leaves at t + delay, while need(t) is still
    // inside every window the averaged gain at that moment is made of.
    private val line = DoubleArray(delay * 2)
    private var linePos = 0
    // Sliding minimum of the per-frame "needed gain" over the last [window] frames: a monotonic deque.
    private val dqVal = DoubleArray(window + 1)
    private val dqAge = LongArray(window + 1)
    private var dqHead = 0
    private var dqLen = 0
    // Running mean of the sliding minimum over the last [window] frames.
    private val mRing = DoubleArray(window) { 1.0 }
    private var mPos = 0
    private var mSum = window.toDouble()
    private var mBelowOne = 0
    private var gain = 1.0
    private var t = 0L

    /** Peak-held gain reduction in dB (<= 0), for the "LIMIT" indicator. Read from any thread. */
    @Volatile var reductionDb = 0f
        private set
    private var minGainSinceRead = 1.0

    fun reset() {
        line.fill(0.0); linePos = 0
        dqHead = 0; dqLen = 0
        mRing.fill(1.0); mPos = 0; mSum = window.toDouble(); mBelowOne = 0
        gain = 1.0; t = 0
    }

    /** Processes [frames] interleaved stereo frames of [buf] in place (doubles); [knownPeak]: their largest magnitude, if known. */
    fun process(buf: DoubleArray, frames: Int, knownPeak: Double = -1.0) {
        if (fastPath && mBelowOne == 0 && gain == 1.0 && (if (knownPeak >= 0) knownPeak else peak(buf, frames)) <= ceiling) {
            delayOnly(buf, frames)
            return
        }
        for (i in 0 until frames) {
            val l = buf[i * 2]
            val r = buf[i * 2 + 1]
            val peak = max(abs(l), abs(r))
            val need = if (peak > ceiling) ceiling / peak else 1.0
            // sliding minimum: drop larger values from the back, expired ones from the front
            while (dqLen > 0 && dqVal[(dqHead + dqLen - 1) % dqVal.size] >= need) dqLen--
            val slot = (dqHead + dqLen) % dqVal.size
            dqVal[slot] = need; dqAge[slot] = t; dqLen++
            while (dqAge[dqHead] <= t - window) { dqHead = (dqHead + 1) % dqVal.size; dqLen-- }
            val m = dqVal[dqHead]
            // mean of m over the window
            val old = mRing[mPos]
            if (old < 1.0) mBelowOne--
            if (m < 1.0) mBelowOne++
            mSum += m - old
            mRing[mPos] = m
            mPos = (mPos + 1) % window
            val b = if (mBelowOne == 0) 1.0 else mSum / window
            var g = gain + (1 - gain) * k
            if (g > 0.999999) g = 1.0
            if (b < g) g = b
            gain = g
            if (g < minGainSinceRead) minGainSinceRead = g
            // delayed output
            val dl = line[linePos * 2]
            val dr = line[linePos * 2 + 1]
            line[linePos * 2] = l
            line[linePos * 2 + 1] = r
            linePos = (linePos + 1) % delay
            var ol = dl * g
            var or = dr * g
            if (g < 1.0) {   // rounding safety only: exact arithmetic already stays within the ceiling
                if (ol > ceiling) ol = ceiling else if (ol < -ceiling) ol = -ceiling
                if (or > ceiling) or = ceiling else if (or < -ceiling) or = -ceiling
            }
            buf[i * 2] = ol
            buf[i * 2 + 1] = or
            t++
        }
        reductionDb = if (minGainSinceRead < 1.0) (20 * log10(minGainSinceRead)).toFloat() else 0f
    }

    /** Tests turn the shortcut off to compare it with the full loop. */
    internal var fastPath = true

    private fun peak(buf: DoubleArray, frames: Int): Double {
        var p = 0.0
        for (i in 0 until frames * 2) { val a = abs(buf[i]); if (a > p) p = a }
        return p
    }

    /**
     * The common case, without the per-sample bookkeeping (measured: about 2 % of a core on the Moto): nothing in the
     * window needs a reduction and the gain is exactly 1, so every needed gain is 1, the sliding minimum and its mean
     * are 1, the gain stays 1, and the output is the input delayed — what the full loop computes, bit for bit.
     */
    private fun delayOnly(buf: DoubleArray, frames: Int) {
        if (DspChain.nativeCascade) {
            linePos = dev.trueform.player.audio.Native.delayLine(buf, frames, line, linePos, delay)
        } else {
            var pos = linePos
            for (i in 0 until frames) {
                val j = pos * 2
                val dl = line[j]; val dr = line[j + 1]
                line[j] = buf[i * 2]; line[j + 1] = buf[i * 2 + 1]
                buf[i * 2] = dl; buf[i * 2 + 1] = dr
                pos = if (pos + 1 == delay) 0 else pos + 1
            }
            linePos = pos
        }
        dqLen = 0                                   // no window entry below 1: no candidates for the minimum
        mPos = ((mPos + frames.toLong()) % window).toInt()   // the mean's ring holds only 1.0 (mBelowOne == 0)
        t += frames
    }

    /** Resets the peak hold of [reductionDb]; the UI calls this after showing a value. */
    fun clearPeak() {
        minGainSinceRead = 1.0
    }

    private fun Double.pow20(db: Float) = Math.pow(this, db / 20.0)
}
