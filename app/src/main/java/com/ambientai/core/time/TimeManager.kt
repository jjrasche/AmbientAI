package com.ambientai.core.time

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimeManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val timeFormat12 = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val timeFormat24 = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun execute(actionName: String, input: JSONObject) = when (actionName) {
        "time.get" -> getCurrentTime(input)
        "timer.set" -> setTimer(input)
        "timer.cancel" -> cancelTimer(input)
        else -> throw Exception("Unknown action: $actionName")
    }
    private fun getCurrentTime(input: JSONObject): JSONObject {
        val now = Calendar.getInstance()
        val use24Hour = input.optBoolean("use24Hour", false)
        val includeDate = input.optBoolean("includeDate", false)
        val timeStr = if (use24Hour) timeFormat24.format(now.time) else timeFormat12.format(now.time)
        val response = if (includeDate) "${dateFormat.format(now.time)}, $timeStr" else timeStr
        return JSONObject().apply {
            put("time", timeStr)
            put("date", dateFormat.format(now.time))
            put("timestamp", now.timeInMillis)
            put("response", response)
        }
    }
    private fun setTimer(input: JSONObject): JSONObject {
        val minutes = input.optInt("minutes", 0)
        val seconds = input.optInt("seconds", 0)
        val totalSeconds = minutes * 60 + seconds
        if (totalSeconds <= 0) throw IllegalArgumentException("Timer duration must be positive")

        // Check if we can schedule exact alarms on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            throw SecurityException("SCHEDULE_EXACT_ALARM permission not granted. Please enable 'Alarms & reminders' permission in app settings.")
        }

        val triggerAtMillis = SystemClock.elapsedRealtime() + (totalSeconds * 1000L)
        val intent = Intent(context, TimerReceiver::class.java).apply { putExtra("duration", totalSeconds) }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
        return JSONObject().apply {
            put("success", true)
            put("durationSeconds", totalSeconds)
            put("message", formatDuration(minutes, seconds))
        }
    }
    private fun cancelTimer(input: JSONObject): JSONObject {
        val intent = Intent(context, TimerReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        return JSONObject().apply { put("success", true); put("message", "Timer cancelled") }
    }
    private fun formatDuration(minutes: Int, seconds: Int) = when {
        minutes > 0 && seconds > 0 -> "$minutes minute${if (minutes > 1) "s" else ""} and $seconds second${if (seconds > 1) "s" else ""}"
        minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""}"
        else -> "$seconds second${if (seconds > 1) "s" else ""}"
    }
}
