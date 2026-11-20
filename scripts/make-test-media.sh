#!/usr/bin/env bash
# Writes the decoder test corpus (docs/plan/01-native-decoder.md, T6) to app/src/androidTest/assets/media/ with
# manifest.json. Synthetic signals only. Needs ffmpeg with libmp3lame, libopus and libvorbis (WSL: apt install ffmpeg).
# The corpus is committed, so later sessions need neither WSL nor this script.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../app/src/androidTest/assets/media"
rm -rf "$out" && mkdir -p "$out"
cd "$out"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
src() { ff -f lavfi -i "aevalsrc=$1" "${@:2}"; }

# --- sources -------------------------------------------------------------------------------------------------------
# tone: 3 s, 44.1 kHz, 1 kHz left / 440 Hz right at -6 dBFS
src "0.5*sin(2*PI*1000*t)|0.5*sin(2*PI*440*t):s=44100:d=3" -c:a pcm_s16le tone.wav
# sweep: 4 s logarithmic 20 Hz - 20 kHz, 48 kHz, 24-bit
src "0.5*sin(87.3263*(exp(t*1.726939)-1))|0.45*sin(87.3263*(exp(t*1.726939)-1)):s=48000:d=4" -c:a pcm_s24le sweep.wav
# hires: 2 s, 96 kHz, 24-bit, 10 kHz + 30 kHz
src "0.25*sin(2*PI*10000*t)+0.25*sin(2*PI*30000*t)|0.25*sin(2*PI*10000*t)+0.25*sin(2*PI*30000*t):s=96000:d=2" -c:a pcm_s24le hires.wav
# bursts (for the waveform analyser): silence, loud noise, quiet noise, loud bass, loud treble, silence, mid tone, mix
B="if(lt(t,1),0,if(lt(t,2),0.9*(random(0)-0.5),if(lt(t,3),0.06*(random(0)-0.5),if(lt(t,4),0.7*sin(2*PI*60*t),if(lt(t,5),0.7*sin(2*PI*8000*t),if(lt(t,6),0,if(lt(t,7),0.5*sin(2*PI*1000*t),0.45*(random(0)-0.5)+0.4*sin(2*PI*80*t))))))))"
src "'$B|$B':s=44100:d=8" -c:a pcm_s16le bursts.wav   # (quoted: the commas would split the filter graph)

# --- encodings of the tone -----------------------------------------------------------------------------------------
ff -i tone.wav -c:a flac tone.flac
ff -i tone.wav -c:a alac tone_alac.m4a
ff -i tone.wav -c:a wavpack tone.wv
ff -i tone.wav -c:a pcm_s16be tone.aiff
ff -i tone.wav -c:a pcm_f32le tone_f32.wav
ff -i tone.wav -c:a libmp3lame -b:a 320k tone_cbr320.mp3
ff -i tone.wav -c:a libmp3lame -q:a 2 tone_v2.mp3
ff -i tone.wav -c:a libmp3lame -b:a 192k -write_xing 0 tone_noheader.mp3
ff -i tone.wav -c:a aac -b:a 192k tone_aac.m4a
ff -i tone.wav -c:a libvorbis -q:a 5 tone.ogg
ff -i tone.wav -c:a libopus -b:a 128k tone.opus
ff -i tone.wav -c:a wmav2 -b:a 192k tone.wma
ff -i tone.wav -ac 1 -c:a flac tone_mono.flac
ff -i tone.wav -af "pan=5.1|FL=c0|FR=c1|FC=0.5*c0+0.5*c1|LFE=0*c0|BL=c0|BR=c1" -c:a flac tone_51.flac
ff -i hires.wav -c:a flac hires.flac

# --- gapless pairs: the sweep cut at frame 96000 -------------------------------------------------------------------
ff -i sweep.wav -af "atrim=end_sample=96000" -c:a pcm_s24le sweep_a.wav
ff -i sweep.wav -af "atrim=start_sample=96000,asetpts=PTS-STARTPTS" -c:a pcm_s24le sweep_b.wav
for half in a b; do
  ff -i sweep_$half.wav -c:a flac gap_$half.flac
  ff -i sweep_$half.wav -c:a libmp3lame -q:a 2 gap_$half.mp3
  ff -i sweep_$half.wav -c:a aac -b:a 192k gap_$half.m4a
  ff -i sweep_$half.wav -c:a libvorbis -q:a 5 gap_$half.ogg
  ff -i sweep_$half.wav -c:a libopus -b:a 128k gap_$half.opus
done
rm sweep.wav sweep_a.wav sweep_b.wav

# --- tags ----------------------------------------------------------------------------------------------------------
ff -i tone.wav -c:a flac -metadata title="Tagged Title" -metadata artist="Tag Artist" -metadata album="Tag Album" \
   -metadata album_artist="Album Artist" -metadata genre="Test" -metadata date="2019-05-01" -metadata track="3/12" \
   -metadata disc="1/2" -metadata REPLAYGAIN_TRACK_GAIN="-6.54 dB" -metadata REPLAYGAIN_TRACK_PEAK="0.988000" \
   -metadata REPLAYGAIN_ALBUM_GAIN="-7.10 dB" -metadata REPLAYGAIN_ALBUM_PEAK="0.999000" tagged.flac
ff -i tone.wav -c:a libmp3lame -b:a 128k -id3v2_version 4 -metadata title=$'Bad \xff\xfe name \xc3' badtag.mp3

# --- hostile input -------------------------------------------------------------------------------------------------
head -c 2000 tone.flac > truncated.flac
: > empty.mp3
printf 'this is not audio\n%.0s' {1..200} > text.mp3

# --- manifest ------------------------------------------------------------------------------------------------------
python3 -I - <<'PY'
import json, os
def e(name, source, rate, frames, lossless, channels=2):
    return {"name": name, "source": source, "rate": rate, "frames": frames, "lossless": lossless, "channels": channels}
T, S = 132300, 96000
files = [
    e("tone.wav", "tone.wav", 44100, T, True), e("tone.flac", "tone.wav", 44100, T, True),
    e("tone_alac.m4a", "tone.wav", 44100, T, True), e("tone.wv", "tone.wav", 44100, T, True),
    e("tone.aiff", "tone.wav", 44100, T, True), e("tone_f32.wav", "tone.wav", 44100, T, True),
    e("tone_cbr320.mp3", "tone.wav", 44100, T, False), e("tone_v2.mp3", "tone.wav", 44100, T, False),
    e("tone_noheader.mp3", "tone.wav", 44100, T, False), e("tone_aac.m4a", "tone.wav", 44100, T, False),
    e("tone.ogg", "tone.wav", 44100, T, False), e("tone.opus", "tone.wav", 44100, T, False),
    e("tone.wma", "tone.wav", 44100, T, False),
    e("tone_mono.flac", "tone.wav", 44100, T, True, 1), e("tone_51.flac", "tone.wav", 44100, T, True, 6),
    e("hires.wav", "hires.wav", 96000, 192000, True), e("hires.flac", "hires.wav", 96000, 192000, True),
    e("bursts.wav", "bursts.wav", 44100, 352800, True), e("tagged.flac", "tone.wav", 44100, T, True),
    e("badtag.mp3", "tone.wav", 44100, T, False),
]
for half in "ab":
    for ext, lossless in (("flac", True), ("mp3", False), ("m4a", False), ("ogg", False), ("opus", False)):
        files.append(e(f"gap_{half}.{ext}", f"gap_{half}.flac", 48000, S, lossless))
json.dump({"files": files, "hostile": ["truncated.flac", "empty.mp3", "text.mp3"]},
          open("manifest.json", "w"), indent=1)
PY
du -ch . | tail -1
ls -l
