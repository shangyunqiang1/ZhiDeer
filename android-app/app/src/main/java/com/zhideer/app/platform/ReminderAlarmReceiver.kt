package com.zhideer.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 提醒到达接收器（T08/#9）：由 AlarmScheduler 设置的闹钟触发，投递本地通知。
 * 调度请求提交成功不代表实际触发成功；投递失败时保留待办，不影响提醒记录。
 *
 * T10/#11：通知投递成功才把调度台账标记为已投递（设备变化恢复时据此区分已投递与错过）；
 * 通知权限关闭导致投递失败时保持待投递，恢复时如实进入错过提示。
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "待办提醒"
        // 通知投递器无状态，可按需构造；测试经 posterFactory 注入替身记录调用
        val posted = runCatching {
            val poster = posterFactory(context.applicationContext)
            poster.ensureChannel()
            poster.post(
                reminderId = reminderId,
                taskId = taskId,
                title = title,
                text = "知时鹿提醒：时间到了，来看看「$title」",
            )
        }.getOrDefault(false)
        if (!posted) return
        val scheduledAtMillis = intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L)
        if (scheduledAtMillis <= 0L) return
        runCatching {
            deliveryRecorderFactory(context.applicationContext)?.recordDelivered(
                reminderKey = reminderId,
                ownerId = taskId,
                ownerTitle = title,
                scheduledAtMillis = scheduledAtMillis,
            )
        }
    }

    companion object {
        const val EXTRA_REMINDER_ID = "com.zhideer.app.extra.REMINDER_ID"
        const val EXTRA_TASK_ID = "com.zhideer.app.extra.TASK_ID"
        const val EXTRA_TITLE = "com.zhideer.app.extra.TITLE"

        /** 计划触发时刻（毫秒）：供调度台账记录已投递；旧 Intent 缺省时跳过台账。 */
        const val EXTRA_SCHEDULED_AT = "com.zhideer.app.extra.SCHEDULED_AT"

        /** 通知投递器构造；生产用 AndroidNotificationPoster，测试可替换为替身。 */
        internal var posterFactory: (Context) -> NotificationPoster = { ctx -> AndroidNotificationPoster(ctx) }

        /** 投递记录器构造；生产从应用容器取，测试可替换为替身或 null（不记录）。 */
        internal var deliveryRecorderFactory: (Context) -> ReminderDeliveryRecorder? = { ctx ->
            (ctx.applicationContext as? com.zhideer.app.ZhiDeerApplication)
                ?.container
                ?.reminderDeliveryRecorder
        }

        fun buildIntent(
            context: Context,
            reminderId: String,
            taskId: String,
            title: String,
            scheduledAtMillis: Long = 0L,
        ): Intent =
            Intent(context, ReminderAlarmReceiver::class.java)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SCHEDULED_AT, scheduledAtMillis)
    }
}
