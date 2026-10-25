package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * 系统调度台账表（T10/#11）：主键（reminderKey, scheduledAtMillis）保证同一提醒同一时刻
 * 只有一条台账，重复恢复不会创建重复调度记录。提醒配置仍存 reminders / calendar_reminders。
 */
@Entity(tableName = "reminder_schedule_log", primaryKeys = ["reminderKey", "scheduledAtMillis"])
data class ReminderScheduleEntity(
    val reminderKey: String,
    val scheduledAtMillis: Long,
    val ownerId: String,
    val ownerTitle: String,
    /** ReminderScheduleState 名称（PENDING / DELIVERED / MISSED）。 */
    val state: String,
    val recordedAtMillis: Long,
    val deliveredAtMillis: Long? = null,
    val detectedAtMillis: Long? = null,
    val acknowledgedAtMillis: Long? = null,
)
