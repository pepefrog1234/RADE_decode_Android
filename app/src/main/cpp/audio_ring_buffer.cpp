/*
 * audio_ring_buffer.cpp - Lock-free SPSC ring buffer implementation.
 */

#include "audio_ring_buffer.h"

AudioRingBuffer::AudioRingBuffer(int32_t capacity)
    : capacity_(capacity), buffer_(capacity, 0) {}

int32_t AudioRingBuffer::write(const int16_t *data, int32_t count) {
    int32_t available = availableToWrite();
    if (count > available) count = available;
    if (count <= 0) return 0;

    int32_t wi = writeIndex_.load(std::memory_order_relaxed);

    int32_t firstChunk = capacity_ - wi;
    if (firstChunk > count) firstChunk = count;

    std::memcpy(&buffer_[wi], data, firstChunk * sizeof(int16_t));

    int32_t secondChunk = count - firstChunk;
    if (secondChunk > 0) {
        std::memcpy(&buffer_[0], data + firstChunk, secondChunk * sizeof(int16_t));
    }

    writeIndex_.store((wi + count) % capacity_, std::memory_order_release);
    return count;
}

int32_t AudioRingBuffer::read(int16_t *data, int32_t count) {
    int32_t available = availableToRead();
    if (count > available) count = available;
    if (count <= 0) return 0;

    int32_t ri = readIndex_.load(std::memory_order_relaxed);

    int32_t firstChunk = capacity_ - ri;
    if (firstChunk > count) firstChunk = count;

    std::memcpy(data, &buffer_[ri], firstChunk * sizeof(int16_t));

    int32_t secondChunk = count - firstChunk;
    if (secondChunk > 0) {
        std::memcpy(data + firstChunk, &buffer_[0], secondChunk * sizeof(int16_t));
    }

    readIndex_.store((ri + count) % capacity_, std::memory_order_release);
    return count;
}

int32_t AudioRingBuffer::availableToRead() const {
    int32_t wi = writeIndex_.load(std::memory_order_acquire);
    int32_t ri = readIndex_.load(std::memory_order_relaxed);
    int32_t diff = wi - ri;
    if (diff < 0) diff += capacity_;
    return diff;
}

int32_t AudioRingBuffer::availableToWrite() const {
    const int32_t ri = readIndex_.load(std::memory_order_acquire);
    const int32_t wi = writeIndex_.load(std::memory_order_relaxed);
    const int32_t used = (wi - ri + capacity_) % capacity_;
    return capacity_ - 1 - used;
}

int32_t AudioRingBuffer::trimToLatest(int32_t keep) {
    if (keep < 0) keep = 0;
    const int32_t available = availableToRead();
    const int32_t discarded = available > keep ? available - keep : 0;
    const int32_t ri = readIndex_.load(std::memory_order_relaxed);
    readIndex_.store((ri + discarded) % capacity_, std::memory_order_release);
    return discarded;
}

void AudioRingBuffer::reset() {
    readIndex_.store(0, std::memory_order_relaxed);
    writeIndex_.store(0, std::memory_order_relaxed);
}
