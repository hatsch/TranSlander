package com.translander.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.translander.TranslanderApp
import com.translander.service.FloatingMicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, checking if service should start")

            // Check if service was enabled and we have overlay permission
            val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true

            if (!hasOverlayPermission) {
                Log.i(TAG, "No overlay permission, not starting service")
                return
            }

            // Use goAsync() to extend receiver lifetime beyond 10s limit
            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val wasEnabled = TranslanderApp.instance.settingsRepository.serviceEnabled.first()

                    if (wasEnabled) {
                        Log.i(TAG, "Service was enabled, starting FloatingMicService")
                        val serviceIntent = Intent(context, FloatingMicService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } else {
                        Log.i(TAG, "Service was not enabled, not starting")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check service enabled state", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
