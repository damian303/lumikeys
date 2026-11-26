#include "LoopSequencer.h"

#include <algorithm>
#include <cmath>

#include "MidiApply.h"

namespace {
constexpr int kStereo = 2;
constexpr int kDrumChannel = 9;
constexpr int kClickAccentNote = 76;  // General MIDI: Hi Wood Block
constexpr int kClickNote = 77;        // General MIDI: Low Wood Block
constexpr int kClickAccentVelocity = 120;
constexpr int kClickVelocity = 90;
}  // namespace

LoopSequencer::~LoopSequencer() {
    delete mPending.exchange(nullptr);
    collectRetired();
    delete mActive;
}

// ---- Control threads --------------------------------------------------------------------------

void LoopSequencer::setLoop(std::unique_ptr<LoopData> data) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    // If the audio thread has not picked up the previous version yet, it comes back here.
    delete mPending.exchange(data.release(), std::memory_order_acq_rel);
    collectRetired();
}

void LoopSequencer::start(int32_t countInBeats, bool record) {
    pushCommand({CommandType::Start, countInBeats, record});
}

void LoopSequencer::stop() {
    pushCommand({CommandType::Stop, 0, false});
}

void LoopSequencer::setRecording(bool recording) {
    pushCommand({CommandType::SetRecording, 0, recording});
}

void LoopSequencer::setMetronome(bool on) {
    pushCommand({CommandType::SetMetronome, 0, on});
}

void LoopSequencer::pushCommand(Command command) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    mCommands.push(command);  // dropped if 63 are already waiting, which would need a stalled audio thread
}

void LoopSequencer::collectRetired() {
    LoopData *old = nullptr;
    while (mRetired.pop(old)) {
        delete old;
    }
}

LoopSequencer::State LoopSequencer::state() const {
    return {mStateRunning.load(std::memory_order_relaxed), mStateRecording.load(std::memory_order_relaxed),
            mStatePosition.load(std::memory_order_relaxed), mStateLength.load(std::memory_order_relaxed)};
}

int LoopSequencer::drainRecorded(int64_t *out, int capacity) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    collectRetired();
    int count = 0;
    while (count < capacity && mRecorded.pop(out[count])) {
        ++count;
    }
    return count;
}

// ---- Audio thread -----------------------------------------------------------------------------

void LoopSequencer::onLiveMessage(uint32_t message) {
    if (!mRunning || !mRecording || mActive == nullptr) {
        return;
    }
    const uint32_t kind = message & 0xF0;
    if (kind != 0x80 && kind != 0x90) {
        return;
    }
    if (midiMessageBank(message) != 0) {
        return;  // accompaniment, not something the player did
    }
    int64_t frame = mPosition;
    if (frame < 0) {
        // A note played slightly ahead of the first beat belongs on the first beat.
        const auto earlyWindow = static_cast<int64_t>(mActive->framesPerBeat / 4);
        if (frame < -earlyWindow) {
            return;
        }
        frame = 0;
    }
    mRecorded.push((frame << 32) | message);
}

int64_t LoopSequencer::clickFrame(int64_t beat) const {
    return std::llround(static_cast<double>(beat) * mActive->framesPerBeat);
}

void LoopSequencer::reindex() {
    const auto &events = mActive->events;
    const int32_t from = static_cast<int32_t>(std::max<int64_t>(mPosition, 0));
    mEventIndex = static_cast<size_t>(
            std::lower_bound(events.begin(), events.end(), from,
                             [](const LoopEvent &event, int32_t frame) { return event.frame < frame; }) -
            events.begin());
    mNextClickBeat = static_cast<int64_t>(std::floor(static_cast<double>(mPosition) / mActive->framesPerBeat));
    while (clickFrame(mNextClickBeat) < mPosition) {
        ++mNextClickBeat;
    }
}

void LoopSequencer::adoptPending(fluid_synth_t *synth) {
    LoopData *incoming = mPending.exchange(nullptr, std::memory_order_acq_rel);
    if (incoming == nullptr) {
        return;
    }
    LoopData *previous = mActive;
    if (previous != nullptr && previous->framesPerBeat > 0 && incoming->framesPerBeat > 0 &&
        previous->framesPerBeat != incoming->framesPerBeat) {
        // Tempo change: keep the same musical position.
        mPosition = std::llround(static_cast<double>(mPosition) *
                                 (incoming->framesPerBeat / previous->framesPerBeat));
    }
    mActive = incoming;
    if (previous != nullptr) {
        mRetired.push(previous);  // if the queue is full the old version leaks; never free here
    }
    if (mActive->lengthFrames > 0 && mPosition >= mActive->lengthFrames) {
        mPosition %= mActive->lengthFrames;
    }
    if (mActive->framesPerBeat > 0) {
        reindex();
    }
    // Instrument choices sit at frame 0; apply them now so a layer added mid-loop sounds right.
    for (const LoopEvent &event : mActive->events) {
        if (event.frame > 0) {
            break;
        }
        if ((event.message & 0xF0) == 0xC0) {
            applyMidiMessage(synth, event.message);
        }
    }
}

void LoopSequencer::allNotesOff(fluid_synth_t *synth) {
    for (int channel = 0; channel < 16; ++channel) {
        fluid_synth_cc(synth, channel, 64, 0);
        fluid_synth_all_notes_off(synth, channel);
    }
    mClickNote = -1;
}

void LoopSequencer::processCommands(fluid_synth_t *synth) {
    Command command{};
    while (mCommands.pop(command)) {
        switch (command.type) {
            case CommandType::Start:
                if (mActive == nullptr || mActive->lengthFrames <= 0 || mActive->framesPerBeat <= 0) {
                    break;
                }
                mRunning = true;
                mRecording = command.flag;
                mPosition = -std::llround(static_cast<double>(command.value) * mActive->framesPerBeat);
                reindex();
                break;
            case CommandType::Stop:
                if (mRunning) {
                    allNotesOff(synth);
                }
                mRunning = false;
                mRecording = false;
                mPosition = 0;
                break;
            case CommandType::SetRecording:
                mRecording = mRunning && command.flag;
                break;
            case CommandType::SetMetronome:
                mMetronome = command.flag;
                break;
        }
    }
}

void LoopSequencer::click(fluid_synth_t *synth, int64_t beat) {
    if (mClickNote >= 0) {
        fluid_synth_noteoff(synth, kDrumChannel, mClickNote);
    }
    const int64_t beatsPerBar = std::max(1, mActive->beatsPerBar);
    const bool accent = ((beat % beatsPerBar) + beatsPerBar) % beatsPerBar == 0;
    mClickNote = accent ? kClickAccentNote : kClickNote;
    fluid_synth_noteon(synth, kDrumChannel, mClickNote, accent ? kClickAccentVelocity : kClickVelocity);
}

void LoopSequencer::fireDue(fluid_synth_t *synth) {
    while (clickFrame(mNextClickBeat) <= mPosition) {
        if (mPosition < 0 || mMetronome) {  // the count-in always clicks
            click(synth, mNextClickBeat);
        }
        ++mNextClickBeat;
    }
    if (mPosition < 0) {
        return;
    }
    const auto &events = mActive->events;
    while (mEventIndex < events.size() && events[mEventIndex].frame <= mPosition) {
        applyMidiMessage(synth, events[mEventIndex].message);
        ++mEventIndex;
    }
}

void LoopSequencer::publish() {
    mStateRunning.store(mRunning, std::memory_order_relaxed);
    mStateRecording.store(mRecording, std::memory_order_relaxed);
    mStatePosition.store(mPosition, std::memory_order_relaxed);
    mStateLength.store(mActive != nullptr ? mActive->lengthFrames : 0, std::memory_order_relaxed);
}

void LoopSequencer::render(fluid_synth_t *synth, float *out, int32_t numFrames) {
    adoptPending(synth);
    processCommands(synth);

    if (!mRunning || mActive == nullptr || mActive->lengthFrames <= 0) {
        fluid_synth_write_float(synth, numFrames, out, 0, kStereo, out, 1, kStereo);
        publish();
        return;
    }

    const int64_t length = mActive->lengthFrames;
    const auto &events = mActive->events;
    int32_t remaining = numFrames;
    while (remaining > 0) {
        fireDue(synth);

        // Render up to the next thing that has to happen at an exact frame.
        int64_t next = std::min(length, clickFrame(mNextClickBeat));
        if (mPosition < 0) {
            next = std::min<int64_t>(next, 0);
        } else if (mEventIndex < events.size()) {
            next = std::min<int64_t>(next, events[mEventIndex].frame);
        }
        const int64_t gap = std::max<int64_t>(next - mPosition, 1);
        const auto segment = static_cast<int32_t>(std::min<int64_t>(remaining, gap));

        fluid_synth_write_float(synth, segment, out, 0, kStereo, out, 1, kStereo);
        out += segment * kStereo;
        mPosition += segment;
        remaining -= segment;

        if (mPosition >= length) {
            mPosition = 0;
            mEventIndex = 0;
            mNextClickBeat = 0;
        }
    }
    publish();
}
