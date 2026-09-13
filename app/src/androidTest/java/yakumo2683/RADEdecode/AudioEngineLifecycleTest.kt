package yakumo2683.RADEdecode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Headless network RX: no mic, speaker, USB radio or RF transmission needed. */
@RunWith(AndroidJUnit4::class)
class AudioEngineLifecycleTest {
    @Test fun concurrentFeedAndReleaseCannotReachTheNextEngine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pcm = ShortArray(960) { if (it % 48 < 24) 2000 else -2000 }
        repeat(20) {
            val old = AudioBridge(context)
            old.setRxJavaOutputEnabled(true)
            assertTrue(old.startNetRx())
            val running = AtomicBoolean(true)
            val failure = AtomicReference<Throwable?>()
            val feeder = thread(name = "rx-lifecycle-test") {
                try {
                    while (running.get()) {
                        old.feedNetRx(pcm, pcm.size)
                        Thread.sleep(1)
                    }
                } catch (t: Throwable) { failure.set(t) }
            }
            try {
                Thread.sleep(10)
                old.release() // May overlap the native PCM feed.
                val next = AudioBridge(context)
                try {
                    next.setRxJavaOutputEnabled(true)
                    assertTrue(next.startNetRx())
                    repeat(8) { old.feedNetRx(pcm, pcm.size) }
                    old.stop()
                    assertTrue("Stale bridge stopped the new receiver", next.isRunning)
                    val spectrum = FloatArray(AudioBridge.SPECTRUM_BINS)
                    assertEquals("Stale PCM entered the next engine", 0L, next.getSpectrum(spectrum))
                    repeat(8) { next.feedNetRx(pcm, pcm.size) }
                    val frame = next.getSpectrum(spectrum)
                    assertTrue("No FFT frame from current receiver", frame > 0)
                    assertEquals("Polling must not invent a new FFT frame", frame, next.getSpectrum(spectrum))
                } finally { next.release() }
            } finally {
                running.set(false)
                feeder.join(3000)
                old.release()
            }
            assertFalse("PCM feeder stuck during teardown", feeder.isAlive)
            failure.get()?.let { throw AssertionError("PCM feeder failed", it) }
        }
    }
}
