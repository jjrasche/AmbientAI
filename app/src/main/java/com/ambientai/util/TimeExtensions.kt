package com.ambientai.util

fun Long.toHumanDuration() = (this / 1000).let { seconds -> (seconds / 60).let { minutes -> (minutes / 60).let { hours -> when { hours > 0 -> (minutes % 60).let { remainingMinutes -> if (remainingMinutes > 0) "$hours hours $remainingMinutes minutes" else "$hours hours" }; minutes > 0 -> "$minutes minutes"; else -> "$seconds seconds" } } } }
