package yakumo2683.RADEdecode.network

/**
 * Inbound sequence tracking for one Icom RS-BA1 UDP stream.
 *
 * The radio numbers every tracked packet it sends — idle pkt0s and data alike
 * — with a 16-bit wrapping sequence at bytes 6-7, and keeps a resend buffer.
 * The reference clients (kappanhang, wfview) watch that numbering and ask the
 * radio to RESEND whatever went missing: a single-seq request (type 0x01) or a
 * range request (type 0x18), exactly the way the radio asks us. We never did,
 * so a downlink packet lost on a weak LTE/VPN link — a chunk of RX audio, a
 * CI-V PTT acknowledgement — was simply gone. This class does the bookkeeping;
 * [IcomNetworkManager] owns the sockets.
 *
 * It also DROPS duplicates: a packet that arrives twice (the original after
 * all, plus the radio's answer to our request) must reach the consumer once,
 * or a duplicated CI-V "OK" could be taken by rigctld as the acknowledgement
 * of the NEXT command.
 *
 * Single-threaded: only touched from the stream's reader thread.
 */
internal class IcomRxSeqTracker(
    /** Largest span of missing packets to ask for in one request (kappanhang caps at 10). */
    private val maxRequestSpan: Int = 10,
    /** Requests sent per poll are capped at this many packets in total. */
    private val maxRequestPerPoll: Int = 30,
    /** Keep asking for a hole for this long, then give up (the consumer conceals). */
    private val giveUpAfterMs: Long = 600,
    /** Minimum spacing between requests for the same hole. */
    private val reRequestMs: Long = 120,
    /** A jump larger than this (either way) is a stream restart, not loss: resync silently. */
    private val resyncSpan: Int = 512
) {
    class Stats {
        var packets = 0L
        var duplicates = 0L
        var missing = 0L
        var requests = 0L      // request packets sent (single or range)
        var healed = 0L        // holes later filled (retransmit or late arrival)
        var lost = 0L          // holes given up on
        var resyncs = 0L
        var pings = 0L         // pkt7 pings seen from the radio
        var healedByPing = 0L  // holes whose seq turned up as a PING → pings share the counter
        override fun toString() =
            "pkts=$packets dup=$duplicates missing=$missing req=$requests healed=$healed lost=$lost " +
            "resync=$resyncs pings=$pings pingFill=$healedByPing"
    }

    val stats = Stats()

    /** Inclusive modular range of sequence numbers to request. */
    data class SeqRange(val first: Int, val last: Int) {
        val size: Int get() = ((last - first) and 0xFFFF) + 1
    }

    private class Hole(val firstMissedMs: Long) {
        var lastRequestMs = 0L
        var requests = 0
    }

    private var highest = -1                     // newest seq accepted so far
    private val holes = HashMap<Int, Hole>()

    val openHoles: Int get() = holes.size

    /**
     * Account for a tracked packet from the radio.
     * @return true when [seq] is new and should be delivered; false when it is a
     *   duplicate of something already delivered (drop it). Idle packets are
     *   never delivered anyway — callers use the return value for data only.
     */
    fun onPacket(seq: Int, nowMs: Long): Boolean {
        stats.packets++
        val s = seq and 0xFFFF
        if (highest < 0) {
            highest = s
            return true
        }
        val ahead = (s - highest) and 0xFFFF
        if (ahead == 0) {
            stats.duplicates++
            return false
        }
        if (ahead < 0x8000) {
            // Newer than anything seen. Everything skipped in between is missing.
            if (ahead > resyncSpan) {
                resync(s)
                return true
            }
            var m = (highest + 1) and 0xFFFF
            while (m != s) {
                holes[m] = Hole(nowMs)
                stats.missing++
                m = (m + 1) and 0xFFFF
            }
            highest = s
            return true
        }
        // Older than the newest: a late/retransmitted packet filling a hole, or a duplicate.
        val behind = 0x10000 - ahead
        if (behind > resyncSpan) {
            resync(s)
            return true
        }
        return if (holes.remove(s) != null) {
            stats.healed++
            true
        } else {
            stats.duplicates++
            false
        }
    }

    private fun resync(s: Int) {
        holes.clear()
        highest = s
        stats.resyncs++
    }

    /**
     * A pkt7 ping from the radio carrying [seq] at bytes 6-7. If that seq is one
     * we thought was missing, the radio's pings consume tracking sequence
     * numbers — the hole was never a lost packet. Counted so the field log can
     * confirm or refute the model before gap requests are ever re-enabled.
     */
    fun onPing(seq: Int) {
        stats.pings++
        if (holes.remove(seq and 0xFFFF) != null) stats.healedByPing++
    }

    /** Drop holes older than the give-up horizon (counted as lost) without
     *  producing any requests — for observe-only use. */
    fun expireHoles(nowMs: Long) {
        if (holes.isEmpty()) return
        val it = holes.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value.firstMissedMs > giveUpAfterMs) {
                it.remove()
                stats.lost++
            }
        }
    }

    /**
     * Holes that are due for a (re-)request now, coalesced into ranges of at
     * most [maxRequestSpan], oldest first, at most [maxRequestPerPoll] packets.
     * Holes older than [giveUpAfterMs] are abandoned (counted as lost) so a
     * dead packet is never asked for forever.
     */
    fun dueRequests(nowMs: Long): List<SeqRange> {
        if (holes.isEmpty()) return emptyList()
        val due = ArrayList<Int>()
        val it = holes.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val h = e.value
            if (nowMs - h.firstMissedMs > giveUpAfterMs) {
                it.remove()
                stats.lost++
                continue
            }
            if (h.requests == 0 || nowMs - h.lastRequestMs >= reRequestMs) due.add(e.key)
        }
        if (due.isEmpty()) return emptyList()
        // Oldest first = furthest behind `highest` in modular distance.
        due.sortByDescending { (highest - it) and 0xFFFF }

        val out = ArrayList<SeqRange>()
        var budget = maxRequestPerPoll
        var i = 0
        while (i < due.size && budget > 0) {
            val first = due[i]
            var last = first
            var n = 1
            while (i + n < due.size && n < maxRequestSpan && n < budget &&
                due[i + n] == ((last + 1) and 0xFFFF)
            ) {
                last = due[i + n]
                n++
            }
            for (k in 0 until n) {
                holes[due[i + k]]?.let { h -> h.requests++; h.lastRequestMs = nowMs }
            }
            out.add(SeqRange(first, last))
            stats.requests++
            budget -= n
            i += n
        }
        return out
    }
}

/**
 * In-order delivery of the radio's RX audio packets with loss concealment.
 *
 * Packets are keyed by the stream's tracking seq. Contiguous packets are
 * released immediately; a hole is waited on for up to [maxPackets] newer
 * packets (~10 ms each at the radio's 100 pkt/s), giving a requested
 * retransmit time to arrive. When the wait is over the missing packets are
 * CONCEALED with silence of the right length rather than skipped: the radio
 * strictly alternates 1364- and 556-byte payloads (one 20 ms frame = 1920
 * bytes), so the size of a missing packet is known, and keeping the sample
 * timeline intact means the modem sees a brief fade instead of a 6-14 ms
 * timing jump that would knock it out of sync.
 *
 * Single-threaded: only touched from one consume coroutine.
 */
internal class IcomAudioJitter(
    private val maxPackets: Int,
    private val onPacket: (ByteArray) -> Unit
) {
    companion object {
        const val PART1_PAYLOAD = 1364
        const val PART2_PAYLOAD = 556
        const val HEADER = 24
    }

    private val buf = HashMap<Int, ByteArray>()
    private var expected = -1
    private var lastPayload = 0

    var delivered = 0L; private set
    var concealed = 0L; private set
    var lateDropped = 0L; private set

    fun add(seq: Int, pkt: ByteArray) {
        val s = seq and 0xFFFF
        if (expected < 0) expected = s
        if (seqLess(s, expected)) {
            lateDropped++          // already played (or concealed) — too late
            return
        }
        buf[s] = pkt
        drain()
        if (buf.size > maxPackets) {
            // Still stuck on a hole after maxPackets newer packets queued up:
            // give up on it, conceal, and move on to the oldest buffered packet.
            var oldest = expected
            var first = true
            for (k in buf.keys) {
                if (first || seqLess(k, oldest)) { oldest = k; first = false }
            }
            while (expected != oldest) {
                conceal()
                expected = (expected + 1) and 0xFFFF
            }
            drain()
        }
    }

    private fun conceal() {
        val payload = if (lastPayload == PART1_PAYLOAD) PART2_PAYLOAD else PART1_PAYLOAD
        lastPayload = payload
        concealed++
        onPacket(ByteArray(HEADER + payload))   // zeros = silence
    }

    private fun drain() {
        while (true) {
            val p = buf.remove(expected) ?: break
            lastPayload = p.size - HEADER
            delivered++
            onPacket(p)
            expected = (expected + 1) and 0xFFFF
        }
    }

    /** True if a is strictly "before" b in 16-bit modular sequence space. */
    private fun seqLess(a: Int, b: Int): Boolean {
        val d = (b - a) and 0xFFFF
        return d != 0 && d < 0x8000
    }
}
