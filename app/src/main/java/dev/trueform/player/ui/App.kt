package dev.trueform.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.AppGraph
import dev.trueform.player.library.SmartList
import dev.trueform.player.library.Track
import dev.trueform.player.settings.Category
import dev.trueform.player.settings.Density
import dev.trueform.player.settings.S
import dev.trueform.player.ui.components.LocalGraph
import dev.trueform.player.ui.components.LocalRowHeight
import dev.trueform.player.ui.eq.EqScreen
import dev.trueform.player.ui.library.DetailScreen
import dev.trueform.player.ui.library.LibraryScreen
import dev.trueform.player.ui.onboarding.FolderPicker
import dev.trueform.player.ui.player.MiniPlayer
import dev.trueform.player.ui.player.NowPlaying
import dev.trueform.player.ui.player.PlayerUi
import dev.trueform.player.ui.search.SearchScreen
import dev.trueform.player.ui.settings.SettingsScreen
import dev.trueform.player.ui.sheets.SheetHost
import dev.trueform.player.ui.sheets.SheetKind
import dev.trueform.player.ui.theme.LocalHaptics
import dev.trueform.player.ui.theme.Tf
import dev.trueform.player.ui.theme.TrueformTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Navigation destinations (docs/plan/04-ui-ux.md section 3). Each folder level is its own entry. */
sealed interface Dest {
    data object Library : Dest
    data class Folder(val id: Long) : Dest
    data class Album(val key: String) : Dest
    data class Artist(val name: String) : Dest
    data class Playlist(val id: Long) : Dest
    data class Smart(val list: SmartList) : Dest
    data object Search : Dest
    data class Settings(val category: Category? = null) : Dest
    data object Equalizer : Dest
    data object Folders : Dest
}

@Stable
class Nav {
    val stack = mutableStateListOf<Dest>(Dest.Library)
    var tab by mutableStateOf(0)
    var forward by mutableStateOf(true)
    /** Entries opened from the expanded player: popping them opens the player again. */
    val fromPlayer = HashSet<Dest>()
    val top get() = stack.last()
    fun push(d: Dest) { forward = true; stack.add(d) }
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }
    fun popToRoot() { forward = false; while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replaceFolderPath(ids: List<Long>) {
        forward = false
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        ids.forEach { stack.add(Dest.Folder(it)) }
    }
}

@UnstableApi
@Composable
fun AppRoot(g: AppGraph, needsSetup: Boolean, onSetupDone: () -> Unit, requestPermission: () -> Unit) {
    val theme by g.settings.flow(S.theme).collectAsState(g.settings[S.theme])
    val haptics by g.settings.flow(S.haptics).collectAsState(g.settings[S.haptics])
    val density by g.settings.flow(S.density).collectAsState(g.settings[S.density])
    val fileNames by g.settings.flow(S.showFileNames).collectAsState(g.settings[S.showFileNames])
    val swipeToQueue by g.settings.flow(S.swipeToQueue).collectAsState(g.settings[S.swipeToQueue])
    TrueformTheme(theme) {
        val dark = Tf.colors.isDark
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (view.context as android.app.Activity).window
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        CompositionLocalProvider(LocalGraph provides g, LocalHaptics provides haptics,
            LocalRowHeight provides if (density == Density.COMPACT) 48.dp else 60.dp,
            dev.trueform.player.ui.components.LocalShowFileNames provides fileNames,
            dev.trueform.player.ui.components.LocalSwipeToQueue provides swipeToQueue) {
            Box(Modifier.fillMaxSize().background(Tf.colors.bg)) {
                if (needsSetup) dev.trueform.player.ui.onboarding.Onboarding(requestPermission, onSetupDone)
                else Main(g)
            }
        }
    }
}

@UnstableApi
@Composable
private fun Main(g: AppGraph) {
    val nav = remember { Nav() }
    val scope = rememberCoroutineScope()
    val ui = remember { PlayerUi(g, scope) }
    val expand = remember { Animatable(0f) }
    var sheet by remember { mutableStateOf<SheetKind?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(Unit) { g.queue.messages.collect { snackbar.showSnackbar(it) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {   // a crash last time: offer the report once (docs/plan/11-testing-hardening.md hardening 1)
        val f = dev.trueform.player.system.Logs.crashFile(context)
        val at = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (f.exists()) f.lastModified() else 0L }
        if (at > g.settings.privateLong("crash_seen")) {
            g.settings.putPrivateLong("crash_seen", at)
            sheet = SheetKind.Message("Trueform closed unexpectedly",
                "A report with the error and what was playing is ready. Nothing leaves the phone unless you share it.",
                "Share report") { dev.trueform.player.system.Logs.runAction(g, S.exportLogs.key, context) }
        }
    }

    fun openPlayer() = scope.launch { expand.animateTo(1f, spring(0.85f, 380f)) }
    fun closePlayer() = scope.launch { expand.animateTo(0f, spring(0.85f, 380f)) }
    fun pushFromPlayer(d: Dest) {
        nav.push(d)
        if (expand.value > 0.5f) { nav.fromPlayer += d; closePlayer() }
    }
    fun back() {
        when {
            sheet != null -> sheet = null
            expand.value > 0.5f -> closePlayer()
            else -> {
                val top = nav.top
                if (nav.pop() && top in nav.fromPlayer) { nav.fromPlayer -= top; openPlayer() }
            }
        }
    }
    // Read the player's position through these (or in layout and drawing): reading expand.value here would recompose
    // the whole app on every frame of opening, closing or dragging the player.
    val playerOpen by remember { derivedStateOf { expand.value > 0.5f } }
    val playerShown by remember { derivedStateOf { expand.value > 0.001f } }
    BackHandler(enabled = sheet != null || playerOpen || nav.stack.size > 1) { back() }

    val actions = remember {
        object : UiActions {
            override fun open(d: Dest) = nav.push(d)
            override fun openFromPlayer(d: Dest) = pushFromPlayer(d)
            override fun back() = back()
            override fun sheet(k: SheetKind) { sheet = k }
            override fun closeSheet() { sheet = null }
            override fun expandPlayer() { openPlayer() }
            override fun collapsePlayer() { closePlayer() }
            override fun tab(i: Int) { nav.tab = i; nav.popToRoot() }
            override fun goToFolderOf(t: Track) {
                scope.launch {
                    val ids = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { g.library.ancestry(t.folderId).map { it.id } }
                    nav.tab = 0
                    nav.replaceFolderPath(ids)
                    closePlayer()
                }
            }
        }
    }

    val player: @Composable () -> Unit = {
        NowPlaying(
            ui,
            onCollapse = { closePlayer() },
            onQueue = { sheet = SheetKind.Queue },
            onEq = { pushFromPlayer(Dest.Equalizer) },
            onTimer = { sheet = SheetKind.Timer },
            onMore = { ui.track?.let { sheet = SheetKind.TrackActions(it, fromPlayer = true) } },
            onOutput = { sheet = SheetKind.Output },
            onInfo = { ui.track?.let { sheet = SheetKind.Info(it) } },
            onGoToSource = { ui.track?.let { actions.goToFolderOf(it) } },
            castButton = { dev.trueform.player.ui.sheets.CastButton(actions) },
        )
    }

    // a phone on its side puts the navigation bar (and a camera cutout) at the side: keep content out of it
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val fullPx = with(LocalDensity.current) { maxHeight.toPx() }
        // Wide screens (tablets, docked or landscape): the library and a player panel that stays open (04-ui-ux.md)
        val panel = minOf(480.dp, maxWidth * 0.42f)
        val wide = maxWidth >= 840.dp && maxHeight >= 600.dp && ui.hasMedia && ui.track != null   // not a phone on its side
        Row(Modifier.fillMaxSize()) {
        // not drawn while the open player covers it, and not offered to accessibility (TalkBack would reach hidden rows,
        // and any accessibility service, a launcher's gestures included, makes Compose compare these nodes on changes)
        Column(Modifier.weight(1f).fillMaxHeight().graphicsLayer { alpha = if (!wide && expand.value > 0.999f) 0f else 1f }
            .then(if (playerOpen && !wide) Modifier.clearAndSetSemantics {} else Modifier)) {
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(dev.trueform.player.ui.components.LocalCovered provides (playerOpen && !wide)) {
                AnimatedContent(
                    nav.top,
                    transitionSpec = {
                        val dir = if (nav.forward) 1 else -1
                        (slideInHorizontally(tween(220)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(220)) { -it / 6 * dir } + fadeOut(tween(160)))
                    },
                    label = "nav",
                ) { dest ->
                    holder.SaveableStateProvider(dest.toString()) {
                        when (dest) {
                            Dest.Library -> LibraryScreen(nav, null, ui, actions)
                            is Dest.Folder -> LibraryScreen(nav, dest.id, ui, actions)
                            is Dest.Album, is Dest.Artist, is Dest.Playlist, is Dest.Smart -> DetailScreen(dest, ui, actions)
                            Dest.Search -> SearchScreen(ui, actions)
                            is Dest.Settings -> SettingsScreen(dest.category, actions)
                            Dest.Equalizer -> EqScreen(actions)
                            Dest.Folders -> FolderPicker(onDone = { actions.back() }, onBack = { actions.back() })
                        }
                    }
                }
                }
            }
            if (ui.hasMedia && ui.track != null && !wide) {
                Box(Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - expand.value }.draggable(
                    rememberDraggableState { d -> scope.launch { expand.snapTo((expand.value - d / fullPx).coerceIn(0f, 1f)) } },
                    Orientation.Vertical,
                    onDragStopped = { v -> if (v < -600f || expand.value > 0.25f) openPlayer() else closePlayer() },
                )) {
                    MiniPlayer(ui, onExpand = { openPlayer() })
                }
            }
            Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(Tf.colors.surface1))
        }
        if (wide) Box(Modifier.width(panel).fillMaxHeight().background(Tf.colors.surface1)) { player() }
        }
        if (playerShown && !wide) {
            Box(Modifier.fillMaxSize()
                .offset { IntOffset(0, ((1f - expand.value) * fullPx).roundToInt()) }
                .draggable(
                    rememberDraggableState { d -> scope.launch { expand.snapTo((expand.value - d / fullPx).coerceIn(0f, 1f)) } },
                    Orientation.Vertical,
                    onDragStopped = { v -> if (v > 600f || expand.value < 0.75f) closePlayer() else openPlayer() },
                )) {
                player()
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }
    sheet?.let { SheetHost(it, ui, actions) }
}

/** What screens may ask of the shell. */
interface UiActions {
    fun open(d: Dest)
    fun openFromPlayer(d: Dest)
    fun back()
    fun sheet(k: SheetKind)
    fun closeSheet()
    fun expandPlayer()
    fun collapsePlayer()
    fun tab(i: Int)
    fun goToFolderOf(t: Track)
}
