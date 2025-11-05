package com.ambientai.core.context

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Provides calendar-based context.
 * Returns meeting status, next event info, and time of day.
 */
class CalendarContextProvider(
    private val context: Context
) : ContextProvider<CalendarContextProvider.CalendarContext>(key = "calendar", cacheDurationMs = 60 * 5000) {
    override suspend fun fetch(): CalendarContext = withContext(Dispatchers.IO) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return@withContext CalendarContext.EMPTY
        }
        val now = System.currentTimeMillis()
        val endWindow = now + (2 * 60 * 60 * 1000) // Next 2 hours
        val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND)
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DTEND} > ?",
            arrayOf(endWindow.toString(), now.toString()),
            CalendarContract.Events.DTSTART
        )?.use { cursor ->
            var inMeeting = false
            var nextEvent: Event? = null
            while (cursor.moveToNext()) {
                val title = cursor.getString(0)
                val start = cursor.getLong(1)
                val end = cursor.getLong(2)

                if (now in start..end && !inMeeting) {
                    inMeeting = true
                }

                // Find next upcoming event
                if (nextEvent == null && start > now) {
                    val minutesUntil = (start - now) / (60 * 1000)
                    nextEvent = Event(title, start, minutesUntil)
                }
            }

            CalendarContext(
                inMeeting = inMeeting,
                nextEvent = nextEvent,
                timeOfDay = now
            )
        } ?: CalendarContext.EMPTY
    }
    data class CalendarContext(
        val inMeeting: Boolean,
        val nextEvent: Event?,
        val timestamp: Long
    ) {
        companion object {
            val EMPTY = CalendarContext(false, null, 0)
        }
    }

    data class Event(
        val title: String,
        val startTime: Long,
        val minutesUntil: Long
    )
    enum class TimeOfDay {
        MORNING,
        AFTERNOON,
        EVENING,
        NIGHT
    }
}