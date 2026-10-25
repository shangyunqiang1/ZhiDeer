package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.zhideer.app.domain.model.Reminder

/**
 * 提醒表：属于某个待办（T08/#9）。提醒时间与截止时间分别设置：
 * - relativeOffsetMillis 非空：相对提醒（相对截止提前的毫秒数），随待办截止移动；
 * - remindAtMillis 非空：独立时刻提醒，不随待办时间变化。
 * 外键级联只作用于待办行被永久删除的场景；取消/回收站由用例显式停止提醒。
 */
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val relativeOffsetMillis: Long?,
    val remindAtMillis: Long?,
    val enabled: Boolean,
    val inexactConfirmed: Boolean,
    val notReadyReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

fun ReminderEntity.toModel() = Reminder(
    id = id,
    taskId = taskId,
    relativeOffsetMillis = relativeOffsetMillis,
    remindAtMillis = remindAtMillis,
    enabled = enabled,
    inexactConfirmed = inexactConfirmed,
    notReadyReason = notReadyReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Reminder.toEntity() = ReminderEntity(
    id = id,
    taskId = taskId,
    relativeOffsetMillis = relativeOffsetMillis,
    remindAtMillis = remindAtMillis,
    enabled = enabled,
    inexactConfirmed = inexactConfirmed,
    notReadyReason = notReadyReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
