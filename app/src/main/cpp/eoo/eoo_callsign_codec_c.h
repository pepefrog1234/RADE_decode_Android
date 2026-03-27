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

/**
 * Decode callsign from EOO soft-decision QPSK symbols.
 *
 * @param syms       Float array from rade_rx() eoo_out: interleaved I,Q pairs.
 * @param sym_size   Number of complex symbols (= rade_n_eoo_bits() / 2).
 * @param callsign   Output buffer for decoded callsign (null-terminated).
 * @param max_len    Size of callsign buffer (should be >= 9).
 * @return           1 if decode succeeded (BER < 0.2 and CRC-8 passed), 0 otherwise.
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
