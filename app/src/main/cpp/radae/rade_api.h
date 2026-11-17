/*---------------------------------------------------------------------------*\

  rade_api.h

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

#ifndef __RADE_API__
#define __RADE_API__

#include <sys/types.h>

#if IS_BUILDING_RADE_API
#if _WIN32
#define RADE_EXPORT __declspec(dllexport) __stdcall
#else
#define RADE_EXPORT __attribute__((visibility("default")))
#endif // _WIN32
#else
#if _WIN32
#define RADE_EXPORT __declspec(dllimport) __stdcall
#else
#define RADE_EXPORT 
#endif // _WIN32
#endif // IS_BUILDING_RADE_API

// This declares a single-precision (float) complex number

#ifndef __RADE_COMP__
#define __RADE_COMP__
typedef struct {
  float real;
  float imag;
} RADE_COMP;
#endif

#include "rade_tx.h"
#include "rade_rx.h"
#include "rade_tx_v2.h"
#include "rade_rx_v2.h"

#ifdef __cplusplus
extern "C" {
#endif

// Sample rates used
#define RADE_MODEM_SAMPLE_RATE 8000           // modem waveform sample rate
#define RADE_SPEECH_SAMPLE_RATE 16000         // speech sample rate

// Scaling constant for int16 <-> float conversion.
//
// TX (float IQ -> int16):
//   int16 = Re{rade_tx() output} * RADE_INT16_SCALE
//   Nominal float IQ amplitude 1.0 maps to int16 value 16384,
//   leaving 6 dB of headroom to the int16 ceiling (32767).
//
// RX, complex IQ input (int16 -> float IQ, I and Q from separate channels):
//   float_I = int16_I / RADE_INT16_SCALE
//   float_Q = int16_Q / RADE_INT16_SCALE
//   Restores unit amplitude at the OFDM correlators.
//
// RX, real-valued input (int16 -> float, imag = 0, e.g. SSB radio or WAV file):
//   float = int16 * (2.0f / RADE_INT16_SCALE)
//   Re{} halves the magnitude of the positive frequency component we tune to;
//   the factor of 2 compensates, restoring unit amplitude.
#define RADE_INT16_SCALE 16384.0f

// init rade_open() flags
#define RADE_USE_C_ENCODER 0x1
#define RADE_USE_C_DECODER 0x2
#define RADE_FOFF_TEST     0x4                // test mode used only by developers
#define RADE_VERBOSE_0     0x8                // reduce verbosity to "quiet" (no per-frame output)
#define RADE_MODE_V2       0x10               // select RADE V2 (default is V1)
#define RADE_VERBOSE_TERSE 0x20               // terse per-frame status (state, sig, f_off, snr, eoo)
#define RADE_VERBOSE_FULL  0x40               // full per-frame status (all fields)
#define RADE_NO_TX_BPF     0x80               // V2 only: disable Tx SSB BPF (default enabled)

// Must be called BEFORE any other RADE functions as this
// initializes internal library state.
RADE_EXPORT void rade_initialize(void);

// Should be called when done with RADE.
RADE_EXPORT void rade_finalize(void);

struct rade {
    int flags;
    int auxdata;
    int bottleneck;

    /* V1 state */
    rade_tx_state    tx;
    rade_rx_state    rx;

    /* V2 state */
    rade_tx_v2_state tx_v2;
    rade_rx_v2_state rx_v2;
};

// note single context only in this version, one context has one Tx, and one Rx
RADE_EXPORT struct rade *rade_open(char model_file[], int flags);
RADE_EXPORT void rade_close(struct rade *r);

// Returns major version — incremented on breaking API changes
RADE_EXPORT int rade_version(void);
// Returns minor version — incremented on non-breaking additions
RADE_EXPORT int rade_version_minor(void);

// helpers to set up arrays
RADE_EXPORT int rade_n_tx_out(struct rade *r);
RADE_EXPORT int rade_n_tx_eoo_out(struct rade *r);  // V1 and V2
RADE_EXPORT int rade_nin_max(struct rade *r);
RADE_EXPORT int rade_n_features_in_out(struct rade *r);
RADE_EXPORT int rade_n_eoo_bits(struct rade *r);    // V1 only; returns 0 for V2

// returns number of RADE_COMP samples written to tx_out[]
RADE_EXPORT int rade_tx(struct rade *r, RADE_COMP tx_out[], float features_in[]);

// V1 only: set the rade_n_eoo_bits() bits to be sent in the EOO frame,
// in +/- 1 float form (note NOT 1 or 0)
RADE_EXPORT void rade_tx_set_eoo_bits(struct rade *r, float eoo_bits[]);

// Transmit the final EOO frame at end of over; returns the number of
// RADE_COMP samples written to tx_eoo_out[].  Works for both V1 and V2.
RADE_EXPORT int rade_tx_eoo(struct rade *r, RADE_COMP tx_eoo_out[]);

// call me before each call to rade_rx(), provide nin samples to rx_in[]
RADE_EXPORT int rade_nin(struct rade *r);

// returns non-zero if features_out[] contains valid output. The number
// returned is the number of samples written to features_out[].
// has_eoo_out is set when an End-of-Over frame is detected (V1 and V2).
// V1 only: if has_eoo_out is set, eoo_out[] contains EOO soft-decision bits
// from QPSK symbols in ..IQIQI... order; pass NULL for eoo_out in V2.
RADE_EXPORT int rade_rx(struct rade *r, float features_out[], int *has_eoo_out, float eoo_out[], RADE_COMP rx_in[]);

// returns non-zero if Rx is currently in sync
RADE_EXPORT int rade_sync(struct rade *r);

// returns the current frequency offset of the Rx signal ( when rade_sync()!=0 )
RADE_EXPORT float rade_freq_offset(struct rade *r);

// returns the current SNR estimate (in dB) of the Rx signal ( when rade_sync()!=0 )
RADE_EXPORT float rade_snrdB_3k_est(struct rade *r);

// V2 only: Tx/Rx sample clock offset (ppm) estimated by the Rx timing loop,
// positive when the Rx sample clock is faster than the Tx. Updated while
// rade_sync()!=0, settles ~30 seconds after acquisition, held after sync is
// lost (e.g. end of over), reset on the next acquisition. V2 tracks offsets
// up to +/-250 ppm; offsets beyond +/-125 ppm indicate out of spec hardware.
// Returns 0 for V1.
RADE_EXPORT float rade_clock_offset_ppm(struct rade *r);

// V2 only: tuning indicator. The V2 receiver can only correct frequency offsets
// within +/-31.25 Hz; larger offsets (up to about +/-94 Hz) are detected and the
// receiver will not sync. While this is happening, returns +1 if the received
// signal is too high in (audio) frequency, -1 if too low, 0 otherwise (no signal,
// in sync, or not enough evidence yet). Typically available ~1.5 s after the signal
// appears, and held for ~2 s after the last failed sync attempt. Intended for a
// "tune up/down" style indicator. Returns 0 for V1.
RADE_EXPORT int rade_mistune(struct rade *r);

// V2 only: per-symbol receiver diagnostics, valid after each rade_rx() call.
// Fields mirror rx2.py's diagnostic log (delta_hat/delta_hat_g/freq_offset/
// gain/snr_est) -- useful for plotting sync/timing/AGC behaviour over a file,
// e.g. to diagnose a false-sync-then-reacquire event. All fields are 0 for V1.
struct rade_stats {
    int   sync;          // 0 = idle, 1 = sync
    float delta_hat;      // tracked timing offset (samples)
    float delta_hat_g;    // instantaneous timing offset (samples)
    float freq_offset;    // IIR-smoothed frequency offset (Hz)
    float gain;           // AGC gain applied to the current symbol
    float snr_est;        // SNR estimate (dB)
};
RADE_EXPORT void rade_get_stats(struct rade *r, struct rade_stats *stats);

// test mode: disable unsync after this many seconds (0 = disabled)
RADE_EXPORT void rade_set_disable_unsync(struct rade *r, float seconds);

// V2 only (aux text channel): set BPSK data symbol to transmit in next modem frame (+1.0 or -1.0)
RADE_EXPORT void rade_tx_set_data_symbol(struct rade *r, float symbol);

// V2 only (aux text channel): get last received BPSK data symbol (soft decision, valid after rade_rx() returns > 0)
RADE_EXPORT float rade_rx_get_data_symbol(struct rade *r);

// V2 only: enable/disable input AGC (on by default). AGC normalises the RMS
// level of incoming rx samples to compensate for TX/RX gain-staging
// mismatches; without it, decode quality is sensitive to input level.
// Effective correction range is +/-20dB around the nominal rx level of 1.0
// (i.e. rx samples are expected to arrive already scaled to roughly that
// peak range -- AGC corrects mismatches within that window, not arbitrary
// input levels).
RADE_EXPORT void rade_rx_set_agc(struct rade *r, int enable);

#ifdef __cplusplus
}
#endif

#endif  //__RADE_API__
