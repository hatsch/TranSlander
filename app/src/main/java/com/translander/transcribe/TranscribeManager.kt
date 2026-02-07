package com.translander.transcribe

import android.content.Context
import android.content.Intent
import android.util.Log
import com.translander.TranslanderApp
import com.translander.util.BootCompatHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Interface for transcription access methods.
 * Allows adding new ways to trigger transcription (Quick Settings tile, widget, etc.)
 */
interface TranscriptionTrigger {
    fun start()
    fun stop()
    val isActive: Boolean
}

/**
 * Manages all transcription access methods.
 * Currently supports:
 * - Folder monitoring (AudioMonitorService)
 *
 * Future options (documented in CLAUDE.md):
 * - Quick Settings tile
 * - Home screen widget
 * - File picker button in Settings
 */
class TranscribeManager(private val context: Context) {

    companion object {
        private const val TAG = "TranscribeManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val triggers = mutableListOf<TranscriptionTrigger>()

    // Audio monitor trigger wrapper
    private val audioMonitorTrigger = object : TranscriptionTrigger {
        override fun start() {
            if (AudioMonitorService.isRunning) return
            try {
                val intent = Intent(context, AudioMonitorService::class.java)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AudioMonitorService", e)
            }
        }

        override fun stop() {
            if (!AudioMonitorService.isRunning) return
            val intent = Intent(context, AudioMonitorService::class.java).apply {
                action = AudioMonitorService.ACTION_STOP
            }
            context.startService(intent)
        }

        override val isActive: Boolean get() = AudioMonitorService.isRunning
    }

    init {
        registerTrigger(audioMonitorTrigger)
    }

    fun registerTrigger(trigger: TranscriptionTrigger) {
        if (trigger !in triggers) {
            triggers.add(trigger)
        }
    }

    fun unregisterTrigger(trigger: TranscriptionTrigger) {
        trigger.stop()
        triggers.remove(trigger)
    }

    /**
     * Start all enabled triggers based on settings.
     * On Android 14+, skips auto-start at boot since dataSync FGS is blocked.
     * BootReceiver handles showing the notification, so we just skip here.
     * User must open app to start services (handled in SettingsActivity.onResume).
     */
    fun startEnabledTriggers() {
        scope.launch {
            val settings = TranslanderApp.instance.settingsRepository
            val monitorEnabled = settings.audioMonitorEnabled.first()

            if (monitorEnabled) {
                if (BootCompatHelper.canStartFgsFromBoot) {
                    audioMonitorTrigger.start()
                } else {
                    // Android 14+: BootReceiver shows the notification,
                    // so we just skip starting here. Services will be started
                    // when user opens the app (SettingsActivity.onResume).
                    Log.i(TAG, "Android 14+: skipping audio monitor start at boot")
                }
            }
        }
    }

    /**
     * Stop all triggers.
     */
    fun stopAll() {
        for (trigger in triggers) {
            trigger.stop()
        }
    }

    /**
     * Enable/disable the audio monitor specifically.
     */
    fun setAudioMonitorEnabled(enabled: Boolean) {
        if (enabled) {
            audioMonitorTrigger.start()
        } else {
            audioMonitorTrigger.stop()
        }
    }

    /**
     * Check if audio monitoring is currently active.
     */
    fun isAudioMonitorActive(): Boolean = audioMonitorTrigger.isActive
}
