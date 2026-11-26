#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

/**
 * Fixed-size queue of packed short MIDI messages (status | data1 << 8 | data2 << 16).
 *
 * The consumer is the audio thread and never blocks. Producers (UI thread, MIDI
 * receiver thread) are serialised by a mutex that the audio thread never takes.
 */
class MidiQueue {
public:
    /** Returns false if the queue is full; the message is dropped. */
    bool push(uint32_t message) {
        std::lock_guard<std::mutex> lock(mProducerMutex);
        const size_t tail = mTail.load(std::memory_order_relaxed);
        const size_t next = (tail + 1) & kMask;
        if (next == mHead.load(std::memory_order_acquire)) {
            return false;
        }
        mSlots[tail] = message;
        mTail.store(next, std::memory_order_release);
        return true;
    }

    /** Audio thread only. */
    bool pop(uint32_t &message) {
        const size_t head = mHead.load(std::memory_order_relaxed);
        if (head == mTail.load(std::memory_order_acquire)) {
            return false;
        }
        message = mSlots[head];
        mHead.store((head + 1) & kMask, std::memory_order_release);
        return true;
    }

private:
    static constexpr size_t kCapacity = 1024;  // power of two
    static constexpr size_t kMask = kCapacity - 1;

    std::array<uint32_t, kCapacity> mSlots{};
    std::atomic<size_t> mHead{0};
    std::atomic<size_t> mTail{0};
    std::mutex mProducerMutex;
};
