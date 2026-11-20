package dev.trueform.player.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import dev.trueform.player.dsp.DspChain
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The DSP chain right before the AudioTrack (docs/plan/02-playback-engine.md T3). DefaultAudioSink runs no custom
 * audio processors on float output, so the EQ lives here. A buffer is processed once, however many times the
 * renderer hands it in again; a read-only buffer is processed into a private copy that is handed on instead.
 */
@UnstableApi
class DspAudioSink(sink: AudioSink, private val dsp: DspChain) : ForwardingAudioSink(sink) {
    private var active = false
    private var processed = false
    private var replacement: ByteBuffer? = null
    private var scratch = FloatArray(0)
    private var warned = false

    /** Frames handed to the sink since the last configure, for the gapless check (debug log). */
    @Volatile var framesThisStream = 0L
        private set

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val f = audioSinkConfig.format
        active = f.sampleMimeType == MimeTypes.AUDIO_RAW && f.pcmEncoding == C.ENCODING_PCM_FLOAT && f.channelCount == 2
        if (active) {
            dsp.configure(f.sampleRate)
        } else if (!warned) {
            warned = true
            Log.w(TAG, "not float stereo, DSP bypassed: $f")
        }
        if (framesThisStream > 0) Log.d(TAG, "stream frames: $framesThisStream")
        framesThisStream = 0
        super.configure(audioSinkConfig)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        var out = buffer
        if (active) {
            if (!processed) {
                framesThisStream += buffer.remaining() / 8
                replacement = process(buffer)
                processed = true
            }
            replacement?.let { out = it }
        }
        val done = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
        if (done) {
            processed = false
            replacement = null
        }
        return done
    }

    override fun flush() {
        processed = false
        replacement = null
        dsp.reset()
        super.flush()
    }

    override fun reset() {
        processed = false
        replacement = null
        dsp.reset()
        super.reset()
    }

    /** In place when writable (returns null); otherwise a processed copy. */
    private fun process(buffer: ByteBuffer): ByteBuffer? {
        val bytes = buffer.remaining()
        val frames = bytes / 8
        if (frames == 0) return null
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.asFloatBuffer().get(scratch, 0, frames * 2)
        if (!dsp.apply(scratch, frames)) return null   // nothing to do: the buffer is untouched
        return if (!buffer.isReadOnly) {
            view.asFloatBuffer().put(scratch, 0, frames * 2)
            null
        } else {
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN).also {
                it.asFloatBuffer().put(scratch, 0, frames * 2)
            }
        }
    }

    private companion object {
        const val TAG = "TFAUDIO"
    }
}
