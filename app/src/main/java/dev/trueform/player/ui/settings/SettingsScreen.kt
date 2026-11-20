package dev.trueform.player.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.AppGraph
import dev.trueform.player.BuildConfig
import dev.trueform.player.audio.NativeDecoder
import dev.trueform.player.settings.Action
import dev.trueform.player.settings.Category
import dev.trueform.player.settings.Choice
import dev.trueform.player.settings.Decibels
import dev.trueform.player.settings.Info
import dev.trueform.player.settings.Number
import dev.trueform.player.settings.S
import dev.trueform.player.settings.Setting
import dev.trueform.player.settings.Text
import dev.trueform.player.settings.Toggle
import dev.trueform.player.settings.matches
import dev.trueform.player.ui.Dest
import dev.trueform.player.ui.UiActions
import dev.trueform.player.ui.components.ContentPadding
import dev.trueform.player.ui.components.IconBtn
import dev.trueform.player.ui.components.LocalGraph
import dev.trueform.player.ui.components.SettingRow
import dev.trueform.player.ui.components.T
import dev.trueform.player.ui.components.TextBtn
import dev.trueform.player.ui.components.TfIcons
import dev.trueform.player.ui.components.TfSlider
import dev.trueform.player.ui.components.TfSwitch
import dev.trueform.player.ui.sheets.SheetKind
import dev.trueform.player.ui.theme.Tf
import dev.trueform.player.ui.theme.tfClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/** Settings, generated from the registry (docs/plan/09-settings.md). */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun SettingsScreen(category: Category?, actions: UiActions) {
    val g = LocalGraph.current
    val c = Tf.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Setting<*>?>(null) }
    val version by g.settings.version.collectAsState()

    val exportSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(SettingsIo.export(g).toByteArray()) } }
    }
    val importSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { SettingsIo.import(g, ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()) }
                    .fold({ "Imported $it values." }, { "That file is not a Trueform export: ${it.message}" })
            }
            actions.sheet(SheetKind.Message("Import", msg))
        }
    }
    val exportPresets = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(g.dsp.exportPresets().toByteArray()) } }
    }
    val importPresets = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching { g.dsp.importPresets(withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText() }) }
                .fold({ "Imported $it presets." }, { "That file holds no presets: ${it.message}" })
            actions.sheet(SheetKind.Message("Presets", msg))
        }
    }

    // "Resume when a Bluetooth device connects" needs BLUETOOTH_CONNECT on Android 12+: asked when it is turned on.
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            g.settings[S.resumeOnConnect] = false
            actions.sheet(SheetKind.Message("Bluetooth", "Without the \"Nearby devices\" permission Trueform cannot see a Bluetooth device connect."))
        }
    }
    val resumeOnConnect by g.settings.flow(S.resumeOnConnect).collectAsState(g.settings[S.resumeOnConnect])
    LaunchedEffect(resumeOnConnect) {
        if (resumeOnConnect && android.os.Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            btPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun run(a: Action) {
        when (a.key) {
            S.musicFolders.key -> actions.open(Dest.Folders)
            S.openEqualizer.key -> actions.open(Dest.Equalizer)
            S.rescan.key -> g.scanner.request()
            S.fullRescan.key -> g.scanner.request(full = true)
            S.allFilesAccess.key -> ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            S.outputProfiles.key -> actions.sheet(SheetKind.Message("Output profiles",
                g.dsp.profiles().joinToString("\n") { it.second.ifBlank { it.first } }.ifEmpty { "No outputs remembered yet." }))
            S.exportPresets.key -> exportPresets.launch("trueform-presets.json")
            S.importPresets.key -> importPresets.launch(arrayOf("application/json", "*/*"))
            S.clearWaveforms.key -> scope.launch(Dispatchers.IO) { g.waveforms.clear() }
            S.clearArtwork.key -> scope.launch(Dispatchers.IO) { g.artwork.clear() }
            S.exportSettings.key -> exportSettings.launch("trueform-settings.json")
            S.importSettings.key -> importSettings.launch(arrayOf("application/json", "*/*"))
            S.resetAll.key -> { g.settings.resetAll(); actions.sheet(SheetKind.Message("Settings", "Every setting is back to its default.")) }
            S.licenses.key -> actions.sheet(SheetKind.Message("Licenses", LICENSES))
            else -> dev.trueform.player.cast.CastUi.runAction(g, a.key, ctx, actions) || dev.trueform.player.update.UpdateUi.runAction(g, a.key, actions)
                || dev.trueform.player.system.Logs.runAction(g, a.key, ctx)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(TfIcons.Back, "Back") { actions.back() }
            T(category?.title ?: "Settings", Tf.type.title, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        if (category == null) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp))
                .background(c.surface2).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) T("Search settings", Tf.type.body, c.textTertiary)
                BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = Tf.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent))
            }
        }
        val rows: List<Setting<*>> = when {
            query.isNotBlank() -> S.all.filter { it.matches(query) && visible(g, it) }
            category != null -> S.all.filter { it.category == category && visible(g, it) }
            else -> emptyList()
        }
        LazyColumn(Modifier.weight(1f), contentPadding = ContentPadding) {
            if (category == null && query.isBlank()) {
                items(Category.entries.filter { cat -> S.all.any { it.category == cat && visible(g, it) } }) { cat ->
                    SettingRow(cat.title, S.all.filter { it.category == cat }.take(3).joinToString(", ") { it.title },
                        trailing = { Icon(TfIcons.ChevronRight, null, tint = c.textTertiary) }) { actions.open(Dest.Settings(cat)) }
                }
            } else {
                items(rows, key = { it.key }) { s ->
                    @Suppress("UNUSED_EXPRESSION") version
                    SettingItem(g, s, onEdit = { editing = it }, onAction = { run(it) })
                }
            }
        }
    }

    editing?.let { s ->
        ModalBottomSheet(onDismissRequest = { editing = null }, containerColor = c.surface1, contentColor = c.text) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) { EditSheet(g, s) { editing = null } }
        }
    }
}

private fun visible(g: AppGraph, s: Setting<*>): Boolean = when (s.category) {
    Category.CAST -> dev.trueform.player.cast.CastUi.available(g)
    Category.UPDATES -> true
    else -> true
}

@UnstableApi
@Composable
private fun SettingItem(g: AppGraph, s: Setting<*>, onEdit: (Setting<*>) -> Unit, onAction: (Action) -> Unit) {
    val c = Tf.colors
    val reset = { g.settings.reset(s) }
    when (s) {
        is Toggle -> {
            val v = g.settings[s]
            SettingRow(s.title, s.summary, trailing = { TfSwitch(v, s.title) { g.settings[s] = it } }, onLongClick = reset) { g.settings[s] = !v }
        }
        is Choice<*> -> SettingRow(s.title, s.labelOf(g.settings[s]), onLongClick = reset) { onEdit(s) }
        is Number -> SettingRow(s.title, "${g.settings[s]} ${s.unit}" + (s.summary?.let { "  ·  $it" } ?: ""), onLongClick = reset) { onEdit(s) }
        is Decibels -> SettingRow(s.title, "%+.1f dB".format(g.settings[s]), onLongClick = reset) { onEdit(s) }
        is Text -> SettingRow(s.title, s.summary, onLongClick = reset) { onEdit(s) }
        is Action -> SettingRow(s.title, s.summary) { onAction(s) }
        is Info -> SettingRow(s.title, AppInfo.value(g, s.key))
        else -> Unit
    }
    @Suppress("UNUSED_EXPRESSION") c
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun EditSheet(g: AppGraph, s: Setting<*>, close: () -> Unit) {
    val c = Tf.colors
    T(s.title, Tf.type.title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    when (s) {
        is Choice<*> -> {
            val cur = g.settings[s]
            for (o in s.options) {
                Row(Modifier.fillMaxWidth().height(52.dp).tfClickable { g.settings.setChoice(s, o); close() }
                    .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(s.labelOf(o), Tf.type.rowTitle, if (o == cur) c.accent else c.text, Modifier.weight(1f))
                    if (o == cur) Icon(TfIcons.Check, null, tint = c.accent)
                }
            }
        }
        is Number -> {
            var v by remember { mutableFloatStateOf(g.settings[s].toFloat()) }
            T("${v.roundToInt()} ${s.unit}", Tf.type.display, c.accent, Modifier.padding(horizontal = 20.dp))
            TfSlider(v, { v = it; g.settings[s] = (it / s.step).roundToInt() * s.step }, Modifier.padding(horizontal = 16.dp),
                range = s.range.first.toFloat()..s.range.last.toFloat(), steps = ((s.range.last - s.range.first) / s.step - 1).coerceAtLeast(0))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextBtn("Default") { g.settings.reset(s); v = s.default.toFloat() }
                TextBtn("Done") { close() }
            }
        }
        is Decibels -> {
            var v by remember { mutableFloatStateOf(g.settings[s]) }
            T("%+.1f dB".format(v), Tf.type.display, c.accent, Modifier.padding(horizontal = 20.dp))
            TfSlider(v, { v = (it / s.step).roundToInt() * s.step; g.settings[s] = v }, Modifier.padding(horizontal = 16.dp), range = s.range)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextBtn("Default") { g.settings.reset(s); v = s.default }
                TextBtn("Done") { close() }
            }
        }
        is Text -> if (s.key == S.libraryTabs.key) TabsEditor(g) else {
            var v by remember { mutableStateOf(g.settings[s]) }
            BasicTextField(v, { v = it; g.settings[s] = it }, Modifier.fillMaxWidth().padding(20.dp), textStyle = Tf.type.body.copy(color = c.text))
        }
        else -> Unit
    }
}

@Composable
private fun TabsEditor(g: AppGraph) {
    val c = Tf.colors
    val all = listOf("folders", "albums", "artists", "songs", "playlists")
    var order by remember { mutableStateOf(g.settings[S.libraryTabs].split(',').map { it.trim() }.filter { it in all }) }
    fun save(l: List<String>) { order = l; g.settings[S.libraryTabs] = l.joinToString(",") }
    val shown = order + all.filter { it !in order }
    for (t in shown) {
        val on = t in order
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TfSwitch(on, t) { if (it) save(order + t) else if (order.size > 1) save(order - t) }
            Spacer(Modifier.size(12.dp))
            T(t.replaceFirstChar { it.uppercase() }, Tf.type.rowTitle, if (on) c.text else c.textTertiary, Modifier.weight(1f))
            if (on && order.indexOf(t) > 0) IconBtn(TfIcons.ArrowUp, "Move up") {
                val i = order.indexOf(t); save(order.toMutableList().apply { removeAt(i); add(i - 1, t) })
            }
        }
    }
}

/** Read-only rows (Settings → Updates and Advanced). */
object AppInfo {
    @UnstableApi
    fun value(g: AppGraph, key: String): String = when (key) {
        S.buildInfo.key -> "${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_NUMBER}" + (BuildConfig.COMMIT.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
        S.audioDetails.key -> {
            val f = g.lastInputFormat
            "Output: ${g.outputs.label.value ?: "–"} · ${f?.let { "${it.sampleRate} Hz, ${it.channelCount} ch, float" } ?: "idle"} · buffer ${g.settings[S.audioBufferMs]} ms · underruns ${g.underruns}"
        }
        S.decoderInfo.key -> "FFmpeg ${NativeDecoder.ffmpegVersion()} · MP3 FLAC AAC ALAC Vorbis Opus WMA APE WavPack Musepack TTA TAK DSD AIFF WAV"
        else -> ""
    }
}

/** Settings and presets as one JSON file (docs/plan/09-settings.md, Advanced). */
object SettingsIo {
    fun export(g: AppGraph): String {
        val s = JSONObject()
        for ((k, v) in g.settings.all()) if (!k.startsWith("_")) s.put(k, v)
        return JSONObject().put("trueform", 1).put("settings", s).put("presets", org.json.JSONArray(g.dsp.exportPresets()))
            .put("eq", g.dsp.state.value.toJson()).toString(1)
    }

    /** Applies known keys only; returns how many values were set. */
    fun import(g: AppGraph, json: String): Int {
        val o = JSONObject(json)
        require(o.optInt("trueform") == 1) { "missing marker" }
        val known = S.all.filter { it.stored }.associateBy { it.key }
        val s = o.getJSONObject("settings")
        var n = 0
        for (k in s.keys()) {
            val setting = known[k] ?: continue
            val v = s.get(k)
            when (setting) {
                is Toggle -> if (v is Boolean) { g.settings[setting] = v; n++ }
                is Number -> (v as? kotlin.Number)?.let { g.settings[setting] = it.toInt(); n++ }
                is Decibels -> (v as? kotlin.Number)?.let { g.settings[setting] = it.toFloat(); n++ }
                is Choice<*>, is Text -> if (v is String) { g.settings.putRaw(k, v); n++ }
                else -> Unit
            }
        }
        o.optJSONArray("presets")?.let { g.dsp.importPresets(it.toString()) }
        return n
    }
}

const val LICENSES = """Trueform uses:

FFmpeg 8.1.3 — GNU LGPL 2.1 or later. Built from the ffmpeg.org release by scripts/build-ffmpeg.sh in the Trueform repository, which is also the recipe for rebuilding and relinking it.

AndroidX Media3, Compose, AppCompat, Palette, Kotlin coroutines — Apache License 2.0.

Google Play services Cast framework — Android Software Development Kit License.

Inter typeface — SIL Open Font License 1.1, © The Inter Project Authors.

Material Symbols — Apache License 2.0, © Google."""
