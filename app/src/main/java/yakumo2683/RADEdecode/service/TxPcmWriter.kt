package yakumo2683.RADEdecode.service

/** Keep PCM order and count only frames accepted by AudioTrack. A short write
 * must not discard the remainder of a modem/EOO frame or extend the drain wait. */
internal class TxPcmWriter(private val sink: (ShortArray, Int, Int) -> Int) {
    var framesWritten: Long = 0
        private set

    fun write(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        var offset = 0
        while (offset < count) {
            val remaining = count - offset
            val written = sink(samples, offset, remaining)
            check(written in 1..remaining) { "TX AudioTrack write failed: result=$written remaining=$remaining" }
            offset += written
            framesWritten += written
        }
    }
}
