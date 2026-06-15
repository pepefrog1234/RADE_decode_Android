package yakumo2683.RADEdecode

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Diagnostic that plays a 1 kHz test tone to a chosen output device and reports
 * what Android *actually* routed it to.
 *
 * Why this exists: "no Bluetooth sound" is ambiguous. It can mean (a) the Android
 * audio route was denied — e.g. some OEMs refuse A2DP media while a USB audio
 * device is attached — or (b) there is simply no decoded RADE speech yet because
 * RX/sync is not up. The decoder only produces sound intermittently, so it is a
 * poor probe for routing. This tone always produces sound, so if it does not
 * reach the selected device the problem is the route, not the decoder.
 *
 * The track is configured identically to the live RX playback path
 * ([yakumo2683.RADEdecode.service.AudioService] RX AudioTrack pump): 16 kHz mono
 * PCM-16, USAGE_MEDIA / CONTENT_TYPE_SPEECH, with [AudioTrack.setPreferredDevice].
 * It works whether or not RX is running, so the user can compare both states and
 * with USB audio connected vs. disconnected.
 *
 * [run] blocks for ~2 s (real-time paced AudioTrack writes); call it off the main
 * thread.
 */
object RxOutputTester {

    data class Result(val ok: Boolean, val summary: String)

    private const val SAMPLE_RATE = 16000      // matches the RX AudioTrack pump
    private const val TONE_HZ = 1000.0
    private const val DURATION_MS = 2000
    private const val AMPLITUDE = 0.3

    /**
     * Play the tone to the output device with id [deviceId] (from
     * [AudioManager.GET_DEVICES_OUTPUTS]); pass <= 0 to leave routing to the
     * system default. Returns a human-readable diagnostic summary.
     */
    fun run(context: Context, deviceId: Int): Result {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val target: AudioDeviceInfo? = if (deviceId > 0) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
        } else {
            null
        }
        if (deviceId > 0 && target == null) {
            return Result(
                false,
                "Selected output (id=$deviceId) is no longer available. " +
                    "Refresh devices and try again."
            )
        }

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val preferred = if (target != null) track.setPreferredDevice(target) else true

        var routed: AudioDeviceInfo? = null
        try {
            track.play()
            val total = SAMPLE_RATE * DURATION_MS / 1000
            val chunk = ShortArray(1600)
            val step = 2.0 * PI * TONE_HZ / SAMPLE_RATE
            var phase = 0.0
            var written = 0
            // MODE_STREAM writes block to roughly real time once the buffer fills,
            // so this loop also paces the ~2 s tone without an explicit sleep.
            while (written < total) {
                val count = minOf(chunk.size, total - written)
                for (i in 0 until count) {
                    chunk[i] = (sin(phase) * AMPLITUDE * 32767.0).toInt().toShort()
                    phase += step
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(chunk, 0, count)
                written += count
            }
            // Read the established route just before tearing the track down.
            routed = runCatching { track.routedDevice }.getOrNull()
        } catch (e: Exception) {
            Log.w("RxOutputTester", "test tone failed", e)
            runCatching { track.release() }
            return Result(false, "Test failed: ${e.message}")
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }

        val matched = target != null && routed != null && routed.id == target.id
        val ok = target == null || matched
        val summary = buildString {
            append("Requested: ${describe(target) ?: "system default"}\n")
            append("setPreferredDevice: $preferred\n")
            append("Android routed to: ${describe(routed) ?: "unknown"}\n")
            append(
                when {
                    target == null -> "ℹ System default — heard on the active output."
                    matched -> "✓ Route honored — tone played on the selected device."
                    // The tone uses AudioTrack.setPreferredDevice (a soft hint).
                    // Decoded RX audio uses Oboe setDeviceId (a hard request), which
                    // is stronger, so a soft override here does not mean RX will fail.
                    else -> "✗ Soft route overridden — tone played elsewhere. " +
                        "Decoded RX uses a stronger route (Oboe) and may still reach " +
                        "the device; confirm by listening to decoded audio."
                }
            )
        }
        Log.i("RxOutputTester", summary.replace("\n", " | "))
        return Result(ok, summary)
    }

    private fun describe(d: AudioDeviceInfo?): String? {
        if (d == null) return null
        return "${typeName(d.type)} \"${d.productName}\" id=${d.id}"
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        else -> "Type $type"
    }
}
