package com.lordv2.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lordv2.app.MainActivity
import com.lordv2.app.R

object Notifications {
    const val CHANNEL = "lord_vpn"
    const val ID = 88

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "VPN connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Lord V2 connection status"
                setShowBadge(false)
            }
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    fun build(ctx: Context, title: String, text: String, connectedSince: Long = 0L): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, LordVpnService::class.java).setAction(LordVpnService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lord)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(0xFF23D5E8.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_stat_power, "Disconnect", stop)
        if (connectedSince > 0) b.setWhen(connectedSince).setShowWhen(true).setUsesChronometer(true) else b.setShowWhen(false)
        return b.build()
    }

    fun notify(ctx: Context, n: Notification) {
        ctx.getSystemService(NotificationManager::class.java)?.notify(ID, n)
    }
}
