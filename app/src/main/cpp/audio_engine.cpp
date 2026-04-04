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

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
    decimFactor_ = inputRate / outputRate;
    if (decimFactor_ < 1) decimFactor_ = 1;

    // Design low-pass FIR: cutoff = outputRate/2, transition band to outputRate*0.6
    // Total taps = DECIM_FIR_TAPS * decimFactor for the full filter
    int totalTaps = DECIM_FIR_TAPS * decimFactor_;
    float fc = (float)outputRate / (2.0f * (float)inputRate);  // normalized cutoff

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

    LOGI("Decimation filter: %dHz→%dHz factor=%d taps=%d", inputRate, outputRate, decimFactor_, totalTaps);
}

/* ── Start / Stop ──────────────────────────────────────────── */

bool AudioEngine::start(int inputDeviceId) {
    if (running_.load()) return true;
    inputDeviceId_ = (inputDeviceId > 0) ? inputDeviceId : 0;

    if (!initModem()) return false;

    int ninMax = rade_nin_max(rade_);
    modemInputBuf_.resize(ninMax * 2, 0);
    modemInputPos_ = 0;
    fftInputPos_ = 0;
    playbackRing_.reset();

    running_.store(true);

    if (!openOutputStream()) {
        running_.store(false); releaseModem(); return false;
    }
    if (!openInputStream()) {
        running_.store(false);
        outputStream_->stop(); outputStream_->close(); outputStream_.reset();
        releaseModem(); return false;
    }

    // Design decimation filter based on actual input rate
    designDecimFilter(actualInputRate_, MODEM_SAMPLE_RATE);

    LOGI("Audio engine started: in=%dHz(÷%d) out=16kHz device=%d",
         actualInputRate_, decimFactor_, inputDeviceId_);
    return true;
}

void AudioEngine::stop() {
    running_.store(false);
    if (inputStream_)  { inputStream_->stop();  inputStream_->close();  inputStream_.reset(); }
    if (outputStream_) { outputStream_->stop(); outputStream_->close(); outputStream_.reset(); }
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
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Mono)
           // Unprocessed: bypass all platform audio processing (AGC, noise
           // suppression, echo cancellation). Critical for modem signal
           // integrity — some phone models apply aggressive filtering that
           // destroys OFDM pilot correlation and prevents sync.
           ->setInputPreset(oboe::InputPreset::Unprocessed)
           ->setDataCallback(inputCb_.get())
           ->setErrorCallback(inputCb_.get());
    // Do NOT set sample rate — capture at device native rate for best quality

    if (inputDeviceId_ > 0) builder.setDeviceId(inputDeviceId_);

    auto result = builder.openStream(inputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input: %s", oboe::convertToText(result));
        return false;
    }

    actualInputRate_ = inputStream_->getSampleRate();
    LOGI("Input: rate=%d ch=%d device=%d preset=%d",
         actualInputRate_, inputStream_->getChannelCount(),
         inputStream_->getDeviceId(), (int)inputStream_->getInputPreset());

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

    auto result = builder.openStream(outputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output: %s", oboe::convertToText(result));
        return false;
    }

    LOGI("Output: rate=%d ch=%d device=%d",
         outputStream_->getSampleRate(), outputStream_->getChannelCount(),
         outputStream_->getDeviceId());

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
    static int logCounter = 0;
    float rmsSum = 0.0f;
    float peakSample = 0.0f;
    float gain = inputGain_.load();
    int totalTaps = (int)decimCoeffs_.size();

    for (int i = 0; i < numFrames; i++) {
        float raw = data[i * channelCount] * gain;
        rmsSum += raw * raw;
        float absRaw = fabsf(raw);
        if (absRaw > peakSample) peakSample = absRaw;

        // Push into FIR history (circular buffer)
        decimHistory_[decimHistPos_] = raw;
        decimHistPos_ = (decimHistPos_ + 1) % totalTaps;

        // Decimate: output one sample every decimFactor_ input samples
        decimPhase_++;
        if (decimPhase_ >= decimFactor_) {
            decimPhase_ = 0;

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
            feedModem(&s16, 1);

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

    if (numFrames > 0)
        inputLevelDb_.store(10.0f * log10f(rmsSum / (float)numFrames + 1e-10f));

    logCounter++;
    if (logCounter % 8 == 0) {
        float peakDb = 20.0f * log10f(peakSample + 1e-10f);
        float rmsDb = 10.0f * log10f(rmsSum / (float)numFrames + 1e-10f);
        LOGI("RX: in=%d gain=%.1f peak=%.1fdB rms=%.1fdB sync=%d snr=%d foff=%.1f ring=%d",
             numFrames, gain, peakDb, rmsDb,
             syncState_.load(), snrEstimate_.load(), freqOffset_.load(),
             playbackRing_.availableToRead());
    }
}

void AudioEngine::feedModem(const int16_t *samples8k, int count) {
    for (int i = 0; i < count; i++) {
        if (modemInputPos_ < (int)modemInputBuf_.size()) {
            modemInputBuf_[modemInputPos_++] = samples8k[i];
        }
        int nin = rade_nin(rade_);
        if (modemInputPos_ >= nin) {
            processModemFrame();
        }
    }
}

void AudioEngine::processModemFrame() {
    if (!rade_) return;

    int nin = rade_nin(rade_);
    int nFeat = rade_n_features_in_out(rade_);
    int nEoo = rade_n_eoo_bits(rade_);

    std::vector<RADE_COMP> rxIn(nin);
    for (int i = 0; i < nin; i++) {
        rxIn[i].real = (float)modemInputBuf_[i] / 32768.0f;
        rxIn[i].imag = 0.0f;
    }

    std::vector<float> features(nFeat);
    std::vector<float> eooBits(nEoo);
    int hasEoo = 0;

    int result = rade_rx(rade_, features.data(), &hasEoo, eooBits.data(), rxIn.data());

    // rade_sync() returns boolean: 1 = synced, 0 = not synced.
    // Map to app states: 0=SEARCH, 2=SYNC (no CANDIDATE from this API).
    int newSync = rade_sync(rade_) ? 2 : 0;
    int oldSync = syncState_.exchange(newSync);
    snrEstimate_.store(rade_snrdB_3k_est(rade_));
    freqOffset_.store(rade_freq_offset(rade_));

    if (newSync != oldSync && callback_)
        callback_->onSyncStateChanged(newSync);

    if (result > 0)
        synthesizeSpeech(features.data(), nFeat);

    if (hasEoo) {
        char cs[32] = {0};
        if (eoo_callsign_decode(eooBits.data(), nEoo, cs, sizeof(cs) - 1)) {
            { std::lock_guard<std::mutex> lk(callsignMutex_); lastCallsign_ = cs; }
            if (callback_) callback_->onCallsignDecoded(cs);
            LOGI("Callsign: %s", cs);
        }
    }

    int rem = modemInputPos_ - nin;
    if (rem > 0) std::memmove(modemInputBuf_.data(), modemInputBuf_.data() + nin, rem * sizeof(int16_t));
    modemInputPos_ = rem;
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

    // Write placeholder header (44 bytes)
    uint8_t header[44] = {0};
    memcpy(header, "RIFF", 4);
    memcpy(header + 8, "WAVE", 4);
    memcpy(header + 12, "fmt ", 4);
    uint32_t fmtSize = 16;         memcpy(header + 16, &fmtSize, 4);
    uint16_t pcmFmt = 1;           memcpy(header + 20, &pcmFmt, 2);
    uint16_t channels = 1;         memcpy(header + 22, &channels, 2);
    uint32_t sampleRate = 16000;   memcpy(header + 24, &sampleRate, 4);
    uint32_t byteRate = 32000;     memcpy(header + 28, &byteRate, 4);
    uint16_t blockAlign = 2;       memcpy(header + 32, &blockAlign, 2);
    uint16_t bitsPerSample = 16;   memcpy(header + 34, &bitsPerSample, 2);
    memcpy(header + 36, "data", 4);
    fwrite(header, 1, 44, wavFile_);
    wavDataBytes_ = 0;

    LOGI("Recording started: %s", path);
    return true;
}

void AudioEngine::stopRecording() {
    std::lock_guard<std::mutex> lk(wavMutex_);
    if (!wavFile_) return;

    // Update RIFF and data sizes
    uint32_t riffSize = wavDataBytes_ + 36;
    fseek(wavFile_, 4, SEEK_SET);
    fwrite(&riffSize, 4, 1, wavFile_);
    fseek(wavFile_, 40, SEEK_SET);
    fwrite(&wavDataBytes_, 4, 1, wavFile_);

    fclose(wavFile_);
    wavFile_ = nullptr;
    LOGI("Recording stopped: %u bytes", wavDataBytes_);
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
            designDecimFilter(actualInputRate_, MODEM_SAMPLE_RATE);
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

    // Wait for the output stream to drain the EOO data from the ring buffer
    // (8kHz output ≈ 125ms per 1000 samples; EOO frame is typically ~2000-4000 samples)
    int waitMs = 0;
    while (txPlaybackRing_.availableToRead() > 0 && waitMs < 2000) {
        usleep(10000);  // 10ms
        waitMs += 10;
    }
    LOGI("TX: EOO drain waited %dms, remaining=%d", waitMs, txPlaybackRing_.availableToRead());

    txRunning_.store(false);
    if (txOutputStream_) { txOutputStream_->stop(); txOutputStream_->close(); txOutputStream_.reset(); }
    releaseTxModem();
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
           // Unprocessed for TX too: RADE vocoder encodes raw speech features,
           // platform noise suppression can remove spectral content needed by
           // LPCNet and degrade decoded audio quality on the other end.
           ->setInputPreset(oboe::InputPreset::Unprocessed)
           ->setDataCallback(txInputCb_.get())
           ->setErrorCallback(txInputCb_.get());

    if (txInputDeviceId_ > 0) builder.setDeviceId(txInputDeviceId_);

    auto result = builder.openStream(txInputStream_);
    if (result != oboe::Result::OK) {
        LOGE("TX: failed to open input: %s", oboe::convertToText(result));
        return false;
    }

    txActualInputRate_ = txInputStream_->getSampleRate();
    LOGI("TX: input rate=%d device=%d", txActualInputRate_, txInputStream_->getDeviceId());

    result = txInputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("TX: failed to start input: %s", oboe::convertToText(result));
        txInputStream_->close(); txInputStream_.reset(); return false;
    }
    return true;
}

bool AudioEngine::openTxOutputStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           ->setSampleRate(MODEM_SAMPLE_RATE)  // 8kHz — Oboe SRC to device rate
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
           ->setChannelCount(oboe::ChannelCount::Mono)
           ->setUsage(oboe::Usage::Media)
           ->setDataCallback(txOutputCb_.get())
           ->setErrorCallback(txOutputCb_.get());

    if (txOutputDeviceId_ > 0) builder.setDeviceId(txOutputDeviceId_);

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
    int16_t tempBuf[4096];
    int toRead = std::min(numFrames, 4096);
    int got = txPlaybackRing_.read(tempBuf, toRead);

    for (int i = 0; i < numFrames; i++) {
        output[i] = (i < got) ? (float)tempBuf[i] / 32768.0f : 0.0f;
    }
}
