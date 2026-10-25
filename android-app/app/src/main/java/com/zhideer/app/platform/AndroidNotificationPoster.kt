package com.zhideer.app.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 基于系统通知的生产投递器（T08/#9）。
 * 提示音与振动由高优先级通知渠道配置承载；POST_NOTIFICATIONS 被拒时返回 false，
 * 不抛异常、不影响待办记录。设备真实投递（通知/提示音/振动）留待统一实机验收。
 */
class AndroidNotificationPoster(private val context: Context) : NotificationPoster {

    override fun ensureChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "知时鹿提醒", NotificationManager.IMPORTANCE_HIGH)
        channel.enableVibration(true)
        manager.createNotificationChannel(channel)
    }

    override fun post(reminderId: String, taskId: String, title: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            val notification = android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(reminderId.hashCode(), notification)
        }.isSuccess
    }

    companion object {
        const val CHANNEL_ID = "zhideer_reminders"
    }
}
