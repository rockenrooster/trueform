package dev.trueform.player

import android.app.Application
import android.content.ComponentName
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.trueform.player.cast.CastManager
import dev.trueform.player.dsp.DspChain
import dev.trueform.player.dsp.DspController
import dev.trueform.player.library.Artwork
import dev.trueform.player.library.Db
import dev.trueform.player.library.LibraryRepo
import dev.trueform.player.library.Scanner
import dev.trueform.player.playback.DspAudioSink
import dev.trueform.player.playback.PlaybackService
import dev.trueform.player.playback.PlayerFactory
import dev.trueform.player.playback.QueueController
import dev.trueform.player.playback.ResumeStore
import dev.trueform.player.playback.SessionPlayer
import dev.trueform.player.playback.StreamOpener
import dev.trueform.player.playback.WindowPlanner
import dev.trueform.player.settings.S
import dev.trueform.player.settings.Settings
import dev.trueform.player.system.BluetoothResume
import dev.trueform.player.system.OutputRoutes
import dev.trueform.player.system.SleepTimer
import dev.trueform.player.waveform.WaveformRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The app's long-lived objects (docs/plan/PLAN.md section 4). Players and the queue: main thread. */
@UnstableApi
class AppGraph(val app: Application) {
    val main: CoroutineScope = MainScope()
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = Settings(app)
    val db by lazy { Db(app) }
    val library by lazy { LibraryRepo(db, settings) }
    val scanner by lazy { Scanner(app, db, library, settings, io) }
    val artwork by lazy { Artwork(app, library, settings) }
    val dsp by lazy { DspController(app, settings, main) }
    val localDsp by lazy { DspChain { dsp.params } }
    val streams by lazy { StreamOpener(settings, library) }
    val resume by lazy { ResumeStore(app.filesDir) }
    val queue by lazy {
        QueueController(app, library, settings, resume, main).also { q -> q.onCurrentChanged = { waveforms.prepare(it) } }
    }
    val waveforms by lazy { WaveformRepo(db, library, io) { path -> streams.forget(path) } }
    val outputs by lazy {
        OutputRoutes(app) { key, label -> dsp.switchRoute(key, label, settings[S.eqPerOutput]) }.also { it.start() }
    }
    val sleepTimer by lazy { SleepTimer(main, { corePlayer }) { settings[S.sleepFade] } }

    private val players by lazy { PlayerFactory.create(app, settings, streams, localDsp) }
    val exoPlayer: ExoPlayer get() = players.first
    val sink: DspAudioSink get() = players.second

    /** GitHub releases (docs/plan/10-release-updater.md T4); a build without a number never asks. */
    val updater by lazy {
        dev.trueform.player.update.Updater(BuildConfig.BUILD_NUMBER, BuildConfig.REPO, java.io.File(app.cacheDir, "updates"),
            dev.trueform.player.update.UrlHttp, { dev.trueform.player.update.Installer.verify(app, it) },
            android.os.SystemClock::elapsedRealtime, io)
    }

    /** Chromecast, where Google Play services exists (docs/plan/07-chromecast.md T1); null elsewhere, nothing touched. */
    val cast: CastManager? by lazy { if (CastManager.available(app)) CastManager(this) else null }

    /** The player the queue drives: the local ExoPlayer, inside Media3's CastPlayer where Cast exists. */
    val corePlayer: Player by lazy {
        exoPlayer.addAnalyticsListener(diagnostics)
        val c = cast
        val p = c?.build(exoPlayer) ?: exoPlayer
        queue.attach(p)
        if (c != null) queue.ahead = { if (c.isRemote) CastManager.REMOTE_AHEAD else WindowPlanner.AHEAD }
        queue.restore(load = c?.isRemote != true)   // a receiver already playing keeps its queue
        watchPlayerSettings()
        p
    }
    val sessionPlayer by lazy { SessionPlayer(corePlayer, queue) }

    /** Underruns and formats since start, for the debug dump and Settings → Advanced → Audio details. */
    @Volatile var underruns = 0
    @Volatile var lastInputFormat: Format? = null
    private val diagnostics = object : AnalyticsListener {
        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            underruns++
            Log.w("TFAUDIO", "underrun: buffer $bufferSizeMs ms, $elapsedSinceLastFeedMs ms since the last write")
        }
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                                               decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            lastInputFormat = format
            Log.i("TFAUDIO", "format: ${format.sampleRate} Hz, ${format.channelCount} ch, encoding ${format.pcmEncoding}")
        }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** A controller bound to the playback service: binding creates the service and its session. Main thread. */
    fun controller(): ListenableFuture<MediaController> {
        controllerFuture?.let { if (!it.isCancelled) return it }
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        return MediaController.Builder(app, token).buildAsync().also { controllerFuture = it }
    }

    /**
     * Audio focus and unplugging (docs/plan/08-bluetooth-system.md T3, T5): without "Resume after an interruption" a
     * call pauses for good; a pause because the output went away is remembered for "resume on connect".
     */
    private val focusAndNoisy = object : Player.Listener {
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS &&
                !settings[S.resumeAfterInterruption]) exoPlayer.pause()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) settings.putPrivateLong(BluetoothResume.NOISY_AT, 0)
            else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                settings.putPrivateLong(BluetoothResume.NOISY_AT, System.currentTimeMillis())
        }
    }

    /** Settings that change the live player or the stream (docs/plan/09-settings.md "Applies"). */
    private fun watchPlayerSettings() {
        exoPlayer.addListener(focusAndNoisy)
        main.launch { settings.flow(S.resumeOnConnect).collect { BluetoothResume.setEnabled(app, it) } }
        main.launch {
            settings.flow(S.respectFocus).drop(1).collect { exoPlayer.setAudioAttributes(PlayerFactory.attributes(), it) }
        }
        main.launch {
            settings.flow(S.pauseOnDisconnect).drop(1).collect { exoPlayer.setHandleAudioBecomingNoisy(it) }
        }
        main.launch {
            combine(settings.flow(S.outputRate), settings.flow(S.maxRate), settings.flow(S.resamplerQuality),
                settings.flow(S.replayGain), settings.version.map {
                    listOf(settings[S.replayGainPreamp], settings[S.untaggedPreamp], settings[S.replayGainNoClip])
                }.distinctUntilChanged()) { a, b, c, d, e -> listOf(a, b, c, d, e) }
                .drop(1)
                .collect { reloadStream() }
        }
    }

    /** Re-opens the playing stream with new output settings: same queue and position, one short gap. */
    fun reloadStream() {
        streams.forget()
        if (cast?.isRemote == true) return   // the receiver's streams: CastManager reloads them itself
        if (corePlayer.mediaItemCount > 0 && corePlayer.playbackState != Player.STATE_IDLE) queue.reloadCurrent()
    }

    /**
     * "Open with Trueform" (docs/plan/08-bluetooth-system.md T7): a file the library knows plays with its folder as
     * the queue; another readable file is added on its own. Content without a readable path is refused (IO thread).
     */
    suspend fun openWith(uri: android.net.Uri): Boolean {
        val path = when (uri.scheme) {
            "file" -> uri.path
            "content" -> runCatching {
                app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            else -> null
        } ?: return false
        if (!java.io.File(path).canRead()) return false
        val t = library.trackByPath(path) ?: scanner.addSingle(path) ?: return false
        val folderIds = if (t.folderId > 0) library.tracksUnder(t.folderId, recursive = false) else emptyList()
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            controller()
            if (t.id in folderIds) queue.play(folderIds, folderIds.indexOf(t.id),
                dev.trueform.player.playback.QueueSource.Folder(t.folderId, false, t.folderId))
            else queue.play(listOf(t.id), 0, dev.trueform.player.playback.QueueSource.Mixed)
        }
        return true
    }

    private var observer: ContentObserver? = null

    /** "Watch for new music": rescan (debounced in the scanner) when Android's media index changes. */
    fun watchMediaStore(on: Boolean) {
        val r = app.contentResolver
        observer?.let { r.unregisterContentObserver(it) }
        observer = null
        if (!on) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = scanner.request()
        }.also { r.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, it) }
    }
}
