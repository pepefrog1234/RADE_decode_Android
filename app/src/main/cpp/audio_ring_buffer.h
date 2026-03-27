/*
 * audio_ring_buffer.h - Lock-free SPSC ring buffer for real-time audio.
 *
 * Single-producer (FARGAN synthesis thread) / single-consumer (Oboe output callback).
 * Uses atomic read/write indices for wait-free operation on the audio thread.
 */

#ifndef AUDIO_RING_BUFFER_H
#define AUDIO_RING_BUFFER_H

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

class AudioRingBuffer {
public:
    explicit AudioRingBuffer(int32_t capacity);

    /** Write samples into the buffer. Returns number of samples actually written. */
    int32_t write(const int16_t *data, int32_t count);

    /** Read samples from the buffer. Returns number of samples actually read. */
    int32_t read(int16_t *data, int32_t count);

    /** Number of samples available to read. */
    int32_t availableToRead() const;

    /** Number of samples that can be written. */
    int32_t availableToWrite() const;

    /** Reset buffer to empty state. Not thread-safe — call only when streams are stopped. */
    void reset();

private:
    std::vector<int16_t> buffer_;
    int32_t capacity_;
    std::atomic<int32_t> readIndex_{0};
    std::atomic<int32_t> writeIndex_{0};
};

#endif // AUDIO_RING_BUFFER_H
