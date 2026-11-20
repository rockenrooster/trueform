package dev.trueform.player

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.settings.S
import dev.trueform.player.ui.AppRoot
import dev.trueform.player.ui.onboarding.audioPermission
import dev.trueform.player.ui.onboarding.hasAudioPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class MainActivity : ComponentActivity() {
    private val g get() = (application as TrueformApp).graph
    private var needsSetup by mutableStateOf(false)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) needsSetup = rootsMissing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent bars without the light scrim of 3-button navigation; AppRoot sets the icon colours per theme.
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        needsSetup = !hasAudioPermission(this) || rootsMissing()
        setContent {
            AppRoot(g, needsSetup, onSetupDone = { needsSetup = false }, requestPermission = { permission.launch(audioPermission()) })
        }
        handleOpenWith(intent)
        lifecycleScope.launch {
            g.settings.flow(S.keepScreenOn).collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        lifecycleScope.launch { g.settings.flow(S.watchMusic).collect { g.watchMediaStore(it && hasAudioPermission(this@MainActivity)) } }
        if (hasAudioPermission(this) && !needsSetup && g.settings[S.scanOnStart]) g.scanner.request()
        if (g.settings[S.playOnOpen]) lifecycleScope.launch { g.controller(); if (g.corePlayer.mediaItemCount > 0) { g.corePlayer.prepare(); g.corePlayer.play() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenWith(intent)
    }

    override fun onStart() {
        super.onStart()
        g.controller()   // binds the playback service: the session and its notification exist while we may play
    }

    private fun rootsMissing(): Boolean = g.library.scanRoots().isEmpty()

    /** "Open with Trueform" (docs/plan/08-bluetooth-system.md T7). */
    private fun handleOpenWith(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { g.openWith(uri) }
            if (!ok) android.widget.Toast.makeText(this@MainActivity, "Trueform can't open that file", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
