package dev.trueform.player.waveform

import android.os.Process
import android.util.Log
import android.util.LruCache
import dev.trueform.player.audio.NativeDecoder
import dev.trueform.player.dsp.Biquad
import dev.trueform.player.dsp.FilterSpec
import dev.trueform.player.dsp.FilterType
import dev.trueform.player.library.Db
import dev.trueform.player.library.LibraryRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Constants to tune by looking at real tracks (docs/plan/05-waveform-seekbar.md). */
object WaveformTuning {
    const val W_MID = 1.6
    const val W_HIGH = 3.0
    const val SHARE_CONTRAST = 2.2f
    /** Bar height: ((rmsDb + RANGE) / RANGE) ^ CURVE of the half height, at least MIN. */
    const val RANGE_DB = 48f
    const val CURVE = 1.4f
    const val MIN = 0.04f
}

/**
 * Columns of peak, RMS and low/mid/high shares, 5 bytes each (T1): four a second, at least 512 (see [columnsFor]).
 * [filled] columns are valid.
 */
class WaveformData(val bytes: ByteArray, val filled: Int = bytes.size / 5) {
    val columns: Int get() = bytes.size / 5
    fun peakDb(i: Int) = (bytes[i * 5].toInt() and 0xff) / 255f * 60f - 60f
    fun rmsDb(i: Int) = (bytes[i * 5 + 1].toInt() and 0xff) / 255f * 60f - 60f
    fun lo(i: Int) = (bytes[i * 5 + 2].toInt() and 0xff) / 255f
    fun mid(i: Int) = (bytes[i * 5 + 3].toInt() and 0xff) / 255f
    fun hi(i: Int) = (bytes[i * 5 + 4].toInt() and 0xff) / 255f

    fun toBlob(): ByteArray = ByteBuffer.allocate(10 + bytes.size).put(MAGIC).putShort(VERSION.toShort())
        .putInt(columns).put(bytes).array()

    companion object {
        const val MIN_COLUMNS = 512
        /** Twenty minutes at four a second; longer files are sampled at [MIN_COLUMNS]. */
        const val MAX_COLUMNS = 4800
        /** 2: four columns a second for the zoomed, centred seek bar (1 had 512 per track; those are analysed again). */
        const val VERSION = 2
        private val MAGIC = "TFW1".toByteArray()

        fun columnsFor(durationMs: Long): Int = (durationMs / 250).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

        fun fromBlob(b: ByteArray): WaveformData? {
            if (b.size < 10 || String(b, 0, 4) != "TFW1") return null
            val bb = ByteBuffer.wrap(b)
            bb.position(4)
            if (bb.short.toInt() != VERSION) return null
            val cols = bb.int
            if (cols <= 0 || b.size != 10 + cols * 5) return null
            return WaveformData(b.copyOfRange(10, b.size))
        }

        fun encodeDb(db: Double): Byte = (((db + 60) / 60).coerceIn(0.0, 1.0) * 255).roundToInt().toByte()
    }
}

/**
 * Decodes a file once and summarises it into columns, four a second (T1). Also returns the exact decoded frame count.
 * Long files (over 20 minutes) are sampled: half a second at the start of each of 512 columns.
 */
object WaveformAnalyzer {
    private const val SAMPLED_ABOVE_MS = 20 * 60 * 1000L

    class Result(val data: WaveformData, val frames: Long?, val rate: Int)

    suspend fun analyze(path: String, durationHintMs: Long, onPartial: (WaveformData) -> Unit): Result {
        val dec = NativeDecoder.open(path, maxRate = 192_000)
        dec.use {
            val fs = dec.outRate.toDouble()
            val lp = Biquad.design(FilterSpec(FilterType.LOW_PASS, 250.0, 0.0, 0.7071), fs)
            val hp = Biquad.design(FilterSpec(FilterType.HIGH_PASS, 2500.0, 0.0, 0.7071), fs)
            val bh = Biquad.design(FilterSpec(FilterType.HIGH_PASS, 250.0, 0.0, 0.7071), fs)
            val bl = Biquad.design(FilterSpec(FilterType.LOW_PASS, 2500.0, 0.0, 0.7071), fs)
            val z = DoubleArray(8)
            val total = if (dec.exact && dec.totalFrames > 0) dec.totalFrames else max(1L, (durationHintMs * dec.outRate / 1000))
            val sampled = durationHintMs > SAMPLED_ABOVE_MS
            val cols = if (sampled) WaveformData.MIN_COLUMNS else WaveformData.columnsFor(durationHintMs)
            val emitEvery = maxOf(16, cols / 32)
            val out = ByteArray(cols * 5)
            val peak = DoubleArray(cols); val sm = DoubleArray(cols); val sl = DoubleArray(cols)
            val smid = DoubleArray(cols); val sh = DoubleArray(cols); val cnt = LongArray(cols)
            var lastEmitted = 0
            val buf = FloatArray(NativeDecoder.CHUNK_FRAMES * 2)
            var frame = 0L

            fun bq(k: dev.trueform.player.dsp.Coeffs, x: Double, zi: Int): Double {
                val y = k.b0 * x + z[zi]
                z[zi] = k.b1 * x - k.a1 * y + z[zi + 1]
                z[zi + 1] = k.b2 * x - k.a2 * y
                return y
            }
            fun feed(n: Int, col0: Int?) {
                for (i in 0 until n) {
                    val l = buf[i * 2].toDouble(); val r = buf[i * 2 + 1].toDouble()
                    val c = col0 ?: (((frame + i) * cols) / total).toInt().coerceIn(0, cols - 1)
                    val p = max(kotlin.math.abs(l), kotlin.math.abs(r))
                    if (p > peak[c]) peak[c] = p
                    val m = (l + r) * 0.5
                    sm[c] += m * m
                    val lo = bq(lp, m, 0); sl[c] += lo * lo
                    val hi = bq(hp, m, 2); sh[c] += hi * hi
                    val mid = bq(bl, bq(bh, m, 4), 6); smid[c] += mid * mid
                    cnt[c]++
                }
            }
            fun finish(c: Int) {
                val n = cnt[c].coerceAtLeast(1)
                out[c * 5] = WaveformData.encodeDb(if (peak[c] > 0) 20 * log10(peak[c]) else -60.0)
                out[c * 5 + 1] = WaveformData.encodeDb(if (sm[c] > 0) 10 * log10(sm[c] / n) else -60.0)
                val aLo = sqrt(sl[c] / n); val aMid = sqrt(smid[c] / n) * WaveformTuning.W_MID; val aHi = sqrt(sh[c] / n) * WaveformTuning.W_HIGH
                val s = aLo + aMid + aHi
                if (s > 0) {
                    val bLo = (aLo / s * 255).roundToInt(); val bMid = (aMid / s * 255).roundToInt()
                    out[c * 5 + 2] = bLo.toByte(); out[c * 5 + 3] = bMid.toByte(); out[c * 5 + 4] = (255 - bLo - bMid).coerceAtLeast(0).toByte()
                } else { out[c * 5 + 2] = 85; out[c * 5 + 3] = 85; out[c * 5 + 4] = 85 }
            }

            if (sampled) {
                val len = dec.outRate / 2
                for (c in 0 until cols) {
                    coroutineContext.ensureActive()
                    dec.seek(c * total / cols)
                    z.fill(0.0)
                    var got = 0
                    while (got < len) {
                        val n = dec.read(minOf(NativeDecoder.CHUNK_FRAMES, len - got))
                        if (n == 0) break
                        dec.floats.position(0); dec.floats.get(buf, 0, n * 2)
                        feed(n, c); got += n
                    }
                    finish(c)
                    if ((c + 1) % 16 == 0) onPartial(WaveformData(out.copyOf(), c + 1))
                }
                return Result(WaveformData(out), null, dec.outRate)
            }
            while (true) {
                coroutineContext.ensureActive()
                val n = dec.read()
                if (n == 0) break
                dec.floats.position(0); dec.floats.get(buf, 0, n * 2)
                feed(n, null)
                frame += n
                val doneCols = ((frame * cols) / total).toInt().coerceAtMost(cols)
                if (doneCols >= lastEmitted + emitEvery) {
                    for (c in lastEmitted until doneCols) finish(c)
                    lastEmitted = doneCols
                    onPartial(WaveformData(out.copyOf(), doneCols))
                }
            }
            for (c in lastEmitted until cols) finish(c)
            return Result(WaveformData(out), frame, dec.outRate)
        }
    }

}

/**
 * Stored waveforms and on-demand analysis (T1): the current track first, then the next two. One background worker.
 */
class WaveformRepo(private val db: Db, private val library: LibraryRepo, private val scope: CoroutineScope,
                   private val onExactFrames: (String) -> Unit) {
    private val cache = LruCache<Long, WaveformData>(64)
    private val flows = HashMap<Long, MutableStateFlow<WaveformData?>>()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val pending = ArrayDeque<Long>()
    private var running: Long? = null
    private var job: Job? = null
    private var current: Long? = null

    /** Milliseconds the last full analysis took, for PROGRESS.md. */
    @Volatile var lastAnalysisMs = 0L

    @Synchronized
    fun observe(trackId: Long): StateFlow<WaveformData?> {
        val f = flows.getOrPut(trackId) { MutableStateFlow(cache.get(trackId)) }
        if (f.value == null) scope.launch(Dispatchers.IO) {
            val stored = load(trackId)
            if (stored != null) { cache.put(trackId, stored); f.value = stored } else prepare(listOf(trackId), keepCurrent = true)
        }
        return f
    }

    /** The current track and the next ones: analysed in this order when not stored yet. */
    @Synchronized
    fun prepare(trackIds: List<Long>, keepCurrent: Boolean = false) {
        if (trackIds.isEmpty()) return
        if (!keepCurrent) current = trackIds.first()
        pending.removeAll(trackIds.toSet())
        trackIds.reversed().forEach { pending.addFirst(it) }
        val r = running
        if (r != null && r != current && current in trackIds && !keepCurrent) {
            job?.cancel()   // the current track first
        }
        pump()
    }

    @Synchronized
    private fun pump() {
        if (job?.isActive == true) return
        val id = pending.removeFirstOrNull() ?: return
        running = id
        job = scope.launch(worker) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                analyzeIfMissing(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                synchronized(this@WaveformRepo) { if (id !in pending) pending.addLast(id) }
            } catch (e: Exception) {
                Log.w("TFWAVE", "analysis failed for $id: ${e.message}")
            } finally {
                synchronized(this@WaveformRepo) { running = null; job = null }
                pump()
            }
        }
    }

    private suspend fun analyzeIfMissing(id: Long) {
        if (cache.get(id) != null || load(id)?.also { cache.put(id, it); flowOf(id).value = it } != null) return
        val t = library.track(id) ?: return
        val t0 = System.currentTimeMillis()
        val flow = flowOf(id)
        val r = WaveformAnalyzer.analyze(t.path, t.durationMs) { partial -> flow.value = partial }
        lastAnalysisMs = System.currentTimeMillis() - t0
        Log.i("TFWAVE", "analysed ${t.fileName} in $lastAnalysisMs ms (${r.frames} frames)")
        cache.put(id, r.data)
        flow.value = r.data
        db.writableDatabase.insertWithOnConflict("waveforms", null,
            Db.values("track_id" to id, "version" to WaveformData.VERSION, "data" to r.data.toBlob()),
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        if (r.frames != null && r.rate == t.sampleRate && r.frames != t.exactFrames) {
            library.setExactFrames(id, r.frames)
            onExactFrames(t.path)
        }
    }

    @Synchronized
    private fun flowOf(id: Long) = flows.getOrPut(id) { MutableStateFlow(null) }

    private fun load(id: Long): WaveformData? =
        db.query("SELECT data FROM waveforms WHERE track_id = ? AND version = ?", id, WaveformData.VERSION) { it.getBlob(0) }
            .firstOrNull()?.let { WaveformData.fromBlob(it) }

    fun clear() {
        db.writableDatabase.delete("waveforms", null, null)
        cache.evictAll()
        synchronized(this) { flows.values.forEach { it.value = null } }
    }

    /** Bar level for drawing: ((rmsDb + R) / R) ^ curve, at least MIN. */
    companion object {
        fun height(db: Float): Float =
            ((db + WaveformTuning.RANGE_DB) / WaveformTuning.RANGE_DB).coerceIn(0f, 1f).pow(WaveformTuning.CURVE).coerceAtLeast(WaveformTuning.MIN)
    }
}
