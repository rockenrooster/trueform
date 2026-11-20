package dev.trueform.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.trueform.player.AppGraph
import dev.trueform.player.MainActivity
import dev.trueform.player.R
import dev.trueform.player.TrueformApp
import dev.trueform.player.library.LibraryRepo
import dev.trueform.player.library.SmartList
import dev.trueform.player.library.Track
import dev.trueform.player.settings.NotificationButtons
import dev.trueform.player.settings.S
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The media session and its library tree (docs/plan/02-playback-engine.md T6, 08-bluetooth-system.md T1-T2). The
 * players live in [AppGraph]; this service owns only the session, so it can come and go around them.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var g: AppGraph

    override fun onCreate() {
        super.onCreate()
        g = (application as TrueformApp).graph
        val provider = DefaultMediaNotificationProvider.Builder(this).build()
        provider.setSmallIcon(R.drawable.ic_stat_trueform)
        setMediaNotificationProvider(provider)
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, g.sessionPlayer, Callback())
            .setSessionActivity(activity)
            .build()
        g.main.launch {
            combine(g.queue.state, g.settings.flow(S.notificationButtons)) { s, b -> Triple(s.shuffle, s.repeat, b) }
                .collect { (shuffle, repeat, buttons) -> updateButtons(shuffle, repeat, buttons) }
        }
        Log.i(TAG, "service created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * No automatic restart after the process dies: the restarted service would have nothing playing, miss its
     * foreground deadline and end in an ANR (seen on the Moto). A media button or the app starts it again.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player ?: return
        if (!p.playWhenReady || p.mediaItemCount == 0 || g.settings[S.stopOnSwipe]) {
            p.pause()
            g.queue.savePosition()
            stopSelf()
        }
    }

    override fun onDestroy() {
        g.queue.savePosition()
        session?.release()
        session = null
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    private fun updateButtons(shuffle: Boolean, repeat: Repeat, buttons: NotificationButtons) {
        val s = session ?: return
        if (buttons == NotificationButtons.NONE) {
            s.setMediaButtonPreferences(emptyList())
            return
        }
        val shuffleButton = CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(if (shuffle) "Shuffle on" else "Shuffle off")
            .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
        val repeatButton = CommandButton.Builder(when (repeat) {
            Repeat.OFF -> CommandButton.ICON_REPEAT_OFF; Repeat.ALL -> CommandButton.ICON_REPEAT_ALL; Repeat.ONE -> CommandButton.ICON_REPEAT_ONE
        })
            .setDisplayName(when (repeat) { Repeat.OFF -> "Repeat off"; Repeat.ALL -> "Repeat all"; Repeat.ONE -> "Repeat one" })
            .setSessionCommand(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
        s.setMediaButtonPreferences(listOf(shuffleButton, repeatButton))
    }

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                     customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SHUFFLE -> g.queue.setShuffle(!g.queue.state.value.shuffle)
                CMD_REPEAT -> g.queue.cycleRepeat()
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                          isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val p = g.corePlayer
            if (p.mediaItemCount == 0) g.queue.restore()
            if (p.mediaItemCount == 0) return Futures.immediateFailedFuture(UnsupportedOperationException("nothing to resume"))
            val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, p.currentMediaItemIndex, p.currentPosition))
        }

        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                     mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long):
            ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val first = mediaItems.getOrNull(if (startIndex >= 0) startIndex else 0)
            val resolved = first?.let { tree.resolvePlay(it.mediaId) }
            if (resolved != null) {
                val (ids, index, source) = resolved
                g.queue.play(ids, index, source, playWhenReady = false)
                val p = g.corePlayer
                val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, p.currentMediaItemIndex, 0))
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                     mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val ids = mediaItems.flatMap { tree.resolvePlay(it.mediaId)?.first?.let { l -> listOfNotNull(l.getOrNull(tree.indexIn(it.mediaId, l))) } ?: emptyList() }
            if (ids.isNotEmpty()) g.queue.append(ids)
            return Futures.immediateFuture(mutableListOf())
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                                      params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(tree.node(LibraryTree.ROOT, "Trueform"), params))

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                               mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = tree.item(mediaId) ?: return Futures.immediateFuture(LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                                   page: Int, pageSize: Int, params: LibraryParams?):
            ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = tree.children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(all.subList(from, to), params))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                              params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            val n = g.library.search(query).tracks.size
            session.notifySearchResultChanged(browser, query, n, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                                       page: Int, pageSize: Int, params: LibraryParams?):
            ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val tracks = g.library.search(query).tracks
            val ids = tracks.map { it.id }
            val all = tracks.map { tree.trackItem(it, "search:" + LibraryTree.enc(query)) }
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            tree.rememberSearch(query, ids)
            return Futures.immediateFuture(LibraryResult.ofItemList(all.subList(from, to), params))
        }
    }

    private val tree by lazy { LibraryTree(this, g.library) }

    companion object {
        private const val TAG = "TFSVC"
        const val CMD_SHUFFLE = "tf.shuffle"
        const val CMD_REPEAT = "tf.repeat"
    }
}

/**
 * The browse tree for Bluetooth browsing and Android Auto (docs/plan/08-bluetooth-system.md T2). Track ids carry
 * their context ("track:<id>@folder:<fid>") so "play this" means "play this list from here".
 */
class LibraryTree(private val context: android.content.Context, private val library: LibraryRepo) {
    private val searches = HashMap<String, List<Long>>()

    fun rememberSearch(q: String, ids: List<Long>) { searches[enc(q)] = ids }

    fun node(id: String, title: String, subtitle: String? = null, artUri: android.net.Uri? = null,
             type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtworkUri(artUri)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(type).build()).build()

    fun trackItem(t: Track, ctx: String): MediaItem = QueueController.mediaItem(context, t, "track:${t.id}@$ctx")

    fun item(id: String): MediaItem? = when {
        id == ROOT -> node(ROOT, "Trueform")
        id.startsWith("track:") -> id.substringAfter("track:").substringBefore("@").toLongOrNull()?.let { library.track(it) }
            ?.let { trackItem(it, id.substringAfter("@", "")) }
        else -> children(ROOT).firstOrNull { it.mediaId == id } ?: node(id, id)
    }

    fun children(parent: String): List<MediaItem> = when {
        parent == ROOT -> listOf(
            node(FOLDERS, "Folders"), node(ALBUMS, "Albums", type = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            node(ARTISTS, "Artists", type = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            node(PLAYLISTS, "Playlists", type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS), node(RECENT, "Recently added"))
        parent == FOLDERS -> library.roots().map { folderNode(it) }
        parent.startsWith("folder:") -> {
            val id = parent.removePrefix("folder:").toLongOrNull() ?: return emptyList()
            val c = library.children(id)
            c.folders.map { folderNode(it) } + c.tracks.map { trackItem(it, parent) }
        }
        parent == ALBUMS -> library.albums().map {
            node("album:" + enc(it.key), it.title, it.artist, dev.trueform.player.library.Artwork.trackUri(context, it.coverTrackId, 256),
                MediaMetadata.MEDIA_TYPE_ALBUM)
        }
        parent.startsWith("album:") -> library.albumTracks(dec(parent.removePrefix("album:"))).map { trackItem(it, parent) }
        parent == ARTISTS -> library.artists().map {
            node("artist:" + enc(it.name), it.name, "${it.trackCount} songs",
                dev.trueform.player.library.Artwork.trackUri(context, it.coverTrackId, 256), MediaMetadata.MEDIA_TYPE_ARTIST)
        }
        parent.startsWith("artist:") -> library.artistTracks(dec(parent.removePrefix("artist:"))).map { trackItem(it, parent) }
        parent == PLAYLISTS -> SmartList.entries.map { node("smart:${it.name}", it.title, type = MediaMetadata.MEDIA_TYPE_PLAYLIST) } +
            library.playlists().map { node("playlist:${it.id}", it.name, "${it.trackCount} songs", type = MediaMetadata.MEDIA_TYPE_PLAYLIST) }
        parent.startsWith("smart:") -> runCatching { SmartList.valueOf(parent.removePrefix("smart:")) }.getOrNull()
            ?.let { l -> library.smart(l).map { trackItem(it, parent) } } ?: emptyList()
        parent.startsWith("playlist:") -> parent.removePrefix("playlist:").toLongOrNull()
            ?.let { id -> library.playlistTracks(id).map { trackItem(it, parent) } } ?: emptyList()
        parent == RECENT -> library.smart(SmartList.RECENTLY_ADDED, 50).map { trackItem(it, "smart:RECENTLY_ADDED") }
        else -> emptyList()
    }

    private fun folderNode(f: dev.trueform.player.library.Folder) =
        node("folder:${f.id}", f.name, if (f.subfolderCount > 0) "${f.subfolderCount} folders · ${f.tracksTotal} songs" else "${f.tracksTotal} songs",
            android.net.Uri.parse("content://${context.packageName}.art/folder/${f.id}?s=256"))

    /** A track id with context → (the context's track ids, the track's index, the queue source). */
    fun resolvePlay(mediaId: String): Triple<List<Long>, Int, QueueSource?>? {
        if (!mediaId.startsWith("track:")) return null
        val tid = mediaId.substringAfter("track:").substringBefore("@").toLongOrNull() ?: return null
        val ctx = mediaId.substringAfter("@", "")
        val (ids, source) = when {
            ctx.startsWith("folder:") -> {
                val fid = ctx.removePrefix("folder:").toLong()
                library.tracksUnder(fid, recursive = false) to QueueSource.Folder(fid, false, fid)
            }
            ctx.startsWith("album:") -> library.albumTracks(dec(ctx.removePrefix("album:"))).map { it.id } to QueueSource.Album(dec(ctx.removePrefix("album:")))
            ctx.startsWith("artist:") -> library.artistTracks(dec(ctx.removePrefix("artist:"))).map { it.id } to QueueSource.Artist(dec(ctx.removePrefix("artist:")))
            ctx.startsWith("playlist:") -> library.playlistTracks(ctx.removePrefix("playlist:").toLong()).map { it.id } to QueueSource.Playlist(ctx.removePrefix("playlist:").toLong())
            ctx.startsWith("smart:") -> (runCatching { library.smart(SmartList.valueOf(ctx.removePrefix("smart:"))).map { it.id } }.getOrDefault(emptyList())) to QueueSource.Mixed
            ctx.startsWith("search:") -> (searches[ctx.removePrefix("search:")] ?: listOf(tid)) to QueueSource.Mixed
            else -> listOf(tid) to QueueSource.Mixed
        }
        val list = ids.ifEmpty { listOf(tid) }
        return Triple(list, list.indexOf(tid).coerceAtLeast(0), source)
    }

    fun indexIn(mediaId: String, ids: List<Long>): Int =
        ids.indexOf(mediaId.substringAfter("track:").substringBefore("@").toLongOrNull() ?: -1L)

    companion object {
        const val ROOT = "root"
        const val FOLDERS = "folders"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        const val RECENT = "recent"
        fun enc(s: String): String = Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        fun dec(s: String): String = runCatching { String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }.getOrDefault("")
    }
}
