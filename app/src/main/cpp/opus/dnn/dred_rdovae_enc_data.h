/* Stub: DRED encoder data not needed for RADE decode (RX-only FARGAN).
 * Provides the size constants referenced by dred_rdovae_enc.h structs. */
#ifndef DRED_RDOVAE_ENC_DATA_H
#define DRED_RDOVAE_ENC_DATA_H

#include "nnet.h"

#define ENC_GRU1_STATE_SIZE 1
#define ENC_GRU2_STATE_SIZE 1
#define ENC_GRU3_STATE_SIZE 1
#define ENC_GRU4_STATE_SIZE 1
#define ENC_GRU5_STATE_SIZE 1
#define ENC_CONV1_STATE_SIZE 1
#define ENC_CONV2_STATE_SIZE 1
#define ENC_CONV3_STATE_SIZE 1
#define ENC_CONV4_STATE_SIZE 1
#define ENC_CONV5_STATE_SIZE 1
#define ENC_DENSE1_OUT_SIZE 1
#define ENC_GLU1_OUT_SIZE 1
#define ENC_GLU2_OUT_SIZE 1
#define ENC_GLU3_OUT_SIZE 1
#define ENC_GLU4_OUT_SIZE 1
#define ENC_GLU5_OUT_SIZE 1
#define ENC_OUTPUT_OUT_SIZE 1

struct RDOVAEEnc {
    int dummy;
};

#endif
