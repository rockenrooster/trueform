#!/usr/bin/env bash
# A library meant to break a scanner (docs/plan/11-testing-hardening.md hardening 5). Synthetic files only.
#   bash scripts/make-hostile-library.sh [out dir, default captures/hostile]   (WSL, needs the ffmpeg CLI)
# Push with:  adb -s <serial> push captures/hostile /storage/emulated/0/TrueformTest/Hostile
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../captures/hostile}"
rm -rf "$out" && mkdir -p "$out/Broken" "$out/Many"
ff() { ffmpeg -nostdin -hide_banner -loglevel error -y "$@"; }

# Broken files: empty, truncated, not audio at all
: > "$out/Broken/empty.flac"
ff -f lavfi -i "sine=f=440:d=20" -c:a flac "$out/Broken/whole.flac"
head -c 12000 "$out/Broken/whole.flac" > "$out/Broken/truncated.flac" && rm "$out/Broken/whole.flac"
head -c 50000 /dev/urandom > "$out/Broken/noise-not-audio.mp3"
printf 'this is text' > "$out/Broken/text.ogg"

# A 20 MB cover picture in an MP3
ff -f lavfi -i "nullsrc=s=6000x6000,geq=lum='random(1)*255':cb=128:cr=128" -frames:v 1 -q:v 1 "$out/big.jpg"
ff -f lavfi -i "sine=f=330:d=10" -c:a libmp3lame -b:a 128k "$out/plain.mp3"
ff -i "$out/plain.mp3" -i "$out/big.jpg" -map 0 -map 1 -c copy -id3v2_version 3 \
   -metadata:s:v title="Album cover" -metadata:s:v comment="Cover (front)" "$out/Huge cover art.mp3"
rm "$out/big.jpg" "$out/plain.mp3"

# Tags that are not valid text, and an absurdly long title
bad=$(printf 'Bad \xff\xfe\xc3 tag \x01\x02')
ff -f lavfi -i "sine=f=550:d=8" -c:a flac -metadata title="$bad" -metadata artist="$bad" -metadata album="$bad" \
   "$out/invalid tags.flac"
long_title=$(printf 'Long%.0s' $(seq 1 400))
ff -f lavfi -i "sine=f=660:d=8" -c:a libvorbis -metadata title="$long_title" "$out/long title.ogg"

# A very long file name (240 bytes with the extension)
name=$(printf 'n%.0s' $(seq 1 235))
ff -f lavfi -i "sine=f=770:d=8" -c:a aac -b:a 96k "$out/$name.m4a"

# Two hours, and 384 kHz
ff -f lavfi -i "sine=f=220:d=7200" -c:a libopus -b:a 24k "$out/two hours.opus"
ff -f lavfi -i "sine=f=1000:d=8:sample_rate=384000" -c:a flac -sample_fmt s32 "$out/384 kHz.flac"

# 20,000 small files in 200 folders
ff -f lavfi -i "sine=f=880:d=0.5" -c:a libmp3lame -b:a 32k "$out/tiny.mp3"
for d in $(seq -w 1 200); do
  mkdir -p "$out/Many/Folder $d"
  for f in $(seq -w 1 100); do cp "$out/tiny.mp3" "$out/Many/Folder $d/Track $f.mp3"; done
done
rm "$out/tiny.mp3"
find "$out" -type f | wc -l
du -sh "$out"
