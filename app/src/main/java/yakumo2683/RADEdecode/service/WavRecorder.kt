package yakumo2683.RADEdecode.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records FARGAN 16kHz Int16 PCM output to a WAV file.
 *
 * WAV format: 16kHz, mono, 16-bit PCM.
 * Files stored in app-specific storage under recordings/.
 */
class WavRecorder(private val context: Context) {

    companion object {
        private const val TAG = "WavRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }

    private var outputStream: FileOutputStream? = null
    private var file: File? = null
    private var totalSamplesWritten: Long = 0

    fun start(sessionId: Long): File? {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()

        val filename = "session_${sessionId}_${System.currentTimeMillis()}.wav"
        file = File(dir, filename)

        return try {
            outputStream = FileOutputStream(file)
            totalSamplesWritten = 0

            // Write placeholder WAV header (will be updated on stop)
            writeWavHeader(outputStream!!, 0)

            Log.i(TAG, "Started recording to $filename")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            null
        }
    }

    fun writeSamples(pcm: ShortArray, count: Int = pcm.size) {
        val os = outputStream ?: return
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            buf.putShort(pcm[i])
        }
        os.write(buf.array())
        totalSamplesWritten += count
    }

    fun stop(): File? {
        val os = outputStream ?: return null
        os.flush()
        os.close()
        outputStream = null

        // Update WAV header with actual data size
        val f = file ?: return null
        try {
            val raf = RandomAccessFile(f, "rw")
            val dataSize = totalSamplesWritten * CHANNELS * (BITS_PER_SAMPLE / 8)
            val fileSize = dataSize + 36

            // Update RIFF chunk size (offset 4)
            raf.seek(4)
            raf.write(intToLittleEndian(fileSize.toInt()))

            // Update data chunk size (offset 40)
            raf.seek(40)
            raf.write(intToLittleEndian(dataSize.toInt()))

            raf.close()
            Log.i(TAG, "Stopped recording: ${f.name} (${f.length()} bytes, $totalSamplesWritten samples)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WAV header", e)
        }

        return f
    }

    val isRecording: Boolean get() = outputStream != null

    val currentFile: File? get() = file

    private fun writeWavHeader(os: FileOutputStream, dataSize: Int) {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8

        os.write("RIFF".toByteArray())
        os.write(intToLittleEndian(dataSize + 36))  // file size - 8
        os.write("WAVE".toByteArray())

        // fmt subchunk
        os.write("fmt ".toByteArray())
        os.write(intToLittleEndian(16))              // subchunk size
        os.write(shortToLittleEndian(1))             // PCM format
        os.write(shortToLittleEndian(CHANNELS))
        os.write(intToLittleEndian(SAMPLE_RATE))
        os.write(intToLittleEndian(byteRate))
        os.write(shortToLittleEndian(blockAlign))
        os.write(shortToLittleEndian(BITS_PER_SAMPLE))

        // data subchunk
        os.write("data".toByteArray())
        os.write(intToLittleEndian(dataSize))
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    }
}
