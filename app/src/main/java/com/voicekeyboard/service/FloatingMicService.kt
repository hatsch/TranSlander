package com.voicekeyboard.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.util.Log
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.voicekeyboard.R
import com.voicekeyboard.VoiceKeyboardApp
import com.voicekeyboard.asr.AudioRecorder
import com.voicekeyboard.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FloatingMicService : Service() {

    companion object {
        private const val TAG = "FloatingMicService"
        const val ACTION_STOP = "com.voicekeyboard.STOP_SERVICE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var micButton: ImageView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var audioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null

    private var isRecording = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()

        // Check overlay permission before setting up floating view
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingView()
        initializeRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If restarted by system (intent is null), don't restart - user must explicitly enable
        if (intent == null) {
            Log.i(TAG, "Service restarted by system, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(VoiceKeyboardApp.NOTIFICATION_ID, createNotification())
        return START_NOT_STICKY  // Don't auto-restart
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        recordingJob?.cancel()
        audioRecorder?.release()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        serviceScope.cancel()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_mic, null)
        micButton = floatingView.findViewById(R.id.floating_mic_button)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        // Restore saved position
        serviceScope.launch {
            val (savedX, savedY) = VoiceKeyboardApp.instance.settingsRepository.buttonPosition.first()
            if (savedX >= 0 && savedY >= 0) {
                layoutParams.x = savedX
                layoutParams.y = savedY
                windowManager.updateViewLayout(floatingView, layoutParams)
            }
        }

        windowManager.addView(floatingView, layoutParams)

        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
                        // This was a tap, not a drag
                        toggleRecording()
                    } else {
                        // Save new position
                        serviceScope.launch {
                            VoiceKeyboardApp.instance.settingsRepository.setButtonPosition(
                                layoutParams.x,
                                layoutParams.y
                            )
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun initializeRecognizer() {
        Log.i(TAG, "initializeRecognizer called")
        serviceScope.launch(Dispatchers.IO) {
            val recognizerManager = VoiceKeyboardApp.instance.recognizerManager
            val modelManager = VoiceKeyboardApp.instance.modelManager
            Log.i(TAG, "Model ready: ${modelManager.isModelReady()}, recognizer ready: ${recognizerManager.isInitialized()}")

            if (modelManager.isModelReady() && !recognizerManager.isInitialized()) {
                val success = recognizerManager.initialize()
                Log.i(TAG, "Recognizer initialization: $success")
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
        val recognizerManager = VoiceKeyboardApp.instance.recognizerManager
        Log.i(TAG, "startRecording called, recognizer ready=${recognizerManager.isInitialized()}")

        if (!recognizerManager.isInitialized()) {
            Log.w(TAG, "Recognizer not initialized, trying to load")
            TextInjectionService.instance?.showToast("Loading model, please wait...")
                ?: android.widget.Toast.makeText(this, "Loading model, please wait...", android.widget.Toast.LENGTH_SHORT).show()
            initializeRecognizer()
            return
        }

        isRecording = true
        updateMicButtonState()

        audioRecorder = AudioRecorder()
        recordingJob = serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Starting audio recording")
            audioRecorder?.startRecording()
        }
    }

    private fun stopRecording() {
        Log.i(TAG, "stopRecording called")
        isRecording = false
        updateMicButtonState()

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

    private suspend fun transcribeAudio(audioData: ShortArray) {
        Log.i(TAG, "transcribeAudio called with ${audioData.size} samples")
        val language = VoiceKeyboardApp.instance.settingsRepository.preferredLanguage.first()
        val langCode = if (language == "auto") null else language

        val result = VoiceKeyboardApp.instance.recognizerManager.transcribe(audioData, langCode)
        Log.i(TAG, "Transcription result: '$result'")

        if (!result.isNullOrBlank()) {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                injectText(result)
            }
        }
    }

    private fun injectText(text: String) {
        Log.i(TAG, "injectText called with: '$text'")
        val injectionService = TextInjectionService.instance
        Log.i(TAG, "TextInjectionService.instance is ${if (injectionService != null) "available" else "null"}")
        if (injectionService != null) {
            injectionService.injectText(text)
        } else {
            Log.w(TAG, "No injection service, falling back to clipboard")
            copyToClipboard(text)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)
        TextInjectionService.instance?.showToast("Copied to clipboard: $text")
            ?: android.widget.Toast.makeText(this, "Copied: $text", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun updateMicButtonState() {
        micButton.setImageResource(
            if (isRecording) R.drawable.ic_mic_recording else R.drawable.ic_mic
        )
        micButton.setBackgroundResource(
            if (isRecording) R.drawable.mic_button_recording_bg else R.drawable.mic_button_bg
        )
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, FloatingMicService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, SettingsActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, VoiceKeyboardApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_mic_small)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
