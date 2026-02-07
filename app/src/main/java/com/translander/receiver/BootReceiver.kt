package com.translander.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.translander.R
import com.translander.TranslanderApp
import com.translander.service.FloatingMicService
import com.translander.transcribe.AudioMonitorService
import com.translander.util.BootCompatHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        // BroadcastReceiver timeout is 10s, use 8s to leave margin for finish()
        private const val BOOT_TIMEOUT_MS = 8000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, checking if services should start")

            val pendingResult = goAsync()

            // Use runBlocking to ensure pendingResult.finish() is called reliably
            // within the BroadcastReceiver timeout
            runBlocking {
                try {
                    withTimeoutOrNull(BOOT_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            handleBootCompleted(context)
                        }
                    } ?: Log.w(TAG, "Boot handling timed out")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check service enabled state", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun handleBootCompleted(context: Context) = coroutineScope {
        val app = TranslanderApp.instance
        // Parallel DataStore reads to minimize boot delay
        val floatingMicDeferred = async {
            withTimeoutOrNull(5000L) { app.settingsRepository.serviceEnabled.first() } ?: false
        }
        val audioMonitorDeferred = async {
            withTimeoutOrNull(5000L) { app.settingsRepository.audioMonitorEnabled.first() } ?: false
        }
        val floatingMicEnabled = floatingMicDeferred.await()
        val audioMonitorEnabled = audioMonitorDeferred.await()

        // Check overlay permission for floating mic
        val hasOverlayPermission = Settings.canDrawOverlays(context)

        val canStartFloatingMic = floatingMicEnabled && hasOverlayPermission
        val canStartAudioMonitor = audioMonitorEnabled

        Log.i(TAG, "floatingMicEnabled=$floatingMicEnabled, audioMonitorEnabled=$audioMonitorEnabled, hasOverlay=$hasOverlayPermission")

        if (!canStartFloatingMic && !canStartAudioMonitor) {
            Log.i(TAG, "No services enabled, nothing to start")
            return@coroutineScope
        }

        if (BootCompatHelper.canStartFgsFromBoot) {
            // Pre-Android 14: can start FGS directly
            if (canStartFloatingMic) {
                Log.i(TAG, "Starting FloatingMicService")
                val serviceIntent = Intent(context, FloatingMicService::class.java)
                context.startForegroundService(serviceIntent)
            }
            if (canStartAudioMonitor) {
                Log.i(TAG, "Starting AudioMonitorService")
                val serviceIntent = Intent(context, AudioMonitorService::class.java)
                context.startForegroundService(serviceIntent)
            }
        } else {
            // Android 14+: show single unified notification
            Log.i(TAG, "Android 14+: showing notification instead of starting services")
            app.serviceAlertNotification.show(R.string.service_start_services)
        }
    }
}
