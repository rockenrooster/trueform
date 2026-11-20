package dev.trueform.player.audio

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * One open file, decoded to interleaved float32 stereo at [outRate]. Not thread-safe: one user thread.
 * [read] fills [buffer] (and its float view [floats]) from index 0.
 */
class NativeDecoder private constructor(
    private var handle: Long,
    val outRate: Int,
    /** Frames at [outRate] as the container states them; see [exact]. */
    val totalFrames: Long,
    /** False when the length is a bitrate guess (headerless MP3): use [scanFrames] for a better count. */
    val exact: Boolean,
    val sourceRate: Int,
    /** 0 for lossy codecs. */
    val sourceBits: Int,
    val sourceChannels: Int,
) : Closeable {

    val buffer: ByteBuffer = ByteBuffer.allocateDirect(CHUNK_FRAMES * 8).order(ByteOrder.nativeOrder())
    val floats: FloatBuffer = buffer.asFloatBuffer()

    /** Decodes up to [maxFrames] (at most [CHUNK_FRAMES]) frames into [buffer]. Returns frames, 0 at the end. */
    fun read(maxFrames: Int = CHUNK_FRAMES): Int {
        val n = Native.read(live(), buffer, minOf(maxFrames, CHUNK_FRAMES))
        if (n < 0) throw IOException("decode failed: ${Native.errorString(n)}")
        return n
    }

    fun seek(frame: Long) {
        val r = Native.seek(live(), frame)
        if (r < 0) throw IOException("seek failed: ${Native.errorString(r)}")
    }

    fun setGain(linear: Float) = Native.setGain(live(), linear)

    /** Counts the file's frames by its packets. Call before reading: it leaves the decoder at frame 0. */
    fun scanFrames(): Long = Native.scanFrames(live())

    override fun close() {
        if (handle != 0L) {
            Native.close(handle)
            handle = 0L
        }
    }

    private fun live(): Long {
        check(handle != 0L) { "decoder closed" }
        return handle
    }

    companion object {
        const val CHUNK_FRAMES = 4096

        const val QUALITY_NORMAL = 0
        const val QUALITY_HIGH = 1
        const val QUALITY_VERY_HIGH = 2

        /** outRate 0: the file's rate, halved until it is at most [maxRate]. Throws [IOException]. */
        fun open(
            path: String,
            fd: Int = -1,
            outRate: Int = 0,
            maxRate: Int = 192_000,
            quality: Int = QUALITY_HIGH,
        ): NativeDecoder {
            val info = LongArray(Native.INFO_COUNT)
            val err = IntArray(1)
            val h = Native.open(path.toByteArray(Charsets.UTF_8), fd, outRate, maxRate, quality, info, err)
            if (h == 0L) throw IOException("cannot open $path: ${Native.errorString(err[0])}")
            return NativeDecoder(
                h,
                outRate = info[Native.INFO_OUT_RATE].toInt(),
                totalFrames = info[Native.INFO_TOTAL_FRAMES],
                exact = info[Native.INFO_EXACT] != 0L,
                sourceRate = info[Native.INFO_SOURCE_RATE].toInt(),
                sourceBits = info[Native.INFO_SOURCE_BITS].toInt(),
                sourceChannels = info[Native.INFO_SOURCE_CHANNELS].toInt(),
            )
        }

        fun ffmpegVersion(): String = Native.ffmpegVersion()
    }
}
