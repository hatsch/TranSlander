package com.voicekeyboard.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import java.io.File

class ParakeetRecognizer(
    private val context: Context,
    private val modelPath: String
) {
    companion object {
        private const val TAG = "ParakeetRecognizer"
    }

    private var recognizer: OfflineRecognizer? = null

    init {
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        try {
            val modelDir = File(modelPath)
            if (!modelDir.exists()) {
                Log.e(TAG, "Model directory does not exist: $modelPath")
                return
            }

            // Find model files
            val encoderPath = File(modelDir, "encoder.onnx").absolutePath
            val decoderPath = File(modelDir, "decoder.onnx").absolutePath
            val joinerPath = File(modelDir, "joiner.onnx").absolutePath
            val tokensPath = File(modelDir, "tokens.txt").absolutePath

            val transducerConfig = OfflineTransducerModelConfig(
                encoder = encoderPath,
                decoder = decoderPath,
                joiner = joinerPath
            )

            val modelConfig = OfflineModelConfig(
                transducer = transducerConfig,
                tokens = tokensPath,
                numThreads = 4,
                debug = false,
                provider = "cpu",
                modelType = "nemo_transducer"
            )

            val featureConfig = FeatureConfig(
                sampleRate = AudioRecorder.SAMPLE_RATE,
                featureDim = 80
            )

            val config = OfflineRecognizerConfig(
                featConfig = featureConfig,
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )

            recognizer = OfflineRecognizer(null, config)
            Log.i(TAG, "Recognizer initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognizer", e)
            recognizer = null
        }
    }

    fun transcribe(audioData: ShortArray, languageCode: String? = null): String? {
        val rec = recognizer ?: return null

        if (audioData.isEmpty()) {
            return null
        }

        return try {
            // Convert ShortArray to FloatArray (normalized to -1.0 to 1.0)
            val floatSamples = FloatArray(audioData.size) { i ->
                audioData[i] / 32768.0f
            }

            val stream = rec.createStream()
            stream.acceptWaveform(floatSamples, AudioRecorder.SAMPLE_RATE)

            rec.decode(stream)

            val result = rec.getResult(stream)
            stream.release()

            result.text.trim().ifEmpty { null }

        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            null
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }

    fun isReady(): Boolean = recognizer != null
}
