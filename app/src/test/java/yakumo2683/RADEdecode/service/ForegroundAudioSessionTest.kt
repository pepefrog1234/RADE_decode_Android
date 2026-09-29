package yakumo2683.RADEdecode.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAudioSessionTest {
    @Test
    fun backgroundReconnectReusesTheExistingForegroundGrant() {
        val session = ForegroundAudioSession()
        var starts = 0
        session.start { starts++ }
        val ticket = requireNotNull(session.holdForReconnect())
        assertTrue(session.isActive) // audio is paused, foreground is retained
        val resumed = session.resume(ticket) {
            session.start { error("Background recovery must not promote a service") }
            true
        }
        assertEquals(true, resumed)
        assertEquals(1, starts)
        assertFalse(session.canResume(ticket))
        assertTrue(session.isActive)
    }

    @Test
    fun notificationStopRevokesRecoveryWithoutStartingAnyAudio() {
        val session = ForegroundAudioSession()
        session.start {}
        val ticket = requireNotNull(session.holdForReconnect())
        session.stop {}
        assertNull(session.resume(ticket) { error("Stop must cancel automatic RX") })
        assertFalse(session.isActive)
    }

    @Test
    fun stopThenManualStartCannotResurrectAnOldRecovery() {
        val session = ForegroundAudioSession()
        session.start {}
        val staleTicket = requireNotNull(session.holdForReconnect())
        session.stop {}
        session.start {}
        assertNull(session.resume(staleTicket) { error("Stale reconnect must not touch new RX") })
        assertTrue(session.isActive)
    }

    @Test
    fun anotherLossSupersedesThePreviousRecoveryTicket() {
        val session = ForegroundAudioSession()
        session.start {}
        val old = requireNotNull(session.holdForReconnect())
        val current = requireNotNull(session.holdForReconnect())
        assertNull(session.resume(old) { error("Older reconnect completed late") })
        assertEquals(true, session.resume(current) { true })
        assertNull(session.resume(current) { error("A recovery ticket is single use") })
    }

    @Test
    fun replacedServiceWithoutForegroundGrantCannotAutomaticallyResume() {
        val original = ForegroundAudioSession()
        original.start {}
        val ticket = requireNotNull(original.holdForReconnect())
        val replacement = ForegroundAudioSession()
        assertNull(replacement.holdForReconnect())
        assertNull(replacement.resume(ticket) { error("Replacement must wait for user Start") })
    }

    @Test
    fun deniedForegroundStartDoesNotClaimAnActiveSessionAndUserCanRetry() {
        val session = ForegroundAudioSession()
        val rejection = IllegalStateException("Foreground service start not allowed")
        val caught = try {
            session.start { throw rejection }
            null
        } catch (e: IllegalStateException) { e }
        assertSame(rejection, caught)
        assertFalse(session.isActive)
        assertNull(session.holdForReconnect())
        session.start {}
        assertTrue(session.isActive)
    }

    @Test
    fun exhaustedReconnectDemotesTheSessionAndCannotResumeLater() {
        val session = ForegroundAudioSession()
        val events = mutableListOf<String>()
        session.start { events += "foreground" }
        val ticket = requireNotNull(session.holdForReconnect())
        if (session.canResume(ticket)) session.stop { events += "stopped" }
        assertEquals(listOf("foreground", "stopped"), events)
        assertNull(session.resume(ticket) { error("Reconnect already gave up") })
    }
}
