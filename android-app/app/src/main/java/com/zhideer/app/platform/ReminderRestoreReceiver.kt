package com.zhideer.app.platform

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhideer.app.ZhiDeerApplication
import com.zhideer.app.domain.model.ReminderRestoreTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 设备变化恢复接收器（T10/#11）：监听重启、系统时间调整、系统时区变化与精确提醒权限变化，
 * 统一交给 ReminderRestoreUseCases 按真实本地记录重建调度并合并错过提示。
 * 恢复是幂等的：重复广播不会创建重复闹钟或重复错过记录。
 */
class ReminderRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val trigger = triggerFor(intent.action) ?: return
        val runner = runnerFactory(context) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { runner(trigger) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /**
         * 广播动作到恢复原因的映射；未知动作不触发恢复。
         * 精确提醒权限变化动作由系统在用户授权后广播（API 31+）。
         */
        fun triggerFor(action: String?): ReminderRestoreTrigger? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> ReminderRestoreTrigger.DEVICE_BOOT
            Intent.ACTION_TIME_CHANGED -> ReminderRestoreTrigger.TIME_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> ReminderRestoreTrigger.TIMEZONE_CHANGED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                ReminderRestoreTrigger.EXACT_ALARM_PERMISSION_CHANGED
            else -> null
        }

        /** 恢复执行器；生产从应用容器取用例，测试可替换记录调用。 */
        internal var runnerFactory: (Context) -> (suspend (ReminderRestoreTrigger) -> Unit)? = { ctx ->
            (ctx.applicationContext as? ZhiDeerApplication)?.container?.let { container ->
                { trigger: ReminderRestoreTrigger -> container.reminderRestoreUseCases.restore(trigger) }
            }
        }
    }
}
