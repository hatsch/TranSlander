package com.translander.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.translander.TranslanderApp

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
     * If already loading, waits for completion.
     */
    suspend fun initialize(): Boolean {
        return mutex.withLock {
            // Check loading state inside lock to prevent race
            if (_isLoading.value) {
                Log.i(TAG, "Already loading, waiting for completion")
                // Release lock and wait for initialization
                return waitForInitialization()
            }

            if (recognizer != null) {
                Log.i(TAG, "Recognizer already initialized")
                return true
            }

            if (!modelManager.isModelReady()) {
                Log.i(TAG, "Model not ready")
                return false
            }

            _isLoading.value = true
            try {
                withContext(Dispatchers.IO) {
                    recognizer = ParakeetRecognizer(context, modelManager.getModelPath())
                    val ready = recognizer?.isReady() == true
                    _isReady.value = ready
                    Log.i(TAG, "Recognizer initialized: $ready")
                    ready
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize recognizer", e)
                false
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Wait for an ongoing initialization to complete.
     * Returns true if model is ready after waiting.
     */
    private suspend fun waitForInitialization(timeoutMs: Long = 30000): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (_isLoading.value) {
                kotlinx.coroutines.delay(100)
            }
            recognizer != null
        } ?: false
    }

    /**
     * Ensure recognizer is initialized, waiting if necessary.
     * Use this from external integrations that need the model ready.
     */
    suspend fun ensureInitialized(): Boolean {
        if (recognizer != null) return true
        if (_isLoading.value) return waitForInitialization()
        return initialize()
    }

    /**
     * Transcribe audio data using the shared recognizer.
     * Applies dictionary replacements if enabled.
     */
    suspend fun transcribe(audioData: ShortArray): String? {
        val rec = recognizer
        if (rec == null) {
            Log.w(TAG, "Recognizer not initialized")
            return null
        }
        return withContext(Dispatchers.IO) {
            val rawResult = rec.transcribe(audioData)

            // Apply dictionary replacements if enabled
            if (rawResult != null) {
                val app = TranslanderApp.instance
                val dictionaryEnabled = app.settingsRepository.dictionaryEnabled.first()
                if (dictionaryEnabled) {
                    val corrected = app.dictionaryManager.applyReplacements(rawResult)
                    if (corrected != rawResult) {
                        Log.i(TAG, "Applied corrections: '$rawResult' -> '$corrected'")
                    }
                    corrected
                } else {
                    rawResult
                }
            } else {
                null
            }
        }
    }

    fun isInitialized(): Boolean = recognizer != null

    fun release() {
        recognizer?.release()
        recognizer = null
        _isReady.value = false
    }
}
