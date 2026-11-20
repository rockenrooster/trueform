package dev.trueform.player.audio

import java.io.IOException

/** Tags and stream facts of one file, read by FFmpeg without decoding. */
data class ProbeInfo(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val composer: String,
    val year: Int,
    val trackNo: Int,
    val discNo: Int,
    val rgTrackGain: Float?,
    val rgTrackPeak: Float?,
    val rgAlbumGain: Float?,
    val rgAlbumPeak: Float?,
    val codec: String,
    val container: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    /** 0 for lossy. */
    val bits: Int,
    val bitrate: Int,
    val hasCover: Boolean,
    val durationIsEstimate: Boolean,
)

object TrackProbe {

    /** Throws [IOException] when FFmpeg cannot read the file. */
    fun probe(path: String, fd: Int = -1): ProbeInfo {
        val meta = arrayOfNulls<ByteArray>(Native.META_COUNT)
        val nums = LongArray(Native.NUM_COUNT)
        val r = Native.probe(path.toByteArray(Charsets.UTF_8), fd, meta, nums)
        if (r < 0) throw IOException("cannot read $path: ${Native.errorString(r)}")
        fun s(i: Int) = meta[i]?.let { String(it, Charsets.UTF_8).trim() } ?: ""
        val r128Track = s(Native.META_R128_TRACK_GAIN).toIntOrNull()?.let { r128ToReplayGain(it) }
        val r128Album = s(Native.META_R128_ALBUM_GAIN).toIntOrNull()?.let { r128ToReplayGain(it) }
        return ProbeInfo(
            title = s(Native.META_TITLE),
            artist = s(Native.META_ARTIST),
            album = s(Native.META_ALBUM),
            albumArtist = s(Native.META_ALBUM_ARTIST),
            genre = s(Native.META_GENRE),
            composer = s(Native.META_COMPOSER),
            year = parseYear(s(Native.META_DATE)),
            trackNo = parseLeadingNumber(s(Native.META_TRACK)),
            discNo = parseLeadingNumber(s(Native.META_DISC)),
            rgTrackGain = parseGainDb(s(Native.META_RG_TRACK_GAIN)) ?: r128Track,
            rgTrackPeak = parsePeak(s(Native.META_RG_TRACK_PEAK)),
            rgAlbumGain = parseGainDb(s(Native.META_RG_ALBUM_GAIN)) ?: r128Album,
            rgAlbumPeak = parsePeak(s(Native.META_RG_ALBUM_PEAK)),
            codec = s(Native.META_CODEC),
            container = s(Native.META_CONTAINER),
            durationMs = nums[Native.NUM_DURATION_US] / 1000,
            sampleRate = nums[Native.NUM_SAMPLE_RATE].toInt(),
            channels = nums[Native.NUM_CHANNELS].toInt(),
            bits = nums[Native.NUM_BITS].toInt(),
            bitrate = nums[Native.NUM_BITRATE].toInt(),
            hasCover = nums[Native.NUM_HAS_COVER] != 0L,
            durationIsEstimate = nums[Native.NUM_DURATION_IS_ESTIMATE] != 0L,
        )
    }

    /** "3/12" → 3, "03" → 3, "" → 0. */
    fun parseLeadingNumber(s: String): Int {
        var i = 0
        while (i < s.length && s[i].isWhitespace()) i++
        var n = 0
        var digits = 0
        while (i < s.length && s[i].isDigit() && digits < 6) {
            n = n * 10 + (s[i] - '0'); i++; digits++
        }
        return n
    }

    /** "2019-05-01", "2019", "2019/05" → 2019; anything without a plausible year → 0. */
    fun parseYear(s: String): Int {
        val m = Regex("""(1[0-9]{3}|2[0-9]{3})""").find(s) ?: return 0
        return m.value.toInt()
    }

    /** "-6.54 dB", "+1.2dB", "-6,54 dB" → dB. */
    fun parseGainDb(s: String): Float? {
        if (s.isEmpty()) return null
        val m = Regex("""[-+]?[0-9]+(?:[.,][0-9]+)?""").find(s) ?: return null
        return m.value.replace(',', '.').toFloatOrNull()?.takeIf { it in -60f..60f }
    }

    fun parsePeak(s: String): Float? {
        if (s.isEmpty()) return null
        return s.trim().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it < 100f }
    }

    /** R128 gains are Q7.8 dB relative to -23 LUFS; ReplayGain's reference is -18 LUFS: 5 dB more. */
    fun r128ToReplayGain(q78: Int): Float = q78 / 256f + 5f
}
