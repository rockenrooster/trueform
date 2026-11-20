# M6 — DSP and equaliser

Goal: an equaliser that sounds right and cannot clip: graphic with 5, 10, 15 or 31 bands, or four tone knobs
(sub-bass, bass, mid, treble); a preamp that sets itself from the curve; a look-ahead limiter as the guarantee;
ReplayGain; balance and mono. With everything neutral the audio leaves the app bit-for-bit as decoded.

All of it is plain Kotlin in `dsp/`, tested on the JVM. No JNI.
`// ponytail: Kotlin kernel; move the inner loop to C++/NEON only if the 31-band 192 kHz measurement below misses
its budget.`

## Where DSP runs

- **Local playback**: `DspAudioSink` (M2) calls `DspChain.process` on 100 ms buffers of float stereo on ExoPlayer's
  playback thread, right before the `AudioTrack`. A change is heard after the output buffer drains: about 0.3 s
  with the default 250 ms buffer.
- **Chromecast rendering** (M7): each rendered stream owns another `DspChain` fed from the same parameters.
- **ReplayGain** is not in the chain. It is a gain in the decoder (`Native.setGain`), so it changes exactly at
  track boundaries and applies to cast streams too.

Chain order: **preamp → EQ filters → balance / mono → limiter**.

## T1. `Biquad` and `EqDesign`

Filter design: Robert Bristow-Johnson's cookbook, coefficients in `Double`, normalised by `a0`.

```
A = 10^(gainDb/40)   w0 = 2π·f0/fs   α = sin(w0)/(2·Q)   c = cos(w0)

peaking:    b0 = 1+α·A   b1 = −2c   b2 = 1−α·A      a0 = 1+α/A   a1 = −2c   a2 = 1−α/A
low shelf:  b0 = A[(A+1) − (A−1)c + 2√A·α]   b1 = 2A[(A−1) − (A+1)c]    b2 = A[(A+1) − (A−1)c − 2√A·α]
            a0 = (A+1) + (A−1)c + 2√A·α      a1 = −2[(A−1) + (A+1)c]    a2 = (A+1) + (A−1)c − 2√A·α
high shelf: b0 = A[(A+1) + (A−1)c + 2√A·α]   b1 = −2A[(A−1) + (A+1)c]   b2 = A[(A+1) + (A−1)c − 2√A·α]
            a0 = (A+1) − (A−1)c + 2√A·α      a1 = 2[(A−1) − (A+1)c]     a2 = (A+1) − (A−1)c − 2√A·α
low/high pass (for the waveform analyser): standard cookbook forms, Q = 0.7071
```

Processing: transposed direct form II, `Double` state, per channel:
`y = b0·x + z1;  z1 = b1·x − a1·y + z2;  z2 = b2·x − a2·y`.
States whose magnitude falls below `1e-30` are set to 0 (no denormals during silence).

Magnitude at frequency `f` (`w = 2π·f/fs`), used for the graph, the correction and the auto preamp:

```
|H|² = (b0² + b1² + b2² + 2(b0·b1 + b1·b2)·cos w + 2·b0·b2·cos 2w)
     / (1  + a1² + a2² + 2(a1 + a1·a2)·cos w   + 2·a2·cos 2w)
```

`EqDesign.responseDb(filters, fs, f)` sums the filters' dB. The UI graph and the audio path call the same code.

## T2. Band layouts

| Mode | Centre frequencies (Hz) | Q |
|---|---|---|
| 5 bands | 60, 230, 910, 3600, 14000 | 0.667 |
| 10 bands | 31.25, 62.5, 125, 250, 500, 1k, 2k, 4k, 8k, 16k | 1.414 |
| 15 bands | 25, 40, 63, 100, 160, 250, 400, 630, 1k, 1.6k, 2.5k, 4k, 6.3k, 10k, 16k | 2.145 |
| 31 bands | 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1k, 1.25k, 1.6k, 2k, 2.5k, 3.15k, 4k, 5k, 6.3k, 8k, 10k, 12.5k, 16k, 20k | 4.318 |

`Q = √(2^B) / (2^B − 1)` for a band `B` octaves wide. All graphic bands are peaking filters. A band whose centre is
at or above `0.45·fs` is left out at that sample rate.

Gain range per band: ±12 dB by default; setting "Gain range": ±6, ±12, ±15.

**Changing the band count keeps the sound**: the new sliders take the old curve's value at their centre
frequencies (linear interpolation over log-frequency between old centres, flat beyond the ends).

**Tone mode** (four knobs, ±12 dB): constants in one object, `ToneTuning`, to adjust by ear with the owner:

| Knob | Filter |
|---|---|
| Sub-bass | peaking, 45 Hz, Q 0.8 |
| Bass | peaking, 120 Hz, Q 0.8 |
| Mid | peaking, 1 kHz, Q 0.6 |
| Treble | high shelf, 6.5 kHz, Q 0.7 |

Tone and graphic settings are stored separately; switching mode switches which set is active.

## T3. Sliders mean what they say (band interaction)

Neighbouring peaking filters overlap, so naive gains make the real response overshoot the sliders (badly at 31
bands). Correct the filter gains so the response at each centre equals the slider:

```
g = target.copy()                              // target = slider values in dB
repeat 4 times:
    for each band i: actual[i] = responseDb(filtersWith(g), fs, centre[i])
    for each band i: g[i] = clamp(g[i] + 0.8·(target[i] − actual[i]), −(range+6), range+6)
```

Runs on a slider change, off the audio thread (31 × 31 evaluations × 4). Setting "Accurate band gains", default on.

## T4. Automatic preamp ("automatic clipping adjustment")

`autoPreampDb(filters, fs) = −max(0, max over grid of responseDb)`, the grid being 256 log-spaced points from 10 Hz
to `min(22 kHz, 0.49·fs)` plus every band centre, rounded down to 0.1 dB.

- "Auto" on (default): the preamp is this value; the slider displays it and is not draggable.
- "Auto" off: the user's slider value (−15…+15 dB).
- The chain computes it for its own sample rate; the UI computes it for the playing track's rate with the same
  function, so the number shown is the number applied.

A curve that only cuts gets 0 dB. A +6 dB bass boost gets −6 dB overall: the boost is made by turning the rest
down, which is what keeps full-scale music from clipping.

## T5. Limiter

The preamp makes clipping unlikely; the limiter makes it impossible (filters shift phase, so peaks can still rise a
little, and lossy decoders overshoot full scale on their own).

Stereo-linked look-ahead peak limiter:

- `L = max(8, round(0.005·fs))` samples (5 ms). Delay `D = L − 1`.
- Per frame: `need = min(1, ceiling / max(|l|, |r|))`.
- `m` = minimum of `need` over the last `L` frames (monotonic deque in preallocated ring arrays).
- `b` = mean of `m` over the last `L` frames (running sum): a linear attack that reaches the needed reduction
  exactly when the loud sample leaves the delay line.
- Release: `g = min(b, gPrev + (1 − gPrev)·k)` with `k = 1 − exp(−1 / (0.150·fs))` (150 ms).
- Output = the frame from `D` frames ago × `g`.

With window `L`, average `L` and delay `L − 1`, the output never exceeds `ceiling`, whatever the input (the tests
assert it). When nothing exceeds the ceiling, `g` is exactly 1 and the output is the input delayed, bit for bit.

Ceiling: **0 dBFS** by default, so untouched music stays untouched; setting "Limiter ceiling": 0, −0.5, −1 dBFS
(a lower ceiling leaves room for Bluetooth codecs to overshoot). Setting "Limiter": on by default.

The limiter publishes its current gain reduction (peak-hold, in dB) and post-limiter peak levels through
`@Volatile` fields; the EQ screen's `LIMIT` indicator and meters read them.

Known limit, accepted: the last 5 ms of a stream (queue end, or a sample-rate change) stay in the delay line and are
not played. `// ponytail: limiter tail dropped at stream end; flush it through an output-side buffer if it is ever
audible.`

## T6. `DspParams`, `DspController`, `DspChain`

```kotlin
data class FilterSpec(val type: FilterType, val f0: Double, val gainDb: Double, val q: Double)
data class DspParams(
    val eqOn: Boolean, val filters: List<FilterSpec>,     // gains already corrected (T3)
    val autoPreamp: Boolean, val manualPreampDb: Float,
    val balance: Float, val mono: Boolean,                // balance −1…+1
    val limiterOn: Boolean, val ceilingDb: Float,
)
```

- `DspController` (main thread) owns the editable state (mode, band count, slider values, knob values, preamp,
  toggles), persists it in DataStore, and publishes an immutable `DspParams` through a `@Volatile` field and a
  `StateFlow` (for the UI). Publishing is debounced by 10 ms while a control is being dragged.
- `DspChain` (one per audio consumer): `configure(sampleRate)`, `process(buf: FloatArray, frames: Int)` in place,
  `reset()`.
  - At the start of each `process` call it compares the published params reference with the one it last used. On
    a change it builds the new filter set (copying filter states when the band layout is the same) and
    **crossfades**: for the first `min(2048, frames)` frames both the old and the new chain run and their outputs
    are mixed linearly; after that only the new one runs. Preamp changes ride the same crossfade. No clicks, no
    zipper noise, and a band-count change is handled the same way.
  - Work in a reusable `DoubleArray`: float in → double through preamp, filters, balance, limiter → float out.
  - Skip stages that are neutral: no multiply at 0 dB, no filters when the EQ is off or every gain is 0, no balance
    at centre. With everything neutral only the limiter's delay remains, and the output is bit-identical to the
    input.
  - `configure` with an unchanged rate keeps all state (gapless track changes); a new rate rebuilds coefficients
    and clears state. `reset()` (seek, flush) clears filter and limiter state.
- Balance: left gain `min(1, 1 − balance)`, right gain `min(1, 1 + balance)`. Mono: both channels become
  `(L + R) / 2`.

## T7. ReplayGain

Settings: mode Off (default) / Track / Album; "ReplayGain preamp" (−15…+15 dB, default 0); "Preamp for files
without tags" (−15…0 dB, default 0); "Prevent clipping" (default on).

`LibraryRepo.playInfo` computes the linear gain: pick track or album gain (album falls back to track), add the
preamp; with "Prevent clipping" and a peak tag, cap the gain at `1 / peak`; untagged files get the untagged preamp.
A change of these settings calls `PlayerFactory.reloadStream()`. Reading the tags is already done by the probe
(M1/M3).

## T8. Presets

`eq_presets(id, name, builtIn, mode, bandCount, gains, tone, preampDb, autoPreamp)`; gains and tone as JSON arrays.

Built-ins are defined on the 10-band layout and interpolated to the current band count when applied (dB,
31 Hz → 16 kHz). They are starting points for the owner to retune:

| Preset | Gains |
|---|---|
| Flat | 0 0 0 0 0 0 0 0 0 0 |
| Bass boost | 6 5 4 2.5 1 0 0 0 0 0 |
| Bass cut | −6 −5 −4 −2 −1 0 0 0 0 0 |
| Treble boost | 0 0 0 0 0 1 2.5 4 5 6 |
| Vocal | −2 −1.5 −1 1 3 4 3 1.5 0 −1 |
| Rock | 4.5 3.5 2 0 −1.5 −1 1 2.5 3.5 4 |
| Pop | −1 0 1.5 3 3.5 2.5 1 0 −0.5 −1 |
| Jazz | 3 2 1 1.5 −1 −1 0 1 2 3 |
| Classical | 3.5 2.5 1.5 1 0 0 0 1.5 2.5 3.5 |
| Electronic | 5 4 1.5 0 −1.5 1 0.5 1.5 4 5 |
| Loudness | 6 4 1.5 0 −1 −1 0 1.5 4 5.5 |
| Speech | −4 −3 −1 1 3 4 3.5 2 0 −2 |

User presets: save, rename, delete, export and import as a JSON file (share sheet / file picker).

## T9. Screens

Build the Equalizer screen as specified in `04-ui-ux.md` ("Equalizer"): response graph drawn with
`EqDesign.responseDb`, draw-on-graph editing, band sliders, band-count selector, preamp with Auto, limiter
indicator, meters, presets, the Tone knobs, and the "More" section (balance, mono, gain range). The `Knob`
composable is new here. Add the EQ pill and limiter dot to the player screen.

## Tests (JVM, `src/test`)

1. **Magnitude formula**: impulse response (65536 samples) of a peaking filter; its magnitude at several
   frequencies by Goertzel matches the formula within 0.01 dB, at 44.1, 48, 96 and 192 kHz.
2. **Design**: peaking gain at `f0` equals the requested dB within 0.01; shelves reach their gain far from the
   corner.
3. **Corrected graphic EQ**: at all band centres, |actual − slider| ≤ 0.3 dB for smooth curves (every built-in at
   10 and 31 bands) and ≤ 1.0 dB for 31 bands alternating +6 / −6.
4. **Auto preamp**: after applying it, the maximum of the response over the grid is ≤ 0.05 dB; a cut-only curve
   gives exactly 0.
5. **Limiter bound**: white noise at +12 dBFS, sine bursts at +6, single samples of amplitude 8, and a square wave:
   the output never exceeds the ceiling (tolerance 1e-6). At ceilings 0, −0.5 and −1 dBFS.
6. **Transparency**: with neutral params the output equals the input delayed by `D`, compared bitwise, for random
   input within ±1.
7. **Stability**: every layout × gains of ±15 on all bands × the four sample rates: each filter satisfies
   `|a2| < 1` and `|a1| < 1 + a2`; ten seconds of noise produce no NaN, no infinity.
8. **No click on change**: a steady 100 Hz sine; switch the 125 Hz band from 0 to +12 dB mid-buffer: during the
   crossfade the largest sample-to-sample step is at most twice the largest step of the louder steady signal.
9. **Silence**: after music, 60 s of zeros: filter states are exactly 0 at the end.
10. **Band-count change**: a 10-band curve converted to 31 and back differs from the original by ≤ 0.5 dB.
11. **Balance and mono**: obvious cases.
12. **ReplayGain parsing and gain selection**: tag strings, album fallback, peak capping.

## Exit checks

- All tests pass.
- Kernel cost on the Moto, recorded in PROGRESS.md: time to process one 100 ms buffer with 10 bands at 44.1 kHz
  (budget 0.5 ms) and 31 bands at 192 kHz (budget 6 ms).
- One end-to-end check on a phone: the debug command `render` (`--es path <sweep> --es out <wav>`) writes the
  stream after the chain to the app's files directory; pull it and confirm with a small Python script that the
  measured response matches the app's `dump` of the expected curve within 0.5 dB.
- On a phone, by the owner: dragging sliders and knobs is smooth and heard within about a third of a second; a
  +12 dB bass boost on a loud track does not distort, and `LIMIT` shows why.

## Pitfalls

- The stability region of a biquad in (`a1`, `a2`) is a triangle; do not interpolate anything but complete
  coefficient sets, and prefer the crossfade above to interpolation.
- Never allocate in `process`. The chain's arrays are sized in `configure` and grown only when a larger buffer
  arrives.
- The graph must be drawn from the filters actually in use (corrected gains), not from the slider values.
- Presets store slider values, not corrected gains.
