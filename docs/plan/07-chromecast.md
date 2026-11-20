# M7 — Chromecast that does not drop out

Goal: cast to any Chromecast or Cast speaker for hours with the phone in a pocket, screen off. The owner's reason
for wanting this app is that Poweramp drops out in long background casts, so this milestone is judged by a soak
test, not by a demo.

## 1. Why casts drop out, and what this design does about each cause

| Cause | Answer here |
|---|---|
| **A real-time stream from the phone.** The phone encodes what it would have played and pushes it live. The receiver holds a second or two. Any stall on the phone (Wi-Fi power-save, a busy CPU, a GC pause, Doze) is heard, and the two clocks drift apart over hours until the buffer runs dry or overflows. | **No live stream.** Every track is an HTTP resource with an exact length and byte-range support. The receiver pulls it at its own pace and buffers far ahead (tens of seconds to the whole track). Phone stalls are absorbed; there is no clock to drift. |
| **Wi-Fi power-save with the screen off.** On Android 14+ an app cannot hold Wi-Fi in high-performance mode in the background: `WIFI_MODE_FULL_HIGH_PERF` is deprecated and silently becomes the low-latency lock, which only works with the screen on and the app in front (**verified**, `WifiManager` reference). | The design does not need low latency. Power-save adds delay, not loss, and a buffered pull does not care. The lock is still taken for the cases where it helps. |
| **The sender has to push each next track.** If the phone is asleep at the track boundary, the music stops. | **The receiver owns its queue.** It always has the next 20 tracks loaded as a Cast queue with preload, and advances by itself. The phone only tops the queue up, any time in the next hour. |
| **The cast session is suspended by a Wi-Fi blip**, and the sender reacts by restarting playback or by playing on the phone. Media3's defaults do exactly this: on `onSessionSuspended` its `CastPlayer` moves playback to the local player with the same play state, and on resume it loads the queue again (**verified**, `RemoteCastPlayer.java` 1977-1984, `CastPlayerImpl.java` 52-64, `DefaultCastPlayerTransferCallback.java`). Media3's default Cast options also switch off session resumption and the reconnection service (**verified**, `DefaultCastOptionsProvider.java` 41-47). | Our own Cast options turn resumption and the reconnection service on. Our own transfer callback **pauses** the phone when the session goes away and, when it returns, **adopts** the receiver's running queue instead of reloading it. |
| **The process is killed or frozen** by the system or an OEM battery manager. | A media-playback foreground service runs while the receiver plays; a partial wake lock is held; the user is offered the battery-optimisation exemption once. The HTTP server uses a fixed port and a persistent key, so the URLs already in the receiver's queue work again as soon as the process is back. |
| **The receiver cannot decode the file** (WMA, ALAC, APE, anything above 96 kHz). | Those tracks are served as WAV rendered by our decoder. |

Receiver capabilities (**verified**, developers.google.com/cast/docs/media): containers MP2T, MP3, MP4, OGG, WAV,
WebM; audio codecs FLAC up to 96 kHz / 24-bit, HE-AAC, LC-AAC, MP3, Opus, Vorbis, WAV (LPCM), WebM.

Known limits, accepted for version 1: no gapless on Chromecast (the Default Media Receiver leaves a short gap
between queue items); an EQ change while casting takes about a second and one short gap (the current item is
reloaded).

## 2. Pieces

```
QueueController ──window of ~23 items──► Media3 CastPlayer ──► RemoteCastPlayer ──Cast SDK──► receiver (Default Media Receiver)
        ▲                                     │ (our CastTransfer decides what a session change does)          │
        │                                     └──► ExoPlayer (local, paused while casting)                     │ HTTP GET + Range
   CastSessionWatcher ── session events ──► CastGuard (wake lock, Wi-Fi lock)                                  ▼
                                                                                   CastHttpServer  /s/<key>/f|r|a/<trackId>
                                                                                     f = original file   r = rendered WAV   a = artwork
```

## T1. Availability and initialisation

- Add `media3-cast`. Cast exists only where Google Play services does:
  `GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS`. On the
  Fire tablet it does not: the session player is then the plain ExoPlayer, there is no cast button, and no Cast
  class is touched. Test this on the Fire 7.
- Manifest: `<meta-data android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
  android:value="dev.trueform.player.cast.TfCastOptionsProvider"/>`.

```kotlin
class TfCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setResumeSavedSession(true)
        .setEnableReconnectionService(true)
        .setStopReceiverApplicationWhenEndingSession(false)   // "Stop casting" still stops it explicitly
        .setRemoteToLocalEnabled(true)
        .setCastMediaOptions(                                  // Media3's session is the only media session
            CastMediaOptions.Builder().setMediaSessionEnabled(false).setNotificationOptions(null).build())
        .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
```

- **Do not call `Cast.getSingletonInstance(context).initialize(...)`.** That path installs Media3's
  `DefaultCastOptionsProvider` (no resumption, no reconnection). Without that call Media3 initialises from the
  manifest provider when the first cast object is created (**verified**, `Cast.java` 232-253). Media3 marks this
  path as kept for compatibility; staying on 1.11.1 keeps it. Create the cast objects after the first frame, not in
  `Application.onCreate`.
- Internet permission, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`.

## T2. `CastHttpServer`

A small HTTP/1.1 server on plain `java.net.ServerSocket`, written here (about 250 lines) rather than a dependency:
it serves one kind of client and three routes.

- **Address and port**: bind to the Wi-Fi interface's IPv4 address (from `ConnectivityManager` link properties of
  the Wi-Fi network), preferred port 47836, next free port up to +10 if taken, `reuseAddress = true`. The chosen
  port is remembered.
- **Key**: 128 random bits, hex, created once per install and stored (setting "Reset cast key" makes a new one).
  Every URL starts with `/s/<key>/`. Compare in constant time. Anything else gets `404` with no body.
- **Routes**:
  - `GET|HEAD /s/<key>/f/<trackId>.<ext>` — the original file, straight from disk.
  - `GET|HEAD /s/<key>/r/<trackId>.wav?b=<16|24>&sr=<rate>&v=<n>` — a `WavStream` (M2) with integer PCM, ReplayGain,
    and a `DspChain` when DSP is active for cast. `v` only defeats caches; the server always renders with the
    current parameters.
  - `GET /s/<key>/a/<trackId>.webp` — artwork from the artwork cache, the 1024 px WebP (built: receivers decode WebP,
    and one cache serves the app and the receiver).
  - A range of a rendered WAV starts a fresh DSP chain: it decodes up to one second before the range and drops it, so
    the filters and the limiter's 5 ms look-ahead are where an uninterrupted stream has them (ranges join bit for bit;
    measured on the Moto).
- **Ranges**: `bytes=a-`, `bytes=a-b`, `bytes=-n`. Reply `206` with `Content-Range: bytes a-b/total`, or `200`
  without a range, or `416` with `Content-Range: bytes */total`. Always send `Content-Length`, `Accept-Ranges:
  bytes`, `Content-Type`, `Access-Control-Allow-Origin: *`, `Connection: close`. `OPTIONS` gets `204` with the CORS
  headers.
- **Threads**: one accept thread; a pool of at most 8 workers; request line and headers limited to 8 KB and 15 s.
  A worker blocked in `write` because the receiver's buffer is full is normal back-pressure.
- **Only site-local clients** (`InetAddress.isSiteLocalAddress`).
- **Lifetime**: started when a cast session starts connecting, stopped 10 minutes after the last session ended.
  Restarted when the Wi-Fi address changes; then the remote window is rebuilt with the new base URL at the current
  position.
- Every request is logged to `CastLog` (T9): time, client, route, track, range, bytes sent, duration, how it ended.

JVM tests: request parsing, range arithmetic (including suffix and invalid ranges), key check, route parsing. PC
test against a phone: `curl.exe -r 0-99`, `-r 1000-`, `-r -500`, `-I` on all three routes; a rendered WAV fetched
in two halves equals the one fetched whole; a wrong key gets 404. (The PC and the phones share the hotspot subnet.)

## T3. `CastItemConverter`

Implements Media3's `MediaItemConverter`. It runs on the main thread, so everything it needs is in the
`MediaItem` extras put there when the window item is built: track id, entry id, duration, codec, container, sample
rate, bits, channels.

`toMediaQueueItem`:

- Decide passthrough or render:

  | Source | Passthrough when | Content type |
  |---|---|---|
  | MP3 | always | `audio/mpeg` |
  | FLAC | ≤ 96 kHz, ≤ 24-bit, ≤ 2 channels | `audio/flac` |
  | AAC in MP4/M4A | always | `audio/mp4` |
  | Vorbis or Opus in Ogg | always | `audio/ogg` |
  | WAV, integer PCM | ≤ 96 kHz, ≤ 24-bit, 2 channels | `audio/wav` |
  | anything else | never | |

  Render (`audio/wav`) when passthrough is not possible, **or** DSP is active for cast (EQ on with a non-flat
  curve, a non-zero preamp, balance or mono), **or** ReplayGain is on. Setting "Apply EQ and gain when casting"
  (default on) can force passthrough wherever the receiver can decode the file.
- Render format: bit depth from the setting "Cast quality" (24-bit default, or 16-bit); rate = the source rate,
  halved until it fits 96 kHz (or 48 kHz in the compatibility setting): 176.4 kHz becomes 88.2 kHz, not 96 kHz. An
  integer ratio keeps every range on the continuous stream's sample grid (a non-integer resampler restarts a fraction
  of a sample off after a seek). If the WAV would pass 4 GB, use 16-bit and at most 48 kHz.
- Build `MediaInfo` with the URL as content id and content URL, the content type, `STREAM_TYPE_BUFFERED`, the
  duration, `MediaMetadata(MEDIA_TYPE_MUSIC_TRACK)` (title, artist, album, track number, one `WebImage` with the
  art URL), and custom data `{"tf": {"e": entryId, "t": trackId, "k": keyTag}}` (`keyTag` = the first 8 hex digits
  of the SHA-256 of the server key; it marks items as ours).
- `MediaQueueItem.Builder(info).setAutoplay(true).setPreloadTime(20.0)`.

`toMediaItem` (receiver → app, used when joining a session): if the custom data has `tf`, rebuild our item from
the ids; otherwise behave like Media3's default converter.

## T4. Players and `CastTransfer`

```kotlin
val remote = RemoteCastPlayer.Builder(context).setMediaItemConverter(castItemConverter).build()
val player = CastPlayer.Builder(context)
    .setLocalPlayer(exoPlayer)
    .setRemotePlayer(remote)
    .setTransferCallback(CastTransfer(queue, watcher, settings))
    .build()                       // this is what SessionPlayer wraps from now on
```

`CastSessionWatcher`: a `SessionManagerListener<CastSession>` registered with
`Cast.getSingletonInstance(context).addSessionManagerListener(...)`. It records how the current session began
(`onSessionStarting` → fresh, `onSessionResuming` → resumed) and exposes
`StateFlow<CastState>` = `None | Connecting(device) | Connected(device) | Reconnecting(device)`
(`onSessionSuspended` → `Reconnecting`; `onSessionEnded`, `onSessionResumeFailed` → `None`).

`CastTransfer.transferState(source, target)` (Media3 calls it just before it switches the active player; after it
returns Media3 prepares the target and stops the source):

- **Remote → local** (session ended or suspended): put the window on the local player at the remote's last
  position, with `playWhenReady = false`. The phone never starts playing by itself. The UI shows "Reconnecting to
  Living Room…" or "Cast ended · paused".
- **Local → remote, fresh session**: put the window on the remote at the local position, with the local play
  state. (For the remote the planner uses `AHEAD = 20`.)
- **Local → remote, resumed session**: **adopt if possible.** Look at
  `castSession.remoteMediaClient.mediaStatus`:
  - It has a current item whose custom data carries our `keyTag` and an entry id that is in the logical queue, and
    the player state is playing, buffering, loading or paused → touch nothing on the receiver. Set the logical
    index to that entry. The next window sync then only appends or trims around what is already there.
  - No status yet → do nothing now; wait for the first `RemoteMediaClient.Callback.onStatusUpdated` (5 s limit) and
    decide then.
  - Otherwise → load as for a fresh session.

  Log which branch ran (`CastLog`: `adopted`, `loaded`, `waited`).

This part needs experiments on a real receiver; the behaviour in the table of section 1 is the requirement, the
mechanism above is the planned way to get it. If adoption cannot be made reliable, fall back to "load at the
receiver's last reported position" and record the limit in PROGRESS.md and PLAN.md.

## T5. `CastGuard`

- While `Connected` and the remote is set to play: hold `PowerManager.PARTIAL_WAKE_LOCK` (`trueform:cast`) and a
  `WifiManager.WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`; harmless where it no longer has effect). Release 60 s after a
  pause and at session end. Setting "Keep the phone awake while casting", default on.
- Battery optimisation: if `PowerManager.isIgnoringBatteryOptimizations` is false, show once, on the first cast, a
  sheet: "For hours of casting with the screen off, let Trueform run without battery restrictions" with a button
  that fires `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for our package (permission
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), plus one line about the extra switch on OnePlus/Oppo and Samsung. Also
  reachable from Settings → Cast.
- Confirm on a phone that the service is in the foreground while the receiver plays
  (`dumpsys activity services dev.trueform.player.debug` shows `isForeground=true`) and leaves it when paused.

## T6. The queue on the receiver

- Same `QueueController` and planner as local playback. Shuffle works because the logical order is already
  shuffled; the receiver just plays its queue in order.
- Repeat: `ONE` and `ALL` (when the queue fits the window) map to the player's repeat modes, which
  `RemoteCastPlayer` supports (**verified**, `PERMANENT_AVAILABLE_COMMANDS`); a large queue with `ALL` wraps by
  top-up, as locally.
- Top-ups and trims are `addMediaItems` / `removeMediaItems`, which become queue insert and remove messages
  (**verified**, `RemoteCastPlayer.java` 1611-1649). A 23-item load is roughly 20 KB of JSON, well inside the Cast
  message limit.
- Jumping far in the queue, or choosing new music, is one `setMediaItems` (a load).

## T7. DSP and setting changes while casting

A debounced (700 ms) `QueueController.reloadRemote()`: `setMediaItems(window, index, currentPosition)` with new
`v` values. Triggers: any `DspParams` change while a rendered stream is in use; DSP going from neutral to active
or back (items switch between passthrough and render); ReplayGain or cast-quality settings. The EQ screen shows
"Changes take a second while casting" while a cast is active.

## T8. UI

- Cast button in the library top bar and the player top bar: Media3's Compose `MediaRouteButton` first (it shows
  the standard chooser and controller dialogs; the activity's AppCompat theme with an opaque `colorPrimary` is what
  those dialogs need, **verified** in `MediaRouteButton.kt`). Hidden where Cast is unavailable.
- Then replace it with the app's own **Output sheet** (`04-ui-ux.md`): "This phone", cast devices found by
  `MediaRouter` with the Cast selector while the sheet is open, connect and "Stop casting", and a volume slider for
  the cast device (0–20; hardware volume keys already control it through the session). If time is short, the
  standard dialogs stay; say so in PROGRESS.md.
- The player's output pill shows "→ Living Room"; `Reconnecting` shows as a quiet inline status, never a dialog.
- The waveform seekbar works unchanged (stored data, receiver-reported position).

## T9. `CastLog`

A ring of the last 2000 events, also appended to `filesDir/cast.log` (rotated at 1 MB): HTTP requests (T2),
session events with reasons, receiver player state changes (playing, buffering, idle with reason), adopt/load
decisions, network changes, lock changes, queue top-ups. Settings → Cast → "Cast log" shows it with a share
button. The soak test reads this file.

## T10. Android 17 and target SDK 37

Local-network protection is enforced for apps that target API 37: accepting an incoming LAN connection needs the
runtime permission `ACCESS_LOCAL_NETWORK` (**verified**, developer.android.com/privacy-and-security/
local-network-permission). The app targets 36, where LAN access is open. Raising the target later means: declare
and request that permission before the server starts, and explain it ("so your Chromecast can fetch the music from
this phone"). Do not raise the target without doing this.

## Tests and the soak protocol

Unit and PC tests as listed under T2. Converter tests: the passthrough table; render parameters; custom data round
trip. Planner tests already cover top-up.

On devices, with a Chromecast on the same Wi-Fi as the phone (owner provides; cast volume low or as the owner
says):

1. **Basics**: connect, play a folder, next/previous, seek, pause/resume, shuffle, repeat, queue edits, volume,
   stop casting, disconnect and reconnect. One track each of MP3, FLAC 16/44, FLAC 24/96, M4A, Ogg, Opus
   (passthrough) and WMA, ALAC, WavPack, FLAC 24/192 (render). EQ on: everything renders, and the change is
   audible.
2. **Wi-Fi blip** (the owner toggles the phone's Wi-Fi off for 20 s, then on; scripts may not change device
   settings): the receiver never stops; the app shows Reconnecting then Connected; `CastLog` says `adopted`; the
   position and track are right; the phone made no sound.
3. **Process death**: `am force-stop` on our package while casting. The receiver finishes what it has buffered.
   Start the app: the session resumes, the queue is adopted, the next tracks load from the same URLs.
4. **Soak**: a queue of at least 3 hours, phone on battery, screen off, adb disconnected (wireless debugging keeps
   Wi-Fi awake and would flatter the result). Afterwards read `cast.log`. **Pass** = no receiver buffering period
   longer than 2 s other than at the start of an item, no idle-with-error, every suspension followed by an
   adoption, position monotonic, at least 3 hours played, and the owner heard no dropout. Run it on the OnePlus 12R
   (the strictest battery manager among the test phones) and once on a Motorola.
5. **Fire tablet**: no cast button, no crash.

## Exit checks

- Tests 1–5 pass; the soak log and its summary are attached to PROGRESS.md.
- The owner has used it for an evening.

## Pitfalls

- Do not shorten the receiver's buffer or pace the HTTP response to make EQ changes faster; the buffer is the
  robustness.
- URLs must stay valid for as long as an item can sit in the receiver's queue: same key, same port, across process
  restarts. Never put the phone's hostname in a URL; use the IPv4 address.
- `RemoteCastPlayer` ignores `setShuffleModeEnabled`; never rely on player-side shuffle.
- Cast's own notification and media session must stay off, or there are two of each.
- The receiver stops an idle or long-paused app by itself (minutes). Pressing play after that must reconnect and
  reload at the saved position, not fail silently.
- Everything Cast-related must be skipped, not just hidden, when Play services is missing.
