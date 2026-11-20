# M0 — Bootstrap

Goal: a project that builds debug and release APKs from the command line on this PC, starts on a phone, and has
the scripts and conventions every later milestone relies on. No features.

Reference projects on this PC (read, do not edit):
- `D:\Stuff\DevFolder\AndroidApps\SmsBlaster` — the owner's Kotlin + Compose app (Gradle Kotlin DSL layout).
- `D:\Stuff\DevFolder\Android_Games\Raveform` — scripts (`scripts\env.ps1`, `build-android.ps1`,
  `deploy-android.ps1`, `release-key.ps1`), the release workflow and the device rules.

## Steps

### T1. Repository

1. `git init -b main` in `D:\Stuff\DevFolder\AndroidApps\Trueform` (the folder already holds `docs/` and
   `CLAUDE.md`). No remote yet (M10).
2. `.gitignore`: `/.gradle/ /build/ /app/build/ /app/.cxx/ /local.properties /third_party/ffmpeg/ /keys/
   *.keystore *.jks *.p12 *.pfx /captures/ /.idea/ /.kotlin/ *.log /scripts/devices.local.ps1`.
3. `.gitattributes`: `* text=auto eol=lf`, `*.bat text eol=crlf`, `*.ps1 text eol=crlf`, binary for `*.png *.jpg
   *.webp *.ttf *.flac *.mp3 *.m4a *.ogg *.opus *.wma *.wav *.aiff *.wv`.

### T2. Gradle project

1. Gradle wrapper at the version AGP 9.5.1 names (9.6.x). `gradle/libs.versions.toml` holds every version from
   `PLAN.md` section 5.
2. Root `build.gradle.kts`: plugins `com.android.application` (9.5.1), `org.jetbrains.kotlin.plugin.compose`,
   `org.jetbrains.kotlin.plugin.serialization`, `com.google.devtools.ksp`, all `apply false`. No
   `org.jetbrains.kotlin.android`: AGP 9 compiles Kotlin itself.
3. `gradle.properties`:
   ```
   org.gradle.jvmargs=-Xmx3g -XX:ActiveProcessorCount=4 -Dfile.encoding=UTF-8
   org.gradle.workers.max=4
   org.gradle.caching=true
   android.useAndroidX=true
   android.nonTransitiveRClass=true
   ```
4. `app/build.gradle.kts`:
   - `namespace = "dev.trueform.player"`, `applicationId = "dev.trueform.player"`, `minSdk = 29`,
     `targetSdk = 36`, `compileSdk = 36`, `ndkVersion = "28.2.13676358"`, `ndk.abiFilters += "arm64-v8a"`.
   - Build number: `val buildNumber = providers.environmentVariable("TF_BUILD_NUMBER").orNull?.toIntOrNull() ?: 0`.
     `versionCode = if (buildNumber > 0) buildNumber else 1`, `versionName = "0.1.0"`.
     `buildConfigField("int", "BUILD_NUMBER", "$buildNumber")`,
     `buildConfigField("String", "REPO", "\"rockenrooster/trueform\"")`. Enable `buildFeatures.buildConfig`.
   - `debug`: `applicationIdSuffix = ".debug"`, `versionNameSuffix = "-debug"`, label override `Trueform Debug`.
   - `release`: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt` plus
     `proguard-rules.pro`; signing from the environment exactly as Raveform's `android/app/build.gradle` does
     (`TF_KEYSTORE`, `TF_KEYSTORE_PASSWORD`, `TF_KEY_ALIAS`, `TF_KEY_PASSWORD`; debug key when `TF_KEYSTORE` is
     unset). Keep `-keepattributes SourceFile,LineNumberTable` so crash traces are readable.
   - `compileOptions` Java 17. `buildFeatures.compose = true`.
   - `externalNativeBuild.cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }` is added in M1,
     not now.
5. Dependencies now: Compose BOM, `ui`, `material3`, `ui-tooling-preview`, `activity-compose`,
   `lifecycle-runtime-compose`, coroutines, and `androidx.appcompat:appcompat` (for the theme only; see T3). Later
   milestones add their own.

### T3. App shell

1. `TrueformApp : Application` creating `AppGraph` (a plain class with `lazy` members; filled in by later
   milestones).
2. `MainActivity : ComponentActivity`, edge to edge, one composable showing the name and
   `BuildConfig.BUILD_NUMBER`.
3. Manifest: `android:label="Trueform"`, a temporary adaptive icon (the final icon is M4), theme
   `Theme.AppCompat.DayNight.NoActionBar` parent (MediaRouter's dialogs need an AppCompat theme in M7; using it from
   the start avoids a later theme change), `android:requestLegacyExternalStorage="true"` (only API 29 reads it).
4. `proguard-rules.pro`: empty apart from the keep-attributes line; M1 adds the JNI rule.

### T4. Scripts (PowerShell 7, repo root)

Port from Raveform, trimmed to what this project needs:

- `scripts\env.ps1`: `$TF_ROOT`, `$TF_JDK`, `$TF_SDK` (`ANDROID_HOME` or `%LOCALAPPDATA%\Android\Sdk`), `$TF_ADB`,
  `$TF_GIT = 'C:\Program Files\Git\cmd\git.exe'`, `$TF_APP_ID = 'dev.trueform.player.debug'`; sets `JAVA_HOME` and
  `ANDROID_HOME` for the process; **the 4-core affinity block** (copy `RfCpuTopology`, `Get-RfCpuMask` and the
  affinity assignment from Raveform's `env.ps1` lines 21-87, renamed); `Invoke-TfGit` (schannel);
  `Invoke-TfAdb -Serial`. It dot-sources `scripts\devices.local.ps1` if present: an **untracked** file holding the
  `$TF_DEVICES` table (name → serials, copied from Raveform's `$RF_DEVICES`). Serial numbers do not go into the
  public repository; commit `scripts\devices.example.ps1` with made-up values instead.
- `scripts\build.ps1 [-Config debug|release]`: dot-sources `env.ps1`, runs
  `gradlew.bat --max-workers=4 :app:assemble<Config>`.
- `scripts\deploy.ps1 -Device <name> [-Config debug]`: resolves the serial, checks and writes the phone lock,
  `adb -s <serial> install -r <apk>`, removes the lock.
- `scripts\test.ps1`: `gradlew.bat --max-workers=4 :app:testDebugUnitTest`.
- `scripts\device.ps1 -Device <name> -Cmd <…>`: wraps the debug broadcast hooks of M2 (placeholder now).

### T5. Records

1. `docs/PROGRESS.md` with an M0 entry: versions that built, APK sizes, the phone it ran on.
2. Update `PLAN.md` section 5 if any version had to change.
3. First commit.

## Exit checks

- `scripts\build.ps1 -Config debug` and `-Config release` both succeed; Task Manager shows the Gradle daemon on at
  most 4 cores.
- `scripts\deploy.ps1 -Device op12r` installs `dev.trueform.player.debug`; the activity shows the name and
  `BUILD 0`.
- `aapt2 dump badging` on the release APK shows `package: name='dev.trueform.player'`, `sdkVersion:'29'`,
  `targetSdkVersion:'36'`, and only `arm64-v8a` once native code exists.

## Pitfalls

- AGP 9 fails with "Cannot add extension with name 'kotlin'" if `org.jetbrains.kotlin.android` is applied.
- `kotlinOptions {}` is gone under built-in Kotlin; use `kotlin { compilerOptions { } }` if an option is needed.
- The Kotlin version in effect is the Kotlin Gradle plugin on the build classpath, which the Compose and
  serialization plugins bring in at their own version; AGP only demands a minimum. `gradlew buildEnvironment`
  shows what was resolved. The Compose compiler plugin, the serialization plugin and KSP must all agree with it.
- A dependency may demand `compileSdk 37` through its AAR metadata. Raise `compileSdk` only; leave `targetSdk` at 36
  (see `07-chromecast.md`, "Android 17").
- Do not add `material-icons-extended`. Icons are a small set of vector drawables added in M4.
