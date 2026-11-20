#include "tf_decoder.h"

#include <cmath>
#include <cstring>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/intreadwrite.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#include <android/log.h>
#define TRACE(...) do { if (gTrace) __android_log_print(ANDROID_LOG_INFO, "TFDEC", __VA_ARGS__); } while (0)

namespace tf {

bool gTrace = false;

int openInput(AVFormatContext** fmt, const char* path, int fd) {
    AVDictionary* opts = nullptr;
    // MP3: seek by the generic index (built by reading frame headers), not the Xing table of contents, whose
    // 1%-steps give timestamps that are off by hundreds of samples.
    av_dict_set(&opts, "usetoc", "0", 0);
    const char* url = path;
    if (fd >= 0) {
        av_dict_set_int(&opts, "fd", fd, 0);   // FFmpeg's fd: protocol dup()s it; the caller keeps its descriptor
        url = "fd:";
    }
    int r = avformat_open_input(fmt, url, nullptr, &opts);
    av_dict_free(&opts);
    return r;
}

Decoder* Decoder::open(const char* path, int fd, int outRate, int maxRate, int quality, int64_t info[INFO_COUNT],
                       int* error) {
    Decoder* d = new Decoder();
    int r = d->init(path, fd, outRate, maxRate, quality, info);
    if (r < 0) {
        delete d;
        *error = r;
        return nullptr;
    }
    *error = 0;
    return d;
}

Decoder::~Decoder() {
    av_frame_free(&frame_);
    av_packet_free(&pkt_);
    swr_free(&swr_);
    avcodec_free_context(&dec_);
    avformat_close_input(&fmt_);
}

int Decoder::init(const char* path, int fd, int outRate, int maxRate, int quality, int64_t info[INFO_COUNT]) {
    int r = openInput(&fmt_, path, fd);
    if (r < 0) return r;
    if ((r = avformat_find_stream_info(fmt_, nullptr)) < 0) return r;
    stream_ = av_find_best_stream(fmt_, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (stream_ < 0) return stream_;
    AVStream* st = fmt_->streams[stream_];
    for (unsigned i = 0; i < fmt_->nb_streams; ++i) {
        if (static_cast<int>(i) != stream_) fmt_->streams[i]->discard = AVDISCARD_ALL;   // cover art, other tracks
    }
    const AVCodec* codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!codec) return AVERROR_DECODER_NOT_FOUND;
    dec_ = avcodec_alloc_context3(codec);
    if (!dec_) return AVERROR(ENOMEM);
    if ((r = avcodec_parameters_to_context(dec_, st->codecpar)) < 0) return r;
    dec_->pkt_timebase = st->time_base;
    dec_->thread_count = 1;
    if ((r = avcodec_open2(dec_, codec, nullptr)) < 0) return r;
    if (dec_->sample_rate <= 0 || dec_->ch_layout.nb_channels <= 0) return AVERROR_INVALIDDATA;

    int src = dec_->sample_rate;
    if (outRate <= 0) {
        outRate = src;
        if (maxRate > 0) {
            while (outRate > maxRate) outRate /= 2;   // keeps the rate family: 352.8k -> 176.4k, 384k -> 192k
        }
    }
    outRate_ = outRate;
    quality_ = quality;
    // Content frame 0. A positive start time is trimmed encoder delay (MP3: LAME delay); a negative one is pre-roll
    // the decoder discards (Opus pre-skip), so the content starts at 0 and the stated duration includes the pre-roll.
    int64_t start = st->start_time != AV_NOPTS_VALUE ? st->start_time : 0;
    origin_ = start > 0 ? start : 0;
    // Ogg Opus: granule positions count the pre-skip, which the decoder drops: the timeline and the stated length
    // both start pre-skip samples early.
    int64_t preSkip = 0;
    if (st->codecpar->codec_id == AV_CODEC_ID_OPUS && fmt_->iformat && std::strcmp(fmt_->iformat->name, "ogg") == 0 &&
        st->codecpar->initial_padding > 0 && start >= 0) {
        preSkip = av_rescale_q(st->codecpar->initial_padding, AVRational{1, dec_->sample_rate}, st->time_base);
    }
    traceLeft_ = 3;
    if ((r = initResampler()) < 0) return r;

    pkt_ = av_packet_alloc();
    frame_ = av_frame_alloc();
    if (!pkt_ || !frame_) return AVERROR(ENOMEM);

    int64_t frames = 0;
    if (st->duration != AV_NOPTS_VALUE && st->duration > 0) {
        int64_t d = st->duration + (start < 0 ? start : 0) - preSkip;
        frames = av_rescale_q(d, st->time_base, AVRational{1, outRate_});
    } else if (fmt_->duration != AV_NOPTS_VALUE && fmt_->duration > 0) {
        frames = av_rescale(fmt_->duration, outRate_, AV_TIME_BASE);
    }
    total_ = frames;
    exact_ = frames > 0 && fmt_->duration_estimation_method != AVFMT_DURATION_FROM_BITRATE;
    int bits = st->codecpar->bits_per_raw_sample;
    if (bits <= 0 && st->codecpar->codec_id >= AV_CODEC_ID_FIRST_AUDIO && st->codecpar->codec_id < AV_CODEC_ID_ADPCM_IMA_QT) {
        bits = av_get_bits_per_sample(st->codecpar->codec_id);   // PCM in WAV/AIFF
    }
    info[INFO_OUT_RATE] = outRate_;
    info[INFO_TOTAL_FRAMES] = frames;
    info[INFO_EXACT] = exact_ ? 1 : 0;
    info[INFO_SOURCE_RATE] = src;
    info[INFO_SOURCE_BITS] = bits;
    info[INFO_SOURCE_CHANNELS] = dec_->ch_layout.nb_channels;
    return 0;
}

int Decoder::initResampler() {
    AVChannelLayout in{};
    if (dec_->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) {
        av_channel_layout_default(&in, dec_->ch_layout.nb_channels);
    } else {
        av_channel_layout_copy(&in, &dec_->ch_layout);
    }
    AVChannelLayout out = AV_CHANNEL_LAYOUT_STEREO;
    swr_free(&swr_);
    int r = swr_alloc_set_opts2(&swr_, &out, AV_SAMPLE_FMT_FLT, outRate_, &in, dec_->sample_fmt, dec_->sample_rate,
                                0, nullptr);
    int inChannels = in.nb_channels;
    av_channel_layout_uninit(&in);
    if (r < 0) return r;
    av_opt_set_double(swr_, "rematrix_maxval", 1.0, 0);   // a downmix never exceeds full scale
    if (quality_ >= 2) {
        av_opt_set_int(swr_, "filter_size", 128, 0);
        av_opt_set_double(swr_, "cutoff", 0.985, 0);
        av_opt_set_int(swr_, "filter_type", SWR_FILTER_TYPE_KAISER, 0);
        av_opt_set_double(swr_, "kaiser_beta", 14, 0);
        av_opt_set_int(swr_, "exact_rational", 1, 0);
    } else if (quality_ == 1) {
        av_opt_set_int(swr_, "filter_size", 64, 0);
        av_opt_set_double(swr_, "cutoff", 0.98, 0);
        av_opt_set_int(swr_, "filter_type", SWR_FILTER_TYPE_KAISER, 0);
        av_opt_set_double(swr_, "kaiser_beta", 12, 0);
    }
    if (inChannels == 1) {
        const double mono[2] = {1.0, 1.0};   // mono plays at full level on both sides, not -3 dB
        if ((r = swr_set_matrix(swr_, mono, 1)) < 0) return r;
    }
    return swr_init(swr_);
}

int64_t Decoder::outIndexOf(int64_t pts) const {
    const AVRational tb = fmt_->streams[stream_]->time_base;
    return av_rescale_q_rnd(pts - origin_, tb, AVRational{1, outRate_},
                            static_cast<AVRounding>(AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
}

int Decoder::convert(const AVFrame* frame) {
    int room = swr_get_out_samples(swr_, frame ? frame->nb_samples : 0);
    if (room <= 0) room = 4096;
    if (buf_.size() < static_cast<size_t>(room) * 2) buf_.resize(static_cast<size_t>(room) * 2);
    uint8_t* out = reinterpret_cast<uint8_t*>(buf_.data());
    int n = swr_convert(swr_, &out, room, frame ? const_cast<const uint8_t**>(frame->extended_data) : nullptr,
                        frame ? frame->nb_samples : 0);
    if (n < 0) return n;
    bufPos_ = 0;
    bufLen_ = static_cast<size_t>(n) * 2;
    return n;
}

int Decoder::decodeMore() {
    for (;;) {
        int r = avcodec_receive_frame(dec_, frame_);
        if (r == 0) {
            errors_ = 0;
            if (traceLeft_ > 0) {
                --traceLeft_;
                TRACE("frame pts %lld samples %d", (long long)frame_->pts, frame_->nb_samples);
            }
            if (needSync_) {
                needSync_ = false;
                // pts, not best_effort_timestamp: libavcodec moves pts past samples it trims (encoder delay).
                int64_t pts = frame_->pts != AV_NOPTS_VALUE ? frame_->pts : frame_->best_effort_timestamp;
                TRACE("sync: frame pts %lld best %lld samples %d origin %lld target %lld", (long long)frame_->pts,
                      (long long)frame_->best_effort_timestamp, frame_->nb_samples, (long long)origin_,
                      (long long)seekTarget_);
                if (pts != AV_NOPTS_VALUE) {
                    int64_t at = outIndexOf(pts);
                    drop_ = at < seekTarget_ ? seekTarget_ - at : 0;
                    pos_ = at > seekTarget_ ? at : seekTarget_;   // a file that cannot seek back far enough starts late
                }
            }
            int n = convert(frame_);
            av_frame_unref(frame_);
            if (n < 0) return n;
            if (n > 0) return 1;
            continue;
        }
        if (r == AVERROR_EOF) {
            decoderEof_ = true;
            if (!swrFlushed_) {
                swrFlushed_ = true;   // the resampler's last few samples
                int n = convert(nullptr);
                if (n < 0) return n;
                if (n > 0) return 1;
            }
            return 0;
        }
        if (r != AVERROR(EAGAIN)) {
            if (++errors_ > 50) return r;
            continue;
        }
        if (demuxEof_) return 0;
        r = av_read_frame(fmt_, pkt_);
        if (r < 0) {
            if (r == AVERROR(EAGAIN)) continue;
            // End of file, or a read error at the end of a damaged file: drain the decoder either way.
            demuxEof_ = true;
            avcodec_send_packet(dec_, nullptr);
            continue;
        }
        if (pkt_->stream_index != stream_) {
            av_packet_unref(pkt_);
            continue;
        }
        if (needSync_ || traceLeft_ > 0) {
            size_t skipSize = 0;
            const uint8_t* skip = av_packet_get_side_data(pkt_, AV_PKT_DATA_SKIP_SAMPLES, &skipSize);
            TRACE("sync: packet pts %lld dts %lld dur %lld pos %lld skip %d", (long long)pkt_->pts,
                  (long long)pkt_->dts, (long long)pkt_->duration, (long long)pkt_->pos,
                  skip && skipSize >= 4 ? (int)AV_RL32(skip) : -1);
        }
        r = avcodec_send_packet(dec_, pkt_);
        av_packet_unref(pkt_);
        if (r < 0 && r != AVERROR(EAGAIN)) {
            if (r == AVERROR(ENOMEM) || ++errors_ > 50) return r;   // a damaged packet: skip it
        }
    }
}

int Decoder::read(float* dst, int maxFrames) {
    // A stated length ends the output: AAC in MP4 decodes its end padding, which the edit list excludes.
    if (exact_) {
        int64_t left = total_ - pos_;
        if (left <= 0) return 0;
        if (left < maxFrames) maxFrames = static_cast<int>(left);
    }
    int produced = 0;
    while (produced < maxFrames) {
        if (bufPos_ < bufLen_) {
            size_t availFrames = (bufLen_ - bufPos_) / 2;
            if (drop_ > 0) {
                size_t d = drop_ < static_cast<int64_t>(availFrames) ? static_cast<size_t>(drop_) : availFrames;
                bufPos_ += d * 2;
                drop_ -= static_cast<int64_t>(d);
                continue;
            }
            size_t want = static_cast<size_t>(maxFrames - produced);
            size_t n = availFrames < want ? availFrames : want;
            std::memcpy(dst + static_cast<size_t>(produced) * 2, buf_.data() + bufPos_, n * 2 * sizeof(float));
            bufPos_ += n * 2;
            produced += static_cast<int>(n);
            continue;
        }
        if (decoderEof_ && swrFlushed_) break;
        int r = decodeMore();
        if (r < 0) {
            if (produced > 0) break;   // hand over what we have; the error comes back on the next call
            return r;
        }
        if (r == 0) break;
    }
    if (gain_ != 1.0f) {
        float* p = dst;
        for (int i = 0, n = produced * 2; i < n; ++i) p[i] *= gain_;
    }
    pos_ += produced;
    return produced;
}

int Decoder::seek(int64_t frame) {
    if (frame < 0) frame = 0;
    AVStream* st = fmt_->streams[stream_];
    // Run-in for lossy codecs: MP3's bit reservoir reaches back up to 511 bytes, which is five small VBR frames, and
    // the frames decoded without it come out wrong (measured: 0.1 s was not enough); Opus/Vorbis/AAC pre-roll.
    // Ogg: FFmpeg derives the timestamps of the page it lands on from that page's granule, except on the last (EOS)
    // page, where they are guesses (measured: hundreds of samples off). 2 s back never lands on the last page of a
    // longer file; shorter files land on the first page, whose timestamps are exact.
    const AVCodecDescriptor* cd = avcodec_descriptor_get(st->codecpar->codec_id);
    bool lossless = cd && (cd->props & AV_CODEC_PROP_LOSSLESS) && !(cd->props & AV_CODEC_PROP_LOSSY);
    bool ogg = fmt_->iformat && std::strcmp(fmt_->iformat->name, "ogg") == 0;
    int64_t runIn = ogg ? 2LL * outRate_ : lossless ? 0 : av_rescale(outRate_, 2, 5);
    int64_t start = frame > runIn ? frame - runIn : 0;
    int64_t ts = origin_ + av_rescale_q(start, AVRational{1, outRate_}, st->time_base);
    TRACE("seek: frame %lld ts %lld (tb %d/%d) start_time %lld", (long long)frame, (long long)ts, st->time_base.num,
          st->time_base.den, (long long)st->start_time);
    int r = av_seek_frame(fmt_, stream_, ts, AVSEEK_FLAG_BACKWARD);
    if (r < 0) r = av_seek_frame(fmt_, stream_, origin_, AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_ANY);
    if (r < 0) r = avformat_seek_file(fmt_, -1, INT64_MIN, 0, INT64_MAX, 0);
    if (r < 0) return r;
    avcodec_flush_buffers(dec_);
    if ((r = initResampler()) < 0) return r;
    bufPos_ = bufLen_ = 0;
    demuxEof_ = decoderEof_ = swrFlushed_ = false;
    errors_ = 0;
    needSync_ = true;
    seekTarget_ = frame;
    drop_ = 0;
    pos_ = frame;
    traceLeft_ = 3;
    return 0;
}

int64_t Decoder::scanFrames() {
    AVStream* st = fmt_->streams[stream_];
    int64_t sum = 0;
    while (av_read_frame(fmt_, pkt_) >= 0) {
        if (pkt_->stream_index == stream_ && pkt_->duration > 0) sum += pkt_->duration;
        av_packet_unref(pkt_);
    }
    int64_t frames = av_rescale_q(sum, st->time_base, AVRational{1, outRate_});
    seek(0);
    if (!exact_) total_ = frames;
    return frames;
}

}  // namespace tf
