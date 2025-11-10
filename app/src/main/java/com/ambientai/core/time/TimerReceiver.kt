package com.ambientai.core.time

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.ambientai.R

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val duration = intent.getIntExtra("duration", 0)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "timer_channel"
        val channel = NotificationChannel(channelId, "Timer Notifications", NotificationManager.IMPORTANCE_HIGH).apply { description = "Notifications for timer completion" }
        notificationManager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Timer Complete")
            .setContentText("Your ${formatDuration(duration)} timer has finished")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setAutoCancel(true)
            .build()
        notificationManager.notify(1001, notification)
    }
    private fun formatDuration(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return when {
            minutes > 0 && seconds > 0 -> "$minutes minute${if (minutes > 1) "s" else ""} and $seconds second${if (seconds > 1) "s" else ""}"
            minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""}"
            else -> "$seconds second${if (seconds > 1) "s" else ""}"
        }
    }
}
