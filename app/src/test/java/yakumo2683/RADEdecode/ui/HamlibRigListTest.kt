package yakumo2683.RADEdecode.ui

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional host integration: RADE_TEST_RIGCTLD=/path/to/rigctld of Hamlib 4.7.2,
 * the version bundled in the APK.
 */
class HamlibRigListTest {
    /**
     * In the list since before this test, and models of neither Hamlib 4.5.5
     * nor 4.7.2: rigctld exits when it is started with one of them. They keep
     * their places because the selected model is saved as a position in the
     * list.
     */
    private val unknownToHamlib = setOf(7, 1008, 1012, 3001)

    @Test fun everyOtherListedModelIsOneTheBundledRigctldHas() {
        val binary = System.getenv("RADE_TEST_RIGCTLD")
        assumeTrue("Set RADE_TEST_RIGCTLD to run the real Hamlib integration", binary != null)
        val listing = ProcessBuilder(binary, "-l").redirectErrorStream(true).start()
        val known = listing.inputStream.bufferedReader().readLines()
            .mapNotNull { Regex("""^\s*(\d+)\s""").find(it)?.groupValues?.get(1)?.toInt() }.toSet()
        listing.waitFor()
        assertEquals(unknownToHamlib, rigModels.map { it.id }.filter { it !in known }.toSet())
    }
}
