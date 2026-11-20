#!/usr/bin/env bash
# Builds the FFmpeg static libraries that libtrueform.so links (docs/plan/01-native-decoder.md, T1).
# Linux only: WSL on the development PC, ubuntu-24.04 in GitHub Actions.
#
#   NDK=<path to a Linux NDK r28c> bash scripts/build-ffmpeg.sh [output dir]
#
# Without NDK the script downloads the Linux NDK 28.2.13676358 (r28c) into its work directory, checked against the
# SHA-1 that Google's SDK repository lists for it. Output (default third_party/ffmpeg): include/, lib/arm64-v8a/*.a,
# BUILD_INFO.txt. LGPL configuration: no --enable-gpl, no --enable-nonfree. This script is also the recipe for
# rebuilding and relinking FFmpeg (docs/LICENSES.md).
set -euo pipefail

FFMPEG_VERSION=8.1.3
FFMPEG_SHA256=7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3   # ffmpeg-8.1.3.tar.xz, 2026-11-20
NDK_REVISION=28.2.13676358
API=29
ABI=arm64-v8a
JOBS=${TF_JOBS:-4}        # the PC's 4-core rule

here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../third_party/ffmpeg}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
work="${TF_FFMPEG_WORK:-$HOME/.cache/trueform-ffmpeg}"
mkdir -p "$work"

log() { printf '[ffmpeg] %s\n' "$*"; }

# ---- the NDK -------------------------------------------------------------------------------------------------
if [ -z "${NDK:-}" ]; then
  NDK="$work/android-ndk-r28c"
  if [ ! -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]; then
    log "downloading the Linux NDK $NDK_REVISION"
    curl -fsSL https://dl.google.com/android/repository/repository2-3.xml -o "$work/repository.xml"
    read -r ndk_zip ndk_sha1 < <(python3 -I - "$work/repository.xml" "$NDK_REVISION" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for pkg in root.iter('remotePackage'):
    if pkg.get('path') != 'ndk;' + sys.argv[2]:
        continue
    for arch in pkg.iter('archive'):
        if (arch.findtext('host-os') or '') == 'linux':
            c = arch.find('complete')
            print(c.findtext('url'), c.findtext('checksum'))
            sys.exit(0)
sys.exit('no linux archive for ndk;' + sys.argv[2])
PY
)
    curl -fL --retry 3 -o "$work/$ndk_zip" "https://dl.google.com/android/repository/$ndk_zip"
    echo "$ndk_sha1  $work/$ndk_zip" | sha1sum -c -
    rm -rf "$work/ndk-unzip" && mkdir -p "$work/ndk-unzip"
    unzip -q "$work/$ndk_zip" -d "$work/ndk-unzip"
    rm -rf "$NDK" && mv "$work/ndk-unzip"/android-ndk-* "$NDK"
    rm -rf "$work/ndk-unzip" "$work/$ndk_zip"
  fi
fi
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
test -x "$TC/bin/clang" || { echo "no NDK toolchain at $TC" >&2; exit 1; }
ndk_rev=$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties" | tr -d '\r')
log "NDK $ndk_rev at $NDK"

# ---- the source ----------------------------------------------------------------------------------------------
tarball="$work/ffmpeg-$FFMPEG_VERSION.tar.xz"
if [ ! -f "$tarball" ]; then
  log "downloading FFmpeg $FFMPEG_VERSION"
  curl -fL --retry 3 -o "$tarball.part" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
  mv "$tarball.part" "$tarball"
fi
sha=$(sha256sum "$tarball" | cut -d' ' -f1)
if [ -n "$FFMPEG_SHA256" ] && [ "$sha" != "$FFMPEG_SHA256" ]; then
  echo "checksum mismatch for $tarball: $sha, expected $FFMPEG_SHA256" >&2; exit 1
fi
[ -n "$FFMPEG_SHA256" ] || log "UNPINNED tarball SHA-256: $sha (pin it in FFMPEG_SHA256)"
src="$work/ffmpeg-$FFMPEG_VERSION"
rm -rf "$src" && tar -xJf "$tarball" -C "$work"

# ---- configure -----------------------------------------------------------------------------------------------
DEMUXERS=aac,ac3,aiff,amr,ape,asf,caf,dsf,dts,eac3,flac,iff,matroska,mov,mp3,mpc,mpc8,ogg,tak,tta,w64,wav,wv,xwma
PARSERS=aac,aac_latm,ac3,dca,flac,mpegaudio,opus,vorbis
DECODERS=aac,aac_latm,ac3,alac,amrnb,amrwb,ape,dca,eac3,flac,mp1float,mp2float,mp3float,mpc7,mpc8,opus,tak,tta,vorbis
DECODERS=$DECODERS,wavpack,wmalossless,wmapro,wmav1,wmav2,wmavoice,dsd_lsbf,dsd_lsbf_planar,dsd_msbf,dsd_msbf_planar
DECODERS=$DECODERS,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be
DECODERS=$DECODERS,pcm_u8,pcm_s8,pcm_alaw,pcm_mulaw,adpcm_ms,adpcm_ima_wav

config=(
  --prefix="$work/install"
  --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile
  --cc="$TC/bin/aarch64-linux-android${API}-clang"
  --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip"
  --sysroot="$TC/sysroot"
  --enable-static --disable-shared --enable-pic
  --disable-everything --disable-programs --disable-doc --disable-autodetect --disable-debug
  --disable-avdevice --disable-avfilter --disable-swscale --disable-network
  --enable-protocol=file,fd
  --enable-demuxer=$DEMUXERS
  --enable-parser=$PARSERS
  --enable-decoder=$DECODERS
)
log "configure"
cd "$src"
./configure "${config[@]}" > "$work/configure.log" 2>&1 || { tail -n 40 "$work/configure.log"; cat ffbuild/config.log | tail -n 40; exit 1; }
if grep -n 'did not match anything' "$work/configure.log"; then
  echo "a component name above is not known to FFmpeg $FFMPEG_VERSION: fix the lists in this script" >&2; exit 1
fi

log "make -j$JOBS"
make -j"$JOBS" > "$work/make.log" 2>&1 || { tail -n 60 "$work/make.log"; exit 1; }
make install > /dev/null

rm -rf "$out/include" "$out/lib"
mkdir -p "$out/lib/$ABI"
cp -r "$work/install/include" "$out/include"
for lib in avformat avcodec swresample avutil; do cp "$work/install/lib/lib$lib.a" "$out/lib/$ABI/"; done
{
  echo "FFmpeg $FFMPEG_VERSION (ffmpeg.org release tarball, SHA-256 $sha)"
  echo "NDK $ndk_rev, API $API, ABI $ABI"
  echo "built $(date -u '+%Y-%m-%d %H:%M') UTC"
  echo "configure ${config[*]}"
} > "$out/BUILD_INFO.txt"
ls -l "$out/lib/$ABI"
log "done: $out"
