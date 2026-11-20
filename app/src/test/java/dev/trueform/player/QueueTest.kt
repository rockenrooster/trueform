package dev.trueform.player

import dev.trueform.player.library.LibraryRepo
import dev.trueform.player.library.NaturalOrder
import dev.trueform.player.playback.QueueModel
import dev.trueform.player.playback.QueueSource
import dev.trueform.player.playback.QueueState
import dev.trueform.player.playback.Repeat
import dev.trueform.player.playback.WindowPlanner
import dev.trueform.player.playback.WindowPlanner.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueTest {
    private var next = 1L
    private val model = QueueModel({ next++ }, Random(1))

    private fun tracks(s: QueueState) = s.entries.map { it.trackId }

    @Test
    fun shuffleKeepsCurrentAndRestoresOrder() {
        val s0 = model.play((100L..119L).toList(), 5, shuffle = false, repeat = Repeat.OFF, source = QueueSource.Songs)
        assertEquals(105L, s0.current!!.trackId)
        val s1 = model.setShuffle(s0, true)
        assertEquals(0, s1.index)
        assertEquals(105L, s1.current!!.trackId)
        assertEquals((100L..119L).toSet(), tracks(s1).toSet())
        val s2 = model.append(s1, listOf(200L, 201L))
        val s3 = model.setShuffle(model.jump(s2, 7), false)
        assertEquals((100L..119L).toList() + listOf(200L, 201L), tracks(s3))
        assertEquals(s2.entries[7].id, s3.current!!.id)
    }

    @Test
    fun editsKeepTheCurrentEntry() {
        var s = model.play(listOf(1L, 2L, 3L, 4L), 1, false, Repeat.OFF, null)
        s = model.insertNext(s, listOf(9L))
        assertEquals(listOf(1L, 2L, 9L, 3L, 4L), tracks(s))
        assertEquals(2L, s.current!!.trackId)
        s = model.remove(s, s.entries[0].id)
        assertEquals(0, s.index); assertEquals(2L, s.current!!.trackId)
        s = model.move(s, 0, 3)
        assertEquals(listOf(9L, 3L, 4L, 2L), tracks(s)); assertEquals(2L, s.current!!.trackId)
        s = model.remove(s, s.current!!.id)
        assertEquals(listOf(9L, 3L, 4L), tracks(s)); assertEquals(2, s.index)
    }

    @Test
    fun windowOfSmallAndLargeQueues() {
        val small = model.play((1L..10L).toList(), 4, false, Repeat.ALL, null)
        val (w, i) = WindowPlanner.window(small)
        assertEquals(10, w.size); assertEquals(4, i)
        val big = model.play((1L..100L).toList(), 50, false, Repeat.OFF, null)
        val (wb, ib) = WindowPlanner.window(big)
        assertEquals(33, wb.size); assertEquals(2, ib)
        val end = model.jump(big, 99)
        val (we, ie) = WindowPlanner.window(end)
        assertEquals(3, we.size); assertEquals(2, ie)
        val wrap = model.jump(model.play((1L..100L).toList(), 0, false, Repeat.ALL, null), 99)
        val (ww, iw) = WindowPlanner.window(wrap)
        assertEquals(33, ww.size); assertEquals(2, iw)
        assertEquals(ww.size, ww.toSet().size)
        assertEquals(wrap.entries[0].id, ww[3])   // after the last entry comes the first
    }

    private fun apply(list: MutableList<Long>, cur: Int, ops: List<Op>): Pair<MutableList<Long>, Int> {
        var c = cur
        var l = list
        for (op in ops) when (op) {
            is Op.Reset -> { l = op.ids.toMutableList(); c = op.index }
            is Op.Remove -> { repeat(op.to - op.from) { l.removeAt(op.from) }; if (op.to <= c) c -= op.to - op.from }
            is Op.Add -> { l.addAll(op.at, op.ids); if (op.at <= c) c += op.ids.size }
        }
        return l to c
    }

    @Test
    fun topUpAfterATransitionOnlyAddsAndTrims() {
        val s = model.play((1L..100L).toList(), 10, false, Repeat.OFF, null)
        val (w, i) = WindowPlanner.window(s)
        val moved = model.jump(s, 11)
        val (w2, i2) = WindowPlanner.window(moved)
        val ops = WindowPlanner.plan(w, i + 1, w2, i2)
        assertTrue(ops.none { it is Op.Reset })
        val (l, c) = apply(w.toMutableList(), i + 1, ops)
        assertEquals(w2, l); assertEquals(i2, c)
    }

    @Test
    fun randomEditsAlwaysEndAtTheWindow() {
        val rnd = Random(42)
        var s = model.play((1L..80L).toList(), 0, false, Repeat.OFF, null)
        var (list, cur) = WindowPlanner.window(s).let { it.first.toMutableList() to it.second }
        repeat(2000) {
            s = when (rnd.nextInt(7)) {
                0 -> model.jump(s, rnd.nextInt(s.entries.size))
                1 -> model.setShuffle(s, !s.shuffle)
                2 -> model.insertNext(s, listOf(rnd.nextLong(1000)))
                3 -> model.append(s, listOf(rnd.nextLong(1000), rnd.nextLong(1000)))
                4 -> if (s.entries.size > 5) model.remove(s, s.entries[rnd.nextInt(s.entries.size)].id) else s
                5 -> model.move(s, rnd.nextInt(s.entries.size), rnd.nextInt(s.entries.size))
                else -> model.jump(s, (s.index + 1).coerceAtMost(s.entries.size - 1))   // a transition
            }.let { if (rnd.nextInt(20) == 0) it.copy(repeat = Repeat.entries[rnd.nextInt(3)]) else it }
            val (want, wi) = WindowPlanner.window(s)
            val ops = WindowPlanner.plan(list, cur, want, wi)
            val r = apply(list, cur, ops)
            list = r.first; cur = r.second
            assertEquals(want, list)
            assertEquals(wi, cur)
            assertTrue(list.size <= WindowPlanner.BACK + WindowPlanner.AHEAD + 1)
        }
    }

    @Test
    fun naturalOrder() {
        val names = listOf("Track 10", "Track 2", "track 1", "Track 3a", "", "b", "A")
        assertEquals(listOf("", "A", "b", "track 1", "Track 2", "Track 3a", "Track 10"), names.sortedWith(NaturalOrder))
        assertTrue(NaturalOrder.compare("Étude", "Etude") != 0)   // accents differ, but never equal-and-unstable
        assertTrue(NaturalOrder.compare("01 Intro", "1 Intro") != 0)
        assertTrue(NaturalOrder.compare("Disc 2/Track 1", "Disc 10/Track 1") < 0)
    }

    @Test
    fun scanRootPrefixes() {
        val roots = listOf(LibraryRepo.Root("/s/Music", true), LibraryRepo.Root("/s/Music/Podcasts", false),
            LibraryRepo.Root("/s/Music/Podcasts/Keep", true))
        assertTrue(LibraryRepo.included("/s/Music/a.flac", roots))
        assertTrue(!LibraryRepo.included("/s/Music/Podcasts/b.mp3", roots))
        assertTrue(LibraryRepo.included("/s/Music/Podcasts/Keep/c.mp3", roots))
        assertTrue(!LibraryRepo.included("/s/Musicals/d.mp3", roots))
        assertTrue(!LibraryRepo.included("/s/Other/e.mp3", roots))
    }
}
