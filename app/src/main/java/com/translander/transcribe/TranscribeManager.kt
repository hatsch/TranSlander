package com.translander.transcribe

import android.content.Context
import android.content.Intent
import android.os.Build
import com.translander.TranslanderApp
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val triggers = mutableListOf<TranscriptionTrigger>()

    // Audio monitor trigger wrapper
    private val audioMonitorTrigger = object : TranscriptionTrigger {
        private var active = false

        override fun start() {
            if (active) return
            val intent = Intent(context, AudioMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            active = true
        }

        override fun stop() {
            if (!active) return
            val intent = Intent(context, AudioMonitorService::class.java).apply {
                action = AudioMonitorService.ACTION_STOP
            }
            context.startService(intent)
            active = false
        }

        override val isActive: Boolean get() = active
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
     */
    fun startEnabledTriggers() {
        scope.launch {
            val settings = TranslanderApp.instance.settingsRepository
            val monitorEnabled = settings.audioMonitorEnabled.first()

            if (monitorEnabled) {
                audioMonitorTrigger.start()
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
