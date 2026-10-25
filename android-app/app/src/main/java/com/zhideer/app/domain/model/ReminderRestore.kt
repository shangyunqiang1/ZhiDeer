package com.zhideer.app.domain.model

/**
 * 设备变化后的提醒恢复（T10/#11）。
 *
 * 触发原因只描述“为什么现在核对”，恢复规则完全一致：读取真实本地提醒记录，
 * 重建未到点的调度；已过点且未投递的调度合并为错过提示（不逐条补响、不自动延期/完成）；
 * 权限、通知与省电限制如实报告。设备重启、系统时间调整、时区变化与权限变化都必须走本入口。
 */
enum class ReminderRestoreTrigger(val label: String) {
    APP_START("应用启动"),
    MANUAL("手动核对"),
    DEVICE_BOOT("设备重启"),
    TIME_CHANGED("系统时间调整"),
    TIMEZONE_CHANGED("系统时区变化"),
    EXACT_ALARM_PERMISSION_CHANGED("精确提醒权限变化"),
}

/**
 * 系统调度台账状态：用于如实区分「已调度」与「已投递」，并让恢复幂等。
 * - [PENDING]：已向系统提交调度，尚未投递；
 * - [DELIVERED]：提醒接收器已成功投递通知；
 * - [MISSED]：设备关闭/休眠/省电限制期间到点但未投递，合并为提示；错过是终态，
 *   即便随后时间倒拨也不重新调度（不补响）。
 */
enum class ReminderScheduleState { PENDING, DELIVERED, MISSED }

/**
 * 一条提醒的系统调度台账记录。
 * [reminderKey] 为调度键（待办提醒 = 提醒 id；日程提醒 = "cal:" 前缀 + 提醒 id），
 * 与 AlarmScheduler 的键一致；[ownerId] 为待办或日程 id，[ownerTitle] 为标题快照。
 */
data class ReminderScheduleEntry(
    val reminderKey: String,
    val ownerId: String,
    val ownerTitle: String,
    val scheduledAtMillis: Long,
    val state: ReminderScheduleState,
    val recordedAtMillis: Long,
    val deliveredAtMillis: Long? = null,
    val detectedAtMillis: Long? = null,
    val acknowledgedAtMillis: Long? = null,
)

/** 设备当前可观察能力：不据此推断“实际投递”，只描述系统状态。 */
data class ReminderEnvironmentStatus(
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val batteryOptimizationsIgnored: Boolean,
) {
    /** 未来提醒是否还能被可靠调度（还取决于系统实际投递）。 */
    val schedulingAvailable: Boolean get() = exactAlarmsAllowed
}

/** 时区核对项：安排保留创建时区，设备时区已变化；确认前绝不静默修改。 */
data class ReminderZoneAnchor(
    val ownerType: ReminderOwner,
    val ownerId: String,
    val ownerTitle: String,
    val anchoredZoneId: String,
    val deviceZoneId: String,
    val nextReminderAtMillis: Long?,
)

/** 单个提醒体系一次恢复的统计（待办与日程分别统计后合并进报告）。 */
data class ReminderRestoreOutcome(
    val scannedCount: Int = 0,
    val rescheduledCount: Int = 0,
    val notReadyCount: Int = 0,
    val skippedCount: Int = 0,
)

/**
 * 一次设备变化恢复的完整报告：供界面“提醒状态”页与手动核对展示。
 * [missed] 为当前仍未确认知悉的错过提醒（合并展示），[newlyMissedCount] 为本次新识别数量。
 */
data class ReminderRestoreReport(
    val trigger: ReminderRestoreTrigger,
    val restoredAtMillis: Long,
    val scannedCount: Int,
    val futureScheduledCount: Int,
    val notReadyCount: Int,
    val skippedCount: Int,
    val newlyMissedCount: Int,
    val missed: List<ReminderScheduleEntry>,
    val environment: ReminderEnvironmentStatus,
    val zoneAnchors: List<ReminderZoneAnchor>,
    val nextReminder: ReminderScheduleEntry?,
    val messages: List<String>,
) {
    val missedCount: Int get() = missed.size

    /** 错过提醒的合并提示文案；无错过时为 null（不显示空提示）。 */
    fun missedPromptText(): String? {
        if (missed.isEmpty()) return null
        val titles = missed.map { it.ownerTitle }.distinct().joinToString("、")
        return "有 ${missed.size} 条提醒在设备关闭、休眠或省电限制期间错过（$titles）。" +
            "已合并在此处提示，不会逐条补响，也不会自动延期或完成事项。"
    }
}
