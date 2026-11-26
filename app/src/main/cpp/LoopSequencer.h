#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include <fluidsynth.h>

#include "SpscRing.h"

struct LoopEvent {
    int32_t frame;     // position within the loop, in audio frames
    uint32_t message;  // packed short MIDI message
};

/** One immutable version of the loop. Built off the audio thread, then swapped in. */
struct LoopData {
    int32_t lengthFrames = 0;
    double framesPerBeat = 0.0;
    int32_t beatsPerBar = 4;
    std::vector<LoopEvent> events;  // sorted by frame
};

/**
 * Plays a loop of MIDI events against the audio clock, with a metronome and a
 * count-in, and captures what is played live while recording.
 *
 * The position is counted in rendered audio frames, so timing does not depend on
 * any other clock. The audio thread never locks or allocates: new loop versions
 * and transport commands arrive through atomics and lock-free queues.
 */
class LoopSequencer {
public:
    struct State {
        bool running;
        bool recording;
        int64_t position;  // frames; negative during the count-in
        int32_t length;    // frames
    };

    ~LoopSequencer();

    // ---- Control threads (serialised internally) ----

    /** Replaces the loop contents. Playback continues from the same musical position. */
    void setLoop(std::unique_ptr<LoopData> data);

    /** Starts from the top after [countInBeats] of metronome. */
    void start(int32_t countInBeats, bool record);
    void stop();
    void setRecording(bool recording);
    void setMetronome(bool on);
    State state() const;

    /**
     * Copies captured live messages into [out] as (frame << 32) | message.
     * Returns how many were written.
     */
    int drainRecorded(int64_t *out, int capacity);

    // ---- Audio thread ----

    /** Call for each live message as it is applied; captures it when recording. */
    void onLiveMessage(uint32_t message);

    /** Renders interleaved stereo, firing loop events and metronome clicks at their frames. */
    void render(fluid_synth_t *synth, float *out, int32_t numFrames);

private:
    enum class CommandType : int32_t { Start, Stop, SetRecording, SetMetronome };
    struct Command {
        CommandType type;
        int32_t value;
        bool flag;
    };

    void pushCommand(Command command);
    void collectRetired();

    void adoptPending(fluid_synth_t *synth);
    void processCommands(fluid_synth_t *synth);
    void reindex();
    void fireDue(fluid_synth_t *synth);
    void click(fluid_synth_t *synth, int64_t beat);
    void allNotesOff(fluid_synth_t *synth);
    int64_t clickFrame(int64_t beat) const;
    void publish();

    // Shared between control and audio threads.
    std::mutex mControlMutex;
    std::atomic<LoopData *> mPending{nullptr};
    SpscRing<LoopData *, 32> mRetired;    // audio -> control, to be deleted off the audio thread
    SpscRing<Command, 64> mCommands;      // control -> audio
    SpscRing<int64_t, 1024> mRecorded;    // audio -> control
    std::atomic<bool> mStateRunning{false};
    std::atomic<bool> mStateRecording{false};
    std::atomic<int64_t> mStatePosition{0};
    std::atomic<int32_t> mStateLength{0};

    // Audio thread only.
    LoopData *mActive = nullptr;
    bool mRunning = false;
    bool mRecording = false;
    bool mMetronome = true;
    int64_t mPosition = 0;
    size_t mEventIndex = 0;
    int64_t mNextClickBeat = 0;
    int mClickNote = -1;
};
