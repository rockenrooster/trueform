package dev.trueform.player

import android.app.Application
import android.os.StrictMode
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.system.Logs

@UnstableApi
class TrueformApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {   // docs/plan/11-testing-hardening.md hardening 2: log only
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                .detectNetwork().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects().penaltyLog().build())
        }
        graph = AppGraph(this)
        Logs.install(this) { crashState() }
    }

    /** One line about the player for a crash report: what played where, never more. */
    private fun crashState(): String = runCatching {
        val q = graph.queue.state.value
        val t = graph.queue.currentTrack()
        "${q.entries.size} queued, entry ${q.index}, ${t?.formatLabel ?: "no track"}, build ${BuildConfig.BUILD_NUMBER}, " +
            "Android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    }.getOrDefault("?")
}
