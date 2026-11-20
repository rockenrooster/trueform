package dev.trueform.player.ui.onboarding

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.trueform.player.library.LibraryRepo
import dev.trueform.player.library.StorageRoots
import dev.trueform.player.ui.components.IconBtn
import dev.trueform.player.ui.components.LocalGraph
import dev.trueform.player.ui.components.PillButton
import dev.trueform.player.ui.components.T
import dev.trueform.player.ui.components.TfIcons
import dev.trueform.player.ui.theme.Tf
import dev.trueform.player.ui.theme.tfClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE

fun hasAudioPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

/** First run: welcome, access to music, choose folders (docs/plan/04-ui-ux.md "First run"). */
@Composable
fun Onboarding(requestPermission: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var granted by remember { mutableStateOf(hasAudioPermission(ctx)) }
    LifecycleResumeEffect(Unit) { granted = hasAudioPermission(ctx); onPauseOrDispose {} }
    when {
        step == 0 -> Welcome(TfIcons.GraphicEq, "Trueform", "A music player for your own files. Your folders, played true to the recording.",
            "Get started") { step = if (granted) 2 else 1 }
        step == 1 && !granted -> Welcome(TfIcons.Lock, "Allow access to your music",
            "Trueform reads the audio files on this phone. Nothing leaves the phone.", "Allow access", requestPermission)
        else -> FolderPicker(onDone = onDone, onBack = null)
    }
}

@Composable
private fun Welcome(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, button: String, onClick: () -> Unit) {
    val c = Tf.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(c.waveLow, c.waveMid, c.waveHigh))),
            contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(44.dp), tint = c.bg) }
        Spacer(Modifier.height(28.dp))
        T(title, Tf.type.display.copy(fontSize = 30.sp, lineHeight = 36.sp), maxLines = 2)
        Spacer(Modifier.height(10.dp))
        T(body, Tf.type.body.copy(fontSize = 16.sp, lineHeight = 24.sp), c.textSecondary, maxLines = 5)
        Spacer(Modifier.height(32.dp))
        PillButton(button, null, primary = true, onClick = onClick)
    }
}

/**
 * Choose music folders (docs/plan/03-library-scanner.md T7): volumes, an expandable tree, tri-state boxes. The longest
 * matching root decides, so unchecking a subfolder of a checked folder excludes it.
 */
@Composable
fun FolderPicker(onDone: () -> Unit, onBack: (() -> Unit)?) {
    val g = LocalGraph.current
    val c = Tf.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val roots = remember { mutableStateListOf<LibraryRepo.Root>() }
    val expanded = remember { mutableStateListOf<String>() }
    val children = remember { mutableStateOf(mapOf<String, List<String>>()) }
    val volumes = remember { StorageRoots.volumes(ctx).filter { it.mounted } }
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { g.library.scanRoots() }
        roots.addAll(saved.ifEmpty { listOf(LibraryRepo.Root(StorageRoots.defaultRoot(), true)) })
        val primary = volumes.firstOrNull { it.primary }?.path
        if (primary != null) expanded += primary
    }
    LaunchedEffect(expanded.toList()) {
        val map = children.value.toMutableMap()
        for (p in expanded) if (p !in map) map[p] = withContext(Dispatchers.IO) {
            File(p).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
                ?.map { it.path }?.sortedWith { a, b -> dev.trueform.player.library.NaturalOrder.compare(File(a).name, File(b).name) } ?: emptyList()
        }
        children.value = map
    }
    fun state(path: String): Int {   // 0 off, 1 on, 2 partly
        val on = LibraryRepo.included(path, roots)
        val differs = roots.any { it.path.startsWith("$path/") && it.included != on }
        return if (differs) 2 else if (on) 1 else 0
    }
    fun toggle(path: String) {
        val on = state(path) != 1
        roots.removeAll { it.path == path || it.path.startsWith("$path/") }
        val parentOn = LibraryRepo.included(path, roots)
        if (parentOn != on) roots += LibraryRepo.Root(path, on)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconBtn(TfIcons.Back, "Back", onClick = onBack) else Spacer(Modifier.width(12.dp))
            T("Music folders", Tf.type.title, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        T("Checked folders and everything in them are scanned. Uncheck a subfolder to leave it out.", Tf.type.body, c.textSecondary,
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 3)
        val rows = buildList {
            fun add(path: String, depth: Int, label: String) {
                add(Triple(path, depth, label))
                if (path in expanded) children.value[path]?.forEach { add(it, depth + 1, File(it).name) }
            }
            for (v in volumes) add(v.path, 0, v.label)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.first }) { (path, depth, label) ->
                val s = state(path)
                Row(Modifier.fillMaxWidth().height(52.dp).tfClickable { toggle(path) }.padding(start = (12 + depth * 20).dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (s == 0) androidx.compose.ui.graphics.Color.Transparent else c.accent)
                        .border(2.dp, if (s == 0) c.outlineStrong else c.accent, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        if (s == 1) Icon(TfIcons.Check, null, Modifier.size(16.dp), tint = c.onAccent)
                        if (s == 2) Box(Modifier.size(10.dp, 2.dp).background(c.onAccent))
                    }
                    Icon(TfIcons.Folder, null, Modifier.size(20.dp), tint = c.textSecondary)
                    T(label, Tf.type.rowTitle.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                    val open = path in expanded
                    IconBtn(if (open) TfIcons.ExpandMore else TfIcons.ChevronRight, if (open) "Collapse" else "Expand", size = 44.dp) {
                        if (open) expanded.remove(path) else expanded.add(path)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            PillButton(if (onBack == null) "Start" else "Save", null, primary = true) {
                scope.launch {
                    withContext(Dispatchers.IO) { g.library.setScanRoots(roots.toList()) }
                    g.scanner.request()
                    onDone()
                }
            }
        }
    }
}
