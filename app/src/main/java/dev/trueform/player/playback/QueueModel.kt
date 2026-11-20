package dev.trueform.player.playback

import kotlin.random.Random

data class QueueEntry(val id: Long, val trackId: Long)

enum class Repeat { OFF, ALL, ONE }

/** What the user asked to play; auto-advance uses it (docs/plan/02-playback-engine.md T5). */
sealed interface QueueSource {
    data class Folder(val folderId: Long, val recursive: Boolean, val lastFolderId: Long) : QueueSource
    data class Album(val key: String) : QueueSource
    data class Artist(val name: String) : QueueSource
    data class Playlist(val id: Long) : QueueSource
    data object Songs : QueueSource
    data object Mixed : QueueSource
}

/** The logical queue. [index] is -1 when empty. [natural] holds entry ids in unshuffled order while shuffle is on. */
data class QueueState(
    val entries: List<QueueEntry> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.OFF,
    val source: QueueSource? = null,
    val natural: List<Long>? = null,
) {
    val current: QueueEntry? get() = entries.getOrNull(index)
    val isEmpty get() = entries.isEmpty()
    fun indexOf(entryId: Long, near: Int = index): Int {
        val lo = (near - 3).coerceAtLeast(0)
        val hi = (near + 40).coerceAtMost(entries.size - 1)
        for (i in lo..hi) if (entries[i].id == entryId) return i
        return entries.indexOfFirst { it.id == entryId }
    }
}

/** Pure queue operations; every one returns a new state (JVM-tested). [ids] hands out entry ids. */
class QueueModel(private val ids: () -> Long, private val random: Random = Random.Default) {

    fun play(trackIds: List<Long>, startIndex: Int, shuffle: Boolean, repeat: Repeat, source: QueueSource?): QueueState {
        if (trackIds.isEmpty()) return QueueState(repeat = repeat, shuffle = shuffle)
        val entries = trackIds.map { QueueEntry(ids(), it) }
        val start = startIndex.coerceIn(0, entries.size - 1)
        val base = QueueState(entries, start, false, repeat, source, null)
        return if (shuffle) setShuffle(base, true) else base
    }

    /** On: the current entry first, the rest shuffled; off: the natural order back, still on the current entry. */
    fun setShuffle(s: QueueState, on: Boolean): QueueState {
        if (on == s.shuffle) return s
        if (s.isEmpty) return s.copy(shuffle = on, natural = null)
        val cur = s.current!!
        return if (on) {
            val rest = s.entries.filter { it.id != cur.id }.toMutableList()
            rest.shuffle(random)
            s.copy(entries = listOf(cur) + rest, index = 0, shuffle = true, natural = s.entries.map { it.id })
        } else {
            val byId = s.entries.associateBy { it.id }
            val natural = s.natural.orEmpty().mapNotNull { byId[it] }
            val known = natural.map { it.id }.toSet()
            val ordered = natural + s.entries.filter { it.id !in known }   // entries added while shuffled go last
            s.copy(entries = ordered, index = ordered.indexOfFirst { it.id == cur.id }.coerceAtLeast(0), shuffle = false, natural = null)
        }
    }

    fun insertNext(s: QueueState, trackIds: List<Long>): QueueState {
        if (trackIds.isEmpty()) return s
        val add = trackIds.map { QueueEntry(ids(), it) }
        if (s.isEmpty) return s.copy(entries = add, index = 0, source = QueueSource.Mixed)
        val at = s.index + 1
        val entries = s.entries.toMutableList().apply { addAll(at, add) }
        val natural = s.natural?.let { n ->
            val curPos = n.indexOf(s.current!!.id)
            n.toMutableList().apply { addAll(curPos + 1, add.map { it.id }) }
        }
        return s.copy(entries = entries, natural = natural, source = QueueSource.Mixed)
    }

    fun append(s: QueueState, trackIds: List<Long>, keepSource: Boolean = false): QueueState {
        if (trackIds.isEmpty()) return s
        val add = trackIds.map { QueueEntry(ids(), it) }
        if (s.isEmpty) return s.copy(entries = add, index = 0, source = if (keepSource) s.source else QueueSource.Mixed)
        return s.copy(
            entries = s.entries + add,
            natural = s.natural?.plus(add.map { it.id }),
            source = if (keepSource) s.source else QueueSource.Mixed,
        )
    }

    fun remove(s: QueueState, entryId: Long): QueueState {
        val i = s.entries.indexOfFirst { it.id == entryId }
        if (i < 0) return s
        val entries = s.entries.toMutableList().apply { removeAt(i) }
        val index = when {
            entries.isEmpty() -> -1
            i < s.index -> s.index - 1
            i == s.index -> s.index.coerceAtMost(entries.size - 1)
            else -> s.index
        }
        return s.copy(entries = entries, index = index, natural = s.natural?.minus(entryId))
    }

    fun move(s: QueueState, from: Int, to: Int): QueueState {
        if (from == to || from !in s.entries.indices || to !in s.entries.indices) return s
        val entries = s.entries.toMutableList()
        val e = entries.removeAt(from)
        entries.add(to, e)
        val curId = s.current?.id
        return s.copy(entries = entries, index = entries.indexOfFirst { it.id == curId })
    }

    fun jump(s: QueueState, index: Int): QueueState =
        if (index in s.entries.indices) s.copy(index = index) else s

    fun clear(s: QueueState) = QueueState(shuffle = s.shuffle, repeat = s.repeat)
}

/**
 * The window of the logical queue the player holds (BACK before the current entry, AHEAD after), and the fewest
 * player operations that turn the player's list into it (docs/plan/02-playback-engine.md T5).
 */
object WindowPlanner {
    const val BACK = 2
    const val AHEAD = 30

    sealed interface Op {
        data class Reset(val ids: List<Long>, val index: Int) : Op
        data class Remove(val from: Int, val to: Int) : Op          // [from, to)
        data class Add(val at: Int, val ids: List<Long>) : Op
    }

    /** Entry ids of the window and the current entry's position in it. */
    fun window(s: QueueState, ahead: Int = AHEAD): Pair<List<Long>, Int> {
        val n = s.entries.size
        if (n == 0 || s.index < 0) return emptyList<Long>() to -1
        if (n <= BACK + ahead + 1) return s.entries.map { it.id } to s.index
        val out = ArrayList<Long>(BACK + ahead + 1)
        var idx = 0
        for (k in -BACK..ahead) {
            val i = s.index + k
            val j = when {
                i in 0 until n -> i
                s.repeat == Repeat.ALL -> ((i % n) + n) % n
                else -> -1
            }
            if (j < 0) continue
            if (k == 0) idx = out.size
            out += s.entries[j].id
        }
        return out to idx
    }

    fun plan(cur: List<Long>, curIndex: Int, want: List<Long>, wantIndex: Int): List<Op> {
        if (want.isEmpty()) return if (cur.isEmpty()) emptyList() else listOf(Op.Remove(0, cur.size))
        if (cur.isEmpty() || curIndex !in cur.indices || cur[curIndex] != want[wantIndex]) return listOf(Op.Reset(want, wantIndex))
        val ops = ArrayList<Op>()
        // after the current entry: keep the common prefix, replace the rest
        val curAfter = cur.subList(curIndex + 1, cur.size)
        val wantAfter = want.subList(wantIndex + 1, want.size)
        var p = 0
        while (p < curAfter.size && p < wantAfter.size && curAfter[p] == wantAfter[p]) p++
        if (p < curAfter.size) ops += Op.Remove(curIndex + 1 + p, cur.size)
        if (p < wantAfter.size) ops += Op.Add(curIndex + 1 + p, wantAfter.subList(p, wantAfter.size).toList())
        // before it: keep the common suffix, replace the rest
        val curBefore = cur.subList(0, curIndex)
        val wantBefore = want.subList(0, wantIndex)
        var q = 0
        while (q < curBefore.size && q < wantBefore.size && curBefore[curBefore.size - 1 - q] == wantBefore[wantBefore.size - 1 - q]) q++
        if (q < curBefore.size) ops += Op.Remove(0, curBefore.size - q)
        if (q < wantBefore.size) ops += Op.Add(0, wantBefore.subList(0, wantBefore.size - q).toList())
        return ops
    }
}
