/*---------------------------------------------------------------------------*\

  rade_api.c

  Library of API functions that implement the Radio Autoencoder API.

\*---------------------------------------------------------------------------*/

/*
  Copyright (C) 2024 David Rowe

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions
  are met:

  - Redistributions of source code must retain the above copyright
  notice, this list of conditions and the following disclaimer.

  - Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in the
  documentation and/or other materials provided with the distribution.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
  ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
  LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
  A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION OR
  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

#define VERSION       2  /* Bump on breaking API changes */
#define VERSION_MINOR 1  /* Bump on non-breaking additions (e.g. new flags, new functions) */

#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "rade_api.h"
#include "rade_tx_v2.h"
#include "rade_rx_v2.h"

/*---------------------------------------------------------------------------*\
                        INITIALIZATION
\*---------------------------------------------------------------------------*/

void rade_initialize(void) {
    /* No initialization needed */
}

void rade_finalize(void) {
    /* No finalization needed */
}

struct rade *rade_open(char model_file[], int flags) {
    struct rade *r = (struct rade *)malloc(sizeof(struct rade));
    if (r == NULL) {
        fprintf(stderr, "rade_open: failed to allocate memory\n");
        return NULL;
    }
    memset(r, 0, sizeof(struct rade));

    r->flags = flags;
    r->auxdata = 1;
    r->bottleneck = 3;

    /* Note: model_file is ignored in this implementation
       Weights are compiled in via rade_enc_data.c and rade_dec_data.c */
    fprintf(stderr, "rade_open: model_file=%s (ignored, using built-in weights)\n", model_file);

    if (flags & RADE_MODE_V2) {
        /* Initialize V2 transmitter */
        if (rade_tx_v2_init(&r->tx_v2) != 0) {
            fprintf(stderr, "rade_open: failed to initialize V2 transmitter\n");
            free(r);
            return NULL;
        }

        /* Initialize V2 receiver (BPF enabled by default) */
        if (rade_rx_v2_init(&r->rx_v2, 1) != 0) {
            fprintf(stderr, "rade_open: failed to initialize V2 receiver\n");
            free(r);
            return NULL;
        }

        if (flags & RADE_VERBOSE_0)        r->rx_v2.verbose = 0;
        else if (flags & RADE_VERBOSE_FULL)  r->rx_v2.verbose = 3;
        else if (flags & RADE_VERBOSE_TERSE) r->rx_v2.verbose = 2;
        else                                 r->rx_v2.verbose = 0;

        fprintf(stderr, "rade_open: V2 n_features_in=%d Nmf=%d Neoo=%d\n",
                rade_tx_v2_n_features_in(),
                rade_tx_v2_n_samples_out(),
                rade_tx_v2_n_eoo_out());
    } else {
        /* Initialize V1 transmitter */
        int bpf_en = 0;  /* BPF disabled by default */
        if (rade_tx_init(&r->tx, NULL, r->bottleneck, r->auxdata, bpf_en) != 0) {
            fprintf(stderr, "rade_open: failed to initialize transmitter\n");
            free(r);
            return NULL;
        }

        /* Initialize V1 receiver */
        if (rade_rx_init(&r->rx, NULL, r->bottleneck, r->auxdata, 1) != 0) {
            fprintf(stderr, "rade_open: failed to initialize receiver\n");
            free(r);
            return NULL;
        }

        if (flags & RADE_VERBOSE_0)
            r->rx.verbose = 0;

        fprintf(stderr, "rade_open: V1 n_features_in=%d Nmf=%d Neoo=%d n_eoo_bits=%d\n",
                rade_tx_n_features_in(&r->tx),
                rade_tx_n_samples_out(&r->tx),
                rade_tx_n_eoo_out(&r->tx),
                rade_tx_n_eoo_bits(&r->tx));
    }

    return r;
}

void rade_close(struct rade *r) {
    if (r != NULL) {
        free(r);
    }
}

/*---------------------------------------------------------------------------*\
                         GETTERS
\*---------------------------------------------------------------------------*/

int rade_version(void) {
    return VERSION;
}

int rade_version_minor(void) {
    return VERSION_MINOR;
}

int rade_n_tx_out(struct rade *r) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return rade_tx_v2_n_samples_out();
    return rade_tx_n_samples_out(&r->tx);
}

int rade_n_tx_eoo_out(struct rade *r) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return rade_tx_v2_n_eoo_out();
    return rade_tx_n_eoo_out(&r->tx);
}

int rade_nin_max(struct rade *r) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return rade_rx_v2_nin_max();
    return rade_rx_nin_max(&r->rx);
}

int rade_nin(struct rade *r) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return rade_rx_v2_nin(&r->rx_v2);
    return rade_rx_nin(&r->rx);
}

int rade_n_features_in_out(struct rade *r) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return rade_tx_v2_n_features_in();
    return rade_tx_n_features_in(&r->tx);
}

int rade_n_eoo_bits(struct rade *r) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return 0;  /* V2 has no EOO data channel */
    return rade_tx_n_eoo_bits(&r->tx);
}

/*---------------------------------------------------------------------------*\
                         TRANSMISSION
\*---------------------------------------------------------------------------*/

RADE_EXPORT void rade_tx_set_eoo_bits(struct rade *r, float eoo_bits[]) {
    assert(r != NULL);
    assert(eoo_bits != NULL);
    rade_tx_state_set_eoo_bits(&r->tx, eoo_bits);
}


int rade_tx(struct rade *r, RADE_COMP tx_out[], float features_in[]) {
    assert(r != NULL);
    assert(features_in != NULL);
    assert(tx_out != NULL);

    if (r->flags & RADE_MODE_V2) return rade_tx_v2_process(&r->tx_v2, tx_out, features_in);
    return rade_tx_process(&r->tx, tx_out, features_in);
}

int rade_tx_eoo(struct rade *r, RADE_COMP tx_eoo_out[]) {
    assert(r != NULL);
    assert(tx_eoo_out != NULL);

    if (r->flags & RADE_MODE_V2) return rade_tx_v2_eoo(&r->tx_v2, tx_eoo_out);
    return rade_tx_state_eoo(&r->tx, tx_eoo_out);
}

/*---------------------------------------------------------------------------*\
                         RECEPTION
\*---------------------------------------------------------------------------*/

int rade_rx(struct rade *r, float features_out[], int *has_eoo_out, float eoo_out[], RADE_COMP rx_in[]) {
    assert(r != NULL);
    assert(features_out != NULL);
    assert(rx_in != NULL);

    if (r->flags & RADE_MODE_V2) {
        int ret = rade_rx_v2_process(&r->rx_v2, features_out, rx_in);
        *has_eoo_out = (ret & 0x2) ? 1 : 0;
        return (ret & 0x1) ? rade_rx_v2_n_features_out() : 0;
    }

    int ret = rade_rx_process(&r->rx, features_out, eoo_out, rx_in);
    *has_eoo_out = (ret & 0x2) ? 1 : 0;
    return (ret & 0x1) ? rade_rx_n_features_out(&r->rx) : 0;
}

int rade_sync(struct rade *r) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return (r->rx_v2.state == RADE_RX_V2_SYNC);
    return rade_rx_sync(&r->rx);
}

float rade_freq_offset(struct rade *r) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return r->rx_v2.freq_offset;
    return rade_rx_freq_offset(&r->rx);
}

float rade_snrdB_3k_est(struct rade *r) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return r->rx_v2.snr_est_dB;
    return (float)rade_rx_snrdB_3k_est(&r->rx);
}

void rade_set_disable_unsync(struct rade *r, float seconds) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return;  /* not supported in V2 */
    r->rx.disable_unsync = seconds;
}

void rade_tx_set_data_symbol(struct rade *r, float symbol) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) rade_tx_v2_set_data_symbol(&r->tx_v2, symbol);
}

float rade_rx_get_data_symbol(struct rade *r) {
    assert(r != NULL);
    if (r->flags & RADE_MODE_V2) return rade_rx_v2_get_data_symbol(&r->rx_v2);
    return 0.0f;
}
