package com.zhideer.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 提醒归属（T09/#10 纯新增）：同一提醒体系服务两种事项。
 * - [TASK]：待办提醒，相对提醒以截止时刻为基准；
 * - [CALENDAR]：日程提醒，相对提醒以开始时刻为基准。
 * 归属决定调度键前缀与换算基准，用于区分关联待办与日程各自的提醒。
 */
enum class ReminderOwner { TASK, CALENDAR }

/**
 * 提醒：在指定时间通过知时鹿通知用户关注某件事项的安排（T08/#9，T09/#10 扩展归属）。
 * 提醒时间与事项的截止时间分别设置，截止不自动产生提醒。
 *
 * 两种形态互斥：
 * - [relativeOffsetMillis] 非空：相对提醒，相对基准时刻提前指定毫秒数，
 *   基准变化时随之移动（待办随截止、日程随开始，由各自用例重算）；
 * - [remindAtMillis] 非空：独立时刻提醒，单独指定日期和时刻，
 *   不因所关联事项的时间变化而自动移动。
 *
 * [ownerType]（T09/#10 纯新增，默认待办）：归属对象类型，[taskId] 保存归属对象稳定 id
 * （待办提醒 = 待办 id；日程提醒 = 日程 id）。归属由各自持久化表承载，字段仅用于模型层区分。
 *
 * [notReadyReason] 非空表示提醒未就绪（如未授予精确闹钟权限），提醒记录保留，
 * 由界面提供修复入口；降级为非精确提醒必须经用户显式确认（[inexactConfirmed]）。
 */
data class Reminder(
    val id: String,
    val ownerType: ReminderOwner = ReminderOwner.TASK,
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

/** 相对提醒换算后的具体触发时刻：待办相对提醒 = 截止时刻 - 提前量；独立时刻提醒即 [Reminder.remindAtMillis]。 */
fun Reminder.resolvedAt(task: TodoTask?): Long? = when {
    relativeOffsetMillis != null -> task?.dueAtMillis?.let { it - relativeOffsetMillis }
    else -> remindAtMillis
}

/**
 * 日程提醒换算后的具体触发时刻（T09/#10 纯新增）：
 * 相对提醒 = 日程开始时刻 - 提前量（随日程移动）；独立时刻提醒即 [Reminder.remindAtMillis]。
 */
fun Reminder.resolvedAtForEntry(entry: CalendarEntry?): Long? = when {
    relativeOffsetMillis != null -> entry?.startMillis?.let { it - relativeOffsetMillis }
    else -> remindAtMillis
}

/**
 * 系统调度键（T09/#10 纯新增）：日程提醒加归属前缀，与待办提醒的键空间区分，
 * 避免同 id 时 PendingIntent/通知互相覆盖。待办提醒保持原键（既有行为不变）。
 */
fun Reminder.schedulerKey(): String = when (ownerType) {
    ReminderOwner.TASK -> id
    ReminderOwner.CALENDAR -> "cal:$id"
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
