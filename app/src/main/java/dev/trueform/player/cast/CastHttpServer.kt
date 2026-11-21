package dev.trueform.player.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Chromecast fetches each track from here (docs/plan/07-chromecast.md T2): exact lengths, byte ranges, only
 * site-local clients, every URL behind a per-install key. Plain ServerSocket: one kind of client, three routes.
 */
class CastHttpServer(
    private val context: Context,
    private val key: () -> String,
    private val log: CastLog,
    private val handler: Handler,
    /** The port used last time: URLs a receiver still holds keep working after a restart (T2). */
    private val rememberedPort: () -> Int,
) {
    /** What the routes serve; implemented by CastManager (library, artwork, rendering). */
    interface Handler {
        fun file(trackId: Long): File?
        fun art(trackId: Long): File?
        /** A rendered WAV stream for the track with these query parameters, or null. */
        fun render(trackId: Long, query: Map<String, String>): Body?
    }

    /** A response body of known length, positioned by [open]. */
    interface Body : AutoCloseable {
        val length: Long
        val contentType: String
        fun open(offset: Long)
        fun read(buf: ByteArray, off: Int, len: Int): Int
    }

    @Volatile private var socket: ServerSocket? = null
    private var pool = Executors.newFixedThreadPool(8)
    @Volatile var host: String? = null
        private set
    @Volatile var port: Int = 0
        private set

    val baseUrl: String? get() = host?.let { h -> "http://$h:$port/s/${key()}" }

    @Synchronized
    fun start(): Boolean {
        val ip = wifiAddress() ?: run { log.add("server: no Wi-Fi address"); return false }
        if (socket != null && host == ip.hostAddress) return true
        stop()
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(8)
        for (p in (listOf(rememberedPort()) + (PREFERRED_PORT..PREFERRED_PORT + 10)).filter { it > 0 }.distinct()) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(ip, p))
                socket = s
                host = ip.hostAddress
                port = p
                Thread({ acceptLoop(s) }, "tf-cast-http").apply { isDaemon = true }.start()
                log.add("server: listening on ${ip.hostAddress}:$p")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "port $p: ${e.message}")
            }
        }
        return false
    }

    @Synchronized
    fun stop() {
        socket?.runCatching { close() }
        socket = null
        host = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: Exception) { break }
            if (!c.inetAddress.isSiteLocalAddress && !c.inetAddress.isLoopbackAddress) { c.close(); continue }
            if (android.os.SystemClock.elapsedRealtime() < blackoutUntil) { c.close(); continue }
            pool.execute { handle(c) }
        }
    }

    private val open = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var blackoutUntil = 0L

    /**
     * Tests only (debug command): the network "drops" for [ms]: open transfers are cut and new connections refused,
     * as when the receiver loses Wi-Fi for a moment (11-testing-hardening.md, the Wi-Fi blip, without switching the
     * phone's Wi-Fi). The receiver should ask again with a range and play on.
     */
    fun blackout(ms: Long) {
        blackoutUntil = android.os.SystemClock.elapsedRealtime() + ms
        open.forEach { runCatching { it.close() } }
        log.add("server: blackout for $ms ms")
    }

    private fun handle(c: Socket) {
        val started = System.currentTimeMillis()
        var line = ""
        var sent = 0L
        var outcome = "ok"
        open.add(c)
        try {
            c.soTimeout = 15_000
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val req = Request.parse(input) ?: return
            line = "${req.method} ${req.path.substringBefore("?")}" + (req.headers["range"]?.let { " $it" } ?: "")
            if (req.method == "OPTIONS") { respond(out, 204, "No Content", emptyMap(), 0); return }
            val route = Route.parse(req.path, key())
            if (route == null || (req.method != "GET" && req.method != "HEAD")) {
                respond(out, 404, "Not Found", emptyMap(), 0); outcome = "404"; return
            }
            val body: Body = when (route.kind) {
                'f' -> handler.file(route.trackId)?.let { FileBody(it) }
                'a' -> handler.art(route.trackId)?.let { FileBody(it) }
                'r' -> handler.render(route.trackId, route.query)
                else -> null
            } ?: run { respond(out, 404, "Not Found", emptyMap(), 0); outcome = "404"; return }
            body.use { b ->
                val range = Range.parse(req.headers["range"], b.length)
                if (range == Range.INVALID) {
                    respond(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */${b.length}"), 0)
                    outcome = "416"; return
                }
                val (from, to) = range?.let { it.first to it.last } ?: (0L to b.length - 1)
                val len = (to - from + 1).coerceAtLeast(0)
                val headers = mutableMapOf("Content-Type" to b.contentType, "Accept-Ranges" to "bytes")
                if (range != null) headers["Content-Range"] = "bytes $from-$to/${b.length}"
                respond(out, if (range != null) 206 else 200, if (range != null) "Partial Content" else "OK", headers, len)
                if (req.method == "HEAD" || len == 0L) return
                b.open(from)
                val buf = ByteArray(64 * 1024)
                var left = len
                while (left > 0) {
                    val n = b.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n; sent += n
                }
                out.flush()
            }
        } catch (e: Exception) {
            outcome = e.javaClass.simpleName
        } finally {
            open.remove(c)
            runCatching { c.close() }
            if (line.isNotEmpty()) log.add("http ${c.inetAddress.hostAddress} $line -> $outcome, ${sent / 1024} KB in ${System.currentTimeMillis() - started} ms")
        }
    }

    private fun respond(out: OutputStream, code: Int, reason: String, headers: Map<String, String>, length: Long) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Range\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    @Suppress("DEPRECATION")   // allNetworks: still the simple way to find the Wi-Fi network on API 29-36
    private fun wifiAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.let { return it }
        }
        return null
    }

    fun shutdown() {
        stop()
        pool.shutdownNow()
        pool.awaitTermination(1, TimeUnit.SECONDS)
    }

    private class FileBody(private val f: File, override val contentType: String = mime(f.extension)) : Body {
        private var raf: RandomAccessFile? = null
        override val length = f.length()
        override fun open(offset: Long) { raf = RandomAccessFile(f, "r").also { it.seek(offset) } }
        override fun read(buf: ByteArray, off: Int, len: Int) = raf!!.read(buf, off, len)
        override fun close() { raf?.close() }
    }

    /** The parsed request line and headers (lower-case names). Limited to 8 KB. */
    data class Request(val method: String, val path: String, val headers: Map<String, String>) {
        companion object {
            fun parse(input: InputStream): Request? {
                val lines = ArrayList<String>()
                val sb = StringBuilder()
                var total = 0
                while (true) {
                    val ch = input.read()
                    if (ch < 0) return null
                    if (++total > 8192) return null
                    if (ch == '\n'.code) {
                        val l = sb.toString().trimEnd('\r')
                        sb.setLength(0)
                        if (l.isEmpty()) break
                        lines += l
                    } else sb.append(ch.toChar())
                }
                val first = lines.firstOrNull()?.split(' ') ?: return null
                if (first.size < 2) return null
                val headers = lines.drop(1).mapNotNull { h ->
                    val i = h.indexOf(':'); if (i <= 0) null else h.substring(0, i).trim().lowercase() to h.substring(i + 1).trim()
                }.toMap()
                return Request(first[0].uppercase(), first[1], headers)
            }
        }
    }

    /** /s/<key>/<f|r|a>/<trackId>.<ext>?query */
    data class Route(val kind: Char, val trackId: Long, val query: Map<String, String>) {
        companion object {
            fun parse(path: String, key: String): Route? {
                val p = path.substringBefore('?')
                val q = path.substringAfter('?', "").split('&').filter { '=' in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
                val parts = p.trim('/').split('/')
                if (parts.size != 4 || parts[0] != "s") return null
                if (!constantTimeEquals(parts[1], key)) return null
                val kind = parts[2].singleOrNull()?.takeIf { it in "fra" } ?: return null
                val id = parts[3].substringBefore('.').toLongOrNull() ?: return null
                return Route(kind, id, q)
            }

            private fun constantTimeEquals(a: String, b: String): Boolean =
                MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
        }
    }

    /** Byte ranges: "bytes=a-", "bytes=a-b", "bytes=-n". Null: no Range header. */
    object Range {
        val INVALID = LongRange(-1, -2)
        fun parse(header: String?, length: Long): LongRange? {
            if (header == null) return null
            val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
            if (!header.trim().startsWith("bytes=") || '-' !in spec) return INVALID
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-').trim()
            if (length <= 0) return INVALID
            return when {
                a.isEmpty() -> {
                    val n = b.toLongOrNull() ?: return INVALID
                    if (n <= 0) INVALID else (length - n).coerceAtLeast(0)..(length - 1)
                }
                else -> {
                    val from = a.toLongOrNull() ?: return INVALID
                    val to = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return INVALID).coerceAtMost(length - 1)
                    if (from >= length || from > to) INVALID else from..to
                }
            }
        }
    }

    companion object {
        private const val TAG = "TFCAST"
        const val PREFERRED_PORT = 47836

        fun mime(ext: String) = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }

        fun keyTag(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    }
}
