/*---------------------------------------------------------------------------*\

  rade_rx_v2.h

  RADE V2 receiver: rate-Fs IQ samples in, speech features out.
  C port of rx2.py / RADEv2Receiver.

  Processing model: one OFDM symbol (sym_len = 160 samples) per call.
  - CP autocorrelation for timing/frequency acquisition and tracking.
  - ML frame sync (FrameSyncNet) to determine even/odd frame alignment.
  - EOO detection via channel time-domain sparsity.

\*---------------------------------------------------------------------------*/

/*
  Copyright (C) 2025 David Rowe

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

#ifndef RADE_RX_V2_H
#define RADE_RX_V2_H

#include "rade_v2_ofdm.h"
#include "rade_dec_v2.h"
#include "rade_dec_v2_data.h"
#include "rade_sync.h"
#include "rade_sync_data.h"
#include "rade_bpf.h"

#ifdef __cplusplus
extern "C" {
#endif

/*---------------------------------------------------------------------------*\
                            STATE MACHINE
\*---------------------------------------------------------------------------*/

#define RADE_RX_V2_IDLE  0
#define RADE_RX_V2_SYNC  1

/* Rx buffer: 3 symbols deep for autocorrelation */
#define RADE_V2_RX_BUF_SIZE  (3 * RADE_V2_SYM_LEN)

/* Output: dec_stride feature frames, padded to 36 floats each */
#define RADE_V2_FEATURES_OUT  (RADE_V2_FRAMES_PER_STEP * RADE_V2_NB_TOTAL_FEATURES)

/* Wrong carrier detector (see rade_rx_v2.c update_wrong_k()). rho is computed over the
   last RADE_V2_WK_N symbols at a lag of one frame (2 symbols), and the mistune direction
   uses lags of one frame +/- RADE_V2_DIR_D samples, so the history buffer holds
   (RADE_V2_WK_N + 2) symbols + RADE_V2_DIR_D samples. */
#define RADE_V2_WK_N        16
#define RADE_V2_DIR_D       4
#define RADE_V2_WK_BUF_SIZE ((RADE_V2_WK_N + 2) * RADE_V2_SYM_LEN + RADE_V2_DIR_D)

/*---------------------------------------------------------------------------*\
                              RECEIVER STATE
\*---------------------------------------------------------------------------*/

typedef struct {
    /* DSP components */
    rade_v2_ofdm   ofdm;
    rade_bpf       bpf;
    int            bpf_en;

    /* V2 ML models */
    RADEDecV2      dec_model;
    RADEDecV2State dec_state;
    RADESync       sync_model;

    /* State machine */
    int   state;          /* RADE_RX_V2_IDLE or RADE_RX_V2_SYNC */
    int   count;          /* Signal detection counter (idle) or hangover (sync) */
    int   count1;         /* Re-acquire counter */
    int   s;              /* Symbol counter */
    int   i;              /* Output frame counter */
    int   timing_adj;     /* Enable timing adjustment after n symbols */
    int   n_acq;          /* Number of acquisitions */
    int   hangover;       /* Hangover symbols before un-sync (default 75) */

    /* Input AGC: normalises rx sample RMS to agc_target before buffering,
       clipped to +/-20dB (0.1 to 10.0 gain). On by default. Gain is derived
       from a persistent IIR-smoothed power estimate (agc_power), matching
       radae_v2.py's _compute_gain(). */
    int   agc_en;
    float agc_target;
    float agc_power;
    float gain;            /* AGC gain applied to the current symbol */

    /* Timing / frequency tracking */
    float delta_hat;      /* Tracked timing offset (PI or IIR loop) */
    float delta_rate;     /* PI timing loop: estimated drift, samples/symbol */
    int   timing_pi;      /* 1 (default): PI timing loop, 0: legacy one pole IIR */
    float delta_hat_g;    /* Instantaneous timing offset (argmax |Ry_smooth|) */
    float freq_offset;    /* IIR-smoothed frequency offset (Hz) */
    float freq_offset_g;  /* Instantaneous frequency offset (Hz) */
    float Ry_max;
    float Ry_min;
    int   new_sig_delta_hat;
    int   new_sig_f_hat;

    /* SNR estimation */
    float snr_est_dB;
    float snr_offset_dB;
    float snr_corr_a;
    float snr_corr_b;

    /* Frame sync (even/odd accumulation) */
    float frame_sync_even;
    float frame_sync_odd;

    /* EOO detection */
    float eoo_smooth;

    /* Wrong carrier detector and mistune direction. The CP autocorrelation freq
       estimate only covers +/-Fs/(2M) (+/-31.25 Hz); a larger offset aliases by a
       whole carrier (62.5 Hz) and, without this detector, the receiver syncs and
       decodes garbled speech. */
    int       wrong_k_en;    /* 1 (default): acquisition veto, in sync check, mistune */
    float     dir_bias;      /* mistune direction slope with carriers aligned (deg/sample),
                                model specific (RADE_V2_DIR_BIAS) */
    float     fc;            /* centre of the carriers (Hz) */
    float     rho;           /* frame to frame correlation over the last WK_N symbols */
    float     rho_sync;      /* smoothed rho while in sync */
    int       wrong_k;       /* rho says the carriers are misaligned */
    int       n_veto;        /* acquisitions vetoed */
    int       n_wk_drop;     /* syncs dropped by the in sync check */
    int       n_nonfinite;   /* input blocks discarded as non-finite (NaN/Inf) */
    RADE_COMP dir_Q;         /* mistune direction evidence */
    int       dir_n;
    int       dir_last;      /* symbol of last veto or in sync drop */
    float     dir_slope;
    int       mistune;       /* +1 signal too high in freq, -1 too low, 0 unknown/OK */
    RADE_COMP wk_buf[RADE_V2_WK_BUF_SIZE];

    /* Buffers */
    RADE_COMP rx_buf[RADE_V2_RX_BUF_SIZE];     /* 3 * sym_len complex samples */
    RADE_COMP rx_i[2 * RADE_V2_SYM_LEN];       /* Last Ns=2 freq-corrected symbols */
    RADE_COMP rx_sym_td[RADE_V2_M];            /* Last symbol M samples (for EOO) */
    RADE_COMP rx_phase;                         /* Continuous phase rotator */
    RADE_COMP Ry_norm[RADE_V2_SYM_LEN];
    RADE_COMP Ry_smooth[RADE_V2_SYM_LEN];

    float az_hat[RADE_V2_LATENT_DIM];          /* Last latent vector from demod */

    /* Last received BPSK data symbol (soft decision, +/-1 range) */
    float data_symbol;

    /* nin: samples required for next call */
    int nin;

    /* Verbosity */
    int verbose;

} rade_rx_v2_state;

/*---------------------------------------------------------------------------*\
                            FUNCTIONS
\*---------------------------------------------------------------------------*/

/* Initialise V2 receiver.
   bpf_en: 1 to enable input bandpass filter, 0 to disable.
   Returns 0 on success, -1 on failure. */
int rade_rx_v2_init(rade_rx_v2_state *rx, int bpf_en);

/* Samples needed for the next call to rade_rx_v2_process (always sym_len = 160,
   except when timing adjustment shifts it by sym_len/4). */
int rade_rx_v2_nin(const rade_rx_v2_state *rx);

/* Maximum samples ever needed (for static buffer allocation). */
int rade_rx_v2_nin_max(void);

/* Number of output float values when a frame is produced.
   = RADE_V2_FRAMES_PER_STEP * RADE_V2_NB_TOTAL_FEATURES = 144 */
int rade_rx_v2_n_features_out(void);

/* Process nin IQ samples.
   rx_in:        [nin] complex input samples
   features_out: [RADE_V2_FEATURES_OUT] output (valid only when return & 1)

   Return flags:
     bit 0 (0x1): features_out contains valid decoded features
     bit 1 (0x2): end-of-over detected */
int rade_rx_v2_process(rade_rx_v2_state *rx, float *features_out,
                       const RADE_COMP *rx_in);

/* Returns the Tx/Rx sample clock offset in ppm estimated by the PI timing
   loop, positive when the Rx sample clock is faster than the Tx. Updated
   while in sync and takes ~30 seconds to settle after acquisition. Held
   after sync is lost (e.g. at the end of an over) and reset to 0 on the
   next acquisition. Returns 0 if the legacy IIR timing loop is selected. */
float rade_rx_v2_clock_offset_ppm(const rade_rx_v2_state *rx);

/* Tuning indicator from the wrong carrier detector: +1 the received signal is too
   high in (audio) frequency, -1 too low, 0 no indication.
   Only non-zero while acquisition is being prevented because the frequency offset is
   outside the receiver's +/-31.25 Hz range (typically after ~1.5 s). */
int rade_rx_v2_mistune(const rade_rx_v2_state *rx);

/* Returns the last received BPSK data symbol (soft decision, valid after
   rade_rx_v2_process returns with bit 0 set). */
float rade_rx_v2_get_data_symbol(const rade_rx_v2_state *rx);

#ifdef __cplusplus
}
#endif

#endif /* RADE_RX_V2_H */
