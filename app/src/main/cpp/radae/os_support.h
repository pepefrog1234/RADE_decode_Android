/* os_support.h - Minimal stub for RADE on iOS/macOS */
#ifndef OS_SUPPORT_H
#define OS_SUPPORT_H

#include <string.h>
#include <stdlib.h>

#include "opus_types.h"
#include "opus_defines.h"

/* Memory operations */
#ifndef OPUS_COPY
#define OPUS_COPY(dst, src, n) (memcpy((dst), (src), (n)*sizeof(*(dst))))
#endif

#ifndef OPUS_MOVE
#define OPUS_MOVE(dst, src, n) (memmove((dst), (src), (n)*sizeof(*(dst))))
#endif

#ifndef OPUS_CLEAR
#define OPUS_CLEAR(dst, n) (memset((dst), 0, (n)*sizeof(*(dst))))
#endif

/* Memory allocation */
static inline void *opus_alloc(size_t size) { return malloc(size); }
static inline void opus_free(void *ptr) { free(ptr); }

#define OPUS_ALLOC(size) opus_alloc(size)
#define OPUS_FREE(ptr) opus_free(ptr)

#endif /* OS_SUPPORT_H */
