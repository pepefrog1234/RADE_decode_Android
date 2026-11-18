/* tunecheck.c — the RADE V2 tuning indicator and clock offset, as the app
 * reads them.
 *
 * The receiver screen shows a "tune up / tune down" hint from rade_mistune()
 * and the Tx/Rx sound-card clock offset from rade_clock_offset_ppm(). Both
 * depend on sign conventions inside the vendored modem (the mistune direction
 * even on a model specific bias, RADE_V2_DIR_BIAS), so a radae/ update can
 * silently turn the hint around. This check generates signals with rade_tx(),
 * detunes or resamples them the way a mistuned SSB receiver or an off-rate
 * sound card would, and feeds them to rade_rx() through the same int16
 * conversion as AudioEngine::processModemFrame and the app's own indicator
 * logic (tune_indicator.h: nothing in sync, a direction only once the modem
 * has held it for 3 s).
 *
 * Exit code 0 = every scenario behaved as the UI assumes:
 *   - V2 signal +45 Hz / -45 Hz off: never syncs, indicator +1 / -1 only,
 *     shown 3 s after the modem first reports it, cleared within 3.5 s after
 *     the signal ends
 *   - on frequency, noise only, V1: no indication
 *   - retuned into range: syncs, and no indication from then on
 *   - a direction the modem reports for less than 3 s is never shown
 *   - Rx clock 100 ppm fast / slow: estimate positive / negative, held
 *     after the over
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include "rade_api.h"
#include "tune_indicator.h"   /* the app's own header, app/src/main/cpp */

#define FS        8000.0
#define LEAD_IN_S 1.0     /* silence before the signal */
#define TAIL_S    4.0     /* silence after it: covers the indicator's hold */

/* Mirrors audio_engine.h. */
static const float RADE_RX_REAL_SCALE = 2.0f / RADE_INT16_SCALE;

typedef struct {
    const char *name;
    int    v2;
    double signal_s;      /* length of the transmission (0: none) */
    double offset_hz;     /* audio frequency error of the received signal */
    int    retune;        /* 1: the error becomes retune_hz at retune_at_s */
    double retune_at_s;
    double retune_hz;
    double level_db;      /* channel gain relative to the rade_api.h nominal level */
    double noise_rms;     /* white noise in int16 units (0: none) */
    double clock_ppm;     /* Rx sample clock relative to Tx (+: Rx faster) */
    double tail_s;        /* silence after the signal (0: TAIL_S) */
} scenario;

typedef struct {
    int    ever_sync;         /* rade_sync() != 0 on any call */
    double sync_at_s;         /* first sync, seconds after the signal starts */
    int    saw_plus;          /* the app's indicator was +1 on some call */
    int    saw_minus;         /* ... or -1 */
    double first_ind_s;       /* first indication, seconds after the signal starts */
    double last_ind_s;        /* last indication, seconds after the signal starts */
    double first_raw_s;       /* first non-zero rade_mistune(), same time base */
    double signal_end_s;
    int    ind_before_signal; /* indication during the lead-in silence */
    int    ind_after_sync;    /* indication on any call after the first sync */
    int    ind_at_end;        /* still indicating when the run ends */
    long   raw_nonzero;       /* calls with rade_mistune() != 0 */
    long   raw_in_sync;       /* ... of which rade_sync() != 0 as well */
    float  clock_ppm;         /* rade_clock_offset_ppm() when the signal ends */
    float  clock_ppm_unsync;  /* ... on the call that lost sync */
    float  clock_ppm_final;   /* ... and when the run ends */
} result;

/* Deterministic noise: the check must give the same answer on every run. */
static uint32_t lcg_state;
static double lcg_uniform(void) {
    lcg_state = lcg_state * 1664525u + 1013904223u;
    return ((double)(lcg_state >> 8) + 1.0) / 16777217.0;
}
static double lcg_gauss(void) {
    double u = lcg_uniform();
    return sqrt(-2.0 * log(u)) * cos(2.0 * M_PI * lcg_uniform());
}

static result run(const scenario *sc) {
    result res;
    memset(&res, 0, sizeof(res));
    res.sync_at_s = res.first_ind_s = res.last_ind_s = res.first_raw_s = -1.0;
    lcg_state = 20261117u;

    int flags = RADE_USE_C_ENCODER | RADE_USE_C_DECODER | RADE_VERBOSE_0
              | (sc->v2 ? RADE_MODE_V2 : 0);
    rade_initialize();
    struct rade *r = rade_open(NULL, flags);
    if (!r) { fprintf(stderr, "rade_open failed\n"); exit(1); }

    int nFeat = rade_n_features_in_out(r);
    int nTxOut = rade_n_tx_out(r);
    int ninMax = rade_nin_max(r);
    int nEooBits = rade_n_eoo_bits(r);

    /* Transmit: modem frames with the same synthetic features as loopback.c. */
    long frames = sc->signal_s > 0.0 ? (long)(sc->signal_s * FS / nTxOut) + 1 : 0;
    RADE_COMP *tx = calloc((size_t)(frames > 0 ? frames : 1) * nTxOut, sizeof(RADE_COMP));
    float *feat = calloc(nFeat, sizeof(float));
    long txLen = 0;
    for (long f = 0; f < frames; f++) {
        for (int i = 0; i < nFeat; i++) feat[i] = 0.1f * (float)((i + f) % 7);
        txLen += rade_tx(r, &tx[txLen], feat);
    }

    /* Channel. A sound card whose clock runs clock_ppm fast takes its n-th
       sample at Tx time n / (1 + ppm): windowed-sinc interpolation. A receiver
       tuned offset_hz low on USB (or high on LSB) raises every audio frequency
       by offset_hz: rotate the complex modem output, which is one sided, and
       only then take the real part a sound card delivers. */
    const int HALF = 16;
    double rate = 1.0 + sc->clock_ppm * 1e-6;
    double gain = pow(10.0, sc->level_db / 20.0);
    long leadIn = (long)(LEAD_IN_S * FS);
    long sigLen = (long)((double)txLen * rate);
    long total = leadIn + sigLen + (long)((sc->tail_s > 0.0 ? sc->tail_s : TAIL_S) * FS);
    int16_t *pcm = calloc((size_t)total, sizeof(int16_t));
    double phase = 0.0;
    for (long n = 0; n < total; n++) {
        double v = 0.0;
        long k = n - leadIn;
        if (k >= 0 && k < sigLen) {
            double t = (double)k / rate;
            long k0 = (long)floor(t);
            double frac = t - (double)k0;
            double re = 0.0, im = 0.0;
            if (sc->clock_ppm == 0.0) {
                re = tx[k0].real;
                im = tx[k0].imag;
            } else {
                for (int j = -HALF + 1; j <= HALF; j++) {
                    long kk = k0 + j;
                    if (kk < 0 || kk >= txLen) continue;
                    double x = frac - (double)j;
                    double s = fabs(x) < 1e-9 ? 1.0 : sin(M_PI * x) / (M_PI * x);
                    double w = 0.5 * (1.0 + cos(M_PI * x / HALF));
                    re += tx[kk].real * s * w;
                    im += tx[kk].imag * s * w;
                }
            }
            double hz = (sc->retune && (double)k / FS >= sc->retune_at_s)
                      ? sc->retune_hz : sc->offset_hz;
            phase += 2.0 * M_PI * hz / FS;
            v = (re * cos(phase) - im * sin(phase)) * gain * RADE_INT16_SCALE;
        }
        if (sc->noise_rms > 0.0) v += sc->noise_rms * lcg_gauss();
        if (v > 32767.0) v = 32767.0;
        if (v < -32768.0) v = -32768.0;
        pcm[n] = (int16_t)lrint(v);
    }

    /* Receive exactly like the app: rade_nin() samples per call, int16 scaled
       by RADE_RX_REAL_SCALE into the real part, then the status reads and the
       indicator update AudioEngine::processModemFrame makes. */
    tune_indicator indicator;
    tune_indicator_reset(&indicator);
    float *featOut = calloc(nFeat > 0 ? nFeat : 1, sizeof(float));
    float *eooOut = calloc(nEooBits > 0 ? nEooBits : 1, sizeof(float));
    RADE_COMP *rxIn = calloc(ninMax, sizeof(RADE_COMP));
    res.signal_end_s = (double)sigLen / FS;
    long pos = 0;
    int ind = 0, was_sync = 0, clock_taken = 0;
    while (pos + rade_nin(r) <= total) {
        int nin = rade_nin(r);
        for (int i = 0; i < nin; i++) {
            rxIn[i].real = (float)pcm[pos + i] * RADE_RX_REAL_SCALE;
            rxIn[i].imag = 0.0f;
        }
        int hasEoo = 0;
        rade_rx(r, featOut, &hasEoo, eooOut, rxIn);
        pos += nin;

        double t = (double)(pos - leadIn) / FS;
        int sync = rade_sync(r) != 0;
        int raw = rade_mistune(r);
        ind = tune_indicator_update(&indicator, raw, sync, nin);
        if (raw) {
            res.raw_nonzero++;
            if (sync) res.raw_in_sync++;
            if (res.first_raw_s < 0.0) res.first_raw_s = t;
        }
        /* Before the indication is judged: the call that acquires counts as
           "in sync", which is exactly where the library still reports the
           old direction. */
        if (sync && !res.ever_sync) { res.ever_sync = 1; res.sync_at_s = t; }
        if (ind) {
            if (ind > 0) res.saw_plus = 1; else res.saw_minus = 1;
            if (res.first_ind_s < 0.0) res.first_ind_s = t;
            res.last_ind_s = t;
            if (t <= 0.0) res.ind_before_signal = 1;
            if (res.ever_sync) res.ind_after_sync = 1;
        }
        if (was_sync && !sync) res.clock_ppm_unsync = rade_clock_offset_ppm(r);
        was_sync = sync;
        if (!clock_taken && t >= res.signal_end_s) {
            res.clock_ppm = rade_clock_offset_ppm(r);
            clock_taken = 1;
        }
    }
    res.ind_at_end = ind != 0;
    res.clock_ppm_final = rade_clock_offset_ppm(r);

    free(rxIn); free(eooOut); free(featOut); free(pcm); free(feat); free(tx);
    rade_close(r);
    rade_finalize();
    return res;
}

static int failures;
static void expect(int ok, const char *what) {
    fprintf(stderr, "    %s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) failures++;
}

static result report(const scenario *sc) {
    fprintf(stderr, "=== %s ===\n", sc->name);
    result res = run(sc);
    fprintf(stderr, "    sync=%d", res.ever_sync);
    if (res.ever_sync) fprintf(stderr, " (at %.2f s)", res.sync_at_s);
    fprintf(stderr, ", indicator=%s", res.saw_plus && res.saw_minus ? "+1 and -1"
                                    : res.saw_plus ? "+1" : res.saw_minus ? "-1" : "none");
    if (res.first_ind_s >= 0.0)
        fprintf(stderr, " (from %.2f s to %.2f s; signal ends at %.2f s)",
                res.first_ind_s, res.last_ind_s, res.signal_end_s);
    if (res.first_raw_s >= 0.0)
        fprintf(stderr, ", rade_mistune() from %.2f s", res.first_raw_s);
    fprintf(stderr, ", rade_mistune()!=0 while in sync: %ld call%s, clock=%.1f ppm\n",
            res.raw_in_sync, res.raw_in_sync == 1 ? "" : "s", (double)res.clock_ppm);
    return res;
}

/* A V2 signal the receiver must refuse to sync on, with the indicator
   pointing one way only, appearing and clearing when the app promises. */
static void expect_mistuned(const result *res, int direction) {
    expect(!res->ever_sync, "receiver never syncs");
    if (direction > 0) {
        expect(res->saw_plus, "indicator reports +1 (signal too high)");
        expect(!res->saw_minus, "indicator never reports -1");
    } else {
        expect(res->saw_minus, "indicator reports -1 (signal too low)");
        expect(!res->saw_plus, "indicator never reports +1");
    }
    expect(!res->ind_before_signal, "no indication before the signal starts");
    expect(res->first_raw_s > 0.0 && res->first_raw_s <= 3.0,
           "rade_mistune() reports it within 3 s of the signal starting");
    expect(res->first_ind_s >= res->first_raw_s + 2.9 && res->first_ind_s <= res->first_raw_s + 3.1,
           "indication is shown once the modem has held that direction for 3 s");
    expect(res->last_ind_s > res->signal_end_s - 0.1
               && res->last_ind_s <= res->signal_end_s + 3.5,
           "indication lasts to the end of the signal and clears within 3.5 s");
    expect(!res->ind_at_end, "no indication once the signal is gone");
}

int main(void) {
    result res;

    scenario high = { .name = "V2 signal 45 Hz too high",
                      .v2 = 1, .signal_s = 8.0, .offset_hz = 45.0 };
    res = report(&high);
    expect_mistuned(&res, +1);

    scenario low = { .name = "V2 signal 45 Hz too low",
                     .v2 = 1, .signal_s = 8.0, .offset_hz = -45.0 };
    res = report(&low);
    expect_mistuned(&res, -1);

    /* Phone microphone levels are low; the receiver's input AGC covers it. */
    scenario quiet = { .name = "V2 signal 45 Hz too low, 30 dB below nominal level",
                       .v2 = 1, .signal_s = 8.0, .offset_hz = -45.0, .level_db = -30.0 };
    res = report(&quiet);
    expect_mistuned(&res, -1);

    scenario tuned = { .name = "V2 signal on frequency",
                       .v2 = 1, .signal_s = 6.0 };
    res = report(&tuned);
    expect(res.ever_sync, "receiver syncs");
    expect(!res.saw_plus && !res.saw_minus, "indicator stays 0");

    /* The operator follows the hint: 45 Hz too high for 7 s, then one step of
       60 Hz in the indicated direction (the hint's "about 60 Hz"). */
    scenario retune = { .name = "V2 signal 45 Hz too high, receiver tuned up 60 Hz after 7 s",
                        .v2 = 1, .signal_s = 12.0, .offset_hz = 45.0,
                        .retune = 1, .retune_at_s = 7.0, .retune_hz = -15.0 };
    res = report(&retune);
    expect(res.saw_plus && !res.saw_minus, "indicator reports +1 only");
    expect(res.first_ind_s > 0.0 && res.first_ind_s < 7.0, "indication appears before the retune");
    expect(res.ever_sync && res.sync_at_s > 7.0 && res.sync_at_s < 8.0, "receiver syncs within 1 s of the retune");
    expect(!res.ind_after_sync, "no indication from the moment of sync on");

    /* A direction the modem holds for less than 3 s is not shown: a 2 s
       signal has rade_mistune() non-zero from about 1.3 s until its 2 s hold
       runs out at about 3.9 s, which is 2.6 s. */
    scenario brief = { .name = "V2 signal 45 Hz too high for 2 s only",
                       .v2 = 1, .signal_s = 2.0, .offset_hz = 45.0 };
    res = report(&brief);
    expect(res.raw_nonzero > 0, "rade_mistune() does report it");
    expect(!res.saw_plus && !res.saw_minus, "indicator stays 0");

    scenario noise = { .name = "V2 receiver, noise only",
                       .v2 = 1, .noise_rms = 2000.0, .tail_s = 10.0 };
    res = report(&noise);
    expect(!res.ever_sync, "receiver never syncs");
    expect(!res.saw_plus && !res.saw_minus, "indicator stays 0");

    /* V1 has neither indicator; it corrects a 45 Hz error by itself. */
    scenario v1 = { .name = "V1 signal 45 Hz too high, Rx clock 100 ppm fast",
                    .v2 = 0, .signal_s = 6.0, .offset_hz = 45.0, .clock_ppm = 100.0 };
    res = report(&v1);
    expect(res.ever_sync, "receiver syncs");
    expect(res.raw_nonzero == 0, "rade_mistune() is 0 on every call");
    expect(res.clock_ppm == 0.0f && res.clock_ppm_final == 0.0f, "rade_clock_offset_ppm() is 0");

    /* After 30 s the estimate has covered about three quarters of the way,
       so only its sign and rough size are checked. */
    scenario fast = { .name = "V2 signal on frequency, Rx clock 100 ppm fast",
                      .v2 = 1, .signal_s = 30.0, .clock_ppm = 100.0 };
    res = report(&fast);
    expect(res.ever_sync, "receiver syncs");
    expect(!res.saw_plus && !res.saw_minus, "indicator stays 0");
    expect(res.clock_ppm > 40.0f && res.clock_ppm < 130.0f, "clock offset estimate is +40..+130 ppm");
    expect(res.clock_ppm_final == res.clock_ppm_unsync && res.clock_ppm_final > 40.0f,
           "estimate is held after sync is lost");

    scenario slow = { .name = "V2 signal on frequency, Rx clock 100 ppm slow",
                      .v2 = 1, .signal_s = 30.0, .clock_ppm = -100.0 };
    res = report(&slow);
    expect(res.ever_sync, "receiver syncs");
    expect(!res.saw_plus && !res.saw_minus, "indicator stays 0");
    expect(res.clock_ppm < -40.0f && res.clock_ppm > -130.0f, "clock offset estimate is -40..-130 ppm");

    fprintf(stderr, "tunecheck: %d failure%s\n", failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
