package dev.trueform.player.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.trueform.player.AppGraph
import dev.trueform.player.BuildConfig
import dev.trueform.player.settings.S
import dev.trueform.player.ui.UiActions
import dev.trueform.player.ui.components.LocalGraph
import dev.trueform.player.ui.components.PillButton
import dev.trueform.player.ui.components.T
import dev.trueform.player.ui.components.TextBtn
import dev.trueform.player.ui.sheets.SheetKind
import dev.trueform.player.ui.theme.Tf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The update pill in the library's top bar and its sheet (docs/plan/10-release-updater.md T4). */
object UpdateUi {
    /** Shown while a newer build exists. Entering the library asks GitHub (at most once a minute). */
    @Composable
    fun Pill(actions: UiActions) {
        val g = LocalGraph.current
        val s by g.updater.state.collectAsState()
        LaunchedEffect(Unit) { if (g.settings[S.checkUpdates]) g.updater.check() }
        val r = releaseOf(s) ?: return   // a failed attempt keeps the pill: the sheet says why and offers "Try again"
        PillButton("Update · build ${r.build}", null, primary = false) { actions.sheet(SheetKind.Update) }
    }

    @Composable
    fun Sheet(actions: UiActions) {
        val g = LocalGraph.current
        val c = Tf.colors
        val context = LocalContext.current
        val s by g.updater.state.collectAsState()
        val r = releaseOf(s)
        // "Download and install": the installer starts as soon as the verified file is there
        LaunchedEffect(s) { (s as? UpdateState.Ready)?.let { ready -> context.activity()?.let { Installer.install(it, ready.file) } } }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(r?.title ?: "Updates", Tf.type.title)
            if (r == null) {
                T("Trueform is up to date.", Tf.type.body, c.textSecondary)
                return@Column
            }
            T("%.1f MB · playback stops while the update installs".format(r.apkSize / 1e6), Tf.type.caption, c.textSecondary)
            when (val st = s) {
                is UpdateState.Downloading -> {
                    T("Downloading… ${(st.fraction * 100).toInt()}%", Tf.type.label)
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surface3)) {
                        Box(Modifier.fillMaxWidth(st.fraction).height(6.dp).background(c.accent))
                    }
                }
                is UpdateState.Ready -> PillButton("Install", null, primary = true) {
                    context.activity()?.let { Installer.install(it, st.file) }
                }
                is UpdateState.Failed -> {
                    T("Not installed: ${st.reason}", Tf.type.caption, c.danger, maxLines = 3)
                    PillButton("Try again", null, primary = true) { g.updater.download(r) }
                }
                else -> PillButton("Download and install", null, primary = true) { g.updater.download(r) }
            }
            Box(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                T(notesText(r.notes), Tf.type.body, c.textSecondary, maxLines = Int.MAX_VALUE)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextBtn("View on GitHub") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))) }
                }
            }
        }
    }

    /** Settings → Updates → "Check now"; false when [key] is not an update action. */
    fun runAction(g: AppGraph, key: String, actions: UiActions): Boolean {
        if (key != S.checkNow.key) return false
        if (BuildConfig.BUILD_NUMBER <= 0) {
            actions.sheet(SheetKind.Message("Updates", "This is a local build: it does not ask GitHub for updates."))
            return true
        }
        g.io.launch {
            g.updater.checkNow()
            withContext(Dispatchers.Main) {
                if (g.updater.state.value is UpdateState.Available) actions.sheet(SheetKind.Update)
                else actions.sheet(SheetKind.Message("Updates", "Trueform is up to date (build ${BuildConfig.BUILD_NUMBER})."))
            }
        }
        return true
    }

    /** A sheet's LocalContext is a wrapper around the activity, not the activity itself. */
    private tailrec fun android.content.Context.activity(): Activity? = when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.activity()
        else -> null
    }

    private fun releaseOf(s: UpdateState): Release? = when (s) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Ready -> s.release
        is UpdateState.Failed -> s.release
        UpdateState.Idle -> null
    }

    /** Release notes as plain text: no Markdown emphasis or code marks, "-" lines as bullets. */
    fun notesText(md: String): String = md.lines().joinToString("\n") { line ->
        line.replace("**", "").replace("`", "").let { if (it.startsWith("- ")) "•  " + it.removePrefix("- ") else it }
    }.trim()
}
