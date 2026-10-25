package com.zhideer.app.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.zhideer.app.domain.model.ReminderEnvironmentStatus

/**
 * 设备提醒能力探针（T10/#11）：只读取系统可观察状态，不推断真实投递。
 * 权限撤销、通知关闭与省电限制分别报告，界面据此给出对应修复入口；
 * 探针失败时按“不可用”处理，绝不假装可用。
 */
interface ReminderCapabilityProbe {

    fun snapshot(): ReminderEnvironmentStatus
}

/** 生产实现：通知总开关与运行时权限、精确闹钟能力、电池优化忽略状态。 */
class AndroidReminderCapabilityProbe(private val context: Context) : ReminderCapabilityProbe {

    override fun snapshot(): ReminderEnvironmentStatus {
        val notificationsAllowed = runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val runtimeGranted = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            runtimeGranted && manager.areNotificationsEnabled()
        }.getOrDefault(false)

        val exactAlarmsAllowed = runCatching {
            if (Build.VERSION.SDK_INT < 31) {
                true
            } else {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            }
        }.getOrDefault(false)

        val batteryOptimizationsIgnored = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

        return ReminderEnvironmentStatus(
            notificationsAllowed = notificationsAllowed,
            exactAlarmsAllowed = exactAlarmsAllowed,
            batteryOptimizationsIgnored = batteryOptimizationsIgnored,
        )
    }
}
