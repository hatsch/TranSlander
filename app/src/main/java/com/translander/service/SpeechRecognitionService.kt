package com.translander.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.translander.TranslanderApp
import com.translander.asr.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Speech Recognition Service that integrates with system voice input.
 *
 * When registered, this allows keyboards and other apps to use Translander's
 * offline speech recognition through the standard Android SpeechRecognizer API.
 *
 * Users can select Translander as their default voice input in:
 * Settings > System > Language & Input > Voice Input
 */
class SpeechRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "SpeechRecognitionService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null
    private var currentCallback: Callback? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SpeechRecognitionService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "SpeechRecognitionService destroyed")
        cleanup()
        serviceScope.cancel()
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.i(TAG, "onStartListening called")

        if (listener == null) {
            Log.e(TAG, "Callback is null")
            return
        }

        currentCallback = listener

        // Extract language hint from intent if provided
        val languageHint = recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        Log.i(TAG, "Language hint: $languageHint")

        // Check if recognizer is ready, or wait for it to initialize
        val recognizerManager = TranslanderApp.instance.recognizerManager
        if (!recognizerManager.isInitialized()) {
            Log.i(TAG, "Recognizer not initialized, attempting to initialize")

            serviceScope.launch {
                try {
                    val success = recognizerManager.ensureInitialized()
                    if (success) {
                        Log.i(TAG, "Model initialized successfully")
                        startRecording(listener, languageHint)
                    } else {
                        Log.w(TAG, "Failed to initialize model")
                        listener.error(SpeechRecognizer.ERROR_SERVER)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error initializing model", e)
                    listener.error(SpeechRecognizer.ERROR_SERVER)
                }
            }
            return
        }

        // Start recording
        startRecording(listener, languageHint)
    }

    override fun onStopListening(listener: Callback?) {
        Log.i(TAG, "onStopListening called")
        stopRecordingAndTranscribe(listener ?: currentCallback)
    }

    override fun onCancel(listener: Callback?) {
        Log.i(TAG, "onCancel called")
        cleanup()
    }

    private fun startRecording(listener: Callback, languageHint: String?) {
        // Signal that we're ready to receive audio
        listener.readyForSpeech(Bundle())

        audioRecorder = AudioRecorder()
        recordingJob = serviceScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Starting audio recording")

                withContext(Dispatchers.Main) {
                    listener.beginningOfSpeech()
                }

                audioRecorder?.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                withContext(Dispatchers.Main) {
                    listener.error(SpeechRecognizer.ERROR_AUDIO)
                }
            }
        }
    }

    private fun stopRecordingAndTranscribe(listener: Callback?) {
        if (listener == null) {
            Log.w(TAG, "No callback for transcription")
            cleanup()
            return
        }

        recordingJob?.cancel()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val audioData = audioRecorder?.stopRecording()
                audioRecorder?.release()
                audioRecorder = null

                withContext(Dispatchers.Main) {
                    listener.endOfSpeech()
                }

                if (audioData == null || audioData.isEmpty()) {
                    Log.w(TAG, "No audio data captured")
                    withContext(Dispatchers.Main) {
                        listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                    }
                    return@launch
                }

                Log.i(TAG, "Audio data size: ${audioData.size} samples")

                // Transcribe
                val result = TranslanderApp.instance.recognizerManager.transcribe(audioData, null)

                withContext(Dispatchers.Main) {
                    if (!result.isNullOrBlank()) {
                        Log.i(TAG, "Transcription result: $result")

                        // Build results bundle
                        val results = Bundle().apply {
                            putStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION,
                                arrayListOf(result)
                            )
                            putFloatArray(
                                SpeechRecognizer.CONFIDENCE_SCORES,
                                floatArrayOf(1.0f)
                            )
                        }
                        listener.results(results)
                    } else {
                        Log.w(TAG, "No speech detected")
                        listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                withContext(Dispatchers.Main) {
                    listener.error(SpeechRecognizer.ERROR_SERVER)
                }
            }
        }
    }

    private fun cleanup() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecorder?.release()
        audioRecorder = null
        currentCallback = null
    }
}
