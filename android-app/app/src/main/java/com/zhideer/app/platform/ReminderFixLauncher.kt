package com.zhideer.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 提醒能力修复入口（T10/#11）：跳系统设置，由用户本人完成授权或关闭省电限制。
 * 应用不尝试绕过系统限制、不伪装成功；跳转失败时静默返回（界面保留手动核对入口）。
 */
interface ReminderFixLauncher {

    /** 通知设置（应用级开关与渠道）。 */
    fun openNotificationSettings()

    /** 精确闹钟（「闹钟和提醒」）授权页。 */
    fun openExactAlarmSettings()

    /** 电池优化设置页。 */
    fun openBatteryOptimizationSettings()

    /** 应用详情页（兜底入口）。 */
    fun openAppSettings()
}

/** 生产实现：使用系统公开设置页；跳转失败不影响应用内状态展示。 */
class AndroidReminderFixLauncher(private val context: Context) : ReminderFixLauncher {

    override fun openNotificationSettings() {
        launch(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    override fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) {
            openAppSettings()
            return
        }
        launch(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + context.packageName),
            ),
        )
    }

    override fun openBatteryOptimizationSettings() {
        launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    override fun openAppSettings() {
        launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName),
            ),
        )
    }

    private fun launch(intent: Intent) {
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
