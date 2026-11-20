package dev.trueform.player

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.trueform.player.audio.NativeDecoder
import dev.trueform.player.audio.TrackProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** docs/plan/01-native-decoder.md, T6: the decoder against the synthetic corpus in androidTest/assets/media. */
@RunWith(AndroidJUnit4::class)
class DecoderCorpusTest {

    data class Entry(val name: String, val source: String, val rate: Int, val frames: Int, val lossless: Boolean, val channels: Int)

    companion object {
        private const val TAG = "TFTEST"
        lateinit var dir: File
        lateinit var entries: List<Entry>
        lateinit var hostile: List<String>

        @JvmStatic
        @BeforeClass
        fun copyCorpus() {
            val inst = InstrumentationRegistry.getInstrumentation()
            val assets = inst.context.assets
            dir = File(inst.targetContext.cacheDir, "corpus").apply { mkdirs() }
            for (name in assets.list("media")!!) {
                assets.open("media/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            }
            val m = JSONObject(File(dir, "manifest.json").readText())
            val files = m.getJSONArray("files")
            entries = (0 until files.length()).map { i ->
                val o = files.getJSONObject(i)
                Entry(o.getString("name"), o.getString("source"), o.getInt("rate"), o.getInt("frames"),
                    o.getBoolean("lossless"), o.getInt("channels"))
            }
            val h = m.getJSONArray("hostile")
            hostile = (0 until h.length()).map { h.getString(it) }
        }

        fun path(name: String) = File(dir, name).absolutePath

        /** The whole file as interleaved stereo floats. */
        fun decode(name: String, outRate: Int = 0): FloatArray {
            NativeDecoder.open(path(name), outRate = outRate).use { d ->
                var out = FloatArray(1 shl 16)
                var n = 0
                while (true) {
                    val f = d.read()
                    if (f == 0) break
                    if (n + f * 2 > out.size) out = out.copyOf(maxOf(out.size * 2, n + f * 2))
                    d.floats.position(0)
                    d.floats.get(out, n, f * 2)
                    n += f * 2
                }
                return out.copyOf(n)
            }
        }

        fun left(x: FloatArray) = FloatArray(x.size / 2) { x[it * 2] }

        /** Lag (-max..max) of b relative to a with the highest normalised correlation, over a middle window. */
        fun bestLag(a: FloatArray, b: FloatArray, maxLag: Int): Pair<Int, Double> {
            val start = maxLag + 2000
            val len = minOf(20000, minOf(a.size, b.size) - start - maxLag - 1)
            var best = 0
            var bestC = -2.0
            for (lag in -maxLag..maxLag) {
                var ab = 0.0; var aa = 0.0; var bb = 0.0
                for (i in start until start + len) {
                    val x = a[i].toDouble(); val y = b[i + lag].toDouble()
                    ab += x * y; aa += x * x; bb += y * y
                }
                val c = ab / sqrt(aa * bb + 1e-30)
                if (c > bestC) { bestC = c; best = lag }
            }
            return best to bestC
        }
    }

    @Test
    fun decodesEveryFileAndReportsLengths() {
        val report = StringBuilder("\nfile                 stated     decoded    expected  rate\n")
        for (e in entries) {
            NativeDecoder.open(path(e.name)).use { d ->
                var frames = 0L
                while (true) {
                    val f = d.read()
                    if (f == 0) break
                    frames += f
                }
                report.append(String.format("%-20s %-10d %-10d %-9d %d %s\n", e.name, d.totalFrames, frames,
                    e.frames.toLong() * d.outRate / e.rate, d.outRate, if (d.exact) "" else "(estimate)"))
                assertTrue("${e.name}: decoded nothing", frames > 0)
            }
        }
        Log.i(TAG, report.toString())
    }

    @Test
    fun losslessIsBitExact() {
        for (e in entries.filter { it.lossless && it.channels == 2 && it.source != it.name && it.source.endsWith(".wav") }) {
            val ref = decode(e.source)
            val got = decode(e.name)
            assertEquals("${e.name}: length", ref.size, got.size)
            for (i in ref.indices) {
                if (ref[i] != got[i]) fail("${e.name}: sample $i differs: ${got[i]} vs ${ref[i]}")
            }
        }
    }

    @Test
    fun monoAndSurroundDownmix() {
        val mono = decode("tone_mono.flac")
        assertEquals(132300 * 2, mono.size)
        for (i in 0 until mono.size / 2) assertEquals("mono duplicated", mono[i * 2], mono[i * 2 + 1])
        // -ac 1 averaged 1 kHz and 440 Hz at 0.5 each: peaks near 0.5; duplicated at full level, not -3 dB.
        assertTrue("mono level", mono.maxOf { abs(it) } > 0.45f)
        val surround = decode("tone_51.flac")
        assertEquals(132300 * 2, surround.size)
        assertTrue("downmix within full scale", surround.all { abs(it) <= 1.0f })
    }

    @Test
    fun frameCountsAreExact() {
        val gapless = setOf("tone_cbr320.mp3", "tone_v2.mp3", "tone_aac.m4a", "tone.ogg", "badtag.mp3", "tone.opus") +
            entries.filter { it.name.startsWith("gap_") }.map { it.name }
        for (e in entries.filter { it.lossless || it.name in gapless }) {
            // Opus decodes at 48 kHz: ask for the source rate so the counts compare.
            val got = decode(e.name, outRate = e.rate).size / 2
            if (e.name == "tone.opus") assertTrue("tone.opus frames $got", abs(got - e.frames) <= 2)
            else assertEquals("${e.name}: frames", e.frames, got)
        }
        // The stated length is what the WAV stream declares: it must match for gapless files too.
        for (name in listOf("tone_aac.m4a", "gap_a.m4a", "gap_a.opus", "tone_v2.mp3", "gap_b.ogg")) {
            NativeDecoder.open(path(name)).use { d ->
                val expected = entries.first { it.name == name }.frames.toLong() * d.outRate / entries.first { it.name == name }.rate
                assertTrue("$name stated ${d.totalFrames} vs $expected", abs(d.totalFrames - expected) <= 1)
            }
        }
    }

    @Test
    fun headerlessMp3IsAnEstimateThatScanImproves() {
        NativeDecoder.open(path("tone_noheader.mp3")).use { d ->
            val scanned = d.scanFrames()
            Log.i(TAG, "tone_noheader.mp3: stated ${d.totalFrames} exact=${d.exact} scanned $scanned")
            assertTrue("scan within 3 MP3 frames: $scanned", abs(scanned - 132300) <= 3456)
            var frames = 0L
            while (true) { val f = d.read(); if (f == 0) break; frames += f }   // still decodes from 0 after the scan
            assertTrue("decoded after scan: $frames", abs(frames - 132300) <= 3456)
        }
    }

    /** The sweep (not periodic, unlike the tone) decoded from a lossy file lines up with the lossless one. */
    @Test
    fun lossyIsAlignedWithTheSource() {
        val ref = left(decode("gap_a.flac"))
        for (ext in listOf("mp3", "m4a", "ogg", "opus")) {
            val got = left(decode("gap_a.$ext", outRate = 48000))
            val (lag, c) = bestLag(ref, got, 1200)
            Log.i(TAG, "gap_a.$ext: lag $lag corr $c")
            assertTrue("gap_a.$ext: correlation $c", c > 0.98)
            // libopus's pre-skip leaves its output 14 samples (0.3 ms) early on this file: the encoder's, not ours.
            assertTrue("gap_a.$ext: lag $lag", abs(lag) <= if (ext == "opus") 16 else 0)
        }
        // The tone is periodic, so only its correlation means something; WMA has no gapless information.
        val tone = left(decode("tone.wav"))
        for (name in listOf("tone_cbr320.mp3", "tone_v2.mp3", "tone_aac.m4a", "tone.ogg", "tone.opus", "tone.wma")) {
            val (lag, c) = bestLag(tone, left(decode(name, outRate = 44100)), 40)
            Log.i(TAG, "$name: lag $lag corr $c")
            assertTrue("$name: correlation $c", c > 0.99)
        }
    }

    @Test
    fun seeksLandOnTheFrame() {
        val rnd = Random(7)
        val files = entries.filter { it.channels == 2 && (it.lossless || it.name.startsWith("gap_")) && !it.name.startsWith("bursts") } +
            entries.filter { it.name == "tone_noheader.mp3" }
        val problems = mutableListOf<String>()
        for (e in files) try {
            val rate = e.rate
            val whole = decode(e.name, outRate = rate)
            val frames = whole.size / 2
            NativeDecoder.open(path(e.name), outRate = rate).use { d ->
                repeat(10) {
                    val target = if (it == 0) 0 else rnd.nextInt(0, frames - 5000)
                    d.seek(target.toLong())
                    val got = FloatArray(8000)
                    var n = 0
                    while (n < 8000) {
                        val f = d.read((8000 - n) / 2)
                        if (f == 0) break
                        d.floats.position(0); d.floats.get(got, n, f * 2); n += f * 2
                    }
                    if (e.lossless) {
                        for (i in 0 until 8000) {
                            val w = whole[target * 2 + i]
                            if (got[i] != w) fail("${e.name}: seek $target sample $i: ${got[i]} vs $w")
                        }
                    } else {
                        // Where the seek really landed: the lag of the output within the straight decode.
                        val a = left(whole); val b = left(got)
                        var best = 0; var bestC = -2.0
                        for (lag in -1200..1200) {
                            val s = target + lag
                            if (s < 0 || s + 3000 > a.size) continue
                            var ab = 0.0; var aa = 0.0; var bb = 0.0
                            for (i in 1000 until 3000) { val x = a[s + i].toDouble(); val y = b[i].toDouble(); ab += x * y; aa += x * x; bb += y * y }
                            val c = ab / sqrt(aa * bb + 1e-30)
                            if (c > bestC) { bestC = c; best = lag }
                        }
                        if (best != 0) Log.i(TAG, "${e.name}: seek $target landed at lag $best (corr $bestC)")
                        if (e.name != "tone_noheader.mp3") {
                            assertEquals("${e.name}: seek $target lag (corr $bestC)", 0, best)
                            assertTrue("${e.name}: seek $target correlation $bestC", bestC > 0.98)
                        }
                    }
                }
            }
        } catch (t: AssertionError) {
            problems += t.message ?: e.name
        }
        problems.forEach { Log.i(TAG, "seek problem: $it") }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** No sample added or lost at a track boundary, no time shift. Lossy codecs leave small edge errors at a cut. */
    @Test
    fun gaplessPairsJoin() {
        val ref = left(decode("gap_a.flac") + decode("gap_b.flac"))
        for (ext in listOf("flac", "mp3", "m4a", "ogg", "opus")) {
            val a = decode("gap_a.$ext", outRate = 48000)
            val b = decode("gap_b.$ext", outRate = 48000)
            val total = (a.size + b.size) / 2
            val l = left(a + b)
            val join = a.size / 2
            var maxNear = 0f
            for (i in join - 2000 until join + 2000) if (i !in join - 16..join + 16) maxNear = maxOf(maxNear, abs(l[i] - l[i - 1]))
            var maxJoin = 0f
            for (i in join - 16..join + 16) maxJoin = maxOf(maxJoin, abs(l[i] - l[i - 1]))
            var maxErr = 0f
            for (i in join - 64..join + 64) maxErr = maxOf(maxErr, abs(l[i] - ref[i]))
            Log.i(TAG, "gapless $ext: frames $total (192000), join step $maxJoin vs neighbourhood $maxNear, error vs FLAC $maxErr")
            assertEquals("gap $ext frames", 192000, total)
            if (ext == "flac") assertTrue("flac join step", maxJoin <= 2 * maxNear)
            else assertTrue("gap $ext join error $maxErr", maxErr < 0.25f)
        }
    }

    @Test
    fun resamplerStaysInRange() {
        val a = decode("gap_a.flac", outRate = 44100)
        assertTrue("88200 frames: ${a.size / 2}", abs(a.size / 2 - 88200) <= 2)
        assertTrue("within full scale", a.all { abs(it) <= 1.0f })
        val hi = decode("hires.flac", outRate = 0)
        assertEquals("96 kHz kept", 192000 * 2, hi.size)
    }

    @Test
    fun probeReadsTags() {
        val p = TrackProbe.probe(path("tagged.flac"))
        assertEquals("Tagged Title", p.title)
        assertEquals("Tag Artist", p.artist)
        assertEquals("Tag Album", p.album)
        assertEquals("Album Artist", p.albumArtist)
        assertEquals(2019, p.year)
        assertEquals(3, p.trackNo)
        assertEquals(1, p.discNo)
        assertEquals(-6.54f, p.rgTrackGain!!, 0.001f)
        assertEquals(0.988f, p.rgTrackPeak!!, 0.001f)
        assertEquals(-7.10f, p.rgAlbumGain!!, 0.001f)
        assertEquals(44100, p.sampleRate)
        assertEquals(2, p.channels)
        assertEquals(16, p.bits)
        assertEquals("flac", p.codec)
        assertTrue("duration ${p.durationMs}", abs(p.durationMs - 3000) <= 1)
        val bad = TrackProbe.probe(path("badtag.mp3"))   // invalid UTF-8 in the title: no crash
        Log.i(TAG, "badtag title '${bad.title}', codec ${bad.codec}")
        val hires = TrackProbe.probe(path("hires.flac"))
        assertEquals(24, hires.bits)
        assertEquals(96000, hires.sampleRate)
        assertEquals(0, TrackProbe.probe(path("tone_v2.mp3")).bits)
    }

    @Test
    fun hostileInputFailsCleanly() {
        for (name in hostile) {
            try {
                NativeDecoder.open(path(name)).use { d -> while (d.read() > 0) Unit }
                Log.i(TAG, "$name: opened and decoded (partial file)")
            } catch (e: IOException) {
                Log.i(TAG, "$name: ${e.message}")
            }
            try { TrackProbe.probe(path(name)) } catch (_: IOException) {}
        }
    }
}
