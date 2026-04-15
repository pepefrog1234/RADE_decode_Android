/*
 * rade_jni.cpp - JNI bridge for RADE modem, FARGAN vocoder, and EOO callsign codec.
 *
 * Maps native C functions to Java/Kotlin methods in yakumo2683.RADEdecode.RADEWrapper.
 */

#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <string>
#include <android/log.h>

#include "audio_engine.h"

extern "C" {
#include "rade_api.h"
#include "fargan.h"
#include "cpu_support.h"
#include "eoo_callsign_codec_c.h"
}

#define LOG_TAG "RADE_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ── Persistent native state ───────────────────────────────── */

static struct rade *g_rade = nullptr;
static FARGANState *g_fargan = nullptr;

/* ── JNI function names ────────────────────────────────────── */
/* Package: yakumo2683.RADEdecode  Class: RADEWrapper */

#define JNI_METHOD(name) \
    Java_yakumo2683_RADEdecode_RADEWrapper_##name

extern "C" {

/* ────────────────────────────────────────────────────────────
 *  RADE modem
 * ──────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
JNI_METHOD(nativeOpen)(JNIEnv *env, jobject /* this */, jint flags) {
    if (g_rade != nullptr) {
        rade_close(g_rade);
        g_rade = nullptr;
    }

    rade_initialize();
    g_rade = rade_open(nullptr, (int)flags);

    if (g_rade == nullptr) {
        LOGE("rade_open failed");
        return JNI_FALSE;
    }

    LOGI("rade_open succeeded, version=%d", rade_version());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
JNI_METHOD(nativeClose)(JNIEnv *env, jobject /* this */) {
    if (g_rade != nullptr) {
        rade_close(g_rade);
        g_rade = nullptr;
    }
    rade_finalize();
}

JNIEXPORT jint JNICALL
JNI_METHOD(nativeNin)(JNIEnv *env, jobject /* this */) {
    if (g_rade == nullptr) return 0;
    return rade_nin(g_rade);
}

JNIEXPORT jint JNICALL
JNI_METHOD(nativeNinMax)(JNIEnv *env, jobject /* this */) {
    if (g_rade == nullptr) return 0;
    return rade_nin_max(g_rade);
}

JNIEXPORT jint JNICALL
JNI_METHOD(nativeGetNumFeatures)(JNIEnv *env, jobject /* this */) {
    if (g_rade == nullptr) return 0;
    return rade_n_features_in_out(g_rade);
}

JNIEXPORT jint JNICALL
JNI_METHOD(nativeGetNumEooBits)(JNIEnv *env, jobject /* this */) {
    if (g_rade == nullptr) return 0;
    return rade_n_eoo_bits(g_rade);
}

/*
 * nativeRx: Feed IQ samples to the RADE receiver.
 *
 * @param samplesIn  short[] of modem input samples (real-valued, will be
 *                   converted to RADE_COMP with imag=0)
 * @param numSamples number of samples (must equal rade_nin())
 * @param featuresOut float[] buffer for decoded features (pre-allocated)
 * @param eooOut     float[] buffer for EOO bits (pre-allocated)
 *
 * @return  bits: bit 0 = valid features, bit 1 = EOO detected,
 *          negative on error
 */
JNIEXPORT jint JNICALL
JNI_METHOD(nativeRx)(JNIEnv *env, jobject /* this */,
                     jshortArray samplesIn, jint numSamples,
                     jfloatArray featuresOut, jfloatArray eooOut) {
    if (g_rade == nullptr) return -1;

    jshort *inBuf = env->GetShortArrayElements(samplesIn, nullptr);
    if (inBuf == nullptr) return -1;

    /* Convert Int16 to RADE_COMP (real = sample/32768, imag = 0) */
    auto *rx_in = (RADE_COMP *)malloc(numSamples * sizeof(RADE_COMP));
    for (int i = 0; i < numSamples; i++) {
        rx_in[i].real = (float)inBuf[i] / 32768.0f;
        rx_in[i].imag = 0.0f;
    }
    env->ReleaseShortArrayElements(samplesIn, inBuf, JNI_ABORT);

    int n_features = rade_n_features_in_out(g_rade);
    int n_eoo = rade_n_eoo_bits(g_rade);

    auto *features = (float *)calloc(n_features, sizeof(float));
    auto *eoo = (float *)calloc(n_eoo, sizeof(float));

    int has_eoo = 0;
    int result = rade_rx(g_rade, features, &has_eoo, eoo, rx_in);

    /* Copy features out */
    if (featuresOut != nullptr && result > 0) {
        int outLen = env->GetArrayLength(featuresOut);
        int copyLen = (n_features < outLen) ? n_features : outLen;
        env->SetFloatArrayRegion(featuresOut, 0, copyLen, features);
    }

    /* Copy EOO bits out */
    if (eooOut != nullptr && has_eoo) {
        int outLen = env->GetArrayLength(eooOut);
        int copyLen = (n_eoo < outLen) ? n_eoo : outLen;
        env->SetFloatArrayRegion(eooOut, 0, copyLen, eoo);
    }

    free(rx_in);
    free(features);
    free(eoo);

    int ret = 0;
    if (result > 0) ret |= 1;   /* valid features */
    if (has_eoo)    ret |= 2;   /* EOO detected */
    return ret;
}

JNIEXPORT jint JNICALL
JNI_METHOD(nativeSync)(JNIEnv *env, jobject /* this */) {
    if (g_rade == nullptr) return 0;
    return rade_sync(g_rade);
}

JNIEXPORT jfloat JNICALL
JNI_METHOD(nativeFreqOffset)(JNIEnv *env, jobject /* this */) {
    if (g_rade == nullptr) return 0.0f;
    return rade_freq_offset(g_rade);
}

JNIEXPORT jint JNICALL
JNI_METHOD(nativeSnrEstimate)(JNIEnv *env, jobject /* this */) {
    if (g_rade == nullptr) return 0;
    return rade_snrdB_3k_est(g_rade);
}

JNIEXPORT jint JNICALL
JNI_METHOD(nativeVersion)(JNIEnv *env, jobject /* this */) {
    return rade_version();
}

/* ────────────────────────────────────────────────────────────
 *  FARGAN vocoder
 * ──────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
JNI_METHOD(nativeFarganInit)(JNIEnv *env, jobject /* this */) {
    if (g_fargan != nullptr) {
        free(g_fargan);
        g_fargan = nullptr;
    }

    g_fargan = (FARGANState *)calloc(1, sizeof(FARGANState));
    if (g_fargan == nullptr) {
        LOGE("Failed to allocate FARGANState");
        return JNI_FALSE;
    }

    fargan_init(g_fargan);
    LOGI("FARGAN initialized");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
JNI_METHOD(nativeFarganClose)(JNIEnv *env, jobject /* this */) {
    if (g_fargan != nullptr) {
        free(g_fargan);
        g_fargan = nullptr;
    }
}

/*
 * nativeFarganCont: Warmup / continuation for FARGAN.
 * Called with the first few frames to initialize internal state.
 *
 * @param pcm      float[] of 320 PCM samples (FARGAN_CONT_SAMPLES)
 * @param features float[] of NB_FEATURES features
 */
JNIEXPORT void JNICALL
JNI_METHOD(nativeFarganCont)(JNIEnv *env, jobject /* this */,
                             jfloatArray pcm, jfloatArray features) {
    if (g_fargan == nullptr) return;

    jfloat *pcmBuf = env->GetFloatArrayElements(pcm, nullptr);
    jfloat *featBuf = env->GetFloatArrayElements(features, nullptr);

    fargan_cont(g_fargan, pcmBuf, featBuf);

    env->ReleaseFloatArrayElements(pcm, pcmBuf, JNI_ABORT);
    env->ReleaseFloatArrayElements(features, featBuf, JNI_ABORT);
}

/*
 * nativeFarganSynthesize: Synthesize one frame of speech (160 samples @ 16kHz).
 *
 * @param features float[] of NB_FEATURES features
 * @param pcmOut   short[] buffer for 160 Int16 PCM samples (pre-allocated)
 */
JNIEXPORT void JNICALL
JNI_METHOD(nativeFarganSynthesize)(JNIEnv *env, jobject /* this */,
                                   jfloatArray features, jshortArray pcmOut) {
    if (g_fargan == nullptr) return;

    jfloat *featBuf = env->GetFloatArrayElements(features, nullptr);

    opus_int16 pcm[FARGAN_FRAME_SIZE]; /* 160 samples */
    fargan_synthesize_int(g_fargan, pcm, featBuf);

    env->ReleaseFloatArrayElements(features, featBuf, JNI_ABORT);

    /* Copy Int16 PCM to output */
    jshort *outBuf = env->GetShortArrayElements(pcmOut, nullptr);
    memcpy(outBuf, pcm, FARGAN_FRAME_SIZE * sizeof(jshort));
    env->ReleaseShortArrayElements(pcmOut, outBuf, 0);
}

/* ────────────────────────────────────────────────────────────
 *  EOO callsign codec
 * ──────────────────────────────────────────────────────────── */

/*
 * nativeEooCallsignDecode: Decode EOO bits into a callsign string.
 *
 * @param syms float[] of EOO symbol soft-decisions
 * @return decoded callsign String, or null on failure
 */
JNIEXPORT jstring JNICALL
JNI_METHOD(nativeEooCallsignDecode)(JNIEnv *env, jobject /* this */,
                                    jfloatArray syms) {
    jfloat *symBuf = env->GetFloatArrayElements(syms, nullptr);
    int symLen = env->GetArrayLength(syms);

    char callsign[32];
    memset(callsign, 0, sizeof(callsign));

    int ok = eoo_callsign_decode(symBuf, symLen, callsign, sizeof(callsign) - 1);

    env->ReleaseFloatArrayElements(syms, symBuf, JNI_ABORT);

    if (ok) {
        return env->NewStringUTF(callsign);
    }
    return nullptr;
}

} /* extern "C" — end of RADEWrapper JNI */

/* ────────────────────────────────────────────────────────────
 *  AudioEngine (integrated audio pipeline)
 *  C++ code must be outside extern "C"
 * ──────────────────────────────────────────────────────────── */

static AudioEngine *g_audioEngine = nullptr;

/* JNI callback bridge: forwards native events to Kotlin */
static JavaVM *g_jvm = nullptr;
static jobject g_audioCallbackRef = nullptr;

class JniAudioCallback : public AudioEngineCallback {
public:
    void onSyncStateChanged(int state) override {
        callKotlin([state](JNIEnv *env, jobject obj) {
            jclass cls = env->GetObjectClass(obj);
            jmethodID mid = env->GetMethodID(cls, "onSyncStateChanged", "(I)V");
            if (mid) env->CallVoidMethod(obj, mid, (jint)state);
        });
    }

    void onCallsignDecoded(const char *callsign) override {
        std::string cs(callsign); // copy for lambda capture
        callKotlin([cs](JNIEnv *env, jobject obj) {
            jclass cls = env->GetObjectClass(obj);
            jmethodID mid = env->GetMethodID(cls, "onCallsignDecoded",
                                             "(Ljava/lang/String;)V");
            if (mid) {
                jstring jcs = env->NewStringUTF(cs.c_str());
                env->CallVoidMethod(obj, mid, jcs);
                env->DeleteLocalRef(jcs);
            }
        });
    }

private:
    template <typename Func>
    void callKotlin(Func fn) {
        if (!g_jvm || !g_audioCallbackRef) return;
        JNIEnv *env = nullptr;
        bool attached = false;
        int status = g_jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            g_jvm->AttachCurrentThread(&env, nullptr);
            attached = true;
        }
        if (env) {
            fn(env, g_audioCallbackRef);
        }
        if (attached) {
            g_jvm->DetachCurrentThread();
        }
    }
};

static JniAudioCallback g_jniCallback;

#define JNI_AUDIO(name) \
    Java_yakumo2683_RADEdecode_AudioBridge_##name

extern "C" {

JNIEXPORT jboolean JNICALL
JNI_AUDIO(nativeCreate)(JNIEnv *env, jobject /* this */) {
    if (g_audioEngine != nullptr) {
        delete g_audioEngine;
    }
    g_audioEngine = new AudioEngine();
    g_audioEngine->setCallback(&g_jniCallback);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeDestroy)(JNIEnv *env, jobject /* this */) {
    if (g_audioEngine) {
        delete g_audioEngine;
        g_audioEngine = nullptr;
    }
    if (g_audioCallbackRef) {
        env->DeleteGlobalRef(g_audioCallbackRef);
        g_audioCallbackRef = nullptr;
    }
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeSetCallback)(JNIEnv *env, jobject /* this */, jobject callback) {
    env->GetJavaVM(&g_jvm);
    if (g_audioCallbackRef) {
        env->DeleteGlobalRef(g_audioCallbackRef);
        g_audioCallbackRef = nullptr;
    }
    if (callback) {
        g_audioCallbackRef = env->NewGlobalRef(callback);
    }
}

JNIEXPORT jboolean JNICALL
JNI_AUDIO(nativeStart)(JNIEnv *env, jobject /* this */, jint inputDeviceId) {
    if (!g_audioEngine) return JNI_FALSE;
    return g_audioEngine->start(inputDeviceId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeStop)(JNIEnv *env, jobject /* this */) {
    if (g_audioEngine) g_audioEngine->stop();
}

JNIEXPORT jboolean JNICALL
JNI_AUDIO(nativeIsRunning)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return JNI_FALSE;
    return g_audioEngine->isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeSetInputDevice)(JNIEnv *env, jobject /* this */, jint deviceId) {
    if (g_audioEngine) g_audioEngine->setInputDevice(deviceId);
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeSetOutputVolume)(JNIEnv *env, jobject /* this */, jfloat volume) {
    if (g_audioEngine) g_audioEngine->setOutputVolume(volume);
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeSetInputGain)(JNIEnv *env, jobject /* this */, jfloat gain) {
    if (g_audioEngine) g_audioEngine->setInputGain(gain);
}

JNIEXPORT jfloat JNICALL
JNI_AUDIO(nativeGetInputGain)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return 1.0f;
    return g_audioEngine->getInputGain();
}

JNIEXPORT jint JNICALL
JNI_AUDIO(nativeGetSyncState)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return 0;
    return g_audioEngine->getSyncState();
}

JNIEXPORT jint JNICALL
JNI_AUDIO(nativeGetSnrEstimate)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return 0;
    return g_audioEngine->getSnrEstimate();
}

JNIEXPORT jfloat JNICALL
JNI_AUDIO(nativeGetFreqOffset)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return 0.0f;
    return g_audioEngine->getFreqOffset();
}

JNIEXPORT jboolean JNICALL
JNI_AUDIO(nativeIsUnprocessedRejected)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return JNI_FALSE;
    return g_audioEngine->isUnprocessedRejected() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
JNI_AUDIO(nativeGetInputLevel)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return -100.0f;
    return g_audioEngine->getInputLevel();
}

JNIEXPORT jfloat JNICALL
JNI_AUDIO(nativeGetOutputLevel)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return -100.0f;
    return g_audioEngine->getOutputLevel();
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeGetSpectrum)(JNIEnv *env, jobject /* this */, jfloatArray out) {
    if (!g_audioEngine) return;
    int len = env->GetArrayLength(out);
    jfloat *buf = env->GetFloatArrayElements(out, nullptr);
    g_audioEngine->getSpectrum(buf, len);
    env->ReleaseFloatArrayElements(out, buf, 0);
}

JNIEXPORT jstring JNICALL
JNI_AUDIO(nativeGetLastCallsign)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return env->NewStringUTF("");
    std::string cs = g_audioEngine->getLastCallsign();
    return env->NewStringUTF(cs.c_str());
}

JNIEXPORT jboolean JNICALL
JNI_AUDIO(nativeStartRecording)(JNIEnv *env, jobject /* this */, jstring path) {
    if (!g_audioEngine) return JNI_FALSE;
    const char *p = env->GetStringUTFChars(path, nullptr);
    bool ok = g_audioEngine->startRecording(p);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeStopRecording)(JNIEnv *env, jobject /* this */) {
    if (g_audioEngine) g_audioEngine->stopRecording();
}

/* ── TX (Transmit) JNI methods ───────────────────────────────── */

JNIEXPORT jboolean JNICALL
JNI_AUDIO(nativeStartTx)(JNIEnv *env, jobject /* this */,
                          jint inputDeviceId, jint outputDeviceId) {
    if (!g_audioEngine) return JNI_FALSE;
    return g_audioEngine->startTx(inputDeviceId, outputDeviceId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeStopTx)(JNIEnv *env, jobject /* this */) {
    if (g_audioEngine) g_audioEngine->stopTx();
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeSetTxCallsign)(JNIEnv *env, jobject /* this */, jstring callsign) {
    if (!g_audioEngine) return;
    const char *cs = env->GetStringUTFChars(callsign, nullptr);
    g_audioEngine->setTxCallsign(cs);
    env->ReleaseStringUTFChars(callsign, cs);
}

JNIEXPORT jfloat JNICALL
JNI_AUDIO(nativeGetTxLevel)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return -100.0f;
    return g_audioEngine->getTxLevel();
}

/**
 * Read modem samples from TX ring buffer (8kHz int16).
 * Used by Java AudioTrack for USB audio output.
 * Returns number of samples actually read.
 */
JNIEXPORT jint JNICALL
JNI_AUDIO(nativeReadTxRing)(JNIEnv *env, jobject /* this */,
                            jshortArray outBuf, jint maxSamples) {
    if (!g_audioEngine) return 0;
    jshort *buf = env->GetShortArrayElements(outBuf, nullptr);
    int got = g_audioEngine->readTxRing(reinterpret_cast<int16_t*>(buf), maxSamples);
    env->ReleaseShortArrayElements(outBuf, buf, 0);
    return got;
}

JNIEXPORT jboolean JNICALL
JNI_AUDIO(nativeIsTxUsingJavaOutput)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return JNI_FALSE;
    return g_audioEngine->isTxUsingJavaOutput() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
JNI_AUDIO(nativeSetTxOutputDevice)(JNIEnv *env, jobject /* this */, jint deviceId) {
    if (g_audioEngine) g_audioEngine->setTxOutputDevice(deviceId);
}

JNIEXPORT jboolean JNICALL
JNI_AUDIO(nativeIsTxRunning)(JNIEnv *env, jobject /* this */) {
    if (!g_audioEngine) return JNI_FALSE;
    return g_audioEngine->isTxRunning() ? JNI_TRUE : JNI_FALSE;
}

} /* extern "C" */
