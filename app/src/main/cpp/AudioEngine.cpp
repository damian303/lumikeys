#include "AudioEngine.h"

#include <cmath>
#include <cstring>

#include <android/log.h>
#include <fluidsynth.h>

#include "MidiApply.h"

#define LOG_TAG "LumiAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
constexpr int kChannelCount = 2;
constexpr int kPolyphony = 64;
constexpr double kGain = 0.6;
}  // namespace

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::openStream(int32_t sampleRate) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setChannelCount(kChannelCount)
            ->setChannelConversionAllowed(true)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    if (sampleRate > 0) {
        // Reopening after a device change: keep the rate the synth was created with.
        builder.setSampleRate(sampleRate)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
    }

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }
    if (mStream->getChannelCount() != kChannelCount ||
        mStream->getFormat() != oboe::AudioFormat::Float) {
        LOGE("unexpected stream format: channels=%d format=%s", mStream->getChannelCount(),
             oboe::convertToText(mStream->getFormat()));
        closeStream();
        return false;
    }
    mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);
    LOGI("stream open: rate=%d burst=%d buffer=%d sharing=%s api=%s", mStream->getSampleRate(),
         mStream->getFramesPerBurst(), mStream->getBufferSizeInFrames(),
         oboe::convertToText(mStream->getSharingMode()),
         oboe::convertToText(mStream->getAudioApi()));
    return true;
}

void AudioEngine::closeStream() {
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
}

bool AudioEngine::start(const std::string &soundFontPath) {
    std::lock_guard<std::mutex> lock(mLifecycleMutex);
    if (mSynth.load() != nullptr) {
        return true;
    }
    if (!openStream(0)) {
        return false;
    }

    mSettings = new_fluid_settings();
    fluid_settings_setnum(mSettings, "synth.sample-rate", mStream->getSampleRate());
    fluid_settings_setint(mSettings, "synth.polyphony", kPolyphony);
    fluid_settings_setint(mSettings, "synth.midi-channels", kChannelsPerBank * kChannelBanks);
    fluid_settings_setnum(mSettings, "synth.gain", kGain);

    fluid_synth_t *synth = new_fluid_synth(mSettings);
    if (synth == nullptr) {
        LOGE("new_fluid_synth failed");
        delete_fluid_settings(mSettings);
        mSettings = nullptr;
        closeStream();
        return false;
    }
    int soundFontId = fluid_synth_sfload(synth, soundFontPath.c_str(), 1);
    if (soundFontId == FLUID_FAILED) {
        LOGE("failed to load SoundFont %s", soundFontPath.c_str());
        delete_fluid_synth(synth);
        delete_fluid_settings(mSettings);
        mSettings = nullptr;
        closeStream();
        return false;
    }
    LOGI("SoundFont loaded (id %d) from %s", soundFontId, soundFontPath.c_str());

    // The accompaniment bank needs its own drum channel, like channel 10 of the first bank.
    const int backingDrums = kChannelsPerBank + kDrumChannelInBank;
    if (fluid_synth_set_channel_type(synth, backingDrums, CHANNEL_TYPE_DRUM) != FLUID_OK ||
        fluid_synth_program_change(synth, backingDrums, 0) != FLUID_OK) {
        LOGE("could not set up the accompaniment drum channel");
    }
    LOGI("synth has %d channels", fluid_synth_count_midi_channels(synth));

    mSynth.store(synth);
    oboe::Result result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        closeStream();
        mSynth.store(nullptr);
        delete_fluid_synth(synth);
        delete_fluid_settings(mSettings);
        mSettings = nullptr;
        return false;
    }
    mPlaying = true;
    return true;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mLifecycleMutex);
    mPlaying = false;
    closeStream();
    fluid_synth_t *synth = mSynth.exchange(nullptr);
    if (synth != nullptr) {
        delete_fluid_synth(synth);
    }
    if (mSettings != nullptr) {
        delete_fluid_settings(mSettings);
        mSettings = nullptr;
    }
}

void AudioEngine::setPlaying(bool playing) {
    std::lock_guard<std::mutex> lock(mLifecycleMutex);
    if (mSynth.load() == nullptr || playing == mPlaying) {
        return;
    }
    mPlaying = playing;
    if (!mStream) {
        return;
    }
    oboe::Result result = playing ? mStream->requestStart() : mStream->requestStop();
    if (result != oboe::Result::OK) {
        LOGE("%s failed: %s", playing ? "requestStart" : "requestStop",
             oboe::convertToText(result));
    }
}

void AudioEngine::sendMidi(uint32_t message) {
    if (!mQueue.push(message)) {
        mDropped.fetch_add(1, std::memory_order_relaxed);
    }
}

AudioEngine::Stats AudioEngine::stats() {
    std::lock_guard<std::mutex> lock(mLifecycleMutex);
    Stats stats;
    stats.droppedMessages = mDropped.load(std::memory_order_relaxed);
    if (mStream) {
        stats.sampleRate = mStream->getSampleRate();
        stats.framesPerBurst = mStream->getFramesPerBurst();
        stats.bufferFrames = mStream->getBufferSizeInFrames();
        auto xruns = mStream->getXRunCount();
        stats.xruns = xruns ? xruns.value() : -1;
        stats.exclusive = mStream->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0;
        auto latency = mStream->calculateLatencyMillis();
        stats.latencyMillis = latency ? static_cast<int32_t>(std::lround(latency.value())) : -1;
    }
    return stats;
}

float AudioEngine::takePeak() {
    return mPeak.exchange(0.0f, std::memory_order_relaxed);
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream * /*stream*/,
                                                   void *audioData, int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    fluid_synth_t *synth = mSynth.load(std::memory_order_acquire);
    if (synth == nullptr) {
        std::memset(out, 0, sizeof(float) * numFrames * kChannelCount);
        return oboe::DataCallbackResult::Continue;
    }

    // Live playing first, then the loop: the sequencer renders the audio, firing its
    // own events at exact frames along the way.
    uint32_t message;
    while (mQueue.pop(message)) {
        applyMidiMessage(synth, message);
        mLoop.onLiveMessage(message);
    }
    mLoop.render(synth, out, numFrames);

    float peak = 0.0f;
    const int sampleCount = numFrames * kChannelCount;
    for (int i = 0; i < sampleCount; ++i) {
        const float magnitude = std::fabs(out[i]);
        if (magnitude > peak) {
            peak = magnitude;
        }
    }
    if (peak > mPeak.load(std::memory_order_relaxed)) {
        mPeak.store(peak, std::memory_order_relaxed);
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    // Typically a route change (headphones, USB audio). Reopen at the synth's rate.
    LOGI("stream closed with error %s; reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> lock(mLifecycleMutex);
    if (mSynth.load() == nullptr) {
        return;
    }
    int32_t sampleRate = mStream ? mStream->getSampleRate() : 0;
    mStream.reset();
    if (sampleRate <= 0 || !openStream(sampleRate)) {
        LOGE("could not reopen the audio stream");
        return;
    }
    if (mPlaying) {
        mStream->requestStart();
    }
}
