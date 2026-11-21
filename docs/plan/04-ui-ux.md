# M4 — UI and UX

Goal: a beautiful, fast, readable app. This file is the design specification; `docs/design/mockup.html` is the
visual reference for the four key screens (open it in a browser; its CSS variables are the tokens below). Where the
two disagree, the mockup shows intent and this file gives the numbers.

The owner's standing UI rules: human readable and space-efficient; clear, high-contrast hover and pressed states on
every interactive element.

## 1. Design direction

**Dark first, artwork forward, quiet chrome, one hero.**

- The interface is near-black glass. Colour comes from the album art and from one warm accent (amber), so the
  music's own artwork is the brightest thing on screen.
- One element is allowed to be loud: the **Trueform seekbar**, the frequency-coloured waveform. Nothing else on the
  player screen competes with it.
- Chrome is flat. Depth comes from three surface tones and hairline outlines, not from shadows. The only glow is
  under the artwork.
- Dense where it helps (lists), generous where it matters (player). No decorative headers, no empty bands.
- Every touch gives an immediate, visible and tactile answer.

## 2. Tokens

### Colour

| Token | Dark (default) | Light | Black (AMOLED) | Use |
|---|---|---|---|---|
| `bg` | `#0B0C0F` | `#FAFAF7` | `#000000` | window background |
| `surface1` | `#13151A` | `#FFFFFF` | `#0C0D10` | cards, sheets, mini player |
| `surface2` | `#1B1E25` | `#F1F1EC` | `#15171C` | inputs, raised rows, chips |
| `surface3` | `#252933` | `#E6E6DF` | `#20232B` | selected chips, slider tracks |
| `outline` | `#2E333D` | `#D9D9D0` | `#262A33` | hairlines, dividers (1 dp) |
| `outlineStrong` | `#454B57` | `#B8B8AD` | `#3D434F` | control borders, slider ends |
| `text` | `#F3F4F6` | `#14161A` | `#F3F4F6` | primary text and icons |
| `textSecondary` | `#A8AEBB` | `#4A505C` | `#A8AEBB` | second lines, labels |
| `textTertiary` | `#737A88` | `#6B7280` | `#737A88` | captions, disabled-looking hints |
| `accent` | `#FFB547` | `#A85F00` | `#FFB547` | active states, play button, sliders |
| `onAccent` | `#1C1200` | `#FFFFFF` | `#1C1200` | content on accent |
| `accentDim` | `#3A2A0C` | `#FFE9C7` | `#2A1E08` | selected backgrounds |
| `waveLow` | `#FF5E4D` | `#D93A2B` | `#FF5E4D` | bass in the waveform |
| `waveMid` | `#FFC24B` | `#C98A00` | `#FFC24B` | mids |
| `waveHigh` | `#7CE4FF` | `#008FB3` | `#7CE4FF` | treble |
| `positive` | `#5BD68A` | `#1E8E4E` | `#5BD68A` | success, "up to date" |
| `danger` | `#FF6B6B` | `#C62828` | `#FF6B6B` | errors, destructive |
| `limit` | `#FF8A3D` | `#D2691E` | `#FF8A3D` | limiter activity |

Contrast targets: body text at least 7:1 on its surface, secondary at least 4.5:1, icons and control outlines at
least 3:1. Check every pairing actually used with a contrast function in a JVM test (`ThemeContrastTest`).

**Artwork colour on the player** (setting "Colour from artwork", default on): run `Palette` on the 256-pixel
thumbnail off the main thread; accent = vibrant, else light vibrant, else muted, then raise its lightness until
contrast against the player background is at least 4.5:1; backdrop = dark muted (else dark vibrant) mixed 55%
toward `bg`. Cache per `artHash` in memory. No artwork, or the setting off: the theme accent and `bg`.

### Type

Bundle **Inter** (variable font, SIL Open Font License; add it to `docs/LICENSES.md`). Times use tabular figures
(`fontFeatureSettings = "tnum"`).

| Style | Size / line | Weight | Use |
|---|---|---|---|
| `display` | 22 / 28 sp | 600 | track title on the player |
| `title` | 20 / 26 sp | 600 | screen titles |
| `rowTitle` | 16 / 22 sp | 500 | list rows |
| `body` | 14 / 20 sp | 400 | text, second lines |
| `label` | 13 / 18 sp | 500 | buttons, chips, tabs |
| `caption` | 12 / 16 sp | 500 | pills, counts |
| `numeric` | 13 / 16 sp | 500, tabular | times, dB values |

All sizes follow the system font scale. At 200% titles may wrap to two lines and rows grow; nothing is cut off.

### Shape, space, size

- Radii: 6 (pills) · 10 (thumbnails) · 14 (cards) · 22 (player artwork) · 28 (sheet tops) · circle (round buttons).
- Spacing on a 4 dp grid. Screen side padding 16 dp (phones), 24 dp (wide).
- Rows: 60 dp comfortable (default), 48 dp compact (setting "List density"). Thumbnails 44 dp, 36 dp compact.
- Touch targets at least 48 × 48 dp, including icon buttons that look smaller.
- Mini player 64 dp. Player transport: play button 72 dp, neighbours 56 dp, outer pair 48 dp.

### States (one implementation: `Modifier.tfClickable`, `TfIndication`)

| State | Look |
|---|---|
| Pressed | overlay of `text` at 16% (12% in light) appearing within one frame; buttons and cards also scale to 0.97; rows do not scale |
| Hover (mouse, stylus) | overlay at 8% |
| Focused (keyboard, D-pad) | 2 dp `accent` ring, 2 dp outside the shape |
| Selected / active | `accentDim` background, `accent` icon or text |
| Disabled | content at 38% alpha, no state layers |

Do not use Material's default ripple: it is faint on dark surfaces. `TfIndication` is a custom
`IndicationNodeFactory` drawing the overlays above, fading out in 120 ms. Every clickable in the app goes through
`tfClickable` so the rule holds everywhere; a lint-style unit test greps the UI sources for bare `clickable(`.

Haptics (setting, default on): a light tick on play/pause, toggles, slider detents (0 dB), knob steps, the start and
end of a scrub, and reorder pick-up/drop.

## 3. Navigation

**Phone (compact width):**

- Home is the Library with top tabs: **Folders · Albums · Artists · Songs · Playlists** (order and visibility are
  settings; Folders first by default). No bottom navigation bar: the bottom edge belongs to the mini player.
- Top bar: title (the current folder's name on the Folders tab), search, cast button, overflow (sort, rescan,
  settings).
- The player is a full-height draggable sheet that rests as the mini player. Queue and Equalizer open from the
  player. Search and Settings are ordinary destinations.
- Back: closes a sheet → collapses the player → goes up one folder → leaves the app. Predictive back animations on.
- Navigation 3: the back stack is a list of keys (`Library`, `Folder(id)`, `Album(key)`, `Artist(name)`,
  `Playlist(id)`, `Search`, `Settings(category?)`, `FolderPicker`, `Onboarding`). Each folder level is its own entry
  so scroll positions restore on back.

**Wide screens (≥ 840 dp, the Galaxy Tab):** navigation rail (the five library sections, search, settings), the
list in the middle, and a persistent player panel 400 dp wide on the right (artwork, seekbar, transport, queue
below). Medium widths (600–839 dp) use the phone layout with wider margins and two-column grids. Phone landscape:
the player splits into artwork left, controls right. Build the phone layout in M4; the wide layout is M9.

## 4. Screens

### Library → Folders (the primary screen)

```
┌──────────────────────────────────────────┐
│ Rock                            ⌕  ⧉  ⋮  │  ⧉ = cast button
│ Internal › Music › Rock                  │  breadcrumb chips, scroll sideways, tap to jump
│ ▔▔▔▔▔▔▔                                  │
│ Folders   Albums   Artists   Songs   …   │
├──────────────────────────────────────────┤
│ ( ▶ Play )  ( ⤮ Shuffle )   348 · 22 h   │  acts on this folder and everything below it
│ ▣  AC-DC                 6 folders · 62  │
│ ▣  Led Zeppelin          9 folders · 81  │
│ ▣  Pink Floyd           12 folders · 140 │
│ ▣  01 Intro              Artist · 1:12   │
│ ▣  02 Something     ▮▯▮  Artist · 3:48   │  playing row: accent title, moving bars
│ ▣  03 Another    [24/96] Artist · 5:02   │  pill only for hi-res or unusual formats
├──────────────────────────────────────────┤
│ ▣  Something — Artist            ⏯   ⏭  │  mini player, faint waveform behind
└──────────────────────────────────────────┘
```

- Subfolders first, then the songs of this folder. A folder row shows its artwork (or a folder glyph on
  `surface2`), name, and "N folders · M songs" or "M songs · duration".
- Song row: thumbnail, title (file name when "Show file names" is on), artist · duration. Format pill only when
  informative (hi-res, lossless on request, or WMA/APE-type formats) so rows stay calm.
- Tap a song: play (scope from the setting "Tapping a song plays", default: this folder and its subfolders,
  starting at the song). Tap a folder: enter it. Long-press either: bottom sheet (Play, Shuffle, Play next, Add to
  queue, Add to playlist; song: Go to album, Info; folder: Exclude from library).
- Swipe a song right: play next. Swipe left: add to queue. (Setting to turn swipes off.) In the folder browser a
  swipe right anywhere in the list goes up one folder instead, as in Poweramp (the owner's choice, 2026-11-20): the
  page follows the finger and goes up past a third of the width or on a fling; play next stays in the long-press
  sheet.
- Multi-select by long-press then taps; a contextual bar offers the batch actions.
- Fast scroller with a letter bubble on lists longer than three screens.
- Folder transitions: shared-axis horizontal, 220 ms.
- The current track's row is highlighted wherever it appears; a small "jump to playing" button appears when it is
  off-screen in the current list.
- While a scan runs: a 2 dp progress line under the top bar and "Reading tags 1,240 / 9,880" in the header row.
- Update available: a small pill in the top bar ("Update · build 128").

Albums: adaptive grid (minimum cell 156 dp), cover, title, artist. Artists: list with a mosaic thumbnail. Songs:
one long list with sort choices. Playlists: smart lists first (Recently added, Most played, Recently played), then
the user's. Detail screens share one layout: large header (art, title, counts, Play and Shuffle), then tracks.

### Mini player

64 dp, `surface1`, top hairline. Artwork 44 dp (shared element with the player), title and artist on one line each,
play/pause, next. Behind the text, the track's waveform at low opacity, the played part brighter: the mini player
is its own progress bar. Tap or swipe up: expand. Swipe sideways: next or previous (setting).

### Now Playing

```
┌──────────────────────────────────────────┐
│ ⌄    PLAYING FROM · Back in Black   ⧉ ⋮ │  tap the middle: go to that folder
│                                          │
│       ┌──────────────────────────┐       │
│       │                          │       │
│       │         artwork          │       │  swipe sideways: previous / next
│       │                          │       │
│       └──────────────────────────┘       │
│ Hells Bells                              │
│ AC/DC · Back in Black                    │  tap: go to artist / album
│ ⟨FLAC 24/96⟩ ⟨→ WH-1000XM5⟩ ⟨EQ Rock⟩ ●  │  format · output · EQ · limiter dot
│                                          │
│ ▂▃▅▇█▇▅▃▂▁▂▃▅▇██▇▅┃▃▂▁▁▂▃▅▇█▇▅▃▂▁▂▃▅▇   │  Trueform seekbar, 88 dp
│ 1:42                              −3:30  │  tap right time: remaining ↔ total
│                                          │
│    ⤮      ⏮      ( ▶ )      ⏭      ↻    │
│                                          │
│   EQ        Queue       Timer       ⋯    │
└──────────────────────────────────────────┘
```

- Background: vertical gradient from the artwork backdrop colour (top) to `bg` (60% down), crossfading over 500 ms
  on track change. Setting "Player background": Gradient (default), Blurred artwork (API 31+ `RenderEffect`),
  Plain.
- Artwork: width minus 48 dp, square, radius 22, a soft glow of the accent colour (24% alpha, 32 dp blur) beneath.
  A pager so neighbours slide in under the finger.
- Pills are informative buttons: format → track info sheet; output → output and cast chooser; EQ → equaliser. The
  dot after them glows `limit` while the limiter is reducing gain.
- Transport: shuffle and repeat show their logical state (`accent` when on; repeat-one shows a "1").
- Bottom row: Equalizer (shows the preset name under the icon when on), Queue, Sleep timer (shows remaining
  minutes when armed), More (add to playlist, go to folder, track info, share file).
- Drag down anywhere outside the seekbar to collapse.

The seekbar itself is specified in `05-waveform-seekbar.md`.

### Queue (sheet from the player)

Header "Playing from Back in Black · 9 of 34 · 1 h 52 min left"; the list starts at the current entry (history
above, scroll up to see it). Drag handle to reorder, swipe to remove, tap to jump. Footer chips: Shuffle, Repeat,
Save as playlist, Clear. When auto-advance will apply, the last row reads "Then: next folder · Highway to Hell".
For huge queues the list is lazy and reads `QueueState.entries` directly.

### Equalizer

```
┌──────────────────────────────────────────┐
│ ←  Equalizer                      ( ON ) │
│ [ Graphic ]  Tone       Rock ▾   ⟲  Save │
│ ┌──────────────────────────────────────┐ │
│ │+12                                   │ │
│ │        ╭───╮                    ╭──  │ │  the real computed response,
│ │  0 ───╯     ╰───╮         ╭────╯     │ │  drawn from the same filter maths
│ │                 ╰─────────╯          │ │  as the audio path
│ │−12   20    100     1k     10k   20k  │ │
│ └──────────────────────────────────────┘ │
│ Bands   5  [10]  15  31                  │
│  ┃  ┃  ┃  ┃  ┃  ┃  ┃  ┃  ┃  ┃           │
│  ●  ●  ┃  ┃  ┃  ┃  ┃  ●  ●  ●           │
│  ┃  ┃  ●  ●  ●  ●  ●  ┃  ┃  ┃           │
│ 31  63 125 250 500 1k 2k 4k 8k 16k       │
│ Preamp ────●─────  ⟨Auto −4.5 dB⟩  LIMIT○│
│ ▸ More: balance, mono, gain range        │
└──────────────────────────────────────────┘
```

- The graph is the centrepiece: grid at 0, ±6, ±12 dB (or the chosen range) and at decades; the curve filled with a
  low-to-high gradient (`waveLow` → `waveMid` → `waveHigh`) at 30% under a 2 dp `accent` line.
- **Draw on the graph**: in graphic mode, dragging a finger across the graph sets each band the finger passes to
  the finger's height (bands skipped by a fast drag are interpolated). This makes 31 bands usable on a phone.
- Sliders: 5 and 10 bands fit the width; 15 and 31 scroll sideways with labels. Snap to 0.5 dB, a detent and tick
  at 0. Double-tap a slider: back to 0. The value shows in a bubble while dragging.
- Preamp slider with an "Auto" chip. Auto on (default): the slider is driven by the computed value and shows it
  ("Auto −4.5 dB"). `LIMIT` lights and shows the reduction ("−1.3 dB") while the limiter works. Thin L/R peak meters
  sit under the graph.
- Presets: a dropdown with built-ins and the user's; "Save" stores the current curve under a name; a dot marks a
  modified preset. With output profiles on (M8) a line under the title says "Profile: Bluetooth · WH-1000XM5".
- Tone mode replaces the sliders with four knobs:

```
│    ◜‾◝       ◜‾◝       ◜‾◝       ◜‾◝     │
│   ( ● )     ( ● )     ( ● )     ( ● )    │   88 dp knobs, value arc from 12 o'clock
│   ◟_◞       ◟_◞       ◟_◞       ◟_◞     │
│  Sub-bass    Bass      Mid     Treble    │
│   +3.0      +5.0       0.0      +2.0     │
```

  Knobs turn by vertical drag (2 dp per 0.1 dB, so a full ±12 dB is a comfortable stroke) or by dragging around
  the rim; a tick per 1 dB; double-tap resets; TalkBack sees a slider with dB values.
- The master switch bypasses the EQ (the limiter stays as protection). Turning it off greys the controls but keeps
  their values.

### Search

A search field with focus on open; results grouped Songs, Albums, Artists, Folders, 50 per group with "Show all";
recent searches as chips when the field is empty; tapping a song plays it with the result list as the queue.

### Settings

A searchable list of categories; each opens a screen of rows generated from the settings registry
(`09-settings.md`). Row: title, one-line current value, the right control (switch, choice sheet, slider sheet with
live effect, action). The search field filters across all categories by title and keywords.

### First run

Three steps, each one screen with one primary button: welcome → allow access to music → choose folders (Music is
pre-checked; counts appear as the walk runs) → library. No carousel, no account, no tips overlay.

### Sheets and small screens

Track info (all tags, path, format, ReplayGain, analysis state), sleep timer (15 / 30 / 45 / 60 / custom, "end of
track", fade option), output chooser (system outputs plus cast devices), add to playlist, update (notes, size,
Download and install), cast log (M7).

## 5. Motion

| What | How |
|---|---|
| Player expand / collapse | follows the finger; settles with a spring (damping 0.85, stiffness 380); artwork morphs 44 dp ↔ full size as a shared element; mini-player text fades in the first 30% |
| Folder in / out | shared-axis X, 220 ms, standard easing; predictive back |
| Track change on the player | artwork pager slide; background crossfade 500 ms; title and artist crossfade with an 8 dp rise, 180 ms |
| Play / pause | icon morph 160 ms; button pulse 0.94 → 1 |
| Waveform appears | bars rise from zero left to right as analysis delivers them, total under 400 ms once complete |
| List changes | item placement animation on reorder and removal |
| Sheets | 280 ms decelerate in, 200 ms accelerate out |

If the system's animation scale is 0, every animation is instant.

## 6. Accessibility

- Every icon button has a content description; state is spoken ("Shuffle, on").
- The seekbar is a slider for TalkBack (range, current time, adjustable with volume keys or swipes, 5 s steps).
- Knobs and band sliders are sliders with dB values.
- Traversal order on the player: collapse, title, artist, seekbar, transport, then the rest.
- No information by colour alone: active toggles also change icon fill or show a dot.
- Works at font scale 200% and display size "largest" on a 360 dp wide screen.

## 7. Performance rules for the UI code

- UI models are immutable data classes; lists are keyed by entity id with `contentType`.
- The playback position is read in the draw phase (a `State` read inside `drawBehind` / `graphicsLayer`), never as
  a composable parameter, so a ticking position does not recompose the screen.
- The waveform is drawn from a cached path or bitmap in `drawWithCache`; per frame only the played/unplayed clip
  moves.
- Images are requested at the size they are shown (256 for rows, 1024 for the player).
- Nothing heavy in `Application.onCreate`: the database opens on an IO thread, Cast initialises after the first
  frame and only where Google Play services exists.
- Collect flows with `repeatOnLifecycle(STARTED)`; no UI work with the screen off.

Budgets (measure, record in PROGRESS.md, phone locked to our app during the run):

| Measure | OnePlus 12R | Moto One 5G Ace |
|---|---|---|
| Cold start to a populated library | ≤ 700 ms | ≤ 1500 ms |
| Library scroll, 95th percentile frame time (`dumpsys gfxinfo framestats`) | ≤ 8.3 ms (120 Hz) | ≤ 16.6 ms |
| Tap a song to first sound | ≤ 300 ms | ≤ 500 ms |
| Player expand gesture | no frame over 16 ms | no frame over 33 ms |

## 8. Tasks

1. **Theme**: tokens as a `TfColors` class behind a `CompositionLocal`, the three palettes, Inter, type scale,
   shapes; `ThemeContrastTest`.
2. **Interaction layer**: `TfIndication`, `tfClickable`, haptics helper, focus ring; the bare-`clickable` test.
3. **Components**: top bar, tabs, breadcrumbs, rows, thumbnails with placeholders, pills, icon buttons, play
   button, slider, chips, sheets, dialogs, empty states, fast scroller, snackbar host. A debug-only "gallery"
   screen shows them all in every state for screenshots.
4. **Icons**: about 30 vector drawables (Material Symbols Rounded, weight 400, exported as XML). The launcher
   icon: adaptive; foreground of seven rounded vertical bars forming a symmetric waveform, coloured `waveLow` →
   `waveMid` → `waveHigh` left to right, on `#0B0C0F`; a monochrome layer for themed icons.
5. **Navigation shell**: Navigation 3 back stack, the player sheet with anchored drag, back handling.
6. **Library screens**: Folders (complete, with all interactions above), then Albums, Artists, Songs, Playlists and
   their detail screens.
7. **Artwork and colour**: the Coil fetcher (`03-library-scanner.md` T6), the session `BitmapLoader`, Palette
   extraction, backdrop.
8. **Mini player and Now Playing** with a plain progress line where the seekbar will go (M5 replaces it).
9. **Queue sheet**, **Search**, **track info**, **sleep timer sheet**.
10. **Permission and first-run flow**, empty and error states (no permission, no folders, nothing found, file
    missing).

## Exit checks

- Side-by-side screenshots (phone, dark theme) of Library, Now Playing, Queue against the mockup: same structure,
  spacing within 4 dp, same tokens. Keep the screenshots in `captures/` (git-ignored) and list them in PROGRESS.md.
- The budgets table is filled in with measured values.
- TalkBack walk-through of Library → play → Now Playing → Queue with the screen content hidden: every control is
  named and operable.
- Pressed, hover (mouse over USB or Bluetooth on the tablet) and focus (keyboard Tab) states are plainly visible on
  every control in the gallery screen, in all three themes.
- Font scale 200%: no clipped text on the three main screens.
