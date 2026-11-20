package dev.trueform.player.cast

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Cast events (docs/plan/07-chromecast.md T9): the last 2000 lines in memory, also appended to filesDir/cast.log,
 * rotated at 1 MB. Any thread; the file is written on its own thread.
 */
class CastLog(dir: File) {
    val file = File(dir, "cast.log")
    private val ring = ArrayDeque<String>()
    private val writer = Executors.newSingleThreadExecutor()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun add(message: String) {
        Log.i("TFCAST", message)
        val line = synchronized(ring) {
            "${time.format(Date())} $message".also {
                if (ring.size >= 2000) ring.removeFirst()
                ring.addLast(it)
            }
        }
        writer.execute {
            runCatching {
                if (file.length() > 1_000_000) file.renameTo(File(file.parentFile, "cast.log.1"))
                file.appendText(line + "\n")
            }
        }
    }

    fun lines(): List<String> = synchronized(ring) { ring.toList() }
}
