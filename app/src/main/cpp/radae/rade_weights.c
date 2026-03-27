/*
 * rade_weights.c - Wrapper to include the large neural network weight data files.
 *
 * This file exists because the data files (rade_enc_data.c ~24MB, rade_dec_data.c ~24MB)
 * are too large to be added to Xcode directly through project manipulation tools.
 * Instead, this wrapper #includes them, and only this wrapper needs to be in the
 * Xcode project's compile sources.
 *
 * The actual data files must exist at the same directory level:
 *   Libraries/radae/rade_enc_data.c
 *   Libraries/radae/rade_dec_data.c
 */

/* Include the encoder weight data */
#include "rade_enc_data.c"

/* Include the decoder weight data */
#include "rade_dec_data.c"
