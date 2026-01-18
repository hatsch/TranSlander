package com.translander.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.translander.TranslanderApp
import com.translander.asr.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TextInjectionService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")

        // Register accessibility button callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    Log.i(TAG, "Accessibility button clicked")
                    toggleRecording()
                }

                override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                    Log.i(TAG, "Accessibility button availability: $available")
                }
            }
            accessibilityButtonController.registerAccessibilityButtonCallback(accessibilityButtonCallback!!)
        }
    }

    private fun initializeRecognizer() {
        Log.i(TAG, "initializeRecognizer called")
        serviceScope.launch(Dispatchers.IO) {
            val recognizerManager = TranslanderApp.instance.recognizerManager
            val modelManager = TranslanderApp.instance.modelManager
            Log.i(TAG, "Model ready: ${modelManager.isModelReady()}, recognizer ready: ${recognizerManager.isInitialized()}")

            if (!modelManager.isModelReady()) {
                withContext(Dispatchers.Main) {
                    showToast("Model not downloaded. Open app to download.")
                }
                return@launch
            }

            if (!recognizerManager.isInitialized()) {
                val success = recognizerManager.initialize()
                withContext(Dispatchers.Main) {
                    if (success) {
                        showToast("Voice recognition ready")
                    } else {
                        showToast("Failed to load model")
                    }
                }
            }
        }
    }

    private fun toggleRecording() {
        Log.i(TAG, "toggleRecording called, isRecording=$isRecording")
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        // Check mic permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Microphone permission not granted")
            showToast("Microphone permission required. Open app to grant.")
            return
        }

        val recognizerManager = TranslanderApp.instance.recognizerManager
        Log.i(TAG, "startRecording called, recognizer ready=${recognizerManager.isInitialized()}")

        if (!recognizerManager.isInitialized()) {
            Log.w(TAG, "Recognizer not initialized, trying to initialize")
            showToast("Loading model, please wait...")
            initializeRecognizer()
            return
        }

        isRecording = true
        showToast("Recording...")

        audioRecorder = AudioRecorder()
        recordingJob = serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Starting audio recording")
            audioRecorder?.startRecording()
        }
    }

    private fun stopRecording() {
        Log.i(TAG, "stopRecording called")
        isRecording = false
        showToast("Processing...")

        recordingJob?.cancel()

        serviceScope.launch(Dispatchers.IO) {
            val audioData = audioRecorder?.stopRecording()
            audioRecorder?.release()
            audioRecorder = null

            Log.i(TAG, "Audio data size: ${audioData?.size ?: 0}")
            if (audioData != null && audioData.isNotEmpty()) {
                transcribeAudio(audioData)
            }
        }
    }

    companion object {
        private const val TAG = "TextInjectionService"
        var instance: TextInjectionService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    private suspend fun transcribeAudio(audioData: ShortArray) {
        Log.i(TAG, "transcribeAudio called with ${audioData.size} samples")
        val language = TranslanderApp.instance.settingsRepository.preferredLanguage.first()
        val langCode = if (language == "auto") null else language

        val result = TranslanderApp.instance.recognizerManager.transcribe(audioData, langCode)
        Log.i(TAG, "Transcription result: '$result'")

        if (!result.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                injectText(result)
            }
        } else {
            withContext(Dispatchers.Main) {
                showToast("No speech detected")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process events, just inject text
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && accessibilityButtonCallback != null) {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(accessibilityButtonCallback!!)
        }
        recordingJob?.cancel()
        audioRecorder?.release()
        serviceScope.cancel()
        instance = null
    }

    fun injectText(text: String) {
        Log.i(TAG, "injectText called with: $text")
        val focusedNode = findFocusedEditText()
        if (focusedNode != null) {
            Log.i(TAG, "Found focused node, injecting text")
            insertTextIntoNode(focusedNode, text)
        } else {
            Log.w(TAG, "No focused text field found, copying to clipboard")
            showToast("No text field focused. Copied to clipboard.")
            copyToClipboard(text)
        }
    }

    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "rootInActiveWindow is null")
            return null
        }

        // First try to find input-focused editable node
        val inputFocused = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocused != null && inputFocused.isEditable) {
            Log.i(TAG, "Found input-focused editable node")
            return inputFocused
        }

        // Fallback to searching the tree
        return findFocusedNode(rootNode)
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) {
            Log.i(TAG, "Found focused editable node: ${node.className}")
            return node
        }

        // Also check for isEditable without isFocused (some apps don't report focus correctly)
        if (node.isEditable && node.isFocusable) {
            Log.i(TAG, "Found editable focusable node: ${node.className}")
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedNode(child)
            if (result != null) {
                return result
            }
        }
        return null
    }

    private fun insertTextIntoNode(node: AccessibilityNodeInfo, text: String) {
        // Get current text - but check if it's just placeholder/hint text
        val rawText = node.text?.toString() ?: ""
        val hintText = node.hintText?.toString() ?: ""

        // If current text equals hint text, field is empty (showing placeholder)
        val currentText = if (rawText == hintText || rawText.isEmpty()) "" else rawText

        Log.d(TAG, "Current text: '$currentText', hint: '$hintText', raw: '$rawText'")

        // Try to get selection/cursor position
        val selectionStart = if (node.textSelectionStart >= 0 && currentText.isNotEmpty())
            node.textSelectionStart else currentText.length
        val selectionEnd = if (node.textSelectionEnd >= 0 && currentText.isNotEmpty())
            node.textSelectionEnd else selectionStart

        // Build new text with insertion
        val newText = if (currentText.isEmpty()) {
            text  // Just set the text directly if field is empty
        } else {
            StringBuilder(currentText)
                .replace(selectionStart, selectionEnd, text)
                .toString()
        }

        // Try ACTION_SET_TEXT first
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            newText
        )
        val setTextSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "ACTION_SET_TEXT result: $setTextSuccess")

        // If ACTION_SET_TEXT failed or isn't supported, try clipboard paste fallback
        if (!setTextSuccess) {
            Log.d(TAG, "ACTION_SET_TEXT failed, trying clipboard paste fallback")
            tryClipboardPaste(node, text)
            return
        }

        // Move cursor to end of inserted text
        val newCursorPosition = if (currentText.isEmpty()) text.length else selectionStart + text.length
        val selectionArgs = Bundle()
        selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPosition)
        selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPosition)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
    }

    private fun tryClipboardPaste(node: AccessibilityNodeInfo, text: String) {
        // Save current clipboard content
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val oldClip = clipboard.primaryClip

        // Set our text to clipboard
        val clip = android.content.ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)

        // Try to paste
        val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "ACTION_PASTE result: $pasteSuccess")

        // Restore old clipboard after a short delay
        if (oldClip != null) {
            android.os.Handler(mainLooper).postDelayed({
                clipboard.setPrimaryClip(oldClip)
            }, 500)
        }

        if (!pasteSuccess) {
            showToast("Copied to clipboard (paste manually)")
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)
    }

    fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
