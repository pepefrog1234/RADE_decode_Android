package yakumo2683.RADEdecode.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercises the actual UDP reader, retransmit buffer and teardown against a local radio. */
class IcomSessionLifecycleTest {
    @Test
    fun lostLogoutCanBeRequestedBeforeGoodbyeAndStopsTransmitTraffic() = runBlocking {
        FakeRadio().use { radio ->
            val pty = FakePty()
            val manager = IcomNetworkManager(pty)
            val persisted = AtomicReference<String?>()
            val clears = Collections.synchronizedList(mutableListOf<Long>())
            manager.saveStaleToken = {
                persisted.set(it)
                if (it == null) clears.add(System.nanoTime())
            }
            manager.loadStaleToken = { persisted.get() }
            try {
                assertEquals(FakePty.PATH, withTimeout(8_000) {
                    manager.connect("127.0.0.1", radio.controlPort, "test", "test")
                })
                assertTrue(manager.audioLinkUp)
                val token = persisted.get()
                assertNotNull(token)

                // Keep both transmit producers active through teardown. CI-V PTT also
                // schedules redundant raw sends, which must not resurrect TX after logout.
                val producer = launch(Dispatchers.Default) {
                    while (isActive) {
                        pty.outbound.offer(PTT_ON.copyOf())
                        manager.sendAudioFrame(ShortArray(960) { 123 })
                        delay(5)
                    }
                }
                try {
                    await(radio.sawSerialData, "PTY never reached the fake radio")
                    await(radio.sawAudioData, "TX audio never reached the fake radio")
                    val disconnected = CountDownLatch(1)
                    val disconnectFailure = AtomicReference<Throwable?>()
                    val worker = thread(name = "icom-test-disconnect") {
                        try { manager.disconnect() }
                        catch (t: Throwable) { disconnectFailure.set(t) }
                        finally { disconnected.countDown() }
                    }
                    await(radio.firstLogout, "No deauth sent")
                    // The fake sent an ACK for another token. It must not erase the
                    // recovery token or bypass the retransmit grace period.
                    assertEquals(token, persisted.get())
                    await(radio.requestedLogout, "Radio could not request the missing deauth")
                    await(radio.retransmittedLogout, "Requested deauth was not retransmitted")
                    await(disconnected, "disconnect did not finish")
                    worker.join(1_000)
                    disconnectFailure.get()?.let { throw AssertionError("disconnect failed", it) }
                } finally {
                    producer.cancelAndJoin()
                }

                radio.checkFailure()
                val packets = radio.snapshot()
                val first = packets.first { it.isLogout && it.loginNumber == 1 }
                val logoutCopies = packets.filter { it.isLogout && it.loginNumber == 1 }
                val preceding = packets.last {
                    it.stream == "control" && it.nanoTime < first.nanoTime && it.isTracked
                }
                assertTrue("Deauth must have a tracked sequence", first.seq != 0)
                assertEquals((preceding.seq + 1) and 0xffff, first.seq)
                assertTrue("Expected proactive copies and the requested resend", logoutCopies.size >= 4)
                logoutCopies.forEach { assertArrayEquals(first.bytes, it.bytes) }
                assertTrue("Inner auth sequence must advance beyond login", u16(first.bytes, 23) > 0)

                val requestedCopy = logoutCopies.first {
                    it.nanoTime >= radio.retransmitRequestedAt
                }
                val goodbye = packets.first { it.type == 5 }
                assertTrue("A goodbye arrived before the requested deauth", requestedCopy.nanoTime < goodbye.nanoTime)
                assertTrue("An unrelated logout ACK closed the streams", goodbye.nanoTime > radio.correctAckSentAt)
                assertNull("Matching logout ACK must clear the saved token", persisted.get())
                assertTrue("Token was cleared before the matching ACK", clears.all {
                    it >= radio.correctAckSentAt
                })

                // Allow queued UDP datagrams 100 ms to reach the other two socket
                // readers; the producer keeps offering new TX throughout this window.
                val unwanted = packets.filter {
                    it.nanoTime > first.nanoTime + TimeUnit.MILLISECONDS.toNanos(100) &&
                        (it.isSerialData || it.isAudioData || it.isAuthRefresh)
                }
                assertTrue("Normal TX or reauth continued after deauth: $unwanted", unwanted.isEmpty())
                assertFalse(manager.isConnected)
            } finally {
                manager.disconnect()
            }
        }
    }

    @Test
    fun unacknowledgedLogoutIsCleanedOnceAndPreviousLoginReplyCannotPoisonReconnect() = runBlocking {
        FakeRadio(leaveFirstLogoutUnacknowledged = true, delaySecondLogin = true).use { radio ->
            val manager = IcomNetworkManager(FakePty())
            val persisted = AtomicReference<String?>()
            manager.saveStaleToken = { persisted.set(it) }
            manager.loadStaleToken = { persisted.get() }
            try {
                assertEquals(FakePty.PATH, withTimeout(8_000) {
                    manager.connect("127.0.0.1", radio.controlPort, "test", "test")
                })
                val firstToken = persisted.get()
                assertNotNull(firstToken)
                manager.disconnect()
                assertEquals("An unacknowledged logout must remain recoverable", firstToken, persisted.get())

                assertEquals(FakePty.PATH, withTimeout(8_000) {
                    manager.connect("127.0.0.1", radio.controlPort, "test", "test")
                })
                await(radio.staleLoginSent, "Fake did not inject the delayed old login response")
                radio.checkFailure()
                assertTrue(manager.isConnected)
                assertTrue(manager.audioLinkUp)
                val logins = radio.snapshot().filter { it.isLogin }
                assertEquals(2, logins.size)
                assertNotEquals("Reconnect reused the previous login nonce", u16(logins[0].bytes, 26), u16(logins[1].bytes, 26))
                assertEquals("Pre-login cleanup must not consume the inner login sequence", 0, u16(logins[1].bytes, 23))
                val cleanups = radio.snapshot().filter { it.isLogout && it.loginNumber == 0 }
                assertTrue("No recovery cleanup was sent on the next connection", cleanups.isNotEmpty())
                assertTrue(cleanups.all { it.nanoTime < logins[1].nanoTime })
                cleanups.forEach { assertArrayEquals(cleanups.first().bytes, it.bytes) }
                assertEquals(firstToken, hex(cleanups.first().bytes.copyOfRange(26, 32)))
                assertTrue("Stale cleanup must itself be tracked", cleanups.first().seq != 0)
                assertNotEquals("New connection kept the old token", firstToken, persisted.get())
                // The delayed packet uses current SIDs but the previous nonce and an
                // explicit credential failure: accepting it would abort reconnect.
                assertEquals(hex(radio.currentToken()), persisted.get())
                manager.disconnect()
                assertNull(persisted.get())
                assertEquals("Cleanup was incorrectly repeated after login", cleanups.size,
                    radio.snapshot().count { it.isLogout && it.loginNumber == 0 })
            } finally {
                manager.disconnect()
            }
        }
    }

    @Test
    fun radioDisconnectDuringAudioHandshakeRetriesTheConnection() = runBlocking {
        FakeRadio(disconnectFirstAudioHandshake = true).use { radio ->
            val manager = IcomNetworkManager(FakePty())
            try {
                // The first attempt reaches serialOpened, but the radio ends its
                // session before audio can complete the deferred connect result.
                assertEquals(FakePty.PATH, withTimeout(8_000) {
                    manager.connect("127.0.0.1", radio.controlPort, "test", "test")
                })
                await(radio.radioDisconnectSent, "Radio did not terminate the first handshake")
                radio.checkFailure()
                assertEquals("Session cancellation skipped the normal connect retry", 2,
                    radio.snapshot().count { it.isLogin })
                assertTrue(manager.isConnected)
                assertTrue(manager.audioLinkUp)
                assertTrue(manager.state.value.error.isEmpty())
            } finally {
                manager.disconnect()
            }
        }
    }

    @Test
    fun callerCancellationDuringAudioHandshakeStillCancelsAndClosesTheSession() = runBlocking {
        FakeRadio(holdAudioHandshake = true).use { radio ->
            val pty = FakePty()
            val manager = IcomNetworkManager(pty)
            val pending = async(Dispatchers.Default) {
                manager.connect("127.0.0.1", radio.controlPort, "test", "test")
            }
            try {
                await(radio.audioHandshakeHeld, "Connect did not reach the held audio handshake")
                assertTrue(pty.isOpen)
                withTimeout(3_000) { pending.cancelAndJoin() }
                assertTrue("Caller cancellation was converted into a retry", pending.isCancelled)
                await(radio.sawGoodbye, "Cancelled connect left its streams open")
                radio.checkFailure()
                assertFalse(manager.state.value.connecting)
                assertFalse(manager.isConnected)
                assertFalse(manager.audioLinkUp)
                assertFalse("Cancelled connect left the PTY open", pty.isOpen)
                assertEquals(1, radio.snapshot().count { it.isLogin })
            } finally {
                pending.cancelAndJoin()
                manager.disconnect()
            }
        }
    }

    private class FakePty : IcomPty {
        companion object { const val PATH = "/fake/icom-pty" }
        val outbound = LinkedBlockingQueue<ByteArray>()
        @Volatile private var opened = false
        val isOpen get() = opened
        override fun open(): String { opened = true; return PATH }
        override fun write(data: ByteArray, len: Int): Int = len
        override fun read(timeoutMs: Int): ByteArray? =
            if (opened) outbound.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: byteArrayOf() else null
        override fun close() { opened = false; outbound.clear() }
    }

    private data class Received(val stream: String, val bytes: ByteArray, val nanoTime: Long, val loginNumber: Int) {
        val type get() = bytes[4].toInt() and 0xff
        val seq get() = u16(bytes, 6)
        val isTracked get() = type == 0
        val isLogin get() = bytes.size == 128 && isTracked
        val isLogout get() = bytes.size == 64 && isTracked && bytes[21] == 1.toByte()
        val isAuthRefresh get() = bytes.size == 64 && isTracked && bytes[21] == 5.toByte()
        val isSerialData get() = stream == "serial" && bytes.size >= 22 && bytes[16] == 0xc1.toByte()
        val isAudioData get() = stream == "audio" && bytes.size > 24 && bytes[16] == 0x80.toByte()
        override fun toString() = "$stream size=${bytes.size} type=$type seq=$seq"
    }

    private class FakeRadio(
        private val leaveFirstLogoutUnacknowledged: Boolean = false,
        private val delaySecondLogin: Boolean = false,
        private val disconnectFirstAudioHandshake: Boolean = false,
        private val holdAudioHandshake: Boolean = false
    ) : AutoCloseable {
        private val controlSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        private val serialSocket = DatagramSocket(50002, InetAddress.getByName("127.0.0.1"))
        private val audioSocket = DatagramSocket(50003, InetAddress.getByName("127.0.0.1"))
        val controlPort get() = controlSocket.localPort
        private val sockets = listOf("control" to controlSocket, "serial" to serialSocket, "audio" to audioSocket)
        private val received = Collections.synchronizedList(mutableListOf<Received>())
        private val failure = AtomicReference<Throwable?>()
        private val nextSid = AtomicInteger(0x12340000)
        private var loginCount = 0
        private var previousToken = byteArrayOf()
        @Volatile private var token = byteArrayOf()
        @Volatile private var running = true
        @Volatile private var latestControlPeer: Peer? = null
        @Volatile private var disconnectRequested = false
        val firstLogout = CountDownLatch(1)
        val requestedLogout = CountDownLatch(1)
        val retransmittedLogout = CountDownLatch(1)
        val staleLoginSent = CountDownLatch(1)
        val sawSerialData = CountDownLatch(1)
        val sawAudioData = CountDownLatch(1)
        val audioHandshakeHeld = CountDownLatch(1)
        val radioDisconnectSent = CountDownLatch(1)
        val sawGoodbye = CountDownLatch(1)
        @Volatile var retransmitRequestedAt = Long.MAX_VALUE
        @Volatile var correctAckSentAt = Long.MAX_VALUE

        private class Peer(val address: InetSocketAddress, val clientSid: ByteArray, val radioSid: Int) {
            var replySeq = 1
            var loginNumber = 0
            var token = byteArrayOf()
            var logout: ByteArray? = null
            var logoutAt = 0L
            var requested = false
            var acknowledged = false
            var delayedLogin: ByteArray? = null
            var delayedLoginAt = 0L
        }

        private val workers = sockets.map { (name, socket) ->
            socket.soTimeout = 10
            thread(name = "fake-icom-$name") {
                val peers = mutableMapOf<Pair<InetSocketAddress, String>, Peer>()
                try {
                    while (running) {
                        val dp = DatagramPacket(ByteArray(2048), 2048)
                        try {
                            socket.receive(dp)
                            val bytes = dp.data.copyOf(dp.length)
                            if (bytes.size < 16) continue
                            val address = dp.socketAddress as InetSocketAddress
                            val peer = peers.getOrPut(address to hex(bytes.copyOfRange(8, 12))) {
                                Peer(address, bytes.copyOfRange(8, 12), nextSid.incrementAndGet())
                            }
                            if (name == "control" && bytes.size == 128 && bytes[4] == 0.toByte()) {
                                peer.loginNumber = ++loginCount
                                latestControlPeer = peer
                            }
                            val packet = Received(name, bytes, System.nanoTime(), peer.loginNumber)
                            received.add(packet)
                            if (packet.isSerialData) sawSerialData.countDown()
                            if (packet.isAudioData) sawAudioData.countDown()
                            if (packet.type == 5) sawGoodbye.countDown()
                            handle(name, socket, peer, packet)
                        } catch (_: SocketTimeoutException) { /* run scheduled radio actions */ }
                        if (name == "control") peers.values.forEach { tick(socket, it) }
                    }
                } catch (e: SocketException) {
                    if (running) failure.set(e)
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
        }

        private fun handle(name: String, socket: DatagramSocket, peer: Peer, packet: Received) {
            val p = packet.bytes
            when (packet.type) {
                3 -> {
                    if (name == "audio" && (holdAudioHandshake ||
                            (disconnectFirstAudioHandshake && latestControlPeer?.loginNumber == 1))) {
                        audioHandshakeHeld.countDown()
                        if (disconnectFirstAudioHandshake && radioDisconnectSent.count != 0L) {
                            disconnectRequested = true
                        }
                        return
                    }
                    send(socket, peer, reply(peer, 16, type = 4, seq = 0))
                }
                6 -> send(socket, peer, reply(peer, 16, type = 6, seq = 1))
                7 -> if (p.size == 21 && p[16] == 0.toByte()) {
                    val response = reply(peer, 21, type = 7, seq = packet.seq)
                    response[16] = 1
                    p.copyInto(response, 17, 17, 21)
                    send(socket, peer, response)
                }
                0 -> when {
                    name != "control" && p.size == 16 -> send(socket, peer, reply(peer, 16))
                    name == "control" && packet.isLogin -> {
                        peer.token = byteArrayOf(p[26], p[27], 0x21, 0x32, 0x43, peer.loginNumber.toByte())
                        token = peer.token.copyOf()
                        val response = reply(peer, 96)
                        p.copyInto(response, 23, 23, 25)
                        peer.token.copyInto(response, 26)
                        if (delaySecondLogin && peer.loginNumber == 2) {
                            val stale = response.copyOf()
                            previousToken.copyInto(stale, 26)
                            byteArrayOf(-1, -1, -1, -2).copyInto(stale, 48)
                            send(socket, peer, stale)
                            staleLoginSent.countDown()
                            peer.delayedLogin = response
                            peer.delayedLoginAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150)
                        } else {
                            previousToken = peer.token.copyOf()
                            send(socket, peer, response)
                        }
                    }
                    name == "control" && packet.isLogout -> handleLogout(socket, peer, packet)
                    name == "control" && p.size == 64 -> {
                        send(socket, peer, authReply(peer, p))
                        if (p[21] == 5.toByte()) {
                            val caps = reply(peer, 168)
                            ByteArray(16) { (it + 1).toByte() }.copyInto(caps, 66)
                            send(socket, peer, caps)
                        }
                    }
                    name == "control" && p.size == 144 -> {
                        val grant = reply(peer, 144)
                        peer.token.copyInto(grant, 26)
                        "IC-7300MK2".toByteArray(Charsets.US_ASCII).copyInto(grant, 64)
                        grant[96] = 1
                        send(socket, peer, grant)
                    }
                }
            }
        }

        private fun handleLogout(socket: DatagramSocket, peer: Peer, packet: Received) {
            // The old token cleanup before login intentionally receives no ACK.
            if (peer.loginNumber == 0) return
            if (peer.logout == null) {
                peer.logout = packet.bytes.copyOf()
                peer.logoutAt = packet.nanoTime
                if (!leaveFirstLogoutUnacknowledged || peer.loginNumber != 1) {
                    val wrong = authReply(peer, packet.bytes)
                    wrong[31] = (wrong[31].toInt() xor 0x7f).toByte()
                    send(socket, peer, wrong)
                }
                firstLogout.countDown()
            }
            if (peer.requested && !peer.acknowledged && packet.bytes.contentEquals(peer.logout)) {
                peer.acknowledged = true
                retransmittedLogout.countDown()
                correctAckSentAt = System.nanoTime()
                send(socket, peer, authReply(peer, packet.bytes))
            }
        }

        private fun tick(socket: DatagramSocket, peer: Peer) {
            val now = System.nanoTime()
            if (disconnectRequested && peer === latestControlPeer && radioDisconnectSent.count != 0L) {
                disconnectRequested = false
                val disconnect = reply(peer, 80)
                disconnect[64] = 1
                send(socket, peer, disconnect)
                radioDisconnectSent.countDown()
            }
            if (peer.delayedLogin != null && now >= peer.delayedLoginAt) {
                send(socket, peer, peer.delayedLogin!!)
                peer.delayedLogin = null
            }
            if (peer.logout != null && !peer.requested &&
                !(leaveFirstLogoutUnacknowledged && peer.loginNumber == 1) &&
                now - peer.logoutAt >= TimeUnit.MILLISECONDS.toNanos(300)) {
                peer.requested = true
                retransmitRequestedAt = now
                // Delayed loss requests for an old auth packet must not log us
                // back in while closing. It may be answered with an idle filler.
                snapshot().lastOrNull {
                    it.stream == "control" && it.loginNumber == peer.loginNumber && it.isAuthRefresh
                }?.let { oldAuth ->
                    send(socket, peer, reply(peer, 16, type = 1, seq = oldAuth.seq))
                }
                send(socket, peer, reply(peer, 16, type = 1, seq = u16(peer.logout!!, 6)))
                requestedLogout.countDown()
            }
        }

        private fun authReply(peer: Peer, request: ByteArray): ByteArray = reply(peer, 64).also {
            it[21] = request[21]
            request.copyInto(it, 23, 23, 32)
        }

        private fun reply(peer: Peer, size: Int, type: Int = 0, seq: Int = peer.replySeq++): ByteArray =
            ByteArray(size).also {
                it[0] = size.toByte(); it[1] = (size ushr 8).toByte()
                it[4] = type.toByte()
                it[6] = seq.toByte(); it[7] = (seq ushr 8).toByte()
                for (i in 0..3) it[8 + i] = (peer.radioSid ushr (24 - i * 8)).toByte()
                peer.clientSid.copyInto(it, 12)
                if (size > 20) it[20] = 2
            }

        private fun send(socket: DatagramSocket, peer: Peer, bytes: ByteArray) {
            socket.send(DatagramPacket(bytes, bytes.size, peer.address))
        }

        fun snapshot(): List<Received> = synchronized(received) { received.toList() }
        fun currentToken(): ByteArray = token.copyOf()
        fun checkFailure() { failure.get()?.let { throw AssertionError("Fake radio failed", it) } }
        override fun close() {
            running = false
            sockets.forEach { it.second.close() }
            workers.forEach { it.join(1_000) }
            checkFailure()
        }
    }

    companion object {
        private val PTT_ON = byteArrayOf(0xfe.toByte(), 0xfe.toByte(), 0xa4.toByte(), 0xe0.toByte(), 0x1c, 0, 1, 0xfd.toByte())
        private fun u16(bytes: ByteArray, offset: Int) =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun await(latch: CountDownLatch, message: String) {
            assertTrue(message, latch.await(3, TimeUnit.SECONDS))
        }
    }
}
