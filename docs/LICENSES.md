# Third-party software in Trueform

The app's Settings → Advanced → Licenses shows the same list.

## FFmpeg 8.1.3 — GNU Lesser General Public License 2.1 or later

Trueform decodes audio with FFmpeg's libavformat, libavcodec, libswresample and libavutil, linked statically into
`libtrueform.so`. The build is the ffmpeg.org release tarball (`ffmpeg-8.1.3.tar.xz`, SHA-256 pinned in the script),
configured audio-only without `--enable-gpl` or `--enable-nonfree`.

- **Source and recipe**: [`scripts/build-ffmpeg.sh`](../scripts/build-ffmpeg.sh) downloads the exact source, checks
  its SHA-256 and builds the libraries for Android (NDK r28c, arm64-v8a, API 29). The configuration is printed into
  `third_party/ffmpeg/BUILD_INFO.txt`.
- **Rebuilding and relinking with a modified FFmpeg**: change or replace the FFmpeg source the script builds, run it,
  then build the app (`gradlew assembleRelease`): `app/src/main/cpp/CMakeLists.txt` links the libraries from
  `third_party/ffmpeg`. All of Trueform's own source code is in this repository.
- License text: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

## AndroidX (Media3, Compose, Activity, Lifecycle, AppCompat, Palette) and Kotlin coroutines — Apache License 2.0

https://www.apache.org/licenses/LICENSE-2.0

## Google Play services Cast framework — Android Software Development Kit License

Used for Chromecast only, and only where Google Play services is installed.
https://developer.android.com/studio/terms

## Inter typeface — SIL Open Font License 1.1

© The Inter Project Authors (https://github.com/rsms/inter). https://openfontlicense.org

## Material Symbols (icon outlines) — Apache License 2.0

© Google. https://github.com/google/material-design-icons
