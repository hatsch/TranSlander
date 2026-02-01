package com.translander.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import com.translander.R
import com.translander.TranslanderApp
import com.translander.asr.AudioRecorder
import com.translander.ui.RecordingUIBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice Input Method Service - registers as an auxiliary/voice IME.
 * Uses the same UI as VoiceInputActivity via RecordingUIBuilder.
 */
class VoiceInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "VoiceInputMethodService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var statusText: TextView? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VoiceInputMethodService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VoiceInputMethodService destroyed")
        cleanup()
        serviceScope.cancel()
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "onCreateInputView")

        // Use shared UI builder - identical to VoiceInputActivity
        val ui = RecordingUIBuilder.createRecordingBar(
            context = this,
            onDoneClick = {
                if (isRecording) {
                    stopRecordingAndTranscribe()
                } else {
                    switchBackToPreviousKeyboard()
                }
            },
            onCancelClick = {
                cleanup()
                switchBackToPreviousKeyboard()
            }
        )

        statusText = ui.statusText
        return ui.view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.i(TAG, "onStartInputView, restarting=$restarting")

        if (!restarting) {
            startRecording()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.i(TAG, "onFinishInputView")

        if (isRecording) {
            stopRecordingAndTranscribe()
        }
    }

    private fun startRecording() {
        val recognizerManager = TranslanderApp.instance.recognizerManager

        if (!recognizerManager.isInitialized()) {
            Log.i(TAG, "Recognizer not initialized, attempting to initialize")
            statusText?.text = getString(R.string.model_loading)

            serviceScope.launch {
                try {
                    val success = recognizerManager.ensureInitialized()
                    if (success) {
                        Log.i(TAG, "Model initialized successfully")
                        startRecording()
                    } else {
                        Log.w(TAG, "Failed to initialize model")
                        statusText?.text = getString(R.string.ime_model_not_available)
                        kotlinx.coroutines.delay(1500)
                        switchBackToPreviousKeyboard()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error initializing model", e)
                    statusText?.text = getString(R.string.ime_error_loading)
                    kotlinx.coroutines.delay(1500)
                    switchBackToPreviousKeyboard()
                }
            }
            return
        }

        Log.i(TAG, "Starting recording")
        isRecording = true
        statusText?.text = getString(R.string.state_listening)

        audioRecorder = AudioRecorder()
        recordingJob = serviceScope.launch(Dispatchers.IO) {
            try {
                audioRecorder?.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                withContext(Dispatchers.Main) {
                    statusText?.text = getString(R.string.toast_recording_error)
                    switchBackToPreviousKeyboard()
                }
            }
        }
    }

    private fun stopRecordingAndTranscribe() {
        if (!isRecording) return

        Log.i(TAG, "Stopping recording")
        isRecording = false
        recordingJob?.cancel()
        statusText?.text = getString(R.string.state_processing)

        serviceScope.launch(Dispatchers.IO) {
            try {
                val audioData = audioRecorder?.stopRecording()
                audioRecorder?.release()
                audioRecorder = null

                if (audioData == null || audioData.isEmpty()) {
                    Log.w(TAG, "No audio data")
                    withContext(Dispatchers.Main) {
                        switchBackToPreviousKeyboard()
                    }
                    return@launch
                }

                Log.i(TAG, "Audio data: ${audioData.size} samples")

                val result = TranslanderApp.instance.recognizerManager.transcribe(audioData, null)

                withContext(Dispatchers.Main) {
                    if (!result.isNullOrBlank()) {
                        Log.i(TAG, "Result: $result")
                        currentInputConnection?.commitText(result, 1)
                    } else {
                        Log.w(TAG, "No speech detected")
                        Toast.makeText(this@VoiceInputMethodService, getString(R.string.toast_no_speech), Toast.LENGTH_SHORT).show()
                    }
                    switchBackToPreviousKeyboard()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                withContext(Dispatchers.Main) {
                    switchBackToPreviousKeyboard()
                }
            }
        }
    }

    private fun switchBackToPreviousKeyboard() {
        try {
            @Suppress("DEPRECATION")
            switchToPreviousInputMethod()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch back", e)
        }
    }

    private fun cleanup() {
        recordingJob?.cancel()
        audioRecorder?.release()
        audioRecorder = null
    }
}
