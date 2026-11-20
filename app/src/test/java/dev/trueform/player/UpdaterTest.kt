package dev.trueform.player

import dev.trueform.player.update.Release
import dev.trueform.player.update.UpdateHttp
import dev.trueform.player.update.UpdateState
import dev.trueform.player.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** docs/plan/10-release-updater.md T4: parsing, the request rules and the download state machine. */
class UpdaterTest {
    // Shaped like GitHub's GET /repos/{owner}/{repo}/releases/latest (fields the updater reads, plus some it ignores)
    private val latest = """
        {"url":"https://api.github.com/repos/rockenrooster/trueform/releases/1","html_url":"https://github.com/rockenrooster/trueform/releases/tag/build-42",
         "id":1,"tag_name":"build-42","target_commitish":"abc","name":"Trueform 0.1.0 build 42","draft":false,"prerelease":false,
         "created_at":"2026-11-20T12:00:00Z","published_at":"2026-11-20T12:05:00Z",
         "assets":[
          {"name":"trueform.apk","content_type":"application/vnd.android.package-archive","size":5,
           "browser_download_url":"https://github.com/rockenrooster/trueform/releases/download/build-42/trueform.apk"},
          {"name":"trueform.apk.sha256","size":79,
           "browser_download_url":"https://github.com/rockenrooster/trueform/releases/download/build-42/trueform.apk.sha256"},
          {"name":"trueform-symbols.zip","size":1000,"browser_download_url":"https://example.invalid/s.zip"}],
         "body":"Trueform 0.1.0, build 42.\n\n**Changes:**\n- Faster scans (abc1234)"}
    """.trimIndent()

    private val apkBytes = "hello".toByteArray()
    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private inner class FakeHttp(var json: String = latest, var apk: ByteArray = apkBytes, var sha: String = "${hex(apkBytes)}  trueform.apk\n",
                                 var failDownload: Boolean = false) : UpdateHttp {
        var gets = 0
        override fun get(url: String, limit: Int): ByteArray {
            gets++
            return if (url.endsWith(".sha256")) sha.toByteArray() else json.toByteArray()
        }
        override fun download(url: String, to: File, progress: (Long) -> Unit) {
            if (failDownload) throw IOException("HTTP 502")
            to.writeBytes(apk)
            progress(apk.size.toLong())
        }
    }

    private var now = 1_000_000L
    private val dir: File = Files.createTempDirectory("updates").toFile()
    private fun updater(build: Int, http: UpdateHttp, verify: (File) -> String? = { null }) =
        Updater(build, "rockenrooster/trueform", dir, http, verify, { now }, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun parsing() {
        val r = Updater.parseRelease(latest)!!
        assertEquals(42, r.build)
        assertEquals("Trueform 0.1.0 build 42", r.title)
        assertEquals(5L, r.apkSize)
        assertTrue(r.apkUrl.endsWith("/build-42/trueform.apk"))
        assertTrue(r.shaUrl!!.endsWith("trueform.apk.sha256"))
        assertNull(Updater.parseRelease(latest.replace("build-42", "v1.0")))                 // not one of ours
        assertNull(Updater.parseRelease(latest.replace("\"trueform.apk\"", "\"other.apk\"")))   // no APK
        assertEquals("ab".repeat(32), Updater.parseChecksum("AB".repeat(32) + "  trueform.apk"))
        assertNull(Updater.parseChecksum("not a checksum"))
        assertEquals("Changes:\n•  Faster scans (abc1234)", dev.trueform.player.update.UpdateUi.notesText("**Changes:**\n- Faster scans (`abc1234`)"))
    }

    @Test
    fun requestRules() {
        val http = FakeHttp()
        updater(0, http).check(force = true)
        assertEquals("a build without a number never asks", 0, http.gets)

        val u = updater(41, http)
        u.check()
        assertEquals(1, http.gets)
        assertTrue(u.state.value is UpdateState.Available)
        now += 30_000; u.check()
        assertEquals("at most once a minute", 1, http.gets)
        u.check(force = true)
        assertEquals("Check now", 2, http.gets)
        now += 61_000; u.check()
        assertEquals(3, http.gets)

        val same = updater(42, FakeHttp())
        same.check()
        assertEquals(UpdateState.Idle, same.state.value)
    }

    private fun downloaded(http: FakeHttp, verify: (File) -> String? = { null }): UpdateState {
        val u = updater(41, http, verify)
        val r: Release = Updater.parseRelease(http.json)!!
        runBlocking { u.downloadNow(r) }
        return u.state.value
    }

    @Test
    fun downloads() {
        val ok = downloaded(FakeHttp())
        assertTrue(ok.toString(), ok is UpdateState.Ready)
        assertTrue((ok as UpdateState.Ready).file.exists())

        val badSum = downloaded(FakeHttp(sha = "0".repeat(64) + "  trueform.apk"))
        assertEquals("the checksum does not match", (badSum as UpdateState.Failed).reason)

        val http = downloaded(FakeHttp(failDownload = true))
        assertEquals("HTTP 502", (http as UpdateState.Failed).reason)

        val short = downloaded(FakeHttp(apk = "hell".toByteArray()))
        assertEquals("the download is incomplete", (short as UpdateState.Failed).reason)

        val otherKey = downloaded(FakeHttp()) { "it is signed with another key" }
        assertEquals("it is signed with another key", (otherKey as UpdateState.Failed).reason)

        assertFalse("failed downloads leave nothing behind",
            dir.listFiles()!!.any { it.name.endsWith(".part") })
    }
}
