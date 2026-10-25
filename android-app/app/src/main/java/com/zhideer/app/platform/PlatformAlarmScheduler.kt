package com.zhideer.app.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 基于 AlarmManager 的生产调度器（T08/#9）。
 * - 精确：setExactAndAllowWhileIdle（API 31+ 需系统授权，未授权返回 false，
 *   由用例标记未就绪并提供修复入口，不悄悄降级）；
 * - 非精确：setAndAllowWhileIdle，仅在用户显式确认降级后由用例调用；
 * - 取消：按相同 reminderId 重建 PendingIntent 后 cancel。
 * PendingIntent 携带 taskId/标题快照供到达时投递通知（标题在重设闹钟前更新）。
 */
class PlatformAlarmScheduler(private val context: Context) : AlarmScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    override suspend fun scheduleExact(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean {
        if (!canScheduleExact()) return false
        return runCatching {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent(reminderId, triggerAtMillis, taskId, title),
            )
        }.isSuccess
    }

    override suspend fun scheduleInexact(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean = runCatching {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent(reminderId, triggerAtMillis, taskId, title),
        )
    }.isSuccess

    override suspend fun cancel(reminderId: String) {
        // 与设置时相同的 requestCode 与 Intent 重建 PendingIntent，确保命中同一条闹钟
        alarmManager.cancel(pendingIntent(reminderId, 0L, taskId = "", title = ""))
    }

    private fun pendingIntent(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): PendingIntent {
        val intent = ReminderAlarmReceiver.buildIntent(context, reminderId, taskId, title, triggerAtMillis)
        return PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
