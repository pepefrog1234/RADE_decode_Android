/* arch.h - Minimal stub for RADE on iOS/macOS
 * Only provides the definitions that RADE actually needs.
 */
#ifndef ARCH_H
#define ARCH_H

#include "opus_types.h"

typedef float opus_val32;
typedef float opus_val16;

#define FLOAT_CAST(x) (x)
#define MULT16_16(a,b) ((float)(a)*(float)(b))
#define MULT16_32_Q15(a,b) ((float)(a)*(float)(b))

#define IMAX(a,b) ((a) > (b) ? (a) : (b))
#define IMIN(a,b) ((a) < (b) ? (a) : (b))

#define celt_assert(x) do { if (!(x)) { /* assertion */ } } while(0)
#define celt_assert2(x, msg) do { if (!(x)) { /* assertion */ } } while(0)
#define celt_fatal(msg) do { } while(0)

/* opus_val32 arithmetic (float) */
#define QCONST16(x,bits) (x)
#define QCONST32(x,bits) (x)

/* Opus CPU detection - not needed for ARM64 iOS, use generic */
#define opus_select_arch() 0

#endif /* ARCH_H */
