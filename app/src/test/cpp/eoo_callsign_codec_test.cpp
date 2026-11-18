// Host regression test; no Android device or radio required.
// c++ -std=c++11 -Wall -Wextra -Iapp/src/main/cpp/eoo
//   app/src/test/cpp/eoo_callsign_codec_test.cpp
//   app/src/main/cpp/eoo/EooCallsignCodec.cpp
//   app/src/main/cpp/eoo/eoo_callsign_codec_c.cpp -o /tmp/rade-eoo-callsign-test
// Run it with -g -fsanitize=address,undefined as well: the encoder's buffer
// (see roundTripsCallsigns) is only checked by a sanitizer.
#include "eoo_callsign_codec_c.h"
#include "EooCallsignCodec.h"

#ifdef NDEBUG
#error "Build without -DNDEBUG: these checks are assert()s."
#endif

#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <signal.h>
#include <sys/mman.h>
#include <unistd.h>

// RADE V1: rade_n_eoo_bits() floats = (RADE_NS - 1) * RADE_NC * 2, i.e. 90
// complex symbols. The callsign codeword is the first 56 symbols (112 floats).
static const int V1_EOO_FLOATS = 180;
static const int V1_EOO_SYMBOLS = V1_EOO_FLOATS / 2;
static const int CODEWORD_FLOATS = EOO_CALLSIGN_MIN_SYMBOLS * 2;

// Floats laid out so that the first byte after the last one is unmapped: a
// read past the end faults here, with or without a sanitizer.
class GuardedFloats {
public:
    explicit GuardedFloats(int count) : count_(count) {
        page_ = static_cast<size_t>(sysconf(_SC_PAGESIZE));
        const size_t bytes = static_cast<size_t>(count) * sizeof(float);
        mapped_ = (bytes / page_ + 2) * page_;
        void *base = mmap(nullptr, mapped_, PROT_READ | PROT_WRITE,
                          MAP_PRIVATE | MAP_ANON, -1, 0);
        assert(base != MAP_FAILED);
        base_ = static_cast<char*>(base);
        char *guard = base_ + mapped_ - page_;
        const int rc = mprotect(guard, page_, PROT_NONE);
        assert(rc == 0);
        (void)rc;
        data_ = reinterpret_cast<float*>(guard - bytes);
    }
    GuardedFloats(int count, const std::vector<float> &source) : GuardedFloats(count) {
        std::copy(source.begin(), source.begin() + count, data_);
    }
    ~GuardedFloats() { munmap(base_, mapped_); }
    float *data() { return data_; }
    int size() const { return count_; }
private:
    GuardedFloats(const GuardedFloats&) = delete;
    GuardedFloats &operator=(const GuardedFloats&) = delete;
    int count_;
    size_t page_, mapped_;
    char *base_;
    float *data_;
};

static void onFault(int) {
    static const char msg[] =
        "FAILED: the callsign decoder read symbols it was not given "
        "(null pointer, or past the end of the buffer)\n";
    const ssize_t ignored = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    (void)ignored;
    _exit(1);
}

static std::vector<float> encoded(const char *callsign) {
    std::vector<float> syms(V1_EOO_FLOATS, 0.0f);
    eoo_callsign_encode(callsign, syms.data(), V1_EOO_FLOATS);
    return syms;
}

static void roundTripsCallsigns() {
    // Eight characters is the longest a callsign gets. The encoder used to
    // write the terminator of such a callsign one byte past its buffer: silent
    // in a plain host build, an abort under AddressSanitizer and on Android,
    // whose fortified strlen() then finds no terminator inside the buffer.
    static const char *const sent[] = {"W1AW", "VK5KVA", "JH0VEQ", "BX4ACP",
                                       "A", "AB1CDEFG", "DL1ABC-P", "k1abc"};
    static const char *const expected[] = {"W1AW", "VK5KVA", "JH0VEQ", "BX4ACP",
                                           "A", "AB1CDEFG", "DL1ABC-P", "K1ABC"};
    for (size_t i = 0; i < sizeof(sent) / sizeof(sent[0]); ++i) {
        const std::vector<float> syms = encoded(sent[i]);
        // Beyond the codeword the encoder writes the known (1, 0) filler.
        for (int f = CODEWORD_FLOATS; f < V1_EOO_FLOATS; ++f)
            assert(syms[f] == (f % 2 == 0 ? 1.0f : 0.0f));

        char callsign[EOO_CALLSIGN_BUF_SIZE];
        std::memset(callsign, '#', sizeof(callsign));
        assert(eoo_callsign_decode(syms.data(), V1_EOO_SYMBOLS, callsign, sizeof(callsign)) == 1);
        assert(std::strcmp(callsign, expected[i]) == 0);

        std::string viaClass;
        assert(EooCallsignDecoder().decode(syms.data(), V1_EOO_SYMBOLS, viaClass));
        assert(viaClass == expected[i]);
    }
}

// The receiver hands the decoder symbols at whatever level the audio arrived
// with (the EOO demodulator corrects phase only), so the level must not matter.
static void decodesAtAnySignalLevel() {
    static const float levels[] = {1e-4f, 0.03f, 1.0f, 250.0f, 3e4f};
    for (size_t i = 0; i < sizeof(levels) / sizeof(levels[0]); ++i) {
        std::vector<float> syms = encoded("VK5KVA");
        for (size_t f = 0; f < syms.size(); ++f) syms[f] *= levels[i];
        char callsign[EOO_CALLSIGN_BUF_SIZE] = {0};
        assert(eoo_callsign_decode(syms.data(), V1_EOO_SYMBOLS, callsign, sizeof(callsign)) == 1);
        assert(std::strcmp(callsign, "VK5KVA") == 0);
    }
    // Silence carries no callsign.
    const std::vector<float> silence(V1_EOO_FLOATS, 0.0f);
    char callsign[EOO_CALLSIGN_BUF_SIZE] = {0};
    assert(eoo_callsign_decode(silence.data(), V1_EOO_SYMBOLS, callsign, sizeof(callsign)) == 0);
}

static void rejectsUnusableArguments() {
    const std::vector<float> syms = encoded("JH0VEQ");
    char callsign[32];
    const std::string untouched(sizeof(callsign), '#');
    std::memset(callsign, '#', sizeof(callsign));

    // No symbols at all. RADE V2 has no EOO payload: rade_n_eoo_bits() is 0
    // and an empty std::vector's data() is null.
    assert(eoo_callsign_decode(nullptr, 0, callsign, sizeof(callsign)) == 0);
    assert(eoo_callsign_decode(nullptr, V1_EOO_SYMBOLS, callsign, sizeof(callsign)) == 0);
    std::string unused;
    assert(!EooCallsignDecoder().decode(nullptr, V1_EOO_SYMBOLS, unused));

    // Fewer symbols than the codeword, including nonsense counts.
    static const int tooFew[] = {-1, 0, 1, EOO_CALLSIGN_MIN_SYMBOLS - 1};
    for (size_t i = 0; i < sizeof(tooFew) / sizeof(tooFew[0]); ++i) {
        assert(eoo_callsign_decode(syms.data(), tooFew[i], callsign, sizeof(callsign)) == 0);
        assert(!EooCallsignDecoder().decode(syms.data(), tooFew[i], unused));
    }

    // Nowhere to put the result, or not enough room for the longest callsign.
    assert(eoo_callsign_decode(syms.data(), V1_EOO_SYMBOLS, nullptr, sizeof(callsign)) == 0);
    static const int tooSmall[] = {-1, 0, 1, 2, EOO_CALLSIGN_BUF_SIZE - 1};
    for (size_t i = 0; i < sizeof(tooSmall) / sizeof(tooSmall[0]); ++i)
        assert(eoo_callsign_decode(syms.data(), V1_EOO_SYMBOLS, callsign, tooSmall[i]) == 0);

    // A refused call leaves the caller's buffer alone.
    assert(std::memcmp(callsign, untouched.data(), sizeof(callsign)) == 0);

    // The smallest accepted buffer holds the longest callsign.
    const std::vector<float> longest = encoded("AB1CDEFG");
    char exact[EOO_CALLSIGN_BUF_SIZE];
    assert(eoo_callsign_decode(longest.data(), V1_EOO_SYMBOLS, exact, sizeof(exact)) == 1);
    assert(std::strcmp(exact, "AB1CDEFG") == 0);
}

// nativeEooCallsignDecode() receives a Kotlin FloatArray of any length and
// passes length / 2 as the symbol count; decode exactly that way here.
static int decodeFloatArray(GuardedFloats &array, char *callsign, int maxLen) {
    return eoo_callsign_decode(array.data(), array.size() / 2, callsign, maxLen);
}

static void neverReadsPastAShortBuffer() {
    const std::vector<float> syms = encoded("BX4ACP");
    char callsign[EOO_CALLSIGN_BUF_SIZE];

    static const int shortCounts[] = {0, 1, 2, 56, 90, CODEWORD_FLOATS - 2, CODEWORD_FLOATS - 1};
    for (size_t i = 0; i < sizeof(shortCounts) / sizeof(shortCounts[0]); ++i) {
        GuardedFloats array(shortCounts[i], syms);
        assert(decodeFloatArray(array, callsign, sizeof(callsign)) == 0);
    }

    // Exactly the codeword is enough, and nothing after it is read.
    static const int enough[] = {CODEWORD_FLOATS, CODEWORD_FLOATS + 1, V1_EOO_FLOATS};
    for (size_t i = 0; i < sizeof(enough) / sizeof(enough[0]); ++i) {
        GuardedFloats array(enough[i], syms);
        std::memset(callsign, '#', sizeof(callsign));
        assert(decodeFloatArray(array, callsign, sizeof(callsign)) == 1);
        assert(std::strcmp(callsign, "BX4ACP") == 0);
    }
}

int main() {
    signal(SIGSEGV, onFault);
    signal(SIGBUS, onFault);
    roundTripsCallsigns();
    decodesAtAnySignalLevel();
    rejectsUnusableArguments();
    neverReadsPastAShortBuffer();
    std::puts("EOO callsign codec regression tests passed");
}
