package com.zhideer.app.platform

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.zhideer.app.domain.model.ClockCapability
import com.zhideer.app.domain.model.ClockLaunchResult

/**
 * 系统时钟适配器实现。
 *
 * 实测（2026-10-25, OPPO PHW110 / ColorOS V15）：`SET_ALARM` 需要清单声明
 * `com.android.alarm.permission.SET_ALARM`，否则系统抛 SecurityException；
 * 具备权限时 ColorOS 的 HandleApiActivity 只打开闹钟列表、不预填编辑器。
 * 因此本实现把"发起成功"如实标为待核对，绝不推断已保存。
 */
class AndroidClockLauncher(private val context: Context) : ClockLauncher {

    override fun capability(): ClockCapability = ClockCapability(
        canCreateAlarm = hasHandler(Intent(AlarmClock.ACTION_SET_ALARM)),
        canOpenAlarmList = hasHandler(Intent(AlarmClock.ACTION_SHOW_ALARMS)),
    )

    private fun hasHandler(intent: Intent): Boolean =
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()

    override fun createAlarm(
        hour: Int,
        minute: Int,
        label: String,
        weekdays: Set<Int>,
    ): ClockLaunchResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (weekdays.isNotEmpty()) {
            // java.time.DayOfWeek 1..7 与平台 Calendar 约定一致（1=周一）
            intent.putExtra(AlarmClock.EXTRA_DAYS, weekdays.sorted().toIntArray())
        }
        return launch(intent)
    }

    override fun openAlarmList(): ClockLaunchResult =
        launch(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun launch(intent: Intent): ClockLaunchResult {
        if (!hasHandler(intent)) return ClockLaunchResult.NoHandler
        return try {
            context.startActivity(intent)
            ClockLaunchResult.Launched
        } catch (se: SecurityException) {
            ClockLaunchResult.Rejected(se.message ?: "系统拒绝本次跳转（可能缺少 SET_ALARM 权限）")
        } catch (e: Exception) {
            ClockLaunchResult.Rejected(e.message ?: "无法发起系统时钟跳转")
        }
    }
}
