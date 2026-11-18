/**
 * eoo_callsign_codec_c.h
 *
 * C-linkage wrapper for EooCallsignDecoder (C++ class).
 * Allows Swift to call the EOO callsign encode/decode functions
 * via the bridging header.
 */

#ifndef EOO_CALLSIGN_CODEC_C_H
#define EOO_CALLSIGN_CODEC_C_H

#ifdef __cplusplus
extern "C" {
#endif

/** Complex symbols the callsign occupies: the LDPC(112,56) codeword as QPSK,
 *  i.e. the first 112 floats of the EOO buffer. */
#define EOO_CALLSIGN_MIN_SYMBOLS 56

/** Smallest callsign buffer the decoder accepts: the longest callsign is
 *  8 characters (48 payload bits, 6 per character) plus the terminator. */
#define EOO_CALLSIGN_BUF_SIZE 9

/**
 * Decode callsign from EOO soft-decision QPSK symbols.
 *
 * @param syms       Float array from rade_rx() eoo_out: interleaved I,Q pairs,
 *                   two floats per complex symbol.
 * @param sym_size   Number of complex symbols in syms (= rade_n_eoo_bits() / 2,
 *                   half the float count; 90 on RADE V1). Pass the whole
 *                   count, not just the 56 that carry the callsign: it is also
 *                   the divisor of the RMS the symbols are normalised with.
 *                   Passing the float count decodes fewer callsigns at
 *                   marginal SNR (app/src/test/cpp/eoo_callsign_snr_sweep.cpp).
 * @param callsign   Output buffer for decoded callsign (null-terminated).
 *                   Written only when the decode succeeds.
 * @param max_len    Size of callsign buffer (at least EOO_CALLSIGN_BUF_SIZE).
 * @return           1 if decode succeeded (BER < 0.2 and CRC-8 passed), 0 otherwise.
 *                   Also 0, without reading syms, when syms or callsign is
 *                   NULL, sym_size < EOO_CALLSIGN_MIN_SYMBOLS or
 *                   max_len < EOO_CALLSIGN_BUF_SIZE.
 */
int eoo_callsign_decode(const float *syms, int sym_size,
                        char *callsign, int max_len);

/**
 * Encode callsign into QPSK symbols for EOO transmission.
 *
 * @param callsign    Callsign string (up to 8 chars, A-Z/0-9/punctuation).
 * @param syms        Output float buffer for interleaved I/Q pairs.
 * @param float_count Total size of syms[] in floats (= rade_n_eoo_bits()).
 */
void eoo_callsign_encode(const char *callsign, float *syms, int float_count);

#ifdef __cplusplus
}
#endif

#endif /* EOO_CALLSIGN_CODEC_C_H */
