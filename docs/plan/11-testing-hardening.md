# M11 — Testing, hardening and 1.0

This file is also the testing reference for every earlier milestone: where each kind of check lives and how to run
it on this PC and these phones without disturbing anyone.

## 1. Layers

| Layer | Where | Runs on | What it proves |
|---|---|---|---|
| JVM unit tests | `app/src/test` | the PC (`scripts\test.ps1`), CI | DSP maths, queue and window logic, WAV and HTTP arithmetic, parsers, the settings registry, theme contrast, the updater's logic |
| Device tests | `app/src/androidTest` | one phone (`scripts\test.ps1 -Device <name>`) | the native decoder against the corpus, gapless joins, the scanner on a real file system, waveform analysis |
| Scripted device runs | `scripts\device.ps1`, `scripts\soak.ps1` + the debug hooks (M2 T8) | one phone | the assembled app: playback state, underruns, resume, timings |
| Owner checks | a person with ears and hardware | phone, headset, car, Chromecast | sound, feel, Bluetooth and cast behaviour |

Rule: logic goes in JVM tests; anything that needs FFmpeg or Android storage goes in device tests; anything that
needs time goes in a scripted run; only what truly needs ears goes to the owner.

## 2. Devices

| Device | Role |
|---|---|
| OnePlus 12R (120 Hz, recent Android, strict battery manager) | primary; performance targets; cast soak |
| Motorola One 5G Ace (Android 11) | the floor for speed and the oldest API tested |
| Galaxy Tab A11+ | wide layout |
| Fire 7 (Fire OS 8, no Google Play services) | no-Cast path, low-end sanity |

Android 10 (API 29) is supported on paper (`requestLegacyExternalStorage`) but no API 29 device is on hand: try an
emulator image once in M3 for the permission and file-listing path, and say in the README that Android 11+ is what
is tested.

The shared-device rules in `PLAN.md` section 8 apply to every run: serial on every command, nothing device-wide,
foreground check, lock file, muted in the app, tidy up (`am force-stop` on **our** package only).

## 3. Scripted runs

`scripts\device.ps1` sends one debug command and prints the `TFSTATE` line. `scripts\soak.ps1 -Device <name>
-Minutes <n> -Path <folder> [-Cast]` does a long run:

1. Lock the phone, check the foreground, deploy, mute, `play_path`.
2. Every 60 s: `dump`; append the state line to `captures\soak-<date>.jsonl`; stop early on an error state.
3. At the end: collect `logcat -d --pid=<pid>` lines tagged `TFAUDIO|TFQUEUE|TFSVC|TFCAST`, pull `cast.log` when
   casting, `force-stop` our package, remove the lock.
4. Print a verdict: minutes played, track transitions, underruns, discontinuities, restarts of the process,
   longest gap between position samples.

Wireless adb keeps Wi-Fi active and can hide power-save problems. For the final local and cast soaks, start the
run, disconnect adb, and read the app's own log files afterwards.

## 4. Budgets (fill the measured column in PROGRESS.md)

| Measure | Budget (OnePlus 12R / Moto) | Set in |
|---|---|---|
| Cold start to a populated library | 700 ms / 1500 ms | M4 |
| Library scroll, 95th percentile frame | 8.3 ms / 16.6 ms | M4 |
| Tap to first sound | 300 ms / 500 ms | M4 |
| Seek settles | 300 ms | M2 |
| First scan, 10,000 files: tree / tags | 15 s / 3 min (OnePlus) | M3 |
| Rescan, nothing changed, 10,000 files | 10 s (OnePlus) | M3 |
| Waveform, 4-minute FLAC / MP3 | 1.5 s / 3 s (OnePlus), 4 s / 7 s (Moto) | M5 |
| DSP, one 100 ms buffer: 10 bands 44.1 kHz / 31 bands 192 kHz | 0.5 ms / 6 ms (Moto) | M6 |
| CPU while playing, screen off, FLAC 16/44.1 with a 10-band EQ | under 3% of one core averaged over 5 minutes (`top -b -n 1 -p <pid>` sampled) | M11 |
| Underruns in a 60-minute local run, an 8-hour local run | 0 | M2, M11 |
| Receiver buffering events in a 3-hour cast | 0 after each item's start | M7 |
| Release APK size | under 15 MB | M10 |

A missed budget is a finding to explain in PROGRESS.md (with the measurement), then fix or renegotiate with the
owner. Measure before optimising.

## 5. Hardening tasks (M11)

1. **Crash capture**: an uncaught-exception handler writes the stack trace and the last `TFSTATE` to
   `filesDir/last-crash.txt`; the next start offers "Trueform closed unexpectedly · Share report". No network, no
   analytics.
2. **Strict checks in debug**: `StrictMode` (disk and network on the main thread, leaked closables) logging only;
   fix what it reports.
3. **Lifecycle torture** (our app in front only): rotate, split-screen, font scale change, theme change, process
   death with "Don't keep activities" left untouched (use `am kill` on our package while backgrounded), permission
   revoked while running (`pm revoke` on our package), storage removed.
4. **Long runs**: local 8 hours on battery, screen off; Bluetooth 2 hours; cast 3 hours (M7's protocol) on two
   phones.
5. **Hostile library**: a folder with 20,000 small files, zero-byte and truncated files, files with huge embedded
   art (20 MB), tags with invalid text, very long names, a two-hour file, a 384 kHz file, a DSD file.
6. **Memory**: after an hour of browsing and playing, total PSS (`dumpsys meminfo <pkg>`) is stable (no growth of
   more than 20 MB between two samples 30 minutes apart).
7. **Baseline profile** (only if the cold-start or scroll budget is missed): add `profileinstaller` and a generated
   baseline profile for start-up and library scrolling.
8. **Release build review**: R8 on, no debug hooks in the release manifest (`aapt2 dump xmltree`), no logging of
   file paths at info level, the exported components are exactly: the activity, the playback service, the artwork
   provider, the Bluetooth receiver.
9. **Fresh-eyes pass** over the UI against `04-ui-ux.md`: states, spacing, contrast, TalkBack, 200% font.

## 6. The 1.0 checklist (the owner signs this off)

Core

- [ ] Fresh install → permission → folders → browsing the hierarchy in under a minute on the owner's library
- [ ] Every format in the owner's library plays; WMA plays; hi-res plays
- [ ] Gapless albums are gapless
- [ ] Eight hours of local playback, screen off, no dropout
- [ ] Queue, shuffle, repeat, auto-advance to the next folder behave as described

Sound

- [ ] With EQ off and gain neutral the output is bit-identical to the decoded audio (transparency test)
- [ ] EQ: 5/10/15/31 bands and the four knobs; no clipping at any setting; `LIMIT` shows when it works
- [ ] ReplayGain modes

Look and feel

- [ ] The four key screens match the mockup; the Trueform seekbar is correct and smooth
- [ ] Pressed, hover and focus states are obvious everywhere
- [ ] Budgets met or explained

Away from the screen

- [ ] Bluetooth: controls, metadata, artwork, resume, per-output EQ
- [ ] Chromecast: the three-hour soak, the Wi-Fi blip, the process kill
- [ ] Notification, lock screen, sleep timer, audio focus

Shipping

- [ ] A push publishes a release; an installed build updates itself and keeps its data
- [ ] README, LICENSES, RELEASE_PIPELINE are accurate
- [ ] Every setting in `09-settings.md` works; a fresh install needs none of them changed

## 7. What "done" means for a task

A task is done when its code is in, its tests pass, its check was actually run on a device where one is named, and
PROGRESS.md says what was measured. "Not verified" is an acceptable entry; a claim without a run is not.
