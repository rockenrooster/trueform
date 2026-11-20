package dev.trueform.player.system

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Where audio is going (docs/plan/08-bluetooth-system.md T4): a key for per-output EQ and a label for the player.
 * Casting overrides everything.
 */
class OutputRoutes(context: Context, private val onChange: (key: String, label: String) -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label
    private val _key = MutableStateFlow("speaker")
    val key: StateFlow<String> = _key
    private var cast: String? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = evaluate()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = evaluate()
    }

    fun start() {
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        evaluate()
    }

    fun setCast(deviceName: String?) {
        cast = deviceName
        evaluate()
    }

    fun evaluate() {
        val (k, l) = cast?.let { "cast:$it" to it } ?: describe(current())
        if (k != _key.value || l != _label.value) {
            _key.value = k
            _label.value = l
            onChange(k, l)
        }
    }

    private fun current(): AudioDeviceInfo? {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return pick(outs.map { it.type }).let { t -> outs.firstOrNull { it.type == t } }
    }

    companion object {
        private val BT = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
        private val USB = setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        private val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG)

        fun bluetoothOutput(am: AudioManager): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BT }

        /** Bluetooth, then USB, wired, HDMI, the speaker (API 29-32 have no "devices for attributes"). */
        fun pick(types: List<Int>): Int? = types.firstOrNull { it in BT } ?: types.firstOrNull { it in USB }
            ?: types.firstOrNull { it in WIRED } ?: types.firstOrNull { it == AudioDeviceInfo.TYPE_HDMI }
            ?: types.firstOrNull { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        fun describe(d: AudioDeviceInfo?): Pair<String, String> {
            val name = d?.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
            return when (d?.type) {
                in BT -> "bt:${name ?: "bluetooth"}" to (name ?: "Bluetooth")
                in USB -> "usb:${name ?: "usb"}" to (name ?: "USB audio")
                in WIRED -> "wired" to "Headphones"
                AudioDeviceInfo.TYPE_HDMI -> "hdmi" to "HDMI"
                else -> "speaker" to "Phone speaker"
            }
        }
    }
}

/**
 * Sleep timer (docs/plan/08-bluetooth-system.md T6): minutes, or the end of the current track; fades out over the
 * last 10 s when the setting is on, then pauses and restores the volume.
 */
class SleepTimer(private val scope: CoroutineScope, private val player: () -> Player, private val fade: () -> Boolean) {
    private val _remaining = MutableStateFlow<Int?>(null)
    /** Whole minutes left, or null when off (0 while it ends with the track). */
    val remainingMin: StateFlow<Int?> = _remaining
    private val _endOfTrack = MutableStateFlow(false)
    val endOfTrack: StateFlow<Boolean> = _endOfTrack
    private var job: Job? = null
    private var endsAt = 0L
    private var listener: Player.Listener? = null

    fun start(minutes: Int) {
        cancel()
        endsAt = System.currentTimeMillis() + minutes * 60_000L
        job = scope.launch {
            while (isActive) {
                val left = endsAt - System.currentTimeMillis()
                _remaining.value = ((left + 59_999) / 60_000).toInt().coerceAtLeast(0)
                if (left <= 10_000) break
                delay(minOf(15_000L, left - 10_000))
            }
            finish()
        }
    }

    fun extend(minutes: Int) {
        if (job?.isActive == true) {
            endsAt += minutes * 60_000L
            val left = endsAt - System.currentTimeMillis()
            start(((left + 59_999) / 60_000).toInt())
        }
    }

    fun startEndOfTrack() {
        cancel()
        val p = player()
        _endOfTrack.value = true
        _remaining.value = 0
        if (p is ExoPlayer) p.pauseAtEndOfMediaItems = true
        else listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { player().pause(); cancel() }
            }
        }.also { p.addListener(it) }
        scope.launch {   // turn the flag off once it has paused
            while (_endOfTrack.value) {
                delay(1000)
                if (!p.playWhenReady) { cancel(); break }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remaining.value = null
        if (_endOfTrack.value) {
            _endOfTrack.value = false
            (player() as? ExoPlayer)?.pauseAtEndOfMediaItems = false
            listener?.let { player().removeListener(it) }
            listener = null
        }
    }

    private suspend fun finish() {
        val p = player()
        val start = p.volume
        if (fade() && p.isPlaying) {
            for (i in 1..40) { p.volume = start * (1 - i / 40f); delay(250) }
        } else {
            val left = endsAt - System.currentTimeMillis()
            if (left > 0) delay(left)
        }
        p.pause()
        p.volume = start
        _remaining.value = null
        job = null
    }
}
