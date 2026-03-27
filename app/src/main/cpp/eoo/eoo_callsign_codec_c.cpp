/**
 * eoo_callsign_codec_c.cpp
 *
 * C-linkage wrapper implementation for EooCallsignDecoder (C++ class).
 * Bridges the C++ API to plain C so Swift can call it via the bridging header.
 */

#include "eoo_callsign_codec_c.h"
#include "EooCallsignCodec.h"

#include <cstring>
#include <string>

extern "C" {

int eoo_callsign_decode(const float *syms, int sym_size,
                        char *callsign, int max_len)
{
    EooCallsignDecoder decoder;
    std::string result;

    if (!decoder.decode(syms, sym_size, result))
        return 0;

    // Copy decoded callsign to output buffer
    const int copy_len = (int)result.size() < (max_len - 1)
                       ? (int)result.size()
                       : (max_len - 1);
    std::memcpy(callsign, result.c_str(), copy_len);
    callsign[copy_len] = '\0';

    return 1;
}

void eoo_callsign_encode(const char *callsign, float *syms, int float_count)
{
    EooCallsignDecoder encoder;
    std::string cs(callsign);
    encoder.encode(cs, syms, float_count);
}

} // extern "C"
