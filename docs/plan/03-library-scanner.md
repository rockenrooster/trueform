# M3 — Library, scanner and folder hierarchy

Goal: the user picks music folders; the app finds every audio file under them, reads tags with FFmpeg, and keeps a
database that the UI browses as a folder hierarchy (first), albums, artists, songs and playlists. Scans are
incremental and automatic. Nothing is lost when an SD card is missing.

## Decisions

| Topic | Decision | Why |
|---|---|---|
| File access | `java.io.File` with real paths | Folder browsing and FFmpeg both want paths. |
| Permission | `READ_MEDIA_AUDIO` (API 33+) or `READ_EXTERNAL_STORAGE` (29–32). Optional "All files access" (`MANAGE_EXTERNAL_STORAGE`) as a setting. | The media permission is one tap and enough for normal music folders. All-files access is for folders with `.nomedia` and for extensions Android does not class as audio (`.ape`, `.wv`, `.dsf`, `.mpc`, `.tta`). The app ships outside the Play Store, so the broad permission is allowed. |
| Tags | our FFmpeg `probe` | One reader for every format, including WMA. No second tag library. |
| Database | Room | Compile-checked SQL and `Flow` queries for Compose. |
| Search | `LIKE` over a few columns | `// ponytail: LIKE scan, fine to ~100k rows; switch to FTS4 if search lags` |
| Folder picker | our own tree with tri-state checkboxes | Needed for include/exclude at any depth; the system picker cannot do that. |

**First thing to confirm on a phone (API 30 Moto and API 34+ OnePlus):** with only the media permission,
`File("/storage/emulated/0/Music").listFiles()` returns the audio files and subfolders. Android filters what an app
sees through the media index. If a version hides files that exist, add a second enumerator that asks `MediaStore`
for `_data` paths under the selected roots; everything after enumeration only needs paths. Do not build that
enumerator unless the check fails.

## T1. Schema

```kotlin
@Entity(tableName = "tracks", indices = [Index("path", unique = true), Index("folderId"), Index("album"), Index("artist")])
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String, val folderId: Long, val fileName: String,
    val size: Long, val mtime: Long,
    val title: String, val artist: String, val album: String, val albumArtist: String,
    val genre: String, val year: Int, val trackNo: Int, val discNo: Int,
    val durationMs: Long, val sampleRate: Int, val bits: Int, val channels: Int,
    val codec: String, val container: String, val bitrate: Int,
    val exactFrames: Long?,                 // decoded count at source rate, set by the analyser (M5)
    val rgTrackGain: Float?, val rgTrackPeak: Float?, val rgAlbumGain: Float?, val rgAlbumPeak: Float?,
    val artHash: String?,                   // set after the first artwork lookup
    val metaState: Int,                     // 0 pending, 1 ok, 2 broken (probe failed or crashed)
    val available: Boolean,                 // false while its storage volume is missing
    val dateAdded: Long, val playCount: Int, val lastPlayed: Long,
)

@Entity(tableName = "folders", indices = [Index("path", unique = true), Index("parentId")])
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String, val parentId: Long?, val name: String, val depth: Int,
    val tracksDirect: Int, val tracksTotal: Int, val durationTotalMs: Long,
    val dfsOrder: Int,                      // position in library order (natural-sorted depth-first)
    val available: Boolean,
)

@Entity(tableName = "scan_roots") data class ScanRoot(@PrimaryKey val path: String, val included: Boolean)
@Entity(tableName = "playlists") data class Playlist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long)
@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "position"])
data class PlaylistEntry(val playlistId: Long, val position: Int, val trackId: Long?, val path: String)
```

`scan_roots` semantics: a path is in the library if its longest matching prefix among the rows has
`included = true`. Unchecking a subfolder of an included folder adds a row with `included = false`.

Later milestones add `waveforms` (M5), `eq_presets` and `output_profiles` (M6, M8). Use Room migrations from the
first released build onward; before that, destructive migration is fine.

## T2. Storage roots and permission

- `StorageRoots.volumes()`: `StorageManager.storageVolumes` with `directory` (API 30+); on API 29 derive each root
  from `getExternalFilesDirs(null)` by cutting at `/Android/`. Each has a label ("Internal storage", the SD card's
  name) and a mounted flag.
- Permission flow: explain in one sentence, request, handle "denied" and "denied permanently" (button to app
  settings). The all-files setting opens `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` for our package and
  rescans when `Environment.isExternalStorageManager()` turns true.
- Default roots after the first grant: the primary volume's `Music` folder if it exists, otherwise the primary
  volume. Other volumes are offered in the picker, unchecked.

## T3. Scanner

Single entry point `Scanner.request(reason, full = false)`; requests are debounced (2 s) and serialised.

**Phase A — walk (fast, makes the library browsable within seconds):**

1. For each included root that is mounted and readable, walk with `File.listFiles()`. Skip directories named
   `Android`, names starting with `.`, and paths excluded by `scan_roots`. Follow no symlinks.
2. Collect files whose extension is in: `mp3 flac m4a m4b aac ogg oga opus wma wav aif aiff ape wv mpc tta dsf dff
   mka ac3 dts amr mp2 caf w64`. Record path, size, mtime.
3. Diff against the database by path:
   - new path → insert a row with `title = file name without extension`, `metaState = 0`.
   - same path, different size or mtime → mark `metaState = 0`, clear `exactFrames`, `artHash`, delete its waveform.
   - path gone → candidate for deletion.
   - **move detection**: a gone path and a new path with the same file name, size and mtime are one file that moved;
     update the row's path and folder instead of delete + insert (keeps play counts, playlists, waveform).
   - remaining gone paths under a scanned root → delete rows.
4. Roots that are not mounted or not readable: do **not** delete anything; set `available = false` on their tracks
   and folders. When they return, the walk sets `available = true` again.
5. Rebuild `folders` from the track paths (every ancestor up to its root; the root itself is a top-level folder),
   with counts, total duration and `dfsOrder` (children sorted with the natural comparator, depth-first).
6. Write in transactions of about 200 rows.

**Phase B — metadata (background, three probes in parallel):**

1. For each track with `metaState = 0`: crash-guard breadcrumb (see `01-native-decoder.md` T4; one breadcrumb file
   per probe worker), `Native.probe`, parse, update the row to `metaState = 1`, or `2` on failure. Title falls back
   to the file name, artist and album to empty.
2. Optional filter "Ignore tracks shorter than N seconds" (default 0 = off) deletes rows after probing.
3. Progress as `StateFlow<ScanProgress>(phase, found, probed, toProbe)`.

Triggers: first grant; app start if the setting "Scan on start" is on (default on); a `ContentObserver` on
`MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` if "Watch for new music" is on (default on, debounced 5 s); pull to
refresh in the library; "Rescan" and "Full rescan" (re-probes everything) in settings; root list changes.

`// ponytail: every scan stats every file (a few seconds per 20k files); cache directory mtimes if libraries above
50k files make that slow.`

## T4. Queries the UI and the queue need (`LibraryRepo`)

- `children(folderId)`: subfolders (natural order) then tracks in that folder, sorted by the setting "Sort songs in
  folders" (file name — default; track number; title). As `Flow`.
- `roots()`: top-level folders. If there is exactly one, the UI opens inside it.
- `tracksUnder(folderId, recursive)`: ids in library order (folder's own tracks first, then subfolders depth-first).
- `nextFolderAfter(folderId)`: the next folder in `dfsOrder` that has direct tracks, or null.
- Albums: `GROUP BY album, CASE WHEN albumArtist != '' THEN albumArtist ELSE 'f:' || folderId END` (an album
  without an album-artist tag is "the tracks with this album name in this folder", which suits folder-organised
  libraries and keeps compilations together). Artists: `COALESCE(NULLIF(albumArtist,''), artist)`.
- Smart lists: recently added (by `dateAdded`), most played, recently played.
- `search(text)`: tracks (title, artist, album, file name), albums, artists, folders (name); 50 per group.
- `playInfo(path)`: `TrackPlayInfo(knownFrames, gain)` for the data source, called off the main thread. Gain from
  the ReplayGain settings (M6); 1.0 until then.
- Play statistics: increment `playCount` and set `lastPlayed` when a track has played for half its length or 4
  minutes.

Natural comparator: digit runs compare as numbers, the rest case-insensitively through a `Collator`. JVM tests:
`Track 2` before `Track 10`, `01 - A` before `1 - B` rules, accents, empty strings.

## T5. Playlists

- Create, rename, delete, add tracks, remove, reorder.
- Entries store the track id **and** the path; after a scan, entries whose id vanished are re-linked by path.
- Import and export `.m3u` / `.m3u8` (UTF-8, relative paths resolved against the playlist's folder). The scanner
  lists playlist files it finds under the roots for one-tap import.

## T6. Artwork (`Artwork.kt`)

Resolution order for a track (setting "Prefer": embedded — default — or folder image):

1. Embedded picture: `Native.extractCover(path)`.
2. Folder image: in the track's directory, a file named `cover`, `folder`, `front`, `album` or `albumart` (any
   case) with extension `jpg`, `jpeg`, `png` or `webp`; the largest if several.
3. None: a generated placeholder (two-colour gradient derived from a hash of the album or folder name, with the
   first letter), so lists never show grey boxes.

Folder rows use the folder image, else the artwork of the first track under the folder (at most three levels down).

Caching:

- The first lookup hashes the picture bytes (length + first 64 KB, SHA-1) and stores the hash in `tracks.artHash`
  (`"none"` when there is no picture). Tracks of one album share a hash, so they share cache files.
- `cacheDir/art/<hash>_256.webp` (lists) and `<hash>_1024.webp` (player), written on first use, decoded with
  sampling so a 4000-pixel cover never sits in memory at full size.
- Compose loads `tfart://track/<id>?s=256` (or `tfart://folder/<id>`) through a Coil 3 `Fetcher`; Coil keeps the
  memory cache and does the crossfade.
- The same repository serves the session's `BitmapLoader` (M4) and the cast HTTP art endpoint (M7).

## T7. Minimal screens (restyled in M4)

Functional only, so the owner can browse and play at the end of M3:

- Permission screen → folder picker (volumes, expandable tree, tri-state boxes, track counts once known) →
  scanning indicator.
- Folder list with back navigation; tapping a track calls
  `QueueController.play(tracksUnder(folder, recursive = true), startIndex, source = Folder(...))`.
- Keep state in view models (`LibraryViewModel`, `FolderPickerViewModel`); M4 replaces only the composables.

## Tests

- JVM: natural comparator; `scan_roots` prefix rules; move detection; M3U parsing and writing; album grouping key.
- Device (`androidTest`): build a tree under `getExternalFilesDir("scan-test")` from the M1 corpus (three levels,
  100 files by copying), then: counts and hierarchy; add, modify, delete and move files and rescan; an unreadable
  root keeps its rows with `available = false`; a root that reappears restores them; tags read correctly;
  a corrupt file ends as `metaState = 2`.

## Exit checks

- On the owner's real library (or a copy of it on a test phone): time to a browsable folder tree after first
  permission grant, time until all tags are read, and rescan time with nothing changed. Budgets for 10,000 files on
  the OnePlus 12R: tree in under 15 s, tags in under 3 minutes, rescan in under 10 s. Record actual counts and
  times.
- The folder hierarchy matches the file system (spot-check five folders against `adb shell ls`).
- Removing and re-inserting an SD card (or renaming a root with all-files access to simulate) loses no rows, no
  playlist entries and no waveforms.
- A song renamed or moved to another folder keeps its play count.

## Pitfalls

- `listFiles()` returns null on unreadable directories; treat as empty and do not delete what was under them.
- `mtime` granularity on FAT/exFAT SD cards is 2 s; compare exactly as stored, do not "fix" it.
- Never probe on the main thread; never hold a database transaction across a probe.
- A playlist entry must survive its track being temporarily unavailable.
