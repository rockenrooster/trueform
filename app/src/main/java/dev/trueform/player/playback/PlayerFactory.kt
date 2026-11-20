package dev.trueform.player.playback

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.wav.WavExtractor
import dev.trueform.player.audio.DecoderSource
import dev.trueform.player.audio.NativeDecoder
import dev.trueform.player.audio.PcmEncoding
import dev.trueform.player.audio.WavStream
import dev.trueform.player.dsp.DspChain
import dev.trueform.player.library.LibraryRepo
import dev.trueform.player.settings.S
import dev.trueform.player.settings.Settings
import java.io.IOException

/**
 * Opens files as WAV streams with the user's output settings. A file's declared length is remembered for the
 * process, so a seek's re-open declares the same length the extractor read first.
 */
class StreamOpener(private val settings: Settings, private val library: LibraryRepo) {
    private val lengths = LruCache<String, Long>(256)

    fun open(path: String, encoding: PcmEncoding, outRate: Int, maxRate: Int, processor: dev.trueform.player.audio.FrameProcessor? = null,
             gain: Float? = null): WavStream {
        val info = library.playInfo(path)
        val dec = NativeDecoder.open(path, outRate = outRate, maxRate = maxRate, quality = settings[S.resamplerQuality].code)
        try {
            dec.setGain(gain ?: info.gain)
            val key = "$path@${dec.outRate}"
            val known = lengths.get(key) ?: info.knownFrames?.takeIf { info.sourceRate > 0 }?.let {
                if (dec.outRate == info.sourceRate) it else it * dec.outRate / info.sourceRate
            }
            val stream = WavStream(DecoderSource(dec), encoding, known, processor)
            lengths.put(key, stream.totalFrames)
            return stream
        } catch (e: Throwable) {
            dec.close()
            throw e
        }
    }

    /** Local playback: float32 at the user's output rate. */
    fun openLocal(path: String): WavStream =
        open(path, PcmEncoding.FLOAT32, settings[S.outputRate].hz, settings[S.maxRate].hz)

    fun forget() = lengths.evictAll()

    /** A file's exact length became known (waveform analysis): the next open declares it. */
    fun forget(path: String) = lengths.snapshot().keys.filter { it.startsWith("$path@") }.forEach { lengths.remove(it) }
}

/** ExoPlayer's view of a file: our decoder behind a WAV header (docs/plan/02-playback-engine.md T2). */
@UnstableApi
class FfmpegWavDataSource(private val opener: StreamOpener) : BaseDataSource(false) {
    private var stream: WavStream? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw IOException("no path in ${dataSpec.uri}")
        val s = opener.openLocal(path)
        stream = s
        s.seek(dataSpec.position)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else s.length - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = stream!!.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            stream?.close()
        } finally {
            stream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val opener: StreamOpener) : DataSource.Factory {
        override fun createDataSource(): DataSource = FfmpegWavDataSource(opener)
    }
}

/** The local player (docs/plan/02-playback-engine.md T4). Main thread. */
@UnstableApi
object PlayerFactory {

    fun create(context: Context, settings: Settings, opener: StreamOpener, dsp: DspChain): Pair<ExoPlayer, DspAudioSink> {
        // Read at every AudioTrack creation, so the buffer setting applies at the next track or reload.
        val bufferSizes = DefaultAudioSink.AudioTrackBufferSizeProvider { minSize, encoding, outputMode, frameSize, rate, bitrate, speed ->
            DefaultAudioTrackBufferSizeProvider.Builder()
                .setTargetPcmBufferDurationUs(settings[S.audioBufferMs] * 1000)
                .build()
                .getBufferSizeInBytes(minSize, encoding, outputMode, frameSize, rate, bitrate, speed)
        }
        val sink = DspAudioSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .setAudioOutputProvider(
                    AudioTrackAudioOutputProvider.Builder(context).setAudioTrackBufferSizeProvider(bufferSizes).build())
                .build(),
            dsp)
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, sink))
        }
        val sources = ProgressiveMediaSource.Factory(
            FfmpegWavDataSource.Factory(opener), ExtractorsFactory { arrayOf(WavExtractor()) })
        val player = ExoPlayer.Builder(context, renderers, sources)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 30_000, 250, 500).build())
            .setAudioAttributes(attributes(), settings[S.respectFocus])
            .setHandleAudioBecomingNoisy(settings[S.pauseOnDisconnect])
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        return player to sink
    }

    fun attributes(): AudioAttributes =
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
}
