package com.famage.skydrop_tv_app.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.famage.skydrop_tv_app.MainActivity
import com.famage.skydrop_tv_app.R
import com.famage.skydrop_tv_app.model.TvReceivedFile

/**
 * Handles Android TV notification channels, heads-up notifications, and on-screen Toast alerts.
 */
object TvNotificationHelper {

    const val CHANNEL_PERSISTENT_ID = "skydrop_tv_persistent_channel"
    const val CHANNEL_ALERT_ID = "skydrop_tv_alert_channel"

    const val NOTIFICATION_ID_PERSISTENT = 2001
    const val NOTIFICATION_ID_INCOMING = 2002
    private const val NOTIFICATION_ID_ALERT_BASE = 3000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val persistentChannel = NotificationChannel(
                CHANNEL_PERSISTENT_ID,
                "SkyDrop TV Background Receiver",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps TV ready to receive files in the background"
                setShowBadge(false)
            }
            manager.createNotificationChannel(persistentChannel)

            val alertChannel = NotificationChannel(
                CHANNEL_ALERT_ID,
                "SkyDrop TV File Transfers",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when files are received on this TV"
                enableVibration(false)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    fun buildPersistentNotification(context: Context, port: Int): Notification {
        val pendingIntent = createLaunchPendingIntent(context)
        return NotificationCompat.Builder(context, CHANNEL_PERSISTENT_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SkyDrop TV Receiver")
            .setContentText("Listening on local Wi-Fi • Ready to receive in background")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun showIncomingFileNotification(
        context: Context,
        filename: String,
        senderName: String,
        totalBytes: Long,
        percent: Int
    ) {
        val pendingIntent = createLaunchPendingIntent(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming Drop from $senderName")
            .setContentText(filename)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent.coerceAtLeast(0), totalBytes <= 0)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID_INCOMING, notification)
    }

    fun updateTransferNotification(
        context: Context,
        filename: String,
        totalBytes: Long,
        percent: Int
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Receiving: $filename")
            .setContentText(if (percent >= 0) "$percent%" else "Transferring...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent.coerceAtLeast(0), totalBytes <= 0)
            .build()
        manager.notify(NOTIFICATION_ID_INCOMING, notification)
    }

    fun cancelIncomingNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID_INCOMING)
    }

    fun showFileReceivedNotification(context: Context, file: TvReceivedFile) {
        val pendingIntent = createLaunchPendingIntent(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("File Received")
            .setContentText("${file.name} received from ${file.senderName}")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID_ALERT_BASE + (System.currentTimeMillis() % 1000).toInt(), notification)
    }

    fun showTextReceivedNotification(context: Context, sender: String, preview: String) {
        val pendingIntent = createLaunchPendingIntent(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Message Received")
            .setContentText("From $sender: \"$preview\"")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID_ALERT_BASE + (System.currentTimeMillis() % 1000).toInt(), notification)
    }

    fun showTvToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    private fun createLaunchPendingIntent(context: Context): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
