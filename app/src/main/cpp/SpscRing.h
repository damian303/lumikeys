#pragma once

#include <array>
#include <atomic>
#include <cstddef>

/**
 * Lock-free queue for exactly one producer thread and one consumer thread.
 * Capacity must be a power of two; one slot is kept empty.
 */
template <typename T, size_t Capacity>
class SpscRing {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

public:
    /** Producer only. Returns false if full. */
    bool push(const T &value) {
        const size_t tail = mTail.load(std::memory_order_relaxed);
        const size_t next = (tail + 1) & (Capacity - 1);
        if (next == mHead.load(std::memory_order_acquire)) {
            return false;
        }
        mSlots[tail] = value;
        mTail.store(next, std::memory_order_release);
        return true;
    }

    /** Consumer only. Returns false if empty. */
    bool pop(T &value) {
        const size_t head = mHead.load(std::memory_order_relaxed);
        if (head == mTail.load(std::memory_order_acquire)) {
            return false;
        }
        value = mSlots[head];
        mHead.store((head + 1) & (Capacity - 1), std::memory_order_release);
        return true;
    }

private:
    std::array<T, Capacity> mSlots{};
    std::atomic<size_t> mHead{0};
    std::atomic<size_t> mTail{0};
};
