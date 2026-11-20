# M9 — Settings

Goal: many settings, every one with a default that suits most people, all in one searchable place, and none of them
costing a hand-built screen.

## Mechanism (build this in M2, extend it in every milestone)

One registry describes every setting; storage, screens and search are generated from it.

```kotlin
enum class Applies { LIVE, RELOAD_STREAM, RESCAN, RECREATE_PLAYER, NEXT_START }

sealed class Setting<T>(
    val key: String, val default: T, val category: Category,
    @StringRes val title: Int, @StringRes val summary: Int? = null,
    val keywords: String = "", val applies: Applies = Applies.LIVE,
    val visibleWhen: (SettingsSnapshot) -> Boolean = { true },
)
class Toggle(…) : Setting<Boolean>(…)
class Choice<E : Enum<E>>(…, val options: List<E>, val label: (E) -> Int) : Setting<E>(…)
class Number(…, val range: IntRange, val step: Int, val unit: Int?) : Setting<Int>(…)
class Decibels(…, val range: ClosedFloatingPointRange<Float>, val step: Float) : Setting<Float>(…)
class Action(…, val run: suspend (Context) -> Unit) : Setting<Unit>(…)      // not stored
class Info(…, val value: (Context) -> String) : Setting<Unit>(…)            // read-only row

object SettingsRegistry { val all: List<Setting<*>> = listOf(/* every setting below */) }
```

- `Settings` wraps DataStore Preferences: `flow(setting)`, `suspend set(setting, value)`, and `now(setting)` from
  an in-memory snapshot kept current by one collector, for readers that cannot suspend (decoder, sink, server).
- `Applies` tells the single place that reacts to changes what to do: nothing extra, `PlayerFactory.reloadStream()`,
  `Scanner.request()`, rebuild the player, or note "takes effect next start" under the row.
- The settings screens render rows by type: switch; choice sheet; slider sheet (with the live value and a reset to
  default); action; info. `visibleWhen` hides rows that do not apply (for example Cast rows without Play services).
- Search filters `all` by title, summary and keywords across categories.
- Every row's long-press offers "Reset to default". Settings → Advanced has "Reset all settings".
- JVM test: keys are unique; every default is inside its range or options; every setting has a title string.

## The settings

Milestone = where the setting is implemented. Defaults are the values in force after a fresh install.

### Library

| Setting | Type | Default | Milestone |
|---|---|---|---|
| Music folders | action (opens the folder picker) | Music folder of internal storage | M3 |
| Scan on start | toggle | on | M3 |
| Watch for new music | toggle | on | M3 |
| Rescan now / Full rescan | actions | | M3 |
| All files access | action + state | off | M3 |
| Ignore tracks shorter than | number, 0–120 s | 0 (off) | M3 |
| Sort songs in folders by | choice: file name, track number, title | file name | M3 |
| Tapping a song plays | choice: this folder, this folder and subfolders, the whole library from here | this folder and subfolders | M3 |
| After the queue ends | choice: continue with the next folder, stop | continue with the next folder | M2/M3 |
| Artwork: prefer | choice: embedded, folder image | embedded | M3 |
| Library tabs | order and visibility of Folders, Albums, Artists, Songs, Playlists | all, Folders first | M4 |

### Playback

| Setting | Type | Default | Milestone |
|---|---|---|---|
| Previous restarts the track after 3 s | toggle | on | M2 |
| Stop when the app is swiped away | toggle | off | M2 |
| Start playing when the app opens | toggle | off | M2 |
| Respect other apps' audio | toggle | on | M8 |
| Resume after an interruption | toggle | on | M8 |
| Sleep timer: fade out at the end | toggle | on | M8 |
| Keep the screen on while the player is open | toggle | off | M4 |

### Audio

| Setting | Type | Default | Applies | Milestone |
|---|---|---|---|---|
| Output sample rate | choice: same as the file, 44.1, 48, 88.2, 96, 176.4, 192 kHz | same as the file | reload stream | M2 |
| Maximum sample rate | choice: 48, 96, 192 kHz | 192 kHz | reload stream | M2 |
| Resampler quality | choice: normal, high, very high | high | reload stream | M2 |
| Audio buffer | number, 100–1000 ms, step 50 | 250 ms | recreate player | M2 |
| ReplayGain | choice: off, track, album | off | reload stream | M6 |
| ReplayGain preamp | decibels, −15…+15 | 0 | reload stream | M6 |
| Preamp for files without ReplayGain | decibels, −15…0 | 0 | reload stream | M6 |
| ReplayGain: prevent clipping | toggle | on | reload stream | M6 |
| Limiter | toggle | on | live | M6 |
| Limiter ceiling | choice: 0, −0.5, −1 dBFS | 0 dBFS | live | M6 |

### Equalizer (the curve itself lives on the Equalizer screen)

| Setting | Type | Default | Milestone |
|---|---|---|---|
| Gain range | choice: ±6, ±12, ±15 dB | ±12 dB | M6 |
| Accurate band gains | toggle | on | M6 |
| Remember EQ per output | toggle | on | M8 |
| Output profiles | action (list: rename, delete) | | M8 |
| Presets: export / import | actions | | M6 |

### Look and feel

| Setting | Type | Default | Milestone |
|---|---|---|---|
| Theme | choice: dark, light, black, follow system | dark | M4 (dark), M9 (others) |
| Colour from artwork | toggle | on | M4 |
| Player background | choice: gradient, blurred artwork, plain | gradient | M4 |
| List density | choice: comfortable, compact | comfortable | M4 |
| Show file names instead of titles | toggle | off | M4 |
| Swipe songs to queue | toggle | on | M4 |
| Swipe the mini player to change track | toggle | on | M4 |
| Haptic feedback | toggle | on | M4 |
| Waveform detail | choice: fine, medium, bold | fine | M5 |
| Waveform shape | choice: mirrored, bars | mirrored | M5 |
| Waveform colour | choice: spectral, accent | spectral | M5 |
| Seek while dragging | toggle | off | M5 |

### Cast (hidden without Google Play services)

| Setting | Type | Default | Milestone |
|---|---|---|---|
| Apply EQ and gain when casting | toggle | on | M7 |
| Cast quality | choice: 24-bit up to 96 kHz, 16-bit 48 kHz (compatibility) | 24-bit up to 96 kHz | M7 |
| Keep the phone awake while casting | toggle | on | M7 |
| Battery restrictions | action + state (opens the exemption request) | | M7 |
| Reset cast key | action | | M7 |
| Cast log | action (viewer with share) | | M7 |

### Bluetooth and outputs

| Setting | Type | Default | Milestone |
|---|---|---|---|
| Pause when headphones or Bluetooth disconnect | toggle | on | M2/M8 |
| Resume when a Bluetooth device connects | toggle | off | M8 |
| …only if it was playing when it disconnected | toggle | on | M8 |
| …delay before resuming | number, 0–5000 ms, step 500 | 1500 ms | M8 |
| Notification buttons | choice: shuffle and repeat, none | shuffle and repeat | M8 |

### Updates

| Setting | Type | Default | Milestone |
|---|---|---|---|
| Check for updates | toggle | on | M10 |
| Check now | action | | M10 |
| This build | info ("0.1.0 · build 128 · a1b2c3d") | | M10 |

### Advanced

| Setting | Type | Milestone |
|---|---|---|
| Audio details | info: current output device, sample rate, format, buffer size, underruns this session | M2 |
| Decoder | info: FFmpeg version and the formats built in | M1 |
| Clear waveform cache / artwork cache | actions | M5 / M3 |
| Export settings and presets / Import | actions (one JSON file; import validates keys and ranges) | M9 |
| Export logs | action (share `cast.log`, the last crash file and a state dump) | M11 |
| Reset all settings | action | M9 |
| Licenses | action (screen from `docs/LICENSES.md` content) | M10 |

## M9 tasks

1. Finish the generated settings screens to the design in `04-ui-ux.md` and make search complete.
2. Walk the tables above: every row exists, is wired, and does what it says when changed during playback. Tick
   each in PROGRESS.md.
3. Export/import of settings and presets.
4. Backup rules (`dataExtractionRules`): include the DataStore file and presets; exclude the library database,
   waveforms, artwork cache and logs (they are rebuilt).
5. Light and Black themes; "follow system".
6. The wide-screen layout (rail, list, persistent player panel) on the Galaxy Tab; phone landscape player.
7. First-run flow polish, every empty and error state, an accessibility pass over the new screens.

## Exit checks

- A fresh install plays music correctly with no setting touched.
- Each setting changed while music plays takes effect as its `Applies` says, without a crash or a stuck player.
- Searching "bass", "gap", "bluetooth", "cast", "update", "folder" finds the relevant rows.
- Export, reset all, import: the previous state returns exactly.
