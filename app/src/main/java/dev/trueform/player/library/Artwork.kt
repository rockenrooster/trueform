package dev.trueform.player.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import dev.trueform.player.TrueformApp
import dev.trueform.player.audio.Native
import dev.trueform.player.settings.ArtPrefer
import dev.trueform.player.settings.S
import dev.trueform.player.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Album art (docs/plan/03-library-scanner.md T6): embedded picture or folder image, hashed so tracks of one album
 * share cache files, cached as 256 and 1024 pixel WebP, held in a memory cache for lists.
 */
class Artwork(private val context: Context, private val repo: LibraryRepo, private val settings: Settings) {
    private val dir = File(context.cacheDir, "art").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(3)

    fun cached(key: String): Bitmap? = memory.get(key)

    suspend fun track(trackId: Long, size: Int): Bitmap? {
        val key = "t${trackId}_$size"
        memory.get(key)?.let { return it }
        return withContext(io) {
            val f = trackFile(trackId, size) ?: return@withContext null
            decodeFile(f)?.also { memory.put(key, it) }
        }
    }

    suspend fun folder(folderId: Long, size: Int): Bitmap? {
        val key = "f${folderId}_$size"
        memory.get(key)?.let { return it }
        return withContext(io) {
            val f = folderFile(folderId, size) ?: return@withContext null
            decodeFile(f)?.also { memory.put(key, it) }
        }
    }

    /** The cached image file for a track (made on first use), or null when it has no picture. Blocking. */
    fun trackFile(trackId: Long, size: Int): File? {
        val t = repo.track(trackId) ?: return null
        var hash = t.artHash
        var source: ByteArray? = null
        if (hash == null) {
            source = pictureBytes(t)
            hash = source?.let { hashOf(it) } ?: NONE
            repo.setArtHash(trackId, hash)
        }
        if (hash == NONE) return null
        val f = File(dir, "${hash}_$size.webp")
        if (f.exists()) return f
        val bytes = source ?: pictureBytes(t) ?: return null
        return writeScaled(bytes, size, f)
    }

    /** A folder's image in its own directory, else its first track's picture. Blocking. */
    fun folderFile(folderId: Long, size: Int): File? {
        val folder = repo.folder(folderId) ?: return null
        folderImage(File(folder.path))?.let { img ->
            val bytes = runCatching { img.readBytes() }.getOrNull() ?: return@let
            val f = File(dir, "${hashOf(bytes)}_$size.webp")
            return if (f.exists()) f else writeScaled(bytes, size, f)
        }
        return folder.coverTrackId?.let { trackFile(it, size) }
    }

    fun clear() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
        repo.db.writableDatabase.execSQL("UPDATE tracks SET art_hash = NULL")
    }

    private fun pictureBytes(t: Track): ByteArray? {
        val embedded = { runCatching { Native.extractCover(t.path.toByteArray(), -1) }.getOrNull() }
        val folder = { folderImage(File(t.dir))?.let { runCatching { it.readBytes() }.getOrNull() } }
        return if (settings[S.artPrefer] == ArtPrefer.FOLDER) folder() ?: embedded() else embedded() ?: folder()
    }

    private fun folderImage(d: File): File? = d.listFiles()?.filter { f ->
        val name = f.nameWithoutExtension.lowercase()
        val ext = f.extension.lowercase()
        name in IMAGE_NAMES && ext in IMAGE_EXTENSIONS && f.isFile
    }?.maxByOrNull { it.length() }

    private fun writeScaled(bytes: ByteArray, size: Int, target: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = size.toFloat() / maxOf(src.width, src.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.WEBP_LOSSY, if (size >= 1024) 88 else 82, it) }
        if (out !== src) src.recycle()
        out.recycle()
        return if (tmp.renameTo(target)) target else null
    }

    private fun decodeFile(f: File): Bitmap? = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()

    private fun hashOf(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(bytes.size.toString().toByteArray())
        md.update(bytes, 0, minOf(bytes.size, 64 * 1024))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SMALL = 256
        const val LARGE = 1024
        const val NONE = "none"
        private val IMAGE_NAMES = setOf("cover", "folder", "front", "album", "albumart")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        fun trackUri(context: Context, trackId: Long, size: Int = LARGE): Uri =
            Uri.parse("content://${context.packageName}.art/track/$trackId?s=$size")
    }
}

/**
 * Read-only artwork for the system and cars (docs/plan/08-bluetooth-system.md T2): content://<package>.art/track/<id>
 * or /folder/<id>, optional ?s=256. Serves only the cache directory, by numeric id.
 */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val art = (context!!.applicationContext as TrueformApp).graph.artwork
        val segs = uri.pathSegments
        val id = segs.getOrNull(1)?.toLongOrNull() ?: throw FileNotFoundException(uri.toString())
        val size = if (uri.getQueryParameter("s") == "256") Artwork.SMALL else Artwork.LARGE
        val f = when (segs.getOrNull(0)) {
            "track" -> art.trackFile(id, size)
            "folder" -> art.folderFile(id, size)
            else -> null
        } ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/webp"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
