package dev.trueform.player.audio

import java.nio.ByteBuffer

/**
 * libtrueform.so (app/src/main/cpp). Paths are UTF-8 bytes; tag strings come back as UTF-8 bytes. Every handle is
 * used by one thread only. Index constants match tf_decoder.h.
 */
internal object Native {
    init {
        System.loadLibrary("trueform")
    }

    // probe() meta indexes
    const val META_TITLE = 0
    const val META_ARTIST = 1
    const val META_ALBUM = 2
    const val META_ALBUM_ARTIST = 3
    const val META_GENRE = 4
    const val META_DATE = 5
    const val META_TRACK = 6
    const val META_DISC = 7
    const val META_COMPOSER = 8
    const val META_RG_TRACK_GAIN = 9
    const val META_RG_TRACK_PEAK = 10
    const val META_RG_ALBUM_GAIN = 11
    const val META_RG_ALBUM_PEAK = 12
    const val META_R128_TRACK_GAIN = 13
    const val META_R128_ALBUM_GAIN = 14
    const val META_CODEC = 15
    const val META_CONTAINER = 16
    const val META_COUNT = 17

    // probe() number indexes
    const val NUM_DURATION_US = 0
    const val NUM_SAMPLE_RATE = 1
    const val NUM_CHANNELS = 2
    const val NUM_BITS = 3
    const val NUM_BITRATE = 4
    const val NUM_HAS_COVER = 5
    const val NUM_DURATION_IS_ESTIMATE = 6
    const val NUM_COUNT = 7

    // open() info indexes
    const val INFO_OUT_RATE = 0
    const val INFO_TOTAL_FRAMES = 1
    const val INFO_EXACT = 2
    const val INFO_SOURCE_RATE = 3
    const val INFO_SOURCE_BITS = 4
    const val INFO_SOURCE_CHANNELS = 5
    const val INFO_COUNT = 6

    external fun ffmpegVersion(): String
    external fun errorString(code: Int): String

    /** Tags and stream facts without decoding. 0 or a negative AVERROR. */
    external fun probe(path: ByteArray, fd: Int, meta: Array<ByteArray?>, nums: LongArray): Int

    /** The embedded front cover (JPEG/PNG bytes as stored), or null. */
    external fun extractCover(path: ByteArray, fd: Int): ByteArray?

    /** A decoder handle, or 0 with the AVERROR in err[0]. fd < 0: use path. */
    external fun open(path: ByteArray, fd: Int, outRate: Int, maxRate: Int, quality: Int, info: LongArray, err: IntArray): Long
    external fun setGain(handle: Long, linear: Float)

    /** Up to maxFrames stereo float frames into dst (direct, native order). Frames, 0 at the end, < 0 an error. */
    external fun read(handle: Long, dst: ByteBuffer, maxFrames: Int): Int
    external fun seek(handle: Long, frame: Long): Int
    external fun scanFrames(handle: Long): Long
    external fun close(handle: Long)

    /** Logs decoder timestamps (logcat tag TFDEC); for tests. */
    external fun setTrace(on: Boolean)

    /** The EQ's biquad cascade in place over interleaved stereo (DspChain): coeffs b0 b1 b2 a1 a2, z z1l z2l z1r z2r. */
    external fun cascade(buf: DoubleArray, frames: Int, coeffs: DoubleArray, z: DoubleArray)

    /** False where the library cannot load (JVM unit tests): callers keep a Kotlin path. */
    val available: Boolean by lazy { runCatching { ffmpegVersion(); true }.getOrDefault(false) }
}

/** Whether [Native] loads, asked without touching it first (its initializer throws on the JVM). */
internal object NativeCheck {
    val loaded: Boolean = runCatching { Native.available }.getOrDefault(false)
}
