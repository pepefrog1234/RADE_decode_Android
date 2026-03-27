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

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ── Oboe callbacks ──────────────────────────────────────────── */

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

/* ── Constructor / Destructor ──────────────────────────────── */

AudioEngine::AudioEngine() {
    fftInput_.resize(FFT_SIZE, 0.0f);
    std::fill(spectrumDb_, spectrumDb_ + FFT_BINS, -100.0f);
    inputCb_ = std::make_unique<InputCallback>(this);
    outputCb_ = std::make_unique<OutputCallback>(this);
}

AudioEngine::~AudioEngine() { stop(); }

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
           ->setInputPreset(oboe::InputPreset::VoiceRecognition)  // higher gain than Unprocessed; no echo cancel
           ->setDataCallback(inputCb_.get());
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
           ->setDataCallback(outputCb_.get());

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

    int newSync = rade_sync(rade_);
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
