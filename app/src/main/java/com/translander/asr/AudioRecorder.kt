package com.translander.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val audioBuffer = mutableListOf<Short>()

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ).coerceAtLeast(SAMPLE_RATE * 2) // At least 1 second buffer

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return

        audioBuffer.clear()

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        val buffer = ShortArray(bufferSize / 2)

        while (isRecording) {
            val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (readCount > 0) {
                synchronized(audioBuffer) {
                    for (i in 0 until readCount) {
                        audioBuffer.add(buffer[i])
                    }
                }
            }
        }
    }

    fun stopRecording(): ShortArray {
        isRecording = false

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        synchronized(audioBuffer) {
            return audioBuffer.toShortArray()
        }
    }

    fun release() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioBuffer.clear()
    }

    fun isRecording(): Boolean = isRecording
}
