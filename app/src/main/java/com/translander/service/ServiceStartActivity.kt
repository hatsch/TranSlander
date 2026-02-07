package com.translander.service

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.translander.TranslanderApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Invisible trampoline activity that starts services and finishes immediately.
 * Used by the boot alert notification on Android 14+ where starting FGS
 * requires the app to briefly be in the foreground.
 *
 * The user taps the notification, this activity starts the enabled services,
 * dismisses the notification, and finishes — the user never sees any UI.
 */
class ServiceStartActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ServiceStartActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = TranslanderApp.instance
        app.applicationScope.launch {
            try {
                val serviceEnabled = app.settingsRepository.serviceEnabled.first()
                val audioMonitorEnabled = app.settingsRepository.audioMonitorEnabled.first()

                if (serviceEnabled) {
                    Log.i(TAG, "Starting FloatingMicService")
                    val serviceIntent = Intent(this@ServiceStartActivity, FloatingMicService::class.java)
                    startForegroundService(serviceIntent)
                }

                if (audioMonitorEnabled) {
                    Log.i(TAG, "Starting AudioMonitorService via TranscribeManager")
                    app.transcribeManager.setAudioMonitorEnabled(true)
                }

                app.serviceAlertNotification.dismiss()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start services", e)
            }
        }

        finish()
    }
}
