package com.translander

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.translander.settings.SettingsActivity
import com.translander.asr.DictionaryManager
import com.translander.asr.ModelManager
import com.translander.asr.RecognizerManager
import com.translander.settings.SettingsRepository
import com.translander.transcribe.TranscribeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
                "Voice Transcribe Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when voice transcription service is active"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            val alertChannel = NotificationChannel(
                SERVICE_ALERT_CHANNEL_ID,
                getString(R.string.service_alert_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.service_alert_channel_description)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    fun showServiceStartNotification(messageResId: Int) {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SERVICE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(messageResId))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(SERVICE_ALERT_NOTIFICATION_ID, notification)
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
