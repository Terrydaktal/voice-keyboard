package io.github.terrydaktal.whisperkeyboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal object WavRecording {
    private const val SAMPLE_RATE = 16_000
    private const val BYTES_PER_SAMPLE = 2
    private const val HEADER_BYTES = 44
    private const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE
    internal const val DEFAULT_TAIL_DRAIN_MS = 280L

    // The service checks RECORD_AUDIO immediately before scheduling the capture.
    @SuppressLint("MissingPermission")
    fun record(
        file: File,
        shouldStop: () -> Boolean,
        onLevel: (Float) -> Unit,
        onPcmChunk: ((ByteArray, Int) -> Unit)? = null,
        tailDrainMs: Long = DEFAULT_TAIL_DRAIN_MS,
    ): Long {
        if (shouldStop()) return 0

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw IOException("Microphone does not support 16 kHz mono PCM.")

        val buffer = ByteArray(BYTES_PER_SECOND / 25)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, buffer.size * 2))
            .build()
        try {
            return RandomAccessFile(file, "rw").use { audio ->
                audio.setLength(HEADER_BYTES.toLong())
                audio.seek(HEADER_BYTES.toLong())

                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IOException("Microphone could not be started.")
                }

                var dataBytes = 0L
                while (!shouldStop()) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count < 0) throw IOException("Microphone read failed ($count).")
                    if (count > 0) {
                        audio.write(buffer, 0, count)
                        onPcmChunk?.invoke(buffer, count)
                        dataBytes += count
                    }
                    if (count >= BYTES_PER_SAMPLE) onLevel(level(buffer, count))
                }

                // Drain a short post-Stop tail so audio still buffered in Android's DSP /
                // AudioFlinger / Bluetooth pipeline is not clipped when Stop is tapped right
                // at the end of a sentence.
                if (dataBytes > 0L && tailDrainMs > 0L) {
                    val drainDeadlineNanos = System.nanoTime() + (tailDrainMs * 1_000_000L)
                    while (System.nanoTime() < drainDeadlineNanos) {
                        val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count <= 0) break
                        audio.write(buffer, 0, count)
                        onPcmChunk?.invoke(buffer, count)
                        dataBytes += count
                    }
                }

                audio.seek(0)
                audio.write(header(dataBytes))
                dataBytes * 1_000 / BYTES_PER_SECOND
            }
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally {
                recorder.release()
            }
        }
    }

    private fun level(buffer: ByteArray, count: Int): Float {
        var squares = 0.0
        for (index in 0 until count - 1 step BYTES_PER_SAMPLE) {
            val sample = (buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)
            squares += sample.toDouble() * sample
        }
        val rms = sqrt(squares / (count / BYTES_PER_SAMPLE)) / 32768
        return sqrt(rms).toFloat().coerceIn(0f, 1f)
    }

    private fun header(dataBytes: Long): ByteArray {
        if (dataBytes > 0xFFFF_FFFFL - 36) throw IOException("Recording is too large for WAV.")
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt((dataBytes + 36).toInt())
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(SAMPLE_RATE)
            .putInt(BYTES_PER_SECOND)
            .putShort(BYTES_PER_SAMPLE.toShort())
            .putShort((8 * BYTES_PER_SAMPLE).toShort())
            .put("data".toByteArray(Charsets.US_ASCII))
            .putInt(dataBytes.toInt())
            .array()
    }
}
