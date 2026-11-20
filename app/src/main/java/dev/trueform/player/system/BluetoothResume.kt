package dev.trueform.player.system

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.TrueformApp
import dev.trueform.player.settings.S
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * "Resume when a Bluetooth device connects" (docs/plan/08-bluetooth-system.md T3). The receiver is enabled only while
 * the setting is on, so other Bluetooth connections (a watch, a car without audio) never start the app. Android 12+
 * delivers these broadcasts only with BLUETOOTH_CONNECT, which Settings asks for when the setting is turned on.
 */
@UnstableApi
class BluetoothResume : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as TrueformApp).graph
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ->
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED
            else -> false
        }
        if (!connected || !g.settings[S.resumeOnConnect]) return
        if (g.settings[S.resumeOnlyIfPlaying]) {
            val at = g.settings.privateLong(NOISY_AT)
            if (at == 0L || System.currentTimeMillis() - at > 30 * 60_000L) return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < 10_000) return   // one connection sends both broadcasts
        lastTrigger = now
        val pending = goAsync()
        g.main.launch {
            try {
                delay(g.settings[S.resumeDelayMs].toLong())   // let the audio route move to the device
                if (!OutputRoutes.bluetoothOutput(context.getSystemService(AudioManager::class.java))) {
                    Log.i(TAG, "a Bluetooth device connected, but no Bluetooth audio output: not resuming")
                    return@launch
                }
                val c = g.controller().await()
                if (c.mediaItemCount == 0) return@launch
                c.play()
                g.settings.putPrivateLong(NOISY_AT, 0)
                Log.i(TAG, "resumed on Bluetooth connect")
            } catch (e: Exception) {
                Log.w(TAG, "resume on connect failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "TFBT"
        /** When playback last paused because the output went away (headphones out, Bluetooth gone); 0: not since. */
        const val NOISY_AT = "noisy_pause_at"
        private var lastTrigger = 0L

        fun setEnabled(context: Context, on: Boolean) {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, BluetoothResume::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }
    }
}
