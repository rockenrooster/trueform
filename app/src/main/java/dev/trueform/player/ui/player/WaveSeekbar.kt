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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameMillis
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.ui.unit.Density
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
import kotlin.math.abs
import kotlin.math.max

/**
 * The Trueform seekbar (docs/plan/05-waveform-seekbar.md T2, T3). The track as rounded bars (or one smooth mirrored
 * outline): its height is loudness, the played part is coloured by the music's tone (warm where bass leads, gold in
 * the mids, cyan where treble leads), the rest is a quiet neutral.
 *
 * [centred] (the full player's default): the playhead stays in the middle and about 30 seconds of the track scroll
 * under it; a drag scrubs with a little momentum. Otherwise the whole track, drawn once into bitmaps per size and
 * style, with a moving playhead (and the mini player's strip, [compact]). Tap seeks.
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
    shape: WaveShape = WaveShape.BARS,
    colorMode: WaveColor = WaveColor.SPECTRAL,
    accent: Color = Tf.colors.accent,
    seekWhileDragging: Boolean = false,
    compact: Boolean = false,
    centred: Boolean = false,
    /** Drawn at this opacity (the mini player's background strip): applied to each drawing, no offscreen layer. */
    faint: Float = 1f,
) {
    if (centred && !compact) {
        CentredWave(data, durationMs, position, playing, onSeek, modifier, height, detail, shape, colorMode, accent, seekWhileDragging)
        return
    }
    val c = Tf.colors
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val hapticsOn = LocalHaptics.current
    val measurer = rememberTextMeasurer()
    val widthPx = remember { IntArray(1) }
    // Redraw when the playhead has moved about half a pixel, not every frame: a 3-minute track across a phone needs
    // about ten redraws a second, and the screen can stay idle in between.
    val tick by produceState(0L, playing, durationMs) {
        // after each wait, also wait for a frame: frames come only while the app is visible, so with the screen off
        // this parks instead of waking the main thread several times a second
        if (playing) while (true) { delay((durationMs / widthPx[0].coerceAtLeast(1) / 2).coerceIn(16L, 500L)); withFrameMillis { value++ } }
    }
    var dragX by remember { mutableStateOf<Float?>(null) }
    var lastLiveSeek by remember { mutableStateOf(0L) }
    val dur by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val now by rememberUpdatedState(position)
    val accentNow by rememberUpdatedState(accent)
    // The bitmaps depend on these only (on the accent only when it colours the waveform), so a recomposition, or the
    // accent changing with the artwork, does not draw them again.
    val accentOnly = colorMode == WaveColor.ACCENT
    val restColor = c.text.copy(alpha = if (c.isDark) 0.20f else 0.17f)
    val palette = remember(c.waveLow, c.waveMid, c.waveHigh, if (accentOnly) accent else Color.Unspecified, restColor, accentOnly) {
        Palette(c.waveLow, c.waveMid, c.waveHigh, accent, restColor, accentOnly)
    }
    val text = c.text
    // The two bitmaps (played and unplayed) are drawn on a background thread: a track change does not hold up a frame.
    var box by remember { mutableStateOf(IntSize.Zero) }
    val bars = shape == WaveShape.BARS && !compact
    val images by produceState<Pair<ImageBitmap, ImageBitmap>?>(null, data, box, bars, compact, detail, palette) {
        val d = data
        val (w, h) = box
        if (d == null || w <= 0 || h <= 0) { value = null; return@produceState }
        value = withContext(Dispatchers.Default) {
            render(d, w, h, density, played = true, bars, compact, detail, palette) to render(d, w, h, density, played = false, bars, compact, detail, palette)
        }
    }

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
            .onSizeChanged { box = it; widthPx[0] = it.width }
            .graphicsLayer()   // its own layer: the playhead's redraws do not redraw the screen around it
            .drawWithCache {
                val cy = if (compact) size.height else size.height / 2
                val half = if (compact) size.height else size.height / 2 - 1.dp.toPx()
                onDrawBehind {
                    @Suppress("UNUSED_EXPRESSION") tick   // redraw as the playhead moves
                    val p = now()
                    val d = dur
                    val accent = accentNow
                    val x = dragX ?: if (d > 0) (p.toFloat() / d * size.width) else 0f
                    val img = images
                    if (img != null) {
                        clipRect(right = x) { drawImage(img.first, alpha = faint) }
                        clipRect(left = x) { drawImage(img.second, alpha = faint) }
                    } else {   // not analysed yet: a quiet line with the progress on it
                        val y = if (compact) size.height - 1.dp.toPx() else cy
                        drawLine(palette.rest, Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round, alpha = faint)
                        drawLine(accent, Offset(0f, y), Offset(x, y), 2.dp.toPx(), StrokeCap.Round, alpha = faint)
                    }
                    if (!compact) {
                        val px = x.coerceIn(1.5.dp.toPx(), size.width - 1.5.dp.toPx())
                        val top = cy - half - 2.dp.toPx()
                        val bottom = cy + half + 2.dp.toPx()
                        drawLine(accent.copy(alpha = 0.28f), Offset(px, top), Offset(px, bottom), 7.dp.toPx(), StrokeCap.Round)
                        drawLine(text, Offset(px, top), Offset(px, bottom), 2.dp.toPx(), StrokeCap.Round)
                        dragX?.let { dx -> scrubHandle(measurer, dx, cy, (dx / size.width * d).toLong(), p, accent, text) }
                    }
                }
            }
    )
}

/** About this much of the track shows across the centred seek bar. */
private const val WINDOW_MS = 30_000f

@Composable
private fun CentredWave(
    data: WaveformData?, durationMs: Long, position: () -> Long, playing: Boolean, onSeek: (Long) -> Unit,
    modifier: Modifier, height: Dp, detail: WaveDetail, shape: WaveShape, colorMode: WaveColor, accent: Color,
    seekWhileDragging: Boolean,
) {
    val c = Tf.colors
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val hapticsOn = LocalHaptics.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val msPerPx = remember { floatArrayOf(30f) }
    // The waveform moves about a pixel every 30 ms on a phone: redraw at that pace while playing, not every frame.
    val tick by produceState(0L, playing) {   // parks while the app is not visible (see the whole-track bar)
        if (playing) while (true) { delay(msPerPx[0].toLong().coerceIn(16L, 250L)); withFrameMillis { value++ } }
    }
    /** The track time under the playhead while dragging or gliding; null otherwise. */
    var scrub by remember { mutableStateOf<Float?>(null) }
    val glide = remember { arrayOfNulls<Job>(1) }
    val lastLiveSeek = remember { longArrayOf(0L) }
    val dur by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val now by rememberUpdatedState(position)
    val accentNow by rememberUpdatedState(accent)
    val live by rememberUpdatedState(seekWhileDragging)
    val accentOnly = colorMode == WaveColor.ACCENT
    val restColor = c.text.copy(alpha = if (c.isDark) 0.20f else 0.17f)
    val palette = remember(c.waveLow, c.waveMid, c.waveHigh, if (accentOnly) accent else Color.Unspecified, restColor, accentOnly) {
        Palette(c.waveLow, c.waveMid, c.waveHigh, accent, restColor, accentOnly)
    }
    val text = c.text
    val path = remember { Path() }
    // Bars and their colours for the whole track, worked out on a background thread (not in the frame that shows them).
    var widthPx by remember { mutableIntStateOf(0) }
    val barStep = (detail.bar * 1.5f + detail.gap * 1.6f) * density.density
    val zoom by produceState<Pair<FloatArray, Array<Color>>?>(null, data, durationMs, widthPx, barStep, palette) {
        val d = data
        if (d == null || durationMs <= 0 || widthPx <= 0) { value = null; return@produceState }
        value = withContext(Dispatchers.Default) {
            val (amp, tone) = zoomedBars(d, durationMs, barStep * WINDOW_MS / widthPx)
            amp to Array(tone.size) { palette.tone(tone[it]) }
        }
    }

    fun shown(): Float = scrub ?: now().toFloat()
    /** Scrubbing stops a second before the end: a flick to the end would otherwise skip to the next track. */
    fun last(): Long = if (dur > 2000) dur - 1000 else dur
    fun liveSeek(ms: Float) {
        val t = SystemClock.uptimeMillis()
        if (live && t - lastLiveSeek[0] > 100) { lastLiveSeek[0] = t; seek(ms.toLong()) }
    }
    fun tickHaptic() { if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    val gestures = Modifier
        .pointerInput(Unit) {
            detectTapGestures { o ->   // the tapped point comes to the playhead
                if (dur <= 0) return@detectTapGestures
                val from = shown()
                glide[0]?.cancel()
                scrub = null
                seek((from + (o.x - size.width / 2f) * msPerPx[0]).toLong().coerceIn(0, last()))
            }
        }
        .pointerInput(Unit) {
            val velocity = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = {
                    glide[0]?.cancel()
                    scrub = shown()
                    velocity.resetTracking()
                    tickHaptic()
                },
                onDragEnd = {
                    // a little momentum: a flick glides on, slowing down, then seeks
                    val v = -velocity.calculateVelocity().x * msPerPx[0]   // track ms per second
                    glide[0] = scope.launch {
                        val anim = Animatable(shown())
                        anim.updateBounds(0f, last().toFloat())
                        if (abs(v) > 400f * msPerPx[0]) anim.animateDecay(v, exponentialDecay(frictionMultiplier = 5f)) { scrub = value; liveSeek(value) }
                        seek(anim.value.toLong())
                        scrub = null
                        tickHaptic()
                    }
                },
                onDragCancel = { scrub = null },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    val s = (shown() - amount * msPerPx[0]).coerceIn(0f, last().toFloat())
                    scrub = s
                    liveSeek(s)
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
            .onSizeChanged { widthPx = it.width; msPerPx[0] = WINDOW_MS / it.width.coerceAtLeast(1) }
            .graphicsLayer()   // its own layer: its redraws do not redraw the screen around it
            .drawWithCache {
                val w = size.width
                val dp = density.density
                val barW = detail.bar * 1.5f * dp
                val step = barStep
                val d = dur
                val cx = w / 2
                val cy = size.height / 2
                val half = size.height / 2 - dp
                val r = CornerRadius(barW / 2, barW / 2)
                onDrawBehind {
                    @Suppress("UNUSED_EXPRESSION") tick   // redraw as the track moves
                    val s = scrub
                    val p = s ?: now().toFloat()
                    val accent = accentNow
                    val shift = p / msPerPx[0]   // the track's pixels left of the playhead
                    val bars = zoom
                    val colors = bars?.second
                    if (bars != null && colors != null && bars.first.isNotEmpty()) {
                        val amp = bars.first
                        val first = ((shift - cx) / step).toInt().coerceIn(0, amp.size - 1)
                        val last = ((shift + cx) / step).toInt().plus(1).coerceIn(first, amp.size - 1)
                        if (shape == WaveShape.MIRRORED) {
                            val k = last - first + 1
                            val xs = FloatArray(k) { cx + (first + it) * step + barW / 2 - shift }
                            val ys = FloatArray(k) { amp[first + it] }
                            path.rewind()
                            outline(path, xs, ys, k, base = cy, half = half, mirrored = true)
                            val playedStops = Array(k) { (xs[it] / w).coerceIn(0f, 1f) to colors[first + it].copy(alpha = edge(xs[it] - cx, cx)) }
                            val restStops = Array(k) { (xs[it] / w).coerceIn(0f, 1f) to palette.rest.copy(alpha = palette.rest.alpha * edge(xs[it] - cx, cx)) }
                            if (k > 1) {
                                clipRect(right = cx) { drawPath(path, Brush.horizontalGradient(*playedStops, startX = 0f, endX = w)) }
                                clipRect(left = cx) { drawPath(path, Brush.horizontalGradient(*restStops, startX = 0f, endX = w)) }
                            }
                        } else {
                            clipRect(right = cx) {
                                for (i in first..last) {
                                    val x = cx + i * step - shift
                                    if (x >= cx) break
                                    val hh = max(amp[i] * half, barW / 2)
                                    drawRoundRect(colors[i], Offset(x, cy - hh), Size(barW, 2 * hh), r, alpha = edge(x + barW / 2 - cx, cx))
                                }
                            }
                            clipRect(left = cx) {
                                for (i in first..last) {
                                    val x = cx + i * step - shift
                                    if (x + barW <= cx) continue
                                    val hh = max(amp[i] * half, barW / 2)
                                    drawRoundRect(palette.rest, Offset(x, cy - hh), Size(barW, 2 * hh), r, alpha = edge(x + barW / 2 - cx, cx))
                                }
                            }
                        }
                    } else {   // not analysed yet: the track as a line, the played part in the accent
                        val start = (cx - shift).coerceAtLeast(0f)
                        val end = if (d > 0) (cx - shift + d / msPerPx[0]).coerceAtMost(w) else w
                        drawLine(palette.rest, Offset(start, cy), Offset(end, cy), 2.dp.toPx(), StrokeCap.Round)
                        if (start < cx) drawLine(accent, Offset(start, cy), Offset(cx, cy), 2.dp.toPx(), StrokeCap.Round)
                    }
                    // the playhead, fixed in the middle
                    val top = cy - half - 2.dp.toPx()
                    val bottom = cy + half + 2.dp.toPx()
                    drawLine(accent.copy(alpha = 0.28f), Offset(cx, top), Offset(cx, bottom), 7.dp.toPx(), StrokeCap.Round)
                    drawLine(text, Offset(cx, top), Offset(cx, bottom), 2.dp.toPx(), StrokeCap.Round)
                    if (s != null) scrubHandle(measurer, cx, cy, s.toLong(), now(), accent, text)
                }
            }
    )
}

/** Bars fade towards the ends of the centred seek bar: full over the middle half, 15% at the edges. */
private fun edge(dx: Float, cx: Float): Float {
    val t = ((abs(dx) / cx - 0.5f) / 0.5f).coerceIn(0f, 1f)
    return 1f - 0.85f * t * t * (3 - 2 * t)
}

/** The scrub handle on the playhead and the bubble above it: target time and the jump ("2:31 · +0:49"). */
private fun DrawScope.scrubHandle(measurer: TextMeasurer, x: Float, cy: Float, target: Long, now: Long, accent: Color, text: Color) {
    drawCircle(accent.copy(alpha = 0.35f), 12.dp.toPx(), Offset(x, cy))
    drawCircle(text, 6.dp.toPx(), Offset(x, cy))
    val delta = target - now
    val label = formatDuration(target) + "  ·  " + (if (delta >= 0) "+" else "−") + formatDuration(abs(delta))
    val layout = measurer.measure(label, TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = Color.White, fontFeatureSettings = "tnum"))
    val lx = (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
    drawRoundRect(Color.Black.copy(alpha = 0.72f), Offset(lx - 8.dp.toPx(), -layout.size.height - 10.dp.toPx()),
        Size(layout.size.width + 16.dp.toPx(), layout.size.height + 8.dp.toPx()), CornerRadius(8.dp.toPx()))
    drawText(layout, topLeft = Offset(lx, -layout.size.height - 6.dp.toPx()))
}

/**
 * Loudness and tone (0..1) for bars of [barMs] each from the start of the track, for the zoomed seek bar: the columns
 * under each bar pooled as in [points], lightly smoothed. Only bars whose columns are analysed (partial data).
 */
internal fun zoomedBars(d: WaveformData, durationMs: Long, barMs: Float): Pair<FloatArray, FloatArray> {
    val cols = d.columns
    val n = (durationMs / barMs).toInt().coerceAtLeast(1)
    val perMs = cols.toDouble() / durationMs
    val amp = FloatArray(n)
    val tone = FloatArray(n)
    var k = 0
    for (i in 0 until n) {
        val c0 = (i * barMs * perMs).toInt().coerceIn(0, cols - 1)
        val c1 = max(c0 + 1, ((i + 1) * barMs * perMs).toInt().coerceAtMost(cols))
        if (c1 > d.filled) break
        pool(d, c0, c1, amp, tone, i)
        k = i + 1
    }
    return blur(amp, k, 1) to blur(tone, k, 5)
}

/** Columns [c0, c1) into one bar: mostly RMS (what you hear), some peak (so drums and attacks show); the tone centroid. */
private fun pool(d: WaveformData, c0: Int, c1: Int, amp: FloatArray, tone: FloatArray, i: Int) {
    var rms = 0f; var peak = -96f; var lo = 0f; var mi = 0f; var hi = 0f
    for (j in c0 until c1) { rms += d.rmsDb(j); peak = max(peak, d.peakDb(j)); lo += d.lo(j); mi += d.mid(j); hi += d.hi(j) }
    val m = (c1 - c0).toFloat()
    amp[i] = 0.7f * WaveformRepo.height(rms / m) + 0.3f * WaveformRepo.height(peak)
    val s = lo + mi + hi
    val centroid = if (s > 0f) (0.5f * mi + hi) / s else 0.5f
    tone[i] = (0.5f + (centroid - 0.5f) * 1.5f).coerceIn(0f, 1f)   // music mostly leans low: widen the spread
}

/** The colours: the tone gradient (or the accent) for the played part, a neutral for the rest. */
private class Palette(val low: Color, val mid: Color, val high: Color, val accent: Color, val rest: Color, val accentOnly: Boolean) {
    /** 0 = bass leads, 0.5 = mids, 1 = treble; blended between neighbours only (Oklab), so never a muddy grey. */
    fun tone(t: Float): Color = when {
        accentOnly -> accent
        t < 0.5f -> lerp(low, mid, (t / 0.5f).coerceIn(0f, 1f))
        else -> lerp(mid, high, ((t - 0.5f) / 0.5f).coerceIn(0f, 1f))
    }
}

/** Loudness (0..1) and tone (0..1) at [n] points across the track, lightly smoothed; fewer when analysis is partial. */
private fun points(d: WaveformData, n: Int, smooth: Int): Pair<FloatArray, FloatArray> {
    val cols = d.columns
    val amp = FloatArray(n)
    val tone = FloatArray(n)
    var k = 0
    for (i in 0 until n) {
        val c0 = (i.toLong() * cols / n).toInt()
        val c1 = max(c0 + 1, ((i + 1).toLong() * cols / n).toInt())
        if (c1 > d.filled) break
        pool(d, c0, c1, amp, tone, i)
        k = i + 1
    }
    return blur(amp, k, smooth) to blur(tone, k, smooth + 4)
}

/** A [radius]-wide triangular moving average over the first [n] values (n is kept as the length). */
private fun blur(v: FloatArray, n: Int, radius: Int): FloatArray {
    if (radius <= 0 || n == 0) return v.copyOf(n)
    val out = FloatArray(n)
    for (i in 0 until n) {
        var sum = 0f; var wsum = 0f
        for (o in -radius..radius) {
            val j = i + o
            if (j < 0 || j >= n) continue
            val wgt = (radius + 1 - abs(o)).toFloat()
            sum += v[j] * wgt; wsum += wgt
        }
        out[i] = sum / wsum
    }
    return out
}

/** One bitmap of the whole track: the played look, or the unplayed one. */
private fun render(d: WaveformData, w: Int, h: Int, density: Density, played: Boolean, bars: Boolean, compact: Boolean,
                   detail: WaveDetail, palette: Palette): ImageBitmap {
    val img = ImageBitmap(w, h)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
        val dp = density.density
        if (bars || compact) {
            // rounded bars: centred and mirrored in the player; thin and standing on the bottom edge in the mini player
            val barW = (if (compact) 2f else detail.bar * 1.5f) * dp
            val gap = (if (compact) 2f else detail.gap * 1.6f) * dp
            val n = ((w + gap) / (barW + gap)).toInt().coerceIn(8, d.columns)
            val (amp, tone) = points(d, n, smooth = 1)
            for (i in amp.indices) {
                val x = i * (barW + gap)
                // the mini player draws the whole strip faint: its unplayed part needs a stronger grey to show at all
                val color = if (played) palette.tone(tone[i]) else if (compact) palette.rest.copy(alpha = palette.rest.alpha * 2f) else palette.rest
                val r = CornerRadius(barW / 2, barW / 2)
                if (compact) {
                    val hh = max(amp[i] * h * 0.75f, barW)
                    drawRoundRect(color, Offset(x, h - hh), Size(barW, hh), r)
                } else {
                    val hh = max(amp[i] * (h / 2f - dp), barW / 2)
                    drawRoundRect(color, Offset(x, h / 2f - hh), Size(barW, 2 * hh), r)
                }
            }
        } else {
            val step = (if (compact) 3f else 2f) * dp
            val n = (w / step).toInt().coerceIn(16, d.columns)
            val smooth = if (compact) 3 else when (detail) { WaveDetail.FINE -> 2; WaveDetail.MEDIUM -> 3; WaveDetail.BOLD -> 5 }
            val (amp, tone) = points(d, n, smooth)
            if (amp.isEmpty()) return@draw
            val xs = FloatArray(amp.size) { i -> if (amp.size == 1) 0f else i * (w - 1f) / (n - 1) }
            val path = Path()
            if (compact) outline(path, xs, amp, amp.size, base = h.toFloat(), half = h - dp, mirrored = false)
            else outline(path, xs, amp, amp.size, base = h / 2f, half = h / 2f - dp, mirrored = true)
            val brush = if (!played) Brush.horizontalGradient(listOf(palette.rest, palette.rest))
            else Brush.horizontalGradient(
                colorStops = Array(amp.size) { i -> (xs[i] / w).coerceIn(0f, 1f) to palette.tone(tone[i]) },
                startX = 0f, endX = w.toFloat())
            drawPath(path, brush)
        }
        if (!compact) fadeEdges(h.toFloat())
    }
    return img
}

/** A smooth outline through the tops (and, mirrored, the bottoms) of the first [n] points, closed around the base line. */
private fun outline(path: Path, xs: FloatArray, amp: FloatArray, n: Int, base: Float, half: Float, mirrored: Boolean) = path.apply {
    if (n == 0) return@apply
    fun top(i: Int) = base - amp[i] * half
    fun bottom(i: Int) = base + amp[i] * half
    moveTo(xs[0], base)
    lineTo(xs[0], top(0))
    for (i in 1 until n) quadraticTo(xs[i - 1], top(i - 1), (xs[i - 1] + xs[i]) / 2, (top(i - 1) + top(i)) / 2)
    lineTo(xs[n - 1], top(n - 1))
    if (mirrored) {
        lineTo(xs[n - 1], bottom(n - 1))
        for (i in n - 2 downTo 0) quadraticTo(xs[i + 1], bottom(i + 1), (xs[i + 1] + xs[i]) / 2, (bottom(i + 1) + bottom(i)) / 2)
        lineTo(xs[0], bottom(0))
    } else {
        lineTo(xs[n - 1], base)
    }
    close()
}

/** Depth: the tips fade a little towards the top and bottom, the middle stays full. */
private fun DrawScope.fadeEdges(h: Float) {
    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.5f to Color.Black, 1f to Color.Black.copy(alpha = 0.55f),
        startY = 0f, endY = h), blendMode = BlendMode.DstIn)
}

private fun spoken(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val r = s % 60
    return if (m > 0) "$m minute${if (m == 1L) "" else "s"} $r" else "$r seconds"
}

