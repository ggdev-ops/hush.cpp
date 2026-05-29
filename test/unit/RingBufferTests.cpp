#include "gtest/gtest.h"
#include "core/RingBuffer.h"
#include <thread>
#include <vector>
#include <numeric>
#include <chrono>
#include <atomic>

TEST(RingBufferTest, PowerOfTwoInitialization) {
    RingBuffer<int16_t> buffer1(10);
    EXPECT_EQ(buffer1.capacity(), 16); // 10 rounds up to 16

    RingBuffer<int16_t> buffer2(32);
    EXPECT_EQ(buffer2.capacity(), 32); // 32 is already a power of two
}

TEST(RingBufferTest, BasicPushPop) {
    RingBuffer<int16_t> buffer(8);
    std::vector<int16_t> input = {1, 2, 3, 4};
    std::vector<int16_t> output(4, 0);

    size_t pushed = buffer.push(input.data(), input.size());
    EXPECT_EQ(pushed, 4);
    EXPECT_EQ(buffer.size(), 4);

    size_t popped = buffer.pop(output.data(), output.size());
    EXPECT_EQ(popped, 4);
    EXPECT_EQ(buffer.size(), 0);

    EXPECT_EQ(output[0], 1);
    EXPECT_EQ(output[1], 2);
    EXPECT_EQ(output[2], 3);
    EXPECT_EQ(output[3], 4);
}

TEST(RingBufferTest, FrameIntegrityNoPartialReads) {
    RingBuffer<int16_t> buffer(8);
    std::vector<int16_t> input = {1, 2, 3};
    std::vector<int16_t> output(4, 0);

    // Push 3 items
    buffer.push(input.data(), input.size());

    // Try to pop 4 items. Since 4 > 3 (available), it must return 0 (no partial reads)
    size_t popped = buffer.pop(output.data(), 4);
    EXPECT_EQ(popped, 0);
    EXPECT_EQ(buffer.size(), 3); // Size remains 3
}

TEST(RingBufferTest, ZeroCopyOverflowDropPolicy) {
    RingBuffer<int16_t> buffer(8); // capacity 8
    std::vector<int16_t> input1 = {1, 2, 3, 4, 5, 6};
    std::vector<int16_t> input2 = {7, 8, 9, 10};

    // Push 6 items (space left: 2)
    size_t pushed1 = buffer.push(input1.data(), input1.size());
    EXPECT_EQ(pushed1, 6);

    // Try to push 4 items. Space is 2, which is < 4.
    // The push must be skipped entirely (zero copy), return 0, and drop count must increase by 4.
    size_t pushed2 = buffer.push(input2.data(), input2.size());
    EXPECT_EQ(pushed2, 0);
    EXPECT_EQ(buffer.size(), 6);
    EXPECT_EQ(buffer.getDropCount(), 4);

    // Verify that data in the buffer was not corrupted or partially overwritten
    std::vector<int16_t> output(6, 0);
    size_t popped = buffer.pop(output.data(), 6);
    EXPECT_EQ(popped, 6);
    EXPECT_EQ(output[0], 1);
    EXPECT_EQ(output[5], 6);
}

TEST(RingBufferTest, WrapAroundCorrectness) {
    RingBuffer<int16_t> buffer(8); // capacity 8
    std::vector<int16_t> input = {1, 2, 3, 4, 5, 6};
    std::vector<int16_t> output1(4, 0);
    std::vector<int16_t> output2(4, 0);

    // 1. Push 6
    buffer.push(input.data(), 6);

    // 2. Pop 4 (readIndex = 4, writeIndex = 6)
    buffer.pop(output1.data(), 4);
    EXPECT_EQ(output1[0], 1);
    EXPECT_EQ(output1[3], 4);

    // 3. Push 4 (write index will wrap: 6 + 4 = 10, writePos wraps from 6 to 2)
    std::vector<int16_t> input2 = {7, 8, 9, 10};
    size_t pushed = buffer.push(input2.data(), 4);
    EXPECT_EQ(pushed, 4);

    // 4. Pop 4 (should wrap read index: 4 + 4 = 8, readPos wraps from 4 to 0)
    size_t popped = buffer.pop(output2.data(), 4);
    EXPECT_EQ(popped, 4);
    EXPECT_EQ(output2[0], 5);
    EXPECT_EQ(output2[1], 6);
    EXPECT_EQ(output2[2], 7);
    EXPECT_EQ(output2[3], 8);
}

TEST(RingBufferTest, ConcurrentSPSCStressTest) {
    // Large capacity to prevent collision during normal flow
    RingBuffer<int16_t> buffer(1024);
    const int numBlocks = 1000;
    const int blockSize = 16;
    std::atomic<bool> producerFinished{false};
    std::atomic<uint64_t> totalPopped{0};

    // Thread A: Producer
    std::thread producer([&]() {
        std::vector<int16_t> block(blockSize);
        for (int b = 0; b < numBlocks; ++b) {
            // Fill block with sequential identifier data
            for (int i = 0; i < blockSize; ++i) {
                block[i] = static_cast<int16_t>(b * blockSize + i);
            }

            // Retry push if buffer is full (simulating flow pressure)
            while (buffer.push(block.data(), blockSize) == 0) {
                std::this_thread::yield();
            }
        }
        producerFinished.store(true, std::memory_order_release);
    });

    // Thread B: Consumer
    std::thread consumer([&]() {
        std::vector<int16_t> block(blockSize);
        int expectedBlockVal = 0;

        while (true) {
            size_t popped = buffer.pop(block.data(), blockSize);
            if (popped > 0) {
                EXPECT_EQ(popped, blockSize);
                for (int i = 0; i < blockSize; ++i) {
                    EXPECT_EQ(block[i], expectedBlockVal * blockSize + i);
                }
                expectedBlockVal++;
                totalPopped.fetch_add(blockSize, std::memory_order_relaxed);
            } else {
                if (producerFinished.load(std::memory_order_acquire) && buffer.size() < blockSize) {
                    break;
                }
                std::this_thread::yield();
            }
        }
    });

    producer.join();
    consumer.join();

    EXPECT_EQ(totalPopped.load(), numBlocks * blockSize);
}
