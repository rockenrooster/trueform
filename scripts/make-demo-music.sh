#!/usr/bin/env bash
# A synthetic demo library for device tests (no one's music): albums in folders, tags, embedded and folder covers,
# hi-res, many formats, a gapless pair and a long track. Writes captures/demo/TrueformTest (git-ignored); push it
# with scripts\push-demo.ps1. Needs ffmpeg with libmp3lame, libopus, libvorbis (WSL: apt install ffmpeg).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../captures/demo/TrueformTest"
rm -rf "$out" && mkdir -p "$out"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

# song: a beat of P seconds, a 4-chord progression on base frequency B, kick, hat, bass, lead, sections.
song() {   # $1 P, $2 B, $3 length in s
  local P=$1 B=$2 L=$3
  local CH="if(eq(mod(floor(t/(4*$P)),4),0),1,if(eq(mod(floor(t/(4*$P)),4),1),0.8409,if(eq(mod(floor(t/(4*$P)),4),2),0.7491,0.8909)))"
  local F="($B*$CH)"
  local SEC="if(lt(t,8),0.35,if(lt(mod(t,64),48),1,0.55))"
  local KICK="if(lt(t,8),0,if(lt(mod(t,64),48),1,0))*0.55*exp(-14*mod(t,$P))*sin(2*PI*52*t*(1+0.5*exp(-30*mod(t,$P))))"
  local HAT="0.07*exp(-45*mod(t+$P/2,$P))*(random(0)*2-1)"
  local PAD="0.09*(sin(2*PI*$F*t)+0.6*sin(2*PI*$F*1.2599*t)+0.5*sin(2*PI*$F*1.4983*t)+0.25*sin(4*PI*$F*t))*(0.55+0.45*exp(-2.5*mod(t,4*$P)))"
  local BASS="0.22*sin(2*PI*$F/2*t)*(0.4+0.6*exp(-6*mod(t,$P/2)))"
  local LEAD="if(gt(mod(t,32),16),0.06*sin(2*PI*$F*2*t+2*sin(2*PI*5*t))*exp(-4*mod(t,$P/2)),0)"
  local FADE="min(1,($L-t)/4)"
  local E="($SEC*($PAD+$BASS+$LEAD)+$KICK+$HAT)*$FADE"
  echo "'${E}|${E}*0.97'"
}
gen() {  # $1 out wav, $2 P, $3 B, $4 length, $5 rate
  ff -f lavfi -i "aevalsrc=$(song "$2" "$3" "$4"):s=$5:d=$4" -c:a pcm_s24le "$1"
}
cover() { ff -f lavfi -i "gradients=s=1000x1000:c0=$2:c1=$3:c2=$4:x0=100:y0=80:x1=900:y1=950:duration=1:speed=0" -frames:v 1 "$1"; }
# meta TITLE ARTIST ALBUM TRACK YEAR: sets the array M for an encode
meta() { M=(-metadata "title=$1" -metadata "artist=$2" -metadata "album=$3" -metadata "track=$4" -metadata "date=$5" -metadata "genre=Demo"); }

tmp=$(mktemp -d)
cover "$tmp/a.jpg" 0xff5e4d 0xffb547 0x4a36b8
cover "$tmp/b.jpg" 0x0e3b52 0x7ce4ff 0x173a2a
cover "$tmp/c.jpg" 0x5a1d1d 0xffc24b 0x1a0d12

# Album A: FLAC 16/44.1 with a folder image
d="$out/Northern Static/Afterlight"; mkdir -p "$d"; cp "$tmp/a.jpg" "$d/cover.jpg"
i=1
for spec in "First Light:0.48:174.6:95" "Glass Harbor:0.5:196:150" "Low Tide Engines:0.46:164.8:130" "Paper Lanterns:0.55:220:110"; do
  IFS=: read -r name p b len <<<"$spec"
  gen "$tmp/s.wav" "$p" "$b" "$len" 44100
  meta "$name" "Northern Static" "Afterlight" "$i/4" 2024
  ff -i "$tmp/s.wav" -af aformat=s16 -c:a flac "${M[@]}" "$d/0$i $name.flac"
  i=$((i+1))
done

# Album B: MP3 V0 with an embedded cover
d="$out/Velvet Motor/Signal Fires"; mkdir -p "$d"
i=1
for spec in "Signal Fires:0.42:146.8:140" "Copper Wire:0.52:185:120" "Night Shift:0.45:138.6:160"; do
  IFS=: read -r name p b len <<<"$spec"
  gen "$tmp/s.wav" "$p" "$b" "$len" 44100
  meta "$name" "Velvet Motor" "Signal Fires" "$i/3" 2023
  ff -i "$tmp/s.wav" -i "$tmp/b.jpg" -map 0:a -map 1:v -c:a libmp3lame -q:a 0 -c:v mjpeg -id3v2_version 3 \
     -disposition:v attached_pic "${M[@]}" "$d/0$i $name.mp3"
  i=$((i+1))
done

# Hi-res: FLAC 24/96 (embedded cover) and 24/192
d="$out/Hi-Res"; mkdir -p "$d"
gen "$tmp/s.wav" 0.5 220 40 96000
meta "Ninety-Six" "Demo Hi-Res" "Hi-Res" "1/2" 2025
ff -i "$tmp/s.wav" -i "$tmp/c.jpg" -map 0:a -map 1:v -c:a flac -c:v mjpeg -disposition:v attached_pic "${M[@]}" "$d/01 Ninety-Six.flac"
gen "$tmp/s.wav" 0.44 246.9 30 192000
meta "One-Ninety-Two" "Demo Hi-Res" "Hi-Res" "2/2" 2025
ff -i "$tmp/s.wav" -c:a flac "${M[@]}" "$d/02 One-Ninety-Two.flac"

# Mixed formats in nested folders
d="$out/Mixed/Lossy"; mkdir -p "$d"
gen "$tmp/s.wav" 0.47 207.7 70 44100
meta "Harbor Lights (AAC)" "Mixed Bag" "Formats" 1 2022; ff -i "$tmp/s.wav" -c:a aac -b:a 256k "${M[@]}" "$d/01 Harbor Lights.m4a"
meta "Opal (Opus)" "Mixed Bag" "Formats" 2 2022;         ff -i "$tmp/s.wav" -c:a libopus -b:a 160k "${M[@]}" "$d/02 Opal.opus"
meta "Ember (Vorbis)" "Mixed Bag" "Formats" 3 2022;      ff -i "$tmp/s.wav" -c:a libvorbis -q:a 6 "${M[@]}" "$d/03 Ember.ogg"
meta "Windmill (WMA)" "Mixed Bag" "Formats" 4 2022;      ff -i "$tmp/s.wav" -c:a wmav2 -b:a 192k "${M[@]}" "$d/04 Windmill.wma"
d="$out/Mixed/Lossless"; mkdir -p "$d"
meta "Ledger (ALAC)" "Mixed Bag" "Formats" 5 2022;       ff -i "$tmp/s.wav" -af aformat=s16 -c:a alac "${M[@]}" "$d/05 Ledger.m4a"
meta "Wavelet (WavPack)" "Mixed Bag" "Formats" 6 2022;   ff -i "$tmp/s.wav" -af aformat=s16 -c:a wavpack "${M[@]}" "$d/06 Wavelet.wv"
ff -i "$tmp/s.wav" -af aformat=s16 -c:a pcm_s16le "$d/07 Plain Wave.wav"

# Gapless: one continuous piece cut in two, FLAC and MP3
d="$out/Gapless"; mkdir -p "$d"
gen "$tmp/g.wav" 0.5 196 80 44100
meta "Continuum I" "Gapless Test" "Gapless" 1 2025
ff -i "$tmp/g.wav" -af "atrim=end_sample=1764000,aformat=s16" -c:a flac "${M[@]}" "$d/01 Continuum I.flac"
meta "Continuum II" "Gapless Test" "Gapless" 2 2025
ff -i "$tmp/g.wav" -af "atrim=start_sample=1764000,asetpts=PTS-STARTPTS,aformat=s16" -c:a flac "${M[@]}" "$d/02 Continuum II.flac"
meta "Continuum I (MP3)" "Gapless Test" "Gapless MP3" 1 2025
ff -i "$tmp/g.wav" -af "atrim=end_sample=1764000" -c:a libmp3lame -q:a 2 "${M[@]}" "$d/03 Continuum I.mp3"
meta "Continuum II (MP3)" "Gapless Test" "Gapless MP3" 2 2025
ff -i "$tmp/g.wav" -af "atrim=start_sample=1764000,asetpts=PTS-STARTPTS" -c:a libmp3lame -q:a 2 "${M[@]}" "$d/04 Continuum II.mp3"

# Long: 12 minutes, MP3 (soak tests)
d="$out/Long"; mkdir -p "$d"
gen "$tmp/s.wav" 0.49 185 720 44100
meta "Long Haul" "Demo" "Long" 1 2025
ff -i "$tmp/s.wav" -c:a libmp3lame -q:a 4 "${M[@]}" "$d/01 Long Haul.mp3"

rm -rf "$tmp"
du -sh "$out"
find "$out" -type f | sort
