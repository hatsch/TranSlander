package com.translander.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.translander.R

/**
 * Shared recording UI builder - creates identical UI for both VoiceInputActivity
 * and VoiceInputMethodService.
 */
object RecordingUIBuilder {

    data class RecordingUI(
        val view: LinearLayout,
        val statusText: TextView
    )

    private fun isNightMode(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun createRecordingBar(
        context: Context,
        onDoneClick: () -> Unit,
        onCancelClick: () -> Unit
    ): RecordingUI {
        val statusText: TextView
        val night = isNightMode(context)
        val backgroundColor = if (night) Color.parseColor("#2D2D2D") else Color.parseColor("#F2F2F2")
        val textColor = if (night) Color.WHITE else Color.parseColor("#1C1B1F")

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(backgroundColor)
            gravity = Gravity.CENTER_VERTICAL
        }

        statusText = TextView(context).apply {
            text = context.getString(R.string.state_listening)
            textSize = 16f
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        layout.addView(statusText)

        val doneButton = Button(context).apply {
            text = context.getString(R.string.overlay_done)
            setOnClickListener { onDoneClick() }
        }
        layout.addView(doneButton)

        val cancelButton = Button(context).apply {
            text = context.getString(R.string.overlay_cancel)
            setOnClickListener { onCancelClick() }
        }
        layout.addView(cancelButton)

        return RecordingUI(layout, statusText)
    }
}

/**
 * System overlay wrapper - displays RecordingUI as a system overlay that doesn't steal focus.
 * Used by VoiceInputActivity.
 */
class RecordingOverlay(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusText: TextView? = null

    var onDoneClick: (() -> Unit)? = null
    var onCancelClick: (() -> Unit)? = null

    fun show() {
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val ui = RecordingUIBuilder.createRecordingBar(
            context,
            onDoneClick = { onDoneClick?.invoke() },
            onCancelClick = { onCancelClick?.invoke() }
        )
        overlayView = ui.view
        statusText = ui.statusText

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_FOCUSABLE prevents stealing focus from browser
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager?.addView(ui.view, params)
    }

    fun hide() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // View might already be removed
            }
        }
        overlayView = null
        windowManager = null
    }

    fun setStatus(text: String) {
        statusText?.text = text
    }
}
