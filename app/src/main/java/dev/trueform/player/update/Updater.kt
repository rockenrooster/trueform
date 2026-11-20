package dev.trueform.player.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A GitHub release of Trueform (tag build-<n>) with its APK. */
data class Release(val build: Int, val title: String, val notes: String, val pageUrl: String,
                   val apkUrl: String, val apkSize: Long, val shaUrl: String?)

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val fraction: Float) : UpdateState
    data class Ready(val release: Release, val file: File) : UpdateState
    data class Failed(val release: Release?, val reason: String) : UpdateState
}

/** The two HTTP operations the updater needs; a fake in the JVM tests. */
interface UpdateHttp {
    /** The body of a GET, at most [limit] bytes; IOException on anything but 2xx. */
    fun get(url: String, limit: Int): ByteArray
    /** Downloads [url] (following redirects) into [to], reporting bytes so far. */
    fun download(url: String, to: File, progress: (Long) -> Unit)
}

/**
 * The in-app updater (docs/plan/10-release-updater.md T4): asks GitHub for the latest release, downloads the APK,
 * checks its SHA-256 and that it is our app, signed with our key and newer, and leaves it Ready for [Installer].
 * Builds without a number (local and debug builds) never ask.
 */
class Updater(
    private val currentBuild: Int,
    private val repo: String,
    private val dir: File,
    private val http: UpdateHttp,
    /** null when the APK is ours, signed with our key and newer; otherwise why not (needs Android). */
    private val verifyPackage: (File) -> String?,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null
    private var lastCheck = Long.MIN_VALUE

    init {
        scope.launch { dir.listFiles()?.forEach { it.delete() } }   // what an earlier version left
    }

    /** At most one request at a time and one a minute, unless [force] ("Check now"). */
    fun check(force: Boolean = false) {
        if (currentBuild <= 0 || job?.isActive == true) return
        val now = clock()
        if (!force && lastCheck != Long.MIN_VALUE && now - lastCheck < 60_000) return
        lastCheck = now
        job = scope.launch { checkNow() }
    }

    suspend fun checkNow() {
        if (currentBuild <= 0) return
        val s = _state.value
        if (s is UpdateState.Downloading || s is UpdateState.Ready) return
        try {
            val body = http.get("https://api.github.com/repos/$repo/releases/latest", 1 shl 20)
            val r = parseRelease(String(body, Charsets.UTF_8))
            _state.value = if (r != null && r.build > currentBuild) UpdateState.Available(r) else UpdateState.Idle
        } catch (e: Exception) {
            Log.i(TAG, "update check failed: ${e.message}")   // offline is normal: no message
        }
    }

    fun download(release: Release) {
        if (job?.isActive == true && _state.value is UpdateState.Downloading) return
        job = scope.launch { downloadNow(release) }
    }

    suspend fun downloadNow(release: Release) {
        dir.mkdirs()
        val part = File(dir, "trueform-${release.build}.apk.part")
        val apk = File(dir, "trueform-${release.build}.apk")
        _state.value = UpdateState.Downloading(release, 0f)
        try {
            http.download(release.apkUrl, part) { n ->
                if (release.apkSize > 0) _state.value = UpdateState.Downloading(release, (n.toFloat() / release.apkSize).coerceIn(0f, 1f))
            }
            if (release.apkSize > 0 && part.length() != release.apkSize) throw IOException("the download is incomplete")
            val want = release.shaUrl?.let { parseChecksum(String(http.get(it, 4096), Charsets.UTF_8)) }
                ?: throw IOException("the release has no checksum")
            if (sha256(part) != want) throw IOException("the checksum does not match")
            verifyPackage(part)?.let { throw IOException(it) }
            apk.delete()
            if (!part.renameTo(apk)) throw IOException("cannot store the download")
            _state.value = UpdateState.Ready(release, apk)
        } catch (e: Exception) {
            part.delete()
            apk.delete()
            Log.w(TAG, "update download failed", e)
            _state.value = UpdateState.Failed(release, e.message ?: e.javaClass.simpleName)
        }
    }

    /** The installer reported a failure (UpdateReceiver). */
    fun installFailed(reason: String) {
        val r = (_state.value as? UpdateState.Ready)?.release
        _state.value = UpdateState.Failed(r, reason)
    }

    fun dismiss() {
        if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
    }

    companion object {
        private const val TAG = "TFUPDATE"
        private val TAG_RE = Regex("^build-(\\d+)$")

        /** GitHub's "latest release" JSON → a release with our assets, or null if it is not one of ours. */
        fun parseRelease(json: String): Release? {
            val o = JSONObject(json)
            val build = TAG_RE.find(o.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var apkSize = 0L
            var shaUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                when (a.optString("name")) {
                    "trueform.apk" -> { apkUrl = a.optString("browser_download_url"); apkSize = a.optLong("size") }
                    "trueform.apk.sha256" -> shaUrl = a.optString("browser_download_url")
                }
            }
            if (apkUrl.isNullOrEmpty()) return null
            return Release(build, o.optString("name").ifEmpty { "build $build" }, o.optString("body"),
                o.optString("html_url"), apkUrl, apkSize, shaUrl?.ifEmpty { null })
        }

        /** "<hex>  trueform.apk" (sha256sum format) → the hex, lower case; null if it is not a SHA-256. */
        fun parseChecksum(text: String): String? =
            text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** HttpURLConnection, 8 s timeouts, our User-Agent; nothing is sent but the request. */
object UrlHttp : UpdateHttp {
    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "trueform-updater")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    override fun get(url: String, limit: Int): ByteArray {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) throw IOException("response too large")
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    override fun download(url: String, to: File, progress: (Long) -> Unit) {
        val c = open(url)
        c.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        progress(total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }
}

/** Hands a verified APK to Android's installer (PackageInstaller; the user confirms). */
object Installer {
    /** null when [file] is our app, signed like this one, and newer; otherwise the reason. */
    fun verify(context: Context, file: File): String? {
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return "not an app package"
        if (apk.packageName != context.packageName) return "it is another app (${apk.packageName})"
        val me = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (apk.longVersionCode <= me.longVersionCode) return "it is not newer than this build"
        val theirs = apk.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val mine = me.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        if (theirs.isNullOrEmpty() || theirs != mine) return "it is signed with another key"
        return null
    }

    /** False when Android first needs "Install unknown apps" for Trueform: the settings page is opened instead. */
    fun install(activity: Activity, file: File): Boolean {
        val pm = activity.packageManager
        if (!pm.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return false
        }
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("trueform.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val done = PendingIntent.getBroadcast(activity, id, Intent(activity, UpdateReceiver::class.java), flags)
            session.commit(done.intentSender)
        }
        return true
    }
}

/** The installer's answers: show its confirmation screen, or report why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // this process is replaced by the new version
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "the installer stopped"
                (context.applicationContext as? dev.trueform.player.TrueformApp)?.graph?.updater?.installFailed(why)
                Toast.makeText(context, "Update not installed: $why", Toast.LENGTH_LONG).show()
            }
        }
    }
}
