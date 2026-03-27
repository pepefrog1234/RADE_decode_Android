/* Stub: DRED decoder data not needed for RADE decode (RX-only FARGAN).
 * Provides the size constants referenced by dred_rdovae_dec.h structs. */
#ifndef DRED_RDOVAE_DEC_DATA_H
#define DRED_RDOVAE_DEC_DATA_H

#include "nnet.h"

/* Minimal sizes so the struct declarations in dred_rdovae_dec.h compile.
 * These are never instantiated in our FARGAN-only build. */
#define DEC_GRU1_STATE_SIZE 1
#define DEC_GRU2_STATE_SIZE 1
#define DEC_GRU3_STATE_SIZE 1
#define DEC_GRU4_STATE_SIZE 1
#define DEC_GRU5_STATE_SIZE 1
#define DEC_CONV1_STATE_SIZE 1
#define DEC_CONV2_STATE_SIZE 1
#define DEC_CONV3_STATE_SIZE 1
#define DEC_CONV4_STATE_SIZE 1
#define DEC_CONV5_STATE_SIZE 1
#define DEC_DENSE1_OUT_SIZE 1
#define DEC_GLU1_OUT_SIZE 1
#define DEC_GLU2_OUT_SIZE 1
#define DEC_GLU3_OUT_SIZE 1
#define DEC_GLU4_OUT_SIZE 1
#define DEC_GLU5_OUT_SIZE 1
#define DEC_OUTPUT_OUT_SIZE 1

#define DRED_NUM_FEATURES 20
#define DRED_LATENT_DIM 24
#define DRED_STATE_DIM 80
#define DRED_NUM_QUANTIZATION_BOUNDARY 40
#define DRED_MAX_FRAMES 100

struct RDOVAEDec {
    int dummy;
};

#endif
