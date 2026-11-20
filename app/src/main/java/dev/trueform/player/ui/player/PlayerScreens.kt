package dev.trueform.player.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.trueform.player.ui.theme.LocalHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.palette.graphics.Palette
import dev.trueform.player.dsp.EqMode
import dev.trueform.player.playback.Repeat
import dev.trueform.player.settings.PlayerBackground
import dev.trueform.player.settings.S
import dev.trueform.player.ui.components.ArtImage
import dev.trueform.player.ui.components.ArtKey
import dev.trueform.player.ui.components.IconBtn
import dev.trueform.player.ui.components.InfoPill
import dev.trueform.player.ui.components.LocalGraph
import dev.trueform.player.ui.components.T
import dev.trueform.player.ui.components.TfIcons
import dev.trueform.player.ui.components.formatDuration
import dev.trueform.player.ui.theme.Tf
import dev.trueform.player.ui.theme.tfClickable
import kotlin.math.abs

/** Colours taken from the artwork (docs/plan/04-ui-ux.md "Artwork colour on the player"). */
data class ArtColors(val accent: Color, val backdrop: Color)

@Composable
fun rememberArtColors(trackId: Long?): ArtColors? {
    val g = LocalGraph.current
    val c = Tf.colors
    val enabled by g.settings.flow(S.artworkColors).collectAsState(g.settings[S.artworkColors])
    var colors by remember(trackId, enabled) { mutableStateOf<ArtColors?>(null) }
    LaunchedEffect(trackId, enabled, c.isDark) {
        if (!enabled || trackId == null) { colors = null; return@LaunchedEffect }
        val bmp = g.artwork.track(trackId, 256) ?: run { colors = null; return@LaunchedEffect }
        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Palette.from(bmp).generate() }
        val vib = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch
        val dark = p.darkMutedSwatch ?: p.darkVibrantSwatch
        var accent = vib?.let { Color(it.rgb) } ?: c.accent
        val back = dark?.let { Color(it.rgb) } ?: c.bg
        val backdrop = mixColor(back, c.bg, 0.55f)
        // raise the accent's lightness until it reads on the backdrop (4.5:1)
        var tries = 0
        while (dev.trueform.player.ui.theme.contrast(accent, backdrop) < 4.5 && tries++ < 12) accent = mixColor(accent, if (c.isDark) Color.White else Color.Black, 0.12f)
        colors = ArtColors(accent, backdrop)
    }
    return colors
}

fun mixColor(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)

/**
 * A sideways swipe between tracks: the current track's content follows the finger (with resistance where there is no
 * track to go to); past about a quarter of the width it changes track and the content keeps going the way it was
 * swiped while the new one slides in from the other side; otherwise it springs back.
 */
private class TrackSwipe(val scope: CoroutineScope) {
    val drag = Animatable(0f)
    /** Where the outgoing track's content starts its slide from (the drag at release), for the track swiped away. */
    var exit by mutableFloatStateOf(0f)
    var exitId by mutableStateOf<Long?>(null)
    /** The track the drag belongs to. */
    var owner by mutableStateOf<Long?>(null)

    fun offset(id: Long?, current: Long?): Float = when {
        id != current -> if (id == exitId) exit else 0f
        id == owner -> drag.value
        else -> 0f
    }
}

@Composable
private fun rememberTrackSwipe(trackId: Long?): TrackSwipe {
    val scope = rememberCoroutineScope()
    val s = remember { TrackSwipe(scope) }
    LaunchedEffect(trackId) {
        if (s.owner != trackId) s.drag.snapTo(0f)
        kotlinx.coroutines.delay(400)   // the slide is over: a later change by button starts from the middle
        s.exitId = null
    }
    return s
}

private fun Modifier.trackSwipe(s: TrackSwipe, ui: PlayerUi, trackId: Long?, onCommit: () -> Unit): Modifier =
    pointerInput(trackId) {
        detectHorizontalDragGestures(
            onDragStart = { s.owner = trackId },
            onDragEnd = {
                val d = s.drag.value
                val possible = if (d < 0) ui.canGoNext() else ui.canGoBack()
                if (possible && abs(d) > size.width * 0.22f) {
                    s.exit = d
                    s.exitId = trackId
                    onCommit()
                    if (d < 0) ui.next() else ui.previousTrack()
                    s.scope.launch {   // the track did not change after all: come back
                        kotlinx.coroutines.delay(800)
                        if (ui.track?.id == trackId) s.drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f))
                    }
                } else s.scope.launch { s.drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
            },
            onDragCancel = { s.scope.launch { s.drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) } },
            onHorizontalDrag = { change, amount ->
                change.consume()
                val free = if (s.drag.value + amount < 0) ui.canGoNext() else ui.canGoBack()
                s.scope.launch { s.drag.snapTo(s.drag.value + if (free) amount else amount * 0.3f) }
            },
        )
    }

/**
 * Track changes slide: [dir] 1 = to the next track (the new one comes in from the right), -1 = back. Without [fade]
 * the content fades itself (see [fadeCheaply]).
 */
private fun <T> AnimatedContentTransitionScope<T>.slideTransition(dir: Int, fraction: Float, clip: Boolean = false, fade: Boolean = true): ContentTransform {
    val slideIn = slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { w -> (w * fraction * dir).toInt() }
    val slideOut = slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { w -> (-w * fraction * dir).toInt() }
    return (if (fade) slideIn + fadeIn(tween(240)) else slideIn) togetherWith (if (fade) slideOut + fadeOut(tween(220)) else slideOut) using
        SizeTransform(clip = clip)
}

/**
 * The fade of a track change applied to each drawing (alpha on every draw, no offscreen copy): the transition's own
 * fade draws the large artwork and its shadow into an offscreen buffer every frame, which slow GPUs cannot keep up with.
 */
@Composable
private fun AnimatedVisibilityScope.fadeCheaply(): State<Float> =
    transition.animateFloat({ tween(240) }, label = "fade") { if (it == EnterExitState.Visible) 1f else 0f }

/** The 64 dp mini player: art, title, previous, play/pause, next; the track's waveform is its background and progress. */
@UnstableApi
@Composable
fun MiniPlayer(ui: PlayerUi, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGraph.current
    val c = Tf.colors
    val t = ui.track ?: return
    val wave by remember(t.id) { g.waveforms.observe(t.id) }.collectAsState()
    val swipeOn by g.settings.flow(S.swipeMini).collectAsState(g.settings[S.swipeMini])
    val swipe = rememberTrackSwipe(t.id)
    val dir = remember(t.id) { ui.direction() }
    val haptic = LocalHapticFeedback.current
    val hapticsOn = LocalHaptics.current
    Box(
        modifier.fillMaxWidth().height(64.dp).background(c.surface1)
            .then(if (swipeOn) Modifier.trackSwipe(swipe, ui, t.id) {
                if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } else Modifier)
            .tfClickable(label = "Open the player", onClick = onExpand),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        WaveSeekbar(wave, ui.durationMs, ui::position, ui.isPlaying, {}, Modifier.fillMaxSize().alpha(0.38f), height = 64.dp, compact = true)
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(t, Modifier.weight(1f), contentKey = { it.id }, transitionSpec = { slideTransition(dir, 0.6f, clip = true) },
                label = "mini") { track ->
                Row(Modifier.fillMaxWidth().graphicsLayer { translationX = swipe.offset(track.id, t.id) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ArtImage(ArtKey.Track(track.id), track.album.ifBlank { track.displayTitle }, Modifier.size(44.dp))
                    Column(Modifier.weight(1f)) {
                        T(track.displayTitle, Tf.type.rowTitle.copy(fontSize = 15.sp, lineHeight = 20.sp))
                        T(track.displayArtist, Tf.type.caption, c.textSecondary)
                    }
                }
            }
            IconBtn(TfIcons.Prev, "Previous") { ui.previous() }
            IconBtn(if (ui.playWhenReady) TfIcons.Pause else TfIcons.Play, if (ui.playWhenReady) "Pause" else "Play", haptic = true) { ui.playPause() }
            IconBtn(TfIcons.Next, "Next") { ui.next() }
        }
    }
}

/** Now Playing (docs/plan/04-ui-ux.md "Now Playing"). */
@UnstableApi
@Composable
fun NowPlaying(
    ui: PlayerUi,
    onCollapse: () -> Unit,
    onQueue: () -> Unit,
    onEq: () -> Unit,
    onTimer: () -> Unit,
    onMore: () -> Unit,
    onOutput: () -> Unit,
    onInfo: () -> Unit,
    onGoToSource: () -> Unit,
    castButton: @Composable () -> Unit,
) {
    val g = LocalGraph.current
    val c = Tf.colors
    val t = ui.track
    val q by g.queue.state.collectAsState()
    val eq by g.dsp.state.collectAsState()
    val art = rememberArtColors(t?.id)
    // The accent changes with the track (no animation: animating it recomposed the whole player every frame while
    // the artwork slid); the backdrop fades, read only when drawing.
    val accent = art?.accent ?: c.accent
    val backdrop = animateColorAsState(art?.backdrop ?: c.bg, tween(500), label = "backdrop")
    val bgMode by g.settings.flow(S.playerBackground).collectAsState(g.settings[S.playerBackground])
    val wave by remember(t?.id) { t?.let { g.waveforms.observe(it.id) } ?: kotlinx.coroutines.flow.MutableStateFlow(null) }.collectAsState()
    val detail by g.settings.flow(S.waveDetail).collectAsState(g.settings[S.waveDetail])
    val shape by g.settings.flow(S.waveShape).collectAsState(g.settings[S.waveShape])
    val colorMode by g.settings.flow(S.waveColor).collectAsState(g.settings[S.waveColor])
    val live by g.settings.flow(S.seekWhileDragging).collectAsState(g.settings[S.seekWhileDragging])
    val centred by g.settings.flow(S.waveCentred).collectAsState(g.settings[S.waveCentred])
    val output by g.outputs.label.collectAsState()
    val limitDb = remember { mutableStateOf(0f) }
    var showRemaining by remember { mutableStateOf(true) }
    val sleep by g.sleepTimer.remainingMin.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(250)
            val l = g.localDsp.limiter
            limitDb.value = l?.reductionDb ?: 0f
            l?.clearPeak()
        }
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val bg = c.bg
    Box(Modifier.fillMaxSize().drawBehind {
        if (bgMode == PlayerBackground.PLAIN) drawRect(bg)
        else backdrop.value.let { b -> drawRect(Brush.verticalGradient(0f to b, 0.36f to mixColor(b, bg, 0.5f), 0.62f to bg)) }
    }) {
        if (bgMode == PlayerBackground.BLUR && t != null && android.os.Build.VERSION.SDK_INT >= 31) {
            ArtImage(ArtKey.Track(t.id), t.album, Modifier.fillMaxSize().blurCompat(), large = false, radius = 0.dp)
            Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.72f)))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            // top bar
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(TfIcons.ExpandMore, "Collapse the player", onClick = onCollapse)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).tfClickable(label = "Go to what is playing", onClick = onGoToSource),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    T("PLAYING FROM", Tf.type.caption.copy(fontSize = 10.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold), c.textSecondary)
                    T(sourceLabel(q.source, t), Tf.type.label.copy(fontWeight = FontWeight.SemiBold))
                }
                castButton()
                IconBtn(TfIcons.More, "More", onClick = onMore)
            }
            val swipe = rememberTrackSwipe(t?.id)
            val dir = remember(t?.id) { ui.direction() }
            val haptic = LocalHapticFeedback.current
            val hapticsOn = LocalHaptics.current
            val artwork: @Composable (Modifier) -> Unit = { m ->
                // swipe the artwork: it follows the finger, then slides the way it was swiped (or springs back)
                Box(m.trackSwipe(swipe, ui, t?.id) { if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }) {
                    AnimatedContent(t, contentKey = { it?.id }, transitionSpec = { slideTransition(dir, 1.15f, fade = false) }, label = "art") { track ->
                        val fade = fadeCheaply()
                        Box(Modifier.fillMaxSize()
                            .graphicsLayer {
                                val o = swipe.offset(track?.id, t?.id)
                                translationX = o
                                rotationZ = o / size.width * 5f
                                alpha = fade.value
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                            .shadow(20.dp, RoundedCornerShape(22.dp), ambientColor = accent.copy(alpha = 0.22f), spotColor = accent.copy(alpha = 0.35f))) {
                            if (track != null) ArtImage(ArtKey.Track(track.id), track.album.ifBlank { track.displayTitle }, Modifier.fillMaxSize(), large = true, radius = 22.dp)
                            else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(c.surface2))
                        }
                    }
                }
            }
            val controls: @Composable ColumnScope.() -> Unit = {
                // title: slides with the artwork, a third as far
                AnimatedContent(t, contentKey = { it?.id }, transitionSpec = { slideTransition(dir, 0.35f) }, label = "title") { track ->
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp).graphicsLayer { translationX = swipe.offset(track?.id, t?.id) * 0.35f }) {
                        T(track?.displayTitle ?: "Nothing playing", Tf.type.display)
                        T(listOf(track?.displayArtist, track?.album).filter { !it.isNullOrBlank() }.joinToString(" · "), Tf.type.body, c.textSecondary)
                    }
                }
                // pills
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t != null) InfoPill(t.formatLabel, onClick = onInfo)
                    output?.let { InfoPill("→ $it", onClick = onOutput) }
                    InfoPill(if (eq.on) "EQ · ${eq.preset ?: if (eq.mode == EqMode.TONE) "Tone" else "Custom"}" else "EQ off", on = eq.on, onClick = onEq)
                    if (limitDb.value < -0.05f) Box(Modifier.size(8.dp).clip(CircleShape).background(c.limit).semantics { contentDescription = "Limiter active" })
                }
                // waveform
                Box(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                    WaveSeekbar(wave, ui.durationMs, ui::position, ui.isPlaying, ui::seek, height = 80.dp, detail = detail, shape = shape,
                        colorMode = colorMode, accent = accent, seekWhileDragging = live, centred = centred)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    PositionText(ui, Modifier.weight(1f), remaining = false)
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).tfClickable(label = "Remaining or total time") { showRemaining = !showRemaining }) {
                        PositionText(ui, Modifier, remaining = showRemaining, total = !showRemaining)
                    }
                }
                // transport
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).height(72.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    ToggleIcon(if (q.shuffle) TfIcons.Shuffle else TfIcons.Shuffle, "Shuffle", q.shuffle, accent) { g.queue.setShuffle(!q.shuffle) }
                    IconBtn(TfIcons.Prev, "Previous", size = 56.dp, iconSize = 30.dp) { ui.previous() }
                    Box(Modifier.size(72.dp).shadow(14.dp, CircleShape, spotColor = accent).clip(CircleShape).background(accent)
                        .tfClickable(scale = true, haptic = true, label = if (ui.playWhenReady) "Pause" else "Play") { ui.playPause() },
                        contentAlignment = Alignment.Center) {
                        Icon(if (ui.playWhenReady) TfIcons.Pause else TfIcons.Play, null, Modifier.size(34.dp), tint = onColor(accent))
                    }
                    IconBtn(TfIcons.Next, "Next", size = 56.dp, iconSize = 30.dp) { ui.next() }
                    ToggleIcon(if (q.repeat == Repeat.ONE) TfIcons.RepeatOne else TfIcons.Repeat,
                        when (q.repeat) { Repeat.OFF -> "Repeat off"; Repeat.ALL -> "Repeat all"; Repeat.ONE -> "Repeat one" },
                        q.repeat != Repeat.OFF, accent) { g.queue.cycleRepeat() }
                }
                Spacer(Modifier.weight(0.3f))
                // actions
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    ActionItem(TfIcons.Eq, if (eq.on) (eq.preset ?: "EQ") else "EQ", eq.on, accent, onEq)
                    ActionItem(TfIcons.Queue, "Queue", false, accent, onQueue)
                    ActionItem(TfIcons.Timer, sleep?.let { "$it min" } ?: "Timer", sleep != null, accent, onTimer)
                    ActionItem(TfIcons.More, "More", false, accent, onMore)
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                if (maxWidth > maxHeight * 1.2f && maxHeight < 520.dp) {
                    // a phone on its side: artwork on the left, everything else on the right
                    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        artwork(Modifier.fillMaxHeight().aspectRatio(1f))
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) { controls() }
                    }
                } else {
                    // as wide as the column allows, but never taller than what the controls (about 420 dp) leave
                    val side = minOf((minOf(maxWidth, 560.dp) - 48.dp) * (if (wide) 0.7f else 1f), maxHeight - 420.dp)
                        .coerceAtLeast(120.dp)
                    Column(Modifier.fillMaxHeight().widthIn(max = 560.dp).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(0.4f))
                        artwork(Modifier.size(side))
                        Spacer(Modifier.weight(0.3f))
                        controls()
                    }
                }
            }
        }
    }
}

private fun Modifier.blurCompat(): Modifier =
    this.graphicsLayer { renderEffect = BlurEffect(60f, 60f, TileMode.Clamp) }

fun onColor(bg: Color): Color = if ((bg.red * 0.299f + bg.green * 0.587f + bg.blue * 0.114f) > 0.55f) Color(0xFF1C1200) else Color.White

@Composable
private fun PositionText(ui: PlayerUi, modifier: Modifier, remaining: Boolean, total: Boolean = false) {
    val frame by androidx.compose.runtime.produceState(0L, ui.isPlaying) {
        if (ui.isPlaying) while (true) { kotlinx.coroutines.delay(250); value++ }
    }
    @Suppress("UNUSED_EXPRESSION") frame
    val pos = ui.position()
    val txt = when {
        total -> formatDuration(ui.durationMs)
        remaining -> "−" + formatDuration((ui.durationMs - pos).coerceAtLeast(0))
        else -> formatDuration(pos)
    }
    T(txt, Tf.type.numeric, Tf.colors.textSecondary, modifier)
}

@Composable
private fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.BottomCenter) {
        IconBtn(icon, "$label, ${if (on) "on" else "off"}", tint = if (on) accent else Tf.colors.text, haptic = true, onClick = onClick)
        if (on) Box(Modifier.padding(bottom = 4.dp).size(4.dp).clip(CircleShape).background(accent))
    }
}

@Composable
private fun ActionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    val c = Tf.colors
    Column(Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).tfClickable(label = label, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (on) accent else c.textSecondary)
        T(label, Tf.type.caption.copy(fontSize = 11.sp), if (on) accent else c.textSecondary)
    }
}

fun sourceLabel(source: dev.trueform.player.playback.QueueSource?, t: dev.trueform.player.library.Track?): String = when (source) {
    is dev.trueform.player.playback.QueueSource.Folder -> t?.dir?.substringAfterLast('/') ?: "Folder"
    is dev.trueform.player.playback.QueueSource.Album -> t?.album ?: "Album"
    is dev.trueform.player.playback.QueueSource.Artist -> source.name
    is dev.trueform.player.playback.QueueSource.Playlist -> "Playlist"
    dev.trueform.player.playback.QueueSource.Songs -> "Songs"
    else -> "Queue"
}
