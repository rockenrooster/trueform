# M2 — Playback engine

Goal: files play through ExoPlayer from our decoder, in float, gapless, in the background, controlled by a media
session. The logical queue lives in the app; the player holds a window of it. No real UI yet: everything is driven by
adb through debug hooks.

Media3 facts this design rests on, all **verified** in the 1.11.1 sources on 2026-11-20:

- `MediaCodecAudioRenderer` passes `audio/raw` straight to the sink when the sink supports the PCM encoding
  (`MediaCodecAudioRenderer.java` 367-371, 488).
- `WavExtractor` reads 32-bit float WAV and RF64, emits samples of 100 ms (`TARGET_SAMPLES_PER_SECOND = 10`), clamps
  a data size larger than the input, and ends cleanly on early end of input (`WavExtractor.java` 61, 216-226, 358).
- A single extractor passed to `ProgressiveMediaSource` is used without sniffing (`BundledExtractorsAdapter.java` 80).
- `DefaultAudioSink` with float output runs only trimming, channel mapping and to-float conversion; custom audio
  processors are skipped (`DefaultAudioSink.java` 753-760).
- `AudioSink.handleBuffer` must be given the **same** `ByteBuffer` again until it returns true, except after a
  flush (`AudioSink.java` 740-752; `DefaultAudioSink.java` 939).
- `DefaultRenderersFactory.buildAudioSink` is overridable; `DefaultAudioTrackBufferSizeProvider.Builder` has
  `setTargetPcmBufferDurationUs`; the default PCM buffer is now a fixed 500 ms (release notes 1.11.0).

## T1. `WavHeader` and `WavStream`

`WavStream` turns one file into a byte stream that looks like a WAV file of known length. Three consumers use it:
ExoPlayer (float32), the cast HTTP server (int16 or int24, M7) and nothing else needs to know about the decoder.

```kotlin
enum class PcmEncoding(val bytes: Int) { FLOAT32(4), INT24(3), INT16(2) }

class WavStream(
    path: String,
    encoding: PcmEncoding,
    outRate: Int, maxRate: Int, quality: Int,     // decoder options
    knownFrames: Long?,                            // exact count from the database, if analysed
    gain: Float,                                   // ReplayGain, linear
    private val dsp: DspChain?,                    // null for local playback (DSP is in the sink); set for cast render
) : Closeable {
    val sampleRate: Int
    val totalFrames: Long        // knownFrames ?: decoder total (after scanFrames() when the decoder's is a guess)
    val length: Long             // header size + totalFrames * 2 * encoding.bytes
    fun seek(byteOffset: Long)   // any offset in 0..length, including inside the header or mid-frame
    fun read(dst: ByteArray, offset: Int, size: Int): Int   // -1 at length
}
```

Rules:

- The stream delivers **exactly `length` bytes**. If the decoder ends early, the rest is silence. If the decoder has
  more, it is cut. HTTP clients and `WavExtractor` both rely on the declared length.
- Header: 44-byte RIFF/WAVE, format tag 3 (IEEE float) for `FLOAT32`, tag 1 for integer PCM, 2 channels. When
  `length` would exceed `0xFFFFFFF0`, write an RF64 header with a `ds64` chunk directly after `WAVE` (riff size,
  data size, sample count as 64-bit, table length 0) and `0xFFFFFFFF` in the 32-bit fields.
- Integer output: scale, add TPDF dither of ±1 LSB for `INT16` only, clamp, write little-endian.
- `seek`: offsets inside the header are served from the header bytes; otherwise
  `frame = (offset - headerSize) / frameBytes`, `decoder.seek(frame)`, and the first `(offset - headerSize) %
  frameBytes` bytes of that frame are dropped.
- Decode in chunks of 4096 frames into one reusable direct buffer.

JVM tests for `WavHeader` (round trip through a tiny parser, RF64 threshold) and for the pad/cut/seek arithmetic
with a fake decoder.

## T2. `FfmpegWavDataSource`

`BaseDataSource(/* isNetwork = */ false)`:

- `open(dataSpec)`: `transferInitializing`; path from `dataSpec.uri.path`; `TrackPlayInfo` (known frames, gain)
  from `LibraryRepo.playInfo(path)` (before M3: null and 1.0); build a `WavStream(FLOAT32, …)` with the output-rate
  settings; `seek(dataSpec.position)`; `transferStarted`; return `length - position` (or `dataSpec.length` if set).
- `read`: from the stream; `bytesTransferred(n)`; `C.RESULT_END_OF_INPUT` at the end.
- `close`: close the stream; `transferEnded` if opened.
- Decoder failures become `IOException` with the FFmpeg error text.

## T3. `DspAudioSink`

A `ForwardingAudioSink` around `DefaultAudioSink.Builder(context).setEnableFloatOutput(true)…build()`.

```kotlin
class DspAudioSink(sink: AudioSink, private val dsp: DspChain) : ForwardingAudioSink(sink) {
    private var active = false        // input is float stereo
    private var processed = false     // current buffer already went through the DSP
    private var replacement: ByteBuffer? = null   // only when the input buffer is read-only
    private var scratch = FloatArray(0)

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val f = config.format
        active = f.sampleMimeType == MimeTypes.AUDIO_RAW &&
                 f.pcmEncoding == C.ENCODING_PCM_FLOAT && f.channelCount == 2
        if (active) dsp.configure(f.sampleRate)       // keeps filter state when the rate did not change
        super.configure(config)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, count: Int): Boolean {
        var out = buffer
        if (active) {
            if (!processed) { replacement = process(buffer); processed = true }
            replacement?.let { out = it }
        }
        val done = super.handleBuffer(out, presentationTimeUs, count)
        if (done) { processed = false; replacement = null }
        return done
    }

    override fun flush() { processed = false; replacement = null; dsp.reset(); super.flush() }
    override fun reset() { processed = false; replacement = null; dsp.reset(); super.reset() }

    /** In place when writable; returns a private copy (to hand to the delegate on every retry) when read-only. */
    private fun process(buffer: ByteBuffer): ByteBuffer? { /* asFloatBuffer().get(scratch) → dsp.process → put back */ }
}
```

The `processed` flag is the whole trick: a buffer is processed once, however many times the sink asks for it
again. In M2 `DspChain` is a stub that passes audio through; M6 fills it in. In debug builds the sink counts frames
per stream and logs them (`TFAUDIO frames=<n> track=<mediaId>`) so the gapless check below can compare them with
the decoder's count.

If the sink ever receives a non-float or non-stereo format, it passes it through untouched and logs once; that would
be a bug elsewhere, since every source is our WAV stream.

## T4. `PlayerFactory`

```kotlin
val bufferSizes = DefaultAudioTrackBufferSizeProvider.Builder()
    .setTargetPcmBufferDurationUs(settings.audioBufferMs * 1000)        // default 250 ms
    .build()
val sink = DspAudioSink(
    DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(true)
        .setAudioOutputProvider(
            AudioTrackAudioOutputProvider.Builder(context).setAudioTrackBufferSizeProvider(bufferSizes).build())
        .build(),
    graph.localDsp)
val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
    arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, sink)) }
val sources = ProgressiveMediaSource.Factory(
    FfmpegWavDataSource.Factory(graph), ExtractorsFactory { arrayOf(WavExtractor()) })

ExoPlayer.Builder(context, renderers, sources)
    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 30_000, 250, 500).build())
    .setAudioAttributes(
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
        /* handleAudioFocus = */ true)
    .setHandleAudioBecomingNoisy(true)
    .setWakeMode(C.WAKE_MODE_LOCAL)
    .build()
```

Only an audio renderer exists. No offload, no speed change (float output has no Sonic), no skip-silence. The
default 13 MB audio byte target of `DefaultLoadControl` bounds decoded PCM in memory (about 34 s at 48 kHz, 8 s at
192 kHz); decode-ahead in bursts lets the CPU sleep between them.

Constructor and builder names are from 1.11.1; adapt to what compiles and note differences in PROGRESS.md.

Settings that change the stream itself (output rate, maximum rate, resampler quality, ReplayGain mode and
pre-amps) take effect by `player.stop(); player.prepare()`: the playlist and position survive, the buffered audio is
dropped, there is one short gap. Put that in one function, `PlayerFactory.reloadStream()`.

## T5. The queue

### Model (pure Kotlin, JVM-tested)

```kotlin
data class QueueEntry(val id: Long, val trackId: Long)     // id is unique for the life of the queue
enum class Repeat { OFF, ALL, ONE }
sealed interface QueueSource {                              // what the user asked to play
    data class Folder(val folderId: Long, val recursive: Boolean, val lastFolderId: Long) : QueueSource
    data class Album(val key: String) : QueueSource
    data class Playlist(val id: Long) : QueueSource
    data object Songs : QueueSource
    data object Mixed : QueueSource                         // after manual edits
}
data class QueueState(
    val entries: List<QueueEntry>,       // play order
    val index: Int,                      // -1 when empty
    val shuffle: Boolean, val repeat: Repeat,
    val source: QueueSource?,
    val naturalIds: LongArray?,          // entry ids in unshuffled order while shuffle is on
)
```

`QueueModel` functions return new states: `play(trackIds, startIndex, shuffle, source)`, `setShuffle(on, random)`
(on: the current entry stays first, the rest is Fisher–Yates shuffled, the natural order is remembered; off: the
natural order returns and `index` follows the current entry), `insertNext`, `append`, `remove(entryId)`,
`move(from, to)`, `jump(index)`.

### Window

The player's playlist is always `entries[index - BACK .. index + AHEAD]`, clipped to the queue, with `BACK = 2`
and `AHEAD = 30`. Each `MediaItem` has `mediaId = "q:<entryId>"`.

`WindowPlanner.plan(playerIds, playerIndex, desired, desiredIndex)` (pure, JVM-tested) returns operations:

- If the player's current id equals the desired current id: keep it. After it, keep the common prefix of the two
  "after" lists, remove the differing tail, append the desired tail. Before it, replace the head only if it differs.
  The usual results are "append k items" and "remove k items from the head", neither of which interrupts playback or
  the pre-buffered next track.
- Otherwise: one `setMediaItems(desired, desiredIndex, startPositionMs)`.

Tests: top-up after a transition is append-only; shuffle on/off keeps the current item and changes the rest; move
and remove; jump far away resets; random operation sequences applied to a fake list player always end with the
player list equal to the desired window.

### Repeat

| Logical | Player |
|---|---|
| `ONE` | `REPEAT_MODE_ONE` |
| `ALL`, whole queue fits in the window | `REPEAT_MODE_ALL` (the player wraps, gapless) |
| `ALL`, queue larger than the window | `REPEAT_MODE_OFF`; the planner's desired window wraps past the end to the start |
| `OFF` | `REPEAT_MODE_OFF` |

### `QueueController` (main thread)

- Holds `StateFlow<QueueState>`; applies model functions, then the plan, to the session's underlying player.
- Listens to the player: on `onMediaItemTransition` it maps `mediaId` to the logical index (look near the expected
  index first), publishes the state, tops the window up, and tells the analyser (M5) about the current and the next
  two tracks. If the id is not found it rebuilds the window around the logical index.
- Auto-advance: when fewer than `AHEAD` entries remain, repeat is `OFF`, the setting "After the queue ends" is
  "Continue with the next folder" and the source is a folder, it appends the tracks of the next folder in library
  order (`LibraryRepo.nextFolderAfter`, M3), shuffled if shuffle is on, and updates `lastFolderId`. Because this
  happens 30 tracks early, the folder boundary is gapless.
- Persists through `ResumeStore` (below).

### `SessionPlayer`

A `ForwardingPlayer` over the real player (ExoPlayer now, Media3 `CastPlayer` from M7) given to the session:

- `getShuffleModeEnabled`, `setShuffleModeEnabled`, `getRepeatMode`, `setRepeatMode` read and write the **logical**
  state in `QueueController`. The wrapped player's shuffle stays off.
- It keeps its own set of registered listeners so it can call `onShuffleModeEnabledChanged`,
  `onRepeatModeChanged` and `onEvents` when the logical state changes. (Bluetooth and Android Auto read these.)
- `getAvailableCommands` adds `COMMAND_SET_SHUFFLE_MODE` and `COMMAND_SET_REPEAT_MODE`.
- Everything else forwards. Next and previous work because the window always holds neighbours.

## T6. `PlaybackService`

`MediaLibraryService` (the library tree itself arrives in M8; return an empty root until then).

Manifest:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<service android:name=".playback.PlaybackService" android:exported="true"
         android:foregroundServiceType="mediaPlayback">
  <intent-filter>
    <action android:name="androidx.media3.session.MediaLibraryService"/>
    <action android:name="android.media.browse.MediaBrowserService"/>
  </intent-filter>
</service>
<receiver android:name="androidx.media3.session.MediaButtonReceiver" android:exported="true">
  <intent-filter><action android:name="android.intent.action.MEDIA_BUTTON"/></intent-filter>
</receiver>
```

The receiver is what lets a headset or car button restart playback when the process is gone (playback resumption).

- The players, `QueueController` and `SessionPlayer` live in `AppGraph` for the life of the process. The service
  owns only the session: it can be destroyed and recreated around the same players.
- `onCreate`: players from `AppGraph`, `MediaLibrarySession.Builder(this, sessionPlayer, callback)` with a session
  activity `PendingIntent` to `MainActivity` and a `BitmapLoader` (M4 supplies artwork; a stub until then).
- Callback: accept connections; `onPlaybackResumption` returns the stored queue window and position;
  `onSetMediaItems` / `onAddMediaItems` resolve `mediaId`s to full items (controllers never see file URIs).
- Notification: Media3's default provider. Custom command buttons for shuffle and repeat are set with the media
  button preferences API in M8.
- `onTaskRemoved`: keep playing if playing (setting "Stop when the app is swiped away", default off); otherwise
  stop the service.
- Errors (`onPlayerError`): log, show a transient message through a `SharedFlow` the UI collects, skip to the next
  item; after three failures in a row, pause.

The UI (from M4) connects with a `MediaController` for transport and reads `QueueController`, `LibraryRepo`,
`DspController` and `Settings` directly from `AppGraph`: one process, no need to tunnel those through session
commands. All player mutations happen on the main thread.

## T7. `ResumeStore`

- `filesDir/queue.bin`: version, shuffle, repeat, index, source, entry count, track ids, natural-order permutation.
  Written atomically (temp file, rename) one second after the last queue change.
- `filesDir/position.bin`: entry id and position; written on pause, every 10 s while playing, and in `onDestroy`.
- At process start the queue is restored paused, so the UI shows the last track at its position.

## T8. Debug hooks (debug builds only)

`src/debug/AndroidManifest.xml` declares `DebugReceiver`, exported, protected by
`android:permission="android.permission.DUMP"` (the shell holds it; apps do not).

```
adb -s <serial> shell am broadcast -n dev.trueform.player.debug/dev.trueform.player.DebugReceiver \
    -a tf.CMD --es cmd <name> [extras]
```

| `cmd` | Extras | Effect |
|---|---|---|
| `mute` | `--ez on true` | `player.volume = 0` (default for every scripted run) |
| `play_path` | `--es path <file or folder>` | queue the file, or the folder recursively, and play |
| `pause` `play` `next` `prev` | | transport |
| `seek` | `--el ms <n>` | seek in the current item |
| `shuffle` `repeat` | `--es v on/off/all/one` | logical queue state |
| `dump` | | one log line `TFSTATE {json}`: state, entry, track path, position, duration, format, queue size, index, window ids, underruns, sink frame counters |

`scripts\device.ps1 -Device <name> -Cmd <cmd> …` sends the broadcast and prints the `TFSTATE` line
(`logcat -d --pid=<our pid>`, never `logcat -c`).

Log tags: `TFAUDIO` (underruns from `AnalyticsListener.onAudioUnderrun`, sink errors, discontinuities, per-stream
frame counts), `TFQUEUE` (plans applied), `TFSVC` (service lifecycle).

## Exit checks

1. JVM tests pass: WAV header, stream arithmetic, queue model, window planner.
2. `play_path` on an album folder (muted) plays; `dump` shows `PLAYING`, float, 2 channels, the source sample
   rate, and a position that advances.
3. Gapless: play the M1 gapless pairs as two-item queues. The `AudioTrack` is not rebuilt between same-rate tracks,
   the only discontinuity reported is the automatic transition, and the sink's frame count per track equals the
   decoder's.
4. `seek` to 60000 ms: 300 ms later the position is between 60.0 s and 60.4 s.
5. 60 minutes with the screen off on battery, muted, on the OnePlus 12R: no `TFAUDIO underrun`, position advanced by
   60 minutes ± 1 s across track changes.
6. `adb shell cmd media_session dispatch pause|play|next|previous` change the state (this is the path headset and
   Bluetooth keys take).
7. Process death: pause, leave the app (only when ours is in front), `adb shell am kill dev.trueform.player.debug`,
   confirm with `pidof` that the process is gone, then `cmd media_session dispatch play`: the last queue resumes
   within 10 s of the saved position. (Do not use `am force-stop` for this check: a force-stopped app receives no
   broadcasts until it is opened again, so the media button would never arrive.)
8. Queue of 20,000 synthetic entries (the same few files repeated): `play`, `shuffle on`, `next` × 50: the player
   never holds more than 33 items and each operation completes in under 50 ms on the main thread.

Record numbers in PROGRESS.md.

## Pitfalls

- `Uri.fromFile(File(path))` and `uri.path` handle `#`, `%` and spaces in file names; do not build `file://` strings
  by hand.
- Do not process the buffer again when `handleBuffer` returned false; that doubles the EQ on the retried part.
- `player.stop()` clears a pending error and keeps the playlist; `player.clearMediaItems()` is not a reload.
- `WAKE_MODE_LOCAL` covers local playback only. Casting needs its own locks (M7).
- A sample-rate change between two tracks rebuilds the `AudioTrack`: a gap of some tens of milliseconds that only
  the "fixed output rate" setting removes. That is expected, not a bug.
