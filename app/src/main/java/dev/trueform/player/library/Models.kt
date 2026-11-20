package dev.trueform.player.library

import java.io.File
import java.text.Collator
import java.util.Locale

data class Track(
    val id: Long,
    val path: String,
    val folderId: Long,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val trackNo: Int,
    val discNo: Int,
    val durationMs: Long,
    val sampleRate: Int,
    val bits: Int,
    val channels: Int,
    val codec: String,
    val container: String,
    val bitrate: Int,
    val exactFrames: Long?,
    val rgTrackGain: Float?,
    val rgTrackPeak: Float?,
    val rgAlbumGain: Float?,
    val rgAlbumPeak: Float?,
    val artHash: String?,
    val metaState: Int,
    val available: Boolean,
    val dateAdded: Long,
    val playCount: Int,
    val lastPlayed: Long,
) {
    val baseName: String get() = fileName.substringBeforeLast('.')
    val displayTitle: String get() = title.ifBlank { baseName }
    val displayArtist: String get() = artist.ifBlank { albumArtist }
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val lossless: Boolean get() = bits > 0
    val dir: String get() = File(path).parent ?: ""

    /** "FLAC · 24/96", "MP3 · 320 kbps" */
    val formatLabel: String
        get() {
            val name = codecLabel()
            val detail = when {
                bits > 0 && sampleRate > 0 -> "$bits/${khz(sampleRate)}"
                bitrate > 0 -> "${bitrate / 1000} kbps"
                else -> ""
            }
            return if (detail.isEmpty()) name else "$name · $detail"
        }

    /** A pill worth showing in a row: hi-res, or a format people rarely meet. Null otherwise. */
    val rowPill: String?
        get() = when {
            bits >= 24 || sampleRate > 48000 -> "$bits/${khz(sampleRate)}".removePrefix("0/")
            extension in setOf("wma", "ape", "wv", "dsf", "dff", "mpc", "tta", "tak") -> extension.uppercase(Locale.ROOT)
            else -> null
        }

    private fun codecLabel(): String = when (codec) {
        "mp3", "mp3float" -> "MP3"
        "aac" -> "AAC"
        "alac" -> "ALAC"
        "flac" -> "FLAC"
        "vorbis" -> "Vorbis"
        "opus" -> "Opus"
        "wmav1", "wmav2", "wmapro", "wmalossless", "wmavoice" -> "WMA"
        "ape" -> "APE"
        "wavpack" -> "WavPack"
        "dsd_lsbf", "dsd_msbf", "dsd_lsbf_planar", "dsd_msbf_planar" -> "DSD"
        else -> if (codec.startsWith("pcm")) (if (extension.startsWith("aif")) "AIFF" else "WAV")
        else codec.uppercase(Locale.ROOT).ifEmpty { extension.uppercase(Locale.ROOT) }
    }

    companion object {
        fun khz(rate: Int): String {
            val k = rate / 1000.0
            return if (k == Math.floor(k)) "${k.toInt()}" else "%.1f".format(Locale.ROOT, k)
        }
    }
}

data class Folder(
    val id: Long,
    val path: String,
    val parentId: Long?,
    val name: String,
    val depth: Int,
    val tracksDirect: Int,
    val tracksTotal: Int,
    val durationTotalMs: Long,
    val dfsOrder: Int,
    val available: Boolean,
    val coverTrackId: Long?,
    val subfolderCount: Int = 0,
)

data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int,
    val trackCount: Int,
    val durationMs: Long,
    val coverTrackId: Long,
)

data class Artist(val name: String, val albumCount: Int, val trackCount: Int, val coverTrackId: Long)

data class Playlist(val id: Long, val name: String, val trackCount: Int, val smart: SmartList? = null)

enum class SmartList(val title: String) { RECENTLY_ADDED("Recently added"), MOST_PLAYED("Most played"), RECENTLY_PLAYED("Recently played") }

/** A folder's contents: subfolders (natural order), then its own tracks. */
data class FolderChildren(val folder: Folder?, val folders: List<Folder>, val tracks: List<Track>)

/** What ReplayGain, the known length and the decoder need for one file (docs/plan/02-playback-engine.md T2). */
data class TrackPlayInfo(val trackId: Long?, val knownFrames: Long?, val sourceRate: Int, val gain: Float)

/**
 * Natural order: digit runs compare as numbers ("Track 2" before "Track 10"), the rest case- and accent-insensitive.
 */
object NaturalOrder : Comparator<String> {
    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei; j = ej
            } else {
                var ei = i; while (ei < a.length && !a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && !b[ej].isDigit()) ej++
                val c = collator.compare(a.substring(i, ei), b.substring(j, ej))
                if (c != 0) return c
                i = ei; j = ej
            }
        }
        val rest = (a.length - i) - (b.length - j)
        if (rest != 0) return rest
        return a.compareTo(b)   // stable tie-break ("01" vs "1", case)
    }
}
