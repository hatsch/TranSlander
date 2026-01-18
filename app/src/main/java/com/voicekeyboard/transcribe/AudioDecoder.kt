package com.voicekeyboard.transcribe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.voicekeyboard.asr.AudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes audio files (OPUS, AAC, OGG, M4A, MP3) to 16kHz mono PCM
 * using Android MediaCodec for use with speech recognition.
 */
class AudioDecoder(private val context: Context) {

    companion object {
        private const val TARGET_SAMPLE_RATE = AudioRecorder.SAMPLE_RATE // 16000
        private const val TIMEOUT_US = 10000L
    }

    sealed class DecodingState {
        data class Progress(val percent: Int) : DecodingState()
        data class Success(val audioData: ShortArray) : DecodingState()
        data class Error(val message: String) : DecodingState()
    }

    /**
     * Decodes audio from a content:// or file:// URI to 16kHz mono PCM.
     * Reports progress via the onProgress callback.
     */
    suspend fun decode(
        uri: Uri,
        onProgress: (Int) -> Unit = {}
    ): DecodingState = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            // Open the audio source
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return@withContext DecodingState.Error("Cannot open audio file")

            // Find audio track
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                return@withContext DecodingState.Error("No audio track found")
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            val mimeType = format.getString(MediaFormat.KEY_MIME)
                ?: return@withContext DecodingState.Error("Unknown audio format")

            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)

            // Create and configure decoder
            codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(format, null, null, 0)
            codec.start()

            // Decode audio
            val rawPcm = decodeToRawPcm(
                extractor = extractor,
                codec = codec,
                durationUs = durationUs,
                onProgress = onProgress
            )

            if (!isActive) {
                return@withContext DecodingState.Error("Decoding cancelled")
            }

            // Convert to mono if stereo
            val monoData = if (channelCount > 1) {
                stereoToMono(rawPcm, channelCount)
            } else {
                rawPcm
            }

            // Resample to target sample rate if needed
            val resampledData = if (sourceSampleRate != TARGET_SAMPLE_RATE) {
                resample(monoData, sourceSampleRate, TARGET_SAMPLE_RATE)
            } else {
                monoData
            }

            onProgress(100)
            DecodingState.Success(resampledData)

        } catch (e: Exception) {
            DecodingState.Error(e.message ?: "Unknown decoding error")
        } finally {
            codec?.stop()
            codec?.release()
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    private suspend fun decodeToRawPcm(
        extractor: MediaExtractor,
        codec: MediaCodec,
        durationUs: Long,
        onProgress: (Int) -> Unit
    ): ShortArray = withContext(Dispatchers.IO) {
        val outputSamples = mutableListOf<Short>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone && isActive) {
            // Feed input to decoder
            if (!inputDone) {
                val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0, sampleSize, presentationTimeUs, 0
                        )
                        extractor.advance()
                    }
                }
            }

            // Get decoded output
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputBufferIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }

                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    // Read PCM samples (16-bit)
                    val shortBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val samples = ShortArray(shortBuffer.remaining())
                    shortBuffer.get(samples)
                    outputSamples.addAll(samples.toList())

                    // Report progress
                    if (durationUs > 0) {
                        val progress = ((bufferInfo.presentationTimeUs * 100) / durationUs).toInt()
                            .coerceIn(0, 99)
                        onProgress(progress)
                    }
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)
            }
        }

        outputSamples.toShortArray()
    }

    /**
     * Convert multi-channel audio to mono by averaging channels.
     */
    private fun stereoToMono(input: ShortArray, channels: Int): ShortArray {
        val monoLength = input.size / channels
        val output = ShortArray(monoLength)

        for (i in 0 until monoLength) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += input[i * channels + ch]
            }
            output[i] = (sum / channels).toShort()
        }

        return output
    }

    /**
     * Resample audio using linear interpolation.
     */
    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (input.isEmpty()) return input

        val ratio = fromRate.toDouble() / toRate
        val outputLength = (input.size / ratio).roundToInt()
        val output = ShortArray(outputLength)

        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val fraction = srcPos - srcIndex

            if (srcIndex + 1 < input.size) {
                // Linear interpolation between two samples
                val sample1 = input[srcIndex]
                val sample2 = input[srcIndex + 1]
                output[i] = (sample1 + (sample2 - sample1) * fraction).roundToInt().toShort()
            } else if (srcIndex < input.size) {
                output[i] = input[srcIndex]
            }
        }

        return output
    }
}
