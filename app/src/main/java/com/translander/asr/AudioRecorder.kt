package com.translander.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    // Collect ShortArray chunks to avoid boxing overhead of MutableList<Short>
    private val audioChunks = mutableListOf<ShortArray>()
    private var totalSamples = 0

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ).coerceAtLeast(SAMPLE_RATE * 2) // At least 1 second buffer

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (!isRecording.compareAndSet(false, true)) return

        synchronized(audioChunks) {
            audioChunks.clear()
            totalSamples = 0
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()

            val buffer = ShortArray(bufferSize / 2)

            while (isRecording.get()) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readCount > 0) {
                    val chunk = buffer.copyOfRange(0, readCount)
                    synchronized(audioChunks) {
                        audioChunks.add(chunk)
                        totalSamples += readCount
                    }
                } else if (readCount < 0) {
                    Log.e(TAG, "AudioRecord read error: $readCount")
                    break
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            audioRecord?.release()
            audioRecord = null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord illegal state", e)
            audioRecord?.release()
            audioRecord = null
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord permission denied", e)
            audioRecord?.release()
            audioRecord = null
        } finally {
            isRecording.set(false)
        }
    }

    fun stopRecording(): ShortArray {
        isRecording.set(false)

        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord?.release()
        audioRecord = null

        synchronized(audioChunks) {
            val result = ShortArray(totalSamples)
            var offset = 0
            for (chunk in audioChunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            return result
        }
    }

    fun release() {
        isRecording.set(false)
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error stopping AudioRecord in release", e)
        }
        audioRecord?.release()
        audioRecord = null
        synchronized(audioChunks) {
            audioChunks.clear()
            totalSamples = 0
        }
    }

    fun isRecording(): Boolean = isRecording.get()
}
