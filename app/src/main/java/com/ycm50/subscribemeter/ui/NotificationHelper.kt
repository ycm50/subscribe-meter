package com.ycm50.subscribemeter.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ycm50.subscribemeter.R

/** 额度告警通知:最紧张的一项超过阈值时提醒一次(同一条通知覆盖更新,不会刷屏)。 */
object NotificationHelper {

    private const val CHANNEL_ID = "quota_alerts"
    private const val NOTIFICATION_ID = 1001

    fun notifyTight(context: Context, providerName: String, windowLabel: String, percent: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "额度告警", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "订阅额度接近上限时提醒"
                },
            )
        }
        val text = windowLabel + "已用 " + String.format("%.1f%%", percent) + ",留点余量吧"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle(providerName + " 额度吃紧")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}
