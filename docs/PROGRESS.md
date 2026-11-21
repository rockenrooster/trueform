# Trueform — progress

What was built, measured and decided, milestone by milestone (docs/plan/). Newest last. "Not verified" means not run.

## Decisions taken during implementation (the owner asked for best judgement, 2026-11-20)

| Decision | Instead of | Why |
|---|---|---|
| `compileSdk` 37.2 (targetSdk 36) | 36 | Compose 1.13 and core 1.19 refuse to compile against less (the plan's pitfall). Target stays 36: see 07-chromecast T10. |
| SharedPreferences behind `Settings` | DataStore | Platform feature; synchronous reads for the decoder, sink and server threads; one dependency fewer. |
| SQLite through `SQLiteOpenHelper` | Room + KSP | Platform feature; no annotation processing; bulk scanner writes with compiled statements; reactive queries by a table-version flow. |
| Own artwork loader (LruCache + coroutine decode) | Coil | Small, fully under control; thumbnails come from our own cache files. |
| Own back stack (`SaveableStateHolder` + `AnimatedContent`) | Navigation 3 | Same features for this app (per-entry saved state, predictive back) without an API to learn on an unstable version. |
| FFmpeg built in WSL Ubuntu, `ffmpeg` CLI installed there (`apt`, as root) | asking first | The owner said to decide and report. |
| Commits use `43393528+rockenrooster@users.noreply.github.com` | the global git e-mail | The repository will be public; the private address stays private. |

## M0 — Bootstrap (2026-11-20)

- Toolchain that builds: AGP 9.5.1, Gradle 9.6.1 (wrapper, SHA-256 pinned), Kotlin Gradle plugin 2.4.21 (pinned on the
  buildscript classpath; AGP 9.5.1 itself asks for 2.2.10), Compose BOM 2026.11.01, Media3 1.11.1, JDK 17.
- `compileSdk` had to be 37.2 (AGP installed platform 37.2 itself).
- First APKs: debug 45.1 MB (unminified, all dependencies), release 2.37 MB (R8).
- Scripts: `env.ps1` (4-core affinity, device resolution by `ro.serialno`, lock files, foreground checks), `build.ps1`,
  `deploy.ps1`, `test.ps1`, `awake.ps1` (the owner asked to keep the Moto's and 12R's screens awake: `svc power stayon
  true` and a 30-minute screen timeout; originals saved in `captures/awake-original.json`, restored by `-Off`).
- Test phones: a Motorola one 5G ace (Android 11) and a OnePlus 12R (Android 16). Serials only in the untracked
  `scripts/devices.local.ps1`.

## M1 — Native decoder (2026-11-20)

- FFmpeg 8.1.3 (ffmpeg.org tarball, SHA-256 pinned in `scripts/build-ffmpeg.sh`), Linux NDK r28c downloaded by the
  script (SHA-1 from Google's repository XML), audio-only LGPL configuration; every component name valid. Build time in
  WSL with `-j4`: about 2 minutes. Static libraries: avcodec 2.2 MB, avformat 1.1 MB, avutil 1.1 MB, swresample 0.15 MB.
- `libtrueform.so`: 2.73 MB stripped, LOAD segments 16 KB aligned.
- Test corpus (`scripts/make-test-media.sh`, 6.8 MB, committed): 33 files, synthetic signals only.
- `DecoderCorpusTest`: 11 tests, all pass on the Moto (Android 11) and the 12R (Android 16).

What the tests found and what was changed:

| Finding (measured) | Change |
|---|---|
| AAC in MP4 decodes 820 frames of end padding (FFmpeg trims the priming via the edit list, not the end) | the decoder stops at the container's stated length when that length is exact |
| Ogg Opus states its length including the 312-sample pre-skip | Ogg + Opus: stated length minus the pre-skip |
| After a seek to the start of an Ogg Opus file FFmpeg does not re-apply the pre-skip; its timestamps start at -312 | content frame 0 is pts 0 for Ogg Opus (origin stays 0); the drop after a seek comes from timestamps |
| MP3 VBR: 0.1 s of run-in after a seek left 1700 wrong samples (bit reservoir over small frames) | 0.4 s run-in for lossy codecs, none for lossless |
| Ogg: a seek that lands on the last (EOS) page gets guessed timestamps (FFmpeg skips its granule walk-back there): output 200-1000 samples off | 2 s run-in for Ogg, so the landing page is never the last page of a longer file |
| MP3 seek used the Xing table of contents? (suspected) | `usetoc=0`; made no difference; kept as harmless |
| The 3 s test tone is periodic: alignment by correlation is ambiguous on it | alignment and seek tests use the 4 s sweep |

Measured lengths (stated by the container / decoded): exact for every lossless file, LAME MP3 (CBR and VBR), Vorbis,
Opus (after the fix) and AAC (after the trim). Headerless MP3: stated 133630 (bitrate estimate), packet scan 133632,
truth 132300 (the 1332 frames are encoder delay and padding nothing in the file describes). WMA: stated 132300,
decoded 131072 (the last partial frame is not output; WMA has no gapless information).

Gapless joins (sweep cut in two, 48 kHz): FLAC exact; largest error against FLAC at the join: Vorbis 0.008, Opus 0.027,
AAC 0.061, MP3 0.089 (codec edge error of the cut halves; no frame added or lost, no time shift). libopus's output is
14 samples (0.3 ms) early against the source on this file; the decoder applies the file's pre-skip as stated.

Not verified yet: decode speed of a real 4-minute FLAC and MP3 (moved to M2, with real music); the resampler's alias
level (only the range and length are tested).

## M2–M6 — Playback, library, UI, waveform, DSP (2026-11-20)

Built as planned: the WAV-wrapped FFmpeg stream into ExoPlayer, the DSP sink, the logical queue with its 2 + 1 + 30
window, the scanner and SQLite library, the Compose UI with folder browsing, the waveform seekbar, the equalizer
(5/10/15/31 bands and tone dials, auto preamp, limiter). Measured on the Moto (Android 11) with the synthetic demo library
(`scripts/make-demo-music.sh`, 20 tracks in `/storage/emulated/0/TrueformTest`):

| Check | Result |
|---|---|
| First scan / rescan | walk 1110 ms + tags 163 ms; rescan 631–749 ms |
| Output | float 44.1 kHz stereo, 0 underruns |
| Gapless (FLAC cut in two) | each half reaches the sink with exactly 1,764,000 frames; same AudioTrack format, no underrun |
| Seek | position exact after `seek` 12345 and 31000 ms |
| Media keys (`input keyevent`: pause, play, next, previous, play/pause) | all act on the session |
| Process death (debug `die`) while paused, then the play key | the service restarts, the queue comes back at the same entry and position (23.6 s) and plays |
| 20,000-entry queue | play 48 ms, shuffle 74 ms, worst "next" 73 ms, window 33 items |
| Waveform analysis | 40 s FLAC in 477 ms |
| EQ | "Bass boost": auto preamp −6.1 dB in the chain |
| Auto-advance | after the last folder's tracks the next folders are appended in library order, up to the end of the library |

Found and fixed:

- `cmd media_session dispatch …` from adb crashes inside Media3 1.11.1 (`RemoteUserInfo`: "packageName should be
  nonempty"; the shell sends no package name). Real keys (`input keyevent`, Bluetooth) take another path and work. Not
  fixable from the app; scripts use `input keyevent`.
- After that crash the system restarted the sticky playback service, which had nothing to play, missed its foreground
  deadline and ended in an ANR. `PlaybackService` now returns `START_NOT_STICKY`: a media button or the app starts it.
- Screens: dark status-bar icons on the dark theme, a heavy glow behind semi-transparent artwork placeholders, a too
  strong mini-player waveform, "1 songs" — all fixed.

Limit found: with the media permission only, Android 11 does not show `.wv` files to the app (scoped storage); they need
"All files access".

## M7 — Chromecast (2026-11-20)

Built: `CastHttpServer` (plain `ServerSocket`, three routes, ranges, per-install key, site-local clients only),
`CastManager` (Media3 `CastPlayer` around the local player, our `TransferCallback`, session watcher, item converter,
wake and Wi-Fi locks, reloads on EQ/ReplayGain/quality/address changes), `TfCastOptionsProvider` from the manifest
(resumption and reconnection on), `CastLog` (`filesDir/cast.log`), Media3's Compose route button in the top bars and
the output sheet, the one-time battery-optimisation prompt, Settings → Cast actions.

PC tests against the Moto's server (curl from the PC on the same network):

| Request | Result |
|---|---|
| `-r 0-99`, `-r 1000-`, `-r -500` on `/f/` | 206 with the right `Content-Range` and length |
| `-I` on `/f/` and `/r/` | 200, `Accept-Ranges: bytes`, exact `Content-Length` (render: 44 + frames × 6) |
| range past the end | 416 with `Content-Range: bytes */<total>` |
| wrong key, unknown route | 404 |
| whole `/f/` download | identical to the source file (SHA-256) |
| rendered WAV (24-bit, 44.1 kHz) in three ranges | joined, identical to the one fetched whole |
| render speed | 40 s of 24/44.1 in 0.76 s (about 50× real time) |

Found and fixed: a range request starts a fresh DSP chain, and the limiter's 5 ms look-ahead delay made a range start
with 5 ms of silence and a 5 ms shift. Each range now decodes up to one second before its start and drops it, so ranges
join bit for bit. A resampled range still lands a fraction of a sample off the continuous stream (the decoder's resampler
restarts at the codec frame it seeks to), so rendering now keeps an integer ratio to the source (176.4 kHz → 88.2 kHz,
not 96 kHz): renders never resample at a non-integer ratio, and ranges stay exact.

Changed from the plan: artwork is served as the cached 1024 px WebP (`/a/<id>.webp`) rather than a 1280 px JPEG (the
receivers decode WebP; one cache instead of two).

Local playback through the `CastPlayer` wrapper re-checked on the Moto: play, seek, next key, gapless as before. The
route chooser lists the Cast receivers on the network.

**Not verified: casting itself (see below).** The receivers on this network are the household's TVs and speakers. Starting a session takes over the device and can switch a TV on, so no session was started
without the owner. The soak protocol (07-chromecast.md, tests 1–5) is ready to run with the owner.

## M8 — Bluetooth and system integration (2026-11-20)

Built: shuffle and repeat as session command buttons (shown in the notification, the lock screen and on cars), the
library tree for Bluetooth browsing and Android Auto, the artwork provider, per-output EQ through `OutputRoutes` (the
EQ state is kept per route key in `eq.json` rather than an `output_profiles` table), audio focus settings ("Resume after
an interruption" off pauses for good on a transient focus loss), the sleep timer, "Open with Trueform" for files with a
readable path, and "Resume when a Bluetooth device connects": a manifest receiver for `ACL_CONNECTED` and the A2DP
connection broadcast, enabled only while the setting is on, resuming only after a pause caused by the output going away
(within 30 minutes) and only once a Bluetooth audio output exists. Settings asks for `BLUETOOTH_CONNECT` when the
setting is turned on (Android 12+).

Verified on the Moto:

- `LibraryTreeTest` (a real `MediaBrowser`): root → five nodes; Folders down to a track; albums and artists have
  children; "play this" from a folder queues that folder from that track; voice search finds the track. Passes.
- `dumpsys media_session` with shuffle on and repeat one: the session's custom actions read "Shuffle on" and "Repeat
  one"; metadata and a 20-item queue are published.
- JVM: the output priority on API 29–32 (`OutputRoutesTest`).

Not verified (needs the owner's hardware): a Bluetooth headset or car (keys, metadata, browsing, resume on connect,
Android 14+ foreground start after the broadcast), Android Auto (an app from outside the Play Store appears there only
with "Unknown sources" on in Android Auto's developer settings), a phone call interrupting playback, two hours on
Bluetooth.

"Open with Trueform" for `content:` URIs without a file path (some mail and chat apps) is not built: those show "Trueform
can't open that file". Files from file managers and downloads play, with their folder as the queue when they are in the
library.

Test tooling found: Gradle's `connectedDebugAndroidTest` uninstalls the app after the run, which wiped the Moto's
library and settings; `scripts/test.ps1` now keeps the APKs installed. A test run also leaves the app "stopped", so
`scripts/device.ps1` sends its broadcasts with `FLAG_INCLUDE_STOPPED_PACKAGES`.

## M9 — Settings, themes, wide screens (2026-11-20)

- Settings audit: every row of `09-settings.md` exists in the registry and is read where it acts (checked by a scan of
  every `S.<name>` reference); the Updates rows are wired in M10. New row "Equalizer" (opens the EQ screen) so a
  search for "bass" finds it. Search is one function (`Setting.matches`) used by the screen and by a JVM test of the
  exit-check words "bass", "gap", "bluetooth", "cast", "update", "folder"; hidden rows (Cast without Play services) stay
  out of results.
- Sliders: one `TfSlider` in the app's colours (EQ preamp and balance, the sleep timer, number and decibel settings).
- Themes: Light checked on the Moto (library, player, equalizer: readable, dark status-bar icons); Dark is the default;
  Black and "follow system" use the same code path.
- Wide screens: at 840 dp and wider the library keeps the left side and the player stays open in a panel on the right
  (no mini player). A phone on its side shows the artwork left and the controls right. **Not verified on a device**:
  the owner asked for testing on the Moto and the 12R, and rotating a shared phone changes a device-wide setting.
- Backup rules: settings, EQ state and presets are backed up; the library, waveforms, artwork and logs are rebuilt.
- Debug command `set` (any stored setting by key) for scripted checks.

## M10 — Releases and the updater (2026-11-20)

- Public repository: https://github.com/rockenrooster/trueform (created with the owner's standing go-ahead to decide).
  Before the first push the history was checked for keys, passwords, device serials and personal data; the
  development history (which named the test phones' serials in this file) stayed on a local branch, and the public
  history starts from one commit.
- Release key: made by `scripts/release-key.ps1` with a random password that was never displayed; it is stored
  DPAPI-encrypted next to the keystore in `keys/` (gitignored). Certificate SHA-256
  `9c165d32fe95e6e78f1433a38dae8094f0e5e00f0f8b4b7d55793aa7f8ea0a0c`. **The owner should back up the keystore and
  the password** (`scripts\release-key.ps1 -ShowPassword`).
- Workflow: cold run (FFmpeg built) 5 min 44 s; warm runs about 4 minutes. APK 9.3 MB (budget 15 MB).
  First run failed: AGP 9 has no `testReleaseUnitTest`; the workflow runs `testDebugUnitTest`.
- Exit checks on the Moto:
  - build 2 downloaded with `gh release download`, checksum checked, installed on a phone that never had the release
    app; onboarding, folder choice and scan worked in the R8 build;
  - after build 3 was published, returning to the library showed "Update · build 3"; the sheet showed the notes; the
    download, the SHA-256, package, signer and version checks passed. **The install did not start**: inside the bottom
    sheet `LocalContext` is a `ContextWrapper`, not the activity. Fixed in build 4 (builds 2 and 3 cannot update
    themselves; nobody but the test phone has them).
  - build 2 → build 4 as an update (`adb install -r`): queue, position and the "Bass boost" EQ were intact;
  - build 4 → build 5 in the app: the pill, the sheet, the download and all checks, then Android's "Install unknown
    apps" page for Trueform opened as designed. That switch is a security setting, so it was left for the owner:
    switch it on once, go back, tap Install, confirm Android's prompt. **The last step (Android's own confirmation and
    the restart into build 5) is not verified.** Data survival across an update was checked with build 2 → 4 above.
  - The sheet's "View on GitHub" sat under the navigation bar when the notes were long: the actions now share one
    row above the notes (build 6).
- Release manifest review: not debuggable, no debug receiver; exported components are the activity, the playback
  service, the artwork provider and the Bluetooth receiver (disabled until its setting is on), plus library
  components: Media3's media button receiver and Bluetooth validation activity, AndroidX's profile installer.

## M11 — Hardening (2026-11-20)

- Crash capture: `filesDir/last-crash.txt` with the stack trace and one line of player state; the next start offers
  "Share report" once. StrictMode in debug builds (log only).
- StrictMode findings: about 0.5 s of main-thread disk work when the player is created (the Cast framework's
  start-up, the saved queue, the database). The Play services check, the database open and the queue file read now
  start on the IO dispatcher at app start; the Cast framework's own initialisation (about 0.45 s on the Moto, after
  the first frame) remains. Moving it would mean creating the cast player after the session (Media3 allows
  `session.player = …`); left as a finding.
- Cold start (debug build, Moto, `am start -W`): 1.66–1.74 s to the first frame (budget 1.5 s for a release build;
  the release build was not measured separately).
- Hostile library (`scripts/make-hostile-library.sh`, 20,029 files, on the Moto): walk 42.8 s, tags 99.6 s; empty,
  truncated and non-audio files are logged and skipped, no crash; a 20 MB cover, invalid tag bytes, a 400-word title,
  a 240-byte file name, a 384 kHz FLAC (played at the 192 kHz cap) and a two-hour Opus file (seek to 1:59:50) all
  play. Memory after this run (debug build, 20,000-track library): 215 MB PSS.
- Waveform: 384 kHz 8 s in 337 ms, MP3 10 s in 195 ms, two-hour Opus (sampled) in 2.5 s; extrapolated 4-minute FLAC
  about 2.9 s and MP3 about 4.7 s on the Moto (budgets 4 s and 7 s).
- CPU while playing (Moto, R8 release build, app in the background, screen on, FLAC with the 10-band "Bass boost",
  	op -b -n 1 every 6 s for a minute): 3.7–11.1 % of one core, median about 7 %, including two waveform analyses of
  the next tracks and one 96 kHz track. The budget (under 3 %, screen off, 5-minute average) is **not met** by this
  measurement; the screen-off run is still to do. Debug build, same test: 3.5–14 %.
- Not done (needs the owner or time with the phones untouched): the 8-hour and Bluetooth/cast soaks, the CPU run
  with the screen off (the owner asked to keep the screens on), TalkBack and 200 % font passes, the Fire tablet
  no-Cast check, the 12R measurements (the 12R was not connected during this session).

## Follow-up — owner requests, Chromecast on a receiver, layouts, power (2026-11-20, later)

Owner requests:

- **Waveform seekbar redesigned** ("looks terrible"): one layer instead of thin bars under a faint peak layer;
  rounded symmetric bars by default ("Smooth" outline as the alternative); the played part takes a tone colour from
  the spectral balance, blended only between neighbouring colours (warm, gold, cyan) in Oklab so it never turns
  grey-brown; the unplayed part is a calm neutral; tips fade slightly; a slim rounded playhead with a soft glow; thin
  bars on the mini player's bottom edge. Checked on the Moto in dark and light themes.
- **"Lock to portrait"** (Look and feel, off by default).

Chromecast, on one of the household's Cast speakers (screenless, idle; our stream at volume 0 before anything played;
the owner asked for decisions, not questions):

- Connect from the route button; the transfer loaded the queue paused at the phone's position; wake and Wi-Fi locks
  held; the receiver fetched the FLAC from the phone's server (range seeks, then the stream).
- Play, next, seek work on the receiver. An EQ change reloads the receiver at the same position with the rendered
  WAV (`/r/<id>.wav`). Per-output EQ: the speaker keeps its own EQ profile, so later sessions to it render.
- Process death while casting: the receiver plays on what it has (about 2.5 minutes, until it needs the next track
  from the phone's server). **The restarted app does not re-attach**: the Cast framework keeps its saved session
  ("googlecast-savedSessionState") but did not resume it, even with the framework started in `onCreate` and a 30 s
  discovery scan with the framework's own selector after a session left open. Choosing the speaker again starts a
  new session at the saved position. Recorded as a limit.
- Not done: the Wi-Fi blip (switching Wi-Fi is a phone-wide setting) and the 3-hour cast soak (it would occupy a
  household speaker for hours).

Layouts (the owner asked for the Moto and the 12R only; the tablet was asleep at its lock screen, so the tablet size
was simulated on the Moto with debug commands that rotate and rescale only this app):

- Phone on its side: it got the tablet's side panel, whose controls fell off the bottom; now it gets the
  side-by-side full player, and content keeps clear of a side navigation bar and the camera cutout.
- Tablet size (1500 × 675 dp): library and player panel side by side; the panel's artwork shrinks so the controls fit.

Power (release build, Moto, screen off, FLAC 16/44.1 with the 10-band EQ, CPU ticks from `/proc/<pid>/stat` over
120 s): **7.6 % of one core** before; most of it on ExoPlayer's playback thread (EQ and the 10 ms work loop),
decoding itself is small (loader thread). ExoPlayer's dynamic scheduling (the thread sleeps until the audio output
needs data): no measurable change (7.9 %). Debug build, warmed up, 60 s each: EQ + limiter 9.7 %, EQ off 5.0 %, both
off 2.8 %: the limiter's per-sample bookkeeping cost about 2 points even when it never acts. It now skips that work
while nothing approaches the ceiling (bit-identical, JVM test): EQ + limiter 7.9 %, EQ off 4.1 %, both off 3.0 %.
**The 3 % budget is still missed with the EQ on**: the 10-band cascade in Kotlin costs about 4 points on the Moto.
The cascade now runs in C++ next to the decoder (left and right as the two lanes of an arm64 register, double
precision), bit-identical to the Kotlin loop (DspNativeTest, max difference 0). One 100 ms buffer on the Moto: 10 bands
at 44.1 kHz 0.88 ms in Kotlin, **0.25 ms** native (budget 0.5 ms); 31 bands at 192 kHz 3.4 ms, **2.7 ms** native (budget
6 ms; the filter recursion's latency bounds both). Screen off, debug build, warmed up: EQ + limiter **6.4 %** (Kotlin
7.9 %), EQ off 4.1 %, both off 3.0 %. The 3 % budget is still missed with the EQ on; what remains is the player's own
loop and the float/double copies around the filters.

Bluetooth: off on the Moto (switching it on is a phone-wide setting); not tested. Android Auto: installed on the
Moto, but testing needs its developer settings changed (the owner's call); the browse tree is covered by
`LibraryTreeTest`.

The 12R stayed unreachable all session (no wireless debugging advertised on the network).

Soaks: the owner capped scripted soaks at 15 minutes (an 8-hour run was stopped after 5 minutes: 3 clean samples).
`ThemeTest` (JVM): text, secondary and tertiary text, accent and the accent button's label meet their contrast ratios on
every surface of the dark, black and light themes.
15-minute soak (Moto, debug build, native EQ on, muted, repeat all): 15 one-minute samples, playing in every one,
4 tracks, 0 underruns, one process, the position never stalled (`captures/soak-moto-20261120-1624.jsonl`).
Release build 13 (R8, native EQ, "Bass boost", screen off, 90 s after a minute of warm-up): **6.1 % of one core** on
the Moto (release before the native cascade and the limiter shortcut: 7.6 %). Under the 3 % budget only without EQ.

Shared-phone slip (2026-11-20 16:46): during that last measurement another session's app (`dev.glowtide.game.dev`)
was in front on the Moto; the script printed the foreground but did not stop, launched Trueform, pressed Home and
turned the screen off for about three minutes. No lock was held by the other session. Scripts that send input must
stop when the foreground is neither the launcher nor Trueform (`Test-TfDeviceFree`), not only print it.

## Follow-up — the owner's hands-on testing on the 12R (2026-11-20, evening)

The 12R came back on the network. Music access for the debug app had to go through the app's own prompt (`pm grant`
is refused on this OnePlus); the owner then ran the onboarding and used the app (722 tracks, scan 2.7 s + 2.0 s).

Owner reports and what changed:

- **No sound**: the debug app was still muted by a scripted run (in-app volume 0, not the phone's volume). Unmuted;
  a reinstall also resets it.
- **"Noticeable lag when swiping and scrolling"** (12R, debug build: median frame 13 ms, 90th percentile 29 ms,
  UI thread slow, GPU fine). Causes found in our code:
  - Opening, closing or dragging the player recomposed the whole app on every frame (the expand progress was read in
    composition: back handler, mini player alpha, visibility). Now read through derived states and in layout/draw.
  - The waveform asked for a new frame on every vsync while playing and re-recorded the layer it sat in (with the
    player open: the whole screen, the library under it included). Now it has its own layer and redraws when the
    playhead moves about half a pixel; the library is not drawn under the open player.
  - The waveform's two bitmaps were drawn again on every recomposition (a new palette object each time), and the
    accent colour animated for 500 ms after every track change, recomposing the whole player each frame and redrawing
    those bitmaps each frame. Stable cache; the accent switches; the backdrop still fades, read only when drawing.
  - Palette extraction (artwork colours) ran on the main thread; now on a background dispatcher.
  - Crossfade per list picture and the transition's own fade on the large artwork rendered offscreen copies;
    replaced by per-drawing alpha. Decoded pictures are uploaded to the GPU before their first frame.
  - Measured on the Moto (debug build, scripted, `dumpsys gfxinfo`, real playback): opening/closing the player 51 % →
    9 % janky frames (median 17 → 8 ms), track changes 61 % → 33 % (still GPU-bound on the Moto: about 9 ms of GPU per
    frame for the player screen at the clocks it runs at), list scrolling unchanged (already smooth). Not measured on
    the 12R yet: the owner was using it.
- **Swipe animation** ("does it slide the way you swipe?" — it did not): the artwork, title and mini player follow the
  finger, then slide out the way they were swiped while the next track comes in from the other side; a short or
  impossible swipe springs back (with resistance where there is no track). Previous/next buttons slide the same way.
- **Mini player**: Previous button.
- **Folder browser**: a swipe to the right goes up a folder (the folder follows the finger), starting anywhere in the
  list, as in Poweramp: the owner's choice, in place of the rows' swipe-right "play next" there (still in the
  long-press menu; other lists keep both row swipes). A first version used only the left edge.
- **Artwork that never loaded after swiping to a song** (reported through another session): the player and the
  notification (through `ArtProvider`) asked for the same large picture at the same moment and wrote the same
  temporary file; one failed or the file was damaged, and the player kept the placeholder. Each writer now has its
  own temporary file, and a file that does not decode is made again.
- **Seek bar centred and zoomed, with momentum** ("like Poweramp"): see 05-waveform-seekbar.md. Four waveform
  columns a second (blob version 2: stored waveforms are analysed again when their track plays). `WaveformTest`
  (JVM): blob round trip with any column count, column bounds, zoomed bar pooling with partial data.
- The player's accent no longer flashes the theme colour between tracks (the previous artwork's colours stay until
  the new ones are ready).
- Centred seek bar checked on the Moto (debug build, muted): the playhead stays in the middle, bars and the smooth
  outline draw with faded ends, the bubble shows target and offset while scrubbing; a 300 px drag moves about 9.4 s
  (30 s across the bar); a tap 300 px right of the middle seeks the same amount forward; a flick glides on (friction
  5: a fast 300 px flick about 5 s further, a moderate one about 2 s). A flick to the end used to jump into the next
  track: scrubbing now stops a second before the end. While playing with the player open it redraws about 37 times
  a second (GPU median 9 ms on the Moto); track changes 36 % janky frames, as before (GPU-bound on the Moto).
- Waveform analysis with the new resolution, Moto, app in front: FLAC 16/44.1 of 95 s, 110 s and 150 s in 762, 529
  and 741 ms, about 1.2–1.9 s for four minutes (budget 4 s).
- **Swiping between songs "a little jumpy"** (owner, 12R). A system trace (`atrace`, 10 s, three swipes, debug build)
  showed: the track change ran inside the touch handler (16 ms) and recomposed and re-measured the new song's screen
  while the artwork slid (frames of 23–52 ms in the middle of the motion); with an accessibility service on (the
  12R's launcher has one for its gestures) Compose compared the whole semantics tree two or three times a second (up
  to 18.6 ms each); and the playing row's animated bars recomposed and re-measured every frame while music played,
  even in the library hidden under the player. Now:
  - The swipe works like a pager: the neighbouring song comes in beside the current one as you drag; on release both
    carry on at the finger's speed (critically damped spring, settles in about 0.3 s), and only then does the track
    change, showing nothing new. A flick is enough. Full player and mini player.
  - The playing bars are drawn in the draw phase and rest while the player covers the list.
  - Waveform bitmaps and the centred bar's bars are worked out on a background thread.
  - The library under the open player, the per-second time labels and the placeholder letters are not offered to
    accessibility (the seek bar reads the position; TalkBack could reach the hidden rows before).
  - On the Moto (no accessibility service), frames during the drag and the glide are 2–9 ms of main-thread work; the
    work of the change itself (40–60 ms in the debug build) now falls after the artwork has stopped. The debug build
    is several times slower than a release build on these paths.
- `scripts/env.ps1` `Test-TfDeviceFree` counted "nothing in front" as free: a run sent swipes to the 12R's dark,
  locked screen (no effect seen). The screen off or the lock screen up now counts as busy.

12R session (the owner unlocked it for testing; debug build first, then staging):

- **Staging build** (new: the test app built like a release, R8 and not debuggable, debug commands included;
  `scripts\build.ps1 -Config staging`). The debug build is the wrong thing to judge smoothness with:

  | 12R | debug build | staging |
  |---|---|---|
  | Library scroll (Songs, 722 tracks, playing), janky frames | 28–34 % | 6.5–7.8 % |
  | … median / 95th percentile frame | 11–13 / 32–36 ms | 7–8 / 20–21 ms |
  | Song swipes, janky frames (median frame) | 17 % (11 ms) | 3.2 % (10 ms) |
  | Cold start (`am start -W`) | 685–741 ms | 144–171 ms (budget 700 ms) |

  The scroll budget (95th percentile 8.3 ms) is still missed on the 12R; with the launcher's accessibility service
  on, every scroll frame also pays for Compose's accessibility bookkeeping (2.5–4.4 ms in the debug trace), and new
  rows are composed during the scroll. Swipes: frames during the drag 3–9 ms, during the glide 1–7 ms; the change
  itself (one heavy frame) now falls after the artwork stops.
- **CPU while playing, screen off, 10-band EQ** (staging build, FLAC 16/44.1, playback started with the app in front,
  then the screen off; 128 s counted, only stretches with the screen off at both ends): **4.76 % of one core**, no
  underruns. The budget (under 3 %) is still missed on the 12R as on the Moto (release 6.1 %). Two earlier attempts
  were spoiled by the phone being woken (one gave 28.7 %; not results). The EQ is kept and put back exactly by the
  new debug commands `eq_keep` / `eq_back` (no `run-as` on a build that is not debuggable).
- Slips this session: the first staging build lacked the debug receiver (its source folder was added to the wrong
  source set), so the first debug command crashed the app; the weather app that had been behind it came to the
  front, the script stopped, and its clean-up pressed Home over that app. The clean-up now presses Home only out of
  Trueform, and the build was fixed (the receiver is checked in the APK).
- A seek while paused is now saved (a restored position was lost when the app was closed right after).
- Library scroll, staging, traced (`atrace`, 8 flings, 722 tracks, playing): 11.3 % janky frames this run; only 3
  frames were slow on the UI thread; the misses are "slow issue draw commands" and GPU time (GPU median 2 ms, 90th
  percentile 12 ms). The render thread itself: median 3.0 ms, 90th percentile 6.1 ms per frame, 5 of 552 over 8 ms,
  spread over command flush, buffer swap (`queueBuffer` about 1.5 ms) and layers; no shader compiles or texture
  uploads. The GPU runs at a low clock until a fling starts. The 95th-percentile budget (8.3 ms, measured as gfxinfo
  frame time including the GPU) stays missed; what remains is outside the app's main thread.
- UI timers (waveform ticks, time labels, limiter meter, position anchor) now park while the app is not visible, so
  they no longer wake the main thread with the screen off.
- **CPU, where it goes** (per thread from `/proc/<pid>/task`, staging build, 10-band EQ): 12R with the app in the
  background (screen on, the owner keeps it awake): 3.78 % in all, 3.08 of it ExoPlayer's playback thread, 0.31 the
  loader (FFmpeg decoding), 0.21 the main thread; EQ off 2.79 %. A `simpleperf` profile of the playback thread on the
  Moto (the 12R refuses perf events): 62 % in our DSP — 34 % the native filter cascade, 16 % the Kotlin loops around
  it (float/double conversion, preamp, peaks, the limiter's delay line) — and about 25 % ExoPlayer's own loop, much
  of it still interpreted right after an install. Changes:
  - The DSP's common case is native end to end: one pass from the float buffer (conversion, preamp, filters, mono,
    balance; it returns the peak the limiter needs), the limiter's idle delay line, one pass back with the meters'
    peaks. The filters use fused multiply-adds (five operations a sample instead of nine; the little cores are limited
    by issue rate). No longer bit-identical to the Kotlin loop: `DspNativeTest` on the Moto, max difference 1.9e-9 at
    44.1 kHz and 1.5e-8 at 192 kHz (below the float output's own step); one 100 ms buffer of the whole chain, 10 bands
    at 44.1 kHz: 0.29 ms native, 0.79 ms Kotlin.
  - Results: 12R, background, screen on, EQ on: 3.35 % (playback thread 2.61) just after the install; **2.88 %**
    (playback 2.22) once Android has compiled the app (`cmd package compile -m speed`, what background optimisation
    does on users' phones within a day). Moto, **screen off**, EQ on, compiled: **3.13 %** (release build 13 was 6.1 %);
    EQ off 1.80 %.
  - Gain filters within 0.02 dB of flat are not run: the band correction leaves bands set to 0 dB at gains like
    -0.011 or -0.0002 dB (inaudible), each as costly as a real band; in the test preset 5 of 10 filters
    (`DspTest`: the response moves by 0.02 dB at most, 20 Hz–20 kHz).
  - ExoPlayer's playback loop wakes about 8 times a second (dynamic scheduling works); nothing to gain there.
  - **Result: Moto, screen off, 10-band EQ, compiled: 2.39 % of one core — the 3 % budget is met**, on the slower
    phone (6.1 % in release build 13). The 12R is faster still (2.88 % before this last step, screen on).
- Release build 31 installed on the 12R as `dev.trueform.player` at the owner's request (from the GitHub release,
  SHA-256 checked), next to the test app.
- List drawing: song rows paint their own background only while swiped; the mini player's faint waveform is drawn
  at its opacity without an offscreen layer (not measured yet: both phones busy with the owner and another session).
- The 15-minute cast soak and the simulated network drop (`cast_blackout`, new) were postponed: at 22:46 the cast
  devices on the network were bedroom and bathroom speakers and TVs (a session can chime or switch a TV on, even at
  volume 0). To run in the daytime.

12R, locked with the screen off (the owner's phone, idle; their queue, position and EQ file restored afterwards):

- Waveform analysis at background priority, screen off: two 3-minute 48 kHz FLACs in 3.1 s and 4.8 s. The budget
  (4-minute FLAC 16/44.1 in 1.5 s) is for the app in use; to measure with the screen on.
- The screen-off CPU measurement could not run this way: Android refuses audio focus ("AudioHardening focus request
  ignored") and the foreground service ("Background started FGS: Disallowed") to an app that starts playback from the
  background, so the debug `play` command did nothing on the locked phone. Not a defect for users (playback starts
  from the app, the notification or a media button); the measurement has to start playback with the app in front,
  then turn the screen off.

Night of 20–21 November, Moto, staging build, muted (a slot agreed with the session that shares the phone; the 12R
left adb at 00:16):

- **Folder browser, swipe right to go up**, three levels deep (TrueformTest › Northern Static › Afterlight): flings
  went up a level each time and stopped at the top folder; a swipe left on a song still adds it to the queue (queue
  4 → 5); a short drag springs back; a mostly vertical drag scrolls. **Bug:** a slow drag past a third of the width
  did not go up (650 px in 0.7, 1.2 and 2.6 s). The swipe was measured inside the page it moves, so the finger's
  position was read in a frame that moved with it: the page followed at about half the finger's speed and never
  reached the third. Fixed: the header and the list move as two layers and the box that measures the swipe stays
  put; past a third goes up unless flung back left. Retested: 650 px in 2.6 s and in 1.2 s go up, the rest as
  before, and the page now follows the finger one to one. Release build 31 has the bug; build 33 has the fix.
- **Library scroll, before and after the list drawing cuts** (Songs, 606-track bench library, compiled; two rounds of
  16 flings per build). Paused, not playing as intended: the script's start command passed a folder path with a
  space, which adb's remote shell split (`scripts\device.ps1` quotes such paths; the throwaway script did not). Both
  builds ran the same way, so the comparison holds:

  | Moto | round 1 | round 2 |
  |---|---|---|
  | before (build without the cuts) | 0.06 % janky, 95th percentile 8 ms | 6.0 %, 19 ms |
  | after (build 33) | 6.1 %, 19 ms | 0.00 %, 8 ms |

  Each build has a clean round and a round with about 6 % janky frames in which the GPU's 90th percentile goes from
  5 to 14–15 ms: the misses come and go with the GPU's state, not with the build. Paused, the cuts make no
  measurable difference. **Playing** (the run repeated an hour later, playback started properly, muted):

  | Moto, playing | round 1 | round 2 |
  |---|---|---|
  | before the cuts | 4.9 % janky, 95th percentile 16 ms, 47 frames with slow draw commands | 4.6 %, 16 ms, 50 |
  | after (build 33) | 2.7 %, 15 ms, 8 | 2.7 %, 15 ms, 13 |

  While playing, the cuts (chiefly the mini player's waveform drawn without an offscreen layer) take janky frames
  from about 4.7 % to 2.7 % and the GPU's 90th percentile from 6–9 ms to 5 ms. The 12R is not measured yet.
- **M8 checks that need no Bluetooth hardware** (08-bluetooth-system.md, hardware checks 3, 6 and 7), Moto,
  Android 11, staging build, muted:
  - Shuffle and repeat from the system media controls (the media player in the notification shade): shuffle on →
    off → on, repeat all → one → off → all; the app followed every tap.
  - Sleep timer of 1 minute with fade (app volume at -40 dB, on a speaker whose media volume is 0): the fade starts
    at 50 s and falls evenly to 0 at 60 s; at 62 s playback is paused, the timer is off, and the volume is back for
    the next play.
  - A phone call, simulated by another holder of audio focus (`focus_grab`: transient focus with the voice-call
    usage for 7 s; the Moto has no SIM): with "Resume after an interruption" on, playback is held while the focus is
    away and carries on when it returns; with it off, playback pauses for good. A real call takes the same audio
    focus path; a real call is left for the owner's phone.
  - New debug commands for these: `vol`, `sleep`, `focus_grab`; the dump also gives the track id, the suppression
    reason, the sleep timer's minutes and the cast server's address.
- **Cast server soak with the PC as the receiver** (the night rules out the household's Cast speakers and TVs; the
  real-receiver soak and Wi-Fi blip stay for the daytime with the owner). The PC pulls the rendered WAV of a
  12-minute MP3 (`/r/<id>.wav`, 16-bit 44.1 kHz) from the Moto's server at real-time pace with a 20 s lead and a
  receiver-sized socket buffer, as a receiver's player does, looping the track for 15 minutes; at minute 5 the
  server drops every connection and refuses new ones for 8 s (`cast_blackout`). Log:
  `captures/cast-pc-soak-moto-20261121-0114.jsonl`. Result: 15.7 minutes, one reconnect (the drop: the client saw it
  4 s later, its retries were refused until the 8 s were over, then a `206` resumed at the exact byte), at most
  4.2 s without data, the lead never under the 2 s start buffer, no buffering period, every response a `206`.
- **Found: a resumed range was not bit-identical** to the uninterrupted render (07-chromecast.md says ranges join bit
  for bit). Two whole renders were equal; a range differed only in its dither: every sample within ±2 LSB (55 %
  equal, 43 % ±1), the music itself identical, so inaudible. The 16-bit dither came from a random generator that
  started over with every range. Fixed: the dither is now drawn from the sample's position (splitmix64, cheaper
  than the generator it replaces), so a range rebuilds the same noise. `WavStreamTest` now checks 16-bit seeks too
  (it skipped them, "not repeatable across seeks") and that the dither is triangular (mean 0, variance 1/6).
  Verified on the Moto: a range from the soak's resume point and one from an odd byte inside a sample equal the
  whole render from there, byte for byte.
- **Cold start**, staging build (built like a release), compiled, Moto: 292 ms to the first frame (`am start -W`;
  budget 700 ms). The release build had not been measured on the Moto (M11: debug build 1.66–1.74 s).
- **200 % font pass** (11-testing-hardening.md task 9), in this app only (new debug command `font`; the phone's own
  font setting is untouched): the player and Settings hold (lines wrap or end in "…"). Song and folder rows had a
  fixed height: the second line lost its descenders ("Mixed Baq") and rows touched. Rows now take at least their
  height and grow with the text. The mini player is a 64 dp bar: its two lines follow the system text up to 130 %
  (the open player shows them at full size). Checked again at 200 %: rows readable, nothing clipped.
- The debug overrides (`density`, `font`) recreated the app's whole content when turned on or off, so the screen fell
  back to the library root; a scripted Back then closed the app and the weather app behind it came forward, and one
  scripted tap landed on its first-run screen (on its logo: nothing changed; the phone was put back at the launcher).
  The overrides now keep the app's state; the scripts tap only what the screen dump shows.
- **Memory** (11-testing-hardening.md task 6, capped at 15 minutes): playing (muted), Songs-tab flings every 20 s,
  staging build: total PSS 104 MB at the start, 114.4 MB at 5 minutes, 114.5 at 10, 126.1 at 15 while scrolling,
  107.3 MB a minute later at rest (Java heap 20, native 19, graphics buffers 39 MB). No growth that stays; the
  plan's hour with samples 30 minutes apart is the owner's to run.
- **Lifecycle checks** (task 3, this app only), Moto:
  - Rotation (this app's orientation, not the phone's): **the screen fell back to the library's first tab** (Songs
    became Folders), and an open player or sheet would have closed: navigation lived in plain `remember` and the
    activity was recreated. The activity now handles orientation, size, density, font scale and night-mode changes
    itself (Compose redraws for them), so rotation, split screen and a system dark-mode switch keep every screen.
    Checked: Songs stays Songs, same process; the landscape layout draws.
  - Theme change (Light, then Dark): no crash, state kept.
  - Process death in the background (paused, Home, then the process ended): `am kill` leaves it alone while its
    media service lives, so the debug `die` ended it as the system would; on restart the queue, the track and the
    position (58,363 ms) were exactly as before.
  - Storage permission revoked while running (`pm revoke` on our package): Android ends the app; it opens on its
    welcome screen ("Get started"); with the permission granted again: library (20 tracks), queue and track as
    before.
  - Not run: split screen with another app and removing storage (another app and an SD card would be needed).
- **Resampler alias level** (01-native-decoder.md T6 item 7; new `ResamplerAliasTest` on the Moto): one-second
  tones made at 96 kHz with 50 ms fades, played at 44.1 kHz; output level against the tone:

  | quality | 20 kHz | 22.6 kHz | 23 kHz | 24 kHz | 26 kHz and up |
  |---|---|---|---|---|---|
  | 0 Normal | -1.3 dB | -15.3 | -19.9 | -36.5 | -91.5 to -115 |
  | 1 High (default) | -0.1 | -21.2 | -31.9 | -86.9 | -127 to -136 |
  | 2 Very high | -0.0 | -38.9 | -76.1 | -138.4 | -140 to -143 |

  Passband to 10 kHz within 0.1 dB at every quality, no sample beyond full scale. The default stays above -100 dB
  only for content within 2 kHz above the output's Nyquist frequency (its aliases land at 20–22 kHz, where music
  carries little at those levels); from 26 kHz up it is below -127 dB. libsoxr is not needed (the plan's condition
  read as audible aliasing); "Very high" is there for anyone who wants it. With abrupt tone starts the filter rang:
  one sample reached 1.04 at High for a -1 dBFS 22.6 kHz tone, as any steep filter would on a step.
