#include <jni.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <termios.h>
#include <pthread.h>
#include <errno.h>
#include <signal.h>
#include <sys/select.h>
#include <sys/wait.h>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "UsbPtyBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ── pty creation ────────────────────────────────────────── */

static int create_pty(char *slavePath, size_t pathLen) {
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        LOGE("posix_openpt failed: %s (errno=%d)", strerror(errno), errno);
        return -1;
    }
    if (grantpt(master) < 0) {
        LOGE("grantpt failed: %s", strerror(errno));
        close(master);
        return -1;
    }
    if (unlockpt(master) < 0) {
        LOGE("unlockpt failed: %s", strerror(errno));
        close(master);
        return -1;
    }
    const char *name = ptsname(master);
    if (!name) {
        LOGE("ptsname failed: %s", strerror(errno));
        close(master);
        return -1;
    }
    strncpy(slavePath, name, pathLen - 1);
    slavePath[pathLen - 1] = '\0';

    /* Raw mode on master — transparent byte relay */
    struct termios t;
    if (tcgetattr(master, &t) == 0) {
        cfmakeraw(&t);
        tcsetattr(master, TCSANOW, &t);
    }

    LOGD("Created pty: master fd=%d, slave=%s", master, slavePath);
    return master;
}

/* ── bridge context ──────────────────────────────────────── */

typedef struct {
    JavaVM      *jvm;
    jobject      connection;   /* UsbDeviceConnection (global ref) */
    jobject      epIn;         /* UsbEndpoint IN  (global ref)     */
    jobject      epOut;        /* UsbEndpoint OUT (global ref)     */
    int          masterFd;
    int          slaveFd;      /* keep slave open to prevent EIO on master */
    volatile int running;
    pthread_t    tRead;        /* USB → pty */
    pthread_t    tWrite;       /* pty → USB */
} BridgeCtx;

static BridgeCtx *g_bridge = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ── USB IN → pty master  (radio → rigctld) ──────────────── */

static void *usb_read_thread(void *arg) {
    BridgeCtx *ctx = (BridgeCtx *)arg;
    JNIEnv *env = NULL;
    if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != 0) {
        LOGE("usb_read: AttachCurrentThread failed");
        return NULL;
    }

    jclass connCls = (*env)->GetObjectClass(env, ctx->connection);
    jmethodID mid  = (*env)->GetMethodID(env, connCls,
        "bulkTransfer", "(Landroid/hardware/usb/UsbEndpoint;[BII)I");
    if (!mid) {
        LOGE("usb_read: cannot find bulkTransfer method");
        (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
        return NULL;
    }

    /* Use a global ref for the byte array so it survives across loops */
    jbyteArray jbuf = (*env)->NewGlobalRef(env, (*env)->NewByteArray(env, 512));

    LOGD("usb_read thread started");

    while (ctx->running) {
        int n = (*env)->CallIntMethod(env, ctx->connection,
                                      mid, ctx->epIn, jbuf, 512, 200);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOGE("usb_read: bulkTransfer exception");
            break;
        }
        if (n > 0) {
            jbyte *bytes = (*env)->GetByteArrayElements(env, jbuf, NULL);
            int off = 0;
            while (off < n && ctx->running) {
                int w = write(ctx->masterFd, bytes + off, n - off);
                if (w < 0) {
                    if (errno == EINTR) continue;
                    LOGE("usb_read: write to pty failed: %s", strerror(errno));
                    break;
                }
                off += w;
            }
            (*env)->ReleaseByteArrayElements(env, jbuf, bytes, JNI_ABORT);
        }
    }

    (*env)->DeleteGlobalRef(env, jbuf);
    (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    LOGD("usb_read thread exited");
    return NULL;
}

/* ── pty master → USB OUT  (rigctld → radio) ─────────────── */

static void *usb_write_thread(void *arg) {
    BridgeCtx *ctx = (BridgeCtx *)arg;
    JNIEnv *env = NULL;
    if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != 0) {
        LOGE("usb_write: AttachCurrentThread failed");
        return NULL;
    }

    jclass connCls = (*env)->GetObjectClass(env, ctx->connection);
    jmethodID mid  = (*env)->GetMethodID(env, connCls,
        "bulkTransfer", "(Landroid/hardware/usb/UsbEndpoint;[BII)I");
    if (!mid) {
        LOGE("usb_write: cannot find bulkTransfer method");
        (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
        return NULL;
    }

    jbyteArray jbuf = (*env)->NewGlobalRef(env, (*env)->NewByteArray(env, 512));

    LOGD("usb_write thread started");

    while (ctx->running) {
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(ctx->masterFd, &fds);
        struct timeval tv = { .tv_sec = 0, .tv_usec = 100000 }; /* 100 ms */

        int sel = select(ctx->masterFd + 1, &fds, NULL, NULL, &tv);
        if (sel < 0) {
            if (errno == EINTR) continue;
            LOGE("usb_write: select failed: %s", strerror(errno));
            break;
        }
        if (sel == 0) continue; /* timeout — check running flag */

        char tmp[512];
        int n = read(ctx->masterFd, tmp, sizeof(tmp));
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EIO) continue;
            LOGE("usb_write: read from pty failed: %s", strerror(errno));
            break;
        }
        if (n == 0) continue;

        (*env)->SetByteArrayRegion(env, jbuf, 0, n, (const jbyte *)tmp);
        int sent = (*env)->CallIntMethod(env, ctx->connection,
                                         mid, ctx->epOut, jbuf, n, 1000);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOGE("usb_write: bulkTransfer exception");
            break;
        }
        if (sent < 0) {
            LOGE("usb_write: bulkTransfer returned %d", sent);
        }
    }

    (*env)->DeleteGlobalRef(env, jbuf);
    (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    LOGD("usb_write thread exited");
    return NULL;
}

/* ── stop helper (must be called with g_lock held) ───────── */

static void stop_bridge_locked(JNIEnv *env) {
    if (!g_bridge) return;
    BridgeCtx *ctx = g_bridge;
    g_bridge = NULL;

    ctx->running = 0;

    /* Close fds to unblock read/select in threads */
    if (ctx->masterFd >= 0) {
        close(ctx->masterFd);
        ctx->masterFd = -1;
    }
    if (ctx->slaveFd >= 0) {
        close(ctx->slaveFd);
        ctx->slaveFd = -1;
    }

    /* Give threads time to notice running==0 and exit */
    usleep(400000);
    /* Detached-style cleanup: threads check ctx->running and will exit.
       We can't portably do a timed join on Android NDK, so just wait briefly. */

    (*env)->DeleteGlobalRef(env, ctx->connection);
    (*env)->DeleteGlobalRef(env, ctx->epIn);
    (*env)->DeleteGlobalRef(env, ctx->epOut);
    free(ctx);

    LOGD("Bridge stopped");
}

/* ── JNI: start bridge ───────────────────────────────────── */

JNIEXPORT jstring JNICALL
Java_yakumo2683_RADEdecode_usb_UsbSerialManager_nativeStartBridge(
        JNIEnv *env, jclass cls,
        jobject connection, jobject endpointIn, jobject endpointOut) {

    pthread_mutex_lock(&g_lock);

    /* Stop any existing bridge */
    stop_bridge_locked(env);

    /* Create pty pair */
    char slavePath[128];
    int masterFd = create_pty(slavePath, sizeof(slavePath));
    if (masterFd < 0) {
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    /* Open slave side to keep a reference — prevents EIO on master
       when rigctld does tcflush/tcsetattr on the slave */
    int slaveFd = open(slavePath, O_RDWR | O_NOCTTY);
    if (slaveFd < 0) {
        LOGE("Failed to open pty slave %s: %s", slavePath, strerror(errno));
        close(masterFd);
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }
    LOGD("Opened slave fd=%d as keepalive", slaveFd);

    BridgeCtx *ctx = calloc(1, sizeof(BridgeCtx));
    if (!ctx) {
        close(masterFd);
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    (*env)->GetJavaVM(env, &ctx->jvm);
    ctx->connection = (*env)->NewGlobalRef(env, connection);
    ctx->epIn       = (*env)->NewGlobalRef(env, endpointIn);
    ctx->epOut      = (*env)->NewGlobalRef(env, endpointOut);
    ctx->masterFd   = masterFd;
    ctx->slaveFd    = slaveFd;
    ctx->running    = 1;

    if (pthread_create(&ctx->tRead, NULL, usb_read_thread, ctx) != 0) {
        LOGE("Failed to create usb_read thread");
        close(masterFd);
        (*env)->DeleteGlobalRef(env, ctx->connection);
        (*env)->DeleteGlobalRef(env, ctx->epIn);
        (*env)->DeleteGlobalRef(env, ctx->epOut);
        free(ctx);
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }
    if (pthread_create(&ctx->tWrite, NULL, usb_write_thread, ctx) != 0) {
        LOGE("Failed to create usb_write thread");
        ctx->running = 0;
        close(masterFd);
        pthread_join(ctx->tRead, NULL);
        (*env)->DeleteGlobalRef(env, ctx->connection);
        (*env)->DeleteGlobalRef(env, ctx->epIn);
        (*env)->DeleteGlobalRef(env, ctx->epOut);
        free(ctx);
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    g_bridge = ctx;
    pthread_mutex_unlock(&g_lock);

    LOGD("Bridge started: slave=%s, masterFd=%d", slavePath, masterFd);
    return (*env)->NewStringUTF(env, slavePath);
}

/* ── JNI: stop bridge ────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_yakumo2683_RADEdecode_usb_UsbSerialManager_nativeStopBridge(
        JNIEnv *env, jclass cls) {
    pthread_mutex_lock(&g_lock);
    stop_bridge_locked(env);
    pthread_mutex_unlock(&g_lock);
}

/* ── JNI: launch rigctld with fdsan disabled ─────────────── */

/**
 * Fork+exec rigctld with fdsan disabled in the child process.
 * Android's fdsan (file descriptor sanitizer) aborts prebuilt binaries
 * that have fd ownership violations. We disable it between fork and exec.
 *
 * @param argv  String array: [binary_path, arg1, arg2, ...]
 * @return      PID of child, or -1 on failure.
 */
JNIEXPORT jint JNICALL
Java_yakumo2683_RADEdecode_network_RigctldProcess_nativeForkExec(
        JNIEnv *env, jclass cls, jobjectArray argv) {

    int argc = (*env)->GetArrayLength(env, argv);
    if (argc < 1) return -1;

    /* Convert Java String[] to C char*[] */
    char **args = calloc(argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, argv, i);
        const char *s = (*env)->GetStringUTFChars(env, js, NULL);
        args[i] = strdup(s);
        (*env)->ReleaseStringUTFChars(env, js, s);
        (*env)->DeleteLocalRef(env, js);
    }
    args[argc] = NULL;

    LOGD("nativeForkExec: %s (argc=%d)", args[0], argc);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        for (int i = 0; i < argc; i++) free(args[i]);
        free(args);
        return -1;
    }

    if (pid == 0) {
        /* ── Child process ── */

        /* LD_PRELOAD libfdsan_disable.so to disable fdsan after exec.
           The .so's constructor calls android_fdsan_set_error_level(0)
           before rigctld's main() runs. */
        char preload_path[512];
        /* nativeLibraryDir is the same dir as the rigctld binary */
        const char *binary = args[0];
        const char *last_slash = strrchr(binary, '/');
        if (last_slash) {
            int dir_len = (int)(last_slash - binary);
            snprintf(preload_path, sizeof(preload_path),
                     "%.*s/libfdsan_disable.so", dir_len, binary);
            setenv("LD_PRELOAD", preload_path, 1);
        }

        /* Redirect stdout+stderr to /dev/null to avoid blocking on pipe */
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }

        execv(args[0], args);
        _exit(127); /* exec failed */
    }

    /* ── Parent process ── */
    for (int i = 0; i < argc; i++) free(args[i]);
    free(args);

    LOGD("Forked rigctld pid=%d", pid);
    return pid;
}

/**
 * Kill a child process.
 */
JNIEXPORT void JNICALL
Java_yakumo2683_RADEdecode_network_RigctldProcess_nativeKill(
        JNIEnv *env, jclass cls, jint pid) {
    if (pid > 0) {
        kill(pid, SIGTERM);
        usleep(200000);
        int status;
        if (waitpid(pid, &status, WNOHANG) == 0) {
            kill(pid, SIGKILL);
            waitpid(pid, &status, 0);
        }
        LOGD("Killed pid=%d", pid);
    }
}
