package com.ambientai.util

/**
 * Format milliseconds as human-readable duration.
 * Examples: "2 hours 15 minutes", "45 minutes", "3 seconds"
 */
fun Long.toHumanDuration(): String {
    val seconds = this / 1000
    val minutes = seconds / 60
    val hours = minutes / 60

    return when {
        hours > 0 -> {
            val remainingMinutes = minutes % 60
            if (remainingMinutes > 0) {
                "$hours hours $remainingMinutes minutes"
            } else {
                "$hours hours"
            }
        }
        minutes > 0 -> "$minutes minutes"
        else -> "$seconds seconds"
    }
}