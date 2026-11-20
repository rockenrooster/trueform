package dev.trueform.player.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.library.LibraryRepo
import dev.trueform.player.playback.QueueSource
import dev.trueform.player.ui.Dest
import dev.trueform.player.ui.UiActions
import dev.trueform.player.ui.components.ArtImage
import dev.trueform.player.ui.components.ArtKey
import dev.trueform.player.ui.components.Chip
import dev.trueform.player.ui.components.ContentPadding
import dev.trueform.player.ui.components.IconBtn
import dev.trueform.player.ui.components.ListRow
import dev.trueform.player.ui.components.LocalGraph
import dev.trueform.player.ui.components.SectionTitle
import dev.trueform.player.ui.components.T
import dev.trueform.player.ui.components.TfIcons
import dev.trueform.player.ui.library.TrackRow
import dev.trueform.player.ui.player.PlayerUi
import dev.trueform.player.ui.sheets.SheetKind
import dev.trueform.player.ui.theme.Tf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@UnstableApi
@Composable
fun SearchScreen(ui: PlayerUi, actions: UiActions) {
    val g = LocalGraph.current
    val c = Tf.colors
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val recent = remember { mutableStateOf((g.settings.privateString("recent_searches") ?: "").split('\n').filter { it.isNotBlank() }) }
    fun saveRecent(q: String) {
        val l = (listOf(q) + recent.value.filter { it != q }).take(8)
        recent.value = l
        g.settings.putPrivateString("recent_searches", l.joinToString("\n"))
    }
    val result by produceState<LibraryRepo.SearchResult?>(null, text) {
        delay(150)
        value = if (text.isBlank()) null else withContext(Dispatchers.IO) { g.library.search(text) }
    }
    val q by g.queue.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(TfIcons.Back, "Back") { actions.back() }
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(c.surface2).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) T("Songs, albums, artists, folders", Tf.type.body, c.textTertiary)
                BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = Tf.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent))
            }
            if (text.isNotEmpty()) IconBtn(TfIcons.Close, "Clear") { text = "" }
        }
        val r = result
        if (r == null) {
            if (recent.value.isNotEmpty()) {
                SectionTitle("RECENT")
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in recent.value) Chip(s, false) { text = s }
                }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ContentPadding) {
            if (r.tracks.isNotEmpty()) {
                item { SectionTitle("SONGS") }
                items(r.tracks, key = { "t${it.id}" }) { t ->
                    TrackRow(t, t.id == q.current?.trackId, ui, onLongClick = { actions.sheet(SheetKind.TrackActions(t)) }) {
                        saveRecent(text.trim())
                        g.controller()
                        g.queue.play(r.tracks.map { it.id }, r.tracks.indexOf(t), QueueSource.Mixed)
                    }
                }
            }
            if (r.albums.isNotEmpty()) {
                item { SectionTitle("ALBUMS") }
                items(r.albums, key = { "a${it.key}" }) { a ->
                    ListRow(a.title, a.artist, art = { ArtImage(ArtKey.Track(a.coverTrackId), a.title, Modifier.fillMaxSize()) }) {
                        saveRecent(text.trim()); actions.open(Dest.Album(a.key))
                    }
                }
            }
            if (r.artists.isNotEmpty()) {
                item { SectionTitle("ARTISTS") }
                items(r.artists, key = { "r${it.name}" }) { a ->
                    ListRow(a.name, "${a.trackCount} songs", art = { ArtImage(ArtKey.Track(a.coverTrackId), a.name, Modifier.fillMaxSize(), radius = 22.dp) }) {
                        saveRecent(text.trim()); actions.open(Dest.Artist(a.name))
                    }
                }
            }
            if (r.folders.isNotEmpty()) {
                item { SectionTitle("FOLDERS") }
                items(r.folders, key = { "f${it.id}" }) { f ->
                    ListRow(f.name, "${f.tracksTotal} songs", art = { ArtImage(ArtKey.Folder(f.id), f.name, Modifier.fillMaxSize(), fallbackIcon = TfIcons.Folder) }) {
                        saveRecent(text.trim()); actions.open(Dest.Folder(f.id))
                    }
                }
            }
            if (r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && r.folders.isEmpty()) item {
                T("Nothing matches “$text”.", Tf.type.body, c.textSecondary, Modifier.padding(20.dp))
            }
        }
    }
}
