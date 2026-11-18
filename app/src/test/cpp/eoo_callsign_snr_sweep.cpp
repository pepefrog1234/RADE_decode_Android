// Host measurement; no Android device or radio required.
// c++ -std=c++11 -O2 -Wall -Wextra -Iapp/src/main/cpp/eoo
//   app/src/test/cpp/eoo_callsign_snr_sweep.cpp
//   app/src/main/cpp/eoo/EooCallsignCodec.cpp
//   app/src/main/cpp/eoo/eoo_callsign_codec_c.cpp -o /tmp/rade-eoo-snr-sweep
// /tmp/rade-eoo-snr-sweep [trials per point, default 3000] [seed, default 1]
//
// Which symbol count should the receiver pass to eoo_callsign_decode()?
// RADE V1 hands over n = rade_n_eoo_bits() = 180 floats, i.e. n / 2 = 90
// complex symbols. The decoder reads the first 56 symbols whatever the count
// says; beyond that the count only sets the divisor of the RMS it normalises
// them with, and so the scale of the LLRs the LDPC decoder starts from (n
// makes them sqrt(2) larger than n / 2). This program encodes random
// callsigns, adds Gaussian noise to the symbols and decodes every noisy frame
// both ways, so the two columns differ by that one argument and nothing else.
//
// Es/N0 is the energy of one QPSK symbol over the noise density: each of I
// and Q gets noise of variance 1 / (2 Es/N0) on unit-amplitude symbols. The
// code is rate 1/2 on QPSK, so this is also Eb/N0 of the 56 payload bits.
// Through the real modem the codeword symbols reach the decoder about 1.7 dB
// below the SNR of the over in 3 kHz: SNR 3 dB in tools/radecheck eoocheck
// corresponds to Es/N0 1.3 dB here.
//
// Answer, v1.6.34 (50000 trials per point, seeds 1 to 4): n / 2 decodes at
// least as many frames at every point and more at every point up to 6.5 dB,
// by up to 2.2 in 100 around Es/N0 1.5 dB, and accepts fewer wrong callsigns.
// The receiver passes n / 2; docs/release-notes/v1.6.34.md has the table.
//
// Exit code 0 unless a noise-free frame fails to decode, or n / 2 decodes
// significantly worse than n (sign test on the frames only one of them got).
#include "eoo_callsign_codec_c.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>

static const int EOO_FLOATS = 180;               // V1 rade_n_eoo_bits()
static const int SYM_SIZE_N = EOO_FLOATS;        // the float count
static const int SYM_SIZE_HALF = EOO_FLOATS / 2; // the complex symbol count
static const double PI = 3.14159265358979323846;

// mt19937_64 is specified bit for bit; std::normal_distribution is not, so
// the Gaussian comes from an explicit Box-Muller transform.
class Noise {
public:
    explicit Noise(uint64_t seed) : rng_(seed), haveSpare_(false), spare_(0.0) {}
    uint64_t bits() { return rng_(); }
    double gaussian() {
        if (haveSpare_) { haveSpare_ = false; return spare_; }
        const double u1 = ((rng_() >> 11) + 0.5) / 9007199254740992.0;   // (0, 1)
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

static std::string randomCallsign(Noise &noise) {
    static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    const int length = 3 + static_cast<int>(noise.bits() % 6);   // 3..8 characters
    std::string callsign;
    for (int i = 0; i < length; ++i) callsign += alphabet[noise.bits() % 36];
    return callsign;
}

enum Outcome { MISSED, CORRECT, WRONG };

static Outcome decode(const float *syms, int symSize, const std::string &sent) {
    char callsign[EOO_CALLSIGN_BUF_SIZE] = {0};
    if (!eoo_callsign_decode(syms, symSize, callsign, sizeof(callsign))) return MISSED;
    return sent == callsign ? CORRECT : WRONG;
}

struct Tally {
    long trials, correctN, wrongN, correctHalf, wrongHalf, onlyN, onlyHalf;
    Tally() : trials(0), correctN(0), wrongN(0), correctHalf(0), wrongHalf(0), onlyN(0), onlyHalf(0) {}
    void add(Outcome n, Outcome half) {
        ++trials;
        correctN += n == CORRECT;
        wrongN += n == WRONG;
        correctHalf += half == CORRECT;
        wrongHalf += half == WRONG;
        onlyN += n == CORRECT && half != CORRECT;
        onlyHalf += half == CORRECT && n != CORRECT;
    }
    void add(const Tally &other) {
        trials += other.trials;
        correctN += other.correctN; wrongN += other.wrongN;
        correctHalf += other.correctHalf; wrongHalf += other.wrongHalf;
        onlyN += other.onlyN; onlyHalf += other.onlyHalf;
    }
};

// One point of the sweep. signal == false sends noise alone: every callsign
// that comes out is a false one.
static Tally measure(bool signal, double esN0dB, long trials, Noise &noise) {
    const double sigma = std::sqrt(0.5 / std::pow(10.0, esN0dB / 10.0));
    Tally tally;
    float clean[EOO_FLOATS], noisy[EOO_FLOATS];
    for (long t = 0; t < trials; ++t) {
        const std::string sent = randomCallsign(noise);
        eoo_callsign_encode(sent.c_str(), clean, EOO_FLOATS);
        for (int i = 0; i < EOO_FLOATS; ++i)
            noisy[i] = static_cast<float>((signal ? clean[i] : 0.0) + sigma * noise.gaussian());
        tally.add(decode(noisy, SYM_SIZE_N, sent), decode(noisy, SYM_SIZE_HALF, sent));
    }
    return tally;
}

// Exact two-sided sign test: how likely is a split at least this uneven if a
// frame that only one variant decodes were equally likely to be either's?
static double signTest(long a, long b) {
    const long n = a + b, k = a < b ? a : b;
    if (n == 0) return 1.0;
    double tail = 0.0;
    for (long i = 0; i <= k; ++i)
        tail += std::exp(std::lgamma(n + 1.0) - std::lgamma(i + 1.0) - std::lgamma(n - i + 1.0)
                         - n * std::log(2.0));
    return tail >= 0.5 ? 1.0 : 2.0 * tail;
}

int main(int argc, char **argv) {
    const long trials = argc > 1 ? std::atol(argv[1]) : 3000;
    const uint64_t seed = argc > 2 ? std::strtoull(argv[2], nullptr, 10) : 1;
    if (trials <= 0) {
        std::fprintf(stderr, "usage: %s [trials per point] [seed]\n", argv[0]);
        return 2;
    }
    Noise noise(seed);

    // Without noise both variants must decode every frame.
    const Tally clean = measure(true, 300.0, 200, noise);
    if (clean.correctN != clean.trials || clean.correctHalf != clean.trials) {
        std::fprintf(stderr, "FAILED: a noise-free callsign did not decode\n");
        return 1;
    }

    std::printf("EOO callsign decode, sym_size = n (%d) versus n / 2 (%d); %ld trials per point, seed %llu\n",
                SYM_SIZE_N, SYM_SIZE_HALF, trials, static_cast<unsigned long long>(seed));
    std::printf("%8s | %25s | %25s | %s\n", "", "sym_size = n", "sym_size = n / 2", "decoded by only");
    std::printf("%8s | %17s %7s | %17s %7s | %7s %7s\n",
                "Es/N0 dB", "decoded", "wrong", "decoded", "wrong", "n", "n / 2");
    Tally total;
    for (int tenths = -30; tenths <= 80; tenths += 5) {
        const Tally point = measure(true, tenths / 10.0, trials, noise);
        total.add(point);
        std::printf("%8.1f | %8ld %7.2f%% %7ld | %8ld %7.2f%% %7ld | %7ld %7ld\n", tenths / 10.0,
                    point.correctN, 100.0 * point.correctN / point.trials, point.wrongN,
                    point.correctHalf, 100.0 * point.correctHalf / point.trials, point.wrongHalf,
                    point.onlyN, point.onlyHalf);
        std::fflush(stdout);
    }
    const Tally noiseOnly = measure(false, 0.0, trials, noise);
    std::printf("%8s | %17s %7ld | %17s %7ld |\n", "noise", "", noiseOnly.wrongN, "", noiseOnly.wrongHalf);

    const double p = signTest(total.onlyN, total.onlyHalf);
    std::printf("\nAll points: %ld of %ld frames decoded with n, %ld with n / 2.\n",
                total.correctN, total.trials, total.correctHalf);
    std::printf("Frames only n decoded: %ld; only n / 2: %ld (sign test p = %.3g).\n",
                total.onlyN, total.onlyHalf, p);
    std::printf("Wrong callsigns accepted, noise row included: %ld with n, %ld with n / 2.\n",
                total.wrongN + noiseOnly.wrongN, total.wrongHalf + noiseOnly.wrongHalf);

    if (total.onlyN > total.onlyHalf && p < 0.001) {
        std::printf("FAILED: sym_size = n / 2 decodes worse than n\n");
        return 1;
    }
    return 0;
}
