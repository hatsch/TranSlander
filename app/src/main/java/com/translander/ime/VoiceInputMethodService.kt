package com.translander.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
 * Voice Input Method Service - registers as an auxiliary/voice IME.
 *
 * This allows keyboards like HeliBoard/AOSP to detect Translander as a voice
 * input method via getShortcutInputMethodsAndSubtypes().
 *
 * When user taps mic button on their keyboard, this IME is activated,
 * records speech, transcribes it, and commits the text to the input field.
 */
class VoiceInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "VoiceInputMethodService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var statusView: TextView? = null

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

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF2D2D2D.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }

        statusView = TextView(this).apply {
            text = "Initializing..."
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        layout.addView(statusView)

        val stopButton = Button(this).apply {
            text = "Done"
            setOnClickListener {
                if (isRecording) {
                    stopRecordingAndTranscribe()
                } else {
                    switchBackToPreviousKeyboard()
                }
            }
        }
        layout.addView(stopButton)

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                cleanup()
                switchBackToPreviousKeyboard()
            }
        }
        layout.addView(cancelButton)

        return layout
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
            Log.w(TAG, "Recognizer not initialized")
            statusView?.text = "Model not loaded.\nOpen Translander app first."

            // Try to initialize
            serviceScope.launch(Dispatchers.IO) {
                val modelManager = TranslanderApp.instance.modelManager
                if (modelManager.isModelReady()) {
                    val success = recognizerManager.initialize()
                    if (success) {
                        withContext(Dispatchers.Main) {
                            startRecording()
                        }
                    }
                }
            }
            return
        }

        Log.i(TAG, "Starting recording")
        isRecording = true
        statusView?.text = "Listening..."

        audioRecorder = AudioRecorder()
        recordingJob = serviceScope.launch(Dispatchers.IO) {
            try {
                audioRecorder?.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                withContext(Dispatchers.Main) {
                    statusView?.text = "Recording error"
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
        statusView?.text = "Processing..."

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
                        Toast.makeText(this@VoiceInputMethodService, "No speech detected", Toast.LENGTH_SHORT).show()
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
