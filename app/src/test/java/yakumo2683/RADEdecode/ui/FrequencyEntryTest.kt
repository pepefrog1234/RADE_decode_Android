package yakumo2683.RADEdecode.ui

import org.junit.Assert.*
import org.junit.Test

class FrequencyEntryTest {
    @Test fun pollingCannotReplaceAnUnsubmittedEdit() {
        val editing = FrequencyEntry().observe(7_100_000).edit("14236")
        assertEquals("14236", editing.observe(7_200_000).text)
        assertEquals(14_236_000L, editing.observe(7_200_000).frequencyHz)
    }

    @Test fun failedOrOldReadbackPreservesTheRequestedFrequency() {
        val sent = FrequencyEntry().edit("5357,5").submitted()
        assertEquals("5357,5", sent.observe(5_300_000).text)
        val confirmed = sent.observe(5_357_500)
        assertEquals("5357.5", confirmed.text)
        assertFalse(confirmed.dirty)
        assertEquals("7100", confirmed.observe(7_100_000).text)
    }

    @Test fun newEditIsNotClearedByThePreviousRequestsReadback() {
        val newer = FrequencyEntry().edit("14236").submitted().edit("7100")
        assertEquals("7100", newer.observe(14_236_000).text)
        assertTrue(newer.observe(14_236_000).dirty)
    }

    @Test fun decimalPointAndCommaBothPreserveHzWithoutChangingMagnitude() {
        for (text in listOf("5357.5", "5357,5"))
            assertEquals(5_357_500L, FrequencyEntry(text).frequencyHz)
        assertEquals(7_100_001L, FrequencyEntry("7100.001").frequencyHz)
        assertEquals("7100.001", FrequencyEntry().observe(7_100_001).text)
        assertEquals("7100.01", FrequencyEntry().observe(7_100_010).text)
        assertEquals("7100", FrequencyEntry().observe(7_100_000).text)
    }

    @Test fun ambiguousOrInvalidInputIsRejectedWithoutDeletingCharacters() {
        for (text in listOf("", "5,357.5", "5.357,5", "1e6", "Infinity", "7100 MHz", "-7100",
                "9.999", "1300000.001", "7100.0001", "9999999999999999999999999999999")) {
            val entry = FrequencyEntry().edit(text)
            assertEquals(text, entry.text)
            assertNull("Accepted invalid input: $text", entry.frequencyHz)
        }
    }
}
