package dev.trueform.player.playback

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** The queue and the position, across process deaths (docs/plan/02-playback-engine.md T7). */
class ResumeStore(dir: File) {
    private val queueFile = File(dir, "queue.bin")
    private val positionFile = File(dir, "position.bin")

    data class Saved(val state: QueueState, val nextEntryId: Long, val positionEntry: Long, val positionMs: Long)

    @Synchronized
    fun saveQueue(s: QueueState, nextEntryId: Long) {
        val tmp = File(queueFile.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(VERSION)
            o.writeBoolean(s.shuffle)
            o.writeInt(s.repeat.ordinal)
            o.writeInt(s.index)
            o.writeLong(nextEntryId)
            when (val src = s.source) {
                is QueueSource.Folder -> { o.writeInt(1); o.writeLong(src.folderId); o.writeBoolean(src.recursive); o.writeLong(src.lastFolderId) }
                is QueueSource.Album -> { o.writeInt(2); o.writeUTF(src.key) }
                is QueueSource.Artist -> { o.writeInt(3); o.writeUTF(src.name) }
                is QueueSource.Playlist -> { o.writeInt(4); o.writeLong(src.id) }
                QueueSource.Songs -> o.writeInt(5)
                QueueSource.Mixed -> o.writeInt(6)
                null -> o.writeInt(0)
            }
            o.writeInt(s.entries.size)
            for (e in s.entries) { o.writeLong(e.id); o.writeLong(e.trackId) }
            val nat = s.natural
            o.writeInt(nat?.size ?: -1)
            nat?.forEach { o.writeLong(it) }
        }
        tmp.renameTo(queueFile)
    }

    @Synchronized
    fun savePosition(entryId: Long, positionMs: Long) {
        val tmp = File(positionFile.path + ".tmp")
        DataOutputStream(tmp.outputStream()).use { it.writeLong(entryId); it.writeLong(positionMs) }
        tmp.renameTo(positionFile)
    }

    @Synchronized
    fun load(): Saved? = runCatching {
        DataInputStream(queueFile.inputStream().buffered()).use { i ->
            if (i.readInt() != VERSION) return null
            val shuffle = i.readBoolean()
            val repeat = Repeat.entries[i.readInt()]
            val index = i.readInt()
            val nextId = i.readLong()
            val source: QueueSource? = when (i.readInt()) {
                1 -> QueueSource.Folder(i.readLong(), i.readBoolean(), i.readLong())
                2 -> QueueSource.Album(i.readUTF())
                3 -> QueueSource.Artist(i.readUTF())
                4 -> QueueSource.Playlist(i.readLong())
                5 -> QueueSource.Songs
                6 -> QueueSource.Mixed
                else -> null
            }
            val n = i.readInt()
            val entries = List(n) { QueueEntry(i.readLong(), i.readLong()) }
            val nn = i.readInt()
            val natural = if (nn >= 0) List(nn) { i.readLong() } else null
            val (pe, pm) = runCatching {
                DataInputStream(positionFile.inputStream()).use { p -> p.readLong() to p.readLong() }
            }.getOrDefault(-1L to 0L)
            Saved(QueueState(entries, index.coerceIn(-1, entries.size - 1), shuffle, repeat, source, natural), nextId, pe, pm)
        }
    }.getOrNull()

    private companion object {
        const val VERSION = 1
    }
}
