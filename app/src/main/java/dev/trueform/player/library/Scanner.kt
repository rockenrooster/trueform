package dev.trueform.player.library

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import dev.trueform.player.audio.TrackProbe
import dev.trueform.player.settings.S
import dev.trueform.player.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** A storage volume the user can pick folders on. */
data class Volume(val path: String, val label: String, val primary: Boolean, val mounted: Boolean)

object StorageRoots {
    fun volumes(context: Context): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        val out = ArrayList<Volume>()
        for (v in sm.storageVolumes) {
            val dir = if (android.os.Build.VERSION.SDK_INT >= 30) v.directory else null
            val path = dir?.absolutePath ?: guessPath(context, v.isPrimary, v.uuid) ?: continue
            out += Volume(path, v.getDescription(context), v.isPrimary, v.state == Environment.MEDIA_MOUNTED)
        }
        return out
    }

    /** API 29: a volume's root from the app's external files dirs (cut at /Android/). */
    private fun guessPath(context: Context, primary: Boolean, uuid: String?): String? {
        if (primary) return Environment.getExternalStorageDirectory().absolutePath
        return context.getExternalFilesDirs(null).filterNotNull().map { it.absolutePath.substringBefore("/Android/") }
            .firstOrNull { uuid != null && it.endsWith(uuid) }
    }

    fun defaultRoot(): String {
        val music = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_MUSIC)
        return if (music.isDirectory) music.absolutePath else Environment.getExternalStorageDirectory().absolutePath
    }
}

/**
 * Finds audio files under the chosen folders and keeps the database in step (docs/plan/03-library-scanner.md T3).
 * Phase A walks the folders and makes the library browsable; phase B reads tags with three probes in parallel.
 * Requests are debounced and run one at a time.
 */
class Scanner(
    private val context: Context,
    private val db: Db,
    private val repo: LibraryRepo,
    private val settings: Settings,
    scope: CoroutineScope,
) {
    enum class Phase { IDLE, WALKING, READING_TAGS }
    data class Progress(val phase: Phase = Phase.IDLE, val found: Int = 0, val probed: Int = 0, val toProbe: Int = 0)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    /** Wall-clock milliseconds of the last finished phases, for PROGRESS.md: walk, tags. */
    @Volatile var lastTimings: Pair<Long, Long> = 0L to 0L

    private val requests = Channel<Boolean>(Channel.CONFLATED)
    private val breadcrumbDir = File(context.filesDir, "probing").apply { mkdirs() }

    init {
        scope.launch(Dispatchers.IO) {
            markCrashedProbes()
            var full = false
            for (f in requests) {
                full = full || f
                delay(DEBOUNCE_MS)
                requests.tryReceive().getOrNull()?.let { full = full || it }
                try {
                    run(full)
                } catch (t: Throwable) {
                    Log.e(TAG, "scan failed", t)
                } finally {
                    full = false
                    _progress.value = Progress()
                }
            }
        }
    }

    fun request(full: Boolean = false) {
        requests.trySend(full)
    }

    // --- the scan --------------------------------------------------------------------------------------------------

    private data class Found(val path: String, val name: String, val dir: String, val size: Long, val mtime: Long)
    private data class Known(val id: Long, val path: String, val name: String, val size: Long, val mtime: Long)

    private suspend fun run(full: Boolean) {
        val roots = repo.scanRoots()
        if (roots.isEmpty()) return
        val t0 = System.currentTimeMillis()
        _progress.value = Progress(Phase.WALKING)

        val included = roots.filter { it.included }
        val readable = included.filter { File(it.path).let { f -> f.isDirectory && f.canRead() } }.map { it.path }
        val unreadable = included.map { it.path } - readable.toSet()

        // Phase A: walk
        val found = HashMap<String, Found>()
        val visited = HashSet<String>()
        for (root in readable) walk(File(root), roots, found, visited)
        _progress.value = Progress(Phase.WALKING, found = found.size)

        val known = HashMap<String, Known>()
        db.query("SELECT id, path, file_name, size, mtime FROM tracks") {
            Known(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4))
        }.forEach { known[it.path] = it }

        fun underReadable(path: String) = readable.any { path.startsWith("$it/") }
        fun underUnreadable(path: String) = unreadable.any { path.startsWith("$it/") }

        val added = found.values.filter { it.path !in known }
        val changed = found.values.filter { f -> known[f.path]?.let { it.size != f.size || it.mtime != f.mtime } == true }
        val gone = known.values.filter { it.path !in found && !underUnreadable(it.path) && (underReadable(it.path) || !repo.isIncluded(it.path, roots)) }

        // Moves: same name, size and time at a new path keep their row (play counts, playlists, waveform).
        val goneByKey = gone.groupBy { Triple(it.name, it.size, it.mtime) }.mapValues { it.value.toMutableList() }
        val moves = ArrayList<Pair<Known, Found>>()
        val reallyAdded = ArrayList<Found>()
        for (a in added) {
            val m = goneByKey[Triple(a.name, a.size, a.mtime)]?.removeFirstOrNull()
            if (m != null) moves += m to a else reallyAdded += a
        }
        val movedIds = moves.map { it.first.id }.toSet()
        val reallyGone = gone.filter { it.id !in movedIds }

        // Folders: every directory with a file, and its ancestors up to its top folder.
        val folderIds = upsertFolders(found.values.map { it.dir }.toSet(), roots)
        val now = System.currentTimeMillis()
        val ins = db.writableDatabase.compileStatement(
            "INSERT INTO tracks(path, folder_id, file_name, size, mtime, title, date_added) VALUES (?, ?, ?, ?, ?, '', ?)")
        for (chunk in reallyAdded.chunked(200)) db.tx {
            for (f in chunk) {
                ins.clearBindings()
                ins.bindString(1, f.path); ins.bindLong(2, folderIds.getValue(f.dir)); ins.bindString(3, f.name)
                ins.bindLong(4, f.size); ins.bindLong(5, f.mtime); ins.bindLong(6, now)
                ins.executeInsert()
            }
        }
        db.tx {
            for (f in changed) {
                update("tracks", Db.values("size" to f.size, "mtime" to f.mtime, "meta_state" to 0, "exact_frames" to null, "art_hash" to null),
                    "path = ?", arrayOf(f.path))
                known[f.path]?.let { delete("waveforms", "track_id = ?", arrayOf(it.id.toString())) }
            }
            for ((k, f) in moves) update("tracks", Db.values("path" to f.path, "file_name" to f.name, "folder_id" to folderIds.getValue(f.dir)),
                "id = ?", arrayOf(k.id.toString()))
            for (k in reallyGone) {
                delete("tracks", "id = ?", arrayOf(k.id.toString()))
                delete("waveforms", "track_id = ?", arrayOf(k.id.toString()))
            }
            // Volumes that are missing keep their rows, marked unavailable; present ones are available again.
            for (u in unreadable) execSQL("UPDATE tracks SET available = 0 WHERE path LIKE ? ESCAPE '\\'", arrayOf("${escape(u)}/%"))
            for (r in readable) execSQL("UPDATE tracks SET available = 1 WHERE available = 0 AND path LIKE ? ESCAPE '\\'", arrayOf("${escape(r)}/%"))
        }
        rebuildFolders(unreadable)
        repo.relinkPlaylists()
        db.changed(Db.Group.TRACKS, Db.Group.FOLDERS, Db.Group.PLAYLISTS)
        val t1 = System.currentTimeMillis()
        Log.i(TAG, "walk: ${found.size} files, +${reallyAdded.size} ~${changed.size} >${moves.size} -${reallyGone.size} in ${t1 - t0} ms")

        // Phase B: tags
        val pending = db.query(if (full) "SELECT id, path FROM tracks WHERE available = 1" else
            "SELECT id, path FROM tracks WHERE meta_state = 0 AND available = 1") { it.getLong(0) to it.getString(1) }
        if (pending.isNotEmpty()) probeAll(pending)
        val minMs = settings[S.minDurationSec] * 1000L
        if (minMs > 0) db.writableDatabase.delete("tracks", "meta_state = 1 AND duration_ms < ?", arrayOf(minMs.toString()))
        rebuildFolders(unreadable)
        db.changed(Db.Group.TRACKS, Db.Group.FOLDERS)
        val t2 = System.currentTimeMillis()
        lastTimings = (t1 - t0) to (t2 - t1)
        Log.i(TAG, "tags: ${pending.size} files in ${t2 - t1} ms")
    }

    private fun walk(dir: File, roots: List<LibraryRepo.Root>, out: HashMap<String, Found>, visited: HashSet<String>) {
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
        if (!visited.add(canonical)) return   // no symlink loops
        val entries = dir.listFiles() ?: return   // unreadable: treat as empty, delete nothing below (see run)
        for (f in entries) {
            val name = f.name
            if (f.isDirectory) {
                if (name.startsWith(".") || name == "Android") continue
                if (!repo.isIncluded(f.path, roots) && roots.none { it.included && it.path.startsWith(f.path + "/") }) continue
                walk(f, roots, out, visited)
            } else {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in EXTENSIONS || !repo.isIncluded(f.path, roots)) continue
                out[f.path] = Found(f.path, name, dir.path, f.length(), f.lastModified())
            }
        }
        _progress.value = _progress.value.copy(found = out.size)
    }

    /** Ids for every needed directory (inserting new ones); top folders are the included roots that hold them. */
    private fun upsertFolders(dirs: Set<String>, roots: List<LibraryRepo.Root>): Map<String, Long> {
        val existing = HashMap<String, Long>()
        db.query("SELECT id, path FROM folders") { it.getString(1) to it.getLong(0) }.forEach { existing[it.first] = it.second }
        val tops = roots.filter { it.included }.map { it.path }.sortedByDescending { it.length }
        val all = HashSet<String>()
        for (d in dirs) {
            val top = tops.firstOrNull { d == it || d.startsWith("$it/") } ?: d
            var p: String? = d
            while (p != null) {
                all += p
                if (p == top) break
                p = File(p).parent
            }
        }
        val ins = db.writableDatabase.compileStatement("INSERT INTO folders(path, name, depth) VALUES (?, ?, 0)")
        db.tx {
            for (p in all.sortedBy { it.length }) {
                if (p in existing) continue
                ins.clearBindings()
                ins.bindString(1, p); ins.bindString(2, File(p).name.ifEmpty { p })
                existing[p] = ins.executeInsert()
            }
        }
        return existing
    }

    /** Parents, depth, counts, durations, cover track and library (depth-first, natural) order of every folder. */
    private fun rebuildFolders(unreadableRoots: List<String>) {
        val folders = db.query("SELECT id, path, name FROM folders") { Triple(it.getLong(0), it.getString(1), it.getString(2)) }
        val idByPath = folders.associate { it.second to it.first }
        val roots = repo.scanRoots()
        val tops = roots.filter { it.included }.map { it.path }.toSet()
        val parentOf = HashMap<Long, Long?>()
        for ((id, path, _) in folders) {
            val parentPath = if (path in tops) null else File(path).parent
            parentOf[id] = parentPath?.let { idByPath[it] }
        }
        data class T(val id: Long, val folder: Long, val name: String, val dur: Long, val available: Boolean)
        val tracks = db.query("SELECT id, folder_id, file_name, duration_ms, available FROM tracks") {
            T(it.getLong(0), it.getLong(1), it.getString(2), it.getLong(3), it.getInt(4) != 0)
        }
        val direct = tracks.groupBy { it.folder }
        val children = HashMap<Long?, MutableList<Triple<Long, String, String>>>()
        for (f in folders) children.getOrPut(parentOf[f.first]) { ArrayList() } += f
        children.values.forEach { l -> l.sortWith { a, b -> NaturalOrder.compare(a.third, b.third) } }

        val totals = HashMap<Long, Pair<Int, Long>>()
        val cover = HashMap<Long, Long?>()
        val order = HashMap<Long, Int>()
        val depth = HashMap<Long, Int>()
        var counter = 0
        fun visit(id: Long, d: Int): Pair<Int, Long> {
            order[id] = counter++
            depth[id] = d
            val own = direct[id].orEmpty()
            var n = own.size
            var dur = own.sumOf { it.dur }
            var c: Long? = own.minWithOrNull { a, b -> NaturalOrder.compare(a.name, b.name) }?.id
            for (child in children[id].orEmpty()) {
                val (cn, cd) = visit(child.first, d + 1)
                n += cn; dur += cd
                if (c == null && cn > 0) c = cover[child.first]
            }
            totals[id] = n to dur
            cover[id] = c
            return n to dur
        }
        for (top in children[null].orEmpty()) visit(top.first, 0)

        db.tx {
            val upd = compileStatement("""UPDATE folders SET parent_id = ?, depth = ?, tracks_direct = ?, tracks_total = ?,
                duration_total = ?, dfs_order = ?, cover_track = ?, subfolders = ?, available = ? WHERE id = ?""")
            for ((id, path, _) in folders) {
                val (n, dur) = totals[id] ?: (0 to 0L)
                val unavailable = unreadableRoots.any { path == it || path.startsWith("$it/") }
                if (n == 0 && !unavailable) { delete("folders", "id = ?", arrayOf(id.toString())); continue }
                upd.clearBindings()
                parentOf[id]?.let { upd.bindLong(1, it) } ?: upd.bindNull(1)
                upd.bindLong(2, (depth[id] ?: 0).toLong())
                upd.bindLong(3, direct[id]?.size?.toLong() ?: 0)
                upd.bindLong(4, n.toLong())
                upd.bindLong(5, dur)
                upd.bindLong(6, (order[id] ?: 0).toLong())
                cover[id]?.let { upd.bindLong(7, it) } ?: upd.bindNull(7)
                upd.bindLong(8, children[id]?.count { (totals[it.first]?.first ?: 0) > 0 }?.toLong() ?: 0)
                upd.bindLong(9, if (unavailable) 0 else 1)
                upd.bindLong(10, id)
                upd.executeUpdateDelete()
            }
        }
    }

    private suspend fun probeAll(pending: List<Pair<Long, String>>) = coroutineScope {
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        _progress.value = Progress(Phase.READING_TAGS, found = _progress.value.found, probed = 0, toProbe = pending.size)
        val workers = (0 until PROBES).map { w ->
            launch(Dispatchers.IO) {
                val crumb = File(breadcrumbDir, "w$w")
                val batch = ArrayList<Pair<Long, android.content.ContentValues>>()
                fun flush() {
                    if (batch.isEmpty()) return
                    db.tx { for ((id, v) in batch) update("tracks", v, "id = ?", arrayOf(id.toString())) }
                    batch.clear()
                    db.changed(Db.Group.TRACKS)
                }
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= pending.size) break
                    val (id, path) = pending[i]
                    crumb.writeText(path)
                    val v = try {
                        val p = TrackProbe.probe(path)
                        Db.values(
                            "title" to p.title, "artist" to p.artist, "album" to p.album, "album_artist" to p.albumArtist,
                            "genre" to p.genre, "composer" to p.composer, "year" to p.year, "track_no" to p.trackNo,
                            "disc_no" to p.discNo, "duration_ms" to p.durationMs, "sample_rate" to p.sampleRate,
                            "bits" to p.bits, "channels" to p.channels, "codec" to p.codec, "container" to p.container,
                            "bitrate" to p.bitrate, "rg_track_gain" to p.rgTrackGain, "rg_track_peak" to p.rgTrackPeak,
                            "rg_album_gain" to p.rgAlbumGain, "rg_album_peak" to p.rgAlbumPeak, "meta_state" to 1,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "probe failed: $path: ${e.message}")
                        Db.values("meta_state" to 2)
                    }
                    crumb.delete()
                    batch += id to v
                    if (batch.size >= 50) flush()
                    val d = done.incrementAndGet()
                    if (d % 25 == 0) _progress.value = _progress.value.copy(probed = d)
                }
                flush()
            }
        }
        workers.forEach { it.join() }
        _progress.value = _progress.value.copy(probed = done.get())
    }

    /** One file outside the library ("open with"): a row without a folder, tags read now. Null when unreadable. */
    fun addSingle(path: String): Track? {
        val f = File(path)
        val p = runCatching { TrackProbe.probe(path) }.getOrNull() ?: return null
        db.writableDatabase.insertWithOnConflict("tracks", null, Db.values(
            "path" to path, "folder_id" to 0L, "file_name" to f.name, "size" to f.length(), "mtime" to f.lastModified(),
            "title" to p.title, "artist" to p.artist, "album" to p.album, "album_artist" to p.albumArtist, "genre" to p.genre,
            "composer" to p.composer, "year" to p.year, "track_no" to p.trackNo, "disc_no" to p.discNo,
            "duration_ms" to p.durationMs, "sample_rate" to p.sampleRate, "bits" to p.bits, "channels" to p.channels,
            "codec" to p.codec, "container" to p.container, "bitrate" to p.bitrate, "rg_track_gain" to p.rgTrackGain,
            "rg_track_peak" to p.rgTrackPeak, "rg_album_gain" to p.rgAlbumGain, "rg_album_peak" to p.rgAlbumPeak,
            "meta_state" to 1, "date_added" to System.currentTimeMillis()), android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        db.changed(Db.Group.TRACKS)
        return repo.trackByPath(path)
    }

    /** A probe that took the process down left its path behind: never open that file again by itself. */
    private fun markCrashedProbes() {
        breadcrumbDir.listFiles()?.forEach { f ->
            val path = runCatching { f.readText() }.getOrNull()
            if (path != null) {
                Log.w(TAG, "the last probe of $path did not return: marked broken")
                db.writableDatabase.update("tracks", Db.values("meta_state" to 2), "path = ?", arrayOf(path))
            }
            f.delete()
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        private const val TAG = "TFSCAN"
        private const val DEBOUNCE_MS = 2000L
        private const val PROBES = 3
        val EXTENSIONS = setOf("mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wma", "wav", "aif", "aiff",
            "ape", "wv", "mpc", "tta", "dsf", "dff", "mka", "ac3", "dts", "amr", "mp2", "caf", "w64")
    }
}
