package dev.trueform.player.ui.sheets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.library.Folder
import dev.trueform.player.library.LibraryRepo
import dev.trueform.player.library.Playlist
import dev.trueform.player.library.Track
import dev.trueform.player.playback.QueueSource
import dev.trueform.player.playback.Repeat
import dev.trueform.player.ui.Dest
import dev.trueform.player.ui.UiActions
import dev.trueform.player.ui.components.ArtImage
import dev.trueform.player.ui.components.ArtKey
import dev.trueform.player.ui.components.Chip
import dev.trueform.player.ui.components.IconBtn
import dev.trueform.player.ui.components.LocalGraph
import dev.trueform.player.ui.components.PillButton
import dev.trueform.player.ui.components.SettingRow
import dev.trueform.player.ui.components.T
import dev.trueform.player.ui.components.TextBtn
import dev.trueform.player.ui.components.TfIcons
import dev.trueform.player.ui.components.formatDuration
import dev.trueform.player.ui.components.formatTotal
import dev.trueform.player.ui.player.PlayerUi
import dev.trueform.player.ui.theme.Tf
import dev.trueform.player.ui.theme.tfClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

sealed interface SheetKind {
    data object Queue : SheetKind
    data class TrackActions(val track: Track, val fromPlayer: Boolean = false, val playlistId: Long? = null) : SheetKind
    data class FolderActions(val folder: Folder) : SheetKind
    data class PlaylistActions(val playlist: Playlist) : SheetKind
    data class Info(val track: Track) : SheetKind
    data class AddToPlaylist(val trackIds: List<Long>) : SheetKind
    data class NewPlaylist(val trackIds: List<Long>) : SheetKind
    data class Rename(val playlist: Playlist) : SheetKind
    data object Timer : SheetKind
    data object Output : SheetKind
    data object Update : SheetKind
    /** Text, scrollable when long, with an optional action button. */
    data class Message(val title: String, val body: String, val button: String? = null, val onButton: (() -> Unit)? = null) : SheetKind
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun SheetHost(kind: SheetKind, ui: PlayerUi, actions: UiActions) {
    val c = Tf.colors
    val g = LocalGraph.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = kind is SheetKind.Queue)
    ModalBottomSheet(onDismissRequest = { actions.closeSheet() }, sheetState = state, containerColor = c.surface1,
        contentColor = c.text, scrimColor = c.bg.copy(alpha = 0.6f), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.navigationBarsPadding()) {
            when (kind) {
                SheetKind.Queue -> QueueSheet(ui, actions)
                is SheetKind.TrackActions -> TrackActionsSheet(kind, actions)
                is SheetKind.FolderActions -> FolderActionsSheet(kind.folder, actions)
                is SheetKind.PlaylistActions -> PlaylistActionsSheet(kind.playlist, actions)
                is SheetKind.Info -> TrackInfoSheet(kind.track)
                is SheetKind.AddToPlaylist -> AddToPlaylistSheet(kind.trackIds, actions)
                is SheetKind.NewPlaylist -> NameSheet("New playlist", "", "Create") { name ->
                    g.io.launch { g.library.createPlaylist(name, kind.trackIds) }; actions.closeSheet()
                }
                is SheetKind.Rename -> NameSheet("Rename playlist", kind.playlist.name, "Rename") { name ->
                    g.io.launch { g.library.renamePlaylist(kind.playlist.id, name) }; actions.closeSheet()
                }
                SheetKind.Timer -> TimerSheet(actions)
                SheetKind.Output -> OutputSheet(actions)
                SheetKind.Update -> UpdateSheet(actions)
                is SheetKind.Message -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T(kind.title, Tf.type.title)
                    Box(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        T(kind.body, Tf.type.body, c.textSecondary, maxLines = Int.MAX_VALUE)
                    }
                    if (kind.button != null) PillButton(kind.button, null, primary = true) { kind.onButton?.invoke(); actions.closeSheet() }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color = Tf.colors.text, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).tfClickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        T(text, Tf.type.rowTitle.copy(fontSize = 15.sp), tint)
    }
}

@Composable
private fun SheetTitle(title: String, sub: String? = null, art: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        art?.invoke()
        Column(Modifier.weight(1f)) {
            T(title, Tf.type.rowTitle.copy(fontWeight = FontWeight.SemiBold))
            if (!sub.isNullOrEmpty()) T(sub, Tf.type.caption, Tf.colors.textSecondary)
        }
    }
}

// --- track, folder, playlist actions ------------------------------------------------------------------------------

@UnstableApi
@Composable
private fun TrackActionsSheet(k: SheetKind.TrackActions, actions: UiActions) {
    val g = LocalGraph.current
    val t = k.track
    val scope = rememberCoroutineScope()
    SheetTitle(t.displayTitle, listOf(t.displayArtist, t.album).filter { it.isNotBlank() }.joinToString(" · ")) {
        ArtImage(ArtKey.Track(t.id), t.album, Modifier.size(48.dp))
    }
    if (!k.fromPlayer) {
        ActionRow(TfIcons.Play, "Play") { g.controller(); g.queue.play(listOf(t.id), 0, QueueSource.Mixed); actions.closeSheet() }
        ActionRow(TfIcons.PlayNext, "Play next") { g.queue.insertNext(listOf(t.id)); actions.closeSheet() }
        ActionRow(TfIcons.PlaylistAdd, "Add to queue") { g.queue.append(listOf(t.id)); actions.closeSheet() }
    }
    ActionRow(TfIcons.Add, "Add to playlist") { actions.sheet(SheetKind.AddToPlaylist(listOf(t.id))) }
    if (k.playlistId != null) ActionRow(TfIcons.Delete, "Remove from this playlist") {
        scope.launch(Dispatchers.IO) {
            val ids = g.library.playlistTracks(k.playlistId).map { it.id }.toMutableList()
            ids.remove(t.id)
            g.library.setPlaylistTracks(k.playlistId, ids)
        }
        actions.closeSheet()
    }
    if (t.album.isNotBlank()) ActionRow(TfIcons.Album, "Go to album") {
        scope.launch {
            val key = withContext(Dispatchers.IO) { g.library.albums().firstOrNull { a -> a.title == t.album && g.library.albumTracks(a.key).any { it.id == t.id } }?.key }
            actions.closeSheet()
            if (key != null) { if (k.fromPlayer) actions.openFromPlayer(Dest.Album(key)) else actions.open(Dest.Album(key)) }
        }
    }
    ActionRow(TfIcons.Folder, "Go to folder") { actions.closeSheet(); actions.goToFolderOf(t) }
    ActionRow(TfIcons.Info, "Track info") { actions.sheet(SheetKind.Info(t)) }
}

@UnstableApi
@Composable
private fun FolderActionsSheet(f: Folder, actions: UiActions) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    SheetTitle(f.name, "${f.tracksTotal} songs · ${formatTotal(f.durationTotalMs)}") {
        ArtImage(ArtKey.Folder(f.id), f.name, Modifier.size(48.dp), fallbackIcon = TfIcons.Folder)
    }
    fun withIds(block: (List<Long>) -> Unit) = scope.launch {
        val ids = withContext(Dispatchers.IO) { g.library.tracksUnder(f.id, true) }
        block(ids); actions.closeSheet()
    }
    ActionRow(TfIcons.Play, "Play") { withIds { g.controller(); g.queue.play(it, 0, QueueSource.Folder(f.id, true, f.id), shuffle = false) } }
    ActionRow(TfIcons.Shuffle, "Shuffle") { withIds { g.controller(); g.queue.play(it, 0, QueueSource.Folder(f.id, true, f.id), shuffle = true) } }
    ActionRow(TfIcons.PlayNext, "Play next") { withIds { g.queue.insertNext(it) } }
    ActionRow(TfIcons.PlaylistAdd, "Add to queue") { withIds { g.queue.append(it) } }
    ActionRow(TfIcons.Add, "Add to playlist") {
        scope.launch { val ids = withContext(Dispatchers.IO) { g.library.tracksUnder(f.id, true) }; actions.sheet(SheetKind.AddToPlaylist(ids)) }
    }
    ActionRow(TfIcons.Close, "Exclude from library", Tf.colors.danger) {
        scope.launch(Dispatchers.IO) {
            val roots = g.library.scanRoots().filter { it.path != f.path } + LibraryRepo.Root(f.path, false)
            g.library.setScanRoots(roots)
            g.scanner.request()
        }
        actions.closeSheet()
    }
}

@Composable
private fun PlaylistActionsSheet(p: Playlist, actions: UiActions) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SheetTitle(p.name, "${p.trackCount} songs")
    ActionRow(TfIcons.Edit, "Rename") { actions.sheet(SheetKind.Rename(p)) }
    ActionRow(TfIcons.Share, "Export as M3U") {
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                val dir = java.io.File(ctx.getExternalFilesDir(null), "playlists").apply { mkdirs() }
                java.io.File(dir, p.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".m3u8").also { g.library.exportM3u(p.id, it) }
            }
            actions.sheet(SheetKind.Message("Exported", f.absolutePath))
        }
    }
    ActionRow(TfIcons.Delete, "Delete playlist", Tf.colors.danger) {
        scope.launch(Dispatchers.IO) { g.library.deletePlaylist(p.id) }
        actions.closeSheet(); actions.back()
    }
}

@Composable
private fun AddToPlaylistSheet(ids: List<Long>, actions: UiActions) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    val lists by remember { g.library.playlistsFlow() }.collectAsState(emptyList())
    SheetTitle("Add ${ids.size} song${if (ids.size == 1) "" else "s"} to…")
    ActionRow(TfIcons.Add, "New playlist", Tf.colors.accent) { actions.sheet(SheetKind.NewPlaylist(ids)) }
    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
        for (p in lists) ActionRow(TfIcons.PlaylistPlay, "${p.name}  ·  ${p.trackCount}") {
            scope.launch(Dispatchers.IO) { g.library.addToPlaylist(p.id, ids) }
            actions.closeSheet()
        }
    }
}

@Composable
private fun NameSheet(title: String, initial: String, confirm: String, onDone: (String) -> Unit) {
    val g = LocalGraph.current
    val c = Tf.colors
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        T(title, Tf.type.title)
        BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(12.dp))
            .background(c.surface2).padding(14.dp), textStyle = Tf.type.rowTitle.copy(color = c.text), singleLine = true,
            cursorBrush = SolidColor(c.accent))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(confirm, null, primary = true) { if (text.isNotBlank()) onDone(text.trim()) }
        }
    }
}

// --- info --------------------------------------------------------------------------------------------------------

@Composable
private fun TrackInfoSheet(t: Track) {
    val g = LocalGraph.current
    val c = Tf.colors
    val wave by produceState(false, t.id) { value = withContext(Dispatchers.IO) { g.db.long("SELECT COUNT(*) FROM waveforms WHERE track_id = ?", t.id) ?: 0 } > 0 }
    val rows = listOf(
        "Title" to t.displayTitle, "Artist" to t.artist, "Album" to t.album, "Album artist" to t.albumArtist,
        "Genre" to t.genre, "Year" to (t.year.takeIf { it > 0 }?.toString() ?: ""), "Track" to (t.trackNo.takeIf { it > 0 }?.toString() ?: ""),
        "Disc" to (t.discNo.takeIf { it > 0 }?.toString() ?: ""), "Length" to formatDuration(t.durationMs),
        "Format" to t.formatLabel, "Codec" to "${t.codec} in ${t.container}",
        "Sample rate" to "${t.sampleRate} Hz", "Bit depth" to if (t.bits > 0) "${t.bits} bits" else "lossy",
        "Channels" to t.channels.toString(), "Bitrate" to if (t.bitrate > 0) "${t.bitrate / 1000} kbps" else "",
        "ReplayGain" to listOfNotNull(t.rgTrackGain?.let { "track %+.2f dB".format(it) }, t.rgAlbumGain?.let { "album %+.2f dB".format(it) }).joinToString(", ").ifEmpty { "none" },
        "Waveform" to if (wave) "analysed" else "not yet", "Exact length" to (t.exactFrames?.let { "$it frames" } ?: "from the file header"),
        "Played" to "${t.playCount} times", "File" to t.path,
    ).filter { it.second.isNotBlank() }
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        T("Track info", Tf.type.title, modifier = Modifier.padding(vertical = 8.dp))
        for ((k, v) in rows) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            T(k, Tf.type.label, c.textSecondary, Modifier.size(width = 110.dp, height = 20.dp))
            T(v, Tf.type.body, c.text, Modifier.weight(1f), maxLines = 4)
        }
    }
}

// --- sleep timer -------------------------------------------------------------------------------------------------

@Composable
private fun TimerSheet(actions: UiActions) {
    val g = LocalGraph.current
    val c = Tf.colors
    val remaining by g.sleepTimer.remainingMin.collectAsState()
    val end by g.sleepTimer.endOfTrack.collectAsState()
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T("Sleep timer", Tf.type.title)
        if (remaining != null) {
            T(if (end) "Stops at the end of this track" else "Stops in $remaining min", Tf.type.body, c.accent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!end) Chip("+10 min", false) { g.sleepTimer.extend(10) }
                Chip("Cancel", false) { g.sleepTimer.cancel(); actions.closeSheet() }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in listOf(15, 30, 45, 60, 90)) Chip("$m min", false) { g.sleepTimer.start(m); actions.closeSheet() }
        }
        Chip("End of this track", end) { g.sleepTimer.startEndOfTrack(); actions.closeSheet() }
        var custom by remember { mutableFloatStateOf(20f) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            dev.trueform.player.ui.components.TfSlider(custom, { custom = it }, Modifier.weight(1f), range = 5f..180f, steps = 34)
            TextBtn("${custom.roundToInt()} min") { g.sleepTimer.start(custom.roundToInt()); actions.closeSheet() }
        }
    }
}

// --- output ------------------------------------------------------------------------------------------------------

@Composable
private fun OutputSheet(actions: UiActions) {
    val g = LocalGraph.current
    val c = Tf.colors
    val label by g.outputs.label.collectAsState()
    val casting = g.cast?.state?.collectAsState()?.value?.phase?.let { it != dev.trueform.player.cast.CastState.Phase.NONE } == true
    Column(Modifier.padding(horizontal = 4.dp)) {
        SheetTitle("Output")
        SettingRow("This phone", if (casting) "Paused while casting" else label ?: "Phone speaker",
            trailing = { if (!casting) Icon(TfIcons.Check, null, tint = c.accent) })
        CastDevices(actions)
    }
}

// --- queue -------------------------------------------------------------------------------------------------------

@UnstableApi
@Composable
private fun QueueSheet(ui: PlayerUi, actions: UiActions) {
    val g = LocalGraph.current
    val c = Tf.colors
    val q by g.queue.state.collectAsState()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (q.index - 1).coerceAtLeast(0))
    val rowPx = with(LocalDensity.current) { 60.dp.toPx() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val remainingMs = remember(q) {
        q.entries.drop(q.index.coerceAtLeast(0)).sumOf { e -> g.queue.trackOf(e)?.durationMs ?: 0L }
    }
    val nextFolder by produceState<String?>(null, q.source, q.entries.size) {
        val src = q.source as? QueueSource.Folder
        value = if (src == null || q.repeat != Repeat.OFF) null else withContext(Dispatchers.IO) {
            g.library.folder(src.lastFolderId)?.let { g.library.nextFolderAfter(it, src.recursive)?.name }
        }
    }
    Column {
        T("Queue", Tf.type.title, modifier = Modifier.padding(horizontal = 20.dp))
        T("Playing from ${dev.trueform.player.ui.player.sourceLabel(q.source, ui.track)} · ${q.index + 1} of ${q.entries.size} · ${formatTotal(remainingMs)} left",
            Tf.type.caption, c.textSecondary, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp), listState) {
            itemsIndexed(q.entries, key = { _, e -> e.id }) { i, e ->
                val t = g.queue.trackOf(e)
                val dragging = i == dragIndex
                QueueRow(
                    title = t?.displayTitle ?: "…", subtitle = t?.displayArtist ?: "", trackId = e.trackId, current = i == q.index,
                    past = i < q.index, modifier = Modifier.zIndex(if (dragging) 1f else 0f)
                        .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) }
                        .background(if (dragging) c.surface2 else androidx.compose.ui.graphics.Color.Transparent),
                    onRemove = { g.queue.remove(e.id) },
                    handle = Modifier.pointerInput(e.id) {
                        detectVerticalDragGestures(
                            onDragStart = { dragIndex = q.entries.indexOfFirst { it.id == e.id }; dragOffset = 0f },
                            onDragEnd = { dragIndex = -1; dragOffset = 0f },
                            onDragCancel = { dragIndex = -1; dragOffset = 0f },
                            onVerticalDrag = { ch, d ->
                                ch.consume()
                                dragOffset += d
                                val from = dragIndex
                                if (from >= 0) {
                                    val steps = (dragOffset / rowPx).toInt()
                                    if (steps != 0) {
                                        val to = (from + steps).coerceIn(0, g.queue.state.value.entries.lastIndex)
                                        if (to != from) { g.queue.move(from, to); dragIndex = to; dragOffset -= (to - from) * rowPx }
                                    }
                                }
                            })
                    },
                ) { g.queue.jump(i) }
            }
            if (nextFolder != null) item {
                T("Then: next folder · $nextFolder", Tf.type.caption, c.textTertiary, Modifier.padding(20.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Shuffle", q.shuffle) { g.queue.setShuffle(!q.shuffle) }
            Chip(when (q.repeat) { Repeat.OFF -> "Repeat off"; Repeat.ALL -> "Repeat all"; Repeat.ONE -> "Repeat one" }, q.repeat != Repeat.OFF) { g.queue.cycleRepeat() }
            Chip("Save", false) { actions.sheet(SheetKind.NewPlaylist(q.entries.map { it.trackId })) }
            Chip("Clear", false) { g.queue.clear(); actions.closeSheet(); actions.collapsePlayer() }
        }
    }
}

@Composable
private fun QueueRow(title: String, subtitle: String, trackId: Long, current: Boolean, past: Boolean, modifier: Modifier,
                     onRemove: () -> Unit, handle: Modifier, onClick: () -> Unit) {
    val c = Tf.colors
    val scope = rememberCoroutineScope()
    val off = remember { Animatable(0f) }
    Box(modifier.fillMaxWidth().height(60.dp)) {
        Row(Modifier.fillMaxWidth().height(60.dp).offset { IntOffset(off.value.roundToInt(), 0) }
            .draggable(rememberDraggableState { d -> scope.launch { off.snapTo((off.value + d).coerceAtMost(0f)) } }, Orientation.Horizontal,
                onDragStopped = { if (off.value < -300f) onRemove() else off.animateTo(0f, tween(160)) })
            .tfClickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ArtImage(ArtKey.Track(trackId), title, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                T(title, Tf.type.rowTitle.copy(fontSize = 15.sp), when { current -> c.accent; past -> c.textTertiary; else -> c.text })
                T(subtitle, Tf.type.caption, c.textSecondary)
            }
            Box(handle.size(48.dp), contentAlignment = Alignment.Center) { Icon(TfIcons.Drag, "Drag to reorder", tint = c.textTertiary) }
        }
    }
}

// --- cast and update hooks (filled in by M7 and M10) -------------------------------------------------------------

@Composable
fun CastButton(actions: UiActions) = dev.trueform.player.cast.CastUi.Button(actions)

@Composable
fun CastDevices(actions: UiActions) = dev.trueform.player.cast.CastUi.Devices(actions)

@Composable
fun UpdatePill(actions: UiActions) = dev.trueform.player.update.UpdateUi.Pill(actions)

@Composable
private fun UpdateSheet(actions: UiActions) = dev.trueform.player.update.UpdateUi.Sheet(actions)

