package dev.trueform.player.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trueform.player.AppGraph
import dev.trueform.player.library.Artwork
import dev.trueform.player.ui.theme.Tf
import dev.trueform.player.ui.theme.tfClickable

val LocalGraph = compositionLocalOf<AppGraph> { error("no AppGraph") }

// --- text ------------------------------------------------------------------------------------------------------------

@Composable
fun T(text: String, style: TextStyle, color: Color = Tf.colors.text, modifier: Modifier = Modifier, maxLines: Int = 1,
      weight: FontWeight? = null) {
    Text(text, modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, fontWeight = weight)
}

// --- buttons ---------------------------------------------------------------------------------------------------------

/** A 48 dp round icon button; [tint] defaults to the text colour. */
@Composable
fun IconBtn(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Tf.colors.text,
            size: Dp = 48.dp, iconSize: Dp = 24.dp, enabled: Boolean = true, haptic: Boolean = false,
            background: Color = Color.Transparent, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(background)
            .tfClickable(enabled = enabled, scale = true, haptic = haptic, label = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

/** Accent-filled pill ("Play") or outlined pill ("Shuffle"). */
@Composable
fun PillButton(text: String, icon: ImageVector?, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Tf.colors
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(50))
            .then(if (primary) Modifier.background(c.accent) else Modifier.border(1.dp, c.outlineStrong, RoundedCornerShape(50)))
            .tfClickable(scale = true, haptic = true, onClick = onClick)
            .padding(start = if (icon != null) 10.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (primary) c.onAccent else c.text
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = fg)
        T(text, Tf.type.label.copy(fontWeight = FontWeight.SemiBold), fg)
    }
}

@Composable
fun TextBtn(text: String, color: Color = Tf.colors.accent, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).tfClickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) {
        T(text, Tf.type.label.copy(fontWeight = FontWeight.SemiBold), color)
    }
}

/** A selectable chip (presets, sort, band counts). */
@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Tf.colors
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(50)).background(if (selected) c.accentDim else c.surface2)
            .tfClickable(role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, Tf.type.caption.copy(fontWeight = FontWeight.SemiBold), if (selected) c.accent else c.textSecondary) }
}

/** Segmented control (Graphic | Tone, 5 | 10 | 15 | 31). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = Tf.colors
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.surface2).padding(3.dp)) {
        for (o in options) {
            val on = o == selected
            Box(Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(if (on) c.accentDim else Color.Transparent)
                .tfClickable(role = Role.Tab) { onSelect(o) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                T(label(o), Tf.type.label.copy(fontWeight = FontWeight.SemiBold), if (on) c.accent else c.textSecondary)
            }
        }
    }
}

/** A small outlined label ("24/96", "WMA"). */
@Composable
fun FormatPill(text: String) {
    val c = Tf.colors
    Box(Modifier.border(1.dp, c.outlineStrong, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        T(text, Tf.type.caption.copy(fontSize = 10.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.SemiBold), c.textSecondary)
    }
}

/** An informative pill on the player ("FLAC · 24/96", "→ WH-1000XM5", "EQ · Rock"). */
@Composable
fun InfoPill(text: String, on: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = Tf.colors
    Row(
        Modifier.height(28.dp).clip(RoundedCornerShape(50))
            .background(if (on) c.accent.copy(alpha = 0.12f) else c.text.copy(alpha = 0.07f))
            .border(1.dp, if (on) c.accent.copy(alpha = 0.45f) else c.text.copy(alpha = 0.10f), RoundedCornerShape(50))
            .tfClickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = if (on) c.accent else c.text)
        T(text, Tf.type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            if (on) c.accent else c.text)
    }
}

// --- switch ----------------------------------------------------------------------------------------------------------

@Composable
fun TfSwitch(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val c = Tf.colors
    Box(
        Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(50))
            .background(if (checked) c.accent else c.surface3)
            .then(if (checked) Modifier else Modifier.border(1.5.dp, c.outlineStrong, RoundedCornerShape(50)))
            .tfClickable(role = Role.Switch, haptic = true, label = label) { onChange(!checked) }
            .semantics { contentDescription = "$label, ${if (checked) "on" else "off"}" },
    ) {
        Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).padding(4.dp).size(22.dp)
            .clip(CircleShape).background(if (checked) c.onAccent else c.textSecondary))
    }
}

// --- artwork ---------------------------------------------------------------------------------------------------------

sealed interface ArtKey {
    data class Track(val id: Long) : ArtKey
    data class Folder(val id: Long) : ArtKey
}

/** Album art at the size it is shown, from the memory cache when it is there; a generated gradient otherwise. */
@Composable
fun ArtImage(key: ArtKey?, seed: String, modifier: Modifier = Modifier, large: Boolean = false, radius: Dp = 10.dp,
             fallbackIcon: ImageVector? = null) {
    val g = LocalGraph.current
    val size = if (large) Artwork.LARGE else Artwork.SMALL
    val cacheKey = when (key) { is ArtKey.Track -> "t${key.id}_$size"; is ArtKey.Folder -> "f${key.id}_$size"; null -> "" }
    var bmp by remember(cacheKey) { mutableStateOf(if (key != null) g.artwork.cached(cacheKey) else null) }
    var tried by remember(cacheKey) { mutableStateOf(bmp != null) }
    LaunchedEffect(cacheKey) {
        if (key != null && bmp == null) {
            bmp = when (key) { is ArtKey.Track -> g.artwork.track(key.id, size); is ArtKey.Folder -> g.artwork.folder(key.id, size) }
        }
        tried = true
    }
    // A picture that arrives after the first frame fades in over the placeholder, alpha applied per drawing (no
    // offscreen copy per row, unlike Crossfade); one already in memory shows at once.
    val shown = remember(cacheKey) { Animatable(if (bmp != null) 1f else 0f) }
    LaunchedEffect(bmp) { if (bmp != null) shown.animateTo(1f, tween(200)) }
    val placeholder by remember(cacheKey) { derivedStateOf { bmp == null || shown.value < 1f } }
    Box(modifier.clip(RoundedCornerShape(radius))) {
        if (placeholder) Placeholder(seed, fallbackIcon, showLetter = tried)
        bmp?.let { b ->
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer {
                alpha = shown.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }, contentScale = ContentScale.Crop)
        }
    }
}

@Composable
fun BoxScope.Placeholder(seed: String, icon: ImageVector?, showLetter: Boolean) {
    val c = Tf.colors
    val h = seed.hashCode()
    val palette = listOf(c.waveLow, c.waveMid, c.waveHigh, Color(0xFF9CF0B8), Color(0xFFB79CFF), Color(0xFFFF9CD0))
    val a = palette[Math.floorMod(h, palette.size)]
    val b = palette[Math.floorMod(h / 7, palette.size)]
    Box(Modifier.fillMaxSize().background(c.surface2).background(Brush.linearGradient(listOf(a.copy(alpha = 0.55f), b.copy(alpha = 0.25f), c.surface2))),
        contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = c.text.copy(alpha = 0.75f))
        else if (showLetter && seed.isNotBlank()) T(seed.trim().take(1).uppercase(), Tf.type.title, c.text.copy(alpha = 0.85f))
    }
}

// --- rows ------------------------------------------------------------------------------------------------------------

val LocalRowHeight = compositionLocalOf { 60.dp }

/** A list row: thumbnail, two lines, trailing content. */
@Composable
fun ListRow(
    title: String,
    subtitle: String,
    art: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    titleColor: Color = if (highlighted) Tf.colors.accent else Tf.colors.text,
    trailing: @Composable RowScope.() -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Tf.colors
    val h = LocalRowHeight.current
    Row(
        modifier.fillMaxWidth().height(h)
            .then(if (highlighted) Modifier.background(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.10f), Color.Transparent))) else Modifier)
            .tfClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val thumb = if (h < 56.dp) 36.dp else 44.dp
        Box(Modifier.size(thumb), content = art)
        Column(Modifier.weight(1f)) {
            T(title, Tf.type.rowTitle, titleColor)
            if (subtitle.isNotEmpty()) T(subtitle, Tf.type.body.copy(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum"), c.textSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
    }
}

/** Three bars that move while the row's track plays. */
@Composable
fun PlayingBars(playing: Boolean, color: Color = Tf.colors.accent) {
    val t = rememberInfiniteTransition(label = "bars")
    val phases = listOf(200, 500, 50).map { delay ->
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(450, delayMillis = 0), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(delay)), label = "b$delay")
    }
    Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for ((i, p) in phases.withIndex()) {
            val f = if (playing) p.value else listOf(0.6f, 1f, 0.4f)[i]
            Box(Modifier.width(3.dp).fillMaxHeight(f).clip(RoundedCornerShape(1.5.dp)).background(color))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    T(text, Tf.type.label.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp), Tf.colors.textSecondary,
        modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    val c = Tf.colors
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(30.dp), tint = c.textSecondary)
        }
        T(title, Tf.type.title, c.text)
        T(body, Tf.type.body, c.textSecondary, maxLines = 4)
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            PillButton(action.first, null, primary = true, onClick = action.second)
        }
    }
}

/** The top bar of library screens. */
@Composable
fun TopBar(title: String, navIcon: ImageVector? = null, onNav: (() -> Unit)? = null,
           actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (navIcon != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (navIcon != null && onNav != null) IconBtn(navIcon, "Back", onClick = onNav)
        T(title, Tf.type.title, modifier = Modifier.weight(1f).padding(start = if (navIcon != null) 4.dp else 0.dp))
        actions()
    }
}

/** Horizontal tabs with an accent underline. */
@Composable
fun Tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).height(44.dp)
        .drawBehind { drawLine(c.outline, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f),
            androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(Modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).tfClickable(role = Role.Tab) { onSelect(i) }
                .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                T(l, Tf.type.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), if (on) c.text else c.textSecondary)
                if (on) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(c.accent))
            }
        }
    }
}

/** Breadcrumb chips: tap one to jump to it. */
@Composable
fun Breadcrumbs(names: List<String>, onSelect: (Int) -> Unit) {
    val c = Tf.colors
    val scroll = rememberScrollState()
    LaunchedEffect(names.size) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp).height(36.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, n ->
            val last = i == names.lastIndex
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (last) c.accentDim else c.surface2)
                .tfClickable(enabled = !last) { onSelect(i) }.padding(horizontal = 10.dp, vertical = 4.dp)) {
                T(n, Tf.type.label, if (last) c.accent else c.textSecondary)
            }
            if (!last) T("›", Tf.type.label, c.textTertiary)
        }
    }
}

/** A settings-style row: title, value line, trailing control. */
@Composable
fun SettingRow(title: String, value: String?, modifier: Modifier = Modifier, enabled: Boolean = true,
               trailing: @Composable () -> Unit = {}, onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val c = Tf.colors
    Row(
        modifier.fillMaxWidth().heightInMin(56.dp)
            .then(if (onClick != null) Modifier.tfClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            T(title, Tf.type.rowTitle.copy(fontSize = 15.sp), if (enabled) c.text else c.textTertiary, maxLines = 2)
            if (!value.isNullOrEmpty()) T(value, Tf.type.body.copy(fontSize = 13.sp), c.textSecondary, maxLines = 3)
        }
        trailing()
    }
}

fun Modifier.heightInMin(min: Dp) = this.heightIn(min = min)

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

fun formatTotal(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

val ContentPadding = PaddingValues(bottom = 96.dp)

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Tf.colors.outline))
}

@Composable
fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Tf.colors.outlineStrong))
    }
}

@Composable
fun widthClass(): Dp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp

@Composable
fun MaxWidth(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 720.dp)) { content() } }
}

/** Material's slider in the app's colours: accent thumb and track, a visible inactive track, no tick marks. */
@Composable
fun TfSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier,
             range: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, enabled: Boolean = true) {
    val c = Tf.colors
    Slider(value, onChange, modifier, enabled = enabled, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surface3,
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            disabledThumbColor = c.textTertiary, disabledActiveTrackColor = c.textTertiary,
            disabledInactiveTrackColor = c.surface2))
}
