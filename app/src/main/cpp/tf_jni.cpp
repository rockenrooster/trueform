// JNI glue for dev.trueform.player.audio.Native (registered by name in JNI_OnLoad, so R8 keeps nothing but the class).
// Strings that come from files cross as byte[] (UTF-8 decoded in Kotlin): NewStringUTF wants modified UTF-8 and
// aborts on malformed tag text. Paths go in as byte[] too, so file names with emoji survive.
#include <android/log.h>
#include <jni.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <strings.h>
#include <cmath>
#include <vector>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#include "tf_decoder.h"

extern "C" {
#include <libavcodec/codec_desc.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
}

namespace {

constexpr const char* kClass = "dev/trueform/player/audio/Native";

void logCallback(void*, int level, const char* fmt, va_list vl) {
    if (level > av_log_get_level()) return;
    __android_log_vprint(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "TFFF", fmt, vl);
}

std::vector<char> pathOf(JNIEnv* env, jbyteArray path) {
    std::vector<char> p;
    if (!path) {
        p.push_back('\0');
        return p;
    }
    jsize n = env->GetArrayLength(path);
    p.resize(static_cast<size_t>(n) + 1);
    env->GetByteArrayRegion(path, 0, n, reinterpret_cast<jbyte*>(p.data()));
    p[static_cast<size_t>(n)] = '\0';
    return p;
}

jbyteArray bytesOf(JNIEnv* env, const char* s) {
    jsize n = static_cast<jsize>(std::strlen(s));
    jbyteArray a = env->NewByteArray(n);
    if (a) env->SetByteArrayRegion(a, 0, n, reinterpret_cast<const jbyte*>(s));
    return a;
}

jstring ffmpegVersion(JNIEnv* env, jclass) { return env->NewStringUTF(av_version_info()); }

jstring errorString(JNIEnv* env, jclass, jint code) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(code, buf, sizeof(buf));
    for (char* c = buf; *c; ++c) {
        if (static_cast<unsigned char>(*c) > 0x7e) *c = '?';
    }
    return env->NewStringUTF(buf);
}

// Tag keys per probe() index; several spellings each. Compared case-insensitively.
const char* const kKeys[tf::META_CODEC][4] = {
    {"title", nullptr},
    {"artist", nullptr},
    {"album", nullptr},
    {"album_artist", "albumartist", "album artist", nullptr},
    {"genre", nullptr},
    {"date", "year", "originaldate", nullptr},
    {"track", "tracknumber", nullptr},
    {"disc", "discnumber", nullptr},
    {"composer", nullptr},
    {"replaygain_track_gain", nullptr},
    {"replaygain_track_peak", nullptr},
    {"replaygain_album_gain", nullptr},
    {"replaygain_album_peak", nullptr},
    {"r128_track_gain", nullptr},
    {"r128_album_gain", nullptr},
};

const char* findTag(const AVDictionary* streamMeta, const AVDictionary* fileMeta, int index) {
    for (const AVDictionary* d : {streamMeta, fileMeta}) {   // the stream wins (Ogg keeps comments there)
        if (!d) continue;
        for (int k = 0; k < 4 && kKeys[index][k]; ++k) {
            const AVDictionaryEntry* e = av_dict_get(d, kKeys[index][k], nullptr, 0);   // case-insensitive by default
            if (e && e->value && e->value[0]) return e->value;
        }
    }
    return nullptr;
}

jint probe(JNIEnv* env, jclass, jbyteArray jpath, jint fd, jobjectArray meta, jlongArray jnums) {
    std::vector<char> path = pathOf(env, jpath);
    AVFormatContext* fmt = nullptr;
    int r = tf::openInput(&fmt, path.data(), fd);
    if (r < 0) return r;
    int s = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    AVStream* st = s >= 0 ? fmt->streams[s] : nullptr;
    bool needInfo = !st || st->codecpar->sample_rate <= 0 || st->codecpar->ch_layout.nb_channels <= 0 ||
                    (st->duration == AV_NOPTS_VALUE && fmt->duration == AV_NOPTS_VALUE);
    if (needInfo) {
        if ((r = avformat_find_stream_info(fmt, nullptr)) < 0) {
            avformat_close_input(&fmt);
            return r;
        }
        s = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        st = s >= 0 ? fmt->streams[s] : nullptr;
    }
    if (!st) {
        avformat_close_input(&fmt);
        return AVERROR_STREAM_NOT_FOUND;
    }
    for (int i = 0; i < tf::META_CODEC; ++i) {
        const char* v = findTag(st->metadata, fmt->metadata, i);
        if (v) env->SetObjectArrayElement(meta, i, bytesOf(env, v));
    }
    const AVCodecDescriptor* cd = avcodec_descriptor_get(st->codecpar->codec_id);
    env->SetObjectArrayElement(meta, tf::META_CODEC, bytesOf(env, cd ? cd->name : "unknown"));
    env->SetObjectArrayElement(meta, tf::META_CONTAINER, bytesOf(env, fmt->iformat ? fmt->iformat->name : "unknown"));

    jlong nums[tf::NUM_COUNT] = {0};
    if (st->duration != AV_NOPTS_VALUE && st->duration > 0) {
        nums[tf::NUM_DURATION_US] = av_rescale_q(st->duration, st->time_base, AV_TIME_BASE_Q);
    } else if (fmt->duration != AV_NOPTS_VALUE) {
        nums[tf::NUM_DURATION_US] = fmt->duration;
    }
    nums[tf::NUM_SAMPLE_RATE] = st->codecpar->sample_rate;
    nums[tf::NUM_CHANNELS] = st->codecpar->ch_layout.nb_channels;
    int bits = st->codecpar->bits_per_raw_sample;
    if (bits <= 0 && st->codecpar->codec_id >= AV_CODEC_ID_FIRST_AUDIO && st->codecpar->codec_id < AV_CODEC_ID_ADPCM_IMA_QT) {
        bits = av_get_bits_per_sample(st->codecpar->codec_id);
    }
    nums[tf::NUM_BITS] = bits;
    nums[tf::NUM_BITRATE] = st->codecpar->bit_rate > 0 ? st->codecpar->bit_rate : fmt->bit_rate;
    for (unsigned i = 0; i < fmt->nb_streams; ++i) {
        if (fmt->streams[i]->disposition & AV_DISPOSITION_ATTACHED_PIC) nums[tf::NUM_HAS_COVER] = 1;
    }
    nums[tf::NUM_DURATION_IS_ESTIMATE] = fmt->duration_estimation_method == AVFMT_DURATION_FROM_BITRATE ? 1 : 0;
    env->SetLongArrayRegion(jnums, 0, tf::NUM_COUNT, nums);
    avformat_close_input(&fmt);
    return 0;
}

jbyteArray extractCover(JNIEnv* env, jclass, jbyteArray jpath, jint fd) {
    std::vector<char> path = pathOf(env, jpath);
    AVFormatContext* fmt = nullptr;
    if (tf::openInput(&fmt, path.data(), fd) < 0) return nullptr;
    jbyteArray result = nullptr;
    const AVPacket* best = nullptr;
    for (unsigned i = 0; i < fmt->nb_streams; ++i) {
        const AVStream* st = fmt->streams[i];
        if (!(st->disposition & AV_DISPOSITION_ATTACHED_PIC) || st->attached_pic.size <= 0) continue;
        const AVDictionaryEntry* c = av_dict_get(st->metadata, "comment", nullptr, 0);
        bool front = c && c->value && strcasestr(c->value, "front");
        if (!best || front) best = &st->attached_pic;   // the front cover when tagged, else the first picture
        if (front) break;
    }
    if (best) {
        result = env->NewByteArray(best->size);
        if (result) env->SetByteArrayRegion(result, 0, best->size, reinterpret_cast<const jbyte*>(best->data));
    }
    avformat_close_input(&fmt);
    return result;
}

jlong open(JNIEnv* env, jclass, jbyteArray jpath, jint fd, jint outRate, jint maxRate, jint quality, jlongArray jinfo,
           jintArray jerr) {
    std::vector<char> path = pathOf(env, jpath);
    int64_t info[tf::INFO_COUNT] = {0};
    int error = 0;
    tf::Decoder* d = tf::Decoder::open(path.data(), fd, outRate, maxRate, quality, info, &error);
    if (!d) {
        jint e = error;
        env->SetIntArrayRegion(jerr, 0, 1, &e);
        return 0;
    }
    jlong out[tf::INFO_COUNT];
    for (int i = 0; i < tf::INFO_COUNT; ++i) out[i] = info[i];
    env->SetLongArrayRegion(jinfo, 0, tf::INFO_COUNT, out);
    return reinterpret_cast<jlong>(d);
}

void setGain(JNIEnv*, jclass, jlong h, jfloat gain) { reinterpret_cast<tf::Decoder*>(h)->setGain(gain); }

jint read(JNIEnv* env, jclass, jlong h, jobject dst, jint maxFrames) {
    auto* p = static_cast<float*>(env->GetDirectBufferAddress(dst));
    jlong cap = env->GetDirectBufferCapacity(dst);
    if (!p || cap < static_cast<jlong>(maxFrames) * 8) return AVERROR(EINVAL);
    return reinterpret_cast<tf::Decoder*>(h)->read(p, maxFrames);
}

jint seek(JNIEnv*, jclass, jlong h, jlong frame) { return reinterpret_cast<tf::Decoder*>(h)->seek(frame); }

jlong scanFrames(JNIEnv*, jclass, jlong h) { return reinterpret_cast<tf::Decoder*>(h)->scanFrames(); }

void close(JNIEnv*, jclass, jlong h) { delete reinterpret_cast<tf::Decoder*>(h); }

void setTrace(JNIEnv*, jclass, jboolean on) { tf::gTrace = on; }

// The equalizer's biquad cascade (DspChain.Stage), in place over interleaved stereo doubles: transposed direct form II
// as in the Kotlin loop it replaces; on arm64 left and right are the two lanes of one register, with fused multiply-adds
// (five operations a sample instead of nine: the phones' little cores are limited by how many they can issue; results
// differ from the Kotlin loop by rounding only, about 1e-16 relative). coeffs: b0 b1 b2 a1 a2 per filter; z: z1l z2l
// z1r z2r per filter. States that fall into the denormal range are flushed (long silences).
void cascadeD(double* buf, jint frames, const double* k, double* z, jsize filters) {
    for (jsize f = 0; f < filters; ++f) {
        const double* c = k + f * 5;
        double* s = z + f * 4;
#if defined(__aarch64__)
        const float64x2_t b0 = vdupq_n_f64(c[0]), b1 = vdupq_n_f64(c[1]), b2 = vdupq_n_f64(c[2]);
        const float64x2_t na1 = vdupq_n_f64(-c[3]), na2 = vdupq_n_f64(-c[4]);
        float64x2_t z1 = {s[0], s[2]};
        float64x2_t z2 = {s[1], s[3]};
        for (jint i = 0; i < frames; ++i) {
            const float64x2_t x = vld1q_f64(buf + 2 * i);
            const float64x2_t y = vfmaq_f64(z1, b0, x);              // b0 x + z1
            z1 = vfmaq_f64(vfmaq_f64(z2, b1, x), na1, y);            // b1 x + z2 - a1 y
            z2 = vfmaq_f64(vmulq_f64(b2, x), na2, y);                // b2 x - a2 y
            vst1q_f64(buf + 2 * i, y);
        }
        double out[4] = {vgetq_lane_f64(z1, 0), vgetq_lane_f64(z2, 0), vgetq_lane_f64(z1, 1), vgetq_lane_f64(z2, 1)};
#else
        double z1l = s[0], z2l = s[1], z1r = s[2], z2r = s[3];
        for (jint i = 0; i < frames; ++i) {
            const double xl = buf[2 * i], xr = buf[2 * i + 1];
            const double yl = c[0] * xl + z1l, yr = c[0] * xr + z1r;
            z1l = c[1] * xl - c[3] * yl + z2l; z2l = c[2] * xl - c[4] * yl;
            z1r = c[1] * xr - c[3] * yr + z2r; z2r = c[2] * xr - c[4] * yr;
            buf[2 * i] = yl; buf[2 * i + 1] = yr;
        }
        double out[4] = {z1l, z2l, z1r, z2r};
#endif
        for (int j = 0; j < 4; ++j) s[j] = std::fabs(out[j]) < 1e-30 ? 0.0 : out[j];
    }
}

void cascade(JNIEnv* env, jclass, jdoubleArray jbuf, jint frames, jdoubleArray jcoeffs, jdoubleArray jz) {
    const jsize filters = env->GetArrayLength(jcoeffs) / 5;
    if (frames <= 0 || filters <= 0 || env->GetArrayLength(jz) < filters * 4 || env->GetArrayLength(jbuf) < frames * 2) return;
    auto* buf = static_cast<double*>(env->GetPrimitiveArrayCritical(jbuf, nullptr));
    auto* k = static_cast<double*>(env->GetPrimitiveArrayCritical(jcoeffs, nullptr));
    auto* z = static_cast<double*>(env->GetPrimitiveArrayCritical(jz, nullptr));
    if (buf && k && z) cascadeD(buf, frames, k, z, filters);
    if (z) env->ReleasePrimitiveArrayCritical(jz, z, 0);
    if (k) env->ReleasePrimitiveArrayCritical(jcoeffs, k, JNI_ABORT);
    if (buf) env->ReleasePrimitiveArrayCritical(jbuf, buf, 0);
}

// One DSP stage in a single pass from the float buffer (DspChain.apply without a crossfade): float to double into
// work, preamp (skipped at exactly 1), the cascade, mono, balance (skipped at 1, 1), as DspChain.Stage.run does in
// Kotlin. Returns the largest magnitude in work (the limiter's "nothing to do" test).
jdouble stageIn(JNIEnv* env, jclass, jfloatArray jin, jint frames, jdoubleArray jwork, jdouble preamp,
                jdoubleArray jcoeffs, jdoubleArray jz, jboolean mono, jdouble gl, jdouble gr) {
    const jint n = frames * 2;
    const jsize filters = env->GetArrayLength(jcoeffs) / 5;
    if (frames <= 0 || env->GetArrayLength(jin) < n || env->GetArrayLength(jwork) < n || env->GetArrayLength(jz) < filters * 4) return 0;
    auto* in = static_cast<float*>(env->GetPrimitiveArrayCritical(jin, nullptr));
    auto* w = static_cast<double*>(env->GetPrimitiveArrayCritical(jwork, nullptr));
    auto* k = filters > 0 ? static_cast<double*>(env->GetPrimitiveArrayCritical(jcoeffs, nullptr)) : nullptr;
    auto* z = filters > 0 ? static_cast<double*>(env->GetPrimitiveArrayCritical(jz, nullptr)) : nullptr;
    double peak = 0;
    if (in && w && (filters == 0 || (k && z))) {
        for (jint i = 0; i < n; ++i) w[i] = static_cast<double>(in[i]);
        if (preamp != 1.0) for (jint i = 0; i < n; ++i) w[i] *= preamp;
        if (filters > 0) cascadeD(w, frames, k, z, filters);
        if (mono) for (jint i = 0; i < n; i += 2) { const double m = (w[i] + w[i + 1]) * 0.5; w[i] = m; w[i + 1] = m; }
        if (gl != 1.0 || gr != 1.0) for (jint i = 0; i < n; i += 2) { w[i] *= gl; w[i + 1] *= gr; }
        for (jint i = 0; i < n; ++i) { const double a = std::fabs(w[i]); if (a > peak) peak = a; }
    }
    if (z) env->ReleasePrimitiveArrayCritical(jz, z, 0);
    if (k) env->ReleasePrimitiveArrayCritical(jcoeffs, k, JNI_ABORT);
    if (w) env->ReleasePrimitiveArrayCritical(jwork, w, 0);
    if (in) env->ReleasePrimitiveArrayCritical(jin, in, JNI_ABORT);
    return peak;
}

// The way back: work to float into out, with the largest left and right magnitudes into peaks[0..1] (the meters).
void stageOut(JNIEnv* env, jclass, jdoubleArray jwork, jint frames, jfloatArray jout, jfloatArray jpeaks) {
    const jint n = frames * 2;
    if (frames <= 0 || env->GetArrayLength(jwork) < n || env->GetArrayLength(jout) < n || env->GetArrayLength(jpeaks) < 2) return;
    auto* w = static_cast<double*>(env->GetPrimitiveArrayCritical(jwork, nullptr));
    auto* out = static_cast<float*>(env->GetPrimitiveArrayCritical(jout, nullptr));
    auto* pk = static_cast<float*>(env->GetPrimitiveArrayCritical(jpeaks, nullptr));
    if (w && out && pk) {
        float pl = 0, pr = 0;
        for (jint i = 0; i < n; i += 2) {
            const float l = static_cast<float>(w[i]), r = static_cast<float>(w[i + 1]);
            out[i] = l; out[i + 1] = r;
            const float al = std::fabs(l), ar = std::fabs(r);
            if (al > pl) pl = al;
            if (ar > pr) pr = ar;
        }
        pk[0] = pl; pk[1] = pr;
    }
    if (pk) env->ReleasePrimitiveArrayCritical(jpeaks, pk, 0);
    if (out) env->ReleasePrimitiveArrayCritical(jout, out, 0);
    if (w) env->ReleasePrimitiveArrayCritical(jwork, w, JNI_ABORT);
}

// The limiter's idle case (Limiter.delayOnly): work goes through the delay line of [delay] stereo frames starting at
// [pos]; returns the new position.
jint delayLine(JNIEnv* env, jclass, jdoubleArray jwork, jint frames, jdoubleArray jline, jint pos, jint delay) {
    if (frames <= 0 || delay <= 0 || env->GetArrayLength(jwork) < frames * 2 || env->GetArrayLength(jline) < delay * 2) return pos;
    auto* w = static_cast<double*>(env->GetPrimitiveArrayCritical(jwork, nullptr));
    auto* line = static_cast<double*>(env->GetPrimitiveArrayCritical(jline, nullptr));
    if (w && line) {
        for (jint i = 0; i < frames; ++i) {
            const jint j = pos * 2;
            const double dl = line[j], dr = line[j + 1];
            line[j] = w[i * 2]; line[j + 1] = w[i * 2 + 1];
            w[i * 2] = dl; w[i * 2 + 1] = dr;
            pos = pos + 1 == delay ? 0 : pos + 1;
        }
    }
    if (line) env->ReleasePrimitiveArrayCritical(jline, line, 0);
    if (w) env->ReleasePrimitiveArrayCritical(jwork, w, 0);
    return pos;
}

const JNINativeMethod kMethods[] = {
    {"ffmpegVersion", "()Ljava/lang/String;", reinterpret_cast<void*>(ffmpegVersion)},
    {"errorString", "(I)Ljava/lang/String;", reinterpret_cast<void*>(errorString)},
    {"probe", "([BI[[B[J)I", reinterpret_cast<void*>(probe)},
    {"extractCover", "([BI)[B", reinterpret_cast<void*>(extractCover)},
    {"open", "([BIIII[J[I)J", reinterpret_cast<void*>(open)},
    {"setGain", "(JF)V", reinterpret_cast<void*>(setGain)},
    {"read", "(JLjava/nio/ByteBuffer;I)I", reinterpret_cast<void*>(read)},
    {"seek", "(JJ)I", reinterpret_cast<void*>(seek)},
    {"scanFrames", "(J)J", reinterpret_cast<void*>(scanFrames)},
    {"close", "(J)V", reinterpret_cast<void*>(close)},
    {"setTrace", "(Z)V", reinterpret_cast<void*>(setTrace)},
    {"cascade", "([DI[D[D)V", reinterpret_cast<void*>(cascade)},
    {"stageIn", "([FI[DD[D[DZDD)D", reinterpret_cast<void*>(stageIn)},
    {"stageOut", "([DI[F[F)V", reinterpret_cast<void*>(stageOut)},
    {"delayLine", "([DI[DII)I", reinterpret_cast<void*>(delayLine)},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass(kClass);
    if (!cls) return JNI_ERR;
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
    av_log_set_level(AV_LOG_ERROR);
    av_log_set_callback(logCallback);
    return JNI_VERSION_1_6;
}
