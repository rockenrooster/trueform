# Trueform Music Player — master plan

Written 2026-11-20 for the implementing agent. Read this file first, then the subplan of the milestone you are on.
Every fact marked **(verified)** was checked on 2026-11-20 against the source named beside it. Everything else is a
design decision or an expectation to confirm on a device.

## 1. What the owner asked for

A music player for Android, similar to Poweramp:

- simple and efficient; ultra high audio quality
- EQ with automatic clipping adjustment, a configurable number of bands (preamp, 10, 31, …), or simple
  sub-bass / bass / mid / treble dials
- many file types (mp3, flac, m4a, wma, ogg, aac, …)
- folder hierarchy as the preferred way to browse; selectable music folders that are scanned automatically
- Chromecast that does not drop out in long background play (Poweramp does)
- Bluetooth
- lots of settings with reasonable defaults
- a Poweramp-style graph on the player screen that shows upcoming loudness
- a public GitHub repo with releases and an updater like Raveform's
- beautiful, with excellent UI and UX

Answers the owner gave on 2026-11-20:

| Question | Answer |
|---|---|
| Name | **Trueform Music Player** (launcher label `Trueform`) |
| The player-screen graph | A **waveform seekbar**: the whole track's loudness shape, coloured by bass / mid / treble, used as the seek bar. No live spectrum analyser. |
| Updater | **Download and install in the app** (verify SHA-256, then Android's install prompt) |
| Helpers | The owner asked for no subagents while planning. |

Identity: application id `dev.trueform.player` (debug builds `dev.trueform.player.debug`), repository
`rockenrooster/trueform` (the name was free on 2026-11-20, **verified** with `gh repo view`), release asset
`trueform.apk`, native library `libtrueform.so`, log tags `TF…`, environment variables `TF_…`.

## 2. Priorities and non-goals

Priorities, in order, when two of them pull apart:

1. Audio quality: nothing in the path truncates, clips or resamples without the user choosing it.
2. No dropouts: local, Bluetooth and Chromecast playback survive hours with the screen off.
3. Simple and efficient: one module, few dependencies, low CPU while playing.
4. Beautiful and fast UI.
5. Many settings, each with a default that needs no touching.

Not in version 1 (do not build; each is a later decision of the owner): live spectrum analyser, crossfade, lyrics,
tag editing, streaming services, home-screen widget, parametric EQ editor, loudness scanning of untagged files,
USB-DAC bit-perfect mode, gapless Chromecast, playback speed, Play Store distribution, ABIs other than `arm64-v8a`.

## 3. Decisions at a glance

| Area | Decision | Why |
|---|---|---|
| Language, UI | Kotlin, Jetpack Compose, Material 3, single `:app` module, manual dependency wiring (no Hilt) | The owner's other app here (SmsBlaster) is Kotlin + Compose. One module and no DI framework keep builds fast and the code small. |
| Playback pipeline | **Media3 ExoPlayer** for queue, buffering, seeking, gapless, audio focus, session, notification, Bluetooth controls | It is the maintained, battle-tested pipeline. Writing a native output engine would re-solve all of that. |
| Decoding | **Our own small FFmpeg JNI library** decodes every file to 32-bit float PCM. ExoPlayer plays it as a virtual WAV stream through its stock `WavExtractor`. | One decode path for all formats: WMA, APE and the rest work, MP3/AAC decode to float instead of 16-bit, and the same stream feeds Chromecast and the waveform analyser. Media3 has no ASF/WMA extractor, and its FFmpeg extension only supplies decoders, not demuxers. |
| DSP | Kotlin, 64-bit state, in a `ForwardingAudioSink` wrapped around `DefaultAudioSink` (float output) | **(verified, `DefaultAudioSink.java` 1.11.1 lines 753-760)**: with float output enabled, Media3 skips every custom `AudioProcessor`; with it disabled, everything is cut to 16-bit first. The sink wrapper is the one place that sees float PCM right before the `AudioTrack`. |
| EQ | Cascade of RBJ biquads; graphic 5/10/15/31 bands or 4-knob tone mode; automatic preamp from the computed response plus a look-ahead limiter | "Automatic clipping adjustment" is the preamp; the limiter is the guarantee. |
| Library | Direct file access (`java.io.File`) under selected folders, own scanner, tags read with FFmpeg, Room database | Real paths make folder browsing and FFmpeg trivial. No MediaStore metadata quirks, no SAF tree walking. |
| Queue | App-owned logical queue (`QueueController`); the player only ever holds a **window** of it | Chromecast cannot take a big queue in one message; a 20,000-song shuffled folder must work; shuffle must work on Chromecast (Media3's cast player ignores shuffle, **verified** `RemoteCastPlayer.java` line 837). One mechanism for local and remote. |
| Chromecast | Media3 `CastPlayer` (local + remote) with our own transfer logic; the phone serves each track over HTTP with exact length and Range support; the receiver pulls, buffers ahead and advances its own queue | The receiver keeps playing through phone Wi-Fi power-save, brief Wi-Fi loss and sender hiccups. No real-time stream, so no clock drift. See `07-chromecast.md` for the failure analysis. |
| Player-screen graph | Precomputed 512-column waveform with three-band colour, stored per track, drawn as the seek bar | Chosen by the owner. Works while casting because it does not need live audio. |
| Updater | GitHub `releases/latest`, tag `build-<commit count>`, in-app download, SHA-256 and signing-certificate check, `PackageInstaller` | Raveform's scheme (**verified**, `RaveformActivity.java` 284-344, `release.yml`) plus the in-app install the owner chose. |
| SDK levels | `minSdk 29`, `targetSdk 36`, `compileSdk` 36 (37 only if a dependency's AAR metadata demands it) | **(verified, developer.android.com local-network-permission)**: apps targeting 37 need the new `ACCESS_LOCAL_NETWORK` runtime permission to accept LAN connections, which the cast HTTP server does. Stay on 36 until that is handled deliberately. |
| License | No `LICENSE` file, like Raveform (**verified**: its repo has none). FFmpeg is LGPL; `docs/LICENSES.md` lists third-party notices and the build script is the relinking recipe. | The owner decides on a license; do not add one unasked. |

## 4. Architecture

```
                         ┌──────────────────────────── app process ────────────────────────────┐
 file on storage         │                                                                      │
      │                  │  libtrueform.so (FFmpeg: avformat + avcodec + swresample)            │
      ▼                  │    demux → decode (float) → stereo, rate cap/fixed → ReplayGain gain │
 NativeDecoder ──────────┤                                                                      │
      │                  │  WavStream: virtual WAV, exact length, seekable by byte offset       │
      ├──────────────────┼─► FfmpegWavDataSource ─► ExoPlayer (WavExtractor → sample queue →    │
      │                  │      MediaCodecAudioRenderer in PCM bypass)                          │
      │                  │        ─► DspAudioSink [preamp → EQ → balance/mono → limiter]        │
      │                  │        ─► DefaultAudioSink, float AudioTrack ─► speaker / BT / USB   │
      │                  │                                                                      │
      ├──────────────────┼─► CastHttpServer  /f/ original file   /r/ rendered WAV (+DSP)        │
      │                  │        ◄── Chromecast pulls with HTTP Range, buffers ahead           │
      │                  │                                                                      │
      └──────────────────┼─► WaveformAnalyzer ─► waveforms table ─► Trueform seekbar            │
                         │                                                                      │
  QueueController (logical queue, shuffle, repeat, auto-advance)                                │
      │  keeps a window of ~33 items in ▼                                                       │
  SessionPlayer (facade) ─► Media3 CastPlayer ─┬─ ExoPlayer (local)                             │
      ▲                                        └─ RemoteCastPlayer (Chromecast)                 │
  MediaLibrarySession in PlaybackService ◄── notification, lock screen, Bluetooth AVRCP, Auto   │
      ▲                                                                                         │
  Compose UI ── MediaController (transport) + direct StateFlows (queue, library, DSP, settings) │
                         └──────────────────────────────────────────────────────────────────────┘
```

Threads: the players and `QueueController` live on the main thread. Each `NativeDecoder` instance is used by one
thread only (an ExoPlayer loader thread, an HTTP worker, or the analyser). DSP runs on ExoPlayer's playback thread.
DSP parameters cross threads as immutable snapshots in a `@Volatile` field.

## 5. Toolchain and versions

Checked 2026-11-20. The versions exist; **their pairing is confirmed only by the first successful build** (milestone
M0). If a pairing fails, step down to the pair each tool's release notes name, and write the result here.

| Tool | Version | Source of the check |
|---|---|---|
| JDK | 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot`) | on this PC; AGP 9.5 needs JDK 17 |
| Android Gradle plugin | 9.5.1 | Google Maven index; release notes: minimum Gradle 9.6.0, build tools 36.0.0, default NDK 28.2.13676358, highest API 37 |
| Gradle | 9.6.x (AGP 9.5's minimum and default; 9.9.2 is the newest but not listed as tested) | services.gradle.org, AGP notes |
| Kotlin | 2.4.21, with the Compose compiler plugin of the same version | Maven Central. AGP 9 has built-in Kotlin: do **not** apply `org.jetbrains.kotlin.android` (developer.android.com/build/migrate-to-built-in-kotlin) |
| KSP | 2.3.14 | Maven Central |
| Compose BOM | 2026.11.01 (Material 3 1.5.0) | Google Maven index |
| Media3 | 1.11.1 (`exoplayer`, `session`, `cast`) | Google Maven index, RELEASENOTES.md. Do not take 1.12 betas. |
| Cast framework | 22.3.1, MediaRouter 1.8.1: whatever `media3-cast` 1.11.1 pulls in; do not override | `media3-cast-1.11.1.pom` |
| Room | 2.8.5 | Google Maven index |
| DataStore Preferences | 1.3.0 | Google Maven index |
| Navigation 3 | 1.2.2 (`navigation3-runtime`, `navigation3-ui`) | Google Maven index |
| Lifecycle | 2.11.1 | Google Maven index |
| Activity Compose | 1.13.0 | Google Maven index |
| Coil | 3.6.3 (`coil-compose`) | Maven Central |
| Palette | 1.1.0 (`palette-ktx`) | Google Maven index |
| NDK, CMake | 28.2.13676358, 3.22.1 (both installed in `%LOCALAPPDATA%\Android\Sdk`) | on this PC |
| FFmpeg | tag `n8.1.3` | GitHub tags. 9.x exists; pin 8.1.3 because the API calls in `01-native-decoder.md` were written against 8.x. |
| GitHub Actions | `actions/checkout@v7`, `actions/setup-java@v6`, `actions/upload-artifact@v7` | the versions Raveform's workflow uses today |

On this PC: Android platforms 29, 30, 33, 35, 36, 37.0; build tools up to 36.0.0; `gh` 2.95.0 logged in as
`rockenrooster` with `repo` and `workflow` scopes; WSL 2 with an `Ubuntu` distribution (stopped).

## 6. Repository layout

```
Trueform/
  CLAUDE.md                     rules for sessions (short; points here)
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradlew(.bat)
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/dev/trueform/player/
      TrueformApp.kt  AppGraph.kt  MainActivity.kt
      audio/      Native.kt  NativeDecoder.kt  TrackProbe.kt  WavStream.kt  WavHeader.kt
      playback/   PlaybackService.kt  PlayerFactory.kt  FfmpegWavDataSource.kt  DspAudioSink.kt
                  QueueModel.kt  WindowPlanner.kt  QueueController.kt  SessionPlayer.kt  ResumeStore.kt
      dsp/        Biquad.kt  EqDesign.kt  Limiter.kt  DspParams.kt  DspChain.kt  DspController.kt
      library/    Db.kt  Entities.kt  Daos.kt  Scanner.kt  StorageRoots.kt  LibraryRepo.kt  Artwork.kt  ArtProvider.kt
      waveform/   WaveformAnalyzer.kt  WaveformData.kt  WaveformRepo.kt
      cast/       TfCastOptionsProvider.kt  CastHttpServer.kt  CastItemConverter.kt  CastTransfer.kt
                  CastGuard.kt  CastLog.kt
      system/     OutputRoutes.kt  BtConnectReceiver.kt  SleepTimer.kt
      settings/   Settings.kt  SettingsRegistry.kt
      update/     Updater.kt  UpdateReceiver.kt
      ui/         theme/  components/  library/  player/  queue/  eq/  search/  settings/  onboarding/
    src/main/cpp/ CMakeLists.txt  tf_decoder.h  tf_decoder.cpp  tf_jni.cpp
    src/debug/    AndroidManifest.xml  java/…/DebugReceiver.kt        (automation hooks, debug only)
    src/test/     JVM tests: dsp, queue, wav header, http range, updater parsing, natural sort
    src/androidTest/  device tests: decoder corpus, gapless, scanner;  assets/media/ (test corpus)
  third_party/ffmpeg/           built by scripts/build-ffmpeg.sh, git-ignored
  scripts/                      env.ps1 build.ps1 deploy.ps1 test.ps1 device.ps1 soak.ps1
                                build-ffmpeg.sh make-test-media.sh release-key.ps1
  docs/
    plan/                       this plan
    design/mockup.html          visual reference for the UI
    PROGRESS.md                 what was done, measured and decided (start at M0)
    RELEASE_PIPELINE.md  LICENSES.md
  keys/                         release keystore, git-ignored
  .github/workflows/release.yml
```

## 7. Milestones

Each milestone ends with a build that runs on a phone and passes its exit checks. Do them in order unless noted.

| # | Milestone | Subplan | Exit checks (short form) |
|---|---|---|---|
| M0 | Bootstrap: project, build, scripts, empty app | `00-bootstrap.md` | `assembleDebug` and `assembleRelease` pass; app starts on a phone; version table above confirmed |
| M1 | Native decoder: FFmpeg build, JNI, probe, test corpus | `01-native-decoder.md` | device tests: every corpus format decodes, lossless is bit-exact, frame counts exact, seeks land, gapless pairs join |
| M2 | Playback engine: WAV stream, ExoPlayer, sink, service, queue, resume | `02-playback-engine.md` | a folder plays by adb command; gapless; 60 minutes screen-off without underrun; notification and headset keys work |
| M3 | Library: database, scanner, folder tree, permissions | `03-library-scanner.md` | first scan and rescan times within budget; hierarchy correct; SD card removal loses nothing |
| M4 | UI foundation: design system, library screens, mini player, now playing, queue | `04-ui-ux.md` | screens match `docs/design/mockup.html`; frame-time budget met; TalkBack pass |
| M5 | Trueform seekbar: analyser, storage, drawing, scrubbing | `05-waveform-seekbar.md` | waveform of the test tones is correct; analysis time within budget; scrubbing seeks precisely |
| M6 | DSP and equaliser: chain, design, limiter, presets, ReplayGain, EQ screens | `06-dsp-equalizer.md` | JVM tests for response accuracy, limiter ceiling, stability, transparent bypass; knobs and sliders work live |
| M7 | Chromecast | `07-chromecast.md` | three-hour screen-off soak with no receiver buffering; Wi-Fi off/on does not restart or move playback |
| M8 | Bluetooth and system integration | `08-bluetooth-system.md` | car/headset controls, browse tree, per-output EQ, sleep timer, open-with |
| M9 | Settings complete, onboarding, tablet, themes, accessibility | `09-settings.md`, `04-ui-ux.md` | every setting in the registry is wired and searchable |
| M10 | Release pipeline and updater | `10-release-updater.md` | a push to `main` publishes `build-<n>`; an older installed build updates itself |
| M11 | Hardening and 1.0 | `11-testing-hardening.md` | all budgets measured and recorded; soak tests pass; checklist signed off by the owner |

M10 does not depend on M5–M9. Pull it forward (right after M4 is a good point) once the owner wants to install
builds from GitHub. M5 and M6 are independent of each other.

Every milestone adds its own settings to `SettingsRegistry` as it goes; M9 only finishes the screens.

## 8. Rules for the implementing agent

These come from the owner's instruction files and from Raveform's `CLAUDE.md`, which governs the same PC and the same
phones. `CLAUDE.md` in the project root repeats the ones that apply to every session.

- Small, focused changes. No unrelated edits. No destructive git commands unless asked. Commit at working
  checkpoints; a commit is the backup the owner's rules ask for before a risky change.
- **Push only when the owner asks.** The repository is public and every push to `main` publishes a release.
  Creating the GitHub repository is also the owner's call: ask once, at M10.
- Builds and tests on the PC use at most **4 CPU cores** (Raveform's rule for this PC). `scripts/env.ps1` sets the
  process affinity; Gradle gets `--max-workers=4`; `make -j4` in WSL. Never run `gradlew --stop`: other sessions
  share the Gradle user home.
- Run builds and tests when a step needs them to prove something, not by reflex.
- The phones are **shared with other Claude sessions**. Every adb command carries `-s <serial>`. Nothing
  device-wide: no `adb kill-server`, no reboot, no `logcat -c`, no `settings put`, no volume changes, no
  uninstalling or stopping other packages. Check the foreground app before launching ours; if someone else's app is
  in front, wait or use another phone. Honour the lock files in
  `D:\Stuff\DevFolder\Android_Games\Raveform\captures\locks\<serial>.lock` (a lock younger than 30 minutes with
  another name means busy; write your own as `trueform` plus the time and delete it afterwards).
- **Device test runs are muted in the app** (`player.volume = 0` through the debug hook) unless the owner asked to
  listen. The full pipeline still runs. Never change the phone's volume.
- The owner's daily copy is the release app. Development uses the `.debug` application id so the two never collide.
- Git on this PC: use `C:\Program Files\Git\cmd\git.exe -c http.sslBackend=schannel` (the PATH git is an MSYS2
  build and the user's gitconfig points at a missing CA file). `gh` works as is.
- UI rules from the owner: human readable and space-efficient; clear, high-contrast hover and pressed states on
  everything interactive.
- Performance rule from the owner: measure or reason about a bottleneck before changing an algorithm.
- Keep `docs/PROGRESS.md`: per milestone, what was built, what was measured (numbers, device, date), what was
  decided and why, and what is not verified.
- When this plan and reality disagree (an API differs, a pairing fails), fix the plan file in the same commit.

## 9. What needs the owner

| When | What |
|---|---|
| M1 | Permission to `apt-get install` build tools (`make`, `ffmpeg` for test media) in the WSL Ubuntu distribution, or the word to build FFmpeg in GitHub Actions instead |
| M2 onwards | A folder of real music on a test phone, and which phone is the daily driver |
| M6 | Listening: EQ feel, tone-knob frequencies, limiter transparency (numbers are tuned by measurement first, ears last) |
| M7 | A Chromecast on the same Wi-Fi as a test phone |
| M8 | A Bluetooth headset or car to confirm controls and metadata |
| M10 | Go-ahead to create the public repository and push; running `scripts\release-key.ps1` (it asks for the keystore password, which no session may see or store) |
| M11 | Sign-off on the 1.0 checklist; a decision on a license if one is wanted |

## 10. Risks and fallbacks

| Risk | Sign | Fallback |
|---|---|---|
| The AGP 9.5.1 / Kotlin 2.4.21 / KSP 2.3.14 pairing does not build | M0 sync or compile errors | Use the pairing the KSP and Compose release notes name; record it in section 5 |
| FFmpeg cross-build trouble in WSL | configure or link errors | Build the same script in GitHub Actions (`ubuntu-24.04`) and download the artifact |
| In-place DSP in the sink wrapper misbehaves on some path (read-only buffer, re-submitted buffer) | doubled EQ, crackle after a stall | The wrapper already copies when the buffer is read-only; if still wrong, move DSP one step later, into a forwarding `AudioOutput` behind a `ForwardingAudioOutputProvider` (both exist in Media3 1.11.1) |
| A device refuses a float `AudioTrack` at a high rate | `AudioSink.InitializationException` in the log | Cap the decoder output rate (setting "Maximum sample rate", default 192 kHz; drop to 96 or 48 on that device) |
| Cast session resume cannot adopt the running receiver queue | restart or position jump after Wi-Fi off/on | Reload the window at the receiver's last reported position (one short gap), documented as the known limit |
| The receiver rejects 24-bit or 96 kHz WAV | receiver error / idle with error | Setting "Cast quality" falls back to 16-bit 48 kHz |
| Exact length unknown for odd files (VBR MP3 without a header) | track ends early or late by milliseconds | Packet-count scan on open; analysis stores the decoded count; the stream pads or cuts to its declared length |
| OEM battery managers kill the service during long casts | soak test log shows the process restarting | Battery-optimisation exemption prompt; fixed port and key so the receiver's URLs stay valid after a restart |
| Media3 APIs used here are `@UnstableApi` | compile errors after a version bump | Stay on 1.11.1 until a milestone is finished; bump only between milestones |

## 11. Open questions (defaults in force until the owner says otherwise)

1. License: none, like Raveform.
2. Default accent colour: amber `#FFB547`, replaced on the player screen by a colour taken from the artwork.
3. Tapping a song plays its folder including subfolders, then continues with the next folder.
4. Chromecast gets EQ and ReplayGain when they are active (rendered WAV), and the untouched file when they are not.
5. When a cast session ends or drops, playback on the phone stays paused.
