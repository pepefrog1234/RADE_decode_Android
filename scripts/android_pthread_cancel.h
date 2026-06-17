/*
 * Android bionic has no pthread_cancel(). Hamlib references it in src/rig.c
 * (async-data handler thread) and rotators/ars/ars.c. The async-data thread is
 * only started for backends with async_data_supported (Icom transceive etc.),
 * NOT for the polled Kenwood/Elecraft path used by the KX3 — so this best-effort
 * stub is never actually invoked there. It exists so the tree compiles and links
 * on Android. Force-included via CFLAGS (-include) by build-hamlib-android.sh.
 */
#ifndef RADE_ANDROID_PTHREAD_CANCEL_SHIM_H
#define RADE_ANDROID_PTHREAD_CANCEL_SHIM_H

#include <pthread.h>
#include <signal.h>

__attribute__((unused))
static inline int pthread_cancel(pthread_t thread) {
    /* signal 0 = validity check only; no signal delivered, no real cancel. */
    return pthread_kill(thread, 0);
}

#endif /* RADE_ANDROID_PTHREAD_CANCEL_SHIM_H */
