package dev.trueform.player

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.trueform.player.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The browse tree as a car or a Bluetooth controller sees it (docs/plan/08-bluetooth-system.md T2), through a real
 * MediaBrowser. Needs a scanned library on the phone (the demo library); plays nothing (the volume is 0 and the queue
 * is only prepared).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LibraryTreeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())

    /** Media3 controllers live on the main thread; the test thread waits for their futures. */
    private fun <T> onMain(block: () -> ListenableFuture<T>): T {
        var f: ListenableFuture<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { f = block() }
        return f!!.get(20, TimeUnit.SECONDS)
    }

    private fun children(b: MediaBrowser, id: String): List<MediaItem> =
        onMain { b.getChildren(id, 0, 500, null) }.value!!.toList()

    @Test
    fun browseSearchAndPlay() {
        val g = (context.applicationContext as TrueformApp).graph
        InstrumentationRegistry.getInstrumentation().runOnMainSync { g.corePlayer.volume = 0f }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val b = onMain { MediaBrowser.Builder(context, token).setApplicationLooper(Looper.getMainLooper()).buildAsync() }
        try {
            val root = onMain { b.getLibraryRoot(null) }.value!!
            assertEquals("root", root.mediaId)
            val top = children(b, root.mediaId).map { it.mediaId }
            assertEquals(listOf("folders", "albums", "artists", "playlists", "recent"), top)

            // Folders, down to the first folder with tracks: every node browsable, every track playable
            var level = children(b, "folders")
            assertTrue("no top-level folders: is the library scanned?", level.isNotEmpty())
            var track: MediaItem? = null
            var parent = ""
            for (depth in 0 until 5) {
                track = level.firstOrNull { it.mediaMetadata.isPlayable == true }
                if (track != null) break
                val next = level.first { it.mediaMetadata.isBrowsable == true }
                parent = next.mediaId
                level = children(b, parent)
            }
            assertNotNull("no track within 5 levels", track)
            assertTrue(track!!.mediaId, track.mediaId.startsWith("track:") && track.mediaId.endsWith("@$parent"))
            val folderTracks = level.count { it.mediaMetadata.isPlayable == true }

            // Albums and artists have children too
            val album = children(b, "albums").first()
            assertTrue(children(b, album.mediaId).isNotEmpty())
            val artist = children(b, "artists").first()
            assertTrue(children(b, artist.mediaId).isNotEmpty())

            // "Play this" = the folder from this track
            onMain { b.setMediaItem(track); b.prepare(); com.google.common.util.concurrent.Futures.immediateFuture(Unit) }
            Thread.sleep(1500)
            var queued = 0
            var currentTrack = -1L
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val s = g.queue.state.value
                queued = s.entries.size
                currentTrack = s.current?.trackId ?: -1
            }
            val trackId = track.mediaId.substringAfter("track:").substringBefore("@").toLong()
            assertEquals(trackId, currentTrack)
            assertTrue("queue $queued, folder $folderTracks", queued >= folderTracks)

            // Voice search
            val title = track.mediaMetadata.title.toString()
            val word = title.split(' ').first { it.length >= 3 }
            onMain { b.search(word, null) }
            val found = onMain { b.getSearchResult(word, 0, 50, null) }.value!!
            assertTrue("search '$word' found nothing", found.any { it.mediaId.startsWith("track:$trackId@") })
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { b.pause(); b.release() }
        }
    }
}
