/*
 * rade_all.c - Unity build file for the RADE C library.
 *
 * This file #includes all RADE C source files into a single compilation unit.
 * This approach ensures all symbols are compiled and linked into the app,
 * since Xcode's project tools may not always add files to the target's
 * "Compile Sources" build phase.
 *
 * The individual .c files exist on disk in the same directory but are NOT
 * added to the Xcode project's compile sources to avoid duplicate symbols.
 */

/* Neural network primitives - rade_nnet.c is compiled separately by Xcode
   as its own compilation unit, so we do NOT #include it here. */

/* Neural network weight data (encoder ~24MB + decoder ~24MB) */
#include "rade_enc_data.c"
#include "rade_dec_data.c"

/* FFT library */
#include "kiss_fft.c"
#include "kiss_fftr.c"

/* DSP utilities */
#include "rade_dsp.c"

/* OFDM modem */
#include "rade_ofdm.c"

/* Bandpass filter */
#include "rade_bpf.c"

/* Acquisition (pilot detection / sync) */
#include "rade_acq.c"

/* Encoder and decoder
   Both rade_enc.c and rade_dec.c define a static conv1_cond_init().
   In a unity build they collide, so rename the decoder's copy. */
#include "rade_enc.c"
#define conv1_cond_init dec_conv1_cond_init
#include "rade_dec.c"
#undef conv1_cond_init

/* Transmitter and receiver */
#include "rade_tx.c"
#include "rade_rx.c"

/* Top-level API (Python-free implementation) */
#include "rade_api_nopy.c"
