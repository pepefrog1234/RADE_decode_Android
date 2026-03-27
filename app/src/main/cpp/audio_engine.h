/*
 * audio_engine.h - Core audio engine using Oboe for RADE decode.
 *
 * Captures at device native rate (typically 48kHz), downsamples to 8kHz
 * using polyphase FIR decimation (matching iOS AVAudioConverter quality).
 * Output at 16kHz via Oboe SRC.
 */

#ifndef AUDIO_ENGINE_H
#define AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstdio>

#include "audio_ring_buffer.h"

extern "C" {
#include "rade_api.h"
#include "fargan.h"
#include "eoo_callsign_codec_c.h"
}

constexpr int MODEM_SAMPLE_RATE   = 8000;
constexpr int SPEECH_SAMPLE_RATE  = 16000;

constexpr int FFT_SIZE            = 1024;
constexpr int FFT_BINS            = FFT_SIZE / 2;
constexpr int FARGAN_WARMUP_FRAMES = 5;
constexpr int RING_BUFFER_SIZE    = 32000;

/* Polyphase FIR decimation filter */
constexpr int DECIM_FIR_TAPS      = 48;   // taps per phase

struct AudioEngineCallback {
    virtual ~AudioEngineCallback() = default;
    virtual void onSyncStateChanged(int state) {}
    virtual void onCallsignDecoded(const char *callsign) {}
};

class InputCallback;
class OutputCallback;

class AudioEngine {
    friend class InputCallback;
    friend class OutputCallback;

public:
    AudioEngine();
    ~AudioEngine();

    bool start(int inputDeviceId = 0);
    void stop();
    bool isRunning() const { return running_.load(); }

    void setInputDevice(int deviceId);
    void setOutputVolume(float volume);
    void setInputGain(float gain);
    float getInputGain() const { return inputGain_.load(); }

    int getSyncState() const { return syncState_.load(); }
    int getSnrEstimate() const { return snrEstimate_.load(); }
    float getFreqOffset() const { return freqOffset_.load(); }
    float getInputLevel() const { return inputLevelDb_.load(); }
    float getOutputLevel() const { return outputLevelDb_.load(); }

    void getSpectrum(float *out, int maxBins);
    std::string getLastCallsign();
    void setCallback(AudioEngineCallback *cb) { callback_ = cb; }

    /** Start recording decoded 16kHz speech to WAV file. */
    bool startRecording(const char *path);
    /** Stop recording and finalize WAV header. */
    void stopRecording();

private:
    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::shared_ptr<oboe::AudioStream> outputStream_;
    std::unique_ptr<InputCallback> inputCb_;
    std::unique_ptr<OutputCallback> outputCb_;

    int inputDeviceId_ = 0;
    std::atomic<bool> running_{false};

    int actualInputRate_ = 48000;
    int decimFactor_ = 6;   // 48000/8000

    struct rade *rade_ = nullptr;
    FARGANState *fargan_ = nullptr;
    int farganWarmupCount_ = 0;
    bool farganReady_ = false;

    AudioRingBuffer playbackRing_{RING_BUFFER_SIZE};

    /* Decimation FIR filter state */
    std::vector<float> decimCoeffs_;   // FIR coefficients
    std::vector<float> decimHistory_;  // circular buffer
    int decimHistPos_ = 0;
    int decimPhase_ = 0;               // counts samples mod decimFactor_

    std::vector<int16_t> modemInputBuf_;
    int modemInputPos_ = 0;

    std::vector<float> fftInput_;
    int fftInputPos_ = 0;
    float spectrumDb_[FFT_BINS];
    std::mutex spectrumMutex_;

    std::atomic<float> inputLevelDb_{-100.0f};
    std::atomic<float> outputLevelDb_{-100.0f};
    std::atomic<float> outputVolume_{1.0f};
    std::atomic<float> inputGain_{4.0f};   // compensate Android mic low gain vs iOS

    std::atomic<int> syncState_{0};
    std::atomic<int> snrEstimate_{0};
    std::atomic<float> freqOffset_{0.0f};

    std::string lastCallsign_;
    std::mutex callsignMutex_;
    AudioEngineCallback *callback_ = nullptr;

    /* WAV recording */
    FILE *wavFile_ = nullptr;
    uint32_t wavDataBytes_ = 0;
    std::mutex wavMutex_;

    bool initModem();
    void releaseModem();
    void designDecimFilter(int inputRate, int outputRate);

    void processInputFrames(const float *data, int32_t numFrames, int32_t channelCount);
    void feedModem(const int16_t *samples8k, int count);
    void processModemFrame();
    void synthesizeSpeech(const float *features, int nFeatures);
    void computeFFT();
    void renderOutput(float *data, int32_t numFrames);

    bool openInputStream();
    bool openOutputStream();
};

class InputCallback : public oboe::AudioStreamDataCallback {
public:
    explicit InputCallback(AudioEngine *e) : engine_(e) {}
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *d, int32_t n) override;
private:
    AudioEngine *engine_;
};

class OutputCallback : public oboe::AudioStreamDataCallback {
public:
    explicit OutputCallback(AudioEngine *e) : engine_(e) {}
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *d, int32_t n) override;
private:
    AudioEngine *engine_;
};

#endif
