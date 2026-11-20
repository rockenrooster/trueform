package dev.trueform.player.library

import dev.trueform.player.settings.FolderSort
import dev.trueform.player.settings.ReplayGainMode
import dev.trueform.player.settings.S
import dev.trueform.player.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import kotlin.math.pow

/** Queries over the library (docs/plan/03-library-scanner.md T4). Flows re-run when their tables change. */
class LibraryRepo(val db: Db, private val settings: Settings) {

    private fun <T> watch(vararg groups: Db.Group, query: () -> T): Flow<T> {
        val flows = groups.map { db.changes(it) } + settings.version.map { 0L }
        return combine(flows) { it.sum() }.map { query() }.flowOn(Dispatchers.IO)
    }

    // --- tracks ----------------------------------------------------------------------------------------------------

    fun track(id: Long): Track? =
        db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks WHERE id = ?", id, map = Db::track).firstOrNull()

    fun tracks(ids: Collection<Long>): Map<Long, Track> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, Track>(ids.size)
        for (chunk in ids.distinct().chunked(500)) {
            db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks WHERE id IN (${chunk.joinToString(",")})", map = Db::track)
                .forEach { out[it.id] = it }
        }
        return out
    }

    fun trackByPath(path: String): Track? =
        db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks WHERE path = ?", path, map = Db::track).firstOrNull()

    fun trackFlow(id: Long): Flow<Track?> = watch(Db.Group.TRACKS) { track(id) }

    fun folderTrackSort(tracks: List<Track>): List<Track> = when (settings[S.folderSort]) {
        FolderSort.FILE_NAME -> tracks.sortedWith { a, b -> NaturalOrder.compare(a.fileName, b.fileName) }
        FolderSort.TRACK_NUMBER -> tracks.sortedWith(compareBy<Track>({ it.discNo }, { it.trackNo }).thenComparator { a, b -> NaturalOrder.compare(a.fileName, b.fileName) })
        FolderSort.TITLE -> tracks.sortedWith { a, b -> NaturalOrder.compare(a.displayTitle, b.displayTitle) }
    }

    // --- folders ---------------------------------------------------------------------------------------------------

    fun folder(id: Long): Folder? =
        db.query("SELECT ${Db.FOLDER_COLUMNS} FROM folders WHERE id = ?", id, map = Db::folder).firstOrNull()

    fun roots(): List<Folder> =
        db.query("SELECT ${Db.FOLDER_COLUMNS} FROM folders WHERE parent_id IS NULL AND tracks_total > 0", map = Db::folder)
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }

    fun rootsFlow(): Flow<List<Folder>> = watch(Db.Group.FOLDERS) { roots() }

    fun children(folderId: Long?): FolderChildren {
        if (folderId == null) return FolderChildren(null, roots(), emptyList())
        val f = folder(folderId)
        val subs = db.query("SELECT ${Db.FOLDER_COLUMNS} FROM folders WHERE parent_id = ? AND tracks_total > 0", folderId, map = Db::folder)
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        val tracks = folderTrackSort(db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks WHERE folder_id = ?", folderId, map = Db::track))
        return FolderChildren(f, subs, tracks)
    }

    fun childrenFlow(folderId: Long?): Flow<FolderChildren> = watch(Db.Group.FOLDERS, Db.Group.TRACKS) { children(folderId) }

    /** The folder and its ancestors, root first (breadcrumbs). */
    fun ancestry(folderId: Long): List<Folder> {
        val out = ArrayList<Folder>()
        var f = folder(folderId)
        while (f != null) {
            out += f
            f = f.parentId?.let { folder(it) }
        }
        return out.reversed()
    }

    /** Track ids in library order: the folder's own tracks, then its subfolders depth-first. */
    fun tracksUnder(folderId: Long, recursive: Boolean): List<Long> {
        val out = ArrayList<Long>()
        fun visit(id: Long) {
            val c = children(id)
            c.tracks.mapTo(out) { it.id }
            if (recursive) c.folders.forEach { visit(it.id) }
        }
        visit(folderId)
        return out
    }

    /**
     * The next folder in library order that has tracks of its own, after [folder] (after its whole subtree when the
     * queue took it [recursive]ly), or null at the end of the library.
     */
    fun nextFolderAfter(folder: Folder, recursive: Boolean): Folder? {
        val from = if (recursive) {
            db.long("SELECT MIN(dfs_order) FROM folders WHERE dfs_order > ? AND depth <= ?", folder.dfsOrder, folder.depth)
                ?: return null
        } else folder.dfsOrder + 1L
        return db.query("SELECT ${Db.FOLDER_COLUMNS} FROM folders WHERE dfs_order >= ? AND tracks_direct > 0 ORDER BY dfs_order LIMIT 1",
            from, map = Db::folder).firstOrNull()
    }

    /** Every track in library order (folders depth-first, each folder's tracks in the folder sort). */
    fun libraryOrder(): List<Long> {
        val folders = db.query("SELECT id FROM folders WHERE tracks_direct > 0 ORDER BY dfs_order") { it.getLong(0) }
        val out = ArrayList<Long>()
        for (f in folders) folderTrackSort(db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks WHERE folder_id = ?", f, map = Db::track)).mapTo(out) { it.id }
        return out
    }

    // --- albums, artists, songs ------------------------------------------------------------------------------------

    private val albumKeySql = "album || char(31) || CASE WHEN album_artist != '' THEN album_artist ELSE 'f:' || folder_id END"

    fun albums(): List<Album> = db.query(
        """SELECT $albumKeySql AS k, album, MAX(CASE WHEN album_artist != '' THEN album_artist ELSE artist END),
           MAX(year), COUNT(*), SUM(duration_ms), MIN(id) FROM tracks WHERE album != '' GROUP BY k""") {
        Album(it.getString(0), it.getString(1), it.getString(2) ?: "", it.getInt(3), it.getInt(4), it.getLong(5), it.getLong(6))
    }.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }

    fun albumsFlow(): Flow<List<Album>> = watch(Db.Group.TRACKS) { albums() }

    fun albumTracks(key: String): List<Track> =
        db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks WHERE $albumKeySql = ?", key, map = Db::track)
            .sortedWith(compareBy<Track>({ it.discNo }, { it.trackNo }).thenComparator { a, b -> NaturalOrder.compare(a.fileName, b.fileName) })

    fun album(key: String): Album? = albums().firstOrNull { it.key == key }

    fun artists(): List<Artist> = db.query(
        """SELECT CASE WHEN album_artist != '' THEN album_artist ELSE artist END AS a, COUNT(DISTINCT album), COUNT(*), MIN(id)
           FROM tracks WHERE a != '' GROUP BY a""") {
        Artist(it.getString(0), it.getInt(1), it.getInt(2), it.getLong(3))
    }.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }

    fun artistsFlow(): Flow<List<Artist>> = watch(Db.Group.TRACKS) { artists() }

    fun artistTracks(name: String): List<Track> =
        db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks WHERE (CASE WHEN album_artist != '' THEN album_artist ELSE artist END) = ?",
            name, map = Db::track)
            .sortedWith(compareBy<Track>({ it.album.lowercase() }, { it.discNo }, { it.trackNo }).thenComparator { a, b -> NaturalOrder.compare(a.fileName, b.fileName) })

    fun allTracks(): List<Track> = db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks", map = Db::track)
        .sortedWith { a, b -> NaturalOrder.compare(a.displayTitle, b.displayTitle) }

    fun allTracksFlow(): Flow<List<Track>> = watch(Db.Group.TRACKS) { allTracks() }

    fun trackCount(): Int = db.long("SELECT COUNT(*) FROM tracks")?.toInt() ?: 0

    fun smart(list: SmartList, limit: Int = 200): List<Track> = when (list) {
        SmartList.RECENTLY_ADDED -> db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks ORDER BY date_added DESC, id DESC LIMIT ?", limit, map = Db::track)
        SmartList.MOST_PLAYED -> db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks WHERE play_count > 0 ORDER BY play_count DESC, last_played DESC LIMIT ?", limit, map = Db::track)
        SmartList.RECENTLY_PLAYED -> db.query("SELECT ${Db.TRACK_COLUMNS} FROM tracks WHERE last_played > 0 ORDER BY last_played DESC LIMIT ?", limit, map = Db::track)
    }

    // --- search ----------------------------------------------------------------------------------------------------

    data class SearchResult(val tracks: List<Track>, val albums: List<Album>, val artists: List<Artist>, val folders: List<Folder>)

    // ponytail: LIKE scan, fine to ~100k rows; switch to FTS4 if search lags
    fun search(text: String, limit: Int = 50): SearchResult {
        val q = text.trim()
        if (q.isEmpty()) return SearchResult(emptyList(), emptyList(), emptyList(), emptyList())
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val tracks = db.query("""SELECT ${Db.TRACK_COLUMNS} FROM tracks WHERE title LIKE ?1 ESCAPE '\' OR artist LIKE ?1 ESCAPE '\'
            OR album LIKE ?1 ESCAPE '\' OR file_name LIKE ?1 ESCAPE '\' LIMIT $limit""", like, map = Db::track)
        val ql = q.lowercase()
        val albums = albums().filter { it.title.lowercase().contains(ql) || it.artist.lowercase().contains(ql) }.take(limit)
        val artists = artists().filter { it.name.lowercase().contains(ql) }.take(limit)
        val folders = db.query("SELECT ${Db.FOLDER_COLUMNS} FROM folders WHERE name LIKE ? ESCAPE '\\' AND tracks_total > 0 LIMIT $limit", like, map = Db::folder)
        return SearchResult(tracks, albums, artists, folders)
    }

    // --- playback info ---------------------------------------------------------------------------------------------

    /** Known length and ReplayGain gain for the data source; called off the main thread (M6 T7). */
    fun playInfo(path: String): TrackPlayInfo {
        val t = trackByPath(path)
        return TrackPlayInfo(t?.id, t?.exactFrames, t?.sampleRate ?: 0, gainFor(t))
    }

    fun gainFor(t: Track?): Float {
        val mode = settings[S.replayGain]
        if (mode == ReplayGainMode.OFF) return 1f
        val (gain, peak) = when {
            t == null -> null to null
            mode == ReplayGainMode.ALBUM && t.rgAlbumGain != null -> t.rgAlbumGain to t.rgAlbumPeak
            t.rgTrackGain != null -> t.rgTrackGain to t.rgTrackPeak
            else -> null to null
        }
        var lin = if (gain == null) 10.0.pow(settings[S.untaggedPreamp] / 20.0)
        else 10.0.pow((gain + settings[S.replayGainPreamp]) / 20.0)
        if (gain != null && peak != null && peak > 0f && settings[S.replayGainNoClip]) lin = minOf(lin, 1.0 / peak)
        return lin.toFloat()
    }

    // --- writes from playback and analysis -------------------------------------------------------------------------

    fun setExactFrames(trackId: Long, frames: Long) {
        db.writableDatabase.update("tracks", Db.values("exact_frames" to frames), "id = ?", arrayOf(trackId.toString()))
    }

    fun setArtHash(trackId: Long, hash: String) {
        db.writableDatabase.update("tracks", Db.values("art_hash" to hash), "id = ?", arrayOf(trackId.toString()))
    }

    fun countPlay(trackId: Long) {
        db.writableDatabase.execSQL("UPDATE tracks SET play_count = play_count + 1, last_played = ? WHERE id = ?",
            arrayOf<Any>(System.currentTimeMillis(), trackId))
        db.changed(Db.Group.TRACKS)
    }

    // --- scan roots ------------------------------------------------------------------------------------------------

    data class Root(val path: String, val included: Boolean)

    fun scanRoots(): List<Root> = db.query("SELECT path, included FROM scan_roots") { Root(it.getString(0), it.getInt(1) != 0) }

    fun setScanRoots(roots: List<Root>) {
        db.tx {
            delete("scan_roots", null, null)
            for (r in roots) insert("scan_roots", null, Db.values("path" to r.path.trimEnd('/'), "included" to r.included))
        }
    }

    fun isIncluded(path: String, roots: List<Root> = scanRoots()): Boolean = included(path, roots)

    companion object {
        /** Longest matching prefix among the roots decides (docs/plan/03-library-scanner.md T1). */
        fun included(path: String, roots: List<Root>): Boolean {
            var best: Root? = null
            for (r in roots) {
                if ((path == r.path || path.startsWith(r.path + "/")) && (best == null || r.path.length > best.path.length)) best = r
            }
            return best?.included == true
        }
    }

    // --- playlists -------------------------------------------------------------------------------------------------

    fun playlists(): List<Playlist> = db.query(
        "SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlist_id = p.id) FROM playlists p") {
        Playlist(it.getLong(0), it.getString(1), it.getInt(2))
    }.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }

    fun playlistsFlow(): Flow<List<Playlist>> = watch(Db.Group.PLAYLISTS, Db.Group.TRACKS) { playlists() }

    fun playlistTracks(id: Long): List<Track> {
        val entries = db.query("SELECT track_id, path FROM playlist_entries WHERE playlist_id = ? ORDER BY position", id) {
            (if (it.isNull(0)) null else it.getLong(0)) to it.getString(1)
        }
        val byId = tracks(entries.mapNotNull { it.first })
        return entries.mapNotNull { (tid, path) -> tid?.let { byId[it] } ?: trackByPath(path) }
    }

    fun createPlaylist(name: String, trackIds: List<Long> = emptyList()): Long {
        val id = db.writableDatabase.insert("playlists", null, Db.values("name" to name, "created_at" to System.currentTimeMillis()))
        if (trackIds.isNotEmpty()) addToPlaylist(id, trackIds)
        db.changed(Db.Group.PLAYLISTS)
        return id
    }

    fun renamePlaylist(id: Long, name: String) {
        db.writableDatabase.update("playlists", Db.values("name" to name), "id = ?", arrayOf(id.toString()))
        db.changed(Db.Group.PLAYLISTS)
    }

    fun deletePlaylist(id: Long) {
        db.tx {
            delete("playlist_entries", "playlist_id = ?", arrayOf(id.toString()))
            delete("playlists", "id = ?", arrayOf(id.toString()))
        }
        db.changed(Db.Group.PLAYLISTS)
    }

    fun addToPlaylist(id: Long, trackIds: List<Long>) {
        val byId = tracks(trackIds)
        db.tx {
            var pos = (db.long("SELECT MAX(position) FROM playlist_entries WHERE playlist_id = ?", id) ?: -1L) + 1
            for (t in trackIds) {
                val tr = byId[t] ?: continue
                insert("playlist_entries", null, Db.values("playlist_id" to id, "position" to pos++, "track_id" to t, "path" to tr.path))
            }
        }
        db.changed(Db.Group.PLAYLISTS)
    }

    /** Replaces a playlist's entries (after a reorder or removal in the UI). */
    fun setPlaylistTracks(id: Long, trackIds: List<Long>) {
        val byId = tracks(trackIds)
        db.tx {
            delete("playlist_entries", "playlist_id = ?", arrayOf(id.toString()))
            trackIds.forEachIndexed { i, t ->
                val tr = byId[t] ?: return@forEachIndexed
                insert("playlist_entries", null, Db.values("playlist_id" to id, "position" to i, "track_id" to t, "path" to tr.path))
            }
        }
        db.changed(Db.Group.PLAYLISTS)
    }

    /** After a scan: entries whose track id vanished are linked again by path. */
    fun relinkPlaylists() {
        db.writableDatabase.execSQL("""UPDATE playlist_entries SET track_id = (SELECT id FROM tracks WHERE tracks.path = playlist_entries.path)
            WHERE track_id IS NULL OR track_id NOT IN (SELECT id FROM tracks)""")
    }

    /** M3U/M3U8 import: entries resolved against the playlist's folder, matched to library tracks. */
    fun importM3u(file: File): Long {
        val base = file.parentFile
        val ids = file.readLines(Charsets.UTF_8).asSequence()
            .map { it.trim().removePrefix("﻿") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> val f = File(line.replace('\\', '/')); if (f.isAbsolute) f else File(base, line.replace('\\', '/')) }
            .mapNotNull { trackByPath(it.canonicalPath)?.id ?: trackByPath(it.absolutePath)?.id }
            .toList()
        return createPlaylist(file.nameWithoutExtension, ids)
    }

    fun exportM3u(id: Long, target: File) {
        val tracks = playlistTracks(id)
        target.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("#EXTM3U\n")
            for (t in tracks) {
                w.write("#EXTINF:${t.durationMs / 1000},${t.displayArtist} - ${t.displayTitle}\n")
                w.write(t.path + "\n")
            }
        }
    }
}
