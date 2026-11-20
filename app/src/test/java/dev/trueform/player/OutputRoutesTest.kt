package dev.trueform.player

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_HDMI
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
import dev.trueform.player.system.OutputRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/plan/08-bluetooth-system.md T4: the output priority on API 29-32. */
class OutputRoutesTest {
    @Test
    fun priority() {
        val p = OutputRoutes::pick
        assertEquals(TYPE_BUILTIN_SPEAKER, OutputRoutes.pick(listOf(TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_SPEAKER)))
        assertEquals(TYPE_WIRED_HEADPHONES, OutputRoutes.pick(listOf(TYPE_BUILTIN_SPEAKER, TYPE_WIRED_HEADPHONES)))
        assertEquals(TYPE_USB_HEADSET, OutputRoutes.pick(listOf(TYPE_WIRED_HEADPHONES, TYPE_USB_HEADSET, TYPE_HDMI)))
        assertEquals(TYPE_BLUETOOTH_A2DP, OutputRoutes.pick(listOf(TYPE_BUILTIN_SPEAKER, TYPE_USB_HEADSET, TYPE_BLUETOOTH_A2DP)))
        assertEquals(TYPE_BLE_HEADSET, OutputRoutes.pick(listOf(TYPE_BLE_HEADSET, TYPE_BUILTIN_SPEAKER)))
        assertEquals(TYPE_HDMI, OutputRoutes.pick(listOf(TYPE_HDMI, TYPE_BUILTIN_SPEAKER)))
        assertNull(OutputRoutes.pick(listOf(TYPE_BLUETOOTH_SCO)))   // a call headset only: not a media output
        assertNull(p(emptyList()))
    }
}
