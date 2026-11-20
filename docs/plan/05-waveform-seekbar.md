# M5 — The Trueform seekbar

Goal: the seek bar on the player is the track's waveform. Bar height is loudness, bar colour is the balance of
bass, mids and treble, the played part is bright and the rest is dim, so the listener sees what is coming. It is
computed once per track, stored, and works the same when casting. This is the feature the app is named after.

The owner chose this over a live spectrum analyser. Do not build a live analyser.

## Decisions

| Topic | Decision |
|---|---|
| Resolution | 512 columns per track, whatever its length. Screens show 90–300 bars; columns are pooled down when drawing. |
| Per column | peak level, RMS level, and the shares of low / mid / high energy: 5 bytes |
| Bands | low below 250 Hz, mid 250 Hz–2.5 kHz, high above 2.5 kHz (second-order filters on the mono mix) |
| Storage | `waveforms(trackId PRIMARY KEY, version INTEGER, data BLOB)` in Room, about 2.6 KB per analysed track, deleted with its track or when the file changes |
| When | on demand: the current track first, then the next two in the queue. No library-wide background analysis. |
| Side product | the exact decoded frame count, written to `tracks.exactFrames` (the playback and cast streams use it for exact lengths) |

## T1. Analyser

`WaveformAnalyzer.analyze(track): Flow<WaveformData>` on one background thread at
`THREAD_PRIORITY_BACKGROUND`, so it never competes with playback decoding.

1. Open a `NativeDecoder` at the source rate (cap 192 kHz), gain 1.0.
2. For every frame: `peak = max(|L|, |R|)`; `m = (L + R) / 2`; run `m` through three filters (reuse `Biquad` from
   M6, or a local copy until M6 exists): low-pass 250 Hz → `lo`; high-pass 2500 Hz → `hi`; high-pass 250 Hz then
   low-pass 2500 Hz → `mid`. Butterworth Q (0.7071).
3. Column `c` covers frames `[c · N / 512, (c + 1) · N / 512)`, `N` being the decoder's total. Accumulate per
   column: the maximum `peak`, the sums of `m²`, `lo²`, `mid²`, `hi²`, and the count.
4. Encode each finished column:
   - `peakDb = 20·log10(peak)`, `rmsDb = 10·log10(sum m² / count)`; byte = `round(clamp((dB + 60) / 60, 0, 1) · 255)`.
   - band amplitudes `aLo = sqrt(lo²/n)`, `aMid = sqrt(mid²/n) · W_MID`, `aHi = sqrt(hi²/n) · W_HIGH`, normalised so
     the three bytes sum to 255. `W_MID = 1.6` and `W_HIGH = 3.0` compensate for music carrying most of its energy
     in the bass; they are **tuning constants** in one place (`WaveformTuning`), to adjust by looking at real
     tracks until hi-hats read cyan, vocals gold and kick drums coral.
5. Emit the partial result every 16 columns (the UI animates bars in as they arrive).
6. At the end: store the blob (`"TFW1"`, version, column count, then 512 × 5 bytes), set `exactFrames` to the
   decoded frame count, emit the final value.

Long files (over 20 minutes): do not decode everything. For each column seek to its start and decode 0.5 s. This
is about fifteen times faster for a two-hour mix; `exactFrames` is left as it was.

Cancellation: a request for a new current track cancels a running analysis of a non-current track; an analysis of
the current track that is more than half done finishes.

`WaveformRepo.observe(trackId): Flow<WaveformData?>` returns the stored blob, or starts the analysis and relays
its partial results. `QueueController` calls `WaveformRepo.prepare(current, next1, next2)` on every transition.

## T2. Drawing (`WaveSeekbar` composable)

Inputs: `WaveformData?`, duration, a position provider (a lambda read in the draw phase), `isPlaying`, colours,
style settings, `onSeek(ms)`.

- Bars: `count = floor(width / (barWidth + gap))`. Pool columns into bars: level = mean RMS of the pooled columns,
  tip = max peak, shares = mean shares. Styles (setting "Waveform detail"): Fine 2 + 1 dp (default), Medium
  3 + 1.5 dp, Bold 4 + 2 dp.
- Height: `h = clamp(((rmsDb + 48) / 48) ^ 1.4, 0.04, 1)` of the half-height; the peak adds a thinner "tip" above
  the body at 35% alpha (`hTip` from `peakDb` with the same curve). Curve constants live in `WaveformTuning`.
- Shape (setting "Waveform shape"): **Mirrored** (default): the bar grows up from a centre line at 62% of the
  height, with a reflection below at 60% of its height and 45% alpha. **Bars**: from the bottom edge.
- Colour (setting "Waveform colour"): **Spectral** (default): raise each share to the power `SHARE_CONTRAST`
  (2.2, in `WaveformTuning`), renormalise, then mix `waveLow`, `waveMid`, `waveHigh` by those weights in linear
  RGB. Without the power every bar averages out to the same pastel; with it the dominant band sets the hue (the
  mockup uses exactly this rule). **Accent**: the current accent colour.
- Played and unplayed: bars left of the playhead at full alpha, right of it at 38%. The bar the playhead is inside
  is split at the exact pixel. Rounded bar ends (radius = half the bar width).
- Playhead: a 2 dp line in `text` colour across the full height. While dragging: a 12 dp handle on it and a bubble
  above with the target time and the offset ("2:31 · +0:49").
- Before data exists: a 2 dp hairline with a plain progress fill; bars rise from it as columns arrive.
- Under the bar: elapsed time left; right, remaining or total (tap toggles; remembered).

Implementation notes:

- Build the bar geometry once per (data, size, style) in `drawWithCache`: two `Path`s or one offscreen
  `ImageBitmap` with all bars at full alpha. Per frame draw it twice with clip rectangles (played at alpha 1,
  unplayed at 0.38). Nothing is rebuilt while only the position moves.
- Position at display rate: `pos = lastKnownPos + (now − lastUpdateTime)` while playing, re-anchored on every
  position discontinuity, play/pause change and once a second. Read inside the draw scope.
- The mini player reuses the pooled data at about 60 bars and low alpha.

## T3. Interaction

| Gesture | Result |
|---|---|
| Tap | seek to that point |
| Horizontal drag | scrub: the playhead and bubble follow the finger; the seek happens on release. Setting "Seek while dragging" (default off) seeks continuously, throttled to 10 per second. |
| Drag ended outside the bar | still seeks (the gesture owns the pointer once started) |
| TalkBack / keyboard | slider semantics; step 5 s; announces "1 minute 42 of 5 minutes 12" |

The seekbar consumes its drags so the player sheet does not start collapsing. A light haptic tick marks scrub start
and end. Seeking while casting sends the seek to the receiver on release only.

## Tests

- JVM: blob encode/decode round trip; column boundary arithmetic for odd lengths; pooling; colour mix; height curve
  monotonic; position extrapolation.
- Device: analyse the corpus file `bursts` (silence, loud, quiet, loud bass, loud treble, one second each):
  silence columns have level bytes below 8; loud above quiet; the bass section's low share above 0.7; the treble
  section's high share above 0.6. `exactFrames` equals the manifest count. Analysing a 30-minute generated file
  takes the sampled path and finishes in under 10 s.

## Exit checks

- Budgets, recorded in PROGRESS.md: full analysis of a 4-minute FLAC 16/44.1 in ≤ 1.5 s (OnePlus 12R) and ≤ 4 s
  (Moto); a 4-minute MP3 in ≤ 3 s and ≤ 7 s. Playback started at the same moment shows no underrun.
- On five very different real tracks (the owner's library) the waveform visibly matches the music: quiet intros
  are low, drops are tall and warm, bright sections are cool. Screenshots in `captures/`.
- Scrubbing lands within 100 ms of the released position; the played/unplayed boundary moves smoothly at the
  display's refresh rate with no recomposition (check with the layout inspector's recomposition counts or a debug
  counter).
- Second play of a track shows its waveform in the first frame of the player.

## Pitfalls

- The decoder used for analysis is a separate instance from the one playing; never share handles.
- A changed file (new mtime) must drop its stored waveform (the scanner does this).
- Do not key waveforms by path; rows are keyed by track id, which survives moves.
