package com.zhideer.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 提醒：在指定时间通过知时鹿通知用户关注某件事项的安排（T08/#9）。
 * 提醒时间与事项的截止时间分别设置，截止不自动产生提醒。
 *
 * 两种形态互斥：
 * - [relativeOffsetMillis] 非空：相对提醒，相对截止时刻提前指定毫秒数，
 *   待办截止变化时随之移动（随截止移动，由 ReminderUseCases 重算）；
 * - [remindAtMillis] 非空：独立时刻提醒，单独指定日期和时刻，
 *   不因所关联待办的时间变化而自动移动。
 *
 * [notReadyReason] 非空表示提醒未就绪（如未授予精确闹钟权限），提醒记录保留，
 * 由界面提供修复入口；降级为非精确提醒必须经用户显式确认（[inexactConfirmed]）。
 */
data class Reminder(
    val id: String,
    val taskId: String,
    val relativeOffsetMillis: Long? = null,
    val remindAtMillis: Long? = null,
    val enabled: Boolean = true,
    val inexactConfirmed: Boolean = false,
    val notReadyReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isRelative: Boolean get() = relativeOffsetMillis != null
}

/** 相对提醒换算后的具体触发时刻：相对提醒 = 截止时刻 - 提前量；独立时刻提醒即 [Reminder.remindAtMillis]。 */
fun Reminder.resolvedAt(task: TodoTask?): Long? = when {
    relativeOffsetMillis != null -> task?.dueAtMillis?.let { it - relativeOffsetMillis }
    else -> remindAtMillis
}

/**
 * 提醒的展示快照：[resolvedAtMillis] 为当前换算出的具体时间；
 * [isOverdue] 标记已过期（具体时刻早于当前时间），供界面明确提示。
 */
data class ReminderDisplay(
    val reminder: Reminder,
    val resolvedAtMillis: Long?,
    val isOverdue: Boolean,
)

/** 供界面展示的具体时刻格式：与待办编辑一致的本地时区 yyyy-MM-dd HH:mm。 */
fun ReminderDisplay.formatAt(zoneId: ZoneId): String? = resolvedAtMillis?.let {
    Instant.ofEpochMilli(it).atZone(zoneId).let { z ->
        LocalDate.from(z).toString() + " " + z.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
}
