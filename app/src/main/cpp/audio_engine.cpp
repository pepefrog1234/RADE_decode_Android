/*
 * audio_engine.cpp
 *
 * Input: capture at device native rate (48kHz) → polyphase FIR decimation → 8kHz
 *   This matches iOS: AVAudioEngine 48kHz → AVAudioConverter → 8kHz Int16
 * Output: FARGAN 16kHz → ring buffer → Oboe SRC → device rate
 */

#include "audio_engine.h"
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <unistd.h>
#include <time.h>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

constexpr float RX_SIGNAL_GATE_DB = -36.0f;
constexpr float RX_DROPOUT_ABS_DB = -45.0f;
constexpr float RX_DROPOUT_REL_DB = 14.0f;
constexpr float RX_DROPOUT_ACTIVE_DB = -38.0f;
constexpr int RX_DROPOUT_RECENT_CALLBACKS = 8;   // about 160 ms at 20 ms/callback
constexpr int RX_DROPOUT_CONCEAL_MAX_CALLBACKS = 2;
constexpr int MODEM_QUEUE_MAX_SAMPLES = MODEM_SAMPLE_RATE * 2;

/* ── RX Oboe callbacks ──────────────────────────────────────── */

oboe::DataCallbackResult InputCallback::onAudioReady(
        oboe::AudioStream *s, void *d, int32_t n) {
    if (!engine_->running_.load()) return oboe::DataCallbackResult::Stop;
    engine_->processInputFrames(static_cast<float*>(d), n, s->getChannelCount());
    return oboe::DataCallbackResult::Continue;
}

oboe::DataCallbackResult OutputCallback::onAudioReady(
        oboe::AudioStream *s, void *d, int32_t n) {
    if (!engine_->running_.load()) return oboe::DataCallbackResult::Stop;
    engine_->renderOutput(static_cast<float*>(d), n);
    return oboe::DataCallbackResult::Continue;
}

void InputCallback::onErrorAfterClose(oboe::AudioStream *s, oboe::Result error) {
    LOGE("Input stream error: %s — restarting", oboe::convertToText(error));
    if (engine_->running_.load()) {
        engine_->restartInputStream();
    }
}

void OutputCallback::onErrorAfterClose(oboe::AudioStream *s, oboe::Result error) {
    LOGE("Output stream error: %s — restarting", oboe::convertToText(error));
    if (engine_->running_.load()) {
        engine_->restartOutputStream();
    }
}

/* ── TX Oboe callbacks ──────────────────────────────────────── */

oboe::DataCallbackResult TxInputCallback::onAudioReady(
        oboe::AudioStream *s, void *d, int32_t n) {
    if (!engine_->txRunning_.load()) return oboe::DataCallbackResult::Stop;
    engine_->processTxInputFrames(static_cast<float*>(d), n, s->getChannelCount());
    return oboe::DataCallbackResult::Continue;
}

oboe::DataCallbackResult TxOutputCallback::onAudioReady(
        oboe::AudioStream *s, void *d, int32_t n) {
    if (!engine_->txRunning_.load()) return oboe::DataCallbackResult::Stop;
    engine_->renderTxOutput(static_cast<float*>(d), n);
    return oboe::DataCallbackResult::Continue;
}

void TxInputCallback::onErrorAfterClose(oboe::AudioStream *s, oboe::Result error) {
    LOGE("TX input stream error: %s", oboe::convertToText(error));
}

void TxOutputCallback::onErrorAfterClose(oboe::AudioStream *s, oboe::Result error) {
    LOGE("TX output stream error: %s", oboe::convertToText(error));
}

/* ── Constructor / Destructor ──────────────────────────────── */

AudioEngine::AudioEngine() {
    fftInput_.resize(FFT_SIZE, 0.0f);
    std::fill(spectrumDb_, spectrumDb_ + FFT_BINS, -100.0f);
    inputCb_ = std::make_unique<InputCallback>(this);
    outputCb_ = std::make_unique<OutputCallback>(this);
    txInputCb_ = std::make_unique<TxInputCallback>(this);
    txOutputCb_ = std::make_unique<TxOutputCallback>(this);
}

AudioEngine::~AudioEngine() { stop(); stopTx(); }

/* ── Polyphase FIR decimation filter design ──────────────────── */

void AudioEngine::designDecimFilter(int inputRate, int outputRate) {
    decimInputRate_ = (inputRate > 0) ? inputRate : INPUT_SAMPLE_RATE;
    decimOutputRate_ = (outputRate > 0) ? outputRate : MODEM_SAMPLE_RATE;
    decimFactor_ = (decimInputRate_ + decimOutputRate_ - 1) / decimOutputRate_;
    if (decimFactor_ < 1) decimFactor_ = 1;

    // Design low-pass FIR: cutoff = outputRate/2, transition band to outputRate*0.6
    // Total taps = DECIM_FIR_TAPS * decimFactor for the full filter
    int totalTaps = DECIM_FIR_TAPS * decimFactor_;
    float fc = (float)decimOutputRate_ / (2.0f * (float)decimInputRate_);  // normalized cutoff

    decimCoeffs_.resize(totalTaps);
    float sum = 0;
    int M = totalTaps - 1;
    for (int i = 0; i < totalTaps; i++) {
        float n = (float)i - (float)M / 2.0f;
        // Sinc
        float h;
        if (fabsf(n) < 1e-6f) {
            h = 2.0f * fc;
        } else {
            h = sinf(2.0f * (float)M_PI * fc * n) / ((float)M_PI * n);
        }
        // Kaiser-like window (Blackman-Harris for good stopband)
        float w = 0.35875f
                - 0.48829f * cosf(2.0f * (float)M_PI * (float)i / (float)M)
                + 0.14128f * cosf(4.0f * (float)M_PI * (float)i / (float)M)
                - 0.01168f * cosf(6.0f * (float)M_PI * (float)i / (float)M);
        decimCoeffs_[i] = h * w;
        sum += decimCoeffs_[i];
    }
    // Normalize for unity DC gain
    for (int i = 0; i < totalTaps; i++) {
        decimCoeffs_[i] /= sum;
    }

    decimHistory_.resize(totalTaps, 0.0f);
    decimHistPos_ = 0;
    decimPhase_ = 0;

    LOGI("Decimation filter: %dHz→%dHz factor≈%d taps=%d",
         decimInputRate_, decimOutputRate_, decimFactor_, totalTaps);
}

static int64_t monotonicNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void writePcmWavHeader(FILE *file, uint32_t sampleRate, uint32_t dataBytes) {
    uint8_t header[44] = {0};
    memcpy(header, "RIFF", 4);
    uint32_t riffSize = dataBytes + 36;
    memcpy(header + 4, &riffSize, 4);
    memcpy(header + 8, "WAVE", 4);
    memcpy(header + 12, "fmt ", 4);
    uint32_t fmtSize = 16;         memcpy(header + 16, &fmtSize, 4);
    uint16_t pcmFmt = 1;           memcpy(header + 20, &pcmFmt, 2);
    uint16_t channels = 1;         memcpy(header + 22, &channels, 2);
    memcpy(header + 24, &sampleRate, 4);
    uint32_t byteRate = sampleRate * channels * sizeof(int16_t);
    memcpy(header + 28, &byteRate, 4);
    uint16_t blockAlign = channels * sizeof(int16_t);
    memcpy(header + 32, &blockAlign, 2);
    uint16_t bitsPerSample = 16;   memcpy(header + 34, &bitsPerSample, 2);
    memcpy(header + 36, "data", 4);
    memcpy(header + 40, &dataBytes, 4);
    fwrite(header, 1, sizeof(header), file);
}

static void updatePcmWavHeader(FILE *file, uint32_t dataBytes) {
    long pos = ftell(file);
    uint32_t riffSize = dataBytes + 36;
    fseek(file, 4, SEEK_SET);
    fwrite(&riffSize, 4, 1, file);
    fseek(file, 40, SEEK_SET);
    fwrite(&dataBytes, 4, 1, file);
    fseek(file, pos, SEEK_SET);
}

/* ── Start / Stop ──────────────────────────────────────────── */

bool AudioEngine::start(int inputDeviceId, int outputDeviceId) {
    if (running_.load()) return true;
    inputDeviceId_ = (inputDeviceId > 0) ? inputDeviceId : 0;
    outputDeviceId_ = (outputDeviceId > 0) ? outputDeviceId : 0;

    if (!initModem()) return false;

    modemInputBuf_.resize(MODEM_QUEUE_MAX_SAMPLES, 0);
    modemInputPos_ = 0;
    fftInputPos_ = 0;
    rxSelectedChannel_ = 0;
    rxWorkInput_.clear();
    rxLastGoodInput_.clear();
    rxModemOut_.clear();
    rxLastGoodValid_ = false;
    rxLastGoodRmsDb_ = -100.0f;
    rxLastGoodPeak_ = 0.0f;
    rxConcealRun_ = 0;
    rxCallbacksSinceGood_ = RX_DROPOUT_RECENT_CALLBACKS + 1;
    modemQueueDropSamples_ = 0;
    playbackRing_.reset();

    running_.store(true);
    startModemWorker();

    if (!openOutputStream()) {
        running_.store(false); stopModemWorker(); releaseModem(); return false;
    }
    if (!openInputStream()) {
        running_.store(false);
        stopModemWorker();
        outputStream_->stop(); outputStream_->close(); outputStream_.reset();
        releaseModem(); return false;
    }

    LOGI("Audio engine started: in=%dHz(÷%d) out=16kHz inDev=%d outDev=%d",
         actualInputRate_, decimFactor_, inputDeviceId_, outputDeviceId_);
    return true;
}

void AudioEngine::stop() {
    running_.store(false);
    netRxRunning_.store(false);
    if (inputStream_)  { inputStream_->stop();  inputStream_->close();  inputStream_.reset(); }
    stopModemWorker();
    if (outputStream_) { outputStream_->stop(); outputStream_->close(); outputStream_.reset(); }
    stopRecording();
    stopModemRecording();
    inputSessionId_ = -1;
    releaseModem();
}

void AudioEngine::setInputDevice(int deviceId) {
    inputDeviceId_ = (deviceId > 0) ? deviceId : 0;
    if (running_.load()) { stop(); start(inputDeviceId_); }
}

void AudioEngine::setOutputVolume(float volume) {
    outputVolume_.store(std::clamp(volume, 0.0f, 1.0f));
}

void AudioEngine::setInputGain(float gain) {
    inputGain_.store(std::clamp(gain, 0.1f, 50.0f));
}

/* ── Stream setup ──────────────────────────────────────────── */

bool AudioEngine::openInputStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
           // USB capture on some Android builds intermittently returns near-zero
           // buffers through AAudio while reporting no xrun. OpenSL ES usually
           // rides the older AudioRecord path and is more stable for USB class
           // audio interfaces, so use it for RX capture.
           ->setAudioApi(oboe::AudioApi::OpenSLES)
           // A receive modem needs continuity more than low latency. USB audio
           // devices commonly deliver tiny low-latency bursts; doing modem and
           // vocoder work on that deadline can create capture overruns and
           // short holes that break pilot tracking.
           ->setPerformanceMode(oboe::PerformanceMode::None)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           // Lock capture to 48 kHz. Our polyphase decimator assumes 48→8 kHz
           // (decimFactor_=6); accepting the device native rate risks 44.1 kHz
           // or other values that break modem correlation. If the HW can't
           // deliver 48 kHz natively, Oboe inserts a high-quality resampler.
           ->setSampleRate(INPUT_SAMPLE_RATE)
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
           // The modem/vocoder pipeline sometimes runs inside the input
           // callback. A 4 ms USB-audio burst leaves too little deadline
           // headroom on phones, so ask for ~20 ms callbacks while still
           // using Oboe's stream buffer for continuity.
           ->setFramesPerDataCallback(INPUT_SAMPLE_RATE / 50)
           // Unprocessed: ask the platform to bypass AGC, NS, AEC. Some OEMs
           // (Samsung S24, Pixel variants) ignore this hint at the HAL layer,
           // so we additionally disable effects on the allocated session id
           // from Kotlin after open — that's the canonical, always-works path.
           ->setInputPreset(oboe::InputPreset::Unprocessed)
           ->setSessionId(oboe::SessionId::Allocate)
           ->setDataCallback(inputCb_.get())
           ->setErrorCallback(inputCb_.get());

    if (inputDeviceId_ > 0) builder.setDeviceId(inputDeviceId_);

    auto result = builder.openStream(inputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input with OpenSL ES: %s; retrying default API",
             oboe::convertToText(result));
        builder.setAudioApi(oboe::AudioApi::Unspecified);
        result = builder.openStream(inputStream_);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open input: %s", oboe::convertToText(result));
            return false;
        }
    }

    actualInputRate_ = inputStream_->getSampleRate();
    inputSessionId_ = inputStream_->getSessionId();
    auto actualPreset = inputStream_->getInputPreset();
    auto actualPerf = inputStream_->getPerformanceMode();
    auto actualFormat = inputStream_->getFormat();
    auto actualSharing = inputStream_->getSharingMode();
    auto actualApi = inputStream_->getAudioApi();
    LOGI("Input opened: api=%d rate=%d ch=%d fpcb=%d device=%d preset=%d perf=%d format=%d share=%d session=%d",
         (int)actualApi, actualInputRate_, inputStream_->getChannelCount(),
         inputStream_->getFramesPerDataCallback(), inputStream_->getDeviceId(),
         (int)actualPreset, (int)actualPerf, (int)actualFormat,
         (int)actualSharing, inputSessionId_);
    if (actualPreset != oboe::InputPreset::Unprocessed) {
        LOGE("Input: device did NOT honor Unprocessed preset (got %d). "
             "Kotlin will disable effects via session id as a fallback.",
             (int)actualPreset);
        unprocessedRejected_.store(true);
    } else {
        unprocessedRejected_.store(false);
    }

    // Buffer-size headroom against overruns. The default LowLatency buffer is a
    // single burst — the tightest setting — which breaks up on weak / heavily
    // loaded devices (e.g. Huawei MediaPad M5) during the CPU-heavy startup
    // window: this is the reported "reception breaks up right after start, then
    // stable once settled; screen off→on (which restarts the stream after the
    // spike) fixes it". A few bursts of headroom let the capture ride through
    // transient CPU starvation. Costs negligible latency — the modem already
    // sits behind a ring buffer. Applied here so cold-open AND restart get it.
    {
        int32_t burst = inputStream_->getFramesPerBurst();
        int32_t cap = inputStream_->getBufferCapacityInFrames();
        if (burst > 0) {
            int32_t target = (cap > 0) ? cap : burst * 8;
            auto bufR = inputStream_->setBufferSizeInFrames(target);
            if (bufR) {
                LOGI("Input buffer: %d frames (burst=%d cap=%d)", bufR.value(),
                     burst, inputStream_->getBufferCapacityInFrames());
            }
        }
    }

    designDecimFilter(actualInputRate_, MODEM_SAMPLE_RATE);

    result = inputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input: %s", oboe::convertToText(result));
        inputStream_->close(); inputStream_.reset(); return false;
    }
    return true;
}

bool AudioEngine::openOutputStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           ->setSampleRate(SPEECH_SAMPLE_RATE)
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
           ->setChannelCount(oboe::ChannelCount::Mono)
           ->setUsage(oboe::Usage::Media)
           ->setDataCallback(outputCb_.get())
           ->setErrorCallback(outputCb_.get());

    // Route decoded speech away from USB audio (the rig's own audio back-feed).
    // Without this, Android picks USB as the preferred Media destination
    // whenever a USB audio device is connected, so the user hears nothing in
    // the phone's speaker. Wired headsets / BT still override this preference
    // at the Android audio policy level.
    if (outputDeviceId_ > 0) builder.setDeviceId(outputDeviceId_);

    auto result = builder.openStream(outputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output: %s", oboe::convertToText(result));
        return false;
    }

    LOGI("Output opened: rate=%d ch=%d device=%d (requested=%d)",
         outputStream_->getSampleRate(), outputStream_->getChannelCount(),
         outputStream_->getDeviceId(), outputDeviceId_);

    // Buffer headroom against underruns during the CPU-heavy startup window
    // (see openInputStream for the full rationale — same MediaPad M5 symptom).
    {
        int32_t burst = outputStream_->getFramesPerBurst();
        if (burst > 0) {
            auto bufR = outputStream_->setBufferSizeInFrames(burst * 4);
            if (bufR) {
                LOGI("Output buffer: %d frames (burst=%d cap=%d)", bufR.value(),
                     burst, outputStream_->getBufferCapacityInFrames());
            }
        }
    }

    result = outputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output: %s", oboe::convertToText(result));
        outputStream_->close(); outputStream_.reset(); return false;
    }
    return true;
}

/* ── Output: 16kHz from ring buffer ──────────────────────────── */

void AudioEngine::renderOutput(float *output, int32_t numFrames) {
    float volume = outputVolume_.load();
    int16_t tempBuf[4096];
    int toRead = std::min(numFrames, 4096);
    int got = playbackRing_.read(tempBuf, toRead);

    float rmsSum = 0.0f;
    for (int i = 0; i < numFrames; i++) {
        float sample = (i < got) ? (float)tempBuf[i] / 32768.0f * volume : 0.0f;
        output[i] = sample;
        rmsSum += sample * sample;
    }
    if (numFrames > 0)
        outputLevelDb_.store(10.0f * log10f(rmsSum / (float)numFrames + 1e-10f));
}

/* ── Input: native rate → FIR decimate → 8kHz → modem ────────── */

void AudioEngine::processInputFrames(const float *data, int32_t numFrames, int32_t channelCount) {
    if (numFrames <= 0 || channelCount <= 0) {
        return;
    }
    static int logCounter = 0;
    float gain = inputGain_.load();
    int totalTaps = (int)decimCoeffs_.size();
    if (totalTaps <= 0 || decimFactor_ <= 0) {
        return;
    }
    int sourceChannel = 0;
    constexpr int MAX_INPUT_CHANNELS = 8;
    float channelEnergy[MAX_INPUT_CHANNELS] = {0.0f};
    int channelsToCheck = std::min(channelCount, MAX_INPUT_CHANNELS);
    if (channelCount > 1) {
        for (int i = 0; i < numFrames; i++) {
            for (int ch = 0; ch < channelsToCheck; ch++) {
                float v = data[i * channelCount + ch];
                channelEnergy[ch] += v * v;
            }
        }

        if (rxSelectedChannel_ < 0 || rxSelectedChannel_ >= channelsToCheck) {
            rxSelectedChannel_ = 0;
        }
        int bestChannel = rxSelectedChannel_;
        for (int ch = 0; ch < channelsToCheck; ch++) {
            if (channelEnergy[ch] > channelEnergy[bestChannel]) {
                bestChannel = ch;
            }
        }
        // Avoid buffer-to-buffer channel flapping. Switch only when another
        // channel is clearly hotter, which is typical when a radio is wired to
        // one side of a stereo USB sound card and the other side is floating.
        if (bestChannel != rxSelectedChannel_ &&
            channelEnergy[bestChannel] > channelEnergy[rxSelectedChannel_] * 2.0f + 1e-9f) {
            rxSelectedChannel_ = bestChannel;
            LOGI("RX: selected input channel %d/%d", rxSelectedChannel_ + 1, channelCount);
        }
        sourceChannel = rxSelectedChannel_;
    }

    if ((int)rxWorkInput_.size() != numFrames) {
        rxWorkInput_.assign(numFrames, 0.0f);
    }

    float actualRmsSum = 0.0f;
    float actualPeakSample = 0.0f;
    int clipRiskCount = 0;
    for (int i = 0; i < numFrames; i++) {
        float raw = data[i * channelCount + sourceChannel] * gain;
        rxWorkInput_[i] = raw;
        actualRmsSum += raw * raw;
        float absRaw = fabsf(raw);
        if (absRaw > actualPeakSample) actualPeakSample = absRaw;
        if (absRaw >= 0.98f) clipRiskCount++;
    }

    float actualRmsDb = 10.0f * log10f(actualRmsSum / (float)numFrames + 1e-10f);
    bool syncedForConceal = syncState_.load() != 0;
    bool dropoutCandidate = syncedForConceal
            && rxLastGoodValid_
            && rxCallbacksSinceGood_ <= RX_DROPOUT_RECENT_CALLBACKS
            && actualRmsDb < RX_DROPOUT_ABS_DB
            && actualRmsDb < (rxLastGoodRmsDb_ - RX_DROPOUT_REL_DB);
    bool concealedDropout = false;

    if (dropoutCandidate &&
        rxConcealRun_ < RX_DROPOUT_CONCEAL_MAX_CALLBACKS &&
        (int)rxLastGoodInput_.size() == numFrames) {
        std::copy(rxLastGoodInput_.begin(), rxLastGoodInput_.end(), rxWorkInput_.begin());
        rxConcealRun_++;
        rxCallbacksSinceGood_++;
        concealedDropout = true;
        LOGI("RX dropout: concealed %dms gap actual=%.1fdB replay=%.1fdB ch=%d/%d run=%d",
             (int)(1000LL * numFrames / std::max(1, actualInputRate_)),
             actualRmsDb, rxLastGoodRmsDb_, sourceChannel + 1, channelCount, rxConcealRun_);
    } else {
        if (dropoutCandidate && rxConcealRun_ >= RX_DROPOUT_CONCEAL_MAX_CALLBACKS) {
            static int longDropoutLogCounter = 0;
            longDropoutLogCounter++;
            if (longDropoutLogCounter % 10 == 1) {
                LOGI("RX dropout: not concealing longer gap actual=%.1fdB last=%.1fdB",
                     actualRmsDb, rxLastGoodRmsDb_);
            }
        }
        if (actualRmsDb > RX_DROPOUT_ACTIVE_DB) {
            rxLastGoodInput_ = rxWorkInput_;
            rxLastGoodValid_ = true;
            rxLastGoodRmsDb_ = actualRmsDb;
            rxLastGoodPeak_ = actualPeakSample;
            rxCallbacksSinceGood_ = 0;
            rxConcealRun_ = 0;
        } else {
            rxCallbacksSinceGood_++;
            if (!dropoutCandidate) {
                rxConcealRun_ = 0;
            }
        }
    }

    rxModemOut_.clear();
    rxModemOut_.reserve((int)(((int64_t)numFrames * decimOutputRate_) / std::max(1, decimInputRate_)) + 2);

    for (int i = 0; i < numFrames; i++) {
        float raw = rxWorkInput_[i];

        // Push into FIR history (circular buffer)
        decimHistory_[decimHistPos_] = raw;
        decimHistPos_ = (decimHistPos_ + 1) % totalTaps;

        // Decimate to the exact modem rate. For 48k→8k this emits every
        // 6 samples; for devices that actually open at 44.1k it alternates
        // 5/6-sample intervals instead of silently feeding the modem at 8.82k.
        decimPhase_ += decimOutputRate_;
        if (decimPhase_ >= decimInputRate_) {
            decimPhase_ -= decimInputRate_;

            // Apply FIR filter (convolution at this output point)
            float filtered = 0.0f;
            int idx = decimHistPos_;
            for (int j = 0; j < totalTaps; j++) {
                idx--;
                if (idx < 0) idx = totalTaps - 1;
                filtered += decimHistory_[idx] * decimCoeffs_[j];
            }

            // Clamp and convert to Int16
            filtered = std::clamp(filtered, -0.999f, 0.999f);
            int16_t s16 = (int16_t)(filtered * 32767.0f);
            rxModemOut_.push_back(s16);

            // FFT at 8kHz
            if (fftInputPos_ < FFT_SIZE) {
                fftInput_[fftInputPos_++] = filtered;
            }
            if (fftInputPos_ >= FFT_SIZE) {
                computeFFT();
                fftInputPos_ = 0;
            }
        }
    }
    if (!rxModemOut_.empty()) {
        feedModem(rxModemOut_.data(), (int)rxModemOut_.size());
    }

    if (numFrames > 0)
        inputLevelDb_.store(actualRmsDb);

    logCounter++;
    if (logCounter % 8 == 0) {
        float peakDb = 20.0f * log10f(actualPeakSample + 1e-10f);
        float clipPct = (numFrames > 0) ? (100.0f * (float)clipRiskCount / (float)numFrames) : 0.0f;
        int32_t xruns = -1;
        if (inputStream_) {
            auto xr = inputStream_->getXRunCount();
            if (xr) xruns = xr.value();
        }
        if (channelCount > 1 && channelsToCheck >= 2) {
            float ch1Db = 10.0f * log10f(channelEnergy[0] * gain * gain / (float)numFrames + 1e-10f);
            float ch2Db = 10.0f * log10f(channelEnergy[1] * gain * gain / (float)numFrames + 1e-10f);
            LOGI("RX: in=%d ch=%d/%d chDb=%.1f/%.1f gain=%.1f peak=%.1fdB rms=%.1fdB clip=%.1f%% conceal=%d sync=%d snr=%d foff=%.1f ring=%d xrun=%d",
                 numFrames, sourceChannel + 1, channelCount, ch1Db, ch2Db, gain,
                 peakDb, actualRmsDb, clipPct, concealedDropout ? 1 : 0,
                 syncState_.load(), snrEstimate_.load(), freqOffset_.load(),
                 playbackRing_.availableToRead(), xruns);
        } else {
            LOGI("RX: in=%d ch=%d/%d gain=%.1f peak=%.1fdB rms=%.1fdB clip=%.1f%% conceal=%d sync=%d snr=%d foff=%.1f ring=%d xrun=%d",
                 numFrames, sourceChannel + 1, channelCount, gain, peakDb,
                 actualRmsDb, clipPct, concealedDropout ? 1 : 0,
                 syncState_.load(), snrEstimate_.load(), freqOffset_.load(),
                 playbackRing_.availableToRead(), xruns);
        }
    }
}

void AudioEngine::feedModem(const int16_t *samples8k, int count) {
    if (!samples8k || count <= 0 || !modemWorkerRunning_.load()) {
        return;
    }

    bool dropped = false;
    uint32_t droppedTotal = 0;
    {
        std::lock_guard<std::mutex> lk(modemMutex_);
        int capacity = (int)modemInputBuf_.size();
        if (capacity <= 0) return;

        if (count > capacity) {
            samples8k += count - capacity;
            count = capacity;
        }
        if (modemInputPos_ + count > capacity) {
            int drop = modemInputPos_ + count - capacity;
            drop = std::min(drop, modemInputPos_);
            if (drop > 0) {
                int rem = modemInputPos_ - drop;
                if (rem > 0) {
                    std::memmove(modemInputBuf_.data(), modemInputBuf_.data() + drop,
                                 rem * sizeof(int16_t));
                }
                modemInputPos_ = rem;
                modemQueueDropSamples_ += drop;
                droppedTotal = modemQueueDropSamples_;
                dropped = true;
            }
        }
        std::memcpy(modemInputBuf_.data() + modemInputPos_, samples8k, count * sizeof(int16_t));
        modemInputPos_ += count;
    }
    modemCv_.notify_one();

    if (dropped) {
        LOGE("RX modem queue overflow: dropped samples total=%u", droppedTotal);
    }
}

void AudioEngine::startModemWorker() {
    if (modemWorkerRunning_.exchange(true)) return;
    modemThread_ = std::thread(&AudioEngine::modemWorkerLoop, this);
}

void AudioEngine::stopModemWorker() {
    bool wasRunning = modemWorkerRunning_.exchange(false);
    if (wasRunning) {
        modemCv_.notify_all();
    }
    if (modemThread_.joinable()) {
        modemThread_.join();
    }
    std::lock_guard<std::mutex> lk(modemMutex_);
    modemInputPos_ = 0;
}

void AudioEngine::modemWorkerLoop() {
    std::vector<int16_t> frame;
    while (true) {
        int nin = 0;
        {
            std::unique_lock<std::mutex> lk(modemMutex_);
            modemCv_.wait(lk, [&]() {
                return !modemWorkerRunning_.load() ||
                       (rade_ && modemInputPos_ >= rade_nin(rade_));
            });
            if (!modemWorkerRunning_.load()) {
                break;
            }
            if (!rade_) continue;
            nin = rade_nin(rade_);
            if (nin <= 0 || modemInputPos_ < nin) continue;
            frame.resize(nin);
            std::memcpy(frame.data(), modemInputBuf_.data(), nin * sizeof(int16_t));
            int rem = modemInputPos_ - nin;
            if (rem > 0) {
                std::memmove(modemInputBuf_.data(), modemInputBuf_.data() + nin,
                             rem * sizeof(int16_t));
            }
            modemInputPos_ = rem;
        }
        processModemFrame(frame.data(), nin);
    }
}

void AudioEngine::recordModemSamples(const int16_t *samples8k, int count) {
    std::lock_guard<std::mutex> lk(modemWavMutex_);
    if (modemWavFile_ && samples8k && count > 0) {
        fwrite(samples8k, sizeof(int16_t), count, modemWavFile_);
        modemWavDataBytes_ += count * sizeof(int16_t);
        if ((modemWavDataBytes_ % 16000) < (uint32_t)(count * sizeof(int16_t))) {
            updatePcmWavHeader(modemWavFile_, modemWavDataBytes_);
            fflush(modemWavFile_);
        }
    }
}

void AudioEngine::processModemFrame(const int16_t *frame, int nin) {
    if (!rade_ || !frame || nin <= 0) return;

    int nFeat = rade_n_features_in_out(rade_);
    int nEoo = rade_n_eoo_bits(rade_);

    float frameRmsSum = 0.0f;
    std::vector<RADE_COMP> rxIn(nin);
    for (int i = 0; i < nin; i++) {
        float sample = (float)frame[i] / 32768.0f;
        frameRmsSum += sample * sample;
        rxIn[i].real = sample;
        rxIn[i].imag = 0.0f;
    }
    float modemLevelDb = 10.0f * log10f(frameRmsSum / (float)nin + 1e-10f);

    recordModemSamples(frame, nin);

    bool syncedBefore = rade_sync(rade_) != 0;
    static int lowGateLogCounter = 0;
    if (!syncedBefore && modemLevelDb < RX_SIGNAL_GATE_DB) {
        lowGateLogCounter++;
        if (lowGateLogCounter % 20 == 1) {
            LOGI("RX gate: skip search mdm=%.1fdB gate=%.1fdB",
                 modemLevelDb, RX_SIGNAL_GATE_DB);
        }
        return;
    }

    std::vector<float> features(nFeat);
    std::vector<float> eooBits(nEoo);
    int hasEoo = 0;

    int64_t t0 = monotonicNs();
    int result = rade_rx(rade_, features.data(), &hasEoo, eooBits.data(), rxIn.data());
    int64_t t1 = monotonicNs();

    // rade_sync() returns boolean: 1 = synced, 0 = not synced.
    // Map to app states: 0=SEARCH, 2=SYNC (no CANDIDATE from this API).
    int newSync = rade_sync(rade_) ? 2 : 0;
    int oldSync = syncState_.exchange(newSync);
    snrEstimate_.store(rade_snrdB_3k_est(rade_));
    freqOffset_.store(rade_freq_offset(rade_));

    if (newSync != oldSync) {
        LOGI("RX sync: %d->%d result=%d eoo=%d snr=%d foff=%.1f input=%.1fdB mdm=%.1fdB",
             oldSync, newSync, result, hasEoo, snrEstimate_.load(),
             freqOffset_.load(), inputLevelDb_.load(), modemLevelDb);
        if (callback_) callback_->onSyncStateChanged(newSync);
    }

    if (result > 0)
        synthesizeSpeech(features.data(), nFeat);
    int64_t t2 = monotonicNs();

    static int modemFrameLogCounter = 0;
    modemFrameLogCounter++;
    int rxMs = (int)((t1 - t0) / 1000000LL);
    int synthMs = (int)((t2 - t1) / 1000000LL);
    if (rxMs >= 5 || synthMs >= 5 || modemFrameLogCounter % 20 == 0) {
        LOGI("RX frame: nin=%d result=%d eoo=%d sync=%d mdm=%.1fdB rx=%dms synth=%dms ring=%d",
             nin, result, hasEoo, syncState_.load(), modemLevelDb, rxMs, synthMs,
             playbackRing_.availableToRead());
    }

    if (hasEoo) {
        char cs[32] = {0};
        if (eoo_callsign_decode(eooBits.data(), nEoo, cs, sizeof(cs) - 1)) {
            { std::lock_guard<std::mutex> lk(callsignMutex_); lastCallsign_ = cs; }
            if (callback_) callback_->onCallsignDecoded(cs);
            LOGI("Callsign: %s", cs);
        }
    }

}

void AudioEngine::synthesizeSpeech(const float *features, int nTotalFeatures) {
    if (!fargan_) return;

    // RADE outputs multiple frames per modem frame:
    //   nTotalFeatures = NZMF(3) × FRAMES_PER_STEP(4) × NB_TOTAL_FEATURES(36) = 432
    // Each frame has NB_TOTAL_FEATURES(36) floats, but FARGAN uses only NB_FEATURES(20).
    static const int NB_TOTAL = 36;  // RADE_NB_TOTAL_FEATURES / NB_TOTAL_FEATURES
    static const int NB_FEAT  = 20;  // NB_FEATURES — what FARGAN actually reads
    int nFrames = nTotalFeatures / NB_TOTAL;

    for (int f = 0; f < nFrames; f++) {
        const float *frameFeatures = features + f * NB_TOTAL;

        if (!farganReady_) {
            // Warmup: feed frames one by one via fargan_cont
            // fargan_cont expects NB_FEATURES-strided features for FARGAN_CONT_SAMPLES/FARGAN_FRAME_SIZE frames
            float silence[FARGAN_CONT_SAMPLES] = {0};
            fargan_cont(fargan_, silence, frameFeatures);
            farganWarmupCount_++;
            if (farganWarmupCount_ >= FARGAN_WARMUP_FRAMES) {
                farganReady_ = true;
                LOGI("FARGAN warmup complete after %d frames", farganWarmupCount_);
            }
            continue;
        }

        // Normal synthesis: one frame → 160 samples @ 16kHz
        int16_t pcm[FARGAN_FRAME_SIZE];
        fargan_synthesize_int(fargan_, pcm, frameFeatures);
        playbackRing_.write(pcm, FARGAN_FRAME_SIZE);

        // Write to WAV if recording
        {
            std::lock_guard<std::mutex> lk(wavMutex_);
            if (wavFile_) {
                fwrite(pcm, sizeof(int16_t), FARGAN_FRAME_SIZE, wavFile_);
                wavDataBytes_ += FARGAN_FRAME_SIZE * sizeof(int16_t);

                // Periodically flush and update WAV header so the file
                // is playable while still being recorded (~every 1 second)
                if ((wavDataBytes_ % 32000) < (FARGAN_FRAME_SIZE * sizeof(int16_t))) {
                    long pos = ftell(wavFile_);
                    uint32_t riffSize = wavDataBytes_ + 36;
                    fseek(wavFile_, 4, SEEK_SET);
                    fwrite(&riffSize, 4, 1, wavFile_);
                    fseek(wavFile_, 40, SEEK_SET);
                    fwrite(&wavDataBytes_, 4, 1, wavFile_);
                    fseek(wavFile_, pos, SEEK_SET);
                    fflush(wavFile_);
                }
            }
        }
    }
}

/* ── FFT (8kHz, 0-4kHz) ─────────────────────────────────────── */

void AudioEngine::computeFFT() {
    float windowed[FFT_SIZE];
    for (int i = 0; i < FFT_SIZE; i++) {
        float w = 0.5f * (1.0f - cosf(2.0f * (float)M_PI * (float)i / (float)(FFT_SIZE - 1)));
        windowed[i] = fftInput_[i] * w;
    }
    float tmp[FFT_BINS];
    for (int k = 0; k < FFT_BINS; k++) {
        float re = 0.0f, im = 0.0f;
        for (int n = 0; n < FFT_SIZE; n++) {
            float a = 2.0f * (float)M_PI * (float)k * (float)n / (float)FFT_SIZE;
            re += windowed[n] * cosf(a);
            im -= windowed[n] * sinf(a);
        }
        tmp[k] = 20.0f * log10f(sqrtf(re * re + im * im) + 1e-10f);
    }
    { std::lock_guard<std::mutex> lk(spectrumMutex_); std::memcpy(spectrumDb_, tmp, sizeof(tmp)); }
}

void AudioEngine::getSpectrum(float *out, int maxBins) {
    std::lock_guard<std::mutex> lk(spectrumMutex_);
    std::memcpy(out, spectrumDb_, std::min(maxBins, FFT_BINS) * (int)sizeof(float));
}

std::string AudioEngine::getLastCallsign() {
    std::lock_guard<std::mutex> lk(callsignMutex_);
    return lastCallsign_;
}

/* ── WAV recording ───────────────────────────────────────────── */

bool AudioEngine::startRecording(const char *path) {
    std::lock_guard<std::mutex> lk(wavMutex_);
    if (wavFile_) fclose(wavFile_);

    wavFile_ = fopen(path, "wb");
    if (!wavFile_) { LOGE("Cannot open WAV: %s", path); return false; }

    writePcmWavHeader(wavFile_, SPEECH_SAMPLE_RATE, 0);
    wavDataBytes_ = 0;

    LOGI("Recording started: %s", path);
    return true;
}

void AudioEngine::stopRecording() {
    std::lock_guard<std::mutex> lk(wavMutex_);
    if (!wavFile_) return;

    updatePcmWavHeader(wavFile_, wavDataBytes_);

    fclose(wavFile_);
    wavFile_ = nullptr;
    LOGI("Recording stopped: %u bytes", wavDataBytes_);
}

bool AudioEngine::startModemRecording(const char *path) {
    std::lock_guard<std::mutex> lk(modemWavMutex_);
    if (modemWavFile_) fclose(modemWavFile_);

    modemWavFile_ = fopen(path, "wb");
    if (!modemWavFile_) { LOGE("Cannot open modem WAV: %s", path); return false; }

    writePcmWavHeader(modemWavFile_, MODEM_SAMPLE_RATE, 0);
    modemWavDataBytes_ = 0;

    LOGI("Modem recording started: %s", path);
    return true;
}

void AudioEngine::stopModemRecording() {
    std::lock_guard<std::mutex> lk(modemWavMutex_);
    if (!modemWavFile_) return;

    updatePcmWavHeader(modemWavFile_, modemWavDataBytes_);
    fclose(modemWavFile_);
    modemWavFile_ = nullptr;
    LOGI("Modem recording stopped: %u bytes", modemWavDataBytes_);
}

/* ── Stream restart (called from error callbacks) ────────────── */

void AudioEngine::restartInputStream() {
    LOGI("Restarting input stream...");
    if (inputStream_) {
        inputStream_->close();
        inputStream_.reset();
    }
    // Brief delay to let the system release resources
    usleep(200000); // 200ms
    if (running_.load()) {
        if (openInputStream()) {
            LOGI("Input stream restarted successfully");
        } else {
            LOGE("Failed to restart input stream");
        }
    }
}

void AudioEngine::restartOutputStream() {
    LOGI("Restarting output stream...");
    if (outputStream_) {
        outputStream_->close();
        outputStream_.reset();
    }
    usleep(200000);
    if (running_.load()) {
        if (openOutputStream()) {
            LOGI("Output stream restarted successfully");
        } else {
            LOGE("Failed to restart output stream");
        }
    }
}

/* ── Modem ───────────────────────────────────────────────────── */

bool AudioEngine::initModem() {
    rade_initialize();
    rade_ = rade_open(nullptr, RADE_USE_C_DECODER);
    if (!rade_) { LOGE("rade_open failed"); return false; }
    LOGI("RADE opened v%d", rade_version());

    fargan_ = (FARGANState *)calloc(1, sizeof(FARGANState));
    if (!fargan_) { rade_close(rade_); rade_ = nullptr; return false; }
    fargan_init(fargan_);
    farganWarmupCount_ = 0;
    farganReady_ = false;
    LOGI("FARGAN init ok");
    return true;
}

void AudioEngine::releaseModem() {
    if (rade_) { rade_close(rade_); rade_ = nullptr; }
    if (fargan_) { free(fargan_); fargan_ = nullptr; }
    rade_finalize();
    farganReady_ = false;
    farganWarmupCount_ = 0;
}

/* ════════════════════════════════════════════════════════════════
 *  TX (Transmit) Pipeline
 *
 *  Mic (device rate) → decimate to 16kHz → LPCNet features (36 per 160 samples)
 *  → accumulate N feature frames → rade_tx() → RADE_COMP @ 8kHz
 *  → convert to real float → Oboe output (8kHz, Oboe SRC to device rate)
 * ════════════════════════════════════════════════════════════════ */

bool AudioEngine::initTxModem() {
    rade_initialize();
    rade_ = rade_open(nullptr, RADE_USE_C_ENCODER | RADE_USE_C_DECODER);
    if (!rade_) { LOGE("TX: rade_open failed"); return false; }

    int nFeatIn = rade_n_features_in_out(rade_);
    txFeaturesPerTx_ = nFeatIn / NB_TOTAL_FEATURES;
    LOGI("TX: rade opened, features_per_tx=%d (total floats=%d)", txFeaturesPerTx_, nFeatIn);

    lpcnetEnc_ = lpcnet_encoder_create();
    if (!lpcnetEnc_) { LOGE("TX: lpcnet_encoder_create failed"); releaseModem(); return false; }
    lpcnet_encoder_init(lpcnetEnc_);
    LOGI("TX: LPCNet encoder init ok");

    txFeatureAccum_.resize(nFeatIn, 0.0f);
    txFeatureFrames_ = 0;
    txSpeechBuf_.resize(TX_SPEECH_FRAME, 0);
    txSpeechPos_ = 0;

    return true;
}

void AudioEngine::releaseTxModem() {
    if (lpcnetEnc_) { lpcnet_encoder_destroy(lpcnetEnc_); lpcnetEnc_ = nullptr; }
    if (rade_) { rade_close(rade_); rade_ = nullptr; }
    rade_finalize();
    txFeatureAccum_.clear();
    txFeatureFrames_ = 0;
    txSpeechPos_ = 0;
}

bool AudioEngine::startTx(int inputDeviceId, int outputDeviceId) {
    if (txRunning_.load()) return true;
    if (running_.load()) stop();  // stop RX first

    txInputDeviceId_ = (inputDeviceId > 0) ? inputDeviceId : 0;
    txOutputDeviceId_ = (outputDeviceId > 0) ? outputDeviceId : 0;
    LOGI("TX: startTx inputDev=%d outputDev=%d", txInputDeviceId_, txOutputDeviceId_);

    if (!initTxModem()) return false;

    txPlaybackRing_.reset();
    txRunning_.store(true);

    // Start input stream FIRST so it begins capturing and processing audio
    // before the output stream starts consuming from the ring buffer.
    if (!openTxInputStream()) {
        txRunning_.store(false); releaseTxModem(); return false;
    }

    // Pre-fill ring buffer with silence-encoded modem frames to prevent
    // underruns at startup and absorb input jitter during TX.
    // Generate 3 frames (~360ms buffer) of silence through the encoder.
    {
        std::vector<int16_t> silence(TX_SPEECH_FRAME, 0);
        float features[NB_TOTAL_FEATURES];
        for (int prefill = 0; prefill < 3; prefill++) {
            for (int f = 0; f < txFeaturesPerTx_; f++) {
                lpcnet_compute_single_frame_features(lpcnetEnc_, silence.data(), features, 0);
                int offset = f * NB_TOTAL_FEATURES;
                memcpy(txFeatureAccum_.data() + offset, features, NB_TOTAL_FEATURES * sizeof(float));
            }
            int nTxOut = rade_n_tx_out(rade_);
            std::vector<RADE_COMP> txOut(nTxOut);
            int produced = rade_tx(rade_, txOut.data(), txFeatureAccum_.data());
            for (int i = 0; i < produced; i++) {
                float sample = std::clamp(txOut[i].real, -0.999f, 0.999f);
                int16_t s16 = (int16_t)(sample * 32767.0f);
                txPlaybackRing_.write(&s16, 1);
            }
        }
        LOGI("TX: pre-filled ring buffer with %d samples", txPlaybackRing_.availableToRead());
    }

    if (!openTxOutputStream()) {
        txRunning_.store(false);
        txInputStream_->stop(); txInputStream_->close(); txInputStream_.reset();
        releaseTxModem(); return false;
    }

    // Design decimation filter for speech input if needed
    if (txActualInputRate_ != SPEECH_SAMPLE_RATE) {
        txDecimFactor_ = txActualInputRate_ / SPEECH_SAMPLE_RATE;
        if (txDecimFactor_ < 1) txDecimFactor_ = 1;
        // Reuse the same FIR design as RX but for speech rate
        int totalTaps = DECIM_FIR_TAPS * txDecimFactor_;
        float fc = (float)SPEECH_SAMPLE_RATE / (2.0f * (float)txActualInputRate_);
        txDecimCoeffs_.resize(totalTaps);
        float sum = 0;
        int M = totalTaps - 1;
        for (int i = 0; i < totalTaps; i++) {
            float n = (float)i - (float)M / 2.0f;
            float h = (fabsf(n) < 1e-6f) ? 2.0f * fc :
                      sinf(2.0f * (float)M_PI * fc * n) / ((float)M_PI * n);
            float w = 0.35875f
                    - 0.48829f * cosf(2.0f * (float)M_PI * (float)i / (float)M)
                    + 0.14128f * cosf(4.0f * (float)M_PI * (float)i / (float)M)
                    - 0.01168f * cosf(6.0f * (float)M_PI * (float)i / (float)M);
            txDecimCoeffs_[i] = h * w;
            sum += txDecimCoeffs_[i];
        }
        for (int i = 0; i < totalTaps; i++) txDecimCoeffs_[i] /= sum;
        txDecimHistory_.resize(totalTaps, 0.0f);
        txDecimHistPos_ = 0;
        txDecimPhase_ = 0;
        LOGI("TX: decimation %dHz→%dHz factor=%d", txActualInputRate_, SPEECH_SAMPLE_RATE, txDecimFactor_);
    } else {
        txDecimFactor_ = 1;
        txDecimCoeffs_.clear();
        txDecimHistory_.clear();
    }

    LOGI("TX: started, input=%dHz output=%dHz outDev=%d",
         txActualInputRate_, txOutputRate_, txOutputDeviceId_);
    return true;
}

void AudioEngine::stopTx() {
    if (!txRunning_.load()) return;

    // Stop accepting new mic input
    if (txInputStream_) { txInputStream_->stop(); txInputStream_->close(); txInputStream_.reset(); }

    // Send EOO frame — writes encoded callsign into txPlaybackRing_
    sendTxEoo();

    // Wait for the output stream to drain the EOO data from the ring buffer.
    // Skip drain wait when using Java AudioTrack (pump handles draining).
    if (!txUseJavaOutput_) {
        int waitMs = 0;
        while (txPlaybackRing_.availableToRead() > 0 && waitMs < 2000) {
            usleep(10000);  // 10ms
            waitMs += 10;
        }
        LOGI("TX: EOO drain waited %dms, remaining=%d", waitMs, txPlaybackRing_.availableToRead());
    }

    txRunning_.store(false);
    if (txOutputStream_) { txOutputStream_->stop(); txOutputStream_->close(); txOutputStream_.reset(); }
    releaseTxModem();
    txNetMode_ = false;
    LOGI("TX: stopped");
}

void AudioEngine::setTxCallsign(const char *callsign) {
    std::lock_guard<std::mutex> lk(txCallsignMutex_);
    txCallsign_ = callsign ? callsign : "";
    LOGI("TX: callsign set to '%s'", txCallsign_.c_str());
}

void AudioEngine::setTxOutputDevice(int deviceId) {
    txOutputDeviceId_ = (deviceId > 0) ? deviceId : 0;
}

bool AudioEngine::openTxInputStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Mono)
           ->setDataCallback(txInputCb_.get())
           ->setErrorCallback(txInputCb_.get());

    if (txInputDeviceId_ > 0) {
        // Force built-in mic when USB audio is connected.
        // Try multiple strategies since Oboe may ignore setDeviceId with some presets.
        builder.setDeviceId(txInputDeviceId_);
        builder.setInputPreset(oboe::InputPreset::Generic);
        builder.setPerformanceMode(oboe::PerformanceMode::None);
    } else {
        builder.setInputPreset(oboe::InputPreset::Unprocessed);
    }

    auto result = builder.openStream(txInputStream_);
    if (result != oboe::Result::OK) {
        LOGE("TX: failed to open input: %s", oboe::convertToText(result));
        return false;
    }

    // Check if we got the requested device; if not, retry with Exclusive mode
    if (txInputDeviceId_ > 0 && txInputStream_->getDeviceId() != txInputDeviceId_) {
        LOGI("TX: input device mismatch: wanted %d got %d, retrying Exclusive",
             txInputDeviceId_, txInputStream_->getDeviceId());
        txInputStream_->close(); txInputStream_.reset();

        oboe::AudioStreamBuilder retry;
        retry.setDirection(oboe::Direction::Input)
             ->setPerformanceMode(oboe::PerformanceMode::None)
             ->setSharingMode(oboe::SharingMode::Exclusive)
             ->setFormat(oboe::AudioFormat::Float)
             ->setChannelCount(oboe::ChannelCount::Mono)
             ->setInputPreset(oboe::InputPreset::Unprocessed)
             ->setDeviceId(txInputDeviceId_)
             ->setDataCallback(txInputCb_.get())
             ->setErrorCallback(txInputCb_.get());

        result = retry.openStream(txInputStream_);
        if (result != oboe::Result::OK) {
            LOGE("TX: retry failed: %s", oboe::convertToText(result));
            return false;
        }
    }

    txActualInputRate_ = txInputStream_->getSampleRate();
    LOGI("TX: input rate=%d device=%d (wanted=%d)",
         txActualInputRate_, txInputStream_->getDeviceId(), txInputDeviceId_);

    result = txInputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("TX: failed to start input: %s", oboe::convertToText(result));
        txInputStream_->close(); txInputStream_.reset(); return false;
    }
    return true;
}

bool AudioEngine::openTxOutputStream() {
    if (txOutputDeviceId_ > 0) {
        // USB audio: skip Oboe, let Java AudioTrack handle output via readTxRingBuffer JNI
        LOGI("TX: USB output device %d — using Java AudioTrack (no Oboe output stream)", txOutputDeviceId_);
        txOutputRate_ = 8000;  // Java side will read at modem rate
        txUseJavaOutput_ = true;
        return true;
    }

    txUseJavaOutput_ = false;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           ->setSampleRate(MODEM_SAMPLE_RATE)
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
           ->setChannelCount(oboe::ChannelCount::Mono)
           ->setUsage(oboe::Usage::Media)
           ->setDataCallback(txOutputCb_.get())
           ->setErrorCallback(txOutputCb_.get());

    auto result = builder.openStream(txOutputStream_);
    if (result != oboe::Result::OK) {
        LOGE("TX: failed to open output: %s", oboe::convertToText(result));
        return false;
    }

    txOutputRate_ = txOutputStream_->getSampleRate();
    LOGI("TX: output rate=%d device=%d", txOutputRate_, txOutputStream_->getDeviceId());

    result = txOutputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("TX: failed to start output: %s", oboe::convertToText(result));
        txOutputStream_->close(); txOutputStream_.reset(); return false;
    }
    return true;
}

void AudioEngine::processTxInputFrames(const float *data, int32_t numFrames, int32_t channelCount) {
    float rmsSum = 0.0f;

    for (int i = 0; i < numFrames; i++) {
        float raw = data[i * channelCount];
        rmsSum += raw * raw;

        // Decimation to 16kHz (if input rate != 16kHz)
        if (txDecimFactor_ > 1) {
            int totalTaps = (int)txDecimCoeffs_.size();
            txDecimHistory_[txDecimHistPos_] = raw;
            txDecimHistPos_ = (txDecimHistPos_ + 1) % totalTaps;
            txDecimPhase_++;
            if (txDecimPhase_ < txDecimFactor_) continue;
            txDecimPhase_ = 0;

            float filtered = 0.0f;
            int idx = txDecimHistPos_;
            for (int j = 0; j < totalTaps; j++) {
                idx--;
                if (idx < 0) idx = totalTaps - 1;
                filtered += txDecimHistory_[idx] * txDecimCoeffs_[j];
            }
            raw = std::clamp(filtered, -0.999f, 0.999f);
        }

        // Now we have a 16kHz sample — feed to speech buffer
        int16_t s16 = (int16_t)(raw * 32767.0f);
        if (txSpeechPos_ < TX_SPEECH_FRAME) {
            txSpeechBuf_[txSpeechPos_++] = s16;
        }

        if (txSpeechPos_ >= TX_SPEECH_FRAME) {
            processTxFeatureFrame();
            txSpeechPos_ = 0;
        }
    }

    if (numFrames > 0)
        txInputLevelDb_.store(10.0f * log10f(rmsSum / (float)numFrames + 1e-10f));
}

void AudioEngine::processTxFeatureFrame() {
    if (!lpcnetEnc_ || !rade_) return;

    // Extract 36 features from 160 samples of 16kHz speech
    float features[NB_TOTAL_FEATURES];
    lpcnet_compute_single_frame_features(lpcnetEnc_, txSpeechBuf_.data(), features, 0);

    // Accumulate features for rade_tx()
    int offset = txFeatureFrames_ * NB_TOTAL_FEATURES;
    if (offset + NB_TOTAL_FEATURES <= (int)txFeatureAccum_.size()) {
        memcpy(txFeatureAccum_.data() + offset, features, NB_TOTAL_FEATURES * sizeof(float));
        txFeatureFrames_++;
    }

    if (txFeatureFrames_ >= txFeaturesPerTx_) {
        generateTxOutput();
        txFeatureFrames_ = 0;
    }
}

void AudioEngine::generateTxOutput() {
    if (!rade_) return;

    int nTxOut = rade_n_tx_out(rade_);
    std::vector<RADE_COMP> txOut(nTxOut);

    int produced = rade_tx(rade_, txOut.data(), txFeatureAccum_.data());
    if (produced <= 0) return;

    // Convert RADE_COMP (IQ) to real-valued int16 samples at 8kHz
    // Use only the real part for baseband audio output
    for (int i = 0; i < produced; i++) {
        float sample = std::clamp(txOut[i].real, -0.999f, 0.999f);
        int16_t s16 = (int16_t)(sample * 32767.0f);
        txPlaybackRing_.write(&s16, 1);
    }

    static int txLogCounter = 0;
    if (++txLogCounter % 10 == 0) {
        LOGI("TX: produced %d samples, ring=%d", produced, txPlaybackRing_.availableToRead());
    }
}

void AudioEngine::sendTxEoo() {
    if (!rade_) return;

    // Encode callsign into EOO bits
    {
        std::lock_guard<std::mutex> lk(txCallsignMutex_);
        if (!txCallsign_.empty()) {
            int nEoo = rade_n_eoo_bits(rade_);
            std::vector<float> eooBits(nEoo, 0.0f);
            eoo_callsign_encode(txCallsign_.c_str(), eooBits.data(), nEoo);
            rade_tx_set_eoo_bits(rade_, eooBits.data());
            LOGI("TX: EOO callsign='%s' encoded (%d bits)", txCallsign_.c_str(), nEoo);
        }
    }

    int nEooOut = rade_n_tx_eoo_out(rade_);
    std::vector<RADE_COMP> eooOut(nEooOut);
    int produced = rade_tx_eoo(rade_, eooOut.data());

    for (int i = 0; i < produced; i++) {
        float sample = std::clamp(eooOut[i].real, -0.999f, 0.999f);
        int16_t s16 = (int16_t)(sample * 32767.0f);
        txPlaybackRing_.write(&s16, 1);
    }

    LOGI("TX: EOO sent, %d samples", produced);
}

void AudioEngine::renderTxOutput(float *output, int32_t numFrames) {
    if (txOutputRate_ <= MODEM_SAMPLE_RATE) {
        // Direct: stream rate matches modem rate (8kHz, e.g. via Oboe SRC)
        int16_t tempBuf[4096];
        int toRead = std::min(numFrames, 4096);
        int got = txPlaybackRing_.read(tempBuf, toRead);
        for (int i = 0; i < numFrames; i++) {
            output[i] = (i < got) ? (float)tempBuf[i] / 32768.0f : 0.0f;
        }
    } else {
        // Upsample: stream rate > modem rate (e.g. 48kHz USB audio)
        // Read modem-rate samples and interpolate to output rate
        double ratio = (double)MODEM_SAMPLE_RATE / (double)txOutputRate_;
        int modemNeeded = (int)((double)numFrames * ratio) + 2;
        int16_t tempBuf[4096];
        int toRead = std::min(modemNeeded, 4096);
        int got = txPlaybackRing_.read(tempBuf, toRead);

        for (int i = 0; i < numFrames; i++) {
            double srcPos = (double)i * ratio;
            int idx = (int)srcPos;
            float frac = (float)(srcPos - (double)idx);

            if (idx >= got) {
                output[i] = 0.0f;
            } else if (idx + 1 < got) {
                float s0 = (float)tempBuf[idx] / 32768.0f;
                float s1 = (float)tempBuf[idx + 1] / 32768.0f;
                output[i] = s0 + frac * (s1 - s0);
            } else {
                output[i] = (float)tempBuf[idx] / 32768.0f;
            }
        }
    }
}

/* ════════════════════════════════════════════════════════════════
 *  Network audio (Icom RS-BA1 / IC-705 Wi-Fi) — Phase 2 "full wireless"
 *
 *  Same modem/vocoder pipeline as the USB path, but the rig-facing audio
 *  is carried over UDP (port 50003) by IcomNetworkManager instead of an
 *  Oboe stream to/from a USB sound card:
 *    RX: UDP PCM → feedNetRx() → decimate netRate→8k → modem → FARGAN
 *        → playbackRing_ → Oboe output (phone speaker). No Oboe input.
 *    TX: mic → Oboe input → LPCNet/RADE encoder → txPlaybackRing_ (8k),
 *        drained + upsampled to netRate by fillNetTxFrame(). No Oboe output.
 * ════════════════════════════════════════════════════════════════ */

bool AudioEngine::startNetRx(int outputDeviceId, int netRate) {
    if (running_.load() || netRxRunning_.load()) return true;
    outputDeviceId_ = (outputDeviceId > 0) ? outputDeviceId : 0;

    if (!initModem()) return false;

    modemInputBuf_.resize(MODEM_QUEUE_MAX_SAMPLES, 0);
    modemInputPos_ = 0;
    fftInputPos_ = 0;
    rxModemOut_.clear();
    modemQueueDropSamples_ = 0;
    playbackRing_.reset();

    // running_ drives renderOutput/synthesizeSpeech and sync callbacks; there is
    // simply no Oboe input stream — feedNetRx() is the sample source instead.
    running_.store(true);
    netRxRunning_.store(true);
    startModemWorker();

    if (!openOutputStream()) {
        running_.store(false); netRxRunning_.store(false);
        stopModemWorker();
        releaseModem();
        return false;
    }

    designDecimFilter(netRate, MODEM_SAMPLE_RATE);
    actualInputRate_ = netRate;

    LOGI("Net RX started: netRate=%dHz(÷%d) outDev=%d", netRate, decimFactor_, outputDeviceId_);
    return true;
}

void AudioEngine::feedNetRx(const int16_t *pcm, int count) {
    static int logCounter = 0;
    if (!netRxRunning_.load()) return;
    int totalTaps = (int)decimCoeffs_.size();
    if (totalTaps <= 0) return;

    // RS-BA1 network audio arrives near full-scale (the radio's digital line
    // level), far hotter than the Android mic/USB input the default input gain
    // was tuned for. Attenuate here so the modem sees a healthy level even with
    // the user's digital-gain slider at minimum; user gain still applies on top.
    float gain = inputGain_.load() * NET_RX_ATTEN;
    float rmsSum = 0.0f;
    float peakSample = 0.0f;
    int clipRiskCount = 0;
    std::vector<int16_t> modemOut;
    modemOut.reserve((int)(((int64_t)count * decimOutputRate_) / std::max(1, decimInputRate_)) + 2);

    for (int i = 0; i < count; i++) {
        float raw = ((float)pcm[i] / 32768.0f) * gain;
        rmsSum += raw * raw;
        float absRaw = fabsf(raw);
        if (absRaw > peakSample) peakSample = absRaw;
        if (absRaw >= 0.98f) clipRiskCount++;

        decimHistory_[decimHistPos_] = raw;
        decimHistPos_ = (decimHistPos_ + 1) % totalTaps;

        decimPhase_ += decimOutputRate_;
        if (decimPhase_ >= decimInputRate_) {
            decimPhase_ -= decimInputRate_;

            float filtered = 0.0f;
            int idx = decimHistPos_;
            for (int j = 0; j < totalTaps; j++) {
                idx--;
                if (idx < 0) idx = totalTaps - 1;
                filtered += decimHistory_[idx] * decimCoeffs_[j];
            }

            filtered = std::clamp(filtered, -0.999f, 0.999f);
            int16_t s16 = (int16_t)(filtered * 32767.0f);
            modemOut.push_back(s16);

            if (fftInputPos_ < FFT_SIZE) fftInput_[fftInputPos_++] = filtered;
            if (fftInputPos_ >= FFT_SIZE) { computeFFT(); fftInputPos_ = 0; }
        }
    }

    if (!modemOut.empty()) {
        feedModem(modemOut.data(), (int)modemOut.size());
    }

    if (count > 0)
        inputLevelDb_.store(10.0f * log10f(rmsSum / (float)count + 1e-10f));

    logCounter++;
    if (logCounter % 25 == 0 && count > 0) {
        float peakDb = 20.0f * log10f(peakSample + 1e-10f);
        float rmsDb = 10.0f * log10f(rmsSum / (float)count + 1e-10f);
        float clipPct = 100.0f * (float)clipRiskCount / (float)count;
        LOGI("Net RX: in=%d gain=%.2f peak=%.1fdB rms=%.1fdB clip=%.1f%% sync=%d snr=%d foff=%.1f",
             count, gain, peakDb, rmsDb, clipPct,
             syncState_.load(), snrEstimate_.load(), freqOffset_.load());
    }
}

bool AudioEngine::startNetTx(int inputDeviceId, int netRate) {
    if (txRunning_.load()) return true;
    if (running_.load()) stop();  // stop RX first

    txInputDeviceId_ = (inputDeviceId > 0) ? inputDeviceId : 0;
    txOutputDeviceId_ = 0;
    txNetMode_ = true;
    txUseJavaOutput_ = true;     // reuse: no Oboe output stream, skip drain wait
    txOutputRate_ = netRate;
    LOGI("Net TX: startNetTx inputDev=%d netRate=%d", txInputDeviceId_, netRate);

    if (!initTxModem()) { txNetMode_ = false; return false; }

    txPlaybackRing_.reset();
    txRunning_.store(true);

    if (!openTxInputStream()) {
        txRunning_.store(false); txNetMode_ = false; releaseTxModem(); return false;
    }

    // Build the anti-imaging interpolation filter now that txOutputRate_ (netRate)
    // is known. Must precede the first fillNetTxFrame() call.
    designNetTxInterpFilter(txOutputRate_ / MODEM_SAMPLE_RATE);

    // Pre-fill the ring with silence-encoded modem frames to absorb startup
    // jitter and the phone-vs-radio clock difference. Target ~200 ms so the
    // deadline-paced UDP TX pump (AudioService.startNetTxPump) never underruns
    // mid-over — an underrun forces zero-padding that corrupts the continuous
    // RADE waveform and makes the far end unable to decode.
    {
        std::vector<int16_t> silence(TX_SPEECH_FRAME, 0);
        float features[NB_TOTAL_FEATURES];
        const int prefillTarget = MODEM_SAMPLE_RATE / 5;  // ~200 ms @ 8 kHz
        int prefillGuard = 0;
        while (txPlaybackRing_.availableToRead() < prefillTarget && prefillGuard++ < 64) {
            for (int f = 0; f < txFeaturesPerTx_; f++) {
                lpcnet_compute_single_frame_features(lpcnetEnc_, silence.data(), features, 0);
                int offset = f * NB_TOTAL_FEATURES;
                memcpy(txFeatureAccum_.data() + offset, features, NB_TOTAL_FEATURES * sizeof(float));
            }
            int nTxOut = rade_n_tx_out(rade_);
            std::vector<RADE_COMP> txOut(nTxOut);
            int produced = rade_tx(rade_, txOut.data(), txFeatureAccum_.data());
            for (int i = 0; i < produced; i++) {
                float sample = std::clamp(txOut[i].real, -0.999f, 0.999f);
                int16_t s16 = (int16_t)(sample * 32767.0f);
                txPlaybackRing_.write(&s16, 1);
            }
        }
        LOGI("Net TX: pre-filled ring with %d samples", txPlaybackRing_.availableToRead());
    }

    // Design mic-rate → 16kHz decimation if needed (same as USB TX path).
    if (txActualInputRate_ != SPEECH_SAMPLE_RATE) {
        txDecimFactor_ = txActualInputRate_ / SPEECH_SAMPLE_RATE;
        if (txDecimFactor_ < 1) txDecimFactor_ = 1;
        int totalTaps = DECIM_FIR_TAPS * txDecimFactor_;
        float fc = (float)SPEECH_SAMPLE_RATE / (2.0f * (float)txActualInputRate_);
        txDecimCoeffs_.resize(totalTaps);
        float sum = 0;
        int M = totalTaps - 1;
        for (int i = 0; i < totalTaps; i++) {
            float n = (float)i - (float)M / 2.0f;
            float h = (fabsf(n) < 1e-6f) ? 2.0f * fc :
                      sinf(2.0f * (float)M_PI * fc * n) / ((float)M_PI * n);
            float w = 0.35875f
                    - 0.48829f * cosf(2.0f * (float)M_PI * (float)i / (float)M)
                    + 0.14128f * cosf(4.0f * (float)M_PI * (float)i / (float)M)
                    - 0.01168f * cosf(6.0f * (float)M_PI * (float)i / (float)M);
            txDecimCoeffs_[i] = h * w;
            sum += txDecimCoeffs_[i];
        }
        for (int i = 0; i < totalTaps; i++) txDecimCoeffs_[i] /= sum;
        txDecimHistory_.resize(totalTaps, 0.0f);
        txDecimHistPos_ = 0;
        txDecimPhase_ = 0;
        LOGI("Net TX: decimation %dHz→%dHz factor=%d", txActualInputRate_, SPEECH_SAMPLE_RATE, txDecimFactor_);
    } else {
        txDecimFactor_ = 1;
        txDecimCoeffs_.clear();
        txDecimHistory_.clear();
    }

    LOGI("Net TX: started, input=%dHz netRate=%d", txActualInputRate_, txOutputRate_);
    return true;
}

/**
 * Design the polyphase anti-imaging FIR for 8 kHz → netRate network TX audio.
 * Windowed-sinc low-pass prototype at the output rate, decomposed into
 * `interpFactor` phases each normalized to unity sum (flat unity passband).
 */
void AudioEngine::designNetTxInterpFilter(int interpFactor) {
    netTxInterpL_ = (interpFactor >= 1) ? interpFactor : 1;
    int tpp = NET_TX_INTERP_TAPS;
    netTxTapsPerPhase_ = tpp;
    int totalTaps = tpp * netTxInterpL_;

    // Cutoff 3.5 kHz: passes the full RADE/SSB passband flat while rejecting the
    // upsampling images that begin at 4 kHz (the 8 kHz Nyquist).
    float fcNorm = 3500.0f / (float)(MODEM_SAMPLE_RATE * netTxInterpL_);
    std::vector<float> proto(totalTaps);
    int M = totalTaps - 1;
    for (int i = 0; i < totalTaps; i++) {
        float n = (float)i - (float)M / 2.0f;
        float h = (fabsf(n) < 1e-6f) ? 2.0f * fcNorm
                  : sinf(2.0f * (float)M_PI * fcNorm * n) / ((float)M_PI * n);
        float w = 0.35875f
                - 0.48829f * cosf(2.0f * (float)M_PI * (float)i / (float)M)
                + 0.14128f * cosf(4.0f * (float)M_PI * (float)i / (float)M)
                - 0.01168f * cosf(6.0f * (float)M_PI * (float)i / (float)M);
        proto[i] = h * w;
    }

    netTxInterpPhases_.assign(netTxInterpL_, std::vector<float>(tpp, 0.0f));
    for (int p = 0; p < netTxInterpL_; p++) {
        float sum = 0.0f;
        for (int k = 0; k < tpp; k++) {
            int idx = k * netTxInterpL_ + p;
            float c = (idx < totalTaps) ? proto[idx] : 0.0f;
            netTxInterpPhases_[p][k] = c;
            sum += c;
        }
        if (fabsf(sum) > 1e-9f)
            for (int k = 0; k < tpp; k++) netTxInterpPhases_[p][k] /= sum;
    }
    netTxInterpHist_.assign(tpp, 0.0f);
    netTxInterpHistPos_ = 0;
    LOGI("Net TX interp: 8kHz->%dHz L=%d tapsPerPhase=%d", txOutputRate_, netTxInterpL_, tpp);
}

int AudioEngine::fillNetTxFrame(int16_t *out, int numSamples) {
    if (numSamples <= 0) return 0;
    int L = netTxInterpL_;
    int tpp = netTxTapsPerPhase_;
    if (L < 1 || tpp <= 0 || (int)netTxInterpPhases_.size() != L) {
        // Filter not initialized — emit silence rather than garbage.
        for (int i = 0; i < numSamples; i++) out[i] = 0;
        return numSamples;
    }

    // Integer polyphase interpolation: each 8 kHz input sample yields L outputs.
    // History is retained across calls, so 20 ms frames join seamlessly (the old
    // linear path both drooped the passband AND dropped ~2 samples per frame).
    int inNeeded = numSamples / L;
    int16_t in[1024];
    if (inNeeded > 1024) inNeeded = 1024;
    int got = txPlaybackRing_.read(in, inNeeded);

    int outPos = 0;
    for (int n = 0; n < inNeeded; n++) {
        // Zero-pad on underrun so the radio still receives a continuous stream.
        float xs = (n < got) ? (float)in[n] : 0.0f;
        netTxInterpHist_[netTxInterpHistPos_] = xs;
        netTxInterpHistPos_ = (netTxInterpHistPos_ + 1) % tpp;

        for (int p = 0; p < L && outPos < numSamples; p++) {
            const float *hp = netTxInterpPhases_[p].data();
            float acc = 0.0f;
            int idx = netTxInterpHistPos_;
            for (int k = 0; k < tpp; k++) {
                idx--; if (idx < 0) idx = tpp - 1;
                acc += netTxInterpHist_[idx] * hp[k];
            }
            acc = std::clamp(acc, -32767.0f, 32767.0f);
            out[outPos++] = (int16_t)acc;
        }
    }
    while (outPos < numSamples) out[outPos++] = 0;
    return numSamples;
}
