package com.translander.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.translander.R
import com.translander.TranslanderApp
import com.translander.settings.SettingsActivity

/**
 * Handles the service alert notification shown when FGS cannot start at boot.
 * On Android 14+, restricted FGS types (microphone, dataSync) cannot start from
 * BOOT_COMPLETED, so we show a notification prompting the user to open the app.
 */
class ServiceAlertNotification(private val context: Context) {

    /**
     * Show a notification prompting the user to tap and start services.
     * Uses an ongoing notification that the user must tap to dismiss.
     *
     * @param messageResId String resource ID for the notification message
     */
    fun show(messageResId: Int) {
        val intent = Intent(context, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, TranslanderApp.SERVICE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(messageResId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(TranslanderApp.SERVICE_ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Dismiss the service alert notification.
     * Called after services have been successfully started.
     */
    fun dismiss() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(TranslanderApp.SERVICE_ALERT_NOTIFICATION_ID)
    }
}
