package com.translander.transcribe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.translander.R
import com.translander.TranslanderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import android.util.Log

class AudioMonitorService : Service() {
    private val TAG = "AudioMonitorService"

    companion object {
        const val ACTION_STOP = "at.webformat.translander.action.STOP_MONITOR"

        const val AUDIO_DETECTED_NOTIFICATION_ID = 2002

        @Volatile
        var isRunning = false
            private set

        private const val DEBOUNCE_MS = 2000L

        private val AUDIO_EXTENSIONS = setOf(
            "opus", "ogg", "m4a", "aac", "mp3", "wav", "3gp", "amr"
        )

        fun getDefaultMonitoredPaths(): Set<String> {
            return setOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fileObservers: MutableList<FileObserver> = CopyOnWriteArrayList()
    // Per-file debounce to avoid losing distinct files arriving within the debounce window
    // ConcurrentHashMap because FileObserver events arrive on a background thread
    private val debounceJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    override fun onCreate() {
        super.onCreate()
        // Delete orphaned audio_monitor channel (now using shared service channel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .deleteNotificationChannel("audio_monitor")
        }
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = TranslanderApp.instance.buildServiceNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(TranslanderApp.NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(TranslanderApp.NOTIFICATION_ID, notification)
            }
            isRunning = true
            TranslanderApp.instance.updateServiceNotification()
            // Dismiss any failure notification from previous boot attempt
            getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(TranslanderApp.SERVICE_ALERT_NOTIFICATION_ID)
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            // Android 14+ blocks dataSync FGS from BOOT_COMPLETED or background
            Log.w(TAG, "Cannot start foreground service from boot/background", e)
            showFailureNotification()
            stopSelf()
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            // Missing permission for FGS type
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
        startMonitoring()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopMonitoring()
        serviceScope.cancel()
        TranslanderApp.instance.updateServiceNotification()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Audio detected channel (default importance with sound)
            val audioDetectedChannel = NotificationChannel(
                "audio_detected",
                getString(R.string.audio_detected_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.audio_detected_channel_description)
            }
            notificationManager.createNotificationChannel(audioDetectedChannel)
        }
    }

    private fun showFailureNotification() {
        TranslanderApp.instance.serviceAlertNotification.show(R.string.service_start_folder_monitor)
    }

    private fun startMonitoring() {
        // Stop any existing observers before starting new ones
        stopMonitoring()

        serviceScope.launch {
            val settings = TranslanderApp.instance.settingsRepository
            val monitoredPaths = settings.monitoredFolders.first()
                .ifEmpty { getDefaultMonitoredPaths() }

            Log.i(TAG, "Starting monitoring for paths: $monitoredPaths")

            for (path in monitoredPaths) {
                val directory = File(path)
                Log.i(TAG, "Checking path: $path, exists=${directory.exists()}, isDir=${directory.isDirectory}")
                if (directory.exists() && directory.isDirectory) {
                    createFileObserver(directory)
                }
            }
        }
    }

    private fun createFileObserver(directory: File) {
        Log.i(TAG, "Creating FileObserver for: ${directory.absolutePath}")
        val canonicalDir = directory.canonicalPath
        val observer = object : FileObserver(directory, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                Log.d(TAG, "FileObserver event: $event, path: $path")
                if (path == null) return

                val file = File(directory, path)

                // Path traversal protection: ensure file is within monitored directory
                if (!file.canonicalPath.startsWith(canonicalDir)) {
                    Log.w(TAG, "Path traversal attempt blocked: $path")
                    return
                }

                Log.d(TAG, "File: ${file.absolutePath}, isAudio=${isAudioFile(file)}")
                if (isAudioFile(file)) {
                    onAudioFileDetected(file)
                }
            }
        }
        try {
            observer.startWatching()
            fileObservers.add(observer)
            Log.i(TAG, "FileObserver started for: ${directory.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FileObserver for: ${directory.absolutePath}", e)
        }
    }

    private fun stopMonitoring() {
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
        for (observer in fileObservers) {
            observer.stopWatching()
        }
        fileObservers.clear()
    }

    private fun isAudioFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    private fun onAudioFileDetected(file: File) {
        val filePath = file.absolutePath
        Log.i(TAG, "onAudioFileDetected: $filePath")

        // Per-file debounce: cancels duplicate events for the same file
        // without losing distinct files arriving in the same window
        debounceJobs[filePath]?.cancel()
        debounceJobs[filePath] = serviceScope.launch {
            delay(DEBOUNCE_MS)
            debounceJobs.remove(filePath)
            // Verify file still exists and is readable
            if (file.exists() && file.length() > 0 && file.canRead()) {
                Log.i(TAG, "Launching transcription for: $filePath")
                launchTranscription(file)
            } else {
                Log.w(TAG, "File not ready or inaccessible: $filePath")
            }
        }
    }

    private fun launchTranscription(file: File) {
        val transcribeIntent = Intent(this, TranscribeActivity::class.java).apply {
            action = TranscribeActivity.ACTION_TRANSCRIBE
            putExtra(TranscribeActivity.EXTRA_FILE_PATH, file.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Show notification about detected audio
        showAudioDetectedNotification(file.name, transcribeIntent)

        startActivity(transcribeIntent)
    }

    private fun showAudioDetectedNotification(fileName: String, transcribeIntent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            AUDIO_DETECTED_NOTIFICATION_ID,
            transcribeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "audio_detected")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.audio_detected_title))
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(AUDIO_DETECTED_NOTIFICATION_ID, notification)
    }
}
