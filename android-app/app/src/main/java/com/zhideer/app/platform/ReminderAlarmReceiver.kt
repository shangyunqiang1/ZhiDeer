package com.zhideer.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 提醒到达接收器（T08/#9）：由 AlarmScheduler 设置的闹钟触发，投递本地通知。
 * 调度请求提交成功不代表实际触发成功；投递失败时保留待办，不影响提醒记录。
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "待办提醒"
        // 通知投递器无状态，可按需构造；测试经 posterFactory 注入替身记录调用
        val poster = posterFactory(context.applicationContext)
        poster.ensureChannel()
        poster.post(
            reminderId = reminderId,
            taskId = taskId,
            title = title,
            text = "知时鹿提醒：时间到了，来看看「$title」",
        )
    }

    companion object {
        const val EXTRA_REMINDER_ID = "com.zhideer.app.extra.REMINDER_ID"
        const val EXTRA_TASK_ID = "com.zhideer.app.extra.TASK_ID"
        const val EXTRA_TITLE = "com.zhideer.app.extra.TITLE"

        /** 通知投递器构造；生产用 AndroidNotificationPoster，测试可替换为替身。 */
        internal var posterFactory: (Context) -> NotificationPoster = { ctx -> AndroidNotificationPoster(ctx) }

        fun buildIntent(context: Context, reminderId: String, taskId: String, title: String): Intent =
            Intent(context, ReminderAlarmReceiver::class.java)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_TITLE, title)
    }
}
