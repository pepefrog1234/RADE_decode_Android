package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test

class RigctldCommandTest {
    private val commands = listOf(
        // USB serial through this app's pty bridge
        rigctldCommand("/app/lib/librigctld.so", 3073, 4532, "/dev/pts/3", 19200, "94", pty = true),
        // Icom network pty
        rigctldCommand("/app/lib/librigctld.so", 3085, 4532, "/dev/pts/4", 115200,
            pty = true, uncachedReadback = true),
        // SDR application's CAT server, or a remote rigctld (NET rigctl)
        rigctldCommand("/app/lib/librigctld.so", 2048, 4532, "192.168.1.20:13013"),
        rigctldCommand("/app/lib/librigctld.so", 2, 4532, "192.168.1.20:4532"),
        // No device at all
        rigctldCommand("/app/lib/librigctld.so", 1, 4532)
    )

    /** Hamlib listens on every interface unless it is given an address. */
    @Test fun everyCommandLineListensOnLoopbackOnly() {
        for (command in commands) {
            assertEquals("$command", 1, command.count { it == "-T" || it.startsWith("--listen-addr") })
            assertEquals("$command", "127.0.0.1", command[command.indexOf("-T") + 1])
        }
        assertEquals("127.0.0.1", RIGCTLD_LISTEN_ADDRESS)
    }

    /**
     * Hamlib 4.7 left to itself caches for 1000 ms, longer than RigController
     * waits before it asks whether a key-on took effect, and runs a publisher
     * thread nothing listens to.
     */
    @Test fun everyCommandLineSaysHowLongHamlibMayCache() {
        for (command in commands) {
            val conf = command.single { it.startsWith("--set-conf=") }.removePrefix("--set-conf=").split(",")
            assertTrue("$command", "poll_interval=0" in conf)
            assertEquals("$command", 1, conf.count { it.startsWith("cache_timeout=") })
        }
        assertEquals(500, RIGCTLD_CACHE_MS)
    }

    @Test fun usbSerialPty() {
        assertEquals(
            listOf("/app/lib/librigctld.so", "-m", "3073", "-t", "4532", "-T", "127.0.0.1",
                "-r", "/dev/pts/3", "-s", "19200", "-c", "0x94", "--set-conf=poll_interval=0,cache_timeout=500"),
            commands[0]
        )
    }

    @Test fun icomNetworkPtyReadsBackFromTheRadio() {
        assertEquals(
            listOf("/app/lib/librigctld.so", "-m", "3085", "-t", "4532", "-T", "127.0.0.1",
                "-r", "/dev/pts/4", "-s", "115200", "--set-conf=poll_interval=0,cache_timeout=0"),
            commands[1]
        )
    }

    @Test fun networkRigHasNoBaudRate() {
        assertEquals(
            listOf("/app/lib/librigctld.so", "-m", "2048", "-t", "4532", "-T", "127.0.0.1",
                "-r", "192.168.1.20:13013", "--set-conf=poll_interval=0,cache_timeout=500"),
            commands[2]
        )
        assertEquals(
            listOf("/app/lib/librigctld.so", "-m", "1", "-t", "4532", "-T", "127.0.0.1",
                "--set-conf=poll_interval=0,cache_timeout=500"),
            commands[4]
        )
    }

    @Test fun civAddressIsAlwaysPassedAsHex() {
        for (address in listOf("94", "0x94", "0X94")) {
            val command = rigctldCommand("rigctld", 3073, 4532, "/dev/pts/3", 19200, address)
            assertEquals("0x94", command[command.indexOf("-c") + 1])
        }
        assertFalse("-c" in rigctldCommand("rigctld", 3073, 4532, "/dev/pts/3", 19200))
    }

    @Test fun xieguTimeoutsApplyToAPtyOnly() {
        val cached = "--set-conf=poll_interval=0,cache_timeout=500"
        for (model in listOf(3076, 3087, 3088, 3089, 3091)) {
            assertEquals("$cached,timeout=200,retry=1",
                rigctldCommand("rigctld", model, 4532, "/dev/pts/3", 19200, pty = true).last())
            assertEquals(cached, rigctldCommand("rigctld", model, 4532, "/dev/ttyUSB0", 19200).last())
        }
        assertEquals(cached, rigctldCommand("rigctld", 3085, 4532, "/dev/pts/3", 115200, pty = true).last())
    }
}
