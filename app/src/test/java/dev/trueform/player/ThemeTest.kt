package dev.trueform.player

import dev.trueform.player.ui.theme.BlackColors
import dev.trueform.player.ui.theme.DarkColors
import dev.trueform.player.ui.theme.LightColors
import dev.trueform.player.ui.theme.contrast
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/plan/04-ui-ux.md, 11-testing-hardening.md: readable text in every theme (WCAG contrast ratios). */
class ThemeTest {
    @Test
    fun textAndAccentAreReadable() {
        for ((name, t) in listOf("dark" to DarkColors, "black" to BlackColors, "light" to LightColors)) {
            for ((surfaceName, s) in listOf("bg" to t.bg, "surface1" to t.surface1, "surface2" to t.surface2)) {
                fun check(what: String, fg: androidx.compose.ui.graphics.Color, min: Double) {
                    val r = contrast(fg, s)
                    assertTrue("$name: $what on $surfaceName is %.2f:1, needs %.1f:1".format(r, min), r >= min)
                }
                check("text", t.text, 7.0)
                check("secondary text", t.textSecondary, 4.5)
                check("tertiary text", t.textTertiary, 3.0)      // hints and disabled rows: large-text level
                check("accent", t.accent, 3.0)                   // icons, outlines, the playhead
            }
            assertTrue("$name: text on the accent button", contrast(t.onAccent, t.accent) >= 4.5)
        }
    }
}
