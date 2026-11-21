package dev.trueform.player.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.AppGraph
import dev.trueform.player.library.Folder
import dev.trueform.player.library.FolderChildren
import dev.trueform.player.library.Scanner
import dev.trueform.player.library.SmartList
import dev.trueform.player.library.Track
import dev.trueform.player.playback.QueueSource
import dev.trueform.player.settings.S
import dev.trueform.player.settings.TapPlays
import dev.trueform.player.ui.Dest
import dev.trueform.player.ui.Nav
import dev.trueform.player.ui.UiActions
import dev.trueform.player.ui.components.ArtImage
import dev.trueform.player.ui.components.ArtKey
import dev.trueform.player.ui.components.Breadcrumbs
import dev.trueform.player.ui.components.ContentPadding
import dev.trueform.player.ui.components.EmptyState
import dev.trueform.player.ui.components.FormatPill
import dev.trueform.player.ui.components.IconBtn
import dev.trueform.player.ui.components.ListRow
import dev.trueform.player.ui.components.LocalGraph
import dev.trueform.player.ui.components.PillButton
import dev.trueform.player.ui.components.PlayingBars
import dev.trueform.player.ui.components.SectionTitle
import dev.trueform.player.ui.components.T
import dev.trueform.player.ui.components.Tabs
import dev.trueform.player.ui.components.TfIcons
import dev.trueform.player.ui.components.formatDuration
import dev.trueform.player.ui.components.formatTotal
import dev.trueform.player.ui.components.plural
import dev.trueform.player.ui.player.PlayerUi
import dev.trueform.player.ui.sheets.CastButton
import dev.trueform.player.ui.sheets.SheetKind
import dev.trueform.player.ui.sheets.UpdatePill
import dev.trueform.player.ui.theme.Tf
import dev.trueform.player.ui.theme.tfClickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val TAB_LABELS = mapOf("folders" to "Folders", "albums" to "Albums", "artists" to "Artists", "songs" to "Songs", "playlists" to "Playlists")

@UnstableApi
@Composable
fun LibraryScreen(nav: Nav, folderId: Long?, ui: PlayerUi, actions: UiActions) {
    val g = LocalGraph.current
    val tabsSetting by g.settings.flow(S.libraryTabs).collectAsState(g.settings[S.libraryTabs])
    val tabs = tabsSetting.split(',').map { it.trim() }.filter { it in TAB_LABELS }.ifEmpty { TAB_LABELS.keys.toList() }
    val tab = tabs.getOrElse(nav.tab.coerceIn(0, tabs.lastIndex)) { "folders" }
    val roots by remember { g.library.rootsFlow() }.collectAsState(emptyList())
    // One top folder: open inside it.
    val effectiveFolder = folderId ?: roots.singleOrNull()?.id
    val ancestry by produceState(emptyList<Folder>(), effectiveFolder) {
        value = if (effectiveFolder == null) emptyList() else withContext(Dispatchers.IO) { g.library.ancestry(effectiveFolder) }
    }
    val title = if (tab == "folders") ancestry.lastOrNull()?.name ?: "Library" else TAB_LABELS[tab] ?: "Library"
    val scan by g.scanner.progress.collectAsState()
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }

    Column(Modifier.fillMaxSize()
        .then(if (folderId != null) Modifier.edgeSwipeUp(swipe, scope) { actions.back() } else Modifier)
        .graphicsLayer { translationX = swipe.value }
        .statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (folderId != null) 4.dp else 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (folderId != null) IconBtn(TfIcons.Back, "Up one folder") { actions.back() }
            T(title, Tf.type.title, modifier = Modifier.weight(1f, fill = false).padding(start = if (folderId != null) 4.dp else 0.dp, end = 8.dp))
            UpdatePill(actions)
            Spacer(Modifier.weight(1f))
            IconBtn(TfIcons.Search, "Search") { actions.open(Dest.Search) }
            CastButton(actions)
            IconBtn(TfIcons.Settings, "Settings") { actions.open(Dest.Settings()) }
        }
        if (tab == "folders" && ancestry.size > 1) {
            Breadcrumbs(ancestry.map { it.name }) { i -> nav.replaceFolderPath(ancestry.take(i + 1).drop(if (folderId == null || roots.size == 1) 1 else 0).map { it.id }) }
        }
        Tabs(tabs.map { TAB_LABELS.getValue(it) }, tabs.indexOf(tab)) { actions.tab(it) }
        if (scan.phase != Scanner.Phase.IDLE) ScanLine(scan)
        Box(Modifier.weight(1f)) {
            when (tab) {
                "folders" -> FolderContent(effectiveFolder, roots, ui, actions)
                "albums" -> AlbumsTab(actions)
                "artists" -> ArtistsTab(actions)
                "songs" -> SongsTab(ui, actions)
                "playlists" -> PlaylistsTab(actions)
            }
        }
    }
}

/**
 * Swipe in from the left edge (the finger moving right) to go up one folder: the folder follows the finger, then goes
 * past a third of the width or on a fling, else springs back. The edge strip is claimed before the rows see the touch,
 * so song rows keep their own swipes (right: play next, left: add to queue) and a vertical move still scrolls.
 */
private fun Modifier.edgeSwipeUp(offset: Animatable<Float, AnimationVector1D>, scope: CoroutineScope, onUp: () -> Unit): Modifier =
    pointerInput(Unit) {
        val edge = 40.dp.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > edge) return@awaitEachGesture
            val velocity = VelocityTracker()
            var dragging = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val dx = change.position.x - down.position.x
                if (!dragging) {
                    if (abs(change.position.y - down.position.y) > slop && abs(change.position.y - down.position.y) >= abs(dx)) return@awaitEachGesture
                    if (dx <= slop) continue
                    dragging = true
                }
                change.consume()
                velocity.addPosition(change.uptimeMillis, change.position)
                scope.launch { offset.snapTo((dx - slop).coerceAtLeast(0f)) }
            }
            if (!dragging) return@awaitEachGesture
            if (offset.value > size.width / 3f || velocity.calculateVelocity().x > 1200f) onUp()
            else scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f)) }
        }
    }

@Composable
private fun ScanLine(p: Scanner.Progress) {
    val c = Tf.colors
    val frac = if (p.toProbe > 0) p.probed.toFloat() / p.toProbe else 0f
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.surface3)) {
            Box(Modifier.fillMaxWidth(if (p.phase == Scanner.Phase.WALKING) 0.15f else frac.coerceIn(0.02f, 1f)).height(2.dp).background(c.accent))
        }
        T(if (p.phase == Scanner.Phase.WALKING) "Looking for music · ${p.found} files" else "Reading tags ${p.probed} / ${p.toProbe}",
            Tf.type.caption, c.textSecondary, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@UnstableApi
@Composable
private fun FolderContent(folderId: Long?, roots: List<Folder>, ui: PlayerUi, actions: UiActions) {
    val g = LocalGraph.current
    val c = Tf.colors
    val scope = rememberCoroutineScope()
    val content by remember(folderId) { g.library.childrenFlow(folderId) }.collectAsState(null)
    val q by g.queue.state.collectAsState()
    val currentTrackId = q.current?.trackId
    val ch = content ?: return
    if (folderId == null && roots.isEmpty()) {
        EmptyState(TfIcons.Library, "No music found", "Choose the folders that hold your music, or wait for the scan to finish.",
            "Choose folders" to { actions.open(Dest.Folders) })
        return
    }
    val listState = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = ContentPadding) {
        val f = ch.folder
        if (f != null) item("head") {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Play", TfIcons.Play, primary = true) { playFolder(g, scope, f, shuffle = false) }
                PillButton("Shuffle", TfIcons.Shuffle, primary = false) { playFolder(g, scope, f, shuffle = true) }
                Spacer(Modifier.weight(1f))
                T("${plural(f.tracksTotal, "song")} · ${formatTotal(f.durationTotalMs)}", Tf.type.caption, c.textSecondary)
            }
        }
        items(ch.folders, key = { "f${it.id}" }, contentType = { "folder" }) { sub ->
            ListRow(
                title = sub.name,
                subtitle = if (sub.subfolderCount > 0) "${plural(sub.subfolderCount, "folder")} · ${plural(sub.tracksTotal, "song")}" else "${plural(sub.tracksTotal, "song")} · ${formatTotal(sub.durationTotalMs)}",
                art = { ArtImage(ArtKey.Folder(sub.id), sub.name, Modifier.fillMaxSize(), fallbackIcon = TfIcons.Folder) },
                titleColor = if (sub.available) c.text else c.textTertiary,
                onLongClick = { actions.sheet(SheetKind.FolderActions(sub)) },
            ) { actions.open(Dest.Folder(sub.id)) }
        }
        items(ch.tracks, key = { "t${it.id}" }, contentType = { "track" }) { t ->
            SwipeRow(onPlayNext = { g.queue.insertNext(listOf(t.id)) }, onQueue = { g.queue.append(listOf(t.id)) }) {
                TrackRow(t, t.id == currentTrackId, ui, onLongClick = { actions.sheet(SheetKind.TrackActions(t)) }) {
                    playFromFolder(g, scope, ch, t)
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun TrackRow(t: Track, current: Boolean, ui: PlayerUi, showNumber: Boolean = false, onLongClick: () -> Unit, onClick: () -> Unit) {
    val names = dev.trueform.player.ui.components.LocalShowFileNames.current
    val c = Tf.colors
    ListRow(
        title = if (names) t.baseName else t.displayTitle,
        subtitle = listOf(t.displayArtist.ifBlank { null }, formatDuration(t.durationMs)).filterNotNull().joinToString(" · "),
        art = {
            if (showNumber && t.trackNo > 0) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                T("${t.trackNo}", Tf.type.numeric, if (current) c.accent else c.textSecondary)
            } else ArtImage(ArtKey.Track(t.id), t.album.ifBlank { t.displayTitle }, Modifier.fillMaxSize())
        },
        highlighted = current,
        titleColor = when { current -> c.accent; !t.available || t.metaState == 2 -> c.textTertiary; else -> c.text },
        trailing = {
            if (current) PlayingBars(ui.isPlaying)
            t.rowPill?.let { FormatPill(it) }
        },
        onLongClick = onLongClick,
        onClick = onClick,
    )
}

/** Swipe right: play next; left: add to queue (docs/plan/04-ui-ux.md). */
@Composable
fun SwipeRow(onPlayNext: () -> Unit, onQueue: () -> Unit, content: @Composable () -> Unit) {
    if (!dev.trueform.player.ui.components.LocalSwipeToQueue.current) { content(); return }
    val c = Tf.colors
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var widthPx by remember { mutableStateOf(1f) }
    Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }) {
        if (offset.value != 0f) {
            Row(Modifier.matchParentSize().background(if (offset.value > 0) c.accentDim else c.surface3).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (offset.value > 0) Arrangement.Start else Arrangement.End) {
                Icon(if (offset.value > 0) TfIcons.PlayNext else TfIcons.PlaylistAdd, null, tint = if (offset.value > 0) c.accent else c.text)
                Spacer(Modifier.size(8.dp))
                T(if (offset.value > 0) "Play next" else "Add to queue", Tf.type.label, if (offset.value > 0) c.accent else c.text)
            }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.background(c.bg)
            .draggable(rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(-widthPx * 0.5f, widthPx * 0.5f)) } },
                Orientation.Horizontal,
                onDragStopped = {
                    val v = offset.value
                    if (abs(v) > widthPx * 0.25f) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        if (v > 0) onPlayNext() else onQueue()
                    }
                    offset.animateTo(0f, tween(180))
                })) { content() }
    }
}

// --- play helpers ----------------------------------------------------------------------------------------------------

private fun playFolder(g: AppGraph, scope: kotlinx.coroutines.CoroutineScope, f: Folder, shuffle: Boolean) {
    scope.launch {
        val ids = withContext(Dispatchers.IO) { g.library.tracksUnder(f.id, recursive = true) }
        if (ids.isEmpty()) return@launch
        g.controller()
        g.queue.play(ids, if (shuffle) (ids.indices).random() else 0, QueueSource.Folder(f.id, true, f.id), shuffle = shuffle)
    }
}

@UnstableApi
private fun playFromFolder(g: AppGraph, scope: kotlinx.coroutines.CoroutineScope, ch: FolderChildren, t: Track) {
    val f = ch.folder ?: return
    scope.launch {
        val mode = g.settings[S.tapPlays]
        val (ids, source) = withContext(Dispatchers.IO) {
            when (mode) {
                TapPlays.FOLDER -> ch.tracks.map { it.id } to QueueSource.Folder(f.id, false, f.id)
                TapPlays.FOLDER_RECURSIVE -> g.library.tracksUnder(f.id, recursive = true) to QueueSource.Folder(f.id, true, f.id)
                TapPlays.LIBRARY -> g.library.libraryOrder() to QueueSource.Songs
            }
        }
        g.controller()
        g.queue.play(ids, ids.indexOf(t.id).coerceAtLeast(0), source)
    }
}

// --- other tabs ------------------------------------------------------------------------------------------------------

@Composable
private fun AlbumsTab(actions: UiActions) {
    val g = LocalGraph.current
    val c = Tf.colors
    val albums by remember { g.library.albumsFlow() }.collectAsState(null)
    val list = albums ?: return
    if (list.isEmpty()) { EmptyState(TfIcons.Album, "No albums", "Albums appear when your files carry album tags."); return }
    LazyVerticalGrid(GridCells.Adaptive(156.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(list, key = { it.key }) { a ->
            Column(Modifier.clip(RoundedCornerShape(14.dp)).tfClickable(label = a.title) { actions.open(Dest.Album(a.key)) }.padding(4.dp)) {
                ArtImage(ArtKey.Track(a.coverTrackId), a.title, Modifier.fillMaxWidth().aspectRatio(1f), radius = 14.dp)
                T(a.title, Tf.type.label.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                T(a.artist.ifBlank { "${a.trackCount} songs" }, Tf.type.caption, c.textSecondary)
            }
        }
    }
}

@Composable
private fun ArtistsTab(actions: UiActions) {
    val g = LocalGraph.current
    val artists by remember { g.library.artistsFlow() }.collectAsState(null)
    val list = artists ?: return
    if (list.isEmpty()) { EmptyState(TfIcons.Artist, "No artists", "Artists appear when your files carry artist tags."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ContentPadding) {
        items(list, key = { it.name }) { a ->
            ListRow(a.name, "${a.albumCount} albums · ${a.trackCount} songs",
                art = { ArtImage(ArtKey.Track(a.coverTrackId), a.name, Modifier.fillMaxSize(), radius = 22.dp) }) {
                actions.open(Dest.Artist(a.name))
            }
        }
    }
}

@UnstableApi
@Composable
private fun SongsTab(ui: PlayerUi, actions: UiActions) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    val songs by remember { g.library.allTracksFlow() }.collectAsState(null)
    val q by g.queue.state.collectAsState()
    val list = songs ?: return
    if (list.isEmpty()) { EmptyState(TfIcons.Note, "No songs yet", "Songs appear here as the scan finds them."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ContentPadding) {
        item("head") {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Shuffle all", TfIcons.Shuffle, primary = true) {
                    g.controller(); g.queue.play(list.map { it.id }, list.indices.random(), QueueSource.Songs, shuffle = true)
                }
                Spacer(Modifier.weight(1f))
                T("${list.size} songs", Tf.type.caption, Tf.colors.textSecondary)
            }
        }
        items(list, key = { it.id }, contentType = { "track" }) { t ->
            SwipeRow(onPlayNext = { g.queue.insertNext(listOf(t.id)) }, onQueue = { g.queue.append(listOf(t.id)) }) {
                TrackRow(t, t.id == q.current?.trackId, ui, onLongClick = { actions.sheet(SheetKind.TrackActions(t)) }) {
                    scope.launch { g.controller(); g.queue.play(list.map { it.id }, list.indexOf(t), QueueSource.Songs) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(actions: UiActions) {
    val g = LocalGraph.current
    val c = Tf.colors
    val playlists by remember { g.library.playlistsFlow() }.collectAsState(null)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ContentPadding) {
        item { SectionTitle("SMART LISTS") }
        items(SmartList.entries) { s ->
            ListRow(s.title, when (s) { SmartList.RECENTLY_ADDED -> "Newest files first"; SmartList.MOST_PLAYED -> "By play count"; SmartList.RECENTLY_PLAYED -> "Last played first" },
                art = { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                    Icon(when (s) { SmartList.RECENTLY_ADDED -> TfIcons.Add; SmartList.MOST_PLAYED -> TfIcons.Star; SmartList.RECENTLY_PLAYED -> TfIcons.History }, null, tint = c.textSecondary)
                } }) { actions.open(Dest.Smart(s)) }
        }
        item { SectionTitle("PLAYLISTS") }
        items(playlists.orEmpty(), key = { it.id }) { p ->
            ListRow(p.name, "${p.trackCount} songs",
                art = { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                    Icon(TfIcons.PlaylistPlay, null, tint = c.textSecondary)
                } },
                onLongClick = { actions.sheet(SheetKind.PlaylistActions(p)) }) { actions.open(Dest.Playlist(p.id)) }
        }
        item {
            ListRow("New playlist", "", art = { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(c.accentDim), contentAlignment = Alignment.Center) {
                Icon(TfIcons.Add, null, tint = c.accent) } }, titleColor = c.accent) { actions.sheet(SheetKind.NewPlaylist(emptyList())) }
        }
    }
}

// --- detail screens --------------------------------------------------------------------------------------------------

@UnstableApi
@Composable
fun DetailScreen(dest: Dest, ui: PlayerUi, actions: UiActions) {
    val g = LocalGraph.current
    val c = Tf.colors
    val scope = rememberCoroutineScope()
    val version by g.db.changes(dev.trueform.player.library.Db.Group.PLAYLISTS).collectAsState()
    val tracks by produceState<List<Track>?>(null, dest, version) {
        value = withContext(Dispatchers.IO) {
            when (dest) {
                is Dest.Album -> g.library.albumTracks(dest.key)
                is Dest.Artist -> g.library.artistTracks(dest.name)
                is Dest.Playlist -> g.library.playlistTracks(dest.id)
                is Dest.Smart -> g.library.smart(dest.list)
                else -> emptyList()
            }
        }
    }
    val playlistName by produceState("", dest) {
        value = if (dest is Dest.Playlist) withContext(Dispatchers.IO) { g.library.playlists().firstOrNull { it.id == dest.id }?.name ?: "Playlist" } else ""
    }
    val q by g.queue.state.collectAsState()
    val list = tracks
    val title = when (dest) {
        is Dest.Album -> list?.firstOrNull()?.album ?: ""
        is Dest.Artist -> dest.name
        is Dest.Playlist -> playlistName
        is Dest.Smart -> dest.list.title
        else -> ""
    }
    val source: QueueSource = when (dest) {
        is Dest.Album -> QueueSource.Album(dest.key)
        is Dest.Artist -> QueueSource.Artist(dest.name)
        is Dest.Playlist -> QueueSource.Playlist(dest.id)
        else -> QueueSource.Mixed
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(TfIcons.Back, "Back") { actions.back() }
            T(title, Tf.type.title, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (dest is Dest.Playlist) IconBtn(TfIcons.More, "Playlist options") {
                actions.sheet(SheetKind.PlaylistActions(dev.trueform.player.library.Playlist(dest.id, playlistName, list?.size ?: 0)))
            }
        }
        if (list == null) return@Column
        if (list.isEmpty()) {
            EmptyState(TfIcons.Note, "Nothing here yet", if (dest is Dest.Playlist) "Add songs with a long press on any song." else "Play some music first.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ContentPadding) {
            item("header") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ArtImage(ArtKey.Track(list.first().id), title, Modifier.size(112.dp), radius = 14.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        T(title, Tf.type.display, maxLines = 2)
                        val sub = when (dest) {
                            is Dest.Album -> listOfNotNull(list.first().albumArtist.ifBlank { list.first().artist }.ifBlank { null }, list.first().year.takeIf { it > 0 }?.toString()).joinToString(" · ")
                            else -> ""
                        }
                        if (sub.isNotEmpty()) T(sub, Tf.type.body, c.textSecondary)
                        T("${list.size} songs · ${formatTotal(list.sumOf { it.durationMs })}", Tf.type.caption, c.textSecondary)
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Play", TfIcons.Play, primary = true) { g.controller(); g.queue.play(list.map { it.id }, 0, source, shuffle = false) }
                    PillButton("Shuffle", TfIcons.Shuffle, primary = false) { g.controller(); g.queue.play(list.map { it.id }, list.indices.random(), source, shuffle = true) }
                }
                Spacer(Modifier.height(8.dp))
            }
            items(list, key = { it.id }) { t ->
                SwipeRow(onPlayNext = { g.queue.insertNext(listOf(t.id)) }, onQueue = { g.queue.append(listOf(t.id)) }) {
                    TrackRow(t, t.id == q.current?.trackId, ui, showNumber = dest is Dest.Album,
                        onLongClick = { actions.sheet(SheetKind.TrackActions(t, playlistId = (dest as? Dest.Playlist)?.id)) }) {
                        scope.launch { g.controller(); g.queue.play(list.map { it.id }, list.indexOf(t), source) }
                    }
                }
            }
        }
    }
}
