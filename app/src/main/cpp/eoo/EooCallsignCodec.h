/**
 * EooCallsignCodec.h
 *
 * Declaration of EooCallsignDecoder: encodes and decodes the operator callsign
 * carried in a RADE End-of-Over (EOO) float symbol buffer.
 *
 * See EooCallsignCodec.cpp for implementation details and provenance.
 *
 * Provenance / License:
 *   Derived from codec2 1.2.0 (David Rowe et al.) and FreeDV GUI
 *   (Mooneer Salem et al.), both licensed under the GNU LGPL v2.1.
 *   This file is therefore also released under the LGPL v2.1.
 *   See <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>.
 */

#pragma once

#include <cstdint>
#include <string>

/**
 * Encodes and decodes the callsign carried in a RADE End-of-Over symbol buffer.
 *
 * Usage (decode):
 *   EooCallsignDecoder dec;
 *   std::string callsign;
 *   if (dec.decode(eooOut, rade_n_eoo_bits(dv) / 2, callsign))
 *       std::cout << callsign << "\n";
 *
 * Usage (encode):
 *   EooCallsignDecoder enc;
 *   std::vector<float> eooOut(rade_n_eoo_bits(dv));
 *   enc.encode("W1AW", eooOut.data(), eooOut.size());
 *   rade_tx_set_eoo_bits(dv, eooOut.data());
 */
class EooCallsignDecoder
{
public:
    /**
     * Attempt to decode the callsign from an EOO symbol buffer.
     *
     * @param syms      Float array from rade_rx() eooOut: interleaved I, Q
     *                  pairs, so syms[2*i] = real part, syms[2*i+1] = imag
     *                  part of symbol i.
     * @param symSize   Number of complex symbols = rade_n_eoo_bits(dv) / 2.
     * @param callsign  Set to the decoded callsign string on success.
     * @return          true if the LDPC BER estimate is < 0.2 and CRC-8 passes.
     */
    bool decode(const float *syms, int symSize, std::string &callsign) const;

    /**
     * Encode a callsign into QPSK symbols for an EOO float buffer.
     *
     * Mirrors rade_text_generate_tx_string() from rade_text.c.
     * Pass the returned buffer directly to rade_tx_set_eoo_bits().
     *
     * @param callsign   Callsign string (up to 8 chars).
     *                   Supported characters: A–Z (case-insensitive), 0–9,
     *                   and the punctuation set &'()*+,-./ (ASCII 38–47).
     *                   Unsupported characters are silently skipped.
     * @param syms       Output float buffer.  Interleaved I/Q pairs:
     *                   syms[2*i] = real part, syms[2*i+1] = imag part.
     * @param floatCount Total size of syms[] in floats = rade_n_eoo_bits(dv).
     *                   The first 112 floats carry the 56 LDPC-encoded QPSK
     *                   symbols.  floats[112..floatCount-1] are filled with
     *                   the known filler sequence required by the RADE decoder.
     */
    void encode(const std::string &callsign, float *syms, int floatCount) const;
};
