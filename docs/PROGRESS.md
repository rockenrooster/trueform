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
