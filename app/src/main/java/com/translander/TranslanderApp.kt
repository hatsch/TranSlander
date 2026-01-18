package com.translander

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
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
        transcribeManager.startEnabledTriggers()
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
        private const val TAG = "TranslanderApp"
        const val NOTIFICATION_CHANNEL_ID = "voice_transcribe_service"
        const val NOTIFICATION_ID = 1001

        lateinit var instance: TranslanderApp
            private set
    }
}
