# M8 — Bluetooth and system integration

Goal: the app behaves like a first-class Android media app everywhere outside its own screens: Bluetooth headsets
and cars, the notification and lock screen, Android Auto, audio focus, a sleep timer, an EQ that follows the
output device, and files opened from other apps.

What already works from M2 without code here: Bluetooth audio itself (Android routes the `AudioTrack`), transport
keys from headsets and cars, track metadata and artwork over AVRCP, the media notification, lock-screen and
quick-settings controls, pause on unplug. This milestone adds the rest and verifies all of it on real hardware.

## T1. Shuffle, repeat and notification buttons

- Finish `SessionPlayer` (M2 T5) so logical shuffle and repeat are reported and settable through the session.
  Check with `adb shell dumpsys media_session`: the session shows the state after toggling in the app.
- Two custom command buttons on the session, shuffle and repeat, each with icons per state (`CommandButton`; use
  the media-button-preferences setter if 1.11.1 has it, otherwise `setCustomLayout`). Handle them in
  `onCustomCommand` by calling `QueueController`. Setting "Notification buttons": Shuffle + Repeat (default), None.

## T2. Library tree for Bluetooth browsing and Android Auto

`PlaybackService` is already a `MediaLibraryService`. Implement its tree:

| Id | Children |
|---|---|
| `root` | `folders`, `albums`, `artists`, `playlists`, `recent` |
| `folders` | top-level folders; `folder:<id>` → its subfolders, then its tracks |
| `albums`, `album:<key>` | albums; tracks |
| `artists`, `artist:<name>` | artists; their albums |
| `playlists`, `playlist:<id>` | smart lists and user playlists; tracks |
| `recent` | the 50 most recently added tracks |

- Track ids carry their context: `track:<id>@folder:<fid>`, `track:<id>@album:<key>` and so on, so "play this"
  becomes "play this list starting here": `onSetMediaItems` resolves the context, calls `QueueController.play` and
  returns the window items.
- `onGetChildren` pages with `LIMIT/OFFSET`. `onSearch` / `onGetSearchResult` use `LibraryRepo.search` (voice
  search in the car).
- Artwork for outside controllers: a small read-only `ContentProvider` (`dev.trueform.player.art`) that opens the
  cached 256-pixel thumbnail for `track/<id>` or `folder/<id>`. Use these `content://` URIs as `artworkUri` in
  every `MediaItem`; the session's `BitmapLoader` and Coil resolve the same ids in-process.
- Android Auto: manifest `<meta-data android:name="com.google.android.gms.car.application"
  android:resource="@xml/automotive_app_desc"/>` with `<automotiveApp><uses name="media"/></automotiveApp>`. An
  app installed from outside the Play Store appears in Android Auto only with "Unknown sources" enabled in Android
  Auto's developer settings: tell the owner.

## T3. Bluetooth behaviour

| Setting | Default | Mechanism |
|---|---|---|
| Pause when headphones or Bluetooth disconnect | on | `setHandleAudioBecomingNoisy` |
| Resume when a Bluetooth device connects | off | below |
| …only if it was playing when it disconnected | on | remember the time and reason of the last pause (within 30 minutes, reason "became noisy") |
| …delay before resuming | 1.5 s | wait for the audio route |

Resume on connect: a manifest receiver for `android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED`
(state connected). On API 31+ receiving it needs the runtime permission `BLUETOOTH_CONNECT`: request it only when
the user turns the setting on. The receiver connects a `MediaController` to the service and calls `play()`.
Starting a foreground service from the background is allowed after a Bluetooth broadcast that requires that
permission; confirm on Android 14+ that no `ForegroundServiceStartNotAllowedException` appears.

Headset keys: Media3's defaults (single press play/pause, double press next). No settings.

## T4. Output routes and per-output EQ

`OutputRoutes` reports where audio is going:

- API 33+: `AudioManager.getAudioDevicesForAttributes(mediaAttributes)`. API 29–32: from
  `getDevices(GET_DEVICES_OUTPUTS)` pick by priority: Bluetooth (A2DP, LE) → USB → wired → HDMI → built-in
  speaker. Re-evaluate on `AudioDeviceCallback` events and when playback starts.
- Casting overrides everything: `CastState.Connected(device)`.
- Route key and label: `speaker` "Phone speaker", `wired` "Headphones", `usb:<product>`, `bt:<product>`,
  `cast:<device>`.

Uses:

- The player's output pill ("→ WH-1000XM5") and the Output sheet.
- **Remember EQ per output** (setting, default on): table `output_profiles(routeKey PRIMARY KEY, label, state
  JSON, lastUsed)`. On a route change: save the current EQ state under the old key, load the new key's state. A
  route never seen before starts with a copy of the current state. The EQ screen shows "Profile: Bluetooth ·
  WH-1000XM5". The switch goes through `DspController`, so the chain crossfades. Settings → Equalizer lists the
  profiles (rename, delete).

JVM tests: priority selection from fake device lists; profile save/load sequence.

## T5. Audio focus

| Setting | Default | Effect |
|---|---|---|
| Respect other apps' audio | on | `setAudioAttributes(attrs, handleAudioFocus = value)`; off = keep playing over other apps |
| Resume after an interruption | on | ExoPlayer resumes by itself after a transient loss; when off, call `pause()` when playback becomes suppressed for transient focus loss |

Ducking for short sounds is left to the system.

## T6. Sleep timer

`SleepTimer` in the service's scope: minutes (15, 30, 45, 60, custom), or "end of this track"
(`ExoPlayer.setPauseAtEndOfMediaItems(true)` locally; when casting, pause at the next transition). Setting "Fade out
at the end" (default on, last 10 s, by ramping `player.volume`, restored afterwards). The player's Timer button shows
the remaining minutes; tapping it again offers "+10 min" and "Cancel". The timer survives leaving the app and ends
with the service.

## T7. Open with Trueform (do last; skip if time is short and say so in PROGRESS.md)

- `MainActivity` intent filter: `ACTION_VIEW`, schemes `file` and `content`, MIME `audio/*`.
- A `file:` URI or a `content:` URI that resolves to a readable path plays through the normal path; if that file
  is in the library, play it from the library with its folder as the queue.
- Otherwise open the `content:` URI with `ContentResolver.openFileDescriptor`, detach the descriptor and give it to
  the decoder (`Native.open(path = "", fd = …)`, FFmpeg's `fd:` protocol, compiled in since M1). The data source
  accepts `content:` URIs for this. The item is a one-track queue; its tags come from `Native.probe` given the
  same kind of descriptor (add an `fd` parameter to `probe`, as `open` has).

## Verification on hardware

With the owner's Bluetooth headset and, if available, a car or a Bluetooth speaker with a display:

1. Play, pause, next, previous from the device; title, artist, album, artwork and position show and stay in sync
   after seeks.
2. Disconnect: playback pauses. With resume-on-connect on: reconnect resumes after the delay; with the app's
   process gone it still resumes (playback resumption).
3. Shuffle and repeat toggled from the notification; a car that offers them can set them.
4. Browsing from a car or Android Auto: Folders → a folder → a track plays that folder from that track. Search by
   voice finds a song.
5. Per-output EQ: set a curve on the speaker, connect the headset (curve changes, no click), set another, unplug
   (the first curve returns).
6. A phone call pauses and playback resumes after; with "Resume after an interruption" off it stays paused.
7. Sleep timer of 1 minute with fade: fades and pauses; the volume is restored for the next play.
8. Two hours on Bluetooth with the screen off: no underrun in the log, no audible dropout reported by the owner.

Record what was tested on which devices, and what could not be tested, in PROGRESS.md.

## Pitfalls

- Controllers outside the app never receive file URIs; every `mediaId` must be resolvable by the service alone.
- The legacy session queue is size-limited by binder; the window of 33 items keeps well under it.
- Do not request `BLUETOOTH_CONNECT` at first start. It is needed only for the resume-on-connect setting.
- `getAudioDevicesForAttributes` can return several devices (dual audio); take the first for the label.
- The artwork provider is exported so the system UI and the car can read thumbnails. It must serve nothing else:
  only numeric ids, only the cache directory.
