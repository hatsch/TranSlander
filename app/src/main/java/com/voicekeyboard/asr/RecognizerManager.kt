package com.voicekeyboard.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages a shared ParakeetRecognizer instance across services.
 * Ensures the model is loaded only once and shared between TextInjectionService and FloatingMicService.
 */
class RecognizerManager(private val context: Context, private val modelManager: ModelManager) {

    companion object {
        private const val TAG = "RecognizerManager"
    }

    private var recognizer: ParakeetRecognizer? = null
    private val mutex = Mutex()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /**
     * Initialize the recognizer if model is ready.
     * Safe to call multiple times - will only initialize once.
     */
    suspend fun initialize(): Boolean = mutex.withLock {
        if (recognizer != null) {
            Log.i(TAG, "Recognizer already initialized")
            return true
        }

        if (_isLoading.value) {
            Log.i(TAG, "Already loading")
            return false
        }

        if (!modelManager.isModelReady()) {
            Log.i(TAG, "Model not ready")
            return false
        }

        _isLoading.value = true
        return try {
            withContext(Dispatchers.IO) {
                recognizer = ParakeetRecognizer(context, modelManager.getModelPath())
                val ready = recognizer?.isReady() == true
                _isReady.value = ready
                Log.i(TAG, "Recognizer initialized: $ready")
                ready
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognizer", e)
            false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Transcribe audio data using the shared recognizer.
     */
    suspend fun transcribe(audioData: ShortArray, languageCode: String?): String? {
        val rec = recognizer
        if (rec == null) {
            Log.w(TAG, "Recognizer not initialized")
            return null
        }
        return withContext(Dispatchers.IO) {
            rec.transcribe(audioData, languageCode)
        }
    }

    fun isInitialized(): Boolean = recognizer != null

    fun release() {
        recognizer?.release()
        recognizer = null
        _isReady.value = false
    }
}
