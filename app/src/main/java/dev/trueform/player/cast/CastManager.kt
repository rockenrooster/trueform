package dev.trueform.player.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.cast.Cast
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import dev.trueform.player.AppGraph
import dev.trueform.player.audio.PcmEncoding
import dev.trueform.player.audio.WavStream
import dev.trueform.player.dsp.DspChain
import dev.trueform.player.dsp.DspParams
import dev.trueform.player.library.Artwork
import dev.trueform.player.playback.QueueController
import dev.trueform.player.playback.WindowPlanner
import dev.trueform.player.settings.ReplayGainMode
import dev.trueform.player.settings.S
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import com.google.android.gms.cast.MediaMetadata as CastMetadata

/** What the cast button, the output sheet and the player show (docs/plan/07-chromecast.md T4). */
data class CastState(val phase: Phase, val device: String?) {
    enum class Phase { NONE, CONNECTING, CONNECTED, RECONNECTING }

    companion object {
        val NONE = CastState(Phase.NONE, null)
    }
}

/**
 * Chromecast (docs/plan/07-chromecast.md): the cast player around the local one, the HTTP server the receiver pulls
 * every track from, the session watcher, the transfer rules (the phone pauses when a session drops; a resumed session
 * keeps the receiver's queue) and the locks. Main thread, except the server's routes. Created only where Google Play
 * services exists.
 */
@UnstableApi
class CastManager(private val g: AppGraph) : CastHttpServer.Handler {
    val log = CastLog(g.app.filesDir)
    private val main = Handler(Looper.getMainLooper())
    val server = CastHttpServer(g.app, { key }, log, this) { g.settings.privateLong(PORT).toInt() }
    private val guard = Guard()
    private val _state = MutableStateFlow(CastState.NONE)
    val state: StateFlow<CastState> = _state

    private lateinit var remote: RemoteCastPlayer
    private var resumed = false              // the session was resumed, not started: keep what the receiver plays
    @Volatile private var version = 0        // "v" in render URLs: a reload is never answered from a cache
    private var lastRemotePos = 0L           // the receiver's last known position, for a drop to the phone
    private var lastRemoteAt = 0L
    private var lastRemotePlaying = false

    val isRemote: Boolean get() = ::remote.isInitialized && remote.isCastSessionAvailable

    /** 128 random bits, made once per install; every URL carries it. */
    val key: String get() = g.settings.privateString(KEY) ?: newKey()
    private val keyTag get() = CastHttpServer.keyTag(key)

    private fun newKey(): String {
        val k = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        g.settings.putPrivateString(KEY, k)
        return k
    }

    /** The player the queue drives from now on: [local] while no session is active, the receiver while one is. */
    fun build(local: Player): Player {
        remote = RemoteCastPlayer.Builder(g.app).setMediaItemConverter(Converter()).build()
        remote.addListener(RemoteEvents())
        Cast.getSingletonInstance(g.app).addSessionManagerListener(Watcher())
        g.app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), NetworkWatcher())
        watchSettings()
        return CastPlayer.Builder(g.app).setLocalPlayer(local).setRemotePlayer(remote).setTransferCallback(Transfer()).build()
    }

    /** "Stop casting": the receiver stops; the phone takes the queue, paused. */
    fun stop() = Cast.getSingletonInstance(g.app).endCurrentSession(true)

    /** Settings → Cast → "Reset cast key": links the receiver holds stop working; a running cast reloads. */
    fun resetKey() {
        newKey()
        log.add("key: reset")
        reload("key reset")
    }

    /** Debug builds: serve without a session, for the PC test of the routes. */
    fun startServerForTest(): String? = if (startServer()) server.baseUrl else null

    // --- sessions ----------------------------------------------------------------------------------------------------

    private inner class Watcher : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) { resumed = false; connecting(session, "starting") }
        override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session, "started")
        override fun onSessionStartFailed(session: CastSession, error: Int) = ended("start failed, error $error")
        override fun onSessionEnding(session: CastSession) = log.add("session: ending")
        override fun onSessionEnded(session: CastSession, error: Int) = ended("ended, error $error")
        override fun onSessionResuming(session: CastSession, sessionId: String) { resumed = true; connecting(session, "resuming") }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session, "resumed")
        override fun onSessionResumeFailed(session: CastSession, error: Int) = ended("resume failed, error $error")
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            resumed = true
            _state.value = CastState(CastState.Phase.RECONNECTING, session.castDevice?.friendlyName)
            log.add("session: suspended, reason $reason")
            guard.update()
        }
    }

    private fun connecting(s: CastSession, how: String) {
        main.removeCallbacks(stopServer)
        startServer()
        _state.value = CastState(CastState.Phase.CONNECTING, s.castDevice?.friendlyName)
        log.add("session: $how (${s.castDevice?.friendlyName})")
    }

    private fun connected(s: CastSession, how: String) {
        main.removeCallbacks(stopServer)
        startServer()
        val name = s.castDevice?.friendlyName ?: "Chromecast"
        _state.value = CastState(CastState.Phase.CONNECTED, name)
        g.outputs.setCast(name)
        log.add("session: $how ($name)")
        guard.update()
    }

    private fun ended(how: String) {
        _state.value = CastState.NONE
        g.outputs.setCast(null)
        log.add("session: $how")
        guard.update()
        main.removeCallbacks(stopServer)
        main.postDelayed(stopServer, 10 * 60_000L)
    }

    private val stopServer = Runnable { server.stop(); log.add("server: stopped") }

    private fun startServer(): Boolean {
        val ok = server.start()
        if (ok) g.settings.putPrivateLong(PORT, server.port.toLong())
        return ok
    }

    // --- switching players (T4) ----------------------------------------------------------------------------------------

    private inner class Transfer : CastPlayer.TransferCallback {
        override fun transferState(source: Player, target: Player) {
            if (target === remote) toRemote(source) else toPhone(source, target)
        }
    }

    /** The session went away (suspended or ended): the phone takes the queue paused. It never plays by itself. */
    private fun toPhone(source: Player, target: Player) {
        val pos = if (!source.currentTimeline.isEmpty) source.currentPosition
        else lastRemotePos + if (lastRemotePlaying) SystemClock.elapsedRealtime() - lastRemoteAt else 0L
        g.queue.loadInto(target, pos, playWhenReady = false, aheadCount = WindowPlanner.AHEAD)
        log.add("transfer: to the phone, paused at ${pos / 1000} s")
    }

    private fun toRemote(source: Player) {
        startServer()
        val client = Cast.getSingletonInstance(g.app).currentCastSession?.remoteMediaClient
        if (resumed && client != null) {
            val status = client.mediaStatus
            if (status == null) {
                StatusWaiter(client, source.currentPosition, source.playWhenReady).start()
                return
            }
            if (adopt(status, "adopted")) return
        }
        load(remote, source.currentPosition, source.playWhenReady, "loaded")
    }

    private fun load(target: Player, pos: Long, play: Boolean, how: String) {
        version++
        g.queue.loadInto(target, pos, play, REMOTE_AHEAD)
        log.add("transfer: $how at ${pos / 1000} s, ${if (play) "playing" else "paused"}")
    }

    /** True when the receiver plays an item of ours from this queue: it keeps playing, untouched. */
    private fun adopt(status: MediaStatus, how: String): Boolean {
        val tf = status.mediaInfo?.customData?.optJSONObject("tf") ?: return false
        val active = status.playerState == MediaStatus.PLAYER_STATE_PLAYING || status.playerState == MediaStatus.PLAYER_STATE_BUFFERING ||
            status.playerState == MediaStatus.PLAYER_STATE_LOADING || status.playerState == MediaStatus.PLAYER_STATE_PAUSED
        if (tf.optString("k") != keyTag || !active || !g.queue.adopt(tf.optLong("e", -1))) return false
        log.add("transfer: $how entry ${tf.optLong("e")}, receiver state ${status.playerState}")
        return true
    }

    /** A resumed session without a receiver status yet: decide on the first status, or after 5 s. */
    private inner class StatusWaiter(private val client: RemoteMediaClient, private val pos: Long, private val play: Boolean) :
        RemoteMediaClient.Callback(), Runnable {
        private var done = false

        fun start() {
            log.add("transfer: waiting for the receiver's status")
            client.registerCallback(this)
            main.postDelayed(this, 5000)
        }

        override fun onStatusUpdated() = finish()
        override fun run() = finish()

        private fun finish() {
            if (done) return
            done = true
            client.unregisterCallback(this)
            main.removeCallbacks(this)
            val status = client.mediaStatus
            if (status == null || !adopt(status, "adopted after waiting")) load(g.queue.player, pos, play, "loaded after waiting")
        }
    }

    private inner class RemoteEvents : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = log.add("receiver: " + when (playbackState) {
            Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; else -> "ended"
        })

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            log.add(if (isPlaying) "receiver: playing" else "receiver: stopped playing")
            guard.update()
        }

        override fun onPlayerError(error: PlaybackException) = log.add("receiver: error ${error.errorCodeName} ${error.message}")

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            log.add("receiver: item ${mediaItem?.mediaId?.ifEmpty { "?" }}, reason $reason")

        override fun onEvents(player: Player, events: Player.Events) {
            if (remote.currentTimeline.isEmpty) return
            lastRemotePos = remote.currentPosition
            lastRemoteAt = SystemClock.elapsedRealtime()
            lastRemotePlaying = remote.isPlaying
        }
    }

    // --- reloads (T7) ------------------------------------------------------------------------------------------------

    /** The receiver loads the window again with fresh URLs at the current position: one short gap. */
    private fun reload(why: String) {
        if (!isRemote) return
        version++
        log.add("reload: $why")
        g.queue.reloadCurrent()
    }

    private fun watchSettings() {
        g.main.launch {
            g.dsp.paramsFlow.drop(1).collectLatest {
                if (!g.settings[S.castDsp]) return@collectLatest
                delay(700)
                reload("equalizer changed")
            }
        }
        g.main.launch {
            combine(g.settings.flow(S.castDsp), g.settings.flow(S.castQuality), g.settings.flow(S.replayGain),
                g.settings.version.map {
                    listOf(g.settings[S.replayGainPreamp], g.settings[S.untaggedPreamp], g.settings[S.replayGainNoClip])
                }.distinctUntilChanged()) { a, b, c, d -> listOf(a, b, c, d) }
                .drop(1)
                .collectLatest {
                    delay(700)
                    reload("cast settings changed")
                }
        }
    }

    private inner class NetworkWatcher : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { main.post { addressCheck() } }
        override fun onLost(network: Network) { main.post { log.add("network: Wi-Fi lost") } }
    }

    /** A new Wi-Fi address: the server moves, and the receiver's window is rebuilt with the new URLs (T2). */
    private fun addressCheck() {
        val old = server.host ?: return
        if (!startServer() || server.host == old) return
        log.add("network: address $old -> ${server.host}")
        reload("address changed")
    }

    // --- items (T3) --------------------------------------------------------------------------------------------------

    data class Plan(val contentType: String, val render: Boolean, val bits: Int, val rate: Int)

    /** Passthrough when the receiver can play the file and nothing has to change in it; otherwise a rendered WAV. */
    fun plan(codec: String, container: String, rate: Int, bits: Int, channels: Int, durationMs: Long): Plan {
        val type = passthroughType(codec, container, rate, bits, channels)
        val change = g.settings[S.castDsp] && (shapesSound(g.dsp.params) || g.settings[S.replayGain] != ReplayGainMode.OFF)
        if (type != null && !change) return Plan(type, false, 0, 0)
        return renderFormat(g.settings[S.castQuality].bits, g.settings[S.castQuality].maxRate, rate, durationMs)
    }

    private inner class Converter : MediaItemConverter {
        override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
            val m = mediaItem.mediaMetadata
            val x = m.extras ?: Bundle.EMPTY
            val trackId = x.getLong(QueueController.EXTRA_TRACK_ID, -1)
            val base = server.baseUrl ?: "http://0.0.0.0:${CastHttpServer.PREFERRED_PORT}/s/$key"
            val p = plan(x.getString("codec") ?: "", x.getString("container") ?: "", x.getInt("rate"), x.getInt("bits"),
                x.getInt("channels"), x.getLong("durationMs"))
            val url = if (p.render) "$base/r/$trackId.wav?b=${p.bits}&sr=${p.rate}&v=$version"
            else "$base/f/$trackId.${x.getString("ext") ?: "bin"}"
            val meta = CastMetadata(CastMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(CastMetadata.KEY_TITLE, m.title?.toString() ?: "")
                m.artist?.let { putString(CastMetadata.KEY_ARTIST, it.toString()) }
                m.albumTitle?.let { putString(CastMetadata.KEY_ALBUM_TITLE, it.toString()) }
                m.albumArtist?.let { putString(CastMetadata.KEY_ALBUM_ARTIST, it.toString()) }
                m.trackNumber?.let { putInt(CastMetadata.KEY_TRACK_NUMBER, it) }
                addImage(WebImage(Uri.parse("$base/a/$trackId.webp")))
            }
            val custom = JSONObject().put("tf", JSONObject()
                .put("e", QueueController.entryIdOf(mediaItem)).put("t", trackId).put("k", keyTag))
            val info = MediaInfo.Builder(url)
                .setContentUrl(url)
                .setContentType(p.contentType)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setStreamDuration(x.getLong("durationMs"))
                .setMetadata(meta)
                .setCustomData(custom)
                .build()
            return MediaQueueItem.Builder(info).setAutoplay(true).setPreloadTime(20.0).build()
        }

        /** Receiver → app (joining a session): our items become queue items again; anything else is unknown (-1). */
        override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
            val info = mediaQueueItem.media
            val tf = info?.customData?.optJSONObject("tf")
            if (tf != null && tf.optString("k") == keyTag) {
                g.queue.track(tf.optLong("t"))?.let { return QueueController.mediaItem(g.app, it, "q:${tf.optLong("e")}") }
            }
            return MediaItem.Builder().setMediaId("").setUri(info?.contentId ?: "").build()
        }
    }

    // --- the server's routes (T2), on its worker threads -------------------------------------------------------------

    override fun file(trackId: Long): File? = g.library.track(trackId)?.let { File(it.path) }?.takeIf { it.canRead() }

    override fun art(trackId: Long): File? = g.artwork.trackFile(trackId, Artwork.LARGE)

    override fun render(trackId: Long, query: Map<String, String>): CastHttpServer.Body? {
        val t = g.library.track(trackId) ?: return null
        val bits = if (query["b"] == "16") 16 else 24
        val rate = query["sr"]?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 48000
        val shape = g.settings[S.castDsp]
        val chain = if (shape) DspChain { g.dsp.params } else null
        val stream = g.streams.open(t.path, if (bits == 16) PcmEncoding.INT16 else PcmEncoding.INT24, rate, rate,
            processor = chain, gain = if (shape) null else 1f)
        chain?.configure(stream.sampleRate)
        return RenderBody(stream)
    }

    /**
     * A range request starts a fresh chain: it decodes up to a second before [open]'s offset and drops it, so the
     * filters and the limiter's 5 ms delay are where an uninterrupted stream would have them (ranges join cleanly).
     */
    private class RenderBody(private val s: WavStream) : CastHttpServer.Body {
        override val length get() = s.length
        override val contentType = "audio/wav"
        override fun open(offset: Long) {
            val preroll = (offset - s.headerSize).coerceIn(0, s.sampleRate * 2L * s.encoding.bytes)
            s.seek(offset - preroll)
            val skip = ByteArray(64 * 1024)
            var left = preroll
            while (left > 0) {
                val n = s.read(skip, 0, minOf(skip.size.toLong(), left).toInt())
                if (n <= 0) break
                left -= n
            }
        }
        override fun read(buf: ByteArray, off: Int, len: Int) = s.read(buf, off, len)
        override fun close() = s.close()
    }

    // --- locks (T5) --------------------------------------------------------------------------------------------------

    /** A partial wake lock and a Wi-Fi lock while the receiver is set to play; released 60 s after it stops. */
    private inner class Guard {
        private val wake = g.app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trueform:cast").apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")   // the high-performance mode is a no-op from Android 14; harmless there
        private val wifi = g.app.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "trueform:cast").apply { setReferenceCounted(false) }
        private val release = Runnable { hold(false) }

        fun update() {
            main.removeCallbacks(release)
            val session = _state.value.phase == CastState.Phase.CONNECTED || _state.value.phase == CastState.Phase.RECONNECTING
            when {
                !session || !g.settings[S.castKeepAwake] -> hold(false)
                isRemote && remote.playWhenReady -> hold(true)
                else -> main.postDelayed(release, 60_000)
            }
        }

        private fun hold(on: Boolean) {
            if (on == wake.isHeld) return
            if (on) {
                wake.acquire()
                wifi.acquire()
            } else {
                wake.release()
                if (wifi.isHeld) wifi.release()
            }
            log.add(if (on) "locks: held" else "locks: released")
        }
    }

    companion object {
        /** Items after the current one on the receiver: fewer than locally, still hours of music (T4). */
        const val REMOTE_AHEAD = 20
        private const val KEY = "cast_key"
        private const val PORT = "cast_port"

        fun available(context: Context): Boolean =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

        /** What the Default Media Receiver plays as is (developers.google.com/cast/docs/media), or null: render. */
        fun passthroughType(codec: String, container: String, rate: Int, bits: Int, channels: Int): String? = when {
            codec == "mp3" -> "audio/mpeg"
            codec == "flac" && container == "flac" && rate <= 96000 && bits <= 24 && channels <= 2 -> "audio/flac"
            codec == "aac" && "mp4" in container -> "audio/mp4"
            (codec == "vorbis" || codec == "opus") && container == "ogg" -> "audio/ogg"
            codec.startsWith("pcm_s") && container == "wav" && rate <= 96000 && bits in 16..24 && channels == 2 -> "audio/wav"
            else -> null
        }

        /** EQ with a curve or a preamp, balance or mono: the sound changes, so the receiver gets a render. */
        fun shapesSound(p: DspParams): Boolean =
            (p.eqOn && (p.filters.isNotEmpty() || (!p.autoPreamp && p.manualPreampDb != 0f))) || p.balance != 0f || p.mono

        /** Integer PCM at [castRate]; 16-bit and at most 48 kHz if the file would pass 4 GB. */
        fun renderFormat(bits: Int, maxRate: Int, sourceRate: Int, durationMs: Long): Plan {
            var b = bits
            var sr = castRate(sourceRate, maxRate)
            if (durationMs / 1000.0 * sr * 2 * b / 8 > 4.0e9) { b = 16; sr = castRate(sr, 48000) }
            return Plan("audio/wav", true, b, sr)
        }

        /**
         * The source rate, halved until it fits [maxRate] (176.4 kHz → 88.2 kHz, not 96 kHz): an integer ratio keeps
         * every range request on the same sample grid, so ranges join bit for bit.
         */
        fun castRate(sourceRate: Int, maxRate: Int): Int {
            if (sourceRate <= 0) return minOf(48000, maxRate)
            var r = sourceRate
            while (r > maxRate && r % 2 == 0 && r / 2 >= 44100) r /= 2
            return minOf(r, maxRate)
        }
    }
}

/**
 * Cast options from the manifest (T1): sessions resume after a Wi-Fi blip or a restart, the reconnection service
 * runs, the receiver is not stopped when the app lets go, and Media3's session is the only media session.
 */
class TfCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setResumeSavedSession(true)
        .setEnableReconnectionService(true)
        .setStopReceiverApplicationWhenEndingSession(false)
        .setRemoteToLocalEnabled(true)
        .setCastMediaOptions(CastMediaOptions.Builder().setMediaSessionEnabled(false).setNotificationOptions(null).build())
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
