package dev.trueform.player.ui.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trueform.player.settings.WaveColor
import dev.trueform.player.settings.WaveDetail
import dev.trueform.player.settings.WaveShape
import dev.trueform.player.ui.components.formatDuration
import dev.trueform.player.ui.theme.InterFamily
import dev.trueform.player.ui.theme.LocalHaptics
import dev.trueform.player.ui.theme.Tf
import dev.trueform.player.waveform.WaveformData
import dev.trueform.player.waveform.WaveformRepo
import dev.trueform.player.waveform.WaveformTuning
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * The Trueform seekbar (docs/plan/05-waveform-seekbar.md T2, T3): bar height is loudness, colour is the balance of
 * bass, mids and treble, the played part is bright. The bars are drawn once into a bitmap per size and style; each
 * frame only the played/unplayed clip moves. Tap seeks, drag scrubs (seek on release).
 */
@Composable
fun WaveSeekbar(
    data: WaveformData?,
    durationMs: Long,
    position: () -> Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 80.dp,
    detail: WaveDetail = WaveDetail.FINE,
    shape: WaveShape = WaveShape.MIRRORED,
    colorMode: WaveColor = WaveColor.SPECTRAL,
    accent: Color = Tf.colors.accent,
    seekWhileDragging: Boolean = false,
    compact: Boolean = false,
) {
    val c = Tf.colors
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val hapticsOn = LocalHaptics.current
    val measurer = rememberTextMeasurer()
    val frame by produceState(0L, playing) { if (playing) while (true) withFrameMillis { value = it } }
    var dragX by remember { mutableStateOf<Float?>(null) }
    var lastLiveSeek by remember { mutableStateOf(0L) }
    val dur by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val low = c.waveLow; val mid = c.waveMid; val high = c.waveHigh
    val text = c.text

    val gestures = if (compact) Modifier else Modifier
        .pointerInput(Unit) {
            detectTapGestures { o -> if (dur > 0) seek((o.x / size.width * dur).toLong().coerceIn(0, dur)) }
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { o -> dragX = o.x; if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                onDragEnd = {
                    dragX?.let { x -> if (dur > 0) seek((x / size.width * dur).toLong().coerceIn(0, dur)) }
                    dragX = null
                    if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onDragCancel = { dragX = null },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    val x = ((dragX ?: change.position.x) + amount).coerceIn(0f, size.width.toFloat())
                    dragX = x
                    if (seekWhileDragging && dur > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastLiveSeek > 100) { lastLiveSeek = now; seek((x / size.width * dur).toLong()) }
                    }
                },
            )
        }

    val pos = position()
    Box(
        modifier.fillMaxWidth().height(height)
            .then(gestures)
            .semantics {
                contentDescription = "Seek bar"
                progressBarRangeInfo = ProgressBarRangeInfo(pos.toFloat(), 0f..max(1f, durationMs.toFloat()))
                stateDescription = "${spoken(pos)} of ${spoken(durationMs)}"
                setProgress { v -> seek(v.toLong()); true }
            }
            .drawWithCache {
                val w = size.width.toInt().coerceAtLeast(1)
                val h = size.height.toInt().coerceAtLeast(1)
                val barW = with(density) { (if (compact) 3.dp else detail.bar.dp).toPx() }
                val gap = with(density) { (if (compact) 2.dp else detail.gap.dp).toPx() }
                val bitmap: ImageBitmap? = data?.let { d ->
                    ImageBitmap(w, h).also { img ->
                        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
                            val count = ((w + gap) / (barW + gap)).toInt().coerceAtLeast(1)
                            val mirrored = shape == WaveShape.MIRRORED && !compact
                            val base = if (mirrored) h * 0.62f else h.toFloat()
                            val up = base - 2f
                            val down = h - base - 2f
                            val cols = WaveformData.COLUMNS
                            for (i in 0 until count) {
                                val c0 = i * cols / count
                                val c1 = max(c0 + 1, (i + 1) * cols / count)
                                if (c1 > d.filled) break
                                var rms = 0f; var peak = -60f; var lo = 0f; var mi = 0f; var hi = 0f
                                for (k in c0 until c1) {
                                    rms += d.rmsDb(k); peak = max(peak, d.peakDb(k)); lo += d.lo(k); mi += d.mid(k); hi += d.hi(k)
                                }
                                val n = (c1 - c0).toFloat()
                                val body = WaveformRepo.height(rms / n) * up
                                val tip = WaveformRepo.height(peak) * up
                                val color = if (colorMode == WaveColor.ACCENT) accent else mix(low, mid, high, lo / n, mi / n, hi / n)
                                val x = i * (barW + gap)
                                val r = CornerRadius(barW / 2, barW / 2)
                                drawRoundRect(color.copy(alpha = 0.35f), Offset(x, base - tip), Size(barW, tip), r)
                                drawRoundRect(color, Offset(x, base - body), Size(barW, body), r)
                                if (mirrored) {
                                    val refl = body * 0.6f * (down / (up * 0.6f)).coerceAtMost(1f)
                                    drawRoundRect(color.copy(alpha = 0.45f), Offset(x, base + 1.5f), Size(barW, refl), r)
                                }
                            }
                        }
                    }
                }
                onDrawBehind {
                    @Suppress("UNUSED_EXPRESSION") frame   // redraw every frame while playing
                    val p = position()
                    val x = dragX ?: if (durationMs > 0) (p.toFloat() / durationMs * size.width) else 0f
                    if (bitmap != null) {
                        clipRect(right = x) { drawImage(bitmap, alpha = if (compact) 0.9f else 1f) }
                        clipRect(left = x) { drawImage(bitmap, alpha = if (compact) 0.35f else 0.38f) }
                    } else {
                        val y = size.height * (if (compact) 0.98f else 0.62f)
                        drawLine(text.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                        drawLine(text.copy(alpha = 0.7f), Offset(0f, y), Offset(x, y), 2.dp.toPx())
                    }
                    if (!compact) {
                        drawRect(text, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                        dragX?.let { dx ->
                            drawCircle(text, 6.dp.toPx(), Offset(dx, size.height * 0.62f))
                            val target = (dx / size.width * durationMs).toLong()
                            val delta = target - p
                            val label = formatDuration(target) + "  ·  " + (if (delta >= 0) "+" else "−") + formatDuration(abs(delta))
                            val layout = measurer.measure(label, TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = text, fontFeatureSettings = "tnum"))
                            val lx = (dx - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                            drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(lx - 8.dp.toPx(), -layout.size.height - 10.dp.toPx()),
                                Size(layout.size.width + 16.dp.toPx(), layout.size.height + 8.dp.toPx()), CornerRadius(8.dp.toPx()))
                            drawText(layout, topLeft = Offset(lx, -layout.size.height - 6.dp.toPx()))
                        }
                    }
                }
            }
    )
}

private fun spoken(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val r = s % 60
    return if (m > 0) "$m minute${if (m == 1L) "" else "s"} $r" else "$r seconds"
}

/** Weights sharpened by [WaveformTuning.SHARE_CONTRAST], mixed in linear RGB, so the dominant band sets the hue. */
private fun mix(a: Color, b: Color, c: Color, wa: Float, wb: Float, wc: Float): Color {
    val pa = wa.pow(WaveformTuning.SHARE_CONTRAST); val pb = wb.pow(WaveformTuning.SHARE_CONTRAST); val pc = wc.pow(WaveformTuning.SHARE_CONTRAST)
    val s = (pa + pb + pc).takeIf { it > 0f } ?: 1f
    fun lin(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    fun gam(v: Float) = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1 / 2.4f) - 0.055f
    fun ch(x: Float, y: Float, z: Float) = gam(((lin(x) * pa + lin(y) * pb + lin(z) * pc) / s).coerceIn(0f, 1f))
    return Color(ch(a.red, b.red, c.red), ch(a.green, b.green, c.green), ch(a.blue, b.blue, c.blue))
}
