#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

#include <fluidsynth.h>
#include <oboe/Oboe.h>

#include "LoopSequencer.h"
#include "MidiQueue.h"

/**
 * Owns the Oboe output stream and the FluidSynth instance.
 *
 * All synth note/control calls happen on the audio thread: other threads only
 * push packed MIDI messages into the queue, which the callback drains before
 * rendering each buffer.
 */
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    struct Stats {
        int32_t sampleRate = 0;
        int32_t framesPerBurst = 0;
        int32_t bufferFrames = 0;
        int32_t xruns = 0;
        int32_t exclusive = 0;
        int32_t droppedMessages = 0;
        /** Output latency Android reports for the stream, or -1 if unknown. */
        int32_t latencyMillis = -1;
    };

    ~AudioEngine() override;

    /** Opens the stream, creates the synth, loads the SoundFont and starts audio. */
    bool start(const std::string &soundFontPath);
    void stop();

    /** Starts or stops the stream without unloading the synth (app foreground/background). */
    void setPlaying(bool playing);

    void sendMidi(uint32_t message);

    Stats stats();

    /** Peak absolute output sample since the previous call. */
    float takePeak();

    /** The loop sequencer that renders through this engine. */
    LoopSequencer &loop() { return mLoop; }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    bool openStream(int32_t sampleRate);
    void closeStream();

    std::mutex mLifecycleMutex;
    std::shared_ptr<oboe::AudioStream> mStream;
    fluid_settings_t *mSettings = nullptr;
    // Written only while no stream is running; read by the audio thread.
    std::atomic<fluid_synth_t *> mSynth{nullptr};
    bool mPlaying = false;

    MidiQueue mQueue;
    LoopSequencer mLoop;
    std::atomic<float> mPeak{0.0f};
    std::atomic<int32_t> mDropped{0};
};
