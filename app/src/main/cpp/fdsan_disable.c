#include <dlfcn.h>

/**
 * Shared library constructor that disables Android's fdsan.
 * LD_PRELOAD this .so when launching prebuilt binaries (like rigctld)
 * that trigger false-positive fd ownership violations.
 */
__attribute__((constructor))
static void disable_fdsan(void) {
    typedef void (*set_level_fn)(int);
    set_level_fn fn = (set_level_fn)dlsym(RTLD_DEFAULT, "android_fdsan_set_error_level");
    if (fn) {
        fn(0);  /* ANDROID_FDSAN_ERROR_LEVEL_DISABLED */
    }
}
