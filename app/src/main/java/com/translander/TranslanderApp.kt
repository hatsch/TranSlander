package com.translander

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.translander.asr.DictionaryManager
import com.translander.asr.ModelManager
import com.translander.asr.RecognizerManager
import com.translander.notification.ServiceAlertNotification
import com.translander.service.FloatingMicService
import com.translander.settings.SettingsActivity
import com.translander.settings.SettingsRepository
import com.translander.transcribe.AudioMonitorService
import com.translander.transcribe.TranscribeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TranslanderApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var modelManager: ModelManager
        private set

    lateinit var recognizerManager: RecognizerManager
        private set

    lateinit var dictionaryManager: DictionaryManager
        private set

    lateinit var transcribeManager: TranscribeManager
        private set

    val serviceAlertNotification: ServiceAlertNotification by lazy {
        ServiceAlertNotification(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        dictionaryManager = DictionaryManager(this)
        modelManager = ModelManager(this)
        recognizerManager = RecognizerManager(this, modelManager)
        transcribeManager = TranscribeManager(this)
        createNotificationChannel()

        // Auto-load model on startup if setting is enabled
        applicationScope.launch(Dispatchers.IO) {
            val autoLoad = settingsRepository.autoLoadModel.first()
            if (autoLoad && modelManager.isModelReady()) {
                Log.i(TAG, "Auto-loading speech model")
                recognizerManager.initialize()
            }
        }

        // Auto-start audio monitor if enabled
        try {
            transcribeManager.startEnabledTriggers()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start enabled triggers", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.service_notification_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when voice transcription service is active"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            val alertImportance = runBlocking {
                alertStyleToImportance(settingsRepository.alertNotificationStyle.first())
            }
            val alertChannel = NotificationChannel(
                SERVICE_ALERT_CHANNEL_ID,
                getString(R.string.service_alert_channel_name),
                alertImportance
            ).apply {
                description = getString(R.string.service_alert_channel_description)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    fun recreateAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.deleteNotificationChannel(SERVICE_ALERT_CHANNEL_ID)

            val importance = runBlocking {
                alertStyleToImportance(settingsRepository.alertNotificationStyle.first())
            }
            val alertChannel = NotificationChannel(
                SERVICE_ALERT_CHANNEL_ID,
                getString(R.string.service_alert_channel_name),
                importance
            ).apply {
                description = getString(R.string.service_alert_channel_description)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    private fun alertStyleToImportance(style: String): Int {
        return when (style) {
            SettingsRepository.ALERT_SILENT -> NotificationManager.IMPORTANCE_MIN
            SettingsRepository.ALERT_HIGH -> NotificationManager.IMPORTANCE_DEFAULT
            else -> NotificationManager.IMPORTANCE_LOW
        }
    }

    fun buildServiceNotification(): Notification {
        val micRunning = FloatingMicService.isRunning
        val monitorRunning = AudioMonitorService.isRunning

        val text = when {
            micRunning && monitorRunning -> getString(R.string.notification_mic_and_monitor)
            micRunning -> getString(R.string.service_notification_text)
            monitorRunning -> getString(R.string.monitor_notification_title)
            else -> getString(R.string.app_name)
        }

        val openIntent = Intent(this, SettingsActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic_small)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .build()
    }

    fun updateServiceNotification() {
        if (!FloatingMicService.isRunning && !AudioMonitorService.isRunning) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildServiceNotification())
    }

    companion object {
        private const val TAG = "TranslanderApp"
        const val NOTIFICATION_CHANNEL_ID = "voice_transcribe_service"
        const val SERVICE_ALERT_CHANNEL_ID = "service_alert"
        const val NOTIFICATION_ID = 1001
        const val SERVICE_ALERT_NOTIFICATION_ID = 1002

        lateinit var instance: TranslanderApp
            private set
    }
}
