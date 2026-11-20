package dev.trueform.player.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Graphic EQ band layouts (docs/plan/06-dsp-equalizer.md T2). */
enum class BandLayout(val count: Int, val centres: DoubleArray, val octaves: Double) {
    B5(5, doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0), 2.0),
    B10(10, doubleArrayOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0), 1.0),
    B15(15, doubleArrayOf(25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0), 2.0 / 3.0),
    B31(31, doubleArrayOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
        800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0), 1.0 / 3.0);

    /** Q = sqrt(2^B) / (2^B - 1) for a band B octaves wide. */
    val q: Double get() = sqrt(2.0.pow(octaves)) / (2.0.pow(octaves) - 1)

    fun label(i: Int): String {
        val f = centres[i]
        return if (f >= 1000) {
            val k = f / 1000
            if (k == floor(k)) "${k.toInt()}k" else "%.1fk".format(k).replace(".0k", "k")
        } else if (f == floor(f)) "${f.toInt()}" else "%.1f".format(f)
    }

    companion object {
        fun of(count: Int) = entries.firstOrNull { it.count == count } ?: B10
    }
}

/** The four tone knobs (constants to tune by ear; docs/plan/06-dsp-equalizer.md T2). */
object ToneTuning {
    val names = listOf("Sub-bass", "Bass", "Mid", "Treble")
    fun specs(gains: FloatArray): List<FilterSpec> = listOf(
        FilterSpec(FilterType.PEAKING, 45.0, gains[0].toDouble(), 0.8),
        FilterSpec(FilterType.PEAKING, 120.0, gains[1].toDouble(), 0.8),
        FilterSpec(FilterType.PEAKING, 1000.0, gains[2].toDouble(), 0.6),
        FilterSpec(FilterType.HIGH_SHELF, 6500.0, gains[3].toDouble(), 0.7),
    )
}

object EqDesign {
    /** Rate the band-gain correction is computed at (docs/plan/06-dsp-equalizer.md T3). */
    const val DESIGN_RATE = 48000.0

    fun designAll(specs: List<FilterSpec>, fs: Double): List<Coeffs> =
        specs.filter { it.f0 < 0.45 * fs }.map { Biquad.design(it, fs) }

    fun responseDb(filters: List<Coeffs>, fs: Double, f: Double): Double {
        val w = 2 * PI * f / fs
        val c1 = cos(w)
        val c2 = cos(2 * w)
        var sum = 0.0
        for (k in filters) sum += Biquad.magnitudeDbCos(k, c1, c2)
        return sum
    }

    /**
     * Filter gains that make the summed response at each centre equal the slider (neighbouring bands overlap, so
     * naive gains overshoot). 4 iterations of g += 0.8 (target - actual).
     */
    fun correctedGains(layout: BandLayout, targets: FloatArray, rangeDb: Float, fs: Double = DESIGN_RATE): DoubleArray {
        val q = layout.q
        val n = layout.count
        val c1 = DoubleArray(n) { cos(2 * PI * layout.centres[it] / fs) }
        val c2 = DoubleArray(n) { cos(4 * PI * layout.centres[it] / fs) }
        val limit = rangeDb + 6.0
        val g = DoubleArray(n) { targets[it].toDouble() }
        if (targets.all { it == 0f }) return g
        repeat(4) {
            val coeffs = Array(n) { Biquad.design(FilterSpec(FilterType.PEAKING, layout.centres[it], g[it], q), fs) }
            val actual = DoubleArray(n) { i ->
                var s = 0.0
                for (k in coeffs) s += Biquad.magnitudeDbCos(k, c1[i], c2[i])
                s
            }
            for (i in 0 until n) g[i] = (g[i] + 0.8 * (targets[i] - actual[i])).coerceIn(-limit, limit)
        }
        return g
    }

    fun graphicSpecs(layout: BandLayout, gains: DoubleArray): List<FilterSpec> =
        List(layout.count) { FilterSpec(FilterType.PEAKING, layout.centres[it], gains[it], layout.q) }

    /** 256 log-spaced points from 10 Hz up to min(22 kHz, 0.49 fs), plus [extra] (the band centres). */
    fun grid(fs: Double, extra: DoubleArray = DoubleArray(0)): DoubleArray {
        val top = minOf(22000.0, 0.49 * fs)
        val base = DoubleArray(256) { 10.0 * (top / 10.0).pow(it / 255.0) }
        return base + extra.filter { it < top }.toDoubleArray()
    }

    /**
     * The automatic preamp: minus the curve's highest point, rounded down to 0.1 dB ("automatic clipping
     * adjustment"). A curve that only cuts gets 0.
     */
    fun autoPreampDb(filters: List<Coeffs>, fs: Double, extra: DoubleArray = DoubleArray(0)): Double {
        if (filters.isEmpty()) return 0.0
        var peak = 0.0
        for (f in grid(fs, extra)) peak = max(peak, responseDb(filters, fs, f))
        return -kotlin.math.ceil(peak * 10 - 1e-9) / 10
    }

    /**
     * A curve on one layout moved to another: each new band takes the old curve's value at its centre, linear over
     * log-frequency between old centres, flat beyond the ends.
     */
    fun convert(from: BandLayout, gains: FloatArray, to: BandLayout): FloatArray {
        if (from == to) return gains.copyOf()
        val lx = DoubleArray(from.count) { ln(from.centres[it]) }
        return FloatArray(to.count) { i ->
            val x = ln(to.centres[i])
            when {
                x <= lx.first() -> gains.first()
                x >= lx.last() -> gains.last()
                else -> {
                    var j = 0
                    while (lx[j + 1] < x) j++
                    val t = (x - lx[j]) / (lx[j + 1] - lx[j])
                    (gains[j] + (gains[j + 1] - gains[j]) * t).toFloat()
                }
            }
        }
    }
}
