#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <atomic>
#include <vector>
#include <cstdint>
#include <algorithm>
#include <cstddef>

template <typename T>
class alignas(64) RingBuffer {
public:
    explicit RingBuffer(size_t capacity) {
        capacity_ = nextPowerOfTwo(capacity);
        mask_ = capacity_ - 1;
        buffer_.resize(capacity_);
        writeIndex.store(0, std::memory_order_relaxed);
        readIndex.store(0, std::memory_order_relaxed);
        dropCount.store(0, std::memory_order_relaxed);
    }

    // Disable copy/move to enforce safe thread-ownership boundaries
    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    /**
     * Push a block of items.
     * Thread safety: Must ONLY be called by the single producer thread.
     * Returns the number of items successfully pushed.
     * If the buffer does not have enough capacity for the entire block,
     * the write is skipped entirely (zero copy), dropCount is incremented by count, and 0 is returned.
     */
    size_t push(const T* items, size_t count) {
        if (count == 0) return 0;

        size_t currentWrite = writeIndex.load(std::memory_order_relaxed);
        size_t currentRead = readIndex.load(std::memory_order_acquire);

        size_t available = capacity_ - (currentWrite - currentRead);
        if (available < count) {
            // Insufficient space: skip copy entirely to preserve CPU and buffer boundaries
            dropCount.fetch_add(count, std::memory_order_relaxed);
            return 0;
        }

        size_t writePos = currentWrite & mask_;
        size_t firstPart = std::min(count, capacity_ - writePos);
        
        std::copy(items, items + firstPart, buffer_.data() + writePos);
        if (count > firstPart) {
            std::copy(items + firstPart, items + count, buffer_.data());
        }

        // Publish the write index with release semantics, acting as a memory barrier
        writeIndex.store(currentWrite + count, std::memory_order_release);
        return count;
    }

    /**
     * Pop a block of items.
     * Thread safety: Must ONLY be called by the single consumer thread.
     * Returns the number of items successfully popped.
     * Only reads committed frames. If the available samples are less than the requested count,
     * it returns 0 immediately (no partial reads, ensuring frame integrity).
     */
    size_t pop(T* items, size_t count) {
        if (count == 0) return 0;

        size_t currentRead = readIndex.load(std::memory_order_relaxed);
        size_t currentWrite = writeIndex.load(std::memory_order_acquire);

        size_t available = currentWrite - currentRead;
        if (available < count) {
            // Not enough committed samples: return 0 immediately to avoid partial reads
            return 0;
        }

        size_t readPos = currentRead & mask_;
        size_t firstPart = std::min(count, capacity_ - readPos);

        std::copy(buffer_.data() + readPos, buffer_.data() + readPos + firstPart, items);
        if (count > firstPart) {
            std::copy(buffer_.data(), buffer_.data() + (count - firstPart), items + firstPart);
        }

        // Publish the read index with release semantics
        readIndex.store(currentRead + count, std::memory_order_release);
        return count;
    }

    size_t size() const {
        size_t currentWrite = writeIndex.load(std::memory_order_relaxed);
        size_t currentRead = readIndex.load(std::memory_order_relaxed);
        return currentWrite > currentRead ? (currentWrite - currentRead) : 0;
    }

    size_t capacity() const {
        return capacity_;
    }

    uint64_t getDropCount() const {
        return dropCount.load(std::memory_order_relaxed);
    }

    void reset() {
        writeIndex.store(0, std::memory_order_relaxed);
        readIndex.store(0, std::memory_order_relaxed);
        dropCount.store(0, std::memory_order_relaxed);
    }

private:
    static size_t nextPowerOfTwo(size_t v) {
        if (v == 0) return 1;
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v |= v >> 32;
        v++;
        return v;
    }

    size_t capacity_;
    size_t mask_;
    std::vector<T> buffer_;

    // Align indices to 64 bytes to guarantee they live on separate cache lines.
    // This prevents false sharing / cache ping-pong latency stalls.
    alignas(64) std::atomic<uint64_t> writeIndex{0};
    alignas(64) std::atomic<uint64_t> readIndex{0};
    alignas(64) std::atomic<uint64_t> dropCount{0};
};

#endif // RING_BUFFER_H
