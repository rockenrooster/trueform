package dev.trueform.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.audio.PcmEncoding
import dev.trueform.player.dsp.DspChain
import dev.trueform.player.library.LibraryRepo
import dev.trueform.player.playback.QueueController
import dev.trueform.player.playback.QueueSource
import dev.trueform.player.playback.Repeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-build automation (docs/plan/02-playback-engine.md T8):
 *   adb shell am broadcast -n dev.trueform.player.debug/dev.trueform.player.DebugReceiver -a tf.CMD --es cmd <name> ...
 * Every command logs one line with tag TFSTATE or TFDEBUG.
 */
@UnstableApi
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as TrueformApp).graph
        val cmd = intent.getStringExtra("cmd") ?: return
        val pending = goAsync()
        g.main.launch {
            try {
                // also the broadcast's result ("am broadcast" prints it): some phones drop the log lines of an app that
                // is not debuggable, as the staging build is not
                pending.resultData = handle(g, cmd, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "$cmd failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(g: AppGraph, cmd: String, i: Intent): String? {
        val p = g.corePlayer
        when (cmd) {
            "mute" -> p.volume = if (i.getBooleanExtra("on", true)) 0f else 1f
            "roots" -> {
                val paths = (i.getStringExtra("paths") ?: "").split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
                withContext(Dispatchers.IO) { g.library.setScanRoots(paths.map { LibraryRepo.Root(it, true) }) }
                g.scanner.request()
            }
            "scan" -> g.scanner.request(i.getBooleanExtra("full", false))
            "play_path" -> {
                g.controller()   // binds the service, so the session and the notification exist
                val path = (i.getStringExtra("path") ?: return null).trimEnd('/')
                val (ids, source) = withContext(Dispatchers.IO) {
                    val folder = g.db.long("SELECT id FROM folders WHERE path = ?", path)
                    if (folder != null) g.library.tracksUnder(folder, true) to QueueSource.Folder(folder, true, folder)
                    else listOfNotNull(g.library.trackByPath(path)?.id) to QueueSource.Mixed
                }
                if (ids.isEmpty()) Log.w(TAG, "nothing in the library at $path") else g.queue.play(ids, 0, source)
            }
            "play" -> { g.controller(); if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }   // a restored queue starts idle
            "pause" -> p.pause()
            "next" -> p.seekToNext()
            "prev" -> p.seekToPrevious()
            "seek" -> p.seekTo(i.getLongExtra("ms", 0))
            "jump" -> g.queue.jump(i.getIntExtra("index", 0))
            "shuffle" -> g.queue.setShuffle(i.getStringExtra("v") == "on")
            "repeat" -> g.queue.setRepeat(when (i.getStringExtra("v")) { "all" -> Repeat.ALL; "one" -> Repeat.ONE; else -> Repeat.OFF })
            "eq" -> {   // --es bands "4.5,3.5,..." (10 values) --ez on true --es mode GRAPHIC|TONE
                i.getStringExtra("mode")?.let { g.dsp.setMode(dev.trueform.player.dsp.EqMode.valueOf(it)) }
                i.getStringExtra("bands")?.let { s -> g.dsp.setBands(s.split(',').map { it.trim().toFloat() }.toFloatArray()) }
                if (i.hasExtra("on")) g.dsp.setOn(i.getBooleanExtra("on", true))
            }
            // the owner's EQ kept before a measurement and put back exactly after it (no run-as on the staging build)
            "eq_keep" -> keptEq = g.dsp.state.value
            "eq_back" -> keptEq?.let { k -> g.dsp.update { k } }
            "render" -> withContext(Dispatchers.IO) {
                // The local DSP chain over a whole file, written as float WAV (docs/plan/06-dsp-equalizer.md exit check).
                val path = i.getStringExtra("path") ?: return@withContext
                val out = File(g.app.getExternalFilesDir(null), i.getStringExtra("out") ?: "render.wav")
                val chain = DspChain { g.dsp.params }
                g.streams.open(path, PcmEncoding.FLOAT32, 0, 192000, processor = { buf, frames ->
                    chain.apply(buf, frames)
                }).use { s ->
                    chain.configure(s.sampleRate)
                    out.outputStream().buffered().use { o ->
                        val b = ByteArray(65536)
                        while (true) { val n = s.read(b, 0, b.size); if (n < 0) break; o.write(b, 0, n) }
                    }
                }
                Log.i(TAG, "rendered $path -> $out")
            }
            "bigqueue" -> {   // docs/plan/02-playback-engine.md exit check 8: a 20,000-entry queue
                val ids = withContext(Dispatchers.IO) { g.library.libraryOrder() }
                val n = i.getIntExtra("n", 20000)
                val big = List(n) { ids[it % ids.size] }
                g.controller()
                var t = System.nanoTime()
                g.queue.play(big, 0, QueueSource.Mixed)
                Log.i(TAG, "bigqueue: play ${n} in ${(System.nanoTime() - t) / 1_000_000} ms")
                t = System.nanoTime(); g.queue.setShuffle(true)
                Log.i(TAG, "bigqueue: shuffle in ${(System.nanoTime() - t) / 1_000_000} ms")
                var worst = 0L
                repeat(50) {
                    t = System.nanoTime(); p.seekToNext(); g.queue.onMediaItemTransition(p.currentMediaItem, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                    worst = maxOf(worst, System.nanoTime() - t)
                }
                Log.i(TAG, "bigqueue: worst next ${worst / 1_000_000} ms, window ${p.mediaItemCount}")
            }
            "die" -> android.os.Process.killProcess(android.os.Process.myPid())   // process death, as the system does it
            "density" -> MainActivity.debugDensity = i.getStringExtra("v")?.toFloatOrNull()   // this app only; no value: normal
            "orient" -> MainActivity.current?.get()?.requestedOrientation = when (i.getStringExtra("v")) {   // this app only
                "land" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "port" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            "set" -> {   // --es key <setting key> --es value <value>: any stored setting (a Choice takes the enum name)
                val key = i.getStringExtra("key")
                val v = i.getStringExtra("value") ?: ""
                when (val s = dev.trueform.player.settings.S.all.firstOrNull { it.key == key }) {
                    is dev.trueform.player.settings.Toggle -> g.settings.putRaw(s.key, v.toBoolean())
                    is dev.trueform.player.settings.Number -> g.settings.putRaw(s.key, v.toInt())
                    is dev.trueform.player.settings.Decibels -> g.settings.putRaw(s.key, v.toFloat())
                    is dev.trueform.player.settings.Choice<*>, is dev.trueform.player.settings.Text -> g.settings.putRaw(s!!.key, v)
                    else -> Log.w(TAG, "no stored setting $key")
                }
            }
            "cast_stop" -> g.cast?.stop()
            "cast_blackout" -> g.cast?.server?.blackout(i.getLongExtra("ms", 8000))   // the Wi-Fi blip, simulated

            "cast_log" -> g.cast?.log?.lines()?.takeLast(i.getIntExtra("n", 40))?.forEach { Log.i(TAG, "castlog: $it") }
            "cast_http" -> {   // the cast server without a session, for the PC test of its routes (07-chromecast.md T2)
                val base = g.cast?.startServerForTest()
                val t = withContext(Dispatchers.IO) { g.library.libraryOrder().firstOrNull()?.let { g.library.track(it) } }
                Log.i(TAG, "cast_http: base $base track ${t?.id} ${t?.extension} ${t?.path}")
            }
            "dump" -> {}
            else -> Log.w(TAG, "unknown command $cmd")
        }
        return if (cmd != "render") dump(g) else null
    }

    private suspend fun dump(g: AppGraph): String {
        val p = g.corePlayer
        val q = g.queue.state.value
        val t = g.queue.currentTrack()
        val fmt = g.lastInputFormat
        val scan = g.scanner.progress.value
        val counts = withContext(Dispatchers.IO) { g.library.trackCount() }
        val o = JSONObject()
            .put("state", when (p.playbackState) { Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY"; else -> "ENDED" })
            .put("playing", p.isPlaying).put("playWhenReady", p.playWhenReady).put("volume", p.volume.toDouble())
            .put("entry", q.current?.id ?: -1).put("track", t?.path ?: "").put("title", t?.displayTitle ?: "")
            .put("position", p.currentPosition).put("duration", p.duration)
            .put("rate", fmt?.sampleRate ?: 0).put("channels", fmt?.channelCount ?: 0).put("encoding", fmt?.pcmEncoding ?: 0)
            .put("queue", q.entries.size).put("index", q.index).put("shuffle", q.shuffle).put("repeat", q.repeat.name)
            .put("window", JSONArray((0 until p.mediaItemCount).map { QueueController.entryIdOf(p.getMediaItemAt(it)) }))
            .put("windowIndex", p.currentMediaItemIndex)
            .put("underruns", g.underruns).put("sinkFrames", g.sink.framesThisStream)
            .put("library", counts).put("scan", "${scan.phase} ${scan.found} ${scan.probed}/${scan.toProbe}")
            .put("scanMs", "${g.scanner.lastTimings.first}/${g.scanner.lastTimings.second}")
            .put("eqOn", g.dsp.state.value.on).put("preampDb", g.localDsp.preampDb)
            .put("cast", g.cast?.state?.value?.let { "${it.phase} ${it.device ?: ""}".trim() } ?: "unavailable")
            .put("remote", g.cast?.isRemote == true)
            .put("limitDb", (g.localDsp.limiter?.reductionDb ?: 0f).toDouble())
        Log.i("TFSTATE", o.toString())
        return o.toString()
    }

    private companion object {
        const val TAG = "TFDEBUG"
        var keptEq: dev.trueform.player.dsp.EqState? = null
    }
}
