// Host regression test; no Android device or radio required.
#include "audio_ring_buffer.h"
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <thread>

static void wrapAndTrim() {
    AudioRingBuffer ring(16);
    int16_t input[30], out[16];
    for (int i = 0; i < 30; ++i) input[i] = i;
    assert(ring.write(input, 12) == 12);
    assert(ring.read(out, 10) == 10);
    assert(ring.write(input + 12, 10) == 10); // wraps the write index
    assert(ring.trimToLatest(5) == 7);
    assert(ring.read(out, 16) == 5);
    for (int i = 0; i < 5; ++i) assert(out[i] == 17 + i);
    assert(ring.write(input + 22, 8) == 8);
    assert(ring.trimToLatest(0) == 8);
    assert(ring.availableToRead() == 0);
    assert(ring.availableToWrite() == 15);
}

static void repeatedPlaybackStalls() {
    AudioRingBuffer old(32000), live(32000);
    int16_t pcm[160] = {}, out[160];
    int oldPeak = 0, livePeak = 0, dropped = 0;
    // Five simulated minutes. Repeated 50 ms stalls previously accumulated
    // into ~2 s of stale sound; trimming on the consumer keeps it bounded.
    for (int tick = 0; tick < 30000; ++tick) {
        old.write(pcm, 160); live.write(pcm, 160);
        if (tick % 100 >= 5) {
            if (live.availableToRead() > 4800) dropped += live.trimToLatest(1600);
            old.read(out, 160); live.read(out, 160);
            oldPeak = std::max(oldPeak, old.availableToRead());
            livePeak = std::max(livePeak, live.availableToRead());
        }
    }
    assert(oldPeak > 30000);
    assert(livePeak <= 4800);
    assert(dropped > 0);
    std::printf("Five-minute stalls: legacy peak=%d ms; bounded peak=%d ms\n", oldPeak / 16, livePeak / 16);
}

static void concurrentProducerAndTrimmingConsumer() {
    AudioRingBuffer ring(512);
    std::atomic<bool> done{false};
    std::thread producer([&] {
        int next = 0;
        while (next < 1000000) {
            int16_t samples[64];
            for (int i = 0; i < 64; ++i) samples[i] = (next + i) % 30000;
            next += ring.write(samples, std::min(64, 1000000 - next));
            std::this_thread::yield();
        }
        done.store(true, std::memory_order_release);
    });
    int previous = -1, reads = 0;
    while (!done.load(std::memory_order_acquire) || ring.availableToRead() > 0) {
        if (ring.availableToRead() > 192) ring.trimToLatest(64);
        int16_t samples[32];
        int count = ring.read(samples, 32);
        for (int i = 0; i < count; ++i) {
            if (previous >= 0) {
                int distance = (samples[i] - previous + 30000) % 30000;
                assert(distance > 0 && distance <= 512); // no duplication, reordering or torn data
            }
            previous = samples[i];
            ++reads;
        }
        std::this_thread::yield();
    }
    producer.join();
    assert(reads > 0);
}

int main() {
    wrapAndTrim();
    repeatedPlaybackStalls();
    concurrentProducerAndTrimmingConsumer();
    std::puts("Audio ring regression tests passed");
}
