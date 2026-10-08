package com.lordv2.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun formatSpeed(bps: Long): String = formatBytes(bps) + "/s"

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

fun timeAgo(t: Long): String {
    if (t <= 0) return "Never"
    val d = (System.currentTimeMillis() - t) / 1000
    return when {
        d < 60 -> "Just now"
        d < 3600 -> "${d / 60}m ago"
        d < 86400 -> "${d / 3600}h ago"
        else -> "${d / 86400}d ago"
    }
}

fun clock(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(t))
fun dateTime(t: Long): String = if (t <= 0) "Never" else SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(t))
