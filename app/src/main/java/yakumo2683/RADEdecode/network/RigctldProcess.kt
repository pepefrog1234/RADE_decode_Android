package yakumo2683.RADEdecode.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * Manages a local rigctld process bundled with the app.
 *
 * Extracts the arm64 rigctld binary from assets on first run,
 * then launches it as a child process pointing at the specified
 * serial device path. The RigController connects to localhost:4532.
 *
 * Usage:
 *   rigctldProcess.start(model = 1, device = "/dev/ttyUSB0", speed = 9600)
 *   // then connect RigController to "127.0.0.1:4532"
 *   rigctldProcess.stop()
 */
class RigctldProcess(private val context: Context) {

    companion object {
        private const val TAG = "RigctldProcess"
        private const val BINARY_NAME = "rigctld"
        private const val DEFAULT_PORT = 4532

        init { System.loadLibrary("rade_jni") }

        @JvmStatic
        external fun nativeForkExec(argv: Array<String>): Int

        @JvmStatic
        external fun nativeKill(pid: Int)

        /** Diagnostic: liveness/exit cause of a forked child ("running",
         *  "DIED: killed by SIGSEGV (11)", "DIED: exited code N", …). */
        @JvmStatic
        external fun nativeProcessExit(pid: Int): String

        private val FAST_PTY_CAT_MODELS = setOf(
            3076, // Xiegu X108G
            3087, // Xiegu X6100
            3088, // Xiegu G90
            3089  // Xiegu X5105
        )
    }

    private var process: Process? = null
    private var nativePid: Int = -1
    private var logJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isRunning: Boolean get() = nativePid > 0 || process?.isAlive == true

    /**
     * Diagnostic for "connection lost": report whether the local rigctld child is
     * still alive and, if it died, why (e.g. "DIED: killed by SIGSEGV (11)").
     * Returns null when no local rigctld was launched (e.g. remote-TCP mode), so
     * the caller can omit it. "running" means rigctld is fine and the break is
     * elsewhere (USB-serial bridge / socket).
     */
    fun exitDiagnostics(): String? {
        val pid = nativePid
        return if (pid > 0) {
            try { nativeProcessExit(pid) } catch (e: Throwable) { "probe failed: ${e.message}" }
        } else {
            null
        }
    }

    /**
     * Start rigctld with specified parameters.
     *
     * @param model     Hamlib rig model number (e.g. 1=Dummy, 2=NET rigctl, 3014=IC-7300)
     * @param device    Serial device path (e.g. "/dev/ttyUSB0")
     * @param speed     Baud rate (e.g. 9600, 19200, 38400, 115200)
     * @param port      TCP listen port (default 4532)
     * @param civAddr   Optional CI-V address for Icom rigs (hex string e.g. "94")
     */
    fun start(
        model: Int,
        device: String = "",
        speed: Int = 9600,
        port: Int = DEFAULT_PORT,
        civAddr: String = ""
    ): Boolean {
        if (isRunning) {
            Log.w(TAG, "rigctld already running")
            return true
        }

        val binary = ensureBinary() ?: return false

        val cmd = mutableListOf(
            binary.absolutePath,
            "-m", model.toString(),
            "-t", port.toString()
        )

        if (device.isNotEmpty()) {
            cmd.addAll(listOf("-r", device))
        }

        if (speed > 0) {
            cmd.addAll(listOf("-s", speed.toString()))
        }

        if (civAddr.isNotEmpty()) {
            cmd.addAll(listOf("-c", civAddrArg(civAddr)))
        }

        return launchProcess(cmd)
    }

    /**
     * Start rigctld using a pty slave path provided by UsbSerialManager.
     *
     * The pty slave behaves like a real serial port (supports tcsetattr etc.)
     * while the pty master is bridged to the USB device via bulk transfers.
     *
     * @param model     Hamlib rig model number
     * @param ptyPath   Pty slave path (e.g. "/dev/pts/3")
     * @param speed     Baud rate (rigctld will tcsetattr on the pty — this is fine)
     * @param port      TCP listen port (default 4532)
     * @param civAddr   Optional CI-V address for Icom rigs
     */
    fun startWithPty(
        model: Int,
        ptyPath: String,
        speed: Int = 9600,
        port: Int = DEFAULT_PORT,
        civAddr: String = ""
    ): Boolean {
        if (isRunning) {
            Log.w(TAG, "rigctld already running")
            return true
        }

        val binary = ensureBinary() ?: return false

        val cmd = mutableListOf(
            binary.absolutePath,
            // NOTE: do NOT pass -vvvvv here. Max verbosity makes rigctld emit many
            // stderr lines per command (piped through JNI to logcat); combined with
            // the slow Xiegu G90 CI-V it contributed to multi-second PTT lag.
            "-m", model.toString(),
            "-t", port.toString(),
            "-r", ptyPath
        )

        if (speed > 0) {
            cmd.addAll(listOf("-s", speed.toString()))
        }

        if (civAddr.isNotEmpty()) {
            cmd.addAll(listOf("-c", civAddrArg(civAddr)))
        }

        if (model in FAST_PTY_CAT_MODELS) {
            // Bound Xiegu CI-V/PTT readback stalls without applying the tight
            // timeout to Icom RS-BA1/IC-705 network ptys. IC-705 Wi-Fi can exceed
            // 200 ms and then fails get_freq, preventing FreeDV Reporter freq_change.
            cmd.add("--set-conf=timeout=200,retry=1")
        }

        return launchProcess(cmd)
    }

    /**
     * Hamlib parses `--civaddr` as DECIMAL unless the value is 0x-prefixed
     * (icom.c TOK_CIVADDR: atoi() vs strtol(.., 16)). The CI-V field in this
     * app is hex ("94" means 0x94), so always pass the 0x form.
     */
    private fun civAddrArg(civAddr: String): String =
        "0x" + civAddr.removePrefix("0x").removePrefix("0X")

    private fun launchProcess(cmd: List<String>): Boolean {
        Log.i(TAG, "Starting: ${cmd.joinToString(" ")}")

        return try {
            // Use JNI fork/exec to disable fdsan in child process.
            // Android's fdsan kills the prebuilt rigctld binary with SIGABRT.
            val argv = cmd.toTypedArray()
            nativePid = nativeForkExec(argv)
            if (nativePid <= 0) {
                Log.e(TAG, "nativeForkExec failed")
                return false
            }

            // Give it a moment to start
            Thread.sleep(500)
            Log.i(TAG, "rigctld started (pid=$nativePid)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start rigctld: ${e.message}")
            false
        }
    }

    fun stop() {
        if (nativePid > 0) {
            nativeKill(nativePid)
            Log.i(TAG, "rigctld stopped (pid=$nativePid)")
            nativePid = -1
        }
        // Also stop any ProcessBuilder-launched process (legacy)
        logJob?.cancel()
        logJob = null
        process?.let { p ->
            p.destroy()
            try { p.waitFor() } catch (_: Exception) {}
        }
        process = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun getPid(): String {
        return try {
            val f = process?.javaClass?.getDeclaredField("pid")
            f?.isAccessible = true
            f?.get(process)?.toString() ?: "?"
        } catch (_: Exception) { "?" }
    }

    /**
     * Locate the rigctld binary.
     *
     * On modern Android (10+), app filesDir is mounted noexec, so we cannot
     * extract-and-run from there.  Instead, rigctld is packaged as
     * jniLibs/arm64-v8a/librigctld.so and installed to nativeLibraryDir,
     * which has execute permission.
     *
     * Falls back to assets extraction for older devices where nativeLibraryDir
     * copy isn't available.
     */
    private fun ensureBinary(): File? {
        // Primary: use the copy in nativeLibraryDir (has exec permission)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativeBin = File(nativeDir, "librigctld.so")
        if (nativeBin.exists() && nativeBin.canExecute()) {
            Log.i(TAG, "Using native lib: ${nativeBin.absolutePath}")
            return nativeBin
        }

        // Fallback: extract from assets (works on older Android)
        val dest = File(context.filesDir, BINARY_NAME)
        if (!dest.exists()) {
            try {
                context.assets.open(BINARY_NAME).use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                dest.setExecutable(true)
                Log.i(TAG, "Extracted rigctld to ${dest.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract rigctld: ${e.message}")
                return null
            }
        }
        if (!dest.canExecute()) dest.setExecutable(true)
        return dest
    }
}
