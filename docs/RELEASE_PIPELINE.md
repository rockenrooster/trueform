# Release pipeline and updater

## Where releases come from

`.github/workflows/release.yml` runs on every push to `main` (pushes that only touch `docs/` or Markdown files are
skipped) and when started by hand (`gh workflow run release.yml -R rockenrooster/trueform --ref main`). On an
`ubuntu-24.04` runner it:

1. installs the NDK (28.2.13676358), CMake 3.22.1 and platform 37.2;
2. restores FFmpeg from the Actions cache, or builds it with `scripts/build-ffmpeg.sh` (the cache key is the NDK
   version and the script's hash, so changing the script rebuilds FFmpeg);
3. runs the JVM unit tests;
4. builds the release APK (R8, shrunk resources) signed with the key in the repository's secrets, with
   `TF_BUILD_NUMBER` = the commit count and `TF_COMMIT` = the short hash;
5. publishes a GitHub release:
   - tag `build-<N>` (`N` = `git rev-list --count HEAD`), title `Trueform <version> build <N>`, marked latest;
   - `trueform.apk` — so https://github.com/rockenrooster/trueform/releases/latest/download/trueform.apk is always
     the newest build;
   - `trueform.apk.sha256` (sha256sum format), `trueform-symbols.zip` (the unstripped `libtrueform.so`, for
     `ndk-stack`), `mapping.txt` (R8);
   - notes: version, build, commit, time, install instructions, the signing certificate's SHA-256 and the commits
     since the previous `build-*` tag.

Without the signing secrets nothing is published: the APK stays with the run for seven days. An APK signed with
another key could not update an installed copy, so offering it would be a broken update.

Never rewrite the history of `main`: the build number is the commit count and must only grow.

## Signing

`scripts\release-key.ps1` (run once, on the development PC) creates `keys\trueform-release.keystore` (PKCS12, RSA
4096, alias `trueform`, 10,000 days) with a random password. `keys\` is gitignored. The password is never shown or
stored in clear: it is kept encrypted with Windows DPAPI in `keys\trueform-release.password` (readable only by the
same Windows account; `scripts\release-key.ps1 -ShowPassword` prints it for a backup). The script sets the workflow's
secrets `TF_KEYSTORE_BASE64`, `TF_KEYSTORE_PASSWORD`, `TF_KEY_ALIAS` and `TF_KEY_PASSWORD` through `gh secret set` on
standard input, and starts a release build. `-SecretsOnly` sets the secrets again from the existing keystore.

**Back up the keystore and its password off the PC.** A build signed with any other key cannot update an installed
copy; users would have to uninstall (losing their library database and settings) to install it.

## Versions

- `versionCode` = the build number (`build-<N>`), so every release is an upgrade for Android's installer.
- `versionName` comes from `app/build.gradle.kts` (`0.1.0`).
- Local builds have build number 0, and debug builds use the application id `dev.trueform.player.debug`, so they
  install side by side with a release and never ask GitHub for updates.

## The in-app updater

`update/Updater.kt`, docs/plan/10-release-updater.md T4.

- **Check**: when the library screen opens (setting "Check for updates", on by default; Settings → Updates → "Check
  now" forces one). At most one request a minute, one at a time; `GET
  https://api.github.com/repos/rockenrooster/trueform/releases/latest`, 8 s timeouts, nothing else sent. A tag
  `build-<N>` with `N` above this build shows the pill "Update · build N" in the library's top bar. Offline or any
  error: nothing is shown.
- **Download**: into the app's cache, with progress. Then the file must match `trueform.apk.sha256`, be the package
  `dev.trueform.player`, be signed with the installed app's certificate and have a higher version code; otherwise it
  is deleted and the sheet says why.
- **Install**: Android's `PackageInstaller`; Android shows its own confirmation. The first time, Android asks to allow
  "Install unknown apps" for Trueform. Library, settings, EQ, queue and position stay: it is an update, not a new
  install.
