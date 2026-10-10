#ifndef TX_INPUT_HEALTH_H
#define TX_INPUT_HEALTH_H

#include <atomic>
#include <cstdint>
#include <memory>

/** Control-thread TX capture watchdog. Stream callbacks only touch Capture's
 * atomics; all other methods are serialized by AudioEngineAccess in JNI.
 * A fresh token belongs to every opened stream, so a delayed error from a
 * retired stream cannot affect a replacement or a later transmission. */
class TxInputHealth {
public:
    static constexpr int64_t STARTUP_TIMEOUT_NS = 5000000000LL;
    static constexpr int64_t STALL_TIMEOUT_NS = 1500000000LL;
    static constexpr int64_t ROUTE_SETTLE_NS = 200000000LL;
    static constexpr int MAX_REOPENS = 2;

    struct Capture {
        explicit Capture(int64_t now) : startedNs(now) {}

        // Even zero-valued PCM proves that capture is alive. Never infer
        // microphone failure from RMS: a quiet room is a valid input.
        bool onFrames(int64_t now) {
            if (!accepting.load()) return false;
            lastFramesNs.store(now);
            return true;
        }
        void onError() { disconnected.store(true); }

        std::atomic<bool> accepting{true};
        std::atomic<bool> disconnected{false};
        std::atomic<int64_t> startedNs;
        std::atomic<int64_t> lastFramesNs{-1};
    };

    enum class Action { None, Reopen, Failed };

    void beginSession() {
        endSession();
        active_ = true;
        failed_ = false;
        reopens_ = 0;
    }

    void endSession() {
        suspendCapture();
        capture_.reset();
        active_ = false;
        failed_ = false;
        settleUntilNs_ = -1;
    }

    std::shared_ptr<Capture> beginCapture(int64_t now) {
        suspendCapture();
        capture_ = std::make_shared<Capture>(now);
        settleUntilNs_ = -1;
        return capture_;
    }

    void captureStarting(int64_t now) {
        if (capture_) capture_->startedNs.store(now);
    }

    void suspendCapture() {
        if (capture_) capture_->accepting.store(false);
    }

    void captureFailed() {
        if (capture_) capture_->onError();
    }

    int reopenCount() const { return reopens_; }

    Action poll(int64_t now) {
        if (!active_) return Action::None;
        if (failed_) return Action::Failed;
        if (!capture_) return Action::None;

        const int64_t last = capture_->lastFramesNs.load();
        const bool timedOut = last < 0
            ? now - capture_->startedNs.load() >= STARTUP_TIMEOUT_NS
            : now - last >= STALL_TIMEOUT_NS;
        if (!capture_->disconnected.load() && !timedOut) {
            settleUntilNs_ = -1;
            return Action::None;
        }
        if (reopens_ >= MAX_REOPENS) {
            failed_ = true;
            return Action::Failed;
        }
        if (settleUntilNs_ < 0) settleUntilNs_ = now + ROUTE_SETTLE_NS;
        if (now < settleUntilNs_) return Action::None;

        ++reopens_;
        settleUntilNs_ = -1;
        return Action::Reopen;
    }

private:
    std::shared_ptr<Capture> capture_;
    bool active_ = false;
    bool failed_ = false;
    int reopens_ = 0;
    int64_t settleUntilNs_ = -1;
};

#endif
