package dev.trueform.player.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The library database (docs/plan/03-library-scanner.md T1). Plain SQLite: compiled statements for the scanner's
 * bulk writes, and [changes] (a version per table group) for reactive queries.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "library.db", null, VERSION) {

    enum class Group { TRACKS, FOLDERS, PLAYLISTS, WAVEFORMS }

    private val versions = Group.entries.associateWith { MutableStateFlow(0L) }
    fun changes(g: Group): StateFlow<Long> = versions.getValue(g)
    fun changed(vararg groups: Group) = groups.forEach { versions.getValue(it).value++ }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE tracks(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            path TEXT NOT NULL UNIQUE,
            folder_id INTEGER NOT NULL,
            file_name TEXT NOT NULL,
            size INTEGER NOT NULL,
            mtime INTEGER NOT NULL,
            title TEXT NOT NULL DEFAULT '',
            artist TEXT NOT NULL DEFAULT '',
            album TEXT NOT NULL DEFAULT '',
            album_artist TEXT NOT NULL DEFAULT '',
            genre TEXT NOT NULL DEFAULT '',
            composer TEXT NOT NULL DEFAULT '',
            year INTEGER NOT NULL DEFAULT 0,
            track_no INTEGER NOT NULL DEFAULT 0,
            disc_no INTEGER NOT NULL DEFAULT 0,
            duration_ms INTEGER NOT NULL DEFAULT 0,
            sample_rate INTEGER NOT NULL DEFAULT 0,
            bits INTEGER NOT NULL DEFAULT 0,
            channels INTEGER NOT NULL DEFAULT 0,
            codec TEXT NOT NULL DEFAULT '',
            container TEXT NOT NULL DEFAULT '',
            bitrate INTEGER NOT NULL DEFAULT 0,
            exact_frames INTEGER,
            rg_track_gain REAL, rg_track_peak REAL, rg_album_gain REAL, rg_album_peak REAL,
            art_hash TEXT,
            meta_state INTEGER NOT NULL DEFAULT 0,
            available INTEGER NOT NULL DEFAULT 1,
            date_added INTEGER NOT NULL,
            play_count INTEGER NOT NULL DEFAULT 0,
            last_played INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX tracks_folder ON tracks(folder_id)")
        db.execSQL("CREATE INDEX tracks_album ON tracks(album)")
        db.execSQL("CREATE INDEX tracks_artist ON tracks(artist)")
        db.execSQL("""CREATE TABLE folders(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            path TEXT NOT NULL UNIQUE,
            parent_id INTEGER,
            name TEXT NOT NULL,
            depth INTEGER NOT NULL,
            tracks_direct INTEGER NOT NULL DEFAULT 0,
            tracks_total INTEGER NOT NULL DEFAULT 0,
            duration_total INTEGER NOT NULL DEFAULT 0,
            dfs_order INTEGER NOT NULL DEFAULT 0,
            available INTEGER NOT NULL DEFAULT 1,
            cover_track INTEGER,
            subfolders INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX folders_parent ON folders(parent_id)")
        db.execSQL("CREATE TABLE scan_roots(path TEXT PRIMARY KEY, included INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE playlist_entries(playlist_id INTEGER NOT NULL, position INTEGER NOT NULL,
            track_id INTEGER, path TEXT NOT NULL, PRIMARY KEY(playlist_id, position))""")
        db.execSQL("CREATE TABLE waveforms(track_id INTEGER PRIMARY KEY, version INTEGER NOT NULL, data BLOB NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Before the first release: rebuild. From then on, add a migration per version here.
        for (t in listOf("tracks", "folders", "scan_roots", "playlists", "playlist_entries", "waveforms")) {
            db.execSQL("DROP TABLE IF EXISTS $t")
        }
        onCreate(db)
    }

    inline fun <T> tx(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = db.block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (Cursor) -> T): List<T> {
        readableDatabase.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += map(c)
            return out
        }
    }

    fun long(sql: String, vararg args: Any?): Long? =
        readableDatabase.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    companion object {
        const val VERSION = 1

        const val TRACK_COLUMNS = "id, path, folder_id, file_name, title, artist, album, album_artist, genre, year, " +
            "track_no, disc_no, duration_ms, sample_rate, bits, channels, codec, container, bitrate, exact_frames, " +
            "rg_track_gain, rg_track_peak, rg_album_gain, rg_album_peak, art_hash, meta_state, available, date_added, " +
            "play_count, last_played"

        fun track(c: Cursor) = Track(
            id = c.getLong(0), path = c.getString(1), folderId = c.getLong(2), fileName = c.getString(3),
            title = c.getString(4), artist = c.getString(5), album = c.getString(6), albumArtist = c.getString(7),
            genre = c.getString(8), year = c.getInt(9), trackNo = c.getInt(10), discNo = c.getInt(11),
            durationMs = c.getLong(12), sampleRate = c.getInt(13), bits = c.getInt(14), channels = c.getInt(15),
            codec = c.getString(16), container = c.getString(17), bitrate = c.getInt(18),
            exactFrames = if (c.isNull(19)) null else c.getLong(19),
            rgTrackGain = c.floatOrNull(20), rgTrackPeak = c.floatOrNull(21), rgAlbumGain = c.floatOrNull(22),
            rgAlbumPeak = c.floatOrNull(23), artHash = if (c.isNull(24)) null else c.getString(24),
            metaState = c.getInt(25), available = c.getInt(26) != 0, dateAdded = c.getLong(27),
            playCount = c.getInt(28), lastPlayed = c.getLong(29),
        )

        const val FOLDER_COLUMNS = "id, path, parent_id, name, depth, tracks_direct, tracks_total, duration_total, " +
            "dfs_order, available, cover_track, subfolders"

        fun folder(c: Cursor) = Folder(
            id = c.getLong(0), path = c.getString(1), parentId = if (c.isNull(2)) null else c.getLong(2),
            name = c.getString(3), depth = c.getInt(4), tracksDirect = c.getInt(5), tracksTotal = c.getInt(6),
            durationTotalMs = c.getLong(7), dfsOrder = c.getInt(8), available = c.getInt(9) != 0,
            coverTrackId = if (c.isNull(10)) null else c.getLong(10), subfolderCount = c.getInt(11),
        )

        private fun Cursor.floatOrNull(i: Int) = if (isNull(i)) null else getFloat(i)

        fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
            for ((k, v) in pairs) when (v) {
                null -> putNull(k)
                is String -> put(k, v)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is Float -> put(k, v)
                is Double -> put(k, v)
                is Boolean -> put(k, if (v) 1 else 0)
                is ByteArray -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }
}
