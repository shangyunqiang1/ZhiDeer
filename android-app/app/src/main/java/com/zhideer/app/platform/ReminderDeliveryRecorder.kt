package com.zhideer.app.platform

/**
 * 提醒投递记录器（T10/#11）：提醒接收器成功投递通知后，把对应调度台账标记为已投递，
 * 供设备变化恢复时区分“已投递”与“错过”。投递失败（如通知权限被关）时不得记录已投递，
 * 该调度仍保留为待投递，恢复时如实进入错过提示。
 */
fun interface ReminderDeliveryRecorder {

    fun recordDelivered(
        reminderKey: String,
        ownerId: String,
        ownerTitle: String,
        scheduledAtMillis: Long,
    )
}
