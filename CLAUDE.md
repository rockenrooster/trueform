# Trueform Music Player — notes for Claude sessions

Android music player (Kotlin, Compose, Media3, FFmpeg). Status: implemented through M11; `docs/PROGRESS.md` says what was verified and what was not. Scripted soaks: 15 minutes at most (the owner).

**Start here:** `docs/plan/PLAN.md` (master plan, decisions, milestones), then the subplan of the current
milestone in `docs/plan/`. `docs/design/mockup.html` is the visual reference. `docs/PROGRESS.md` (create it at M0)
records what was built, measured and decided.

## Rules

- Small, focused changes; no unrelated edits; no destructive git commands unless asked. Commit at working
  checkpoints.
- **Push only when the owner asks.** The repository is public and a push to `main` publishes a release.
- Builds and tests on this PC use at most 4 CPU cores: run them through `scripts\*.ps1` (they dot-source
  `scripts\env.ps1`), Gradle with `--max-workers=4`. Never run `gradlew --stop`.
- Run builds and tests when a step needs them as proof, not by reflex.
- Judge smoothness (frame times, jank) with the staging build (`scripts\build.ps1 -Config staging`: the test app built
  like a release, debug commands included), never the debug build: Compose is several times slower when debuggable.
- Git: `C:\Program Files\Git\cmd\git.exe -c http.sslBackend=schannel` (`Invoke-TfGit`). `gh` works as is.
- Never read, print, copy or commit anything under `keys/`.

## Shared phones

Other Claude sessions use the same phones at the same time.

1. Every adb command has `-s <serial>`.
2. Nothing device-wide: no `adb kill-server`, no reboot, no `logcat -c` (read with `logcat -d --pid=<pid>`), no
   `settings put`, no volume changes, no stopping or uninstalling other packages.
3. Before launching or sending input, check the foreground app; if it is neither the launcher nor ours, wait or
   use another phone.
4. Lock files: `D:\Stuff\DevFolder\Android_Games\Raveform\captures\locks\<serial>.lock`. A lock younger than 30
   minutes with another name means busy. Write `trueform` and the time before a run; delete it after.
5. Scripted runs are muted inside the app (debug command `mute`) unless the owner asked to listen.
6. Development uses the application id `dev.trueform.player.debug`. Never install over or stop
   `dev.trueform.player` (the owner's daily copy) unless asked.
7. Device serials stay in the untracked `scripts\devices.local.ps1`.

## UI rules (from the owner)

Human readable and space-efficient. Clear, high-contrast hover and pressed states on everything interactive
(`Modifier.tfClickable`; see `docs/plan/04-ui-ux.md`).

## When the plan is wrong

If an API, version or behaviour differs from what a plan file says, fix the plan file in the same commit as the
code and note it in `docs/PROGRESS.md`.
