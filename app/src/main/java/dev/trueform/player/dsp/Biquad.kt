package dev.trueform.player.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class FilterType { PEAKING, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS }

/** One filter as the user's curve describes it; independent of the sample rate. */
data class FilterSpec(val type: FilterType, val f0: Double, val gainDb: Double, val q: Double)

/** Normalised biquad coefficients (a0 = 1). */
class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    val isIdentity get() = b0 == 1.0 && b1 == a1 && b2 == a2
}

/** Robert Bristow-Johnson's cookbook (docs/plan/06-dsp-equalizer.md T1). */
object Biquad {
    fun design(spec: FilterSpec, fs: Double): Coeffs {
        val w0 = 2 * PI * spec.f0 / fs
        val c = cos(w0)
        val alpha = sin(w0) / (2 * spec.q)
        val a = 10.0.pow(spec.gainDb / 40)
        return when (spec.type) {
            FilterType.PEAKING -> {
                if (spec.gainDb == 0.0) return Coeffs(1.0, -2 * c / (1 + alpha), (1 - alpha) / (1 + alpha), -2 * c / (1 + alpha), (1 - alpha) / (1 + alpha))
                norm(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            }
            FilterType.LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                norm(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
                    (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s)
            }
            FilterType.HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                norm(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
                    (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
            }
            FilterType.LOW_PASS -> norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            FilterType.HIGH_PASS -> norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
    }

    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    /** |H| in dB at frequency f. */
    fun magnitudeDb(k: Coeffs, f: Double, fs: Double): Double {
        val w = 2 * PI * f / fs
        return magnitudeDbCos(k, cos(w), cos(2 * w))
    }

    /** |H| in dB given cos(w) and cos(2w) (precomputed for a frequency grid). */
    fun magnitudeDbCos(k: Coeffs, c1: Double, c2: Double): Double {
        val num = k.b0 * k.b0 + k.b1 * k.b1 + k.b2 * k.b2 + 2 * (k.b0 * k.b1 + k.b1 * k.b2) * c1 + 2 * k.b0 * k.b2 * c2
        val den = 1 + k.a1 * k.a1 + k.a2 * k.a2 + 2 * (k.a1 + k.a1 * k.a2) * c1 + 2 * k.a2 * c2
        return 10 * log10(num / den)
    }

    /** The stability triangle of a biquad's denominator. */
    fun isStable(k: Coeffs) = kotlin.math.abs(k.a2) < 1 && kotlin.math.abs(k.a1) < 1 + k.a2
}
