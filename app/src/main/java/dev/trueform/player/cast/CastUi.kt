package dev.trueform.player.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.AppGraph
import dev.trueform.player.settings.S
import dev.trueform.player.system.Logs
import dev.trueform.player.ui.UiActions
import dev.trueform.player.ui.components.LocalGraph
import dev.trueform.player.ui.components.SettingRow
import dev.trueform.player.ui.components.TextBtn
import dev.trueform.player.ui.sheets.SheetKind
import dev.trueform.player.ui.theme.Tf

/**
 * The cast button and the cast part of the output sheet (docs/plan/07-chromecast.md T8): Media3's route button with
 * the standard chooser and controller dialogs. Nothing shows where Cast is unavailable.
 */
@UnstableApi
object CastUi {
    @Composable
    fun Button(actions: UiActions) {
        val g = LocalGraph.current
        val cast = g.cast ?: return
        RouteButton()
        BatteryPrompt(g, cast, actions)
    }

    /** Media3's button is a Material icon button: it takes its colour from LocalContentColor, black by default. */
    @Composable
    private fun RouteButton() = CompositionLocalProvider(LocalContentColor provides Tf.colors.text) {
        MediaRouteButton(Modifier.size(48.dp))
    }

    @Composable
    fun Devices(actions: UiActions) {
        val g = LocalGraph.current
        val cast = g.cast ?: return
        val s by cast.state.collectAsState()
        val name = s.device ?: "Chromecast"
        when (s.phase) {
            CastState.Phase.NONE -> SettingRow("Cast", "Chromecast and Google Cast speakers on this Wi-Fi",
                trailing = { RouteButton() })
            CastState.Phase.CONNECTING -> SettingRow("Connecting to $name…", null)
            CastState.Phase.RECONNECTING -> SettingRow("Reconnecting to $name…", "It keeps playing what it has buffered")
            CastState.Phase.CONNECTED -> SettingRow("Casting to $name", "The volume keys control it",
                trailing = { TextBtn("Stop casting") { cast.stop(); actions.closeSheet() } })
        }
    }

    fun available(g: AppGraph): Boolean = g.cast != null

    /** Settings → Cast rows; false when [key] is not a cast action. */
    fun runAction(g: AppGraph, key: String, context: Context, actions: UiActions): Boolean {
        val cast = g.cast
        when (key) {
            S.castBattery.key -> requestBattery(context)
            S.castResetKey.key -> {
                cast?.resetKey()
                actions.sheet(SheetKind.Message("Cast key", "A new key is in use. Links a Chromecast held before no longer work."))
            }
            S.castLog.key -> actions.sheet(SheetKind.Message("Cast log",
                cast?.log?.lines()?.takeLast(300)?.reversed()?.joinToString("\n")?.ifEmpty { null } ?: "Nothing yet.",
                "Share") { Logs.runAction(g, S.exportLogs.key, context) })
            else -> return false
        }
        return true
    }

    /** Asked once, on the first cast (T5): hours with the screen off need the app exempt from battery limits. */
    @Composable
    private fun BatteryPrompt(g: AppGraph, cast: CastManager, actions: UiActions) {
        val context = LocalContext.current
        val s by cast.state.collectAsState()
        LaunchedEffect(s.phase) {
            if (s.phase != CastState.Phase.CONNECTED || g.settings.privateLong(ASKED) != 0L) return@LaunchedEffect
            if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) return@LaunchedEffect
            g.settings.putPrivateLong(ASKED, 1)
            actions.sheet(SheetKind.Message("Casting for hours", BATTERY_TEXT, "Allow") { requestBattery(context) })
        }
    }

    fun requestBattery(context: Context) {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(ask) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    private const val ASKED = "cast_battery_asked"
    private const val BATTERY_TEXT = "For hours of casting with the screen off, let Trueform run without battery " +
        "restrictions. On OnePlus and Oppo phones, also allow background activity in the app's battery settings; on " +
        "Samsung, set the app's battery use to Unrestricted."
}
