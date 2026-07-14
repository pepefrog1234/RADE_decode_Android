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
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include <cstdio>

#include "audio_ring_buffer.h"

extern "C" {
#include "rade_api.h"
#include "fargan.h"
#include "lpcnet.h"
#include "eoo_callsign_codec_c.h"
}

constexpr int INPUT_SAMPLE_RATE   = 48000;
constexpr int MODEM_SAMPLE_RATE   = 8000;
constexpr int SPEECH_SAMPLE_RATE  = 16000;

// Attenuation applied to RS-BA1 network RX audio (Icom IC-705 over UDP 50003).
// The radio sends near-full-scale digital audio — far hotter than the quiet
// Android mic / USB line input the input-gain default targets — so without this
// the modem input clips even with the user's digital-gain slider at minimum.
// ~-16.5 dB. Only the network RX path uses it; USB RX is unaffected. The user's
// digital gain still multiplies on top.
constexpr float NET_RX_ATTEN = 0.15f;

// Taps per phase for the network-TX polyphase anti-imaging interpolator
// (8 kHz modem → 48 kHz UDP audio, integer factor 6). Linear interpolation
// droops ~4 dB by 3 kHz and rejects images poorly — it sounds fine but distorts
// the RADE constellation so the far end can't decode. The USB TX path avoids
// this only because Android's own resampler does the 8→48 kHz conversion.
constexpr int NET_TX_INTERP_TAPS = 24;

constexpr int FFT_SIZE            = 1024;
constexpr int FFT_BINS            = FFT_SIZE / 2;
constexpr int FARGAN_WARMUP_FRAMES = 5;
constexpr int RING_BUFFER_SIZE    = 32000;
constexpr int TX_SPEECH_FRAME     = 160;   // 10ms @ 16kHz (== LPCNET_FRAME_SIZE)
constexpr int INTERP_FIR_TAPS     = 48;    // taps per phase for interpolation

/* Polyphase FIR decimation filter */
constexpr int DECIM_FIR_TAPS      = 48;   // taps per phase

struct AudioEngineCallback {
    virtual ~AudioEngineCallback() = default;
    virtual void onSyncStateChanged(int state) {}
    virtual void onCallsignDecoded(const char *callsign) {}
};

class InputCallback;
class OutputCallback;
class TxInputCallback;
class TxOutputCallback;

class AudioEngine {
    friend class InputCallback;
    friend class OutputCallback;
    friend class TxInputCallback;
    friend class TxOutputCallback;

public:
    AudioEngine();
    ~AudioEngine();

    /* RX mode */
    bool start(int inputDeviceId = 0, int outputDeviceId = 0,
               bool voiceCommunicationOutput = false,
               bool bleAudioOutput = false);
    void stop();
    bool isRunning() const { return running_.load(); }
    /* Half-duplex pause: close the RX mic input but KEEP the RX output stream
     * (and its Bluetooth LE Audio / LC3 media route) open, so a local-monitoring
     * TX can run without tearing the media route down. */
    bool pauseRxInput();
    bool resumeRxInput();

    /* TX mode. keepRxAlive = local LC3 monitoring with no rig: capture the mic
     * for the level meter only, open no modem/output, and never touch the RX
     * output stream (the caller pauses/resumes RX input around this).
     * voiceCommunicationInput = capture through the Bluetooth communication
     * route (LE Audio / LC3 headset mic): use the VOICE_COMMUNICATION preset and
     * follow the communication device instead of pinning a device id.
     * voiceRecognitionInput = an LC3 headset is connected but the TX mic is NOT
     * it (built-in/USB mic): use VOICE_RECOGNITION instead of Generic/Unprocessed
     * so the mic-open doesn't drag the headset's LC3 group into the failing
     * "LIVE" reconfiguration (multi-second open stall + silence after TX). */
    bool startTx(int inputDeviceId, int outputDeviceId, bool keepRxAlive = false,
                 bool voiceCommunicationInput = false,
                 bool voiceRecognitionInput = false);
    void stopTx(bool drainEoo = true);
    bool isTxRunning() const { return txRunning_.load(); }
    void setTxCallsign(const char *callsign);
    void setTxOutputDevice(int deviceId);
    float getTxLevel() const { return txInputLevelDb_.load(); }
    int readTxRing(int16_t *buf, int maxSamples) { return txPlaybackRing_.read(buf, maxSamples); }
    /** Samples still queued for TX playback (used to drain the EOO after stopTx). */
    int txRingAvailable() { return txPlaybackRing_.availableToRead(); }
    bool isTxUsingJavaOutput() const { return txUseJavaOutput_; }
    int readRxRing(int16_t *buf, int maxSamples) { return playbackRing_.read(buf, maxSamples); }
    void setRxJavaOutputEnabled(bool enabled);
    bool isRxUsingJavaOutput() const { return rxUseJavaOutput_; }
    void setRxVoiceCommunicationOutputEnabled(bool enabled);

    /* ── Network audio (Icom RS-BA1 / IC-705 Wi-Fi) ──────────────
     * Same DSP pipeline as the USB/Oboe path, but the rig-facing audio
     * travels over UDP instead of an Oboe input/output stream:
     *   RX: feedNetRx() pushes received PCM into the modem (no Oboe input)
     *   TX: mic → encoder → txPlaybackRing_, drained via readTxRing()
     *       (reuses the existing txUseJavaOutput_ path; no Oboe output) */
    bool startNetRx(int outputDeviceId, int netRate);
    void feedNetRx(const int16_t *pcm, int count);
    bool startNetTx(int inputDeviceId, int netRate, bool voiceCommunicationInput = false);
    /** Read one TX frame upsampled to the network rate (zero-padded on underrun). */
    int fillNetTxFrame(int16_t *out, int numSamples);
    bool isNetRx() const { return netRxRunning_.load(); }

    void setInputDevice(int deviceId);
    void setOutputDevice(int deviceId);
    void setDevices(int inputDeviceId, int outputDeviceId);
    void setOutputVolume(float volume);
    void setInputGain(float gain);
    float getInputGain() const { return inputGain_.load(); }
    /* TX mic gain: applied to the raw mic samples before feature extraction.
     * Android mic capture is quiet (the RX side compensates the same hardware
     * with inputGain_ 4x); without this the FARGAN speech decoded at the far
     * end is reported as under-modulated. */
    void setTxMicGain(float gain);
    float getTxMicGain() const { return txMicGain_.load(); }

    int getSyncState() const { return syncState_.load(); }
    int getSnrEstimate() const { return snrEstimate_.load(); }
    float getFreqOffset() const { return freqOffset_.load(); }
    bool isUnprocessedRejected() const { return unprocessedRejected_.load(); }

    /** Select the RADE V2 waveform (experimental) for subsequent modem opens.
     *  Takes effect on the next RX/TX start. V1 and V2 are not interoperable
     *  on the air; on V2 the EOO frame carries no callsign payload. */
    void setRadeV2Enabled(bool v) { radeV2_.store(v); }
    bool isRadeV2Enabled() const { return radeV2_.load(); }
    /**
     * Session id of the currently open input stream, or -1 if no stream is open.
     * Exposed so Kotlin can attach AcousticEchoCanceler / NoiseSuppressor /
     * AutomaticGainControl objects and disable them — some OEMs (notably
     * Samsung) apply platform audio effects even when Unprocessed is requested,
     * and explicit effect disabling on the session id is the canonical fix.
     */
    int getInputSessionId() const { return inputSessionId_; }
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
    std::shared_ptr<InputCallback> inputCb_;
    std::shared_ptr<OutputCallback> outputCb_;
    std::mutex streamMutex_;

    int inputDeviceId_ = 0;
    int outputDeviceId_ = 0;
    std::atomic<bool> running_{false};

    int actualInputRate_ = 48000;
    int decimFactor_ = 6;   // 48000/8000

    struct rade *rade_ = nullptr;
    FARGANState *fargan_ = nullptr;
    int farganWarmupCount_ = 0;
    bool farganReady_ = false;
    /* First FARGAN_WARMUP_FRAMES decoded frames (36-float stride), collected
     * across rade_rx batches for one correct fargan_cont call. */
    float farganWarmupFeat_[FARGAN_WARMUP_FRAMES * 36] = {};

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
    std::atomic<float> txMicGain_{4.0f};   // same mic, same compensation on the TX side

    std::atomic<int> syncState_{0};
    std::atomic<int> snrEstimate_{0};
    std::atomic<float> freqOffset_{0.0f};
    std::atomic<bool> unprocessedRejected_{false};
    std::atomic<bool> radeV2_{false};
    int inputSessionId_ = -1;

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
    void restartInputStream(oboe::AudioStream *closedStream = nullptr);
    void restartOutputStream(oboe::AudioStream *closedStream = nullptr);

    /* ── TX pipeline ─────────────────────────────────────── */
    std::shared_ptr<oboe::AudioStream> txInputStream_;
    std::shared_ptr<oboe::AudioStream> txOutputStream_;
    std::shared_ptr<TxInputCallback> txInputCb_;
    std::shared_ptr<TxOutputCallback> txOutputCb_;

    std::atomic<bool> txRunning_{false};
    int txInputDeviceId_ = 0;
    int txOutputDeviceId_ = 0;
    bool txUseJavaOutput_ = false;
    bool rxUseJavaOutput_ = false;
    bool rxUseVoiceCommunicationOutput_ = false;
    // RX is rendering MEDIA to a Bluetooth LE Audio (LC3) headset. When set, the
    // RX capture uses VoiceRecognition instead of Unprocessed: on Samsung an
    // UNPROCESSED capture is mapped to the LE Audio "LIVE" context, which drags the
    // streaming LC3 group into a bidirectional (render + headset-mic) reconfig that
    // fails to start in a permanent loop, silencing the headset after the first TX.
    // VoiceRecognition maps to a context the headset mic does not advertise, so the
    // LC3 group stays render-only. Effects are stripped via the session id anyway.
    bool rxBleAudioOutput_ = false;
    bool txKeepRxAlive_ = false;        // local LC3 monitoring: mic-only TX, RX output kept open
    // Capture the TX mic through the active Bluetooth communication route (LE
    // Audio / LC3). VOICE_COMMUNICATION maps to the conversational context the
    // headset actually advertises as a source; Generic/Unprocessed map to
    // contexts that either fall back to the built-in mic or drag the LC3 group
    // into the failing "LIVE" reconfig. Routing follows the communication device
    // set from Kotlin, so no device id is pinned (BLE ids go stale on every
    // LE Audio reconfig).
    bool txUseVoiceCommInput_ = false;
    // TX mic is the built-in/USB mic while an LC3 headset is connected: capture
    // with VOICE_RECOGNITION instead of Generic/Unprocessed. Same mechanism as
    // rxBleAudioOutput_: Samsung maps Generic/Unprocessed capture to LE Audio
    // contexts the headset serves, force-attaching a headset-mic leg to the LC3
    // group — the mic-open then stalls for seconds on the failing reconfig and
    // the headset can drop to silence. VoiceAssistants (0x20) is not in the
    // headset's advertised source contexts, so the group is left alone.
    bool txUseVoiceRecInput_ = false;
    bool txNetMode_ = false;            // TX audio goes to UDP, not Oboe/AudioTrack
    std::atomic<bool> netRxRunning_{false};  // RX audio comes from UDP, not Oboe input

    LPCNetEncState *lpcnetEnc_ = nullptr;
    AudioRingBuffer txPlaybackRing_{RING_BUFFER_SIZE};

    /* Speech capture buffer (16kHz) */
    int txActualInputRate_ = 16000;
    int txDecimFactor_ = 1;
    std::vector<float> txDecimCoeffs_;
    std::vector<float> txDecimHistory_;
    int txDecimHistPos_ = 0;
    int txDecimPhase_ = 0;

    /* LPCNet feature extraction */
    std::vector<int16_t> txSpeechBuf_;
    int txSpeechPos_ = 0;
    std::vector<float> txFeatureAccum_;
    int txFeatureFrames_ = 0;
    int txFeaturesPerTx_ = 0;    // number of feature frames per rade_tx() call

    /* TX output interpolation (8kHz → output device rate) */
    int txOutputRate_ = 48000;
    int txInterpFactor_ = 6;
    std::vector<float> txInterpCoeffs_;

    /* Network TX polyphase interpolator (8 kHz → netRate). State is carried
     * across 20 ms frames so there is no per-frame boundary glitch. */
    std::vector<std::vector<float>> netTxInterpPhases_;  // [L][tapsPerPhase]
    std::vector<float> netTxInterpHist_;                 // input history (circular)
    int netTxInterpHistPos_ = 0;
    int netTxInterpL_ = 6;
    int netTxTapsPerPhase_ = 0;
    void designNetTxInterpFilter(int interpFactor);

    /* TX callsign for EOO */
    std::string txCallsign_;
    std::mutex txCallsignMutex_;

    std::atomic<float> txInputLevelDb_{-100.0f};

    /* Gap-free TX start: a filler thread keeps the ring topped up with
     * silence-encoded modem frames until the mic actually delivers, so the
     * transmitted waveform is continuous however long the mic (BT/LC3 route)
     * takes to spin up. Encoder handoff is lock-free: the mic callback
     * discards its input until the filler has exited (txFillerDone_). */
    std::thread txFillerThread_;
    std::atomic<bool> txFillerStop_{false};
    std::atomic<bool> txFillerDone_{true};
    std::atomic<bool> txMicSeen_{false};
    int64_t txStartNs_ = 0;

    bool initTxModem();
    void releaseTxModem();
    bool openTxInputStream();   // opens but does NOT start; caller starts it last
    bool openTxOutputStream();
    void designTxDecimFilter();  // mic-rate → 16 kHz speech decimation FIR
    void processTxInputFrames(const float *data, int32_t numFrames, int32_t channelCount);
    void processTxFeatureFrame();
    void generateTxOutput();
    void sendTxEoo();
    void renderTxOutput(float *data, int32_t numFrames);
    void designTxInterpFilter(int inputRate, int outputRate);
    void encodeTxSilenceFrame(int *fadeIn);  // one silence frame → ring (fadeIn: onset ramp counter or nullptr)
    void startTxFiller();
    void stopTxFiller();
    void txFillerLoop();
};

class InputCallback : public oboe::AudioStreamDataCallback,
                      public oboe::AudioStreamErrorCallback {
public:
    explicit InputCallback(AudioEngine *e) : engine_(e) {}
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *d, int32_t n) override;
    void onErrorAfterClose(oboe::AudioStream *s, oboe::Result error) override;
private:
    AudioEngine *engine_;
};

class OutputCallback : public oboe::AudioStreamDataCallback,
                       public oboe::AudioStreamErrorCallback {
public:
    explicit OutputCallback(AudioEngine *e) : engine_(e) {}
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *d, int32_t n) override;
    void onErrorAfterClose(oboe::AudioStream *s, oboe::Result error) override;
private:
    AudioEngine *engine_;
};

class TxInputCallback : public oboe::AudioStreamDataCallback,
                         public oboe::AudioStreamErrorCallback {
public:
    explicit TxInputCallback(AudioEngine *e) : engine_(e) {}
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *d, int32_t n) override;
    void onErrorAfterClose(oboe::AudioStream *s, oboe::Result error) override;
private:
    AudioEngine *engine_;
};

class TxOutputCallback : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    explicit TxOutputCallback(AudioEngine *e) : engine_(e) {}
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *d, int32_t n) override;
    void onErrorAfterClose(oboe::AudioStream *s, oboe::Result error) override;
private:
    AudioEngine *engine_;
};

#endif
