#include <jni.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <termios.h>
#include <pthread.h>
#include <errno.h>
#include <sys/select.h>
#include <android/log.h>

/*
 * Kotlin-bytestream <-> pty bridge for the Icom network (RS-BA1) rig control.
 *
 * Unlike usb_serial_helper.c (which bridges USB bulk endpoints to a pty entirely
 * in native threads), here the CI-V bytes live in Kotlin: they are tunnelled over
 * UDP by IcomNetworkManager.  So we expose the pty master as four primitive JNI
 * calls and let Kotlin pump bytes both ways:
 *
 *   radio --UDP 50002--> IcomNetworkManager --nativeIcomPtyWrite--> pty master
 *                                                                       |
 *                                                              /dev/pts/N (slave)
 *                                                                       |
 *                                                rigctld -m 3085 -r /dev/pts/N
 *                                                                       |
 *   radio <--UDP 50002-- IcomNetworkManager <--nativeIcomPtyRead--- pty master
 *
 * The pty slave behaves like a real serial port so rigctld's tcsetattr/tcflush
 * calls succeed; baud rate is meaningless for a pty and simply ignored.
 */

#define TAG "IcomPty"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int g_master = -1;
static int g_slave  = -1;   /* kept open so the master doesn't get EIO on tcsetattr */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ── JNI: open pty, return slave path (e.g. "/dev/pts/3") ─────── */

JNIEXPORT jstring JNICALL
Java_yakumo2683_RADEdecode_network_IcomNetworkManager_nativeIcomPtyOpen(
        JNIEnv *env, jclass cls) {
    pthread_mutex_lock(&g_lock);

    /* Close any previous pty first. */
    if (g_master >= 0) { close(g_master); g_master = -1; }
    if (g_slave  >= 0) { close(g_slave);  g_slave  = -1; }

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        LOGE("posix_openpt failed: %s (errno=%d)", strerror(errno), errno);
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }
    if (grantpt(master) < 0 || unlockpt(master) < 0) {
        LOGE("grantpt/unlockpt failed: %s", strerror(errno));
        close(master);
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }
    const char *name = ptsname(master);
    if (!name) {
        LOGE("ptsname failed: %s", strerror(errno));
        close(master);
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    char slavePath[128];
    strncpy(slavePath, name, sizeof(slavePath) - 1);
    slavePath[sizeof(slavePath) - 1] = '\0';

    /* Raw mode on the master so bytes relay transparently. */
    struct termios t;
    if (tcgetattr(master, &t) == 0) {
        cfmakeraw(&t);
        tcsetattr(master, TCSANOW, &t);
    }

    /* Hold the slave open so the master survives rigctld's tcflush/tcsetattr. */
    int slave = open(slavePath, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        LOGE("open slave %s failed: %s", slavePath, strerror(errno));
        close(master);
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    g_master = master;
    g_slave  = slave;
    LOGD("pty open: master=%d slave=%s", master, slavePath);

    pthread_mutex_unlock(&g_lock);
    return (*env)->NewStringUTF(env, slavePath);
}

/* ── JNI: write bytes to the master (radio CI-V -> rigctld) ───── */

JNIEXPORT jint JNICALL
Java_yakumo2683_RADEdecode_network_IcomNetworkManager_nativeIcomPtyWrite(
        JNIEnv *env, jclass cls, jbyteArray data, jint len) {
    int fd;
    pthread_mutex_lock(&g_lock);
    fd = g_master;
    pthread_mutex_unlock(&g_lock);
    if (fd < 0 || len <= 0) return -1;

    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return -1;

    int off = 0;
    while (off < len) {
        int w = write(fd, bytes + off, len - off);
        if (w < 0) {
            if (errno == EINTR) continue;
            LOGE("write to pty failed: %s", strerror(errno));
            break;
        }
        off += w;
    }
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return off;
}

/* ── JNI: read bytes from the master (rigctld -> radio CI-V) ───
 *
 * Blocks up to timeoutMs for data.  Returns:
 *   - a byte[] of length >0 with the bytes rigctld wrote, or
 *   - an empty byte[] on timeout (caller should loop), or
 *   - null on error / closed pty (caller should stop).
 */

JNIEXPORT jbyteArray JNICALL
Java_yakumo2683_RADEdecode_network_IcomNetworkManager_nativeIcomPtyRead(
        JNIEnv *env, jclass cls, jint timeoutMs) {
    int fd;
    pthread_mutex_lock(&g_lock);
    fd = g_master;
    pthread_mutex_unlock(&g_lock);
    if (fd < 0) return NULL;

    fd_set fds;
    FD_ZERO(&fds);
    FD_SET(fd, &fds);
    struct timeval tv = { .tv_sec = timeoutMs / 1000,
                          .tv_usec = (timeoutMs % 1000) * 1000 };

    int sel = select(fd + 1, &fds, NULL, NULL, &tv);
    if (sel < 0) {
        if (errno == EINTR) return (*env)->NewByteArray(env, 0);
        LOGE("select failed: %s", strerror(errno));
        return NULL;
    }
    if (sel == 0) {
        return (*env)->NewByteArray(env, 0);  /* timeout */
    }

    unsigned char tmp[1024];
    int n = read(fd, tmp, sizeof(tmp));
    if (n < 0) {
        if (errno == EINTR || errno == EAGAIN) return (*env)->NewByteArray(env, 0);
        if (errno == EIO) return (*env)->NewByteArray(env, 0); /* slave reopened */
        LOGE("read from pty failed: %s", strerror(errno));
        return NULL;
    }
    if (n == 0) return (*env)->NewByteArray(env, 0);

    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *)tmp);
    return out;
}

/* ── JNI: close the pty ───────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_yakumo2683_RADEdecode_network_IcomNetworkManager_nativeIcomPtyClose(
        JNIEnv *env, jclass cls) {
    pthread_mutex_lock(&g_lock);
    if (g_master >= 0) { close(g_master); g_master = -1; }
    if (g_slave  >= 0) { close(g_slave);  g_slave  = -1; }
    pthread_mutex_unlock(&g_lock);
    LOGD("pty closed");
}
