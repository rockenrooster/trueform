# M1 — Native decoder (FFmpeg + JNI)

Goal: `libtrueform.so` opens any supported audio file by path, reports its tags and format, and delivers
sample-accurate, seekable, gapless **32-bit float stereo PCM**. Nothing else in the app touches FFmpeg.

Why our own JNI instead of Media3's FFmpeg extension: that extension only adds *decoders*. Media3 has no demuxer
for ASF (WMA), APE, WavPack, Musepack, TTA, DSF and so on, and the owner listed WMA. One native reader that does
demux and decode covers every format the same way and also feeds Chromecast rendering and waveform analysis.

## Decisions

| Topic | Decision |
|---|---|
| FFmpeg | tag `n8.1.3`, LGPL configuration (no `--enable-gpl`, no `--enable-nonfree`), audio only, no network, static libraries linked into one shared library |
| ABI | `arm64-v8a` only. `// ponytail: one ABI; add armeabi-v7a/x86_64 to build-ffmpeg.sh and abiFilters when a user needs them` |
| Output | always interleaved float32 little-endian, **always 2 channels** (mono is duplicated, more than 2 is downmixed). One channel layout for everything means fewer `AudioTrack` rebuilds and one DSP code path. |
| Sample rate | the source rate, capped at a maximum (default 192000), or one fixed rate chosen in settings. Conversion by `libswresample`. |
| Gain | a linear gain (ReplayGain) applied in native code after conversion |
| Access | by file path (`file` protocol). The `fd` protocol is compiled in for the later "open with" feature (M8). |
| Where it builds | `scripts/build-ffmpeg.sh` under WSL Ubuntu on this PC and on the Linux runner in CI. The output directory `third_party/ffmpeg/` is git-ignored. |

## T1. `scripts/build-ffmpeg.sh`

Runs on Linux (WSL or CI). Inputs: `NDK` (path to a Linux NDK r28c = 28.2.13676358), optional output directory.

1. Work on the Linux filesystem (`${TF_FFMPEG_WORK:-$HOME/.cache/trueform-ffmpeg}`), not under `/mnt/d` (slow).
2. Fetch `https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n8.1.3.tar.gz`. On the first run print its SHA-256;
   then pin that value in the script and fail on mismatch.
3. Configure (`TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64`, `API=29`):

   ```
   ./configure --prefix="$WORK/install" \
     --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
     --cc="$TC/bin/aarch64-linux-android${API}-clang" \
     --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
     --sysroot="$TC/sysroot" \
     --enable-static --disable-shared --enable-pic \
     --disable-everything --disable-programs --disable-doc --disable-autodetect --disable-debug \
     --disable-avdevice --disable-avfilter --disable-swscale --disable-network \
     --enable-protocol=file,fd \
     --enable-demuxer=aac,ac3,aiff,amr,ape,asf,caf,dsf,dts,eac3,flac,iff,matroska,mov,mp3,mpc,mpc8,ogg,tak,tta,w64,wav,wv,xwma \
     --enable-parser=aac,aac_latm,ac3,dca,flac,mpegaudio,opus,vorbis \
     --enable-decoder=aac,aac_latm,ac3,alac,amrnb,amrwb,ape,dca,eac3,flac,mp1float,mp2float,mp3float,mpc7,mpc8,opus,tak,tta,vorbis,wavpack,wmalossless,wmapro,wmav1,wmav2,wmavoice,dsd_lsbf,dsd_lsbf_planar,dsd_msbf,dsd_msbf_planar,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be,pcm_u8,pcm_s8,pcm_alaw,pcm_mulaw,adpcm_ms,adpcm_ima_wav
   ```

   The float MPEG decoders are named on purpose (`mp3float`, not `mp3`): lossy codecs must decode to float.
   **Validate every name**: capture configure's output and fail if it contains `did not match anything` (compare
   against `./configure --list-decoders`, `--list-demuxers`, `--list-parsers`). Fix the list here if a name changed.
4. `make -j4 && make install`. Copy `include/` and `lib/*.a` to `<out>/include` and `<out>/lib/arm64-v8a/`. Write
   `<out>/BUILD_INFO.txt`: tag, tarball SHA-256, NDK version, the configure line, the date.

Running it on this PC: `wsl -d Ubuntu -- bash scripts/build-ffmpeg.sh` from the repo root (the repo is at
`/mnt/d/Stuff/DevFolder/AndroidApps/Trueform` inside WSL). It needs `make`, `tar`, `curl`, `unzip` in WSL and the
Linux NDK zip from developer.android.com (verify its published SHA-1/SHA-256). **Ask the owner before installing
packages in their WSL distribution.** If WSL is not an option, run the same script in a GitHub Actions job and
download the artifact (needs the repository, M10).

Exit check: `third_party/ffmpeg/lib/arm64-v8a/libav{format,codec,util}.a` and `libswresample.a` exist; the four
together are a few megabytes; `BUILD_INFO.txt` is written.

## T2. CMake and the library

`app/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(trueform CXX)
set(FF ${CMAKE_SOURCE_DIR}/../../../../third_party/ffmpeg)
if(NOT EXISTS ${FF}/lib/${ANDROID_ABI}/libavcodec.a)
  message(FATAL_ERROR "FFmpeg is not built. Run scripts/build-ffmpeg.sh (docs/plan/01-native-decoder.md).")
endif()
add_library(trueform SHARED tf_decoder.cpp tf_jni.cpp)
target_compile_features(trueform PRIVATE cxx_std_20)
target_compile_options(trueform PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti -O2)
target_include_directories(trueform PRIVATE ${FF}/include)
foreach(lib avformat avcodec swresample avutil)        # this order
  target_link_libraries(trueform PRIVATE ${FF}/lib/${ANDROID_ABI}/lib${lib}.a)
endforeach()
target_link_libraries(trueform PRIVATE log m)
target_link_options(trueform PRIVATE -Wl,-Bsymbolic -Wl,--gc-sections -Wl,--exclude-libs,ALL)
```

Add `externalNativeBuild` to `app/build.gradle.kts`. Add to `proguard-rules.pro`:
`-keep class dev.trueform.player.audio.Native { *; }`.

Exit check: `llvm-readelf -l libtrueform.so` shows `LOAD` segments aligned to `0x4000` (16 KB pages; NDK r28
default). The stripped library is under 5 MB.

## T3. JNI surface

Register with `RegisterNatives` in `JNI_OnLoad` (no name mangling, safe under R8). Kotlin side:

```kotlin
internal object Native {
    init { System.loadLibrary("trueform") }

    external fun ffmpegVersion(): String
    external fun errorString(code: Int): String

    /** Tags and stream facts without decoding. Returns 0 or a negative AVERROR. */
    external fun probe(path: String, meta: Array<ByteArray?>, nums: LongArray): Int
    /** Embedded cover picture bytes (JPEG/PNG as stored), or null. */
    external fun extractCover(path: String): ByteArray?

    /** Opens a decoder. fd < 0 means "use path". outRate 0 = source rate (capped by maxRate).
     *  info out: [0] output rate, [1] total output frames, [2] 1 if exact, [3] source rate,
     *  [4] source bits per sample (0 for lossy), [5] source channels. Returns a handle, or 0. */
    external fun open(path: String, fd: Int, outRate: Int, maxRate: Int, quality: Int, info: LongArray): Long
    external fun setGain(handle: Long, linear: Float)
    /** Fills dst (a direct buffer) with up to maxFrames stereo float frames. Returns frames, 0 at end, <0 on error. */
    external fun read(handle: Long, dst: java.nio.ByteBuffer, maxFrames: Int): Int
    /** Positions the decoder so the next read starts exactly at this output frame. */
    external fun seek(handle: Long, frame: Long): Int
    /** Counts packet durations over the whole file (no decoding). For files whose duration was a bitrate guess. */
    external fun scanFrames(handle: Long): Long
    external fun close(handle: Long)
}
```

Rules for the glue:

- **Strings from files cross JNI as `byte[]`** and are decoded in Kotlin with `String(bytes, Charsets.UTF_8)`.
  `NewStringUTF` wants modified UTF-8 and aborts under CheckJNI on malformed tag text.
- `probe` index constants live in one Kotlin file (`TrackProbe.kt`) and one C++ header, both listing: `TITLE,
  ARTIST, ALBUM, ALBUM_ARTIST, GENRE, DATE, TRACK, DISC, COMPOSER, RG_TRACK_GAIN, RG_TRACK_PEAK, RG_ALBUM_GAIN,
  RG_ALBUM_PEAK, R128_TRACK_GAIN, R128_ALBUM_GAIN, CODEC, CONTAINER` and numbers `DURATION_US, SAMPLE_RATE,
  CHANNELS, BITS, BITRATE, HAS_COVER, DURATION_IS_ESTIMATE`.
- One handle is used by one thread. No global mutable state in native code.
- `av_log_set_level(AV_LOG_ERROR)`; route FFmpeg's log callback to logcat tag `TFFF` in debug builds.
- `NativeDecoder` (Kotlin) wraps a handle, implements `Closeable`, and owns one reusable direct `ByteBuffer`.

## T4. Decoder behaviour (`tf_decoder.cpp`)

Open:

1. `avformat_open_input`, then `avformat_find_stream_info`, `av_find_best_stream(AVMEDIA_TYPE_AUDIO)`.
2. `avcodec_alloc_context3`, `avcodec_parameters_to_context`, `thread_count = 1`, `avcodec_open2`.
3. `swr_alloc_set_opts2` to `AV_SAMPLE_FMT_FLT`, stereo, the output rate. Set `rematrix_maxval = 1.0` so a
   downmix cannot exceed full scale. Resampler quality presets (only matter when rates differ):

   | `quality` | Options |
   |---|---|
   | 0 Normal | library defaults |
   | 1 High (default) | `filter_size=64`, `cutoff=0.98`, `filter_type=kaiser`, `kaiser_beta=12` |
   | 2 Very high | `filter_size=128`, `cutoff=0.985`, `filter_type=kaiser`, `kaiser_beta=14`, `exact_rational=1` |

   These numbers are starting values: `// ponytail: swresample's own resampler; build with libsoxr if a
   measurement shows aliasing above -100 dB`. Check with the sweep test in T6. Measured 2026-11-21
   (`ResamplerAliasTest`, 96 → 44.1 kHz): High is below -127 dB from 26 kHz up and -87 dB at 24 kHz; only content
   within 2 kHz above Nyquist aliases higher (into 20–22 kHz). Very high is below -138 dB from 24 kHz. No libsoxr.
4. Total frames: from the stream duration (`AVStream.duration` in its time base, else `AVFormatContext.duration`),
   scaled to the output rate. `exact = (fmt->duration_estimation_method != AVFMT_DURATION_FROM_BITRATE)`.

Read loop: drain `swr_convert(swr, out, room, nullptr, 0)` first; when it returns 0, read a packet
(`av_read_frame`), `avcodec_send_packet`, pull frames with `avcodec_receive_frame` and push each into the resampler
(`swr_convert(swr, nullptr, 0, frame->extended_data, frame->nb_samples)`); at end of file send a null packet, then
drain. Skip packets of other streams. On `AVERROR_INVALIDDATA` from one packet, continue; after 50 consecutive
errors return the error. Apply the gain to the frames written.

Gapless: leave libavcodec's automatic sample skipping on (do not set `AV_CODEC_FLAG2_SKIP_MANUAL`). The demuxers
turn LAME/Xing delay and padding, MP4 edit lists and Ogg granule positions into skip instructions, and the decoder
drops those samples. T6 proves it per format.

Seek to output frame `F`:

1. Target time `t = F / outRate`. Seek the demuxer to `max(0, t - 0.1 s)` with `AVSEEK_FLAG_BACKWARD`,
   `avcodec_flush_buffers`, reset the resampler (`swr_init` again).
2. Decode forward. The first frame's presentation time gives its output frame index `P`; drop `F - P` frames before
   returning data. If `P > F` (a format that cannot seek back that far), continue from `P`.
3. The 0.1 s run-in gives MP3's bit reservoir and Opus/Vorbis pre-roll what they need.

`scanFrames`: remember the read position, `av_read_frame` over the whole file summing `pkt->duration` for the audio
stream, seek back, return the sum scaled to the output rate. Used when `exact` is false.

Crash guard for the callers (implemented in Kotlin, M3 and M2 use it): before `probe` or `open` of a file that has
never been read successfully, write its path to `filesDir/probing.txt`; delete the file after the call returns. If
that file exists at app start, the named file took the process down: mark it broken in the database and never
open it again without the user asking.

## T5. Probe and cover

- Read tags from both `fmt->metadata` and the audio stream's metadata (Ogg stores them on the stream); the stream
  wins on conflict. Keys are compared case-insensitively: `title, artist, album, album_artist, genre, date (or
  year), track, disc, composer, replaygain_track_gain, replaygain_track_peak, replaygain_album_gain,
  replaygain_album_peak, R128_TRACK_GAIN, R128_ALBUM_GAIN`.
- Kotlin parses: `track` and `disc` may be `3/12`; `date` may be `2019-05-01` (take the year); ReplayGain values
  look like `-6.54 dB`; R128 values are integers in 1/256 dB relative to −23 LUFS (add 5 dB to land on ReplayGain's
  −18 LUFS reference).
- `BITS`: `codecpar->bits_per_raw_sample`, else from the sample format for PCM-like codecs, else 0 (lossy).
- Cover: the stream with `AV_DISPOSITION_ATTACHED_PIC`; return `attached_pic.data` unchanged.
- `probe` must be fast: skip `avformat_find_stream_info` when the header already gave sample rate, channels and
  duration; call it only when one is missing. Budget: median under 5 ms per file from flash.

## T6. Test corpus and device tests

`scripts/make-test-media.sh` (WSL, needs the `ffmpeg` command there) writes `app/src/androidTest/assets/media/`
plus `manifest.json`. The corpus is committed (keep it under 6 MB) so later sessions need no WSL.

Sources, generated by the script:

- `tone.wav`: 3 s, 44.1 kHz, 16-bit stereo, 1 kHz left / 440 Hz right at −6 dBFS.
- `sweep.wav`: 6 s logarithmic sweep 20 Hz–20 kHz, 48 kHz, 24-bit.
- `hires.wav`: 2 s, 96 kHz, 24-bit, 10 kHz + 30 kHz tones.
- `bursts.wav`: 8 s: silence / loud / quiet / loud bass / loud treble sections of 1 s each (for M5).

Encodings of `tone.wav`: FLAC, ALAC (m4a), WavPack, AIFF, WAV float, MP3 CBR 320 and VBR V2 (LAME, with the
Xing/LAME header), MP3 VBR with the header stripped, AAC-LC (m4a), Vorbis (ogg), Opus, WMA v2 (`-c:a wmav2`), a
mono FLAC, a 5.1 FLAC. `hires.wav` to FLAC 24/96. Gapless pairs: `sweep.wav` cut at sample 144000 into `a` and `b`,
each half encoded to FLAC, MP3 (LAME header), AAC, Vorbis and Opus. `manifest.json` records per file: source file,
sample rate, exact frame count, lossless flag.

Instrumented tests (`androidTest`, run on a phone, assets copied to the app's cache directory first):

1. **Decode all**: every file opens and decodes to the end without error; output is stereo float.
2. **Lossless is bit-exact**: FLAC, ALAC, WavPack, AIFF, WAV: decoded samples scaled back to the source bit depth
   equal the source WAV sample for sample. Mono and 5.1 files have the right length and no clipping.
3. **Frame counts**: decoded frames equal the manifest count for lossless files and for lossy files that carry
   gapless information (LAME MP3, AAC in MP4, Vorbis, Opus). For the header-stripped MP3: `exact` is false and
   `scanFrames` is within 3 MP3 frames (3456 samples) of the truth.
4. **Lossy alignment**: cross-correlate decoded lossy audio with the source; the peak is at lag 0 (proves
   encoder-delay trimming) and the normalised correlation is above 0.99.
5. **Seek**: for each format, seek to 10 random frames; the first 2000 frames read after the seek match a
   straight-through decode at the same position (exact for lossless; correlation above 0.99 and lag 0 for lossy;
   lag within one frame-length for the header-stripped MP3).
6. **Gapless join**: decode `a` then `b`; total frames equal the uncut sweep; around the join the largest
   sample-to-sample step is no more than twice the largest step in the neighbouring 2000 frames. If AAC fails only
   because of how the test encoder wrote its padding, record the measured error in PROGRESS.md and keep the test
   with that tolerance.
7. **Resampler**: decode `sweep` FLAC at 44.1 kHz output with quality 1; no sample exceeds 1.0; record the
   spectrum's worst alias level in PROGRESS.md.
8. **Probe**: tags written by the script (title, artist, album, track `3/12`, ReplayGain) read back; a file with
   invalid UTF-8 in a tag does not crash.
9. **Hostile input**: a truncated file, a zero-byte file and a text file renamed to `.mp3` return errors, no crash.

`scripts/test.ps1 -Device <name>` runs them with `gradlew :app:connectedDebugAndroidTest` on one phone (lock rules
apply).

## Exit checks

- All T6 tests pass on the OnePlus 12R and on one Motorola One 5G Ace.
- Decode speed recorded in PROGRESS.md: a 4-minute FLAC 16/44.1 and a 4-minute MP3 320, full decode time on both
  phones (expected well under 2 s and 4 s).
- `docs/LICENSES.md` started: FFmpeg, its license (LGPL 2.1 or later), the tag, a pointer to
  `scripts/build-ffmpeg.sh` as the build recipe.

## Pitfalls

- DSDIFF (`.dff`) is handled by the `iff` demuxer; there is no `dff` demuxer.
- `swr_convert` buffers input it could not convert; always drain before feeding more.
- After `av_seek_frame` the first packets may still be from before the target: rely on timestamps, not on counts.
- Do not link FFmpeg as separate shared objects unless the owner asks; static keeps packaging to one file. The
  repository being public with the build script satisfies LGPL relinking.
- FUSE storage is slower than internal app storage; all timings are taken on `/storage/emulated/0`.
