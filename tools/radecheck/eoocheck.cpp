/* eoocheck.cpp — the EOO callsign through the real V1 modem, with the buffer
 * handling and the decoder call of the app:
 *   TX  eoo_callsign_encode() -> rade_tx_set_eoo_bits() -> rade_tx_eoo(), real
 *       part to int16                                (AudioEngine::sendTxEoo)
 *   RX  int16 * RADE_RX_REAL_SCALE, imag = 0 -> rade_rx() in rade_nin() chunks
 *       -> eoo_callsign_decode(eoo, rade_n_eoo_bits() / 2, ...)
 *                                           (AudioEngine::processModemFrame)
 *
 *   ./build/eoocheck
 *       Clean channel. Exit code 0 = every callsign came back, the longest
 *       (8 characters) included, and V2 — which has no EOO payload — reported
 *       its end of over and decoded nothing.
 *   ./build/eoocheck TRIALS SEED SNR_DB [SNR_DB ...]
 *       Random callsigns in white noise at the given SNRs: power of the
 *       speech frames over the noise power in 3 kHz, in dB. "reading" is the
 *       modem's own estimate at the end of the over, the figure the receiver
 *       screen shows. Every EOO frame the receiver reports is decoded with
 *       sym_size = n (the float count, what the app passed up to v1.6.32) and
 *       with sym_size = n / 2 (the symbol count, what it passes now), so the
 *       two columns differ by that argument alone. Always exits 0; this mode
 *       is a measurement (see docs/release-notes/v1.6.34.md). Configure with
 *       -DCMAKE_BUILD_TYPE=Release for it: the default build is unoptimised.
 */
extern "C" {
#include "rade_api.h"
}
#include "eoo_callsign_codec_c.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <string>
#include <vector>

static const float RX_REAL_SCALE = 2.0f / RADE_INT16_SCALE;   /* audio_engine.h */
static const int BODY_FRAMES = 20;                            /* 2.4 s of over */
static const double PI = 3.14159265358979323846;

static int16_t txSample(float sample) {    /* generateTxOutput / sendTxEoo */
    return (int16_t)(std::min(std::max(sample, -0.999f), 0.999f) * 32767.0f);
}

/* mt19937_64 is specified bit for bit; std::normal_distribution is not. */
class Noise {
public:
    explicit Noise(uint64_t seed) : rng_(seed), haveSpare_(false), spare_(0.0) {}
    uint64_t bits() { return rng_(); }
    double gaussian() {
        if (haveSpare_) { haveSpare_ = false; return spare_; }
        const double u1 = ((rng_() >> 11) + 0.5) / 9007199254740992.0;
        const double u2 = ((rng_() >> 11) + 0.5) / 9007199254740992.0;
        const double radius = std::sqrt(-2.0 * std::log(u1));
        spare_ = radius * std::sin(2.0 * PI * u2);
        haveSpare_ = true;
        return radius * std::cos(2.0 * PI * u2);
    }
private:
    std::mt19937_64 rng_;
    bool haveSpare_;
    double spare_;
};

struct Heard {
    int eooFrames;                         /* has_eoo reports during the over */
    float snrReading;                      /* modem's SNR estimate at the first */
    std::vector<std::string> half, full;   /* callsigns decoded with sym_size
                                              = n / 2 and = n; at most one
                                              each per report */
    Heard() : eooFrames(0), snrReading(0.0f) {}
};

static bool contains(const std::vector<std::string> &list, const std::string &callsign) {
    return std::find(list.begin(), list.end(), callsign) != list.end();
}
static long others(const std::vector<std::string> &list, const std::string &callsign) {
    return (long)list.size() - (long)std::count(list.begin(), list.end(), callsign);
}

class Link {
public:
    explicit Link(int flags) : r_(rade_open(NULL, flags)), eooSamples_(0) {
        if (!r_) { std::fprintf(stderr, "rade_open failed\n"); std::exit(1); }
        nFeat_ = rade_n_features_in_out(r_);
        nEoo_ = rade_n_eoo_bits(r_);
        ninMax_ = rade_nin_max(r_);
        feat_.resize(nFeat_);
        frame_.resize(std::max(rade_n_tx_out(r_), rade_n_tx_eoo_out(r_)));
        features_.resize(nFeat_);
        eoo_.resize(nEoo_ > 0 ? nEoo_ : 1);
        rxIn_.resize(ninMax_);
    }
    ~Link() { rade_close(r_); }
    int eooFloats() const { return nEoo_; }

    /* One over as the app transmits it: speech frames, then the EOO frame.
       The receiver reports the EOO frame only once the audio after it has
       arrived, so silence of four receiver chunks follows (as in loopback.c). */
    std::vector<int16_t> transmit(const std::string &callsign) {
        std::vector<int16_t> audio;
        for (int f = 0; f < BODY_FRAMES; f++) {
            for (int i = 0; i < nFeat_; i++) feat_[i] = 0.1f * (float)((i + f) % 7);
            append(audio, rade_tx(r_, frame_.data(), feat_.data()));
        }
        if (!callsign.empty() && nEoo_ > 0) {
            std::vector<float> eooBits(nEoo_, 0.0f);
            eoo_callsign_encode(callsign.c_str(), eooBits.data(), nEoo_);
            rade_tx_set_eoo_bits(r_, eooBits.data());
        }
        append(audio, rade_tx_eoo(r_, frame_.data()));
        eooSamples_ = rade_n_tx_eoo_out(r_) + 4 * ninMax_;
        audio.resize(audio.size() + 4 * ninMax_, 0);
        return audio;
    }
    /* Samples at the end of transmit()'s result that are not speech frames. */
    int eooSamples() const { return eooSamples_; }

    /* Feed audio as the app does and decode every EOO frame reported. */
    void receive(const std::vector<int16_t> &audio, Heard *heard) {
        size_t pos = 0;
        while (pos + rade_nin(r_) <= audio.size()) {
            const int nin = rade_nin(r_);
            for (int i = 0; i < nin; i++) {
                rxIn_[i].real = (float)audio[pos + i] * RX_REAL_SCALE;
                rxIn_[i].imag = 0.0f;
            }
            pos += nin;
            int hasEoo = 0;
            rade_rx(r_, features_.data(), &hasEoo, eoo_.data(), rxIn_.data());
            if (!hasEoo) continue;
            if (heard->eooFrames++ == 0) heard->snrReading = rade_snrdB_3k_est(r_);
            char cs[32] = {0};
            /* processModemFrame() skips the decoder when nEoo is 0 (V2), where
               its vector is empty and data() null. The decoder's own guard
               must give the same answer, so make that call here. */
            if (eoo_callsign_decode(nEoo_ > 0 ? eoo_.data() : NULL, nEoo_ / 2, cs, sizeof(cs) - 1))
                heard->half.push_back(cs);
            if (nEoo_ > 0 && eoo_callsign_decode(eoo_.data(), nEoo_, cs, sizeof(cs) - 1))
                heard->full.push_back(cs);
        }
    }
    bool inSync() const { return rade_sync(r_) != 0; }

private:
    void append(std::vector<int16_t> &audio, int n) {
        for (int i = 0; i < n; i++) audio.push_back(txSample(frame_[i].real));
    }
    struct rade *r_;
    int eooSamples_, nFeat_, nEoo_, ninMax_;
    std::vector<float> feat_, features_, eoo_;
    std::vector<RADE_COMP> frame_, rxIn_;
};

static const int QUIET = RADE_USE_C_ENCODER | RADE_USE_C_DECODER | RADE_VERBOSE_0;

static int cleanChannel() {
    static const char *const callsigns[] = {"VK5KVA", "W1AW", "JH0VEQ", "BX4ACP", "AB1CDEFG"};
    int failures = 0;
    Link v1(QUIET);
    for (size_t i = 0; i < sizeof(callsigns) / sizeof(callsigns[0]); i++) {
        Heard heard;
        v1.receive(v1.transmit(callsigns[i]), &heard);
        const bool ok = heard.eooFrames == 1 && heard.half.size() == 1 && heard.half[0] == callsigns[i];
        std::fprintf(stderr, "V1 %-8s -> end of over x%d, callsign '%s' %s\n", callsigns[i],
                     heard.eooFrames, heard.half.empty() ? "" : heard.half[0].c_str(),
                     ok ? "ok" : "FAILED");
        failures += !ok;
    }
    Link v2(QUIET | RADE_MODE_V2);
    Heard heard;
    v2.receive(v2.transmit("VK5KVA"), &heard);
    const bool ok = v2.eooFloats() == 0 && heard.eooFrames == 1 && heard.half.empty();
    std::fprintf(stderr, "V2 (no EOO payload) -> end of over x%d, %d callsigns %s\n",
                 heard.eooFrames, (int)heard.half.size(), ok ? "ok" : "FAILED");
    failures += !ok;
    return failures ? 1 : 0;
}

static std::string randomCallsign(Noise &noise) {
    static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    const int length = 3 + (int)(noise.bits() % 6);
    std::string callsign;
    for (int i = 0; i < length; i++) callsign += alphabet[noise.bits() % 36];
    return callsign;
}

static int noiseSweep(long trials, uint64_t seed, int nSnr, char **snrArgs) {
    Link link(QUIET);

    /* Signal power of the speech frames, the reference the SNR is quoted
       against. Measured once so every over meets the same noise. */
    double power = 0.0;
    long count = 0;
    for (int i = 0; i < 4; i++) {
        const std::vector<int16_t> over = link.transmit("");
        for (size_t k = 0; k + link.eooSamples() < over.size(); k++) {
            power += (double)over[k] * over[k];
            count++;
        }
    }
    power /= count;

    std::printf("V1 EOO callsign through the modem; %ld overs per point, seed %llu\n",
                trials, (unsigned long long)seed);
    std::printf("%6s | %7s | %7s | %22s | %22s | %s\n", "", "", "end of", "sym_size = n",
                "sym_size = n / 2", "decoded by only");
    std::printf("%6s | %7s | %7s | %15s %6s | %15s %6s | %7s %7s\n", "SNR dB", "reading", "over",
                "decoded", "wrong", "decoded", "wrong", "n", "n / 2");
    for (int s = 0; s < nSnr; s++) {
        const double snrDb = std::atof(snrArgs[s]);
        /* White noise of variance sigma^2 at 8 kHz spreads over 4 kHz. */
        const double sigma = std::sqrt(power / std::pow(10.0, snrDb / 10.0) * 4000.0 / 3000.0);
        Noise noise(seed * 1000003ULL + (uint64_t)s);
        long eoo = 0, okHalf = 0, okFull = 0, wrongHalf = 0, wrongFull = 0, onlyHalf = 0, onlyFull = 0;
        double reading = 0.0;
        for (long t = 0; t < trials; t++) {
            const std::string sent = randomCallsign(noise);
            std::vector<int16_t> audio(2400, 0);              /* noise before the over */
            const std::vector<int16_t> over = link.transmit(sent);
            audio.insert(audio.end(), over.begin(), over.end());
            for (size_t i = 0; i < audio.size(); i++) {
                /* 0.2: leave the noise peaks room below int16 full scale. */
                const double v = 0.2 * (audio[i] + sigma * noise.gaussian());
                audio[i] = (int16_t)std::lrint(std::min(std::max(v, -32767.0), 32767.0));
            }
            Heard heard;
            link.receive(audio, &heard);
            /* A missed end of over leaves the receiver in sync: let it drop
               out on noise, as it would on air, before the next over. */
            for (int guard = 0; link.inSync() && guard < 10; guard++) {
                std::vector<int16_t> hiss(4800);
                for (size_t i = 0; i < hiss.size(); i++)
                    hiss[i] = (int16_t)std::lrint(0.2 * sigma * noise.gaussian());
                Heard ignored;
                link.receive(hiss, &ignored);
            }
            const bool half = contains(heard.half, sent), full = contains(heard.full, sent);
            eoo += heard.eooFrames > 0;
            reading += heard.snrReading;       /* 0 when no end of over was seen */
            okHalf += half; okFull += full;
            wrongHalf += others(heard.half, sent);
            wrongFull += others(heard.full, sent);
            onlyHalf += half && !full; onlyFull += full && !half;
        }
        std::printf("%6.1f | %7.1f | %6.2f%% | %7ld %6.2f%% %6ld | %7ld %6.2f%% %6ld | %7ld %7ld\n",
                    snrDb, eoo ? reading / eoo : 0.0, 100.0 * eoo / trials,
                    okFull, 100.0 * okFull / trials, wrongFull,
                    okHalf, 100.0 * okHalf / trials, wrongHalf, onlyFull, onlyHalf);
        std::fflush(stdout);
    }
    return 0;
}

int main(int argc, char **argv) {
    rade_initialize();
    int rc;
    if (argc == 1) {
        rc = cleanChannel();
    } else if (argc >= 4 && std::atol(argv[1]) > 0) {
        rc = noiseSweep(std::atol(argv[1]), std::strtoull(argv[2], NULL, 10), argc - 3, argv + 3);
    } else {
        std::fprintf(stderr, "usage: %s [TRIALS SEED SNR_DB [SNR_DB ...]]\n", argv[0]);
        rc = 2;
    }
    rade_finalize();
    return rc;
}
