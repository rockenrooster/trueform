package dev.trueform.player.ui.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import dev.trueform.player.ui.components.TfSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.dsp.BandLayout
import dev.trueform.player.dsp.EqDesign
import dev.trueform.player.dsp.EqMode
import dev.trueform.player.dsp.EqPreset
import dev.trueform.player.dsp.ToneTuning
import dev.trueform.player.settings.GainRange
import dev.trueform.player.settings.S
import dev.trueform.player.ui.UiActions
import dev.trueform.player.ui.components.Chip
import dev.trueform.player.ui.components.IconBtn
import dev.trueform.player.ui.components.LocalGraph
import dev.trueform.player.ui.components.PillButton
import dev.trueform.player.ui.components.Segmented
import dev.trueform.player.ui.components.T
import dev.trueform.player.ui.components.TextBtn
import dev.trueform.player.ui.components.TfIcons
import dev.trueform.player.ui.components.TfSwitch
import dev.trueform.player.ui.theme.InterFamily
import dev.trueform.player.ui.theme.LocalHaptics
import dev.trueform.player.ui.theme.Tf
import dev.trueform.player.ui.theme.tfClickable
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The Equalizer (docs/plan/04-ui-ux.md "Equalizer", 06-dsp-equalizer.md T9). */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun EqScreen(actions: UiActions) {
    val g = LocalGraph.current
    val c = Tf.colors
    val eq by g.dsp.state.collectAsState()
    val params by g.dsp.paramsFlow.collectAsState()
    val userPresets by g.dsp.userPresets.collectAsState()
    val route by g.dsp.routeLabel.collectAsState()
    val rangeSetting by g.settings.flow(S.gainRange).collectAsState(g.settings[S.gainRange])
    val range = rangeSetting.db
    var limitDb by remember { mutableFloatStateOf(0f) }
    var meterL by remember { mutableFloatStateOf(0f) }
    var meterR by remember { mutableFloatStateOf(0f) }
    var presetSheet by remember { mutableStateOf(false) }
    var saveSheet by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            val l = g.localDsp.limiter
            limitDb = l?.reductionDb ?: 0f
            l?.clearPeak()
            val (pl, pr) = g.localDsp.takePeaks()
            meterL = maxOf(pl, meterL * 0.85f); meterR = maxOf(pr, meterR * 0.85f)
        }
    }
    // the response the audio path uses, at 48 kHz, without the preamp
    val filters = remember(params) { if (params.eqOn) EqDesign.designAll(params.filters, 48000.0) else emptyList() }
    val autoPre = remember(filters) { EqDesign.autoPreampDb(filters, 48000.0, params.filters.map { it.f0 }.toDoubleArray()) }
    val preampShown = if (eq.autoPreamp) autoPre.toFloat() else eq.preampDb

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(TfIcons.Back, "Back") { actions.back() }
            T("Equalizer", Tf.type.title, modifier = Modifier.weight(1f).padding(start = 4.dp))
            TfSwitch(eq.on, "Equalizer") { g.dsp.setOn(it) }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Segmented(listOf(EqMode.GRAPHIC, EqMode.TONE), eq.mode, { if (it == EqMode.GRAPHIC) "Graphic" else "Tone" }) { g.dsp.setMode(it) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(10.dp)).tfClickable { presetSheet = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                T((eq.preset ?: "Custom") + if (eq.presetModified) " •" else "", Tf.type.label.copy(fontWeight = FontWeight.SemiBold))
                Icon(TfIcons.ExpandMore, null, Modifier.size(18.dp), tint = c.textSecondary)
            }
            IconBtn(TfIcons.Reset, "Reset the curve", size = 40.dp, iconSize = 20.dp, tint = c.textSecondary) { g.dsp.reset() }
            TextBtn("Save") { saveSheet = true }
        }
        if (route != null) T("Profile: $route", Tf.type.caption, c.textTertiary, Modifier.padding(horizontal = 16.dp))
        // graph
        ResponseGraph(filters, range, eq, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(170.dp),
            enabled = eq.mode == EqMode.GRAPHIC) { idx, db -> g.dsp.setBand(idx, db) }
        Meters(meterL, meterR)
        // presets
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in EqPreset.BUILT_IN + userPresets) Chip(p.name, eq.preset == p.name && !eq.presetModified) { g.dsp.applyPreset(p) }
        }
        if (eq.mode == EqMode.GRAPHIC) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                T("Bands", Tf.type.caption, c.textSecondary)
                Segmented(BandLayout.entries.toList(), eq.layout, { "${it.count}" }) { g.dsp.setLayout(it) }
            }
            val scroll = rememberScrollState()
            Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 10.dp),
                horizontalArrangement = if (eq.layout.count <= 10) Arrangement.SpaceBetween else Arrangement.spacedBy(2.dp)) {
                for (i in 0 until eq.layout.count) {
                    BandSlider(eq.gains.getOrElse(i) { 0f }, range, eq.layout.label(i),
                        Modifier.width(if (eq.layout.count <= 10) 34.dp else 30.dp)) { g.dsp.setBand(i, it) }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0 until 4) Knob(eq.tone[i], range, ToneTuning.names[i]) { g.dsp.setTone(i, it) }
            }
        }
        // preamp
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(60.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            T("Preamp", Tf.type.label, c.textSecondary, Modifier.width(56.dp))
            TfSlider(preampShown, { g.dsp.setPreamp((it * 2).roundToInt() / 2f) }, Modifier.weight(1f), range = -15f..15f, enabled = !eq.autoPreamp)
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (eq.autoPreamp) c.accentDim else c.surface2)
                .tfClickable(label = "Automatic preamp") { g.dsp.setAutoPreamp(!eq.autoPreamp) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                T(if (eq.autoPreamp) "Auto %+.1f dB".format(preampShown) else "%+.1f dB".format(preampShown),
                    Tf.type.caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), if (eq.autoPreamp) c.accent else c.text)
            }
            val active = limitDb < -0.05f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.semantics { contentDescription = if (active) "Limiter reducing ${"%.1f".format(-limitDb)} dB" else "Limiter idle" }) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) c.limit else c.surface3))
                T(if (active) "%.1f".format(limitDb) else "LIMIT", Tf.type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                    if (active) c.limit else c.textTertiary)
            }
        }
        // more
        Row(Modifier.fillMaxWidth().height(48.dp).tfClickable { more = !more }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (more) TfIcons.ExpandMore else TfIcons.ChevronRight, null, tint = c.textSecondary)
            T("More: balance, mono, gain range", Tf.type.label, c.textSecondary, Modifier.padding(start = 6.dp))
        }
        if (more) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Balance", Tf.type.label, modifier = Modifier.width(96.dp))
                TfSlider(eq.balance, { g.dsp.setBalance(if (abs(it) < 0.04f) 0f else it) }, Modifier.weight(1f), range = -1f..1f)
                T(when { eq.balance == 0f -> "Centre"; eq.balance < 0 -> "L ${(-eq.balance * 100).roundToInt()}%"; else -> "R ${(eq.balance * 100).roundToInt()}%" },
                    Tf.type.caption, c.textSecondary, Modifier.width(64.dp).padding(start = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Mono", Tf.type.label, modifier = Modifier.weight(1f))
                TfSwitch(eq.mono, "Mono") { g.dsp.setMono(it) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T("Gain range", Tf.type.label, modifier = Modifier.weight(1f))
                for (r in GainRange.entries) Chip(r.label, r == rangeSetting) { g.settings[S.gainRange] = r }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (presetSheet) ModalBottomSheet(onDismissRequest = { presetSheet = false }, containerColor = c.surface1, contentColor = c.text) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            T("Presets", Tf.type.title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            for (p in EqPreset.BUILT_IN + userPresets) Row(Modifier.fillMaxWidth().height(52.dp).tfClickable { g.dsp.applyPreset(p); presetSheet = false }
                .padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                T(p.name, Tf.type.rowTitle, if (eq.preset == p.name) c.accent else c.text, Modifier.weight(1f))
                if (!p.builtIn) IconBtn(TfIcons.Delete, "Delete ${p.name}", tint = c.textSecondary) { g.dsp.deletePreset(p.name) }
            }
        }
    }
    if (saveSheet) ModalBottomSheet(onDismissRequest = { saveSheet = false }, containerColor = c.surface1, contentColor = c.text) {
        var name by remember { mutableStateOf(eq.preset?.takeIf { p -> userPresets.any { it.name == p } } ?: "") }
        Column(Modifier.navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T("Save preset", Tf.type.title)
            androidx.compose.foundation.text.BasicTextField(name, { name = it }, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(c.surface2).padding(14.dp), textStyle = Tf.type.rowTitle.copy(color = c.text), singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton("Save", null, primary = true) { if (name.isNotBlank()) { g.dsp.saveAsPreset(name.trim()); saveSheet = false } }
            }
        }
    }
}

/** The curve the filters make; dragging across it sets the bands the finger passes (graphic mode). */
@Composable
private fun ResponseGraph(filters: List<dev.trueform.player.dsp.Coeffs>, range: Float, eq: dev.trueform.player.dsp.EqState,
                          modifier: Modifier, enabled: Boolean, onBand: (Int, Float) -> Unit) {
    val c = Tf.colors
    val measurer = rememberTextMeasurer()
    val layout by rememberUpdatedState(eq.layout)
    val r by rememberUpdatedState(range)
    val set by rememberUpdatedState(onBand)
    var lastBand by remember { mutableIntStateOf(-1) }
    var lastDb by remember { mutableFloatStateOf(0f) }
    fun bandAt(x: Float, w: Float): Int {
        val f = 20.0 * 1000.0.pow((x / w).toDouble().coerceIn(0.0, 1.0))
        return layout.centres.indices.minBy { abs(ln(layout.centres[it]) - ln(f)) }
    }
    fun dbAt(y: Float, h: Float) = (((1 - 2 * y / h) * r) * 2).roundToInt() / 2f
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(c.surface1).border(1.dp, c.outline, RoundedCornerShape(14.dp))
        .then(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { o -> val b = bandAt(o.x, size.width.toFloat()); val d = dbAt(o.y, size.height.toFloat()).coerceIn(-r, r)
                    lastBand = b; lastDb = d; set(b, d) },
                onDragEnd = { lastBand = -1 },
            ) { ch, _ ->
                ch.consume()
                val b = bandAt(ch.position.x, size.width.toFloat())
                val d = dbAt(ch.position.y, size.height.toFloat()).coerceIn(-r, r)
                if (lastBand >= 0 && abs(b - lastBand) > 1) {   // a fast drag skipped bands: fill them in
                    val step = if (b > lastBand) 1 else -1
                    var i = lastBand + step
                    while (i != b) { set(i, (((lastDb + (d - lastDb) * (i - lastBand).toFloat() / (b - lastBand)) * 2).roundToInt() / 2f)); i += step }
                }
                set(b, d); lastBand = b; lastDb = d
            }
        }.pointerInput(Unit) {
            detectTapGestures { o -> set(bandAt(o.x, size.width.toFloat()), dbAt(o.y, size.height.toFloat()).coerceIn(-r, r)) }
        })
        .semantics { contentDescription = "Frequency response graph" }) {
        val left = 30.dp.toPx(); val right = 10.dp.toPx(); val top = 12.dp.toPx(); val bottom = 22.dp.toPx()
        val pw = size.width - left - right; val ph = size.height - top - bottom
        fun x(f: Double) = (left + pw * (ln(f / 20) / ln(1000.0))).toFloat()
        fun y(db: Double) = (top + ph / 2 - db / r * ph / 2).toFloat()
        val label = androidx.compose.ui.text.TextStyle(fontFamily = InterFamily, fontSize = 9.sp, color = c.textTertiary)
        for (db in listOf(-r.toDouble(), -r / 2.0, 0.0, r / 2.0, r.toDouble())) {
            drawLine(if (db == 0.0) c.outlineStrong else c.outline, Offset(left, y(db)), Offset(size.width - right, y(db)), 1f)
        }
        for (txt in listOf("+${r.toInt()}" to r.toDouble(), "0" to 0.0, "−${r.toInt()}" to -r.toDouble())) {
            val m = measurer.measure(txt.first, label)
            drawText(m, topLeft = Offset(left - 6.dp.toPx() - m.size.width, y(txt.second) - m.size.height / 2))
        }
        for (f in listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)) {
            val major = f in setOf(20.0, 100.0, 1000.0, 10000.0, 20000.0)
            drawLine(c.outline.copy(alpha = if (major) 1f else 0.45f), Offset(x(f), top), Offset(x(f), top + ph), 1f)
            if (major) {
                val m = measurer.measure(if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}", label)
                drawText(m, topLeft = Offset((x(f) - m.size.width / 2).coerceIn(left, size.width - right - m.size.width), size.height - bottom + 5.dp.toPx()))
            }
        }
        val line = Path(); val area = Path()
        area.moveTo(left, y(0.0))
        val n = 160
        for (i in 0..n) {
            val f = 20.0 * 1000.0.pow(i.toDouble() / n)
            val db = if (filters.isEmpty()) 0.0 else EqDesign.responseDb(filters, 48000.0, f).coerceIn(-r.toDouble(), r.toDouble())
            val px = x(f); val py = y(db)
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
            area.lineTo(px, py)
        }
        area.lineTo(left + pw, y(0.0)); area.close()
        drawPath(area, Brush.horizontalGradient(listOf(c.waveLow, c.waveMid, c.waveHigh), left, left + pw), alpha = 0.26f)
        drawPath(line, if (eq.on) c.accent else c.textTertiary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (eq.mode == EqMode.GRAPHIC) for (i in 0 until eq.layout.count) {
            val f = eq.layout.centres[i]
            val db = if (filters.isEmpty()) 0.0 else EqDesign.responseDb(filters, 48000.0, f)
            drawCircle(c.surface1, 3.dp.toPx(), Offset(x(f), y(db.coerceIn(-r.toDouble(), r.toDouble()))))
            drawCircle(if (eq.on) c.accent else c.textTertiary, 3.dp.toPx(), Offset(x(f), y(db.coerceIn(-r.toDouble(), r.toDouble()))), style = Stroke(1.6.dp.toPx()))
        }
    }
}

@Composable
private fun Meters(l: Float, r: Float) {
    val c = Tf.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (v in listOf(l, r)) {
            val db = if (v > 0f) 20 * log10(v) else -60f
            val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.surface3)) {
                Box(Modifier.fillMaxWidth(frac).height(3.dp).background(Brush.horizontalGradient(
                    0f to c.positive, 0.72f to c.positive, 0.88f to c.waveMid, 1f to c.limit)))
            }
        }
    }
}

/** A vertical band slider: drag, snap to 0.5 dB, a detent at 0, double-tap resets. */
@Composable
private fun BandSlider(value: Float, range: Float, label: String, modifier: Modifier, onChange: (Float) -> Unit) {
    val c = Tf.colors
    val haptic = LocalHapticFeedback.current
    val hapticsOn = LocalHaptics.current
    val v by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var dragging by remember { mutableStateOf(false) }
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T(if (value == 0f) "0" else "%+.1f".format(value).replace(".0", ""), Tf.type.caption.copy(fontSize = 10.sp, fontFeatureSettings = "tnum"),
            if (dragging) c.accent else c.textSecondary)
        Box(Modifier.height(150.dp).width(30.dp)
            .semantics {
                contentDescription = "$label Hz band"
                progressBarRangeInfo = ProgressBarRangeInfo(value, -range..range)
                setProgress { nv -> change((nv * 2).roundToInt() / 2f); true }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(0f) }) }
            .pointerInput(range) {
                var acc = 0f
                detectVerticalDragGestures(onDragStart = { acc = v; dragging = true }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { ch, d ->
                    ch.consume()
                    acc = (acc - d / size.height * 2 * range).coerceIn(-range, range)
                    val snapped = (acc * 2).roundToInt() / 2f
                    if (snapped != v) {
                        if (snapped == 0f && hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        change(snapped)
                    }
                }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2
                val trackW = 4.dp.toPx()
                drawRoundRect(c.surface3, Offset(cx - trackW / 2, 0f), Size(trackW, size.height), androidx.compose.ui.geometry.CornerRadius(trackW / 2))
                drawLine(c.outlineStrong, Offset(cx - 8.dp.toPx(), size.height / 2), Offset(cx + 8.dp.toPx(), size.height / 2), 1f)
                val y = size.height / 2 - value / range * size.height / 2
                drawRect(c.accent, Offset(cx - trackW / 2, minOf(y, size.height / 2)), Size(trackW, abs(y - size.height / 2)))
                drawCircle(Color.Black.copy(alpha = 0.35f), 10.dp.toPx(), Offset(cx, y + 1.dp.toPx()))
                drawCircle(if (dragging) c.accent else c.text, 9.dp.toPx(), Offset(cx, y))
            }
        }
        T(label, Tf.type.caption.copy(fontSize = 10.sp), c.textTertiary, Modifier.padding(top = 8.dp))
    }
}

/** A tone knob: vertical drag (2 dp per 0.1 dB), a tick per dB, double-tap resets. */
@Composable
private fun Knob(value: Float, range: Float, name: String, onChange: (Float) -> Unit) {
    val c = Tf.colors
    val haptic = LocalHapticFeedback.current
    val hapticsOn = LocalHaptics.current
    val v by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp)
            .semantics {
                contentDescription = "$name knob"
                progressBarRangeInfo = ProgressBarRangeInfo(value, -range..range)
                setProgress { nv -> change((nv * 10).roundToInt() / 10f); true }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(0f) }) }
            .pointerInput(range) {
                var acc = 0f
                detectVerticalDragGestures(onDragStart = { acc = v }) { ch, d ->
                    ch.consume()
                    acc = (acc - d / 2.dp.toPx() * 0.1f).coerceIn(-range, range)
                    val snapped = (acc * 10).roundToInt() / 10f
                    if (snapped.toInt() != v.toInt() && hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (snapped != v) change(snapped)
                }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2 - 4.dp.toPx()
                val stroke = 4.dp.toPx()
                drawArc(c.surface3, 135f, 270f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                val ang = value / range * 135f
                if (value != 0f) drawArc(c.accent, 270f + minOf(0f, ang), abs(ang), false, Offset(center.x - radius, center.y - radius),
                    Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(c.surface2, radius * 0.72f, center)
                drawCircle(c.outlineStrong, radius * 0.72f, center, style = Stroke(1f))
                val a = Math.toRadians((ang - 90).toDouble())
                val p0 = Offset(center.x + (radius * 0.28f * cos(a)).toFloat(), center.y + (radius * 0.28f * sin(a)).toFloat())
                val p1 = Offset(center.x + (radius * 0.56f * cos(a)).toFloat(), center.y + (radius * 0.56f * sin(a)).toFloat())
                drawLine(if (value == 0f) c.textSecondary else c.accent, p0, p1, 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        T(name, Tf.type.caption.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp))
        T("%+.1f dB".format(value).replace("+0.0", "0.0"), Tf.type.caption.copy(fontFeatureSettings = "tnum"), if (value == 0f) c.textTertiary else c.accent)
    }
}
