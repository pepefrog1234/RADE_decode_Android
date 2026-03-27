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
    }

    private var process: Process? = null
    private var logJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isRunning: Boolean get() = process?.isAlive == true

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
            cmd.addAll(listOf("-c", civAddr))
        }

        Log.i(TAG, "Starting: ${cmd.joinToString(" ")}")

        return try {
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
            process = pb.start()

            // Log stdout/stderr in background
            logJob = scope.launch {
                try {
                    process?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.d(TAG, line)
                        }
                    }
                } catch (_: Exception) {}
            }

            // Give it a moment to start
            Thread.sleep(500)

            if (process?.isAlive == true) {
                Log.i(TAG, "rigctld started (pid=${getPid()})")
                true
            } else {
                Log.e(TAG, "rigctld exited immediately with code ${process?.exitValue()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start rigctld: ${e.message}")
            false
        }
    }

    fun stop() {
        logJob?.cancel()
        logJob = null
        process?.let { p ->
            p.destroy()
            try { p.waitFor() } catch (_: Exception) {}
            Log.i(TAG, "rigctld stopped")
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
     * Extract rigctld binary from assets to app's internal storage.
     * Only copies if the file doesn't exist or has changed.
     */
    private fun ensureBinary(): File? {
        val dest = File(context.filesDir, BINARY_NAME)

        if (!dest.exists()) {
            try {
                context.assets.open(BINARY_NAME).use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                dest.setExecutable(true)
                Log.i(TAG, "Extracted rigctld to ${dest.absolutePath} (${dest.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract rigctld: ${e.message}")
                return null
            }
        }

        if (!dest.canExecute()) {
            dest.setExecutable(true)
        }

        return dest
    }
}
