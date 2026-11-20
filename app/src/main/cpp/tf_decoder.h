// Trueform's FFmpeg reader: any supported file in, interleaved float32 stereo out, sample-accurate seeking,
// gapless trimming by libavcodec (docs/plan/01-native-decoder.md). One Decoder is used by one thread only.
#pragma once

#include <cstdint>
#include <vector>

struct AVFormatContext;
struct AVCodecContext;
struct SwrContext;
struct AVPacket;
struct AVFrame;

namespace tf {

// Indexes of probe() results. Same numbers in audio/TrackProbe.kt.
enum Meta : int {
    META_TITLE, META_ARTIST, META_ALBUM, META_ALBUM_ARTIST, META_GENRE, META_DATE, META_TRACK, META_DISC,
    META_COMPOSER, META_RG_TRACK_GAIN, META_RG_TRACK_PEAK, META_RG_ALBUM_GAIN, META_RG_ALBUM_PEAK,
    META_R128_TRACK_GAIN, META_R128_ALBUM_GAIN, META_CODEC, META_CONTAINER, META_COUNT
};
enum Num : int {
    NUM_DURATION_US, NUM_SAMPLE_RATE, NUM_CHANNELS, NUM_BITS, NUM_BITRATE, NUM_HAS_COVER,
    NUM_DURATION_IS_ESTIMATE, NUM_COUNT
};
// open() info out-array.
enum Info : int {
    INFO_OUT_RATE, INFO_TOTAL_FRAMES, INFO_EXACT, INFO_SOURCE_RATE, INFO_SOURCE_BITS, INFO_SOURCE_CHANNELS, INFO_COUNT
};

// Opens path, or the descriptor fd when fd >= 0 (FFmpeg's fd: protocol; the descriptor is dup'ed).
int openInput(AVFormatContext** fmt, const char* path, int fd);

// Timestamp tracing to logcat (tag TFDEC) for the decoder tests.
extern bool gTrace;

class Decoder {
public:
    // outRate 0: the source rate, halved until it is at most maxRate. quality: 0 normal, 1 high, 2 very high.
    static Decoder* open(const char* path, int fd, int outRate, int maxRate, int quality, int64_t info[INFO_COUNT],
                         int* error);
    ~Decoder();

    void setGain(float linear) { gain_ = linear; }
    // Up to maxFrames stereo frames into dst. Returns frames, 0 at the end, < 0 an AVERROR.
    int read(float* dst, int maxFrames);
    // The next read() starts at this output frame (or at the earliest one the file allows).
    int seek(int64_t frame);
    // Sum of packet durations over the whole file, at the output rate. Leaves the decoder at frame 0.
    int64_t scanFrames();

private:
    Decoder() = default;
    int init(const char* path, int fd, int outRate, int maxRate, int quality, int64_t info[INFO_COUNT]);
    int initResampler();
    int decodeMore();                 // fills buf_ with the next converted chunk; 1, 0 at end, < 0 error
    int convert(const AVFrame* frame);
    int64_t outIndexOf(int64_t pts) const;

    AVFormatContext* fmt_ = nullptr;
    AVCodecContext* dec_ = nullptr;
    SwrContext* swr_ = nullptr;
    AVPacket* pkt_ = nullptr;
    AVFrame* frame_ = nullptr;
    int stream_ = -1;
    int outRate_ = 0;
    int quality_ = 1;
    int64_t origin_ = 0;              // the stream's start time: content frame 0
    float gain_ = 1.0f;
    int64_t total_ = 0;               // stated length at outRate_
    bool exact_ = false;              // total_ is from the container, not a bitrate guess: output stops there
    int64_t pos_ = 0;                 // output index of the next frame read() returns
    int traceLeft_ = 0;               // packets/frames still to trace (gTrace)

    std::vector<float> buf_;          // converted interleaved stereo not yet returned
    size_t bufPos_ = 0, bufLen_ = 0;  // in floats
    bool demuxEof_ = false, decoderEof_ = false, swrFlushed_ = false;
    int errors_ = 0;                  // consecutive decode errors
    bool needSync_ = false;           // after a seek: the next decoded frame tells how much to drop
    int64_t seekTarget_ = 0;
    int64_t drop_ = 0;                // output frames still to drop
};

}  // namespace tf
