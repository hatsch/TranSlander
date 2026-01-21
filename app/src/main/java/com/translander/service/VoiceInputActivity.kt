package com.translander.service

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
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
 * Activity that handles voice input requests from keyboards and other apps.
 *
 * This is triggered when an app sends android.speech.action.RECOGNIZE_SPEECH intent.
 * Shows a minimal UI while recording, then returns the transcription result.
 */
class VoiceInputActivity : Activity() {

    companion object {
        private const val TAG = "VoiceInputActivity"
        private const val REQUEST_PERMISSION = 1
    }

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "VoiceInputActivity created")

        // Check microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No microphone permission")
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // Check if recognizer is ready
        val recognizerManager = TranslanderApp.instance.recognizerManager
        if (!recognizerManager.isInitialized()) {
            Log.w(TAG, "Recognizer not initialized")
            Toast.makeText(this, "Voice model not loaded. Open Translander app first.", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // Start recording immediately
        startRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        activityScope.cancel()
    }

    override fun onBackPressed() {
        if (isRecording) {
            stopRecordingAndTranscribe()
        } else {
            setResult(RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    private fun startRecording() {
        Log.i(TAG, "Starting recording")
        isRecording = true

        Toast.makeText(this, "Listening... Tap back to stop", Toast.LENGTH_SHORT).show()

        audioRecorder = AudioRecorder()
        recordingJob = activityScope.launch(Dispatchers.IO) {
            try {
                audioRecorder?.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VoiceInputActivity, "Recording error", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        }

        // Auto-stop after silence or timeout (simplified: just use a timeout)
        activityScope.launch {
            kotlinx.coroutines.delay(10000) // 10 second max recording
            if (isRecording) {
                stopRecordingAndTranscribe()
            }
        }
    }

    private fun stopRecordingAndTranscribe() {
        if (!isRecording) return

        Log.i(TAG, "Stopping recording")
        isRecording = false
        recordingJob?.cancel()

        Toast.makeText(this, "Processing...", Toast.LENGTH_SHORT).show()

        activityScope.launch(Dispatchers.IO) {
            try {
                val audioData = audioRecorder?.stopRecording()
                audioRecorder?.release()
                audioRecorder = null

                if (audioData == null || audioData.isEmpty()) {
                    Log.w(TAG, "No audio data")
                    withContext(Dispatchers.Main) {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                    return@launch
                }

                Log.i(TAG, "Audio data: ${audioData.size} samples")

                val result = TranslanderApp.instance.recognizerManager.transcribe(audioData, null)

                withContext(Dispatchers.Main) {
                    if (!result.isNullOrBlank()) {
                        Log.i(TAG, "Result: $result")

                        val resultIntent = Intent().apply {
                            putStringArrayListExtra(
                                RecognizerIntent.EXTRA_RESULTS,
                                arrayListOf(result)
                            )
                        }
                        setResult(RESULT_OK, resultIntent)
                    } else {
                        Log.w(TAG, "No speech detected")
                        setResult(RESULT_CANCELED)
                    }
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                withContext(Dispatchers.Main) {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        }
    }

    private fun cleanup() {
        recordingJob?.cancel()
        audioRecorder?.release()
        audioRecorder = null
    }
}
