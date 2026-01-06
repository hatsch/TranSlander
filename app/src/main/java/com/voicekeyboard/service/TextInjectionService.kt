package com.voicekeyboard.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class TextInjectionService : AccessibilityService() {

    companion object {
        private const val TAG = "TextInjectionService"
        var instance: TextInjectionService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process events, just inject text
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun injectText(text: String) {
        Log.i(TAG, "injectText called with: $text")
        val focusedNode = findFocusedEditText()
        if (focusedNode != null) {
            Log.i(TAG, "Found focused node, injecting text")
            insertTextIntoNode(focusedNode, text)
            focusedNode.recycle()
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
        inputFocused?.recycle()

        // Fallback to searching the tree
        return findFocusedNode(rootNode)
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) {
            Log.i(TAG, "Found focused editable node: ${node.className}")
            return AccessibilityNodeInfo.obtain(node)
        }

        // Also check for isEditable without isFocused (some apps don't report focus correctly)
        if (node.isEditable && node.isFocusable) {
            Log.i(TAG, "Found editable focusable node: ${node.className}")
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedNode(child)
            child.recycle()
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

        // Set the new text
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            newText
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        // Move cursor to end of inserted text
        val newCursorPosition = if (currentText.isEmpty()) text.length else selectionStart + text.length
        val selectionArgs = Bundle()
        selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPosition)
        selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPosition)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
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
