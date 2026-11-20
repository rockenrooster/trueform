package dev.trueform.player

import android.app.Application
import androidx.media3.common.util.UnstableApi

@UnstableApi
class TrueformApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
