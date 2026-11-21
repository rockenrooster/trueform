package dev.trueform.player.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.trueform.player.library.Artwork
import dev.trueform.player.library.LibraryRepo
import dev.trueform.player.library.Track
import dev.trueform.player.settings.AfterQueue
import dev.trueform.player.settings.S
import dev.trueform.player.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The logical queue and the player's window of it (docs/plan/02-playback-engine.md T5). Main thread only.
 * The player never holds more than BACK + 1 + AHEAD items; shuffle is ours, so it works on Chromecast too.
 */
class QueueController(
    private val context: Context,
    private val library: LibraryRepo,
    private val settings: Settings,
    private val resume: ResumeStore,
    private val scope: CoroutineScope,
) : Player.Listener {

    lateinit var player: Player
        private set
    private var nextEntryId = 1L
    private val model = QueueModel({ nextEntryId++ })
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    /** Called when logical shuffle changes (SessionPlayer reports it to controllers). */
    var onShuffleChanged: ((Boolean) -> Unit)? = null
    /** Called on every current-entry change with the current and next two track ids (waveform preparation). */
    var onCurrentChanged: ((List<Long>) -> Unit)? = null
    /** Window size after the current entry; Chromecast uses fewer (07-chromecast.md T4). */
    var ahead: () -> Int = { WindowPlanner.AHEAD }

    private val tracks = HashMap<Long, Track>()       // queue entries' tracks, for building items
    private var errorsInRow = 0
    private var countedEntry = -1L
    private var saveJob: Job? = null

    fun attach(p: Player) {
        if (::player.isInitialized) player.removeListener(this)
        player = p
        p.addListener(this)
    }

    // --- commands --------------------------------------------------------------------------------------------------

    fun play(trackIds: List<Long>, startIndex: Int, source: QueueSource?, shuffle: Boolean? = null,
             startPositionMs: Long = 0, playWhenReady: Boolean = true) {
        val s = model.play(trackIds, startIndex, shuffle ?: _state.value.shuffle, _state.value.repeat, source)
        publish(s)
        autoAdvanceCheck()
        sync(reset = true, positionMs = startPositionMs)
        player.playWhenReady = playWhenReady
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    fun setShuffle(on: Boolean) {
        val s = model.setShuffle(_state.value, on)
        if (s === _state.value) return
        publish(s)
        sync()
        onShuffleChanged?.invoke(on)
    }

    fun setRepeat(r: Repeat) {
        if (r == _state.value.repeat) return
        publish(_state.value.copy(repeat = r))
        player.repeatMode = r.toPlayer()
        autoAdvanceCheck()
        sync()
    }

    fun cycleRepeat() = setRepeat(when (_state.value.repeat) { Repeat.OFF -> Repeat.ALL; Repeat.ALL -> Repeat.ONE; Repeat.ONE -> Repeat.OFF })

    fun insertNext(trackIds: List<Long>) {
        val wasEmpty = _state.value.isEmpty
        publish(model.insertNext(_state.value, trackIds))
        if (wasEmpty) sync(reset = true).also { player.prepare() } else sync()
    }

    fun append(trackIds: List<Long>) {
        val wasEmpty = _state.value.isEmpty
        publish(model.append(_state.value, trackIds))
        if (wasEmpty) sync(reset = true).also { player.prepare() } else sync()
    }

    fun remove(entryId: Long) {
        val before = _state.value
        val s = model.remove(before, entryId)
        publish(s)
        if (before.current?.id == entryId) sync(reset = true).also { if (s.isEmpty) player.stop() } else sync()
    }

    fun move(from: Int, to: Int) {
        publish(model.move(_state.value, from, to))
        sync()
    }

    fun jump(index: Int) {
        val s = model.jump(_state.value, index)
        if (s === _state.value && index == _state.value.index) {
            player.seekTo(0); return
        }
        publish(s)
        sync(reset = true)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun clear() {
        publish(model.clear(_state.value))
        player.stop()
        player.clearMediaItems()
    }

    /** Re-reads the window after a change of DSP or stream settings (cast reload, ReplayGain). */
    fun reloadCurrent() {
        val pos = player.currentPosition
        val playing = player.playWhenReady
        sync(reset = true, positionMs = pos)
        player.playWhenReady = playing
        player.prepare()
    }

    // --- restore ---------------------------------------------------------------------------------------------------

    /**
     * Puts the window on [target] directly, at [positionMs]: a switch between the phone and a Chromecast
     * (07-chromecast.md T4). The logical queue is unchanged.
     */
    fun loadInto(target: Player, positionMs: Long, playWhenReady: Boolean, aheadCount: Int) {
        val s = _state.value
        val (want, wantIndex) = WindowPlanner.window(s, aheadCount)
        val trackOf = s.entries.associate { it.id to it.trackId }
        ensureTracks(want.mapNotNull { trackOf[it] })
        target.repeatMode = s.repeat.toPlayer()
        if (want.isEmpty()) target.clearMediaItems()
        else target.setMediaItems(want.mapNotNull { item(it, trackOf[it]) }, wantIndex.coerceAtLeast(0), positionMs)
        target.playWhenReady = playWhenReady
    }

    /** A Chromecast already plays [entryId] of this queue: follow it, touch nothing on the receiver. */
    fun adopt(entryId: Long): Boolean {
        val i = _state.value.indexOf(entryId)
        if (i < 0) return false
        if (i != _state.value.index) publish(_state.value.copy(index = i))
        notifyCurrent()
        return true
    }

    /** The last session's queue, paused at its position, without preparing (the first play prepares). */
    fun restore(load: Boolean = true) {
        val saved = resume.load() ?: return
        nextEntryId = saved.nextEntryId
        val known = library.tracks(saved.state.entries.map { it.trackId })
        val entries = saved.state.entries.filter { it.trackId in known }
        if (entries.isEmpty()) return
        val cur = saved.state.current
        val idx = entries.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
        val s = saved.state.copy(entries = entries, index = idx)
        tracks.putAll(known)
        _state.value = s
        if (!load) return
        player.repeatMode = s.repeat.toPlayer()
        val pos = if (s.current?.id == saved.positionEntry) saved.positionMs else 0L
        sync(reset = true, positionMs = pos)
        player.playWhenReady = false
    }

    // --- the window ------------------------------------------------------------------------------------------------

    /** A sync waits while a Chromecast's queue still has items whose details have not arrived. */
    private var syncDeferred = false

    private fun sync(reset: Boolean = false, positionMs: Long = 0) {
        val s = _state.value
        val (want, wantIndex) = WindowPlanner.window(s, ahead())
        val cur = (0 until player.mediaItemCount).map { entryIdOf(player.getMediaItemAt(it)) }
        if (!reset && want.isNotEmpty() && -1L in cur) {
            syncDeferred = true
            return
        }
        syncDeferred = false
        val ops = if (reset || want.isEmpty()) listOf(WindowPlanner.Op.Reset(want, wantIndex))
        else WindowPlanner.plan(cur, player.currentMediaItemIndex, want, wantIndex)
        val trackOf = s.entries.associate { it.id to it.trackId }
        ensureTracks(want.mapNotNull { trackOf[it] })
        for (op in ops) when (op) {
            is WindowPlanner.Op.Reset -> {
                if (op.ids.isEmpty()) player.clearMediaItems()
                else player.setMediaItems(op.ids.mapNotNull { item(it, trackOf[it]) }, op.index.coerceAtLeast(0), positionMs)
            }
            is WindowPlanner.Op.Remove -> player.removeMediaItems(op.from, op.to)
            is WindowPlanner.Op.Add -> player.addMediaItems(op.at, op.ids.mapNotNull { item(it, trackOf[it]) })
        }
        if (ops.isNotEmpty()) Log.d(TAG, "window: ${ops.joinToString { it.javaClass.simpleName }} -> ${player.mediaItemCount} items")
    }

    private fun ensureTracks(ids: List<Long>) {
        val missing = ids.filter { it !in tracks }
        if (missing.isNotEmpty()) tracks.putAll(library.tracks(missing))
        if (tracks.size > 2000) {   // keep only what the queue still uses
            val used = _state.value.entries.map { it.trackId }.toSet()
            tracks.keys.retainAll(used)
        }
    }

    private fun item(entryId: Long, trackId: Long?): MediaItem? {
        val t = trackId?.let { tracks[it] } ?: return null
        return mediaItem(context, t, "q:$entryId")
    }

    // --- player events ---------------------------------------------------------------------------------------------

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (syncDeferred) sync()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // -1: a Chromecast queue item whose details have not arrived yet; its real item follows
        val entryId = mediaItem?.let { entryIdOf(it) }?.takeIf { it >= 0 } ?: return
        val s = _state.value
        val i = s.indexOf(entryId)
        if (i < 0) {
            sync(reset = true)
            return
        }
        if (i != s.index) publish(s.copy(index = i))
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) errorsInRow = 0
        autoAdvanceCheck()
        sync()
        notifyCurrent()
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.w(TAG, "playback error on ${player.currentMediaItem?.mediaId}: ${error.errorCodeName} ${error.message}", error)
        val name = currentTrack()?.displayTitle ?: "this track"
        _messages.tryEmit("Can't play $name: ${error.cause?.message ?: error.errorCodeName}")
        errorsInRow++
        if (errorsInRow >= 3 || !player.hasNextMediaItem()) {
            errorsInRow = 0
            player.pause()
            return
        }
        player.seekToNextMediaItem()
        player.prepare()
        player.play()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) startTicker() else savePosition()
    }

    /** A seek while paused is kept too (the app may be closed before it plays again). */
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) savePosition()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) errorsInRow = 0
    }

    private var ticker: Job? = null
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var n = 0
            while (isActive && player.isPlaying) {
                delay(5000)
                countPlayIfDue()
                if (++n % 2 == 0) savePosition()
            }
        }
    }

    private fun countPlayIfDue() {
        val e = _state.value.current ?: return
        if (e.id == countedEntry) return
        val dur = player.duration.takeIf { it > 0 } ?: return
        if (player.currentPosition >= minOf(dur / 2, 240_000L)) {
            countedEntry = e.id
            scope.launch(Dispatchers.IO) { library.countPlay(e.trackId) }
        }
    }

    fun currentTrack(): Track? = _state.value.current?.let { tracks[it.trackId] ?: library.track(it.trackId) }

    /**
     * The track a swipe would bring in: the next one ([dir] 1) or the previous one (-1), as the player moves (around the
     * ends only when repeating all; "repeat one" steps on like "off").
     */
    fun neighbour(dir: Int): Track? {
        val s = _state.value
        if (s.entries.isEmpty()) return null
        var i = s.index + dir
        if (i !in s.entries.indices) {
            if (s.repeat != Repeat.ALL) return null
            i = Math.floorMod(i, s.entries.size)
        }
        return track(s.entries[i].trackId)
    }
    fun trackOf(entry: QueueEntry): Track? = tracks[entry.trackId]
    /** Main thread; reads the library only for a track the queue has not loaded. */
    fun track(trackId: Long): Track? = tracks[trackId] ?: library.track(trackId)?.also { tracks[trackId] = it }

    private fun notifyCurrent() {
        val s = _state.value
        val ids = (0..2).mapNotNull { s.entries.getOrNull(s.index + it)?.trackId }
        onCurrentChanged?.invoke(ids)
    }

    // --- auto-advance ----------------------------------------------------------------------------------------------

    private fun autoAdvanceCheck() {
        var s = _state.value
        if (s.repeat != Repeat.OFF || settings[S.afterQueue] != AfterQueue.NEXT_FOLDER) return
        var src = s.source as? QueueSource.Folder ?: return
        var guard = 0
        while (s.entries.size - s.index - 1 < ahead() && guard++ < 50) {
            val last = library.folder(src.lastFolderId) ?: break
            val next = library.nextFolderAfter(last, src.recursive)
            Log.d(TAG, "auto-advance after ${last.name} (recursive ${src.recursive}): ${next?.name ?: "end of library"}")
            if (next == null) break
            var ids = library.tracksUnder(next.id, recursive = false)
            if (s.shuffle) ids = ids.shuffled()
            src = src.copy(lastFolderId = next.id, recursive = false)
            s = model.append(s, ids, keepSource = true).copy(source = src)
        }
        if (s !== _state.value) publish(s)
    }

    // --- persistence -----------------------------------------------------------------------------------------------

    private fun publish(s: QueueState) {
        _state.value = s
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1000)
            val snapshot = _state.value
            val next = nextEntryId
            launch(Dispatchers.IO) { resume.saveQueue(snapshot, next) }
        }
    }

    fun savePosition() {
        if (!::player.isInitialized) return
        val e = _state.value.current ?: return
        val pos = player.currentPosition
        scope.launch(Dispatchers.IO) { resume.savePosition(e.id, pos) }
    }

    companion object {
        private const val TAG = "TFQUEUE"

        fun entryIdOf(item: MediaItem): Long = item.mediaId.removePrefix("q:").toLongOrNull() ?: -1L

        fun Repeat.toPlayer() = when (this) {
            Repeat.OFF -> Player.REPEAT_MODE_OFF
            Repeat.ALL -> Player.REPEAT_MODE_ALL
            Repeat.ONE -> Player.REPEAT_MODE_ONE
        }

        /** A playable item for a track; [mediaId] says what it is to us ("q:<entry>" in the queue). */
        fun mediaItem(context: Context, t: Track, mediaId: String): MediaItem {
            val extras = Bundle().apply {
                putLong(EXTRA_TRACK_ID, t.id)
                putString("codec", t.codec)
                putString("container", t.container)
                putString("ext", t.extension)
                putInt("rate", t.sampleRate)
                putInt("bits", t.bits)
                putInt("channels", t.channels)
                putLong("durationMs", t.durationMs)
            }
            val meta = MediaMetadata.Builder()
                .setTitle(t.displayTitle)
                .setArtist(t.displayArtist)
                .setAlbumTitle(t.album.ifBlank { null })
                .setAlbumArtist(t.albumArtist.ifBlank { null })
                .setTrackNumber(t.trackNo.takeIf { it > 0 })
                .setDiscNumber(t.discNo.takeIf { it > 0 })
                .setReleaseYear(t.year.takeIf { it > 0 })
                .setGenre(t.genre.ifBlank { null })
                .setDurationMs(t.durationMs.takeIf { it > 0 })
                .setArtworkUri(Artwork.trackUri(context, t.id))
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setExtras(extras)
                .build()
            return MediaItem.Builder().setMediaId(mediaId).setUri(Uri.fromFile(File(t.path))).setMediaMetadata(meta).build()
        }

        const val EXTRA_TRACK_ID = "trackId"
    }
}
