// Host regression test; no Android device, microphone, or transmitter required.
// c++ -std=c++11 -pthread -Iapp/src/main/cpp
//   app/src/test/cpp/tx_input_health_test.cpp -o /tmp/rade-tx-input-health-test
#include "tx_input_health.h"
#include <cassert>
#include <cstdio>
#include <thread>

using Action = TxInputHealth::Action;
static constexpr int64_t MS = 1000000LL;

static void disconnectRecoveryIsBoundedPerOver() {
    TxInputHealth health;
    health.beginSession();
    auto capture = health.beginCapture(0);
    capture->onFrames(10 * MS);
    capture->onError();
    assert(health.poll(20 * MS) == Action::None);
    assert(health.poll(219 * MS) == Action::None);
    assert(health.poll(220 * MS) == Action::Reopen);
    assert(health.reopenCount() == 1);

    // Successful capture must not reset the per-over retry budget.
    capture = health.beginCapture(220 * MS);
    for (int i = 3; i < 100; ++i) {
        assert(capture->onFrames(i * 100 * MS));
        assert(health.poll(i * 100 * MS) == Action::None);
    }
    capture->onError();
    assert(health.poll(10000 * MS) == Action::None);
    assert(health.poll(10200 * MS) == Action::Reopen);
    assert(health.reopenCount() == 2);
    capture = health.beginCapture(10200 * MS);
    capture->onError();
    assert(health.poll(10201 * MS) == Action::Failed);
    assert(health.poll(99999 * MS) == Action::Failed);
}

static void missingStartupAndRunningStalls() {
    TxInputHealth health;
    health.beginSession();
    auto capture = health.beginCapture(0);
    assert(health.poll(4999 * MS) == Action::None);
    assert(health.poll(5000 * MS) == Action::None);
    assert(health.poll(5199 * MS) == Action::None);
    assert(health.poll(5200 * MS) == Action::Reopen);

    capture = health.beginCapture(5200 * MS);
    // Opening and setup time do not spend the new capture's startup window.
    health.captureStarting(5500 * MS);
    assert(health.poll(10499 * MS) == Action::None);
    assert(capture->onFrames(10500 * MS));
    assert(health.poll(11999 * MS) == Action::None);
    assert(health.poll(12000 * MS) == Action::None);
    assert(health.poll(12200 * MS) == Action::Reopen);

    capture = health.beginCapture(12200 * MS);
    assert(health.poll(17200 * MS) == Action::Failed);
}

static void silentButLiveInputIsHealthy() {
    TxInputHealth health;
    health.beginSession();
    auto capture = health.beginCapture(0);
    // Capture health observes callback arrival, independent of zero RMS.
    for (int64_t t = 0; t < 60000 * MS; t += 10 * MS) {
        assert(capture->onFrames(t));
        assert(health.poll(t) == Action::None);
    }
    assert(health.reopenCount() == 0);
}

static void resumedFramesCancelOnlyATimeout() {
    TxInputHealth health;
    health.beginSession();
    auto capture = health.beginCapture(0);
    assert(health.poll(5000 * MS) == Action::None);
    capture->onFrames(5100 * MS);
    assert(health.poll(5200 * MS) == Action::None);
    assert(health.reopenCount() == 0);

    capture->onError();
    assert(health.poll(5200 * MS) == Action::None);
    capture->onFrames(5399 * MS);
    assert(health.poll(5400 * MS) == Action::Reopen);
}

static void retiredCallbacksAndNewSessionsAreIsolated() {
    TxInputHealth health;
    health.beginSession();
    auto retired = health.beginCapture(0);
    retired->onError();
    assert(health.poll(100 * MS) == Action::None);
    assert(health.poll(300 * MS) == Action::Reopen);
    auto replacement = health.beginCapture(300 * MS);
    assert(!retired->onFrames(301 * MS));
    // A late error may still arrive on Oboe's old callback thread.
    std::thread oldError([retired] { retired->onError(); });
    oldError.join();
    replacement->onFrames(310 * MS);
    assert(health.poll(310 * MS) == Action::None);

    health.endSession();
    assert(!replacement->onFrames(320 * MS));
    replacement->onError();
    assert(health.poll(99999 * MS) == Action::None);
    health.beginSession();
    auto next = health.beginCapture(100000 * MS);
    assert(health.reopenCount() == 0);
    replacement->onError();
    assert(health.poll(100001 * MS) == Action::None);
    next->onError();
    assert(health.poll(100002 * MS) == Action::None);
    assert(health.poll(100202 * MS) == Action::Reopen);
}

static void failedReopenConsumesTheSameBudget() {
    TxInputHealth health;
    health.beginSession();
    health.beginCapture(0);
    health.captureFailed();
    assert(health.poll(0) == Action::None);
    assert(health.poll(200 * MS) == Action::Reopen);
    health.beginCapture(200 * MS);
    health.captureFailed();
    assert(health.poll(201 * MS) == Action::None);
    assert(health.poll(401 * MS) == Action::Reopen);
    health.beginCapture(401 * MS);
    health.captureFailed();
    assert(health.poll(402 * MS) == Action::Failed);
}

int main() {
    disconnectRecoveryIsBoundedPerOver();
    missingStartupAndRunningStalls();
    silentButLiveInputIsHealthy();
    resumedFramesCancelOnlyATimeout();
    retiredCallbacksAndNewSessionsAreIsolated();
    failedReopenConsumesTheSameBudget();
    std::puts("TX input health regression tests passed");
}
