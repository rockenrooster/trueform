package dev.trueform.player.settings

import dev.trueform.player.settings.Category.ADVANCED
import dev.trueform.player.settings.Category.AUDIO
import dev.trueform.player.settings.Category.BLUETOOTH
import dev.trueform.player.settings.Category.CAST
import dev.trueform.player.settings.Category.EQUALIZER
import dev.trueform.player.settings.Category.LIBRARY
import dev.trueform.player.settings.Category.LOOK
import dev.trueform.player.settings.Category.PLAYBACK
import dev.trueform.player.settings.Category.UPDATES

enum class OutputRate(val hz: Int, val label: String) {
    SOURCE(0, "Same as the file"), HZ_44100(44100, "44.1 kHz"), HZ_48000(48000, "48 kHz"),
    HZ_88200(88200, "88.2 kHz"), HZ_96000(96000, "96 kHz"), HZ_176400(176400, "176.4 kHz"), HZ_192000(192000, "192 kHz"),
}
enum class MaxRate(val hz: Int, val label: String) { HZ_48000(48000, "48 kHz"), HZ_96000(96000, "96 kHz"), HZ_192000(192000, "192 kHz") }
enum class ResamplerQuality(val code: Int, val label: String) { NORMAL(0, "Normal"), HIGH(1, "High"), VERY_HIGH(2, "Very high") }
enum class AfterQueue(val label: String) { NEXT_FOLDER("Continue with the next folder"), STOP("Stop") }
enum class TapPlays(val label: String) {
    FOLDER("This folder"), FOLDER_RECURSIVE("This folder and its subfolders"), LIBRARY("The whole library from here"),
}
enum class FolderSort(val label: String) { FILE_NAME("File name"), TRACK_NUMBER("Track number"), TITLE("Title") }
enum class ArtPrefer(val label: String) { EMBEDDED("Embedded picture"), FOLDER("Folder image") }
enum class ReplayGainMode(val label: String) { OFF("Off"), TRACK("Track"), ALBUM("Album") }
enum class Ceiling(val db: Float, val label: String) { DB0(0f, "0 dBFS"), DB_05(-0.5f, "−0.5 dBFS"), DB_1(-1f, "−1 dBFS") }
enum class GainRange(val db: Float, val label: String) { DB6(6f, "±6 dB"), DB12(12f, "±12 dB"), DB15(15f, "±15 dB") }
enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), BLACK("Black"), SYSTEM("Follow system") }
enum class PlayerBackground(val label: String) { GRADIENT("Gradient"), BLUR("Blurred artwork"), PLAIN("Plain") }
enum class Density(val label: String) { COMFORTABLE("Comfortable"), COMPACT("Compact") }
enum class WaveDetail(val bar: Float, val gap: Float, val label: String) {
    FINE(2f, 1f, "Fine"), MEDIUM(3f, 1.5f, "Medium"), BOLD(4f, 2f, "Bold"),
}
enum class WaveShape(val label: String) { MIRRORED("Smooth"), BARS("Bars") }   // names stored; labels shown
enum class WaveColor(val label: String) { SPECTRAL("Spectral"), ACCENT("Accent") }
enum class CastQuality(val bits: Int, val maxRate: Int, val label: String) {
    HIGH(24, 96000, "24-bit up to 96 kHz"), COMPAT(16, 48000, "16-bit 48 kHz (compatibility)"),
}
enum class NotificationButtons(val label: String) { SHUFFLE_REPEAT("Shuffle and repeat"), NONE("None") }

/** Every setting, its default and its row (docs/plan/09-settings.md). */
object S {
    // Library
    val musicFolders = Action("music_folders", LIBRARY, "Music folders", "Choose the folders scanned for music", "folders scan library")
    val scanOnStart = Toggle("scan_on_start", true, LIBRARY, "Scan on start", "Look for new and changed files when the app opens", "rescan")
    val watchMusic = Toggle("watch_music", true, LIBRARY, "Watch for new music", "Rescan when Android reports new audio files", "rescan new")
    val rescan = Action("rescan", LIBRARY, "Rescan now", "Find new, changed and removed files", "scan")
    val fullRescan = Action("full_rescan", LIBRARY, "Full rescan", "Read every file's tags again", "scan tags")
    val allFilesAccess = Action("all_files", LIBRARY, "All files access", "Needed for folders with .nomedia and for APE, WavPack, DSD files", "permission storage")
    val minDurationSec = Number("min_duration", 0, LIBRARY, "Ignore tracks shorter than", 0..120, 5, "s", "0 = keep everything", "short", Applies.RESCAN)
    val folderSort = Choice("folder_sort", FolderSort.FILE_NAME, LIBRARY, "Sort songs in folders by", FolderSort.entries, { it.label }, keywords = "order")
    val tapPlays = Choice("tap_plays", TapPlays.FOLDER_RECURSIVE, LIBRARY, "Tapping a song plays", TapPlays.entries, { it.label }, keywords = "queue folder")
    val afterQueue = Choice("after_queue", AfterQueue.NEXT_FOLDER, LIBRARY, "After the queue ends", AfterQueue.entries, { it.label }, keywords = "folder continue next")
    val artPrefer = Choice("art_prefer", ArtPrefer.EMBEDDED, LIBRARY, "Artwork: prefer", ArtPrefer.entries, { it.label }, keywords = "cover album art")
    val libraryTabs = Text("library_tabs", "folders,albums,artists,songs,playlists", LIBRARY, "Library tabs", "Order and visibility of the library sections", "tabs sections")

    // Playback
    val previousRestarts = Toggle("prev_restarts", true, PLAYBACK, "Previous restarts the track after 3 s", keywords = "back previous")
    val stopOnSwipe = Toggle("stop_on_swipe", false, PLAYBACK, "Stop when the app is swiped away", keywords = "recents close")
    val playOnOpen = Toggle("play_on_open", false, PLAYBACK, "Start playing when the app opens", keywords = "autoplay")
    val respectFocus = Toggle("respect_focus", true, PLAYBACK, "Respect other apps' audio", "Pause or duck when another app plays", "focus duck", Applies.RECREATE_PLAYER)
    val resumeAfterInterruption = Toggle("resume_after_interruption", true, PLAYBACK, "Resume after an interruption", "After a call or a notification", "focus call")
    val sleepFade = Toggle("sleep_fade", true, PLAYBACK, "Sleep timer: fade out at the end", keywords = "timer sleep")
    val keepScreenOn = Toggle("keep_screen_on", false, PLAYBACK, "Keep the screen on while the player is open", keywords = "display awake")

    // Audio
    val outputRate = Choice("output_rate", OutputRate.SOURCE, AUDIO, "Output sample rate", OutputRate.entries, { it.label }, "Resampling happens only when this differs from the file", "resample hz khz gapless", Applies.RELOAD_STREAM)
    val maxRate = Choice("max_rate", MaxRate.HZ_192000, AUDIO, "Maximum sample rate", MaxRate.entries, { it.label }, "Files above this are resampled", "hires resample", Applies.RELOAD_STREAM)
    val resamplerQuality = Choice("resampler_quality", ResamplerQuality.HIGH, AUDIO, "Resampler quality", ResamplerQuality.entries, { it.label }, keywords = "resample filter", applies = Applies.RELOAD_STREAM)
    val audioBufferMs = Number("audio_buffer_ms", 250, AUDIO, "Audio buffer", 100..1000, 50, "ms", "Longer is safer against dropouts; shorter makes EQ changes faster", "latency underrun dropout", Applies.RECREATE_PLAYER)
    val replayGain = Choice("replaygain", ReplayGainMode.OFF, AUDIO, "ReplayGain", ReplayGainMode.entries, { it.label }, "Even out loudness with the files' ReplayGain tags", "volume loudness normalize", Applies.RELOAD_STREAM)
    val replayGainPreamp = Decibels("replaygain_preamp", 0f, AUDIO, "ReplayGain preamp", -15f..15f, 0.5f, keywords = "volume loudness", applies = Applies.RELOAD_STREAM)
    val untaggedPreamp = Decibels("untagged_preamp", 0f, AUDIO, "Preamp for files without ReplayGain", -15f..0f, 0.5f, keywords = "volume loudness", applies = Applies.RELOAD_STREAM)
    val replayGainNoClip = Toggle("replaygain_noclip", true, AUDIO, "ReplayGain: prevent clipping", "Never raise a track past its peak", "clip peak", Applies.RELOAD_STREAM)
    val limiter = Toggle("limiter", true, AUDIO, "Limiter", "Keeps EQ boosts from clipping", "clip protection")
    val limiterCeiling = Choice("limiter_ceiling", Ceiling.DB0, AUDIO, "Limiter ceiling", Ceiling.entries, { it.label }, "A lower ceiling leaves room for Bluetooth codecs", "clip bluetooth")

    // Equalizer
    val gainRange = Choice("eq_gain_range", GainRange.DB12, EQUALIZER, "Gain range", GainRange.entries, { it.label }, keywords = "eq band slider")
    val accurateBands = Toggle("eq_accurate", true, EQUALIZER, "Accurate band gains", "The curve follows the sliders exactly", "eq correction")
    val openEqualizer = Action("open_equalizer", EQUALIZER, "Equalizer", "Bands, tone dials and presets", "bass treble mid sub tone eq curve loudness")
    val eqPerOutput = Toggle("eq_per_output", true, EQUALIZER, "Remember EQ per output", "Speaker, headphones and each Bluetooth device keep their own EQ", "bluetooth headphones profile")
    val outputProfiles = Action("output_profiles", EQUALIZER, "Output profiles", "Rename or delete saved EQ profiles", "bluetooth eq")
    val exportPresets = Action("export_presets", EQUALIZER, "Export presets", keywords = "eq backup")
    val importPresets = Action("import_presets", EQUALIZER, "Import presets", keywords = "eq restore")

    // Look and feel
    val theme = Choice("theme", ThemeMode.DARK, LOOK, "Theme", ThemeMode.entries, { it.label }, keywords = "dark light black amoled")
    val lockPortrait = Toggle("lock_portrait", false, LOOK, "Lock to portrait", "Trueform stays upright when the phone turns", "rotation orientation landscape rotate")
    val artworkColors = Toggle("artwork_colors", true, LOOK, "Colour from artwork", "The player takes its colours from the album art", "color accent")
    val playerBackground = Choice("player_background", PlayerBackground.GRADIENT, LOOK, "Player background", PlayerBackground.entries, { it.label }, keywords = "blur")
    val density = Choice("density", Density.COMFORTABLE, LOOK, "List density", Density.entries, { it.label }, keywords = "compact rows")
    val showFileNames = Toggle("show_file_names", false, LOOK, "Show file names instead of titles", keywords = "filename tags")
    val swipeToQueue = Toggle("swipe_to_queue", true, LOOK, "Swipe songs to queue", "Right: play next. Left: add to queue", "gesture")
    val swipeMini = Toggle("swipe_mini", true, LOOK, "Swipe the mini player to change track", keywords = "gesture next previous")
    val haptics = Toggle("haptics", true, LOOK, "Haptic feedback", keywords = "vibration")
    val waveDetail = Choice("wave_detail", WaveDetail.FINE, LOOK, "Waveform detail", WaveDetail.entries, { it.label }, keywords = "seekbar bars")
    val waveShape = Choice("wave_shape", WaveShape.BARS, LOOK, "Waveform shape", WaveShape.entries, { it.label }, keywords = "seekbar")
    val waveColor = Choice("wave_color", WaveColor.SPECTRAL, LOOK, "Waveform colour", WaveColor.entries, { it.label }, keywords = "seekbar spectrum")
    val seekWhileDragging = Toggle("seek_while_dragging", false, LOOK, "Seek while dragging", "Hear the position while scrubbing the waveform", "scrub seekbar")
    val waveCentred = Toggle("wave_centred", true, LOOK, "Centred playhead", "The waveform scrolls under a fixed playhead, about 30 seconds across. Off: the whole track", "seekbar zoom scroll poweramp")

    // Cast
    val castDsp = Toggle("cast_dsp", true, CAST, "Apply EQ and gain when casting", "Off: the Chromecast gets the original file wherever it can play it", "chromecast eq replaygain")
    val castQuality = Choice("cast_quality", CastQuality.HIGH, CAST, "Cast quality", CastQuality.entries, { it.label }, keywords = "chromecast bits sample rate")
    val castKeepAwake = Toggle("cast_keep_awake", true, CAST, "Keep the phone awake while casting", "Prevents dropouts during long casts with the screen off", "chromecast wake lock wifi")
    val castBattery = Action("cast_battery", CAST, "Battery restrictions", "Let Trueform run without battery restrictions", "chromecast battery optimization")
    val castResetKey = Action("cast_reset_key", CAST, "Reset cast key", "Invalidates the links a Chromecast holds", "chromecast security")
    val castLog = Action("cast_log", CAST, "Cast log", "What the Chromecast fetched and when", "chromecast debug")

    // Bluetooth and outputs
    val pauseOnDisconnect = Toggle("pause_on_disconnect", true, BLUETOOTH, "Pause when headphones or Bluetooth disconnect", keywords = "unplug noisy", applies = Applies.RECREATE_PLAYER)
    val resumeOnConnect = Toggle("resume_on_connect", false, BLUETOOTH, "Resume when a Bluetooth device connects", keywords = "car headset auto play")
    val resumeOnlyIfPlaying = Toggle("resume_only_if_playing", true, BLUETOOTH, "…only if it was playing when it disconnected", keywords = "bluetooth")
    val resumeDelayMs = Number("resume_delay_ms", 1500, BLUETOOTH, "…delay before resuming", 0..5000, 500, "ms", keywords = "bluetooth")
    val notificationButtons = Choice("notification_buttons", NotificationButtons.SHUFFLE_REPEAT, BLUETOOTH, "Notification buttons", NotificationButtons.entries, { it.label }, keywords = "lock screen shuffle repeat")

    // Updates
    val checkUpdates = Toggle("check_updates", true, UPDATES, "Check for updates", "Ask GitHub for a newer build when the library opens", "github release")
    val checkNow = Action("check_now", UPDATES, "Check now", keywords = "github release update")
    val buildInfo = Info("build_info", UPDATES, "This build", "version")

    // Advanced
    val audioDetails = Info("audio_details", ADVANCED, "Audio details", "output device sample rate underrun")
    val decoderInfo = Info("decoder_info", ADVANCED, "Decoder", "ffmpeg formats codecs")
    val clearWaveforms = Action("clear_waveforms", ADVANCED, "Clear waveform cache", keywords = "storage")
    val clearArtwork = Action("clear_artwork", ADVANCED, "Clear artwork cache", keywords = "storage images")
    val exportSettings = Action("export_settings", ADVANCED, "Export settings and presets", keywords = "backup")
    val importSettings = Action("import_settings", ADVANCED, "Import settings and presets", keywords = "restore")
    val exportLogs = Action("export_logs", ADVANCED, "Export logs", "Cast log, last crash and the player state", "debug report")
    val resetAll = Action("reset_all", ADVANCED, "Reset all settings", keywords = "defaults")
    val licenses = Action("licenses", ADVANCED, "Licenses", "Third-party software in Trueform", "open source ffmpeg")

    val all: List<Setting<*>> = listOf(
        musicFolders, scanOnStart, watchMusic, rescan, fullRescan, allFilesAccess, minDurationSec, folderSort, tapPlays,
        afterQueue, artPrefer, libraryTabs,
        previousRestarts, stopOnSwipe, playOnOpen, respectFocus, resumeAfterInterruption, sleepFade, keepScreenOn,
        outputRate, maxRate, resamplerQuality, audioBufferMs, replayGain, replayGainPreamp, untaggedPreamp, replayGainNoClip,
        limiter, limiterCeiling,
        openEqualizer, gainRange, accurateBands, eqPerOutput, outputProfiles, exportPresets, importPresets,
        theme, lockPortrait, artworkColors, playerBackground, density, showFileNames, swipeToQueue, swipeMini, haptics, waveDetail,
        waveShape, waveColor, waveCentred, seekWhileDragging,
        castDsp, castQuality, castKeepAwake, castBattery, castResetKey, castLog,
        pauseOnDisconnect, resumeOnConnect, resumeOnlyIfPlaying, resumeDelayMs, notificationButtons,
        checkUpdates, checkNow, buildInfo,
        audioDetails, decoderInfo, clearWaveforms, clearArtwork, exportSettings, importSettings, exportLogs, resetAll, licenses,
    )
}
