package dev.trueform.player

import dev.trueform.player.audio.TrackProbe
import dev.trueform.player.settings.S
import dev.trueform.player.settings.matches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    @Test
    fun tagNumbers() {
        assertEquals(3, TrackProbe.parseLeadingNumber("3/12"))
        assertEquals(3, TrackProbe.parseLeadingNumber(" 03"))
        assertEquals(0, TrackProbe.parseLeadingNumber(""))
        assertEquals(0, TrackProbe.parseLeadingNumber("A1"))
        assertEquals(2019, TrackProbe.parseYear("2019-05-01"))
        assertEquals(1987, TrackProbe.parseYear("1987"))
        assertEquals(0, TrackProbe.parseYear("unknown"))
    }

    @Test
    fun replayGainStrings() {
        assertEquals(-6.54f, TrackProbe.parseGainDb("-6.54 dB")!!, 1e-4f)
        assertEquals(1.2f, TrackProbe.parseGainDb("+1.2dB")!!, 1e-4f)
        assertEquals(-6.54f, TrackProbe.parseGainDb("-6,54 dB")!!, 1e-4f)
        assertNull(TrackProbe.parseGainDb(""))
        assertNull(TrackProbe.parseGainDb("loud"))
        assertEquals(0.988f, TrackProbe.parsePeak("0.988000")!!, 1e-6f)
        assertNull(TrackProbe.parsePeak("0"))
        // R128: Q7.8 relative to -23 LUFS; ReplayGain reference is 5 dB louder
        assertEquals(-1f + 5f, TrackProbe.r128ToReplayGain(-256), 1e-6f)
    }

    @Test
    fun settingsRegistryIsConsistent() {
        val keys = S.all.map { it.key }
        assertEquals("duplicate keys", keys.size, keys.toSet().size)
        for (s in S.all) {
            assertTrue("${s.key} has a title", s.title.isNotBlank())
            when (s) {
                is dev.trueform.player.settings.Number -> assertTrue(s.key, s.default in s.range)
                is dev.trueform.player.settings.Decibels -> assertTrue(s.key, s.default in s.range)
                is dev.trueform.player.settings.Choice<*> -> assertTrue(s.key, s.default in s.options)
                else -> Unit
            }
        }
    }

    @Test
    fun settingsSearchFindsTheExitCheckWords() {   // docs/plan/09-settings.md exit check 3
        for (q in listOf("bass", "gap", "bluetooth", "cast", "update", "folder")) {
            assertTrue("search '$q' finds nothing", S.all.any { it.matches(q) })
        }
        assertTrue(S.all.none { it.matches("   ") })
    }
}
