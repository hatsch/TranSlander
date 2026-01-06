package com.voicekeyboard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.voicekeyboard.asr.ModelManager
import com.voicekeyboard.asr.RecognizerManager
import com.voicekeyboard.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoiceKeyboardApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var modelManager: ModelManager
        private set

    lateinit var recognizerManager: RecognizerManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        modelManager = ModelManager(this)
        recognizerManager = RecognizerManager(this, modelManager)
        createNotificationChannel()

        // Auto-load model on startup if setting is enabled
        applicationScope.launch(Dispatchers.IO) {
            val autoLoad = settingsRepository.autoLoadModel.first()
            if (autoLoad && modelManager.isModelReady()) {
                Log.i(TAG, "Auto-loading speech model")
                recognizerManager.initialize()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Voice Transcribe Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when voice transcription service is active"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "VoiceKeyboardApp"
        const val NOTIFICATION_CHANNEL_ID = "voice_transcribe_service"
        const val NOTIFICATION_ID = 1001

        lateinit var instance: VoiceKeyboardApp
            private set
    }
}
