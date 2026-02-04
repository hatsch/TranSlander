package com.translander.service

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
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.translander.R
import com.translander.TranslanderApp
import com.translander.asr.AudioRecorder
import com.translander.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.translander.settings.SettingsRepository

class FloatingMicService : Service() {

    companion object {
        private const val TAG = "FloatingMicService"
        const val ACTION_STOP = "at.webformat.translander.STOP_SERVICE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorderMutex = Mutex()

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var micButton: ImageView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var audioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null

    private var isRecording = false
    private var isIntentionalStop = false
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
                isIntentionalStop = true
                stopSelf()
                return START_NOT_STICKY
            }
        }

        try {
            startForeground(TranslanderApp.NOTIFICATION_ID, createNotification())
            // Dismiss any failure notification from previous boot attempt
            getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(TranslanderApp.SERVICE_ALERT_NOTIFICATION_ID)
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            // Android 14+ blocks microphone FGS from BOOT_COMPLETED or background
            Log.w(TAG, "Cannot start foreground service from boot/background", e)
            showFailureNotification()
            stopSelf()
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            // Missing RECORD_AUDIO permission for microphone FGS type
            Log.w(TAG, "Missing permission for foreground service", e)
            showFailureNotification()
            stopSelf()
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            // Race condition: app went to background before startForeground completed
            Log.w(TAG, "Cannot start foreground service, app in background", e)
            showFailureNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY  // Don't auto-restart
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Only sync preference to false if intentionally stopped (not restarting for size change)
        if (isIntentionalStop) {
            TranslanderApp.instance.applicationScope.launch {
                TranslanderApp.instance.settingsRepository.setServiceEnabled(false)
            }
        }
        recordingJob?.cancel()
        // Note: Not using mutex here since we're shutting down and coroutine scope is being cancelled
        audioRecorder?.release()
        audioRecorder = null
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        serviceScope.cancel()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_mic, null)
        micButton = floatingView.findViewById(R.id.floating_mic_button)

        // Apply button size from settings
        serviceScope.launch {
            val size = TranslanderApp.instance.settingsRepository.floatingButtonSize.first()
            val sizeDp = when (size) {
                SettingsRepository.BUTTON_SIZE_SMALL -> 44
                SettingsRepository.BUTTON_SIZE_LARGE -> 72
                else -> 56  // MEDIUM (default)
            }
            val sizePx = (sizeDp * resources.displayMetrics.density).toInt()

            withContext(Dispatchers.Main) {
                val params = micButton.layoutParams
                params.width = sizePx
                params.height = sizePx
                micButton.layoutParams = params
            }
        }

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        // Default position - will be updated async once loaded from settings
        val defaultX = 100
        val defaultY = 300

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultX
            y = defaultY
        }

        windowManager.addView(floatingView, layoutParams)

        // Load saved position asynchronously and update layout
        serviceScope.launch {
            val (savedX, savedY) = TranslanderApp.instance.settingsRepository.buttonPosition.first()
            if (savedX >= 0 && savedY >= 0) {
                withContext(Dispatchers.Main) {
                    layoutParams.x = savedX
                    layoutParams.y = savedY
                    windowManager.updateViewLayout(floatingView, layoutParams)
                }
            }
        }

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
                    if (kotlin.math.abs(deltaX) < 10 && kotlin.math.abs(deltaY) < 10) {
                        // This was a tap, not a drag
                        toggleRecording()
                    } else {
                        // Save new position
                        serviceScope.launch {
                            TranslanderApp.instance.settingsRepository.setButtonPosition(
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
            val recognizerManager = TranslanderApp.instance.recognizerManager
            val modelManager = TranslanderApp.instance.modelManager
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
        // Check mic permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Microphone permission not granted")
            android.widget.Toast.makeText(this, getString(R.string.toast_mic_required), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val recognizerManager = TranslanderApp.instance.recognizerManager
        Log.i(TAG, "startRecording called, recognizer ready=${recognizerManager.isInitialized()}")

        if (!recognizerManager.isInitialized()) {
            Log.w(TAG, "Recognizer not initialized, trying to load")
            TextInjectionService.instance?.showToast(getString(R.string.toast_loading_model))
                ?: android.widget.Toast.makeText(this, getString(R.string.toast_loading_model), android.widget.Toast.LENGTH_SHORT).show()
            initializeRecognizer()
            return
        }

        isRecording = true
        updateMicButtonState()

        recordingJob = serviceScope.launch(Dispatchers.IO) {
            recorderMutex.withLock {
                audioRecorder = AudioRecorder()
            }
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
            val audioData = recorderMutex.withLock {
                val data = audioRecorder?.stopRecording()
                audioRecorder?.release()
                audioRecorder = null
                data
            }

            Log.i(TAG, "Audio data size: ${audioData?.size ?: 0}")
            if (audioData != null && audioData.isNotEmpty()) {
                transcribeAudio(audioData)
            } else {
                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.toast_no_speech))
                }
            }
        }
    }

    private suspend fun transcribeAudio(audioData: ShortArray) {
        Log.i(TAG, "transcribeAudio called with ${audioData.size} samples")
        val result = TranslanderApp.instance.recognizerManager.transcribe(audioData)
        Log.i(TAG, "Transcription result: '$result'")

        withContext(Dispatchers.Main) {
            if (!result.isNullOrBlank()) {
                injectText(result)
            } else {
                showToast(getString(R.string.toast_no_speech))
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
        TextInjectionService.instance?.showToast(getString(R.string.toast_copied, text))
            ?: android.widget.Toast.makeText(this, getString(R.string.toast_copied, text), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun updateMicButtonState() {
        micButton.setImageResource(
            if (isRecording) R.drawable.ic_mic_recording else R.drawable.ic_mic
        )
        micButton.setBackgroundResource(
            if (isRecording) R.drawable.mic_button_recording_bg else R.drawable.mic_button_bg
        )
    }

    private fun showFailureNotification() {
        TranslanderApp.instance.serviceAlertNotification.show(R.string.service_start_floating_mic)
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

        return NotificationCompat.Builder(this, TranslanderApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_mic_small)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_close, getString(R.string.action_stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
