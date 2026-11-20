# M10 — GitHub releases and the in-app updater

Goal: every push to `main` publishes a signed APK as a GitHub release; the installed app notices a newer build,
downloads it, verifies it and hands it to Android's installer.

The model is Raveform. Read these first:

- `D:\Stuff\DevFolder\Android_Games\Raveform\.github\workflows\release.yml`
- `D:\Stuff\DevFolder\Android_Games\Raveform\docs\RELEASE_PIPELINE.md`
- `D:\Stuff\DevFolder\Android_Games\Raveform\scripts\release-key.ps1`
- `D:\Stuff\DevFolder\Android_Games\Raveform\android\app\build.gradle` (signing and build number)
- `…\Raveform\android\app\src\main\java\dev\raveform\game\RaveformActivity.java` lines 284-344 (the update check)

Differences from Raveform, all deliberate:

| Raveform | Trueform | Why |
|---|---|---|
| The update pill opens the release page in a browser | The app downloads and installs the APK itself | The owner's choice |
| `versionCode` is always 1 | `versionCode` = the build number | An update must be a higher version for the installer; local debug builds use another application id, so they never collide |
| Without signing secrets the workflow publishes an APK signed with a throwaway key | Without signing secrets **nothing is published** (the APK stays with the run) | An in-app update signed with a different key cannot install; offering it would be a broken update |
| Fetches SDL and other sources | Builds or restores FFmpeg (`scripts/build-ffmpeg.sh`, cached) | |

## T1. Before anything is pushed

1. Ask the owner for the go-ahead to create the public repository and push. Nothing leaves the PC before that.
2. Check the whole history for anything that must not be public: keystores, passwords, tokens, device serial
   numbers, personal data, music that is not ours (the test corpus must be synthetic tones only). Search by file
   name and by content. Device serials live in the untracked `scripts\devices.local.ps1`.
3. `gh repo create rockenrooster/trueform --public --source . --remote origin
   --description "Trueform Music Player: folder-based hi-fi music player for Android"`, then push `main` with
   `Invoke-TfGit`.

## T2. The release key (the owner runs this)

Port `release-key.ps1`: keystore `keys\trueform-release.keystore` (PKCS12, RSA 4096, alias `trueform`, 10,000
days), repository `rockenrooster/trueform`, secrets `TF_KEYSTORE_BASE64`, `TF_KEYSTORE_PASSWORD`, `TF_KEY_ALIAS`,
`TF_KEY_PASSWORD`. It asks for the password interactively and passes values to `gh secret set` on standard input.

No session may read, print or copy the keystore or its password. Tell the owner to back both up off the PC: a build
signed with any other key cannot update an installed copy.

## T3. `.github/workflows/release.yml`

Start from Raveform's file. Shape:

```yaml
name: Release APK
on:
  push:
    branches: [main]
    paths-ignore: ['docs/**', '**/*.md']
  workflow_dispatch:
concurrency:
  group: release-apk-${{ github.ref }}
  cancel-in-progress: true
permissions:
  contents: write
defaults:
  run:
    shell: bash
jobs:
  apk:
    runs-on: ubuntu-24.04
    timeout-minutes: 60
    env:
      TF_NDK: '28.2.13676358'
      TF_CMAKE: '3.22.1'
    steps:
      - uses: actions/checkout@v7
        with: { fetch-depth: 0 }                 # the commit count is the build number
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: '17', cache: gradle }
      - name: Android SDK packages
        run: |
          sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
          yes | "$sdkmanager" --licenses > /dev/null || true
          "$sdkmanager" --install "ndk;$TF_NDK" "cmake;$TF_CMAKE" "platforms;android-36" > /dev/null
      - name: Restore FFmpeg
        id: ffmpeg
        uses: actions/cache@<current major>
        with:
          path: third_party/ffmpeg
          key: ffmpeg-${{ env.TF_NDK }}-${{ hashFiles('scripts/build-ffmpeg.sh') }}
      - name: Build FFmpeg
        if: steps.ffmpeg.outputs.cache-hit != 'true'
        run: NDK="$ANDROID_HOME/ndk/$TF_NDK" bash scripts/build-ffmpeg.sh
      - name: Unit tests
        run: ./gradlew --no-daemon --console=plain :app:testDebugUnitTest   # AGP 9: no release unit-test task
      - name: Build the release APK
        env:
          TF_KEYSTORE_BASE64: ${{ secrets.TF_KEYSTORE_BASE64 }}
          TF_KEYSTORE_PASSWORD: ${{ secrets.TF_KEYSTORE_PASSWORD }}
          TF_KEY_ALIAS: ${{ secrets.TF_KEY_ALIAS }}
          TF_KEY_PASSWORD: ${{ secrets.TF_KEY_PASSWORD }}
        run: |
          trap 'rm -f "$RUNNER_TEMP/release.keystore"' EXIT
          if [ -n "$TF_KEYSTORE_BASE64" ]; then
            printf '%s' "$TF_KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.keystore"
            export TF_KEYSTORE="$RUNNER_TEMP/release.keystore"
            echo "TF_SIGNED=secrets" >> "$GITHUB_ENV"
          else
            echo "::warning::No signing secrets: this build is debug-signed and will not be published"
            echo "TF_SIGNED=none" >> "$GITHUB_ENV"
          fi
          unset TF_KEYSTORE_BASE64
          export TF_BUILD_NUMBER=$(git rev-list --count HEAD)
          ./gradlew --no-daemon --console=plain :app:assembleRelease
      - name: Package and write the notes          # as Raveform: apk, sha256, symbols, apksigner check, notes
        id: pkg
        run: …
      - name: Publish the release
        if: github.ref == 'refs/heads/main' && env.TF_SIGNED == 'secrets'
        env: { GH_TOKEN: '${{ github.token }}', TAG: '${{ steps.pkg.outputs.tag }}', TITLE: '${{ steps.pkg.outputs.title }}' }
        run: …                                     # gh release create/upload … --latest, as Raveform
      - name: Keep the APK with the run
        if: github.ref != 'refs/heads/main' || env.TF_SIGNED != 'secrets'
        uses: actions/upload-artifact@v7
        with: { name: 'trueform-${{ steps.pkg.outputs.tag }}', path: out/trueform.apk, retention-days: 7 }
```

Release contents:

- tag `build-<N>` (`N` = `git rev-list --count HEAD`), title `Trueform <versionName> build <N>`, marked latest;
- `trueform.apk` (so `releases/latest/download/trueform.apk` is always the newest), `trueform.apk.sha256`
  (`sha256sum` format), `trueform-symbols.zip` (the unstripped `libtrueform.so`), `mapping.txt` (R8);
- notes: version, build, commit, build time, install instructions ("Android 10 or later, 64-bit ARM"), the signing
  certificate's SHA-256 from `apksigner verify --print-certs`, and the commits since the previous `build-*` tag.

Look up the current major version of `actions/cache` when writing the file. Never rewrite the history of `main`:
the build number is the commit count and must only grow.

Exit check: a push to `main` produces the release in under 15 minutes on a cold cache and under 6 on a warm one;
`gh release download -R rockenrooster/trueform -p trueform.apk` then `adb install` works on a phone that never
had the app.

## T4. `Updater`

```kotlin
data class Release(val build: Int, val title: String, val notes: String, val pageUrl: String,
                   val apkUrl: String, val apkSize: Long, val shaUrl: String?)

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val fraction: Float) : UpdateState
    data class Ready(val release: Release, val file: File) : UpdateState
    data class Failed(val release: Release?, val reason: String) : UpdateState
}

class Updater(…) {
    val state: StateFlow<UpdateState>
    fun check(force: Boolean = false)
    fun download(release: Release)
    fun install(activity: Activity, ready: UpdateState.Ready)
}
```

**Check** (the same rules as Raveform's `checkForUpdate`, plus the setting):

- Never for a build without a number (`BuildConfig.BUILD_NUMBER == 0`: local and debug builds).
- When the library screen is entered or returned to, if "Check for updates" is on; `force` for "Check now".
- One request at a time, at most one per 60 s (`SystemClock.elapsedRealtime`). GitHub allows 60 unauthenticated
  requests per hour per address.
- `GET https://api.github.com/repos/<BuildConfig.REPO>/releases/latest` with `Accept:
  application/vnd.github+json` and `User-Agent: trueform-updater`, 8 s connect and read timeouts, body limited to
  1 MB, `HttpURLConnection` and `org.json` (no HTTP library). Nothing but the request is sent.
- `tag_name` must match `^build-(\d+)$`; the assets `trueform.apk` (URL, size) and `trueform.apk.sha256` are found
  by name. A newer build → `Available`. Any failure → stay `Idle`, log, no message (offline is normal).

**Download**: to `cacheDir/updates/trueform-<build>.apk.part`, following redirects, with progress; rename when
complete. Then verify, and delete the file on any failure:

1. SHA-256 of the file equals the first token of the `.sha256` asset.
2. `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)`: the package name is ours, the signing
   certificates equal the installed app's, the version code is higher than ours.

**Install**: `PackageInstaller`. Create a full-install session for our package (API 31+:
`setRequireUserAction(USER_ACTION_REQUIRED)`), copy the APK into it, `commit` with a `PendingIntent` to
`UpdateReceiver`. On `STATUS_PENDING_USER_ACTION` start the confirmation intent it carries from the activity; on
failure statuses show the message. Manifest: `REQUEST_INSTALL_PACKAGES`. If
`packageManager.canRequestPackageInstalls()` is false, first send the user to
`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for our package and continue when they return.

Old files in `cacheDir/updates` are deleted at start.

**UI**: a pill in the library top bar, "Update · build 128" (`accent` outline). It opens a sheet: title, notes
(plain text; lines starting with `-` as bullets), size, "Download and install" with progress, "View on GitHub",
and the line "Playback stops while the update installs". Settings → Updates has the toggle, "Check now" and the
build line.

JVM tests: parsing a saved real API response; tag and checksum-file parsing; the rate limit and busy rules with a
fake clock; the state machine with a fake HTTP layer (success, bad checksum, HTTP error, truncated download).

## T5. Documents

- `docs/RELEASE_PIPELINE.md`: where, what runs when, downloading, signing, the updater, versioning, in the style
  of Raveform's.
- `README.md`: what Trueform is, features, the install link
  (`https://github.com/rockenrooster/trueform/releases/latest/download/trueform.apk`), supported formats,
  screenshots, how to build (JDK 17, Android SDK, `scripts/build-ffmpeg.sh`, `gradlew assembleDebug`).
- `docs/LICENSES.md`: FFmpeg (LGPL 2.1 or later; tag; the build script is the recipe; how to rebuild and relink),
  Inter (OFL), AndroidX/Media3 (Apache 2.0), Coil, Google Play services Cast. An in-app Licenses screen shows the
  same text.

## Exit checks

1. Build `N` from GitHub is installed on a phone with a scanned library, a custom EQ and a queue.
2. A new commit is pushed; build `N + 1` is published.
3. Opening the library shows the pill within a minute. The sheet shows the notes. Download, verify, Android's
   install prompt, install.
4. After reopening: the version line shows `N + 1`; library, settings, EQ, queue and position are intact.
5. Negative checks on a debug-menu build of the updater pointed at fixtures: a corrupted APK is rejected on
   checksum; an APK signed with another key is rejected before the installer is shown.
6. A local debug build never contacts GitHub (no request in the log).
