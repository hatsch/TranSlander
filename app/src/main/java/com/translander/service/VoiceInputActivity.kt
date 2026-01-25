package com.translander.service

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.translander.TranslanderApp
import com.translander.asr.AudioRecorder
import com.translander.ui.RecordingOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity that handles voice input requests from keyboards and other apps.
 * Uses a system overlay for UI to avoid stealing focus from the calling app.
 */
class VoiceInputActivity : Activity() {

    companion object {
        private const val TAG = "VoiceInputActivity"
    }

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var recordingOverlay: RecordingOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "VoiceInputActivity created")

        // Try to prevent focus stealing from browser
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission")
            Toast.makeText(this, "Overlay permission required. Enable in Translander settings.", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

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

        // Show overlay and start recording
        showOverlayAndRecord()
    }

    private fun showOverlayAndRecord() {
        recordingOverlay = RecordingOverlay(this).apply {
            onDoneClick = {
                if (isRecording) {
                    stopRecordingAndTranscribe()
                }
            }
            onCancelClick = {
                cleanup()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        recordingOverlay?.show()

        startRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        activityScope.cancel()
    }

    @Deprecated("Deprecated in Java")
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

        recordingOverlay?.setStatus("Listening...")

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

        // Auto-stop after timeout
        activityScope.launch {
            kotlinx.coroutines.delay(10000)
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

        recordingOverlay?.setStatus("Processing...")

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
                        Log.i(TAG, "Transcription result: '$result'")

                        val resultIntent = Intent().apply {
                            putStringArrayListExtra(
                                RecognizerIntent.EXTRA_RESULTS,
                                arrayListOf(result)
                            )
                            putStringArrayListExtra(
                                "android.speech.extra.RESULTS",
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
        recordingOverlay?.hide()
        recordingOverlay = null
        recordingJob?.cancel()
        audioRecorder?.release()
        audioRecorder = null
    }
}
