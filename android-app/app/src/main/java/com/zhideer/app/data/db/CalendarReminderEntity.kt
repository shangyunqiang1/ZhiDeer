package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.zhideer.app.domain.model.Reminder
import com.zhideer.app.domain.model.ReminderOwner

/**
 * 日程提醒表（T09/#10）：属于某个日程。提醒时间与日程开始时间分别设置：
 * - relativeOffsetMillis 非空：相对提醒（相对开始提前的毫秒数），随日程改期移动；
 * - remindAtMillis 非空：独立时刻提醒，不随日程时间变化。
 *
 * 与 reminders（待办提醒）分表而不是同表加 ownerType 混存：reminders 表持有指向
 * tasks.id 的外键级联约束，混存日程行必须删掉该约束（Room 外键无法按行条件生效）；
 * 分表保留两处外键完整性与既有待办提醒查询/用例零改动。提醒归属由 [toModel]
 * 映射为 ReminderOwner.CALENDAR，调度键用 schedulerKey() 加前缀区分。
 * 外键级联只作用于日程行被永久删除的场景；回收站删除由用例显式停止提醒。
 */
@Entity(
    tableName = "calendar_reminders",
    foreignKeys = [
        ForeignKey(
            entity = CalendarEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entryId")],
)
data class CalendarReminderEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val relativeOffsetMillis: Long?,
    val remindAtMillis: Long?,
    val enabled: Boolean,
    val inexactConfirmed: Boolean,
    val notReadyReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

fun CalendarReminderEntity.toModel() = Reminder(
    id = id,
    ownerType = ReminderOwner.CALENDAR,
    taskId = entryId,
    relativeOffsetMillis = relativeOffsetMillis,
    remindAtMillis = remindAtMillis,
    enabled = enabled,
    inexactConfirmed = inexactConfirmed,
    notReadyReason = notReadyReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Reminder.toCalendarEntity() = CalendarReminderEntity(
    id = id,
    entryId = taskId,
    relativeOffsetMillis = relativeOffsetMillis,
    remindAtMillis = remindAtMillis,
    enabled = enabled,
    inexactConfirmed = inexactConfirmed,
    notReadyReason = notReadyReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
