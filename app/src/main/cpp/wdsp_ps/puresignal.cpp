/*  puresignal.cpp — C++ facade over the vendored WDSP PureSignal engine
 *
 *  [RADE] New file (not from WDSP). See puresignal.h for the API contract.
 *
 *  The engine it drives is GPL-2.0-or-later, Copyright (C) 2013-2019
 *  Warren Pratt, NR0V — see docs/licenses/WDSP-NOTICE.md.
 *
 *  Design: exactly one WDSP "channel" (index 0). The facade owns
 *   - the CALCC instance (calibration computer, calcc.c) — fed by psFeed
 *     via pscc(); spawns its own detached calibration thread internally
 *   - the IQC instance (correction applier, iqc.c) — run in place on the
 *     TX stream by psApply via xiqc()
 *   - the minimal txa[]/ch[] channel globals the vendored code expects
 *     (storage in wdsp_port.c)
 *
 *  Locking:
 *   - g_psLock (rwlock): create/destroy exclusive vs everything else shared
 *   - ch[0].csDSP: held by psApply around xiqc — this mirrors WDSP's DSP
 *     thread (main.c) and is what the calibration thread's coefficient
 *     handoff (SetTXAiqcStart/Swap/End in iqc.c) synchronizes against
 *   - txa[0].calcc.cs_update: taken inside pscc()/GetPSInfo()/SetPS*()
 *     by the vendored code itself
 *   - g_feedLock: facade-only guard for the psFeed conversion scratch
 */

#include <pthread.h>
#include <unistd.h>
#include <string.h>
#include <android/log.h>

#include "wdsp_min.h"
#include "puresignal.h"

// [RADE] linux_port.h defines function-style min/max/Sleep macros; keep them
// out of the C++ code below.
#undef min
#undef max
#undef Sleep

#define PS_LOG_TAG "PureSignalPS"
#define PS_LOGI(...) __android_log_print(ANDROID_LOG_INFO, PS_LOG_TAG, __VA_ARGS__)
#define PS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PS_LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kChannel = 0;         // the single WDSP channel we own
constexpr int kInts = 16;           // envelope intervals (Thetis default)
constexpr int kSpi = 256;           // samples per interval (Thetis default)
constexpr double kHwPeak = 1.0;     // TX reference expected to peak at <= 1.0
constexpr double kMoxDelay = 0.1;   // s of TX to skip after keying (Thetis default)
constexpr double kLoopDelay = 0.0;  // s between auto-cals (Thetis default)
constexpr double kPtol = 0.8;       // fit population tolerance (Thetis default)
constexpr int kNpsamps = 256;       // pin-mode extension samples (Thetis default)
constexpr double kAlpha = 0.9;      // stabilize-mode smoothing (Thetis default)
constexpr int kMaxBlockSize = 16384;

pthread_rwlock_t g_psLock = PTHREAD_RWLOCK_INITIALIZER;
pthread_mutex_t g_feedLock = PTHREAD_MUTEX_INITIALIZER;

bool g_created = false;
int g_blockSize = 0;
CALCC g_calcc = nullptr;
IQC g_iqc = nullptr;
double* g_feedTx = nullptr;   // psFeed float->double scratch (TX reference)
double* g_feedRx = nullptr;   // psFeed float->double scratch (RX feedback)
double* g_iqBuf = nullptr;    // psApply in-place buffer (iqc in == out)

// Must be called with g_psLock held for writing.
void psDestroyLocked()
{
    if (!g_created) return;

    // Orderly shutdown, mirroring WDSP SetPSIntsAndSpi(): close the pscc()
    // and xiqc() gates, then clear busy/running so a calibration thread
    // blocked in the SetTXAiqcStart/Swap/End busy-wait can exit.
    SetPSControl(kChannel, 1, 0, 0, 0);
    SetPSMox(kChannel, 0);
    ForceShutDown(g_calcc, g_iqc, 50);
    usleep(50 * 1000);  // allow an in-flight solver thread to finish calc()

    txa[kChannel].iqc.p0 = txa[kChannel].iqc.p1 = nullptr;
    destroy_iqc(g_iqc);
    g_iqc = nullptr;
    txa[kChannel].calcc.p = nullptr;
    destroy_calcc(g_calcc);
    g_calcc = nullptr;
    DeleteCriticalSection(&ch[kChannel].csDSP);

    // malloc0 == malloc on this port (see linux_port.h _aligned_malloc)
    free(g_iqBuf);   g_iqBuf = nullptr;
    free(g_feedTx);  g_feedTx = nullptr;
    free(g_feedRx);  g_feedRx = nullptr;

    g_blockSize = 0;
    g_created = false;
    PS_LOGI("PureSignal engine destroyed");
}

} // namespace

bool psCreate(int rate, int blockSize)
{
    if (rate <= 0 || blockSize <= 0) {
        PS_LOGE("psCreate: bad args rate=%d blockSize=%d", rate, blockSize);
        return false;
    }
    if (blockSize > kMaxBlockSize) blockSize = kMaxBlockSize;

    pthread_rwlock_wrlock(&g_psLock);
    if (g_created) psDestroyLocked();

    InitializeCriticalSectionAndSpinCount(&ch[kChannel].csDSP, 2500);

    // Parameters mirror Thetis/WDSP create_txa() defaults (TXA.c).
    g_calcc = create_calcc(
        kChannel,           // channel number
        1,                  // run calibration (pscc gate)
        blockSize,          // input buffer size
        rate,               // feedback sample rate
        kInts,              // ints
        kSpi,               // spi
        1.0 / kHwPeak,      // hw_scale
        kMoxDelay,          // mox delay
        kLoopDelay,         // loop delay
        kPtol,              // ptol
        0,                  // mox
        0,                  // solidmox
        1,                  // pin mode
        1,                  // map mode
        0,                  // stbl mode
        kNpsamps,           // pin samples
        kAlpha);            // alpha
    txa[kChannel].calcc.p = g_calcc;

    g_iqBuf = (double*)malloc0((int)(blockSize * sizeof(complex)));
    g_iqc = create_iqc(
        0,                  // run (turned on by the engine after a good cal)
        blockSize,          // size
        g_iqBuf,            // input buffer (in place)
        g_iqBuf,            // output buffer (in place)
        (double)rate,       // sample rate (sets the 5 ms changeover ramp)
        kInts,              // ints
        0.005,              // changeover time (tup)
        kSpi);              // spi
    txa[kChannel].iqc.p0 = txa[kChannel].iqc.p1 = g_iqc;

    g_feedTx = (double*)malloc0((int)(blockSize * sizeof(complex)));
    g_feedRx = (double*)malloc0((int)(blockSize * sizeof(complex)));

    g_blockSize = blockSize;
    g_created = true;
    pthread_rwlock_unlock(&g_psLock);

    PS_LOGI("PureSignal engine created: rate=%d blockSize=%d ints=%d spi=%d",
            rate, blockSize, kInts, kSpi);
    return true;
}

void psDestroy()
{
    pthread_rwlock_wrlock(&g_psLock);
    psDestroyLocked();
    pthread_rwlock_unlock(&g_psLock);
}

void psFeed(const float* txRefIQ, const float* fbIQ, int nSamples)
{
    if (!txRefIQ || !fbIQ || nSamples <= 0) return;
    pthread_rwlock_rdlock(&g_psLock);
    if (g_created) {
        pthread_mutex_lock(&g_feedLock);
        int off = 0;
        while (nSamples > 0) {
            const int m = (nSamples < g_blockSize) ? nSamples : g_blockSize;
            for (int i = 0; i < 2 * m; i++) {
                g_feedTx[i] = (double)txRefIQ[2 * off + i];
                g_feedRx[i] = (double)fbIQ[2 * off + i];
            }
            pscc(kChannel, m, g_feedTx, g_feedRx);
            off += m;
            nSamples -= m;
        }
        pthread_mutex_unlock(&g_feedLock);
    }
    pthread_rwlock_unlock(&g_psLock);
}

void psApply(float* txIQ, int nSamples)
{
    if (!txIQ || nSamples <= 0) return;
    pthread_rwlock_rdlock(&g_psLock);
    if (g_created) {
        // Mirror WDSP main.c: the DSP thread holds csDSP across the TX
        // chain; SetTXAiqcStart/Swap/End (calibration thread) synchronize
        // coefficient swaps against it.
        EnterCriticalSection(&ch[kChannel].csDSP);
        if (_InterlockedAnd(&g_iqc->run, 1)) {
            int off = 0;
            while (nSamples > 0) {
                const int m = (nSamples < g_blockSize) ? nSamples : g_blockSize;
                for (int i = 0; i < 2 * m; i++)
                    g_iqBuf[i] = (double)txIQ[2 * off + i];
                setSize_iqc(g_iqc, m);
                xiqc(g_iqc);
                for (int i = 0; i < 2 * m; i++)
                    txIQ[2 * off + i] = (float)g_iqBuf[i];
                off += m;
                nSamples -= m;
            }
        }
        // run == 0: pass-through (matches xiqc's own bypass; buffer untouched)
        LeaveCriticalSection(&ch[kChannel].csDSP);
    }
    pthread_rwlock_unlock(&g_psLock);
}

void psGetInfo(int* info16)
{
    if (!info16) return;
    pthread_rwlock_rdlock(&g_psLock);
    if (g_created)
        GetPSInfo(kChannel, info16);
    else
        memset(info16, 0, 16 * sizeof(int));
    pthread_rwlock_unlock(&g_psLock);
}

void psSetRun(bool run)
{
    pthread_rwlock_rdlock(&g_psLock);
    if (g_created) {
        if (run) {
            SetPSMox(kChannel, 1);
            SetPSControl(kChannel, 0, 0, 1, 0);   // automode on
        } else {
            SetPSControl(kChannel, 1, 0, 0, 0);   // reset (ramps correction out)
            SetPSMox(kChannel, 0);
        }
        PS_LOGI("psSetRun(%d)", run ? 1 : 0);
    }
    pthread_rwlock_unlock(&g_psLock);
}
