package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Optional host integration: RADE_TEST_RIGCTLD=/path/to/rigctld of Hamlib 4.7.2,
 * the version bundled in the APK (as for [HamlibIcomTuningTest]).
 *
 * rigctld has no login. Started the way the app starts it, it must answer on
 * loopback and on no other address of the machine.
 */
class HamlibListenAddressTest {
    @Test fun theAppsCommandLineIsReachableOnLoopbackOnly() {
        val binary = System.getenv("RADE_TEST_RIGCTLD")
        assumeTrue("Set RADE_TEST_RIGCTLD to run the real Hamlib integration", binary != null)
        check(File(binary!!).canExecute())

        // The addresses that do reach a listener bound to every interface.
        // Without one, this machine cannot show the difference.
        val outside = ServerSocket(0).use { everyInterface ->
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { connects(it, everyInterface.localPort) }
        }
        assumeTrue("This machine has no address besides loopback", outside.isNotEmpty())

        val port = ServerSocket(0).use { it.localPort }
        val log = File.createTempFile("rade-hamlib-listen-", ".log")
        val process = ProcessBuilder(rigctldCommand(binary, model = 1, port = port))
            .redirectErrorStream(true).redirectOutput(log).start()
        var passed = false
        try {
            val loopback = InetAddress.getByName(RIGCTLD_LISTEN_ADDRESS)
            val deadline = System.nanoTime() + 15_000_000_000L
            var answer: String? = null
            while (answer == null && System.nanoTime() < deadline && process.isAlive) {
                try {
                    Socket(loopback, port).use { socket ->
                        socket.soTimeout = 5000
                        socket.getOutputStream().write("+\\get_freq\n".toByteArray())
                        val reader = socket.getInputStream().bufferedReader()
                        answer = generateSequence { reader.readLine() }
                            .firstOrNull { it.startsWith("RPRT ") }
                    }
                } catch (_: IOException) { Thread.sleep(50) }
            }
            assertEquals("The app's own connection must still work", "RPRT 0", answer)

            for (address in outside) {
                assertFalse("rigctld answers on ${address.hostAddress}:$port", connects(address, port))
            }
            passed = true
        } catch (t: Throwable) {
            throw AssertionError("Hamlib log: $log\n${log.readText().takeLast(4000)}", t)
        } finally {
            process.destroyForcibly().waitFor()
            if (passed) log.delete()
        }
    }

    private fun connects(address: InetAddress, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), 2000) }
        true
    } catch (_: IOException) {
        false
    }
}
