package dev.trueform.player

import dev.trueform.player.ui.player.zoomedBars
import dev.trueform.player.waveform.WaveformData
import dev.trueform.player.waveform.WaveformTuning
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/plan/05-waveform-seekbar.md: the stored blob (columns per track now vary) and the centred seek bar's bars. */
class WaveformTest {
    private fun data(cols: Int, filled: Int = cols) = WaveformData(ByteArray(cols * 5) { (it % 251).toByte() }, filled)

    @Test
    fun blobRoundTripKeepsTheColumnCount() {
        for (cols in listOf(512, 1234, 4800)) {
            val d = data(cols)
            val back = WaveformData.fromBlob(d.toBlob())!!
            assertEquals(cols, back.columns)
            assertArrayEquals(d.bytes, back.bytes)
        }
        assertNull(WaveformData.fromBlob(data(512).toBlob().copyOf(100)))   // truncated
    }

    @Test
    fun fourColumnsASecondWithinBounds() {
        assertEquals(512, WaveformData.columnsFor(60_000))       // a minute: the minimum
        assertEquals(720, WaveformData.columnsFor(180_000))      // three minutes: four a second
        assertEquals(4800, WaveformData.columnsFor(3_600_000))   // an hour: capped (longer files are sampled anyway)
    }

    @Test
    fun zoomedBarsCoverTheTrackAndStopWhereAnalysisIs() {
        val (amp, tone) = zoomedBars(data(720), 180_000, 400f)   // 3 minutes in 0.4 s bars
        assertEquals(450, amp.size)
        assertEquals(450, tone.size)
        assertTrue(amp.all { it in 0f..1f } && tone.all { it in 0f..1f })
        assertEquals(225, zoomedBars(data(720, filled = 360), 180_000, 400f).first.size)   // half analysed
    }

    /** One column per level (dB), even band shares. */
    private fun levels(db: List<Float>) =
        WaveformData(ByteArray(db.size * 5) { if (it % 5 == 1) WaveformData.encodeDb(db[it / 5].toDouble()) else 85.toByte() })

    @Test
    fun barsShowQuietAndLoudPartsAgainstTheTracksOwnLevel() {
        // 10 s silence, 10 s quiet intro, 25 s verse, 25 s chorus, one loud click; four columns a second, a bar per column
        val db = List(40) { -60f } + List(40) { -26f } + List(100) { -14f } + List(100) { -8f } + listOf(0f)
        val amp = zoomedBars(levels(db), db.size * 250L, 250f).first
        assertEquals(1f, amp[230], 0.03f)     // chorus: full height (the click does not set the scale)
        assertEquals(0.5f, amp[130], 0.03f)   // verse, 6 dB under the chorus: half
        assertEquals(0.13f, amp[60], 0.03f)   // intro
        assertEquals(WaveformTuning.MIN, amp[20], 1e-6f)   // silence
        // a silent track stays at the floor instead of being scaled up
        assertTrue(zoomedBars(levels(List(100) { -60f }), 25_000, 250f).first.all { abs(it - WaveformTuning.MIN) < 1e-6f })
    }
}
