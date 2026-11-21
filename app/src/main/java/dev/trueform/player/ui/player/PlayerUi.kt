package dev.trueform.player.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.AppGraph
import dev.trueform.player.library.Track
import dev.trueform.player.settings.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the player screens show, observed from the player on the main thread. The position is extrapolated from
 * the last report, so the waveform can move at the display's rate without asking the player every frame.
 */
@Stable
@UnstableApi
class PlayerUi(private val g: AppGraph, scope: CoroutineScope) : Player.Listener {
    private val p: Player get() = g.corePlayer

    var isPlaying by mutableStateOf(false); private set
    var playWhenReady by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var track by mutableStateOf<Track?>(null); private set
    var hasMedia by mutableStateOf(false); private set
    var durationMs by mutableLongStateOf(0L); private set
    var isRemote by mutableStateOf(false); private set
    private var anchorPos = 0L
    private var anchorAt = 0L

    init {
        p.addListener(this)
        refresh()
        scope.launch {
            while (true) {
                delay(1000)
                if (isPlaying) reanchor()
            }
        }
    }

    /** Current position in ms; cheap; read it in the draw phase. */
    fun position(): Long {
        val pos = if (isPlaying) anchorPos + (SystemClock.elapsedRealtime() - anchorAt) else anchorPos
        return if (durationMs > 0) pos.coerceIn(0, durationMs) else pos.coerceAtLeast(0)
    }

    override fun onEvents(player: Player, events: Player.Events) = refresh()

    fun refresh() {
        isPlaying = p.isPlaying
        playWhenReady = p.playWhenReady
        buffering = p.playbackState == Player.STATE_BUFFERING
        hasMedia = p.mediaItemCount > 0
        track = g.queue.currentTrack()
        durationMs = p.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0L
        isRemote = p.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE
        reanchor()
    }

    private fun reanchor() {
        anchorPos = p.currentPosition
        anchorAt = SystemClock.elapsedRealtime()
    }

    fun playPause() {
        g.controller()
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    fun next() {
        mark(1)
        p.seekToNext()
    }

    fun previous() {
        mark(-1)
        if (g.settings[S.previousRestarts]) p.seekToPrevious() else p.seekToPreviousMediaItem()
    }

    /** A swipe back always means the previous track (never "restart this one"). */
    fun previousTrack() {
        mark(-1)
        p.seekToPreviousMediaItem()
    }

    fun canGoNext() = p.hasNextMediaItem()
    fun canGoBack() = p.hasPreviousMediaItem()
    /** The track beside the current one for a swipe: next ([dir] 1) or previous (-1); null where there is none. */
    fun neighbour(dir: Int): Track? = if (if (dir > 0) canGoNext() else canGoBack()) g.queue.neighbour(dir) else null

    private var intent = 1
    private var intentAt = 0L
    private fun mark(direction: Int) { intent = direction; intentAt = SystemClock.elapsedRealtime() }

    /**
     * Which way a track change happening now slides: back after "previous" from this screen in the last 2 s, forwards
     * otherwise (next, auto-advance, a pick from the queue).
     */
    fun direction(): Int = if (SystemClock.elapsedRealtime() - intentAt < 2000) intent else 1

    fun seek(ms: Long) {
        p.seekTo(ms)
        anchorPos = ms
        anchorAt = SystemClock.elapsedRealtime()
    }
}
