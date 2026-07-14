/* loopback.c — reproduce the app's exact RX flow: rade_tx() frames into a
 * signal buffer, then feed them to rade_rx() in rade_nin() chunks, watching
 * sync/decode (the "signal received" moment) under ASAN. Mirrors
 * AudioEngine::processModemFrame buffer sizing precisely. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "rade_api.h"

static int run(int flags, const char *label) {
    fprintf(stderr, "=== %s ===\n", label);
    rade_initialize();
    struct rade *r = rade_open(NULL, flags);
    if (!r) { fprintf(stderr, "open failed\n"); return 1; }

    int nFeat = rade_n_features_in_out(r);
    int nTxOut = rade_n_tx_out(r);
    int nEooOut = rade_n_tx_eoo_out(r);
    int ninMax = rade_nin_max(r);
    int nEooBits = rade_n_eoo_bits(r);
    fprintf(stderr, "nFeat=%d nTxOut=%d nEooOut=%d ninMax=%d nEooBits=%d\n",
            nFeat, nTxOut, nEooOut, ninMax, nEooBits);

    /* TX 25 modem frames + EOO, like an over */
    int frames = 25;
    RADE_COMP *sig = calloc((size_t)frames * nTxOut + nEooOut + 4 * ninMax,
                            sizeof(RADE_COMP));
    float *feat = calloc(nFeat, sizeof(float));
    long sigLen = 0;
    for (int f = 0; f < frames; f++) {
        for (int i = 0; i < nFeat; i++) feat[i] = 0.1f * (float)((i + f) % 7);
        sigLen += rade_tx(r, &sig[sigLen], feat);
    }
    if (nEooBits > 0) {
        float *eooBits = calloc(nEooBits, sizeof(float));
        for (int i = 0; i < nEooBits; i++) eooBits[i] = (i & 1) ? 1.0f : -1.0f;
        rade_tx_set_eoo_bits(r, eooBits);
        free(eooBits);
    }
    sigLen += rade_tx_eoo(r, &sig[sigLen]);
    fprintf(stderr, "tx signal: %ld samples\n", sigLen);

    /* RX exactly like the app: chunk = rade_nin(), features buffer sized
       rade_n_features_in_out(), eoo buffer rade_n_eoo_bits() */
    long pos = 0;
    int synced = 0, featTotal = 0, eooSeen = 0;
    float *featOut = calloc(nFeat > 0 ? nFeat : 1, sizeof(float));
    float *eooOut = calloc(nEooBits > 0 ? nEooBits : 1, sizeof(float));
    while (pos + rade_nin(r) <= sigLen) {
        int nin = rade_nin(r);
        int hasEoo = 0;
        int n = rade_rx(r, featOut, &hasEoo, eooOut, &sig[pos]);
        pos += nin;
        featTotal += n;
        if (hasEoo) eooSeen++;
        int s = rade_sync(r);
        if (s && !synced) {
            synced = 1;
            fprintf(stderr, "SYNC at sample %ld (snr=%.1f foff=%.1f)\n",
                    pos, (double)rade_snrdB_3k_est(r), (double)rade_freq_offset(r));
        }
    }
    fprintf(stderr, "done: synced=%d featTotal=%d eooSeen=%d\n",
            synced, featTotal, eooSeen);

    free(featOut); free(eooOut); free(feat); free(sig);
    rade_close(r);
    rade_finalize();
    return synced ? 0 : 2;
}

int main(void) {
    int rc1 = run(RADE_USE_C_ENCODER | RADE_USE_C_DECODER, "V1");
    int rc2 = run(RADE_USE_C_ENCODER | RADE_USE_C_DECODER | RADE_MODE_V2, "V2");
    fprintf(stderr, "V1 rc=%d, V2 rc=%d\n", rc1, rc2);
    return rc1 || rc2;
}
