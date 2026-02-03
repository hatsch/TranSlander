package com.translander.transcribe

import android.app.Notification
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
import android.util.Log

class AudioMonitorService : Service() {
    private val TAG = "AudioMonitorService"

    companion object {
        const val ACTION_STOP = "at.webformat.translander.action.STOP_MONITOR"

        const val NOTIFICATION_CHANNEL_ID = "audio_monitor"
        const val SERVICE_NOTIFICATION_ID = 2001
        const val AUDIO_DETECTED_NOTIFICATION_ID = 2002

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
    private var fileObservers: MutableList<FileObserver> = mutableListOf()
    private var debounceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = createServiceNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(SERVICE_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, notification)
            }
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
        stopMonitoring()
        serviceScope.cancel()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Service channel (low importance, silent)
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.monitor_channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

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

    private fun createServiceNotification(): Notification {
        val stopIntent = Intent(this, AudioMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(getString(R.string.monitor_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_mic,
                getString(R.string.monitor_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun startMonitoring() {
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
        observer.startWatching()
        fileObservers.add(observer)
        Log.i(TAG, "FileObserver started for: ${directory.absolutePath}")
    }

    private fun stopMonitoring() {
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

        // Small debounce to avoid rapid duplicate events from FileObserver
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(500)
            Log.i(TAG, "Launching transcription for: $filePath")
            launchTranscription(file)
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
