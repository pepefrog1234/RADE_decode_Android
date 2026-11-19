package yakumo2683.RADEdecode.network

/**
 * The one address the bundled rigctld listens on. rigctld has no login, and
 * left to itself it listens on every interface: anyone on the same Wi-Fi,
 * hotspot or VPN could then retune the rig or key it. Only this app talks to
 * the daemon, over loopback ([RigController] connects to this address).
 */
internal const val RIGCTLD_LISTEN_ADDRESS = "127.0.0.1"

/**
 * How long the bundled rigctld may answer from Hamlib's cache, in ms.
 * [RigController] waits longer than this before it trusts a PTT status.
 */
internal const val RIGCTLD_CACHE_MS = 500

private val FAST_PTY_CAT_MODELS = setOf(
    3076, // Xiegu X108G
    3087, // Xiegu X6100
    3088, // Xiegu G90
    3089, // Xiegu X5105
    3091  // Xiegu X6200
)

/**
 * Command line for the bundled rigctld.
 *
 * @param binary    Path of the rigctld executable
 * @param model     Hamlib rig model number (e.g. 1=Dummy, 2=NET rigctl, 3014=IC-7300)
 * @param port      TCP port to listen on, at [RIGCTLD_LISTEN_ADDRESS]
 * @param device    Serial device, pty slave or "host:port" of a network rig; "" for none
 * @param speed     Baud rate; 0 leaves Hamlib's default (a pty or network rig ignores it)
 * @param civAddr   Optional CI-V address for Icom rigs (hex string e.g. "94")
 * @param pty       [device] is a pty bridged by this app (USB serial or Icom network)
 * @param uncachedReadback Query the radio on every read instead of answering from Hamlib's cache
 */
internal fun rigctldCommand(
    binary: String,
    model: Int,
    port: Int,
    device: String = "",
    speed: Int = 0,
    civAddr: String = "",
    pty: Boolean = false,
    uncachedReadback: Boolean = false
): List<String> = buildList {
    add(binary)
    // NOTE: do NOT pass -vvvvv here. Max verbosity makes rigctld emit many
    // stderr lines per command (piped through JNI to logcat); combined with
    // the slow Xiegu G90 CI-V it contributed to multi-second PTT lag.
    addAll(listOf("-m", model.toString()))
    addAll(listOf("-t", port.toString()))
    addAll(listOf("-T", RIGCTLD_LISTEN_ADDRESS))

    if (device.isNotEmpty()) addAll(listOf("-r", device))
    if (speed > 0) addAll(listOf("-s", speed.toString()))
    if (civAddr.isNotEmpty()) addAll(listOf("-c", civAddrArg(civAddr)))

    // How long Hamlib answers a read from what it last read or was told to
    // set, instead of asking the radio. Always given: left to itself Hamlib
    // 4.7 starts a publisher thread (for multicast listeners this app does not
    // have) that sets the cache to its poll interval, 1000 ms, and
    // RigController.confirmKeyed would then read back its own key-on.
    // poll_interval=0 keeps that thread from starting; RigController polls.
    // The owned Icom network daemon must query the radio after Set/OFF, not
    // echo the value that Hamlib cached on an ACK.
    val conf = mutableListOf("poll_interval=0", "cache_timeout=${if (uncachedReadback) 0 else RIGCTLD_CACHE_MS}")

    if (pty && model in FAST_PTY_CAT_MODELS) {
        // Bound Xiegu CI-V/PTT readback stalls without applying the tight
        // timeout to Icom RS-BA1/IC-705 network ptys. IC-705 Wi-Fi can exceed
        // 200 ms and then fails get_freq, preventing FreeDV Reporter freq_change.
        conf += listOf("timeout=200", "retry=1")
    }

    add("--set-conf=" + conf.joinToString(","))
}

/**
 * Hamlib parses `--civaddr` as DECIMAL unless the value is 0x-prefixed
 * (icom.c TOK_CIVADDR: atoi() vs strtol(.., 16)). The CI-V field in this
 * app is hex ("94" means 0x94), so always pass the 0x form.
 */
private fun civAddrArg(civAddr: String): String =
    "0x" + civAddr.removePrefix("0x").removePrefix("0X")
