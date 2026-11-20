package dev.trueform.player.audio

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.random.Random

enum class PcmEncoding(val bytes: Int) { FLOAT32(4), INT24(3), INT16(2) }

/** Interleaved stereo float frames from somewhere: the native decoder, or a fake in tests. */
interface PcmSource : Closeable {
    val outRate: Int
    val totalFrames: Long
    val exact: Boolean
    /** Up to [maxFrames] frames into [dst] from float index [offset]; returns frames, 0 at the end. */
    fun read(dst: FloatArray, offset: Int, maxFrames: Int): Int
    fun seek(frame: Long)
    fun scanFrames(): Long
}

/** [NativeDecoder] as a [PcmSource]. */
class DecoderSource(val decoder: NativeDecoder) : PcmSource {
    override val outRate get() = decoder.outRate
    override val totalFrames get() = decoder.totalFrames
    override val exact get() = decoder.exact
    override fun read(dst: FloatArray, offset: Int, maxFrames: Int): Int {
        val n = decoder.read(maxFrames)
        if (n > 0) {
            decoder.floats.position(0)
            decoder.floats.get(dst, offset, n * 2)
        }
        return n
    }
    override fun seek(frame: Long) = decoder.seek(frame)
    override fun scanFrames() = decoder.scanFrames()
    override fun close() = decoder.close()
}

/** Changes float frames in place before they become bytes (the cast render's EQ and limiter). */
fun interface FrameProcessor {
    fun process(buf: FloatArray, frames: Int)
}

object WavHeader {
    private const val RF64_THRESHOLD = 0xFFFFFFF0L

    /** A RIFF/WAVE header (RF64 with a ds64 chunk past 4 GB) for 2-channel PCM of the given length. */
    fun build(encoding: PcmEncoding, sampleRate: Int, frames: Long): ByteArray {
        val channels = 2
        val blockAlign = channels * encoding.bytes
        val dataBytes = frames * blockAlign
        val rf64 = dataBytes + 36 > RF64_THRESHOLD
        val b = ByteBuffer.allocate(if (rf64) 80 else 44).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(s: String) = s.forEach { b.put(it.code.toByte()) }
        tag(if (rf64) "RF64" else "RIFF")
        b.putInt(if (rf64) -1 else (dataBytes + 36).toInt())
        tag("WAVE")
        if (rf64) {
            tag("ds64"); b.putInt(28)
            b.putLong(dataBytes + 72)          // RIFF size: everything after the first 8 bytes
            b.putLong(dataBytes)
            b.putLong(frames)
            b.putInt(0)                        // table length
        }
        tag("fmt "); b.putInt(16)
        b.putShort(if (encoding == PcmEncoding.FLOAT32) 3 else 1)   // IEEE float or integer PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * blockAlign)
        b.putShort(blockAlign.toShort())
        b.putShort((encoding.bytes * 8).toShort())
        tag("data")
        b.putInt(if (rf64) -1 else dataBytes.toInt())
        return b.array()
    }
}

/**
 * One file as a WAV byte stream of exactly [length] bytes (docs/plan/02-playback-engine.md T1): ExoPlayer's
 * WavExtractor reads it locally (FLOAT32), the cast server serves it to a Chromecast (INT24/INT16). If the decoder
 * ends early the rest is silence; if it has more, it is cut. Seekable to any byte. Not thread-safe.
 */
class WavStream(
    private val source: PcmSource,
    val encoding: PcmEncoding,
    knownFrames: Long? = null,
    private val processor: FrameProcessor? = null,
) : Closeable {
    val sampleRate = source.outRate
    val totalFrames: Long = knownFrames ?: if (source.exact) source.totalFrames else source.scanFrames()
    private val frameBytes = 2 * encoding.bytes
    private val header = WavHeader.build(encoding, sampleRate, totalFrames)
    val headerSize = header.size
    val length: Long = headerSize + totalFrames * frameBytes

    private var pos = 0L                       // byte position of the next read
    private var framePos = 0L                  // frame index the source delivers next
    private var sourceEnded = false
    private val floats = FloatArray(CHUNK * 2)
    private val bytes = ByteArray(CHUNK * frameBytes)
    private val byteView = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private var pendOff = 0
    private var pendLen = 0
    private var skipInChunk = 0                 // bytes of the next chunk to drop (a seek into the middle of a frame)
    private val dither = Random(0x7F4A7C15)

    fun seek(byteOffset: Long) {
        val target = byteOffset.coerceIn(0, length)
        pos = target
        pendOff = 0; pendLen = 0
        val dataOff = (target - headerSize).coerceAtLeast(0)
        val frame = (dataOff / frameBytes).coerceAtMost(totalFrames)
        skipInChunk = (dataOff % frameBytes).toInt()
        if (frame != framePos || sourceEnded) {
            if (frame < totalFrames) source.seek(frame)
            sourceEnded = frame >= totalFrames
            framePos = frame
        }
    }

    /** Fills [dst] from [off]; returns bytes, or -1 at [length]. */
    fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        var n = 0
        while (n < len && pos < length) {
            if (pos < headerSize) {
                val k = minOf(len - n, (headerSize - pos).toInt())
                System.arraycopy(header, pos.toInt(), dst, off + n, k)
                n += k; pos += k
                continue
            }
            if (pendOff >= pendLen) {
                fillChunk()
                if (pendOff >= pendLen) break
            }
            val k = minOf(len - n, pendLen - pendOff)
            System.arraycopy(bytes, pendOff, dst, off + n, k)
            pendOff += k; n += k; pos += k
        }
        return if (n == 0) -1 else n
    }

    private fun fillChunk() {
        val want = minOf(CHUNK.toLong(), totalFrames - framePos).toInt()
        if (want <= 0) { pendOff = 0; pendLen = 0; return }
        var got = 0
        while (got < want && !sourceEnded) {
            val r = source.read(floats, got * 2, want - got)
            if (r <= 0) { sourceEnded = true; break }
            got += r
        }
        if (got < want) java.util.Arrays.fill(floats, got * 2, want * 2, 0f)   // pad to the declared length
        processor?.process(floats, want)
        encode(want)
        framePos += want
        pendLen = want * frameBytes
        pendOff = skipInChunk.coerceAtMost(pendLen)
        skipInChunk = 0
    }

    private fun encode(frames: Int) {
        val n = frames * 2
        byteView.clear()
        when (encoding) {
            PcmEncoding.FLOAT32 -> byteView.asFloatBuffer().put(floats, 0, n)
            PcmEncoding.INT24 -> for (i in 0 until n) {
                val v = (floats[i].coerceIn(-1f, 1f) * 8388607f).roundToInt()
                val o = i * 3
                bytes[o] = v.toByte(); bytes[o + 1] = (v shr 8).toByte(); bytes[o + 2] = (v shr 16).toByte()
            }
            PcmEncoding.INT16 -> for (i in 0 until n) {
                // TPDF dither of ±1 LSB before rounding to 16 bits
                val d = (dither.nextFloat() - dither.nextFloat()) / 32768f
                val v = ((floats[i] + d).coerceIn(-1f, 1f) * 32767f).roundToInt()
                val o = i * 2
                bytes[o] = v.toByte(); bytes[o + 1] = (v shr 8).toByte()
            }
        }
    }

    override fun close() = source.close()

    companion object {
        const val CHUNK = 4096
    }
}
