package dev.trueform.player

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.trueform.player.audio.Native
import dev.trueform.player.audio.NativeDecoder
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt
import kotlin.random.Random

/** Diagnostics only: replays the seek test's sequence for Vorbis with timestamp tracing (logcat TFDEC, TFTEST). */
@RunWith(AndroidJUnit4::class)
class DecoderTraceTest {
    @Test
    fun traceSeeks() {
        DecoderCorpusTest.copyCorpus()
        val rnd = Random(7)
        val files = DecoderCorpusTest.entries.filter { it.channels == 2 && (it.lossless || it.name.startsWith("gap_")) && !it.name.startsWith("bursts") }
        for (e in files) {
            val whole = DecoderCorpusTest.decode(e.name, outRate = e.rate)
            val frames = whole.size / 2
            val traced = e.name.endsWith(".ogg")
            NativeDecoder.open(DecoderCorpusTest.path(e.name), outRate = e.rate).use { d ->
                repeat(10) {
                    val target = if (it == 0) 0 else rnd.nextInt(0, frames - 5000)
                    if (!traced) return@repeat
                    Native.setTrace(true)
                    Log.i("TFTEST", "trace ${e.name} seek #$it $target")
                    d.seek(target.toLong())
                    val got = FloatArray(8000)
                    var n = 0
                    while (n < 8000) {
                        val f = d.read((8000 - n) / 2); if (f == 0) break
                        d.floats.position(0); d.floats.get(got, n, f * 2); n += f * 2
                    }
                    Native.setTrace(false)
                    val sb = StringBuilder("trace ${e.name} #$it got ${n / 2} frames, error per 500:")
                    for (blk in 0 until 8) {
                        var err = 0.0
                        for (i in blk * 500 until (blk + 1) * 500) {
                            val dlt = got[i * 2] - whole[(target + i) * 2]; err += dlt * dlt
                        }
                        sb.append(String.format(" %.4f", sqrt(err / 500)))
                    }
                    Log.i("TFTEST", sb.toString())
                }
            }
        }
    }
}
