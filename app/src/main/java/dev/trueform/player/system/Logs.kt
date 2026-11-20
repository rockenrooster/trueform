package dev.trueform.player.system

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import dev.trueform.player.AppGraph
import dev.trueform.player.settings.S
import java.io.File

/**
 * Crash capture and "Export logs" (docs/plan/11-testing-hardening.md, Hardening 1): the last crash's stack trace and
 * state go to filesDir/last-crash.txt; the export shares it with the cast log. No network, no analytics.
 */
object Logs {
    fun crashFile(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context, state: () -> String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile(context).writeText("Trueform crashed on thread ${thread.name} at ${java.util.Date()}\n\n" +
                    android.util.Log.getStackTraceString(e) + "\n\nState: " + runCatching(state).getOrDefault("?"))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    @UnstableApi
    fun runAction(g: AppGraph, key: String, context: Context): Boolean {
        if (key != S.exportLogs.key) return false
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val out = File(dir, "trueform-logs.txt")
        out.bufferedWriter().use { w ->
            w.write("Trueform logs\n\n")
            crashFile(context).takeIf { it.exists() }?.let { w.write(it.readText()); w.write("\n\n") }
            File(context.filesDir, "cast.log").takeIf { it.exists() }?.let { w.write("Cast log:\n"); w.write(it.readText()) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share logs")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
