/* audiocheck.c — full speech A/B through the app's exact chain:
 *   16k wav → lpcnet features → rade_tx → rade_rx → FARGAN → 16k wav
 * for V1 and V2, mirroring AudioEngine's TX pump and synthesizeSpeech
 * (including the FARGAN warmup behavior). Prints duration/energy stats. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "rade_api.h"
#include "lpcnet.h"
#include "fargan.h"

#define FRAME 160          /* 10 ms @ 16 kHz */
#define NB_TOTAL 36
#define WARMUP_FRAMES 5    /* mirrors audio_engine.h FARGAN_WARMUP_FRAMES */

static short *read_wav16(const char *path, long *n) {
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(1); }
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 44, SEEK_SET);
    long ns = (sz - 44) / 2;
    short *d = malloc(ns * 2);
    fread(d, 2, ns, f); fclose(f);
    *n = ns; return d;
}
static void write_wav16(const char *path, const short *d, long n) {
    FILE *f = fopen(path, "wb");
    unsigned int u; unsigned short s;
    fwrite("RIFF", 1, 4, f); u = 36 + n * 2; fwrite(&u, 4, 1, f);
    fwrite("WAVEfmt ", 1, 8, f); u = 16; fwrite(&u, 4, 1, f);
    s = 1; fwrite(&s, 2, 1, f); s = 1; fwrite(&s, 2, 1, f);
    u = 16000; fwrite(&u, 4, 1, f); u = 32000; fwrite(&u, 4, 1, f);
    s = 2; fwrite(&s, 2, 1, f); s = 16; fwrite(&s, 2, 1, f);
    fwrite("data", 1, 4, f); u = n * 2; fwrite(&u, 4, 1, f);
    fwrite(d, 2, n, f); fclose(f);
}

static void run(int flags, const char *label, const short *in, long nIn,
                const char *outPath) {
    fprintf(stderr, "=== %s ===\n", label);
    rade_initialize();
    struct rade *r = rade_open(NULL, flags);
    if (!r) { fprintf(stderr, "open failed\n"); exit(1); }
    int nFeat = rade_n_features_in_out(r);
    int nTxOut = rade_n_tx_out(r);
    int framesPerTx = nFeat / NB_TOTAL;

    LPCNetEncState *enc = lpcnet_encoder_create();
    FARGANState fargan; memset(&fargan, 0, sizeof(fargan));
    fargan_init(&fargan);

    long nFrames = nIn / FRAME;
    long maxSig = (nFrames / framesPerTx + 4) * nTxOut + rade_n_tx_eoo_out(r);
    RADE_COMP *sig = calloc(maxSig, sizeof(RADE_COMP));
    long sigLen = 0;

    /* TX pump: encode 10ms frames, accumulate framesPerTx, rade_tx */
    float *featAccum = calloc(nFeat, sizeof(float));
    int accum = 0;
    for (long f = 0; f < nFrames; f++) {
        float feats[NB_TOTAL];
        lpcnet_compute_single_frame_features(enc, &in[f * FRAME], feats, 0);
        memcpy(&featAccum[accum * NB_TOTAL], feats, sizeof(feats));
        if (++accum == framesPerTx) {
            sigLen += rade_tx(r, &sig[sigLen], featAccum);
            accum = 0;
        }
    }
    sigLen += rade_tx_eoo(r, &sig[sigLen]);
    fprintf(stderr, "tx: %ld samples (%.1f s RF)\n", sigLen, sigLen / 8000.0);

    /* RX + FARGAN synthesis, mirroring synthesizeSpeech */
    float *featOut = calloc(nFeat, sizeof(float));
    int nEooBits = rade_n_eoo_bits(r);
    float *eooOut = calloc(nEooBits > 0 ? nEooBits : 1, sizeof(float));
    short *out = calloc(nIn + 16 * FRAME, 2);
    long outLen = 0;
    int warm = 0, ready = 0, syncSeen = 0;
    float warmFeat[WARMUP_FRAMES * NB_TOTAL];
    long pos = 0;
    while (pos + rade_nin(r) <= sigLen) {
        int nin = rade_nin(r);
        int hasEoo = 0;
        int result = rade_rx(r, featOut, &hasEoo, eooOut, &sig[pos]);
        pos += nin;
        if (rade_sync(r)) syncSeen = 1;
        if (result > 0) {
            int nF = result / NB_TOTAL;
            for (int f = 0; f < nF; f++) {
                const float *ff = &featOut[f * NB_TOTAL];
                if (!ready) {
                    /* mirrors the fixed synthesizeSpeech: collect 5 frames,
                       repack to NB_FEATURES(20) stride, one fargan_cont */
                    memcpy(&warmFeat[warm * NB_TOTAL], ff, NB_TOTAL * sizeof(float));
                    if (++warm >= WARMUP_FRAMES) {
                        float cont[WARMUP_FRAMES * 20];
                        for (int k = 0; k < WARMUP_FRAMES; k++)
                            memcpy(&cont[k * 20], &warmFeat[k * NB_TOTAL], 20 * sizeof(float));
                        float sil[FARGAN_CONT_SAMPLES] = {0};
                        fargan_cont(&fargan, sil, cont);
                        ready = 1;
                    }
                    continue;
                }
                fargan_synthesize_int(&fargan, &out[outLen], ff);
                outLen += FRAME;
            }
        }
    }
    double rms = 0; short peak = 0;
    for (long i = 0; i < outLen; i++) {
        rms += (double)out[i] * out[i];
        if (abs(out[i]) > peak) peak = abs(out[i]);
    }
    rms = outLen ? sqrt(rms / outLen) : 0;
    fprintf(stderr, "%s: sync=%d out=%.2fs (in %.2fs) rms=%.0f peak=%d\n",
            label, syncSeen, outLen / 16000.0, nIn / 16000.0, rms, (int)peak);
    write_wav16(outPath, out, outLen);

    free(sig); free(featAccum); free(featOut); free(eooOut); free(out);
    lpcnet_encoder_destroy(enc);
    rade_close(r);
    rade_finalize();
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: audiocheck in16k.wav\n"); return 1; }
    long nIn = 0;
    short *in = read_wav16(argv[1], &nIn);
    run(RADE_USE_C_ENCODER | RADE_USE_C_DECODER, "V1", in, nIn, "out_v1.wav");
    run(RADE_USE_C_ENCODER | RADE_USE_C_DECODER | RADE_MODE_V2, "V2", in, nIn, "out_v2.wav");
    free(in);
    return 0;
}
